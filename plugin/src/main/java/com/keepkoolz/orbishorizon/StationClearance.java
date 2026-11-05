package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;

import java.util.List;
import java.util.logging.Level;

/** Free volume of the stations of a world, and the rule that tells whether a station's structure may be edited. */
final class StationClearance {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Bounding box half side of the boat corridor: the pier frame is turned by quarter turns only, so the corridor reaches 20 blocks on each axis, 22 leaves a margin. */
    private static final int BOAT_REACH = 22;
    private static final int BALLOON_REACH = 12;

    private StationClearance() {
    }

    /** The station whose free volume holds this cell, null if none. Never throws. */
    static TransitNetwork.Station blocking(World world, int x, int y, int z) {
        try {
            List<TransitNetwork.Station> all = TransitNetwork.get().stations(world.getName());
            for (TransitNetwork.Station s : all) {
                org.joml.Vector3i q = s.quay;
                if (q == null) {
                    continue;
                }
                if (Deployables.BOAT_STATION_KIND.equals(s.kind)) {
                    if (Math.abs(x - q.x) > BOAT_REACH || Math.abs(z - q.z) > BOAT_REACH) {
                        continue;
                    }
                    Rotation r = s.quayRotation == null ? Rotation.None : s.quayRotation;
                    if (StationGeometry.inBoatCorridor(q, r, BoatBerth.surfaceY(s), x, y, z)) {
                        return s;
                    }
                } else {
                    if (Math.abs(x - q.x) > BALLOON_REACH || Math.abs(z - q.z) > BALLOON_REACH) {
                        continue;
                    }
                    if (StationGeometry.inBalloonClearance(q, x, y, z)) {
                        return s;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station clearance test failed");
            Telemetry.error("station-clearance", e, null, null);
        }
        return null;
    }

    /** Whether the structure of the station may be edited: its own flag, or a generated station while the setting allows it. */
    static boolean editable(TransitNetwork.Station s) {
        return s.editable || (s.owner == null && TransitSettings.generatedStationsEditable());
    }
}
