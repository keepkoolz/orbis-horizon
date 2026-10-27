package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Ground work around a boat station, without any world access: the dome of cells to clear above the deck level, the columns to fill
 * under the hut and between it and the shore with the block found below, and the check that the station master stands on solid ground with two free cells. Cell
 * states are those of DeployPlacement.CellState: 0 empty, 1 liquid, 2 solid. Everything is computed in the prefab frame, then each
 * column is rotated and added to the origin so that cells stay whole.
 */
final class BoatSiteGround {
    /** Horizontal radius of the dome, in cells. */
    static final int DOME_RADIUS = 10;
    /** Height of the dome above its centre, in cells. */
    static final int DOME_HEIGHT = 24;
    /** Least height cleared over the station footprint and over the shuttle, whatever the dome gives there. */
    static final int DOME_MIN_HEIGHT = 5;
    /** Most cells filled under one column. */
    static final int FILL_DEPTH = 32;
    /** Most columns walked outward from an edge of the land part of the station to find the shore. */
    static final int ATTACH_REACH = 5;
    /** Block used to fill when no column found a solid block. */
    static final String FILL_DEFAULT = "Soil_Sand";

    private static final double CENTRE_X = -0.5;
    private static final double CENTRE_Z = 6;

    private BoatSiteGround() {
    }

    /** Dome height at the horizontal distance d from its centre, -1 when d is outside the dome. */
    static int domeHeight(double d) {
        if (d >= DOME_RADIUS) {
            return -1;
        }
        double r = d / DOME_RADIUS;
        return (int) Math.floor(DOME_HEIGHT * Math.sqrt(1 - r * r));
    }

    /**
     * Bounding square of the dome columns in the prefab frame: {minX, minZ, maxX, maxZ}. Centred on the dome (z 6) with a half-size
     * of DOME_RADIUS + 1, so that it holds every column clearCells can return.
     */
    static int[] domeSquare() {
        int cz = (int) CENTRE_Z;
        return new int[] {-DOME_RADIUS - 1, cz - DOME_RADIUS - 1, DOME_RADIUS, cz + DOME_RADIUS + 1};
    }

    /** The world cells to empty: for each column of the dome, from origin.y up to origin.y + height. */
    static Set<Vector3i> clearCells(Vector3i origin, Rotation rotation, StructureShape shuttle) {
        Map<Long, Integer> heights = new LinkedHashMap<>();
        for (int px = -DOME_RADIUS - 1; px <= DOME_RADIUS; px++) {
            for (int pz = (int) CENTRE_Z - DOME_RADIUS - 1; pz <= (int) CENTRE_Z + DOME_RADIUS; pz++) {
                int h = domeHeight(Math.hypot(px - CENTRE_X, pz - CENTRE_Z));
                if (px >= -6 && px <= 5 && pz >= 1 && pz <= 11) {
                    h = Math.max(h, DOME_MIN_HEIGHT);
                }
                if (h >= 0) {
                    heights.put(key(px, pz), h);
                }
            }
        }
        Rotation boatRotation = Deployables.BOAT_STATION_BERTH_ROTATION;
        int[] berth = Deployables.BOAT_STATION_BERTH_ORIGIN;
        for (StructureShape.Cell c : shuttle.cells()) {
            Vector3i p = c.rotated(boatRotation);
            heights.merge(key(p.x + berth[0], p.z + berth[2]), DOME_MIN_HEIGHT, Math::max);
        }
        Set<Vector3i> out = new LinkedHashSet<>();
        for (Map.Entry<Long, Integer> e : heights.entrySet()) {
            Vector3i column = world(origin, rotation, (int) (e.getKey() >> 32), (int) (long) e.getKey());
            for (int dy = 0; dy <= e.getValue(); dy++) {
                out.add(new Vector3i(column.x, origin.y + dy, column.z));
            }
        }
        return out;
    }

    /** The world columns (x, origin.y - 1, z) under the hut to fill. */
    static List<Vector3i> fillColumns(Vector3i origin, Rotation rotation) {
        List<Vector3i> out = new ArrayList<>();
        for (int pz = 1; pz <= 7; pz++) {
            for (int px = -6; px <= -2; px++) {
                out.add(world(origin, rotation, px, pz).add(0, origin.y - 1, 0));
            }
        }
        for (int pz = 1; pz <= 5; pz++) {
            for (int px = -1; px <= 5; px++) {
                out.add(world(origin, rotation, px, pz).add(0, origin.y - 1, 0));
            }
        }
        return out;
    }

