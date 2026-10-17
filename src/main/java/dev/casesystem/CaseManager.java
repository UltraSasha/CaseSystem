package dev.casesystem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration model (cases / rarities / items) + the two-level GUIs and the
 * flat GUI roulette fallback. Nested data classes live here as well.
 */
public final class CaseManager implements Listener {

    // ------------------------------------------------------------------
    // Nested data model
    // ------------------------------------------------------------------
    public static final class Rarity {
        public final String id, color;
        public final int weight;
        public final ItemStack display;
        Rarity(String id, String color, int weight, ItemStack display) {
            this.id = id; this.color = color; this.weight = weight; this.display = display;
        }
    }

    public static final class ItemDef {
        public final String id, rarityId;
        public final int weight;
        public final ItemStack icon;
        public final List<String> commands;
        ItemDef(String id, String rarityId, int weight, ItemStack icon, List<String> commands) {
            this.id = id; this.rarityId = rarityId; this.weight = weight; this.icon = icon; this.commands = commands;
        }
    }

    public static final class CaseDef {
        public final String id;
        public final String name;
        public final List<String> description;
        public final boolean showContents;
        public final ItemStack icon;
        public final LinkedHashMap<String, Rarity> rarities = new LinkedHashMap<>();
        public final List<ItemDef> items = new ArrayList<>();

        CaseDef(String id, String name, List<String> description, boolean showContents, ItemStack icon) {
            this.id = id; this.name = name; this.description = description;
            this.showContents = showContents; this.icon = icon;
        }

        public List<ItemDef> itemsOf(String rarityId) {
            List<ItemDef> out = new ArrayList<>();
            for (ItemDef it : items) if (it.rarityId.equals(rarityId)) out.add(it);
            return out;
        }

        /** Total probability of an item inside this case (rarity weight/sum * item weight/sum). */
        public double chanceOf(ItemDef it) {
            Rarity r = rarities.get(it.rarityId);
            long rSum = totalRarityWeight(), iSum = totalItemWeight(it.rarityId);
            if (r == null || rSum <= 0 || iSum <= 0) return 0;
            return (100.0 * r.weight / rSum) * (100.0 * it.weight / iSum) / 100.0;
        }

        public long totalRarityWeight() { long s = 0; for (Rarity r : rarities.values()) s += Math.max(0, r.weight); return s; }
        public long totalItemWeight(String rarityId) { long s = 0; for (ItemDef i : itemsOf(rarityId)) s += Math.max(0, i.weight); return s; }
    }

    /** GUI view markers (attached to inventories via InventoryHolder). */
    public enum View { MAIN, DETAIL, ROULETTE }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private final CaseSystem plugin;
    private final Map<String, CaseDef> cases = new LinkedHashMap<>();
    private final NamespacedKey cmdKey, caseKey;
    /** Roulette sessions: inventory -> items laid out on its middle row. */
    private final Map<Inventory, Integer> rouletteTicks = new LinkedHashMap<>();
    private final Map<Inventory, Integer> rouletteWinnerSlot = new LinkedHashMap<>();
    private final Map<Inventory, Runnable> rouletteFinish = new LinkedHashMap<>();

    public CaseManager(CaseSystem plugin) {
        this.plugin = plugin;
        this.cmdKey = new NamespacedKey(plugin, "item_commands");
        this.caseKey = new NamespacedKey(plugin, "case_id");
        parseCases(plugin.getConfig());
    }

    public Map<String, CaseDef> getCases() { return cases; }
    public NamespacedKey commandKey() { return cmdKey; }

