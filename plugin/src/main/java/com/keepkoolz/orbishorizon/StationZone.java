package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Protected cylinder around each station: nothing of the six player structures (balloons, airship, tents)
 * may be placed inside it. Pure geometry here, the world is only read to list the stations.
 */
final class StationZone {
    private StationZone() {
    }

    /** One station's cylinder: centre of the pad, from the grass layer up to topY (inclusive). */
    record Zone(String station, double cx, double cz, int bottomY, int topY) {
    }

    static Zone zoneOf(TransitNetwork.Station s) {
        Vector3d p = StationMarkerProvider.markerPoint(s.origin, s.rotation);
        return new Zone(s.name, p.x, p.z, s.origin.y + Deployables.STATION_GRASS_Y,
                (int) p.y + TransitConstants.STATION_ZONE_HEIGHT);
    }

    /** True if the cell (x, y, z) lies in the height range and its square is closer than the radius to the axis. */
    static boolean cellTouches(Zone z, int x, int y, int cz) {
        if (y < z.bottomY() || y > z.topY()) {
            return false;
        }
        double dx = z.cx() - Math.max(x, Math.min(z.cx(), x + 1));
        double dz = z.cz() - Math.max(cz, Math.min(z.cz(), cz + 1));
        double r = TransitConstants.STATION_ZONE_RADIUS;
        return dx * dx + dz * dz < r * r;
    }

    /** Name of the first station whose zone one cell of the placed shape touches, or null. */
    static String blockingStation(List<Zone> zones, StructureShape s, Vector3i origin, Rotation rotation) {
        if (s == null || zones.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        List<Vector3i> world = new ArrayList<>(s.cells().size());
        for (StructureShape.Cell c : s.cells()) {
            Vector3i w = c.rotated(rotation).add(origin);
            world.add(w);
            minX = Math.min(minX, w.x);
            maxX = Math.max(maxX, w.x);
            minY = Math.min(minY, w.y);
            maxY = Math.max(maxY, w.y);
            minZ = Math.min(minZ, w.z);
            maxZ = Math.max(maxZ, w.z);
        }
        double r = TransitConstants.STATION_ZONE_RADIUS;
        for (Zone z : zones) {
            // Bounding-box prefilter against the cylinder's bounding box.
            if (maxY < z.bottomY() || minY > z.topY()
                    || maxX + 1 < z.cx() - r || minX > z.cx() + r
                    || maxZ + 1 < z.cz() - r || minZ > z.cz() + r) {
                continue;
            }
            for (Vector3i w : world) {
                if (cellTouches(z, w.x, w.y, w.z)) {
                    return z.station();
                }
            }
        }
        return null;
    }

    /** Same test for a deployment of the given structure type; null when the type is not concerned. */
    static String blockingStation(World world, String kindId, StructureShape s, Vector3i origin, Rotation rotation) {
        if (!concerned(kindId)) {
            return null;
        }
        List<Zone> zones = new ArrayList<>();
        for (TransitNetwork.Station st : TransitNetwork.get().stations(world.getName())) {
            zones.add(zoneOf(st));
        }
        return blockingStation(zones, s, origin, rotation);
    }

    private static boolean concerned(String kindId) {
        return Deployables.BALLOON_KIND.equals(kindId) || Deployables.BASIC_KIND.equals(kindId)
                || Deployables.TRANSPORT_KIND.equals(kindId) || Deployables.AIRSHIP_KIND.equals(kindId) || Deployables.STEAMBOAT_KIND.equals(kindId)
                || Deployables.TENT_KIND.equals(kindId) || Deployables.TENT_BIG_KIND.equals(kindId);
    }
}
