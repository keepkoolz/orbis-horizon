package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * No camping pack (full or empty) can be stored in a chest of a mod tent, so packs cannot be nested endlessly.
 * Slot filters are not saved with the world: this system installs them whenever a chest component appears in a registered
 * camp. The packing safety net (split) ejects any forbidden stack found in a captured chest.
 */
public final class CampChestFilter extends RefSystem<ChunkStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Set<String> FORBIDDEN = Set.of(
            "Camping_Pack_Bedroll", "Camping_Pack_Tent", "Camping_Pack_Tent_Big",
            "Camping_Tent_Crate", "Camping_Tent_Big_Crate");

    /** Pure rule: true for the items a tent chest refuses. */
    static boolean forbidden(String itemId) {
        return itemId != null && FORBIDDEN.contains(itemId);
    }

    /** Pure: for each slot item id, true when the stack must be ejected. */
    static boolean[] ejectMask(String[] slotItemIds) {
        boolean[] mask = new boolean[slotItemIds.length];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = forbidden(slotItemIds[i]);
        }
        return mask;
    }

    /** Pure per-container plan over slot item ids (null = empty slot). */
    record Plan(boolean[] eject, boolean anyEjected, boolean anyLeft) {}

    static Plan plan(String[] ids) {
        boolean[] mask = ejectMask(ids);
        boolean any = false;
        boolean left = false;
        for (int i = 0; i < ids.length; i++) {
            if (mask[i]) {
                any = true;
            } else if (ids[i] != null) {
                left = true;
            }
        }
        return new Plan(mask, any, left);
    }

    /** Kept cargo (forbidden slots emptied, slots preserved) and the stacks ejected from it. */
    record Split(BalloonCargo kept, List<ItemStack> ejected, Vector3i dropPos) {}

    /** Splits a captured cargo. Entries left empty are dropped from the kept cargo. Never throws. */
    static Split split(BalloonCargo cargo) {
        List<BalloonCargo.Entry> keptEntries = new ArrayList<>();
        List<ItemStack> ejected = new ArrayList<>();
        Vector3i pos = null;
        for (BalloonCargo.Entry e : cargo.entries()) {
            String[] ids = new String[e.slots.length];
            for (int i = 0; i < ids.length; i++) {
                ItemStack st = e.slots[i];
                ids[i] = st != null && !st.isEmpty() ? st.getItemId() : null;
            }
            Plan plan = plan(ids);
            boolean[] mask = plan.eject();
            ItemStack[] slots = e.slots.clone();
            for (int i = 0; i < slots.length; i++) {
                if (mask[i]) {
                    ejected.add(slots[i]);
                    slots[i] = null;
                    if (pos == null) {
                        pos = e.local;
                    }
                }
            }
            boolean any = plan.anyEjected();
            boolean left = plan.anyLeft();
            if (!any) {
                keptEntries.add(e);
            } else if (left) {
                keptEntries.add(new BalloonCargo.Entry(e.local, e.blockName, e.capacity, slots));
            }
        }
        return new Split(new BalloonCargo(keptEntries), ejected, pos);
    }

    /** Installs the add filter on every slot of a container. */
    static void install(ItemContainer container) {
        SlotFilter filter = (type, c, slot, stack) -> stack == null || !forbidden(stack.getItemId());
        for (short s = 0; s < container.getCapacity(); s++) {
            container.setSlotFilter(FilterActionType.ADD, s, filter);
        }
    }

    // ------------------------------------------------------------------ system

    private final Query<ChunkStore> query = Query.and(ItemContainerBlock.getComponentType(), BlockModule.BlockStateInfo.getComponentType());

    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdded(Ref<ChunkStore> ref, AddReason reason, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
        try {
            ItemContainerBlock block = buffer.getComponent(ref, ItemContainerBlock.getComponentType());
            BlockModule.BlockStateInfo info = buffer.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            if (block == null || info == null || block.getItemContainer() == null) {
                return;
            }
            Vector3i p = new Vector3i();
            if (!info.fillWorldPos(store, p)) {
                return;
            }
            World world = store.getExternalData().getWorld();
            BalloonRegistry.Entry e = BalloonRegistry.find(world, p);
            if (e != null && isCamp(e.kind())) {
                install(block.getItemContainer());
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Tent chest filter: cannot install");
        }
    }

    @Override
    public void onEntityRemove(Ref<ChunkStore> ref, RemoveReason reason, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
    }

    static boolean isCamp(String kind) {
        return Deployables.BEDROLL_KIND.equals(kind) || Deployables.TENT_KIND.equals(kind) || Deployables.TENT_BIG_KIND.equals(kind);
    }

    /** Installs the filter on the chests of a freshly deployed camp (components may not exist yet: the system covers those). Never throws. */
    static boolean installAround(World world, StructureShape shape, Vector3i origin, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation) {
        boolean all = true;
        try {
            for (StructureShape.Cell c : shape.cells()) {
                if (c.filler() != 0 || !"Furniture_Kweebec_Chest_Small".equals(c.baseName())) {
                    continue;
                }
                Vector3i p = c.rotated(rotation).add(origin);
                ItemContainerBlock b = SectionBlocks.blockComponent(world.getChunkStore(), ItemContainerBlock.getComponentType(), p.x, p.y, p.z);
                if (b != null && b.getItemContainer() != null) {
                    install(b.getItemContainer());
                } else {
                    all = false;
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Tent chest filter: cannot install after placement");
            return false;
        }
        return all;
    }

    private static final java.util.concurrent.ScheduledExecutorService RETRY = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "orbis-camp-chest-filter");
        t.setDaemon(true);
        return t;
    });

    /**
     * After a deploy: installs the filter on the camp's chests on the world thread, retrying every 250 ms for up to 10 s while a
     * chest component does not exist yet. Independent of any contents, and of the RefSystem (the registry entry may not exist
     * when the component first appears). Never throws.
     */
    static void installWithRetry(World world, StructureShape shape, Vector3i origin,
                                 com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation) {
        attempt(world, shape, new Vector3i(origin), rotation, 0);
    }

    private static void attempt(World world, StructureShape shape, Vector3i origin,
                                com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation, int tries) {
        try {
            world.execute(() -> {
                boolean done = installAround(world, shape, origin, rotation);
                if (!done && tries < 40) {
                    try {
                        RETRY.schedule(() -> attempt(world, shape, origin, rotation, tries + 1), 250, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (RuntimeException ex) {
                        LOGGER.at(Level.WARNING).withCause(ex).log("Tent chest filter: retry failed");
                    }
                }
            });
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Tent chest filter: attempt failed");
        }
    }
}
