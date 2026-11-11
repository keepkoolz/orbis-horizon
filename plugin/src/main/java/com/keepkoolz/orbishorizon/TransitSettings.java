package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/** Server-wide settings, stored in transit-settings.json of the data folder, including the recipes switched off and the station contract prices. */
final class TransitSettings {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile Set<String> disabledRecipes = new HashSet<>();
    static final int DEFAULT_CONTRACT_ESSENCES = 1000;
    static final int DEFAULT_CONTRACT_CONCENTRATED = 10;
    private static volatile int contractEssences = DEFAULT_CONTRACT_ESSENCES;
    private static volatile int contractConcentrated = DEFAULT_CONTRACT_CONCENTRATED;
    private static volatile boolean packBackView = true;
    private static volatile boolean markerSharing = true;
    /** The worldgenEnabled and worldgenOldTerrain values as read, not interpreted (null when absent), written back unchanged. */
    private static volatile BsonValue rawWorldgenEnabled;
    private static volatile BsonValue rawWorldgenOldTerrain;
    private static volatile boolean fareEnabled = true;
    private static volatile boolean generatedStationsEditable = false;
    private static volatile ConsoleLogs.Threshold consoleLogLevel = ConsoleLogs.Threshold.WARNING;
    private static volatile Path file;

    private TransitSettings() {
    }

    /** Life essences a station contract costs when paid with essences. Default: 1000. */
    static int contractEssences() {
        return contractEssences;
    }

    /** Concentrated life essences a station contract costs when the player lacks essences. Default: 10. */
    static int contractConcentrated() {
        return contractConcentrated;
    }

    /** Sets the price in essences. A value below 1 is refused. */
    static synchronized void setContractEssences(int n) {
        if (n < 1) return;
        contractEssences = n;
        save();
    }

    /** Sets the price in concentrated essences. A value below 1 is refused. */
    static synchronized void setContractConcentrated(int n) {
        if (n < 1) return;
        contractConcentrated = n;
        save();
    }

    /** Item ids of the recipes switched off, as an unmodifiable copy. Default: none. */
    static Set<String> disabledRecipes() {
        return Collections.unmodifiableSet(new HashSet<>(disabledRecipes));
    }

    /** Switches a recipe off or on. */
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

    /** Whether map markers (vehicles, tents, stations) can be shared between players. Default: on. */
    static boolean markerSharing() {
        return markerSharing;
    }

    /** Whether a public transit ticket costs essences. Off: destinations are queued for free. Default: on. */
    static boolean fareEnabled() {
        return fareEnabled;
    }

    /** Whether the generated stations may be edited like the stations of players: only the quay core stays locked. Default: off. */
    static boolean generatedStationsEditable() {
        return generatedStationsEditable;
    }

    /** Minimal level of the mod's logs shown in the server console. Default: INFO. */
    static ConsoleLogs.Threshold consoleLogLevel() {
        return consoleLogLevel;
    }

    /** Reads the file. Missing: defaults. Unreadable: defaults, and the file is kept as .bad (same rule as stations.json). */
    static synchronized void load(Path dataDir) {
        file = dataDir.resolve("transit-settings.json");
        disabledRecipes = new HashSet<>();
        contractEssences = DEFAULT_CONTRACT_ESSENCES;
        contractConcentrated = DEFAULT_CONTRACT_CONCENTRATED;
        packBackView = true;
        rawWorldgenEnabled = null;
        rawWorldgenOldTerrain = null;
        markerSharing = true;
        fareEnabled = true;
        generatedStationsEditable = false;
        consoleLogLevel = ConsoleLogs.Threshold.WARNING;
        if (!Files.isRegularFile(file)) return;
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (doc.containsKey("disabledRecipes")) {
                Set<String> read = new HashSet<>();
                for (var v : doc.getArray("disabledRecipes")) {
                    if (v.isString() && ModRecipes.isModItem(v.asString().getValue())) read.add(v.asString().getValue());
                }
                disabledRecipes = read;
            }
            contractEssences = positiveInt(doc, "contractEssences", DEFAULT_CONTRACT_ESSENCES);
            contractConcentrated = positiveInt(doc, "contractConcentrated", DEFAULT_CONTRACT_CONCENTRATED);
            packBackView = !doc.containsKey("packBackView") || doc.getBoolean("packBackView").getValue();
            rawWorldgenEnabled = doc.get("worldgenEnabled");
            rawWorldgenOldTerrain = doc.get("worldgenOldTerrain");
            markerSharing = !doc.containsKey("markerSharing") || doc.getBoolean("markerSharing").getValue();
            fareEnabled = !doc.containsKey("fareEnabled") || doc.getBoolean("fareEnabled").getValue();
            generatedStationsEditable = doc.containsKey("generatedStationsEditable") && doc.get("generatedStationsEditable").isBoolean()
                    && doc.getBoolean("generatedStationsEditable").getValue();
            consoleLogLevel = doc.containsKey("consoleLogLevel") && doc.get("consoleLogLevel").isString()
                    ? ConsoleLogs.Threshold.parse(doc.getString("consoleLogLevel").getValue()) : ConsoleLogs.Threshold.WARNING;
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("transit-settings.json unreadable, default settings");
            disabledRecipes = new HashSet<>();
            contractEssences = DEFAULT_CONTRACT_ESSENCES;
            contractConcentrated = DEFAULT_CONTRACT_CONCENTRATED;
            rawWorldgenEnabled = null;
            rawWorldgenOldTerrain = null;
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

    static synchronized void setMarkerSharing(boolean on) {
        markerSharing = on;
        save();
    }

    static synchronized void setFareEnabled(boolean on) {
        fareEnabled = on;
        save();
    }

    static synchronized void setGeneratedStationsEditable(boolean on) {
        generatedStationsEditable = on;
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

    /** A whole number of at least 1 under the key, the default when the key is missing or not such a number. */
    private static int positiveInt(BsonDocument doc, String key, int fallback) {
        if (!doc.containsKey(key) || !doc.get(key).isNumber()) return fallback;
        int n = doc.get(key).asNumber().intValue();
        return n >= 1 ? n : fallback;
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
            doc.put("contractEssences", new BsonInt32(contractEssences));
            doc.put("contractConcentrated", new BsonInt32(contractConcentrated));
            doc.put("packBackView", new BsonBoolean(packBackView));
            BsonValue legacyEnabled = rawWorldgenEnabled;
            if (legacyEnabled != null) doc.put("worldgenEnabled", legacyEnabled);
            doc.put("markerSharing", new BsonBoolean(markerSharing));
            BsonValue legacyOldTerrain = rawWorldgenOldTerrain;
            if (legacyOldTerrain != null) doc.put("worldgenOldTerrain", legacyOldTerrain);
            doc.put("fareEnabled", new BsonBoolean(fareEnabled));
            doc.put("generatedStationsEditable", new BsonBoolean(generatedStationsEditable));
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
