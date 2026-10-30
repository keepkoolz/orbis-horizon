package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Navigation of the maritime shuttle on the sea chart: a coarse corridor ({@link ChartRoute}, searched a few thousand nodes per tick)
 * and a local pilot ({@link LocalNav}) that follows it on the real blocks. The pilot's paths become the waypoints of
 * {@link BoatAutopilot}. The trip is recorded in the shared {@link SeaChartBook}: kept at the arrival, forgotten otherwise.
 * World thread only, except the pure helpers.
 */
final class ShuttleNav {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Corridor nodes expanded per tick. */
    static final int CORRIDOR_NODES_PER_TICK = 2000;
    /** Local search nodes expanded per tick. */
    static final int LOCAL_NODES_PER_TICK = 1000;
    /** A new local search starts when this little is left of the current path (blocks). */
    static final double REFRESH_RANGE = 24.0;
    /** ... but only once the shuttle went this far since the last path (blocks). */
    static final double REFRESH_MIN_MOVE = 12.0;
    /** The shuttle has not left its departure berth within this distance of the berth centre (blocks). */
    static final double DEPARTURE_RANGE = 12.0;

    private ShuttleNav() {
    }

    // ------------------------------------------------------------------ pure helpers (tested in TransitTests)

    /** Length of a polyline in blocks (rounded). */
    static int corridorBlocks(List<int[]> points) {
        return (int) Math.round(BoatQuay.pathLength(points));
    }

    /** The list from index on, then the berth centre column unless it already ends there. Never changes the input. */
    static List<int[]> completed(List<int[]> list, int fromIndex, int[] berth) {
        List<int[]> out = new ArrayList<>();
        for (int i = Math.max(0, Math.min(fromIndex, list.size())); i < list.size(); i++) {
            out.add(list.get(i));
        }
        if (out.isEmpty() || out.get(out.size() - 1)[0] != berth[0] || out.get(out.size() - 1)[1] != berth[1]) {
            out.add(new int[] {berth[0], berth[1]});
        }
        return out;
    }

    /** True when the wait for unloaded sections is over: enough loads were asked, or it lasts too long. */
    static boolean loadWaitExpired(int attempts, long sinceMs, long nowMs) {
        return attempts >= BoatBerth.MAX_LOAD_ATTEMPTS || nowMs - sinceMs > BoatBerth.STALL_MS;
    }

    /** The shuttle is held still for unloaded sections on a tick whose pilot result is WAIT_DATA, and only then. */
    static boolean heldForData(LocalNav.Kind kind) {
        return kind == LocalNav.Kind.WAIT_DATA;
    }

    enum Wait { WAIT, LOAD, DATA_LOADED, GIVE_UP }

    /**
     * What to do on a tick of WAIT_DATA. A load in flight is waited for (until the stall time), a finished load restarts the search
     * (the counters are kept, only a path ends the wait), else a new load is asked until the attempts or the time run out.
     */
    static Wait waitDecision(boolean loadPending, boolean loadDone, int attempts, long sinceMs, long nowMs) {
        if (loadPending) {
            if (loadDone) {
                return Wait.DATA_LOADED;
            }
            return nowMs - sinceMs > BoatBerth.STALL_MS ? Wait.GIVE_UP : Wait.WAIT;
        }
        return loadWaitExpired(attempts, sinceMs, nowMs) ? Wait.GIVE_UP : Wait.LOAD;
    }

    /** True for a shuttle sailing from its departure berth (SAIL) that is still within DEPARTURE_RANGE of it. */
    static boolean beforeDeparture(ShuttleState.Phase phase, double distToDepartureBerth) {
        return phase == ShuttleState.Phase.SAIL && beforeDeparture(distToDepartureBerth);
    }

    /** True while the shuttle is still at its departure berth (distance of its centre to the berth centre). */
    static boolean beforeDeparture(double distToDepartureBerth) {
        return distToDepartureBerth <= DEPARTURE_RANGE;
    }

    /** The trip reached the arrival berth: its segments are kept and the pair is no longer incomplete. */
    static void tripArrived(SeaChartBook book, SeaChartBook.Trip t) {
        book.commit(t);
        book.clearIncomplete(t.world(), t.from(), t.to());
    }

