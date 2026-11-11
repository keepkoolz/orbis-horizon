package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure geometry of a station: master cell, quay anchor and quay rotation, and the placements derived from the quay. */
final class StationGeometry {
    private StationGeometry() {}

    /** Geometry of a station: master cell, master yaw in degrees, quay anchor and quay rotation. */
    record Converted(Vector3i master, float masterYaw, Vector3i quay, Rotation quayRotation) {}

    /** Vertical offset between the quay anchor and the dock origin of a balloon station. */
    private static final int DOCK_ABOVE_QUAY = 3;

    /** Geometry of a station described by a whole prefab (origin and rotation), from the fixed cells of its kind. */
    static Converted convert(String kind, Vector3i origin, Rotation rotation) {
        boolean boat = Deployables.BOAT_STATION_KIND.equals(kind);
        StationSpec spec = boat ? StationSpec.BOAT : StationSpec.BALLOON;
        int[] mp = spec.masterPos();
        float[] look = spec.masterLook();
        Vector3i base = new Vector3i(origin);
        Vector3i master = rotation.rotateYaw(new Vector3i(mp[0], mp[1], mp[2]), new Vector3i()).add(base);
        Vector3i quayLocal = boat
                ? new Vector3i(Deployables.BOAT_STATION_PIER_END[0], Deployables.BOAT_STATION_PIER_END[1], Deployables.BOAT_STATION_PIER_END[2])
                : new Vector3i(Deployables.STATION_DOCK_ORIGIN[0], Deployables.STATION_DOCK_ORIGIN[1] - DOCK_ABOVE_QUAY,
                        Deployables.STATION_DOCK_ORIGIN[2]);
        Vector3i quay = rotation.rotateYaw(quayLocal, new Vector3i()).add(base);
        // Same convention as StationMaster.facingYaw: forward = (-sin yaw, -cos yaw), towards the look point.
        Vector3f me = rotation.rotateYaw(new Vector3f(mp[0], mp[1], mp[2]), new Vector3f());
        Vector3f foot = rotation.rotateYaw(new Vector3f(look[0], look[1], look[2]), new Vector3f());
        float yaw = (float) Math.toDegrees(Math.atan2(-(foot.x - me.x), -(foot.z - me.z)));
        return new Converted(master, yaw, quay, rotation);
    }

    /** Dock origin of a balloon station: the quay anchor raised by 3. */
    static Vector3i dockOrigin(Vector3i quay) {
        return new Vector3i(quay).add(0, DOCK_ABOVE_QUAY, 0);
    }

    /**
     * Offset, in the frame of the quay (not turned), from the quay anchor to the origin of the large balloon prefab docked on the
     * pad: the basket (x 0, z 8) centred on the anchor and its lowest block (y 5) one cell above the quay layer.
     */
    private static final Vector3i BIG_BALLOON_DOCK_OFFSET = new Vector3i(0, 1 - 5, -8);

    /** Origin of the large balloon prefab docked on the quay: the offset turned by the quay rotation, added to the quay anchor. */
    static Vector3i bigBalloonDockOrigin(Vector3i quay, Rotation quayRotation) {
        return quayRotation.rotateYaw(new Vector3i(BIG_BALLOON_DOCK_OFFSET), new Vector3i()).add(quay);
    }

    /** Placement rotation of the large balloon docked on the quay: the rotation of the quay. */
    static Rotation bigBalloonDockRotation(Rotation quayRotation) {
        return quayRotation;
    }

    /** Berth origin of the station boat: the pier end plus (0, -1, 3) turned by the quay rotation. */
    static Vector3i berthOrigin(Vector3i pierEnd, Rotation r) {
        return r.rotateYaw(new Vector3i(0, -1, 3), new Vector3i()).add(pierEnd);
    }

    /** World rotation of the station boat, turned a half turn when the station is flipped. */
    static Rotation berthRotation(Rotation r, boolean flipped) {
        Rotation out = r.add(Deployables.BOAT_STATION_BERTH_ROTATION);
        return flipped ? out.add(Rotation.OneEighty) : out;
    }

    /** Bottom of the protected zone, in world y. */
    static int zoneBottomY(String kind, Vector3i quay) {
        return quay.y - (Deployables.BOAT_STATION_KIND.equals(kind) ? 10 : 7);
    }

