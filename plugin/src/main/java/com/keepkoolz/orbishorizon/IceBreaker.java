package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocksound.config.BlockSoundSet;
import com.hypixel.hytale.protocol.BlockSoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.function.IntFunction;

/**
 * Ice rules of the ice-breaking steamboat. The column, hull and placement rules read the world through a function of the cell
 * kind, so they run outside the game. {@link #breakUnderHull} and {@link #breakFootprint} write the world: they remove the floe
 * ice a boat touches and refill the water.
 */
final class IceBreaker {
    /** What a world cell is, for the ice rules. */
    enum Kind { WATER, ICE, AIR, SOLID, UNLOADED }

    /**
     * Highest ice, in blocks above the waterline, the boat still breaks. A column whose ice reaches the cell above this height is
     * an iceberg: it blocks the hull and the pathfinding avoids it.
     */
    static final int MAX_FLOE_HEIGHT = 2;
    private static final Set<String> BREAKABLE = Set.of("Rock_Ice", "Rock_Ice_Permafrost");

    private IceBreaker() {
    }

    /** True for the ice blocks the boat breaks (sea ice and permafrost ice, not icicles). */
    static boolean isBreakableIce(String blockId) {
        return blockId != null && BREAKABLE.contains(blockId);
    }

    /**
     * Probe rule of one column from the lowest hull cell (at least one block under the surface) up to the hull top. The bottom,
     * surface and top are the ones of the hull the flight tests ({@link AirshipManager.Hull#bottomY}, surfaceY, topY), so the probe
     * and {@link #hullCellBlocks} read every column the same way. Ice at surfaceY + MAX_FLOE_HEIGHT + 1 means an
     * iceberg, which blocks; otherwise the ice is a floe the hull breaks, up to surfaceY + MAX_FLOE_HEIGHT. An unloaded cell makes
     * the column unknown unless a blocking cell is found.
     */
    static WaterRoute.Cell column(IntFunction<Kind> at, int bottomY, int surfaceY, int topY) {
        boolean iceberg = at.apply(surfaceY + MAX_FLOE_HEIGHT + 1) == Kind.ICE;
        int floeTop = surfaceY + MAX_FLOE_HEIGHT;
        boolean unknown = false;
        for (int y = Math.min(bottomY, surfaceY - 1); y <= topY; y++) {
            Kind k = at.apply(y);
            if (k == Kind.UNLOADED) {
                unknown = true;
                continue;
            }
            boolean open = (k == Kind.ICE && !iceberg && y <= floeTop) || k == (y <= surfaceY ? Kind.WATER : Kind.AIR);
            if (!open) {
                return WaterRoute.Cell.BLOCKED;
            }
        }
        return unknown ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.WATER;
    }

    /** Reads the kind of one world cell. A function, so the hull rules can run on a fake world in tests. */
    @FunctionalInterface
    interface CellReader {
        Kind at(int x, int y, int z);
    }

    /** Columns around the hull footprint that always break, in blocks. */
    static final int ICE_MARGIN = 1;
    /** Outer reach of the ragged margin, in blocks. Columns between {@link #ICE_MARGIN} and this break only where the noise says so. */
    static final int ICE_MARGIN_RAGGED = 2;
    /** Noise cell size in blocks, so the ragged edge forms bites and bulges rather than single-block speckle. */
    private static final int NOISE_CELL = 2;

    /** Tells whether a world column (x, z) belongs to the hull footprint. */
    @FunctionalInterface
    interface ColumnTest {
        boolean has(int x, int z);
    }

