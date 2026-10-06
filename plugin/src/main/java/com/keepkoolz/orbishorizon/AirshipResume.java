package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

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
 * Resume file of an airship flight (prototype). Separate from BalloonResume on purpose (own sub-folder "airships",
 * no burner, no passengers list), so that the balloon files and their format are untouched. One JSON per flight, written
 * atomically (temporary file then rename, fsync when durable), holding the world, the last collision-free pivot cell and heading
 * (the landing is made at the nearest quarter turn), the flying entity UUID, the pilot, the chest contents (BalloonCargo) and, since T57, the engine contents (AirshipEngines, optional key "engines"), and since T63 the bench levels (AirshipBenches, optional key "benches"), and since T64 the passengers (optional key "passengers").
 * Written before the blocks are removed at take-off, updated every few seconds, deleted on landing.
 */
final class AirshipResume {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int VERSION = 1;
    private static volatile Path dir;

    /** A flight read back from disk. pivotX/Y/Z: last valid pivot cell (integers), heading in radians. */
    record Record(UUID id, UUID pilot, String world, int pivotX, int pivotY, int pivotZ, double heading, UUID ship,
                  BalloonCargo cargo, AirshipEngines engines, org.joml.Vector3i takeoffOrigin, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation takeoffRotation,
                  AirshipBenches benches, List<AirshipPassengers.Saved> riders) {
    }

    private AirshipResume() {
    }

    static void init(Path dataDirectory) {
        dir = dataDirectory.resolve("airships");
    }

    private static Path file(UUID id) {
        return dir.resolve(id + ".json");
    }

    /** Writes (or replaces) the resume file. Never throws. */
    static synchronized void write(AirshipFlight f, boolean durable) {
        Path d = dir;
        if (d == null) {
            return;
        }
        try {
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("id", new BsonString(f.id.toString()));
            if (f.pilotUuid != null && f.pilotRef != null) {
                doc.put("pilot", new BsonString(f.pilotUuid.toString()));
            }
            doc.put("world", new BsonString(f.world.getName()));
            doc.put("x", new BsonInt32(f.lastValidPivotCell.x));
            doc.put("y", new BsonInt32(f.lastValidPivotCell.y));
            doc.put("z", new BsonInt32(f.lastValidPivotCell.z));
            doc.put("heading", new org.bson.BsonDouble(f.lastValidTheta));
            if (f.takeoffRotation != null) {
                doc.put("ox", new BsonInt32(f.takeoffOrigin.x));
                doc.put("oy", new BsonInt32(f.takeoffOrigin.y));
                doc.put("oz", new BsonInt32(f.takeoffOrigin.z));
                doc.put("orot", new BsonString(f.takeoffRotation.name()));
            }
            if (f.shipUuid != null) {
                doc.put("ship", new BsonString(f.shipUuid.toString()));
            }
            doc.put("savedAtMs", new BsonInt64(System.currentTimeMillis()));
            if (f.cargo != null) {
                if (f.cargoBson == null) {
                    f.cargoBson = f.cargo.toBson(); // the chests do not change during the flight
                }
                doc.put("cargo", f.cargoBson);
            }
            if (f.engines != null) {
                // The engines burn during the flight: encoded at every write (T57).
                doc.put("engines", f.engines.toBson());
            }
            if (f.benches != null) {
                doc.put("benches", f.benches.toBson()); // T63: level and upgrade items of the benches
            }
            if (!f.riders.isEmpty()) {
                doc.put("passengers", AirshipPassengers.toBson(f)); // T64: uuid and stool of each passenger
            }
            byte[] bytes = doc.toJson().getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(d);
            Path tmp = d.resolve(f.id + ".json.tmp");
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
                Files.move(tmp, file(f.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file(f.id), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Fichier de reprise du dirigeable %s non écrit", f.id);
        }
    }

    static boolean exists(UUID id) {
        return dir != null && Files.exists(file(id));
    }

    static synchronized void delete(UUID id) {
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(file(id));
            Files.deleteIfExists(dir.resolve(id + ".json.tmp"));
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Fichier de reprise du dirigeable %s non effacé", id);
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
            LOGGER.at(Level.WARNING).withCause(e).log("Dossier de reprise des dirigeables illisible : %s", d);
            return list;
        }
        for (Path p : files) {
            try {
                BsonDocument doc = BsonDocument.parse(Files.readString(p, StandardCharsets.UTF_8));
                if (!worldName.equals(doc.getString("world").getValue())) {
                    continue;
                }
                list.add(new Record(UUID.fromString(doc.getString("id").getValue()),
                        doc.containsKey("pilot") ? UUID.fromString(doc.getString("pilot").getValue()) : null, worldName,
                        doc.getNumber("x").intValue(), doc.getNumber("y").intValue(), doc.getNumber("z").intValue(),
                        doc.getNumber("heading").doubleValue(),
                        doc.containsKey("ship") ? UUID.fromString(doc.getString("ship").getValue()) : null,
                        doc.containsKey("cargo") ? BalloonCargo.fromBson(doc.getDocument("cargo")) : null,
                        // T57: absent in files written before the engines existed: no engine contents.
                        doc.containsKey("engines") ? AirshipEngines.fromBson(doc.getDocument("engines")) : null,
                        doc.containsKey("orot") ? new org.joml.Vector3i(doc.getNumber("ox").intValue(), doc.getNumber("oy").intValue(),
                                doc.getNumber("oz").intValue()) : null,
                        doc.containsKey("orot") ? com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.valueOf(
                                doc.getString("orot").getValue()) : null,
                        // T63: absent in files written before the bench levels were kept: no level to put back.
                        doc.containsKey("benches") ? AirshipBenches.fromBson(doc.getDocument("benches")) : null,
                        // T64: absent without passengers or in older files.
                        doc.containsKey("passengers") ? AirshipPassengers.fromBson(doc.getArray("passengers")) : null));
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.SEVERE).withCause(e).log("Fichier de reprise de dirigeable illisible : %s", p);
                try {
                    Files.move(p, p.resolveSibling(p.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // The file stays in place.
                }
            }
        }
        return list;
    }
}
