package dev.casesystem;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Two-phase opening animation with a version-aware 3D display adapter:
 *  - Phase 1: reel of rarity icons (weighted random by rarity weight picks the rarity).
 *  - Phase 2: reel of items ONLY inside the chosen rarity (weighted item roll).
 *
 * Display modes (config animation.type = auto | 3d | gui):
 *  - NATIVE_3D   : native Paper ItemDisplay entity (server >= 1.19.4, incl. 1.21.1).
 *                  The class org.bukkit.entity.ItemDisplay does NOT exist in the 1.19.2
 *                  compile API, so everything is done via reflection -> single JAR.
 *  - PROTO_3D    : fake "item_display" entity sent through ProtocolLib packets
 *                  (SPAWN_ENTITY + ENTITY_METADATA) on 1.19.2. The invisible anchor is
 *                  implemented as a server-side teleport-follow: every tick we re-send
 *                  ENTITY_TELEPORT glued to the player's eyes, so no real ArmorStand /
 *                  Marker entity ever exists.
 *  - GUI_ROULETTE: flat inventory roulette fallback (1.19.2 without ProtocolLib, or type=gui).
 *
 * Sounds and particles are played on both phases.
 */
public final class AnimationManager {

    public enum Mode { NATIVE_3D, PROTO_3D, GUI_ROULETTE }

    private final CaseSystem plugin;
    private final Mode mode;
    private final boolean protocolLibPresent;
    private final List<UUID> busy = new ArrayList<>();          // players mid-animation
    private final List<Entity> liveEntities = new ArrayList<>(); // native displays for cleanup
    private final Method spawnMethod;                            // World.spawn(EntityType, Location, Consumer)

    public AnimationManager(CaseSystem plugin) {
        this.plugin = plugin;
        this.protocolLibPresent = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        this.mode = resolveMode(plugin);
        Method m = null;
        try {
            m = org.bukkit.World.class.getMethod("spawn", org.bukkit.entity.EntityType.class,
                    Location.class, java.util.function.Consumer.class);
        } catch (NoSuchMethodException ignored) {}
        this.spawnMethod = m;
        plugin.getLogger().info("[CaseSystem] animation mode: " + mode);
    }

    private Mode resolveMode(CaseSystem plugin) {
        String type = plugin.getConfig().getString("animation.type", "auto").toLowerCase(Locale.ROOT);
        if ("gui".equals(type)) return Mode.GUI_ROULETTE;
        if (hasNativeItemDisplay()) return Mode.NATIVE_3D;         // 1.19.4+ / 1.20 / 1.21
        // 1.19.2 / 1.19.3: fake packets need ProtocolLib, otherwise flat GUI roulette.
        if (protocolLibPresent) return Mode.PROTO_3D;
        return Mode.GUI_ROULETTE;
    }

    /** True when the running server knows ITEM_DISPLAY (Paper >= 1.19.4). */
    private boolean hasNativeItemDisplay() {
        try { org.bukkit.entity.EntityType.valueOf("ITEM_DISPLAY"); return true; }
        catch (Throwable t) { return false; }
    }

    public String currentMode() {
        return mode.name() + " (type=" + plugin.getConfig().getString("animation.type", "auto") + ")";
    }

    public boolean canStart(Player p) { return !busy.contains(p.getUniqueId()); }

