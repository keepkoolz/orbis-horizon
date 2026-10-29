package com.keepkoolz.orbishorizon;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure log of what a vehicle with a pilot has seen of the world while flying: the 16-block cells its rotation centre went through
 * and the stations it came close to. The pilot only offers destinations found here. Nothing here touches the game except BSON.
 */
final class SkyLog {
    static final int GRID = 16;                 // side of a visited cell (blocks)
    static final int STATION_REACH = 40;        // a station is discovered when the vehicle centre passes this close (horizontal, blocks)
    static final int MARKER_REACH = 32;         // a marker is offered when a visited cell is this close (horizontal, blocks)

    /** Identifier of a discovered station: its origin block, as for the boat stations reached by a {@link WaterLog}. */
    record Reached(int x, int y, int z) {}

    private final Set<Long> cells = new HashSet<>();
    private final List<Reached> reached = new ArrayList<>();
    // Transient: last recorded cell, not saved.
    private boolean hasLast;
    private int lastGx;
    private int lastGz;

    SkyLog copy() {
        SkyLog c = new SkyLog();
        c.cells.addAll(cells);
        c.reached.addAll(reached);
        return c;
    }

    boolean isEmpty() {
        return cells.isEmpty() && reached.isEmpty();
    }

    int size() {
        return cells.size();
    }

    static long cell(int gx, int gz) {
        return WaterRoute.columnKey(gx, gz);
    }

    static int gridOf(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), GRID);
    }

    /** Records the cell of this position; cells skipped since the last recorded one are added along the line. True if new cells were added. */
    boolean record(double x, double z) {
        int gx = gridOf(x);
        int gz = gridOf(z);
        boolean added = false;
        if (hasLast && (gx != lastGx || gz != lastGz)) {
            int dx = gx - lastGx;
            int dz = gz - lastGz;
            int n = Math.max(Math.abs(dx), Math.abs(dz));
            for (int i = 1; i <= n; i++) {
                int cx = lastGx + (int) Math.round((double) dx * i / n);
                int cz = lastGz + (int) Math.round((double) dz * i / n);
                added |= cells.add(cell(cx, cz));
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

    List<Reached> reached() {
        return Collections.unmodifiableList(reached);
    }

    boolean hasReached(Reached r) {
        return reached.contains(r);
    }

    /** True only the first time this station is added. */
    boolean addReached(int x, int y, int z) {
        Reached r = new Reached(x, y, z);
        if (reached.contains(r)) {
            return false;
        }
        reached.add(r);
        return true;
    }

    /** Whether a vehicle centre at (cx, cz) is close enough to a station reference point (px, pz) to discover it. */
    static boolean stationInReach(double cx, double cz, double px, double pz) {
        return stationInReach(cx, cz, px, pz, STATION_REACH);
    }

    /** As {@link #stationInReach(double, double, double, double)}, with a horizontal reach of radius blocks (inclusive). */
    static boolean stationInReach(double cx, double cz, double px, double pz, double radius) {
        double dx = px - cx;
        double dz = pz - cz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** True if a visited cell is within MARKER_REACH blocks of the point (nearest point of the cell square, horizontal distance). */
    boolean markerVisited(double x, double z) {
        if (cells.isEmpty()) {
            return false;
        }
        int minGx = gridOf(x - MARKER_REACH - GRID);
        int maxGx = gridOf(x + MARKER_REACH);
        int minGz = gridOf(z - MARKER_REACH - GRID);
        int maxGz = gridOf(z + MARKER_REACH);
        for (int gx = minGx; gx <= maxGx; gx++) {
            for (int gz = minGz; gz <= maxGz; gz++) {
                if (!contains(gx, gz)) {
                    continue;
                }
                double nx = Math.max((double) gx * GRID, Math.min(x, (double) gx * GRID + GRID));
                double nz = Math.max((double) gz * GRID, Math.min(z, (double) gz * GRID + GRID));
                if (Math.hypot(x - nx, z - nz) <= MARKER_REACH) {
                    return true;
                }
            }
        }
        return false;
    }

    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        BsonArray log = new BsonArray();
        for (long k : cells) {
            log.add(new BsonInt64(k));
        }
        d.put("log", log);
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
    static SkyLog fromBson(BsonDocument d) {
        SkyLog s = new SkyLog();
        if (d == null) {
            return s;
        }
        try {
            if (d.containsKey("log") && d.get("log").isArray()) {
                for (BsonValue v : d.getArray("log")) {
                    if (v.isNumber()) {
                        s.cells.add(v.asNumber().longValue());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // malformed log dropped
        }
        try {
            if (d.containsKey("reached") && d.get("reached").isArray()) {
                for (BsonValue v : d.getArray("reached")) {
                    try {
                        BsonDocument o = v.asDocument();
                        Reached r = new Reached(o.getNumber("x").intValue(), o.getNumber("y").intValue(), o.getNumber("z").intValue());
                        if (!s.reached.contains(r)) {
                            s.reached.add(r);
                        }
                    } catch (RuntimeException ignored) {
                        // malformed entry dropped
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // malformed reached list dropped
        }
        return s;
    }
}