    /** The trip failed (rescue, no corridor, pilot failure): its segments are forgotten and the pair is incomplete. */
    static void tripFailed(SeaChartBook book, SeaChartBook.Trip t) {
        book.forget(t);
        book.markIncomplete(t.world(), t.from(), t.to());
    }

    /** The shuttle was removed for another reason (server stop, station removed, end of the leaving leg): segments forgotten. */
    static void tripRemoved(SeaChartBook book, SeaChartBook.Trip t) {
        book.forget(t);
    }

    /** What a corridor reads of the book besides the chart: the closed cells and the learned segments of a world. Read only. */
    static ChartRoute.View view(SeaChartBook book, String world) {
        return new ChartRoute.View() {
            @Override
            public boolean closed(int cx, int cz) {
                return book.closed(world, cx, cz, System.currentTimeMillis());
            }

            @Override
            public List<SeaChartBook.Segment> segments() {
                return book.segments(world);
            }
        };
    }

    /** Points of a corridor traced in one go (no budget), null when there is none. */
    static List<int[]> traceNow(SeaChart chart, ChartRoute.View view, int fromX, int fromZ, int toX, int toZ) {
        ChartRoute r = new ChartRoute(chart, view, fromX, fromZ, toX, toZ);
        return r.step(Integer.MAX_VALUE) == ChartRoute.Status.FOUND ? r.points() : null;
    }

    /** Ends the navigation of a state without touching the book (a boat voyage that backs off, or a pilot that is dropped). */
    static void stop(ShuttleState st) {
        clear(st);
    }

    // ------------------------------------------------------------------ trip records of a shuttle (never throw)

    /** The shuttle docked at its arrival berth: the trip is kept and the navigation ends. */
    static void arrived(ShuttleState st) {
        SeaChartBook.Trip t = st.trip;
        clear(st);
        if (t != null) {
            try {
                tripArrived(SeaCharts.book(), t);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Shuttle: trip not kept in the chart memory");
            }
        }
    }

    /** The shuttle is rescued or its navigation failed: the trip is forgotten and the pair noted incomplete. */
    static void failed(ShuttleState st) {
        SeaChartBook.Trip t = st.trip;
        st.trip = null;
        if (t != null) {
            try {
                tripFailed(SeaCharts.book(), t);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Shuttle: trip not forgotten in the chart memory");
            }
        }
    }

    /** The shuttle flight is removed: the trip is forgotten. */
    static void removed(ShuttleState st) {
        if (st == null) {
            return;
        }
        SeaChartBook.Trip t = st.trip;
        st.trip = null;
        if (t != null) {
            try {
                tripRemoved(SeaCharts.book(), t);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Shuttle: trip not forgotten in the chart memory");
            }
        }
    }

    private static void clear(ShuttleState st) {
        st.trip = null;
        st.nav = null;
        st.corridorSearch = null;
        st.navReady = false;
        st.navFinal = false;
        st.navActive = false;
        st.noCorridor = false;
        st.navGoal = null;
        clearWait(st);
    }

    /** Ends a data wait: counters cleared and, when a wait was on, the watchdog restarts from now. */
    private static void endWait(AirshipFlight f, ShuttleState st, long now) {
        if (st.waitSinceMs != 0) {
            BoatAutopilot.markProgress(f, st, now);
        }
        clearWait(st);
    }

