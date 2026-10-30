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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Registry of transit stations, persisted in stations.json in the plugin data folder. */
final class TransitNetwork {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Source of queue head identities, unique across stations. Declared before INSTANCE so it exists before any Station. */
    private static final AtomicLong HEAD_IDS = new AtomicLong();
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
        /** Ticket paid for each queued destination, key = lower-case normalised destination name. Guarded by the network lock. */
        final Map<String, TransitFare.Payment> payments = new HashMap<>();
        /** Deployables.STATION_KIND (balloon station) or Deployables.BOAT_STATION_KIND. */
        final String kind;
        /** Ocean number of the berth in the sea chart of the world, 0 while unknown or when the berth is not on water. Volatile: set by the chart thread's result on the world thread. */
        volatile int oceanId;
        /** Identity of the entry at the head of the queue: a new value each time the head entry leaves. In memory only. Guarded by the network lock. */
        long headId = HEAD_IDS.incrementAndGet();

        Station(String name, String world, Vector3i origin, Rotation rotation, UUID masterUuid, UUID owner, List<String> queue, String kind) {
            this.name = name;
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.masterUuid = masterUuid;
            this.owner = owner;
            this.queue = queue;
            this.kind = kind;
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

    /** Stations of this world that have the given kind (Deployables.STATION_KIND or BOAT_STATION_KIND). */
    List<Station> stations(String world, String kind) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations) if (s.world.equals(world) && s.kind.equals(kind)) out.add(s);
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

    /** The station of this world whose origin has these x and z (any height), null if none. */
    Station atColumn(String world, int x, int z) {
        for (Station s : stations) {
            if (s.world.equals(world) && s.origin.x == x && s.origin.z == z) return s;
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
                        queue, d.containsKey("kind") ? d.getString("kind").getValue() : Deployables.STATION_KIND));
                loadPayments(stations.get(stations.size() - 1), d);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("stations.json unreadable, network empty");
            stations.clear();
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Cannot rename stations.json to .bad");
            }
        }
    }

    private static String payKey(String destination) { return normalize(destination).toLowerCase(java.util.Locale.ROOT); }

    /** Reads the optional "payments" array; a malformed entry or one whose destination left the queue is ignored. */
    private static void loadPayments(Station s, BsonDocument d) {
        if (!d.containsKey("payments")) return;
        for (var v : d.getArray("payments")) {
            try {
                BsonDocument p = v.asDocument();
                String key = payKey(p.getString("dest").getValue());
                boolean queued = false;
                for (String q : s.queue) if (payKey(q).equals(key)) { queued = true; break; }
                if (!queued) continue;
                String item = p.getString("item").getValue();
                int qty = p.getInt32("qty").getValue();
                if (item.isEmpty() || qty <= 0) throw new IllegalArgumentException("invalid payment");
                s.payments.put(key, new TransitFare.Payment(UUID.fromString(p.getString("payer").getValue()), item, qty));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Unreadable payment in stations.json, ignored");
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
        return queueEnqueue(s, destination, null);
    }

    /** Same, and records the ticket paid for the destination when it was added. */
    synchronized StationQueue.EnqueueResult queueEnqueue(Station s, String destination, TransitFare.Payment payment) {
        StationQueue.EnqueueResult r = StationQueue.addTo(s.queue, s.name, destination);
        if (r == StationQueue.EnqueueResult.ADDED) {
            if (payment != null) s.payments.put(payKey(destination), payment);
            save();
        }
        return r;
    }

    synchronized String queuePoll(Station s) {
        if (s.queue.isEmpty()) return null;
        String head = s.queue.remove(0);
        s.headId = HEAD_IDS.incrementAndGet();
        if (!queueHas(s, head)) s.payments.remove(payKey(head));
        save();
        return head;
    }

    private static boolean queueHas(Station s, String destination) {
        for (String q : s.queue) if (q.equalsIgnoreCase(destination)) return true;
        return false;
    }

    /** Removes the first occurrence of this destination (departure of the balloon that was heading there). Returns its payment, or null. */
    synchronized TransitFare.Payment queueRemoveFirst(Station s, String destination) {
        for (int i = 0; i < s.queue.size(); i++) {
            if (s.queue.get(i).equalsIgnoreCase(destination)) {
                s.queue.remove(i);
                if (i == 0) s.headId = HEAD_IDS.incrementAndGet();
                TransitFare.Payment p = s.payments.remove(payKey(destination));
                save();
                return p;
            }
        }
        return null;
    }

    /** Removes every occurrence of this destination and returns the payments taken off (empty if none). */
    synchronized List<TransitFare.Payment> queueRemoveAll(Station s, String destination) {
        List<TransitFare.Payment> out = new ArrayList<>();
        boolean headLeaves = !s.queue.isEmpty() && s.queue.get(0).equalsIgnoreCase(destination);
        if (s.queue.removeIf(n -> n.equalsIgnoreCase(destination))) {
            if (headLeaves) s.headId = HEAD_IDS.incrementAndGet();
            TransitFare.Payment p = s.payments.remove(payKey(destination));
            if (p != null) out.add(p);
            save();
        }
        return out;
    }

    /** Empties and returns the payments table (destination to payment), for a station being removed. */
    synchronized Map<String, TransitFare.Payment> takeAllPayments(Station s) {
        Map<String, TransitFare.Payment> out = new HashMap<>(s.payments);
        s.payments.clear();
        if (!out.isEmpty()) save();
        return out;
    }

    /** Head of the queue, null if empty. */
    synchronized String queueHead(Station s) {
        return s.queue.isEmpty() ? null : s.queue.get(0);
    }

    /** Identity of the current head entry: unchanged while it stays at the head, new once it has left (even if the same destination is queued again). */
    synchronized long queueHeadId(Station s) {
        return s.headId;
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
                d.put("kind", new BsonString(s.kind));
                BsonArray q = new BsonArray();
                for (String n : s.queue) q.add(new BsonString(n));
                d.put("queue", q);
                if (!s.payments.isEmpty()) {
                    BsonArray pa = new BsonArray();
                    for (var e : s.payments.entrySet()) {
                        BsonDocument p = new BsonDocument();
                        p.put("dest", new BsonString(e.getKey()));
                        p.put("payer", new BsonString(e.getValue().payer().toString()));
                        p.put("item", new BsonString(e.getValue().itemId()));
                        p.put("qty", new BsonInt32(e.getValue().quantity()));
                        pa.add(p);
                    }
                    d.put("payments", pa);
                }
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
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot write stations.json");
        }
    }
}
