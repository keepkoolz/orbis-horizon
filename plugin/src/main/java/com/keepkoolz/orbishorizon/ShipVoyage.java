package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Steamboat voyages with the NPC pilot: destinations (stops and reached boat stations), the corridor planned before the departure (the
 * path of the water log, else a {@link ChartRoute} on the sea chart) and the sailing itself: {@link ShuttleNav} runs the {@link LocalNav}
 * pilot along the corridor and {@link BoatAutopilot} follows its paths with the voyage's {@link ShuttleState}. The sea chart book is
 * only read. The landing, the retries and the incidents are those of the airship voyage (VoyageState, AirshipAutopilot.stop).
 */
final class ShipVoyage {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** A plan without an answer after this is given up (crew.voyage.noChart). */
    static final long PLAN_TIMEOUT_MS = 60_000L;
    /** Blocks sailed between two points of the track of a voyage. The track is not capped: one point per 48 blocks of a voyage that is bounded by its destination and its watchdogs. */
    static final double TRACK_STEP = 48.0;
    /** Log cells (2 blocks each) between two points of a corridor taken from the water log: 48 blocks. */
    static final int CORRIDOR_CELLS = 24;

    private ShipVoyage() {
    }

    // ------------------------------------------------------------------ destinations (pure)

    /** Destinations of a boat: its stops then its reached stations still in the network, farther than WaterLog.DEST_MIN, each group by distance. x, z = rotation centre target. */
    static List<VoyagePlanner.Destination> destinations(WaterLog w, List<TransitNetwork.Station> boatStations, double fromX, double fromZ,
                                                        Function<TransitNetwork.Station, double[]> pierAndZone) {
        Set<WaterLog.Reached> existing = new HashSet<>();
        for (TransitNetwork.Station s : boatStations) {
            existing.add(new WaterLog.Reached(s.origin.x, s.origin.y, s.origin.z));
        }
        w.dropReachedNotIn(existing);
        List<VoyagePlanner.Destination> stops = new ArrayList<>();
        for (WaterLog.Stop st : w.stops()) {
            if (Math.hypot(st.cx() - fromX, st.cz() - fromZ) >= WaterLog.DEST_MIN) {
                stops.add(new VoyagePlanner.Destination(st.name(), VoyagePlanner.Kind.STOP, st.cx(), st.cz(), st.y()));
            }
        }
        List<VoyagePlanner.Destination> stations = new ArrayList<>();
        for (TransitNetwork.Station s : boatStations) {
            if (!w.reached().contains(new WaterLog.Reached(s.origin.x, s.origin.y, s.origin.z))) {
                continue;
            }
            double[] pz = pierAndZone.apply(s);
            int[] t = pz != null ? w.stationTarget(pz[0], pz[1], pz[2], pz[3]) : null;
            if (t != null && Math.hypot(t[0] - fromX, t[1] - fromZ) >= WaterLog.DEST_MIN) {
                stations.add(new VoyagePlanner.Destination(s.name, VoyagePlanner.Kind.STATION, t[0], t[1], s.origin.y));
            }
        }
        Comparator<VoyagePlanner.Destination> near = Comparator.comparingDouble(d -> Math.hypot(d.x() - fromX, d.z() - fromZ));
        stops.sort(near);
        stations.sort(near);
        List<VoyagePlanner.Destination> out = new ArrayList<>(stops);
        out.addAll(stations);
        return out;
    }

    /** Target centre column and heading of a destination: a stop gives (cx, cz) and its rotation; a station gives WaterLog.stationTarget and NaN. Null if none. */
    static double[] goalOf(WaterLog w, VoyagePlanner.Destination d, TransitNetwork.Station station, double[] pierAndZone) {
        if (w == null || d == null) {
            return null;
        }
        if (d.kind() == VoyagePlanner.Kind.STOP) {
            for (WaterLog.Stop st : w.stops()) {
                if (st.name().equals(d.name())) {
                    return new double[] {st.cx(), st.cz(), st.rotation().getRadians()};
                }
            }
            return null;
        }
        if (d.kind() == VoyagePlanner.Kind.STATION && station != null && pierAndZone != null) {
            int[] t = w.stationTarget(pierAndZone[0], pierAndZone[1], pierAndZone[2], pierAndZone[3]);
            return t != null ? new double[] {t[0], t[1], Double.NaN} : null;
        }
        return null;
    }

