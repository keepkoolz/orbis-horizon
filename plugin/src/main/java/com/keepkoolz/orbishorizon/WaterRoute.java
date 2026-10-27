package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Pure water route search for the maritime shuttle. No game class is used: the world is
 * read through a {@link ColumnProbe}, so everything can be tested outside the game.
 *
 * Coordinates are world block columns (x, z). Grid node (gx, gz) covers columns
 * gx*GRID .. gx*GRID+1 and is centred on column (gx*GRID+1, gz*GRID+1). A point is passable
 * when every column within CLEARANCE blocks of it is water inside the search box.
 */
final class WaterRoute {
    enum Cell { WATER, BLOCKED, UNKNOWN }

    interface ColumnProbe {
        Cell at(int x, int z);
    }

    static final int GRID = 2;
    /**
     * Half width kept clear on both sides of a leg. The hull reaches about 7.5 blocks from its centre line; 8 left no margin once the
     * sample points are rounded to columns and the clear area is a union of circles, so a diagonal leg could scrape a reef.
     */
    static final int CLEARANCE = 9;
    /**
     * Radius kept clear around a waypoint, where the ship turns on the spot: the hull then sweeps a circle around its rotation centre
     * (about 17.2 blocks, see BoatRoutes), far more than the beam. Checked on the intermediate waypoints only.
     */
    static final int TURN_CLEARANCE = 19;
    /**
     * NEED_DATA answers for one unloaded column of the turn clearance check after which that column counts as not clear (kept under
     * BoatRoutes.MAX_LOAD_ATTEMPTS, so the search itself never gives up on it).
     */
    private static final int TURN_DATA_RETRIES = 3;
    /** Turn circles judged per step call at most with the tick budget of 2,000 nodes (each is about 1,100 column reads). */
    private static final int TURN_BUDGET = 6;
    /** Distance from a berth within which the open water check looks for a way out. */
    static final int EXIT_RADIUS = 64;
    /** Distance from the berth a free turn circle must be at, to count as open water. */
    static final int EXIT_MIN_DISTANCE = 40;
    static final int MAX_LENGTH = 4000;
    static final int MAX_EXPANDED = 550_000;

    private WaterRoute() {
    }

    /** Key of a world column in the set given to {@link Search#avoiding}. */
    static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * True when a shuttle moored at (fromX, fromZ) can sail out to open water under the route rules: a flood fill over the grid
     * nodes whose point keeps the leg clearance (tapered near the start like a route), reaching a node at least EXIT_MIN_DISTANCE
     * from the start whose whole turn circle is water. An unloaded column counts as blocked.
     */
    static boolean exitsToOpenWater(ColumnProbe probe, int fromX, int fromZ) {
        int half = EXIT_RADIUS + TURN_CLEARANCE;
        return new Search(probe, fromX, fromZ, fromX, fromZ, fromX - half, fromZ - half, fromX + half, fromZ + half)
                .reachesOpenWater(EXIT_MIN_DISTANCE);
    }

    enum Status { RUNNING, NEED_DATA, FOUND, NO_ROUTE }

    private static final byte UNSET = 0;
    private static final byte YES = 1;
    private static final byte NO = 2;
    private static final byte UNKNOWN = 3;

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    static final class Search {
        private final ColumnProbe probe;
        private final int fromX;
        private final int fromZ;
        private final int toX;
        private final int toZ;
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final int height;
        private int maxLength = MAX_LENGTH;
        /** Columns that are never navigable (cells a shuttle just hit), over the probe. */
        private java.util.Set<Long> avoid = java.util.Set.of();
        /** The start is the position of a stuck ship: it is accepted as it is, and its surroundings get no start taper. */
        private boolean freeStart;
        private boolean goalFound;
        /** NEED_DATA answers per unloaded column of a turn circle. */
        private final java.util.Map<Long, Integer> columnRetries = new java.util.HashMap<>();
        /** Turn clearance per node column of the search box (YES or NO, never UNKNOWN), filled lazily like points. */
        private byte[] turnPoints;
        private int turnBudget;
        private int stepTurnBudget = TURN_BUDGET;

        // Flat caches over the search box: column results (WATER=YES, BLOCKED=NO) and point passability.
        private final byte[] columns;
        private final byte[] points;
        // Node arrays over the grid covering the box.
        private final int gx0;
        private final int gz0;
        private final int gridW;
        private final int gridH;
        private final double[] bestG;
        private final int[] parent;
        private final boolean[] closed;
        private final PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> {
            int c = Double.compare(p[0], q[0]);
            return c != 0 ? c : Double.compare(q[1], p[1]); // equal f: larger g first
        });
        private final int startGx;
        private final int startGz;
        private final int goalGx;
        private final int goalGz;
        private boolean started;
        private int expanded;
        private Status done;
        private int[] missing;
        private List<int[]> waypoints = new ArrayList<>();

