package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.Pair;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * The station master: the NPC standing in the hut of a balloon station (Server/NPC/Roles/Station_Master.json).
 *
 * The NPC is NOT Frozen. Frozen stops the role tick, so the last MovementStates (walk or run) stayed set and the clients kept
 * animating them (seen in the spike, and on frozen sheep). The role has no motion at all (BodyMotion Nothing, like the game's
 * static NPCs, for example Component_Instruction_Stationary_Idle) and the entity is Invulnerable, so it neither wanders nor
 * dies, and its idle animation is the normal one.
 *
 * Talking: the NPC entity carries an Interactable marker and an Interactions component whose Use entry names the root interaction
 * Station_Master_Use (the game sets Use to *UseNPC on every NPC when it is created, so it is overwritten after the spawn, and
 * again at each world load). The hint is ours, not the game's "Trade".
 *
 * Reservation: one player at a time may talk to a master. It is released after 8 blocks, 60 s, death or disconnection.
 * onTalk is the single entry point: it opens the destination window (DestinationPage), which keeps the reservation until it ends.
 */
final class StationMaster {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String ROLE = "Station_Master";
    static final String ROOT_INTERACTION_ID = "Station_Master_Use";
    static final String HINT = "server.orbis_horizon.interactionHints.stationMaster";

    private StationMaster() {
    }

    // ------------------------------------------------------------------ position

    /** World position (feet) of the master of a station: centre of its prefab cell, rotated with the station. Pure. */
    static Vector3d masterPoint(TransitNetwork.Station s) {
        Vector3d logical = new Vector3d(s.origin.x + 0.5, s.origin.y, s.origin.z + 0.5);
        int[] p = Deployables.STATION_MASTER_POS;
        Vector3d out = BalloonManager.prefabPoint(logical, s.rotation, new Vector3f(p[0], p[1], p[2]));
        return out.add(0, 0.05, 0);
    }

    // ------------------------------------------------------------------ spawn, find, remove

