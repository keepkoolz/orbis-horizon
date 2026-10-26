package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Water routes between the boat stations of a world, for the maritime shuttle. One ordered pair (world, from, to) per route,
 * searched by {@link WaterRoute} one at a time, a few thousand nodes per tick, and persisted in boat-routes.json. The return
 * route is the outward route reversed, so one search resolves both directions.
 */
final class BoatRoutes {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    enum RouteState { COMPUTING, FOUND, NONE }

    /** Nodes expanded per tick. */
    static final int NODES_PER_TICK = 2000;
    /** Search box margin around the two berths, in blocks. */
    static final int BOX_MARGIN = 256;
    /** A column that stays unknown this long (ms) ends the search without a route. */
    static final long STALL_MS = 60_000L;
    /** Load attempts on the same missing column before giving up. */
    static final int MAX_LOAD_ATTEMPTS = 5;
    /** Half-width of the box loaded around a missing column. */
    private static final int LOAD_RADIUS = 15;
    /** Hull height above the waterline when the shuttle shape cannot be read. */
    private static final int FALLBACK_HULL_HEIGHT = 6;

    private BoatRoutes() {
    }

    /** One route: state and the rotation centre waypoints (x, z) from the departure berth to the arrival berth, both included. */
    record Route(RouteState state, List<int[]> points) {
    }

    /** Store of the routes, with the file. An instance of its own so tests do not touch the shared one. */
    static final class Book {
        private final Map<String, Route> routes = new LinkedHashMap<>();
        private Path file;

        private static String key(String world, String from, String to) {
            return world + "\u0000" + from + "\u0000" + to;
        }

        private static String[] split(String key) {
            return key.split("\u0000", -1);
        }

        synchronized Route get(String world, String from, String to) {
            return routes.get(key(world, from, to));
        }

        /** A new station: both directions towards each other station go back to COMPUTING. */
        synchronized void addStation(String world, String name, Collection<String> others) {
            for (String o : others) {
                if (o.equals(name)) {
                    continue;
                }
                routes.put(key(world, name, o), new Route(RouteState.COMPUTING, List.of()));
                routes.put(key(world, o, name), new Route(RouteState.COMPUTING, List.of()));
            }
            save();
        }

        /** A removed station: every pair that involves it is forgotten. */
        synchronized void removeStation(String world, String name) {
            routes.keySet().removeIf(k -> {
                String[] p = split(k);
                return p[0].equals(world) && (p[1].equals(name) || p[2].equals(name));
            });
            save();
        }

        /** Stores the result of a search for from to to, and the reversed one for to to from. */
        synchronized void resolve(String world, String from, String to, RouteState state, List<int[]> points) {
            routes.put(key(world, from, to), new Route(state, points));
            List<int[]> back = new ArrayList<>(points);
            java.util.Collections.reverse(back);
            routes.put(key(world, to, from), new Route(state, back));
            save();
        }

        /** Adds a missing COMPUTING pair (stations that existed before this feature, or lost entries). */
        synchronized void ensure(String world, String from, String to) {
            if (!from.equals(to) && !routes.containsKey(key(world, from, to))) {
                routes.put(key(world, from, to), new Route(RouteState.COMPUTING, List.of()));
            }
        }

        /** First route of the world still to compute, as {from, to}, or null. */
        synchronized String[] nextComputing(String world) {
            for (Map.Entry<String, Route> e : routes.entrySet()) {
                String[] p = split(e.getKey());
                if (p[0].equals(world) && e.getValue().state() == RouteState.COMPUTING) {
                    return new String[] {p[1], p[2]};
                }
            }
            return null;
        }

        /** Drops one pair (a station went missing without a removal event). */
        synchronized void drop(String world, String from, String to) {
            routes.remove(key(world, from, to));
            save();
        }

