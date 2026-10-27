package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pure log of the water cells a boat sailed through (rotation-centre cells on the {@link WaterRoute#GRID} grid),
 * with the stops the captain marked and the boat stations reached. Nothing here touches the game except
 * {@link Rotation} and BSON.
 */
final class WaterLog {
    static final int STOP_MIN_GAP = 32;        // a new stop needs every other stop at least this far (blocks)
    static final int STATION_REACH = 40;        // a boat station is noted when the rotation centre passes this close to its pier
    static final int DEST_MIN = 32;             // destinations closer than this are not offered
    /**
     * Farthest reach of the steamboat hull from its rotation centre, with a margin: the prefab spans x -7..7 and z -15..12 around the
     * centre (prefab 0, 0), so a cell corner is up to about 17.2 blocks away (hypot(7.5, 15.5)); 18 rounded up, plus 2 blocks.
     */
    static final int HULL_REACH = 20;
    /** Arrival cell kept out of the station zone for any heading: zone radius plus the hull reach. */
    static final int STATION_STANDOFF = TransitConstants.STATION_ZONE_RADIUS + HULL_REACH;

    private static final int MAX_EXPANDED = 200_000; // cells expanded by one log search
    private static final int START_SEARCH = 8;  // cells around the start looked at for the nearest log cell
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    record Stop(String name, int x, int y, int z, Rotation rotation, int cx, int cz) {}

    record Reached(int x, int y, int z) {}

    private final Set<Long> cells = new HashSet<>();
    private final List<Stop> stops = new ArrayList<>();
    private final List<Reached> reached = new ArrayList<>();
    // Transient: last recorded cell, not saved.
    private boolean hasLast;
    private int lastGx;
    private int lastGz;

    WaterLog copy() {
        WaterLog c = new WaterLog();
        c.cells.addAll(cells);
        c.stops.addAll(stops);
        c.reached.addAll(reached);
        return c;
    }

    boolean isEmpty() {
        return cells.isEmpty() && stops.isEmpty() && reached.isEmpty();
    }

    int size() {
        return cells.size();
    }

    static long cell(int gx, int gz) {
        return WaterRoute.columnKey(gx, gz);
    }

    static int gridOf(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), WaterRoute.GRID);
    }

    private static int centre(int g) {
        return g * WaterRoute.GRID + WaterRoute.GRID / 2;
    }

    /** Records the cell of this rotation centre; cells skipped since the last recorded one are added along the line (8-connected). True if new cells were added. */
    boolean record(double centreX, double centreZ) {
        int gx = gridOf(centreX);
        int gz = gridOf(centreZ);
        boolean added = false;
        if (hasLast && (gx != lastGx || gz != lastGz)) {
            int dx = gx - lastGx;
            int dz = gz - lastGz;
            int n = Math.max(Math.abs(dx), Math.abs(dz));
            for (int i = 1; i <= n; i++) {
                int x = lastGx + (int) Math.round((double) dx * i / n);
                int z = lastGz + (int) Math.round((double) dz * i / n);
                added |= cells.add(cell(x, z));
            }
        } else {
            added = cells.add(cell(gx, gz));
        }
        hasLast = true;
        lastGx = gx;
        lastGz = gz;
        return added;
    }

    /** Next record() starts a new line (after a landing or a resume). */
    void forgetLastPosition() {
        hasLast = false;
    }

    boolean contains(int gx, int gz) {
        return cells.contains(cell(gx, gz));
    }

    List<Stop> stops() {
        return Collections.unmodifiableList(stops);
    }

    List<Reached> reached() {
        return Collections.unmodifiableList(reached);
    }

    /** Smallest free stop number, as text ("1", "2"...): the stored name of a stop is its number only. */
    String nextStopNumber() {
        Set<String> used = new HashSet<>();
        for (Stop s : stops) {
            used.add(s.name());
        }
        int n = 1;
        while (used.contains(Integer.toString(n))) {
            n++;
        }
        return Integer.toString(n);
    }

    /** Null if added, else the chat key of the refusal. Adds the stop's centre cell to the log too. */
    String addStop(int x, int y, int z, Rotation rotation, int cx, int cz) {
        for (Stop s : stops) {
            if (Math.hypot(s.cx() - cx, s.cz() - cz) < STOP_MIN_GAP) {
                return "crew.stop.tooClose";
            }
        }
        stops.add(new Stop(nextStopNumber(), x, y, z, rotation, cx, cz));
        cells.add(cell(gridOf(cx), gridOf(cz)));
        return null;
    }

    boolean forgetStop(String number) {
        return stops.removeIf(s -> s.name().equals(number));
    }

    boolean addReached(int x, int y, int z) {
        Reached r = new Reached(x, y, z);
        if (reached.contains(r)) {
            return false;
        }
        reached.add(r);
        return true;
    }

    /** Stations removed from the network are forgotten. */
    void dropReachedNotIn(Set<Reached> existing) {
        reached.removeIf(r -> !existing.contains(r));
    }

    /**
     * Shortest path on the log cells (A*, 8 neighbours, diagonal cost sqrt 2) from the log cell nearest to the start
     * (searched within 8 cells) to the goal cell; avoided cells never used. Rotation-centre columns {x, z}
     * (cell centres; first = real start column, last = goal column), empty if none.
     */
    List<int[]> path(int startX, int startZ, int goalX, int goalZ, Set<Long> avoidCells) {
        int goalGx = gridOf(goalX);
        int goalGz = gridOf(goalZ);
        if (cells.isEmpty() || !contains(goalGx, goalGz) || avoidCells.contains(cell(goalGx, goalGz))) {
            return List.of();
        }
        int sgx = gridOf(startX);
        int sgz = gridOf(startZ);
        int bestGx = 0;
        int bestGz = 0;
        double best = Double.MAX_VALUE;
        for (int dx = -START_SEARCH; dx <= START_SEARCH; dx++) {
            for (int dz = -START_SEARCH; dz <= START_SEARCH; dz++) {
                int gx = sgx + dx;
                int gz = sgz + dz;
                if (!contains(gx, gz) || avoidCells.contains(cell(gx, gz))) {
                    continue;
                }
                double d = Math.hypot(dx, dz);
                if (d < best) {
                    best = d;
                    bestGx = gx;
                    bestGz = gz;
                }
            }
        }
        if (best == Double.MAX_VALUE) {
            return List.of();
        }
        long startKey = cell(bestGx, bestGz);
        long goalKey = cell(goalGx, goalGz);
        HashMap<Long, Double> g = new HashMap<>();
        HashMap<Long, Long> from = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        // entries: {f, gx, gz}
        g.put(startKey, 0.0);
        open.add(new double[] {Math.hypot(bestGx - goalGx, bestGz - goalGz), bestGx, bestGz});
        int expanded = 0;
        boolean found = false;
        while (!open.isEmpty()) {
            double[] cur = open.poll();
            int cx = (int) cur[1];
            int cz = (int) cur[2];
            long ck = cell(cx, cz);
            if (!closed.add(ck)) {
                continue;
            }
            if (ck == goalKey) {
                found = true;
                break;
            }
            if (++expanded > MAX_EXPANDED) {
                break;
            }
            double cg = g.get(ck);
            for (int[] d : DIRS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                long nk = cell(nx, nz);
                if (!cells.contains(nk) || avoidCells.contains(nk) || closed.contains(nk)) {
                    continue;
                }
                double ng = cg + Math.hypot(d[0], d[1]);
                Double old = g.get(nk);
                if (old == null || ng < old) {
                    g.put(nk, ng);
                    from.put(nk, ck);
                    open.add(new double[] {ng + Math.hypot(nx - goalGx, nz - goalGz), nx, nz});
                }
            }
        }
        if (!found) {
            return List.of();
        }
        ArrayList<int[]> out = new ArrayList<>();
        long k = goalKey;
        while (true) {
            out.add(new int[] {centre((int) (k >> 32)), centre((int) k)});
            if (k == startKey) {
                break;
            }
            k = from.get(k);
        }
        Collections.reverse(out);
        out.get(out.size() - 1)[0] = goalX;
        out.get(out.size() - 1)[1] = goalZ;
        int[] first = out.get(0);
        if (first[0] != startX || first[1] != startZ) {
            out.add(0, new int[] {startX, startZ});
        }
        return out;
    }

    /**
     * Drops the middle point of three that are exactly collinear and go the same way (cross product 0, positive dot product): only the
     * turning points, the first and the last are kept. Safe on any path, shortened or not: a point is dropped only when the straight line
     * through it is the one already sailed.
     */
    static List<int[]> collapse(List<int[]> path) {
        if (path.size() <= 2) {
            return new ArrayList<>(path);
        }
        ArrayList<int[]> out = new ArrayList<>();
        out.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            int[] a = out.get(out.size() - 1);
            int[] b = path.get(i);
            int[] c = path.get(i + 1);
            long ux = (long) b[0] - a[0];
            long uz = (long) b[1] - a[1];
            long vx = (long) c[0] - b[0];
            long vz = (long) c[1] - b[1];
            boolean straight = ux * vz - uz * vx == 0 && ux * vx + uz * vz > 0;
            if (!straight) {
                out.add(b);
            }
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    /** Log cell centre at least STATION_STANDOFF blocks from the zone centre (x, z), nearest to the pier point; null if none. */
    int[] stationTarget(double pierX, double pierZ, double zoneCx, double zoneCz) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (long k : cells) {
            int x = centre((int) (k >> 32));
            int z = centre((int) k);
            if (Math.hypot(x - zoneCx, z - zoneCz) < STATION_STANDOFF) {
                continue;
            }
            double d = Math.hypot(x - pierX, z - pierZ);
            if (d < bestD) {
                bestD = d;
                best = new int[] {x, z};
            }
        }
        return best;
    }

    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        BsonArray log = new BsonArray();
        for (long k : cells) {
            log.add(new BsonInt64(k));
        }
        d.put("log", log);
        BsonArray st = new BsonArray();
        for (Stop s : stops) {
            BsonDocument o = new BsonDocument();
            o.put("n", new BsonString(s.name()));
            o.put("x", new BsonInt32(s.x()));
            o.put("y", new BsonInt32(s.y()));
            o.put("z", new BsonInt32(s.z()));
            o.put("rot", new BsonString(s.rotation().name()));
            o.put("cx", new BsonInt32(s.cx()));
            o.put("cz", new BsonInt32(s.cz()));
            st.add(o);
        }
        d.put("stops", st);
        BsonArray re = new BsonArray();
        for (Reached r : reached) {
            BsonDocument o = new BsonDocument();
            o.put("x", new BsonInt32(r.x()));
            o.put("y", new BsonInt32(r.y()));
            o.put("z", new BsonInt32(r.z()));
            re.add(o);
        }
        d.put("reached", re);
        return d;
    }

    /** Null-safe: a malformed part is dropped, never throws. */
    static WaterLog fromBson(BsonDocument d) {
        WaterLog w = new WaterLog();
        if (d == null) {
            return w;
        }
        try {
            if (d.containsKey("log") && d.get("log").isArray()) {
                for (BsonValue v : d.getArray("log")) {
                    if (v.isNumber()) {
                        w.cells.add(v.asNumber().longValue());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // malformed log dropped
        }
        try {
            if (d.containsKey("stops") && d.get("stops").isArray()) {
                for (BsonValue v : d.getArray("stops")) {
                    try {
                        BsonDocument o = v.asDocument();
                        w.stops.add(new Stop(o.getString("n").getValue(), o.getNumber("x").intValue(),
                                o.getNumber("y").intValue(), o.getNumber("z").intValue(),
                                Rotation.valueOf(o.getString("rot").getValue()),
                                o.getNumber("cx").intValue(), o.getNumber("cz").intValue()));
                    } catch (RuntimeException ignored) {
                        // malformed stop dropped
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // malformed stops dropped
        }
        try {
            if (d.containsKey("reached") && d.get("reached").isArray()) {
                for (BsonValue v : d.getArray("reached")) {
                    try {
                        BsonDocument o = v.asDocument();
                        Reached r = new Reached(o.getNumber("x").intValue(), o.getNumber("y").intValue(),
                                o.getNumber("z").intValue());
                        if (!w.reached.contains(r)) {
                            w.reached.add(r);
                        }
                    } catch (RuntimeException ignored) {
                        // malformed entry dropped
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // malformed reached list dropped
        }
        return w;
    }
}
