package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Pure planning of the generated stations from the world seed and a terrain probe. */
final class WorldStationPlanner {
    /**
     * A station to build: its kind, its origin column, the paste rotation and whether it is a crossing partner.
     * The fallbacks exclude the main site and are ordered by preference.
     */
    record PlannedSite(String kind, int x, int z, Rotation rotation, boolean partner, List<Site> fallbacks) {
        PlannedSite(String kind, int x, int z, Rotation rotation, boolean partner) {
            this(kind, x, z, rotation, partner, List.of());
        }
    }

    /** An alternative origin column and paste rotation for a station, tried when the main site is refused. */
    record Site(int x, int z, Rotation rotation) {
    }

    static final int MAX_SITES = 4;
    static final int PARTNER_FALLBACKS = 3;
    static final int CELL = 2048;
    static final int SPACING = 1500;
    static final int SHORE_STEP = 32;
    static final int SEA_AHEAD = 40;
    static final int ZONE_STEP = 128;
    static final int ZONE_MARGIN = 128;
    static final int SEA_NEAR = 256;
    static final int CROSS_STEP = 16;
    static final int CROSS_MIN = 200;
    static final int CROSS_MAX = WaterRoute.MAX_LENGTH - 200;
    static final int PARTNER_REUSE = 400;
    static final int PORTAL_HALF = 27;
    static final int RING_MIN = 40;
    static final int RING_MAX = 90;
    static final int RING_STEP = 8;
    static final int MAX_SLOPE = 2;
    static final int LATERAL_STEP = 8;
    static final int LATERAL_MAX = 48;
    /** Minimum Chebyshev distance between a fallback and the main site or another fallback (twice the lateral repair reach). */
    static final int FALLBACK_GAP = 2 * LATERAL_MAX;
    private static final int SHORE_WALK = 72;
    /**
     * Floor of the shuttle (Steamboat_Public.prefab.json, cells at prefab y 0), prefab frame before rotation. Rows are z -3..5,
     * columns x -1..1, a hash is a column with a floor cell and a dot is an empty column.
     */
    private static final String[] SHUTTLE_FLOOR = {".#.", "###", "###", "###", "###", "###", "###", ".#.", ".#."};
    private static final int FOOTPRINT_STEP = 4;
    private static final Rotation[] ROTATIONS = {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy};

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private WorldStationPlanner() {
    }

    static int cellOf(int blockCoord) {
        return Math.floorDiv(blockCoord, CELL);
    }

    /** The paste rotation that sends the prefab +z side (the pier) toward the cardinal direction (dx, dz). */
    static Rotation facing(int dx, int dz) {
        for (Rotation r : new Rotation[] {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            Vector3i v = r.rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
            if (v.x == Integer.signum(dx) && v.z == Integer.signum(dz)) {
                return r;
            }
        }
        return Rotation.None;
    }

    /**
     * Plans the boat station of a cell and its crossing partners.
     * @param existingBoat positions {x, z} of the boat stations already planned or built
     * @return the cell station first, then its partners, or an empty list
     */
    static List<PlannedSite> planSeaCell(long seed, int cellX, int cellZ, TerrainProbe t, List<int[]> existingBoat) {
        SplittableRandom rnd = new SplittableRandom(seed ^ (cellX * 0x9E3779B97F4A7C15L) ^ (cellZ * 0xC2B2AE3D27D4EB4FL) ^ 0x5EA);
        int baseX = cellX * CELL, baseZ = cellZ * CELL;
        int drawX = baseX + rnd.nextInt(CELL), drawZ = baseZ + rnd.nextInt(CELL);

        List<int[]> seaSamples = seaSamples(t, cellX, cellZ);
        if (seaSamples.isEmpty()) {
            return new ArrayList<>();
        }
        List<long[]> candidates = new ArrayList<>();
        for (int x = baseX; x < baseX + CELL; x += SHORE_STEP) {
            for (int z = baseZ; z < baseZ + CELL; z += SHORE_STEP) {
                if (!nearSample(seaSamples, x, z)) {
                    continue;
                }
                if (!SeaRules.isLand(t, x, z)) {
                    continue;
                }
                int[] shore = shoreline(t, x, z);
                if (shore == null) {
                    continue;
                }
                int sx = shore[0], sz = shore[1];
                int[] dir = {shore[2], shore[3]};
                long dx = sx - drawX, dz = sz - drawZ;
                long d = dx * dx + dz * dz;
                candidates.add(new long[] {d, sx, sz, dir[0], dir[1]});
            }
        }
        candidates.sort((a, b) -> Long.compare(a[0], b[0]));
        List<PlannedSite> sites = new ArrayList<>();
        int bestX = 0, bestZ = 0;
        Rotation rotation = null;
        List<Site> fallbacks = new ArrayList<>();
        for (long[] c : candidates) {
            if (fallbacks.size() >= MAX_SITES - 1) {
                break;
            }
            Rotation r = facing((int) c[3], (int) c[4]);
            int cx = (int) c[1], cz = (int) c[2];
            if (rotation == null) {
                // The main site is the closest fitting candidate that keeps its distance from the other boat stations.
                if (!near(existingBoat, cx, cz, SPACING) && boatSiteFits(t, cx, cz, r)) {
                    bestX = cx;
                    bestZ = cz;
                    rotation = r;
                }
            } else if (farFromSites(fallbacks, bestX, bestZ, cx, cz)
                    && !near(existingBoat, cx, cz, SPACING) && boatSiteFits(t, cx, cz, r)) {
                fallbacks.add(new Site(cx, cz, r));
            }
        }
        if (rotation == null) {
            return sites;
        }
        sites.add(new PlannedSite(Deployables.BOAT_STATION_KIND, bestX, bestZ, rotation, false, List.copyOf(fallbacks)));

        Vector3i end = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], Deployables.BOAT_STATION_PIER_END[1],
                Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
        int startX = bestX + end.x, startZ = bestZ + end.z;
        List<int[]> known = new ArrayList<>(existingBoat);
        known.add(new int[] {bestX, bestZ});
        for (int[] d : DIRECTIONS) {
            PlannedSite partner = crossing(t, startX, startZ, d, known);
            if (partner != null) {
                sites.add(partner);
                known.add(new int[] {partner.x(), partner.z()});
            }
        }
        return sites;
    }