    /**
     * The columns that link the station to the shore. From each edge of the land part of the station (back edge pz 1 for px -6 to 5
     * walking -z, left edge px -6 for pz 1 to 7 walking -x, right edge px 5 for pz 1 to 5 walking +x), the columns are walked
     * outward in a straight line, at most ATTACH_REACH of them. The column at step k is land when its cell at origin.y - 1 is solid.
     * When land is found at step k, the columns of steps 1 to k - 1 are returned, none when there is no land in reach (no jetty into
     * the sea). A column already in fillColumns, or already returned for another line, is left out.
     */
    static List<Vector3i> attachColumns(Vector3i origin, Rotation rotation, DeployPlacement.CellState state) {
        Set<Long> seen = new java.util.HashSet<>();
        for (int pz = 1; pz <= 7; pz++) {
            for (int px = -6; px <= -2; px++) {
                seen.add(key(px, pz));
            }
        }
        for (int pz = 1; pz <= 5; pz++) {
            for (int px = -1; px <= 5; px++) {
                seen.add(key(px, pz));
            }
        }
        List<int[]> lines = new ArrayList<>();
        for (int px = -6; px <= 5; px++) {
            lines.add(new int[] {px, 1, 0, -1});
        }
        for (int pz = 1; pz <= 7; pz++) {
            lines.add(new int[] {-6, pz, -1, 0});
        }
        for (int pz = 1; pz <= 5; pz++) {
            lines.add(new int[] {5, pz, 1, 0});
        }
        List<Vector3i> out = new ArrayList<>();
        for (int[] line : lines) {
            List<int[]> gap = new ArrayList<>();
            boolean landFound = false;
            for (int k = 1; k <= ATTACH_REACH; k++) {
                int px = line[0] + line[2] * k;
                int pz = line[1] + line[3] * k;
                Vector3i w = world(origin, rotation, px, pz);
                if (state.at(w.x, origin.y - 1, w.z) == 2) {
                    landFound = true;
                    break;
                }
                gap.add(new int[] {px, pz});
            }
            if (!landFound) {
                continue;
            }
            for (int[] c : gap) {
                if (seen.add(key(c[0], c[1]))) {
                    out.add(world(origin, rotation, c[0], c[1]).add(0, origin.y - 1, 0));
                }
            }
        }
        return out;
    }

    /**
     * The blocks to place, by world cell. Each column is read from origin.y - 1 downwards over FILL_DEPTH cells at most. The empty and
     * liquid cells above the first solid one are filled with that solid block (solidIdAt). A column with no solid cell fills all its
     * cells with the most frequent block of the other columns (ties go in alphabetical order), or FILL_DEFAULT when there is none.
     */
    static Map<Vector3i, String> fillPlan(Vector3i origin, Rotation rotation, DeployPlacement.CellState state,
                                          Function<Vector3i, String> solidIdAt) {
        Map<Vector3i, String> plan = new LinkedHashMap<>();
        List<List<Vector3i>> deepColumns = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        List<Vector3i> tops = new ArrayList<>(fillColumns(origin, rotation));
        tops.addAll(attachColumns(origin, rotation, state));
        for (Vector3i top : tops) {
            List<Vector3i> cells = new ArrayList<>();
            Vector3i solid = null;
            for (int i = 0; i < FILL_DEPTH; i++) {
                Vector3i p = new Vector3i(top.x, top.y - i, top.z);
                if (state.at(p.x, p.y, p.z) == 2) {
                    solid = p;
                    break;
                }
                cells.add(p);
            }
            if (solid == null) {
                deepColumns.add(cells);
                continue;
            }
            String id = solidIdAt.apply(solid);
            if (id == null) {
                id = FILL_DEFAULT;
            }
            counts.merge(id, 1, Integer::sum);
            for (Vector3i p : cells) {
                plan.put(p, id);
            }
        }
        String common = FILL_DEFAULT;
        int best = 0;
        for (String id : new java.util.TreeSet<>(counts.keySet())) {
            if (counts.get(id) > best) {
                common = id;
                best = counts.get(id);
            }
        }
        for (List<Vector3i> cells : deepColumns) {
            for (Vector3i p : cells) {
                plan.put(p, common);
            }
        }
        return plan;
    }

    /**
     * The cell that keeps the station master from standing: the cell under his feet when it is not solid, otherwise his own cell or
     * the one above when one of them is not empty. Null when the spot is good.
     */
    static Vector3i masterFault(Vector3i origin, Rotation rotation, DeployPlacement.CellState state) {
        int[] pos = Deployables.BOAT_STATION_MASTER_POS;
        Vector3i m = rotation.rotateYaw(new Vector3i(pos[0], pos[1], pos[2]), new Vector3i()).add(origin);
        if (state.at(m.x, m.y - 1, m.z) != 2) {
            return new Vector3i(m.x, m.y - 1, m.z);
        }
        if (state.at(m.x, m.y, m.z) != 0) {
            return m;
        }
        if (state.at(m.x, m.y + 1, m.z) != 0) {
            return new Vector3i(m.x, m.y + 1, m.z);
        }
        return null;
    }

    /** The state seen after the cells of emptied became empty and the cells of solid became solid (solid wins). */
    static DeployPlacement.CellState simulated(DeployPlacement.CellState base, Set<Vector3i> emptied, Set<Vector3i> solid) {
        return (x, y, z) -> {
            Vector3i p = new Vector3i(x, y, z);
            if (solid.contains(p)) {
                return 2;
            }
            return emptied.contains(p) ? 0 : base.at(x, y, z);
        };
    }

    private static Vector3i world(Vector3i origin, Rotation rotation, int px, int pz) {
        return rotation.rotateYaw(new Vector3i(px, 0, pz), new Vector3i()).add(origin.x, 0, origin.z);
    }

    private static long key(int px, int pz) {
        return ((long) px << 32) | (pz & 0xFFFFFFFFL);
    }
}