    /** Half side of the column above the pad of a balloon station, kept free of blocks up to the height limit. */
    private static final int COLUMN_RADIUS = 3;
    /** Half side of the square around the pad of a balloon station where nothing is built above the pad plus two. */
    private static final int SQUARE_RADIUS = 12;
    /** Height above the quay anchor allowed in the square around the pad. */
    private static final int SQUARE_HEIGHT = 2;

    /**
     * Whether a cell is in the free volume of a balloon station: the column of 7 x 7 above the quay layer without any height limit,
     * and the square of 25 x 25 centred on the quay anchor above the quay layer plus two.
     */
    static boolean inBalloonClearance(Vector3i quay, int x, int y, int z) {
        int dx = Math.abs(x - quay.x);
        int dz = Math.abs(z - quay.z);
        if (dx <= COLUMN_RADIUS && dz <= COLUMN_RADIUS && y > quay.y) {
            return true;
        }
        return dx <= SQUARE_RADIUS && dz <= SQUARE_RADIUS && y > quay.y + SQUARE_HEIGHT;
    }

    /**
     * Whether a cell is in the corridor of the station boat: in the frame of the pier (rotation r, origin at the pier end), local x
     * from -8 to 8, local z from 1 to 20, and heights from one under the water surface to eight above it.
     */
    static boolean inBoatCorridor(Vector3i pierEnd, Rotation r, int surfaceY, int x, int y, int z) {
        if (y < surfaceY - 1 || y > surfaceY + 8) {
            return false;
        }
        Vector3i l = r.toInverse().rotateYaw(new Vector3i(x - pierEnd.x, 0, z - pierEnd.z), new Vector3i());
        return l.x >= -8 && l.x <= 8 && l.z >= 1 && l.z <= 20;
    }

    /**
     * Whether a cell belongs to the locked core of a station's quay. Balloon station: the pad layer of 7 x 7 at the height of the
     * quay anchor. Boat station: in the frame of the pier, local x from -2 to 3, local z from -4 to 0, heights from 10 under the
     * pier end to 2 above it.
     */
    static boolean inQuayCore(TransitNetwork.Station s, int x, int y, int z) {
        Vector3i q = s.quay;
        if (q == null) {
            return false;
        }
        if (!Deployables.BOAT_STATION_KIND.equals(s.kind)) {
            return y == q.y && Math.abs(x - q.x) <= COLUMN_RADIUS && Math.abs(z - q.z) <= COLUMN_RADIUS;
        }
        if (y < q.y - 10 || y > q.y + 2) {
            return false;
        }
        Rotation r = s.quayRotation == null ? Rotation.None : s.quayRotation;
        Vector3i l = r.toInverse().rotateYaw(new Vector3i(x - q.x, 0, z - q.z), new Vector3i());
        return l.x >= -2 && l.x <= 3 && l.z >= -4 && l.z <= 0;
    }

    /** Test of a cell where a player can stand: a solid block below and two free cells above. */
    interface Standable {
        boolean at(int x, int y, int z);
    }

    /** Farthest horizontal distance from the master cell, in blocks, of a drop point. */
    private static final int DROP_RADIUS = 6;
    /** Largest height difference from the master cell of a drop point. */
    private static final int DROP_HEIGHT = 2;
    /** Side of the square pad around the quay anchor. */
    private static final int PAD_SIDE = 7;

    /**
     * Drop points of arriving passengers (feet position, cell centre): the standable cells within DROP_RADIUS blocks of the master
     * cell horizontally and DROP_HEIGHT vertically, the master cell excluded, nearest first (horizontal distance, then height
     * difference, then x, then z), looped up to count. Without any such cell, count times the centre of the master cell.
     */
    static List<Vector3d> dropPoints(Vector3i master, int count, Standable standable) {
        return dropPoints(master, count, standable, false);
    }

    /**
     * Centre of the drop points of a station. Maritime station: its arrival cell if set and standable, else the cell above the quay
     * anchor, else the master cell. Any other station: the master cell.
     */
    static Vector3i dropCentre(TransitNetwork.Station s, Standable standable) {
        if (!Deployables.BOAT_STATION_KIND.equals(s.kind)) {
            return s.master;
        }
        Vector3i a = s.arrival;
        if (a != null && standable.at(a.x, a.y, a.z)) {
            return a;
        }
        Vector3i q = s.quay;
        return q != null ? new Vector3i(q.x, q.y + 1, q.z) : s.master;
    }

