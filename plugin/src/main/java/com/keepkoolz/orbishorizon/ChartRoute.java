package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* corridor on the sea chart, from the cell of a start block to the cell of a goal block. Steps run over the 8
 * neighbours of cells of the same water kind. Learned segments are extra edges. The search is incremental: each
 * call to step expands a bounded number of nodes, so it can be spread over several ticks.
 */
final class ChartRoute {
    enum Status { RUNNING, FOUND, NONE }

    /** What the corridor reads besides the chart: closed cells and learned segments. */
    interface View {
        boolean closed(int cx, int cz);

        List<SeaChartBook.Segment> segments();
    }

    static final int MAX_NODES = 60_000;
    static final int POINT_CELLS = 3;

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double SHALLOW_FACTOR = 3.0;
    private static final int SHALLOW_DEPTH = 3;
    private static final double SHORE_FACTOR = 2.0;
    private static final double SEGMENT_FACTOR = 0.8;
    private static final double HEURISTIC_FACTOR = 0.8;
    private static final int CROSS_STEP = 4;

    private static final class Node {
        final int cx;
        final int cz;
        /** Terrain cost factor of entering this cell, computed once. */
        final double factor;
        double g;
        double f;
        Node parent;
        boolean closedSet;

        Node(int cx, int cz, double factor) {
            this.cx = cx;
            this.cz = cz;
            this.factor = factor;
        }
    }

    /** A learned segment seen from one of its end cells. */
    private record Edge(int toX, int toZ, double length, SeaChartBook.Segment segment) {
    }

    private final SeaChart chart;
    private final View view;
    private final int startX;
    private final int startZ;
    private final int goalX;
    private final int goalZ;
    private final SeaChart.Water kind;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, List<Edge>> edges = new HashMap<>();
    private final Set<Long> special = new HashSet<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private int expanded;
    private Status status = Status.RUNNING;
    private Node goalNode;

