package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;
import com.keepkoolz.orbishorizon.api.Stations;

import java.util.logging.Level;

/** Aligns the registry of generated stations with the transit network at start. */
final class WorldgenReconcile {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private WorldgenReconcile() {
    }

    /**
     * Marks REMOVED every BUILT entry whose column holds no station of the network. Does nothing when the network could not be
     * trusted (an unreadable station file looks like an empty network). Returns the number of entries changed.
     */
    static int run(GeneratedStations registry, Stations stations) {
        int removed = 0;
        try {
            if (!stations.networkTrusted()) {
                LOGGER.at(Level.WARNING).log("Transit network unreadable, generated stations not reconciled");
                return 0;
            }
            for (String world : registry.worlds()) {
                for (GeneratedStations.Entry e : registry.entries(world)) {
                    if (e.state == GeneratedStations.State.BUILT && stations.stationAt(world, e.x, e.z) == null) {
                        registry.setState(e, GeneratedStations.State.REMOVED);
                        removed++;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated stations not reconciled");
            WorldgenApi.telemetry().error("worldgen", e, null, "reconcile");
        }
        return removed;
    }
}