    // ------------------------------------------------------------------
    // Opening flow (two phases)
    // ------------------------------------------------------------------
    public void startOpening(Player p, CaseManager.CaseDef c, Runnable onFinish) {
        busy.add(p.getUniqueId());
        var cfg = plugin.getConfig();
        Sound tick = CaseManager.sound(cfg.getString("animation.tick-sound", "BLOCK_NOTE_BLOCK_PLING"), Sound.BLOCK_NOTE_BLOCK_PLING);
        Sound fin  = CaseManager.sound(cfg.getString("animation.final-sound", "ENTITY_PLAYER_LEVELUP"), Sound.ENTITY_PLAYER_LEVELUP);
        Particle part = particle(cfg.getString("animation.particle", "VILLAGER_HAPPY"));
        double height = cfg.getDouble("animation.display-height", 1.8);

        // Roll results FIRST: weighted rarity, then weighted item within that rarity.
        CaseManager.Rarity rarity = weightedRarity(c);
        if (rarity == null) { finish(p, onFinish); return; }
        CaseManager.ItemDef item = weightedItem(c, rarity.id);
        if (item == null) { finish(p, onFinish); return; }

        List<ItemStack> strip1 = new ArrayList<>();
        for (CaseManager.Rarity r : c.rarities.values()) strip1.add(r.display);
        List<ItemStack> strip2 = new ArrayList<>();
        for (CaseManager.ItemDef it : c.itemsOf(rarity.id)) strip2.add(it.icon);

        int phase1 = Math.max(5, cfg.getInt("animation.phase1-ticks", 40));
        int phase2 = Math.max(5, cfg.getInt("animation.phase2-ticks", 60));
        // Configurable delays (ticks): rarity->items pause, final display time before reward/GUI.
        int betweenDelay = Math.max(0, cfg.getInt("animation.between-phases-delay-ticks", 10));
        int finishDelay  = Math.max(0, cfg.getInt("animation.finish-delay-ticks", 20));
        int guiStartDelay = Math.max(0, cfg.getInt("animation.gui-start-delay-ticks", 2));

        if (mode == Mode.GUI_ROULETTE) {
            runGuiPhase(p, strip1, phase1, guiStartDelay, tick, () ->
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.cases().startRoulette(p, strip2, indexIn(strip2, item.icon), () -> {
                        if (p.isOnline()) {
                            p.playSound(p.getLocation(), fin, 1f, 1f);
                            deliver(p, c, rarity, item);
                        }
                        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(p, onFinish), finishDelay);
                    }), betweenDelay));
            return;
        }

        // ---- 3D path (native or protocol) ----
        Anchor anchor = createAnchor(p, height);
        if (anchor == null) { // adapter failed -> flat fallback for this session
            runGuiPhase(p, strip1, phase1, guiStartDelay, tick, () ->
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.cases().startRoulette(p, strip2, indexIn(strip2, item.icon), () -> {
                        if (p.isOnline()) { deliver(p, c, rarity, item); }
                        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(p, onFinish), finishDelay);
                    }), betweenDelay));
            return;
        }
        showAt(anchor, strip1.get(0)); // show the reel immediately at animation start
        runReel3D(p, anchor, strip1, phase1, tick, part, indexOfRarity(c, rarity, strip1), () ->
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                runReel3D(p, anchor, strip2, phase2, tick, part, indexIn(strip2, item.icon), () -> {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        destroyAnchor(anchor);
                        if (p.isOnline()) {
                            p.playSound(p.getLocation(), fin, 1f, 1f);
                            if (part != null)
                                p.spawnParticle(part, p.getEyeLocation().add(0, height, 0), 40, 0.3, 0.3, 0.3, 0.15);
                            deliver(p, c, rarity, item);
                        }
                        finish(p, onFinish);
                    }, finishDelay);
                }), betweenDelay));
    }

    private void runGuiPhase(Player p, List<ItemStack> strip, int ticks, int startDelay, Sound tick, Runnable end) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(new CaseManager.GuiHolder(CaseManager.View.ROULETTE), 27,
                CaseSystem.Messages.color(plugin.getConfig().getString("gui.roulette-title", "&8Открытие&7...")));
        for (int i = 0; i < 9; i++) inv.setItem(10 + i, strip.get(i % strip.size()));
        p.openInventory(inv);
        final int[] t = {0};
        final int[] task = new int[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            double frac = Math.min(1.0, (double) t[0] / ticks);
            int speed = 2 + (int) ((1.0 - frac) * 4); // fast start, decelerating to 2/tick
            for (int i = 0; i < 9; i++) inv.setItem(10 + i, strip.get(Math.floorMod(t[0] * speed + i - 4, strip.size())));
            p.playSound(p.getLocation(), tick, 0.5f, 1f + (float) frac * 0.6f);
            if (t[0] >= ticks) {
                Bukkit.getScheduler().cancelTask(task[0]);
                end.run();
            }
        }, (long) startDelay, 1L).getTaskId();
    }

    /**
     * Smooth 3D reel: position advances along an eased curve (fast start -> slow stop)
     * and lands EXACTLY on stopIndex on the last tick, so the animation "brakes" onto
     * the winning icon instead of flickering two icons per tick.
     */
    private void runReel3D(Player p, Anchor anchor, List<ItemStack> strip, int ticks,
                            Sound tick, Particle part, int stopIndex, Runnable end) {
        final int size = Math.max(1, strip.size());
        final double totalSteps = Math.max(size, ticks * 0.9);
        final double target = stopIndex + totalSteps;
        final int[] t = {0};
        final int[] holder = new int[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            double frac = Math.min(1.0, (double) t[0] / ticks);
            double pos = target * (1.0 - Math.pow(1.0 - frac, 2)); // ease-out quadratic
            int idx = (int) Math.floor(pos) % size;
            showAt(anchor, strip.get(idx));
            if (p.isOnline()) {
                float pitch = 1f + (float) frac * 0.6f;
                p.playSound(p.getLocation(), tick, 0.5f, pitch);
                if (part != null && t[0] % 4 == 0)
                    p.spawnParticle(part, p.getEyeLocation().add(0, plugin.getConfig().getDouble("animation.display-height", 1.8), 0),
                            4, 0.2, 0.2, 0.2, 0.05);
            }
            if (t[0] >= ticks) {
                Bukkit.getScheduler().cancelTask(holder[0]);
                showAt(anchor, strip.get(Math.floorMod(stopIndex, size))); // land on the winner
                end.run();
            }
        }, 1L, 1L).getTaskId();
    }

    private void deliver(Player p, CaseManager.CaseDef c, CaseManager.Rarity r, CaseManager.ItemDef item) {
        plugin.rewards().deliver(p, c, r, item);
    }

    private void finish(Player p, Runnable onFinish) {
        busy.remove(p.getUniqueId());
        if (onFinish != null) Bukkit.getScheduler().runTask(plugin, onFinish);
    }

    /** Fake entity ids currently alive per player (cleanup on quit/disable). */
    private final java.util.Map<UUID, List<Integer>> liveFakes = new java.util.concurrent.ConcurrentHashMap<>();

    public void shutdown() {
        for (Entity e : new ArrayList<>(liveEntities)) {
            try { e.remove(); } catch (Throwable ignored) {}
        }
        liveEntities.clear();
        for (java.util.Map.Entry<UUID, List<Integer>> en : liveFakes.entrySet())
            for (Integer id : en.getValue()) sendFakeRemove(en.getKey(), id);
        liveFakes.clear();
        busy.clear();
    }

    /** Removes any leftover fake displays of a quitting player (called from CaseSystem listener). */
    public void cleanupPlayer(UUID id) {
        List<Integer> ids = liveFakes.remove(id);
        if (ids != null) for (Integer eid : ids) sendFakeRemove(id, eid);
    }

    // ------------------------------------------------------------------
    // Weighted random helpers
    // ------------------------------------------------------------------
    private CaseManager.Rarity weightedRarity(CaseManager.CaseDef c) {
        long total = c.totalRarityWeight();
        if (total <= 0) return null;
        long roll = java.util.concurrent.ThreadLocalRandom.current().nextLong(total);
        for (CaseManager.Rarity r : c.rarities.values()) {
            roll -= Math.max(0, r.weight);
            if (roll < 0) return r;
        }
        return null;
    }

    private CaseManager.ItemDef weightedItem(CaseManager.CaseDef c, String rarityId) {
        long total = c.totalItemWeight(rarityId);
        if (total <= 0) return null;
        long roll = java.util.concurrent.ThreadLocalRandom.current().nextLong(total);
        for (CaseManager.ItemDef it : c.itemsOf(rarityId)) {
            roll -= Math.max(0, it.weight);
            if (roll < 0) return it;
        }
        return null;
    }

    private int indexOfRarity(CaseManager.CaseDef c, CaseManager.Rarity r, List<ItemStack> strip) {
        int i = 0;
        for (CaseManager.Rarity x : c.rarities.values()) {
            if (x.id.equals(r.id)) return Math.floorMod(i, Math.max(1, strip.size()));
            i++;
        }
        return 0;
    }

    private int indexIn(List<ItemStack> strip, ItemStack icon) {
        for (int i = 0; i < strip.size(); i++) if (strip.get(i).isSimilar(icon)) return i;
        return 0;
    }

    // ------------------------------------------------------------------
    // Versioned display adapter (nested class #1)
    // ------------------------------------------------------------------
    /** Floating item display: native entity (NATIVE_3D) or pure fake id (PROTO_3D). */
    private static final class Anchor {
        Object displayRef;   // native ItemDisplay Entity OR Integer fake entity id
        Location anchorLoc;  // last known position of the fake display
        UUID owner;
    }

    private Anchor createAnchor(Player p, double height) {
        Anchor a = new Anchor();
        a.owner = p.getUniqueId();
        Location at = p.getEyeLocation().add(0, height, 0);
        try {
            if (mode == Mode.PROTO_3D) {
                if (!proto.available())
                    throw new IllegalStateException("item_display protocol id unresolved on this server");
                int fakeId = nextFakeId();
                a.displayRef = fakeId;
                a.anchorLoc = at.clone();
                liveFakes.computeIfAbsent(p.getUniqueId(), k2 -> new ArrayList<>()).add(fakeId);
                sendFakeSpawn(p, fakeId, at);
            } else {
                // NATIVE_3D (>= 1.19.4): reflective spawn of ITEM_DISPLAY.
                Object spawned = spawnMethod.invoke(p.getWorld(),
                        org.bukkit.entity.EntityType.valueOf("ITEM_DISPLAY"), at.clone(),
                        (java.util.function.Consumer<Object>) ent -> { /* defaults are fine */ });
                if (!(spawned instanceof Entity ent)) throw new IllegalStateException("spawn returned non-entity");
                applyBillboard(ent);
                a.displayRef = ent;
                liveEntities.add(ent);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("3D display creation failed (" + t.getMessage() + ") - using GUI roulette for this open.");
            return null;
        }
        return a;
    }

    /** Reflective Billboard.CENTER (enum also absent from the 1.19.2 compile API). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyBillboard(Entity display) {
        try {
            Class<?> bb = Class.forName("org.bukkit.entity.Billboard");
            Object center = Enum.valueOf((Class<Enum>) bb.asSubclass(Enum.class), "CENTER");
            display.getClass().getMethod("setBillboard", bb).invoke(display, center);
        } catch (Throwable ignored) {}
    }

    private void showAt(Anchor a, ItemStack stack) {
        if (a == null) return;
        try {
            if (mode == Mode.PROTO_3D) {
                Player pl = Bukkit.getPlayer(a.owner);
                if (pl != null) {
                    Location cur = pl.getEyeLocation()
                            .add(0, plugin.getConfig().getDouble("animation.display-height", 1.8), 0);
                    a.anchorLoc = cur;
                    sendFakeTeleport(pl, (Integer) a.displayRef, cur); // invisible-anchor follow (every tick -> smooth)
                }
                sendFakeItemSync(a.owner, (Integer) a.displayRef, stack);
            } else if (a.displayRef instanceof Entity ent) {
                ent.getClass().getMethod("setItemStack", ItemStack.class).invoke(ent, stack);
            }
        } catch (Throwable ignored) { /* keep the animation alive on transient errors */ }
    }

    private void destroyAnchor(Anchor a) {
        if (a == null) return;
        try {
            if (mode == Mode.PROTO_3D) {
                sendFakeRemove(a.owner, (Integer) a.displayRef);
                List<Integer> l = liveFakes.get(a.owner);
                if (l != null) l.remove((Integer) a.displayRef);
            } else if (a.displayRef instanceof Entity ent) {
                ent.remove();
                liveEntities.remove(ent);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------
    // ProtocolLib fake ItemDisplay sender (nested class #2)
    // ------------------------------------------------------------------
    /**
     * All ProtocolLib/NMS interaction is isolated here and wrapped in reflection +
     * try/catch, so a missing or incompatible ProtocolLib can never crash the plugin.
     */
    private final class ProtoSender {

        private final java.util.concurrent.atomic.AtomicInteger fakeCounter =
                new java.util.concurrent.atomic.AtomicInteger(500_000_000);

        private int nextFakeId() { return fakeCounter.getAndAdd(7); }

        /**
         * Protocol entity-type VarInt of minecraft:item_display.
         * IMPORTANT: this is the PROTOCOL id (registry order of vanilla types), NOT the
         * runtime BuiltInRegistries id - and item_display does not exist in 1.19.2 at all.
         * The value is resolved dynamically by looking up a KNOWN type (zombified_piglin)
         * in the live registry and comparing against the known protocol table; the result
         * is cached per server build. Fallback = 111 (item_display in 1.19.4+ registries).
         */
        private final int itemTypeVarInt = resolveItemDisplayTypeId();

        private int resolveItemDisplayTypeId() {
            try {
                Class<?> regCls = null;
                for (String c : new String[]{"net.minecraft.core.registries.BuiltInRegistries",
                                             "net.minecraft.core.IRegistryCustom"}) {
                    try { regCls = Class.forName(c); break; } catch (Throwable ignored) {}
                }
                if (regCls != null) {
                    Object reg = regCls.getField("ENTITY_TYPE").get(null);
                    Class<?> mcKey = null;
                    for (String c : new String[]{"net.minecraft.resources.MinecraftKey",
                                                 "net.minecraft.resources.ResourceLocation"}) {
                        try { mcKey = Class.forName(c); break; } catch (Throwable ignored) {}
                    }
                    if (mcKey != null) {
                        Object key = mcKey.getConstructor(String.class).newInstance("minecraft:zombified_piglin");
                        Object idObj = reg.getClass().getMethod("getId", mcKey).invoke(reg, key);
                        if (idObj instanceof Number n) {
                            int runtimeZombie = n.intValue();
                            // zombified_piglin is protocol id 15 on every 1.19.x version.
                            int offset = 15 - runtimeZombie;
                            int display = 111 + offset; // item_display protocol id on 1.19.4 == 111
                            if (display > 0 && display < 1000) return display;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // Do NOT guess a registry number: an unknown id spawns a random vanilla mob
            // (e.g. piglin). Returning -1 disables PROTO_3D -> clean GUI-roulette fallback.
            return -1;
        }

        /** True only when the item_display protocol id was resolved for this server. */
        boolean available() { return itemTypeVarInt > 0; }

        void spawn(Player p, int entityId, Location at) {
            if (!protocolLibPresent || !available()) return;
            try {
                Object pm = protocolManager();
                if (pm == null) return;
                Object spawn = createPacket(pm, "Play.Server.SPAWN_ENTITY");
                // 1.19.2 packet layout: entityId(int), UUID, entityType(VarInt), x,y,z(double),
                // pitch/yaw(byte angles), data(int), velocityX/Y/Z(int), removed(boolean).
                // ProtocolLib has no setter for the type VarInt -> write it via reflection.
                invokeWrite(packetIntegers(spawn), 0, entityId);
                invokeWrite(packetUUIDs(spawn), 0, UUID.randomUUID());
                Object doubles = packetField(spawn, "getDoubles");
                invokeWrite(doubles, 0, at.getX());
                invokeWrite(doubles, 1, at.getY());
                invokeWrite(doubles, 2, at.getZ());
                writeEntityTypeVarInt(packetHandle(spawn), itemTypeVarInt);
                sendPacket(pm, p, spawn);

                // Invisible flag (byte metadata index 0 = 0x20): the client only sees the
                // rendered item - this is our "invisible anchor" concept.
                sendFlagsMetadata(pm, p, entityId, (byte) 0x20);
            } catch (Throwable t) {
                plugin.getLogger().warning("ProtocolLib fake spawn failed: " + t.getMessage());
            }
        }

        void syncItem(UUID ownerId, int entityId, ItemStack stack) {
            Player p = Bukkit.getPlayer(ownerId);
            if (p == null || !protocolLibPresent) return;
            try {
                Object pm = protocolManager();
                if (pm == null) return;
                Object nms = craftItemStack(stack);                       // net.minecraft.world.item.ItemStack
                Object optional = java.util.Optional.of(nms);
                Object serializer = findSerializer("itemstack");          // Optional<ItemStack> serializer
                sendMetadataItems(pm, p, entityId,
                        new Object[]{dataWatcherItem(8, serializer, optional)}); // index 8 = ItemDisplay.item
            } catch (Throwable ignored) {}
        }

        void teleport(Player p, int entityId, Location at) {
            if (!protocolLibPresent) return;
            try {
                Object pm = protocolManager();
                if (pm == null) return;
                Object tele = createPacket(pm, "Play.Server.ENTITY_TELEPORT");
                invokeWrite(packetIntegers(tele), 0, entityId);
                Object doubles = packetField(tele, "getDoubles");
                invokeWrite(doubles, 0, at.getX());
                invokeWrite(doubles, 1, at.getY());
                invokeWrite(doubles, 2, at.getZ());
                sendPacket(pm, p, tele);
            } catch (Throwable ignored) {}
        }

        void remove(UUID ownerId, int entityId) {
            Player p = Bukkit.getPlayer(ownerId);
            if (p == null || !protocolLibPresent) return;
            try {
                Object pm = protocolManager();
                if (pm == null) return;
                Object rem = createPacket(pm, "Play.Server.ENTITY_DESTROY");
                boolean sent = false;
                // Modern form: NMS IntList field.
                for (String cls : new String[]{"net.minecraft.util.IntList", "net.minecraft.nbt.NBTProcessReadFinalizeTag"}) {
                    try {
                        Class<?> intList = Class.forName(cls);
                        if (!intList.getSimpleName().equals("IntList")) continue;
                        Object list = intList.getConstructor(int[].class).newInstance((Object) new int[]{entityId});
                        setFieldByType(packetHandle(rem), list.getClass(), list);
                        sent = true; break;
                    } catch (Throwable ignored) {}
                }
                if (!sent) {
                    Object intArrays = packetField(rem, "getIntegerArrays");
                    invokeWrite(intArrays, 0, new int[]{entityId}); // legacy fallback
                }
                sendPacket(pm, p, rem);
            } catch (Throwable ignored) {}
        }

        // -- ProtocolLib access via REFLECTION ONLY -------------------------
        // All com.comphenix.* references are string-based so that AnimationManager
        // verifies/loads even when ProtocolLib is absent (NoClassDefFoundError-safe).

        private static Object protocolManager() {
            try {
                Class<?> lib = Class.forName("com.comphenix.protocol.ProtocolLibrary");
                return lib.getMethod("getProtocolManager").invoke(null);
            } catch (Throwable t) { return null; }
        }

        /** pm.createPacket(PacketType.Play.Server.<name>) */
        private static Object createPacket(Object pm, String typeName) throws Throwable {
            Class<?> pt = Class.forName("com.comphenix.protocol.PacketType");
            Class<?> play = Class.forName("com.comphenix.protocol.PacketType$Play");
            Class<?> server = Class.forName("com.comphenix.protocol.PacketType$Play$Server");
            Object playEnum = play.getField("PLAY").get(null);
            Object serverEnum = server.getField("SERVER").get(playEnum);
            Object type = server.getField(typeName.substring(typeName.lastIndexOf('.') + 1)).get(serverEnum);
            return server.getMethod("createPacket", pt).invoke(serverEnum, type);
        }

        private static Object packetHandle(Object container) throws Throwable {
            return container.getClass().getMethod("getHandle").invoke(container);
        }

        private static Object packetField(Object container, String getter) throws Throwable {
            return container.getClass().getMethod(getter).invoke(container);
        }

        private static Object packetIntegers(Object container) throws Throwable {
            return packetField(container, "getIntegers");
        }

        private static Object packetUUIDs(Object container) throws Throwable {
            return packetField(container, "getUUIDs");
        }

        /** collection.write(int index, Object value) - StructureModifier polymorphic write. */
        private static void invokeWrite(Object modifier, int index, Object value) throws Throwable {
            for (Method m : modifier.getClass().getMethods()) {
                if (m.getName().equals("write") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == int.class) {
                    Class<?> vt = m.getParameterTypes()[1];
                    if (vt.isInstance(value) || (value != null && vt.isAssignableFrom(value.getClass()))) {
                        m.invoke(modifier, index, value);
                        return;
                    }
                }
            }
            throw new NoSuchMethodException("write(int," + value.getClass().getSimpleName() + ")");
        }

        /** pm.sendServerPacket(Player, PacketContainer) */
        private static void sendPacket(Object pm, Player p, Object container) throws Throwable {
            Class<?> entityCls = Class.forName("org.bukkit.entity.Entity");
            Class<?> containerCls = Class.forName("com.comphenix.protocol.events.PacketContainer");
            Method m = pm.getClass().getMethod("sendServerPacket", entityCls, containerCls);
            m.invoke(pm, p, container);
        }

        // -- low-level reflection helpers ---------------------------------

        /**
         * Writes the entity-type VarInt field of SPAWN_ENTITY.
         * The type field sits BETWEEN entityId(int) and the first position double.
         * Locate it by declaration ORDER (fields between id and x), not by counting
         * ints: on some mappings the previous heuristic hit a velocity/data field and
         * left the real type at default 0 = piglin -> client rendered a ghost piglin.
         */
        private void writeEntityTypeVarInt(Object packet, int typeId) throws Throwable {
            java.lang.reflect.Field[] fs = packet.getClass().getDeclaredFields();
            java.lang.reflect.Field idF = null, firstD = null;
            for (java.lang.reflect.Field f : fs) {
                Class<?> t = f.getType();
                if ((t == int.class || t == Integer.class) && idF == null) idF = f;
                else if ((t == double.class || t == Double.class) && firstD == null) firstD = f;
                if (idF != null && firstD != null) break;
            }
            if (idF == null || firstD == null) throw new IllegalStateException("SPAWN_ENTITY fields not found");
            idF.setAccessible(true); firstD.setAccessible(true);
            java.lang.reflect.Field target = null;
            try {
                long offId = idF.getLong(packet), offD = firstD.getLong(packet);
                if (offD > offId) {
                    for (java.lang.reflect.Field f : fs) {
                        Class<?> t = f.getType();
                        if ((t == int.class || t == Integer.class) && f != idF) {
                            f.setAccessible(true);
                            long off = f.getLong(packet);
                            if (off > offId && off < offD) { target = f; break; }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (target == null) {
                // Fallback: first int field that is NOT the entity id.
                for (java.lang.reflect.Field f : fs) {
                    Class<?> t = f.getType();
                    if ((t == int.class || t == Integer.class) && f != idF) { target = f; break; }
                }
            }
            if (target == null) throw new IllegalStateException("SPAWN_ENTITY type field not found");
            target.setAccessible(true);
            target.setInt(packet, typeId);
        }

        private Object craftItemStack(ItemStack stack) throws Throwable {
            String pkg = Bukkit.getServer().getClass().getPackage().getName(); // org.bukkit.craftbukkit.v1_19_R2
            Class<?> craft = Class.forName(pkg + ".inventory.CraftItemStack");
            return craft.getMethod("asNMSCopy", ItemStack.class).invoke(null, stack);
        }

        private Object findSerializer(String needle) throws Throwable {
            Class<?> serCls = Class.forName("net.minecraft.network.syncher.DataWatcherRegistry");
            for (java.lang.reflect.Field f : serCls.getFields()) {
                if (!f.getType().getName().endsWith("DataWatcherSerializer")) continue;
                Object v = f.get(null);
                if (v != null && v.toString().toLowerCase(Locale.ROOT).contains(needle)) return v;
            }
            throw new IllegalStateException("serializer '" + needle + "' not found");
        }

        private Object dataWatcherItem(int index, Object serializer, Object value) throws Throwable {
            Class<?> itemCls = Class.forName("net.minecraft.network.syncher.DataWatcher$Item");
            Class<?> serIfc = Class.forName("net.minecraft.network.syncher.DataWatcherSerializer");
            return itemCls.getConstructor(int.class, serIfc, Object.class).newInstance(index, serializer, value);
        }

        private void sendFlagsMetadata(Object pm, Player p, int entityId, byte flags) throws Throwable {
            Object byteSer = findSerializer("byte");
            sendMetadataItems(pm, p, entityId, new Object[]{dataWatcherItem(0, byteSer, flags)});
        }

        private void sendMetadataItems(Object pm, Player p, int entityId, Object[] items) {
            try {
                Object meta = createPacket(pm, "Play.Server.ENTITY_METADATA");
                invokeWrite(packetIntegers(meta), 0, entityId);
                Object handle = packetHandle(meta);
                Object arr = Array.newInstance(items.getClass().getComponentType(), items.length);
                System.arraycopy(items, 0, arr, 0, items.length);
                setFieldByType(handle, arr.getClass(), arr);
                sendPacket(pm, p, meta);
            } catch (Throwable ignored) {}
        }

        private void setFieldByType(Object target, Class<?> valueType, Object value) {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().isAssignableFrom(valueType)) {
                        try { f.setAccessible(true); f.set(target, value); return; } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    // Delegating wrappers so the rest of the class stays tidy -----------------
    private final ProtoSender proto = new ProtoSender();
    private int nextFakeId() { return proto.nextFakeId(); }
    private void sendFakeSpawn(Player p, int id, Location at) { proto.spawn(p, id, at); }
    private void sendFakeItemSync(UUID owner, int id, ItemStack stack) { proto.syncItem(owner, id, stack); }
    private void sendFakeTeleport(Player p, int id, Location at) { proto.teleport(p, id, at); }
    private void sendFakeRemove(UUID owner, int id) { proto.remove(owner, id); }

    private Particle particle(String name) {
        try { return Particle.valueOf(name.toUpperCase(Locale.ROOT)); } catch (Throwable t) { return Particle.VILLAGER_HAPPY; }
    }

    /** Helper kept package-visible for tests/debug: current server version string. */
    public String serverVersion() { return Bukkit.getBukkitVersion(); }
}
