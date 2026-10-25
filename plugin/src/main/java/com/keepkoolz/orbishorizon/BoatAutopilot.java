package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Pilotless sailing of the maritime shuttle (AirshipFlight.shuttle) along the waypoints of its water route. The waypoints are
 * followed by the ship's rotation centre (AirshipManager.centerWorld), which a turn keeps fixed, not by its pivot. Called by
 * AirshipManager.tickOne on every tick of a shuttle flight. SAIL: turn on the spot toward the next waypoint (yaw controller of the
 * player-piloted ship, AirshipAutopilot.yawStep), then sail straight to it at the height locked on the waterline, next waypoint
 * once within 3 blocks. ARRIVE (last waypoint = berth centre): stop with the centre exactly on the berth centre, turn around it to
 * the berth heading (the pivot then lands on the berth pivot), then DOCKED and {@link ShuttleState.Listener#onArrived}. Blocked hull (no progress for 5 s): a one-off route from the
 * current position is asked to BoatRoutes; no route, or still no progress for 30 s: {@link ShuttleState.Listener#onRescue}. LEAVE:
 * sailed like SAIL, {@link ShuttleState.Listener#onRouteEnd} at the last waypoint or after 5 s without progress. The other phases
 * (HOLD, DROP, APPROACH, DOCKED) are held still. Nothing is ever burnt: the wheels, the thrust models and the engine sound follow the movement.
 */
final class BoatAutopilot {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double MAX_DT = 0.25;
    /** An intermediate waypoint is reached within this distance (blocks). */
    static final double WAYPOINT_RADIUS = 3.0;
    /** The berth centre is reached within this distance (the last move snaps on it). */
    static final double BERTH_RADIUS = 0.05;
    /** The last blocks of the route are sailed slowly. */
    static final double SLOW_RANGE = 12.0;
    static final double MIN_SPEED = 0.5;
    /** Turning on the spot ends below this heading error, and starts again above REAIM_DEG while sailing. */
    private static final double TURN_DONE_DEG = 2.0;
    private static final double REAIM_DEG = 10.0;
    /** Near the waypoint the heading to it swings: no re-aim closer than this. */
    private static final double NO_REAIM_RANGE = 1.5;
    private static final long WINDOW_MS = 250;
    private static final double PROGRESS_MOVE = 0.2;
    /** No progress this long: a one-off route is asked from the current position. */
    static final long REROUTE_AFTER_MS = 5_000L;
    /** One-off routes asked per stuck episode, at most. */
    static final int MAX_REROUTES = 2;
    /** The sideways slide at a berth goes at this share of the sailing speed. */
    static final double SLIDE_SPEED_FACTOR = 0.5;
    /** The arrival manoeuvre is decided once the rest of the route is this long (blocks): the destination is then loaded. */
    static final double ARRIVAL_DECIDE_RANGE = 80.0;
    /** Sections around the destination berth are asked to load at most this often. */
    static final long LOAD_RETRY_MS = 1_000L;
    /** Look-ahead: hull poses tested ahead along the leg (rotation centre distances, blocks), and how far ahead at most. */
    static final double[] LOOK_STEPS = {8, 16, 24};
    static final double LOOK_RANGE = 26.0;
    /** The last waypoint is a berth or pre-berth point: poses this close to it are not tested (the berth manoeuvre owns that area). */
    static final double END_GUARD = 12.0;
    /** Sideways offsets (blocks) tried for a detour, each on both sides, nearest first. */
    static final double[] LATERALS = {3, 6, 9, 12};
    static final int MAX_SHIFT_TRIES = LATERALS.length * 2;
    /** Detours inserted on one leg, at most, before an early reroute is asked instead. */
    static final int MAX_SHIFTS = 6;
    static final long SCAN_MS = 250L;
    /** Detour candidates judged per scan (each costs a few hull tests). */
    static final int TRIES_PER_SCAN = 2;
    /** An obstacle nearer than this along the leg: the shuttle holds still while a detour is searched. */
    static final double IMMINENT = 10.0;
    static final long RESCUE_AFTER_MS = TransitConstants.RESCUE_NO_PROGRESS_SECONDS * 1000L;

    private BoatAutopilot() {
    }

    // ------------------------------------------------------------------ pure helpers (tested in TransitTests)

    /** Heading (radians) that points the bow at the centre of a waypoint column {x, z} (bow = (-sin theta, -cos theta)). */
    static double targetHeading(double px, double pz, int[] wp) {
        return AirshipAutopilot.headingTo(wp[0] + 0.5 - px, wp[1] + 0.5 - pz);
    }

    /** True when the point (the rotation centre) is within radius of the centre of the waypoint column {x, z}. */
    static boolean reached(double px, double pz, int[] wp, double radius) {
        return Math.hypot(wp[0] + 0.5 - px, wp[1] + 0.5 - pz) <= radius;
    }

    /** Sailing speed with this route length left: full speed, then slower over the last SLOW_RANGE blocks, never under MIN_SPEED. */
    static double sailSpeed(double distToEnd, double fullSpeed) {
        if (distToEnd >= SLOW_RANGE) {
            return fullSpeed;
        }
        return Math.min(fullSpeed, Math.max(MIN_SPEED, fullSpeed * distToEnd / SLOW_RANGE));
    }

    /** Sideways slide speed with this distance left to the target: half the sailing speed, slowed over the last blocks. */
    static double slideSpeed(double distToTarget, double fullSpeed) {
        return sailSpeed(distToTarget, fullSpeed * SLIDE_SPEED_FACTOR);
    }

    /** Absolute heading change from a to b (radians, 0 to pi), without a 2 pi jump across +-pi. */
    static double headingChange(double a, double b) {
        return Math.abs(AirshipMath.wrapPi(b - a));
    }

    /** Whether the hull, with its rotation centre at (cx, cz) and this heading, touches something. */
    interface PoseTest {
        boolean blocked(double cx, double cz, double theta);
    }

    /** Heading steps (radians) sampled along a turn on the spot, ends included. */
    static final double TURN_SAMPLE = Math.toRadians(12);

    /** Whether the hull touches something at any sampled heading of a turn on the spot from one heading to another at (x, z). */
    static boolean turnBlocked(PoseTest t, double x, double z, double from, double to) {
        double delta = AirshipMath.wrapPi(to - from);
        int n = Math.max(1, (int) Math.ceil(Math.abs(delta) / TURN_SAMPLE));
        for (int i = 0; i <= n; i++) {
            if (t.blocked(x, z, from + delta * i / n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks ahead along the leg to the waypoint wp (x, z column; next is the one after it, null when wp is the last). Tests the hull at
     * LOOK_STEPS along the leg with the leg's heading, at the end of the look range, and at the waypoint itself with the heading of the
     * next leg (the ship turns there). Returns {distance along the leg, centre x, centre z, heading} of the first blocked pose, or null.
     * Near the last waypoint (a berth or pre-berth point, where the berth manoeuvre takes over) the poses closer than END_GUARD are left out.
     */
    static double[] blockedAhead(PoseTest t, double cx, double cz, int[] wp, int[] next) {
        double wx = wp[0] + 0.5;
        double wz = wp[1] + 0.5;
        double dx = wx - cx;
        double dz = wz - cz;
        double dist = Math.hypot(dx, dz);
        if (dist < 1.0) {
            return null;
        }
        double th = AirshipAutopilot.headingTo(dx, dz);
        double reach = Math.min(next == null ? dist - END_GUARD : dist, LOOK_RANGE);
        double lastK = 0;
        for (double k : LOOK_STEPS) {
            if (k > reach) {
                break;
            }
            if (t.blocked(cx + dx / dist * k, cz + dz / dist * k, th)) {
                return new double[] {k, cx + dx / dist * k, cz + dz / dist * k, th};
            }
            lastK = k;
        }
        if (reach - lastK >= 3.0 && reach >= 3.0) {
            if (t.blocked(cx + dx / dist * reach, cz + dz / dist * reach, th)) {
                return new double[] {reach, cx + dx / dist * reach, cz + dz / dist * reach, th};
            }
        }
        if (next != null && dist <= LOOK_RANGE) {
            double th2 = AirshipAutopilot.headingTo(next[0] + 0.5 - wx, next[1] + 0.5 - wz);
            if (turnBlocked(t, wx, wz, th, th2)) {
                return new double[] {dist, wx, wz, th2};
            }
        }
        return null;
    }

    /**
     * Detour candidate number n (0 = 3 blocks to the left, 1 = 3 to the right, 2 = 6 left...): a point shifted sideways from the leg,
     * beyond the obstacle (kBlocked + 18 blocks along the leg, at least 6 before the waypoint), snapped to a column centre. The poses of
     * the turn here toward it (from theta0, sampled), the leg to it (middle and end, its own heading), the turn there (sampled) and the
     * first 16 blocks after it (heading to wp) must be free. Returns {x, z} of the detour column centre, or null.
     */
    static double[] detour(PoseTest t, double cx, double cz, double theta0, int[] wp, double kBlocked, int n) {
        if (n < 0 || n >= MAX_SHIFT_TRIES) {
            return null;
        }
        double wx = wp[0] + 0.5;
        double wz = wp[1] + 0.5;
        double dist = Math.hypot(wx - cx, wz - cz);
        double forward = Math.min(kBlocked + 18.0, dist - 6.0);
        if (forward < 6.0) {
            return null;
        }
        double ux = (wx - cx) / dist;
        double uz = (wz - cz) / dist;
        double side = n % 2 == 0 ? 1 : -1;
        double lateral = LATERALS[n / 2] * side;
        double px = Math.floor(cx + ux * forward - uz * lateral) + 0.5;
        double pz = Math.floor(cz + uz * forward + ux * lateral) + 0.5;
        double h1 = AirshipAutopilot.headingTo(px - cx, pz - cz);
        double h2 = AirshipAutopilot.headingTo(wx - px, wz - pz);
        if (turnBlocked(t, cx, cz, theta0, h1) || t.blocked(cx + (px - cx) * 0.5, cz + (pz - cz) * 0.5, h1)
                || turnBlocked(t, px, pz, h1, h2)) {
            return null;
        }
        double rest = Math.hypot(wx - px, wz - pz);
        for (double k : new double[] {8, 16}) {
            if (k < rest && t.blocked(px + (wx - px) / rest * k, pz + (wz - pz) / rest * k, h2)) {
                return null;
            }
        }
        return new double[] {px, pz};
    }

    /** Look {Model, skin} of the station master NPC of a station (helm mannequin of the shuttle), or null. Never throws. */
    static Object[] masterLook(Store<EntityStore> store, World world, TransitNetwork.Station station) {
        return readLook(store, world, station, true);
    }

    /** Look of the station master: read now, else the one remembered for this station (never another station's). Logs why when asked. */
    private static Object[] readLook(Store<EntityStore> store, World world, TransitNetwork.Station station, boolean log) {
        try {
            UUID id = station != null ? station.masterUuid : null;
            Object[] look = null;
            String why = "chef de gare sans identifiant";
            if (id != null) {
                Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(id);
                look = AirshipCrew.lookOf(store, ref);
                why = ref == null || !ref.isValid() ? "chef de gare absent du monde (section inactive ou non chargée)"
                        : "chef de gare sans modèle ou sans apparence";
            }
            if (look != null) {
                LOOKS.put(station, look);
                return look;
            }
            Object[] kept = station != null ? LOOKS.get(station) : null;
            if (log) {
                LOGGER.at(Level.INFO).log("Navette : apparence du pilote lue sur le chef de %s impossible (%s), %s",
                        station != null ? station.name : "?", why, kept != null ? "apparence mémorisée utilisée" : "aucune apparence mémorisée");
            }
            return kept;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : apparence du chef de gare illisible");
            return null;
        }
    }

    /** Last look read on each station master: a shuttle called while the master cannot be read still gets its pilot. */
    private static final java.util.Map<TransitNetwork.Station, Object[]> LOOKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOOK_RETRY_MS = 10_000L;

    // ------------------------------------------------------------------ every tick

    /** One tick of a shuttle flight. Never throws. */
    static void step(Store<EntityStore> store, World world, AirshipFlight f, double dt, long now) {
        step(store, world, f, f.shuttle, dt, now);
    }

    /** One tick of a sailing flight driven by this state (the public shuttle's, or a steamboat voyage's). Never throws. */
    static void step(Store<EntityStore> store, World world, AirshipFlight f, ShuttleState st, double dt, long now) {
        if (st == null || f.landing || f.finishing) {
            return;
        }
        try {
            AirshipManager am = AirshipManager.get();
            AirshipManager.Hull hull = am.hullOf(f.kind, f.kind.shape());
            double d = Math.min(Math.max(dt, 0), MAX_DT);
            if (st.windowMs == 0) {
                st.windowMs = now;
                st.windowX = f.pos.x;
                st.windowZ = f.pos.z;
                st.windowTheta = f.theta;
                markProgress(f, st, now);
            }
            takeReroute(store, f, st, now);
            if ((st.from != null || st.to != null) && now >= st.lookRetryMs && (f.crewMan == null || f.crewMan.ref == null || !f.crewMan.ref.isValid())) {
                st.lookRetryMs = now + LOOK_RETRY_MS; // the shuttle has no (valid) pilot mannequin: try again
                if (f.crewMan != null) {
                    LOGGER.at(Level.WARNING).log("Navette %s : mannequin du pilote invalide (entité perdue), recréation", f.id);
                    AirshipCrew.removeMannequin(store, f.crewMan);
                    f.crewMan = null;
                }
                am.attachShuttleMannequin(store, f, readLook(store, world, st.from != null ? st.from : st.to, false), now);
            }
            if (f.finishing) {
                return;
            }
            boolean sailing = st.phase == ShuttleState.Phase.SAIL || st.phase == ShuttleState.Phase.ARRIVE
                    || st.phase == ShuttleState.Phase.LEAVE;
            if (sailing) {
                sail(store, world, am, hull, f, st, d, now);
            } else {
                f.yawRate = 0; // the other phases are driven by the boat station logic: the shuttle holds still
                f.yawAcc = 0;
            }
            if (f.finishing) {
                return;
            }
            window(am, f, st, now);
            am.updateThrust(store, f, now);
            ShuttleState.Phase after = st.phase; // the arrival callback may have moved it on (DROP): no watchdog then
            if (sailing && (after == ShuttleState.Phase.SAIL || after == ShuttleState.Phase.ARRIVE
                    || after == ShuttleState.Phase.LEAVE)) {
                watchdog(store, world, f, st, now);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : erreur dans le tick de %s", f.id);
            Telemetry.error("transit", e, Telemetry.kindId(f.kind), st.phase.name());
        }
    }

    private static void sail(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                             ShuttleState st, double dt, long now) {
        List<int[]> wps = st.waypoints;
        if (wps.isEmpty()) {
            LOGGER.at(Level.WARNING).log("Navette %s : aucun point de passage", f.id);
            rescue(store, f, st);
            return;
        }
        Vector3d c = am.centerWorld(f.kind, f.pos, f.theta); // the route is followed by the rotation centre
        if (st.preparedFor != wps) {
            prepare(world, am, hull, f, st, c);
            wps = st.waypoints;
        }
        if (st.slide != ShuttleState.Slide.NONE) {
            slide(store, world, am, hull, f, st, c, dt, now);
            return;
        }
        if (st.rerouting && st.avoidHold) {
            f.yawRate = 0; // an early reroute is on its way: hold still rather than sail into the obstacle it avoids
            f.yawAcc = 0;
            return;
        }
        if (st.arrivalFor != st.waypoints) {
            decideArrival(world, am, hull, f, st, c, now);
            wps = st.waypoints;
        }
        int last = wps.size() - 1;
        while (st.index < last && reached(c.x, c.z, wps.get(st.index), WAYPOINT_RADIUS)) {
            if (!st.detourKeys.contains(WaterRoute.columnKey(wps.get(st.index)[0], wps.get(st.index)[1]))) {
                st.shifts = 0; // a detour point does not give the next obstacle a fresh budget
            }
            st.index++;
            st.shiftTry = 0;
            st.turning = true; // aim at the next waypoint before sailing on
        }
        if (st.index >= last && st.phase == ShuttleState.Phase.SAIL) {
            st.setPhase(ShuttleState.Phase.ARRIVE, now);
        }
        if (st.phase == ShuttleState.Phase.LEAVE && st.index >= last && reached(c.x, c.z, wps.get(last), WAYPOINT_RADIUS)) {
            routeEnd(store, f, st); // end of the leaving leg: the shuttle vanishes
            return;
        }
        int[] wp = wps.get(Math.min(st.index, last));
        double tx = wp[0] + 0.5;
        double tz = wp[1] + 0.5;
        double dx = tx - c.x;
        double dz = tz - c.z;
        double dist = Math.hypot(dx, dz);
        if (st.phase == ShuttleState.Phase.ARRIVE && dist <= BERTH_RADIUS) {
            berth(store, world, am, hull, f, st, dt, now);
            return;
        }
        double err = AirshipMath.wrapPi(targetHeading(c.x, c.z, wp) - f.theta);
        if (st.turning) {
            if (Math.abs(err) < Math.toRadians(TURN_DONE_DEG) || dist < NO_REAIM_RANGE) {
                st.turning = false;
                f.yawRate = 0;
                f.yawAcc = 0;
            } else {
                AirshipAutopilot.yawStep(world, am, hull, f, err, dt, now);
                return;
            }
        } else if (dist >= NO_REAIM_RANGE && Math.abs(err) > Math.toRadians(REAIM_DEG)) {
            st.turning = true;
            return;
        }
        f.yawRate = 0;
        f.yawAcc = 0;
        if (lookAhead(world, am, hull, f, st, c, now)) {
            return; // an obstacle is near: hold still this tick (detour inserted, or searched next tick)
        }
        double remaining = dist;
        for (int i = Math.min(st.index, last); i < last; i++) {
            int[] a = wps.get(i);
            int[] b = wps.get(i + 1);
            remaining += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        double speed = sailSpeed(remaining, BalloonManager.defaultHorizontalSpeed(world, store));
        double move = Math.min(speed * dt, dist);
        double y = Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY; // never climb: the hull stays on the waterline
        Vector3d next = am.pivotFromCenter(f.kind, new Vector3d(c.x + dx / dist * move, y, c.z + dz / dist * move), f.theta);
        am.move(world, f, hull, next, f.theta, now);
    }

    /**
     * Scans the leg ahead (at most every SCAN_MS) and shifts sideways before contact: a detour point is inserted before the current
     * waypoint. Runs on every leg, the last one included (poses near the last point are left out, see END_GUARD), but not while turning,
     * sliding or rerouting: the berth manoeuvre is never shifted. No free offset (or too many detours): an early reroute that avoids
     * the cells in the way. Returns true when the shuttle must hold still this tick (checked on every tick). Never throws.
     */
    private static boolean lookAhead(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, ShuttleState st,
                                     Vector3d c, long now) {
        try {
            if (st.rerouting) {
                return false;
            }
            if (now < st.scanMs) {
                return now < st.holdUntilMs; // an obstacle is near and the detour is still searched: hold on every tick
            }
            st.scanMs = now + SCAN_MS;
            st.holdUntilMs = 0;
            List<int[]> wps = st.waypoints;
            int last = wps.size() - 1;
            double y = Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY;
            int allowed = f.blockedNow;
            PoseTest test = (x, z, th) -> {
                Vector3d pv = am.pivotFromCenter(f.kind, new Vector3d(x, y, z), th);
                return AirshipManager.blocked(world, hull, pv.x, pv.y, pv.z, th, allowed) > allowed;
            };
            int[] wp = wps.get(Math.min(st.index, last));
            double[] hit = blockedAhead(test, c.x, c.z, wp, st.index < last ? wps.get(st.index + 1) : null);
            if (hit == null) {
                st.shiftTry = 0;
                return false;
            }
            int tries = 0;
            while (tries++ < TRIES_PER_SCAN && st.shiftTry < MAX_SHIFT_TRIES && st.shifts < MAX_SHIFTS) {
                int n = st.shiftTry++;
                double[] d = detour(test, c.x, c.z, f.theta, wp, hit[0], n);
                if (d != null) {
                    int[] col = {(int) Math.floor(d[0]), (int) Math.floor(d[1])};
                    List<int[]> nl = new java.util.ArrayList<>(wps);
                    nl.add(Math.min(st.index, last), col);
                    st.waypoints = nl;
                    st.preparedFor = nl;
                    if (st.arrivalFor == wps) {
                        st.arrivalFor = nl;
                    }
                    st.detourKeys.add(WaterRoute.columnKey(col[0], col[1]));
                    if (st.fullRoute != null) {
                        st.detoursSinceTrim++;
                    }
                    st.shifts++;
                    st.shiftTry = 0;
                    st.turning = true;
                    LOGGER.at(Level.INFO).log("Navette %s : obstacle à %.0f blocs, déport de %.0f blocs vers (%d, %d)", f.id, hit[0],
                            LATERALS[n / 2], col[0], col[1]);
                    return true;
                }
            }
            if (st.shiftTry >= MAX_SHIFT_TRIES || st.shifts >= MAX_SHIFTS) {
                return earlyReroute(world, am, hull, f, st, hit);
            }
            if (hit[0] <= IMMINENT) {
                st.holdUntilMs = st.scanMs;
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette %s : anticipation d'obstacle en échec", f.id);
            return false;
        }
    }

    /** No detour is free: asks a one-off route that avoids the blocking cell, before any contact. */
    private static boolean earlyReroute(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, ShuttleState st,
                                     double[] hit) {
        if (st.reroutes >= MAX_REROUTES || (st.to == null && st.router == null)) {
            st.shiftTry = 0; // enough tries: the shuttle goes on and the watchdog takes over on contact
            return false;
        }
        double y = Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY;
        Vector3d pv = am.pivotFromCenter(f.kind, new Vector3d(hit[1], y, hit[2]), hit[3]);
        int[] cell = AirshipManager.firstBlockingCell(world, hull, pv.x, pv.y, pv.z, hit[3], f.blockedNow);
        if (cell != null) {
            st.avoid.add(WaterRoute.columnKey(cell[0], cell[2]));
        }
        st.reroutes++;
        st.avoidHold = true;
        st.shiftTry = 0;
        st.shifts = 0;
        LOGGER.at(Level.INFO).log("Navette %s : aucun déport libre, nouvelle route anticipée (%s)", f.id, AirshipManager.describeCell(world, cell));
        reroute(world, f, st, false);
        return true;
    }

    /**
     * Adapts a new waypoint list to the berths, once per list. Leaving a berth (the centre is on the berth centre): the route starts at
     * the pre-berth point and the shuttle first slides out to it, heading fixed. Arriving (the route ends at the berth centre of the
     * destination): the route ends at the pre-berth point, where the shuttle turns to the berth heading before sliding in. The
     * pre-berth point must be open water for the hull at the berth heading, else the route is left as it is (turns at the berth).
     */
    private static void prepare(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, ShuttleState st, Vector3d c) {
        List<int[]> wps = st.waypoints;
        st.preparedFor = wps;
        st.slide = ShuttleState.Slide.NONE; // a new list starts a new manoeuvre
        st.slideIn = false;
        st.fullRoute = null;
        if (wps.isEmpty()) {
            return;
        }
        ShuttleState.Phase ph = st.phase;
        TransitNetwork.Station dep = ph == ShuttleState.Phase.LEAVE ? st.to : st.from;
        if (st.index == 0 && dep != null && (ph == ShuttleState.Phase.SAIL || ph == ShuttleState.Phase.LEAVE)) {
            Vector3d b = BoatRoutes.berthCentre(dep);
            if (Math.hypot(c.x - b.x, c.z - b.z) < 1.0) {
                Vector3i pre = BoatRoutes.preBerthCell(dep);
                if (openWater(world, am, hull, f, pre)) {
                    List<int[]> out = BoatRoutes.departRoute(wps, BoatRoutes.berthCentreCell(dep), pre, BoatRoutes.BERTH_STANDOFF);
                    if (out.size() < 2) {
                        return; // nothing to sail beyond the pre-berth point: no manoeuvre
                    }
                    st.waypoints = out;
                    st.preparedFor = out;
                    st.slide = ShuttleState.Slide.OUT;
                    wps = out;
                } else {
                    LOGGER.at(Level.INFO).log("Navette %s : point de pré-amarrage de %s non dégagé, départ sans glissade", f.id, dep.name);
                }
            }
        }
    }

    /**
     * Decides the arrival end of the waypoint list, once per list, late: when the rest of the route is under ARRIVAL_DECIDE_RANGE and the
     * sections around the pre-berth point are loaded (they are far away and unloaded at departure). The route is then trimmed to end at the
     * pre-berth point and the shuttle slides in after turning there. Left as it is (turns at the berth, old behaviour) when the hull does not
     * have the room at the pre-berth point, or when the route end is not the berth centre.
     */
    private static void decideArrival(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, ShuttleState st,
                                      Vector3d c, long now) {
        List<int[]> wps = st.waypoints;
        TransitNetwork.Station arr = st.to;
        ShuttleState.Phase ph = st.phase;
        if (wps.isEmpty() || arr == null || st.sidewaysAbandoned || (ph != ShuttleState.Phase.SAIL && ph != ShuttleState.Phase.ARRIVE)) {
            st.arrivalFor = wps;
            return;
        }
        Vector3i berth = BoatRoutes.berthCentreCell(arr);
        int[] end = wps.get(wps.size() - 1);
        if (end[0] != berth.x || end[1] != berth.z) {
            st.arrivalFor = wps;
            LOGGER.at(Level.INFO).log("Navette %s : la route de %s ne finit pas au centre d'amarrage, arrivée sans manœuvre par le travers",
                    f.id, arr.name);
            return;
        }
        if (BoatQuay.remaining(wps, st.index, c.x, c.z) > ARRIVAL_DECIDE_RANGE) {
            return; // too far: decided later, once the destination is near (and loaded)
        }
        Vector3i pre = BoatRoutes.preBerthCell(arr);
        int minX = Math.min(berth.x, pre.x) - 20;
        int maxX = Math.max(berth.x, pre.x) + 20;
        int minZ = Math.min(berth.z, pre.z) - 20;
        int maxZ = Math.max(berth.z, pre.z) + 20;
        int y = (int) Math.floor(Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY);
        ChunkStore cs = world.getChunkStore();
        if (!SectionBlocks.sectionsLoaded(cs, minX, y - 6, minZ, maxX, y + 12, maxZ)) {
            if (now >= st.loadRetryMs) {
                st.loadRetryMs = now + LOAD_RETRY_MS;
                SectionBlocks.loadSections(cs, minX, y - 6, minZ, maxX, y + 12, maxZ); // checked again next tick
            }
            return;
        }
        st.arrivalFor = wps;
        double dBerth = Math.hypot(c.x - (berth.x + 0.5), c.z - (berth.z + 0.5));
        if (dBerth < BoatRoutes.BERTH_STANDOFF) {
            LOGGER.at(Level.INFO).log("Navette %s : déjà à moins de %d blocs du pont de %s, arrivée sans manœuvre par le travers", f.id,
                    BoatRoutes.BERTH_STANDOFF, arr.name);
        } else if (openWater(world, am, hull, f, pre, BoatRoutes.berthTheta(arr))) {
            List<int[]> out = BoatRoutes.arriveRoute(wps, berth, pre, BoatRoutes.BERTH_STANDOFF);
            st.fullRoute = wps;
            st.waypoints = out;
            st.arrivalFor = out;
            st.preparedFor = out;
            st.index = Math.min(st.index, out.size() - 1);
            st.slideIn = true;
            st.detoursSinceTrim = 0;
        } else {
            LOGGER.at(Level.INFO).log("Navette %s : point de pré-amarrage de %s non dégagé, arrivée sans manœuvre par le travers", f.id,
                    arr.name);
        }
    }

    /** The trimmed last leg is blocked: back to the untrimmed route (turn at the berth, old behaviour), once. */
    private static boolean abandonSideways(AirshipFlight f, ShuttleState st, long now) {
        if (!st.slideIn || st.fullRoute == null || st.slide != ShuttleState.Slide.NONE
                || st.index < st.waypoints.size() - 1) {
            return false; // only the last leg (to the pre-berth point) and the turn there fall back, never a stall far out
        }
        List<int[]> full = st.fullRoute;
        List<int[]> trimmed = st.waypoints;
        st.fullRoute = null;
        st.sidewaysAbandoned = true; // once per trip: a later reroute is not trimmed again
        st.slideIn = false;
        st.waypoints = full;
        st.preparedFor = full;
        st.arrivalFor = full; // not trimmed again
        st.index = Math.max(0, BoatRoutes.firstDropped(full, trimmed) - st.detoursSinceTrim); // detours inserted after the trim are not in full
        st.detoursSinceTrim = 0;
        st.turning = true;
        markProgress(f, st, now);
        LOGGER.at(Level.INFO).log("Navette %s : dernier tronçon vers le pré-amarrage bloqué, arrivée sans manœuvre par le travers", f.id);
        return true;
    }

    private static boolean openWater(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, Vector3i preCell) {
        return openWater(world, am, hull, f, preCell, f.theta);
    }

    /** True when the hull at this heading, with its rotation centre on the pre-berth column, touches nothing. */
    private static boolean openWater(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, Vector3i preCell,
                                     double theta) {
        double y = Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY;
        Vector3d pivot = am.pivotFromCenter(f.kind, new Vector3d(preCell.x + 0.5, y, preCell.z + 0.5), theta);
        return AirshipManager.blocked(world, hull, pivot.x, pivot.y, pivot.z, theta, 0) == 0;
    }

    /**
     * One tick of a sideways slide, heading fixed: OUT toward the pre-berth point (then the shuttle aims at its first waypoint), IN toward
     * the berth centre (then docked).
     */
    private static void slide(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                              ShuttleState st, Vector3d c, double dt, long now) {
        f.yawRate = 0;
        f.yawAcc = 0;
        boolean out = st.slide == ShuttleState.Slide.OUT;
        double tx;
        double tz;
        if (out) {
            int[] pre = st.waypoints.get(0);
            tx = pre[0] + 0.5;
            tz = pre[1] + 0.5;
        } else {
            Vector3d b = BoatRoutes.berthCentre(st.to);
            tx = b.x;
            tz = b.z;
        }
        double dx = tx - c.x;
        double dz = tz - c.z;
        double dist = Math.hypot(dx, dz);
        if (dist <= BERTH_RADIUS) {
            st.slide = ShuttleState.Slide.NONE;
            if (out) {
                st.turning = true; // aim at the first waypoint of the route
            } else {
                st.slideIn = false;
                dock(f, st, now);
            }
            return;
        }
        double speed = slideSpeed(dist, BalloonManager.defaultHorizontalSpeed(world, store));
        double move = Math.min(speed * dt, dist);
        double y = Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY;
        Vector3d next = am.pivotFromCenter(f.kind, new Vector3d(c.x + dx / dist * move, y, c.z + dz / dist * move), f.theta);
        am.move(world, f, hull, next, f.theta, now);
    }

    /** Centre on the berth centre (or on the pre-berth point): turn around it to the berth heading, slide in if needed, then docked. */
    private static void berth(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                              ShuttleState st, double dt, long now) {
        double goal = st.to != null ? BoatRoutes.berthTheta(st.to) : st.goalTheta;
        double err = Double.isNaN(goal) ? 0 : AirshipMath.wrapPi(goal - f.theta);
        if (Math.abs(err) > 1e-4) {
            AirshipAutopilot.yawStep(world, am, hull, f, err, dt, now); // the centre stays where it is
            return;
        }
        f.yawRate = 0;
        f.yawAcc = 0;
        if (st.slideIn) {
            st.slide = ShuttleState.Slide.IN; // the next tick slides sideways to the berth centre
            return;
        }
        dock(f, st, now);
    }

    private static void dock(AirshipFlight f, ShuttleState st, long now) {
        f.yawRate = 0;
        f.yawAcc = 0;
        st.setPhase(ShuttleState.Phase.DOCKED, now);
        LOGGER.at(Level.INFO).log("Navette : arrivée%s", st.to != null ? " à " + st.to.name : "");
        ShuttleState.Listener l = st.listener;
        if (l != null) {
            try {
                l.onArrived(f);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Navette : traitement de l'arrivée en échec");
                Telemetry.error("transit", e, Telemetry.kindId(f.kind), st.phase.name());
            }
        }
    }

    /** Travel window: speed, moving and turn of the last 250 ms, for the thrust models and the wheels. Nothing is burnt. */
    private static void window(AirshipManager am, AirshipFlight f, ShuttleState st, long now) {
        if (now - st.windowMs < WINDOW_MS) {
            return;
        }
        double seconds = (now - st.windowMs) / 1000.0;
        f.travelSpeed = Math.hypot(f.pos.x - st.windowX, f.pos.z - st.windowZ) / seconds;
        f.windowTurn = headingChange(st.windowTheta, f.theta);
        st.windowMs = now;
        st.windowX = f.pos.x;
        st.windowZ = f.pos.z;
        st.windowTheta = f.theta;
        f.moving = f.travelSpeed > am.fuelMoveMin && st.slide == ShuttleState.Slide.NONE; // the wheels of a slide are set by PaddleWheels
        f.burning = f.moving;
        f.reversing = false; // the shuttle always sails bow first
    }

    private static void markProgress(AirshipFlight f, ShuttleState st, long now) {
        st.progressX = f.pos.x;
        st.progressZ = f.pos.z;
        st.progressTheta = f.theta;
        st.lastProgressMs = now;
    }

    /** Blocked hull: a one-off route after 5 s without progress (twice at most), the rescue after 30 s. */
    private static void watchdog(Store<EntityStore> store, World world, AirshipFlight f, ShuttleState st, long now) {
        double moved = Math.abs(f.pos.x - st.progressX) + Math.abs(f.pos.z - st.progressZ);
        if (moved > PROGRESS_MOVE || headingChange(st.progressTheta, f.theta) > Math.toRadians(1)) {
            markProgress(f, st, now);
            st.reroutes = 0;
            return;
        }
        long stuck = now - st.lastProgressMs;
        if (stuck >= REROUTE_AFTER_MS / 2 && abandonSideways(f, st, now)) {
            return;
        }
        if (st.phase == ShuttleState.Phase.LEAVE && stuck >= REROUTE_AFTER_MS) {
            routeEnd(store, f, st); // nobody aboard: a blocked leaving shuttle just vanishes where it is
            return;
        }
        if (stuck >= RESCUE_AFTER_MS) {
            LOGGER.at(Level.INFO).log("Navette %s : aucun progrès depuis %d s", f.id, stuck / 1000);
            rescue(store, f, st);
            return;
        }
        if (stuck >= REROUTE_AFTER_MS && !st.rerouting && st.reroutes < MAX_REROUTES
                && (st.to != null || st.router != null)) {
            st.reroutes++;
            reroute(world, f, st, true);
        }
    }

    /**
     * Asks for a one-off route from the shuttle's position to the berth of this station and makes it the shuttle's destination: the
     * answer is applied by the next step (new waypoints, or the rescue when there is none). A request in flight is superseded.
     */
    static void rerouteTo(World world, AirshipFlight f, ShuttleState st, TransitNetwork.Station target) {
        rerouteTo(world, f, st, target, false);
    }

    /** One-off route for this state: its router (voyage to a plain point), else toward the station it sails to. */
    static void reroute(World world, AirshipFlight f, ShuttleState st, boolean stuck) {
        if (st.router == null && st.to == null) {
            LOGGER.at(Level.INFO).log("Navette %s : nouvelle route impossible, ni station ni routeur", f.id);
            return;
        }
        ShuttleState.Router r = st.router;
        if (r != null) {
            r.reroute(world, f, st, stuck);
        } else {
            rerouteTo(world, f, st, st.to, stuck);
        }
    }

    /** Same; stuck is true when the hull is blocked: the route then starts free of the clearance taper and avoids the cell that hit. */
    static void rerouteTo(World world, AirshipFlight f, ShuttleState st, TransitNetwork.Station target, boolean stuck) {
        int[] hitCell = st.lastHit;
        if (stuck && hitCell != null) {
            st.avoid.add(WaterRoute.columnKey(hitCell[0], hitCell[2]));
        }
        st.to = target;
        st.rerouting = true;
        int token = ++st.rerouteToken;
        LOGGER.at(Level.INFO).log("Navette %s en (%.1f, %.1f) : nouvelle route vers %s demandée", f.id, f.pos.x, f.pos.z, target.name);
        Vector3d c = AirshipManager.get().centerWorld(f.kind, f.pos, f.theta);
        AirshipManager am = AirshipManager.get();
        BoatRoutes.requestReroute(world, c.x, c.z, target, () -> am.isActive(f) && f.sailState() == st && !st.rescued
                && token == st.rerouteToken, pts -> {
            if (token == st.rerouteToken) {
                st.rerouteResult = pts != null && !pts.isEmpty() ? pts : ShuttleState.EMPTY_ROUTE;
            }
        }, st.avoid, stuck);
    }

    /** Applies the answer of a one-off route: the new waypoints, or the rescue when there is none. */
    private static void takeReroute(Store<EntityStore> store, AirshipFlight f, ShuttleState st, long now) {
        List<int[]> r = st.rerouteResult;
        if (r == null) {
            return;
        }
        st.rerouteResult = null;
        st.rerouting = false;
        st.avoidHold = false;
        st.shifts = 0;
        st.shiftTry = 0;
        if (st.phase == ShuttleState.Phase.DOCKED || st.phase == ShuttleState.Phase.DROP || st.phase == ShuttleState.Phase.LEAVE) {
            return; // late answer: the shuttle reached its berth meanwhile
        }
        if (r == ShuttleState.EMPTY_ROUTE) {
            LOGGER.at(Level.INFO).log("Navette %s : aucune route depuis sa position", f.id);
            while (!st.fallbacks.isEmpty()) {
                TransitNetwork.Station next = st.fallbacks.remove(0);
                if (TransitNetwork.get().byName(f.world.getName(), next.name) != next) {
                    continue; // removed meanwhile
                }
                ShuttleState.Listener l = st.listener;
                if (l != null) {
                    l.onRetarget(f, next);
                }
                rerouteTo(f.world, f, st, next, true);
                return;
            }
            rescue(store, f, st);
            return;
        }
        st.fallbacks.clear();
        st.detourKeys.clear();
        st.detoursSinceTrim = 0;
        st.waypoints = new java.util.ArrayList<>(r);
        st.index = 0;
        st.turning = true;
        st.setPhase(ShuttleState.Phase.SAIL, now);
        markProgress(f, st, now); // the new route gets its full 30 s
        LOGGER.at(Level.INFO).log("Navette %s : nouvelle route de %d point(s)", f.id, r.size());
    }

    /**
     * Sets a new phase after the boat station logic held the shuttle (HOLD, DROP): it aims again at its waypoint and the watchdog
     * starts from now (the time spent holding is not counted as being stuck).
     */
    static void resume(AirshipFlight f, ShuttleState st, ShuttleState.Phase phase, long now) {
        st.setPhase(phase, now);
        st.turning = true;
        markProgress(f, st, now);
    }

    /** End of a LEAVE leg: the listener ends the flight, else it is ended here. Once per flight. Never throws. */
    private static void routeEnd(Store<EntityStore> store, AirshipFlight f, ShuttleState st) {
        if (st.rescued) {
            return;
        }
        st.rescued = true; // no rescue after the end of the route
        ShuttleState.Listener l = st.listener;
        try {
            if (l != null) {
                l.onRouteEnd(f);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : fin de route en échec, retrait du vol");
            Telemetry.error("transit", e, Telemetry.kindId(f.kind), st.phase.name());
        }
        AirshipManager am = AirshipManager.get();
        if (am.isActive(f)) {
            if (endAction(f.shuttle == st) == EndAction.END_SHUTTLE) {
                am.endShuttle(store, f, "fin de route");
            } else if (!f.landing && !am.landImmediately(f)) {
                // A landing the listener started is left to its retries; the voyage step keeps trying otherwise.
                LOGGER.at(Level.INFO).log("Voyage %s : fin de route, pose sur place impossible pour l'instant", f.id);
            }
        }
    }

    /**
     * The shuttle cannot go on: the listener's rescue (the boat station logic), else the flight is ended without blocks (riders
     * released on the surface, entities removed). Once per flight. Never throws.
     */
    static void rescue(Store<EntityStore> store, AirshipFlight f) {
        rescue(store, f, f.shuttle);
    }

    /** What ends a flight the listener left active: the public shuttle vanishes, a voyage lands where it is. */
    enum EndAction { END_SHUTTLE, LAND_IN_PLACE }

    static EndAction endAction(boolean publicShuttle) {
        return publicShuttle ? EndAction.END_SHUTTLE : EndAction.LAND_IN_PLACE;
    }

    static void rescue(Store<EntityStore> store, AirshipFlight f, ShuttleState st) {
        if (st == null || st.rescued) {
            return;
        }
        st.rescued = true;
        ShuttleState.Listener l = st.listener;
        try {
            if (l != null) {
                l.onRescue(f);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : secours en échec, retrait du vol");
            Telemetry.error("transit", e, Telemetry.kindId(f.kind), st.phase.name());
        }
        AirshipManager am = AirshipManager.get();
        if (am.isActive(f)) {
            // No listener, or it did not end the flight: the flight never stays registered after a rescue.
            if (endAction(f.shuttle == st) == EndAction.END_SHUTTLE) {
                LOGGER.at(Level.INFO).log("Navette %s : secours, vol retiré", f.id);
                am.endShuttle(store, f, "secours");
            } else if (f.voyage != null && f.voyage.backoff != null) {
                // A voyage backing off: the listener sent it to its next back-off point (or it keeps trying where it is), no landing here.
                LOGGER.at(Level.INFO).log("Voyage %s : secours pendant le repli, pas de pose sur place", f.id);
            } else if (!f.landing && !am.landImmediately(f)) {
                // A landing the listener started (AirshipAutopilot.stop) is left to its retries; the voyage step keeps trying otherwise.
                LOGGER.at(Level.INFO).log("Voyage %s : secours, pose sur place impossible pour l'instant", f.id);
            }
        }
    }
}