    ChartRoute(SeaChart chart, View view, int fromX, int fromZ, int toX, int toZ) {
        this.chart = chart;
        this.view = view;
        this.startX = SeaChart.cellOf(fromX);
        this.startZ = SeaChart.cellOf(fromZ);
        this.goalX = SeaChart.cellOf(toX);
        this.goalZ = SeaChart.cellOf(toZ);
        SeaChart.Water k = chart.water(startX, startZ);
        if (k == SeaChart.Water.LAND) {
            k = chart.water(goalX, goalZ);
        }
        this.kind = k == SeaChart.Water.LAND ? SeaChart.Water.SEA : k;
        special.add(pack(startX, startZ));
        special.add(pack(goalX, goalZ));
        List<SeaChartBook.Segment> learned = view.segments();
        if (learned != null) {
            for (SeaChartBook.Segment s : learned) {
                int ax = SeaChart.cellOf(s.ax());
                int az = SeaChart.cellOf(s.az());
                int bx = SeaChart.cellOf(s.bx());
                int bz = SeaChart.cellOf(s.bz());
                double length = Math.hypot(bx - ax, bz - az);
                special.add(pack(ax, az));
                special.add(pack(bx, bz));
                edges.computeIfAbsent(pack(ax, az), k2 -> new ArrayList<>()).add(new Edge(bx, bz, length, s));
                edges.computeIfAbsent(pack(bx, bz), k2 -> new ArrayList<>()).add(new Edge(ax, az, length, s));
            }
        }
        if (!view.closed(startX, startZ) && !view.closed(goalX, goalZ)) {
            Node start = new Node(startX, startZ, 1.0);
            start.g = 0;
            start.f = heuristic(startX, startZ);
            nodes.put(pack(startX, startZ), start);
            open.add(start);
        }
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private double heuristic(int cx, int cz) {
        return Math.hypot(goalX - cx, goalZ - cz) * HEURISTIC_FACTOR;
    }

    private boolean passable(int cx, int cz) {
        return chart.ocean(cx, cz) != 0 && chart.water(cx, cz) == kind && !view.closed(cx, cz);
    }

    /** A cell a step may enter: open water of the right kind, or a start, goal or segment end cell. */
    private boolean enterable(int cx, int cz) {
        if (view.closed(cx, cz)) {
            return false;
        }
        return passable(cx, cz) || special.contains(pack(cx, cz));
    }

    private double stepFactor(int cx, int cz) {
        double factor = 1.0;
        if (chart.depth(cx, cz) <= SHALLOW_DEPTH) {
            factor *= SHALLOW_FACTOR;
        }
        int ocean = chart.ocean(cx, cz);
        for (int[] d : DIRS) {
            if (chart.ocean(cx + d[0], cz + d[1]) != ocean) {
                factor *= SHORE_FACTOR;
                break;
            }
        }
        return factor;
    }

    private boolean segmentOpen(SeaChartBook.Segment s) {
        double dx = s.bx() - s.ax();
        double dz = s.bz() - s.az();
        double length = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(length / CROSS_STEP));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            int x = (int) Math.round(s.ax() + dx * t);
            int z = (int) Math.round(s.az() + dz * t);
            if (view.closed(SeaChart.cellOf(x), SeaChart.cellOf(z))) {
                return false;
            }
        }
        return true;
    }

    /** Relaxes the edge into (cx, cz). A terrain edge costs base times the cell factor, a segment edge costs base. */
    private void relax(Node from, int cx, int cz, double base, boolean terrain) {
        long key = pack(cx, cz);
        Node n = nodes.get(key);
        if (n == null) {
            n = new Node(cx, cz, stepFactor(cx, cz));
            n.g = Double.MAX_VALUE;
            nodes.put(key, n);
        }
        double g = from.g + (terrain ? base * n.factor : base);
        if (g >= n.g) {
            return;
        }
        n.g = g;
        n.f = g + heuristic(cx, cz);
        n.parent = from;
        n.closedSet = false;
        open.add(n);
    }

    /** Expands at most budget nodes. NONE once MAX_NODES nodes were expanded in total or nothing is left. */
    Status step(int budget) {
        if (status != Status.RUNNING) {
            return status;
        }
        int done = 0;
        while (done < budget) {
            Node n = open.poll();
            if (n == null) {
                status = Status.NONE;
                return status;
            }
            if (n.closedSet) {
                continue;
            }
            n.closedSet = true;
            if (n.cx == goalX && n.cz == goalZ) {
                goalNode = n;
                status = Status.FOUND;
                return status;
            }
            if (expanded >= MAX_NODES) {
                status = Status.NONE;
                return status;
            }
            expanded++;
            done++;
            expand(n);
        }
        return status;
    }

    private void expand(Node n) {
        for (int i = 0; i < DIRS.length; i++) {
            int dx = DIRS[i][0];
            int dz = DIRS[i][1];
            int nx = n.cx + dx;
            int nz = n.cz + dz;
            if (!enterable(nx, nz)) {
                continue;
            }
            boolean diagonal = dx != 0 && dz != 0;
            if (diagonal && (!passable(n.cx + dx, n.cz) || !passable(n.cx, n.cz + dz))) {
                continue;
            }
            relax(n, nx, nz, diagonal ? Math.sqrt(2) : 1.0, true);
        }
        List<Edge> out = edges.get(pack(n.cx, n.cz));
        if (out != null) {
            for (Edge e : out) {
                if (view.closed(e.toX(), e.toZ()) || !segmentOpen(e.segment())) {
                    continue;
                }
                relax(n, e.toX(), e.toZ(), e.length() * SEGMENT_FACTOR, false);
            }
        }
    }

    /** Cell centres in blocks along the corridor, every POINT_CELLS cells, ends included. Valid after FOUND. */
    List<int[]> points() {
        List<int[]> out = new ArrayList<>();
        if (goalNode == null) {
            return out;
        }
        List<Node> path = new ArrayList<>();
        for (Node n = goalNode; n != null; n = n.parent) {
            path.add(n);
        }
        Collections.reverse(path);
        int last = 0;
        out.add(centre(path.get(0)));
        for (int i = 1; i < path.size(); i++) {
            if (i % POINT_CELLS == 0 || i == path.size() - 1) {
                addSpaced(out, path, last, i);
                last = i;
            }
        }
        return out;
    }

    private static int[] centre(Node n) {
        return new int[] {n.cx * SeaChart.CHART_STEP + SeaChart.CHART_STEP / 2,
                n.cz * SeaChart.CHART_STEP + SeaChart.CHART_STEP / 2};
    }

    private static double gap(int[] a, int[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    /**
     * Appends the point of path[to], after the points needed so that no gap exceeds POINT_CELLS * CHART_STEP:
     * a raw cell in between when the steps are short, evenly spaced points on the straight line across a long edge.
     */
    private static void addSpaced(List<int[]> out, List<Node> path, int from, int to) {
        double max = POINT_CELLS * SeaChart.CHART_STEP;
        for (int k = from + 1; k <= to; k++) {
            int[] prev = out.get(out.size() - 1);
            int[] c = centre(path.get(k));
            if (gap(prev, c) > max && k - 1 > from && !same(prev, centre(path.get(k - 1)))) {
                out.add(centre(path.get(k - 1)));
                prev = out.get(out.size() - 1);
            }
            double d = gap(prev, c);
            int parts = (int) Math.ceil(d / max - 1e-9);
            for (int q = 1; q < parts; q++) {
                out.add(new int[] {(int) Math.round(prev[0] + (c[0] - prev[0]) * (double) q / parts),
                        (int) Math.round(prev[1] + (c[1] - prev[1]) * (double) q / parts)});
            }
            if (k == to) {
                out.add(c);
            }
        }
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }
}
