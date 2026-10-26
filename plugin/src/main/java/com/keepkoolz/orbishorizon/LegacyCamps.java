package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * backward compatibility of the camp packs. Old tent crates found in a player's inventory on arrival become packs (full crate
 * to pack with its kept contents, empty crate removed). Tents posed before the packs get their placed bag block when their section
 * is active (once per entry and per start), or the owner is told when the cell is taken. Never throws from a tick or an event.
 */
final class LegacyCamps {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long CHECK_INTERVAL_MS = 1000;
    private static final double NOTIFY_RADIUS = 16;

    /** Entries already handled this start (placed, or found with the bag, or blocked). */
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();
    /** Entries whose bag cell is taken, with the owner to tell. */
    private static final Map<String, UUID> BLOCKED = new ConcurrentHashMap<>();
    /** Players already told since they connected. */
    private static final Set<UUID> NOTIFIED = ConcurrentHashMap.newKeySet();
    /** Next time a blocked entry is re-checked (10 s apart). */
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_CHECK_MS = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 10_000;

    private LegacyCamps() {
    }

    /** Pack item id for a full legacy crate, "" for an empty legacy crate (to delete), null for any other item. */
    static String convertedItem(String itemId) {
        if (itemId == null) {
            return null;
        }
        for (Deployables.Kind k : Deployables.TENTS) {
            if (itemId.equals(k.tent.legacyFullCrateId)) {
                CampTiers.Tier t = CampTiers.ofKind(k.id);
                return t != null ? t.packItemId() : null;
            }
            if (itemId.equals(k.tent.legacyEmptyCrateId)) {
                return "";
            }
        }
        return null;
    }

    /** The bag cell can receive the bag when it holds neither a solid block nor a liquid. */
    static boolean bagCellFree(boolean solid, boolean liquid) {
        return !solid && !liquid;
    }

    private static String key(BalloonRegistry.Entry e) {
        return e.world() + ":" + e.x() + "," + e.y() + "," + e.z();
    }

    private static Vector3i bagWorldCell(BalloonRegistry.Entry e, CampTiers.Tier tier) {
        return e.rotation().rotateYaw(CampTiers.bagCell(tier), new Vector3i()).add(new Vector3i(e.x(), e.y(), e.z()));
    }

