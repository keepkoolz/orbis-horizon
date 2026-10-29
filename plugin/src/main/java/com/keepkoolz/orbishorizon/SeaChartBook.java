package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * What the seed cannot tell: segments the shuttles really sailed, chart cells found blocked and
 * station pairs whose last trip did not finish. Pure data, no world access. One instance of its
 * own per test, the plugin keeps the shared one.
 */
final class SeaChartBook {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Distance sailed between two noted points, in blocks. */
    static final int SEGMENT_STEP = 50;
    /** A point closer than this to a known point is merged with it, in blocks. */
    static final int MERGE = 25;
    /** How long a closed cell stays closed. */
    static final long CLOSE_MS = 24L * 60 * 60 * 1000;

    private static final int VERSION = 1;
    /** Size of a chart cell in blocks. */
    private static final int CELL = 16;
    /** Sampling step along a segment when testing which cells it crosses, in blocks. */
    private static final int CROSS_STEP = 4;

    /** A sailed segment, in blocks. */
    record Segment(int ax, int az, int bx, int bz) {
    }

    /** The points noted during one trip, kept apart until the trip is committed or forgotten. */
    static final class Trip {
        private final String world;
        private final String from;
        private final String to;
        private int maxSegments;
        private final List<int[]> points = new ArrayList<>();
        private double lastX;
        private double lastZ;

        private Trip(String world, String from, String to, int maxSegments) {
            this.world = world;
            this.from = from;
            this.to = to;
            this.maxSegments = Math.max(1, maxSegments);
        }

        /** Notes a position. The first one starts the trace, then one point per SEGMENT_STEP sailed. */
        synchronized void note(double x, double z) {
            if (!points.isEmpty() && Math.hypot(x - lastX, z - lastZ) < SEGMENT_STEP) {
                return;
            }
            points.add(new int[] {(int) Math.round(x), (int) Math.round(z)});
            lastX = x;
            lastZ = z;
            // Over the cap, the oldest pending segment goes.
            while (points.size() > maxSegments + 1) {
                points.remove(0);
            }
        }

        /** Replaces the cap (the corridor length is known after the trip started); the oldest pending segments over it go. */
        synchronized void setMaxSegments(int max) {
            maxSegments = Math.max(1, max);
            while (points.size() > maxSegments + 1) {
                points.remove(0);
            }
        }

        /** Number of segments waiting for the end of the trip. */
        synchronized int pending() {
            return Math.max(0, points.size() - 1);
        }

        private synchronized List<int[]> take() {
            List<int[]> r = new ArrayList<>(points);
            points.clear();
            return r;
        }

        String world() {
            return world;
        }

        String from() {
            return from;
        }

        String to() {
            return to;
        }
    }

    /** Everything remembered about one world. */
    private static final class WorldData {
        final List<Segment> segments = new ArrayList<>();
        final Map<Long, Long> closed = new LinkedHashMap<>();
        final Set<String> incomplete = new java.util.LinkedHashSet<>();
    }

    private final Map<String, WorldData> worlds = new LinkedHashMap<>();
    private Path file;
    /** Called after every mutation, under the book's lock: it must only schedule work, never write. */
    private volatile Runnable changeListener;

    /** Sets what runs after each mutation (null: nothing). The book never writes its file by itself. */
    void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private void changed() {
        Runnable l = changeListener;
        if (l != null) {
            try {
                l.run();
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Mémoire de la carte : rappel de changement en échec");
            }
        }
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static String pairKey(String from, String to) {
        return from + "\u0000" + to;
    }

    /** Pending segments the corridor of the given length allows for one trip. */
    static int maxSegments(int corridorBlocks) {
        return 2 * corridorBlocks / SEGMENT_STEP + 20;
    }

    private WorldData data(String world) {
        return worlds.computeIfAbsent(world, w -> new WorldData());
    }

    synchronized Trip startTrip(String world, String from, String to, int maxSegments) {
        return new Trip(world, from, to, maxSegments);
    }

    /** Keeps the trip: its points are merged with known ones and its new segments are stored. */
    synchronized void commit(Trip t) {
        List<int[]> pts = t.take();
        WorldData d = data(t.world());
        List<int[]> known = new ArrayList<>();
        for (Segment s : d.segments) {
            known.add(new int[] {s.ax(), s.az()});
            known.add(new int[] {s.bx(), s.bz()});
        }
        List<int[]> snapped = new ArrayList<>();
        for (int[] p : pts) {
            int[] best = null;
            double bestD = MERGE;
            for (int[] k : known) {
                double dist = Math.hypot(p[0] - k[0], p[1] - k[1]);
                if (dist < bestD) {
                    bestD = dist;
                    best = k;
                }
            }
            if (best == null) {
                best = p;
                known.add(p);
            }
            snapped.add(best);
        }
        boolean changed = false;
        for (int i = 1; i < snapped.size(); i++) {
            int[] a = snapped.get(i - 1);
            int[] b = snapped.get(i);
            if (a[0] == b[0] && a[1] == b[1]) {
                continue;
            }
            Segment s = new Segment(a[0], a[1], b[0], b[1]);
            Segment back = new Segment(b[0], b[1], a[0], a[1]);
            if (d.segments.contains(s) || d.segments.contains(back)) {
                continue;
            }
            d.segments.add(s);
            changed = true;
        }
        if (changed) {
            changed();
        }
    }

    /** Drops the trip without keeping anything. */
    synchronized void forget(Trip t) {
        t.take();
    }

    synchronized List<Segment> segments(String world) {
        WorldData d = worlds.get(world);
        return d == null ? List.of() : new ArrayList<>(d.segments);
    }

    private static boolean crosses(Segment s, int cx, int cz) {
        double len = Math.hypot(s.bx() - s.ax(), s.bz() - s.az());
        int n = Math.max(1, (int) Math.ceil(len / CROSS_STEP));
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            int x = (int) Math.floor(s.ax() + (s.bx() - s.ax()) * f);
            int z = (int) Math.floor(s.az() + (s.bz() - s.az()) * f);
            if (Math.floorDiv(x, CELL) == cx && Math.floorDiv(z, CELL) == cz) {
                return true;
            }
        }
        return false;
    }

