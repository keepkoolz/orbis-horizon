package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Block, fluid and section access. A section that is not loaded reads as empty. Writes go through the world thread. */
public interface WorldAccess {
    /** Highest block row of the world. */
    int MAX_Y = 319;

    /** True when y is outside the height range of the world. */
    boolean outOfHeight(int y);

    /** Block type at a position, null when the section is not in memory. */
    BlockType typeAt(SectionReader reader, int x, int y, int z);

    /** Id of the liquid in a cell (fire excluded), null when there is none. */
    String liquidAt(SectionReader reader, int x, int y, int z);

    /** True for a water fluid in a cell that is not a solid block. */
    boolean isWaterCell(SectionReader reader, int x, int y, int z);

    /** Sets a block by name (rotation 0, filler 0). False when the name is unknown or the section is not loaded. */
    boolean setBlock(ChunkStore store, int x, int y, int z, String blockKey, int settings);

    /** Sets a fluid. False when the section is not loaded. */
    boolean setFluid(ChunkStore store, int x, int y, int z, int fluidId, byte level);

    /** True when every section crossed by the box is loaded. */
    boolean sectionsLoaded(ChunkStore store, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    /** Loads every section crossed by the box. */
    CompletableFuture<Void> loadSections(ChunkStore store, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    /** The balloons and stations registered in a world, with the world box of each. */
    List<PlacedStructure> structuresIn(World world);
}
