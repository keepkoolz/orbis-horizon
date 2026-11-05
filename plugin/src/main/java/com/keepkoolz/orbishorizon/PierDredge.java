package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.keepkoolz.orbishorizon.api.BuiltBlocks;
import org.joml.Vector3i;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The basin dug in front of the pier end of a boat station placed by a player: the corridor of the shuttle (pier frame, x from -8 to
 * 8, z from 1 to 20) is deepened in a natural bowl, so a pier set near a beach still has water for the shuttle.
 *
 * Profile: the depth below the water surface, counted in water cells including the surface cell, is
 * max(2, round(4 * sqrt(1 - (x / 9)^2))) for x in the pier frame: 4 on the centre line, a half ellipse toward the sides, never
 * under 2 (what the corridor already needs: the surface cell and the one under it). The first two rows next to the pier (z 1 and 2)
 * keep the depth 2 so the posts keep their feet. Cells already water stay, the seabed is never raised. Only natural blocks are dug.
 *
 * {@link #plan} is pure (nothing is written); {@link #dig} writes, on the world thread, once every placement test has passed.
 */
final class PierDredge {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int SET_NO_PARTICLES = 4;

    /** Half width of the zone (shuttle length 11 + 3 on each side = 17 columns) and its length toward the sea. */
    static final int HALF_WIDTH = 8;
    static final int LENGTH = 20;
    /** Depth on the centre line, depth at the edges and rows next to the pier, in water cells. */
    static final int CENTRE_DEPTH = 4;
    static final int MIN_DEPTH = 2;
    /** Rows next to the pier kept at the minimum depth. */
    static final int SHALLOW_ROWS = 2;
    /** Semi axis of the half ellipse, in columns (a little beyond the edge so that the edge column is not cut to the minimum by the ellipse itself). */
    private static final double SEMI_AXIS = 9.0;

    private PierDredge() {
    }

    /** What a cell holds, for the plan. */
    enum Kind { WATER, AIR, BLOCK, LIQUID, UNLOADED }

    /** Content of a cell: water, air, a block (id), a liquid that is not water (id) or a cell that cannot be read. */
    record Cell(Kind kind, String id) {
        static final Cell WATER = new Cell(Kind.WATER, null);
        static final Cell AIR = new Cell(Kind.AIR, null);
    }

    /** Reads cells by world coordinates. */
    @FunctionalInterface
    interface Cells {
        Cell at(int x, int y, int z);
    }

    /** Cells to dig, or the first cell that prevents it (fault, with what it holds). */
    record Plan(Set<Vector3i> dig, Vector3i fault, Cell faultCell) {
        boolean ok() {
            return fault == null;
        }
    }

    /** Target depth of the column (x, z) of the pier frame, in water cells including the surface cell. Pure. */
    static int depth(int x, int z) {
        if (z <= SHALLOW_ROWS) {
            return MIN_DEPTH;
        }
        double t = Math.min(1.0, Math.abs(x) / SEMI_AXIS);
        return Math.max(MIN_DEPTH, (int) Math.round(CENTRE_DEPTH * Math.sqrt(1.0 - t * t)));
    }

    /** True when a block may be dug: natural terrain or vegetation, never a block of a player or of the mod. */
    static boolean diggable(String blockId) {
        if (!BuiltBlocks.isNatural(blockId)) {
            return false;
        }
        String n = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        return !n.startsWith("Hotair_Balloon_");
    }

    /** The cell reading as the placement tests do (0 empty, 1 water, 2 anything else). */
    static DeployPlacement.CellState state(Cells cells) {
        return (x, y, z) -> switch (cells.at(x, y, z).kind()) {
            case WATER -> 1;
            case AIR -> 0;
            default -> 2;
        };
    }

    /**
     * Cells to dig for a pier end and its rotation, surfaceY being the height of the water surface cell. Rows run from the pier
     * outward (z 1 to 20), x from -8 to 8. A cell of the profile that is not water is dug when it is air or a diggable block that no
     * registered structure holds (inStructure); a block of another kind, a liquid that is not water or a cell that cannot be read
     * stops the plan with that cell. Cells above the surface are not part of the plan (the corridor test reads them). Pure.
     */
    static Plan plan(Vector3i pierEnd, Rotation rotation, int surfaceY, Cells cells, Predicate<String> diggable,
                     Predicate<Vector3i> inStructure) {
        Set<Vector3i> dig = new LinkedHashSet<>();
        for (int lz = 1; lz <= LENGTH; lz++) {
            for (int lx = -HALF_WIDTH; lx <= HALF_WIDTH; lx++) {
                Vector3i flat = rotation.rotateYaw(new Vector3i(lx, 0, lz), new Vector3i());
                int x = pierEnd.x + flat.x;
                int z = pierEnd.z + flat.z;
                int depth = depth(lx, lz);
                for (int y = surfaceY; y > surfaceY - depth; y--) {
                    Cell c = cells.at(x, y, z);
                    switch (c.kind()) {
                        case WATER -> {
                        }
                        case AIR -> dig.add(new Vector3i(x, y, z));
                        case BLOCK -> {
                            Vector3i p = new Vector3i(x, y, z);
                            if (!diggable.test(c.id()) || inStructure.test(p)) {
                                return new Plan(dig, p, c);
                            }
                            dig.add(p);
                        }
                        default -> {
                            return new Plan(dig, new Vector3i(x, y, z), c);
                        }
                    }
                }
            }
        }
        return new Plan(dig, null, null);
    }

    /**
     * Fluid and level to dig with: a water cell next to a dug cell (same layer, outside the dug cells), a source fluid first then the
     * highest level, else the water at the foot of the pier end column. waterAt gives the water of a cell or null when it is not
     * readable water. Null when there is none. Pure.
     */
    static SteamboatWater.Neighbour chooseWater(Set<Vector3i> dig, Vector3i foot, Function<Vector3i, SteamboatWater.Neighbour> waterAt) {
        SteamboatWater.Neighbour best = null;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Vector3i p : dig) {
            for (int[] d : dirs) {
                Vector3i n = new Vector3i(p.x + d[0], p.y, p.z + d[1]);
                if (!dig.contains(n)) {
                    SteamboatWater.Neighbour w = waterAt.apply(n);
                    if (w != null) {
                        best = SteamboatWater.betterNeighbour(best, w);
                    }
                }
            }
        }
        if (best == null && foot != null) {
            best = waterAt.apply(foot);
        }
        return best;
    }

    // ------------------------------------------------------------------ world

    /** Reads the world: a cell out of height or in a section that is not loaded is UNLOADED. */
    static Cells worldCells(World world) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        return (x, y, z) -> {
            if (BalloonManager.outOfHeight(y) || !reader.hasStorageInMemory(x, y, z)) {
                return new Cell(Kind.UNLOADED, null);
            }
            BlockType bt = SectionBlocks.typeAt(reader, x, y, z);
            String id = bt == null ? null : (bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId());
            boolean solid = bt != null && bt.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid;
            String liquid = BalloonManager.liquidAt(reader, x, y, z);
            if (solid) {
                return new Cell(Kind.BLOCK, id);
            }
            if (liquid != null) {
                return SteamboatWater.isWaterCell(reader, x, y, z, liquid) ? Cell.WATER : new Cell(Kind.LIQUID, liquid);
            }
            return id == null || BlockType.EMPTY_KEY.equals(id) || "Empty".equals(id) ? Cell.AIR : new Cell(Kind.BLOCK, id);
        };
    }

    /** The water of a world cell (fluid, level, source) or null when it is not water or not loaded. */
    static SteamboatWater.Neighbour worldWater(World world, Vector3i p) {
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            if (BalloonManager.outOfHeight(p.y) || !reader.hasStorageInMemory(p.x, p.y, p.z) || !SteamboatWater.isWaterCell(reader, p.x, p.y, p.z)) {
                return null;
            }
            int id = reader.getFluidId(p.x, p.y, p.z);
            Fluid f = Fluid.getAssetMap().getAsset(id);
            return new SteamboatWater.Neighbour(id, reader.getFluidLevel(p.x, p.y, p.z), f != null && f.getId().endsWith("Source"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * World thread. Removes the block of each cell (no particles) then writes the water. Nothing is checked here, the plan did it all.
     * Returns false when a cell could not be written (section no longer loaded or an error, logged with Telemetry.error): the cells
     * already written stay, they hold water like the rest of the basin.
     */
    static boolean dig(World world, Set<Vector3i> cells, SteamboatWater.Neighbour water, Vector3i quay) {
        int done = 0;
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            for (Vector3i p : cells) {
                if (reader.getBlock(p.x, p.y, p.z) != 0) {
                    SectionBlocks.setAt(world.getChunkStore(), p.x, p.y, p.z, BlockType.EMPTY_KEY, SET_NO_PARTICLES);
                }
                if (!SectionBlocks.setFluidAt(world.getChunkStore(), p.x, p.y, p.z, water.fluidId(), water.level())) {
                    throw new IllegalStateException("section not loaded at (" + p.x + ", " + p.y + ", " + p.z + ")");
                }
                done++;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pier basin at (%d, %d, %d): only %d of %d cell(s) dug", quay.x, quay.y, quay.z, done,
                    cells.size());
            Telemetry.error("deploy", e, Telemetry.kindId(Deployables.BOAT_STATION_PIER), "dredge");
            return false;
        }
        LOGGER.at(Level.INFO).log("Pier basin dug: %d cell(s) in front of the pier end at (%d, %d, %d)", done, quay.x, quay.y, quay.z);
        return true;
    }
}
