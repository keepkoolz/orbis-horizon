package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Block and chunk section access for the 0.7 server API (T99). Modelled on the game's /block get and /block set
 * commands: reads go through a SectionReader, writes through BlockOperations on the section reference of the block.
 * Nothing here takes a World, and none of the readers or references may be kept in a field.
 */
final class SectionBlocks {

    private SectionBlocks() {
    }

    /**
     * Block type at a position, or null if the section is not in memory or the id has no asset. SectionReader.getBlock
     * returns 0 (empty block) when the section is not loaded, so hasStorageInMemory is checked first.
     */
    static BlockType typeAt(SectionReader reader, int x, int y, int z) {
        if (!reader.hasStorageInMemory(x, y, z)) {
            return null;
        }
        return BlockType.getAssetMap().getAsset(reader.getBlock(x, y, z));
    }

    /** Sets a block by name (rotation 0, filler 0), like /block set. False if the block name is unknown. */
    static boolean setAt(ChunkStore store, int x, int y, int z, String blockKey, int settings) {
        int id = BlockType.getAssetMap().getIndex(blockKey);
        if (id < 0) {
            return false;
        }
        BlockType type = BlockType.getAssetMap().getAsset(id);
        if (type == null) {
            return false;
        }
        return setAt(store, x, y, z, id, type, 0, 0, settings);
    }

    /**
     * Sets a block from its id and type on the section that holds it. False only if that section is not loaded.
     * BlockOperations.setBlock returns whether the block changed: false when the same block (id and rotation) is already there.
     * That is not a failure (the cage moving down puts a bar where an identical bar already is), so it is not passed on.
     */
    static boolean setAt(ChunkStore store, int x, int y, int z, int id, BlockType type, int rotation, int filler,
                         int settings) {
        Ref<ChunkStore> ref = store.getChunkSectionReferenceAtBlock(x, y, z);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        BlockOperations.setBlock(store, ref, x, y, z, id, type, rotation, filler, settings);
        return true;
    }

    /**
     * True if the entities of the section holding this block are in the world. 0.7 keeps the entities of a loaded but non ticking section
     * (no player near: NonTicking component) stored in the section (EntitySection$EntitySectionLoadingSystem removes them with reason
     * UNLOAD and puts them back when the section ticks again). Loading a section is not enough to find an entity by UUID, and an
     * entity added there is stored away at once: the reference returned is already invalid.
     */
    static boolean entitiesLoaded(ChunkStore store, int x, int y, int z) {
        Ref<ChunkStore> ref = store.getChunkSectionReferenceAtBlock(x, y, z);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        return store.getStore().getComponent(ref, ChunkStore.REGISTRY.getNonTickingComponentType()) == null;
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile boolean componentFailureLogged;

    /**
     * Block component at a position, also in a section without players near. 0.7 removes the block entities of a NonTicking section
     * from the store (BlockComponentSection$BlockComponentSectionLoadingSystem, reason UNLOAD) and keeps their Holder in the section:
     * BlockModule.getComponent then returns null. The component of that holder is the one put back when the section ticks again, so
     * reading or emptying it here is the same as on a live entity (hypothesis: changes are kept and saved). Null if there is none.
     * Never throws.
     */
    static <T extends Component<ChunkStore>> T blockComponent(ChunkStore cs, ComponentType<ChunkStore, T> type, int x, int y, int z) {
        try {
            Ref<ChunkStore> section = cs.getChunkSectionReferenceAtBlock(x, y, z);
            if (section == null || !section.isValid()) {
                return null;
            }
            Ref<ChunkStore> entity = BlockModule.getBlockEntity(cs.getStore(), section, x, y, z);
            if (entity != null && entity.isValid()) {
                T live = cs.getStore().getComponent(entity, type);
                if (live != null) {
                    return live;
                }
            }
            BlockComponentSection bcs = cs.getStore().getComponent(section, BlockComponentSection.getComponentType());
            if (bcs == null) {
                return null;
            }
            Holder<ChunkStore> holder = bcs.getBlockHolder(ChunkUtil.indexBlock(x, y, z));
            return holder != null ? holder.getComponent(type) : null;
        } catch (RuntimeException e) {
            if (!componentFailureLogged) {
                componentFailureLogged = true;
                LOGGER.at(java.util.logging.Level.WARNING).withCause(e).log("Lecture d'un composant de bloc impossible");
            }
            return null;
        }
    }

    /**
     * Sets the fluid of a cell and wakes its block section so the fluid flows (path of PlaceFluidInteraction). FluidSection.setFluid
     * takes world coordinates: it indexes the section with ChunkUtil.indexBlock, which masks each axis with 31. False only if the
     * section is not loaded.
     */
    static boolean setFluidAt(ChunkStore store, int x, int y, int z, int fluidId, byte level) {
        Ref<ChunkStore> ref = store.getChunkSectionReferenceAtBlock(x, y, z);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        var chunkStore = store.getStore();
        var fluids = chunkStore.getComponent(ref,
                com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());
        var blocks = chunkStore.getComponent(ref,
                com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
        if (fluids == null || blocks == null) {
            return false;
        }
        fluids.setFluid(x, y, z, fluidId, level);
        blocks.setTicking(x, y, z, true);
        return true;
    }

    /** Changes the interaction state of a block (same call and last argument as the bench upgrade in AirshipBenches). */
    static boolean setStateAt(ChunkStore store, int x, int y, int z, BlockType type, String state) {
        Ref<ChunkStore> ref = store.getChunkSectionReferenceAtBlock(x, y, z);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        BlockOperations.setBlockInteractionState(store, ref, x, y, z, type, state, true);
        return true;
    }

    /**
     * True if every section crossed by the box (Y clamped to the world) has a valid reference, so reads are reliable. A box
     * entirely outside rows 0 to WORLD_MAX_Y crosses no section: true (nothing to read there, callers test the height apart).
     */
    static boolean sectionsLoaded(ChunkStore store, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int y0 = ChunkUtil.chunkCoordinate(Math.max(minY, ChunkUtil.MIN_Y));
        int y1 = ChunkUtil.chunkCoordinate(Math.min(maxY, BalloonManager.WORLD_MAX_Y));
        for (int cx = ChunkUtil.chunkCoordinate(minX); cx <= ChunkUtil.chunkCoordinate(maxX); cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = ChunkUtil.chunkCoordinate(minZ); cz <= ChunkUtil.chunkCoordinate(maxZ); cz++) {
                    Ref<ChunkStore> ref = store.getChunkSectionReference(cx, cy, cz);
                    if (ref == null || !ref.isValid()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Loads every section crossed by the box (Y clamped to the world). Completes when all of them are loaded. A box entirely
     * outside rows 0 to WORLD_MAX_Y crosses no section: the future is already complete.
     */
    static CompletableFuture<Void> loadSections(ChunkStore store, int minX, int minY, int minZ, int maxX, int maxY,
                                                int maxZ) {
        int y0 = ChunkUtil.chunkCoordinate(Math.max(minY, ChunkUtil.MIN_Y));
        int y1 = ChunkUtil.chunkCoordinate(Math.min(maxY, BalloonManager.WORLD_MAX_Y));
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int cx = ChunkUtil.chunkCoordinate(minX); cx <= ChunkUtil.chunkCoordinate(maxX); cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = ChunkUtil.chunkCoordinate(minZ); cz <= ChunkUtil.chunkCoordinate(maxZ); cz++) {
                    futures.add(store.getChunkSectionReferenceAsync(cx, cy, cz));
                }
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }
}
