package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Stations known to each player, per world, and stations shared with every player of a world. A station is identified by its
 * origin, like the entries of the sky log. A station is known to a player when he discovered it himself or when it is shared.
 * Kept in known-stations.json in the plugin data directory.
 */
final class KnownStations {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final KnownStations INSTANCE = new KnownStations();

    /** Stations known personally, by world then player. */
    private final Map<String, Map<UUID, Set<SkyLog.Reached>>> personal = new HashMap<>();
    /** Stations known to everyone, by world. */
    private final Map<String, Set<SkyLog.Reached>> shared = new HashMap<>();
    private volatile Path file;

    static KnownStations get() {
        return INSTANCE;
    }

    // ---- pure core (tested) ----

    /** True if the player knows the station: discovered by him or shared. */
    synchronized boolean known(String world, UUID player, SkyLog.Reached station) {
        return known(world, player, station, TransitSettings.markerSharing());
    }

    /** Pure form of known: shared stations count only when includeShared is true (sharing switched on). Nothing stored is changed. */
    synchronized boolean known(String world, UUID player, SkyLog.Reached station, boolean includeShared) {
        Set<SkyLog.Reached> s = shared.get(world);
        if (includeShared && s != null && s.contains(station)) {
            return true;
        }
        Map<UUID, Set<SkyLog.Reached>> w = personal.get(world);
        Set<SkyLog.Reached> p = w == null ? null : w.get(player);
        return p != null && p.contains(station);
    }

    /** True if the station is shared with every player of the world. */
    synchronized boolean isShared(String world, SkyLog.Reached station) {
        Set<SkyLog.Reached> s = shared.get(world);
        return s != null && s.contains(station);
    }

    /** True if the player knew the station personally (shared ones do not count). */
    synchronized boolean knownPersonally(String world, UUID player, SkyLog.Reached station) {
        Map<UUID, Set<SkyLog.Reached>> w = personal.get(world);
        Set<SkyLog.Reached> p = w == null ? null : w.get(player);
        return p != null && p.contains(station);
    }

    /** Adds the station to the player's own list. True only the first time. */
    synchronized boolean add(String world, UUID player, SkyLog.Reached station) {
        return personal.computeIfAbsent(world, k -> new HashMap<>()).computeIfAbsent(player, k -> new HashSet<>()).add(station);
    }

    /** Shares the station with every player of the world. True only the first time. */
    synchronized boolean share(String world, SkyLog.Reached station) {
        return shared.computeIfAbsent(world, k -> new HashSet<>()).add(station);
    }

    synchronized BsonDocument toBson() {
        BsonArray players = new BsonArray();
        for (Map.Entry<String, Map<UUID, Set<SkyLog.Reached>>> w : personal.entrySet()) {
            for (Map.Entry<UUID, Set<SkyLog.Reached>> p : w.getValue().entrySet()) {
                BsonDocument d = new BsonDocument();
                d.put("world", new BsonString(w.getKey()));
                d.put("player", new BsonString(p.getKey().toString()));
                d.put("stations", stationsToBson(p.getValue()));
                players.add(d);
            }
        }
        BsonArray sharedArr = new BsonArray();
        for (Map.Entry<String, Set<SkyLog.Reached>> w : shared.entrySet()) {
            BsonDocument d = new BsonDocument();
            d.put("world", new BsonString(w.getKey()));
            d.put("stations", stationsToBson(w.getValue()));
            sharedArr.add(d);
        }
        BsonDocument doc = new BsonDocument();
        doc.put("v", new BsonInt32(1));
        doc.put("players", players);
        doc.put("shared", sharedArr);
        return doc;
    }

    private static BsonArray stationsToBson(Set<SkyLog.Reached> set) {
        BsonArray arr = new BsonArray();
        for (SkyLog.Reached r : set) {
            BsonArray a = new BsonArray();
            a.add(new BsonInt32(r.x()));
            a.add(new BsonInt32(r.y()));
            a.add(new BsonInt32(r.z()));
            arr.add(a);
        }
        return arr;
    }

    /** Replaces the content with the document's. A malformed entry is skipped, the others are kept. */
    synchronized void fromBson(BsonDocument doc) {
        personal.clear();
        shared.clear();
        if (doc == null) {
            return;
        }
        if (doc.isArray("players")) {
            for (BsonValue v : doc.getArray("players")) {
                try {
                    BsonDocument d = v.asDocument();
                    String world = d.getString("world").getValue();
                    UUID player = UUID.fromString(d.getString("player").getValue());
                    personal.computeIfAbsent(world, k -> new HashMap<>()).computeIfAbsent(player, k -> new HashSet<>())
                            .addAll(stationsFromBson(d.getArray("stations")));
                } catch (RuntimeException e) {
                    // skipped
                }
            }
        }
        if (doc.isArray("shared")) {
            for (BsonValue v : doc.getArray("shared")) {
                try {
                    BsonDocument d = v.asDocument();
                    shared.computeIfAbsent(d.getString("world").getValue(), k -> new HashSet<>())
                            .addAll(stationsFromBson(d.getArray("stations")));
                } catch (RuntimeException e) {
                    // skipped
                }
            }
        }
    }

    private static Set<SkyLog.Reached> stationsFromBson(BsonArray arr) {
        Set<SkyLog.Reached> out = new HashSet<>();
        for (BsonValue v : arr) {
            try {
                BsonArray a = v.asArray();
                out.add(new SkyLog.Reached(a.get(0).asInt32().getValue(), a.get(1).asInt32().getValue(), a.get(2).asInt32().getValue()));
            } catch (RuntimeException e) {
                // skipped
            }
        }
        return out;
    }

    // ---- file ----

    /** Reads known-stations.json. Missing: empty. Unreadable: empty, the file is renamed .bad. Never throws. */
    synchronized void load(Path dataDir) {
        personal.clear();
        shared.clear();
        try {
            file = dataDir.resolve("known-stations.json");
            if (!Files.isRegularFile(file)) {
                return;
            }
            fromBson(BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("known-stations.json illisible, stations connues oubliées");
            personal.clear();
            shared.clear();
            try {
                if (file != null && Files.isRegularFile(file)) {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer known-stations.json en .bad");
            }
        }
    }

    static void init(Path dataDir) {
        INSTANCE.load(dataDir);
    }

    /** Atomic write (tmp file then rename). Never throws. */
    synchronized void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, toBson().toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de known-stations.json impossible");
        }
    }

    /** Adds the station to the player's list and saves. True only the first time. */
    static boolean learn(String world, UUID player, TransitNetwork.Station s) {
        boolean added = INSTANCE.add(world, player, new SkyLog.Reached(s.origin.x, s.origin.y, s.origin.z));
        if (added) {
            INSTANCE.save();
        }
        return added;
    }
}
