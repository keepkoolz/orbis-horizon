package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Resume file of a flight (T19): one JSON per flight in the plugin data folder
 * (sub-folder "flights", name "pilot-uuid.json").
 *
 * It holds what is needed to put the balloon and its contents back down if the server stops before the
 * landing took place: world, prefab origin, rotation, flying entity UUID, burner contents
 * (remaining fuel included) and chests. It is written before the blocks are removed at take-off
 * (with fsync), updated every few seconds during the flight, and deleted on landing.
 * Every write goes through a temporary file then an atomic rename: the file is always
 * either the complete old version or the new one, never a truncated file.
 * When a world loads, BalloonManager.recoverWorld puts back down each flight left in the folder.
 */
final class BalloonResume {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int VERSION = 1;
    private static volatile Path dir;
    /** Passengers to put back down on their next connection (T21), one file per player. */
    private static volatile Path strandedDir;

    /** A passenger of a flight read back from disk: their seat (stool cell, prefab frame). */
    record Seated(UUID uuid, int x, int y, int z) {
    }

    /** A flight read back from disk. */
    record Record(UUID pilot, String world, Deployables.Kind kind, Vector3i origin, Rotation rotation, UUID balloon,
                  BurnerFuel burner, BalloonCargo cargo, List<Seated> passengers) {
    }

    /** Position where to put down a player left in the air when the flight was resumed (T21). */
    record Stranded(String world, double x, double y, double z) {
    }

    private BalloonResume() {
    }

    static void init(Path dataDirectory) {
        dir = dataDirectory.resolve("flights");
        strandedDir = dataDirectory.resolve("stranded");
    }

    private static Path file(UUID pilot) {
        return dir.resolve(pilot + ".json");
    }

    /** Writes (or replaces) the flight resume file. Never throws. Durable: fsync before the rename. */
    static synchronized void write(BalloonFlight f, Vector3i origin, boolean durable) {
        Path d = dir;
        if (d == null) {
            return;
        }
        try {
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("pilot", new BsonString(f.pilotUuid.toString()));
            doc.put("world", new BsonString(f.world.getName()));
            // T54: balloon type (absent in older files: the large balloon).
            doc.put("kind", new BsonString(f.kind.id));
            doc.put("x", new BsonInt32(origin.x));
            doc.put("y", new BsonInt32(origin.y));
            doc.put("z", new BsonInt32(origin.z));
            doc.put("rotation", new BsonString(f.rotation.name()));
            if (f.balloonUuid != null) {
                doc.put("balloon", new BsonString(f.balloonUuid.toString()));
            }
            doc.put("savedAtMs", new org.bson.BsonInt64(System.currentTimeMillis()));
            // T21: seated passengers (uuid and stool cell), to put them back down if the resume happens.
            BsonArray seated = new BsonArray();
            for (BalloonFlight.Passenger p : f.passengers) {
                BsonDocument d2 = new BsonDocument();
                d2.put("uuid", new BsonString(p.uuid.toString()));
                d2.put("x", new BsonInt32(p.seatBlock.x));
                d2.put("y", new BsonInt32(p.seatBlock.y));
                d2.put("z", new BsonInt32(p.seatBlock.z));
                seated.add(d2);
            }
            doc.put("passengers", seated);
            if (f.burner != null) {
                doc.put("burner", f.burner.toBson());
            }
            if (f.cargo != null) {
                if (f.cargoBson == null) {
                    // The chests do not change during the flight: encoded only once.
                    f.cargoBson = f.cargo.toBson();
                }
                doc.put("cargo", f.cargoBson);
            }
            byte[] bytes = doc.toJson().getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(d);
            Path target = file(f.pilotUuid);
            Path tmp = d.resolve(f.pilotUuid + ".json.tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                if (durable) {
                    ch.force(true);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Fichier de reprise du vol de %s non écrit", f.pilotUuid);
        }
    }

    static boolean exists(UUID pilot) {
        return dir != null && Files.exists(file(pilot));
    }

    /** Deletes the resume file (the flight is over). */
    static synchronized void delete(UUID pilot) {
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(file(pilot));
            Files.deleteIfExists(dir.resolve(pilot + ".json.tmp"));
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Fichier de reprise du vol de %s non effacé", pilot);
        }
    }

