package com.keepkoolz.orbishorizon;

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

/** Server-wide transit settings, stored in transit-settings.json of the data folder. Default: recipe off. */
final class TransitSettings {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile boolean recipeEnabled = false;
    private static volatile boolean packBackView = true;
    private static volatile Path file;

    private TransitSettings() {
    }

    static boolean recipeEnabled() {
        return recipeEnabled;
    }

    /** whether a camp pack in the inventory is drawn on the player's back. Default: on. */
    static boolean packBackView() {
        return packBackView;
    }

    /** Reads the file. Missing: defaults. Unreadable: defaults, and the file is kept as .bad (same rule as stations.json). */
    static synchronized void load(Path dataDir) {
        file = dataDir.resolve("transit-settings.json");
        recipeEnabled = false;
        packBackView = true;
        if (!Files.isRegularFile(file)) return;
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            recipeEnabled = doc.containsKey("recipeEnabled") && doc.getBoolean("recipeEnabled").getValue();
            packBackView = !doc.containsKey("packBackView") || doc.getBoolean("packBackView").getValue();
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("transit-settings.json illisible, réglages par défaut");
            recipeEnabled = false;
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer transit-settings.json en .bad");
            }
        }
    }

    static synchronized void setRecipeEnabled(boolean on) {
        recipeEnabled = on;
        save();
    }

    static synchronized void setPackBackView(boolean on) {
        packBackView = on;
        save();
    }

    private static void save() {
        boolean on = recipeEnabled;
        Path f = file;
        if (f == null) return;
        try {
            BsonDocument doc = new BsonDocument();
            doc.put("recipeEnabled", new BsonBoolean(on));
            doc.put("packBackView", new BsonBoolean(packBackView));
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de transit-settings.json impossible");
        }
    }
}
