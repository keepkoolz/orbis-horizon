package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Registry of generated stations, the portals already seen and the sea cells already planned,
 * persisted in generated-stations.json in the plugin data folder. Every method is synchronized
 * (chunk event thread, planner executor and world thread all call it).
 */
final class GeneratedStations {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final GeneratedStations INSTANCE = new GeneratedStations();
    private static final String FILE_NAME = "generated-stations.json";
    /** Entries closer than this (blocks, horizontally) count as the same site. */
    private static final int AT_RADIUS = 4;
    /** A portal closer than this to one already seen is the same portal. */
    private static final int PORTAL_RADIUS = 64;

    enum State { PLANNED, BUILT, ABANDONED, REMOVED }

    static final class Entry {
        final String world;
        final String kind;
        /** Active site: origin column and paste rotation, moved by advance. */
        volatile int x, z;
        volatile Rotation rotation;
        final boolean partner;
        volatile State state;
        /** Ranked sites, the main one first; the active one is replaced by the site the station was built at. Immutable list. */
        volatile List<WorldStationPlanner.Site> sites;
        volatile int active;
        /** True for an entry created since the last load; never persisted. */
        final boolean thisSession;

        Entry(String world, String kind, int x, int z, Rotation rotation, boolean partner, State state) {
            this(world, kind, List.of(new WorldStationPlanner.Site(x, z, rotation)), 0, partner, state, false);
        }

        Entry(String world, String kind, List<WorldStationPlanner.Site> sites, int active, boolean partner, State state,
              boolean thisSession) {
            this.world = world;
            this.kind = kind;
            this.sites = List.copyOf(sites);
            this.active = active;
            WorldStationPlanner.Site s = this.sites.get(active);
            this.x = s.x();
            this.z = s.z();
            this.rotation = s.rotation();
            this.partner = partner;
            this.state = state;
            this.thisSession = thisSession;
        }
    }

    private record Point(String world, int x, int z) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Point> portals = new ArrayList<>();
    private final List<Point> cells = new ArrayList<>();
    /** Identity of each world the registry knows, to tell a recreated world from the one the entries were made for. */
    private final Map<String, UUID> worlds = new HashMap<>();
    private Path file;

    private GeneratedStations() {}

    static GeneratedStations get() { return INSTANCE; }

