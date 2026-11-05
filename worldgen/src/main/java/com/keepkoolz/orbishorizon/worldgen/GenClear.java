package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.joml.Vector3i;

import java.util.List;
import java.util.logging.Level;

/** Clears the vegetation and decorative pebbles of the ground where a generated station is built. Soil and rock are never touched. */
final class GenClear {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int SET_NO_PARTICLES = 4;

    private GenClear() {
    }

    /** True for a trunk, foliage, plant, decorative pebble or tree decoration block. A leading * (block state) is ignored. */
    static boolean removable(String blockId) {
        return com.keepkoolz.orbishorizon.api.BuiltBlocks.removable(blockId);
    }

    /** True if the block at the position is a removable one (section not loaded: false). */
    static boolean removableAt(SectionReader reader, int x, int y, int z) {
        BlockType t = WorldgenApi.world().typeAt(reader, x, y, z);
        return t != null && removable(t.getId());
    }

    /** Clears the removable blocks of cells (prefab frame) placed at origin. Returns the number of cells cleared. */
    static int clear(World world, List<Vector3i> cells, Vector3i origin, Rotation rotation) {
        int cleared = 0;
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (Vector3i c : cells) {
            Vector3i p = rotation.rotateYaw(c, new Vector3i()).add(origin);
            cleared += clearCell(world, reader, p.x, p.y, p.z);
        }
        return cleared;
    }

    /** Clears the removable blocks of a box given in the prefab frame (bounds included), rotated and placed at origin. */
    static int clearBox(World world, int[] min, int[] max, Vector3i origin, Rotation rotation) {
        int cleared = 0;
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (int x = min[0]; x <= max[0]; x++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int z = min[2]; z <= max[2]; z++) {
                    Vector3i p = rotation.rotateYaw(new Vector3i(x, y, z), new Vector3i()).add(origin);
                    cleared += clearCell(world, reader, p.x, p.y, p.z);
                }
            }
        }
        return cleared;
    }

    private static int clearCell(World world, SectionReader reader, int x, int y, int z) {
        if (WorldgenApi.world().outOfHeight(y) || !removableAt(reader, x, y, z)) {
            return 0;
        }
        return erase(world, x, y, z) ? 1 : 0;
    }

    /** Writes air at a cell, without particles. False when the section is not loaded or the write failed (logged). */
    static boolean erase(World world, int x, int y, int z) {
        try {
            return WorldgenApi.world().setBlock(world.getChunkStore(), x, y, z, BlockType.EMPTY_KEY, SET_NO_PARTICLES);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot clear at (%d, %d, %d)", x, y, z);
            WorldgenApi.telemetry().error("worldgen", e, null, "clear");
            return false;
        }
    }
}
