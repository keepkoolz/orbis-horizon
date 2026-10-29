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
        int[] p = StationSpec.of(s.kind).masterPos();
        Vector3d out = BalloonManager.prefabPoint(logical, s.rotation, new Vector3f(p[0], p[1], p[2]));
        return out.add(0, 0.05, 0);
    }

    // ------------------------------------------------------------------ spawn, find, remove

    /** Creates the master NPC of a station and stores its UUID in the station. Null if it cannot be created (yet). On the world thread. */
    static UUID spawn(Store<EntityStore> store, World world, TransitNetwork.Station s) {
        return spawn(store, world, s, false);
    }

    /**
     * retried: the call comes from the recovery after a first failed attempt, so a new failure is not handed over again.
     * When the entities of the master's section are not loaded (a station built with nobody near), spawnNPC gives no usable entity
     * and the NPC would later appear without skin or interactions: nothing is created and the station goes to the recovery
     * (ensure, then resolvePending), which waits for the section and adopts a master standing there before creating one.
     */
    private static UUID spawn(Store<EntityStore> store, World world, TransitNetwork.Station s, boolean retried) {
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
            Vector3d point = masterPoint(s);
            if (!SectionBlocks.entitiesLoaded(world.getChunkStore(), (int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z))) {
                LOGGER.at(Level.INFO).log("Chef de gare de « %s » : section non chargée, il sera créé à son chargement", s.name);
                ensure(world, s, false);
                return null;
            }
            Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> p =
                    plugin.spawnNPC(store, ROLE, null, point, yawRotation(facingYaw(s)));
            if (p == null || p.first() == null || !p.first().isValid()) {
                if (retried) {
                    LOGGER.at(Level.WARNING).log("Chef de gare de « %s » : création impossible après un second essai", s.name);
                } else {
                    LOGGER.at(Level.WARNING).log("Chef de gare de « %s » : création impossible, confié à la reprise", s.name);
                    ensure(world, s, true);
                }
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
            Telemetry.error("transit", e, null, null);
            return null;
        }
    }

    /**
     * Stations that are locked in the registry but never joined the network (server stopped or crashed before the name was
     * validated) are registered with their automatic name. completeRegistration creates the master through the usual path.
     */
    static void registerOrphans(World world) {
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.STATION_KIND)) {
            registerOrphan(world, e, Deployables.STATION_KIND);
        }
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.BOAT_STATION_KIND)) {
            registerOrphan(world, e, Deployables.BOAT_STATION_KIND);
        }
    }

    private static void registerOrphan(World world, BalloonRegistry.Entry e, String kind) {
        {
            org.joml.Vector3i origin = new org.joml.Vector3i(e.x(), e.y(), e.z());
            if (TransitNetwork.get().at(world.getName(), origin) == null) {
                LOGGER.at(Level.INFO).log("Station posée en (%d, %d, %d) sans nom : enregistrée avec un nom automatique",
                        origin.x, origin.y, origin.z);
                DeployStationInteraction.completeRegistration(world, origin, e.rotation(), e.owner(), null, null, kind);
            }
        }
    }

    /**
     * The master has the player model (role Appearance "Player"): he gets a player skin drawn from a generator seeded with his
     * station (world and origin), so each station has its own master and keeps him across restarts, with the station cap
     * (MASTER_CAP) and a shaven face (no facial hair, no stubble). Only when his skin lacks the cap or still has a beard.
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
            if (have != null && have.getPlayerSkin() != null && MASTER_CAP.equals(have.getPlayerSkin().headAccessory)
                    && !bearded(have.getPlayerSkin())) {
                return;
            }
            long seed = java.util.Objects.hash(s.world, s.origin.x, s.origin.y, s.origin.z);
            com.hypixel.hytale.protocol.PlayerSkin skin =
                    com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get().generateRandomSkin(new java.util.Random(seed));
            if (skin != null) {
                skin.headAccessory = MASTER_CAP;
                shave(skin);
                com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent c =
                        new com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent(skin);
                c.setNetworkOutdated(); // sent again to the clients that already see the master
                store.putComponent(ref, com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent.getComponentType(), c);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Apparence du chef de gare de « %s » non posée", s.name);
            Telemetry.error("transit", e, null, null);
        }
    }

    private static final String STUBBLE_FACE = "Face_Stubble";

    static boolean bearded(com.hypixel.hytale.protocol.PlayerSkin skin) {
        return skin.facialHair != null || (skin.face != null && skin.face.startsWith(STUBBLE_FACE));
    }

    /** Removes the facial hair and turns a stubble face into the neutral one, keeping its colour. */
    static void shave(com.hypixel.hytale.protocol.PlayerSkin skin) {
        skin.facialHair = null;
        if (skin.face != null && skin.face.startsWith(STUBBLE_FACE)) {
            skin.face = PilotLook.replaceId(skin.face, "Face_Neutral");
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
                new DisplayNameComponent(Texts.t(StationSpec.of(s.kind).masterNameKey()).param("station", s.name)));
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
            Telemetry.error("transit", e, null, null);
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

    /** Station waiting for its master entity after a world load. readyMs: when its entities were first seen loaded (0: not yet). */
    private static final class Pending {
        final World world;
        final TransitNetwork.Station station;
        /** True when the station comes back after a failed creation: a second failure is not retried. */
        final boolean retried;
        long readyMs;

        Pending(World world, TransitNetwork.Station station, boolean retried) {
            this.world = world;
            this.station = station;
            this.retried = retried;
        }

        World world() {
            return world;
        }

        TransitNetwork.Station station() {
            return station;
        }
    }

    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    /**
     * Time left after the entities of the master's section are loaded (a player came near) before a master not found by UUID is
     * replaced: an orphan master standing there is adopted, otherwise one is created. Nothing is decided while the section does not
     * tick, because its entities are not in the world then (0.7, SectionBlocks.entitiesLoaded): deciding then gave a new master at
     * every restart, stored away with the section, and they all came back stacked under the real one.
     */
    private static final long ENTITY_GRACE_MS = 2_000;
    /** Half size of the box around the master point where masters of the station are looked for (stacked duplicates, orphans). */
    private static final double MASTER_BOX = 1.5;

    /** World load and plugin start: every station of the world gets its master back (found by UUID, else recreated). */
    static void recoverWorld(World world) {
        registerOrphans(world);
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            ensure(world, s);
        }
    }

    /**
     * Loads the section of the master, then hands the station to resolvePending (tickReservations), which waits for the entities of
     * that section to be in the world before finding, adopting or creating the master.
     */
    static void ensure(World world, TransitNetwork.Station s) {
        ensure(world, s, false);
    }

    private static void ensure(World world, TransitNetwork.Station s, boolean retried) {
        try {
            Vector3d p = masterPoint(s);
            int bx = (int) Math.floor(p.x), by = (int) Math.floor(p.y), bz = (int) Math.floor(p.z);
            SectionBlocks.loadSections(world.getChunkStore(), bx, by - 2, bz, bx, by + 2, bz).whenComplete((c, t) -> {
                try {
                    world.execute(() -> {
                        PENDING.removeIf(q -> q.station() == s);
                        PENDING.add(new Pending(world, s, retried));
                    });
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
            TransitNetwork.Station s = p.station();
            if (!TransitNetwork.get().stations(world.getName()).contains(s)) {
                PENDING.remove(p);
                continue;
            }
            try {
                Ref<EntityStore> ref = refOf(world, s);
                if (ref != null) {
                    PENDING.remove(p);
                    wire(store, ref, s);
                    removeDuplicates(store, s, ref);
                    continue;
                }
                Vector3d mp = masterPoint(s);
                if (!SectionBlocks.entitiesLoaded(world.getChunkStore(), (int) Math.floor(mp.x), (int) Math.floor(mp.y), (int) Math.floor(mp.z))) {
                    p.readyMs = 0; // nobody near: the entities of the section are stored away, nothing can be decided
                    continue;
                }
                if (p.readyMs == 0) {
                    p.readyMs = now;
                }
                if (now - p.readyMs < ENTITY_GRACE_MS) {
                    continue;
                }
                PENDING.remove(p);
                Ref<EntityStore> orphan = mastersAt(store, s).stream().findFirst().orElse(null);
                if (orphan != null) {
                    UUIDComponent id = store.getComponent(orphan, UUIDComponent.getComponentType());
                    if (id != null) {
                        TransitNetwork.get().setMaster(s, id.getUuid());
                        wire(store, orphan, s);
                        LOGGER.at(Level.INFO).log("Chef de gare de « %s » repris sur place (%s)", s.name, id.getUuid());
                        removeDuplicates(store, s, orphan);
                        continue;
                    }
                }
                spawn(store, world, s, p.retried);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Chef de gare de « %s » non remis en état", s.name);
                Telemetry.error("transit", e, null, null);
            }
        }
    }

    /** Station master NPCs (role ROLE) standing at the master point of a station. On the world thread. */
    private static List<Ref<EntityStore>> mastersAt(Store<EntityStore> store, TransitNetwork.Station s) {
        Vector3d mp = masterPoint(s);
        Vector3d min = new Vector3d(mp.x - MASTER_BOX, mp.y - 0.5, mp.z - MASTER_BOX);
        Vector3d max = new Vector3d(mp.x + MASTER_BOX, mp.y + 2.5, mp.z + MASTER_BOX);
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (Ref<EntityStore> ref : com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInBox(min, max, store)) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            com.hypixel.hytale.server.npc.entities.NPCEntity npc =
                    store.getComponent(ref, com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
            if (npc != null && ROLE.equals(npc.getRoleName())) {
                out.add(ref);
            }
        }
        return out;
    }

    /** Removes the other station masters stacked at the master point (left by older versions). On the world thread. */
    private static void removeDuplicates(Store<EntityStore> store, TransitNetwork.Station s, Ref<EntityStore> keep) {
        UUIDComponent keepId = store.getComponent(keep, UUIDComponent.getComponentType());
        if (keepId == null) {
            return;
        }
        int removed = 0;
        for (Ref<EntityStore> ref : mastersAt(store, s)) {
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            if (id != null && !keepId.getUuid().equals(id.getUuid())) {
                store.removeEntity(ref, RemoveReason.REMOVE);
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.at(Level.INFO).log("Chef de gare de « %s » : %d doublon(s) retiré(s)", s.name, removed);
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
                removeDuplicates(store, s, ref); // a stacked master coming back with its section later

            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Câblage du chef de gare de « %s » non vérifié", s.name);
                Telemetry.error("transit", e, null, null);
            }
        }
    }

    // ------------------------------------------------------------------ facing

    /** A rotation holding only a yaw. */
    private static Rotation3f yawRotation(float yaw) {
        Rotation3f r = new Rotation3f();
        r.setYaw(yaw);
        return r;
    }

    /** World yaw of a master facing the look point of his station (StationSpec.masterLook) (forward = (-sin yaw, -cos yaw)). Pure. */
    static float facingYaw(TransitNetwork.Station s) {
        Vector3d logical = new Vector3d(s.origin.x + 0.5, s.origin.y, s.origin.z + 0.5);
        float[] look = StationSpec.of(s.kind).masterLook();
        Vector3d foot = BalloonManager.prefabPoint(logical, s.rotation, new Vector3f(look[0], look[1], look[2]));
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
                Telemetry.error("transit", e, null, null);
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
            Telemetry.error("transit", e, null, null);
            release(s, player.getUuid());
        }
    }
}
