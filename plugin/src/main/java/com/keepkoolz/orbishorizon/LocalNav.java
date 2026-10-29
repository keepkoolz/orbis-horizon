package com.keepkoolz.orbishorizon;

import java.util.List;
import java.util.Set;

/**
 * Local pilot: follows a corridor of points on the real blocks. It runs a {@link WaterRoute.Search} in a square window centred on
 * the boat, towards the most advanced corridor point inside that window. Pure engine, no world access: the world thread calls
 * {@link #tick} once per game tick with the column probe of that tick, and the search is spread over several ticks.
 */
final class LocalNav {
    /** The corridor the pilot follows: it can be traced again from a position, and a chart cell can be closed. */
    interface Corridor {
        /** Points of a new corridor from the given position, or null when no corridor exists any more. */
        List<int[]> retrace(int fromX, int fromZ);

        /** Closes the chart cell that contains the block column. */
        void close(int x, int z);
    }

    enum Kind { RUNNING, PATH, WAIT_DATA, FAILED }

    /** box is {minX, minZ, maxX, maxZ} in blocks: the area to load for WAIT_DATA, null otherwise. */
    record Result(Kind kind, List<int[]> path, int[] box) {
    }

    static final int WINDOW = 96;
    static final int WIDE_WINDOW = 192;
    static final int NODES = 4000;
    static final int MAX_RETRACES = 5;
    static final int PROGRESS = 32;
    /** Radius around a corridor point searched for a clear node when the point itself is not clear. */
    private static final int GOAL_RADIUS = 16;

    /** Side in blocks of a chart cell. */
    private static final int CHART_CELL = 16;
    private static final int CLEAR = 0;
    private static final int BLOCKED = 1;
    private static final int UNKNOWN = 2;

    private final Corridor corridor;
    private final HullClearance hull;
    private final int goalX;
    private final int goalZ;
    private List<int[]> points = List.of();
    private WaterRoute.ColumnProbe live;
    private WaterRoute.Search search;
    private boolean wide;
    private boolean waiting;
    private boolean gaveUp;
    private boolean failed;
    private boolean hasPath;
    private boolean started;
    private int retraces;
    private double lastDistance;
    private double lastX;
    private double lastZ;
    /** Corridor point the current search aims at (the cell closed when the window has no path). */
    private int[] target = new int[2];
    private int[] waitBox;
    private int lastCx;
    private int lastCz;
    /** True once the attempt turned an unloaded column into a blocked one after a give-up. */
    private boolean unknownHit;
    /** Box of the current window, for the cell to close. */
    private int[] windowBox = new int[4];

    LocalNav(Corridor corridor, HullClearance hull, int goalX, int goalZ) {
        this.corridor = corridor;
        this.hull = hull;
        this.goalX = goalX;
        this.goalZ = goalZ;
    }

    /** Replaces the corridor followed; the running search is dropped. */
    void setCorridor(List<int[]> points) {
        this.points = points == null ? List.of() : points;
        search = null;
        wide = false;
        waiting = false;
        gaveUp = false;
        unknownHit = false;
    }

    /** True when there is no current path, or the boat has gone half a window from where the last search started. */
    boolean needsSearch(double cx, double cz) {
        return !hasPath || Math.hypot(cx - lastX, cz - lastZ) >= WINDOW / 2.0;
    }

    /** Drops the running search so the next tick starts a new one. The wait and give-up state are left as they are. */
    void cancel() {
        search = null;
    }

    /** The unloaded columns are loaded: the search restarts. */
    void dataLoaded() {
        waiting = false;
        gaveUp = false;
        unknownHit = false;
        search = null;
    }

    /** Loading gave up: unknown columns count as blocked for the rest of the search, which restarts. */
    void dataGaveUp() {
        waiting = false;
        gaveUp = true;
        search = null;
    }

    Result tick(double cx, double cz, WaterRoute.ColumnProbe probe, int budget, Set<Long> avoid) {
        live = probe;
        try {
            return tickInner(cx, cz, budget, avoid);
        } finally {
            live = null;
        }
    }