        Search(ColumnProbe probe, int fromX, int fromZ, int toX, int toZ, int minX, int minZ, int maxX, int maxZ) {
            this.probe = probe;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toZ = toZ;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            int width = Math.max(0, maxX - minX + 1);
            height = Math.max(0, maxZ - minZ + 1);
            columns = new byte[width * height];
            points = new byte[width * height];
            gx0 = Math.floorDiv(minX, GRID);
            gz0 = Math.floorDiv(minZ, GRID);
            gridW = Math.max(0, Math.floorDiv(maxX, GRID) - gx0 + 1);
            gridH = Math.max(0, Math.floorDiv(maxZ, GRID) - gz0 + 1);
            bestG = new double[gridW * gridH];
            java.util.Arrays.fill(bestG, Double.MAX_VALUE);
            parent = new int[gridW * gridH];
            closed = new boolean[gridW * gridH];
            startGx = Math.floorDiv(fromX, GRID);
            startGz = Math.floorDiv(fromZ, GRID);
            goalGx = Math.floorDiv(toX, GRID);
            goalGz = Math.floorDiv(toZ, GRID);
        }

        /**
         * Overrides the maximum length in blocks (default {@link #MAX_LENGTH}). The length is measured
         * along the chain of node centres, a few blocks off the exact start and goal.
         */
        Search withMaxLength(int blocks) {
            this.maxLength = blocks;
            return this;
        }

        /** World columns (see {@link WaterRoute#columnKey}) that count as blocked whatever the probe says. */
        Search avoiding(java.util.Set<Long> columns) {
            this.avoid = columns == null ? java.util.Set.of() : columns;
            return this;
        }

        /** Marks the start as a stuck ship's position: always passable, and the clearance does not taper around it. */
        Search withFreeStart() {
            this.freeStart = true;
            return this;
        }

        List<int[]> waypoints() {
            return waypoints;
        }

        /** An unknown column met by the last NEED_DATA, null once the search progressed. */
        int[] missingColumn() {
            return missing;
        }

        private int colIndex(int x, int z) {
            return (x - minX) * height + (z - minZ);
        }

        private int nodeIndex(int gx, int gz) {
            return (gx - gx0) * gridH + (gz - gz0);
        }

        private boolean nodeInGrid(int gx, int gz) {
            return gx >= gx0 && gz >= gz0 && gx - gx0 < gridW && gz - gz0 < gridH;
        }

