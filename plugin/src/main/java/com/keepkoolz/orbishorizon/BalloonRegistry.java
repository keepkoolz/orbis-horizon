package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Registry of the structures deployed by the mod, locked against adding and removing blocks (T27, made
 * generic in T43: the structure type is the kind from Deployables).
 *
 * An entry is (world, prefab origin, rotation, kind, nullable owner). It is added by each paste of the mod's prefab
 * (crate, landing (chain or command), running dry, T19 recovery: all go through
 * BalloonManager.pasteKeepingTerrain) and removed at take-off (the blocks no longer exist during the flight). The
 * "deployed.json" file in the plugin's data folder keeps the registry between two starts. Balloons
 * deployed before this version are not in the registry, so they are not locked until their
 * next landing.
 *
 * An entry is checked on each use: if the anchor burner is no longer in place (balloon destroyed
 * by other means, such as a command or a building tool), it is removed and no longer blocks anything.
 */
final class BalloonRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int VERSION = 2;

    /**
     * A deployed structure (T43). The kind is the Deployables type. The owner (nullable) is the player who
     * placed it. It does not count in equality: an entry is found by world, origin, rotation and kind.
     */
    record Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Entry e && world.equals(e.world) && x == e.x && y == e.y && z == e.z
                    && rotation == e.rotation && kind.equals(e.kind);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(world, x, y, z, rotation, kind);
        }
    }

    /** The deployed structures, all worlds together. */
    private static final Set<Entry> ENTRIES = ConcurrentHashMap.newKeySet();
    private static volatile Path file;

    private BalloonRegistry() {
    }

    static void init(Path dataDirectory) {
        file = dataDirectory.resolve("deployed.json");
        load();
    }

    static int size() {
        return ENTRIES.size();
    }

    /** The deployed balloons registered for this world (T40, administration command). All balloon kinds (T54). */
    static java.util.List<Entry> entriesIn(World world) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        String name = world.getName();
        for (Entry e : ENTRIES) {
            if (e.world.equals(name)) {
                Deployables.Kind k = Deployables.get(e.kind);
                if (k == null || !k.isBalloon()) {
                    continue;
                }
                list.add(e);
            }
        }
        return list;
    }

    /** The deployed structures of exactly this type registered for this world (airship despawn). */
    static java.util.List<Entry> entriesOfKind(World world, String kindId) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        String name = world.getName();
        for (Entry e : ENTRIES) {
            if (e.world.equals(name) && e.kind.equals(kindId)) {
                list.add(e);
            }
        }
        return list;
    }

    /** Registers a deployed structure of the given type, with its owner (nullable). No effect if already there. Never throws. */
    static synchronized void add(World world, Vector3i origin, Rotation rotation, String kind, UUID owner) {
        try {
            if (ENTRIES.add(new Entry(world.getName(), origin.x, origin.y, origin.z, rotation, kind, owner))) {
                save();
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Structure « %s » non enregistrée dans le registre de verrouillage", kind);
        }
    }

    /** Removes a structure from the registry, whatever its owner. Never throws. */
    static synchronized void remove(World world, Vector3i origin, Rotation rotation, String kind) {
        try {
            if (ENTRIES.remove(new Entry(world.getName(), origin.x, origin.y, origin.z, rotation, kind, null))) {
                save();
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Structure « %s » non retirée du registre de verrouillage", kind);
        }
    }

    private static synchronized void remove(Entry entry) {
        if (ENTRIES.remove(entry)) {
            LOGGER.at(Level.INFO).log("Structure « %s » en (%d, %d, %d) du monde %s absente : retirée du registre de verrouillage",
                    entry.kind, entry.x, entry.y, entry.z, entry.world);
            save();
        }
    }

    /**
     * True if the cell is locked.
     *
     * Breaking or damaging a block (broken non-null): locked if it is a block of the structure, that is,
     * if the prefab has a block of that type at this cell. A terrain block left in an unfolded ladder cell
     * (T24) is therefore not protected. Placing a block (broken null): locked in any prefab cell and in the
     * type's forbidden placement volume.
     */
    static boolean isLocked(World world, Vector3i pos, BlockType broken, boolean placing) {
        return lockingEntry(world, pos, broken, placing) != null;
    }

    /** The entry that locks this cell (same rules as isLocked), null if none (T43). Used to choose the message. */
    static Entry lockingEntry(World world, Vector3i pos, BlockType broken, boolean placing) {
        if (ENTRIES.isEmpty()) {
            return null;
        }
        String worldName = world.getName();
        for (Entry e : ENTRIES) {
            if (!e.world.equals(worldName)) {
                continue;
            }
            Deployables.Kind kind = kindOf(e);
            BalloonShape shape = kind == null ? null : kind.shapeOrNull();
            if (shape == null) {
                continue;
            }
            Vector3i local = e.rotation.toInverse().rotateYaw(new Vector3i(pos.x - e.x, pos.y - e.y, pos.z - e.z), new Vector3i());
            if (!shape.inBounds(local.x, local.y, local.z)) {
                continue;
            }
            BalloonShape.Cell cell = shape.cellAt(local.x, local.y, local.z);
            boolean inside;
            if (placing) {
                inside = cell != null || kind.inPlaceVolume(local.x, local.y, local.z);
            } else {
                inside = cell != null && (broken == null || BalloonManager.sameBlock(broken, cell));
            }
            if (!inside) {
                continue;
            }
            if (!stillThere(world, shape, e)) {
                remove(e);
                continue;
            }
            return e;
        }
        return null;
    }

    /** The entry whose shape contains a block at this world cell, null if none (T43). Same calculation as isLocked, without the placement volume. */
    static Entry find(World world, Vector3i pos) {
        if (ENTRIES.isEmpty()) {
            return null;
        }
        String worldName = world.getName();
        for (Entry e : ENTRIES) {
            if (!e.world.equals(worldName)) {
                continue;
            }
            Deployables.Kind kind = kindOf(e);
            BalloonShape shape = kind == null ? null : kind.shapeOrNull();
            if (shape == null) {
                continue;
            }
            Vector3i local = e.rotation.toInverse().rotateYaw(new Vector3i(pos.x - e.x, pos.y - e.y, pos.z - e.z), new Vector3i());
            if (!shape.inBounds(local.x, local.y, local.z) || shape.cellAt(local.x, local.y, local.z) == null) {
                continue;
            }
            if (!stillThere(world, shape, e)) {
                remove(e);
                continue;
            }
            return e;
        }
        return null;
    }

    private static final Set<String> UNKNOWN_WARNED = ConcurrentHashMap.newKeySet();

    /** The entry's type, null if unknown (single warning, the entry is kept in the file). */
    private static Deployables.Kind kindOf(Entry e) {
        Deployables.Kind kind = Deployables.get(e.kind);
        if (kind == null && UNKNOWN_WARNED.add(e.kind)) {
            LOGGER.at(Level.WARNING).log("Registre de verrouillage : type de structure inconnu « %s », entrées ignorées", e.kind);
        }
        return kind;
    }

    /** Is the anchor block in place? An unloaded chunk (unknown block) counts as "yes". */
    private static boolean stillThere(World world, BalloonShape shape, Entry e) {
        BalloonShape.Cell anchor = shape.anchor();
        Vector3i p = anchor.rotated(e.rotation).add(e.x, e.y, e.z);
        BlockType bt = world.getBlockType(p.x, p.y, p.z);
        return bt == null || BalloonManager.sameBlock(bt, anchor);
    }

    // ------------------------------------------------------------------ file

    private static void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(f, StandardCharsets.UTF_8));
            for (org.bson.BsonValue v : doc.getArray("balloons")) {
                BsonDocument d = v.asDocument();
                // Version 1: no kind, it is a balloon. Version 2: kind and owner (absent if null).
                String kind = d.containsKey("kind") ? d.getString("kind").getValue() : Deployables.BALLOON_KIND;
                UUID owner = d.containsKey("owner") ? UUID.fromString(d.getString("owner").getValue()) : null;
                ENTRIES.add(new Entry(d.getString("world").getValue(), d.getNumber("x").intValue(),
                        d.getNumber("y").intValue(), d.getNumber("z").intValue(),
                        Rotation.valueOf(d.getString("rotation").getValue()), kind, owner));
            }
            LOGGER.at(Level.INFO).log("Registre de verrouillage : %d structure(s)", ENTRIES.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Registre de verrouillage illisible : %s", f);
            ENTRIES.clear();
            try {
                Files.move(f, f.resolveSibling(f.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The file stays in place.
            }
        }
    }

    /** Writes the whole registry (temporary file then atomic rename, like the T19 recovery file). */
    private static void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            BsonArray list = new BsonArray();
            for (Entry e : ENTRIES) {
                BsonDocument d = new BsonDocument();
                d.put("world", new BsonString(e.world));
                d.put("x", new BsonInt32(e.x));
                d.put("y", new BsonInt32(e.y));
                d.put("z", new BsonInt32(e.z));
                d.put("rotation", new BsonString(e.rotation.name()));
                d.put("kind", new BsonString(e.kind));
                if (e.owner != null) {
                    d.put("owner", new BsonString(e.owner.toString()));
                }
                list.add(d);
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("balloons", list);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Registre de verrouillage non écrit");
        }
    }
}
