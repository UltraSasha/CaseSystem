package dev.casesystem;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
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

        if (mode == Mode.GUI_ROULETTE) {
            runGuiPhase(p, strip1, phase1, tick, () ->
                plugin.cases().startRoulette(p, strip2, indexIn(strip2, item.icon), () -> {
                    if (p.isOnline()) {
                        p.playSound(p.getLocation(), fin, 1f, 1f);
                        deliver(p, c, rarity, item);
                    }
                    finish(p, onFinish);
                }));
            return;
        }

        // ---- 3D path (native or protocol) ----
        Anchor anchor = createAnchor(p, height);
        if (anchor == null) { // adapter failed -> flat fallback for this session
            runGuiPhase(p, strip1, phase1, tick, () ->
                plugin.cases().startRoulette(p, strip2, indexIn(strip2, item.icon), () -> {
                    if (p.isOnline()) { deliver(p, c, rarity, item); }
                    finish(p, onFinish);
                }));
            return;
        }
        runReel3D(p, anchor, strip1, phase1, tick, part, () ->
            runReel3D(p, anchor, strip2, phase2, tick, part, () -> {
                destroyAnchor(anchor);
                if (p.isOnline()) {
                    p.playSound(p.getLocation(), fin, 1f, 1f);
                    if (part != null)
                        p.spawnParticle(part, p.getEyeLocation().add(0, height, 0), 40, 0.3, 0.3, 0.3, 0.15);
                    deliver(p, c, rarity, item);
                }
                finish(p, onFinish);
            }));
    }

    private void runGuiPhase(Player p, List<ItemStack> strip, int ticks, Sound tick, Runnable end) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(new CaseManager.GuiHolder(CaseManager.View.ROULETTE), 27,
                CaseSystem.Messages.color(plugin.getConfig().getString("gui.roulette-title", "&8Открытие&7...")));
        for (int i = 0; i < 9; i++) inv.setItem(10 + i, strip.get(i % strip.size()));
        p.openInventory(inv);
        final int[] t = {0};
        final int[] task = new int[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            for (int i = 0; i < 9; i++) inv.setItem(10 + i, strip.get(Math.floorMod(t[0] * 2 + i - 4, strip.size())));
            p.playSound(p.getLocation(), tick, 0.5f, 1f + (t[0] % 4) * 0.1f);
            if (t[0] >= ticks) {
                Bukkit.getScheduler().cancelTask(task[0]);
                end.run();
            }
        }, 1L, 1L).getTaskId();
    }

    private void runReel3D(Player p, Anchor anchor, List<ItemStack> strip, int ticks, Sound tick, Particle part, Runnable end) {
        final int[] t = {0};
        final int[] holder = new int[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            showAt(anchor, strip.get(Math.floorMod(t[0] * 2, strip.size())));
            if (p.isOnline()) {
                p.playSound(p.getLocation(), tick, 0.5f, 1f + (t[0] % 4) * 0.1f);
                if (part != null && t[0] % 4 == 0)
                    p.spawnParticle(part, p.getEyeLocation().add(0, plugin.getConfig().getDouble("animation.display-height", 1.8), 0),
                            4, 0.2, 0.2, 0.2, 0.05);
            }
            if (t[0] >= ticks) {
                Bukkit.getScheduler().cancelTask(holder[0]);
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

    public void shutdown() {
        for (Entity e : new ArrayList<>(liveEntities)) {
            try { e.remove(); } catch (Throwable ignored) {}
        }
        liveEntities.clear();
        busy.clear();
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
                int fakeId = nextFakeId();
                a.displayRef = fakeId;
                a.anchorLoc = at.clone();
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
                    if (a.anchorLoc == null || a.anchorLoc.distanceSquared(cur) > 0.01) {
                        a.anchorLoc = cur;
                        sendFakeTeleport(pl, (Integer) a.displayRef, cur); // invisible-anchor follow
                    }
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

        private int fakeCounter = -2_000_000;
        private final int itemTypeVarInt = resolveItemDisplayTypeId();

        private int nextFakeId() { return fakeCounter--; }

        /** Registry id of minecraft:item_display on 1.19.2 (absent from the EntityType enum). */
        private int resolveItemDisplayTypeId() {
            String[] keyClasses = {"net.minecraft.resources.MinecraftKey", "net.minecraft.resources.ResourceLocation"};
            for (String kc : keyClasses) {
                try {
                    Class<?> regCls = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                    Object reg = regCls.getField("ENTITY_TYPE").get(null);
                    Class<?> keyCls = Class.forName(kc);
                    Object key = keyCls.getConstructor(String.class).newInstance("minecraft:item_display");
                    Object id = reg.getClass().getMethod("getId", keyCls).invoke(reg, key);
                    if (id instanceof Number n) return n.intValue();
                } catch (Throwable ignored) {}
            }
            return 3; // best-effort default for the 1.19.x protocol registry space
        }

        void spawn(Player p, int entityId, Location at) {
            if (!protocolLibPresent) return;
            try {
                ProtocolManager pm = ProtocolLibrary.getProtocolManager();
                com.comphenix.protocol.events.PacketContainer spawn = pm.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
                // 1.19 packet fields: id(VarInt), uuid, type(VarInt), x,y,z(double),
                // pitch(byte), yaw(byte), data(int), velocity(vector)
                spawn.getIntegers().write(0, entityId);
                spawn.getUUIDs().write(0, UUID.randomUUID());
                spawn.getDoubles().write(0, at.getX()).write(1, at.getY()).write(2, at.getZ());
                writeEntityTypeVarInt(spawn.getHandle(), itemTypeVarInt);
                pm.sendServerPacket(p, spawn);

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
                ProtocolManager pm = ProtocolLibrary.getProtocolManager();
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
                ProtocolManager pm = ProtocolLibrary.getProtocolManager();
                com.comphenix.protocol.events.PacketContainer tele = pm.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
                tele.getIntegers().write(0, entityId);
                tele.getDoubles().write(0, at.getX()).write(1, at.getY()).write(2, at.getZ());
                pm.sendServerPacket(p, tele);
            } catch (Throwable ignored) {}
        }

        void remove(UUID ownerId, int entityId) {
            Player p = Bukkit.getPlayer(ownerId);
            if (p == null || !protocolLibPresent) return;
            try {
                ProtocolManager pm = ProtocolLibrary.getProtocolManager();
                com.comphenix.protocol.events.PacketContainer rem = pm.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                boolean sent = false;
                // Modern form: NMS IntList field.
                for (String cls : new String[]{"net.minecraft.util.IntList", "net.minecraft.nbt.NBTProcessReadFinalizeTag"}) {
                    try {
                        Class<?> intList = Class.forName(cls);
                        if (!intList.getSimpleName().equals("IntList")) continue;
                        Object list = intList.getConstructor(int[].class).newInstance((Object) new int[]{entityId});
                        setFieldByType(rem.getHandle(), list.getClass(), list);
                        sent = true; break;
                    } catch (Throwable ignored) {}
                }
                if (!sent) rem.getIntegerArrays().write(0, new int[]{entityId}); // legacy fallback
                pm.sendServerPacket(p, rem);
            } catch (Throwable ignored) {}
        }

        // -- low-level reflection helpers ---------------------------------

        /** Writes the entity-type VarInt field of SPAWN_ENTITY (field order differs per mapping). */
        private void writeEntityTypeVarInt(Object packet, int typeId) throws Throwable {
            java.lang.reflect.Field[] fs = packet.getClass().getDeclaredFields();
            int intSeen = 0;
            for (java.lang.reflect.Field f : fs) {
                if (f.getType() != int.class) continue;
                // On 1.19.2 the layout is: [0]=entityId, [1]=entityType, then data/velocity ints.
                if (intSeen == 1) { f.setAccessible(true); f.setInt(packet, typeId); return; }
                intSeen++;
            }
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

        private void sendFlagsMetadata(ProtocolManager pm, Player p, int entityId, byte flags) throws Throwable {
            Object byteSer = findSerializer("byte");
            sendMetadataItems(pm, p, entityId, new Object[]{dataWatcherItem(0, byteSer, flags)});
        }

        private void sendMetadataItems(ProtocolManager pm, Player p, int entityId, Object[] items) {
            try {
                com.comphenix.protocol.events.PacketContainer meta = pm.createPacket(PacketType.Play.Server.ENTITY_METADATA);
                meta.getIntegers().write(0, entityId);
                Object handle = meta.getHandle();
                Object arr = Array.newInstance(items.getClass().getComponentType(), items.length);
                System.arraycopy(items, 0, arr, 0, items.length);
                setFieldByType(handle, arr.getClass(), arr);
                pm.sendServerPacket(p, meta);
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
