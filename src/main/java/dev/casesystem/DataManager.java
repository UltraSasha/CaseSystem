package dev.casesystem;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
    /** Lifetime "opened" statistics per player/case. */
    private final Map<UUID, Map<String, Integer>> openedCache = new ConcurrentHashMap<>();
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

    /** Remaining opens for a case (>= 0). Cases are ALWAYS visible in the GUI -
     *  this method only reports how many opens the player has (0 = no counter yet). */
    public int getAvailable(UUID id, String name, String caseId) {
        // 1) The plugin's own counter is the source of truth (LP node / storage counter).
        if (usingLuckPerms()) {
            try {
                User u = lpApi().getUserManager().getUser(id);
                if (u != null) {
                    int best = -1;
                    boolean bareGranted = false;
                    for (Node n : u.getNodes()) {
                        String k = n.getKey();
                        if (k.startsWith(PERM_BASE + caseId.toLowerCase(Locale.ROOT) + ".")) {
                            if (!n.getValue()) continue;
                            String suffix = k.substring((PERM_BASE + caseId.toLowerCase(Locale.ROOT)).length() + 1);
                            try { best = Math.max(best, Integer.parseInt(suffix)); } catch (NumberFormatException ignored) {}
                        } else if (k.equals(PERM_BASE + caseId.toLowerCase(Locale.ROOT))) {
                            bareGranted = n.getValue();
                        }
                    }
                    if (best >= 0) return best;
                    // Bare node without number: OP -> unlimited; normal player ->
                    // migrate on login (see onLogin/migrateBareNodes), until then treat as 0.
                    if (bareGranted) {
                        Player online = Bukkit.getPlayer(id);
                        return (online != null && online.isOp()) ? Integer.MAX_VALUE : 0;
                    }
                    return 0;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("LuckPerms read failed: " + t.getMessage());
            }
        }
        // 2) No LP (or user not loaded): cached counter first.
        Map<String, Integer> m = cache.get(id);
        if (m != null && m.containsKey(caseId)) return Math.max(0, m.get(caseId));
        // 3) Vanilla numbered permission grant, e.g. ...open.common.5
        PlayerHolder ph = new PlayerHolder(id, name);
        for (String s : ph.grantedSuffixes(PERM_BASE + caseId.toLowerCase(Locale.ROOT))) {
            try { int v = Integer.parseInt(s); if (v > 0) return v; } catch (NumberFormatException ignored) {}
        }
        // 4) Bare node: OP -> unlimited; normal player -> migration handles it on login.
        if (ph.has(PERM_BASE + caseId.toLowerCase(Locale.ROOT))) {
            Player online = Bukkit.getPlayer(id);
            return (online != null && online.isOp()) ? Integer.MAX_VALUE : 0;
        }
        return 0;
    }

    /** Total lifetime opens of a case by the player (for /case stats). */
    public int getOpened(UUID id, String caseId) {
        Map<String, Integer> m = openedCache.get(id);
        return m == null ? 0 : m.getOrDefault(caseId.toLowerCase(Locale.ROOT), 0);
    }

    /** Removes up to {@code amount} opens; returns how many were actually removed. */
    public int takeOpens(UUID id, String name, String caseId, int amount) {
        caseId = caseId.toLowerCase(Locale.ROOT);
        int avail = getAvailable(id, name, caseId);
        if (avail <= 0) return 0;
        if (avail == Integer.MAX_VALUE) {          // unlimited -> set explicit finite 0
            setOpens(id, name, caseId, 0);
            return amount;                          // requested amount considered taken
        }
        int taken = Math.min(avail, amount);
        setOpens(id, name, caseId, avail - taken);
        return taken;
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
                    // Storage.set removes one Node at a time - loop instead of passing the Set.
                    for (Node n : toRemove) u.data().remove(n);
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
        // lifetime stats
        openedCache.computeIfAbsent(id, k -> new HashMap<>())
                .merge(caseId.toLowerCase(Locale.ROOT), 1, Integer::sum);
        enqueueSave(id);
        return true;
    }

    /** Called from async pre-login: load counters off the main thread. */
    public void preload(UUID id, String name) {
        Map<String, Integer> m = storage.load(id.toString());
        if (m != null) cache.put(id, new HashMap<>(m));
        Map<String, Integer> o = storage.loadOpened(id.toString());
        if (o != null) openedCache.put(id, new HashMap<>(o));
    }

    private int getAvailableRawCounter(UUID id, String caseId) {
        Map<String, Integer> m = cache.get(id);
        return m == null ? 0 : m.getOrDefault(caseId, 0);
    }

    /** Called on join: ensure cache exists; sync-on-login can pull fresh data. */
    public void onLogin(UUID id, String name) {
        if (plugin.getConfig().getBoolean("storage.sync-on-login-logout", true)) preload(id, name);
        // Do NOT clobber the LuckPerms counter with an empty map: it would make
        // getAvailable() return 0 and hide all cases in the GUI.
        cache.computeIfAbsent(id, k -> new HashMap<>());
        openedCache.computeIfAbsent(id, k -> new HashMap<>(storage.loadOpened(id.toString())));
        migrateBareNodes(id, name);
    }

    /**
     * Bare permission nodes without a number (casesystem.case.open.&lt;id&gt;) are migrated
     * ON LOGIN for non-OP players: the node is revoked instantly and +1 open is credited
     * to the counter. OPs keep the bare node as unlimited access.
     */
    private void migrateBareNodes(UUID id, String name) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || p.isOp()) return;
        for (String caseId : plugin.cases().getCases().keySet()) {
            String perm = PERM_BASE + caseId.toLowerCase(Locale.ROOT);
            if (usingLuckPerms()) {
                try {
                    User u = lpApi().getUserManager().getUser(id);
                    if (u == null) continue;
                    Node bare = null;
                    boolean hasNumbered = false;
                    for (Node n : u.getNodes()) {
                        if (n.getKey().equals(perm) && n.getValue()) bare = n;
                        else if (n.getKey().startsWith(perm + ".") && n.getValue()) hasNumbered = true;
                    }
                    if (bare != null) {
                        u.data().remove(bare);
                        int cur = hasNumbered ? getAvailable(id, name, caseId)
                                : cache.getOrDefault(id, new HashMap<>()).getOrDefault(caseId, 0);
                        lpApi().getUserManager().saveUser(u);
                        setOpens(id, name, caseId, Math.max(0, cur) + 1);
                        plugin.getLogger().info("[CaseSystem] Migrated bare node '" + perm + "' for " + name + " -> counter+1");
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Bare-node migration failed for " + name + ": " + t.getMessage());
                }
            } else {
                // No LuckPerms: check effective vanilla permissions once per login.
                if (p.hasPermission(perm) && !p.hasPermission(perm + ".*")) {
                    p.removeAttachment(p.addAttachment(plugin, perm, false)); // revoke presence locally
                    int cur = cache.getOrDefault(id, new HashMap<>()).getOrDefault(caseId, 0);
                    setOpens(id, name, caseId, cur + 1);
                    plugin.getLogger().info("[CaseSystem] Migrated bare perm '" + perm + "' for " + name + " -> counter+1");
                }
            }
        }
    }

    /** Flush cache entry to storage asynchronously. */
    public void save(UUID id) {
        Map<String, Integer> m = cache.get(id);
        if (m == null && !openedCache.containsKey(id)) return;
        enqueueSave(id); // opened-stats always persisted; counters only when LP is absent
    }

    public void reload() {
        cache.clear();
        openedCache.clear();
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
            Map<String, Integer> o = openedCache.getOrDefault(id, new HashMap<>());
            try { storage.store(id.toString(), m, o); }
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
            // OfflinePlayer has no hasPermission() in the 1.19.2 API - check online players
            // directly; for offline ones fall back to the permission registered in plugin.yml
            // with default TRUE (best-effort without LuckPerms).
            Player online = Bukkit.getPlayer(id);
            if (online != null) return online.hasPermission(perm);
            org.bukkit.permissions.Permission def = Bukkit.getPluginManager().getPermission(perm);
            return def != null && def.getDefault() == org.bukkit.permissions.PermissionDefault.TRUE;
        }
        /** Suffixes of granted permissions like casesystem.case.open.<caseId>.<suffix> (online only). */
        java.util.List<String> grantedSuffixes(String permPrefix) {
            java.util.List<String> out = new java.util.ArrayList<>();
            Player online = Bukkit.getPlayer(id);
            if (online == null) return out;
            for (org.bukkit.permissions.PermissionAttachmentInfo info : online.getEffectivePermissions()) {
                String k = info.getPermission().toLowerCase(Locale.ROOT);
                if (!info.getValue()) continue;
                if (k.startsWith(permPrefix.toLowerCase(Locale.ROOT) + "."))
                    out.add(k.substring(permPrefix.length() + 1));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Nested: storage abstraction + JSON / SQLite implementations
    // ------------------------------------------------------------------
    abstract static class Storage {
        abstract void init();
        abstract Map<String, Integer> load(String uuid);
        /** Lifetime opened-stats; default impl reads the ".opened" key space. */
        Map<String, Integer> loadOpened(String uuid) { return new LinkedHashMap<>(); }
        abstract void store(String uuid, Map<String, Integer> data, Map<String, Integer> opened);
        abstract void close();
        abstract String name();
    }

    /**
     * JSON storage implemented with Bukkit's YamlConfiguration as a JSON-safe
     * key/value bridge (flat "uuid.case" keys, stats under "uuid.opened.case").
     * Avoids shading Gson while staying human-readable on disk.
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
                if (key.startsWith(uuid + ".") && !key.startsWith(uuid + ".opened.")) {
                    try { out.put(key.substring(uuid.length() + 1), yaml.getInt(key)); }
                    catch (Throwable ignored) {}
                }
            }
            return out;
        }

        @Override synchronized Map<String, Integer> loadOpened(String uuid) {
            Map<String, Integer> out = new LinkedHashMap<>();
            String p = uuid + ".opened.";
            for (String key : yaml.getKeys(false)) {
                if (key.startsWith(p)) {
                    try { out.put(key.substring(p.length()), yaml.getInt(key)); }
                    catch (Throwable ignored) {}
                }
            }
            return out;
        }

        @Override synchronized void store(String uuid, Map<String, Integer> data, Map<String, Integer> opened) {
            for (String key : yaml.getKeys(false))
                if (key.startsWith(uuid + ".")) yaml.set(key, null);
            for (Map.Entry<String, Integer> e : data.entrySet())
                yaml.set(uuid + "." + e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : opened.entrySet())
                yaml.set(uuid + ".opened." + e.getKey(), e.getValue());
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
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS cases_opened (uuid TEXT, case_id TEXT, opened INT, PRIMARY KEY(uuid, case_id))");
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

        @Override synchronized Map<String, Integer> loadOpened(String uuid) {
            Map<String, Integer> out = new LinkedHashMap<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT case_id, opened FROM cases_opened WHERE uuid=?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
                }
            } catch (Exception e) { throw new RuntimeException(e); }
            return out;
        }

        @Override synchronized void store(String uuid, Map<String, Integer> data, Map<String, Integer> opened) {
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
                try (PreparedStatement upd = con.prepareStatement(
                        "INSERT INTO cases_opened(uuid, case_id, opened) VALUES(?,?,?) " +
                        "ON CONFLICT(uuid, case_id) DO UPDATE SET opened=excluded.opened")) {
                    for (Map.Entry<String, Integer> e : opened.entrySet()) {
                        upd.setString(1, uuid); upd.setString(2, e.getKey()); upd.setInt(3, e.getValue());
                        upd.addBatch();
                    }
                    upd.executeBatch();
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