    private Result tickInner(double cx, double cz, int budget, Set<Long> avoid) {
        if (failed) {
            return new Result(Kind.FAILED, null, null);
        }
        if (!started) {
            started = true;
            lastDistance = Math.hypot(cx - goalX, cz - goalZ);
        }
        if (waiting) {
            return new Result(Kind.WAIT_DATA, null, waitBox);
        }
        Set<Long> avoided = avoid == null ? Set.of() : avoid;
        if (search == null) {
            Result r = begin((int) Math.floor(cx), (int) Math.floor(cz), avoided);
            if (r != null) {
                return r;
            }
        }
        WaterRoute.Status s = search.step(budget);
        switch (s) {
            case RUNNING:
                return new Result(Kind.RUNNING, null, null);
            case NEED_DATA:
                waiting = true;
                waitBox = waitBox(currentHalf());
                return new Result(Kind.WAIT_DATA, null, waitBox);
            case FOUND: {
                List<int[]> path = new java.util.ArrayList<>(search.waypoints());
                search = null;
                wide = false;
                gaveUp = false;
                unknownHit = false;
                hasPath = true;
                return new Result(Kind.PATH, path, null);
            }
            default:
                search = null;
                return noPath(cx, cz);
        }
    }

    private int currentHalf() {
        return (wide ? WIDE_WINDOW : WINDOW) / 2;
    }

    /** Box of the current window and the next one ahead, centred on the corridor point aimed at. */
    private int[] waitBox(int half) {
        int[] b = windowBox(lastCx, lastCz, half);
        int[] n = windowBox(target[0], target[1], half);
        return new int[] {Math.min(b[0], n[0]), Math.min(b[1], n[1]), Math.max(b[2], n[2]), Math.max(b[3], n[3])};
    }

    private static int[] windowBox(int x, int z, int half) {
        return new int[] {x - half, z - half, x + half, z + half};
    }

    /** Starts a search in the window around (bx, bz); a result when it cannot start (waiting for data, or no goal). */
    private Result begin(int bx, int bz, Set<Long> avoid) {
        int half = currentHalf();
        hasPath = false;
        lastCx = bx;
        lastCz = bz;
        lastX = bx;
        lastZ = bz;
        int[] box = windowBox(bx, bz, half);
        target = pickTarget(bx, bz, half);
        WaterRoute.ColumnProbe wrapped = (x, z) -> {
            WaterRoute.Cell c = live.at(x, z);
            if (c == WaterRoute.Cell.UNKNOWN && gaveUp) {
                unknownHit = true;
                return WaterRoute.Cell.BLOCKED;
            }
            return c;
        };
        windowBox = box;
        int[] goal = null;
        int state = clearState(wrapped, avoid, box, centre(target[0]), centre(target[1]), hull.leg());
        if (state == UNKNOWN && !gaveUp) {
            waiting = true;
            waitBox = waitBox(half);
            return new Result(Kind.WAIT_DATA, null, waitBox);
        }
        if (state == CLEAR) {
            goal = target;
        } else {
            WaterRoute.Search probeSearch = new WaterRoute.Search(wrapped, bx, bz, target[0], target[1], box[0], box[1], box[2], box[3])
                    .clearance(hull).taperGoal(false).avoiding(avoid);
            goal = probeSearch.nearestClear(target[0], target[1], GOAL_RADIUS);
            if (goal == null && !gaveUp && hasUnknown(wrapped, avoid, box, target[0], target[1], GOAL_RADIUS + hull.leg())) {
                waiting = true;
                waitBox = waitBox(half);
                return new Result(Kind.WAIT_DATA, null, waitBox);
            }
        }
        if (goal == null) {
            return noPath(bx, bz);
        }
        search = new WaterRoute.Search(wrapped, bx, bz, goal[0], goal[1], box[0], box[1], box[2], box[3])
                .clearance(hull).taperGoal(false).withFreeStart().avoiding(avoid).maxExpanded(NODES);
        return null;
    }

    /** No path in this window: the wide window once, then the cell is closed and the corridor traced again. */
    private Result noPath(double cx, double cz) {
        search = null;
        if (!wide) {
            wide = true;
            return new Result(Kind.RUNNING, null, null);
        }
        wide = false;
        hasPath = false;
        if (!unknownHit) {
            int[] cell = blockedPoint();
            corridor.close(cell[0], cell[1]);
        }
        List<int[]> next = corridor.retrace((int) Math.floor(cx), (int) Math.floor(cz));
        if (next == null) {
            failed = true;
            return new Result(Kind.FAILED, null, null);
        }
        double distance = Math.hypot(cx - goalX, cz - goalZ);
        retraces = lastDistance - distance < PROGRESS ? retraces + 1 : 0;
        lastDistance = distance;
        points = next;
        gaveUp = false;
        unknownHit = false;
        if (retraces >= MAX_RETRACES) {
            failed = true;
            return new Result(Kind.FAILED, null, null);
        }
        return new Result(Kind.RUNNING, null, null);
    }