    /** Closes a cell at the given time and forgets the learned segments that cross it. */
    synchronized void close(String world, int cx, int cz, long nowMs) {
        WorldData d = data(world);
        d.closed.put(cellKey(cx, cz), nowMs);
        d.segments.removeIf(s -> crosses(s, cx, cz));
        changed();
    }

    synchronized boolean closed(String world, int cx, int cz, long nowMs) {
        WorldData d = worlds.get(world);
        if (d == null) {
            return false;
        }
        Long at = d.closed.get(cellKey(cx, cz));
        return at != null && nowMs - at < CLOSE_MS;
    }

    /** A shuttle sailed through the cell: it is no longer closed. */
    synchronized void crossed(String world, int cx, int cz) {
        WorldData d = worlds.get(world);
        if (d != null && d.closed.remove(cellKey(cx, cz)) != null) {
            changed();
        }
    }

    synchronized void markIncomplete(String world, String from, String to) {
        if (data(world).incomplete.add(pairKey(from, to))) {
            changed();
        }
    }

    synchronized void clearIncomplete(String world, String from, String to) {
        WorldData d = worlds.get(world);
        if (d != null && d.incomplete.remove(pairKey(from, to))) {
            changed();
        }
    }

    synchronized boolean incomplete(String world, String from, String to) {
        WorldData d = worlds.get(world);
        return d != null && d.incomplete.contains(pairKey(from, to));
    }

    /** Reads the file and remembers its path for save. An unreadable file is renamed .bad and the book starts empty. */
    synchronized void load(Path path) {
        file = path;
        worlds.clear();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!doc.containsKey("version") || doc.getInt32("version").getValue() != VERSION) {
                throw new IllegalStateException("version inconnue");
            }
            for (var v : doc.getArray("worlds")) {
                BsonDocument w = v.asDocument();
                WorldData d = data(w.getString("world").getValue());
                for (var s : w.getArray("segments")) {
                    BsonArray a = s.asArray();
                    d.segments.add(new Segment(a.get(0).asInt32().getValue(), a.get(1).asInt32().getValue(),
                            a.get(2).asInt32().getValue(), a.get(3).asInt32().getValue()));
                }
                for (var c : w.getArray("closed")) {
                    BsonArray a = c.asArray();
                    d.closed.put(cellKey(a.get(0).asInt32().getValue(), a.get(1).asInt32().getValue()),
                            a.get(2).asNumber().longValue());
                }
                for (var p : w.getArray("incomplete")) {
                    BsonArray a = p.asArray();
                    d.incomplete.add(pairKey(a.get(0).asString().getValue(), a.get(1).asString().getValue()));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("sea-chart.json illisible, mémoire de la carte vide");
            worlds.clear();
            try {
                Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer sea-chart.json en .bad");
            }
        }
    }

    /** Forgets everything and the file: back to a book that was never loaded. */
    synchronized void reset() {
        worlds.clear();
        file = null;
        changeListener = null;
    }

    /** Held while a file is written, so two saves never interleave. Lock order: saveLock first, then the book's monitor. */
    private final Object saveLock = new Object();

    /** Writes to the path given to load. Does nothing when load was never called. The book is locked only to copy its data. */
    void save() {
        try {
            synchronized (saveLock) { // taken first: snapshots are written in the order they are taken
                Path f;
                String json;
                synchronized (this) {
                    f = file;
                    if (f == null) {
                        return;
                    }
                    json = snapshot();
                }
                write(f, json);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de sea-chart.json impossible");
        }
    }

    /** The document of the whole book, under the book's monitor. */
    private String snapshot() {
        {
            BsonArray arr = new BsonArray();
            for (Map.Entry<String, WorldData> e : worlds.entrySet()) {
                WorldData d = e.getValue();
                BsonDocument w = new BsonDocument();
                w.put("world", new BsonString(e.getKey()));
                BsonArray segs = new BsonArray();
                for (Segment s : d.segments) {
                    BsonArray a = new BsonArray();
                    a.add(new BsonInt32(s.ax()));
                    a.add(new BsonInt32(s.az()));
                    a.add(new BsonInt32(s.bx()));
                    a.add(new BsonInt32(s.bz()));
                    segs.add(a);
                }
                w.put("segments", segs);
                BsonArray closedArr = new BsonArray();
                for (Map.Entry<Long, Long> c : d.closed.entrySet()) {
                    BsonArray a = new BsonArray();
                    a.add(new BsonInt32((int) (c.getKey() >> 32)));
                    a.add(new BsonInt32((int) (long) c.getKey()));
                    a.add(new BsonInt64(c.getValue()));
                    closedArr.add(a);
                }
                w.put("closed", closedArr);
                BsonArray inc = new BsonArray();
                for (String k : d.incomplete) {
                    String[] p = k.split("\u0000", -1);
                    BsonArray a = new BsonArray();
                    a.add(new BsonString(p[0]));
                    a.add(new BsonString(p[1]));
                    inc.add(a);
                }
                w.put("incomplete", inc);
                arr.add(w);
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("worlds", arr);
            return doc.toJson();
        }
    }

    private static void write(Path f, String json) throws IOException {
        Files.createDirectories(f.toAbsolutePath().getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