        synchronized void load(Path path) {
            file = path;
            routes.clear();
            if (!Files.isRegularFile(path)) {
                return;
            }
            try {
                BsonDocument doc = BsonDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
                if (!doc.containsKey("version") || doc.getInt32("version").getValue() != VERSION) {
                    // Older files joined the pivots: every pair is computed again (they come back as COMPUTING).
                    LOGGER.at(Level.INFO).log("boat-routes.json d'une ancienne version : routes à recalculer");
                    return;
                }
                for (var v : doc.getArray("routes")) {
                    BsonDocument d = v.asDocument();
                    List<int[]> pts = new ArrayList<>();
                    for (var p : d.getArray("points")) {
                        BsonArray a = p.asArray();
                        pts.add(new int[] {a.get(0).asInt32().getValue(), a.get(1).asInt32().getValue()});
                    }
                    routes.put(key(d.getString("world").getValue(), d.getString("from").getValue(), d.getString("to").getValue()),
                            new Route(RouteState.valueOf(d.getString("state").getValue()), pts));
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("boat-routes.json illisible, aucune route connue");
                routes.clear();
                try {
                    Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer boat-routes.json en .bad");
                }
            }
        }

        synchronized void save() {
            Path f = file;
            if (f == null) {
                return;
            }
            try {
                BsonArray arr = new BsonArray();
                for (Map.Entry<String, Route> e : routes.entrySet()) {
                    String[] p = split(e.getKey());
                    BsonDocument d = new BsonDocument();
                    d.put("world", new BsonString(p[0]));
                    d.put("from", new BsonString(p[1]));
                    d.put("to", new BsonString(p[2]));
                    d.put("state", new BsonString(e.getValue().state().name()));
                    BsonArray pts = new BsonArray();
                    for (int[] pt : e.getValue().points()) {
                        BsonArray a = new BsonArray();
                        a.add(new BsonInt32(pt[0]));
                        a.add(new BsonInt32(pt[1]));
                        pts.add(a);
                    }
                    d.put("points", pts);
                    arr.add(d);
                }
                BsonDocument doc = new BsonDocument();
                doc.put("version", new BsonInt32(VERSION));
                doc.put("routes", arr);
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Écriture de boat-routes.json impossible");
            }
        }
    }

    private static final Book BOOK = new Book();
    /** Version of boat-routes.json: 2 = waypoints of the rotation centre (1 joined the pivots). */
    static final int VERSION = 4; // bump when the route shape or clearances change, saved routes are recomputed

    static void load(Path dataDir) {
        BOOK.load(dataDir.resolve("boat-routes.json"));
    }

    // ---- berth geometry (pure) ----

    /** Prefab origin of the shuttle moored at the berth of this station, world cell. */
    static Vector3i berthOrigin(TransitNetwork.Station s) {
        Vector3i local = new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                Deployables.BOAT_STATION_BERTH_ORIGIN[2]);
        return s.rotation.rotateYaw(local, new Vector3i()).add(s.origin);
    }

