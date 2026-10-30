package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Passenger protection: while aboard, a passenger cannot be damaged (DamageFilter) and is not detected by NPCs
 * (Intangible marker). The pilot is never protected. The mod only removes an Intangible marker it added itself and never
 * removes it from a spectating player. The world of a reference is read with store.getExternalData().getWorld().
 */
public final class PassengerProtection {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public enum Mode { FULL, DAMAGE, OFF }

    private record Entry(World world, boolean addedByMod) {}

    private static final Map<Ref<EntityStore>, Entry> PROTECTED = new ConcurrentHashMap<>();
    private static volatile Mode mode = Mode.FULL;
    /**
     * Players on whom the mod placed the Intangible marker and has not removed it yet. Persisted in protected.json
     * (the marker is saved with the player): survives a world change (old reference invalid) and a crash.
     */
    private static final Set<UUID> MARKED = ConcurrentHashMap.newKeySet();
    private static volatile Path file;

    private PassengerProtection() {}

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode m) {
        if (m != null) {
            mode = m;
            save();
        }
    }

    /** The marker is added only in FULL mode and only if the player had none. */
    static boolean shouldAdd(boolean hadMarker, Mode m) {
        return m == Mode.FULL && !hadMarker;
    }

    /** The marker is removed only if the mod added it and the player is not spectating. */
    static boolean shouldRemove(boolean addedByMod, boolean spectating) {
        return addedByMod && !spectating;
    }

    /** Reads protected.json: the marked players and the mode. Missing or unknown mode: FULL. Missing file: empty. Unreadable: empty, and the file is kept as .bad. Never throws. */
    static synchronized void load(Path dataDir) {
        MARKED.clear();
        mode = Mode.FULL;
        try {
            file = dataDir.resolve("protected.json");
            if (!Files.isRegularFile(file)) return;
            BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (BsonValue v : doc.getArray("players")) {
                MARKED.add(UUID.fromString(v.asString().getValue()));
            }
            if (doc.containsKey("mode") && doc.get("mode").isString()) {
                try {
                    mode = Mode.valueOf(doc.getString("mode").getValue());
                } catch (IllegalArgumentException e) {
                    mode = Mode.FULL;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("protected.json unreadable, placed markers list empty");
            MARKED.clear();
            try {
                if (file != null && Files.isRegularFile(file)) {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Could not rename protected.json to .bad");
            }
        }
    }

    /** True if the mod placed the marker on this player and has not removed it. */
    static boolean isMarked(UUID uuid) {
        return uuid != null && MARKED.contains(uuid);
    }

    static void mark(UUID uuid) {
        if (uuid != null && MARKED.add(uuid)) {
            save();
        }
    }

    static void unmark(UUID uuid) {
        if (uuid != null && MARKED.remove(uuid)) {
            save();
        }
    }

    /** Atomic write (tmp file then rename). Never throws. */
    private static synchronized void save() {
        Path f = file;
        if (f == null) return;
        try {
            BsonArray arr = new BsonArray();
            for (UUID u : MARKED) {
                arr.add(new BsonString(u.toString()));
            }
            BsonDocument doc = new BsonDocument();
            doc.put("players", arr);
            doc.put("mode", new BsonString(mode.name()));
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Writing protected.json failed");
        }
    }

    public static boolean isProtected(Ref<EntityStore> ref) {
        return ref != null && PROTECTED.containsKey(ref);
    }

    public static boolean addedByMod(Ref<EntityStore> ref) {
        Entry e = ref == null ? null : PROTECTED.get(ref);
        return e != null && e.addedByMod();
    }

    public static void protect(ComponentAccessor<EntityStore> store, Ref<EntityStore> ref) {
        try {
            if (mode == Mode.OFF || ref == null || !ref.isValid() || PROTECTED.containsKey(ref)) {
                return;
            }
            boolean had = store.getComponent(ref, Intangible.getComponentType()) != null;
            UUID uuid = uuidOf(store, ref);
            boolean add = shouldAdd(had, mode);
            if (add) {
                store.putComponent(ref, Intangible.getComponentType(), Intangible.INSTANCE);
                mark(uuid);
            }
            // A marker the mod placed earlier and could not remove (listed) still belongs to the mod.
            boolean ours = add || (had && isMarked(uuid));
            PROTECTED.put(ref, new Entry(store.getExternalData().getWorld(), ours));
            LOGGER.at(Level.INFO).log("Passenger protection " + name(store, ref) + " : enabled (marker placed: " + (add ? "yes" : "no") + ")");
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Passenger protection: enabling failed");
        }
    }

    public static void release(ComponentAccessor<EntityStore> store, Ref<EntityStore> ref) {
        try {
            Entry e = ref == null ? null : PROTECTED.remove(ref);
            if (e == null) {
                return;
            }
            if (ref.isValid()) {
                boolean spectating = store.getComponent(ref, Spectating.getComponentType()) != null;
                if (shouldRemove(e.addedByMod(), spectating)) {
                    store.tryRemoveComponent(ref, Intangible.getComponentType());
                    unmark(uuidOf(store, ref));
                }
                LOGGER.at(Level.INFO).log("Passenger protection " + name(store, ref) + " : removed");
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Passenger protection: removal failed");
        }
    }

    /** Releases every protected player of this world that is not in stillAboard. */
    public static void sweep(ComponentAccessor<EntityStore> store, World world, Set<Ref<EntityStore>> stillAboard) {
        try {
            List<Ref<EntityStore>> gone = new ArrayList<>();
            for (Map.Entry<Ref<EntityStore>, Entry> e : PROTECTED.entrySet()) {
                if (e.getValue().world() == world && (stillAboard == null || !stillAboard.contains(e.getKey()))) {
                    gone.add(e.getKey());
                }
            }
            for (Ref<EntityStore> r : gone) {
                LOGGER.at(Level.WARNING).log("Protection removed by the sweep: " + name(store, r));
                release(store, r);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Passenger protection: cleanup failed");
        }
    }

    /**
     * After a restart the in-memory registry is lost: removes the marker only if the mod placed it (UUID listed in
     * protected.json) and the player is not spectating.
     */
    public static void clearStale(ComponentAccessor<EntityStore> store, Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid() || PROTECTED.containsKey(ref)) {
                return;
            }
            UUID uuid = uuidOf(store, ref);
            if (isMarked(uuid) && store.getComponent(ref, Spectating.getComponentType()) == null) {
                store.tryRemoveComponent(ref, Intangible.getComponentType());
                unmark(uuid);
                LOGGER.at(Level.INFO).log("Leftover mod marker removed: " + name(store, ref));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Passenger protection: leftover marker not removed");
        }
    }

    /**
     * A player arrives in a world (join or world change): a marker the mod placed and could not remove (old
     * reference invalid, crash) is removed from the holder unless the player is spectating. Never throws.
     */
    static void clearOnArrival(com.hypixel.hytale.component.Holder<EntityStore> holder, UUID uuid) {
        try {
            if (holder == null || !isMarked(uuid)) {
                return;
            }
            if (holder.getComponent(Spectating.getComponentType()) == null) {
                holder.tryRemoveComponent(Intangible.getComponentType());
                unmark(uuid);
                LOGGER.at(Level.INFO).log("Mod marker removed on player arrival " + uuid);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Passenger protection: marker not removed on arrival");
        }
    }

    private static UUID uuidOf(ComponentAccessor<EntityStore> store, Ref<EntityStore> ref) {
        try {
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            return pr != null ? pr.getUuid() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String name(ComponentAccessor<EntityStore> store, Ref<EntityStore> ref) {
        try {
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            return pr != null ? pr.getUsername() : "?";
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Cancels the damage of a protected player (model: DamageSystems$FilterUnkillable). */
    public static final class DamageFilter extends DamageEventSystem {
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getFilterDamageGroup();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, Damage damage) {
            try {
                if (isProtected(chunk.getReferenceTo(index))) {
                    damage.setCancelled(true);
                }
            } catch (RuntimeException e) {
                // never throws from a system
            }
        }
    }
}