    private static void clearWait(ShuttleState st) {
        st.waitSinceMs = 0;
        st.loadAttempts = 0;
        st.pendingLoad = null;
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts (or restarts, toward another station) the navigation of the shuttle from where it is: a new corridor search, a new local
     * pilot and a new trip record. The shuttle holds still until the local pilot gave its first path. Never throws.
     */
    static void start(World world, AirshipFlight f, TransitNetwork.Station to) {
        start(world, f, to, true);
    }

    /** Same as {@link #start(World, AirshipFlight, TransitNetwork.Station)}; recordTrip false keeps the sea chart book out of it. */
    static void start(World world, AirshipFlight f, TransitNetwork.Station to, boolean recordTrip) {
        ShuttleState st = f.shuttle;
        if (st == null || to == null || st.from == null) {
            return;
        }
        try {
            removed(st); // a trip in progress (redirected shuttle) learns nothing
            clear(st);
            String wn = world.getName();
            SeaChartBook book = SeaCharts.book();
            Vector3d c = AirshipManager.get().centerWorld(f.kind, f.pos, f.theta);
            Vector3i goal = BoatBerth.berthCentreCell(to);
            st.to = to;
            st.lastCell = null;
            st.sidewaysAbandoned = false;
            st.tripBackOffs = 0; // a new trip
            st.hasLastBack = false;
            st.slide = ShuttleState.Slide.NONE;
            int direct = (int) Math.round(Math.hypot(goal.x - c.x, goal.z - c.z));
            st.trip = recordTrip ? book.startTrip(wn, st.from.name, to.name, SeaChartBook.maxSegments(direct)) : null;
            SeaChart chart = SeaCharts.chart(wn);
            if (chart == null) {
                st.noCorridor = true;
                LOGGER.at(Level.INFO).log("Shuttle %s: world %s has no sea chart, no corridor", f.id, wn);
                st.nav = new LocalNav(noCorridor(), HullClearance.SHUTTLE, goal.x, goal.z);
                return;
            }
            ChartRoute.View view = view(book, wn);
            st.corridorSearch = new ChartRoute(chart, view, (int) Math.floor(c.x), (int) Math.floor(c.z), goal.x, goal.z);
            st.nav = new LocalNav(new LocalNav.Corridor() {
                @Override
                public List<int[]> retrace(int fromX, int fromZ) {
                    try {
                        List<int[]> pts = traceNow(chart, view, fromX, fromZ, goal.x, goal.z);
                        if (pts == null) {
                            return null;
                        }
                        if (st.corridorSearch != null) {
                            st.corridorSearch = null; // the new corridor replaces the first one, still searched
                            capTrip(st, pts);
                        }
                        LOGGER.at(Level.INFO).log("Shuttle %s: corridor traced again from (%d, %d), %d point(s)", f.id, fromX, fromZ, pts.size());
                        return pts;
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Shuttle %s: new corridor tracing failed", f.id);
                        Telemetry.error("boat.corridor", e, Telemetry.kindId(f.kind), st.phase.name());
                        return null;
                    }
                }

                @Override
                public void close(int x, int z) {
                    if (st.corridorSearch != null) {
                        return; // the corridor is not known yet: an obstacle on the way to the berth says nothing about it
                    }
                    int cx = SeaChart.cellOf(x);
                    int cz = SeaChart.cellOf(z);
                    book.close(wn, cx, cz, System.currentTimeMillis());
                    LOGGER.at(Level.INFO).log("Shuttle %s: chart cell (%d, %d) closed, obstacle towards (%d, %d)", f.id, cx, cz, x, z);
                }
            }, HullClearance.SHUTTLE, goal.x, goal.z);
            LOGGER.at(Level.INFO).log("Shuttle %s: navigating to %s from (%.0f, %.0f)", f.id, to.name, c.x, c.z);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Shuttle %s: navigation not started", f.id);
            Telemetry.error("boat.corridor", e, Telemetry.kindId(f.kind), st.phase.name());
            st.noCorridor = true;
            if (st.nav == null) {
                Vector3i goal = BoatBerth.berthCentreCell(to);
                st.nav = new LocalNav(noCorridor(), HullClearance.SHUTTLE, goal.x, goal.z);
            }
        }
    }

    /** A corridor that cannot be traced again (no chart): the pilot fails at once when it needs one. */
    private static LocalNav.Corridor noCorridor() {
        return new LocalNav.Corridor() {
            @Override
            public List<int[]> retrace(int fromX, int fromZ) {
                return null;
            }

            @Override
            public void close(int x, int z) {
            }
        };
    }

    private static void capTrip(ShuttleState st, List<int[]> points) {
        SeaChartBook.Trip t = st.trip;
        if (t != null) {
            t.setMaxSegments(SeaChartBook.maxSegments(corridorBlocks(points)));
        }
    }

    /**
     * Asks a local search for a blocked shuttle (the watchdog, or the look-ahead that found no detour): the next tick searches again with
     * the columns to avoid, whatever the distance sailed. The cell that hit is avoided when the hull is stuck.
     */
    static void requestSearch(AirshipFlight f, ShuttleState st, boolean stuck) {
        int[] hit = st.lastHit;
        if (stuck && hit != null) {
            st.avoid.add(WaterRoute.columnKey(hit[0], hit[2]));
        }
        st.rerouting = true;
        if (st.nav != null) {
            st.nav.cancel(); // drops a search in progress: the next one starts with the columns to avoid
            st.navActive = false;
        }
        LOGGER.at(Level.INFO).log("Shuttle %s at (%.1f, %.1f): new local pilot search requested", f.id, f.pos.x, f.pos.z);
    }

    // ------------------------------------------------------------------ every tick

    /**
     * One tick of the navigation of a shuttle: trip record, corridor search, local pilot. Returns true when the shuttle must hold still
     * this tick (no path yet, unloaded sections, flight ended). A failure that cannot be recovered calls the rescue. Never throws.
     */
    static boolean tick(Store<EntityStore> store, World world, AirshipManager am, AirshipFlight f, ShuttleState st, long now) {
        if (st.nav == null) {
            return false;
        }
        Vector3d c = am.centerWorld(f.kind, f.pos, f.theta);
        try {
            record(world, st, c);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Shuttle %s: trip not recorded", f.id);
            Telemetry.error("boat.corridor", e, Telemetry.kindId(f.kind), st.phase.name());
        }
        try {
            if (corridor(store, world, f, st, c)) {
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Shuttle %s: corridor failed", f.id);
            Telemetry.error("boat.corridor", e, Telemetry.kindId(f.kind), st.phase.name());
            st.corridorSearch = null;
            st.noCorridor = false;
            BoatAutopilot.rescue(store, f, st);
            return true;
        }
        try {
            return local(store, world, am, f, st, c, now);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Shuttle %s: local pilot failed", f.id);
            Telemetry.error("boat.localnav", e, Telemetry.kindId(f.kind), st.phase.name());
            BoatAutopilot.rescue(store, f, st);
            return true;
        }
    }

    /** Notes the centre in the trip, and tells the book when the chart cell changed (a closed cell the shuttle sails through opens). */
    private static void record(World world, ShuttleState st, Vector3d c) {
        if (st.navGoal != null) {
            return; // a boat voyage reads the book and never writes it
        }
        SeaChartBook.Trip t = st.trip;
        if (t != null) {
            t.note(c.x, c.z);
        }
        int cx = SeaChart.cellOf((int) Math.floor(c.x));
        int cz = SeaChart.cellOf((int) Math.floor(c.z));
        int[] last = st.lastCell;
        if (last == null || last[0] != cx || last[1] != cz) {
            st.lastCell = new int[] {cx, cz};
            SeaCharts.book().crossed(world.getName(), cx, cz);
        }
    }

    /** The first corridor search. True when it ended without a corridor and the shuttle was handled (flight ended or sent elsewhere). */
    private static boolean corridor(Store<EntityStore> store, World world, AirshipFlight f, ShuttleState st, Vector3d c) {
        if (st.noCorridor) {
            st.noCorridor = false;
            noCorridorFound(store, f, st, c);
            return true;
        }
        ChartRoute r = st.corridorSearch;
        if (r == null) {
            return false;
        }
        switch (r.step(CORRIDOR_NODES_PER_TICK)) {
            case RUNNING:
                return false;
            case FOUND: {
                List<int[]> pts = r.points();
                st.corridorSearch = null;
                capTrip(st, pts);
                st.nav.setCorridor(pts);
                st.navActive = st.navReady; // a path found before the corridor is replaced by one that follows it
                endWait(f, st, System.currentTimeMillis());
                LOGGER.at(Level.INFO).log("Shuttle %s: corridor found, %d point(s), %d blocks", f.id, pts.size(), corridorBlocks(pts));
                return false;
            }
            default:
                st.corridorSearch = null;
                noCorridorFound(store, f, st, c);
                return true;
        }
    }

    /** No corridor to the destination: next candidate station if one is left, else the departure fails (still at the berth) or the rescue. */
    private static void noCorridorFound(Store<EntityStore> store, AirshipFlight f, ShuttleState st, Vector3d c) {
        LOGGER.at(Level.INFO).log("Shuttle %s: no corridor to %s", f.id, st.to != null ? st.to.name : "?");
        failed(st); // the pair is noted incomplete
        if (BoatAutopilot.retarget(f, st)) {
            return;
        }
        boolean atBerth = atDepartureBerth(st, c);
        if (!atBerth) {
            Telemetry.error("boat.corridor", new IllegalStateException(st.trip == null ? "approach without corridor" : "no corridor en route"), Telemetry.kindId(f.kind), st.phase.name());
        }
        BoatAutopilot.noCorridor(store, f, st, atBerth);
    }

    private static boolean atDepartureBerth(ShuttleState st, Vector3d c) {
        if (st.from == null) {
            return false;
        }
        Vector3d b = BoatBerth.berthCentre(st.from);
        return beforeDeparture(st.phase, Math.hypot(c.x - b.x, c.z - b.z));
    }

    /** The local pilot. True when the shuttle must hold still. */
    private static boolean local(Store<EntityStore> store, World world, AirshipManager am, AirshipFlight f, ShuttleState st, Vector3d c,
                                 long now) {
        ShuttleState.Phase ph = st.phase;
        boolean forced = st.rerouting && (ph == ShuttleState.Phase.SAIL || ph == ShuttleState.Phase.ARRIVE || ph == ShuttleState.Phase.HOLD);
        if (!forced && (ph != ShuttleState.Phase.SAIL || st.slide != ShuttleState.Slide.NONE)) {
            return false; // arrival manoeuvre, slide out of the berth, held phases: the list is not replaced
        }
        TransitNetwork.Station to = st.to;
        int[] voyageGoal = st.navGoal;
        if (to == null && voyageGoal == null) {
            return false;
        }
        boolean voyage = to == null; // a boat voyage: the goal is a column and the list ends there
        Vector3d b = voyage ? new Vector3d(voyageGoal[0] + 0.5, 0, voyageGoal[1] + 0.5) : BoatBerth.berthCentre(to);
        int[] berth = voyage ? voyageGoal : new int[] {BoatBerth.berthCentreCell(to).x, BoatBerth.berthCentreCell(to).z};
        double toGoal = Math.hypot(c.x - b.x, c.z - b.z);
        // A shuttle freezes its pilot near the berth. A voyage is final once its goal is inside the local window.
        boolean frozen = voyage ? voyageFinal(c.x - b.x, c.z - b.z) : BoatAutopilot.localNavFrozen(toGoal);
        List<int[]> wps = st.waypoints;
        if (!voyage && frozen && st.navReady && !st.navFinal && !forced) {
            // Entering the arrival range: the last list is completed to the berth centre, for the arrival manoeuvre.
            List<int[]> done = completed(wps, st.index, berth);
            st.waypoints = done;
            st.index = 0;
            st.turning = true;
            st.navFinal = true;
            st.navActive = false;
            st.nav.cancel(); // drops a search in progress: the pilot stays frozen
            st.arrivalFor = null;
            st.preparedFor = null;
            LOGGER.at(Level.INFO).log("Shuttle %s: within %.0f blocks of %s, local pilot frozen", f.id, BoatAutopilot.ARRIVAL_DECIDE_RANGE, to.name);
            return false;
        }
        boolean wanted = forced || !st.navReady;
        if (!wanted) {
            if (voyage && frozen) {
                wanted = st.navActive || !st.navFinal; // the goal is in the window: one last search that ends at it
            } else if (!frozen) {
                double left = BoatQuay.remaining(wps, st.index, c.x, c.z);
                wanted = st.navActive || st.nav.needsSearch(c.x, c.z)
                        || (!st.navFinal && left < REFRESH_RANGE && Math.hypot(c.x - st.navX, c.z - st.navZ) >= REFRESH_MIN_MOVE);
            }
        }
        if (!wanted) {
            return false;
        }
        SectionReader[] holder = {new SectionReader(world.getChunkStore())};
        // The probe reads the heights of the hull the flight tests, at the height the flight is locked to.
        WaterRoute.ColumnProbe probe = BoatBerth.probe(holder, BoatBerth.range(f.kind, Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY));
        String goalName = voyage ? "(" + voyageGoal[0] + ", " + voyageGoal[1] + ")" : to.name;
        st.navActive = true;
        LocalNav.Result r = st.nav.tick(c.x, c.z, probe, LOCAL_NODES_PER_TICK, st.avoid);
        switch (r.kind()) {
            case PATH:
                endWait(f, st, now);
                install(f, st, c, r.path(), frozen, berth, forced, now);
                return false;
            case WAIT_DATA:
                st.heldForData = heldForData(r.kind());
                waitForData(world, f, st, r.box(), now);
                return true;
            case FAILED: {
                LOGGER.at(Level.WARNING).log("Shuttle %s: local pilot finds no way to %s", f.id, goalName);
                if (atDepartureBerth(st, c)) {
                    // No corridor (a new tracing found none) while still at the berth: a failed departure, not a rescue.
                    failed(st);
                    BoatAutopilot.noCorridor(store, f, st, true);
                    return true;
                }
                Telemetry.error("boat.localnav", new IllegalStateException("local pilot without a way through"), Telemetry.kindId(f.kind), ph.name());
                BoatAutopilot.rescue(store, f, st);
                return true;
            }
            default:
                return !st.navReady; // the wait counters are kept: only a path or a give-up ends a wait
        }
    }

    /** Half side of the local window minus the margins: a boat voyage whose goal is this near on both axes ends on its next path. */
    static final int VOYAGE_FINAL_REACH = LocalNav.WINDOW / 2 - HullClearance.SHIP.leg() - WaterRoute.GRID;

    /** True when the goal of a boat voyage is inside the local window (offsets from the goal), so the next path can end at it. */
    static boolean voyageFinal(double dx, double dz) {
        return Math.abs(dx) <= VOYAGE_FINAL_REACH && Math.abs(dz) <= VOYAGE_FINAL_REACH;
    }

    /** A path of the local pilot becomes the waypoint list: from the current centre, completed to the berth centre inside the arrival range. */
    private static void install(AirshipFlight f, ShuttleState st, Vector3d c, List<int[]> path, boolean frozen, int[] berth, boolean forced,
                                long now) {
        List<int[]> list = new ArrayList<>(path.size() + 2);
        list.add(new int[] {(int) Math.floor(c.x), (int) Math.floor(c.z)});
        list.addAll(path);
        if (frozen) {
            list = completed(list, 0, berth);
        }
        st.waypoints = list;
        st.index = 0;
        st.turning = true;
        st.arrivalFor = frozen ? null : list; // only a list that ends at the berth gets the arrival decision
        st.navFinal = frozen;
        st.navReady = true;
        st.navActive = false;
        st.navX = c.x;
        st.navZ = c.z;
        st.detourKeys.clear();
        st.detoursSinceTrim = 0;
        st.shifts = 0;
        st.shiftTry = 0;
        if (forced) {
            st.rerouting = false;
            st.avoidHold = false;
            if (st.phase == ShuttleState.Phase.HOLD || st.phase == ShuttleState.Phase.ARRIVE) {
                st.setPhase(ShuttleState.Phase.SAIL, now);
            }
            BoatAutopilot.markProgress(f, st, now); // the new path gets its full 30 s
        }
        LOGGER.at(Level.FINE).log("Shuttle %s: local path of %d point(s)", f.id, list.size());
    }

    /**
     * Unloaded columns met by the search: the sections of the box are asked to load, the shuttle holds still and the watchdog is not
     * running. The wait is bounded by STALL_MS and MAX_LOAD_ATTEMPTS, then the unloaded columns count as blocked.
     */
    private static void waitForData(World world, AirshipFlight f, ShuttleState st, int[] box, long now) {
        if (st.waitSinceMs == 0) {
            st.waitSinceMs = now;
            st.loadAttempts = 0;
        }
        CompletableFuture<?> pending = st.pendingLoad;
        switch (waitDecision(pending != null, pending != null && pending.isDone(), st.loadAttempts, st.waitSinceMs, now)) {
            case WAIT:
                return;
            case DATA_LOADED:
                st.pendingLoad = null;
                st.nav.dataLoaded(); // the search restarts next tick
                BoatAutopilot.markProgress(f, st, now);
                return;
            case GIVE_UP:
                LOGGER.at(Level.WARNING).log("Shuttle %s: sections of the local window never loaded (%d tries), columns counted as blocked",
                        f.id, st.loadAttempts);
                st.nav.dataGaveUp();
                endWait(f, st, now);
                return;
            default: {
                st.loadAttempts++;
                int y = (int) Math.floor(Double.isNaN(f.lockedY) ? f.pos.y : f.lockedY);
                st.pendingLoad = SectionBlocks.loadSections(world.getChunkStore(), box[0], y - 6, box[1], box[2], y + 12, box[3])
                        .exceptionally(e -> null);
            }
        }
    }
}
