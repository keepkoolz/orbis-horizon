package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Upgrade level of the airship's benches during a flight (T63). A bench upgraded on the ground (BenchBlock.tierLevel above 1)
 * would lose its level when the blocks are removed at take-off, and the game would drop its upgrade items as a refund
 * (BenchSystems$OnAddOrRemoved.onEntityRemove, dropUpgradeItems). The level and the upgrade items are copied at take-off,
 * the items are cleared from the live component so the game drops nothing, and both are put back on the bench placed at the
 * same prefab position at landing, with the block state of the level (Tier2, Tier3...), like CraftingManager does when a
 * bench is upgraded.
 *
 * Only the origin cell of a multi-cell bench counts (BalloonShape.Cell.filler is 0). Position in the file and in this list:
 * prefab frame, before rotation.
 */
final class AirshipBenches {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long RESTORE_TIMEOUT_MS = 10000;

    /** One upgraded bench: prefab-frame position of its origin cell, block base name (log only), level and upgrade items. */
    static final class Entry {
        final Vector3i local;
        final String block;
        final int tier;
        final ItemStack[] upgradeItems;

        Entry(Vector3i local, String block, int tier, ItemStack[] upgradeItems) {
            this.local = local;
            this.block = block;
            this.tier = tier;
            this.upgradeItems = upgradeItems;
        }
    }

    /** A level to put back on a bench whose component does not exist yet just after the paste. */
    private static final class Pending {
        final World world;
        final Vector3i pos;
        final Entry entry;
        final long deadlineMs;
        volatile boolean scheduled;

        Pending(World world, Vector3i pos, Entry entry, long deadlineMs) {
            this.world = world;
            this.pos = pos;
            this.entry = entry;
            this.deadlineMs = deadlineMs;
        }
    }

    private static final ConcurrentLinkedQueue<Pending> PENDING = new ConcurrentLinkedQueue<>();

    private final List<Entry> entries = new ArrayList<>();

    private AirshipBenches() {
    }

    static AirshipBenches empty() {
        return new AirshipBenches();
    }

    /**
     * Copies the level and the upgrade items of every upgraded bench of the prefab placed at this origin and rotation, then
     * clears the upgrade items of the live component (the blocks are about to be removed, the game would drop them).
     * Benches at level 1 without upgrade items are skipped. To be called on the world thread.
     */
    static AirshipBenches takeFrom(World world, BalloonShape shape, Vector3i origin, Rotation rotation) {
        AirshipBenches benches = new AirshipBenches();
        for (BalloonShape.Cell c : shape.cells()) {
            if (c.filler() != 0) {
                continue;
            }
            Vector3i p = c.rotated(rotation).add(origin);
            BenchBlock bench = live(world, p);
            if (bench == null) {
                continue;
            }
            ItemStack[] items = copy(bench.getUpgradeItems());
            if (bench.getTierLevel() <= 1 && items.length == 0) {
                continue;
            }
            benches.entries.add(new Entry(new Vector3i(c.x(), c.y(), c.z()), c.baseName(), bench.getTierLevel(), items));
            bench.setUpgradeItems(ItemStack.EMPTY_ARRAY);
        }
        return benches;
    }

    private static BenchBlock live(World world, Vector3i p) {
        return BlockModule.getComponent(BenchBlock.getComponentType(), world, p.x, p.y, p.z);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        if (items == null) {
            return ItemStack.EMPTY_ARRAY;
        }
        return Arrays.stream(items).filter(st -> st != null && !st.isEmpty()).toArray(ItemStack[]::new);
    }