    /** Converts the legacy crates of a player (Holder at arrival) into packs, same slot, same metadata. Never throws. */
    static void convertInventory(Holder<EntityStore> holder) {
        try {
            for (ItemContainer c : CampUpgrade.containers(holder)) {
                for (short i = 0; i < c.getCapacity(); i++) {
                    ItemStack st = c.getItemStack(i);
                    if (st == null || ItemStack.isEmpty(st)) {
                        continue;
                    }
                    String to = convertedItem(st.getItemId());
                    if (to == null) {
                        continue;
                    }
                    if (to.isEmpty()) {
                        c.removeItemStackFromSlot(i);
                        continue;
                    }
                    ItemStack pack = new ItemStack(to, st.getQuantity());
                    org.bson.BsonDocument meta = st.getMetadata();
                    if (meta != null && meta.containsKey(TentContents.METADATA_KEY)) {
                        pack = pack.withMetadata(TentContents.METADATA_KEY, meta.get(TentContents.METADATA_KEY));
                    }
                    c.setItemStackForSlot(i, pack);
                    LOGGER.at(Level.INFO).log("Caisse de tente convertie en sac : %s -> %s", st.getItemId(), to);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Conversion des caisses de tente en échec");
        }
    }

    /** A player connects: they may be told again about a taken bag cell. */
    static void onArrival(UUID player) {
        NOTIFIED.remove(player);
    }

    /**
     * Places the bag block of a tent posed before the packs if its cell is free; otherwise notes the entry so that its owner is told.
     */
    static void placeMissingBag(World world, BalloonRegistry.Entry entry) {
        settle(world, entry);
    }

    private static boolean settle(World world, BalloonRegistry.Entry entry) {
        CampTiers.Tier tier = CampTiers.ofKind(entry.kind());
        if (tier == null) {
            return true;
        }
        Vector3i cell = bagWorldCell(entry, tier);
        var cs = world.getChunkStore();
        if (!SectionBlocks.sectionsLoaded(cs, cell.x, cell.y, cell.z, cell.x, cell.y, cell.z)
                || !SectionBlocks.entitiesLoaded(cs, cell.x, cell.y, cell.z)) {
            return false;
        }
        SectionReader reader = new SectionReader(cs);
        BlockType bt = SectionBlocks.typeAt(reader, cell.x, cell.y, cell.z);
        if (bt != null && tier.placedBlockId().equals(bt.getId())) {
            return true;
        }
        boolean solid = bt != null && bt.getMaterial() == BlockMaterial.Solid;
        boolean liquid = BalloonManager.liquidAt(reader, cell.x, cell.y, cell.z) != null;
        if (!bagCellFree(solid, liquid)) {
            String k = key(entry);
            boolean first = !RETRY_AT.containsKey(k);
            if (entry.owner() != null) {
                BLOCKED.put(k, entry.owner());
            }
            RETRY_AT.put(k, System.currentTimeMillis() + RETRY_MS);
            if (first) {
                LOGGER.at(Level.INFO).log("Tente déjà posée en (%d, %d, %d) : case du sac prise en (%d, %d, %d)", entry.x(), entry.y(), entry.z(), cell.x, cell.y, cell.z);
            }
            return false;
        }
        BLOCKED.remove(key(entry));
        RETRY_AT.remove(key(entry));
        if (SectionBlocks.setAt(cs, cell.x, cell.y, cell.z, tier.placedBlockId(), 4)) {
            LOGGER.at(Level.INFO).log("Tente déjà posée en (%d, %d, %d) : sac posé en (%d, %d, %d)", entry.x(), entry.y(), entry.z(), cell.x, cell.y, cell.z);
            return true;
        }
        return false;
    }

    /** Once a second per world: settles the tents whose section became active, then tells owners near a taken cell. Never throws. */
    static void tick(Store<EntityStore> store, World world) {
        try {
            long now = System.currentTimeMillis();
            Long last = LAST_CHECK_MS.get(world.getName());
            if (last != null && now - last < CHECK_INTERVAL_MS) {
                return;
            }
            LAST_CHECK_MS.put(world.getName(), now);
            Set<String> live = new java.util.HashSet<>();
            for (BalloonRegistry.Entry e : campEntries(world)) {
                live.add(key(e));
            }
            String prefix = world.getName() + ":";
            DONE.removeIf(k -> k.startsWith(prefix) && !live.contains(k));
            BLOCKED.keySet().removeIf(k -> k.startsWith(prefix) && !live.contains(k));
            RETRY_AT.keySet().removeIf(k -> k.startsWith(prefix) && !live.contains(k));
            for (String kind : new String[] {Deployables.TENT_KIND, Deployables.TENT_BIG_KIND}) {
                for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, kind)) {
                    String k = key(e);
                    if (DONE.contains(k)) {
                        continue;
                    }
                    Long retry = RETRY_AT.get(k);
                    if (retry != null && now < retry) {
                        continue;
                    }
                    try {
                        if (settle(world, e)) {
                            DONE.add(k);
                        }
                    } catch (RuntimeException ex) {
                        DONE.add(k);
                        LOGGER.at(Level.WARNING).withCause(ex).log("Sac d'une tente déjà posée : pose en échec");
                    }
                }
            }
            if (BLOCKED.isEmpty()) {
                return;
            }
            for (PlayerRef pr : world.getPlayerRefs()) {
                Ref<EntityStore> ref = pr.getReference();
                if (ref == null || !ref.isValid() || NOTIFIED.contains(pr.getUuid())) {
                    continue;
                }
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                if (t == null) {
                    continue;
                }
                Vector3d p = t.getPosition();
                for (BalloonRegistry.Entry e : campEntries(world)) {
                    UUID owner = BLOCKED.get(key(e));
                    if (owner == null || !owner.equals(pr.getUuid())) {
                        continue;
                    }
                    double dx = p.x - e.x(), dy = p.y - e.y(), dz = p.z - e.z();
                    if (dx * dx + dy * dy + dz * dz <= NOTIFY_RADIUS * NOTIFY_RADIUS) {
                        pr.sendMessage(Texts.t("camp.bagBlocked"));
                        NOTIFIED.add(pr.getUuid());
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camps déjà posés : tick en échec");
        }
    }

    /** Nearest posed camp whose origin or bag cell is within radius of the point, null if none. */
    static BalloonRegistry.Entry nearest(World world, Vector3d at, double radius) {
        BalloonRegistry.Entry best = null;
        double bestD = radius * radius;
        for (BalloonRegistry.Entry e : campEntries(world)) {
            CampTiers.Tier tier = CampTiers.ofKind(e.kind());
            if (tier == null) {
                continue;
            }
            Vector3i bag = bagWorldCell(e, tier);
            double d1 = sq(at.x - e.x(), at.y - e.y(), at.z - e.z());
            double d2 = sq(at.x - bag.x - 0.5, at.y - bag.y, at.z - bag.z - 0.5);
            double d = Math.min(d1, d2);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static List<BalloonRegistry.Entry> campEntries(World world) {
        List<BalloonRegistry.Entry> all = new ArrayList<>();
        for (CampTiers.Tier t : CampTiers.Tier.values()) {
            all.addAll(BalloonRegistry.entriesOfKind(world, t.kindId()));
        }
        return all;
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
