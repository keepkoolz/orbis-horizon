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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Per-player discovery album: the furniture found, in order, and the titles earned. One file per player,
 * discoveries/uuid.json in the data directory, cached while the player is connected and written off the world thread.
 */
final class DiscoveryStore {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int VERSION = 1;

    /** A piece of furniture found: item id and where it was examined. */
    record Entry(String itemId, int x, int y, int z, String world) {
    }

    /** Outcome of an add: whether the entry was new, whether it opened a family, the family whose title was just earned. */
    record AddResult(boolean added, boolean newFamily, String titleFamily) {
    }

    /** The album of one player. Every method is safe to call from any thread. */
    static final class Album {
        private final List<Entry> entries = new ArrayList<>();
        private final Set<String> titles = new LinkedHashSet<>();

        synchronized List<Entry> entries() {
            return List.copyOf(entries);
        }

        synchronized Set<String> titles() {
            return Set.copyOf(titles);
        }

        synchronized boolean has(String itemId) {
            return entry(itemId) != null;
        }

        synchronized Entry entry(String itemId) {
            for (Entry e : entries) {
                if (e.itemId().equals(itemId)) {
                    return e;
                }
            }
            return null;
        }

        /** The families started, in the order of their first entry. Entries of no family are ignored. */
        synchronized List<String> familyOrder() {
            Set<String> out = new LinkedHashSet<>();
            for (Entry e : entries) {
                FurnitureDiscovery.Family f = FurnitureDiscovery.familyOf(e.itemId());
                if (f != null) {
                    out.add(f.id());
                }
            }
            return new ArrayList<>(out);
        }

        /** How many items of the family are in the album. */
        synchronized int found(String familyId) {
            int n = 0;
            for (Entry e : entries) {
                FurnitureDiscovery.Family f = FurnitureDiscovery.familyOf(e.itemId());
                if (f != null && f.id().equals(familyId)) {
                    n++;
                }
            }
            return n;
        }

        /** Adds an entry unless its item is already there. Gives the title when the family becomes complete. */
        synchronized AddResult add(Entry e) {
            if (e == null || has(e.itemId())) {
                return new AddResult(false, false, null);
            }
            FurnitureDiscovery.Family family = FurnitureDiscovery.familyOf(e.itemId());
            boolean newFamily = family != null && found(family.id()) == 0;
            entries.add(e);
            String title = null;
            if (family != null && !titles.contains(family.id()) && found(family.id()) == family.items().size()) {
                titles.add(family.id());
                title = family.id();
            }
            return new AddResult(true, newFamily, title);
        }

        synchronized void addTitle(String familyId) {
            titles.add(familyId);
        }
    }

    private static final ConcurrentHashMap<UUID, Album> CACHE = new ConcurrentHashMap<>();
    /** A single writer, so writes reach the disk in the order they were queued and never overwrite a newer state. */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orbis-horizon-discovery-writer");
        t.setDaemon(true);
        return t;
    });
    private static volatile Path directory;

    private DiscoveryStore() {
    }

    static void init(Path dataDirectory) {
        directory = dataDirectory.resolve("discoveries");
        CACHE.clear();
    }

    private static Path fileOf(UUID player) {
        Path dir = directory;
        return dir == null ? null : dir.resolve(player + ".json");
    }

    /** The album of the player, read from the disk on first use. */
    static Album get(UUID player) {
        Album cached = CACHE.get(player);
        if (cached != null) {
            return cached;
        }
        Path file = fileOf(player);
        Album read = file == null ? new Album() : read(file);
        Album raced = CACHE.putIfAbsent(player, read);
        return raced != null ? raced : read;
    }

    /** Adds the entry to the album and, when it is new, writes the file off the world thread. */
    static AddResult record(UUID player, Entry e) {
        Album album = get(player);
        AddResult result = album.add(e);
        Path file = fileOf(player);
        if (result.added() && file != null) {
            CompletableFuture.runAsync(() -> write(file, album), WRITER);
        }
        return result;
    }

    /** Drops the cached album once every write queued before this call has reached the disk. */
    static void forget(UUID player) {
        try {
            WRITER.execute(() -> CACHE.remove(player));
        } catch (RuntimeException e) {
            CACHE.remove(player);
        }
    }

    /** Waits, briefly, for the queued writes to finish. Never throws and leaves the writer running. */
    static void flush() {
        try {
            CompletableFuture.runAsync(() -> { }, WRITER).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.at(Level.WARNING).log("Discovery flush interrupted");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Discovery flush incomplete");
        }
    }

    /** Reads an album. A missing file gives an empty album, an unreadable one is set aside as .bad. */
    static Album read(Path file) {
        Album album = new Album();
        if (!Files.exists(file)) {
            return album;
        }
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (BsonValue v : doc.getArray("entries")) {
                BsonDocument d = v.asDocument();
                album.add(new Entry(d.getString("item").getValue(), d.getNumber("x").intValue(),
                        d.getNumber("y").intValue(), d.getNumber("z").intValue(), d.getString("world").getValue()));
            }
            for (BsonValue v : doc.getArray("titles")) {
                album.addTitle(v.asString().getValue());
            }
            return album;
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Discovery album unreadable: %s", file);
            Telemetry.error("discovery", e, "store", "read");
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The file stays in place.
            }
            return new Album();
        }
    }

    /** Writes a snapshot of the album taken under its lock (temporary file then atomic rename). */
    static void write(Path file, Album album) {
        try {
            BsonArray entries = new BsonArray();
            BsonArray titles = new BsonArray();
            synchronized (album) {
                for (Entry e : album.entries) {
                    BsonDocument d = new BsonDocument();
                    d.put("item", new BsonString(e.itemId()));
                    d.put("x", new BsonInt32(e.x()));
                    d.put("y", new BsonInt32(e.y()));
                    d.put("z", new BsonInt32(e.z()));
                    d.put("world", new BsonString(e.world()));
                    entries.add(d);
                }
                for (String t : album.titles) {
                    titles.add(new BsonString(t));
                }
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("entries", entries);
            doc.put("titles", titles);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Discovery album not written: %s", file);
            Telemetry.error("discovery", e, "store", "write");
        }
    }
}