    /** JSON form for the resume file: {"benches": [{"x", "y", "z", "block", "tier", "items": [ItemStack.CODEC]}]}. */
    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        BsonArray arr = new BsonArray();
        for (Entry e : entries) {
            BsonDocument ed = new BsonDocument();
            ed.put("x", new BsonInt32(e.local.x));
            ed.put("y", new BsonInt32(e.local.y));
            ed.put("z", new BsonInt32(e.local.z));
            ed.put("block", new org.bson.BsonString(e.block));
            ed.put("tier", new BsonInt32(e.tier));
            BsonArray items = new BsonArray();
            for (ItemStack st : e.upgradeItems) {
                items.add(ItemStack.CODEC.encode(st, new ExtraInfo()));
            }
            ed.put("items", items);
            arr.add(ed);
        }
        d.put("benches", arr);
        return d;
    }

    static AirshipBenches fromBson(BsonDocument d) {
        AirshipBenches benches = new AirshipBenches();
        if (d != null && d.containsKey("benches")) {
            for (BsonValue v : d.getArray("benches")) {
                BsonDocument ed = v.asDocument();
                List<ItemStack> items = new ArrayList<>();
                if (ed.containsKey("items")) {
                    for (BsonValue iv : ed.getArray("items")) {
                        ItemStack st = ItemStack.CODEC.decode(iv, new ExtraInfo());
                        if (st != null && !st.isEmpty()) {
                            items.add(st);
                        }
                    }
                }
                benches.entries.add(new Entry(new Vector3i(ed.getNumber("x").intValue(), ed.getNumber("y").intValue(),
                        ed.getNumber("z").intValue()), ed.containsKey("block") ? ed.getString("block").getValue() : "?",
                        ed.getNumber("tier").intValue(), items.toArray(ItemStack[]::new)));
            }
        }
        return benches;
    }

    /**
     * Puts each level back on the bench placed at this origin and rotation. A bench whose component does not exist yet is
     * retried every tick for RESTORE_TIMEOUT_MS, then its upgrade items are dropped on the ground (the level is lost, like
     * breaking the bench). To be called on the world thread, after the prefab paste.
     */
    void restore(World world, Store<EntityStore> store, Vector3i origin, Rotation rotation) {
        for (Entry e : entries) {
            Vector3i p = rotation.rotateYaw(new Vector3i(e.local), new Vector3i()).add(origin);
            if (!apply(world, p, e)) {
                PENDING.add(new Pending(world, p, e, System.currentTimeMillis() + RESTORE_TIMEOUT_MS));
            }
        }
        entries.clear();
    }

    /** Called every tick by AirshipManager.tick for the retries. */
    static void retry(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Pending p : PENDING) {
            if (p.world != world || p.scheduled) {
                continue;
            }
            p.scheduled = true;
            world.execute(() -> {
                p.scheduled = false;
                if (apply(world, p.pos, p.entry)) {
                    PENDING.remove(p);
                } else if (System.currentTimeMillis() > p.deadlineMs) {
                    PENDING.remove(p);
                    LOGGER.at(Level.WARNING).log("Établi %s du dirigeable introuvable en %s : niveau %d perdu, objets d'amélioration lâchés",
                            p.entry.block, p.pos, p.entry.tier);
                    BalloonManager.dropItems(store, Arrays.asList(p.entry.upgradeItems), p.pos);
                }
            });
        }
    }

    /**
     * Same steps as the game's bench upgrade (CraftingManager): level and upgrade items on the component, block state of the
     * level (BlockOperations.setBlockInteractionState on the base block type, "default" for level 1), then setupSlots again
     * for a processing bench (its slots can depend on the level). Returns false if the component does not exist yet.
     */
    private static boolean apply(World world, Vector3i p, Entry e) {
        BenchBlock bench = live(world, p);
        if (bench == null) {
            return false;
        }
        bench.setTierLevel(e.tier);
        bench.setUpgradeItems(copy(e.upgradeItems));
        BlockModule.BlockStateInfo info = BlockModule.getComponent(BlockModule.BlockStateInfo.getComponentType(), world, p.x, p.y, p.z);
        BlockType bt = world.getBlockType(p.x, p.y, p.z);
        if (info == null || bt == null) {
            LOGGER.at(Level.WARNING).log("Établi %s du dirigeable en %s : état du bloc illisible, niveau %d mis sans changer l'apparence",
                    e.block, p, e.tier);
            return true;
        }
        info.markNeedsSaving();
        try {
            BlockType base = BenchBlock.getBaseBlockType(bt);
            BlockOperations.setBlockInteractionState(world.getChunkStore(), info.getSectionRef(), p.x, p.y, p.z, base,
                    bench.getTierStateName(), true);
            ProcessingBenchBlock processing = BlockModule.getComponent(ProcessingBenchBlock.getComponentType(), world, p.x, p.y, p.z);
            if (processing != null) {
                BlockSection section = world.getChunkStore().getStore().getComponent(info.getSectionRef(), BlockSection.getComponentType());
                int rotationIndex = section != null ? section.getRotationIndex(p.x, p.y, p.z) : 0;
                processing.setupSlots(world, bench, info, p.x, p.y, p.z, base, rotationIndex);
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Établi %s du dirigeable en %s : apparence du niveau %d non appliquée",
                    e.block, p, e.tier);
        }
        LOGGER.at(Level.INFO).log("Établi %s du dirigeable en %s remis au niveau %d (%d objet(s) d'amélioration)",
                e.block, p, e.tier, e.upgradeItems.length);
        return true;
    }

    int entryCount() {
        return entries.size();
    }

    /** All upgrade items, and the list is emptied (administrator removal of a flying ship): nothing can be dropped twice. */
    List<ItemStack> takeAllItems() {
        List<ItemStack> all = new ArrayList<>();
        for (Entry e : entries) {
            all.addAll(Arrays.asList(e.upgradeItems));
        }
        entries.clear();
        return all;
    }

    /** Short text for /orbishorizon airship debug. */
    String describe() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.block).append(" T").append(e.tier);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
