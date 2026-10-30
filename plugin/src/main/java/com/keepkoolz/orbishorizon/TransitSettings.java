package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/** Server-wide settings, stored in transit-settings.json of the data folder, including the recipes switched off. Default: station crate recipes off. */
final class TransitSettings {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile Set<String> disabledRecipes = new HashSet<>(StationSpec.CRATE_IDS);
    private static volatile boolean packBackView = true;
    private static volatile boolean worldgenEnabled = true;
    private static volatile boolean markerSharing = true;
    private static volatile boolean worldgenOldTerrain = false;
    private static volatile boolean fareEnabled = true;
    private static volatile ConsoleLogs.Threshold consoleLogLevel = ConsoleLogs.Threshold.INFO;
    private static volatile Path file;

    private TransitSettings() {
    }

    /** Item ids of the recipes switched off, as an unmodifiable copy. Default: the station crates. */
    static Set<String> disabledRecipes() {
        return Collections.unmodifiableSet(new HashSet<>(disabledRecipes));
    }

    static synchronized void setRecipeDisabled(String itemId, boolean disabled) {
        Set<String> next = new HashSet<>(disabledRecipes);
        if (disabled) next.add(itemId);
        else next.remove(itemId);
        disabledRecipes = next;
        save();
    }

    /** whether a camp pack in the inventory is drawn on the player's back. Default: on. */
    static boolean packBackView() {
        return packBackView;
    }

    /** Whether stations are generated with the world. Default: on. */
    static boolean worldgenEnabled() {
        return worldgenEnabled;
    }

    /** Whether stations may also be generated on terrain explored before the mod generated stations. Default: off. */
    static boolean worldgenOldTerrain() {
        return worldgenOldTerrain;
    }

    /** Whether map markers (vehicles, tents, stations) can be shared between players. Default: on. */
    static boolean markerSharing() {
        return markerSharing;
    }

    /** Whether a public transit ticket costs essences. Off: destinations are queued for free. Default: on. */
    static boolean fareEnabled() {
        return fareEnabled;
    }

    /** Minimal level of the mod's logs shown in the server console. Default: INFO. */
    static ConsoleLogs.Threshold consoleLogLevel() {
        return consoleLogLevel;
    }

    /** Reads the file. Missing: defaults. Unreadable: defaults, and the file is kept as .bad (same rule as stations.json). */
    static synchronized void load(Path dataDir) {
        file = dataDir.resolve("transit-settings.json");
        disabledRecipes = new HashSet<>(StationSpec.CRATE_IDS);
        packBackView = true;
        worldgenEnabled = true;
        markerSharing = true;
        worldgenOldTerrain = false;
        fareEnabled = true;
        consoleLogLevel = ConsoleLogs.Threshold.INFO;
        if (!Files.isRegularFile(file)) return;
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (doc.containsKey("disabledRecipes")) {
                Set<String> read = new HashSet<>();
                for (var v : doc.getArray("disabledRecipes")) {
                    if (v.isString() && ModRecipes.isModItem(v.asString().getValue())) read.add(v.asString().getValue());
                }
                disabledRecipes = read;
            } else if (doc.containsKey("recipeEnabled") && doc.getBoolean("recipeEnabled").getValue()) {
                disabledRecipes = new HashSet<>();
            }
            packBackView = !doc.containsKey("packBackView") || doc.getBoolean("packBackView").getValue();
            worldgenEnabled = !doc.containsKey("worldgenEnabled") || doc.getBoolean("worldgenEnabled").getValue();
            worldgenOldTerrain = doc.containsKey("worldgenOldTerrain") && doc.getBoolean("worldgenOldTerrain").getValue();
            markerSharing = !doc.containsKey("markerSharing") || doc.getBoolean("markerSharing").getValue();
            fareEnabled = !doc.containsKey("fareEnabled") || doc.getBoolean("fareEnabled").getValue();
            consoleLogLevel = doc.containsKey("consoleLogLevel") && doc.get("consoleLogLevel").isString()
                    ? ConsoleLogs.Threshold.parse(doc.getString("consoleLogLevel").getValue()) : ConsoleLogs.Threshold.INFO;
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("transit-settings.json unreadable, default settings");
            disabledRecipes = new HashSet<>(StationSpec.CRATE_IDS);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Cannot rename transit-settings.json to .bad");
            }
        }
    }

    static synchronized void setPackBackView(boolean on) {
        packBackView = on;
        save();
    }

    /** Sets the setting and saves it. When it goes from off to on with worldgenOldTerrain on, the abandoned entries are taken up (see reviveAll). */
    static void setWorldgenEnabled(boolean on) {
        boolean revive;
        synchronized (TransitSettings.class) {
            revive = on && !worldgenEnabled && worldgenOldTerrain;
            worldgenEnabled = on;
            save();
        }
        if (revive) {
            GenerationListener.reviveAll();
        }
    }

    /** Sets the setting, saves it, and when it goes from off to on takes up the abandoned entries of every world (see GenerationListener.reviveAll). */
    static void setWorldgenOldTerrain(boolean on) {
        boolean was;
        synchronized (TransitSettings.class) {
            was = worldgenOldTerrain;
            worldgenOldTerrain = on;
            save();
        }
        if (on && !was) {
            GenerationListener.reviveAll();
        }
    }

    static synchronized void setMarkerSharing(boolean on) {
        markerSharing = on;
        save();
    }

    static synchronized void setFareEnabled(boolean on) {
        fareEnabled = on;
        save();
    }

    /** Sets the level, saves it and applies it to the loggers of the mod (see ConsoleLogs). */
    static void setConsoleLogLevel(ConsoleLogs.Threshold level) {
        synchronized (TransitSettings.class) {
            consoleLogLevel = level;
            save();
        }
        ConsoleLogs.apply(level);
    }

    private static void save() {
        Path f = file;
        if (f == null) return;
        try {
            BsonDocument doc = new BsonDocument();
            BsonArray off = new BsonArray();
            for (String id : ModRecipes.ITEMS) {
                if (disabledRecipes.contains(id)) off.add(new BsonString(id));
            }
            doc.put("disabledRecipes", off);
            doc.put("packBackView", new BsonBoolean(packBackView));
            doc.put("worldgenEnabled", new BsonBoolean(worldgenEnabled));
            doc.put("markerSharing", new BsonBoolean(markerSharing));
            doc.put("worldgenOldTerrain", new BsonBoolean(worldgenOldTerrain));
            doc.put("fareEnabled", new BsonBoolean(fareEnabled));
            doc.put("consoleLogLevel", new BsonString(consoleLogLevel.name()));
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot write transit-settings.json");
        }
    }
}
