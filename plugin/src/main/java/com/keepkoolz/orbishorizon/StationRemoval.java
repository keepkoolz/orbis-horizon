package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.logging.Level;

/** Takes a station out of the network in one operation. It touches no block. */
final class StationRemoval {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private StationRemoval() {
    }

    /**
     * World thread: refunds the tickets of the station, removes its master NPC and its network entry, then drops its quay, ends the
     * flights and shuttles heading to it and removes it from the other queues. Each step is guarded on its own, in this order, so a
     * failure does not stop the next ones. Never throws.
     */
    static void fromNetwork(World world, TransitNetwork.Station s) {
        // Refund first: the payments must be written to refunds.json before the station leaves stations.json.
        step(s, "refund", () -> TransitFare.refundRemovedStation(s));
        step(s, "master", () -> StationMaster.remove(world.getEntityStore().getStore(), s));
        step(s, "network", () -> TransitNetwork.get().remove(s));
        step(s, "save", () -> TransitNetwork.get().save());
        step(s, "names", StationCommand.StationNameType::stationsChanged);
        step(s, "queue", () -> StationQueue.onStationRemoved(world, s));
        step(s, "quay", () -> BoatQuay.onStationRemoved(world, s));
    }

    private static void step(TransitNetwork.Station s, String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station %s: removal step %s failed", s.name, phase);
            Telemetry.error("transit", e, s.kind, "removal." + phase);
        }
    }
}
