package com.keepkoolz.orbishorizon.worldgen;

import java.util.List;

/**
 * Scans of the world for blocks that players build, so that a station is never generated next to a construction on terrain that was
 * explored before the mod generated stations. The rules themselves are the shared {@link com.keepkoolz.orbishorizon.api.BuiltBlocks}.
 */
final class BuiltBlocks {
    private BuiltBlocks() {
    }

    /** True when the block id is a block that does not occur in natural terrain and that players place. A leading * is ignored. */
    static boolean isBuilt(String blockId) {
        return com.keepkoolz.orbishorizon.api.BuiltBlocks.isBuilt(blockId);
    }

    /** A block id read by coordinates, null for an empty cell or a section that is not loaded. */
    @FunctionalInterface
    interface CellReader {
        String idAt(int x, int y, int z);
    }

    /** The first built block found by {@link #firstBuilt}. */
    record Found(String block, int x, int y, int z) {
    }

    /**
     * The first built block in the box {minX, minY, minZ, maxX, maxY, maxZ} widened by margin blocks horizontally (rows scanned from
     * the bottom up, then x, then z), null when there is none.
     */
    static Found firstBuilt(int[] box, int margin, CellReader reader) {
        for (int y = box[1]; y <= box[4]; y++) {
            for (int x = box[0] - margin; x <= box[3] + margin; x++) {
                for (int z = box[2] - margin; z <= box[5] + margin; z++) {
                    String id = reader.idAt(x, y, z);
                    if (isBuilt(id)) {
                        return new Found(id.startsWith("*") ? id.substring(1) : id, x, y, z);
                    }
                }
            }
        }
        return null;
    }

    /** Every built block of the area {minX, minY, minZ, maxX, maxY, maxZ}, rows from the bottom up, then x, then z. */
    static List<Found> collect(int[] area, CellReader reader) {
        List<Found> out = new java.util.ArrayList<>();
        for (int y = area[1]; y <= area[4]; y++) {
            for (int x = area[0]; x <= area[3]; x++) {
                for (int z = area[2]; z <= area[5]; z++) {
                    String id = reader.idAt(x, y, z);
                    if (isBuilt(id)) {
                        out.add(new Found(id.startsWith("*") ? id.substring(1) : id, x, y, z));
                    }
                }
            }
        }
        return out;
    }

    /** The first of the collected cells inside the box widened by margin blocks horizontally, null when none. */
    static Found firstIn(List<Found> cells, int[] box, int margin) {
        for (Found f : cells) {
            if (f.y() >= box[1] && f.y() <= box[4] && f.x() >= box[0] - margin && f.x() <= box[3] + margin
                    && f.z() >= box[2] - margin && f.z() <= box[5] + margin) {
                return f;
            }
        }
        return null;
    }

    /** True when the two boxes {minX, minY, minZ, maxX, maxY, maxZ} share a cell. */
    static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5];
    }
}