        private byte column(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return NO;
            }
            int i = colIndex(x, z);
            byte c = columns[i];
            if (c == UNSET) {
                if (!avoid.isEmpty() && avoid.contains(columnKey(x, z))) {
                    return columns[i] = NO;
                }
                Cell cell = probe.at(x, z);
                if (cell == Cell.UNKNOWN) {
                    return UNKNOWN; // never cached: asked again on the next step
                }
                c = cell == Cell.WATER ? YES : NO;
                columns[i] = c;
            }
            return c;
        }

        /** Tri-state clearance test around a world column; remembers an unknown column. */
        private byte pointState(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return NO;
            }
            int pi = colIndex(x, z);
            if (points[pi] != UNSET) {
                return points[pi];
            }
            byte state = clearState(x, z, CLEARANCE, false);
            if (state != UNKNOWN) {
                points[pi] = state;
            }
            return state;
        }

        /** Tri-state test of the columns within radius around (x, z), the radius tapering near the endpoints. Remembers an unknown column. */
        private byte clearState(int x, int z, int radius, boolean lenient) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return NO;
            }
            int[] unknown = null;
            // Clearance tapers near the endpoints (berths sit next to piers and shore). A free start keeps the taper: a ship stuck by
            // a side contact must be able to leave; the avoided columns stay blocked.
            double d = Math.min(Math.hypot(x - fromX, z - fromZ), Math.hypot(x - toX, z - toZ));
            int r = Math.min(radius, (int) Math.floor(d / 2));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    byte c = column(x + dx, z + dz);
                    if (c == NO) {
                        return NO;
                    }
                    if (c == UNKNOWN && lenient
                            && columnRetries.getOrDefault(columnKey(x + dx, z + dz), 0) >= TURN_DATA_RETRIES) {
                        return NO; // a column that stays unloaded after the retries counts as not clear
                    }
                    if (c == UNKNOWN && unknown == null) {
                        unknown = new int[] {x + dx, z + dz};
                    }
                }
            }
            if (unknown != null) {
                missing = unknown;
                return UNKNOWN;
            }
            return YES;
        }

        private static int centre(int g) {
            return g * GRID + GRID / 2;
        }

        private byte nodeState(int gx, int gz) {
            if (!nodeInGrid(gx, gz)) {
                return NO;
            }
            return pointState(centre(gx), centre(gz));
        }

        Status step(int maxNodes) {
            if (done != null) {
                return done;
            }
            missing = null;
            stepTurnBudget = Math.max(1, maxNodes / 300); // 2,000 nodes per tick: 6 turn circles
            if (goalFound) {
                return finishRoute();
            }
            if (!started) {
                byte s = freeStart ? YES : nodeState(startGx, startGz);
                if (s == UNKNOWN) {
                    return Status.NEED_DATA;
                }
                if (s == NO) {
                    return done = Status.NO_ROUTE;
                }
                byte g = nodeState(goalGx, goalGz);
                if (g == UNKNOWN) {
                    return Status.NEED_DATA;
                }
                if (g == NO) {
                    return done = Status.NO_ROUTE;
                }
                bestG[nodeIndex(startGx, startGz)] = 0.0;
                parent[nodeIndex(startGx, startGz)] = -1;
                open.add(new double[] {heuristic(startGx, startGz), 0.0, startGx, startGz});
                started = true;
            }
            int budget = maxNodes;
            while (!open.isEmpty()) {
                if (budget-- <= 0) {
                    return Status.RUNNING;
                }
                double[] top = open.peek();
                int gx = (int) top[2];
                int gz = (int) top[3];
                int ni = nodeIndex(gx, gz);
                if (closed[ni]) {
                    open.poll();
                    continue;
                }
                if (gx == goalGx && gz == goalGz) {
                    open.poll();
                    goalFound = true;
                    return finishRoute();
                }
                // Resolve every neighbour first, so a NEED_DATA leaves the search untouched.
                byte[] states = new byte[DIRS.length];
                for (int i = 0; i < DIRS.length; i++) {
                    int nx = gx + DIRS[i][0];
                    int nz = gz + DIRS[i][1];
                    if (!nodeInGrid(nx, nz) || closed[nodeIndex(nx, nz)]) {
                        states[i] = NO;
                        continue;
                    }
                    states[i] = nodeState(nx, nz);
                    if (states[i] == UNKNOWN) {
                        return Status.NEED_DATA;
                    }
                }
                open.poll();
                closed[ni] = true;
                if (++expanded > MAX_EXPANDED) {
                    return done = Status.NO_ROUTE;
                }
                double g = bestG[ni];
                for (int i = 0; i < DIRS.length; i++) {
                    if (states[i] != YES) {
                        continue;
                    }
                    int nx = gx + DIRS[i][0];
                    int nz = gz + DIRS[i][1];
                    double ng = g + GRID * Math.hypot(DIRS[i][0], DIRS[i][1]);
                    double f = ng + heuristic(nx, nz);
                    if (f > maxLength) {
                        continue; // exact: the heuristic never overestimates
                    }
                    int nk = nodeIndex(nx, nz);
                    if (ng < bestG[nk]) {
                        bestG[nk] = ng;
                        parent[nk] = ni;
                        open.add(new double[] {f, ng, nx, nz});
                    }
                }
            }
            return done = Status.NO_ROUTE;
        }

        /**
         * Flood fill from the start node through passable nodes (8 neighbours, as in step). True as soon as a reached node centre is at
         * least minDistance from the start and its whole turn circle is clear. Nodes with an unloaded column count as blocked.
         */
        boolean reachesOpenWater(int minDistance) {
            if (!nodeInGrid(startGx, startGz) || nodeState(startGx, startGz) != YES) {
                return false;
            }
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            closed[nodeIndex(startGx, startGz)] = true;
            queue.add(new int[] {startGx, startGz});
            while (!queue.isEmpty()) {
                int[] n = queue.poll();
                int cx = centre(n[0]);
                int cz = centre(n[1]);
                if (Math.hypot(cx - fromX, cz - fromZ) >= minDistance && Boolean.TRUE.equals(turnClear(cx, cz))) {
                    return true;
                }
                for (int[] d : DIRS) {
                    int nx = n[0] + d[0];
                    int nz = n[1] + d[1];
                    if (!nodeInGrid(nx, nz) || closed[nodeIndex(nx, nz)]) {
                        continue;
                    }
                    if (nodeState(nx, nz) == YES) {
                        closed[nodeIndex(nx, nz)] = true;
                        queue.add(new int[] {nx, nz});
                    }
                }
            }
            return false;
        }

        private double heuristic(int gx, int gz) {
            return GRID * Math.hypot(gx - goalGx, gz - goalGz);
        }

        private boolean segmentClear(int ax, int az, int bx, int bz) {
            double len = Math.hypot(bx - ax, bz - az);
            int n = Math.max(1, (int) Math.ceil(len / GRID));
            // A free start is the position of a stuck ship: its own surroundings are not required to be clear.
            for (int i = freeStart && ax == fromX && az == fromZ ? 1 : 0; i <= n; i++) {
                int x = (int) Math.round(ax + (bx - ax) * (double) i / n);
                int z = (int) Math.round(az + (bz - az) * (double) i / n);
                if (pointState(x, z) != YES) {
                    return false;
                }
            }
            return segmentWet(ax, az, bx, bz);
        }

        /**
         * Same rule as segmentClear (CLEARANCE along the line, wet columns), but an unloaded column answers null instead of false.
         * Counts no retry and leaves the search state (missing column, turn budget) alone.
         */
        Boolean legClear(int ax, int az, int bx, int bz) {
            int[] savedMissing = missing;
            try {
                boolean unknown = false;
                int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az) / GRID));
                for (int i = freeStart && ax == fromX && az == fromZ ? 1 : 0; i <= n; i++) {
                    int x = (int) Math.round(ax + (bx - ax) * (double) i / n);
                    int z = (int) Math.round(az + (bz - az) * (double) i / n);
                    byte s = pointState(x, z);
                    if (s == NO) {
                        return false;
                    }
                    unknown |= s == UNKNOWN;
                }
                int m = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az)));
                for (int i = 0; i <= m; i++) {
                    int x = (int) Math.round(ax + (bx - ax) * (double) i / m);
                    int z = (int) Math.round(az + (bz - az) * (double) i / m);
                    byte c = column(x, z);
                    if (c == NO) {
                        return false;
                    }
                    unknown |= c == UNKNOWN;
                }
                return unknown ? null : Boolean.TRUE;
            } finally {
                missing = savedMissing;
            }
        }

        /** True when the TURN_CLEARANCE circle around (x, z) is free, false when not, null when a column is not loaded. */
        Boolean turnClear(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return false;
            }
            if (turnPoints == null) {
                turnPoints = new byte[points.length];
            }
            int pi = colIndex(x, z);
            if (turnPoints[pi] != UNSET) {
                return turnPoints[pi] == YES;
            }
            int[] savedMissing = missing;
            try {
                byte c = clearState(x, z, TURN_CLEARANCE, false);
                if (c == UNKNOWN) {
                    return null;
                }
                turnPoints[pi] = c;
                return c == YES;
            } finally {
                missing = savedMissing;
            }
        }

        /** True when every column along the segment (sampled each block) is known water. */
        private boolean segmentWet(int ax, int az, int bx, int bz) {
            int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az)));
            for (int i = 0; i <= n; i++) {
                int x = (int) Math.round(ax + (bx - ax) * (double) i / n);
                int z = (int) Math.round(az + (bz - az) * (double) i / n);
                if (column(x, z) != YES) {
                    return false;
                }
            }
            return true;
        }

        /** Builds the waypoints once the goal is reached; NEED_DATA for an unloaded turn circle, RUNNING while the build uses its budget. */
        private Status finishRoute() {
            missing = null;
            turnBudget = stepTurnBudget;
            int r = buildWaypoints();
            if (r == BUILD_DATA) {
                if (missing != null) {
                    columnRetries.merge(columnKey(missing[0], missing[1]), 1, Integer::sum);
                }
                return Status.NEED_DATA;
            }
            if (r == BUILD_BUDGET) {
                return Status.RUNNING;
            }
            return done = Status.FOUND;
        }

        private static final int BUILD_DONE = 0;
        private static final int BUILD_DATA = 1;
        private static final int BUILD_BUDGET = 2;

        /**
         * Turn clearance of a chain node (cached per column). UNKNOWN while its circle has an unloaded column; BUILD budget is spent on
         * cache misses only, so a step call never reads more than TURN_BUDGET circles. Returns -1 when the budget is spent.
         */
        private int turnState(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return NO;
            }
            if (turnPoints == null) {
                turnPoints = new byte[points.length];
            }
            int pi = colIndex(x, z);
            if (turnPoints[pi] != UNSET) {
                return turnPoints[pi];
            }
            if (turnBudget <= 0) {
                return -1;
            }
            turnBudget--;
            byte c = clearState(x, z, TURN_CLEARANCE, true);
            if (c != UNKNOWN) {
                turnPoints[pi] = c;
            }
            return c;
        }

        /** BUILD_DONE, BUILD_DATA (missing set: a turn circle column is not loaded) or BUILD_BUDGET (call again). */
        private int buildWaypoints() {
            // Node chain from start to goal, as world columns; exact endpoints at both ends.
            ArrayList<int[]> chain = new ArrayList<>();
            ArrayList<Integer> idx = new ArrayList<>();
            int k = nodeIndex(goalGx, goalGz);
            while (k >= 0) {
                idx.add(k);
                k = parent[k];
            }
            chain.add(new int[] {fromX, fromZ});
            for (int i = idx.size() - 2; i >= 1; i--) {
                int ni = idx.get(i);
                chain.add(new int[] {centre(ni / gridH + gx0), centre(ni % gridH + gz0)});
            }
            chain.add(new int[] {toX, toZ});
            // Keep a point only when the straight line to the next one loses its clearance. The ship turns on the spot at every
            // waypoint, so a corner is placed where the whole turning circle is clear (TURN_CLEARANCE) when the chain has such a node;
            // else the farthest straight segment as before. Consecutive A* nodes are adjacent and both passable, so the fallback
            // keeps the next point.
            ArrayList<int[]> out = new ArrayList<>();
            int cur = 0;
            int last = chain.size() - 1;
            while (cur < last) {
                int far = cur + 1;
                boolean farSet = false;
                int turn = -1;
                for (int j = last; j > cur + 1; j--) {
                    if (!segmentClear(chain.get(cur)[0], chain.get(cur)[1], chain.get(j)[0], chain.get(j)[1])) {
                        continue;
                    }
                    if (!farSet) {
                        far = j; // farthest straight segment (old rule)
                        farSet = true;
                    }
                    if (j == last) {
                        turn = j;
                        break;
                    }
                    int t = turnState(chain.get(j)[0], chain.get(j)[1]);
                    if (t == -1) {
                        return BUILD_BUDGET;
                    }
                    if (t == UNKNOWN) {
                        return BUILD_DATA;
                    }
                    if (t == YES) {
                        turn = j;
                        break;
                    }
                }
                int pick = turn >= 0 ? turn : far;
                out.add(chain.get(pick));
                cur = pick;
            }
            waypoints = out;
            return BUILD_DONE;
        }
    }
}
