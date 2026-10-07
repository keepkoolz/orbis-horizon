package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Model parts. The client keeps the node matrices of an entity model in a uniform block with a fixed size (probably 1024 nodes on
 * a GTX 1060): the nodes beyond are not drawn. The generator therefore splits a big flight model into parts of at most 1000 nodes,
 * part 0 being the model of the type (with the particles and the glowing engine) and the following ones the assets
 * "<BaseId>_Part1", "<BaseId>_Part2"... (same pivot and same root, no particles). Each part is carried by its own entity, placed exactly on
 * the entity it completes and with the same yaw, mounted on it with a zero offset (like the lights).
 *
 * A flight has a Group: the parts of the entity mounted on the pilot (main) and, with dual rendering (T82), the parts of the observer
 * entity (obs). Visibility rules follow the carrying entity. Model changes (flame, off, boost, thrust) only concern part 0.
 */
final class VehicleParts {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Setting (/orbishorizon balloon|airship render parts:on|off|noyaw), read at take-off. */
    enum Mode {
        ON, OFF, NOYAW;

        static Mode parse(String s) {
            for (Mode m : values()) {
                if (m.name().equalsIgnoreCase(s)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** Upper bound of the parts looked for (and of the orphan search). */
    static final int MAX_PARTS = 16;

    static final String PART_KEY = "part";

    /** State of the parts of a flight. */
    static final class Group {
        final List<String> ids = new ArrayList<>();
        final List<Ref<EntityStore>> main = new ArrayList<>();
        final List<Ref<EntityStore>> obs = new ArrayList<>();
        final boolean copyYaw;
        /** Parts that could not be created (warning logged). */
        int failed;

        Group(boolean copyYaw) {
            this.copyYaw = copyYaw;
        }
    }

    private VehicleParts() {
    }

    /** Ids of the additional parts of a model: base_Part1, base_Part2... up to the first missing one. */
    static List<String> partIds(String baseId) {
        List<String> ids = new ArrayList<>();
        if (baseId == null) {
            return ids;
        }
        for (int k = 1; k <= MAX_PARTS; k++) {
            String id = baseId + "_Part" + k;
            if (ModelAsset.getAssetMap().getAsset(id) == null) {
                break;
            }
            ids.add(id);
        }
        return ids;
    }

    /** Deterministic UUID of part k (1-based) of the entity (observer entity: obs true). */
    static UUID uuidFor(UUID mainUuid, int k, boolean obs) {
        return BalloonLights.uuidFor(mainUuid, PART_KEY + k + (obs ? BalloonLights.TWIN_SUFFIX : ""));
    }

    /**
     * Creates the parts of the main entity and, if there is an observer entity, its parts. Rules: main parts only for the pilot, observer
     * parts hidden from the pilot (only with an observer). Returns null if there is nothing to create (setting off, no part asset).
     * Never throws, a part that cannot be created is skipped with a warning in the log.
     */
    static Group spawn(Store<EntityStore> store, Mode mode, String baseId, Ref<EntityStore> main, UUID mainUuid,
                       Ref<EntityStore> observer, UUID pilot) {
        if (mode == Mode.OFF || main == null || mainUuid == null) {
            return null;
        }
        List<String> ids = partIds(baseId);
        if (ids.isEmpty()) {
            return null;
        }
        Group g = new Group(mode != Mode.NOYAW);
        g.ids.addAll(ids);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Ref<EntityStore> p = spawnOne(store, main, uuidFor(mainUuid, i + 1, false), id);
            if (p == null) {
                g.failed++;
                continue;
            }
            g.main.add(p);
            if (observer != null && pilot != null) {
                ViewFilter.only(p, pilot);
                Ref<EntityStore> o = spawnOne(store, observer, uuidFor(mainUuid, i + 1, true), id);
                if (o == null) {
                    g.failed++;
                } else {
                    ViewFilter.hide(o, pilot);
                    g.obs.add(o);
                }
            }
        }
        if (g.failed > 0) {
            LOGGER.at(Level.WARNING).log("Parties de modèle : %d sur %d non créées pour %s, le vol continue sans elles", g.failed,
                    observer != null ? 2 * ids.size() : ids.size(), baseId);
        }
        return g;
    }

    /** Creates one part entity on the host entity (same position and yaw, mounted with a zero offset). Null with a warning if it fails. */
    private static Ref<EntityStore> spawnOne(Store<EntityStore> store, Ref<EntityStore> host, UUID uuid, String modelId) {
        try {
            if (host == null || !host.isValid()) {
                return null;
            }
            TransformComponent t = store.getComponent(host, TransformComponent.getComponentType());
            if (t == null) {
                return null;
            }
            Ref<EntityStore> part = BalloonManager.get().spawnModelEntity(store, new Vector3d(t.getPosition()), t.getRotation().y,
                    modelId, uuid);
            if (part == null) {
                return null;
            }
            store.putComponent(part, MountedComponent.getComponentType(),
                    new MountedComponent(host, new Vector3f(), MountController.Minecart));
            return part;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Partie de modèle %s impossible", modelId);
            return null;
        }
    }

    /** Copies the position (and yaw, unless noyaw) of each host entity to its parts. To be called after the hosts were moved and smoothed. */
    static void sync(Store<EntityStore> store, Group g, Ref<EntityStore> main, Ref<EntityStore> observer) {
        if (g == null) {
            return;
        }
        copy(store, main, g.main, g.copyYaw);
        copy(store, observer, g.obs, g.copyYaw);
    }

    private static void copy(Store<EntityStore> store, Ref<EntityStore> host, List<Ref<EntityStore>> parts, boolean yaw) {
        if (parts.isEmpty() || host == null || !host.isValid()) {
            return;
        }
        TransformComponent from = store.getComponent(host, TransformComponent.getComponentType());
        if (from == null) {
            return;
        }
        for (Ref<EntityStore> p : parts) {
            if (p == null || !p.isValid()) {
                continue;
            }
            TransformComponent to = store.getComponent(p, TransformComponent.getComponentType());
            if (to == null) {
                continue;
            }
            to.setPosition(new Vector3d(from.getPosition()));
            if (yaw) {
                Rotation3f cur = to.getRotation();
                Rotation3f want = from.getRotation();
                if (cur.y != want.y || cur.x != want.x || cur.z != want.z) {
                    to.setRotation(new Rotation3f(want));
                }
            }
        }
    }

    /** Dual rendering ends (T82): the observer parts go, the main parts become visible to everybody. */
    static void collapse(Store<EntityStore> store, Group g) {
        if (g == null) {
            return;
        }
        removeList(store, g.obs);
        for (Ref<EntityStore> p : g.main) {
            ViewFilter.clear(p);
        }
    }

    /** Removes all the parts and their rules. Never throws. */
    static void removeAll(Store<EntityStore> store, Group g) {
        if (g == null) {
            return;
        }
        removeList(store, g.obs);
        removeList(store, g.main);
    }

    private static void removeList(Store<EntityStore> store, List<Ref<EntityStore>> refs) {
        for (Ref<EntityStore> p : refs) {
            try {
                if (p == null) {
                    continue;
                }
                ViewFilter.clear(p);
                if (p.isValid()) {
                    store.removeEntity(p, RemoveReason.REMOVE);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Partie de modèle non supprimée");
            }
        }
        refs.clear();
    }

    /** Removes the parts an interrupted flight may have left (resume, deterministic UUIDs of the flying entity). Never throws. */
    static void removeOrphans(World world, Store<EntityStore> store, UUID mainUuid) {
        if (mainUuid == null) {
            return;
        }
        for (int k = 1; k <= MAX_PARTS; k++) {
            for (boolean obs : new boolean[]{false, true}) {
                try {
                    Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(uuidFor(mainUuid, k, obs));
                    if (ref != null && ref.isValid()) {
                        ViewFilter.clear(ref);
                        store.removeEntity(ref, RemoveReason.REMOVE);
                        LOGGER.at(Level.INFO).log("Partie de modèle %d%s restée dans le monde supprimée", k, obs ? " (observation)" : "");
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Partie de modèle %d restée dans le monde non supprimée", k);
                }
            }
        }
    }

    /** Spawns the parts of a still test model next to it (spawnmodel). Returns the refs created, never throws. */
    static List<Ref<EntityStore>> spawnStill(Store<EntityStore> store, String baseId, Vector3d pos, double yaw) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (String id : partIds(baseId)) {
            try {
                Ref<EntityStore> r = BalloonManager.get().spawnModelEntity(store, new Vector3d(pos), yaw, id);
                if (r != null) {
                    out.add(r);
                } else {
                    LOGGER.at(Level.WARNING).log("Partie de modèle %s non créée", id);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Partie de modèle %s impossible", id);
            }
        }
        return out;
    }

    /** Diagnostic text (English, technical values). */
    static String describe(Group g, Mode mode) {
        if (g == null) {
            return "none (setting " + mode.name().toLowerCase() + ")";
        }
        int alive = 0;
        for (Ref<EntityStore> r : g.main) {
            if (r != null && r.isValid()) {
                alive++;
            }
        }
        int aliveObs = 0;
        for (Ref<EntityStore> r : g.obs) {
            if (r != null && r.isValid()) {
                aliveObs++;
            }
        }
        return g.ids.size() + " part(s) " + g.ids + ", " + alive + " pilot-side entit" + (alive == 1 ? "y" : "ies") + ", " + aliveObs
                + " observer-side entit" + (aliveObs == 1 ? "y" : "ies") + ", yaw copy " + (g.copyYaw ? "on" : "off")
                + (g.failed > 0 ? ", " + g.failed + " failed" : "") + " (setting " + mode.name().toLowerCase() + ")";
    }
}