    /** World rotation of the moored shuttle: station rotation plus the berth rotation. */
    static Rotation berthRotation(TransitNetwork.Station s) {
        return s.rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION);
    }

    /** Cell of the pivot of the moored shuttle. */
    static Vector3i berthPivotCell(TransitNetwork.Station s) {
        int[] pv = Deployables.STEAMBOAT_PUBLIC_PIVOT;
        return berthRotation(s).rotateYaw(new Vector3i(pv[0], pv[1], pv[2]), new Vector3i()).add(berthOrigin(s));
    }

    /** World position of the pivot of the moored shuttle (cell centre in x and z, like AirshipManager.initFlight). */
    static Vector3d berthPivot(TransitNetwork.Station s) {
        Vector3i c = berthPivotCell(s);
        return new Vector3d(c.x + 0.5, c.y, c.z + 0.5);
    }

    /**
     * World position of the rotation centre of the moored shuttle: the point AirshipManager turns the ship around (prefab x 0,
     * z rotationCenterZ, at the pivot height). The water routes join these centres, since a turn keeps the centre fixed.
     */
    static Vector3d berthCentre(TransitNetwork.Station s) {
        return AirshipManager.get().centerWorld(Deployables.STEAMBOAT_PUBLIC, berthPivot(s), berthTheta(s));
    }

    /** Column (x, z) of the rotation centre of the moored shuttle, y of the pivot cell: endpoint of the route searches. */
    static Vector3i berthCentreCell(TransitNetwork.Station s) {
        Vector3d c = berthCentre(s);
        return new Vector3i((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z));
    }

    /** Heading of the moored shuttle. */
    static double berthTheta(TransitNetwork.Station s) {
        return berthRotation(s).getRadians();
    }

    /**
     * Blocks the pre-berth point lies seaward of the berth centre, along the lateral axis of the moored shuttle (station +z, away from
     * the shore and the pier). The hull turns around its rotation centre, so the turning circle (corner radius of the prefab box,
     * about 7.0, hypot(2.5, 6.5) for the box x -2..2, z -5..6) around the pre-berth point must stay clear of the pier: 14 + 6 - 7.0 = 13.0,
     * a block over the pier edge at z 12. 6 is the smallest value that keeps the block (the test counts whole cells, the real gap is larger).
     */
    static final int BERTH_STANDOFF = 6;

    /** Pre-berth column: the berth centre column moved standoff cells along the station's +z axis (rotated with the station). */
    static Vector3i preBerthCell(Rotation stationRotation, Vector3i berthCentreCell, int standoff) {
        Vector3i d = stationRotation.rotateYaw(new Vector3i(0, 0, standoff), new Vector3i());
        return new Vector3i(berthCentreCell.x + d.x, berthCentreCell.y, berthCentreCell.z + d.z);
    }

    /** Pre-berth column of a station. */
    static Vector3i preBerthCell(TransitNetwork.Station s) {
        return preBerthCell(s.rotation, berthCentreCell(s), BERTH_STANDOFF);
    }

    private static double cellDistance(int[] wp, Vector3i cell) {
        return Math.hypot(wp[0] - cell.x, wp[1] - cell.z);
    }

    private static boolean sameCell(int[] wp, Vector3i cell) {
        return wp[0] == cell.x && wp[1] == cell.z;
    }

    /**
     * Route ending at a berth centre made to end at the pre-berth point: the waypoints nearer to the berth centre than the pre-berth
     * point are dropped (the last leg is never sailed bow first into the pier), the pre-berth column ends the route.
     */
    static List<int[]> arriveRoute(List<int[]> wps, Vector3i berthCell, Vector3i preCell, int standoff) {
        List<int[]> out = new ArrayList<>();
        for (int[] w : wps) {
            if (cellDistance(w, berthCell) < standoff - 1e-6) {
                if (out.isEmpty()) {
                    continue;
                }
                break;
            }
            if (!sameCell(w, preCell)) {
                out.add(w);
            }
        }
        out.add(new int[] {preCell.x, preCell.z});
        return out;
    }

    /**
     * Index to resume the untrimmed route at when the sideways arrival is abandoned: the first waypoint arriveRoute dropped (the trimmed
     * list is the kept prefix plus the pre-berth point), at most the last waypoint of the full route.
     */
    static int firstDropped(List<int[]> full, List<int[]> trimmed) {
        return Math.max(0, Math.min(trimmed.size() - 1, full.size() - 1));
    }

    /**
     * Route starting at a berth centre made to start at the pre-berth point: the shuttle first slides out to it, the waypoints nearer to
     * the berth centre than the pre-berth point are dropped.
     */
    static List<int[]> departRoute(List<int[]> wps, Vector3i berthCell, Vector3i preCell, int standoff) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[] {preCell.x, preCell.z});
        boolean inside = true;
        for (int[] w : wps) {
            if (inside && cellDistance(w, berthCell) < standoff - 1e-6) {
                continue;
            }
            inside = false;
            if (!sameCell(w, preCell) || out.size() > 1) {
                out.add(w);
            }
        }
        return out;
    }

    /** Water surface level at the berth: the prefab layer of the waterline sits on the highest water cell. */
    static int surfaceY(TransitNetwork.Station s) {
        return berthOrigin(s).y + Deployables.STEAMBOAT_PUBLIC.airship.waterlineY;
    }

    // ---- queries ----

    static RouteState state(String world, String from, String to) {
        Route r = BOOK.get(world, from, to);
        return r == null ? RouteState.NONE : r.state();
    }

    /** Rotation centre waypoints (x, z) from the departure berth to the arrival berth, empty unless the route is FOUND. */
    static List<int[]> route(String world, String from, String to) {
        Route r = BOOK.get(world, from, to);
        return r == null || r.state() != RouteState.FOUND ? List.of() : r.points();
    }

    // ---- hooks ----

    private static List<String> boatNames(String world) {
        List<String> out = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world, Deployables.BOAT_STATION_KIND)) {
            out.add(s.name);
        }
        return out;
    }

    static void stationAdded(World world, TransitNetwork.Station s) {
        if (!Deployables.BOAT_STATION_KIND.equals(s.kind)) {
            return;
        }
        BOOK.addStation(s.world, s.name, boatNames(s.world)); // a running search involves other stations: left alone
    }

    static void stationRemoved(World world, TransitNetwork.Station s) {
        if (!Deployables.BOAT_STATION_KIND.equals(s.kind)) {
            return;
        }
        BOOK.removeStation(s.world, s.name);
        Active a = active;
        if (a != null && a.world.equals(s.world) && (a.from.equals(s.name) || a.to.equals(s.name))) {
            active = null;
            answer(a.callback, null);
        }
        for (Reroute r : reroutes) {
            if (r.to() == s && reroutes.remove(r)) {
                answer(r.callback(), null);
            }
        }
    }

    // ---- one-off routes (a shuttle whose hull is blocked)

    /** A route asked from a shuttle's current position to a station, outside the file. topY: highest cell the probe checks, NO_TOP for the public shuttle's height above the surface. */
    private record Reroute(String world, int fromX, int fromZ, TransitNetwork.Station to, int toX, int toZ, int surfaceY, int topY, String label,
                           java.util.function.BooleanSupplier wanted, Consumer<List<int[]>> callback, java.util.Set<Long> avoid,
                           boolean freeStart) {
    }

    private static final ConcurrentLinkedQueue<Reroute> reroutes = new ConcurrentLinkedQueue<>();

    /**
     * Asks for a route from a position (rotation centre x, z) to the berth of a station, searched in the single search slot (before the routes
     * of the file still to compute) and never stored. A request whose asker is no longer interested (wanted false: shuttle ended
     * or rescued) is dropped without a search. The callback runs on the world thread with the centre waypoints (x, z) from the
     * position to the berth, or null when there is none (no route, station removed, search failure).
     */
    static void requestReroute(World world, double fromX, double fromZ, TransitNetwork.Station to,
                               java.util.function.BooleanSupplier wanted, Consumer<List<int[]>> callback) {
        requestReroute(world, fromX, fromZ, to, wanted, callback, java.util.Set.of(), false);
    }

    /**
     * Same, with columns the route must avoid (cells the shuttle hit or would hit: the probe judged them navigable, the hull did not) and
     * freeStart for a shuttle that is stuck: its own position is accepted as it is and the clearance does not taper around it, so the
     * route cannot lead back through the cells that hold it.
     */
    static void requestReroute(World world, double fromX, double fromZ, TransitNetwork.Station to,
                               java.util.function.BooleanSupplier wanted, Consumer<List<int[]>> callback, java.util.Set<Long> avoid,
                               boolean freeStart) {
        reroutes.removeIf(r -> !stillWanted(r)); // requests of ended shuttles (any world) do not pile up
        reroutes.add(new Reroute(world.getName(), (int) Math.floor(fromX), (int) Math.floor(fromZ), to, 0, 0, 0, NO_TOP, null,
                wanted, callback, avoid == null ? java.util.Set.of() : java.util.Set.copyOf(avoid), freeStart));
    }

    /** Reroute top meaning "the public shuttle's hull height above the surface". */
    private static final int NO_TOP = Integer.MIN_VALUE;

    /**
     * One-off route from a position to a column (no station): same queue, same search box rule, the water surface and the highest cell
     * of the asking boat given (a private steamboat is taller than the shuttle). The callback gets the centre waypoints (x, z), or null
     * when there is none.
     */
    static void requestRerouteTo(World world, double fromX, double fromZ, int toX, int toZ, int surfaceY, int topY, String label,
                                 java.util.function.BooleanSupplier wanted, Consumer<List<int[]>> callback,
                                 java.util.Set<Long> avoid, boolean freeStart) {
        reroutes.removeIf(r -> !stillWanted(r));
        reroutes.add(new Reroute(world.getName(), (int) Math.floor(fromX), (int) Math.floor(fromZ), null, toX, toZ, surfaceY, topY, label,
                wanted, callback, avoid == null ? java.util.Set.of() : java.util.Set.copyOf(avoid), freeStart));
    }

    private static boolean stillWanted(Reroute r) {
        try {
            return r.wanted() == null || r.wanted().getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hands a result to a reroute callback (null for a route of the file). Never throws. */
    private static void answer(Consumer<List<int[]>> callback, List<int[]> points) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(points);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Route maritime ponctuelle : rappel en échec");
        }
    }

    /** Ends the active search: a reroute gets its answer, a route of the file is stored (and its reverse). */
    private static void finish(Active a, RouteState state, List<int[]> points) {
        if (a.callback != null) {
            answer(a.callback, state == RouteState.FOUND ? points : null);
        } else {
            BOOK.resolve(a.world, a.from, a.to, state, points);
        }
    }

    /** Starts the next reroute of this world, if any. True if one became active (or was answered at once). */
    private static boolean startReroute(World world, String wn) {
        for (Reroute r : reroutes) {
            if (!r.world().equals(wn) || !reroutes.remove(r)) {
                continue;
            }
            if (!stillWanted(r)) {
                continue; // the shuttle was ended or rescued meanwhile: no search for it
            }
            try {
                TransitNetwork.Station to = r.to();
                String toName = to != null ? to.name : r.label();
                if (to != null && TransitNetwork.get().byName(wn, to.name) != to) {
                    answer(r.callback(), null);
                    return true;
                }
                Vector3i pb = to != null ? berthCentreCell(to) : new Vector3i(r.toX(), r.surfaceY(), r.toZ());
                Vector3i pa = new Vector3i(r.fromX(), pb.y, r.fromZ());
                if (tooFar(pa, pb)) {
                    LOGGER.at(Level.INFO).log("Route maritime ponctuelle vers %s : trop loin, pas de route", toName);
                    answer(r.callback(), null);
                    return true;
                }
                int minX = Math.min(pa.x, pb.x) - BOX_MARGIN, maxX = Math.max(pa.x, pb.x) + BOX_MARGIN;
                int minZ = Math.min(pa.z, pb.z) - BOX_MARGIN, maxZ = Math.max(pa.z, pb.z) + BOX_MARGIN;
                int surface = to != null ? surfaceY(to) : r.surfaceY();
                int top = r.topY() != NO_TOP ? r.topY() : surface + hullHeight();
                SectionReader[] holder = new SectionReader[1];
                WaterRoute.Search search = new WaterRoute.Search(probe(holder, surface, top), pa.x, pa.z, pb.x, pb.z, minX, minZ, maxX, maxZ)
                        .avoiding(r.avoid());
                if (r.freeStart()) {
                    search.withFreeStart();
                }
                Active a = new Active(wn, "", toName, search, holder, new int[] {pa.x, pa.z}, surface, top);
                a.callback = r.callback();
                active = a;
                LOGGER.at(Level.INFO).log("Route maritime ponctuelle depuis (%d, %d) vers %s : recherche lancée (%d colonne(s) à éviter%s)",
                        pa.x, pa.z, toName, r.avoid().size(), r.freeStart() ? ", départ libre" : "");
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Route maritime ponctuelle : lancement impossible");
                answer(r.callback(), null);
            }
            return true;
        }
        return false;
    }

    /** Route states from one station towards the others, for the debug command. */
    static String describe(TransitNetwork.Station s) {
        StringBuilder b = new StringBuilder();
        for (String o : boatNames(s.world)) {
            if (!o.equals(s.name)) {
                Route r = BOOK.get(s.world, s.name, o);
                b.append("\n  route to ").append(o).append(": ").append(r == null ? "none" : r.state())
                        .append(r != null && r.state() == RouteState.FOUND ? " (" + r.points().size() + " points)" : "");
            }
        }
        return b.toString();
    }

    // ---- search driver ----

    private static final class Active {
        final String world;
        final String from;
        final String to;
        final WaterRoute.Search search;
        /** Reader of the current tick: the probe reads through it, and it is replaced at every tick. */
        final SectionReader[] reader;
        final int[] fromPos;
        final int surfaceY;
        final int topY;
        CompletableFuture<Void> pending;
        /** One-off route of a shuttle: the result goes to this callback instead of the file. Null for a route of the file. */
        Consumer<List<int[]>> callback;
        int[] lastMissing;
        long missingSince;
        int attempts;

        Active(String world, String from, String to, WaterRoute.Search search, SectionReader[] reader, int[] fromPos, int surfaceY,
               int topY) {
            this.reader = reader;
            this.world = world;
            this.from = from;
            this.to = to;
            this.search = search;
            this.fromPos = fromPos;
            this.surfaceY = surfaceY;
            this.topY = topY;
        }
    }

    private static volatile Active active;
    /** Pair being started, so a failure before the search exists can still end it. */
    private static volatile String[] working;
    private static final java.util.Set<String> checkedWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile Integer hullHeight;

    /** Hull height above the waterline cell (layer waterline is the first one in the water). */
    private static int hullHeight() {
        Integer h = hullHeight;
        if (h == null) {
            int top = Integer.MIN_VALUE;
            try {
                StructureShape shape = Deployables.STEAMBOAT_PUBLIC.shapeOrNull();
                if (shape != null) {
                    for (StructureShape.Cell c : shape.cells()) {
                        top = Math.max(top, c.y());
                    }
                }
            } catch (RuntimeException e) {
                top = Integer.MIN_VALUE;
            }
            if (top == Integer.MIN_VALUE) {
                return FALLBACK_HULL_HEIGHT; // not cached: the shape may be readable later
            }
            h = top - Deployables.STEAMBOAT_PUBLIC.airship.waterlineY;
            hullHeight = h;
        }
        return h;
    }

    /** True when the straight distance between two pivots already exceeds the longest route: no search, no big arrays. */
    static boolean tooFar(Vector3i a, Vector3i b) {
        return Math.hypot(a.x - b.x, a.z - b.z) > WaterRoute.MAX_LENGTH;
    }

    /** Column navigable by the shuttle: water at the surface level and just below, nothing from there up to the hull top. */
    static WaterRoute.ColumnProbe probe(SectionReader[] holder, int surfaceY, int topY) {
        return (x, z) -> {
            SectionReader r = holder[0];
            boolean unknown = false;
            for (int y = surfaceY - 1; y <= topY; y++) {
                if (BalloonManager.outOfHeight(y)) {
                    return WaterRoute.Cell.BLOCKED;
                }
                if (!r.hasStorageInMemory(x, y, z)) {
                    unknown = true;
                    continue;
                }
                String liquid = BalloonManager.liquidAt(r, x, y, z);
                if (y <= surfaceY) {
                    if (!SteamboatWater.isWaterCell(r, x, y, z, liquid)) {
                        return WaterRoute.Cell.BLOCKED;
                    }
                } else if (liquid != null || BalloonManager.blocksBalloon(r, x, y, z)) {
                    return WaterRoute.Cell.BLOCKED;
                }
            }
            return unknown ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.WATER;
        };
    }

    /** One tick of the route work of this world. Never throws. */
    static void tick(World world) {
        try {
            tickWorld(world);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Routes maritimes : tick en échec");
            Telemetry.error("transit", e, "boat_station", null);
            // The pair that failed is given up (NONE), so it is not retried every tick and the others can run.
            Active a = active;
            String[] pair = a != null ? new String[] {a.from, a.to} : working;
            active = null;
            working = null;
            if (a != null && a.callback != null) {
                answer(a.callback, null);
            } else if (pair != null) {
                BOOK.resolve(world.getName(), pair[0], pair[1], RouteState.NONE, List.of());
            }
        }
    }

    private static void tickWorld(World world) {
        String wn = world.getName();
        if (checkedWorlds.add(wn)) {
            // Stations that exist without their pairs (placed before the routes existed, lost entries).
            List<String> names = boatNames(wn);
            for (String a : names) {
                for (String b : names) {
                    BOOK.ensure(wn, a, b);
                }
            }
            BOOK.save();
        }
        Active a = active;
        if (a == null && startReroute(world, wn)) {
            return; // the reroute (if any) runs from the next tick
        }
        if (a == null) {
            String[] next = BOOK.nextComputing(wn);
            if (next == null) {
                return;
            }
            working = next;
            TransitNetwork.Station sa = TransitNetwork.get().byName(wn, next[0]);
            TransitNetwork.Station sb = TransitNetwork.get().byName(wn, next[1]);
            if (sa == null || sb == null) {
                BOOK.drop(wn, next[0], next[1]);
                return;
            }
            if (surfaceY(sa) != surfaceY(sb)) {
                LOGGER.at(Level.INFO).log("Route maritime %s -> %s : surfaces différentes (%d et %d), pas de route",
                        sa.name, sb.name, surfaceY(sa), surfaceY(sb));
                BOOK.resolve(wn, sa.name, sb.name, RouteState.NONE, List.of());
                return;
            }
            Vector3i pa = berthCentreCell(sa);
            Vector3i pb = berthCentreCell(sb);
            if (tooFar(pa, pb)) {
                LOGGER.at(Level.INFO).log("Route maritime %s -> %s : trop loin, pas de route", sa.name, sb.name);
                BOOK.resolve(wn, sa.name, sb.name, RouteState.NONE, List.of());
                working = null;
                return;
            }
            int minX = Math.min(pa.x, pb.x) - BOX_MARGIN, maxX = Math.max(pa.x, pb.x) + BOX_MARGIN;
            int minZ = Math.min(pa.z, pb.z) - BOX_MARGIN, maxZ = Math.max(pa.z, pb.z) + BOX_MARGIN;
            int surface = surfaceY(sa);
            int top = surface + hullHeight();
            SectionReader[] holder = new SectionReader[1];
            WaterRoute.Search search = new WaterRoute.Search(probe(holder, surface, top), pa.x, pa.z, pb.x, pb.z, minX, minZ, maxX, maxZ);
            a = new Active(wn, sa.name, sb.name, search, holder, new int[] {pa.x, pa.z}, surface, top);
            active = a;
            working = null;
            LOGGER.at(Level.INFO).log("Route maritime %s -> %s : recherche lancée", sa.name, sb.name);
        }
        if (!a.world.equals(wn)) {
            return; // one search at a time, led by the tick of its own world
        }
        if (a.pending != null) {
            if (!a.pending.isDone()) {
                return;
            }
            a.pending = null;
        }
        a.reader[0] = new SectionReader(world.getChunkStore()); // a reader lives within one tick
        WaterRoute.Status st = a.search.step(NODES_PER_TICK);
        long now = System.currentTimeMillis();
        switch (st) {
            case RUNNING -> {
                a.lastMissing = null;
            }
            case NEED_DATA -> needData(world, a, now);
            case FOUND -> {
                List<int[]> pts = new ArrayList<>();
                pts.add(a.fromPos);
                pts.addAll(a.search.waypoints());
                active = null;
                finish(a, RouteState.FOUND, pts);
                LOGGER.at(Level.INFO).log("Route maritime %s -> %s : trouvée (%d points)", a.from, a.to, pts.size());
            }
            case NO_ROUTE -> {
                active = null;
                finish(a, RouteState.NONE, List.of());
                LOGGER.at(Level.INFO).log("Route maritime %s -> %s : aucune route", a.from, a.to);
            }
        }
    }

    private static void needData(World world, Active a, long now) {
        int[] miss = a.search.missingColumn();
        if (miss == null) {
            return;
        }
        if (a.lastMissing != null && a.lastMissing[0] == miss[0] && a.lastMissing[1] == miss[1]) {
            a.attempts++;
            if (a.attempts > MAX_LOAD_ATTEMPTS || now - a.missingSince > STALL_MS) {
                LOGGER.at(Level.WARNING).log("Route maritime %s -> %s : colonne (%d, %d) jamais chargée (%d essais), abandon",
                        a.from, a.to, miss[0], miss[1], a.attempts);
                active = null;
                finish(a, RouteState.NONE, List.of());
                return;
            }
        } else {
            a.lastMissing = miss;
            a.missingSince = now;
            a.attempts = 1;
        }
        // At most 2 x 2 chunk columns over about 2 section rows: 8 sections pending at most.
        a.pending = SectionBlocks.loadSections(world.getChunkStore(), miss[0] - LOAD_RADIUS, a.surfaceY - 1, miss[1] - LOAD_RADIUS,
                miss[0] + LOAD_RADIUS, a.topY, miss[1] + LOAD_RADIUS).exceptionally(e -> null);
    }
}