    /**
     * True when the column (x, z), outside the footprint, lies in the broken margin: always within 1.5 blocks of a footprint column
     * (distance 1), and within 2.5 blocks only where a deterministic noise of the world coordinates says so (about half of them).
     * The answer depends only on the footprint and the coordinates, so the edge is stable between ticks and trips.
     */
    static boolean inIceMargin(ColumnTest footprint, int x, int z) {
        if (footprint.has(x, z)) {
            return false;
        }
        int reach = ICE_MARGIN_RAGGED;
        int best = Integer.MAX_VALUE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (footprint.has(x + dx, z + dz)) {
                    best = Math.min(best, dx * dx + dz * dz);
                }
            }
        }
        if (best <= 2 * ICE_MARGIN * ICE_MARGIN) { // within 1.5 blocks: distance 1 or diagonal
            return true;
        }
        return best <= 6 && marginNoise(x, z); // within 2.5 blocks
    }

    /** Deterministic noise of a column, constant over blocks of {@link #NOISE_CELL} x {@link #NOISE_CELL}. True about half of the time. */
    static boolean marginNoise(int x, int z) {
        long h = Math.floorDiv(x, NOISE_CELL) * 0x9E3779B97F4A7C15L + Math.floorDiv(z, NOISE_CELL) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 1L) != 0;
    }

    /**
     * True when a margin cell of the ice-breaker breaks: breakable ice up to surfaceY + MAX_FLOE_HEIGHT in a floe column (no ice at
     * surfaceY + MAX_FLOE_HEIGHT + 1) that floats, that is
     * with water (or ice over water) within {@link #BELOW_SCAN} cells under it, or at or under surfaceY with a water neighbour.
     * Ice on solid ground, such as a glacier or shore ice, stays. Reads only what each test needs.
     */
    static boolean marginCellBreaks(CellReader r, int x, int y, int z, int surfaceY) {
        if (y > surfaceY + MAX_FLOE_HEIGHT || r.at(x, y, z) != Kind.ICE || icebergAt(r, x, z, surfaceY)) {
            return false;
        }
        for (int d = 1; d <= BELOW_SCAN; d++) {
            Kind k = r.at(x, y - d, z);
            if (k == Kind.WATER) {
                return true;
            }
            if (k != Kind.ICE && k != Kind.AIR) {
                break;
            }
        }
        if (y > surfaceY) {
            return false;
        }
        for (int[] d : DIRS) {
            if (r.at(x + d[0], y + d[1], z + d[2]) == Kind.WATER) {
                return true;
            }
        }
        return false;
    }

    /** True when the column (x, z) is an iceberg: ice at the cell above the highest floe height. */
    private static boolean icebergAt(CellReader r, int x, int z, int surfaceY) {
        return r.at(x, surfaceY + MAX_FLOE_HEIGHT + 1, z) == Kind.ICE;
    }

    /** Cells scanned under a wet empty cell to find the water or ice it floats on. */
    static final int BELOW_SCAN = 4;

    /**
     * True when a hull cell of the ice-breaker is blocked. Ice blocks in an iceberg column (ice at surfaceY + MAX_FLOE_HEIGHT + 1) or above the floe height. A wet empty cell
     * is a cell the hull has broken, and counts as free when the column under it reaches water or ice within {@link #BELOW_SCAN}
     * cells. The cells above and below are read only when the cell needs them.
     */
    static boolean hullCellBlocks(CellReader r, int x, int y, int z, boolean wet, int surfaceY) {
        Kind cell = r.at(x, y, z);
        switch (cell) {
            case UNLOADED:
                return false;
            case ICE:
                return y > surfaceY + MAX_FLOE_HEIGHT || icebergAt(r, x, z, surfaceY);
            case WATER:
                return !wet;
            case AIR:
                return wet && !floatsOver(r, x, y, z);
            default:
                return true;
        }
    }

    /** True when the empty cells under this one end on water or ice (or an unloaded cell) within {@link #BELOW_SCAN} cells. */
    private static boolean floatsOver(CellReader r, int x, int y, int z) {
        for (int d = 1; d <= BELOW_SCAN; d++) {
            Kind k = r.at(x, y - d, z);
            if (k == Kind.AIR) {
                continue;
            }
            return k == Kind.WATER || k == Kind.ICE || k == Kind.UNLOADED;
        }
        return false;
    }

    /**
     * Placement state of a world cell for a boat set down or moored (same codes as {@link DeployPlacement.CellState}: 0 empty, 1
     * water, 2 blocked), using the hull rule: floe ice and a broken wet cell count as water under the waterline and as empty above.
     */
    static int placementState(CellReader r, int x, int y, int z, boolean wet, int surfaceY) {
        Kind cell = r.at(x, y, z);
        switch (cell) {
            case WATER:
                return 1;
            case ICE:
                if (y > surfaceY + MAX_FLOE_HEIGHT || icebergAt(r, x, z, surfaceY)) {
                    return 2;
                }
                return wet ? 1 : 0;
            case AIR:
                return wet && floatsOver(r, x, y, z) ? 1 : 0;
            default:
                return 2;
        }
    }

    /** Kind of one world cell. Out of height counts as solid, a section that is not in memory as unloaded. */
    static Kind kindAt(SectionReader r, int x, int y, int z) {
        if (BalloonManager.outOfHeight(y)) {
            return Kind.SOLID;
        }
        if (!r.hasStorageInMemory(x, y, z)) {
            return Kind.UNLOADED;
        }
        BlockType bt = SectionBlocks.typeAt(r, x, y, z);
        if (bt != null && isBreakableIce(bt.getId())) {
            return Kind.ICE;
        }
        String liquid = BalloonManager.liquidAt(r, x, y, z);
        if (SteamboatWater.isWaterCell(r, x, y, z, liquid)) {
            return Kind.WATER;
        }
        if (liquid != null || BalloonManager.blocksBalloon(r, x, y, z)) {
            return Kind.SOLID;
        }
        return Kind.AIR;
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static long lastErrorLogMs;

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Ice cells found under a hull pose, allocated on the first one. */
    private static final class Found {
        LongOpenHashSet seen;
        ArrayList<int[]> cells;

        void add(int x, int y, int z) {
            if (seen == null) {
                seen = new LongOpenHashSet();
                cells = new ArrayList<>();
            }
            if (seen.add(key(x, y, z))) {
                cells.add(new int[] {x, y, z});
            }
        }
    }

    /** True when the cell holds breakable ice (a loaded cell in the height range). */
    private static boolean isIceAt(SectionReader r, int x, int y, int z) {
        if (BalloonManager.outOfHeight(y) || !r.hasStorageInMemory(x, y, z)) {
            return false;
        }
        BlockType bt = SectionBlocks.typeAt(r, x, y, z);
        return bt != null && isBreakableIce(bt.getId());
    }

    /**
     * Breaks the floe ice cells the hull touches at the flight's pose, once per pose: each becomes empty (settings 0, so the game
     * spawns the break particles of the removed block), plays the block's break sound and, at or under the waterline, takes the
     * water of its best neighbouring water cell. Iceberg columns are left alone (they block the hull instead). No item drops.
     * @return number of cells broken
     */
    static int breakUnderHull(Store<EntityStore> store, World world, AirshipManager.Hull h, AirshipFlight f) {
        double px = f.pos.x;
        double py = f.pos.y;
        double pz = f.pos.z;
        double theta = f.theta;
        if (f.iceBrokenPose && f.iceX == px && f.iceY == py && f.iceZ == pz && f.iceTheta == theta) {
            return 0;
        }
        f.iceBrokenPose = true;
        f.iceX = px;
        f.iceY = py;
        f.iceZ = pz;
        f.iceTheta = theta;
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            int topY = h.topY(py);
            int surfaceY = h.surfaceY(py);
            int floeTop = surfaceY + MAX_FLOE_HEIGHT;
            Found found = new Found();
            LongOpenHashSet columns = new LongOpenHashSet();
            int[] lowest = {Integer.MAX_VALUE};
            AirshipManager.blocked((x, y, z, wet) -> {
                columns.add(columnKey(x, z));
                if (y <= floeTop && isIceAt(reader, x, y, z) && !isIceAt(reader, x, floeTop + 1, z)) {
                    found.add(x, y, z);
                    lowest[0] = Math.min(lowest[0], y);
                }
                return false;
            }, h, px, py, pz, theta, Integer.MAX_VALUE);
            if (found.cells != null) {
                // the margin widens the channel only where the hull itself is breaking ice
                addMargin(reader, columns, found, lowest[0], surfaceY, topY);
            }
            return found.cells == null ? 0 : breakCells(store, world, reader, found.cells, surfaceY);
        } catch (RuntimeException e) {
            logError(e, f.kind);
            return 0;
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Adds the floe ice of the margin ring around the footprint columns, over the y range of the hull slice. Only the ring of
     * columns within the ragged reach is read, and a column is read only when the margin rule keeps it.
     */
    private static void addMargin(SectionReader reader, LongOpenHashSet footprint, Found found, int lowY, int surfaceY, int topY) {
        if (footprint.isEmpty()) {
            return;
        }
        ColumnTest inside = (x, z) -> footprint.contains(columnKey(x, z));
        CellReader cells = (x, y, z) -> kindAt(reader, x, y, z);
        LongOpenHashSet tried = new LongOpenHashSet();
        for (long k : footprint.toLongArray()) {
            int fx = (int) (k >> 32);
            int fz = (int) k;
            for (int dx = -ICE_MARGIN_RAGGED; dx <= ICE_MARGIN_RAGGED; dx++) {
                for (int dz = -ICE_MARGIN_RAGGED; dz <= ICE_MARGIN_RAGGED; dz++) {
                    int x = fx + dx;
                    int z = fz + dz;
                    if (inside.has(x, z) || !tried.add(columnKey(x, z)) || !inIceMargin(inside, x, z)) {
                        continue;
                    }
                    if (!hasIce(reader, x, lowY, Math.min(topY, surfaceY + MAX_FLOE_HEIGHT), z)) {
                        continue;
                    }
                    for (int y = lowY; y <= Math.min(topY, surfaceY + MAX_FLOE_HEIGHT); y++) {
                        if (marginCellBreaks(cells, x, y, z, surfaceY)) {
                            found.add(x, y, z);
                        }
                    }
                }
            }
        }
    }

    /** True when the column holds breakable ice between two heights. */
    private static boolean hasIce(SectionReader r, int x, int lowY, int topY, int z) {
        for (int y = lowY; y <= topY; y++) {
            if (isIceAt(r, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Breaks the floe ice inside the footprint of a boat about to be pasted at this pose (mooring, landing), the same way as
     * {@link #breakUnderHull}. Never throws.
     */
    static void breakFootprint(Store<EntityStore> store, World world, Deployables.Kind kind, StructureShape shape, Vector3i origin,
                               Rotation rotation) {
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            Integer line = AirshipManager.spec(kind).waterlineY;
            if (line == null) {
                return;
            }
            int surfaceY = origin.y + line;
            int floeTop = surfaceY + MAX_FLOE_HEIGHT;
            Found found = new Found();
            for (StructureShape.Cell c : shape.cells()) {
                Vector3i p = c.rotated(rotation).add(origin);
                if (p.y <= floeTop && isIceAt(reader, p.x, p.y, p.z) && !isIceAt(reader, p.x, floeTop + 1, p.z)) {
                    found.add(p.x, p.y, p.z);
                }
            }
            if (found.cells != null) {
                breakCells(store, world, reader, found.cells, surfaceY);
            }
        } catch (RuntimeException e) {
            logError(e, kind);
        }
    }

    /** Height of the lowest cell of a shape, relative to its origin. */
    static int minCellY(StructureShape shape) {
        int bottom = Integer.MAX_VALUE;
        for (StructureShape.Cell c : shape.cells()) {
            bottom = Math.min(bottom, c.y());
        }
        return bottom;
    }

    /** Height of the highest cell of a shape, relative to its origin. */
    static int maxCellY(StructureShape shape) {
        int top = Integer.MIN_VALUE;
        for (StructureShape.Cell c : shape.cells()) {
            top = Math.max(top, c.y());
        }
        return top;
    }

    /**
     * Removes the cells bottom-up, so that a cell above another one already filled takes its water. Sound per cell; water for the
     * cells at or under surfaceY.
     */
    private static int breakCells(Store<EntityStore> store, World world, SectionReader reader, List<int[]> cells, int surfaceY) {
        cells.sort((a, b) -> Integer.compare(a[1], b[1]));
        int count = 0;
        for (int[] c : cells) {
            BlockType type = SectionBlocks.typeAt(reader, c[0], c[1], c[2]);
            if (type == null || !SectionBlocks.setAt(world.getChunkStore(), c[0], c[1], c[2], BlockType.EMPTY_KEY, 0)) {
                continue;
            }
            count++;
            playBreak(store, type, c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
            if (c[1] <= surfaceY) {
                fillWater(reader, world, c);
            }
        }
        return count;
    }

    private static void logError(RuntimeException e, Deployables.Kind kind) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogMs >= 60_000L) {
            lastErrorLogMs = now;
            LOGGER.at(Level.WARNING).withCause(e).log("Bris de la glace ignoré");
        }
        Telemetry.error("boat.icebreak", e, Telemetry.kindId(kind), null);
    }

    private static void playBreak(Store<EntityStore> store, BlockType type, double x, double y, double z) {
        BlockSoundSet set = BlockSoundSet.getAssetMap().getAsset(type.getBlockSoundSetIndex());
        if (set == null) {
            return;
        }
        int idx = set.getSoundEventIndices().getOrDefault(BlockSoundEvent.Break, 0);
        if (idx > 0) {
            SoundUtil.playSoundEvent3d(idx, SoundCategory.SFX, x, y, z, store);
        }
    }

    /**
     * Fills a broken cell with the best water of its six neighbours that are water. A neighbour broken just before has already been
     * filled, so a stack of broken cells takes its water from the bottom. None means no water.
     */
    private static void fillWater(SectionReader reader, World world, int[] c) {
        SteamboatWater.Neighbour best = null;
        for (int[] d : DIRS) {
            int x = c[0] + d[0];
            int y = c[1] + d[1];
            int z = c[2] + d[2];
            if (BalloonManager.outOfHeight(y) || !reader.hasStorageInMemory(x, y, z)
                    || !SteamboatWater.isWaterCell(reader, x, y, z)) {
                continue;
            }
            int id = reader.getFluidId(x, y, z);
            Fluid f = Fluid.getAssetMap().getAsset(id);
            boolean src = f != null && f.getId().endsWith("Source");
            best = SteamboatWater.betterNeighbour(best, new SteamboatWater.Neighbour(id, reader.getFluidLevel(x, y, z), src));
        }
        if (best != null) {
            SectionBlocks.setFluidAt(world.getChunkStore(), c[0], c[1], c[2], best.fluidId(), best.level());
        }
    }
}