    // ------------------------------------------------------------------
    // Config parsing
    // ------------------------------------------------------------------
    @SuppressWarnings("deprecation")
    public void parseCases(FileConfiguration cfg) {
        cases.clear();
        ConfigurationSection root = cfg.getConfigurationSection("cases");
        if (root == null) return;
        boolean globalShow = cfg.getBoolean("gui.show-contents", true);
        for (String cid : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(cid);
            if (cs == null) continue;
            cid = cid.toLowerCase(Locale.ROOT);
            String name = CaseSystem.Messages.color(cs.getString("name", cid));
            List<String> desc = new ArrayList<>();
            for (String d : cs.getStringList("description")) desc.add(CaseSystem.Messages.color(d));
            boolean show = cs.contains("show-contents") ? cs.getBoolean("show-contents") : globalShow;

            ItemStack icon = buildIcon(cs, Material.CHEST, name, desc);

            CaseDef def = new CaseDef(cid, name, desc, show, icon);
            ConfigurationSection rs = cs.getConfigurationSection("rarities");
            if (rs != null) for (String rid : rs.getKeys(false)) {
                ConfigurationSection rc = rs.getConfigurationSection(rid);
                if (rc == null) continue;
                rid = rid.toLowerCase(Locale.ROOT);
                String color = CaseSystem.Messages.color(rc.getString("color", "&f"));
                int weight = rc.getInt("weight", 0);
                Material dm = matchMaterial(rc.getString("display-item", "PAPER"), Material.PAPER);
                ItemStack disp = new ItemStack(dm);
                ItemMeta dm2 = disp.getItemMeta();
                if (dm2 != null) {
                    dm2.setDisplayName(color + capitalize(rid));
                    disp.setItemMeta(dm2);
                }
                def.rarities.put(rid, new Rarity(rid, color, weight, disp));
            }
            ConfigurationSection is = cs.getConfigurationSection("items");
            if (is != null) for (String iid : is.getKeys(false)) {
                ConfigurationSection ic = is.getConfigurationSection(iid);
                if (ic == null) continue;
                String rarityId = ic.getString("rarity", "").toLowerCase(Locale.ROOT);
                if (!def.rarities.containsKey(rarityId)) {
                    plugin.getLogger().warning("Item '" + iid + "' in case '" + cid + "' references unknown rarity '" + rarityId + "' - skipped.");
                    continue;
                }
                int weight = ic.getInt("weight", 0);
                Material mat = matchMaterial(ic.getString("material", "STONE"), Material.STONE);
                List<String> lore = new ArrayList<>();
                for (String l : ic.getStringList("lore")) lore.add(CaseSystem.Messages.color(l));
                lore.add("");
                lore.add("&7Редкость: " + def.rarities.get(rarityId).color + rarityId);
                lore.add("&7Шанс: &e" + String.format(Locale.US, "%.2f%%", placeholderChance(def, rarityId, weight)));
                ItemStack item = buildIcon(ic, mat,
                        CaseSystem.Messages.color(ic.getString("name", "&f" + iid)),
                        colorizeList(lore));
                if (ic.getBoolean("glow", false)) {
                    // MENDING exists in the 1.19.2 API and on 1.21.1; combined with
                    // HIDE_ENCHANTS it gives a pure visual "glow" without changing gameplay.
                    ItemMeta gm = item.getItemMeta();
                    if (gm != null) { gm.addEnchant(Enchantment.MENDING, 1, true); gm.addItemFlags(ItemFlag.HIDE_ENCHANTS); item.setItemMeta(gm); }
                }
                List<String> cmds = new ArrayList<>(ic.getStringList("commands"));
                def.items.add(new ItemDef(iid.toLowerCase(Locale.ROOT), rarityId, weight, tagCommands(item, cmds), cmds));
            }
            if (def.rarities.isEmpty() || def.items.isEmpty()) {
                plugin.getLogger().warning("Case '" + cid + "' has no rarities/items - skipped.");
                continue;
            }
            cases.put(cid, def);
        }
    }

    private double placeholderChance(CaseDef def, String rarityId, int itemWeight) {
        long rSum = def.totalRarityWeight(), iSum = def.totalItemWeight(rarityId);
        if (rSum <= 0 || iSum <= 0) return 0;
        Rarity r = def.rarities.get(rarityId);
        return (100.0 * r.weight / rSum) * (100.0 * itemWeight / iSum) / 100.0;
    }

    private List<String> colorizeList(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(CaseSystem.Messages.color(s));
        return out;
    }

    @SuppressWarnings("deprecation")
    private ItemStack buildIcon(ConfigurationSection sec, Material fallback, String name, List<String> baseLore) {
        Material mat = matchMaterial(sec.getString("icon", fallback.name()), fallback);
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            for (String s : baseLore) lore.add(CaseSystem.Messages.color(s));
            if (!lore.isEmpty()) m.setLore(lore);
            if (sec.contains("custom-model-data")) {
                try { m.setCustomModelData(sec.getInt("custom-model-data")); } catch (Throwable ignored) {}
            }
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack tagCommands(ItemStack item, List<String> cmds) {
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.getPersistentDataContainer().set(cmdKey, org.bukkit.persistence.PersistentDataType.STRING, String.join("\n", cmds));
            item.setItemMeta(m);
        }
        return item;
    }