    /**
     * First blocked point on the straight line from the boat to the target, sampled every 4 blocks: a known non-water column or a
     * node that fails the full leg clearance. The target itself when the whole line is clear.
     */
    private int[] blockedPoint() {
        double dx = target[0] - lastCx;
        double dz = target[1] - lastCz;
        double length = Math.hypot(dx, dz);
        int steps = (int) Math.floor(length / 4);
        // Unknown columns do not block here, and avoided columns (other boats) are not obstacles of the chart.
        WaterRoute.ColumnProbe known = (x, z) -> {
            WaterRoute.Cell c = live.at(x, z);
            return c == WaterRoute.Cell.UNKNOWN ? WaterRoute.Cell.WATER : c;
        };
        for (int i = 1; i <= steps; i++) {
            int x = (int) Math.round(lastCx + dx * i * 4 / length);
            int z = (int) Math.round(lastCz + dz * i * 4 / length);
            if (sameChartCell(x, lastCx) && sameChartCell(z, lastCz)) {
                continue; // the boat's own chart cell is never closed
            }
            if (clearState(known, Set.of(), windowBox, centre(x), centre(z), hull.leg()) == BLOCKED) {
                return new int[] {x, z};
            }
        }
        return target;
    }

    /**
     * The corridor point with the highest index inside the window (kept leg blocks plus one grid step from the edges, so the clear
     * circle of its node fits in the box). When that point is close to the boat the goal slides along the corridor towards the next
     * point, up to the window edge. With no point inside, the point closest to the boat is pulled onto the window edge.
     */
    private int[] pickTarget(int bx, int bz, int half) {
        int reach = half - hull.leg() - WaterRoute.GRID;
        int best = -1;
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            if (Math.abs(p[0] - bx) <= reach && Math.abs(p[1] - bz) <= reach) {
                best = i;
            }
        }
        if (best >= 0) {
            int[] p = points.get(best);
            if (best + 1 < points.size() && Math.hypot(p[0] - bx, p[1] - bz) < WINDOW / 4.0) {
                return clip(p, points.get(best + 1), bx, bz, reach);
            }
            return p;
        }
        if (points.isEmpty()) {
            return clip(new int[] {bx, bz}, new int[] {goalX, goalZ}, bx, bz, reach);
        }
        int[] nearest = points.get(0);
        for (int[] p : points) {
            if (Math.hypot(p[0] - bx, p[1] - bz) < Math.hypot(nearest[0] - bx, nearest[1] - bz)) {
                nearest = p;
            }
        }
        return clip(new int[] {bx, bz}, nearest, bx, bz, reach);
    }

    /** The farthest point of the segment a to b that is still within reach of (bx, bz) on both axes. */
    private static int[] clip(int[] a, int[] b, int bx, int bz, int reach) {
        double t = 1.0;
        t = Math.min(t, axisLimit(a[0], b[0], bx, reach));
        t = Math.min(t, axisLimit(a[1], b[1], bz, reach));
        return new int[] {(int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t)};
    }

    private static double axisLimit(int a, int b, int centre, int reach) {
        int d = b - a;
        if (d == 0) {
            return 1.0;
        }
        double limit = d > 0 ? (centre + reach - a) / (double) d : (centre - reach - a) / (double) d;
        return Math.max(0.0, limit);
    }

    private static boolean sameChartCell(int a, int b) {
        return Math.floorDiv(a, CHART_CELL) == Math.floorDiv(b, CHART_CELL);
    }

    private static int centre(int v) {
        return Math.floorDiv(v, WaterRoute.GRID) * WaterRoute.GRID + WaterRoute.GRID / 2;
    }

    /** Clearance of the circle of the given radius around (x, z): CLEAR, BLOCKED (also outside the box) or UNKNOWN. */
    private static int clearState(WaterRoute.ColumnProbe probe, Set<Long> avoid, int[] box, int x, int z, int radius) {
        boolean unknown = false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int cx = x + dx;
                int cz = z + dz;
                if (cx < box[0] || cx > box[2] || cz < box[1] || cz > box[3] || avoid.contains(WaterRoute.columnKey(cx, cz))) {
                    return BLOCKED;
                }
                WaterRoute.Cell c = probe.at(cx, cz);
                if (c == WaterRoute.Cell.BLOCKED) {
                    return BLOCKED;
                }
                unknown |= c == WaterRoute.Cell.UNKNOWN;
            }
        }
        return unknown ? UNKNOWN : CLEAR;
    }

    private static boolean hasUnknown(WaterRoute.ColumnProbe probe, Set<Long> avoid, int[] box, int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                if (dx * dx + dz * dz <= radius * radius && cx >= box[0] && cx <= box[2] && cz >= box[1] && cz <= box[3]
                        && !avoid.contains(WaterRoute.columnKey(cx, cz)) && probe.at(cx, cz) == WaterRoute.Cell.UNKNOWN) {
                    return true;
                }
            }
        }
        return false;
    }
}
