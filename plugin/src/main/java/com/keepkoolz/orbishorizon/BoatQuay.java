package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Destination queue and quay cycle of the boat stations (maritime shuttle). One Quay per boat station, in memory only (the queue
 * itself is saved with the station in stations.json, with the balloon stations' rules: StationQueue.addTo).
 *
 * Cycle: FREE, then (queue not empty, route to the head FOUND) APPROACH while a shuttle sails in from about 40 blocks out on the
 * route; at the berth it is put down in blocks (DOCKED) and the 30 s countdown runs while players step aboard from the pier;
 * at 0 the players aboard are seated, the blocks removed and the shuttle sails to the destination (DEPARTING until it is 40 blocks
 * from the berth, then a 10 s gap). At the destination the arriving shuttle takes the quay (ARRIVING) or waits out at sea (HOLD,
 * spaced by rank) while it is busy; arrivals have priority over new approaches. It stops at the berth, sets its passengers down on
 * the pier, waits 5 s, then sails 40 blocks back along its route and vanishes (no blocks). The launches go through world.execute.
 */
final class BoatQuay {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    enum QuayState { FREE, APPROACH, DOCKED, DEPARTING, ARRIVING }

    /** Route length (blocks) between the berth and the point where an approaching shuttle appears. */
    static final double SPAWN_DISTANCE = 40.0;
    /** An arriving shuttle asks for the quay this far from the berth (route length); the first one waiting holds there. */
    static final double HOLD_DISTANCE = 40.0;
    /** Each further waiting shuttle holds this much farther out. */
    static final double HOLD_SPACING = 12.0;
    /** A departing shuttle frees the quay this far from the berth (straight line). */
    static final double CLEAR_DISTANCE = 40.0;
    /** A shuttle that set its passengers down sails this route length back before vanishing. */
    static final double LEAVE_DISTANCE = 40.0;
    /** The approaching shuttle could not reach the berth: next try. */
    static final long APPROACH_RETRY_MS = 30_000L;
    private static final long RETRY_MS = 5_000L;
    /** Failed departures (one every 5 s) before the moored shuttle is taken away and the head of the queue dropped. */
    static final int MAX_DEPART_TRIES = 4;
    /** Failed approaches (an unreachable berth, a lost shuttle) before the head of the queue is dropped. */
    static final int MAX_APPROACH_TRIES = 3;
    private static final long LOAD_RETRY_MS = 2_000L;

    // ------------------------------------------------------------------ quay state machine (pure, tested in TransitTests)

    /** Quay of one boat station. The transitions take the time as a parameter and touch no world. */
    static final class Quay {
        volatile QuayState state = QuayState.FREE;
        /** Approaching or departing shuttle (null otherwise). */
        volatile AirshipFlight flight;
        volatile long deadlineMs;
        /** Next countdown message threshold in seconds (-1: none left). */
        volatile int nextThreshold = -1;
        /** A launch is scheduled on the world thread. */
        volatile boolean busy;
        volatile long retryAtMs;
        /** The moored shuttle lies the other way round (opposite of the usual berth heading), see BoatRoutes.berthRotation. */
        volatile boolean flipped;
        /** Departures that failed since the shuttle docked: the shuttle is taken away after MAX_DEPART_TRIES. */
        volatile int departFailures;
        /** Until then nothing comes to the berth: gap after a shuttle left it. */
        volatile long gapUntilMs;
        /** Arriving shuttles waiting for the quay, in order of arrival. */
        private final List<UUID> waiting = new ArrayList<>();
        /** The arriving shuttle that holds the quay (state ARRIVING). */
        UUID arriving;

        /** A new approach may start: free, out of the gap and of the retry delay, no arrival pending, something queued. */
        synchronized boolean canApproach(long now, boolean queued) {
            return queued && state == QuayState.FREE && !busy && now >= gapUntilMs && now >= retryAtMs && arriving == null
                    && waiting.isEmpty();
        }

        synchronized void approach() {
            state = QuayState.APPROACH;
        }

        /** Failed approaches since the last docking: after MAX_APPROACH_TRIES the head of the queue is dropped. */
        volatile int approachFailures;

        synchronized boolean shouldGiveUp() {
            return approachFailures >= MAX_APPROACH_TRIES;
        }

        synchronized void approachFailed(long now, long retryMs) {
            approachFailures++;
            flight = null;
            retryAtMs = now + retryMs;
            state = QuayState.FREE;
        }

        /** The shuttle rests in blocks at the berth: the countdown starts. */
        synchronized void docked(long now) {
            flight = null;
            departFailures = 0;
            approachFailures = 0;
            state = QuayState.DOCKED;
            deadlineMs = now + TransitConstants.COUNTDOWN_SECONDS * 1000L;
            nextThreshold = TransitConstants.COUNTDOWN_SECONDS - TransitConstants.MESSAGE_INTERVAL_SECONDS;
        }

        synchronized boolean departureDue(long now) {
            return state == QuayState.DOCKED && now >= deadlineMs;
        }

        synchronized void departing() {
            state = QuayState.DEPARTING;
        }

        /** The departing shuttle is out of the way (or the moored one is gone): free after the gap. */
        synchronized void cleared(long now) {
            flight = null;
            gapUntilMs = now + TransitConstants.QUAY_GAP_SECONDS * 1000L;
            state = QuayState.FREE;
        }

        /** Adds the shuttle to the waiting arrivals (once) and returns its 0-based order. */
        synchronized int register(UUID id) {
            int i = waiting.indexOf(id);
            if (i < 0) {
                waiting.add(id);
                i = waiting.size() - 1;
            }
            return i;
        }

        /** 0-based order of the shuttle among the waiting arrivals (the number waiting if it is not registered). */
        synchronized int order(UUID id) {
            int i = waiting.indexOf(id);
            return i < 0 ? waiting.size() : i;
        }

        /** The shuttle takes the quay if it is free, out of the gap and first in the waiting order. Idempotent for the holder. */
        synchronized boolean tryArrive(UUID id, long now) {
            if (id.equals(arriving)) {
                return true;
            }
            if (arriving != null || state != QuayState.FREE || busy || now < gapUntilMs) {
                return false;
            }
            if (!waiting.isEmpty() && !waiting.get(0).equals(id)) {
                return false;
            }
            waiting.remove(id);
            arriving = id;
            state = QuayState.ARRIVING;
            return true;
        }

        /** The shuttle no longer needs the quay: forgotten in the waiting order, the quay freed after the gap if it held it. */
        synchronized void endArrival(UUID id, long now) {
            waiting.remove(id);
            if (id.equals(arriving)) {
                arriving = null;
                if (state == QuayState.ARRIVING) {
                    cleared(now);
                }
            }
        }

        synchronized boolean arrivalPending() {
            return arriving != null || !waiting.isEmpty();
        }
    }

    // ------------------------------------------------------------------ route helpers (pure, tested in TransitTests)

    /** Length of a polyline of columns {x, z}. */
    static double pathLength(List<int[]> pts) {
        double l = 0;
        for (int i = 1; i < pts.size(); i++) {
            l += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        return l;
    }

    /** The first dist blocks of the polyline: its points up to there, the last one cut (rounded to a column). Whole route if shorter. */
    static List<int[]> leg(List<int[]> pts, double dist) {
        List<int[]> out = new ArrayList<>();
        if (pts.isEmpty()) {
            return out;
        }
        out.add(pts.get(0));
        double done = 0;
        for (int i = 1; i < pts.size(); i++) {
            int[] a = pts.get(i - 1);
            int[] b = pts.get(i);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (done + seg >= dist) {
                double k = seg <= 0 ? 0 : (dist - done) / seg;
                int[] cut = {(int) Math.round(a[0] + (b[0] - a[0]) * k), (int) Math.round(a[1] + (b[1] - a[1]) * k)};
                if (cut[0] != a[0] || cut[1] != a[1] || out.size() == 1) {
                    out.add(cut);
                }
                return out;
            }
            done += seg;
            out.add(b);
        }
        return out;
    }

    static List<int[]> reversed(List<int[]> pts) {
        List<int[]> out = new ArrayList<>(pts);
        Collections.reverse(out);
        return out;
    }

    /** Route length left from the point (x, z) through the centre of waypoint index, then along the waypoints to the last one. */
    static double remaining(List<int[]> pts, int index, double x, double z) {
        if (pts.isEmpty()) {
            return 0;
        }
        int i = Math.min(Math.max(index, 0), pts.size() - 1);
        double l = Math.hypot(pts.get(i)[0] + 0.5 - x, pts.get(i)[1] + 0.5 - z);
        return l + pathLength(pts.subList(i, pts.size()));
    }

    /** Drop points of a boat station in the world (feet position), one per prefab point. */
    static List<Vector3d> dropPoints(Vector3i stationOrigin, Rotation rotation) {
        List<Vector3d> out = new ArrayList<>();
        for (float[] p : Deployables.BOAT_STATION_DROP_POINTS) {
            Vector3f r = rotation.rotateYaw(new Vector3f(p[0], p[1], p[2]), new Vector3f());
            out.add(new Vector3d(stationOrigin.x + r.x + 0.5, stationOrigin.y + r.y, stationOrigin.z + r.z + 0.5));
        }
        return out;
    }

    static List<Vector3d> dropPoints(TransitNetwork.Station s) {
        return dropPoints(s.origin, s.rotation);
    }

    /**
     * Station a rider who leaves a shuttle early (disconnect, server stop) goes back to: the departure station if it still exists, else the
     * nearest other boat station, else the destination if it is the last one, else null. Only boat stations count. Never the destination
     * when another choice exists, so quitting cannot be used to arrive instantly.
     */
    static TransitNetwork.Station returnStation(List<TransitNetwork.Station> stations, String from, String to, Vector3d here) {
        List<TransitNetwork.Station> boats = new ArrayList<>();
        for (TransitNetwork.Station s : stations) {
            if (Deployables.BOAT_STATION_KIND.equals(s.kind)) {
                boats.add(s);
            }
        }
        return TransitAutopilot.returnStation(boats, from, to, here);
    }

    /**
     * Stations a shuttle whose destination was removed tries in order: the departure station if it still exists, then the other boat
     * stations (never the removed one) from the nearest. Only boat stations count.
     */
    static List<TransitNetwork.Station> redirectTargets(List<TransitNetwork.Station> stations, String from, String removed, Vector3d here) {
        List<TransitNetwork.Station> out = new ArrayList<>();
        List<TransitNetwork.Station> others = new ArrayList<>();
        for (TransitNetwork.Station s : stations) {
            if (!Deployables.BOAT_STATION_KIND.equals(s.kind) || s.name.equals(removed)) {
                continue;
            }
            if (s.name.equals(from)) {
                out.add(s);
            } else {
                others.add(s);
            }
        }
        others.sort(java.util.Comparator.comparingDouble(s -> here.distanceSquared(s.origin.x, s.origin.y, s.origin.z)));
        out.addAll(others);
        return out;
    }

    /** Pier drop points of the return station of this trip, or null if there is no boat station left (the caller uses the surface). */
    static List<Vector3d> returnPoints(World world, String from, String to, Vector3d here) {
        TransitNetwork.Station st = returnStation(TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND), from, to,
                here);
        return st == null ? null : dropPoints(st);
    }

    // ------------------------------------------------------------------ state

    private enum Role { APPROACH, VOYAGE }

    /** A shuttle flight driven by the quays: an approach to its departure station, or a voyage between two stations. */
    private static final class Trip {
        final AirshipFlight f;
        final Role role;
        /** Approach: the station it docks at. Voyage: the departure station. */
        final TransitNetwork.Station station;
        /** Voyage: the arrival station (changed when the first one is removed in flight). */
        volatile TransitNetwork.Station dest;
        /** Voyage: holds the quay of the arrival station. */
        boolean arriving;
        long lastWaitMsgMs;
        long dropAtMs;

        Trip(AirshipFlight f, Role role, TransitNetwork.Station station, TransitNetwork.Station dest) {
            this.f = f;
            this.role = role;
            this.station = station;
            this.dest = dest;
        }
    }

    private static final Map<TransitNetwork.Station, Quay> QUAYS = new ConcurrentHashMap<>();
    private static final Map<UUID, Trip> TRIPS = new ConcurrentHashMap<>();

    private BoatQuay() {
    }

    private static Quay quay(TransitNetwork.Station s) {
        return QUAYS.computeIfAbsent(s, k -> new Quay());
    }

    // ------------------------------------------------------------------ public interface

    /**
     * Adds a destination to the boat station's queue (same rules and results as StationQueue.enqueue). ADDED is announced to the
     * players near the pier, the chooser is told about the other results.
     */
    static StationQueue.EnqueueResult enqueue(World world, TransitNetwork.Station s, String destination, PlayerRef chooser,
                                              Store<EntityStore> store, Ref<EntityStore> ref) {
        TransitNetwork net = TransitNetwork.get();
        TransitNetwork.Station target = net.byName(world.getName(), destination);
        if (!Deployables.BOAT_STATION_KIND.equals(s.kind) || (target != null && !target.kind.equals(s.kind))) {
            LOGGER.at(Level.INFO).log("File maritime de %s ignorée : stations de types différents", s.name);
            return StationQueue.EnqueueResult.UNKNOWN;
        }
        if (target == null) {
            LOGGER.at(Level.INFO).log("File maritime de %s : destination inconnue « %s »", s.name, destination);
            if (chooser != null) chooser.sendMessage(Texts.t("transit.network.removed").param("name", TransitNetwork.normalize(destination)));
            return StationQueue.EnqueueResult.UNKNOWN;
        }
        StationQueue.EnqueueResult r = TransitFare.addPaid(s, target.name, chooser, store, ref);
        int position = StationQueue.positionOf(s, target.name);
        switch (r) {
            case ADDED -> broadcast(world, s, Texts.t("transit.boatQueue.added").param("name", target.name).param("position", position));
            case ALREADY_QUEUED -> {
                if (chooser != null) chooser.sendMessage(Texts.t("transit.boatQueue.already").param("position", position));
            }
            case FULL -> {
                if (chooser != null) chooser.sendMessage(Texts.t("transit.boatQueue.full"));
            }
            case SELF, UNKNOWN -> {
            }
        }
        return r;
    }

    static QuayState state(TransitNetwork.Station s) {
        Quay q = QUAYS.get(s);
        return q == null ? QuayState.FREE : q.state;
    }

    /** Seconds left before departure while DOCKED, 0 otherwise. */
    static int secondsLeft(TransitNetwork.Station s) {
        Quay q = QUAYS.get(s);
        if (q == null || q.state != QuayState.DOCKED) {
            return 0;
        }
        return (int) Math.max(0, Math.ceil((q.deadlineMs - System.currentTimeMillis()) / 1000.0));
    }

    // ------------------------------------------------------------------ tick

    /** Called on every tick of every world. Never throws. */
    static void tick(Store<EntityStore> store, World world) {
        List<TransitNetwork.Station> stations = TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND);
        if (stations.isEmpty() && TRIPS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (TransitNetwork.Station s : stations) {
                Quay q = quay(s);
                switch (q.state) {
                    case FREE -> tickFree(store, world, s, q, now);
                    case APPROACH -> {
                        AirshipFlight f = q.flight;
                        if (!q.busy && (f == null || !AirshipManager.get().isActive(f))) {
                            LOGGER.at(Level.WARNING).log("Station maritime %s : navette d'approche perdue, quai libéré", s.name);
                            q.approachFailed(now, RETRY_MS);
                        }
                    }
                    case DOCKED -> tickDocked(store, world, s, q, now);
                    case DEPARTING -> tickDeparting(s, q, now);
                    case ARRIVING -> {
                        UUID id;
                        synchronized (q) {
                            id = q.arriving;
                        }
                        if (id != null && !TRIPS.containsKey(id)) {
                            q.endArrival(id, now); // its flight ended without telling the quay
                        }
                    }
                }
            }
            for (Trip t : new ArrayList<>(TRIPS.values())) {
                if (t.f.world == world) {
                    tickTrip(store, world, t, now);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Quais maritimes de %s : tick en échec", world.getName());
            Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
        }
    }

    private static void tickFree(Store<EntityStore> store, World world, TransitNetwork.Station s, Quay q, long now) {
        String head = TransitNetwork.get().queueHead(s);
        if (head != null && q.shouldGiveUp()) {
            LOGGER.at(Level.WARNING).log("Station maritime %s : %d approches en échec, départ vers %s abandonné", s.name, q.approachFailures, head);
            q.approachFailures = 0;
            TransitFare.refund(TransitNetwork.get().queueRemoveFirst(s, head), head);
            broadcast(world, s, Texts.t("transit.boatQueue.giveUp").param("name", head));
            return;
        }
        if (!q.canApproach(now, head != null)) {
            return;
        }
        BoatRoutes.RouteState rs = BoatRoutes.state(world.getName(), s.name, head);
        if (rs == BoatRoutes.RouteState.COMPUTING) {
            return; // charted later: the approach waits
        }
        List<int[]> route = BoatRoutes.route(world.getName(), s.name, head);
        if (rs != BoatRoutes.RouteState.FOUND || route.size() < 2) {
            LOGGER.at(Level.INFO).log("Station maritime %s : aucune route vers %s, départ retiré de la file", s.name, head);
            TransitFare.refund(TransitNetwork.get().queueRemoveFirst(s, head), head);
            broadcast(world, s, Texts.t("transit.boatQueue.noRoute").param("name", head));
            return;
        }
        List<int[]> wps = reversed(leg(route, SPAWN_DISTANCE)); // from about 40 blocks out back to the berth centre
        AirshipManager am = AirshipManager.get();
        double y = BoatRoutes.berthPivot(s).y;
        int[] first = wps.get(0);
        Vector3d centre = new Vector3d(first[0] + 0.5, y, first[1] + 0.5);
        double theta = wps.size() > 1 ? BoatAutopilot.targetHeading(centre.x, centre.z, wps.get(1)) : BoatRoutes.berthTheta(s);
        Vector3d pivot = am.pivotFromCenter(Deployables.STEAMBOAT_PUBLIC, centre, theta);
        if (!areaLoaded(world, s, centre)) {
            loadArea(world, s, centre);
            q.retryAtMs = now + LOAD_RETRY_MS;
            return;
        }
        q.busy = true;
        world.execute(() -> {
            try {
                if (q.state != QuayState.FREE || QUAYS.get(s) != q) {
                    return;
                }
                long t = System.currentTimeMillis();
                ShuttleState st = new ShuttleState(s, s, wps, ShuttleState.Phase.SAIL, t);
                st.listener = LISTENER;
                AirshipFlight f = am.launchShuttle(store, world, pivot, theta, st, List.of(), BoatAutopilot.masterLook(store, world, s));
                if (f == null) {
                    LOGGER.at(Level.WARNING).log("Station maritime %s : navette d'approche impossible", s.name);
                    q.approachFailed(t, RETRY_MS);
                    return;
                }
                TRIPS.put(f.id, new Trip(f, Role.APPROACH, s, null));
                q.flight = f;
                q.approach();
                LOGGER.at(Level.INFO).log("Station maritime %s : navette d'approche pour %s", s.name, head);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station maritime %s : navette d'approche en échec", s.name);
                Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
                q.approachFailed(System.currentTimeMillis(), RETRY_MS);
            } finally {
                q.busy = false;
            }
        });
    }

    private static void tickDocked(Store<EntityStore> store, World world, TransitNetwork.Station s, Quay q, long now) {
        if (q.busy) {
            return;
        }
        Rotation moored = mooredRotation(world, s);
        if (moored == null) {
            LOGGER.at(Level.INFO).log("Station maritime %s : navette à quai disparue, quai libéré", s.name);
            MooredPilot.remove(world, BoatRoutes.berthOrigin(s));
            q.cleared(now);
            return;
        }
        q.flipped = moored == BoatRoutes.berthRotation(s, true);
        MooredPilot.tick(store, world, s, q.flipped, now); // the pilot stands at the helm while the shuttle waits at the pier
        long leftMs = q.deadlineMs - now;
        int seconds = (int) Math.ceil(leftMs / 1000.0);
        int announce = -1;
        while (q.nextThreshold > 0 && seconds <= q.nextThreshold) {
            announce = q.nextThreshold;
            q.nextThreshold -= TransitConstants.MESSAGE_INTERVAL_SECONDS;
        }
        if (announce > 0 && leftMs > 0) {
            String head = TransitNetwork.get().queueHead(s);
            if (head != null) {
                broadcast(world, s, Texts.t("transit.quay.countdown").param("name", head).param("seconds", announce));
            }
        }
        if (!q.departureDue(now)) {
            return;
        }
        q.busy = true;
        world.execute(() -> {
            try {
                if (q.state != QuayState.DOCKED || QUAYS.get(s) != q) {
                    return;
                }
                depart(store, world, s, q);
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station maritime %s : départ en échec", s.name);
                Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
                departFailed(world, s, q);
            } finally {
                q.busy = false;
            }
        });
    }

    /** End of the countdown, on the world thread: the moored shuttle sails to the head of the queue with the players aboard. */
    private static void depart(Store<EntityStore> store, World world, TransitNetwork.Station s, Quay q) throws IOException {
        long now = System.currentTimeMillis();
        Vector3i origin = BoatRoutes.berthOrigin(s);
        Rotation found = mooredRotation(world, s);
        boolean flipped = found != null ? found == BoatRoutes.berthRotation(s, true) : q.flipped;
        Rotation rotation = BoatRoutes.berthRotation(s, flipped);
        if (!areaLoaded(world, s, BoatRoutes.berthCentre(s))) {
            loadArea(world, s, BoatRoutes.berthCentre(s));
            q.deadlineMs = now + RETRY_MS;
            return;
        }
        String head = TransitNetwork.get().queueHead(s);
        TransitNetwork.Station dest = head != null ? TransitNetwork.get().byName(world.getName(), head) : null;
        List<int[]> route = head != null ? BoatRoutes.route(world.getName(), s.name, head) : List.of();
        if (dest == null || !Deployables.BOAT_STATION_KIND.equals(dest.kind) || route.size() < 2) {
            // Nowhere to go (destination removed, route lost): the shuttle is taken away, nobody aboard is left in the water.
            if (head != null) {
                TransitFare.refund(TransitNetwork.get().queueRemoveFirst(s, head), head);
                broadcast(world, s, Texts.t("transit.boatQueue.noRoute").param("name", head));
            }
            StructureShape shape = Deployables.STEAMBOAT_PUBLIC.shape();
            setDown(store, AirshipPassengers.aboard(store, world, shape, origin, rotation, null), dropPoints(s), null);
            int removed = AirshipManager.get().unmoorShuttle(world, origin, rotation);
            LOGGER.at(Level.INFO).log("Station maritime %s : navette retirée sans destination (%d blocs)", s.name, removed);
            q.cleared(now);
            return;
        }
        AirshipManager am = AirshipManager.get();
        StructureShape shape = Deployables.STEAMBOAT_PUBLIC.shape();
        List<BalloonManager.Occupant> aboard = AirshipPassengers.aboard(store, world, shape, origin, rotation, null);
        List<AirshipPassengers.Rider> riders = am.passengers.assign(store, shape, origin, rotation, aboard);
        List<BalloonManager.Occupant> extras = new ArrayList<>();
        for (BalloonManager.Occupant o : aboard) {
            boolean seated = false;
            for (AirshipPassengers.Rider r : riders) {
                if (r.p.ref.equals(o.ref())) {
                    seated = true;
                }
            }
            if (!seated) {
                extras.add(o);
            }
        }
        setDown(store, extras, dropPoints(s), Texts.t("transit.boat.full"));
        am.passengers.prepare(store, riders); // held in the air before the deck goes
        AirshipFlight f;
        int removed;
        boolean unmoored = false;
        try {
            removed = am.unmoorShuttle(world, origin, rotation);
            unmoored = true;
            ShuttleState st = new ShuttleState(s, dest, route, ShuttleState.Phase.SAIL, now);
            st.listener = LISTENER;
            f = am.launchShuttle(store, world, BoatRoutes.berthPivot(s, flipped), BoatRoutes.berthTheta(s, flipped), st, riders,
                    BoatAutopilot.masterLook(store, world, s));
        } catch (IOException | RuntimeException e) {
            // The riders were prepared but no flight holds them: release them and put them down on the pier.
            am.passengers.releaseAll(store, riders);
            if (unmoored) {
                am.pasteShuttle(store, world, origin, rotation); // the blocks came off: put them back like a failed launch
            }
            setDown(store, ridersOf(aboard, riders), dropPoints(s), null);
            throw e;
        }
        if (f == null) {
            // The riders were released by launchShuttle: the shuttle comes back in blocks and they are set down on the pier.
            am.pasteShuttle(store, world, origin, rotation);
            setDown(store, ridersOf(aboard, riders), dropPoints(s), null);
            LOGGER.at(Level.WARNING).log("Station maritime %s : départ impossible", s.name);
            departFailed(world, s, q);
            return;
        }
        q.departFailures = 0;
        // Crash safety: until they arrive, each rider has a stranded entry on the departure pier (deleted when he is released).
        List<Vector3d> pier = dropPoints(s);
        for (AirshipPassengers.Rider r : f.riders) {
            Vector3d spot = pier.get(Math.floorMod(r.p.seat, pier.size()));
            BalloonResume.writeStranded(r.p.uuid, world.getName(), spot.x, spot.y, spot.z);
            r.stranded = true;
        }
        TRIPS.put(f.id, new Trip(f, Role.VOYAGE, s, dest));
        TransitNetwork.get().queueRemoveFirst(s, head);
        q.flight = f;
        q.departing();
        if (!f.riders.isEmpty()) {
            Telemetry.count("transit.boatTrip");
        }
        LOGGER.at(Level.INFO).log("Station maritime %s : départ vers %s, %d passager(s), %d bloc(s) retirés", s.name, dest.name,
                f.riders.size(), removed);
    }

    /** The occupants among those aboard that were seated as these riders. */
    private static List<BalloonManager.Occupant> ridersOf(List<BalloonManager.Occupant> aboard, List<AirshipPassengers.Rider> riders) {
        List<BalloonManager.Occupant> out = new ArrayList<>();
        for (AirshipPassengers.Rider r : riders) {
            for (BalloonManager.Occupant o : aboard) {
                if (o.ref().equals(r.p.ref)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /**
     * A departure failed (world thread): retried 5 s later, and after MAX_DEPART_TRIES the shuttle is taken away, the head of the
     * queue dropped and the quay freed, so a permanent failure does not loop for ever. Never throws.
     */
    private static void departFailed(World world, TransitNetwork.Station s, Quay q) {
        long now = System.currentTimeMillis();
        int n;
        synchronized (q) {
            n = ++q.departFailures;
            q.deadlineMs = now + RETRY_MS;
        }
        if (n < MAX_DEPART_TRIES) {
            return;
        }
        try {
            String head = TransitNetwork.get().queueHead(s);
            if (head != null) {
                TransitFare.refund(TransitNetwork.get().queueRemoveFirst(s, head), head);
                broadcast(world, s, Texts.t("transit.boat.approachFailed"));
            }
            Vector3i origin = BoatRoutes.berthOrigin(s);
            Rotation rotation = mooredRotation(world, s);
            if (rotation != null) {
                AirshipManager.get().unmoorShuttle(world, origin, rotation);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station maritime %s : navette non retirée après des départs en échec", s.name);
            Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
        }
        LOGGER.at(Level.WARNING).log("Station maritime %s : %d départs en échec, navette retirée et destination abandonnée", s.name, n);
        q.cleared(now);
    }

    private static void tickDeparting(TransitNetwork.Station s, Quay q, long now) {
        AirshipFlight f = q.flight;
        AirshipManager am = AirshipManager.get();
        if (f == null || !am.isActive(f)) {
            q.cleared(now);
            return;
        }
        ShuttleState st = f.shuttle;
        Vector3d c = am.centerWorld(f.kind, f.pos, f.theta);
        Vector3d b = BoatRoutes.berthCentre(s);
        if (Math.hypot(c.x - b.x, c.z - b.z) > CLEAR_DISTANCE || (st != null && st.phase == ShuttleState.Phase.HOLD)) {
            q.cleared(now); // out of the way, or waiting out at sea for its own arrival quay
        }
    }

    private static void tickTrip(Store<EntityStore> store, World world, Trip t, long now) {
        AirshipFlight f = t.f;
        AirshipManager am = AirshipManager.get();
        if (!am.isActive(f) || f.shuttle == null) {
            forget(t, now); // ended elsewhere (server stop, rescue...)
            return;
        }
        if (t.role != Role.VOYAGE) {
            return;
        }
        ShuttleState st = f.shuttle;
        Quay dq = QUAYS.get(t.dest);
        if (dq == null) {
            return; // the arrival station was removed: onStationRemoved redirects the shuttle
        }
        switch (st.phase) {
            case SAIL, ARRIVE -> {
                if (t.arriving) {
                    return;
                }
                Vector3d c = am.centerWorld(f.kind, f.pos, f.theta);
                List<int[]> wps = st.waypoints;
                double rem = remaining(wps, st.index, c.x, c.z);
                if (rem > HOLD_DISTANCE + dq.order(f.id) * HOLD_SPACING) {
                    return;
                }
                if (dq.tryArrive(f.id, now)) {
                    t.arriving = true;
                } else {
                    dq.register(f.id);
                    st.setPhase(ShuttleState.Phase.HOLD, now); // waits out at sea, held still by BoatAutopilot
                    t.lastWaitMsgMs = 0;
                    LOGGER.at(Level.INFO).log("Navette vers %s : ponton occupé, attente au large", t.dest.name);
                }
            }
            case HOLD -> {
                if (st.rerouting || st.rerouteResult != null) {
                    return; // waiting for a one-off route: no quay is claimed yet
                }
                if (dq.tryArrive(f.id, now)) {
                    t.arriving = true;
                    BoatAutopilot.resume(f, st, ShuttleState.Phase.SAIL, now);
                } else if (now - t.lastWaitMsgMs >= TransitConstants.MESSAGE_INTERVAL_SECONDS * 1000L) {
                    t.lastWaitMsgMs = now;
                    tell(store, f, Texts.t("transit.boat.waiting").param("name", t.dest.name));
                }
            }
            case DROP -> {
                if (now - t.dropAtMs < TransitConstants.DROP_WAIT_SECONDS * 1000L) {
                    return;
                }
                List<int[]> back = leg(reversed(st.waypoints), LEAVE_DISTANCE);
                if (back.size() < 2) {
                    forget(t, now);
                    am.endShuttle(store, f, "départ du ponton");
                    return;
                }
                st.waypoints = back;
                st.index = 0;
                BoatAutopilot.resume(f, st, ShuttleState.Phase.LEAVE, now);
            }
            default -> {
            }
        }
    }

    /** Forgets a trip and gives back what it held: the arrival quay, the departure quay, the approach. */
    private static void forget(Trip t, long now) {
        if (TRIPS.remove(t.f.id) == null) {
            return;
        }
        if (t.role == Role.VOYAGE) {
            Quay dq = QUAYS.get(t.dest);
            if (dq != null) {
                dq.endArrival(t.f.id, now);
            }
            Quay fq = QUAYS.get(t.station);
            if (fq != null && fq.state == QuayState.DEPARTING && fq.flight == t.f) {
                fq.cleared(now);
            }
        } else {
            Quay q = QUAYS.get(t.station);
            if (q != null && q.state == QuayState.APPROACH && q.flight == t.f) {
                q.approachFailed(now, APPROACH_RETRY_MS);
            }
        }
    }

    // ------------------------------------------------------------------ shuttle callbacks (world thread, from BoatAutopilot)

    private static final ShuttleState.Listener LISTENER = new ShuttleState.Listener() {
        @Override
        public void onArrived(AirshipFlight f) {
            Store<EntityStore> store = f.world.getEntityStore().getStore();
            AirshipManager am = AirshipManager.get();
            long now = System.currentTimeMillis();
            Trip t = TRIPS.get(f.id);
            if (t == null) {
                am.endShuttle(store, f, "navette sans trajet");
                return;
            }
            if (t.role == Role.APPROACH) {
                TRIPS.remove(f.id);
                TransitNetwork.Station s = t.station;
                Quay q = quay(s);
                boolean flip = f.shuttle != null && f.shuttle.flipped(); // the heading the shuttle arrived with
                if (am.moorShuttle(store, f, BoatRoutes.berthOrigin(s), BoatRoutes.berthRotation(s, flip))) {
                    q.flipped = flip;
                    q.docked(now);
                    String head = TransitNetwork.get().queueHead(s);
                    broadcast(f.world, s, Texts.t("transit.boat.docked").param("name", head != null ? head : s.name)
                            .param("seconds", TransitConstants.COUNTDOWN_SECONDS));
                    LOGGER.at(Level.INFO).log("Station maritime %s : navette à quai", s.name);
                } else {
                    LOGGER.at(Level.WARNING).log("Station maritime %s : navette non posée au ponton", s.name);
                    q.approachFailed(now, APPROACH_RETRY_MS);
                    broadcast(f.world, s, Texts.t("transit.boat.approachFailed"));
                }
                return;
            }
            // Voyage: the passengers are set down on the pier, welcome, then 5 s before leaving.
            List<PlayerRef> people = am.passengers.releaseAt(store, f, dropPoints(t.dest));
            for (PlayerRef pr : people) {
                TransitAutopilot.welcome(pr, t.dest);
            }
            t.dropAtMs = now;
            f.shuttle.setPhase(ShuttleState.Phase.DROP, now);
            LOGGER.at(Level.INFO).log("Navette arrivée à %s : %d passager(s) déposé(s)", t.dest.name, people.size());
        }

        @Override
        public void onRescue(AirshipFlight f) {
            Store<EntityStore> store = f.world.getEntityStore().getStore();
            AirshipManager am = AirshipManager.get();
            long now = System.currentTimeMillis();
            Trip t = TRIPS.get(f.id);
            if (t != null && t.role == Role.VOYAGE) {
                List<TransitNetwork.Station> boats = TransitNetwork.get().stations(f.world.getName(), Deployables.BOAT_STATION_KIND);
                TransitNetwork.Station target = boats.contains(t.dest) ? t.dest
                        : returnStation(boats, t.station.name, t.dest.name, f.pos);
                List<PlayerRef> people;
                if (target != null) {
                    people = am.passengers.releaseAt(store, f, dropPoints(target));
                } else {
                    people = new ArrayList<>(); // no boat station left: the riders are put on the surface under their seat
                    for (AirshipPassengers.Rider r : f.riders) {
                        PlayerRef pr = r.p.ref.isValid() ? store.getComponent(r.p.ref, PlayerRef.getComponentType()) : null;
                        if (pr != null) {
                            people.add(pr);
                        }
                    }
                    am.passengers.releaseAll(store, f);
                }
                for (PlayerRef pr : people) {
                    pr.sendMessage(target != null ? Texts.t("transit.boat.rescue").param("name", target.name)
                            : Texts.t("transit.boat.rescueSurface"));
                }
                LOGGER.at(Level.WARNING).log("Navette vers %s : secours, %d passager(s) déposé(s) à %s", t.dest.name, people.size(),
                        target != null ? target.name : "la surface");
            } else if (t != null) {
                broadcast(f.world, t.station, Texts.t("transit.boat.approachFailed"));
            }
            am.endShuttle(store, f, "secours");
            if (t != null) {
                forget(t, now);
            }
        }

        @Override
        public void onRetarget(AirshipFlight f, TransitNetwork.Station next) {
            Trip t = TRIPS.get(f.id);
            if (t != null && t.role == Role.VOYAGE) {
                t.dest = next;
                t.arriving = false;
                tell(f.world.getEntityStore().getStore(), f, Texts.t("transit.boat.rerouted").param("name", next.name));
            }
        }

        @Override
        public void onRouteEnd(AirshipFlight f) {
            Store<EntityStore> store = f.world.getEntityStore().getStore();
            long now = System.currentTimeMillis();
            AirshipManager.get().endShuttle(store, f, "départ du ponton");
            Trip t = TRIPS.get(f.id);
            if (t != null) {
                forget(t, now);
            }
        }
    };

    // ------------------------------------------------------------------ station removed, world start

    /**
     * A boat station is removed (already out of the network): its approaching shuttle is ended, its moored shuttle taken apart (the
     * hold gives its water back), the shuttles sailing to it set their passengers down at their departure station, and it leaves the
     * other queues. The players aboard the moored shuttle are left to the caller (StationCommand puts the station's players down).
     */
    static void onStationRemoved(World world, TransitNetwork.Station removed) {
        if (!Deployables.BOAT_STATION_KIND.equals(removed.kind)) {
            return;
        }
        TransitFare.refundRemovedStation(removed); // its own queue is lost with it
        Store<EntityStore> store = world.getEntityStore().getStore();
        AirshipManager am = AirshipManager.get();
        Quay q = QUAYS.remove(removed);
        for (Trip t : new ArrayList<>(TRIPS.values())) {
            try {
                if (t.role == Role.APPROACH && t.station == removed) {
                    TRIPS.remove(t.f.id);
                    am.endShuttle(store, t.f, "station retirée");
                } else if (t.role == Role.VOYAGE && t.dest == removed && am.isActive(t.f)) {
                    redirect(world, store, t);
                } else if (t.role == Role.VOYAGE && t.station == removed && am.isActive(t.f)) {
                    rewriteStranded(world, t); // crash safety must not point to the removed pier
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Navette non traitée après le retrait de %s", removed.name);
                Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
            }
        }
        if (q != null) {
            synchronized (q) {
                q.state = QuayState.FREE; // a launch already scheduled sees it is no longer the station's quay
            }
        }
        removeMoored(world, removed, true);
        for (TransitNetwork.Station other : TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND)) {
            if (other != removed && StationQueue.positionOf(other, removed.name) > 0) {
                for (TransitFare.Payment p : TransitNetwork.get().queueRemoveAll(other, removed.name)) {
                    TransitFare.refund(p, removed.name);
                }
                broadcast(world, other, Texts.t("transit.network.removed").param("name", removed.name));
            }
        }
    }

    /**
     * Takes the moored shuttle of a removed station apart. The berth sections must be in memory (else the blocks would stay orphaned
     * and unlocked): if they are not, they are loaded and the removal runs when they are. World thread.
     */
    private static void removeMoored(World world, TransitNetwork.Station removed, boolean firstTry) {
        try {
            Vector3i origin = BoatRoutes.berthOrigin(removed);
            MooredPilot.remove(world, origin); // the pilot is an entity of his own: gone whatever happens to the blocks
            Rotation rotation = mooredRotation(world, removed);
            if (rotation == null) {
                return;
            }
            Vector3d centre = BoatRoutes.berthCentre(removed);
            if (!areaLoaded(world, removed, centre)) {
                if (!firstTry) {
                    LOGGER.at(Level.WARNING).log("Navette à quai de %s : sections du ponton toujours absentes, retrait abandonné", removed.name);
                    return;
                }
                LOGGER.at(Level.INFO).log("Navette à quai de %s : chargement des sections du ponton avant le retrait", removed.name);
                int[] b = areaBox(removed, centre);
                SectionBlocks.loadSections(world.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5])
                        .whenComplete((v, e) -> world.execute(() -> removeMoored(world, removed, false)));
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            // The players aboard the moored shuttle are put on the shore once its blocks are gone.
            List<BalloonManager.Occupant> aboard = AirshipPassengers.aboard(store, world, Deployables.STEAMBOAT_PUBLIC.shape(), origin,
                    rotation, null);
            AirshipManager.get().unmoorShuttle(world, origin, rotation);
            Vector3d shore = StationMaster.masterPoint(removed); // the kiosk stands on dry land: the shore next to the pier
            shore = new Vector3d(shore.x, shore.y + 2, shore.z);
            for (BalloonManager.Occupant o : aboard) {
                if (o.ref().isValid() && o.pos() != null) {
                    BalloonManager.putOnGround(store, world, o.ref(), shore);
                    o.player().sendMessage(Texts.t("transit.boat.removedAboard").param("name", removed.name));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette à quai de %s non retirée", removed.name);
            Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
        }
    }

    /**
     * The arrival station of a shuttle in flight is removed: it sails to the departure station (new route), else to the nearest other
     * boat station it can reach, else the rescue sets its riders down (on the surface when no boat station is left). A shuttle already
     * at the removed pier (dropping or leaving) just vanishes. World thread.
     */
    private static void redirect(World world, Store<EntityStore> store, Trip t) {
        AirshipFlight f = t.f;
        ShuttleState st = f.shuttle;
        AirshipManager am = AirshipManager.get();
        if (st == null || st.phase == ShuttleState.Phase.DROP || st.phase == ShuttleState.Phase.LEAVE) {
            TRIPS.remove(f.id);
            am.endShuttle(store, f, "station retirée");
            return;
        }
        List<TransitNetwork.Station> targets = redirectTargets(
                TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND), t.station.name, t.dest.name, f.pos);
        if (targets.isEmpty()) {
            LISTENER.onRescue(f);
            return;
        }
        TransitNetwork.Station next = targets.get(0);
        st.fallbacks.clear();
        st.fallbacks.addAll(targets.subList(1, targets.size())); // tried in order if the route to this one fails
        t.dest = next;
        t.arriving = false;
        tell(store, f, Texts.t("transit.boat.rerouted").param("name", next.name));
        // Held still until the one-off route is applied (it sets SAIL): the old waypoints end at the removed berth and the new quay
        // must not be claimed before the shuttle is on its new route.
        st.setPhase(ShuttleState.Phase.HOLD, System.currentTimeMillis());
        BoatAutopilot.rerouteTo(world, f, st, next);
    }

    /** The departure pier is gone: the riders' crash safety entries now point to the return station. */
    private static void rewriteStranded(World world, Trip t) {
        List<Vector3d> pts = returnPoints(world, t.station.name, t.dest.name, t.f.pos);
        if (pts == null) {
            return;
        }
        for (AirshipPassengers.Rider r : t.f.riders) {
            if (r.stranded) {
                Vector3d spot = pts.get(Math.floorMod(r.p.seat, pts.size()));
                BalloonResume.writeStranded(r.p.uuid, world.getName(), spot.x, spot.y, spot.z);
            }
        }
    }

    /** Rider of a shuttle flight who leaves the game: back on the pier of the return station (stranded entry), see returnStation. */
    static void dropRider(AirshipFlight f, AirshipPassengers.Rider r) {
        Store<EntityStore> store = f.world.getEntityStore().getStore();
        ShuttleState st = f.shuttle;
        List<Vector3d> pts = st == null ? null : returnPoints(f.world, st.from != null ? st.from.name : null,
                st.to != null ? st.to.name : null, f.pos);
        Vector3d spot = pts != null ? pts.get(Math.floorMod(r.p.seat, pts.size()))
                : BalloonManager.groundBelow(f.world, AirshipManager.get().shipPoint(f, r.p.seatLocal));
        AirshipManager.get().passengers.dropOneAt(store, f, r, spot);
    }

    /** Server stop: every rider goes back to the pier of the return station and the trip is forgotten. World thread. */
    static void dropAllRiders(AirshipFlight f) {
        for (AirshipPassengers.Rider r : new ArrayList<>(f.riders)) {
            try {
                dropRider(f, r);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager %s de la navette non renvoyé au ponton", r.p.name);
                Telemetry.error("transit", e, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
            }
        }
        TRIPS.remove(f.id);
    }

    /** World start: a shuttle found moored in blocks at a boat station's berth (registry) sets its quay to DOCKED with a new countdown. */
    static void recoverWorld(World world) {
        try {
            world.execute(() -> {
                for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND)) {
                    Quay q = quay(s);
                    Rotation moored = q.state == QuayState.FREE ? mooredRotation(world, s) : null;
                    if (moored != null) {
                        q.flipped = moored == BoatRoutes.berthRotation(s, true);
                        LOGGER.at(Level.INFO).log("Station maritime %s : navette à quai retrouvée, compte à rebours de %d s", s.name,
                                TransitConstants.COUNTDOWN_SECONDS);
                        q.docked(System.currentTimeMillis());
                    }
                }
            });
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Reprise des quais maritimes de %s impossible", world.getName());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Rotation of the shuttle moored in blocks at the berth of this station (registry, the usual heading or the opposite one), null when none. */
    static Rotation mooredRotation(World world, TransitNetwork.Station s) {
        Vector3i origin = BoatRoutes.berthOrigin(s);
        for (boolean flipped : new boolean[] {false, true}) {
            Rotation r = BoatRoutes.berthRotation(s, flipped);
            if (BalloonRegistry.get(world, origin, r, Deployables.STEAMBOAT_PUBLIC.id) != null) {
                return r;
            }
        }
        return null;
    }

    /** Sections of the berth and of the point (a shuttle spawn point or the berth centre), from under the hull to above the funnels. */
    private static boolean areaLoaded(World world, TransitNetwork.Station s, Vector3d point) {
        int[] b = areaBox(s, point);
        return SectionBlocks.sectionsLoaded(world.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static void loadArea(World world, TransitNetwork.Station s, Vector3d point) {
        int[] b = areaBox(s, point);
        SectionBlocks.loadSections(world.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static int[] areaBox(TransitNetwork.Station s, Vector3d point) {
        Vector3d c = BoatRoutes.berthCentre(s);
        int r = 18; // holds every cell of the shuttle for any heading around its centre
        int y = BoatRoutes.surfaceY(s);
        return new int[] {(int) Math.floor(Math.min(c.x, point.x)) - r, y - 6, (int) Math.floor(Math.min(c.z, point.z)) - r,
                (int) Math.floor(Math.max(c.x, point.x)) + r, y + 10, (int) Math.floor(Math.max(c.z, point.z)) + r};
    }

    /** Players set down on the pier points (spread over them), with an optional message. */
    private static void setDown(Store<EntityStore> store, List<BalloonManager.Occupant> people, List<Vector3d> points, Message message) {
        int i = 0;
        for (BalloonManager.Occupant o : people) {
            try {
                if (!o.ref().isValid()) {
                    continue;
                }
                AirshipPassengers.teleport(store, o.ref(), points.get(i++ % points.size()));
                if (message != null) {
                    o.player().sendMessage(message);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Joueur laissé au ponton non déplacé");
                Telemetry.error("transit", e, null, null);
            }
        }
    }

    /** Sends a message to every rider of the shuttle. */
    private static void tell(Store<EntityStore> store, AirshipFlight f, Message message) {
        for (AirshipPassengers.Rider r : f.riders) {
            PlayerRef pr = r.p.ref.isValid() ? store.getComponent(r.p.ref, PlayerRef.getComponentType()) : null;
            if (pr != null) {
                pr.sendMessage(message);
            }
        }
    }

    /** Sends the message to the players within TransitConstants.MESSAGE_RADIUS of the berth. */
    static void broadcast(World world, TransitNetwork.Station s, Message message) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3d berth = BoatRoutes.berthCentre(s);
        double r2 = TransitConstants.MESSAGE_RADIUS * TransitConstants.MESSAGE_RADIUS;
        for (PlayerRef p : world.getPlayerRefs()) {
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t != null && t.getPosition().distanceSquared(berth) <= r2) {
                p.sendMessage(message);
            }
        }
    }
}