    /** Material matching tolerant across versions (namespaced or legacy names). */
    private Material matchMaterial(String raw, Material fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        String s = raw.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        Material m = Material.matchMaterial(s);
        if (m == null && !s.startsWith("minecraft:")) m = Material.matchMaterial("minecraft:" + s);
        return m == null ? fallback : m;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------
    // GUI level 1: case list
    // ------------------------------------------------------------------
    public void openMainGui(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        int rows = Math.min(6, Math.max(1, cfg.getInt("gui.rows", 6)));
        String title = CaseSystem.Messages.color(cfg.getString("gui.main-title", "&8Кейсы"));
        Inventory inv = Bukkit.createInventory(new GuiHolder(View.MAIN), rows * 9, title);
        fill(inv, cfg);
        int slot = 10;
        for (CaseDef c : cases.values()) {
            if (!p.hasPermission(DataManager.PERM_BASE + c.id)) continue; // presence check only for listing
            int avail = plugin.data().getAvailable(p.getUniqueId(), p.getName(), c.id);
            if (avail <= 0) continue;
            ItemStack it = c.icon.clone();
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                List<String> lore = m.getLore() == null ? new ArrayList<>() : new ArrayList<>(m.getLore());
                lore.add("&8Открытий: &e" + (avail == Integer.MAX_VALUE ? "∞" : avail));
                m.setLore(colorizeList(lore));
                m.getPersistentDataContainer().set(caseKey, org.bukkit.persistence.PersistentDataType.STRING, c.id);
                it.setItemMeta(m);
            }
            while (slot < inv.getSize() && (slot % 9 == 0 || slot % 9 == 8)) slot++; // keep away from edges
            if (slot >= inv.getSize()) break;
            inv.setItem(slot, it);
            slot++;
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------
    // GUI level 2: case detail
    // ------------------------------------------------------------------
    public void openDetailGui(Player p, CaseDef c) {
        FileConfiguration cfg = plugin.getConfig();
        String title = CaseSystem.Messages.color(cfg.getString("gui.detail-title", "&8Кейс: &f{case}")).replace("{case}", stripColors(c.name));
        Inventory inv = Bukkit.createInventory(new GuiHolder(View.DETAIL), 27, title);
        fill(inv, cfg);
        ItemStack icon = c.icon.clone();
        ItemMeta im = icon.getItemMeta();
        if (im != null) {
            List<String> lore = im.getLore() == null ? new ArrayList<>() : new ArrayList<>(im.getLore());
            int avail = plugin.data().getAvailable(p.getUniqueId(), p.getName(), c.id);
            lore.add("&8Открытий: &e" + (avail == Integer.MAX_VALUE ? "∞" : avail));
            im.setLore(colorizeList(lore));
            im.getPersistentDataContainer().set(caseKey, org.bukkit.persistence.PersistentDataType.STRING, c.id);
            icon.setItemMeta(im);
        }
        inv.setItem(4, icon);

        if (c.showContents) {
            int slot = 19;
            long rSum = c.totalRarityWeight();
            for (Rarity r : c.rarities.values()) {
                if (slot > 25) break;
                ItemStack ri = r.display.clone();
                ItemMeta rm = ri.getItemMeta();
                if (rm != null) {
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Предметов: &f" + c.itemsOf(r.id).size());
                    lore.add("&7Шанс редкости: &e" + String.format(Locale.US, "%.1f%%", rSum > 0 ? 100.0 * r.weight / rSum : 0));
                    for (ItemDef it : c.itemsOf(r.id))
                        lore.add("  &8- " + plainName(it.icon) + " &7(" + String.format(Locale.US, "%.2f%%", c.chanceOf(it)) + ")");
                    rm.setLore(colorizeList(lore));
                    ri.setItemMeta(rm);
                }
                inv.setItem(slot++, ri);
            }
        }
        // Open button
        ItemStack open = new ItemStack(Material.LIME_DYE);
        ItemMeta om = open.getItemMeta();
        if (om != null) {
            om.setDisplayName("&a&lОткрыть кейс");
            om.setLore(colorizeList(List.of("&7Кликните, чтобы открыть", "&7этот кейс.")));
            om.getPersistentDataContainer().set(caseKey, org.bukkit.persistence.PersistentDataType.STRING, c.id);
            open.setItemMeta(om);
        }
        inv.setItem(22, open);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------
    // Flat GUI roulette (fallback animation)
    // ------------------------------------------------------------------
    /**
     * Flat 27-slot roulette GUI: the middle row (slots 10..18) scrolls through
     * {@code strip}; the winning item ends centered at slot 14. On finish {@code onEnd} runs.
     */
    public void startRoulette(Player p, List<ItemStack> strip, int winnerSlot, Runnable onEnd) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(View.ROULETTE), 27,
                CaseSystem.Messages.color(plugin.getConfig().getString("gui.roulette-title", "&8Открытие&7...")));
        for (int i = 0; i < 9; i++) inv.setItem(10 + i, strip.get(i % strip.size()));
        inv.setItem(14, strip.get(winnerSlot % strip.size()));
        rouletteWinnerSlot.put(inv, winnerSlot % strip.size());
        rouletteTicks.put(inv, 0);
        rouletteFinish.put(inv, () -> { cleanupRoulette(inv); onEnd.run(); });
        p.openInventory(inv);
        Sound tick = sound(plugin.getConfig().getString("animation.tick-sound", "BLOCK_NOTE_BLOCK_PLING"), Sound.BLOCK_NOTE_BLOCK_PLING);
        int task = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            Integer t = rouletteTicks.get(inv);
            if (t == null) return;
            t++;
            rouletteTicks.put(inv, t);
            int win = rouletteWinnerSlot.get(inv);
            for (int i = 0; i < 9; i++) {
                int idx = Math.floorMod(t * 3 + i - 4, strip.size());
                inv.setItem(10 + i, strip.get(idx));
            }
            if (t >= 20) {
                for (int i = 0; i < 9; i++) inv.setItem(10 + i, strip.get(Math.floorMod(win + i - 4, strip.size())));
                Bukkit.getScheduler().cancelTask(currentTaskId(inv));
                Runnable end = rouletteFinish.get(inv);
                if (end != null) Bukkit.getScheduler().runTaskLater(plugin, end, 10);
            } else {
                Player pl = p.getPlayer();
                if (pl != null) pl.playSound(pl.getLocation(), tick, 0.6f, 1.0f + (t % 3) * 0.1f);
            }
        }, 1L, 1L);
        taskIds.put(inv, task);
    }

    private final Map<Inventory, Integer> taskIds = new LinkedHashMap<>();
    private int currentTaskId(Inventory inv) { return taskIds.getOrDefault(inv, -1); }

    private void cleanupRoulette(Inventory inv) {
        Integer t = taskIds.remove(inv);
        if (t != null) Bukkit.getScheduler().cancelTask(t);
        rouletteTicks.remove(inv);
        rouletteWinnerSlot.remove(inv); rouletteFinish.remove(inv);
    }

    // ------------------------------------------------------------------
    // Click handling
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder gh)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String caseId = clicked.getItemMeta().getPersistentDataContainer()
                .getOrDefault(caseKey, org.bukkit.persistence.PersistentDataType.STRING, null);
        switch (gh.view) {
            case MAIN -> {
                if (caseId != null) {
                    CaseDef c = cases.get(caseId);
                    if (c != null) openDetailGui(p, c);
                }
            }
            case DETAIL -> {
                if (caseId != null && e.getRawSlot() == 22) {
                    CaseDef c = cases.get(caseId);
                    if (c != null) beginOpen(p, c);
                }
            }
            case ROULETTE -> { /* clicks blocked during animation */ }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        cleanupRoulette(e.getInventory());
    }

    /** Entry point for an actual open attempt (permission + counter check). */
    public void beginOpen(Player p, CaseDef c) {
        if (!plugin.anim().canStart(p)) { p.sendMessage(CaseSystem.Messages.msg("opening-busy")); return; }
        if (!p.hasPermission(DataManager.PERM_BASE + c.id)) { p.sendMessage(CaseSystem.Messages.msg("no-permission")); return; }
        if (!plugin.data().consume(p.getUniqueId(), p.getName(), c.id)) {
            p.sendMessage(CaseSystem.Messages.get("no-rights").replace("%case%", c.name));
            return;
        }
        plugin.anim().startOpening(p, c, () -> openDetailGui(p, c));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private void fill(Inventory inv, FileConfiguration cfg) {
        Material filler = matchMaterial(cfg.getString("gui.filler", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack f = new ItemStack(filler);
        ItemMeta fm = f.getItemMeta();
        if (fm != null) { fm.setDisplayName("&r"); f.setItemMeta(fm); }
        f = colorizeItem(f);
        for (int i = 0; i < inv.getSize(); i++) {
            int col = i % 9, row = i / 9;
            if (col == 0 || col == 8 || row == 0 || row == inv.getSize() / 9 - 1) inv.setItem(i, f);
        }
    }

    private ItemStack colorizeItem(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.setDisplayName(CaseSystem.Messages.color(m.getDisplayName() == null ? "" : m.getDisplayName())); it.setItemMeta(m); }
        return it;
    }

    static Sound sound(String name, Sound fallback) {
        try { return Sound.valueOf(name.toUpperCase(Locale.ROOT)); } catch (Throwable t) { return fallback; }
    }

    private String plainName(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return it.getItemMeta().getDisplayName();
        return it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String stripColors(String s) {
        return org.bukkit.ChatColor.stripColor(s == null ? "" : s);
    }

    /** Inventory holder tagging GUI views. */
    public static final class GuiHolder implements InventoryHolder {
        public final View view;
        GuiHolder(View v) { this.view = v; }
        @Override public Inventory getInventory() { return null; }
    }
}
