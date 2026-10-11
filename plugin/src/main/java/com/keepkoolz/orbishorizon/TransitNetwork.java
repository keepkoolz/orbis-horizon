package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/** Registry of transit stations, persisted in stations.json in the plugin data folder. */
final class TransitNetwork {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final TransitNetwork INSTANCE = new TransitNetwork();

    enum NameCheck { OK, TOO_SHORT, TOO_LONG, TAKEN }

    static final class Station {
        final String name;
        final String world;
        final Vector3i origin;
        final Rotation rotation;
        /** Station master NPC; set after the station is registered (setMaster saves). Volatile: read on world threads. */
        volatile UUID masterUuid;
        final UUID owner;
        final List<String> queue;

        Station(String name, String world, Vector3i origin, Rotation rotation, UUID masterUuid, UUID owner, List<String> queue) {
            this.name = name;
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.masterUuid = masterUuid;
            this.owner = owner;
            this.queue = queue;
        }
    }

    private final List<Station> stations = new CopyOnWriteArrayList<>();
    private volatile Path file;

    static TransitNetwork get() { return INSTANCE; }

    static String normalize(String raw) { return raw == null ? "" : raw.trim(); }

    static NameCheck checkName(String raw, Collection<String> taken) {
        String n = normalize(raw);
        if (n.length() < TransitConstants.NAME_MIN) return NameCheck.TOO_SHORT;
        if (n.length() > TransitConstants.NAME_MAX) return NameCheck.TOO_LONG;
        for (String t : taken) {
            if (t.trim().equalsIgnoreCase(n)) return NameCheck.TAKEN;
        }
        return NameCheck.OK;
    }

    static String autoName(Collection<String> taken) {
        for (int i = 1; ; i++) {
            String candidate = "Station " + i;
            boolean used = false;
            for (String t : taken) {
                if (t.trim().equalsIgnoreCase(candidate)) { used = true; break; }
            }
            if (!used) return candidate;
        }
    }

    List<Station> stations(String world) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations) if (s.world.equals(world)) out.add(s);
        return out;
    }

    /** Every registered station, all worlds (suggestions of /orbishorizon station tp). */
    List<Station> allStations() {
        return new ArrayList<>(stations);
    }

    Station byName(String world, String name) {
        String n = normalize(name);
        for (Station s : stations) {
            if (s.world.equals(world) && s.name.equalsIgnoreCase(n)) return s;
        }
        return null;
    }

    Station at(String world, Vector3i origin) {
        for (Station s : stations) {
            if (s.world.equals(world) && s.origin.equals(origin)) return s;
        }
        return null;
    }

    Station nearest(String world, Vector3d pos, double maxDist) {
        Station best = null;
        double bestD = maxDist * maxDist;
        for (Station s : stations) {
            if (!s.world.equals(world)) continue;
            double d = pos.distanceSquared(s.origin.x, s.origin.y, s.origin.z);
            if (d <= bestD) { bestD = d; best = s; }
        }
        return best;
    }

    synchronized void add(Station s) { stations.add(s); }

    synchronized void remove(Station s) { stations.remove(s); }

    /** Sets the station master's UUID (null = none) and saves. The Station object keeps its identity (reservations key on it). */
    synchronized void setMaster(Station s, UUID master) {
        s.masterUuid = master;
        save();
    }

    synchronized void load(Path dataDir) {
        file = dataDir.resolve("stations.json");
        stations.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (var v : doc.getArray("stations")) {
                BsonDocument d = v.asDocument();
                List<String> queue = new ArrayList<>();
                for (var q : d.getArray("queue")) queue.add(q.asString().getValue());
                stations.add(new Station(d.getString("name").getValue(), d.getString("world").getValue(),
                        new Vector3i(d.getInt32("x").getValue(), d.getInt32("y").getValue(), d.getInt32("z").getValue()),
                        Rotation.valueOf(d.getString("rotation").getValue()),
                        d.containsKey("master") ? UUID.fromString(d.getString("master").getValue()) : null,
                        d.containsKey("owner") ? UUID.fromString(d.getString("owner").getValue()) : null,
                        queue));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("stations.json illisible, réseau vide");
            stations.clear();
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer stations.json en .bad");
            }
        }
    }

    /** Queue mutations go through these synchronized methods so save() sees a consistent list. Every change is saved. */
    synchronized boolean queueAdd(Station s, String destination) {
        if (s.queue.size() >= TransitConstants.MAX_QUEUE) return false;
        s.queue.add(destination);
        save();
        return true;
    }

    /** Applies the queue rules (StationQueue.addTo) to the station's queue and saves when something was added. */
    synchronized StationQueue.EnqueueResult queueEnqueue(Station s, String destination) {
        StationQueue.EnqueueResult r = StationQueue.addTo(s.queue, s.name, destination);
        if (r == StationQueue.EnqueueResult.ADDED) save();
        return r;
    }

    synchronized String queuePoll(Station s) {
        if (s.queue.isEmpty()) return null;
        String head = s.queue.remove(0);
        save();
        return head;
    }

    /** Removes the first occurrence of this destination (departure of the balloon that was heading there). */
    synchronized void queueRemoveFirst(Station s, String destination) {
        for (int i = 0; i < s.queue.size(); i++) {
            if (s.queue.get(i).equalsIgnoreCase(destination)) {
                s.queue.remove(i);
                save();
                return;
            }
        }
    }

    synchronized void queueRemoveAll(Station s, String destination) {
        if (s.queue.removeIf(n -> n.equalsIgnoreCase(destination))) save();
    }

    /** Head of the queue, null if empty. */
    synchronized String queueHead(Station s) {
        return s.queue.isEmpty() ? null : s.queue.get(0);
    }

    synchronized void save() {
        Path f = file;
        if (f == null) return;
        try {
            BsonArray arr = new BsonArray();
            for (Station s : stations) {
                BsonDocument d = new BsonDocument();
                d.put("name", new BsonString(s.name));
                d.put("world", new BsonString(s.world));
                d.put("x", new BsonInt32(s.origin.x));
                d.put("y", new BsonInt32(s.origin.y));
                d.put("z", new BsonInt32(s.origin.z));
                d.put("rotation", new BsonString(s.rotation.name()));
                if (s.masterUuid != null) d.put("master", new BsonString(s.masterUuid.toString()));
                if (s.owner != null) d.put("owner", new BsonString(s.owner.toString()));
                BsonArray q = new BsonArray();
                for (String n : s.queue) q.add(new BsonString(n));
                d.put("queue", q);
                arr.add(d);
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(1));
            doc.put("stations", arr);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de stations.json impossible");
        }
    }
}
