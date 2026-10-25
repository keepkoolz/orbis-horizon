package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The pilot standing at the helm of the maritime shuttle while it waits moored in blocks at its pier (the 30 s countdown), with the
 * look of the flight mannequin (the departure station master's skin, standing pose, facing the bow). A standalone entity (the moored
 * shuttle has no ship entity to mount on): Intangible and never saved with the world, so nothing survives a restart; its UUID depends
 * only on the berth, so a leftover is found and removed. Created and kept by {@link BoatQuay} while DOCKED, removed by
 * {@link AirshipManager#unmoorShuttle} (departure, removal of the shuttle or of the station). Never throws.
 */
final class MooredPilot {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** A missing or invalid pilot is recreated at most this often. */
    private static final long RETRY_MS = 3_000L;

    private static final class Entry {
        AirshipCrew.Mannequin man;
        long nextTryMs;
        boolean warned;
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private MooredPilot() {
    }

    /** Deterministic entity id of the pilot of the shuttle moored at this berth origin. Pure. */
    static UUID uuidFor(String world, Vector3i origin) {
        return UUID.nameUUIDFromBytes(("orbis_horizon_moored_pilot:" + world + ":" + origin.x + "," + origin.y + "," + origin.z)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** World point where the pilot stands: the pivot of the moored shuttle (where the flight mannequin starts). Pure. */
    static Vector3d helmPoint(TransitNetwork.Station s) {
        return BoatRoutes.berthPivot(s);
    }

    /** Yaw that turns the pilot to the bow of the moored shuttle (forward = (-sin yaw, -cos yaw), the heading of the shuttle). Pure. */
    static float facingYaw(TransitNetwork.Station s) {
        return (float) BoatRoutes.berthTheta(s);
    }

    /** One tick of a DOCKED quay: creates the pilot if missing, replays his pose. Never throws. */
    static void tick(Store<EntityStore> store, World world, TransitNetwork.Station s, long now) {
        try {
            Vector3i origin = BoatRoutes.berthOrigin(s);
            UUID id = uuidFor(world.getName(), origin);
            Entry e = ENTRIES.computeIfAbsent(id, k -> new Entry());
            AirshipCrew.Mannequin m = e.man;
            if (m != null && m.ref != null && m.ref.isValid()) {
                AirshipCrew.syncMannequin(store, m, now);
                return;
            }
            if (now < e.nextTryMs) {
                return;
            }
            e.nextTryMs = now + RETRY_MS;
            e.man = null;
            Vector3d at = helmPoint(s);
            if (!SectionBlocks.entitiesLoaded(world.getChunkStore(), (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z))) {
                return; // nobody near: an entity added to a section that does not tick would be stored away and doubled when it wakes up
            }
            Ref<EntityStore> old = world.getEntityStore().getRefFromUUID(id); // a leftover of a lost state
            if (old != null && old.isValid()) {
                store.removeEntity(old, RemoveReason.REMOVE);
            }
            Object[] look = BoatAutopilot.masterLook(store, world, s);
            if (look == null) {
                if (!e.warned) {
                    e.warned = true;
                    LOGGER.at(Level.WARNING).log("Station maritime %s : pas de pilote à la barre de la navette à quai (apparence du chef de gare introuvable)", s.name);
                }
                return;
            }
            e.man = AirshipCrew.createStandalone(store, id, at, facingYaw(s), look, now, AirshipCrew.POSE_STAND);
            if (e.man == null) {
                if (!e.warned) {
                    e.warned = true;
                    LOGGER.at(Level.WARNING).log("Station maritime %s : pas de pilote à la barre de la navette à quai (création de l'entité refusée)", s.name);
                }
            } else {
                e.warned = false;
                LOGGER.at(Level.INFO).log("Station maritime %s : pilote à la barre de la navette à quai", s.name);
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Station maritime %s : pilote de la navette à quai en échec", s.name);
            Telemetry.error("transit", ex, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
        }
    }

    /** Removes the pilot of the shuttle moored at this origin (it leaves, is removed, or the station is). Safe to repeat. Never throws. */
    static void remove(World world, Vector3i origin) {
        try {
            UUID id = uuidFor(world.getName(), origin);
            Entry e = ENTRIES.remove(id);
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (e != null && e.man != null) {
                AirshipCrew.removeMannequin(store, e.man);
            }
            Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(id);
            if (ref != null && ref.isValid()) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilote de la navette à quai non retiré");
            Telemetry.error("transit", ex, Telemetry.kindId(Deployables.STEAMBOAT_PUBLIC), null);
        }
    }
}