    /** {pierX, pierZ, zoneCx, zoneCz} of a boat station. */
    static double[] pierAndZone(TransitNetwork.Station s) {
        Vector3d p = StationMarkerProvider.markerPoint(s);
        StationZone.Zone z = StationZone.zoneOf(s);
        return new double[] {p.x, p.z, z.cx(), z.cz()};
    }

    /** Sets the {dest} parameter of a chat line: "Stop N" for a stop, the plain name otherwise (marker, station). */
    static Message withDest(Message m, VoyagePlanner.Kind kind, String name) {
        return kind == VoyagePlanner.Kind.STOP ? m.param("dest", Texts.t("crew.stop.name").param("n", name)) : m.param("dest", name);
    }

    /** Log cells within WaterRoute.CLEARANCE blocks of these world columns (WaterRoute.columnKey): a reroute on the log avoids them. */
    static Set<Long> cellsNear(Set<Long> columns) {
        Set<Long> out = new HashSet<>();
        int c = WaterRoute.CLEARANCE;
        for (long k : columns) {
            int x = (int) (k >> 32);
            int z = (int) k;
            for (int gx = WaterLog.gridOf(x - c); gx <= WaterLog.gridOf(x + c); gx++) {
                for (int gz = WaterLog.gridOf(z - c); gz <= WaterLog.gridOf(z + c); gz++) {
                    double cx = gx * WaterRoute.GRID + WaterRoute.GRID / 2.0;
                    double cz = gz * WaterRoute.GRID + WaterRoute.GRID / 2.0;
                    if (Math.hypot(cx - (x + 0.5), cz - (z + 0.5)) <= c) {
                        out.add(WaterLog.cell(gx, gz));
                    }
                }
            }
        }
        return out;
    }

    /** Adds the column (x, z) to the track when it is the first or at least TRACK_STEP blocks from the last one. True when added. Pure. */
    static boolean noteTrack(List<int[]> track, double x, double z) {
        int[] last = track.isEmpty() ? null : track.get(track.size() - 1);
        if (last != null && Math.hypot(x - last[0], z - last[1]) < TRACK_STEP) {
            return false;
        }
        track.add(new int[] {(int) Math.floor(x), (int) Math.floor(z)});
        return true;
    }