    static final int ARRIVAL_RADIUS = 16;
    static final int ARRIVAL_HEIGHT = 8;

    /**
     * Why an arrival point cannot be set at the cell, as a message key (after "chat."), or null when it can.
     */
    static String arrivalRefusal(Vector3i cell, Vector3i quay, boolean owner, boolean standable) {
        if (!owner) {
            return "transit.arrival.notOwner";
        }
        if (quay == null) {
            return "transit.arrival.noQuay";
        }
        if (!standable) {
            return "transit.arrival.badSpot";
        }
        long dx = cell.x - quay.x;
        long dz = cell.z - quay.z;
        if (dx * dx + dz * dz > (long) ARRIVAL_RADIUS * ARRIVAL_RADIUS || Math.abs(cell.y - quay.y) > ARRIVAL_HEIGHT) {
            return "transit.arrival.tooFar";
        }
        return null;
    }

    /**
     * Same as the three parameter version around any centre; with includeCentre the centre cell itself is a candidate (distance 0).
     */
    static List<Vector3d> dropPoints(Vector3i master, int count, Standable standable, boolean includeCentre) {
        record Candidate(int x, int y, int z, int dist, int dy) {}
        List<Candidate> found = new ArrayList<>();
        for (int dx = -DROP_RADIUS; dx <= DROP_RADIUS; dx++) {
            for (int dz = -DROP_RADIUS; dz <= DROP_RADIUS; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > DROP_RADIUS * DROP_RADIUS || (!includeCentre && dx == 0 && dz == 0)) {
                    continue;
                }
                for (int dy = -DROP_HEIGHT; dy <= DROP_HEIGHT; dy++) {
                    int x = master.x + dx;
                    int y = master.y + dy;
                    int z = master.z + dz;
                    if (standable.at(x, y, z)) {
                        found.add(new Candidate(x, y, z, d2, Math.abs(dy)));
                    }
                }
            }
        }
        found.sort(Comparator.comparingInt(Candidate::dist).thenComparingInt(Candidate::dy)
                .thenComparingInt(Candidate::x).thenComparingInt(Candidate::z));
        List<Vector3d> out = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            if (found.isEmpty()) {
                out.add(new Vector3d(master.x + 0.5, master.y + 0.1, master.z + 0.5));
            } else {
                Candidate c = found.get(i % found.size());
                out.add(new Vector3d(c.x() + 0.5, c.y() + 0.1, c.z() + 0.5));
            }
        }
        return out;
    }

    /** Standable test of a world: solid block below, two free cells above, a section that is not loaded counts as not standable. */
    static Standable standable(World world) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        return (x, y, z) -> solid(reader, x, y - 1, z) && free(reader, x, y, z) && free(reader, x, y + 1, z);
    }

    private static boolean solid(SectionReader reader, int x, int y, int z) {
        BlockType bt = SectionBlocks.typeAt(reader, x, y, z);
        return bt != null && bt.getMaterial() == BlockMaterial.Solid;
    }

    private static boolean free(SectionReader reader, int x, int y, int z) {
        if (!reader.hasStorageInMemory(x, y, z)) {
            return false;
        }
        BlockType bt = SectionBlocks.typeAt(reader, x, y, z);
        return bt == null || bt.getMaterial() != BlockMaterial.Solid;
    }

    /** World point of the map marker of a station: the centre of its quay cell, one block up. */
    static Vector3d markerPoint(TransitNetwork.Station s) {
        Vector3i q = s.quay;
        return new Vector3d(q.x + 0.5, q.y + 1, q.z + 0.5);
    }

    /**
     * Position of a station in the world for distances and directions: the marker point when it has a quay, else the centre of its
     * master cell, else its origin. The origin is only the identity of a station and is never used as a position.
     */
    static Vector3d position(TransitNetwork.Station s) {
        if (s.quay != null) {
            return markerPoint(s);
        }
        Vector3i m = s.master;
        if (m != null) {
            return new Vector3d(m.x + 0.5, m.y, m.z + 0.5);
        }
        return new Vector3d(s.origin.x + 0.5, s.origin.y, s.origin.z + 0.5);
    }

    /** Height of the quay layer of a station, the origin height for a station without a quay. */
    static int padHeight(TransitNetwork.Station s) {
        return s.quay != null ? s.quay.y : s.origin.y;
    }

    /** Cell around which the sections of a station are loaded: the dock origin of a balloon station, the pier end of a boat station. */
    static Vector3i sectionCentre(TransitNetwork.Station s) {
        if (s.quay == null) {
            return new Vector3i(s.origin);
        }
        return Deployables.BOAT_STATION_KIND.equals(s.kind) ? new Vector3i(s.quay) : dockOrigin(s.quay);
    }

    /** Free cells kept between the highest block under a hovering ship and its lowest block. */
    static final int HOVER_GAP = 3;
    /** Lowest block of a ship hovering over a bare balloon station, above its quay layer (the highest statue of the prefab stands 4 under it). */
    static final int BARE_HOVER_ABOVE_QUAY = 7;
    /** Height above the quay searched for blocks under a hovering ship, and loaded for it. */
    static final int HOVER_SCAN_HEIGHT = 64;
    /** Half side of the footprint of a hovering ship. */
    static final int HOVER_RADIUS = 18;

    /**
     * Lowest block of a ship hovering above a station: HOVER_GAP free cells above the highest solid block under the ship, and never
     * under the level of a bare station (7 above the quay layer, the old statue height plus the gap).
     */
    static int hoverBottomY(int quayY, int highestSolidUnderShip) {
        long above = highestSolidUnderShip == Integer.MIN_VALUE ? Integer.MIN_VALUE : (long) highestSolidUnderShip + 1 + HOVER_GAP;
        return (int) Math.max(quayY + BARE_HOVER_ABOVE_QUAY, above);
    }

    /** Test of a solid cell; a section that is not loaded reads as empty. */
    interface Solid {
        boolean at(int x, int y, int z);
    }

    /**
     * Highest solid cell in the columns of the square of the given half side around (cx, cz), searched from toY down to fromY, or
     * Integer.MIN_VALUE when there is none.
     */
    static int highestSolid(Solid solid, int cx, int cz, int radius, int fromY, int toY) {
        int best = Integer.MIN_VALUE;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = toY; y >= Math.max(fromY, best + 1); y--) {
                    if (solid.at(x, y, z)) {
                        best = y;
                        break;
                    }
                }
            }
        }
        return best;
    }

    /** Box {minX, minY, minZ, maxX, maxY, maxZ} read for the hover height and loaded before it. */
    static int[] hoverBox(Vector3i quay) {
        return new int[] {quay.x - HOVER_RADIUS, quay.y, quay.z - HOVER_RADIUS, quay.x + HOVER_RADIUS,
                Math.min(BalloonManager.WORLD_MAX_Y, quay.y + HOVER_SCAN_HEIGHT), quay.z + HOVER_RADIUS};
    }

    /**
     * Highest solid block over the ship footprint from the quay layer up, read from the world; cells inside the ignored box (the
     * parked ship that is recalled, {minX, minY, minZ, maxX, maxY, maxZ}, may be null) do not count.
     */
    static int highestSolidUnderShip(World world, Vector3i quay, int[] ignored) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        int[] b = hoverBox(quay);
        return highestSolid((x, y, z) -> !inBox(ignored, x, y, z) && solid(reader, x, y, z), quay.x, quay.z, HOVER_RADIUS, b[1], b[4]);
    }

    static boolean inBox(int[] b, int x, int y, int z) {
        return b != null && x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    /** The k-th cell (x first, then z, looping over 49) of the 7 x 7 pad centred on the quay anchor, at the height of the quay. */
    static Vector3i padCell(TransitNetwork.Station s, int k) {
        Vector3i q = s.quay;
        int i = Math.floorMod(k, PAD_SIDE * PAD_SIDE);
        return new Vector3i(q.x - PAD_SIDE / 2 + i % PAD_SIDE, q.y, q.z - PAD_SIDE / 2 + i / PAD_SIDE);
    }
}
