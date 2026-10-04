package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.system.BenchSystems;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.logging.Level;

/**
 * T41: compatibility of burners placed before the tannery-style interface.
 *
 * Before T41 the fuel was in the bench fuel container (3 slots, Fuel field of the
 * JSON). Now it is in the 3 input slots and the JSON no longer has a Fuel field. When the
 * block loads, BenchSystems$ProcessingBenchLifecycle.onEntityAdded calls initializeBenchConfig
 * then setupSlots, which reduces the fuel container to a capacity of 0 (ItemContainer.ensureContainerCapacity)
 * and drops into the world what does not fit (ejectItems). This system runs first (SystemDependency BEFORE) and
 * moves the contents of the fuel container into the input container, which setupSlots then keeps
 * (capacity already 3). Nothing is lost or duplicated: slots 0 to 2 become inputs 0 to 2, the
 * stacks that find no room stay in the old container (the game then drops them, as before).
 *
 * Hypotheses not verified in game: the system order is respected for onEntityAdded, and the
 * component loaded from the chunk is already decoded at that point (otherwise nothing is moved and the game drops
 * the fuel into the world, without loss). The system only touches a component for which setupSlots has not
 * run yet (combinedItemContainer null) and whose block is the mod's burner.
 */
public final class BurnerMigrationSystem extends RefSystem<ChunkStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Field INPUT_FIELD = field("inputContainer");

    private final ComponentType<ChunkStore, ProcessingBenchBlock> benchType = ProcessingBenchBlock.getComponentType();
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> infoType = BlockModule.BlockStateInfo.getComponentType();
    private final Query<ChunkStore> query = Query.and(benchType, infoType);

    private static Field field(String name) {
        try {
            Field f = ProcessingBenchBlock.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Champ %s de ProcessingBenchBlock inaccessible", name);
            return null;
        }
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public Set<Dependency<ChunkStore>> getDependencies() {
        return Set.of(new SystemDependency<ChunkStore, BenchSystems.ProcessingBenchLifecycle>(
                Order.BEFORE, BenchSystems.ProcessingBenchLifecycle.class));
    }

    @Override
    public void onEntityAdded(Ref<ChunkStore> ref, AddReason reason, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
        try {
            migrate(ref, buffer);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Brûleur : déplacement de l'ancien combustible impossible");
        }
    }

    @Override
    public void onEntityRemove(Ref<ChunkStore> ref, RemoveReason reason, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
    }

    private void migrate(Ref<ChunkStore> ref, CommandBuffer<ChunkStore> buffer) {
        ProcessingBenchBlock bench = buffer.getComponent(ref, benchType);
        BlockModule.BlockStateInfo info = buffer.getComponent(ref, infoType);
        if (bench == null || info == null || INPUT_FIELD == null) {
            return;
        }
        ItemContainer fuel = bench.getFuelContainer();
        if (fuel == null || fuel.getCapacity() <= 0 || fuel.isEmpty()) {
            return;
        }
        if (bench.getItemContainer() != null) {
            // setupSlots has already run: too late, the game has already handled this container.
            return;
        }
        if (!isBurner(info, buffer)) {
            return;
        }
        ItemContainer in = bench.getInputContainer();
        short cap = (short) Math.max(BurnerFuel.INPUT_SLOTS, in != null ? in.getCapacity() : 0);
        ItemContainer merged = new SimpleItemContainer(cap);
        int moved = 0;
        if (in != null) {
            for (short s = 0; s < in.getCapacity(); s++) {
                ItemStack st = in.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    merged.setItemStackForSlot(s, st, false);
                }
            }
        }
        java.util.List<ItemStack> left = new java.util.ArrayList<>();
        for (short s = 0; s < fuel.getCapacity(); s++) {
            ItemStack st = fuel.getItemStack(s);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (s < cap && merged.getItemStack(s) == null && merged.setItemStackForSlot(s, st, false).succeeded()) {
                moved += st.getQuantity();
                continue;
            }
            ItemStack rest = merged.addItemStack(st, false, false, false).getRemainder();
            moved += st.getQuantity() - (rest != null && !rest.isEmpty() ? rest.getQuantity() : 0);
            if (rest != null && !rest.isEmpty()) {
                left.add(rest);
            }
        }
        try {
            INPUT_FIELD.set(bench, merged);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Brûleur : conteneur d'entrée non remplaçable, l'ancien combustible reste dans l'ancien conteneur");
            return;
        }
        // The moved stacks are removed from the old container (no duplicates). What found no room
        // stays there: the game drops it, as it did before.
        fuel.clear();
        for (ItemStack rest : left) {
            fuel.addItemStack(rest, false, false, false);
        }
        LOGGER.at(Level.INFO).log("Brûleur d'avant T41 : %d objet(s) de combustible déplacé(s) vers les entrées (%d restés dans l'ancien conteneur)",
                moved, left.size());
    }

    /** Same block type reading as BenchSystems$ProcessingBenchLifecycle.onEntityAdded. */
    private static boolean isBurner(BlockModule.BlockStateInfo info, CommandBuffer<ChunkStore> buffer) {
        Ref<ChunkStore> sectionRef = info.getSectionRef();
        if (sectionRef == null || !sectionRef.isValid()) {
            return false;
        }
        ChunkSection section = buffer.getComponent(sectionRef, ChunkSection.getComponentType());
        BlockSection blocks = buffer.getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null || blocks == null) {
            return false;
        }
        int index = info.getIndex();
        int id = blocks.get(ChunkUtil.xFromIndex(index), ChunkUtil.yFromIndex(index), ChunkUtil.zFromIndex(index));
        BlockType type = BlockType.getAssetMap().getAsset(id);
        if (type == null) {
            return false;
        }
        Bench bench = type.getBench();
        return bench != null && BurnerFlameSystem.BURNER_BENCH_ID.equals(bench.getId());
    }
}
