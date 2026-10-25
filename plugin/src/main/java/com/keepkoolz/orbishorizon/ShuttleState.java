package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * State of a maritime shuttle flight (AirshipFlight.shuttle): the stations it links, the rotation centre waypoints (x, z) it follows and
 * its phase. {@link BoatAutopilot} sails SAIL, ARRIVE and LEAVE; the other phases are held still and driven by the boat station logic
 * (BoatQuay).
 */
final class ShuttleState {
    enum Phase { APPROACH, DOCKED, SAIL, ARRIVE, HOLD, DROP, LEAVE }

    /** What the boat station logic does when the shuttle reaches its berth or must be rescued. */
    interface Listener {
        /** The shuttle stopped at the berth pivot of {@link #to}, aligned on the berth heading (phase already DOCKED). */
        void onArrived(AirshipFlight f);

        /**
         * The shuttle cannot go on (no route, no progress for 30 s, ship entity lost): its passengers must be taken care of and the
         * flight MUST be ended (AirshipManager.endShuttle or a mooring in blocks). Called once per flight; if the flight is still
         * registered afterwards, BoatAutopilot ends it.
         */
        void onRescue(AirshipFlight f);

        /**
         * LEAVE: the shuttle reached the last waypoint of its leaving leg. The flight MUST be ended; if it is still registered
         * afterwards, BoatAutopilot ends it.
         */
        void onRouteEnd(AirshipFlight f);

        /** The shuttle's destination changed after a failed one-off route (next candidate station). */
        default void onRetarget(AirshipFlight f, TransitNetwork.Station next) {
        }
    }

    final TransitNetwork.Station from;
    volatile TransitNetwork.Station to;
    /** Rotation centre columns {x, z} from the departure berth to the arrival berth (both included); the last one is the berth centre. */
    volatile List<int[]> waypoints;
    /** Index of the waypoint sailed to. */
    int index;
    volatile Phase phase;
    long phaseSinceMs;
    /** Last time the shuttle moved or turned noticeably (blocked hull watchdog). */
    long lastProgressMs;
    double progressX;
    double progressZ;
    double progressTheta;
    /** Place in the waiting line of the arrival station (HOLD). */
    int holdIndex;
    /** Turning on the spot toward the next waypoint (hysteresis between turning and sailing). */
    boolean turning = true;
    /** Travel window (wheels, thrust): start time and pose. */
    long windowMs;
    double windowX;
    double windowZ;
    double windowTheta;
    /** A one-off route was asked to BoatRoutes and not answered yet. */
    boolean rerouting;
    /** Reroutes asked since the last progress. */
    int reroutes;
    /** Answer of a reroute, consumed by the next step (null: none waiting). EMPTY_ROUTE: no route. */
    volatile List<int[]> rerouteResult;
    /** Token of the reroute in flight: a late answer of an older request is ignored. */
    int rerouteToken;
    /** Sideways slide at a berth: OUT = from the berth centre to the pre-berth point, IN = from the pre-berth point to the berth centre. */
    enum Slide { NONE, OUT, IN }
    Slide slide = Slide.NONE;
    /** The waypoint list BoatAutopilot already adapted to the berths (identity): a new list is adapted again. */
    List<int[]> preparedFor;
    /** The waypoint list whose arrival end was already decided (trimmed or left as it is), by identity. */
    List<int[]> arrivalFor;
    /** The untrimmed list, kept while the arrival is trimmed to the pre-berth point (fallback when that last leg is blocked). */
    List<int[]> fullRoute;
    /** The sideways arrival was abandoned on this trip: no later list of the trip is trimmed again. */
    boolean sidewaysAbandoned;
    /** Next time the sections around the destination berth may be asked to load again. */
    long loadRetryMs;
    /** The route ends at the pre-berth point: once turned there, the shuttle slides in to the berth centre. */
    boolean slideIn;
    /** Look-ahead (BoatAutopilot): next scan time, detour offsets tried for the obstacle ahead, detours inserted on this leg. */
    long scanMs;
    /** Hold still until this time (an obstacle is near and a detour is being searched), checked on every tick. */
    long holdUntilMs;
    /** Detour columns inserted in the route (World column keys): reaching one does not reset the per-leg detour count. */
    final java.util.Set<Long> detourKeys = new java.util.HashSet<>();
    /** Detours inserted into the list since it was trimmed to the pre-berth point (shifts the resume index of the full route). */
    int detoursSinceTrim;
    /** Next time a missing pilot mannequin is tried again. */
    long lookRetryMs;
    int shiftTry;
    int shifts;
    /** Columns of the cells the hull hit or would hit: a one-off route avoids them. */
    final java.util.Set<Long> avoid = new java.util.HashSet<>();
    /** Last world cell that refused the hull ({x, y, z, wet}, from AirshipManager.firstBlockingCell), null when unknown. */
    volatile int[] lastHit;
    /** An early reroute was asked before contact: the shuttle holds still until the answer. */
    boolean avoidHold;
    /** The rescue was already called. */
    boolean rescued;
    volatile Listener listener;
    /** Final heading when {@link #to} is null (a voyage to a plain point); NaN: none. */
    double goalTheta = Double.NaN;

    /** Asks for a one-off route when {@link #to} is null (a steamboat voyage). */
    interface Router {
        void reroute(World world, AirshipFlight f, ShuttleState st, boolean stuck);
    }

    /** Null for a shuttle. */
    volatile Router router;
    /** Stations still to try, in order, when the one-off route to the current destination fails (station removed in flight). */
    final List<TransitNetwork.Station> fallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

    static final List<int[]> EMPTY_ROUTE = List.of();

    ShuttleState(TransitNetwork.Station from, TransitNetwork.Station to, List<int[]> waypoints, Phase phase, long now) {
        this.from = from;
        this.to = to;
        this.waypoints = new ArrayList<>(waypoints);
        this.phase = phase;
        this.phaseSinceMs = now;
    }

    void setPhase(Phase p, long now) {
        phase = p;
        phaseSinceMs = now;
    }
}
