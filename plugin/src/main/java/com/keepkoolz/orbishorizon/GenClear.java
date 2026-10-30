package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.joml.Vector3i;

import java.util.logging.Level;

/** Clears the vegetation and decorative pebbles of the ground where a generated station is built. Soil and rock are never touched. */
final class GenClear {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int SET_NO_PARTICLES = 4;

    private GenClear() {
    }

    /** True for a trunk, foliage, plant, decorative pebble or tree decoration block. A leading * (block state) is ignored. */
    static boolean removable(String blockId) {
        if (blockId == null) {
            return false;
        }
        String n = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        return n.startsWith("Plant_") || n.startsWith("Rubble_") || n.equals("Wood_Sticks") || n.startsWith("Deco_Nest")
                || n.startsWith("Deco_SpiderWeb") || n.startsWith("Deco_Spider_Cocoon")
                || (n.startsWith("Wood_") && n.matches("Wood_[A-Za-z]+_(Trunk(_Full|_Half|_Stairs)?|Branch_(Short|Long|Corner)|Roots)"));
    }

    /** True if the block at the position is a removable one (section not loaded: false). */
    static boolean removableAt(SectionReader reader, int x, int y, int z) {
        BlockType t = SectionBlocks.typeAt(reader, x, y, z);
        return t != null && removable(t.getId());
    }

    /** Clears the removable blocks of the cells of a shape placed at origin. Returns the number of cells cleared. */
    static int clear(World world, StructureShape shape, Vector3i origin, Rotation rotation) {
        int cleared = 0;
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (StructureShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
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
        if (BalloonManager.outOfHeight(y) || !removableAt(reader, x, y, z)) {
            return 0;
        }
        return erase(world, x, y, z) ? 1 : 0;
    }

    /** Writes air at a cell, without particles. False when the section is not loaded or the write failed (logged). */
    static boolean erase(World world, int x, int y, int z) {
        try {
            return SectionBlocks.setAt(world.getChunkStore(), x, y, z, BlockType.EMPTY_KEY, SET_NO_PARTICLES);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot clear at (%d, %d, %d)", x, y, z);
            Telemetry.error("worldgen", e, null, "clear");
            return false;
        }
    }
}
