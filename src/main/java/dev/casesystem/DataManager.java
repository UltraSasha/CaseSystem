package dev.casesystem;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rights & counters storage.
 *
 * Permission model: casesystem.case.open.&lt;case_id&gt;.&lt;amount&gt;
 *  - LuckPerms installed  -> the node is read/rewritten through the LuckPerms API
 *    (User + Node, value = remaining opens).
 *  - no LuckPerms         -> presence-only check of the group/user permission plus a
 *    numeric counter kept in JSON or SQLite (bundled JDBC driver), synced on join/quit.
 *
 * All disk/DB writes are asynchronous (single worker thread).
 */
public final class DataManager {

    public static final String PERM_BASE = "casesystem.case.open.";

    private final CaseSystem plugin;
    private final Storage storage;
    private final boolean luckPerms;
    /** In-memory cache of counters (source of truth when LP is absent). */
    private final Map<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();
    private final Thread ioThread;
    private volatile boolean running = true;

    public DataManager(CaseSystem plugin) {
        this.plugin = plugin;
        var cfg = plugin.getConfig();
        String type = cfg.getString("storage.type", "JSON").toUpperCase(Locale.ROOT);
        this.storage = "SQLITE".equals(type)
                ? new SqliteStorage(new File(plugin.getDataFolder(), cfg.getString("storage.sqlite-file", "data.db")))
                : new JsonStorage(new File(plugin.getDataFolder(), cfg.getString("storage.file", "data.json")));
        this.storage.init();
        this.luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
        // Async writer thread: queue -> storage.
        this.ioThread = new Thread(this::ioLoop, "CaseSystem-IO");
        this.ioThread.setDaemon(true);
        this.ioThread.start();
    }

    public String storageType() { return storage.name(); }
    public boolean usingLuckPerms() { return luckPerms && lpApi() != null; }

