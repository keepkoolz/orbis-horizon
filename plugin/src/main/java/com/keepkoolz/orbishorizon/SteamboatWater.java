package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;

import org.joml.Vector3i;

/**
 * Water primitives of the steamboat (lot 3): which cells of the hull sit under the waterline, and filling or draining them in the
 * world. Cell positions are in the prefab frame unless a method takes an origin and a rotation.
 */
final class SteamboatWater {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private SteamboatWater() {
    }

    /** Result of the outside flood fill: the box of the shape extended by one cell, and which of its cells the outside reaches. */
    record Outside(boolean[] flags, int ox, int oy, int oz, int sx, int sy, int sz) {
        /** True if the (prefab frame) cell is empty and reached from outside. Cells beyond the extended box count as outside. */
        boolean isOutside(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) {
                return true;
            }
            return flags[(lx * sy + ly) * sz + lz];
        }
    }

    /**
     * Flood fill (6-neighbourhood) of the empty cells of the shape's box extended by one cell, from a corner. Shared by
     * AirshipManager.hullOf (hull cells) and submergedCells (closed cavities).
     */
    static Outside outside(StructureShape shape) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructureShape.Cell c : shape.cells()) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
            maxZ = Math.max(maxZ, c.z());
        }
        int sx = maxX - minX + 3, sy = maxY - minY + 3, sz = maxZ - minZ + 3;
        boolean[] flags = new boolean[sx * sy * sz];
        int ox = minX - 1, oy = minY - 1, oz = minZ - 1;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        flags[0] = true;
        queue.add(new int[]{0, 0, 0});
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : DIRS) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                    continue;
                }
                int idx = (x * sy + y) * sz + z;
                if (flags[idx] || shape.cellAt(x + ox, y + oy, z + oz) != null) {
                    continue;
                }
                flags[idx] = true;
                queue.add(new int[]{x, y, z});
            }
        }
        return new Outside(flags, ox, oy, oz, sx, sy, sz);
    }

    // Keyed by shape identity (WeakHashMap): a shape lives as long as its Deployables type, so the entry is computed once per run.
    private static final Map<StructureShape, Map<Integer, List<Vector3i>>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Cells under (or at) the waterline, prefab frame: block cells with y at most waterlineY, plus the empty cells of the shape's
     * box with y at most waterlineY that the outside does not reach (closed holds). Cached per shape and waterline.
     */
    static List<Vector3i> submergedCells(StructureShape shape, int waterlineY) {
        Map<Integer, List<Vector3i>> perShape = CACHE.computeIfAbsent(shape, k -> new java.util.concurrent.ConcurrentHashMap<>());
        return perShape.computeIfAbsent(waterlineY, wl -> compute(shape, wl));
    }

    private static List<Vector3i> compute(StructureShape shape, int waterlineY) {
        List<Vector3i> out = new ArrayList<>();
        for (StructureShape.Cell c : shape.cells()) {
            if (c.y() <= waterlineY) {
                out.add(new Vector3i(c.x(), c.y(), c.z()));
            }
        }
        Outside o = outside(shape);
        for (int lx = 1; lx < o.sx() - 1; lx++) {
            for (int ly = 1; ly < o.sy() - 1; ly++) {
                int y = ly + o.oy();
                if (y > waterlineY) {
                    break;
                }
                for (int lz = 1; lz < o.sz() - 1; lz++) {
                    int x = lx + o.ox(), z = lz + o.oz();
                    if (shape.cellAt(x, y, z) == null && !o.flags()[(lx * o.sy() + ly) * o.sz() + lz]) {
                        out.add(new Vector3i(x, y, z));
                    }
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Blocks that sit in water on purpose: the shuttle's propeller (Material Empty, so the fluid flows in) is pasted with a water
     * source in its cell (the prefab's fluids entry), and drain must not empty it.
     */
    static final java.util.Set<String> WATERLOGGED = java.util.Set.of("Steamboat_Propeller");

    /** Submerged cells (prefab frame) that hold a waterlogged block: they keep their water when the hull is drained. */
    static List<Vector3i> waterloggedCells(StructureShape shape, int waterlineY) {
        List<Vector3i> out = new ArrayList<>();
        for (StructureShape.Cell c : shape.cells()) {
            if (c.y() <= waterlineY && WATERLOGGED.contains(c.baseName())) {
                out.add(new Vector3i(c.x(), c.y(), c.z()));
            }
        }
        return out;
    }

    /** A neighbouring world water cell: fluid id, level and whether it is a source fluid. */
    record Neighbour(int fluidId, byte level, boolean source) {
    }

    /** Keeps the better of two candidates: a source fluid first, then the highest level (the first one on a tie). */
    static Neighbour betterNeighbour(Neighbour a, Neighbour b) {
        if (a == null) {
            return b;
        }
        if (b.source() != a.source()) {
            return b.source() ? b : a;
        }
        return b.level() > a.level() ? b : a;
    }

    /** Water fluid (id starts with Water) in a cell that is not a solid block. */
    static boolean isWaterCell(SectionReader r, int x, int y, int z) {
        return isWaterCell(r, x, y, z, BalloonManager.liquidAt(r, x, y, z));
    }

    /** Same, with the liquid already read by the caller (saves a second read of the cell). */
    static boolean isWaterCell(SectionReader r, int x, int y, int z, String liquid) {
        if (liquid == null || !liquid.startsWith("Water")) {
            return false;
        }
        BlockType bt = SectionBlocks.typeAt(r, x, y, z);
        return bt == null || bt.getMaterial() != BlockMaterial.Solid;
    }

    /**
     * Fills the submerged cells that have neither fluid nor solid block. Fluid and level come from a neighbouring world water cell
     * (6 neighbours) that is itself outside the list: a source fluid, else the highest level. Without any, nothing is filled.
     * @return number of cells filled
     */
    static int flood(World world, StructureShape s, Vector3i origin, Rotation rotation, int waterlineY) {
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            java.util.Set<Vector3i> cells = worldCells(s, origin, rotation, waterlineY);
            List<Vector3i> todo = new ArrayList<>();
            Neighbour best = null;
            for (Vector3i p : cells) {
                if (BalloonManager.outOfHeight(p.y) || !reader.hasStorageInMemory(p.x, p.y, p.z)) {
                    continue;
                }
                if (reader.getFluidId(p.x, p.y, p.z) == Fluid.EMPTY_ID && !solid(reader, p)) {
                    todo.add(p);
                }
                for (int[] d : DIRS) {
                    Vector3i n = new Vector3i(p.x + d[0], p.y + d[1], p.z + d[2]);
                    if (!cells.contains(n) && !BalloonManager.outOfHeight(n.y) && reader.hasStorageInMemory(n.x, n.y, n.z)
                            && isWaterCell(reader, n.x, n.y, n.z)) {
                        int id = reader.getFluidId(n.x, n.y, n.z);
                        Fluid nf = Fluid.getAssetMap().getAsset(id);
                        boolean src = nf != null && nf.getId().endsWith("Source");
                        best = betterNeighbour(best, new Neighbour(id, reader.getFluidLevel(n.x, n.y, n.z), src));
                    }
                }
            }
            if (best == null) {
                // No neighbouring water (boat out of the water): never create water on dry land.
                LOGGER.at(Level.INFO).log("Bateau : aucune eau voisine, cale non remplie");
                return 0;
            }
            int fluidId = best.fluidId();
            byte level = best.level();
            int filled = 0;
            for (Vector3i p : todo) {
                if (SectionBlocks.setFluidAt(world.getChunkStore(), p.x, p.y, p.z, fluidId, level)) {
                    filled++;
                }
            }
            Fluid chosen = Fluid.getAssetMap().getAsset(fluidId);
            LOGGER.at(Level.INFO).log("Bateau : %d case(s) immergée(s) remplie(s) d'eau (fluide %s, niveau %d)", filled,
                    chosen != null ? chosen.getId() : String.valueOf(fluidId), (int) level);
            return filled;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Bateau : remplissage de l'eau impossible");
            Telemetry.error("steamboat.water", e, null, null);
            return 0;
        }
    }

    /** Empties the fluid of every submerged cell (Fluid.EMPTY_ID, level 0) but the waterlogged blocks' cells. @return number of cells emptied */
    static int drain(World world, StructureShape s, Vector3i origin, Rotation rotation, int waterlineY) {
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            int drained = 0;
            java.util.Set<Vector3i> kept = new java.util.HashSet<>();
            for (Vector3i c : waterloggedCells(s, waterlineY)) {
                kept.add(rotation.rotateYaw(new Vector3i(c), new Vector3i()).add(origin));
            }
            for (Vector3i p : worldCells(s, origin, rotation, waterlineY)) {
                if (kept.contains(p) || BalloonManager.outOfHeight(p.y) || !reader.hasStorageInMemory(p.x, p.y, p.z)
                        || reader.getFluidId(p.x, p.y, p.z) == Fluid.EMPTY_ID) {
                    continue;
                }
                if (SectionBlocks.setFluidAt(world.getChunkStore(), p.x, p.y, p.z, Fluid.EMPTY_ID, (byte) 0)) {
                    drained++;
                }
            }
            LOGGER.at(Level.INFO).log("Bateau : %d case(s) immergée(s) vidée(s) de leur eau", drained);
            return drained;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Bateau : vidage de l'eau impossible");
            Telemetry.error("steamboat.water", e, null, null);
            return 0;
        }
    }

    private static boolean solid(SectionReader r, Vector3i p) {
        BlockType bt = SectionBlocks.typeAt(r, p.x, p.y, p.z);
        return bt != null && bt.getMaterial() == BlockMaterial.Solid;
    }

    private static java.util.Set<Vector3i> worldCells(StructureShape s, Vector3i origin, Rotation rotation, int waterlineY) {
        java.util.Set<Vector3i> out = new java.util.LinkedHashSet<>();
        for (Vector3i c : submergedCells(s, waterlineY)) {
            out.add(rotation.rotateYaw(new Vector3i(c), new Vector3i()).add(origin));
        }
        return out;
    }
}
