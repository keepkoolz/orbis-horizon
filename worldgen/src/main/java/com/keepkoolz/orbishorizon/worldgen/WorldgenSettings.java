package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/** The generation settings, stored in worldgen-settings.json of the data folder. A change that makes abandoned stations eligible again takes them up. */
final class WorldgenSettings {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Object LOCK = new Object();
    private static volatile boolean enabled = true;
    private static volatile boolean oldTerrain = false;
    private static volatile Path file;

    private WorldgenSettings() {
    }

    /**
     * Reads dir/worldgen-settings.json. Missing: reads the two keys of legacyDir/transit-settings.json (a missing key keeps its
     * default) and writes the new file. An unreadable old file gives the defaults in memory and writes nothing, so the next start
     * tries again. An unreadable new file gives the defaults and is kept as .bad.
     */
    static void load(Path dir, Path legacyDir) {
        synchronized (LOCK) {
            file = dir.resolve("worldgen-settings.json");
            enabled = true;
            oldTerrain = false;
            if (Files.isRegularFile(file)) {
                try {
                    read(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("worldgen-settings.json unreadable, default settings");
                    enabled = true;
                    oldTerrain = false;
                    try {
                        Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e2) {
                        LOGGER.at(Level.WARNING).withCause(e2).log("Cannot rename worldgen-settings.json to .bad");
                    }
                    // The defaults are written so that an old setting of transit-settings.json does not come back at the next start.
                    save();
                }
                return;
            }
            Path legacy = legacyDir == null ? null : legacyDir.resolve("transit-settings.json");
            if (legacy != null && Files.isRegularFile(legacy)) {
                try {
                    read(Files.readString(legacy, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("transit-settings.json unreadable, default generation settings");
                    enabled = true;
                    oldTerrain = false;
                    return;
                }
            }
            save();
        }
    }

    /** Reads the two keys of a settings document, a missing key keeping its default. */
    private static void read(String json) {
        BsonDocument doc = BsonDocument.parse(json);
        enabled = !doc.containsKey("worldgenEnabled") || doc.getBoolean("worldgenEnabled").getValue();
        oldTerrain = doc.containsKey("worldgenOldTerrain") && doc.getBoolean("worldgenOldTerrain").getValue();
    }

    private static void save() {
        Path f = file;
        if (f == null) return;
        try {
            BsonDocument doc = new BsonDocument();
            doc.put("worldgenEnabled", new BsonBoolean(enabled));
            doc.put("worldgenOldTerrain", new BsonBoolean(oldTerrain));
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot write worldgen-settings.json");
        }
    }

    /** Whether stations are generated with the world. */
    static boolean enabled() {
        return enabled;
    }

    /** Whether stations may also be generated on terrain explored before the mod generated stations. */
    static boolean oldTerrain() {
        return oldTerrain;
    }

    /** Saves the setting and, when it goes from off to on with old terrain allowed, takes up the abandoned entries. */
    static void setEnabled(boolean on) {
        boolean revive;
        synchronized (LOCK) {
            revive = reviveOnEnable(enabled, on, oldTerrain);
            enabled = on;
            save();
        }
        if (revive) {
            GenerationListener.reviveAll();
        }
    }

    /** Saves the setting and, when it goes from off to on, takes up the abandoned entries of every world. */
    static void setOldTerrain(boolean on) {
        boolean revive;
        synchronized (LOCK) {
            revive = reviveOnOldTerrain(oldTerrain, on);
            oldTerrain = on;
            save();
        }
        if (revive) {
            GenerationListener.reviveAll();
        }
    }

    /** Whether turning generation on takes up the abandoned entries: only from off to on with old terrain allowed. */
    static boolean reviveOnEnable(boolean wasEnabled, boolean nowEnabled, boolean oldTerrain) {
        return !wasEnabled && nowEnabled && oldTerrain;
    }

    /** Whether allowing old terrain takes up the abandoned entries: only when it goes from off to on. */
    static boolean reviveOnOldTerrain(boolean wasOld, boolean nowOld) {
        return !wasOld && nowOld;
    }
}