    private LuckPerms lpApi() {
        if (!luckPerms) return null;
        try { return LuckPermsProvider.get(); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Remaining opens for a case (>= 0). */
    public int getAvailable(UUID id, String name, String caseId) {
        String perm = PERM_BASE + caseId.toLowerCase(Locale.ROOT);
        if (usingLuckPerms()) {
            try {
                User u = lpApi().getUserManager().getUser(id);
                if (u != null) {
                    for (Node n : u.getNodes()) {
                        if (n.getKey().startsWith(perm + ".") && n.getValue()) {
                            String suffix = n.getKey().substring(perm.length() + 1);
                            try { return Math.max(0, Integer.parseInt(suffix)); } catch (NumberFormatException ignored) {}
                        }
                    }
                    // No numbered node -> fall back to plain presence check.
                    return u.hasPermission(perm) ? Integer.MAX_VALUE : 0;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("LuckPerms read failed: " + t.getMessage());
            }
        }
        // No LP (or LP user not loaded yet): cached counter, else plain permission check.
        Map<String, Integer> m = cache.get(id);
        if (m != null && m.containsKey(caseId)) return Math.max(0, m.get(caseId));
        PlayerHolder ph = new PlayerHolder(id, name);
        return ph.has(perm) ? Integer.MAX_VALUE : 0;
    }

    /** Adds (or overwrites with positive delta) opens. Async persisted. */
    public void addOpens(UUID id, String name, String caseId, int amount) {
        caseId = caseId.toLowerCase(Locale.ROOT);
        int cur = getAvailableRawCounter(id, caseId);
        setOpens(id, name, caseId, cur + amount);
    }

    /** Sets exact remaining count and persists (LP node rewrite or storage). */
    public void setOpens(UUID id, String name, String caseId, int newAmount) {
        caseId = caseId.toLowerCase(Locale.ROOT);
        String perm = PERM_BASE + caseId;
        if (usingLuckPerms()) {
            LuckPerms lp = lpApi();
            try {
                User u = lp.getUserManager().getUser(id);
                if (u != null) {
                    Set<Node> toRemove = new HashSet<>();
                    for (Node n : u.getNodes())
                        if (n.getKey().startsWith(perm + ".")) toRemove.add(n);
                    if (!toRemove.isEmpty()) u.data().remove(toRemove);
                    if (newAmount > 0)
                        u.data().add(Node.builder(perm + "." + newAmount).value(true).build());
                    else
                        u.data().add(Node.builder(perm).value(false).build()); // explicit deny at 0
                    lp.getUserManager().saveUser(u);
                    cache.computeIfAbsent(id, k -> new HashMap<>()).put(caseId, Math.max(0, newAmount));
                    return;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("LuckPerms write failed: " + t.getMessage());
            }
        }
        cache.computeIfAbsent(id, k -> new HashMap<>()).put(caseId, Math.max(0, newAmount));
        enqueueSave(id);
    }

    /** Consumes one open. Returns true if it was allowed. Must run on main thread. */
    public boolean consume(UUID id, String name, String caseId) {
        int avail = getAvailable(id, name, caseId);
        if (avail <= 0) return false;
        if (avail != Integer.MAX_VALUE) setOpens(id, name, caseId, avail - 1);
        return true;
    }

    private int getAvailableRawCounter(UUID id, String caseId) {
        Map<String, Integer> m = cache.get(id);
        return m == null ? 0 : m.getOrDefault(caseId, 0);
    }

    /** Called from async pre-login: load counters off the main thread. */
    public void preload(UUID id, String name) {
        Map<String, Integer> m = storage.load(id.toString());
        if (m != null) cache.put(id, new HashMap<>(m));
    }

    /** Called on join: ensure cache exists; sync-on-login can pull fresh data. */
    public void onLogin(UUID id, String name) {
        if (plugin.getConfig().getBoolean("storage.sync-on-login-logout", true)) preload(id, name);
        cache.computeIfAbsent(id, k -> new HashMap<>());
    }

    /** Flush cache entry to storage asynchronously. */
    public void save(UUID id) {
        Map<String, Integer> m = cache.get(id);
        if (m == null) return;
        if (usingLuckPerms()) return; // counters live in LuckPerms nodes
        enqueueSave(id);
    }

    public void reload() {
        cache.clear();
    }

    public void close() {
        running = false;
        synchronized (queue) { queue.notifyAll(); }
        try { ioThread.join(2000); } catch (InterruptedException ignored) {}
        storage.close();
    }

    // ------------------------------------------------------------------
    // Async write queue
    // ------------------------------------------------------------------
    private final java.util.Queue<UUID> queue = new java.util.ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>();

    private void enqueueSave(UUID id) {
        synchronized (queue) {
            if (queued.add(id)) { queue.add(id); queue.notifyAll(); }
        }
    }

    private void ioLoop() {
        while (running || !queue.isEmpty()) {
            UUID id;
            synchronized (queue) {
                while (queue.isEmpty() && running) {
                    try { queue.wait(200); } catch (InterruptedException e) { return; }
                }
                if (queue.isEmpty()) break;
                id = queue.poll();
                queued.remove(id);
            }
            Map<String, Integer> m = cache.getOrDefault(id, new HashMap<>());
            try { storage.store(id.toString(), m); }
            catch (Throwable t) { plugin.getLogger().warning("Async save failed for " + id + ": " + t.getMessage()); }
        }
    }

    // ------------------------------------------------------------------
    // Nested: offline-capable permission presence helper
    // ------------------------------------------------------------------
    /** Wraps an OfflinePlayer for a plain "has permission" check (no number). */
    static final class PlayerHolder {
        private final UUID id;
        PlayerHolder(UUID id, String name) { this.id = id; }
        boolean has(String perm) {
            var p = Bukkit.getOfflinePlayer(id);
            return p != null && p.hasPermission(perm);
        }
    }

    // ------------------------------------------------------------------
    // Nested: storage abstraction + JSON / SQLite implementations
    // ------------------------------------------------------------------
    abstract static class Storage {
        abstract void init();
        abstract Map<String, Integer> load(String uuid);
        abstract void store(String uuid, Map<String, Integer> data);
        abstract void close();
        abstract String name();
    }

    /**
     * JSON storage implemented with Bukkit's YamlConfiguration as a JSON-safe
     * key/value bridge (flat "uuid.case" keys). Avoids shading Gson while staying
     * human-readable on disk.
     */
    static final class JsonStorage extends Storage {
        private final File file;
        private YamlConfiguration yaml;

        JsonStorage(File file) { this.file = file; }

        @Override void init() {
            yaml = YamlConfiguration.loadConfiguration(file);
        }

        @Override synchronized Map<String, Integer> load(String uuid) {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (String key : yaml.getKeys(false)) {
                if (key.startsWith(uuid + ".")) {
                    try { out.put(key.substring(uuid.length() + 1), yaml.getInt(key)); }
                    catch (Throwable ignored) {}
                }
            }
            return out;
        }

        @Override synchronized void store(String uuid, Map<String, Integer> data) {
            for (String key : yaml.getKeys(false))
                if (key.startsWith(uuid + ".")) yaml.set(key, null);
            for (Map.Entry<String, Integer> e : data.entrySet())
                yaml.set(uuid + "." + e.getKey(), e.getValue());
            try { yaml.save(file); } catch (IOException e) { throw new RuntimeException(e); }
        }

        @Override void close() {}
        @Override String name() { return "JSON"; }
    }

    /** SQLite storage via the JDBC driver bundled in modern Minecraft servers. */
    static final class SqliteStorage extends Storage {
        private final File file;
        private Connection con;

        SqliteStorage(File file) { this.file = file; }

        @Override void init() {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("SQLite JDBC driver not available on this server.", e);
            }
            try {
                con = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement st = con.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS cases_data (uuid TEXT, case_id TEXT, amount INT, PRIMARY KEY(uuid, case_id))");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to open SQLite storage", e);
            }
        }

        @Override synchronized Map<String, Integer> load(String uuid) {
            Map<String, Integer> out = new LinkedHashMap<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT case_id, amount FROM cases_data WHERE uuid=?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
                }
            } catch (Exception e) { throw new RuntimeException(e); }
            return out;
        }

        @Override synchronized void store(String uuid, Map<String, Integer> data) {
            try {
                con.setAutoCommit(false);
                try (PreparedStatement del = con.prepareStatement("DELETE FROM cases_data WHERE uuid=?")) {
                    del.setString(1, uuid); del.executeUpdate();
                }
                try (PreparedStatement ins = con.prepareStatement("INSERT OR REPLACE INTO cases_data(uuid, case_id, amount) VALUES(?,?,?)")) {
                    for (Map.Entry<String, Integer> e : data.entrySet()) {
                        ins.setString(1, uuid); ins.setString(2, e.getKey()); ins.setInt(3, e.getValue());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                con.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { try { con.setAutoCommit(true); } catch (Exception ignored) {} }
        }

        @Override synchronized void close() {
            try { if (con != null) con.close(); } catch (Exception ignored) {}
        }

        @Override String name() { return "SQLITE"; }
    }
}
