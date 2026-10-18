package dev.casesystem;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Plugin entry point: bootstrap, command /case (+ optional alias /casesystem),
 * admin subcommands, resource-pack delivery on join and storage sync hooks.
 *
 * Sub-managers: {@link CaseManager} (config model + GUIs),
 * {@link DataManager} (rights & JSON/SQLite/LuckPerms storage),
 * {@link AnimationManager} (3D via ProtocolLib/native ItemDisplay + GUI roulette),
 * {@link RewardManager} (placeholders & console commands).
 */
public final class CaseSystem extends JavaPlugin implements Listener, TabCompleter {

    /** Command label used for Bukkit.getPluginCommand(...) - must match plugin.yml. */
    public static final String COMMAND = "case";

    private static CaseSystem instance;

    private CaseManager caseManager;
    private DataManager dataManager;
    private AnimationManager animationManager;
    private RewardManager rewardManager;

    public static CaseSystem get() { return instance; }

    public CaseManager cases()       { return caseManager; }
    public DataManager data()        { return dataManager; }
    public AnimationManager anim()   { return animationManager; }
    public RewardManager rewards()   { return rewardManager; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Messages.init(this);

        // Order matters: data -> cases -> rewards -> animations.
        Messages.load();
        this.dataManager      = new DataManager(this);
        this.caseManager      = new CaseManager(this);
        this.rewardManager    = new RewardManager(this);
        this.animationManager = new AnimationManager(this);

        org.bukkit.command.PluginCommand cmd = Bukkit.getPluginCommand(COMMAND);
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().severe("[CaseSystem] Command '" + COMMAND + "' is not registered - plugin.yml inside the JAR "
                    + "does not declare it. Build a fresh jar with './gradlew build' and replace the old one in plugins/.");
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(caseManager, this);

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null)
            getLogger().info("[CaseSystem] LuckPerms found - permission counters managed via LuckPerms API.");
        else
            getLogger().info("[CaseSystem] LuckPerms not found - counters kept in " + dataManager.storageType() + " storage.");
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null)
            getLogger().info("[CaseSystem] PlaceholderAPI found - external %...% placeholders enabled.");
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null)
            getLogger().info("[CaseSystem] ProtocolLib found - fake ItemDisplay available for 1.19.2.");
        getLogger().info("[CaseSystem] enabled. Server: " + Bukkit.getVersion());
    }

    @Override
    public void onDisable() {
        if (animationManager != null) animationManager.shutdown();
        if (dataManager != null) {
            for (Player p : Bukkit.getOnlinePlayers()) dataManager.save(p.getUniqueId());
            dataManager.close();
        }
        instance = null;
    }

    /** Reloads config.yml + messages.yml into memory. */
    public void reloadAll() {
        reloadConfig();
        Messages.load();
        // Re-parse cases so GUI names/lores/chances pick up the new messages.yml templates.
        if (caseManager != null) caseManager.parseCases(getConfig());
    }


    /** Colored display name of a case (falls back to the raw id if unknown). */
    private String caseName(String caseId) {
        CaseManager.CaseDef c = caseManager.getCases().get(caseId);
        return Messages.color(c != null ? c.name : caseId);
    }

    // ------------------------------------------------------------------
    // Commands: /case [reload|give|take|stats|list|debug]  (alias /casesystem)
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // /casesystem can be disabled in config -> then it behaves like an unknown command.
        if (label.equalsIgnoreCase("casesystem") && !getConfig().getBoolean("commands.case-system-alias", true)) {
            sender.sendMessage(org.bukkit.ChatColor.RED + "Unknown command. Type \"/help\" for help.");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("GUI only for players."); return true; }
            caseManager.openMainGui(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload": {
                if (!sender.hasPermission("casesystem.admin")) { noPerm(sender); return true; }
                reloadAll();
                caseManager.parseCases(getConfig());
                dataManager.reload();
                sender.sendMessage(Messages.msg("reloaded"));
                return true;
            }
            case "give": {
                if (!sender.hasPermission("casesystem.admin")) { noPerm(sender); return true; }
                if (args.length < 4) { sender.sendMessage(Messages.msg("usage-give")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Messages.msg("target-offline")); return true; }
                String caseId = args[2].toLowerCase(Locale.ROOT);
                if (!caseManager.getCases().containsKey(caseId)) {
                    sender.sendMessage(Messages.get("unknown-case").replace("%case%", caseId));
                    return true;
                }
                int amount;
                try { amount = Integer.parseInt(args[3]); } catch (NumberFormatException e) {
                    sender.sendMessage(Messages.msg("usage-give")); return true;
                }
                if (amount <= 0) { sender.sendMessage(Messages.msg("usage-give")); return true; }
                dataManager.addOpens(target.getUniqueId(), target.getName(), caseId, amount);
                String m = Messages.get("given")
                        .replace("%player%", target.getName())
                        .replace("%case%", caseName(caseId))
                        .replace("%amount%", String.valueOf(amount));
                sender.sendMessage(m);
                if (sender != target) target.sendMessage(m);
                return true;
            }
            case "take": {
                if (!sender.hasPermission("casesystem.admin")) { noPerm(sender); return true; }
                if (args.length < 4) { sender.sendMessage(Messages.msg("usage-take")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Messages.msg("target-offline")); return true; }
                String caseId = args[2].toLowerCase(Locale.ROOT);
                if (!caseManager.getCases().containsKey(caseId)) {
                    sender.sendMessage(Messages.get("unknown-case").replace("%case%", caseId));
                    return true;
                }
                int amount;
                try { amount = Integer.parseInt(args[3]); } catch (NumberFormatException e) {
                    sender.sendMessage(Messages.msg("usage-take")); return true;
                }
                if (amount <= 0) { sender.sendMessage(Messages.msg("usage-take")); return true; }
                int taken = dataManager.takeOpens(target.getUniqueId(), target.getName(), caseId, amount);
                String m = Messages.get("taken")
                        .replace("%player%", target.getName())
                        .replace("%case%", caseName(caseId))
                        .replace("%amount%", String.valueOf(taken));
                sender.sendMessage(m);
                return true;
            }
            case "stats": {
                if (!sender.hasPermission("casesystem.admin")) { noPerm(sender); return true; }
                if (args.length < 2) { sender.sendMessage(Messages.msg("usage-stats")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                UUID tid; String tname;
                if (target != null) { tid = target.getUniqueId(); tname = target.getName(); }
                else {
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
                    if (!op.hasPlayedBefore()) { sender.sendMessage(Messages.msg("target-offline")); return true; }
                    tid = op.getUniqueId(); tname = op.getName() == null ? args[1] : op.getName();
                }
                sender.sendMessage(Messages.get("stats-header").replace("%player%", tname));
                for (CaseManager.CaseDef c : caseManager.getCases().values()) {
                    sender.sendMessage(Messages.get("stats-entry")
                            .replace("%case%", Messages.color(c.name))
                            .replace("%available%", fmt(dataManager.getAvailable(tid, tname, c.id)))
                            .replace("%opened%", String.valueOf(dataManager.getOpened(tid, c.id))));
                }
                return true;
            }
            case "list": {
                if (!sender.hasPermission("casesystem.admin")) { noPerm(sender); return true; }
                sender.sendMessage(Messages.msg("list-header"));
                for (CaseManager.CaseDef c : caseManager.getCases().values()) {
                    long wSum = 0; for (CaseManager.Rarity r : c.rarities.values()) wSum += Math.max(0, r.weight);
                    StringBuilder rs = new StringBuilder();
                    for (CaseManager.Rarity r : c.rarities.values())
                        rs.append(r.color).append(r.id).append("&7(")
                          .append(wSum > 0 ? String.format(Locale.US, "%.1f%%", 100.0 * Math.max(0, r.weight) / wSum) : "0%").append("&7) ");
                    String avail = "нет";
                    if (sender instanceof Player pl) avail = fmt(dataManager.getAvailable(pl.getUniqueId(), pl.getName(), c.id));
                    sender.sendMessage(Messages.get("list-entry")
                            .replace("%case%", Messages.color(c.name))
                            .replace("%available%", avail)
                            .replace("%perm%", c.permId)
                            .replace("%rarities%", rs.toString()));
                }
                return true;
            }
            case "debug": {
                if (!sender.hasPermission("casesystem.admin")) { noPerm(sender); return true; }
                sender.sendMessage(Messages.msg("debug-header"));
                List<String> lines = new ArrayList<>();
                lines.add("version=" + getDescription().getVersion());
                lines.add("server=" + Bukkit.getBukkitVersion());
                lines.add("protocolLib=" + Bukkit.getPluginManager().isPluginEnabled("ProtocolLib"));
                lines.add("placeholderApi=" + Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"));
                lines.add("luckPerms=" + Bukkit.getPluginManager().isPluginEnabled("LuckPerms"));
                lines.add("storage=" + dataManager.storageType());
                lines.add("mode=" + animationManager.currentMode());
                lines.add("cases=" + caseManager.getCases().keySet());
                if (sender instanceof Player pl) {
                    for (String cid : caseManager.getCases().keySet())
                        lines.add("avail." + cid + "=" + fmt(dataManager.getAvailable(pl.getUniqueId(), pl.getName(), cid)));
                }
                for (String l : lines)
                    sender.sendMessage(Messages.get("debug-line").replace("%line%", l));
                return true;
            }
            default:
                sender.sendMessage(Messages.msg("usage-unknown"));
                return true;
        }
    }

    private static String fmt(int avail) { return avail == Integer.MAX_VALUE ? "∞" : String.valueOf(avail); }

    private void noPerm(CommandSender s) { s.sendMessage(Messages.msg("no-permission")); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("casesystem.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("reload", "give", "take", "stats", "list", "debug"))
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && List.of("give", "take", "stats").contains(args[0].toLowerCase(Locale.ROOT))) {
            String a = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a)) out.add(p.getName());
        } else if (args.length == 3 && List.of("give", "take").contains(args[0].toLowerCase(Locale.ROOT))) {
            String a = args[2].toLowerCase(Locale.ROOT);
            for (String id : caseManager.getCases().keySet()) if (id.startsWith(a)) out.add(id);
        } else if (args.length == 4 && List.of("give", "take").contains(args[0].toLowerCase(Locale.ROOT))) {
            out.add("1");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Resource pack + storage sync
    // ------------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        dataManager.onLogin(p.getUniqueId(), p.getName());
        var cfg = getConfig();
        if (cfg.getBoolean("resource-pack.enabled", false)) {
            String url = cfg.getString("resource-pack.url", "");
            String hash = cfg.getString("resource-pack.hash", "");
            String msg = cfg.getString("resource-pack.message", "");
            if (!url.isEmpty()) {
                if (msg != null && !msg.isEmpty()) p.sendMessage(Messages.color(msg));
                try {
                    if (hash != null && !hash.isEmpty())
                        p.setResourcePack(url, hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    else
                        p.setResourcePack(url);
                } catch (Throwable t) {
                    getLogger().warning("Failed to send resource pack URL: " + t.getMessage());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (animationManager != null) animationManager.cleanupPlayer(e.getPlayer().getUniqueId());
        dataManager.save(e.getPlayer().getUniqueId());
    }

    /** Preloads data asynchronously before the player object exists (async-safe read). */
    @EventHandler(ignoreCancelled = false)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        dataManager.preload(e.getUniqueId(), e.getName());
    }

    // ------------------------------------------------------------------
    // Messages helper (nested class #1)
    // ------------------------------------------------------------------
    public static final class Messages {
        private static java.io.File file;
        private static org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();

        private static CaseSystem plugin;

        static void init(CaseSystem pl) {
            plugin = pl;
            file = new java.io.File(pl.getDataFolder(), "messages.yml");
            if (!file.exists()) pl.saveResource("messages.yml", false);
        }

        static void load() {
            if (plugin == null || file == null) return;
            try { yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file); }
            catch (Throwable ignored) {}
        }

        /** Registers casesystem.case.&lt;PermId&gt; permissions so vanilla/OP defaults work. */
        static void registerCasePermissions(java.util.Collection<String> permIds) {
            for (String pid : permIds) {
                String node = "casesystem.case." + pid.toLowerCase(java.util.Locale.ROOT);
                try {
                    if (Bukkit.getPluginManager().getPermission(node) == null) {
                        org.bukkit.permissions.Permission p = new org.bukkit.permissions.Permission(node,
                                org.bukkit.permissions.PermissionDefault.FALSE);
                        // Child: the numbered counter node of the same case.
                        p.getChildren().put(node.replaceFirst("^casesystem\\.case\\.", "casesystem.count.") + ".*", true);
                        Bukkit.getPluginManager().addPermission(p);
                    }
                } catch (Throwable ignored) {}
            }
        }

        public static String get(String key) {
            String def = switch (key) {
                case "prefix" -> "&8[&6CaseSystem&8] &r";
                case "no-permission" -> "&cУ вас нет прав.";
                default -> key;
            };
            return color(yaml.getString(key, def));
        }

        /** String list template from messages.yml (GUI lore lines etc.). */
        public static List<String> getList(String key) {
            List<String> raw = yaml.getStringList(key);
            if (raw.isEmpty()) {
                raw = switch (key) {
                    case "gui-main-entry-lore" -> List.of("&7Открытий: &e%available%", "&7Редкостей: &f%rarities% &8| &7Предметов: &f%items%", "", "%desc%", "", "&8Кликните для просмотра");
                    case "gui-detail-lore" -> List.of("&7Доступно открытий: &e%available%", "&7Право: &f%perm%", "", "%desc%", "", "&a▶ Кликните по кейсу,", "&aчтобы открыть его");
                    case "gui-rarity-lore" -> List.of("&7Шанс редкости: &e%percent%", "&7Предметов: &f%count%", "", "%items%");
                    default -> List.of();
                };
            }
            List<String> out = new ArrayList<>();
            for (String s : raw) out.add(color(s));
            return out;
        }

        public static String msg(String key) { return get("prefix") + get(key); }

        public static String color(String s) {
            return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
        }
    }
}