    /** Flights to put back down in this world. An unreadable file is renamed to ".bad" and ignored. */
    static synchronized List<Record> loadAll(String worldName) {
        List<Record> list = new ArrayList<>();
        Path d = dir;
        if (d == null || !Files.isDirectory(d)) {
            return list;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Dossier de reprise illisible : %s", d);
            return list;
        }
        for (Path p : files) {
            try {
                BsonDocument doc = BsonDocument.parse(Files.readString(p, StandardCharsets.UTF_8));
                if (!worldName.equals(doc.getString("world").getValue())) {
                    continue;
                }
                List<Seated> seated = new ArrayList<>();
                if (doc.containsKey("passengers")) {
                    for (org.bson.BsonValue v : doc.getArray("passengers")) {
                        BsonDocument d2 = v.asDocument();
                        seated.add(new Seated(UUID.fromString(d2.getString("uuid").getValue()),
                                d2.getNumber("x").intValue(), d2.getNumber("y").intValue(), d2.getNumber("z").intValue()));
                    }
                }
                String kindId = doc.containsKey("kind") ? doc.getString("kind").getValue() : Deployables.BALLOON_KIND;
                Deployables.Kind kind = Deployables.get(kindId);
                if (kind == null || !kind.isBalloon()) {
                    throw new IllegalStateException("Type de montgolfière inconnu : " + kindId);
                }
                list.add(new Record(UUID.fromString(doc.getString("pilot").getValue()), worldName, kind,
                        new Vector3i(doc.getNumber("x").intValue(), doc.getNumber("y").intValue(), doc.getNumber("z").intValue()),
                        Rotation.valueOf(doc.getString("rotation").getValue()),
                        doc.containsKey("balloon") ? UUID.fromString(doc.getString("balloon").getValue()) : null,
                        doc.containsKey("burner") ? BurnerFuel.fromBson(doc.getDocument("burner")) : null,
                        doc.containsKey("cargo") ? BalloonCargo.fromBson(doc.getDocument("cargo")) : null,
                        seated));
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.SEVERE).withCause(e).log("Fichier de reprise illisible : %s", p);
                try {
                    Files.move(p, p.resolveSibling(p.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // The file stays in place.
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ passengers left in the air (T21)

    private static Path strandedFile(UUID player) {
        return strandedDir.resolve(player + ".json");
    }

    /**
     * Records the position where to put down an offline passenger: on the server, a player saved in the air on a
     * balloon whose flight was resumed cannot be modified until they connect. They are put down on
     * their next arrival in the world (BalloonManager.onPlayerAdded). Never throws.
     */
    static synchronized void writeStranded(UUID player, String world, double x, double y, double z) {
        Path d = strandedDir;
        if (d == null) {
            return;
        }
        try {
            BsonDocument doc = new BsonDocument();
            doc.put("world", new BsonString(world));
            doc.put("x", new org.bson.BsonDouble(x));
            doc.put("y", new org.bson.BsonDouble(y));
            doc.put("z", new org.bson.BsonDouble(z));
            Files.createDirectories(d);
            Files.writeString(strandedFile(player), doc.toJson(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Position de reprise du passager %s non écrite", player);
        }
    }

    /** Reads then deletes the player's resume position if it concerns this world, otherwise null. */
    static synchronized Stranded takeStranded(UUID player, String world) {
        Path d = strandedDir;
        if (d == null || !Files.exists(strandedFile(player))) {
            return null;
        }
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(strandedFile(player), StandardCharsets.UTF_8));
            if (!world.equals(doc.getString("world").getValue())) {
                return null;
            }
            Files.deleteIfExists(strandedFile(player));
            return new Stranded(world, doc.getNumber("x").doubleValue(), doc.getNumber("y").doubleValue(),
                    doc.getNumber("z").doubleValue());
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Position de reprise du passager %s illisible", player);
            return null;
        }
    }
}