    /** Creates the master NPC of a station and stores its UUID in the station. Null if it cannot be created. On the world thread. */
    static UUID spawn(Store<EntityStore> store, World world, TransitNetwork.Station s) {
        // A deferred spawn may run after the station was removed: never leave an orphan NPC.
        if (!TransitNetwork.get().stations(world.getName()).contains(s)) {
            return null;
        }
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null || plugin.getIndex(ROLE) < 0) {
                LOGGER.at(Level.WARNING).log("Rôle %s introuvable : pas de chef de gare pour « %s »", ROLE, s.name);
                return null;
            }
            Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> p =
                    plugin.spawnNPC(store, ROLE, null, masterPoint(s), yawRotation(facingYaw(s)));
            if (p == null || p.first() == null || !p.first().isValid()) {
                LOGGER.at(Level.WARNING).log("Chef de gare de « %s » : création impossible", s.name);
                return null;
            }
            Ref<EntityStore> ref = p.first();
            wire(store, ref, s);
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            if (id == null) {
                return null;
            }
            TransitNetwork.get().setMaster(s, id.getUuid());
            LOGGER.at(Level.INFO).log("Chef de gare de « %s » créé (%s)", s.name, id.getUuid());
            return id.getUuid();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Chef de gare de « %s » non créé", s.name);
            return null;
        }
    }

    /**
     * Stations that are locked in the registry but never joined the network (server stopped or crashed before the name was
     * validated) are registered with their automatic name. completeRegistration creates the master through the usual path.
     */
    static void registerOrphans(World world) {
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.STATION_KIND)) {
            org.joml.Vector3i origin = new org.joml.Vector3i(e.x(), e.y(), e.z());
            if (TransitNetwork.get().at(world.getName(), origin) == null) {
                LOGGER.at(Level.INFO).log("Station posée en (%d, %d, %d) sans nom : enregistrée avec un nom automatique",
                        origin.x, origin.y, origin.z);
                DeployStationInteraction.completeRegistration(world, origin, e.rotation(), e.owner(), null, null);
            }
        }
    }

    /**
     * The master has the player model (role Appearance "Player"): he gets a player skin drawn from a generator seeded with his
     * station (world and origin), so each station has its own master and keeps him across restarts, with the station cap
     * (MASTER_CAP). Only when he has no skin with the cap yet.
     * The game draws such skins for its own NPCs (ModelSystems$ApplyRandomSkin, CosmeticsModule.generateRandomSkin). Never throws.
     */
    /**
     * Head accessory of every master: the workout cap of the character creator (Cosmetics/CharacterCreator/HeadAccessory.json,
     * id WorkoutCap, gradient set Colored_Cotton) in blue, written "Id.Colour" like the skins the game draws.
     */
    static final String MASTER_CAP = "WorkoutCap.Blue";

    private static void ensureSkin(Store<EntityStore> store, Ref<EntityStore> ref, TransitNetwork.Station s) {
        try {
            com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent have =
                    store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent.getComponentType());
            if (have != null && have.getPlayerSkin() != null && MASTER_CAP.equals(have.getPlayerSkin().headAccessory)) {
                return;
            }
            long seed = java.util.Objects.hash(s.world, s.origin.x, s.origin.y, s.origin.z);
            com.hypixel.hytale.protocol.PlayerSkin skin =
                    com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get().generateRandomSkin(new java.util.Random(seed));
            if (skin != null) {
                skin.headAccessory = MASTER_CAP;
                com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent c =
                        new com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent(skin);
                c.setNetworkOutdated(); // sent again to the clients that already see the master
                store.putComponent(ref, com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent.getComponentType(), c);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Apparence du chef de gare de « %s » non posée", s.name);
        }
    }

    /** Makes an existing NPC entity the station's master: invulnerable, talkable, named. Safe to call again. */
    private static void wire(Store<EntityStore> store, Ref<EntityStore> ref, TransitNetwork.Station s) {
        ensureSkin(store, ref, s);
        store.putComponent(ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        store.ensureComponent(ref, Interactable.getComponentType());
        Interactions interactions = store.getComponent(ref, Interactions.getComponentType());
        if (interactions == null) {
            interactions = new Interactions();
            store.putComponent(ref, Interactions.getComponentType(), interactions);
        }
        interactions.setInteractionId(InteractionType.Use, ROOT_INTERACTION_ID);
        interactions.setInteractionHint(HINT);
        store.putComponent(ref, DisplayNameComponent.getComponentType(),
                new DisplayNameComponent(Texts.t("transit.master.name").param("station", s.name)));
    }

    /** Master NPC of a station, found by UUID, or null. */
    private static Ref<EntityStore> refOf(World world, TransitNetwork.Station s) {
        UUID id = s.masterUuid;
        if (id == null) {
            return null;
        }
        Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(id);
        return ref != null && ref.isValid() ? ref : null;
    }

    /** Removes the master NPC of a station (station removed) and forgets it. On the world thread. */
    static void remove(Store<EntityStore> store, TransitNetwork.Station s) {
        try {
            World world = store.getExternalData().getWorld();
            Ref<EntityStore> ref = refOf(world, s);
            if (ref != null) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Chef de gare de « %s » non supprimé", s.name);
        }
        RESERVATIONS.remove(s);
        LAST_REWIRE.remove(s);
        PENDING.removeIf(p -> p.station() == s);
        TransitNetwork.get().setMaster(s, null);
    }

    static TransitNetwork.Station stationOfMaster(String world, UUID master) {
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world)) {
            if (master.equals(s.masterUuid)) {
                return s;
            }
        }
        return null;
    }

    /** Station whose master stands nearest to a position, within maxDist blocks, or null. */
    static TransitNetwork.Station nearestMaster(String world, Vector3d pos, double maxDist) {
        TransitNetwork.Station best = null;
        double bestD = maxDist * maxDist;
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world)) {
            double d = masterPoint(s).distanceSquared(pos);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ world load

    /** Station waiting for its master entity after a world load. */
    private record Pending(World world, TransitNetwork.Station station, long deadlineMs) {
    }

    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    /** Time given to the world to load the entity before a master not found by UUID is recreated (avoids duplicates). */
    private static final long RECOVER_WAIT_MS = 10_000;

    /** World load and plugin start: every station of the world gets its master back (found by UUID, else recreated). */
    static void recoverWorld(World world) {
        registerOrphans(world);
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            ensure(world, s);
        }
    }

    /**
     * Loads the chunk of the master, then waits up to RECOVER_WAIT_MS for the entity to be found by UUID (tickReservations
     * resolves it). A station without a master (never created, or lost) gets one at once.
     */
    static void ensure(World world, TransitNetwork.Station s) {
        try {
            Vector3d p = masterPoint(s);
            int cx = ChunkUtil.chunkCoordinate((int) Math.floor(p.x));
            int cz = ChunkUtil.chunkCoordinate((int) Math.floor(p.z));
            world.getChunkAsync(ChunkUtil.indexChunk(cx, cz)).whenComplete((c, t) -> {
                try {
                    world.execute(() -> PENDING.add(new Pending(world, s,
                            s.masterUuid == null ? 0 : System.currentTimeMillis() + RECOVER_WAIT_MS)));
                } catch (RuntimeException ex) {
                    LOGGER.at(Level.WARNING).withCause(ex).log("Monde %s arrêté : chef de gare de « %s » non retrouvé", world.getName(), s.name);
                }
            });
        } catch (RuntimeException ex) {
            // At plugin start a world that has not finished starting is not ready: StartWorldEvent calls again.
            LOGGER.at(Level.INFO).log("Monde %s pas encore prêt : chef de gare de « %s » repris au démarrage du monde (%s)",
                    world.getName(), s.name, ex.getClass().getSimpleName());
        }
    }

    private static void resolvePending(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Pending p : new ArrayList<>(PENDING)) {
            if (p.world() != world) {
                continue;
            }
            Ref<EntityStore> ref = refOf(world, p.station());
            if (ref != null) {
                PENDING.remove(p);
                try {
                    wire(store, ref, p.station());
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Chef de gare de « %s » non remis en état", p.station().name);
                }
            } else if (now >= p.deadlineMs()) {
                PENDING.remove(p);
                if (TransitNetwork.get().stations(world.getName()).contains(p.station())) {
                    spawn(store, world, p.station());
                }
            }
        }
    }

    /** Interval between two checks of the Use wiring of a master. */
    private static final long REWIRE_INTERVAL_MS = 2_000;
    private static final Map<TransitNetwork.Station, Long> LAST_REWIRE = new ConcurrentHashMap<>();

    /**
     * The game rebuilds the role (and resets Use to *UseNPC) when the chunk is reloaded during a session: every 2 s per station,
     * if the master entity is present and its Use is not ours, the wiring is applied again.
     */
    private static void rewire(Store<EntityStore> store, World world) {
        long now = System.currentTimeMillis();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            if (s.masterUuid == null) {
                continue;
            }
            Long last = LAST_REWIRE.get(s);
            if (last != null && now - last < REWIRE_INTERVAL_MS) {
                continue;
            }
            LAST_REWIRE.put(s, now);
            try {
                Ref<EntityStore> ref = refOf(world, s);
                if (ref == null) {
                    continue;
                }
                ensureSkin(store, ref, s);
                Interactions i = store.getComponent(ref, Interactions.getComponentType());
                if (i == null || !ROOT_INTERACTION_ID.equals(i.getInteractionId(InteractionType.Use))) {
                    wire(store, ref, s);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Câblage du chef de gare de « %s » non vérifié", s.name);
            }
        }
    }

    // ------------------------------------------------------------------ facing

    /**
     * Prefab point the master looks at: the foot of the station stairs (the first step is the row of Rock_Stone_Brick_Stairs at
     * x = 6, z -2 to 2, y = 0 of the prefab of 11 October 2026), so that he faces the players who come up to the station.
     */
    private static final Vector3f STAIRS_FOOT = new Vector3f(5, 0, 0);

    private static Rotation3f yawRotation(float yaw) {
        Rotation3f r = new Rotation3f();
        r.setYaw(yaw);
        return r;
    }

    /** World yaw of a master facing the foot of the stairs of his station (forward = (-sin yaw, -cos yaw)). Pure. */
    static float facingYaw(TransitNetwork.Station s) {
        Vector3d logical = new Vector3d(s.origin.x + 0.5, s.origin.y, s.origin.z + 0.5);
        Vector3d foot = BalloonManager.prefabPoint(logical, s.rotation, STAIRS_FOOT);
        Vector3d me = masterPoint(s);
        return (float) Math.atan2(-(foot.x - me.x), -(foot.z - me.z));
    }

    /** Every tick: each master of the world faces the foot of his stairs (body and head), whatever his role does with them. */
    private static void faceStairs(Store<EntityStore> store, World world) {
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            if (s.masterUuid == null) {
                continue;
            }
            try {
                Ref<EntityStore> ref = refOf(world, s);
                if (ref == null) {
                    continue;
                }
                float yaw = facingYaw(s);
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                if (t != null && Math.abs(Math.IEEEremainder(t.getRotation().y - yaw, 2 * Math.PI)) > 1e-3) {
                    Rotation3f r = new Rotation3f();
                    r.setYaw(yaw);
                    t.setRotation(r);
                }
                com.hypixel.hytale.server.core.modules.entity.component.HeadRotation h =
                        store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType());
                if (h != null && (Math.abs(Math.IEEEremainder(h.getRotation().y - yaw, 2 * Math.PI)) > 1e-3 || Math.abs(h.getRotation().x) > 1e-3)) {
                    Rotation3f r = new Rotation3f();
                    r.setYaw(yaw);
                    h.setRotation(r);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Orientation du chef de gare de « %s » non réglée", s.name);
            }
        }
    }

    // ------------------------------------------------------------------ reservation

    private record Reservation(UUID player, long sinceMs, String world) {
    }

    /** Keyed by the Station object (identity), so a station keeps its reservation when its master changes. */
    private static final Map<TransitNetwork.Station, Reservation> RESERVATIONS = new ConcurrentHashMap<>();

    /** Reserves the master for a player. True if he now holds it (or already did), false if somebody else holds it. Pure. */
    static boolean reserve(TransitNetwork.Station s, UUID player) {
        Reservation fresh = new Reservation(player, System.currentTimeMillis(), s.world);
        Reservation held = RESERVATIONS.compute(s, (k, old) -> old == null || old.player().equals(player)
                ? (old == null ? fresh : old) : old);
        return held.player().equals(player);
    }

    /** True if this player currently holds the reservation. Pure. */
    static boolean holds(TransitNetwork.Station s, UUID player) {
        Reservation r = RESERVATIONS.get(s);
        return r != null && r.player().equals(player);
    }

    /** Releases the reservation if this player holds it. Pure. */
    static void release(TransitNetwork.Station s, UUID player) {
        RESERVATIONS.computeIfPresent(s, (k, old) -> old.player().equals(player) ? null : old);
    }

    /** Each tick of a world: resolves masters waiting for their entity, then releases stale reservations (8 blocks, 60 s, death, disconnection). */
    static void tickReservations(Store<EntityStore> store, World world) {
        StationNamePage.sweep(world.getName());
        resolvePending(store, world);
        rewire(store, world);
        faceStairs(store, world);
        if (RESERVATIONS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<TransitNetwork.Station, Reservation> e : RESERVATIONS.entrySet()) {
            TransitNetwork.Station s = e.getKey();
            Reservation r = e.getValue();
            if (!r.world().equals(world.getName())) {
                continue;
            }
            boolean drop = now - r.sinceMs() >= TransitConstants.MASTER_RELEASE_SECONDS * 1000L;
            if (!drop) {
                PlayerRef pr = null;
                for (PlayerRef other : world.getPlayerRefs()) {
                    if (other.getUuid().equals(r.player())) {
                        pr = other;
                        break;
                    }
                }
                Ref<EntityStore> ref = pr == null ? null : pr.getReference();
                if (ref == null || !ref.isValid() || BalloonManager.isDead(store, ref)) {
                    drop = true; // disconnected or dead
                } else {
                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    double max = TransitConstants.MASTER_RELEASE_DISTANCE;
                    drop = t == null || masterPoint(s).distanceSquared(new Vector3d(t.getPosition())) > max * max;
                }
            }
            if (drop) {
                release(s, r.player());
            }
        }
    }

    // ------------------------------------------------------------------ talking

    /**
     * A player talks to the master (Use on the NPC). Single entry point: Task 7 replaces the chat list by the destination window.
     * Busy master: transit.master.busy. On the world thread.
     */
    static void onTalk(Store<EntityStore> store, Ref<EntityStore> playerRef, TransitNetwork.Station s) {
        PlayerRef player = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (player == null) {
            return;
        }
        if (!reserve(s, player.getUuid())) {
            player.sendMessage(Texts.t("transit.master.busy"));
            return;
        }
        // The window keeps the reservation until it ends (DestinationPage releases it on any close).
        try {
            Player p = store.getComponent(playerRef, Player.getComponentType());
            if (p == null) {
                release(s, player.getUuid());
                return;
            }
            p.getPageManager().openCustomPage(playerRef, store, new DestinationPage(player, s));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Fenêtre de destination impossible pour « %s »", s.name);
            release(s, player.getUuid());
        }
    }
}
