package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
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
 * Steamboat voyages with the NPC pilot: destinations (stops and reached boat stations), the path planned on the water log (A*, then
 * shortened over open water across the next ticks), and the sailing itself, done by {@link BoatAutopilot} with the voyage's
 * {@link ShuttleState}. The landing, the retries and the incidents are those of the airship voyage (VoyageState, AirshipAutopilot.stop).
 */
final class ShipVoyage {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Leg checks of the plans and reroutes of a world per tick (one shortcut steps per tick, at least 2 legs). */
    static final int LEGS_PER_TICK = 40;
    /** A plan without an answer after this is given up (crew.voyage.noChart). */
    static final long PLAN_TIMEOUT_MS = 60_000L;
    /** A reroute path of at most this many points is shortened at once, in the tick that asks for it. */
    static final int SHORT_PATH = 16;

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

    // ------------------------------------------------------------------ planning (world thread)

    /** A path being planned for a parked boat before its departure. */
    private static final class Plan {
        final String world;
        final String key;
        final PlayerRef captain;
        final BalloonRegistry.Entry entry;
        final VoyagePlanner.Destination dest;
        final Runnable onAccepted;
        final long startMs;
        final SectionReader[] holder = new SectionReader[1];
        Shortcut shortcut;
        /** Answer of the WaterRoute fallback (null: none yet, empty: none at all). */
        volatile List<int[]> answer;

        Plan(String world, String key, PlayerRef captain, BalloonRegistry.Entry entry, VoyagePlanner.Destination dest, Runnable onAccepted,
             long startMs) {
            this.world = world;
            this.key = key;
            this.captain = captain;
            this.entry = entry;
            this.dest = dest;
            this.onAccepted = onAccepted;
            this.startMs = startMs;
        }
    }

    /** A reroute on the log being shortened over the next ticks. */
    private record RerouteJob(String world, AirshipFlight f, ShuttleState st, int token, Shortcut shortcut, SectionReader[] holder) {}

    private record Ready(VoyagePlanner.Destination dest, List<int[]> waypoints) {}

    /** At most one plan per boat (key: world and origin of its entry); a new order replaces the older one. */
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    private static final List<RerouteJob> JOBS = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Plan handed to startVoyage while it runs (keys are per world, each world ticks on its own thread). */
    private static final Map<String, Ready> READY = new ConcurrentHashMap<>();

    private static String keyOf(String world, BalloonRegistry.Entry e) {
        return world + ":" + e.x() + "," + e.y() + "," + e.z();
    }

    /** Water surface level of a boat whose pivot cell is at pivotY (the waterline layer sits on the highest water cell). */
    static int surfaceY(Deployables.Kind kind, int pivotY) {
        Deployables.AirshipSpec sp = AirshipManager.spec(kind);
        return pivotY - Math.round(sp.pivot.y) + sp.waterlineY;
    }

    /** Highest cell of the boat above the water surface (the probe checks up to there). */
    static int topY(Deployables.Kind kind, int surfaceY) {
        int top = 0;
        StructureShape shape = kind.shapeOrNull();
        if (shape != null) {
            for (StructureShape.Cell c : shape.cells()) {
                top = Math.max(top, c.y());
            }
        }
        return surfaceY + top - AirshipManager.spec(kind).waterlineY;
    }

