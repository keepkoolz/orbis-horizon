package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Berth geometry of the boat stations (where the shuttle moors, its pre-berth point, the waypoints trimmed to it) and the column
 * probe the shuttle navigation reads the water with.
 */
final class BoatBerth {
    /** A column that stays unknown this long (ms) ends the wait for it. */
    static final long STALL_MS = 60_000L;
    /** Load attempts on the same missing column before giving up. */
    static final int MAX_LOAD_ATTEMPTS = 5;
    /** Hull height above the waterline when the shape cannot be read. */
    private static final int FALLBACK_HULL_HEIGHT = 6;
    /** Radius, in chart cells, around the berth where the debug command lists closed cells. */
    private static final int DESCRIBE_RADIUS = 8;

    private BoatBerth() {
    }


    // ---- berth geometry (pure) ----

    /** Prefab origin of the shuttle moored at the berth of this station, world cell. */
    static Vector3i berthOrigin(TransitNetwork.Station s) {
        Vector3i local = new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                Deployables.BOAT_STATION_BERTH_ORIGIN[2]);
        return s.rotation.rotateYaw(local, new Vector3i()).add(s.origin);
    }

    /**
     * World rotation of the moored shuttle: station rotation plus the berth rotation, plus a half turn when it lies the other way
     * round (flipped). The berth cell and the rotation centre are the same for both headings, the boarding steps (prefab x +-2, z 0)
     * are symmetric about the prefab origin, so a step touches the pier either way. Pure.
     */
    static Rotation berthRotation(Rotation stationRotation, boolean flipped) {
        Rotation r = stationRotation.add(Deployables.BOAT_STATION_BERTH_ROTATION);
        return flipped ? r.add(Rotation.OneEighty) : r;
    }

    /** World rotation of the shuttle moored the usual way (not flipped). */
    static Rotation berthRotation(TransitNetwork.Station s) {
        return berthRotation(s.rotation, false);
    }

    /** World rotation of the shuttle moored with this heading. */
    static Rotation berthRotation(TransitNetwork.Station s, boolean flipped) {
        return berthRotation(s.rotation, flipped);
    }

    /** Cell of the pivot of the moored shuttle. */
    static Vector3i berthPivotCell(TransitNetwork.Station s) {
        return berthPivotCell(s, false);
    }

    /** Cell of the pivot of the shuttle moored with this heading. */
    static Vector3i berthPivotCell(TransitNetwork.Station s, boolean flipped) {
        int[] pv = Deployables.STEAMBOAT_PUBLIC_PIVOT;
        return berthRotation(s, flipped).rotateYaw(new Vector3i(pv[0], pv[1], pv[2]), new Vector3i()).add(berthOrigin(s));
    }

    /** World position of the pivot of the moored shuttle (cell centre in x and z, like AirshipManager.initFlight). */
    static Vector3d berthPivot(TransitNetwork.Station s) {
        return berthPivot(s, false);
    }

    /** World position of the pivot of the shuttle moored with this heading. */
    static Vector3d berthPivot(TransitNetwork.Station s, boolean flipped) {
        Vector3i c = berthPivotCell(s, flipped);
        return new Vector3d(c.x + 0.5, c.y, c.z + 0.5);
    }

    /**
     * World position of the rotation centre of the moored shuttle: the point AirshipManager turns the ship around (prefab x 0,
     * z rotationCenterZ, at the pivot height). The water routes join these centres, since a turn keeps the centre fixed. It is the same
     * point for both headings (the prefab origin).
     */
    static Vector3d berthCentre(TransitNetwork.Station s) {
        return AirshipManager.get().centerWorld(Deployables.STEAMBOAT_PUBLIC, berthPivot(s), berthTheta(s));
    }

    /** Column (x, z) of the rotation centre of the moored shuttle, y of the pivot cell: endpoint of the route searches. */
    static Vector3i berthCentreCell(TransitNetwork.Station s) {
        Vector3d c = berthCentre(s);
        return new Vector3i((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z));
    }

    /** Heading of the moored shuttle. */
    static double berthTheta(TransitNetwork.Station s) {
        return berthRotation(s).getRadians();
    }

    /** Heading of the shuttle moored with this heading (the usual one, or the opposite). */
    static double berthTheta(TransitNetwork.Station s, boolean flipped) {
        return berthRotation(s, flipped).getRadians();
    }

    /**
     * True when the opposite berth heading is closer to the heading of the shuttle than the usual one (more than a quarter turn away
     * from it). A tie keeps the usual heading. Pure.
     */
    static boolean preferFlipped(double theta, double usualTheta) {
        return Math.abs(AirshipMath.wrapPi(theta - usualTheta)) > Math.PI / 2 + 1e-9;
    }

    /**
     * Blocks the pre-berth point lies seaward of the berth centre, along the lateral axis of the moored shuttle (station +z, away from
     * the shore and the pier). The hull turns around its rotation centre, so the turning circle (corner radius of the prefab box,
     * about 7.0, hypot(2.5, 6.5) for the box x -2..2, z -5..6) around the pre-berth point must stay clear of the pier: 14 + 6 - 7.0 = 13.0,
     * a block over the pier edge at z 12. 6 is the smallest value that keeps the block (the test counts whole cells, the real gap is larger).
     */
    static final int BERTH_STANDOFF = 6;

    /** Pre-berth column: the berth centre column moved standoff cells along the station's +z axis (rotated with the station). */
    static Vector3i preBerthCell(Rotation stationRotation, Vector3i berthCentreCell, int standoff) {
        Vector3i d = stationRotation.rotateYaw(new Vector3i(0, 0, standoff), new Vector3i());
        return new Vector3i(berthCentreCell.x + d.x, berthCentreCell.y, berthCentreCell.z + d.z);
    }

    /** Pre-berth column of a station. */
    static Vector3i preBerthCell(TransitNetwork.Station s) {
        return preBerthCell(s.rotation, berthCentreCell(s), BERTH_STANDOFF);
    }

    private static double cellDistance(int[] wp, Vector3i cell) {
        return Math.hypot(wp[0] - cell.x, wp[1] - cell.z);
    }

    private static boolean sameCell(int[] wp, Vector3i cell) {
        return wp[0] == cell.x && wp[1] == cell.z;
    }

    /**
     * Route ending at a berth centre made to end at the pre-berth point: the waypoints nearer to the berth centre than the pre-berth
     * point are dropped (the last leg is never sailed bow first into the pier), the pre-berth column ends the route.
     */
    static List<int[]> arriveRoute(List<int[]> wps, Vector3i berthCell, Vector3i preCell, int standoff) {
        List<int[]> out = new ArrayList<>();
        for (int[] w : wps) {
            if (cellDistance(w, berthCell) < standoff - 1e-6) {
                if (out.isEmpty()) {
                    continue;
                }
                break;
            }
            if (!sameCell(w, preCell)) {
                out.add(w);
            }
        }
        out.add(new int[] {preCell.x, preCell.z});
        return out;
    }

    /**
     * Index to resume the untrimmed route at when the sideways arrival is abandoned: the first waypoint arriveRoute dropped (the trimmed
     * list is the kept prefix plus the pre-berth point), at most the last waypoint of the full route. Detours inserted into the trimmed
     * list after the trim are not in the full route: they are left out of the count here, once.
     */
    static int firstDropped(List<int[]> full, List<int[]> trimmed, int detours) {
        return Math.max(0, Math.min(trimmed.size() - 1 - detours, full.size() - 1));
    }

    /**
     * Route starting at a berth centre made to start at the pre-berth point: the shuttle first slides out to it, the waypoints nearer to
     * the berth centre than the pre-berth point are dropped.
     */
    static List<int[]> departRoute(List<int[]> wps, Vector3i berthCell, Vector3i preCell, int standoff) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[] {preCell.x, preCell.z});
        boolean inside = true;
        for (int[] w : wps) {
            if (inside && cellDistance(w, berthCell) < standoff - 1e-6) {
                continue;
            }
            inside = false;
            if (!sameCell(w, preCell) || out.size() > 1) {
                out.add(w);
            }
        }
        return out;
    }

    /** Water surface level at the berth: the prefab layer of the waterline sits on the highest water cell. */
    static int surfaceY(TransitNetwork.Station s) {
        return berthOrigin(s).y + Deployables.STEAMBOAT_PUBLIC.airship.waterlineY;
    }

    /**
     * Heights {bottom, surface, top} the column probe reads for a boat of this kind whose pivot is at height py: the lowest cell, the
     * waterline cell and the highest cell of the hull the flight tests ({@link AirshipManager.Hull}), so probe and hull agree. Without
     * a readable shape: the waterline cell, one block under it and {@link #FALLBACK_HULL_HEIGHT} above it.
     */
    static int[] range(Deployables.Kind kind, double py) {
        StructureShape shape = kind.shapeOrNull();
        if (shape == null) {
            int surface = (int) Math.floor(py + 0.5 - AirshipManager.spec(kind).pivot.y + AirshipManager.spec(kind).waterlineY);
            return new int[] {surface - 1, surface, surface + FALLBACK_HULL_HEIGHT};
        }
        AirshipManager.Hull h = AirshipManager.get().hullOf(kind, shape);
        return new int[] {h.bottomY(py), h.surfaceY(py), h.topY(py)};
    }

    /** {@link #range} of the shuttle moored at the berth of s. */
    private static int[] stationRange(TransitNetwork.Station s) {
        return range(Deployables.STEAMBOAT_PUBLIC, berthPivot(s).y);
    }

    /** Column navigable by an ice-breaking boat: water at the surface level and just below, nothing but breakable floe ice or air from there up to the hull top. */
    static WaterRoute.ColumnProbe probe(SectionReader[] holder, int[] range) {
        return (x, z) -> IceBreaker.column(y -> IceBreaker.kindAt(holder[0], x, y, z), range[0], range[1], range[2]);
    }

    /**
     * True when the shuttle moored at the berth of s can reach open water under the route rules (WaterRoute.exitsToOpenWater).
     * s need not be registered. Reads the loaded chunks only: an unloaded column counts as blocked.
     */
    static boolean hasOpenWaterExit(World world, TransitNetwork.Station s) {
        Vector3i pa = berthCentreCell(s);
        return exitsToOpenWaterAt(world, s, pa.x, pa.z);
    }

    /** Same as {@link #hasOpenWaterExit} for a shuttle at the column (x, z) on the water level of the berth of s. */
    private static boolean exitsToOpenWaterAt(World world, TransitNetwork.Station s, int x, int z) {
        SectionReader[] holder = {new SectionReader(world.getChunkStore())};
        return WaterRoute.exitsToOpenWater(probe(holder, stationRange(s)), x, z);
    }

    /**
     * True when a shuttle can appear at the column (x, z): a way out to open water from there, and the full turn clearance of the
     * shuttle hull around it (no taper), so the hull does not overlap the shore. An unloaded column counts as not clear.
     */
    static boolean offshoreSpawnClear(World world, TransitNetwork.Station s, int x, int z) {
        SectionReader[] holder = {new SectionReader(world.getChunkStore())};
        WaterRoute.ColumnProbe probe = probe(holder, stationRange(s));
        return WaterRoute.clearAround(probe, x, z, HullClearance.SHUTTLE.turn()) && WaterRoute.exitsToOpenWater(probe, x, z);
    }
    /** Debug lines of a station: its ocean, the closed chart cells around its berth and the pairs marked incomplete. */
    static List<Message> describe(TransitNetwork.Station s) {
        List<Message> out = new ArrayList<>();
        int ocean = s.oceanId;
        out.add(ocean > 0 ? Texts.t("transit.station.ocean").param("ocean", ocean) : Texts.t("transit.station.oceanUnknown"));
        SeaChartBook book = SeaCharts.book();
        Vector3i b = berthCentreCell(s);
        int cx = SeaChart.cellOf(b.x);
        int cz = SeaChart.cellOf(b.z);
        long now = System.currentTimeMillis();
        int closed = 0;
        for (int dx = -DESCRIBE_RADIUS; dx <= DESCRIBE_RADIUS; dx++) {
            for (int dz = -DESCRIBE_RADIUS; dz <= DESCRIBE_RADIUS; dz++) {
                if (book.closed(s.world, cx + dx, cz + dz, now)) {
                    closed++;
                }
            }
        }
        out.add(Texts.t("transit.station.closedNear").param("n", closed).param("radius", DESCRIBE_RADIUS));
        for (TransitNetwork.Station o : TransitNetwork.get().stations(s.world, Deployables.BOAT_STATION_KIND)) {
            if (o != s && (book.incomplete(s.world, s.name, o.name) || book.incomplete(s.world, o.name, s.name))) {
                out.add(Texts.t("transit.station.incompleteWith").param("name", o.name));
            }
        }
        return out;
    }

}