    /** Corridor of a path of the water log: one point every CORRIDOR_CELLS cells (48 blocks), first and last points kept. Pure. */
    static List<int[]> corridorFromLog(List<int[]> logPath) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < logPath.size(); i++) {
            if (i % CORRIDOR_CELLS == 0 || i == logPath.size() - 1) {
                out.add(new int[] {logPath.get(i)[0], logPath.get(i)[1]});
            }
        }
        return out;
    }

    /** The corridor points, ending on the goal column itself (a chart corridor ends on a chart cell centre). Pure. */
    static List<int[]> endingAt(List<int[]> points, int gx, int gz) {
        List<int[]> out = new ArrayList<>(points);
        int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
        if (last == null || last[0] != gx || last[1] != gz) {
            out.add(new int[] {gx, gz});
        }
        return out;
    }

    /**
     * Corridor from a column to a goal column, traced in one go: the path of the log (avoiding these log cells) first, else a route on
     * the sea chart of the world, read through the book. Null when there is none. Never writes the book.
     */
    static List<int[]> corridorNow(String world, WaterLog log, Set<Long> avoidCells, int fromX, int fromZ, int goalX, int goalZ) {
        if (log != null) {
            List<int[]> path = log.path(fromX, fromZ, goalX, goalZ, avoidCells);
            if (!path.isEmpty()) {
                return corridorFromLog(path);
            }
        }
        SeaChart chart = SeaCharts.chart(world);
        if (chart == null) {
            return null;
        }
        return ShuttleNav.traceNow(chart, ShuttleNav.view(SeaCharts.book(), world), fromX, fromZ, goalX, goalZ);
    }

    /** Same with the chart and the view given: the log path first, else a chart route. */
    static List<int[]> corridorNow(SeaChart chart, ChartRoute.View view, WaterLog log, Set<Long> avoidCells, int fromX, int fromZ,
                                   int goalX, int goalZ) {
        if (log != null) {
            List<int[]> path = log.path(fromX, fromZ, goalX, goalZ, avoidCells);
            if (!path.isEmpty()) {
                return corridorFromLog(path);
            }
        }
        return chart == null ? null : ShuttleNav.traceNow(chart, view, fromX, fromZ, goalX, goalZ);
    }

    // ------------------------------------------------------------------ planning (world thread)

    /** A corridor being planned for a parked boat before its departure. */
    private static final class Plan {
        final String world;
        final String key;
        final PlayerRef captain;
        final BalloonRegistry.Entry entry;
        final VoyagePlanner.Destination dest;
        final Runnable onAccepted;
        final long startMs;
        final int goalX;
        final int goalZ;
        /** Corridor search on the sea chart, stepped over the next ticks (null when the log gave the corridor). */
        ChartRoute search;
        /** The corridor (null: none yet, empty: none at all). */
        List<int[]> answer;

        Plan(String world, String key, PlayerRef captain, BalloonRegistry.Entry entry, VoyagePlanner.Destination dest, Runnable onAccepted,
             long startMs, int goalX, int goalZ) {
            this.world = world;
            this.key = key;
            this.captain = captain;
            this.entry = entry;
            this.dest = dest;
            this.onAccepted = onAccepted;
            this.startMs = startMs;
            this.goalX = goalX;
            this.goalZ = goalZ;
        }
    }

    private record Ready(VoyagePlanner.Destination dest, List<int[]> waypoints) {}

    /** At most one plan per boat (key: world and origin of its entry); a new order replaces the older one. */
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    /** Plan handed to startVoyage while it runs (keys are per world, each world ticks on its own thread). */
    private static final Map<String, Ready> READY = new ConcurrentHashMap<>();

    private static String keyOf(String world, BalloonRegistry.Entry e) {
        return world + ":" + e.x() + "," + e.y() + "," + e.z();
    }

    /** Starts planning the corridor (the log path now, else a chart search over the next ticks) and tells the captain "crew.voyage.charting"; the plan calls back on the world thread. Returns the refusal, or null when the planning started. */
    static Message plan(Store<EntityStore> store, World world, PlayerRef captain, BalloonRegistry.Entry e, VoyagePlanner.Destination d,
                        Runnable onAccepted) {
        CrewRecord crew = e != null ? e.crew() : null;
        try {
            Deployables.Kind kind = Deployables.get(e.kind());
            AirshipManager am = AirshipManager.get();
            Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
            Vector3d pivot = am.parkedPivot(kind, origin, e.rotation());
            Vector3d c = am.centerWorld(kind, pivot, e.rotation().getRadians());
            WaterLog w = e.water() != null ? e.water().copy() : new WaterLog();
            TransitNetwork.Station station = null;
            if (d.kind() == VoyagePlanner.Kind.STATION) {
                TransitNetwork.Station s = TransitNetwork.get().byName(world.getName(), d.name());
                station = s != null && Deployables.BOAT_STATION_KIND.equals(s.kind) ? s : null;
            }
            double[] goal = goalOf(w, d, station, station != null ? pierAndZone(station) : null);
            if (goal == null) {
                LOGGER.at(Level.INFO).log("Boat voyage: no log cell for %s", d.name());
                return Texts.pilot("crew.voyage.noChart", crew);
            }
            int gx = (int) Math.floor(goal[0]);
            int gz = (int) Math.floor(goal[1]);
            int sx = (int) Math.floor(c.x);
            int sz = (int) Math.floor(c.z);
            String key = keyOf(world.getName(), e);
            Plan p = new Plan(world.getName(), key, captain, e, d, onAccepted, System.currentTimeMillis(), gx, gz);
            List<int[]> logPath = w.path(sx, sz, gx, gz, Set.of());
            String source;
            if (!logPath.isEmpty()) {
                p.answer = corridorFromLog(logPath);
                source = "log, " + logPath.size() + " cell(s)";
            } else {
                SeaChart chart = SeaCharts.chart(world.getName());
                if (chart == null) {
                    LOGGER.at(Level.INFO).log("Boat voyage: world %s has no sea chart, no corridor to %s", world.getName(), d.name());
                    return Texts.pilot("crew.voyage.noChart", crew);
                }
                p.search = new ChartRoute(chart, ShuttleNav.view(SeaCharts.book(), world.getName()), sx, sz, gx, gz);
                source = "sea chart";
            }
            PLANS.put(key, p);
            captain.sendMessage(Texts.pilot("crew.voyage.charting", crew));
            LOGGER.at(Level.INFO).log("Boat voyage to %s: computing corridor (%s)", d.name(), source);
            return null;
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Boat voyage: cannot compute corridor");
            Telemetry.error("boat.corridor", ex, e != null ? e.kind() : null, "plan");
            return Texts.pilot("crew.voyage.noChart", crew);
        }
    }

    /** One tick of the plans of this world (one chart search steps per tick, ShuttleNav.CORRIDOR_NODES_PER_TICK nodes), called by BalloonFlightSystem. */
    static void tick(World world) {
        try {
            String wn = world.getName();
            long now = System.currentTimeMillis();
            boolean stepped = false;
            for (Plan p : List.copyOf(PLANS.values())) {
                if (!p.world.equals(wn)) {
                    continue;
                }
                try {
                    if (now - p.startMs > PLAN_TIMEOUT_MS) {
                        PLANS.remove(p.key, p);
                        LOGGER.at(Level.INFO).log("Boat voyage to %s: computation abandoned after %d s", p.dest.name(), PLAN_TIMEOUT_MS / 1000);
                        p.captain.sendMessage(Texts.pilot("crew.voyage.noChart", p.entry.crew()));
                        continue;
                    }
                    if (p.answer == null && p.search != null && !stepped) {
                        stepped = true;
                        switch (p.search.step(ShuttleNav.CORRIDOR_NODES_PER_TICK)) {
                            case FOUND -> p.answer = endingAt(p.search.points(), p.goalX, p.goalZ);
                            case NONE -> p.answer = List.of();
                            default -> {
                            }
                        }
                    }
                    List<int[]> answer = p.answer;
                    if (answer != null) {
                        PLANS.remove(p.key, p);
                        if (answer.isEmpty()) {
                            LOGGER.at(Level.INFO).log("Boat voyage to %s: no corridor on the chart", p.dest.name());
                            p.captain.sendMessage(Texts.pilot("crew.voyage.noChart", p.entry.crew()));
                        } else {
                            depart(world, p, answer);
                        }
                    }
                } catch (RuntimeException ex) {
                    PLANS.remove(p.key, p);
                    LOGGER.at(Level.WARNING).withCause(ex).log("Boat voyage: plan abandoned");
                    Telemetry.error("boat.corridor", ex, p.entry.kind(), "plan");
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Boat voyages: tick failed");
            Telemetry.error("boat.corridor", ex, Deployables.STEAMBOAT_KIND, "plan");
        }
    }

    /** The plan is ready: startVoyage runs again with it (all its refusals checked again), then the plan is dropped either way. */
    private static void depart(World world, Plan p, List<int[]> waypoints) {
        READY.put(p.key, new Ready(p.dest, waypoints));
        try {
            Message m = AirshipManager.get().startVoyage(world.getEntityStore().getStore(), world, p.captain, p.entry, p.dest, p.onAccepted);
            if (m != null && m != AirshipManager.DEFERRED) {
                p.captain.sendMessage(m);
            }
        } finally {
            READY.remove(p.key);
        }
    }

    /** Plan ready for this entry (consumed by AirshipManager.startVoyage), null if none. */
    static List<int[]> takePlan(World world, BalloonRegistry.Entry e, VoyagePlanner.Destination d) {
        if (e == null || d == null) {
            return null;
        }
        String key = keyOf(world.getName(), e);
        Ready r = READY.get(key);
        if (r == null || r.dest().kind() != d.kind() || !r.dest().name().equals(d.name())) {
            return null;
        }
        READY.remove(key);
        return r.waypoints();
    }

    // ------------------------------------------------------------------ sailing

    /**
     * Builds the ShuttleState of a departure: the corridor the local pilot follows (its last point is the goal column), goalTheta,
     * router, listener. The waypoints are one point on the boat until the pilot gives its first path.
     */
    static ShuttleState sailing(AirshipFlight f, List<int[]> corridor, double goalTheta) {
        Vector3d c = AirshipManager.get().centerWorld(f.kind, f.pos, f.theta);
        List<int[]> stub = List.of(new int[] {(int) Math.floor(c.x), (int) Math.floor(c.z)});
        ShuttleState st = new ShuttleState(null, null, stub, ShuttleState.Phase.SAIL, System.currentTimeMillis());
        int[] goal = corridor.get(corridor.size() - 1);
        startNav(f, st, goal[0], goal[1], corridor);
        st.track = new ArrayList<>(List.of(new int[] {stub.get(0)[0], stub.get(0)[1]})); // starts at the departure column
        st.goalTheta = goalTheta;
        st.router = ShipVoyage::reroute;
        st.listener = new ShuttleState.Listener() {
            @Override
            public void onArrived(AirshipFlight fl) {
                VoyageState v = fl.voyage;
                if (v == null) {
                    return;
                }
                if (v.backoff != null) {
                    // A back-off point is reached: try to land here (no arrival announced).
                    v.phase = VoyageState.Phase.STOPPING;
                    v.retries = 0;
                    LOGGER.at(Level.INFO).log("Boat voyage %s: fallback point reached, trying to dock", fl.id);
                    AirshipManager.get().startLanding(fl, false);
                    return;
                }
                v.arrived = true;
                v.phase = VoyageState.Phase.ARRIVE;
                v.progressMs = 0;
                LOGGER.at(Level.INFO).log("Boat voyage: arrived at %s, starting to dock", v.destName);
                AirshipManager.get().startLanding(fl, false);
            }

            @Override
            public void onRescue(AirshipFlight fl) {
                VoyageState v = fl.voyage;
                if (v != null && v.backoff != null) {
                    backOff(fl); // blocked on the way back: the next point
                    return;
                }
                AirshipAutopilot.stop(fl, "crew.voyage.blocked");
            }

            @Override
            public void onRouteEnd(AirshipFlight fl) {
                // No LEAVE phase on a voyage.
            }
        };
        return st;
    }

    /** Landing refusals at a back-off point before the next one is tried. */
    static final int BACKOFF_RETRIES = 3;

    /**
     * Back-off points of a boat whose landing never fits, nearest first: the waypoints already sailed (before index, in reverse order),
     * then the column where it was moored at departure (known to fit). Consecutive duplicates are dropped. Pure.
     */
    static List<int[]> backoffPoints(List<int[]> waypoints, int index, int[] home) {
        List<int[]> out = new ArrayList<>();
        for (int i = Math.min(index, waypoints.size()) - 1; i >= 0; i--) {
            add(out, waypoints.get(i));
        }
        if (home != null) {
            add(out, home);
        }
        return out;
    }

    private static void add(List<int[]> out, int[] p) {
        int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
        if (last == null || last[0] != p[0] || last[1] != p[1]) {
            out.add(new int[] {p[0], p[1]});
        }
    }

    /** A "no mooring fits" line is logged once, then at most this often. */
    static final long NO_MOORING_LOG_MS = 60_000L;

    /** What a boat does when the landing did not fit at its current back-off point. */
    enum BackoffAction { SAIL_BACK, RELEASE_RIDERS, KEEP_TRYING }

    /** A point left: sail back to it; none left: release the riders once, then keep trying alone. Pure. */
    static BackoffAction backoffAction(boolean pointLeft, boolean ridersReleased) {
        if (pointLeft) {
            return BackoffAction.SAIL_BACK;
        }
        return ridersReleased ? BackoffAction.KEEP_TRYING : BackoffAction.RELEASE_RIDERS;
    }

    /**
     * No landing fits here: the boat sails back to the next back-off point (built on the first call from its route and its departure
     * point) and tries to land there. The flight is never ended. With no point left (the departure point may be taken since), the
     * riders, captain included, are put on the surface next to their seat with the pilot's line, the captain's map is taken back,
     * and the boat stays a pilotless flight that keeps trying to land where it is. Never throws.
     */
    static void backOff(AirshipFlight f) {
        VoyageState v = f.voyage;
        ShuttleState st = v != null ? v.water : null;
        if (st == null) {
            return;
        }
        try {
            if (v.backoff == null) {
                v.backoff = new java.util.ArrayDeque<>(backoffPoints(st.track != null ? st.track : List.of(), st.track != null ? st.track.size() : 0, v.homeColumn));
            } else if (!v.backoff.isEmpty()) {
                v.backoff.pollFirst(); // the current point did not fit
            }
            v.retries = 0;
            int[] p = v.backoff.peekFirst();
            BackoffAction action = backoffAction(p != null, v.ridersReleased);
            if (action != BackoffAction.SAIL_BACK) {
                v.phase = VoyageState.Phase.STOPPING;
                v.stopped = true; // nobody aboard to tell: no later stop line
                long now = System.currentTimeMillis();
                if (v.noMooringLogMs == 0 || now - v.noMooringLogMs >= NO_MOORING_LOG_MS) {
                    v.noMooringLogMs = now;
                    LOGGER.at(Level.WARNING).log("Boat voyage %s: no fallback point left, retrying on the spot", f.id);
                }
                if (action == BackoffAction.RELEASE_RIDERS) {
                    releaseRiders(f, v);
                }
                return;
            }
            boolean home = v.homeColumn != null && p[0] == v.homeColumn[0] && p[1] == v.homeColumn[1] && v.backoff.size() == 1;
            ShuttleNav.stop(st); // the back-off leg is a fixed point: the pilot sails it as a plain route (a reroute starts a pilot again)
            st.waypoints = new ArrayList<>(List.of(new int[] {p[0], p[1]}));
            st.index = 0;
            st.goalTheta = home ? v.homeTheta : Double.NaN;
            st.preparedFor = null;
            st.arrivalFor = null;
            st.detourKeys.clear();
            st.shifts = 0;
            st.shiftTry = 0;
            st.reroutes = 0;
            st.rescued = false;
            st.rerouting = false;
            BoatAutopilot.resume(f, st, ShuttleState.Phase.SAIL, System.currentTimeMillis());
            v.phase = VoyageState.Phase.CRUISE;
            LOGGER.at(Level.INFO).log("Boat voyage %s: no docking possible, falling back to (%d, %d)%s, %d point(s) left", f.id,
                    p[0], p[1], home ? " (start point)" : "", v.backoff.size());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Boat voyage: fallback not possible");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), "backoff");
        }
    }

    /** No mooring fits anywhere: the pilot's line, the captain's map taken back, every rider put on the surface next to his seat. */
    private static void releaseRiders(AirshipFlight f, VoyageState v) {
        v.ridersReleased = true;
        try {
            Store<EntityStore> store = f.world.getEntityStore().getStore();
            AirshipPassengers.tell(store, f, "crew.voyage.cannotMoor");
            for (PlayerRef pr : f.world.getPlayerRefs()) {
                if (pr.getUuid().equals(v.captain)) {
                    CaptainMap.removeAll(store, pr.getReference());
                }
            }
            CaptainMap.forget(v.captain);
            int n = f.riders.size();
            AirshipManager.get().passengers.releaseAll(store, f);
            LOGGER.at(Level.INFO).log("Boat voyage %s: no docking possible, %d passenger(s) put on the water, the boat stays alone", f.id, n);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Boat voyage: passengers not released");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), "backoff");
        }
    }

    /**
     * Starts the local pilot of a boat toward a goal column along a corridor: the hull clearances of the steamboat, the corridor traced
     * again from the log or the sea chart when the pilot needs it, and no write in the sea chart book. The boat holds still until the
     * first path. World thread.
     */
    static void startNav(AirshipFlight f, ShuttleState st, int goalX, int goalZ, List<int[]> corridor) {
        String world = f.world.getName();
        st.navGoal = new int[] {goalX, goalZ};
        st.navReady = false;
        st.navFinal = false;
        st.navActive = false;
        st.nav = new LocalNav(new LocalNav.Corridor() {
            @Override
            public List<int[]> retrace(int fromX, int fromZ) {
                try {
                    List<int[]> pts = corridorNow(world, f.water, cellsNear(st.avoid), fromX, fromZ, goalX, goalZ);
                    if (pts != null) {
                        LOGGER.at(Level.INFO).log("Boat voyage %s: corridor traced again from (%d, %d), %d point(s)", f.id, fromX, fromZ, pts.size());
                    }
                    return pts;
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Boat voyage %s: new corridor tracing failed", f.id);
                    Telemetry.error("boat.localnav", e, Telemetry.kindId(f.kind), "retrace");
                    return null;
                }
            }

            @Override
            public void close(int x, int z) {
                // The boat of a player never writes the sea chart book.
            }
        }, HullClearance.SHIP, goalX, goalZ);
        st.nav.setCorridor(corridor);
    }

    /**
     * Router of a boat voyage, reached only while it backs off (a voyage with a pilot is handled by BoatAutopilot.reroute): the boat
     * sails a fixed point, so a blocked one gets a pilot toward the current back-off point (log or chart corridor, else a straight one).
     * World thread.
     */
    static void reroute(World world, AirshipFlight f, ShuttleState st, boolean stuck) {
        VoyageState v = f.voyage;
        if (v == null) {
            return;
        }
        try {
            int[] back = v.backoff != null ? v.backoff.peekFirst() : null;
            if (back == null) {
                return;
            }
            Vector3d c = AirshipManager.get().centerWorld(f.kind, f.pos, f.theta);
            int fx = (int) Math.floor(c.x);
            int fz = (int) Math.floor(c.z);
            List<int[]> corridor = corridorNow(world.getName(), f.water, cellsNear(st.avoid), fx, fz, back[0], back[1]);
            if (corridor == null) {
                corridor = List.of(new int[] {fx, fz}, new int[] {back[0], back[1]});
            }
            startNav(f, st, back[0], back[1], corridor);
            LOGGER.at(Level.INFO).log("Boat voyage %s at (%d, %d): local pilot to the fallback point (%d, %d)", f.id, fx, fz, back[0], back[1]);
            ShuttleNav.requestSearch(f, st, stuck);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Boat voyage %s: new search not possible", f.id);
            Telemetry.error("boat.localnav", e, Telemetry.kindId(f.kind), "reroute");
        }
    }

    /** Notes the boat's centre in the track of the voyage (never throws). */
    private static void recordTrack(AirshipFlight f, ShuttleState st) {
        try {
            if (st.track != null && st.nav != null && f.voyage != null && f.voyage.backoff == null) { // not while backing off: the track is what the back-off walks
                Vector3d c = AirshipManager.get().centerWorld(f.kind, f.pos, f.theta);
                noteTrack(st.track, c.x, c.z);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Boat voyage %s: travelled route not recorded", f.id);
            Telemetry.error("boat.localnav", e, Telemetry.kindId(f.kind), "track");
        }
    }

    /** One tick of a boat voyage (pilotless flight with voyage.water). Never throws. */
    static void step(Store<EntityStore> store, World world, AirshipFlight f, double dt, long now) {
        VoyageState v = f.voyage;
        if (v == null || v.water == null || f.landing || f.finishing) {
            return;
        }
        try {
            if (v.windowMs == 0) {
                v.windowMs = now;
                v.windowX = f.pos.x;
                v.windowZ = f.pos.z;
                v.windowTheta = f.theta;
            }
            switch (v.phase) {
                case ARRIVE, RETRY, STOPPING -> {
                    f.yawRate = 0;
                    f.yawAcc = 0;
                    if (now >= f.nextLandingRetryMs) {
                        AirshipManager.get().startLanding(f, false); // never a climb: a boat keeps its waterline
                    }
                }
                default -> {
                    v.phase = VoyageState.Phase.CRUISE;
                    if (f.dry && !v.stopped) {
                        AirshipAutopilot.stop(f, "crew.voyage.dry"); // no thrust: the boat does not move off
                        return;
                    }
                    recordTrack(f, v.water);
                    BoatAutopilot.step(store, world, f, v.water, dt, now);
                    if (f.finishing || f.landing) {
                        return;
                    }
                    AirshipAutopilot.fuelWindow(store, AirshipManager.get(), f, v, now);
                    if (f.dry && !v.stopped) {
                        AirshipAutopilot.stop(f, "crew.voyage.dry");
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Boat voyage: error in tick of %s", f.id);
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }
}
