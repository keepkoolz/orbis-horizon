package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
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
import java.util.Set;
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
        /** Name of the station. Changes once, when a station that just received its quay is named (rename). */
        volatile String name;
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

        /** Cell of the station master. Volatile: read on world threads. */
        volatile Vector3i master;
        /** Yaw of the station master, in degrees. */
        volatile float masterYaw;
        /** Quay anchor (null without a quay). */
        volatile Vector3i quay;
        volatile Rotation quayRotation;
        /** Cell where passengers arrive on a maritime station (null = not set). */
        volatile Vector3i arrival;
        /** True when the owner may edit the station's structure. */
        volatile boolean editable;
        /** True when origin and rotation designate a whole prefab of the registry (generated and converted stations). */
        final boolean fullPrefab;
        /**
         * True while the name window of the station is open: the station has its quay but stays out of the destinations, queues, map
         * and discovery until the name is validated or the window expires. In memory only, false after a restart.
         */
        volatile boolean naming;

        /** True when the station is part of the network: it has a quay and its name window is not open. */
        boolean inNetwork() {
            return quay != null && !naming;
        }

        Station(String name, String world, Vector3i origin, Rotation rotation, UUID masterUuid, UUID owner, List<String> queue, String kind) {
            this(name, world, origin, rotation, masterUuid, owner, queue, kind, true, owner != null,
                    StationGeometry.convert(kind, origin, rotation));
        }

        Station(String name, String world, Vector3i origin, Rotation rotation, UUID masterUuid, UUID owner, List<String> queue, String kind,
                boolean fullPrefab, boolean editable, StationGeometry.Converted geometry) {
            this(name, world, origin, rotation, masterUuid, owner, queue, kind, fullPrefab, editable,
                    geometry.master(), geometry.masterYaw(), geometry.quay(), geometry.quayRotation());
        }

        Station(String name, String world, Vector3i origin, Rotation rotation, UUID masterUuid, UUID owner, List<String> queue, String kind,
                boolean fullPrefab, boolean editable, Vector3i master, float masterYaw, Vector3i quay, Rotation quayRotation) {
            this.fullPrefab = fullPrefab;
            this.editable = editable;
            this.master = master;
            this.masterYaw = masterYaw;
            this.quay = quay;
            this.quayRotation = quayRotation;
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
    /** Names of the worlds whose first station contract was given (key firstContractGiven of stations.json). */
    private final Set<String> firstContractGiven = new java.util.TreeSet<>();

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

    /** Stations of this world that have the given kind (Deployables.STATION_KIND or BOAT_STATION_KIND) and are in the network (a quay, no name window open). */
    List<Station> stations(String world, String kind) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations) if (s.world.equals(world) && s.kind.equals(kind) && s.inNetwork()) out.add(s);
        return out;
    }

    /** Stations of this world that have no quay yet. */
    List<Station> pending(String world) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations) if (s.world.equals(world) && s.quay == null) out.add(s);
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

    /** Same as byName, but a station outside the network (pending, or its name window open) is not returned: the one to use for every flight or queue destination. */
    Station byNameWithQuay(String world, String name) {
        Station s = byName(world, name);
        return s != null && s.inNetwork() ? s : null;
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
            double d = pos.distanceSquared(StationGeometry.position(s));
            if (d <= bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /** The worlds whose first station contract was given, as a copy. */
    synchronized Set<String> firstContractWorlds() { return new java.util.TreeSet<>(firstContractGiven); }

    /** Notes (or forgets) that the first contract of a world was given and saves. True when the note changed. */
    synchronized boolean setFirstContract(String world, boolean given) {
        boolean changed = given ? firstContractGiven.add(world) : firstContractGiven.remove(world);
        if (changed) save();
        return changed;
    }

    synchronized void add(Station s) { stations.add(s); }

    synchronized void remove(Station s) { stations.remove(s); }

    /**
     * Registers a station that has only its master so far (no quay): origin = master cell, not a whole prefab, editable, the
     * automatic name of the world, saved. The quay is added later by the owner. Returns the new station.
     */
    synchronized Station addPending(String world, Vector3i master, float masterYaw, UUID owner, String kind) {
        List<String> taken = new ArrayList<>();
        for (Station o : stations) if (o.world.equals(world)) taken.add(o.name);
        Station s = new Station(autoName(taken), world, new Vector3i(master), Rotation.None, null, owner, new ArrayList<>(), kind,
                false, true, new Vector3i(master), masterYaw, null, null);
        stations.add(s);
        save();
        return s;
    }

    /**
     * Gives a pending station its quay: the quay anchor and rotation are set, the station is saved and from then on part of the
     * network (stations(world, kind), the map, the discovery and the queues only read stations with a quay). Returns false when the
     * station already has a quay.
     */
    synchronized boolean setQuay(Station s, Vector3i quay, Rotation rotation) {
        if (s.quay != null) {
            return false;
        }
        s.quayRotation = rotation;
        s.quay = quay;
        save();
        return true;
    }

    /** Same, and moves the station master to another cell and yaw (a whole station: the master stands in its hut). */
    synchronized boolean setQuay(Station s, Vector3i quay, Rotation rotation, Vector3i master, float masterYaw) {
        if (s.quay != null) {
            return false;
        }
        s.master = master;
        s.masterYaw = masterYaw;
        return setQuay(s, quay, rotation);
    }

    /** Renames a station when the name is valid and free in its world, saves. True when the station now has that name. */
    synchronized boolean rename(Station s, String name) {
        List<String> taken = new ArrayList<>();
        for (Station o : stations) if (o.world.equals(s.world) && o != s) taken.add(o.name);
        if (checkName(name, taken) != NameCheck.OK) {
            return false;
        }
        s.name = normalize(name);
        save();
        return true;
    }

    /** Sets the station master's UUID (null = none) and saves. The Station object keeps its identity (reservations key on it). */
    synchronized void setMaster(Station s, UUID master) {
        s.masterUuid = master;
        save();
    }

    /** Sets the arrival cell of a station (copied) and saves. */
    synchronized void setArrival(Station s, Vector3i cell) {
        s.arrival = new Vector3i(cell.x, cell.y, cell.z);
        save();
    }

    /** True once stations.json was unreadable at a load (the file left as .bad). Never reset during the session. */
    volatile boolean loadFailed;
    /** True while the file read is a version 1 file that was not yet rewritten: the first save copies it to .v1.bak. */
    private boolean v1Pending;

    private static Vector3i readCell(BsonDocument d) {
        return new Vector3i(d.getInt32("x").getValue(), d.getInt32("y").getValue(), d.getInt32("z").getValue());
    }

    private static BsonDocument cellDoc(Vector3i v) {
        BsonDocument d = new BsonDocument();
        d.put("x", new BsonInt32(v.x));
        d.put("y", new BsonInt32(v.y));
        d.put("z", new BsonInt32(v.z));
        return d;
    }

    synchronized void load(Path dataDir) {
        file = dataDir.resolve("stations.json");
        stations.clear();
        firstContractGiven.clear();
        v1Pending = false;
        if (!Files.isRegularFile(file)) return;
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            boolean v2 = doc.containsKey("version") && doc.get("version").isNumber() && doc.get("version").asNumber().intValue() >= 2;
            v1Pending = !v2;
            if (doc.containsKey("firstContractGiven") && doc.get("firstContractGiven").isArray()) {
                for (var w : doc.getArray("firstContractGiven")) {
                    if (w.isString()) firstContractGiven.add(w.asString().getValue());
                }
            }
            for (var v : doc.getArray("stations")) {
                BsonDocument d = v.asDocument();
                List<String> queue = new ArrayList<>();
                for (var q : d.getArray("queue")) queue.add(q.asString().getValue());
                String kind = d.containsKey("kind") ? d.getString("kind").getValue() : Deployables.STATION_KIND;
                Vector3i origin = readCell(d);
                Rotation rotation = Rotation.valueOf(d.getString("rotation").getValue());
                // version 1 stored the NPC identity under "master"; in version 2 that key holds the master cell
                String uuidKey = v2 ? "masterUuid" : "master";
                UUID masterUuid = d.containsKey(uuidKey) ? UUID.fromString(d.getString(uuidKey).getValue()) : null;
                UUID owner = d.containsKey("owner") ? UUID.fromString(d.getString("owner").getValue()) : null;
                String name = d.getString("name").getValue();
                String world = d.getString("world").getValue();
                if (v2 && d.containsKey("master") && d.get("master").isDocument()) {
                    Vector3i quay = d.containsKey("quay") ? readCell(d.getDocument("quay")) : null;
                    stations.add(new Station(name, world, origin, rotation, masterUuid, owner, queue, kind,
                            d.containsKey("fullPrefab") && d.getBoolean("fullPrefab").getValue(),
                            d.containsKey("editable") && d.getBoolean("editable").getValue(),
                            readCell(d.getDocument("master")),
                            d.containsKey("masterYaw") ? (float) d.getNumber("masterYaw").doubleValue() : 0f,
                            quay,
                            d.containsKey("quayRotation") ? Rotation.valueOf(d.getString("quayRotation").getValue()) : rotation));
                } else {
                    // Version 1, or a version 2 entry without a master cell: the geometry comes from the whole prefab.
                    if (v2) {
                        LOGGER.at(Level.WARNING).log("Station '%s' has no master cell in stations.json, geometry converted from its prefab", name);
                    }
                    stations.add(new Station(name, world, origin, rotation, masterUuid, owner, queue, kind));
                }
                if (v2 && d.containsKey("master") && d.get("master").isDocument()
                        && d.containsKey("arrival") && d.get("arrival").isDocument()) {
                    stations.get(stations.size() - 1).arrival = readCell(d.getDocument("arrival"));
                }
                loadPayments(stations.get(stations.size() - 1), d);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("stations.json unreadable, network empty");
            loadFailed = true;
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
                if (s.masterUuid != null) d.put("masterUuid", new BsonString(s.masterUuid.toString()));
                d.put("master", cellDoc(s.master));
                d.put("masterYaw", new BsonDouble(s.masterYaw));
                if (s.quay != null) d.put("quay", cellDoc(s.quay));
                if (s.arrival != null) d.put("arrival", cellDoc(s.arrival));
                if (s.quayRotation != null) d.put("quayRotation", new BsonString(s.quayRotation.name()));
                d.put("editable", new BsonBoolean(s.editable));
                d.put("fullPrefab", new BsonBoolean(s.fullPrefab));
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
            doc.put("version", new BsonInt32(2));
            doc.put("stations", arr);
            BsonArray first = new BsonArray();
            for (String w : firstContractGiven) first.add(new BsonString(w));
            doc.put("firstContractGiven", first);
            Files.createDirectories(f.getParent());
            if (v1Pending) {
                if (Files.isRegularFile(f)) Files.copy(f, f.resolveSibling(f.getFileName() + ".v1.bak"), StandardCopyOption.REPLACE_EXISTING);
                v1Pending = false;
            }
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