    /** Starts planning (A* now, shortcut over the next ticks) and tells the captain "crew.voyage.charting"; the plan calls back on the world thread. Returns the refusal, or null when the planning started. */
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
                LOGGER.at(Level.INFO).log("Voyage du bateau : aucune case du journal pour %s", d.name());
                return Texts.pilot("crew.voyage.noChart", crew);
            }
            int gx = (int) Math.floor(goal[0]);
            int gz = (int) Math.floor(goal[1]);
            int surface = surfaceY(kind, (int) Math.floor(pivot.y));
            int top = topY(kind, surface);
            String key = keyOf(world.getName(), e);
            Plan p = new Plan(world.getName(), key, captain, e, d, onAccepted, System.currentTimeMillis());
            List<int[]> path = w.path((int) Math.floor(c.x), (int) Math.floor(c.z), gx, gz, Set.of());
            if (!path.isEmpty()) {
                p.shortcut = new Shortcut(path, box -> BoatRoutes.probe(p.holder, surface, top));
            } else {
                // Not on the log from here: a free WaterRoute path, like a reroute.
                BoatRoutes.requestRerouteTo(world, c.x, c.z, gx, gz, surface, top, d.name(), () -> PLANS.get(key) == p,
                        pts -> p.answer = pts != null ? pts : List.of(), Set.of(), false);
            }
            PLANS.put(key, p);
            captain.sendMessage(Texts.pilot("crew.voyage.charting", crew));
            LOGGER.at(Level.INFO).log("Voyage du bateau vers %s : calcul du chemin (%d case(s) du journal)", d.name(), path.size());
            return null;
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Voyage du bateau : calcul du chemin impossible");
            Telemetry.error("airship.autopilot", ex, e != null ? e.kind() : null, "plan");
            return Texts.pilot("crew.voyage.noChart", crew);
        }
    }

    /** One tick of the plans of this world (budget: 40 leg checks), called by BalloonFlightSystem like BoatRoutes.tick. */
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
                        LOGGER.at(Level.INFO).log("Voyage du bateau vers %s : calcul abandonné après %d s", p.dest.name(), PLAN_TIMEOUT_MS / 1000);
                        p.captain.sendMessage(Texts.pilot("crew.voyage.noChart", p.entry.crew()));
                        continue;
                    }
                    List<int[]> answer = p.answer;
                    if (answer != null) {
                        PLANS.remove(p.key, p);
                        if (answer.isEmpty()) {
                            p.captain.sendMessage(Texts.pilot("crew.voyage.noChart", p.entry.crew()));
                        } else {
                            depart(world, p, answer);
                        }
                        continue;
                    }
                    if (p.shortcut != null && !stepped) {
                        stepped = true;
                        p.holder[0] = new SectionReader(world.getChunkStore()); // a reader lives within one tick
                        if (p.shortcut.step(LEGS_PER_TICK)) {
                            PLANS.remove(p.key, p);
                            depart(world, p, p.shortcut.result());
                        }
                    }
                } catch (RuntimeException ex) {
                    PLANS.remove(p.key, p);
                    LOGGER.at(Level.WARNING).withCause(ex).log("Voyage du bateau : plan abandonné");
                    Telemetry.error("airship.autopilot", ex, p.entry.kind(), "plan");
                }
            }
            for (RerouteJob j : JOBS) {
                if (!j.world().equals(wn)) {
                    continue;
                }
                try {
                    if (!stillWanted(j.f(), j.st(), j.token())) {
                        JOBS.remove(j);
                        continue;
                    }
                    if (stepped) {
                        break;
                    }
                    stepped = true;
                    j.holder()[0] = new SectionReader(world.getChunkStore());
                    if (j.shortcut().step(LEGS_PER_TICK)) {
                        JOBS.remove(j);
                        answer(j.st(), j.token(), j.shortcut().result());
                    }
                } catch (RuntimeException ex) {
                    JOBS.remove(j);
                    LOGGER.at(Level.WARNING).withCause(ex).log("Voyage du bateau : nouvelle route abandonnée");
                    Telemetry.error("airship.autopilot", ex, Telemetry.kindId(j.f().kind), "reroute");
                    answer(j.st(), j.token(), List.of());
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Voyages du bateau : tick en échec");
            Telemetry.error("airship.autopilot", ex, Deployables.STEAMBOAT_KIND, "plan");
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

    /** Builds the ShuttleState of a departure: waypoints, goalTheta, router, listener. */
    static ShuttleState sailing(AirshipFlight f, List<int[]> waypoints, double goalTheta) {
        ShuttleState st = new ShuttleState(null, null, waypoints, ShuttleState.Phase.SAIL, System.currentTimeMillis());
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
                    LOGGER.at(Level.INFO).log("Voyage du bateau %s : point de repli atteint, essai d'amarrage", fl.id);
                    AirshipManager.get().startLanding(fl, false);
                    return;
                }
                v.arrived = true;
                v.phase = VoyageState.Phase.ARRIVE;
                v.progressMs = 0;
                LOGGER.at(Level.INFO).log("Voyage du bateau : arrivé à %s, début de l'amarrage", v.destName);
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
                v.backoff = new java.util.ArrayDeque<>(backoffPoints(st.waypoints, st.index, v.homeColumn));
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
                    LOGGER.at(Level.WARNING).log("Voyage du bateau %s : plus aucun point de repli, nouveaux essais sur place", f.id);
                }
                if (action == BackoffAction.RELEASE_RIDERS) {
                    releaseRiders(f, v);
                }
                return;
            }
            boolean home = v.homeColumn != null && p[0] == v.homeColumn[0] && p[1] == v.homeColumn[1] && v.backoff.size() == 1;
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
            st.rerouteResult = null;
            st.rerouteToken++; // a reroute still in flight is for the old route
            BoatAutopilot.resume(f, st, ShuttleState.Phase.SAIL, System.currentTimeMillis());
            v.phase = VoyageState.Phase.CRUISE;
            LOGGER.at(Level.INFO).log("Voyage du bateau %s : aucun amarrage possible, repli vers (%d, %d)%s, %d point(s) restant(s)", f.id,
                    p[0], p[1], home ? " (point de départ)" : "", v.backoff.size());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage du bateau : repli impossible");
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
            LOGGER.at(Level.INFO).log("Voyage du bateau %s : aucun amarrage possible, %d passager(s) posé(s) sur l'eau, le bateau reste seul", f.id, n);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage du bateau : passagers non libérés");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), "backoff");
        }
    }

    /** One-off route of a boat voyage: the log first (avoiding the cells near the hits), then a free WaterRoute path. World thread. */
    static void reroute(World world, AirshipFlight f, ShuttleState st, boolean stuck) {
        VoyageState v = f.voyage;
        if (v == null) {
            return;
        }
        int[] hit = st.lastHit;
        if (stuck && hit != null) {
            st.avoid.add(WaterRoute.columnKey(hit[0], hit[2]));
        }
        st.rerouting = true;
        int token = ++st.rerouteToken;
        AirshipManager am = AirshipManager.get();
        Vector3d c = am.centerWorld(f.kind, f.pos, f.theta);
        int[] back = v.backoff != null ? v.backoff.peekFirst() : null; // backing off: the route goes to the current back-off point
        int gx = back != null ? back[0] : (int) Math.floor(v.destX);
        int gz = back != null ? back[1] : (int) Math.floor(v.destZ);
        String goalName = back != null ? "point de repli" : v.destName;
        Set<Long> columns = new HashSet<>(st.avoid);
        if (hit != null) {
            columns.add(WaterRoute.columnKey(hit[0], hit[2]));
        }
        WaterLog w = f.water;
        List<int[]> path = w != null ? w.path((int) Math.floor(c.x), (int) Math.floor(c.z), gx, gz, cellsNear(columns)) : List.of();
        int surface = surfaceY(f.kind, (int) Math.floor(Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY));
        LOGGER.at(Level.INFO).log("Voyage du bateau %s en (%.1f, %.1f) : nouvelle route vers %s demandée (%s)", f.id, c.x, c.z, goalName,
                path.isEmpty() ? "eau libre" : "journal");
        int top = topY(f.kind, surface);
        if (!path.isEmpty()) {
            SectionReader[] holder = new SectionReader[1];
            Shortcut sc = new Shortcut(path, box -> BoatRoutes.probe(holder, surface, top));
            if (path.size() <= SHORT_PATH) {
                holder[0] = new SectionReader(world.getChunkStore());
                while (!sc.step(Integer.MAX_VALUE)) {
                    // a short path is shortened in this tick
                }
                answer(st, token, sc.result());
            } else {
                JOBS.add(new RerouteJob(world.getName(), f, st, token, sc, holder));
            }
            return;
        }
        BoatRoutes.requestRerouteTo(world, c.x, c.z, gx, gz, surface, top, goalName, () -> stillWanted(f, st, token), pts -> answer(st, token, pts),
                st.avoid, stuck);
    }

    private static boolean stillWanted(AirshipFlight f, ShuttleState st, int token) {
        return AirshipManager.get().isActive(f) && f.sailState() == st && !st.rescued && token == st.rerouteToken;
    }

    private static void answer(ShuttleState st, int token, List<int[]> pts) {
        if (token == st.rerouteToken) {
            st.rerouteResult = pts != null && !pts.isEmpty() ? pts : ShuttleState.EMPTY_ROUTE;
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
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage du bateau : erreur dans le tick de %s", f.id);
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** Shortens a log path piece by piece (at most PIECE points per piece, own WaterRoute.Search box = piece bounds + 20). */
    static final class Shortcut {
        static final int PIECE = 128;          // log points per piece
        static final int LOOK = 64;            // farthest point tried from the current one
        private static final int BOX_MARGIN = 20;

        private final List<int[]> path;
        private final Function<int[], WaterRoute.ColumnProbe> probeForBox;
        private final List<int[]> out = new ArrayList<>();
        private int pieceStart;
        private int pieceEnd = -1;   // last index of the current piece, -1 before the first piece starts
        private int cur;
        private int cand;            // candidate index being tried from cur
        private WaterRoute.Search search;
        private boolean done;
        private List<int[]> result;

        Shortcut(List<int[]> path, Function<int[], WaterRoute.ColumnProbe> probeForBox) {
            this.path = path;
            this.probeForBox = probeForBox;
            if (path.size() <= 2) {
                finish();
            } else {
                out.add(path.get(0));
            }
        }

        /** Does at most maxLegs leg checks. True when done. */
        boolean step(int maxLegs) {
            int legs = 0;
            while (!done) {
                if (search == null) {
                    startPiece();
                }
                if (cur >= pieceEnd) {
                    if (pieceEnd >= path.size() - 1) {
                        finish();
                        break;
                    }
                    pieceStart = pieceEnd;
                    search = null;
                    continue;
                }
                if (cand <= cur + 1) {
                    accept(cur + 1); // nothing better: keep the next log point
                    continue;
                }
                if (legs >= maxLegs) {
                    break;
                }
                legs++;
                int[] a = path.get(cur);
                int[] b = path.get(cand);
                Boolean leg = search.legClear(a[0], a[1], b[0], b[1]);
                if (!Boolean.TRUE.equals(leg)) { // blocked or not loaded: not free
                    cand--;
                    continue;
                }
                if (cand == pieceEnd) {
                    accept(cand);
                    continue;
                }
                if (legs >= maxLegs) {
                    // The turn circle check below counts as a leg too: wait for the next call (the leg result is recomputed, cheap).
                    break;
                }
                legs++;
                if (Boolean.TRUE.equals(search.turnClear(b[0], b[1]))) {
                    accept(cand);
                } else {
                    cand--;
                }
            }
            return done;
        }

        List<int[]> result() {
            return result;
        }

        private void accept(int index) {
            out.add(path.get(index));
            cur = index;
            cand = Math.min(pieceEnd, cur + LOOK);
        }

        private void startPiece() {
            pieceEnd = Math.min(pieceStart + PIECE - 1, path.size() - 1);
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = pieceStart; i <= pieceEnd; i++) {
                int[] p = path.get(i);
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
            minX -= BOX_MARGIN;
            minZ -= BOX_MARGIN;
            maxX += BOX_MARGIN;
            maxZ += BOX_MARGIN;
            int[] first = path.get(pieceStart);
            int[] last = path.get(pieceEnd);
            search = new WaterRoute.Search(probeForBox.apply(new int[] {minX, minZ, maxX, maxZ}), first[0], first[1], last[0], last[1],
                    minX, minZ, maxX, maxZ);
            cur = pieceStart;
            cand = Math.min(pieceEnd, cur + LOOK);
        }

        private void finish() {
            result = WaterLog.collapse(out.isEmpty() ? path : out);
            done = true;
        }
    }
}