    /**
     * Points {x, z} every ZONE_STEP blocks over the cell widened by ZONE_MARGIN whose zone is an ocean or a shore. Only the
     * zone lookup is used, so a cell without coast is told apart cheaply.
     */
    static List<int[]> seaSamples(TerrainProbe t, int cellX, int cellZ) {
        int baseX = cellX * CELL, baseZ = cellZ * CELL;
        List<int[]> samples = new ArrayList<>();
        for (int x = baseX - ZONE_MARGIN; x <= baseX + CELL + ZONE_MARGIN; x += ZONE_STEP) {
            for (int z = baseZ - ZONE_MARGIN; z <= baseZ + CELL + ZONE_MARGIN; z += ZONE_STEP) {
                if (SeaRules.isSeaZone(t.zone(x, z))) {
                    samples.add(new int[] {x, z});
                }
            }
        }
        return samples;
    }

    /** True when the column is within SEA_NEAR blocks (Chebyshev distance) of a sea sample. */
    private static boolean nearSample(List<int[]> samples, int x, int z) {
        for (int[] s : samples) {
            if (Math.abs(s[0] - x) <= SEA_NEAR && Math.abs(s[1] - z) <= SEA_NEAR) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a land column, the first cardinal direction with sea within SHORE_STEP + SEA_AHEAD blocks, walked one block at a
     * time to the last land column and checked for sea ahead from there.
     * @return {x, z, dx, dz} of the shoreline column, or null
     */
    private static int[] shoreline(TerrainProbe t, int x, int z) {
        for (int[] d : DIRECTIONS) {
            for (int k = 1; k <= SHORE_STEP + SEA_AHEAD; k++) {
                if (SeaRules.isLand(t, x + d[0] * k, z + d[1] * k)) {
                    continue;
                }
                int sx = x + d[0] * (k - 1), sz = z + d[1] * (k - 1);
                if (seaAhead(t, sx, sz, d[0], d[1])) {
                    return new int[] {sx, sz, d[0], d[1]};
                }
                break;
            }
        }
        return null;
    }

    /** Sea at 8, 16, 24, 32 and 40 blocks from the column in the direction (dx, dz). */
    private static boolean seaAhead(TerrainProbe t, int x, int z, int dx, int dz) {
        for (int k = SEA_AHEAD / 5; k <= SEA_AHEAD; k += SEA_AHEAD / 5) {
            if (!SeaRules.isSea(t, x + dx * k, z + dz * k)) {
                return false;
            }
        }
        return true;
    }

    private static PlannedSite crossing(TerrainProbe t, int startX, int startZ, int[] d, List<int[]> known) {
        int dist = CROSS_STEP;
        if (!SeaRules.isSea(t, startX + d[0] * dist, startZ + d[1] * dist)) {
            return null;
        }
        while (dist <= CROSS_MAX && SeaRules.isSea(t, startX + d[0] * dist, startZ + d[1] * dist)) {
            dist += CROSS_STEP;
        }
        int x = startX + d[0] * dist, z = startZ + d[1] * dist;
        if (dist > CROSS_MAX || dist < CROSS_MIN || SeaRules.isWater(t, x, z)) {
            return null;
        }
        while (SeaRules.isLand(t, x - d[0], z - d[1])) {
            x -= d[0];
            z -= d[1];
        }
        Rotation rotation = facing(-d[0], -d[1]);
        Site main = null;
        if (seaAhead(t, x, z, -d[0], -d[1]) && boatSiteFits(t, x, z, rotation)) {
            main = new Site(x, z, rotation);
        }
        int px = d[1] != 0 ? 1 : 0, pz = d[0] != 0 ? 1 : 0;
        for (int step = LATERAL_STEP; main == null && step <= LATERAL_MAX; step += LATERAL_STEP) {
            for (int sign = 1; main == null && sign >= -1; sign -= 2) {
                main = lateralSite(t, x + px * sign * step, z + pz * sign * step, d, rotation);
            }
        }
        if (main == null || near(known, main.x(), main.z(), PARTNER_REUSE)) {
            return null;
        }
        List<Site> fallbacks = new ArrayList<>();
        for (int step = FALLBACK_GAP; fallbacks.size() < PARTNER_FALLBACKS && step <= FALLBACK_GAP * PARTNER_FALLBACKS; step += FALLBACK_GAP) {
            for (int sign = 1; fallbacks.size() < PARTNER_FALLBACKS && sign >= -1; sign -= 2) {
                Site s = lateralSite(t, x + px * sign * step, z + pz * sign * step, d, rotation);
                if (s != null && !near(known, s.x(), s.z(), PARTNER_REUSE) && farFromSites(fallbacks, main.x(), main.z(), s.x(), s.z())) {
                    fallbacks.add(s);
                }
            }
        }
        return new PlannedSite(Deployables.BOAT_STATION_KIND, main.x(), main.z(), rotation, true, List.copyOf(fallbacks));
    }

    /**
     * From a column offset along the shore, walks across the water in the direction d to the shore, then back over the land to the
     * last land column.
     * @return the site there when sea lies ahead and the boat station fits, or null
     */
    private static Site lateralSite(TerrainProbe t, int startX, int startZ, int[] d, Rotation rotation) {
        int cx = startX, cz = startZ;
        int k = 0;
        while (k <= SHORE_WALK && SeaRules.isWater(t, cx, cz)) {
            cx += d[0];
            cz += d[1];
            k++;
        }
        if (k > SHORE_WALK) {
            return null;
        }
        k = 0;
        while (k <= SHORE_WALK && SeaRules.isLand(t, cx - d[0], cz - d[1])) {
            cx -= d[0];
            cz -= d[1];
            k++;
        }
        if (k > SHORE_WALK || !seaAhead(t, cx, cz, -d[0], -d[1]) || !boatSiteFits(t, cx, cz, rotation)) {
            return null;
        }
        return new Site(cx, cz, rotation);
    }

    /** Whether (x, z) is at least FALLBACK_GAP (Chebyshev) from the main site and from every fallback kept. */
    private static boolean farFromSites(List<Site> fallbacks, int mainX, int mainZ, int x, int z) {
        if (Math.max(Math.abs(x - mainX), Math.abs(z - mainZ)) < FALLBACK_GAP) {
            return false;
        }
        for (Site s : fallbacks) {
            if (Math.max(Math.abs(x - s.x()), Math.abs(z - s.z())) < FALLBACK_GAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the boat station fits at the origin column: the pier end column is sea, and every floor column of the shuttle at its
     * berth is sea with terrain below the sea level. Land elsewhere in the station place box does not matter, it is cleared when
     * the station is placed.
     */
    static boolean boatSiteFits(TerrainProbe t, int x, int z, Rotation rotation) {
        int[] pe = Deployables.BOAT_STATION_PIER_END;
        Vector3i end = rotation.rotateYaw(new Vector3i(pe[0], 0, pe[2]), new Vector3i());
        if (!SeaRules.isSea(t, x + end.x, z + end.z)) {
            return false;
        }
        int[] o = Deployables.BOAT_STATION_BERTH_ORIGIN;
        Vector3i berth = rotation.rotateYaw(new Vector3i(o[0], 0, o[2]), new Vector3i());
        Rotation berthRotation = rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION);
        for (Vector3i cell : shuttleFloorColumns()) {
            Vector3i p = berthRotation.rotateYaw(cell, new Vector3i());
            int cx = x + berth.x + p.x, cz = z + berth.z + p.z;
            if (!SeaRules.isSea(t, cx, cz) || t.height(cx, cz) >= SeaRules.SEA_LEVEL) {
                return false;
            }
        }
        return true;
    }

    /** The columns of SHUTTLE_FLOOR as (x, 0, z) offsets in the prefab frame. */
    static java.util.Set<Vector3i> shuttleFloorColumns() {
        java.util.Set<Vector3i> columns = new java.util.HashSet<>();
        for (int row = 0; row < SHUTTLE_FLOOR.length; row++) {
            for (int col = 0; col < SHUTTLE_FLOOR[row].length(); col++) {
                if (SHUTTLE_FLOOR[row].charAt(col) == '#') {
                    columns.add(new Vector3i(col - 1, 0, row - 3));
                }
            }
        }
        return columns;
    }

    /**
     * Plans the balloon station beside a temple portal.
     * @param existingAir positions {x, z} of the balloon stations already planned or built
     * @return the site (origin of the station prefab), or null when a balloon station is within SPACING blocks or no spot fits
     */
    static PlannedSite planPortal(int portalX, int portalZ, TerrainProbe t, List<int[]> existingAir) {
        if (near(existingAir, portalX, portalZ, SPACING)) {
            return null;
        }
        int[] min = Deployables.STATION_PLACE_MIN, max = Deployables.STATION_PLACE_MAX;
        long ring2Min = (long) RING_MIN * RING_MIN, ring2Max = (long) RING_MAX * RING_MAX;
        int bestSlope = 0;
        long bestDist = 0, bestBoard = 0;
        PlannedSite best = null;
        for (int dx = -RING_MAX; dx <= RING_MAX; dx += RING_STEP) {
            for (int dz = -RING_MAX; dz <= RING_MAX; dz += RING_STEP) {
                long d2 = (long) dx * dx + (long) dz * dz;
                if (d2 < ring2Min || d2 > ring2Max) {
                    continue;
                }
                int x = portalX + dx, z = portalZ + dz;
                for (Rotation r : ROTATIONS) {
                    Vector3i a = r.rotateYaw(new Vector3i(min[0], 0, min[2]), new Vector3i());
                    Vector3i b = r.rotateYaw(new Vector3i(max[0], 0, max[2]), new Vector3i());
                    int x0 = x + Math.min(a.x, b.x), x1 = x + Math.max(a.x, b.x);
                    int z0 = z + Math.min(a.z, b.z), z1 = z + Math.max(a.z, b.z);
                    if (x1 >= portalX - PORTAL_HALF && x0 <= portalX + PORTAL_HALF
                            && z1 >= portalZ - PORTAL_HALF && z0 <= portalZ + PORTAL_HALF) {
                        continue;
                    }
                    int slope = footprintSlope(t, x0, x1, z0, z1);
                    if (slope < 0 || slope > MAX_SLOPE) {
                        continue;
                    }
                    float[] bp = Deployables.STATION_BOARDING_POINTS[0];
                    Vector3i p = r.rotateYaw(new Vector3i(Math.round(bp[0]), 0, Math.round(bp[2])), new Vector3i());
                    long bx = x + p.x - portalX, bz = z + p.z - portalZ;
                    long board = bx * bx + bz * bz;
                    if (best == null || slope < bestSlope || (slope == bestSlope && (d2 < bestDist || (d2 == bestDist && board < bestBoard)))) {
                        best = new PlannedSite(Deployables.STATION_KIND, x, z, r, false);
                        bestSlope = slope;
                        bestDist = d2;
                        bestBoard = board;
                    }
                }
            }
        }
        return best;
    }

    /** Height spread over the box sampled every FOOTPRINT_STEP blocks, far edges included, or -1 when a column is water. */
    private static int footprintSlope(TerrainProbe t, int x0, int x1, int z0, int z1) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = x0; ; x = Math.min(x + FOOTPRINT_STEP, x1)) {
            for (int z = z0; ; z = Math.min(z + FOOTPRINT_STEP, z1)) {
                int h = t.height(x, z);
                if (h < SeaRules.SEA_LEVEL) {
                    return -1;
                }
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (z == z1) {
                    break;
                }
            }
            if (x == x1) {
                break;
            }
        }
        return hi - lo;
    }

    private static boolean near(List<int[]> positions, int x, int z, int radius) {
        long r2 = (long) radius * radius;
        for (int[] p : positions) {
            long dx = p[0] - x, dz = p[1] - z;
            if (dx * dx + dz * dz < r2) {
                return true;
            }
        }
        return false;
    }
}
