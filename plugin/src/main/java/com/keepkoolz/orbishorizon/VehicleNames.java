package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Names of the vehicles (airship, steamboat, large balloon), kept by the id of the structure (Ownership.id) so the name
 * follows the vehicle from the ground to the air. Stored in vehicle-names.json. A name is trimmed, 3 to 24 characters
 * and unique, ignoring case, among the living vehicles of the same owner.
 */
final class VehicleNames {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int VERSION = 1;
    static final int MIN = 3;
    static final int MAX = 24;
    private static final String[] KINDS = {Deployables.AIRSHIP_KIND, Deployables.STEAMBOAT_KIND, Deployables.BALLOON_KIND};

    /** A vehicle of an owner: structure id and type. */
    record Owned(UUID id, String kind) {
    }

    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();
    private static volatile Path file;

    private VehicleNames() {
    }

    static void init(Path dataDirectory) {
        file = dataDirectory.resolve("vehicle-names.json");
        NAMES.clear();
        load();
    }

    /** The name trimmed, null when shorter than 3 or longer than 24 characters. */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return s.length() < MIN || s.length() > MAX ? null : s;
    }

    /** True when another vehicle of the same owner (the names map) already has this name, ignoring case. */
    static boolean taken(String name, UUID self, Map<UUID, String> namesOfOwner) {
        for (Map.Entry<UUID, String> e : namesOfOwner.entrySet()) {
            if (!e.getKey().equals(self) && e.getValue() != null && e.getValue().trim().equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Smallest N of 1 or more such that "typeLabel N" is the name of no vehicle of this type. */
    static int nextNumber(String kind, List<Owned> owned, Map<UUID, String> names, String typeLabel) {
        for (int n = 1; ; n++) {
            String candidate = typeLabel + " " + n;
            boolean used = false;
            for (Owned o : owned) {
                String name = names.get(o.id());
                if (o.kind().equals(kind) && name != null && name.trim().equalsIgnoreCase(candidate)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return n;
            }
        }
    }

    static String get(UUID id) {
        return id == null ? null : NAMES.get(id);
    }

    static boolean named(UUID id) {
        return id != null && NAMES.containsKey(id);
    }

    /** Sets the name of a vehicle and writes the file at once. */
    static void set(UUID id, String name) {
        if (id == null || name == null) {
            return;
        }
        NAMES.put(id, name);
        save();
    }

    /** The names of the given vehicles, the table entries only. */
    static Map<UUID, String> namesOf(List<Owned> owned) {
        Map<UUID, String> out = new HashMap<>();
        for (Owned o : owned) {
            String n = NAMES.get(o.id());
            if (n != null) {
                out.put(o.id(), n);
            }
        }
        return out;
    }

    /** The vehicles of this owner: registry entries of the named types in every world, plus the vehicles in flight. */
    static List<Owned> ownedVehicles(UUID owner) {
        List<Owned> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        try {
            for (String kind : KINDS) {
                for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKindAllWorlds(kind)) {
                    add(out, seen, owner, e.ownership(), kind);
                }
            }
            for (AirshipFlight f : AirshipManager.get().flights()) {
                add(out, seen, owner, f.ownership, f.kind.id);
            }
            for (BalloonFlight f : BalloonManager.get().flights()) {
                add(out, seen, owner, f.ownership, f.kind.id);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vehicles of an owner not listed");
            Telemetry.error("vehicle-name", e, null, "list");
        }
        return out;
    }

    private static void add(List<Owned> out, Set<UUID> seen, UUID owner, Ownership own, String kind) {
        if (own == null || own.id() == null || !owner.equals(own.owner()) || !isNamedKind(kind) || !seen.add(own.id())) {
            return;
        }
        out.add(new Owned(own.id(), kind));
    }

    private static boolean isNamedKind(String kind) {
        for (String k : KINDS) {
            if (k.equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /** Label of a type in the player's language (English when the key is missing). */
    static String typeLabel(PlayerRef player, String kind) {
        String key = "server.orbis_horizon.vehicle.type." + kind;
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n != null) {
                String text = player == null ? null : i18n.getMessage(player.getLanguage(), key);
                if (text == null) {
                    text = i18n.getMessage("en-US", key);
                }
                if (text != null) {
                    return text;
                }
            }
        } catch (RuntimeException e) {
            // English label below.
        }
        return switch (kind) {
            case Deployables.AIRSHIP_KIND -> "Airship";
            case Deployables.STEAMBOAT_KIND -> "Steamboat";
            default -> "Large balloon";
        };
    }

    /** "Type N" with the smallest free number among this owner's vehicles of the type. */
    static String autoName(PlayerRef owner, String kind) {
        String label = typeLabel(owner, kind);
        List<Owned> owned = ownedVehicles(owner.getUuid());
        return label + " " + nextNumber(kind, owned, namesOf(owned), label);
    }

    private static void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(f, StandardCharsets.UTF_8));
            BsonDocument names = doc.getDocument("names");
            for (String key : names.keySet()) {
                NAMES.put(UUID.fromString(key), names.getString(key).getValue());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Vehicle names unreadable: %s", f);
            NAMES.clear();
            try {
                Files.move(f, f.resolveSibling(f.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The file stays in place.
            }
        }
    }

    /** Writes the whole table (temporary file then atomic rename). */
    private static synchronized void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            BsonDocument names = new BsonDocument();
            for (Map.Entry<UUID, String> e : NAMES.entrySet()) {
                names.put(e.getKey().toString(), new BsonString(e.getValue()));
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("names", names);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vehicle names not written");
            Telemetry.error("vehicle-name", e, null, "save");
        }
    }
}