    /** Sets the data folder and replaces the content with the file's. An unreadable file is renamed .bad and never overwritten. */
    synchronized void load(Path dataDir) {
        file = dataDir.resolve(FILE_NAME);
        entries.clear();
        portals.clear();
        cells.clear();
        worlds.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            List<Entry> e = new ArrayList<>();
            for (var v : doc.getArray("entries")) {
                BsonDocument d = v.asDocument();
                List<WorldStationPlanner.Site> sites = new ArrayList<>();
                int active = 0;
                if (d.containsKey("sites")) {
                    for (var sv : d.getArray("sites")) {
                        BsonDocument sd = sv.asDocument();
                        sites.add(new WorldStationPlanner.Site(sd.getInt32("x").getValue(), sd.getInt32("z").getValue(),
                                Rotation.valueOf(sd.getString("rotation").getValue())));
                    }
                    active = d.containsKey("active") && d.get("active").isNumber() ? d.get("active").asNumber().intValue() : 0;
                }
                if (sites.isEmpty()) {
                    sites.add(new WorldStationPlanner.Site(d.getInt32("x").getValue(), d.getInt32("z").getValue(),
                            Rotation.valueOf(d.getString("rotation").getValue())));
                    active = 0;
                }
                active = Math.max(0, Math.min(active, sites.size() - 1));
                e.add(new Entry(d.getString("world").getValue(), d.getString("kind").getValue(), sites, active,
                        d.getBoolean("partner").getValue(), State.valueOf(d.getString("state").getValue()), false));
            }
            List<Point> p = readPoints(doc.getArray("portals"));
            List<Point> c = readPoints(doc.getArray("cells"));
            Map<String, UUID> w = new HashMap<>();
            if (doc.containsKey("worlds")) {
                for (var v : doc.getArray("worlds")) {
                    BsonDocument d = v.asDocument();
                    w.put(d.getString("world").getValue(), UUID.fromString(d.getString("uuid").getValue()));
                }
            }
            entries.addAll(e);
            portals.addAll(p);
            cells.addAll(c);
            worlds.putAll(w);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("generated-stations.json illisible, registre vide");
            Telemetry.error("worldgen", e, null, "load");
            entries.clear();
            portals.clear();
            cells.clear();
            worlds.clear();
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer generated-stations.json en .bad");
            }
        }
    }

    private static List<Point> readPoints(BsonArray arr) {
        List<Point> out = new ArrayList<>();
        for (var v : arr) {
            BsonDocument d = v.asDocument();
            out.add(new Point(d.getString("world").getValue(), d.getInt32("x").getValue(), d.getInt32("z").getValue()));
        }
        return out;
    }

    private static BsonArray writePoints(List<Point> points) {
        BsonArray arr = new BsonArray();
        for (Point p : points) {
            BsonDocument d = new BsonDocument();
            d.put("world", new BsonString(p.world));
            d.put("x", new BsonInt32(p.x));
            d.put("z", new BsonInt32(p.z));
            arr.add(d);
        }
        return arr;
    }

    /**
     * Binds a world name to its identity. A world stored under another identity was deleted and recreated: its entries, portals and
     * cells are dropped. A world without stored identity adopts the current one. Returns true when entries were dropped.
     */
    synchronized boolean adoptWorld(String world, UUID uuid) {
        if (uuid == null) return false;
        UUID stored = worlds.get(world);
        if (uuid.equals(stored)) return false;
        boolean dropped = false;
        if (stored != null) {
            int before = entries.size() + portals.size() + cells.size();
            entries.removeIf(e -> e.world.equals(world));
            portals.removeIf(p -> p.world.equals(world));
            cells.removeIf(p -> p.world.equals(world));
            dropped = true;
            LOGGER.at(Level.INFO).log("Stations générées : le monde %s a changé d'identité, %d élément(s) du registre abandonné(s)", world,
                    before - (entries.size() + portals.size() + cells.size()));
        }
        worlds.put(world, uuid);
        save();
        return dropped;
    }

    /** Adds a planned site in state PLANNED and saves. */
    synchronized Entry add(String world, WorldStationPlanner.PlannedSite site) {
        List<WorldStationPlanner.Site> sites = new ArrayList<>();
        sites.add(new WorldStationPlanner.Site(site.x(), site.z(), site.rotation()));
        sites.addAll(site.fallbacks());
        Entry e = new Entry(world, site.kind(), sites, 0, site.partner(), State.PLANNED, true);
        entries.add(e);
        save();
        return e;
    }

    /** Moves the entry to its next site and saves. False, with nothing changed, when no site is left. Callers run on the world thread. */
    synchronized boolean advance(Entry e) {
        if (e.active + 1 >= e.sites.size()) return false;
        WorldStationPlanner.Site s = e.sites.get(e.active + 1);
        e.x = s.x();
        e.z = s.z();
        e.rotation = s.rotation();
        e.active = e.active + 1;
        save();
        return true;
    }

    /**
     * Replaces the active site of an entry with the site its station was built at (a repaired site differs from the planned one) and
     * saves, so that every lookup by position uses the built site. Callers run on the world thread.
     */
    synchronized void setBuiltSite(Entry e, int x, int z, Rotation rotation) {
        List<WorldStationPlanner.Site> sites = new ArrayList<>(e.sites);
        sites.set(e.active, new WorldStationPlanner.Site(x, z, rotation));
        e.sites = List.copyOf(sites);
        e.x = x;
        e.z = z;
        e.rotation = rotation;
        save();
    }

    /** The active site of an entry, read as one consistent value (x, z and rotation are moved together by advance). */
    synchronized WorldStationPlanner.Site activeSite(Entry e) {
        return e.sites.get(e.active);
    }

    synchronized List<Entry> entries(String world) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.world.equals(world)) out.add(e);
        return out;
    }

    /** Entries still to build (state PLANNED). */
    synchronized List<Entry> pending(String world) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.world.equals(world) && e.state == State.PLANNED) out.add(e);
        return out;
    }

    synchronized void setState(Entry e, State s) {
        e.state = s;
        save();
    }

    /** The entry within 4 blocks (horizontally) of the position, or null. */
    synchronized Entry at(String world, int x, int z) {
        for (Entry e : entries) {
            if (e.world.equals(world) && Math.abs(e.x - x) <= AT_RADIUS && Math.abs(e.z - z) <= AT_RADIUS) return e;
        }
        return null;
    }

    /** Records a portal; false when one was already seen within 64 blocks. */
    synchronized boolean markPortal(String world, int x, int z) {
        for (Point p : portals) {
            if (!p.world.equals(world)) continue;
            long dx = p.x - x, dz = p.z - z;
            if (dx * dx + dz * dz < (long) PORTAL_RADIUS * PORTAL_RADIUS) return false;
        }
        portals.add(new Point(world, x, z));
        save();
        return true;
    }

    /** Records a sea cell as planned; false when it already was. */
    synchronized boolean markCell(String world, int cellX, int cellZ) {
        for (Point p : cells) if (p.world.equals(world) && p.x == cellX && p.z == cellZ) return false;
        cells.add(new Point(world, cellX, cellZ));
        save();
        return true;
    }

    /** True when the sea cell was recorded as planned. */
    synchronized boolean cellMarked(String world, int cellX, int cellZ) {
        for (Point p : cells) if (p.world.equals(world) && p.x == cellX && p.z == cellZ) return true;
        return false;
    }

    /** Forgets an entry, whatever its state, and saves. */
    synchronized void forget(Entry e) {
        if (entries.remove(e)) save();
    }

    /** Forgets that a sea cell was planned; false when it was not recorded. */
    synchronized boolean unmarkCell(String world, int cellX, int cellZ) {
        boolean removed = cells.removeIf(p -> p.world.equals(world) && p.x == cellX && p.z == cellZ);
        if (removed) save();
        return removed;
    }

    /** Forgets the portals inside the box (block coordinates, bounds included). Returns how many were forgotten. */
    synchronized int forgetPortals(String world, int minX, int minZ, int maxX, int maxZ) {
        int before = portals.size();
        portals.removeIf(p -> p.world.equals(world) && p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ);
        int n = before - portals.size();
        if (n > 0) save();
        return n;
    }

    /** Positions {x, z} of the entries of a kind that are PLANNED or BUILT. */
    synchronized List<int[]> positions(String world, String kind) {
        List<int[]> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.world.equals(world) && e.kind.equals(kind) && (e.state == State.PLANNED || e.state == State.BUILT)) {
                out.add(new int[]{e.x, e.z});
            }
        }
        return out;
    }

    private void save() {
        Path f = file;
        if (f == null) return;
        try {
            BsonArray arr = new BsonArray();
            for (Entry e : entries) {
                BsonDocument d = new BsonDocument();
                d.put("world", new BsonString(e.world));
                d.put("kind", new BsonString(e.kind));
                d.put("x", new BsonInt32(e.x));
                d.put("z", new BsonInt32(e.z));
                d.put("rotation", new BsonString(e.rotation.name()));
                BsonArray sites = new BsonArray();
                for (WorldStationPlanner.Site s : e.sites) {
                    BsonDocument sd = new BsonDocument();
                    sd.put("x", new BsonInt32(s.x()));
                    sd.put("z", new BsonInt32(s.z()));
                    sd.put("rotation", new BsonString(s.rotation().name()));
                    sites.add(sd);
                }
                d.put("sites", sites);
                d.put("active", new BsonInt32(e.active));
                d.put("partner", new org.bson.BsonBoolean(e.partner));
                d.put("state", new BsonString(e.state.name()));
                arr.add(d);
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(1));
            doc.put("entries", arr);
            doc.put("portals", writePoints(portals));
            doc.put("cells", writePoints(cells));
            BsonArray ws = new BsonArray();
            for (Map.Entry<String, UUID> w : worlds.entrySet()) {
                BsonDocument d = new BsonDocument();
                d.put("world", new BsonString(w.getKey()));
                d.put("uuid", new BsonString(w.getValue().toString()));
                ws.add(d);
            }
            doc.put("worlds", ws);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de generated-stations.json impossible");
            Telemetry.error("worldgen", e, null, "save");
        }
    }
}
