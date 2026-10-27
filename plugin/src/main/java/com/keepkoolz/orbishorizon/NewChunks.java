package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Chunks generated after the mod was installed, one map per world. Works in chunk coordinates only.
 * Chunks are stored in regions of 32 x 32 chunks, one bit per chunk. Every method is thread-safe because
 * the chunk event, the flush timer and the shutdown hook run on different threads.
 */
final class NewChunks {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int FORMAT_VERSION = 2;
    /** Files of the first format carry no world identity. */
    private static final int FORMAT_WITHOUT_UUID = 1;
    private static final int REGION_SHIFT = 5;
    private static final int REGION_MASK = 31;
    private static final int LONGS_PER_REGION = 16;

    private static final Map<String, NewChunks> REGISTRY = new HashMap<>();
    private static Path dataDir;

    private final Map<Long, long[]> regions = new HashMap<>();
    private boolean dirty;
    /** Identity of the world the map belongs to, null when unknown. */
    private UUID world;

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xffffffffL);
    }

    private static int bitIndex(int chunkX, int chunkZ) {
        return ((chunkZ & REGION_MASK) << REGION_SHIFT) | (chunkX & REGION_MASK);
    }

    synchronized void mark(int chunkX, int chunkZ) {
        long[] bits = regions.computeIfAbsent(regionKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT), k -> new long[LONGS_PER_REGION]);
        int i = bitIndex(chunkX, chunkZ);
        long mask = 1L << (i & 63);
        if ((bits[i >> 6] & mask) == 0) {
            bits[i >> 6] |= mask;
            dirty = true;
        }
    }

    synchronized boolean isNew(int chunkX, int chunkZ) {
        long[] bits = regions.get(regionKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT));
        if (bits == null) return false;
        int i = bitIndex(chunkX, chunkZ);
        return (bits[i >> 6] & (1L << (i & 63))) != 0;
    }

    synchronized boolean dirty() {
        return dirty;
    }

    /** Writes through a temporary file then moves it over the target. Clears the dirty flag once written. */
    synchronized void write(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(FORMAT_VERSION);
            out.writeBoolean(world != null);
            if (world != null) {
                out.writeLong(world.getMostSignificantBits());
                out.writeLong(world.getLeastSignificantBits());
            }
            out.writeInt(regions.size());
            for (Map.Entry<Long, long[]> e : regions.entrySet()) {
                long key = e.getKey();
                out.writeInt((int) (key >> 32));
                out.writeInt((int) key);
                for (long l : e.getValue()) {
                    out.writeLong(l);
                }
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    static NewChunks read(Path file) {
        return read(file, null);
    }

    /**
     * A missing file gives an empty map. An unreadable file is renamed to .bad and gives an empty map. A file written for another
     * world identity than the current one (a world deleted and recreated under the same name) is renamed to .old and gives an empty
     * map. A file without identity is adopted by the current one.
     */
    static NewChunks read(Path file, UUID current) {
        NewChunks n = new NewChunks();
        n.world = current;
        if (!Files.exists(file)) return n;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION && version != FORMAT_WITHOUT_UUID) throw new IOException("version inconnue");
            UUID stored = null;
            if (version == FORMAT_VERSION && in.readBoolean()) {
                stored = new UUID(in.readLong(), in.readLong());
            }
            if (stored != null && current != null && !stored.equals(current)) {
                in.close();
                LOGGER.at(Level.INFO).log("Carte des chunks générés de %s : le monde a changé d'identité, carte vide", file.getFileName());
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer %s en .old", file.getFileName());
                }
                n.dirty = true;
                return n;
            }
            if (stored != null && current == null) n.world = stored;
            if (stored == null && current != null) n.dirty = true;
            int count = in.readInt();
            if (count < 0) throw new IOException("nombre de régions invalide");
            for (int r = 0; r < count; r++) {
                int rx = in.readInt();
                int rz = in.readInt();
                long[] bits = new long[LONGS_PER_REGION];
                for (int i = 0; i < LONGS_PER_REGION; i++) {
                    bits[i] = in.readLong();
                }
                n.regions.put(regionKey(rx, rz), bits);
            }
            if (in.read() != -1) throw new IOException("données en trop");
            return n;
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Carte des chunks générés illisible (%s), carte vide", file.getFileName());
            Telemetry.error("worldgen", e, null, "read");
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer %s en .bad", file.getFileName());
            }
            NewChunks fresh = new NewChunks();
            fresh.world = current;
            return fresh;
        }
    }

    /** Stores the data folder. Before this call, maps live in memory only. */
    static void init(Path dir) {
        synchronized (REGISTRY) {
            dataDir = dir;
        }
    }

    private static String sanitize(String world) {
        return world.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static Path fileOf(String world) {
        return dataDir == null ? null : dataDir.resolve("generated-chunks-" + sanitize(world) + ".bin");
    }

    /** The map of a world. The identity is checked when the map is first loaded in this session. */
    static NewChunks forWorld(String world, UUID uuid) {
        synchronized (REGISTRY) {
            NewChunks n = REGISTRY.get(world);
            if (n == null) {
                Path f = fileOf(world);
                if (f == null) {
                    n = new NewChunks();
                    n.world = uuid;
                } else {
                    n = read(f, uuid);
                }
                REGISTRY.put(world, n);
            }
            return n;
        }
    }

    /** Writes every modified map. A failure on one world does not stop the others. */
    static void flushAll() {
        Map<String, NewChunks> snapshot;
        synchronized (REGISTRY) {
            snapshot = new HashMap<>(REGISTRY);
        }
        for (Map.Entry<String, NewChunks> e : snapshot.entrySet()) {
            try {
                Path f = fileOf(e.getKey());
                if (f != null && e.getValue().dirty()) e.getValue().write(f);
            } catch (IOException | RuntimeException ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("Écriture de la carte des chunks générés impossible (%s)", e.getKey());
                Telemetry.error("worldgen", ex, null, "write");
            }
        }
    }
}
