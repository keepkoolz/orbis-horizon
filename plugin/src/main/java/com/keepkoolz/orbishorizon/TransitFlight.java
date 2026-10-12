package com.keepkoolz.orbishorizon;

/**
 * State of a transit balloon flight (public transport between stations), carried by BalloonFlight.transit. The balloon has no pilot:
 * the server moves it (BalloonManager.tickDriven asks TransitAutopilot for the position, except during APPROACH, which uses the
 * ordinary automatic descent onto the pad).
 */
final class TransitFlight {
    enum Phase { APPROACH, CLIMB, CRUISE, HOLD, DESCEND, DROP, VANISH }

    /** Name of the station the flight started from (for APPROACH, the station whose pad it lands on). */
    final String from;
    /** Name of the destination station, null for a balloon that only climbs and vanishes. */
    volatile String to;
    volatile Phase phase;
    /** Index among the arrivals waiting at the same quay (Task 8). */
    volatile int holdIndex;
    volatile long phaseSinceMs;
    /** Last time the balloon moved forward (rescue after TransitConstants.RESCUE_NO_PROGRESS_SECONDS without progress). */
    volatile long lastProgressMs;
    /** Cruise height of the entity origin. */
    volatile double cruiseY;
    /** Height of the entity origin at which a VANISH flight is removed. */
    volatile double vanishY;

    /** Destination removed and no station left: the balloon goes straight down to the ground (phase DESCEND without a dock). */
    volatile boolean groundLanding;
    /** The flight holds the destination quay (StationQueue state ARRIVING): it may fly to the pad column and descend. */
    volatile boolean claimed;
    /** Where the balloon was when it started to hold: the holding point is on this side of the pad. */
    volatile org.joml.Vector3d comingFrom;
    /** Next HOLD message (ms). */
    volatile long nextHoldMsgMs;
    /** Last look-ahead obstacle test (ms) and its result. */
    volatile long lastAheadMs;
    volatile boolean aheadBlocked;
    volatile boolean aheadUnloaded;
    /** Start of the wait for unloaded chunks ahead (ms), 0 when not waiting. */
    volatile long chunkWaitSinceMs;
    /** Extent of the flight shape around the origin cell (minX, maxX, minZ, maxZ, minY, maxY, rotated), null until computed. */
    int[] extent;

    TransitFlight(String from, String to, Phase phase, long now) {
        this.from = from;
        this.to = to;
        this.phase = phase;
        this.phaseSinceMs = now;
        this.lastProgressMs = now;
    }

    void setPhase(Phase p, long now) {
        this.phase = p;
        this.phaseSinceMs = now;
        this.lastProgressMs = now;
    }
}
