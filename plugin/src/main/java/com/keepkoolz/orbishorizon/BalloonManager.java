package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.FlyMode;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.world.CancelParticleSystems;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Take-off, flight and landing.
 *
 * On the ground, the balloon is made of real blocks (the prefab). At take-off, those blocks
 * are removed and replaced by an entity carrying a 3D model generated from the prefab.
 * The pilot switches to free flight (as in creative mode) and the entity follows with a fixed
 * offset. If the balloon touches a solid block, the pilot is brought back to the last
 * valid position. On landing, the blocks are placed again and the entity is removed.
 */
public final class BalloonManager {

    /**
     * Quarter turn added to the pilot's look direction to orient the prefab on
     * landing. Same value as "RotationOffset" of the crate interaction.
     */
    public static final Rotation LANDING_ROTATION_OFFSET = Rotation.OneEighty;
    /**
     * Models are rendered with a half turn relative to the prefab frame: with a
     * yaw of pi (OneEighty rotation), the model appeared unrotated, shifted by 17 blocks
     * (test of 3 October 2026, adjusted by hand with /orbishorizon balloon offset 1 0 17). So pi is added.
     */
    static final double MODEL_YAW_CORRECTION = Math.PI;
    /** Burner chain block: take-off. */
    public static final String CHAIN_BLOCK = "Hotair_Balloon_Chain";
    /** Pause of the tracking after a collision, while the teleport arrives. */
    static final long COLLISION_PAUSE_MS = 250;
    /** Minimum interval between two collision messages. */
    static final long COLLISION_MSG_INTERVAL_MS = 2000;
    /** setBlock option that suppresses destruction particles (read in WorldChunk.setBlock). */
    private static final int SET_BLOCK_NO_PARTICLES = 4;
    /** Search radius for the anchor burner around the pilot, in blocks. */
    private static final int SEARCH_RADIUS = 16;
    /** Minimum share of prefab blocks that must be in place to recognise a balloon. */
    private static final double MATCH_RATIO = 0.9;
    /** Factor applied to the player's flight speeds while piloting. */
    private static final float FLY_SPEED_FACTOR = 0.5f;
    /**
     * Flight model without flame: same model, asset without "Particles" (T13). The server sends a
     * ModelUpdate when the ModelComponent is replaced (EntityTrackerSystems$EntityModel, like
     * NPCEntity changing model). That the client then removes the particles is an assumption.
     */
    static final String UNLIT_MODEL_SUFFIX = "_Off";
    /** Lit burner state (State.Definitions of Hotair_Balloon_Burner.json). Off: "default". */
    static final String BURNER_ON_STATE = "On";
    /**
     * Boosted flame while climbing (T17): model "<model>_Boost" (same particles as the normal model
     * plus a torch flame system). The pilot's vertical speed is measured over samples of BOOST_SAMPLE_MS.
     * Hysteresis: the boosted flame starts above BOOST_ON_SPEED blocks per second and
     * returns below BOOST_OFF_SPEED, after at least BOOST_MIN_MS.
     */
    static final String BOOST_MODEL_SUFFIX = "_Boost";
    /** Radius for stopping the jet (the jet is about 5 blocks long at scale 1, T50). */
    private static final double BOOST_FLAME_CANCEL_RADIUS = 8;
    /**
     * T50: mod entity effect (copy of FlamethrowerSource with Infinite set to true) applied to the flying entity during
     * the climb. The client plays the ignition sound and the loop, then the end sound when the effect is removed.
     */
    private static final String BOOST_EFFECT = "Hotair_Balloon_Burner_Boost";
    private static final long BOOST_SAMPLE_MS = 300;
    private static final long BOOST_MIN_MS = 800;
    private static final long BOOST_START_DELAY_MS = 1000;
    private static final double BOOST_ON_SPEED = 1.0;
    private static final double BOOST_OFF_SPEED = 0.3;
    /** Fuel level message in flight, every FUEL_MSG_INTERVAL_MS. */
    private static final long FUEL_MSG_INTERVAL_MS = 15000;
    /**
     * T26, automatic descent (dry burner in flight, T12, or hovering, T20). The server lowers the entity on
     * every tick by flight.descentSpeed blocks per second, the pilot's vertical flight speed read back from their
     * settings (MovementSettings.verticalFlySpeed, after the division by FLY_SPEED_FACTOR), instead of teleporting
     * the pilot every quarter of a second (jerky). The step of one tick is capped at MAX_DESCENT_DT seconds so
     * that a late tick does not skip a cell. With no player to read from, fallback: the game's default vertical
     * speed (10.32 blocks per second, Server/Entity/MovementConfig/Default.json) divided by FLY_SPEED_FACTOR.
     */
    private static final double MAX_DESCENT_DT = 0.25;
    private static final double FALLBACK_DESCENT_SPEED = 10.32 * FLY_SPEED_FACTOR;
    /** Delay for putting the burner contents back into the placed block (component creation is deferred). */
    private static final long RESTORE_TIMEOUT_MS = 10000;
    /** Interval between two updates of the resume file during the flight (T19). */
    private static final long RESUME_WRITE_INTERVAL_MS = 3000;
    /** Maximum wait for a placement on the world thread from another thread (disconnect, shutdown). */
    private static final long LAND_WAIT_MS = 4000;
    /**
     * T20, balloon stopped in the air. It is "in the air" if the GROUND_MARGIN_CELLS cells below it
     * are free. Since T23 the crate takes the targeted block (the ground) as origin with Offset.Y = -4: the bottom
     * of the ladder (y = 5 in the prefab) is placed just above the ground. The margin of 2 also tolerates a
     * balloon floating one cell above the ground (it counts as landed). On the ground, nothing burns.
     * Since T24 the test only looks at flight cells (folded ladder) and the margin is extended by
     * StructureShape.flightBottomGap() (see isInAir).
     */
    private static final int GROUND_MARGIN_CELLS = 2;
    /** Game fluids that are not liquids (Server/Item/Block/Fluids/Fire.json): they do not stop the balloon. */
    private static final String FIRE_FLUID_PREFIX = "Fire";
    /** Recognition of the balloon around the burner: redone at most every HOVER_REVALIDATE_MS. */
    private static final long HOVER_REVALIDATE_MS = 10000;
    /** The burner's chunk is marked for saving at most every HOVER_SAVE_MS during combustion. */
    private static final long HOVER_SAVE_MS = 10000;
    /** Delay before a new attempt if the dry descent could not start. */
    private static final long HOVER_RETRY_MS = 5000;
    /*
     * T54: the gondola volume (a player whose feet are in it is "in the gondola"), the model pivot, the flight model and the crate
     * item belong to each balloon type (Deployables.BalloonSpec). Large balloon: floor y = 8, walking floor y = 9, railing up to
     * y = 10, walls at x = -2 and 2 and at z = 6 and 10 (seats at (+-1, 9, 7) and (+-1, 9, 9)).
     */
    /**
     * T21, height of the seat point above the walking floor (blocks). The game places a player seated on
     * a stool at the centre of the cell plus 0.5 in height (BlockMountPoint.computeWorldSpacePosition: cell
     * + 0.5 + seat Y offset, which is 0 for Furniture_Crude_Stool). Adjustable with /orbishorizon balloon seatheight.
     */
    private static final float SEAT_HEIGHT_DEFAULT = 0.5f;
    /** A passenger who loses their mount more than PASSENGER_MAX_LOSSES times within PASSENGER_LOSS_WINDOW_MS switches to TELEPORT mode. */
    private static final int PASSENGER_MAX_LOSSES = 3;
    private static final long PASSENGER_LOSS_WINDOW_MS = 10000;
    /** Grace period after a passenger is set up and minimum interval between two server actions on them. */
    private static final long PASSENGER_GRACE_MS = 1000;
    private static final long PASSENGER_ACTION_INTERVAL_MS = 500;
    private static final long PASSENGER_TELEPORT_INTERVAL_MS = 150;
    private static final double PASSENGER_TELEPORT_DISTANCE = 0.35;
    /** T82 FOLLOW mode (same values as the airship passengers, T64): correction gain (b/s per block), cap (b/s), snap distance (blocks), pause after a teleport (ms), smallest velocity sent (b/s). */
    private static final double FOLLOW_GAIN = 2.0;
    private static final double FOLLOW_MAX_CORRECTION = 4.0;
    private static final double FOLLOW_SNAP = 1.25;
    private static final long FOLLOW_PAUSE_MS = 250;
    private static final double FOLLOW_VELOCITY_EPS = 0.02;
    /** T25: repeat interval of the seated animation (players coming into view would miss it otherwise). */
    private static final long POSE_ANIM_INTERVAL_MS = 4000;
    /** Placing a passenger on landing: slightly above the floor, in the free cell next to their seat. */
    private static final float LANDING_LIFT = 0.1f;

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final BalloonManager INSTANCE = new BalloonManager();

    private final Map<UUID, BalloonFlight> flights = new ConcurrentHashMap<>();
    /** Flights being resumed (T19), to avoid starting the same resume twice. */
    private final Set<UUID> recovering = ConcurrentHashMap.newKeySet();
    /** Model forced with /orbishorizon balloon model (test), null: each balloon type uses its own model. */
    private volatile String modelOverride;
    /**
     * Display offset of the model, in blocks, in the prefab frame (before rotation).
     * Used to align the model with the block positions: adjustable in game with /orbishorizon balloon offset.
     */
    private volatile Vector3f modelOffset = new Vector3f();

    /**
     * Test of 3 October 2026: the entity is "mounted" on the pilot (MountedComponent, like a
     * passenger), so that the client displays it stuck to the pilot instead of following the positions
     * sent by the server with a delay. Adjustable in game with /orbishorizon balloon attach.
     */
    private volatile boolean attachToPilot = true;

    public boolean attachToPilot() {
        return attachToPilot;
    }

    public void setAttachToPilot(boolean value) {
        attachToPilot = value;
    }

    public Vector3f modelOffset() {
        return new Vector3f(modelOffset);
    }

    public void setModelOffset(float x, float y, float z) {
        modelOffset = new Vector3f(x, y, z);
    }

    /**
     * Pivot of the 3D model of this balloon type, in blocks, in the frame of the logical position (centre of the origin block,
     * bottom of the block): the pilot's feet at the centre of the gondola, under the burner. Must match
     * PIVOT in scripts/prefab_to_model.py, which shifts the model by the same amount.
     */
    static Vector3f pivot(Deployables.Kind kind) {
        return kind.balloon.pivot;
    }

    /** Model used by a flight of this type: the forced one (test) or the type's own. */
    private String modelFor(Deployables.Kind kind) {
        String o = modelOverride;
        return o != null ? o : kind.balloon.modelId;
    }

    /** World point matching a point of the prefab frame (in blocks, before rotation). */
    static Vector3d prefabPoint(Vector3d logical, Rotation rotation, Vector3f local) {
        Vector3f o = rotation.rotateYaw(new Vector3f(local), new Vector3f());
        return new Vector3d(logical).add(o.x, o.y, o.z);
    }

    /**
     * Attachment offset, in the flying entity's frame, of a point of the prefab frame (blocks, before rotation):
     * the client rotates the offset with the mount's yaw (the entity, fixed yaw: prefab rotation plus pi,
     * MODEL_YAW_CORRECTION), so it is the prefab offset (point minus pivot) with x and z inverted. A single calculation
     * for the passenger seats (T21) and the lights (T34, BalloonLights).
     */
    static Vector3f attachOffset(Deployables.Kind kind, Vector3f local) {
        Vector3f d = new Vector3f(local).sub(pivot(kind));
        return new Vector3f(-d.x, d.y, -d.z);
    }

    /** Display position of the entity: model pivot + alignment offset, rotated like the prefab. */
    private Vector3d displayPosition(Deployables.Kind kind, Vector3d logical, Rotation rotation) {
        return prefabPoint(logical, rotation, new Vector3f(pivot(kind)).add(modelOffset));
    }

    /** Diagnostic text about the player's current flight. */
    public String debug(Store<EntityStore> store, PlayerRef player) {
        // Diagnostic output (op), in English whatever the client language: technical values.
        BalloonFlight f = flights.get(player.getUuid());
        if (f == null) {
            return "No flight in progress. Model " + modelId() + ", offset " + modelOffset
                    + ", rendering setting " + (renderDual ? "dual" : "single") + ", model parts setting " + partsMode.name().toLowerCase()
                    + " (view filter " + (ViewFilter.available() ? "registered" : "NOT registered")
                    + ", " + ViewFilter.ruleCount() + " rule(s))"
                    + ", passenger seating " + passengerMode + ", seat pose " + pose.describe()
                    + ", seat height " + seatHeight + ", lights " + BalloonLights.describe(null);
        }
        TransformComponent pt = f.pilotRef != null ? store.getComponent(f.pilotRef, TransformComponent.getComponentType()) : null;
        TransformComponent bt = f.balloonRef != null && f.balloonRef.isValid()
                ? store.getComponent(f.balloonRef, TransformComponent.getComponentType()) : null;
        TransformComponent ot = f.observerRef != null && f.observerRef.isValid()
                ? store.getComponent(f.observerRef, TransformComponent.getComponentType()) : null;
        String rendering = f.dual
                ? "dual (pilot entity " + (f.balloonRef != null && f.balloonRef.isValid() ? "present" : "missing") + ", rule " + ViewFilter.ruleOf(f.balloonRef)
                        + "; observer entity " + (ot != null ? fmt(ot.getPosition()) : "missing") + ", rule " + ViewFilter.ruleOf(f.observerRef) + ")"
                : "single entity (seen by everybody)";
        return "Type " + f.kind.id + ", rendering " + rendering + ", model parts " + VehicleParts.describe(f.parts, partsMode) + ", rotation " + f.rotation + " (" + f.rotation.getDegrees() + " deg)"
                + ", origin " + f.originCell(f.entityPos)
                + ", logical position " + fmt(f.entityPos)
                + ", entity " + (bt != null ? fmt(bt.getPosition()) + " yaw " + bt.getRotation().y : "missing")
                + ", pilot " + (pt != null ? fmt(pt.getPosition()) + " yaw " + pt.getRotation().y : "?")
                + ", pilot offset " + fmt(f.pilotOffset)
                + ", model offset " + modelOffset + ", attached to pilot " + f.attached + ", boosted flame " + f.boost + ", flamethrower sound " + f.roar
                + ", burner " + (f.burner != null ? fuelDebug(f.burner) : "?") + (f.dry ? " (empty)" : "")
                + ", automatic descent " + (f.driven ? "yes" : "no") + " at " + String.format(java.util.Locale.ROOT, "%.2f", f.descentSpeed) + " blocks/s"
                + ", chests " + (f.cargo != null ? f.cargo.itemCount() + " items in " + f.cargo.entries().size() + " containers" : "?")
                + ", passengers " + passengersDebug(store, f)
                + (TransportCage.isTransport(f.kind) ? ", cage animals " + CageAnimals.describe(f.world, f.animals) : "")
                + ", lights " + BalloonLights.describe(f);
    }

    /**
     * T34: applies the lights setting (/orbishorizon balloon light) to the world's current flights: creates the missing
     * lights (enabled), removes them (disabled) or updates their colour (forced radius). On the world thread.
     */
    void applyLightSetting(Store<EntityStore> store, World world) {
        for (BalloonFlight f : flights.values()) {
            if (f.world != world) {
                continue;
            }
            StructureShape s;
            try {
                s = f.kind.shape();
            } catch (IOException e) {
                continue;
            }
            if (!BalloonLights.enabled()) {
                BalloonLights.removeAll(store, f);
            } else if (f.balloonRef != null && f.balloonRef.isValid()) {
                BalloonLights.spawnAll(store, f, s);
                BalloonLights.refreshColors(store, f, s);
            }
        }
    }

    private static String fmt(Vector3d v) {
        return String.format(java.util.Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }
    /** Test entities created by /orbishorizon balloon spawnmodel, one per player. */
    private final Map<UUID, Ref<EntityStore>> testEntities = new ConcurrentHashMap<>();
    private final Map<UUID, List<Ref<EntityStore>>> testParts = new ConcurrentHashMap<>();

    /** The forced model, or "default" (each balloon type uses its own model). */
    public String modelId() {
        String o = modelOverride;
        return o != null ? o : "default";
    }

    /** Returns null if the model exists, otherwise an error message. "default" goes back to each type's own model. */
    public Message setModelId(String id) {
        if ("default".equalsIgnoreCase(id)) {
            modelOverride = null;
            return null;
        }
        if (ModelAsset.getAssetMap().getAsset(id) == null) {
            return Texts.t("error.unknownModel").param("model", id);
        }
        modelOverride = id;
        return null;
    }

    /** Spawns a still entity with the given model, 15 blocks in front of the player (the exact direction remains to be confirmed). */
    public Message spawnTestModel(Store<EntityStore> store, Ref<EntityStore> playerRef, PlayerRef player, String id) {
        Ref<EntityStore> previous = testEntities.remove(player.getUuid());
        if (previous != null && previous.isValid()) {
            store.removeEntity(previous, RemoveReason.REMOVE);
        }
        List<Ref<EntityStore>> previousParts = testParts.remove(player.getUuid());
        if (previousParts != null) {
            for (Ref<EntityStore> r : previousParts) {
                if (r != null && r.isValid()) {
                    store.removeEntity(r, RemoveReason.REMOVE);
                }
            }
        }
        TransformComponent t = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (t == null) {
            return Texts.t("error.noPosition");
        }
        double yaw = t.getRotation().y;
        Vector3d pos = new Vector3d(t.getPosition()).add(-Math.sin(yaw) * 15, 0, -Math.cos(yaw) * 15);
        Ref<EntityStore> ref = spawnModelEntity(store, pos, Rotation.None, id);
        if (ref == null) {
            return Texts.t("error.unknownModel").param("model", id);
        }
        testEntities.put(player.getUuid(), ref);
        // Parts of the model (same position and yaw, still).
        testParts.put(player.getUuid(), VehicleParts.spawnStill(store, id, pos, Rotation.None.getRadians() + MODEL_YAW_CORRECTION));
        return null;
    }

    public static BalloonManager get() {
        return INSTANCE;
    }

    public boolean isFlying(UUID pilot) {
        return flights.containsKey(pilot);
    }

    Iterable<BalloonFlight> flights() {
        return flights.values();
    }

    // ------------------------------------------------------------------ take-off

    /** Returns null if all is well, otherwise an error message for the player. */
    public Message takeOff(Store<EntityStore> store, Ref<EntityStore> pilotRef, PlayerRef player, World world) {
        return takeOff(store, pilotRef, player, world, null);
    }

    /** T87: with the chain block that was used, only the burner directly above it is considered first (fallback: pilot position). */
    public Message takeOff(Store<EntityStore> store, Ref<EntityStore> pilotRef, PlayerRef player, World world, Vector3i chainPos) {
        if (flights.containsKey(player.getUuid())) {
            return Texts.t("error.alreadyPiloting");
        }
        Message unreadable = checkPrefabsReadable();
        if (unreadable != null) {
            return unreadable;
        }
        TransformComponent pilotTransform = store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (pilotTransform == null) {
            return Texts.t("error.noPosition");
        }
        Vector3d pilotPos = new Vector3d(pilotTransform.getPosition());

        // T54: the large and the small balloon share the same anchor burner, the better match decides the type.
        Found found = chainPos != null
                ? find(world, pilotPos, Deployables.BALLOONS, new Vector3i(chainPos.x, chainPos.y + 1, chainPos.z)) : null;
        if (found == null) {
            found = findBalloon(world, pilotPos);
        }
        if (found == null) {
            return Texts.t("error.noBalloon");
        }
        StructureShape s = found.kind().shapeOrNull();
        if (s == null) {
            return Texts.t("error.prefabUnreadable").param("error", found.kind().prefabPath);
        }
        // T73: transport balloon with its cage lowered, open or moving: refused before anything is removed.
        Message cage = TransportCage.takeOffRefusal(world, found.kind(), found.origin, found.rotation);
        if (cage != null) {
            return cage;
        }
        // Burner (T12): fuel is needed to take off. Its contents are kept during the flight.
        Vector3i burnerPos = s.burner().rotated(found.rotation).add(found.origin);
        ProcessingBenchBlock bench = BurnerFuel.live(world, burnerPos);
        if (bench == null) {
            return Texts.t("error.noBurner");
        }
        if (!BurnerFuel.hasFuel(bench)) {
            return Texts.t("error.burnerEmpty");
        }

        // T21: the other players in the gondola are passengers, one seat each. Beyond the
        // seats of the prefab (large balloon: pilot plus 4 passengers, small one: pilot alone), take-off is refused
        // before any block is removed.
        List<Occupant> passengers = new ArrayList<>();
        for (Occupant o : nacelleOccupants(world, store, found.kind(), found.origin, found.rotation)) {
            if (!o.player().getUuid().equals(player.getUuid()) && !flights.containsKey(o.player().getUuid())) {
                passengers.add(o);
            }
        }
        if (passengers.size() > s.seats().size()) {
            return Texts.t("error.balloonFull").param("max", s.seats().size() + 1)
                    .param("extra", passengers.size() - s.seats().size());
        }

        return launch(store, world, s, found, bench, pilotRef, player, false, passengers);
    }

    /** Null if every balloon prefab is readable, otherwise the error message for the player (logged). */
    private static Message checkPrefabsReadable() {
        for (Deployables.Kind k : Deployables.BALLOONS) {
            try {
                k.shape();
            } catch (IOException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Lecture du prefab impossible");
                return Texts.t("error.prefabUnreadable").param("error", String.valueOf(e.getMessage()));
            }
        }
        return null;
    }

    /**
     * Removes the blocks of the balloon found, creates the flying entity and registers the flight. Used for
     * take-off by the chain (dry false, pilot given) and for the dry descent of a balloon stopped
     * in the air (T20, dry true): with a pilot (the player closest to the pivot in the gondola),
     * who is then out of fuel, or without a pilot (pilotRef and player null), the entity then being
     * moved by the server. The fuel check is done before the call. Returns null if all is
     * well, otherwise an error message (nothing is removed then, or everything is put back).
     *
     * T21: passengers is the list of the other players in the gondola. Each is seated on a seat (assignSeats)
     * and mounted on the entity (boardPassengers). The capacity check is done before the call for a take-off,
     * in automatic descent (dry) seats are shared if there are more people than seats.
     */
    private Message launch(Store<EntityStore> store, World world, StructureShape s, Found found, ProcessingBenchBlock bench,
                          Ref<EntityStore> pilotRef, PlayerRef player, boolean dry, List<Occupant> passengers) {
        boolean pilotless = pilotRef == null;
        TransformComponent pilotTransform = pilotless ? null
                : store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (!pilotless && pilotTransform == null) {
            return Texts.t("error.noPosition");
        }
        Vector3d pilotPos = pilotless ? null : new Vector3d(pilotTransform.getPosition());
        UUID flightId = pilotless ? UUID.randomUUID() : player.getUuid();

        // 1. The pilot switches to flight before the blocks are removed, to avoid falling.
        Deployables.Kind kind = found.kind();
        BalloonFlight flight = new BalloonFlight(flightId, pilotRef, world, found.rotation, kind);
        if (!pilotless && !setPilotFlying(store, pilotRef, player, true)) {
            return Texts.t("error.movement");
        }
        // T26: automatic descent speed = the pilot's vertical flight speed (already halved above).
        flight.descentSpeed = pilotless ? defaultDescentSpeed(world, store) : pilotDescentSpeed(store, pilotRef);
        // T21: seats assigned before any block is removed (players' current positions). A player already seated
        // on a stool (block mount) is dismounted first: the block is going to disappear.
        List<BalloonFlight.Passenger> seated = assignSeats(store, kind, s, found.origin, found.rotation, passengers, dry);
        for (BalloonFlight.Passenger p : seated) {
            MountUtil.unseat(store, p.ref);
        }

        // The burner contents are copied then removed from the block: otherwise the game would drop them into the
        // world when the block disappears.
        flight.burner = BurnerFuel.takeFrom(bench);
        // T18: same precaution for the gondola chests, their contents travel with the flight.
        flight.cargo = BalloonCargo.takeFrom(world, s, found.origin, found.rotation);
        String modelId = modelFor(kind);
        flight.modelId = modelId;
        // T19: the contents now only exist in the plugin's memory. The resume file is written
        // (fsync) before the blocks are removed: if the server stops, the next world load
        // puts the balloon and its contents back. T20: also valid for a flight without a pilot.
        // T74: animals captured in the cage of a transport balloon travel with the flight (also written in the resume file).
        flight.animals.addAll(CageAnimals.takeFromEntry(world, found.origin, found.rotation, kind.id));
        BalloonResume.write(flight, found.origin, true);

        // T27: the blocks are going to disappear, the balloon is no longer locked during the flight (landing
        // registers it again, pasteKeepingTerrain). The removals below do not go through the break events.
        BalloonRegistry.remove(world, found.origin, found.rotation, kind.id);

        // 2. Remove the balloon's blocks.
        // First the objects placed on other blocks (ropes, chests, trapdoors...), then the
        // structure, to avoid an object losing its support, breaking and dropping an item.
        int removed = removeBlocks(world, s, found.origin, found.rotation);

        // 3. Create the flying entity in its place. Dry descent: directly the model without flame.
        flight.entityPos.set(found.origin.x + 0.5, found.origin.y, found.origin.z + 0.5);
        String spawnId = modelId;
        if (dry && ModelAsset.getAssetMap().getAsset(modelId + UNLIT_MODEL_SUFFIX) != null) {
            spawnId = modelId + UNLIT_MODEL_SUFFIX;
        }
        Ref<EntityStore> balloon = spawnModelEntity(store, displayPosition(kind, flight.entityPos, found.rotation), found.rotation, spawnId);
        if (balloon == null) {
            // Failure: the blocks are put back and normal walking is restored.
            pastePrefab(kind, world, store, found.origin, found.rotation);
            restoreBurner(kind, world, store, flight.burner, found.origin, found.rotation);
            restoreCargo(world, store, flight.cargo, found.origin, found.rotation);
            CageAnimals.place(world, found.origin, found.rotation, kind.id, flight.animals);
            if (!pilotless) {
                setPilotFlying(store, pilotRef, player, false);
            }
            BalloonResume.delete(flightId);
            return Texts.t("error.modelMissing").param("model", spawnId);
        }
        flight.balloonRef = balloon;
        UUIDComponent balloonId = store.getComponent(balloon, UUIDComponent.getComponentType());
        flight.balloonUuid = balloonId != null ? balloonId.getUuid() : null;
        if (!pilotless && attachToPilot) {
            // The client places a mounted entity at the pilot's position plus the offset rotated
            // by the pilot's yaw (seen on 3 October 2026: the balloon rotated around the pilot when
            // they looked around). So the pilot is placed on the model's pivot (centre of the
            // gondola), which gives a zero offset.
            Vector3d seat = prefabPoint(flight.entityPos, found.rotation, pivot(kind));
            Rotation3f look = new Rotation3f(pilotTransform.getRotation());
            store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(seat, look));
            pilotPos.set(seat);
            Vector3f attach = found.rotation.rotateYaw(new Vector3f(modelOffset), new Vector3f());
            store.putComponent(balloon, MountedComponent.getComponentType(),
                    new MountedComponent(pilotRef, attach, MountController.Minecart));
            flight.attached = true;
            flight.teleportUntilMs = System.currentTimeMillis() + COLLISION_PAUSE_MS;
        }
        if (!pilotless) {
            flight.pilotOffset.set(pilotPos).sub(flight.entityPos);
            flight.lastValidPilotPos.set(pilotPos);
        }
        // T82: dual rendering. The entity mounted on the pilot is seen by the pilot only, an observer entity (moved by the server) is seen by
        // everybody else. Only for a flight with a pilot that attaches the entity and is not dry (a dry flight collapses to one entity).
        if (!pilotless && !dry && attachToPilot && renderDual && ViewFilter.available()) {
            Ref<EntityStore> observer = VehicleView.spawnObserver(store, balloon, flight.balloonUuid, flightId, spawnId);
            if (observer != null) {
                flight.observerRef = observer;
                flight.dual = true;
            }
        }
        // Model parts (entities that carry the nodes beyond the client's limit), on the entity and on the observer entity if any.
        flight.parts = VehicleParts.spawn(store, partsMode, modelId, balloon, flight.balloonUuid,
                flight.dual ? flight.observerRef : null, flight.dual ? flightId : null);
        flight.lastCheckedOrigin.set(found.origin);
        flight.takeoffMs = System.currentTimeMillis();
        flight.lastBurnMs = flight.takeoffMs;
        flight.nextFuelMsgMs = flight.takeoffMs + FUEL_MSG_INTERVAL_MS;
        if (dry) {
            // Dry descent: the flight starts out of fuel.
            flight.dry = true;
            flight.lastDescentMs = flight.takeoffMs;
        }
        // T21: passengers are seated before the first write of the resume file (which contains them).
        boardPassengers(store, flight, seated);
        // T34: lights of the yellow crystals (auxiliary entities mounted on the flying entity), no effect if disabled.
        BalloonLights.spawnAll(store, flight, s);
        // T35: light of the burner flame (not for a dry descent, flame off).
        BalloonLights.spawnBurner(store, flight, s);
        // The chain in flight: an interactable helper entity, using it lands the balloon (like the airship helm).
        BalloonLights.spawnChain(store, flight, s);
        flights.put(flightId, flight);
        flight.lastResumeMs = flight.takeoffMs;
        BalloonResume.write(flight, found.origin, false);
        if (dry) {
            if (!pilotless) {
                player.sendMessage(Texts.t("fuel.dryDescentStart"));
                enterDry(store, flight);
            }
        } else {
            player.sendMessage(Texts.t("fuel.status").param("fuel", fuelMessage(flight.burner)));
            // T37: the chest contents and the number of passengers go to the log (line below), not to the chat.
            if (!flight.cargo.isEmpty()) {
                LOGGER.at(Level.INFO).log("Coffres : %d objet(s) transporté(s)", flight.cargo.itemCount());
            }
        }
        LOGGER.at(Level.INFO).log("%s : origine %s, rotation %s, %d blocs retirés%s", dry ? "Descente à sec" : "Décollage",
                found.origin, found.rotation, removed,
                (pilotless ? " (sans pilote)" : "") + ", " + seated.size() + " passager(s)");
        return null;
    }

    /**
     * Removes the blocks of the balloon placed at this origin, without particles and without dropped items. First the
     * objects placed on other blocks (ropes, chests, trapdoors...), then the structure, to avoid an object losing
     * its support, breaking and dropping an item. Only blocks of the prefab's type are removed (terrain left in
     * an unfolded ladder cell, T24, stays). Used for take-off (launch), removal by an administrator (T40) and tent packing (T46,
     * StructureRemoval: the anchor block is that of the shape passed).
     * Returns the number of blocks removed.
     */
    static int removeBlocks(World world, StructureShape s, Vector3i origin, Rotation rotation) {
        return removeBlocks(world, s, origin, rotation, false);
    }

    /**
     * Same, with a broader notion of "attached" for the airship prototype (benches, posters, potions, books...): everything that
     * is not a structural block (Cloth_, Wood_, Rock_, Soil_) is removed first. The balloon and the tent keep the original rule.
     */
    static int removeBlocks(World world, StructureShape s, Vector3i origin, Rotation rotation, boolean broadAttached) {
        int removed = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (StructureShape.Cell c : s.cells()) {
                if ((broadAttached ? isAttachedBroad(c) : isAttached(c)) != (pass == 0)) {
                    continue;
                }
                Vector3i p = c.rotated(rotation).add(origin);
                BlockType bt = world.getBlockType(p.x, p.y, p.z);
                if (bt != null && sameBlock(bt, c)) {
                    // The anchor block (the burner, formerly the brazier, or the tent's campfire, T46) is removed without the "no particles" option: with it, the flame
                    // stayed displayed in the sky (test of 3 October 2026, suspected cause). The airship (broadAttached) is excluded: its
                    // anchor is the wooden helm (T67), which burst into break particles, and its block particles are cancelled apart.
                    boolean flameAnchor = !broadAttached && s.anchor().baseName().equals(c.baseName());
                    int settings = flameAnchor ? 0 : SET_BLOCK_NO_PARTICLES;
                    world.setBlock(p.x, p.y, p.z, BlockType.EMPTY_KEY, settings);
                    removed++;
                    if (settings == 0) {
                        cancelBlockFlame(world, p);
                    }
                }
            }
        }
        return removed;
    }

    /** A balloon recognised in the world: type, origin, rotation and share of its flight cells in place (0 if not measured). */
    record Found(Deployables.Kind kind, Vector3i origin, Rotation rotation, double ratio) {
    }

    /**
     * Looks for an anchor burner then checks that the prefab blocks are in place around it. T54: every balloon type is tried
     * (same anchor block for all), with the 4 rotations, and the best share of flight cells in place wins (at least MATCH_RATIO).
     */
    private Found findBalloon(World world, Vector3d around) {
        return find(world, around, Deployables.BALLOONS);
    }

    /**
     * Generalised recognition (airship prototype): tries every type of the list around the anchor blocks found within
     * SEARCH_RADIUS of the point, with the 4 rotations, best share of flight cells in place wins (at least MATCH_RATIO).
     */
    Found find(World world, Vector3d around, List<Deployables.Kind> kinds) {
        return find(world, around, kinds, null);
    }

    /**
     * T87: recognition in two steps. 1. For each anchor block, the best type and rotation (the registered priority of T73 only
     * competes at the same anchor). 2. Between anchors: the one whose nacelle contains the search point, otherwise the nearest
     * (to the pivot for a balloon, to the anchor otherwise). With an anchor hint (the burner above the chain that was used), only
     * that anchor is tried.
     */
    Found find(World world, Vector3d around, List<Deployables.Kind> kinds, Vector3i anchorHint) {
        int cx = (int) Math.floor(around.x), cy = (int) Math.floor(around.y), cz = (int) Math.floor(around.z);
        Found bestInside = null;
        double insideDist = Double.MAX_VALUE;
        Found bestNear = null;
        double nearDist = Double.MAX_VALUE;
        int x0 = anchorHint != null ? anchorHint.x : cx - SEARCH_RADIUS;
        int x1 = anchorHint != null ? anchorHint.x : cx + SEARCH_RADIUS;
        int y0 = anchorHint != null ? anchorHint.y : cy - SEARCH_RADIUS;
        int y1 = anchorHint != null ? anchorHint.y : cy + SEARCH_RADIUS;
        int z0 = anchorHint != null ? anchorHint.z : cz - SEARCH_RADIUS;
        int z1 = anchorHint != null ? anchorHint.z : cz + SEARCH_RADIUS;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockType bt = world.getBlockType(x, y, z);
                    if (bt == null) {
                        continue;
                    }
                    Found atAnchor = null;
                    for (Deployables.Kind kind : kinds) {
                        StructureShape s = kind.shapeOrNull();
                        if (s == null || !sameBlock(bt, s.anchor())) {
                            continue;
                        }
                        for (Rotation r : Rotation.VALUES) {
                            Vector3i origin = new Vector3i(x, y, z).sub(s.anchor().rotated(r));
                            double ratio = matchRatioRegistered(world, kind, s, origin, r);
                            if (ratio >= MATCH_RATIO && (atAnchor == null || ratio > atAnchor.ratio)) {
                                atAnchor = new Found(kind, origin, r, ratio);
                            }
                        }
                    }
                    if (atAnchor == null) {
                        continue;
                    }
                    double[] d = anchorDistance(atAnchor, around, x, y, z);
                    if (d[0] >= 0 && d[0] < insideDist) {
                        insideDist = d[0];
                        bestInside = atAnchor;
                    }
                    if (d[1] < nearDist) {
                        nearDist = d[1];
                        bestNear = atAnchor;
                    }
                }
            }
        }
        return bestInside != null ? bestInside : bestNear;
    }

    /**
     * T87: [distance to the pivot if the point is inside the balloon's nacelle (-1 otherwise), distance to the pivot (balloon)
     * or to the anchor (other types)].
     */
    private double[] anchorDistance(Found f, Vector3d point, int ax, int ay, int az) {
        Deployables.BalloonSpec spec = f.kind().balloon;
        if (spec == null) {
            return new double[]{-1, new Vector3d(ax + 0.5, ay + 0.5, az + 0.5).distance(point)};
        }
        Vector3f nMin = spec.nacelleMin;
        Vector3f nMax = spec.nacelleMax;
        Vector3f pv = pivot(f.kind());
        Rotation back = Rotation.None.subtract(f.rotation());
        Vector3f local = back.rotateYaw(new Vector3f((float) (point.x - (f.origin().x + 0.5)), (float) (point.y - f.origin().y),
                (float) (point.z - (f.origin().z + 0.5))), new Vector3f());
        double d = local.distance(pv);
        boolean inside = local.x >= nMin.x && local.x <= nMax.x && local.y >= nMin.y && local.y <= nMax.y
                && local.z >= nMin.z && local.z <= nMax.z;
        return new double[]{inside ? d : -1, d};
    }

    /**
     * Same, T73: a structure that is in the registry at this origin and rotation (transport balloon) is compared in its registered
     * state (cage lowered, side open) and, if it matches, wins over every other type measured at the same burner
     * (TransportCage.REGISTERED_RATIO): the small balloon's shape matches about 90 % in a transport balloon.
     */
    private double matchRatioRegistered(World world, Deployables.Kind kind, StructureShape base, Vector3i origin, Rotation r) {
        double ratio = matchRatio(world, TransportCage.shapeAt(world, kind, base, origin, r), origin, r);
        return ratio >= MATCH_RATIO && TransportCage.registered(world, kind, origin, r) ? TransportCage.REGISTERED_RATIO : ratio;
    }

    /** The shape of a recognised or registered balloon in its registered state (T73), the prefab's if it has no cage state. */
    static StructureShape shapeOfFound(World world, Found f) {
        StructureShape base = f.kind().shapeOrNull();
        return base == null ? null : TransportCage.shapeAt(world, f.kind(), base, f.origin(), f.rotation());
    }

    /** Share of the flight cells (0 to 1) that are in place for a prefab at this origin and rotation. */
    private double matchRatio(World world, StructureShape s, Vector3i origin, Rotation r) {
        int total = s.flightCells().size();
        // Same threshold as before T54: ceil(total * MATCH_RATIO) cells are needed. Rounded here to avoid a different float result.
        int needed = (int) Math.ceil(total * MATCH_RATIO);
        int m = countMatches(world, s, origin, r);
        return m >= needed ? Math.max(MATCH_RATIO, m / (double) total) : 0;
    }

    private int countMatches(World world, StructureShape s, Vector3i origin, Rotation r) {
        int m = 0;
        // T24: only flight cells count, the unfolded ladder may be missing (terrain) without being a problem.
        for (StructureShape.Cell c : s.flightCells()) {
            Vector3i p = c.rotated(r).add(origin);
            BlockType bt = world.getBlockType(p.x, p.y, p.z);
            if (bt != null && sameBlock(bt, c)) {
                m++;
            }
        }
        return m;
    }

    private static boolean isAttached(StructureShape.Cell c) {
        String n = c.baseName();
        // Mod blocks: chain and burner (placed one on top of the other).
        return n.startsWith("Furniture_") || n.startsWith("Deco_") || n.startsWith("Metal_")
                || n.startsWith("Hotair_Balloon_");
    }

    private static boolean isAttachedBroad(StructureShape.Cell c) {
        String n = c.baseName();
        return !(n.startsWith("Cloth_") || n.startsWith("Wood_") || n.startsWith("Rock_") || n.startsWith("Soil_")) || n.startsWith("Wood_Softwood_Fence");
    }

    static boolean sameBlock(BlockType bt, StructureShape.Cell c) {
        String id = bt.getId();
        if (id == null) {
            return false;
        }
        String n = id.startsWith("*") ? id.substring(1) : id;
        return n.startsWith(c.baseName());
    }

    private Ref<EntityStore> spawnModelEntity(Store<EntityStore> store, Vector3d position, Rotation rotation, String id) {
        return spawnModelEntity(store, position, rotation.getRadians() + MODEL_YAW_CORRECTION, id);
    }

    /** Same as above with the entity's yaw given directly, in radians (airship: free heading). */
    Ref<EntityStore> spawnModelEntity(Store<EntityStore> store, Vector3d position, double yawRadians, String id) {
        return spawnModelEntity(store, position, yawRadians, id, null);
    }

    /** Same with a given UUID for the entity (T82: the observer entity has a deterministic one, so a resume can remove an orphan). */
    Ref<EntityStore> spawnModelEntity(Store<EntityStore> store, Vector3d position, double yawRadians, String id, UUID uuid) {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset == null) {
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable", id);
            return null;
        }
        Model model = Model.createUnitScaleModel(asset);
        Rotation3f rot = new Rotation3f();
        rot.setYaw((float) yawRadians);

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(position), rot));
        if (uuid != null) {
            holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(uuid));
        } else {
            holder.ensureComponent(UUIDComponent.getComponentType());
        }
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
        // Without a NetworkId, the entity exists on the server side but is never sent to clients
        // (seen in game: nothing was displayed). The game's minecart adds it the same way
        // (MountSystems$EnsureMinecartComponents). Intangible: no physical collision with
        // the entity, collisions are handled by BalloonManager.
        holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(Intangible.getComponentType());
        // T19: without this marker, EntitySystems$EnsureForSerializable makes the entity saveable and the
        // world saves it with its chunks (a ghost entity without a model reappears on load).
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        // T50: carries the sound effect of the flame jet (EffectControllerComponent.addEffect). Nothing is saved (NonSerialized).
        holder.addComponent(EffectControllerComponent.getComponentType(), new EffectControllerComponent());
        return store.addEntity(holder, AddReason.SPAWN);
    }

    /** Enables or disables the pilot's free flight. */
    private boolean setPilotFlying(Store<EntityStore> store, Ref<EntityStore> pilotRef, PlayerRef player, boolean flying) {
        return setPilotFlying(store, pilotRef, player, flying, FLY_SPEED_FACTOR);
    }

    /** Same with the flight speed factor given (airship: tunable). The factor applies to the current settings. */
    boolean setPilotFlying(Store<EntityStore> store, Ref<EntityStore> pilotRef, PlayerRef player, boolean flying, float speedFactor) {
        MovementManager mm = store.getComponent(pilotRef, MovementManager.getComponentType());
        if (mm == null) {
            return false;
        }
        if (flying) {
            MovementSettings settings = mm.getSettings();
            settings.fly = FlyMode.Forced;
            settings.horizontalFlySpeed *= speedFactor;
            settings.verticalFlySpeed *= speedFactor;
            mm.update(player.getPacketHandler());
        } else {
            mm.resetDefaultsAndUpdate(pilotRef, store);
        }
        return true;
    }

    /** Only changes the pilot's flight mode (without touching the speeds). */
    static void setFlyMode(Store<EntityStore> store, Ref<EntityStore> pilotRef, FlyMode mode) {
        if (!pilotRef.isValid()) {
            return;
        }
        MovementManager mm = store.getComponent(pilotRef, MovementManager.getComponentType());
        PlayerRef player = store.getComponent(pilotRef, PlayerRef.getComponentType());
        if (mm == null || player == null) {
            return;
        }
        mm.getSettings().fly = mode;
        mm.update(player.getPacketHandler());
    }

    private void pastePrefab(Deployables.Kind kind, World world, Store<EntityStore> store, Vector3i origin, Rotation rotation) {
        pastePrefab(kind, world, store, origin, rotation, null);
    }

    /** Same with the owner registered for the placed structure (airship: the pilot). */
    void pastePrefab(Deployables.Kind kind, World world, Store<EntityStore> store, Vector3i origin, Rotation rotation, UUID owner) {
        Path path = PrefabStore.get().findAssetPrefabPath(kind.prefabPath);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("Prefab %s introuvable pour l'atterrissage", kind.prefabPath);
            return;
        }
        try {
            pasteKeepingTerrain(PrefabBufferUtil.getCached(path), world, kind.shape(), origin, rotation, 1 | 8, store, kind.id, owner);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Prefab illisible, pose sans protection du terrain");
            PrefabUtil.paste(PrefabBufferUtil.getCached(path), world, new Vector3i(origin), rotation,
                    new FastRandom(), 1 | 8, 0, store);
        }
    }

    /** A terrain block kept before placement (T24). */
    private record Terrain(int x, int y, int z, int id, BlockType type, int rotation, int filler, WorldChunk chunk) {
    }

    /**
     * Places the prefab without overwriting the terrain with the unfolded ladder (T24). The prefab is pasted in full
     * (PrefabUtil.paste cannot skip cells), then the solid blocks that occupied an unfolded ladder cell
     * are put back (identifier, rotation, filler). The components of a terrain block (chest) are not
     * kept. Ladder cells already occupied by something other than a solid block are overwritten, except
     * liquid cells: the ladder stops at the surface, the original empty block is put back. The prefab has
     * no fluid entry on these cells, so PrefabUtil.paste does not touch the water there.
     * Used by every placement path: landing (chain or command), dry burner, resume from T19 and crate.
     */
    /** Registers the structure of the given type (T43) and its owner (nullable) in the registry. */
    static void pasteKeepingTerrain(com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer buffer,
                                    World world, StructureShape s, Vector3i origin, Rotation rotation, int flags,
                                    com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor,
                                    String kind, UUID owner) {
        List<Terrain> kept = new ArrayList<>();
        for (StructureShape.Cell c : s.unfoldedLadder()) {
            Vector3i p = c.rotated(rotation).add(origin);
            BlockType bt = world.getBlockType(p.x, p.y, p.z);
            if (bt != null && blocksBalloon(world, p.x, p.y, p.z)) {
                WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(p.x), ChunkUtil.chunkCoordinate(p.z)));
                if (chunk != null) {
                    kept.add(new Terrain(p.x, p.y, p.z, chunk.getBlock(p.x, p.y, p.z), bt,
                            chunk.getRotationIndex(p.x, p.y, p.z), chunk.getFiller(p.x, p.y, p.z), chunk));
                }
            }
        }
        PrefabUtil.paste(buffer, world, new Vector3i(origin), rotation, new FastRandom(), flags, 0, accessor);
        for (Terrain t : kept) {
            t.chunk.setBlock(t.x, t.y, t.z, t.id, t.type, t.rotation, t.filler, SET_BLOCK_NO_PARTICLES);
        }
        if (!kept.isEmpty()) {
            LOGGER.at(Level.INFO).log("Pose : %d case(s) d'échelle laissée(s) au terrain", kept.size());
        }
        // T27: the placed balloon is locked (block breaking and placing cancelled, BalloonLockSystems).
        BalloonRegistry.add(world, origin, rotation, kind, owner);
    }

    /** Balloon type of a crate's prefab (T54), null if the given prefab is not a balloon's. */
    Deployables.Kind balloonKindFor(String prefabPath) {
        Deployables.Kind k = Deployables.forPrefab(prefabPath);
        return k != null && k.isBalloon() ? k : null;
    }

    // ------------------------------------------------------------------ landing

    public Message land(Store<EntityStore> store, PlayerRef player) {
        BalloonFlight flight = flights.get(player.getUuid());
        if (flight == null) {
            return Texts.t("error.notPiloting");
        }
        if (flight.driven) {
            // T26: the pilot is seated and their position is no longer that of a free flight: place in position, in the
            // last free cell of the descent and with the flight's orientation.
            Vector3i here = flight.currentOrigin();
            try {
                if (exceedsHeight(flight.kind.shape(), here, flight.rotation)) {
                    return Texts.t("error.heightLimit");
                }
                if (collides(flight.world, flight.kind.shape(), here, flight.rotation)) {
                    return Texts.t("error.noRoom");
                }
            } catch (IOException e) {
                return Texts.t("error.prefabUnreadable").param("error", String.valueOf(e.getMessage()));
            }
            finishFlight(store, flight, here);
            return null;
        }
        TransformComponent pilot = store.getComponent(flight.pilotRef, TransformComponent.getComponentType());
        if (pilot == null) {
            return Texts.t("error.noPosition");
        }
        // New orientation: that of the pilot's look direction, as at deployment.
        Rotation facing = Rotation.closestOfDegrees((float) Math.toDegrees(pilot.getRotation().y));
        Message error = landWith(store, flight, pilot, facing.add(LANDING_ROTATION_OFFSET));
        if (error != null && flight.rotation != facing.add(LANDING_ROTATION_OFFSET)) {
            // No room in the look direction: keep the flight's orientation.
            error = landWith(store, flight, pilot, flight.rotation);
        }
        return error;
    }

    private Message landWith(Store<EntityStore> store, BalloonFlight flight, TransformComponent pilot, Rotation newRotation) {
        // Pilot position in the prefab frame (measured at take-off), then the origin
        // that keeps the pilot at the same spot in the gondola with the new orientation.
        Vector3f local = Rotation.None.subtract(flight.rotation)
                .rotateYaw(new Vector3f((float) flight.pilotOffset.x, (float) flight.pilotOffset.y, (float) flight.pilotOffset.z), new Vector3f());
        Vector3f turned = newRotation.rotateYaw(local, new Vector3f());
        Vector3d pilotPos = pilot.getPosition();
        Vector3d entity = new Vector3d(pilotPos).sub(turned.x, turned.y, turned.z);
        Vector3i origin = flight.originCell(entity);
        try {
            if (exceedsHeight(flight.kind.shape(), origin, newRotation)) {
                return Texts.t("error.heightLimit");
            }
            if (collides(flight.world, flight.kind.shape(), origin, newRotation)) {
                return Texts.t("error.noRoom");
            }
        } catch (IOException e) {
            return Texts.t("error.prefabUnreadable").param("error", String.valueOf(e.getMessage()));
        }
        finishFlight(store, flight, origin, newRotation);
        return null;
    }

    /** Places the blocks at the given origin, removes the entity and gives walking back to the pilot. */
    void finishFlight(Store<EntityStore> store, BalloonFlight flight, Vector3i origin) {
        finishFlight(store, flight, origin, flight.rotation);
    }

    void finishFlight(Store<EntityStore> store, BalloonFlight flight, Vector3i origin, Rotation rotation) {
        flights.remove(flight.pilotUuid);
        // T34: lights first (the luminous blocks come back with the prefab, no double lighting).
        BalloonLights.removeAll(store, flight);
        BalloonLights.removeChain(store, flight);
        setBurnerRoar(store, flight, false);
        pastePrefab(flight.kind, flight.world, store, origin, rotation);
        restoreBurner(flight.kind, flight.world, store, flight.burner, origin, rotation);
        restoreCargo(flight.world, store, flight.cargo, origin, rotation);
        // T74: the captured animals are put back in the placed cage (still frozen, noted in the registry entry).
        CageAnimals.place(flight.world, origin, rotation, flight.kind.id, flight.animals);
        // T21: passengers are dismounted and placed next to their seat (the balloon is back to blocks).
        disembarkPassengers(store, flight, origin, rotation);
        // T19: the flight is over, the resume file no longer has a reason to exist.
        BalloonResume.delete(flight.pilotUuid);
        removeEntities(store, flight);
        if (flight.pilotRef != null && flight.pilotRef.isValid()) {
            PlayerRef player = store.getComponent(flight.pilotRef, PlayerRef.getComponentType());
            if (player != null) {
                setPilotFlying(store, flight.pilotRef, player, false);
            }
        }
        LOGGER.at(Level.INFO).log("Atterrissage : origine %s, rotation %s", origin, rotation);
    }

    // ------------------------------------------------------------------ flight (called every tick)

    void tick(Store<EntityStore> store, World world) {
        if (!pendingRestores.isEmpty()) {
            retryRestores(store, world);
        }
        if (!pendingCargos.isEmpty()) {
            retryCargos(store, world);
        }
        if (flights.isEmpty()) {
            return;
        }
        // T39: a dead pilot or passenger is removed from the flight (safety net of BalloonDeathSystem).
        checkDeaths(store, world);
        for (BalloonFlight flight : flights.values()) {
            if (flight.world != world) {
                continue;
            }
            StructureShape s;
            try {
                s = flight.kind.shape();
            } catch (IOException e) {
                continue;
            }
            if (flight.serverDriven()) {
                // T20 (no pilot) and T26 (seated pilot): automatic descent, the entity is moved by the server.
                if (flight.balloonRef == null || !flight.balloonRef.isValid()
                        || (!flight.pilotless() && !flight.pilotRef.isValid())) {
                    landNow(flight);
                } else if (!flight.stopping) {
                    long nowMs = System.currentTimeMillis();
                    tickDriven(store, world, s, flight, nowMs);
                    updatePassengers(store, world, flight, nowMs);
                }
                continue;
            }
            if (!flight.pilotRef.isValid() || flight.balloonRef == null || !flight.balloonRef.isValid()) {
                // Pilot gone (other world, disconnect the event did not handle) or entity lost:
                // the balloon is placed where it is. The tick already runs on the world thread.
                landNow(flight);
                continue;
            }
            TransformComponent pilotTransform = store.getComponent(flight.pilotRef, TransformComponent.getComponentType());
            TransformComponent balloonTransform = store.getComponent(flight.balloonRef, TransformComponent.getComponentType());
            if (pilotTransform == null || balloonTransform == null) {
                continue;
            }
            long now = System.currentTimeMillis();
            updateFuel(store, world, flight, now);
            if (now - flight.lastResumeMs >= RESUME_WRITE_INTERVAL_MS) {
                flight.lastResumeMs = now;
                BalloonResume.write(flight, flight.currentOrigin(), false);
            }
            updatePassengers(store, world, flight, now);
            if (!flight.dry && !flight.stopping) {
                updateBoost(store, world, flight, pilotTransform.getPosition().y, now);
            }
            // The fly mode stays forced for the whole flight: only the chain (helper entity) or the land command ends it.
            if (now < flight.teleportUntilMs) {
                continue;
            }
            Vector3d pilotPos = pilotTransform.getPosition();
            Vector3d desired = new Vector3d(pilotPos).sub(flight.pilotOffset);
            Vector3i origin = flight.originCell(desired);

            if (!origin.equals(flight.lastCheckedOrigin)) {
                String hit = collision(world, s, origin, flight.rotation);
                if (hit != null) {
                    // Collision: the pilot is brought back to their last valid position.
                    flight.teleportUntilMs = now + COLLISION_PAUSE_MS;
                    // T37: no collision message in the chat any more, the log keeps the block touched (at most every 2 s).
                    if (now - flight.lastCollisionMsgMs > COLLISION_MSG_INTERVAL_MS) {
                        flight.lastCollisionMsgMs = now;
                        LOGGER.at(Level.INFO).log("Collision : %s", hit);
                    }
                    // T76: the height limit is not visible, so the pilot is told (at most every 3 s).
                    if (hit.contains(HEIGHT_HIT) && now - flight.lastHeightMsgMs >= HEIGHT_MSG_INTERVAL_MS) {
                        flight.lastHeightMsgMs = now;
                        PlayerRef pilotPlayer = store.getComponent(flight.pilotRef, PlayerRef.getComponentType());
                        if (pilotPlayer != null) {
                            pilotPlayer.sendMessage(Texts.t("heightLimit.reached"));
                        }
                    }
                    Vector3d back = new Vector3d(flight.lastValidPilotPos);
                    Rotation3f look = new Rotation3f(pilotTransform.getRotation());
                    Ref<EntityStore> pilotRef = flight.pilotRef;
                    world.execute(() -> {
                        if (pilotRef.isValid()) {
                            store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(back, look));
                        }
                    });
                    continue;
                }
                flight.lastCheckedOrigin.set(origin);
            }
            flight.lastValidPilotPos.set(pilotPos);
            flight.entityPos.set(desired);
            balloonTransform.setPosition(displayPosition(flight.kind, desired, flight.rotation));
            VehicleView.sync(store, flight.balloonRef, flight.observerRef); // T82
            VehicleParts.sync(store, flight.parts, flight.balloonRef, flight.observerRef);
            BalloonLights.follow(store, flight);
            CageAnimals.follow(store, flight);
        }
    }

    /**
     * The player used the chain in flight (helper entity, HotairBalloon_Land): if they pilot a balloon, it is placed back as blocks
     * in place, on the ground or in the air (same rules as /orbishorizon balloon land). Anybody else (passenger, other player) is
     * ignored. If it cannot be placed here, the flight simply continues. Call on the world thread.
     */
    void chainLand(Store<EntityStore> store, PlayerRef player) {
        BalloonFlight flight = flights.get(player.getUuid());
        if (flight == null || flight.stopping) {
            return;
        }
        Message error = land(store, player);
        player.sendMessage(error == null ? Texts.t("stopped") : Texts.t("landFailedContinue").param("error", error));
    }

    /** True if a solid block occupies a cell of the balloon placed at this origin. */
    /** Burner flame particles (On state of Hotair_Balloon_Burner.json, like the game's brazier). */
    private static final String BURNER_FLAME = "Campfire_New_Cartoon";

    /**
     * Removing the block is not enough: the flame (of the brazier, then of the burner since T16) stayed displayed in the sky (tests of
     * 3 October 2026, with and without the no-particles option). So clients are asked
     * to stop the flame particle systems in the burner's cell and above it, with the
     * CancelParticleSystems packet used by the CancelParticles effect of the game's trigger volumes.
     */
    static void cancelBlockFlame(World world, Vector3i p) {
        CancelParticleSystems packet = new CancelParticleSystems(
                new Position(p.x - 0.5, p.y - 0.5, p.z - 0.5), new Position(p.x + 1.5, p.y + 2.5, p.z + 1.5),
                new String[]{BURNER_FLAME}, true);
        for (PlayerRef player : world.getPlayerRefs()) {
            player.getPacketHandler().writeNoCache(packet);
        }
    }

    /** T76: marker of the collision description when the contact is the height limit (not a block). */
    static final String HEIGHT_HIT = "limite de hauteur de construction";
    /** T76: minimum delay between two "height limit reached" messages to the pilot. */
    static final long HEIGHT_MSG_INTERVAL_MS = 3000;

    /** Describes the first solid or liquid block touched by the balloon, or null if there is none. */
    private static String collision(World world, StructureShape s, Vector3i origin, Rotation rotation) {
        for (StructureShape.Cell c : s.flightCells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            if (outOfHeight(p.y)) {
                return c.baseName() + " de la montgolfière dépasse la " + HEIGHT_HIT + " en " + p.x + " " + p.y + " " + p.z;
            }
            BlockType bt = world.getBlockType(p.x, p.y, p.z);
            if (bt != null && bt.getMaterial() == BlockMaterial.Solid) {
                return c.baseName() + " de la montgolfière touche " + bt.getId() + " en " + p.x + " " + p.y + " " + p.z;
            }
            String liquid = liquidAt(world, p.x, p.y, p.z);
            if (liquid != null) {
                return c.baseName() + " de la montgolfière touche le liquide " + liquid + " en " + p.x + " " + p.y + " " + p.z;
            }
        }
        return null;
    }

    /**
     * Identifier of the liquid in this cell (water, lava, poison, slime, tar), or null if there is none. Fluids
     * are stored apart from blocks (FluidSection): world.getBlockType gives an empty block in water.
     * WorldChunk.getFluidId takes world coordinates and returns Fluid.EMPTY_ID (0) without fluid. Fire is
     * also a fluid in the game (Fire.json, FireFluidTicker) but not a liquid: it is ignored. An unloaded
     * chunk counts as without liquid, like getBlockType which returns null.
     */
    static String liquidAt(World world, int x, int y, int z) {
        try {
            WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(x), ChunkUtil.chunkCoordinate(z)));
            if (chunk == null) {
                return null;
            }
            int id = chunk.getFluidId(x, y, z);
            if (id == Fluid.EMPTY_ID) {
                return null;
            }
            Fluid fluid = Fluid.getAssetMap().getAsset(id);
            String name = fluid != null ? fluid.getId() : "fluide " + id;
            return name.startsWith(FIRE_FLUID_PREFIX) ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * T76: true if the cell is outside the world's height range (ChunkUtil.MIN_Y = 0 to HEIGHT_MINUS_1 = 319). Blocks
     * placed above the limit are lost, so a vehicle must never reach such a cell.
     */
    static boolean outOfHeight(int y) {
        return y < ChunkUtil.MIN_Y || y > ChunkUtil.HEIGHT_MINUS_1;
    }

    /** T76: true if one cell of the structure (all cells, folded ladder included) is outside the height range at this origin. */
    static boolean exceedsHeight(StructureShape s, Vector3i origin, Rotation rotation) {
        for (StructureShape.Cell c : s.cells()) {
            if (outOfHeight(c.rotated(rotation).add(origin).y)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T76: vertical shift (in blocks, positive = up) that brings every cell of the structure back into the height range, 0 if it
     * already fits, Integer.MIN_VALUE if the structure is too tall for the world. Used by the resume after a stop.
     */
    static int heightShift(StructureShape s, Vector3i origin, Rotation rotation) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (StructureShape.Cell c : s.cells()) {
            int y = c.rotated(rotation).add(origin).y;
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        if (max - min > ChunkUtil.HEIGHT_MINUS_1 - ChunkUtil.MIN_Y) {
            return Integer.MIN_VALUE;
        }
        if (max > ChunkUtil.HEIGHT_MINUS_1) {
            return ChunkUtil.HEIGHT_MINUS_1 - max;
        }
        return min < ChunkUtil.MIN_Y ? ChunkUtil.MIN_Y - min : 0;
    }

    /** True if the cell stops the balloon: solid block, liquid, or outside the world's height range (T76). */
    static boolean blocksBalloon(World world, int x, int y, int z) {
        if (outOfHeight(y)) {
            return true;
        }
        BlockType bt = world.getBlockType(x, y, z);
        return (bt != null && bt.getMaterial() == BlockMaterial.Solid) || liquidAt(world, x, y, z) != null;
    }

    static boolean collides(World world, StructureShape s, Vector3i origin, Rotation rotation) {
        return collides(world, s, origin, rotation, java.util.Set.of());
    }

    static boolean collides(World world, StructureShape s, Vector3i origin, Rotation rotation, java.util.Set<Vector3i> ignore) {
        // T76: every cell, the folded ladder included, must stay inside the world's height range (blocks above are lost).
        if (exceedsHeight(s, origin, rotation)) {
            return true;
        }
        // T24: folded ladder in flight, only the flight cells hit the scenery.
        for (StructureShape.Cell c : s.flightCells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            if (ignore.contains(p)) {
                continue;
            }
            if (blocksBalloon(world, p.x, p.y, p.z)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ game mode change (T75)

    /**
     * Re-applies the movement settings of a game mode to a player, like Player.setGameModeInternal (MovementManager.resetFly(mode)
     * then update(packetHandler)), after the mod's own resets (which restore the default speeds). Returns true if the mode lets
     * the player fly (fly mode not Disabled): creative, so no need to put them on the ground.
     */
    static boolean reapplyGameMode(Store<EntityStore> store, Ref<EntityStore> ref, GameMode mode) {
        try {
            if (ref == null || !ref.isValid()) {
                return false;
            }
            MovementManager mm = store.getComponent(ref, MovementManager.getComponentType());
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            if (mm == null || pr == null) {
                return false;
            }
            mm.resetFly(mode);
            mm.update(pr.getPacketHandler());
            return mm.getSettings().fly != FlyMode.Disabled;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Réglages du mode de jeu non réappliqués");
            return false;
        }
    }

    /** True if the player is the pilot or a passenger of a balloon flight. */
    private boolean inFlight(Ref<EntityStore> ref) {
        for (BalloonFlight flight : flights.values()) {
            if (ref.equals(flight.pilotRef)) {
                return true;
            }
            for (BalloonFlight.Passenger p : flight.passengers) {
                if (ref.equals(p.ref)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Called by GameModeSystem while the event is being handled: the mode has NOT changed yet (setGameModeInternal runs right after
     * the systems). Processing goes through world.execute (queued, so after the change), and only if the mode really changed (another
     * system may have cancelled the event). A pilot or passenger leaves the vehicle.
     */
    void onGameModeChange(Store<EntityStore> store, Ref<EntityStore> ref, GameMode newMode) {
        if (flights.isEmpty() || !inFlight(ref)) {
            return;
        }
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getGameMode() != newMode) {
                return; // change cancelled: nothing to do
            }
            handleGameModeChange(store, world, ref, newMode);
        });
    }

    /**
     * The player left the vehicle because of a game mode change. Pilot: the balloon is placed in place (same path as the chain), else
     * it becomes a pilotless flight that comes down on its own (pilotDied without the death). During the automatic descent (seated pilot,
     * T26) it is the pilotless descent directly. Passenger: released and removed from the flight. In both cases the player is put on
     * the ground under them if the new mode cannot fly, and the new mode's movement settings are re-applied so the mod's resets do not
     * overwrite them. On the world thread, no effect if repeated (the player is no longer in a flight).
     */
    private void handleGameModeChange(Store<EntityStore> store, World world, Ref<EntityStore> ref, GameMode mode) {
        for (BalloonFlight flight : new ArrayList<>(flights.values())) {
            if (flight.world != world) {
                continue;
            }
            if (ref.equals(flight.pilotRef)) {
                if (flight.stopping || flights.get(flight.pilotUuid) != flight) {
                    continue;
                }
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                TransformComponent pt = store.getComponent(ref, TransformComponent.getComponentType());
                Vector3d pilotPos = pt != null ? new Vector3d(pt.getPosition()) : null;
                boolean landed = false;
                if (player != null && !flight.driven) {
                    landed = land(store, player) == null;
                }
                if (!landed) {
                    pilotDied(store, world, flight, false);
                }
                boolean canFly = reapplyGameMode(store, ref, mode);
                if (!landed && !canFly && pilotPos != null) {
                    putOnGround(store, world, ref, pilotPos);
                }
                if (player != null) {
                    player.sendMessage(Texts.t("gameMode.left"));
                }
                LOGGER.at(Level.INFO).log("Pilote %s : changement de mode de jeu (%s), %s", player != null ? player.getUsername() : ref,
                        mode, landed ? "montgolfière posée sur place" : "montgolfière sans pilote");
                continue;
            }
            for (BalloonFlight.Passenger p : new ArrayList<>(flight.passengers)) {
                if (!ref.equals(p.ref)) {
                    continue;
                }
                Vector3d seat = prefabPoint(flight.entityPos, flight.rotation, p.seatLocal);
                releaseDeadPassenger(store, flight, p);
                boolean canFly = reapplyGameMode(store, ref, mode);
                if (!canFly) {
                    putOnGround(store, world, ref, seat);
                }
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr != null) {
                    pr.sendMessage(Texts.t("gameMode.left"));
                }
                LOGGER.at(Level.INFO).log("Passager %s : changement de mode de jeu (%s), démonté", p.name, mode);
            }
        }
    }

    // ------------------------------------------------------------------ death of the pilot or a passenger (T39)

    /**
     * The game removes neither the entity nor the reference of a player who dies: it adds DeathComponent to the same
     * entity (DeathComponent.tryAddComponent) and, on respawn, adds a Teleport to the spawn
     * point (RespawnController, HomeOrSpawnPoint or WorldSpawnPoint) then removes DeathComponent. A dead
     * player therefore keeps a valid reference throughout the death.
     */
    static boolean isDead(Store<EntityStore> store, Ref<EntityStore> ref) {
        return ref != null && ref.isValid() && store.getComponent(ref, DeathComponent.getComponentType()) != null;
    }

    /**
     * Called by BalloonDeathSystem as soon as the death component is added to a player, so well before
     * respawn. Processing goes through the world thread (outside the system) because it changes components.
     */
    void onPlayerDeath(Store<EntityStore> store, Ref<EntityStore> ref) {
        AirshipManager.get().onPlayerDeath(store, ref);
        if (flights.isEmpty()) {
            return;
        }
        World world = store.getExternalData().getWorld();
        world.execute(() -> handleDeath(store, world, ref));
    }

    /** Safety net called by tick(): a dead player of a flight that the event has not yet handled. */
    private void checkDeaths(Store<EntityStore> store, World world) {
        List<Ref<EntityStore>> dead = new ArrayList<>();
        for (BalloonFlight flight : flights.values()) {
            if (flight.world != world) {
                continue;
            }
            if (isDead(store, flight.pilotRef)) {
                dead.add(flight.pilotRef);
            }
            for (BalloonFlight.Passenger p : flight.passengers) {
                if (isDead(store, p.ref)) {
                    dead.add(p.ref);
                }
            }
        }
        for (Ref<EntityStore> ref : dead) {
            world.execute(() -> handleDeath(store, world, ref));
        }
    }

    /** Removes the dead player from the flight where they are pilot (or seated pilot) or passenger. On the world thread, no effect if repeated. */
    private void handleDeath(Store<EntityStore> store, World world, Ref<EntityStore> ref) {
        for (BalloonFlight flight : new ArrayList<>(flights.values())) {
            if (flight.world != world) {
                continue;
            }
            if (ref.equals(flight.pilotRef)) {
                pilotDied(store, world, flight);
                continue;
            }
            for (BalloonFlight.Passenger p : flight.passengers) {
                if (ref.equals(p.ref)) {
                    passengerDied(store, flight, p);
                }
            }
        }
    }

    /**
     * The pilot died in flight: they are no longer tied to the balloon, which descends on its own. Pilot in free flight or
     * seated pilot of the automatic descent (T26), same handling: removed from the passenger list (no
     * teleport, respawn sends them to the spawn point), entity detached from them, movement
     * settings back to normal (no more forced flight or zero speeds), then the flight becomes a flight without a
     * pilot (T20): flame off (model without flame and burner light cut, T13 and T35), automatic descent
     * at the pilot's remembered speed, placed as blocks on contact (finishFlight). The contents of the
     * burner and chests stay in the flight. Passengers stay seated and are placed on landing (T21).
     * The flight changes identifier (random, like a flight without a pilot): the respawned player can pilot another
     * balloon, and the resume file of T19 follows the flight under the new identifier.
     */
    private void pilotDied(Store<EntityStore> store, World world, BalloonFlight flight) {
        pilotDied(store, world, flight, true);
    }

    /** Same, T75: dead false when the pilot leaves because of a game mode change (other message, same handling). */
    private void pilotDied(Store<EntityStore> store, World world, BalloonFlight flight, boolean dead) {
        Ref<EntityStore> ref = flight.pilotRef;
        if (ref == null || flights.get(flight.pilotUuid) != flight) {
            return;
        }
        PlayerRef player = ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : null;
        String name = player != null ? player.getUsername() : String.valueOf(flight.pilotUuid);
        for (BalloonFlight.Passenger p : flight.passengers) {
            if (ref.equals(p.ref)) {
                releaseDeadPassenger(store, flight, p);
            }
        }
        if (flight.attached && flight.balloonRef != null && flight.balloonRef.isValid()) {
            store.tryRemoveComponent(flight.balloonRef, MountedComponent.getComponentType());
        }
        flight.attached = false;
        collapseView(store, flight); // T82
        if (player != null) {
            try {
                setPilotFlying(store, ref, player, false);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Réglages de déplacement de %s non remis à la normale", name);
            }
        }
        boolean wasFree = !flight.driven;
        if (wasFree) {
            // The pilot is no longer there: nothing of theirs is stopping the flight any more.
            flight.stopping = false;
        }
        long now = System.currentTimeMillis();
        if (flight.descentSpeed <= 0) {
            flight.descentSpeed = defaultDescentSpeed(world, store);
        }
        flight.lastDescentMs = now;
        flight.dry = true;
        flight.driven = true;
        // New identifier: the resume file is written under the new name before the old one is erased (a
        // resume that saw both empties the containers already present, recoverOne, so nothing is duplicated).
        UUID oldId = flight.pilotUuid;
        flights.remove(oldId);
        flight.pilotUuid = UUID.randomUUID();
        flight.pilotRef = null;
        flights.put(flight.pilotUuid, flight);
        flight.lastResumeMs = now;
        BalloonResume.write(flight, flight.currentOrigin(), true);
        BalloonResume.delete(oldId);
        setBalloonFlame(store, flight, false);
        for (BalloonFlight.Passenger p : flight.passengers) {
            if (!p.ref.isValid()) {
                continue;
            }
            PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
            if (pr != null) {
                pr.sendMessage(Texts.t(dead ? "pilotDied" : "pilotLeft"));
            }
        }
        LOGGER.at(Level.INFO).log("Pilote %s %s : montgolfière sans pilote, descente automatique à %.2f blocs par seconde (%d passager(s))",
                name, dead ? "mort en vol" : "parti du vol", flight.descentSpeed, flight.passengers.size());
    }

    /** A passenger died: dismounted and forgotten by the flight, the flight continues. They respawn normally. */
    private void passengerDied(Store<EntityStore> store, BalloonFlight flight, BalloonFlight.Passenger p) {
        if (!flight.passengers.contains(p)) {
            return;
        }
        releaseDeadPassenger(store, flight, p);
        LOGGER.at(Level.INFO).log("Passager %s mort en vol : démonté, le vol continue", p.name);
    }

    /** Dismounts a dead player without teleporting them: mount removed, seated pose undone, flight settings restored. Never throws. */
    private void releaseDeadPassenger(Store<EntityStore> store, BalloonFlight flight, BalloonFlight.Passenger p) {
        flight.passengers.remove(p);
        if (!p.ref.isValid()) {
            return;
        }
        try {
            MountUtil.unseat(store, p.ref);
            clearPose(store, p);
            setPassengerHover(store, p, false);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Joueur mort %s non démonté proprement", p.name);
        }
    }

    // ------------------------------------------------------------------ removal by an administrator (T40)

    /** Search radius of /orbishorizon balloon despawn around the administrator, in blocks. */
    private static final double DESPAWN_RADIUS = 48;
    /* Crate item given back to the administrator: that of the removed balloon's type (Deployables.BalloonSpec.crateItemId, T54). */

    /** Result of /orbishorizon balloon despawn: success or not, and the message to display. */
    record DespawnResult(boolean ok, Message message) {
    }

    private static boolean hasPassenger(BalloonFlight f, UUID uuid) {
        for (BalloonFlight.Passenger p : f.passengers) {
            if (p.uuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T40: removes the balloon closest to the administrator (within DESPAWN_RADIUS blocks), placed or in flight.
     * The burner and chest items are dropped on the ground, the blocks (or the flying entity and its lights) are
     * removed without dropping blocks, the balloon leaves the T27 registry and the T19 resume file, and the
     * administrator receives a deployment crate. To be called on the world thread, after the permission check
     * done by the command. The administrator's own flight (pilot or passenger) takes priority over the others.
     */
    DespawnResult despawn(Store<EntityStore> store, Ref<EntityStore> adminRef, PlayerRef admin, World world) {
        Message unreadable = checkPrefabsReadable();
        if (unreadable != null) {
            return new DespawnResult(false, unreadable);
        }
        TransformComponent at = store.getComponent(adminRef, TransformComponent.getComponentType());
        if (at == null) {
            return new DespawnResult(false, Texts.t("error.noPosition"));
        }
        Vector3d here = new Vector3d(at.getPosition());

        // Balloon in flight: their own (pilot or passenger) first, otherwise the closest.
        BalloonFlight flight = null;
        double flightDist = Double.MAX_VALUE;
        for (BalloonFlight f : flights.values()) {
            if (f.world != world) {
                continue;
            }
            boolean mine = admin.getUuid().equals(f.pilotUuid) || hasPassenger(f, admin.getUuid());
            double d = mine ? 0 : prefabPoint(f.entityPos, f.rotation, pivot(f.kind)).distance(here);
            if (d <= DESPAWN_RADIUS && d < flightDist) {
                flight = f;
                flightDist = d;
            }
        }

        // Placed balloon: the T27 registry first (burner in place), then the search around the player,
        // which also finds a balloon placed before T27 (absent from the registry).
        Found posed = null;
        double posedDist = Double.MAX_VALUE;
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesIn(world)) {
            Deployables.Kind entryKind = Deployables.get(e.kind());
            StructureShape s = entryKind != null ? entryKind.shapeOrNull() : null;
            if (s == null) {
                continue;
            }
            // T73: the removal takes the cage as it is (lowered, open), the burner and anchor are those of the prefab.
            Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
            Vector3i burner = s.burner().rotated(e.rotation()).add(origin);
            BlockType bt = world.getBlockType(burner.x, burner.y, burner.z);
            if (bt == null || !sameBlock(bt, s.anchor())) {
                continue; // chunk not loaded or balloon already destroyed
            }
            double d = new Vector3d(burner.x + 0.5, burner.y + 0.5, burner.z + 0.5).distance(here);
            if (d <= DESPAWN_RADIUS && d < posedDist) {
                posed = new Found(entryKind, origin, e.rotation(), 0);
                posedDist = d;
            }
        }
        if (posed == null) {
            Found f = findBalloon(world, here);
            if (f != null) {
                Vector3i burner = f.kind().shapeOrNull().burner().rotated(f.rotation).add(f.origin);
                posed = f;
                posedDist = new Vector3d(burner.x + 0.5, burner.y + 0.5, burner.z + 0.5).distance(here);
            }
        }

        if (flight == null && posed == null) {
            return new DespawnResult(false, Texts.t("despawn.none").param("radius", (int) DESPAWN_RADIUS));
        }
        Message error;
        String crateItem;
        if (flight != null && (posed == null || flightDist <= posedDist)) {
            crateItem = flight.kind.balloon.crateItemId;
            error = despawnFlight(store, flight, adminRef, admin, here);
        } else {
            crateItem = posed.kind().balloon.crateItemId;
            // T73: the shape in the registered state, so that a lowered cage and its chain are removed too.
            error = despawnPosed(store, world, posed.kind(), shapeOfFound(world, posed), posed.origin, posed.rotation, adminRef, admin, here);
        }
        if (error != null) {
            return new DespawnResult(false, error);
        }
        boolean given = giveCrate(store, adminRef, here, crateItem);
        return new DespawnResult(true, Texts.t(given ? "despawn.done" : "despawn.doneInventoryFull"));
    }

    /**
     * Gives a deployment crate to the administrator (Player.giveItem, like picking up an item). If
     * the inventory is full, the crate is dropped at their feet. Returns true if it is in the inventory.
     */
    boolean giveCrate(Store<EntityStore> store, Ref<EntityStore> adminRef, Vector3d at, String crateItemId) {
        ItemStack crate = new ItemStack(crateItemId, 1);
        ItemStack rest = crate;
        try {
            ItemStackTransaction t = Player.giveItem(crate, adminRef, store);
            rest = t != null ? t.getRemainder() : crate;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Caisse non donnée à l'administrateur, lâchée au sol");
        }
        if (rest != null && !rest.isEmpty()) {
            dropItems(store, List.of(rest), new Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)));
            return false;
        }
        return true;
    }

    /** Teleports a player to the ground under a point (dismounted first: a teleport removes the mount, T21). */
    static void putOnGround(Store<EntityStore> store, World world, Ref<EntityStore> ref, Vector3d from) {
        MountUtil.unseat(store, ref);
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        Rotation3f look = t != null ? new Rotation3f(t.getRotation()) : new Rotation3f();
        store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(groundBelow(world, from), look));
    }

    /**
     * T40, placed balloon: the burner and chest contents are copied then emptied BEFORE the blocks are removed.
     * The game already drops a container's contents when its block disappears (ItemContainerSystems$OnAddedOrRemoved and
     * BenchSystems$ProcessingBenchLifecycle, onEntityRemove: dropAllItemStacks then ItemComponent.generateItemDrops,
     * with the setBlock options 0 and 4 used here, only option 2 skips the component): a single path is
     * kept, the plugin's, empty containers drop nothing and nothing is duplicated. The items are then
     * dropped at each container's location. Contents still waiting to be put back after a recent
     * landing (pendingRestores, pendingCargos) are taken and emptied the same way.
     */
    private Message despawnPosed(Store<EntityStore> store, World world, Deployables.Kind kind, StructureShape s, Vector3i origin,
                                Rotation rotation, Ref<EntityStore> adminRef, PlayerRef admin, Vector3d adminPos) {
        Vector3i burnerPos = s.burner().rotated(rotation).add(origin);
        if (!allChunksLoaded(world, s, burnerPos)) {
            return Texts.t("despawn.chunks");
        }
        // Players in the gondola lose their floor: they are put on the ground after the removal.
        List<Occupant> inside = nacelleOccupants(world, store, kind, origin, rotation);
        List<Vector3d> insidePos = new ArrayList<>();
        for (Occupant o : inside) {
            TransformComponent t = store.getComponent(o.ref(), TransformComponent.getComponentType());
            insidePos.add(t != null ? new Vector3d(t.getPosition()) : new Vector3d(burnerPos.x + 0.5, burnerPos.y, burnerPos.z + 0.5));
        }

        ProcessingBenchBlock bench = BurnerFuel.live(world, burnerPos);
        BurnerFuel fuel = bench != null ? BurnerFuel.takeFrom(bench) : null;
        BalloonCargo cargo = BalloonCargo.takeFrom(world, s, origin, rotation);
        List<ItemStack> burnerItems = fuel != null ? fuel.takeAllItems() : new ArrayList<>();
        List<ItemStack> pendingBurnerItems = new ArrayList<>();
        for (PendingRestore p : new ArrayList<>(pendingRestores)) {
            if (p.world == world && p.burnerPos.equals(burnerPos)) {
                pendingBurnerItems.addAll(p.fuel.takeAllItems());
                pendingRestores.remove(p);
            }
        }
        List<ItemStack> pendingCargoItems = new ArrayList<>();
        for (PendingCargo p : new ArrayList<>(pendingCargos)) {
            if (p.world == world && p.origin.equals(origin) && p.rotation == rotation) {
                pendingCargoItems.addAll(p.cargo.takeAllItems());
                pendingCargos.remove(p);
            }
        }

        // T74: animals captured in the cage are released (no longer frozen) and put on the ground under it.
        BalloonRegistry.Entry cageEntry = BalloonRegistry.get(world, origin, rotation, kind.id);
        List<CageAnimals.Animal> cageAnimals = cageEntry == null ? List.of() : cageEntry.animals();
        Vector3d[] cagePos = new Vector3d[cageAnimals.size()];
        for (int i = 0; i < cagePos.length; i++) {
            cagePos[i] = CageAnimals.worldPos(cageAnimals.get(i), origin, rotation, cageEntry.descent());
        }
        // The T27 registry first (the plugin's removals do not go through the locking events).
        BalloonRegistry.remove(world, origin, rotation, kind.id);
        int removed = removeBlocks(world, s, origin, rotation);
        CageAnimals.releaseToGround(world, cageAnimals, cagePos);
        hovers.remove(world.getName() + ":" + burnerPos.x + "," + burnerPos.y + "," + burnerPos.z);

        int items = 0;
        items += dropAndCount(store, burnerItems, burnerPos);
        items += dropAndCount(store, pendingBurnerItems, burnerPos);
        for (BalloonCargo.Entry e : cargo.entries()) {
            items += dropAndCount(store, e.items(), rotation.rotateYaw(new Vector3i(e.local), new Vector3i()).add(origin));
        }
        items += dropAndCount(store, pendingCargoItems, burnerPos);

        for (int i = 0; i < inside.size(); i++) {
            Occupant o = inside.get(i);
            try {
                putOnGround(store, world, o.ref(), insidePos.get(i));
                if (!o.player().getUuid().equals(admin.getUuid())) {
                    o.player().sendMessage(Texts.t("despawn.removedByAdmin"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Joueur %s de la nacelle non posé au sol", o.player().getUsername());
            }
        }
        LOGGER.at(Level.INFO).log("Retrait par %s : montgolfière posée en %s (rotation %s), %d blocs retirés, %d objet(s) lâchés",
                admin.getUsername(), origin, rotation, removed, items);
        return null;
    }

    static int dropAndCount(Store<EntityStore> store, List<ItemStack> list, Vector3i pos) {
        int n = 0;
        for (ItemStack st : list) {
            if (st != null && !st.isEmpty()) {
                n += st.getQuantity();
            }
        }
        dropItems(store, list, pos);
        return n;
    }

    /**
     * T40, balloon in flight: the flight leaves flights (the tick no longer follows it), the lights (T34, T35) and the
     * resume file (T19) are removed, the passengers (the seated pilot of T26 included) are dismounted and
     * put on the ground under their seat, the pilot in free flight gets their movement settings back and is put on the ground
     * under the gondola (otherwise they would fall from a height), then the entity is removed. The contents kept by the flight
     * (burner and chests, copied at take-off) are dropped on the ground under the balloon, or near the administrator
     * if there is no ground within 512 blocks.
     */
    private Message despawnFlight(Store<EntityStore> store, BalloonFlight flight, Ref<EntityStore> adminRef, PlayerRef admin,
                                 Vector3d adminPos) {
        if (flights.get(flight.pilotUuid) != flight) {
            return Texts.t("despawn.alreadyLanding");
        }
        World world = flight.world;
        flights.remove(flight.pilotUuid);
        BalloonLights.removeAll(store, flight);
        BalloonLights.removeChain(store, flight);
        setBurnerRoar(store, flight, false);
        Vector3d pivot = prefabPoint(flight.entityPos, flight.rotation, pivot(flight.kind));
        boolean pilotSeated = false;
        for (BalloonFlight.Passenger p : flight.passengers) {
            if (flight.pilotRef != null && flight.pilotRef.equals(p.ref)) {
                pilotSeated = true;
            }
            try {
                if (!p.ref.isValid()) {
                    continue;
                }
                clearPose(store, p);
                setPassengerHover(store, p, false);
                if (!isDead(store, p.ref)) {
                    putOnGround(store, world, p.ref, prefabPoint(flight.entityPos, flight.rotation, p.seatLocal));
                }
                if (!p.uuid.equals(admin.getUuid())) {
                    PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
                    if (pr != null) {
                        pr.sendMessage(Texts.t("despawn.removedByAdmin"));
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager %s non démonté proprement", p.name);
            }
        }
        flight.passengers.clear();
        Ref<EntityStore> pilot = flight.pilotRef;
        if (pilot != null && pilot.isValid()) {
            try {
                PlayerRef pp = store.getComponent(pilot, PlayerRef.getComponentType());
                if (pp != null) {
                    setPilotFlying(store, pilot, pp, false);
                    if (!pilotSeated && !pp.getUuid().equals(admin.getUuid())) {
                        pp.sendMessage(Texts.t("despawn.removedByAdmin"));
                    }
                }
                if (!pilotSeated && !isDead(store, pilot)) {
                    putOnGround(store, world, pilot, pivot);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Pilote non remis au sol proprement");
            }
        }

        // Contents kept by the flight: dropped on the ground under the balloon.
        Vector3d ground = groundBelow(world, pivot);
        Vector3d where = ground.y != pivot.y ? ground : adminPos;
        Vector3i dropPos = new Vector3i((int) Math.floor(where.x), (int) Math.floor(where.y), (int) Math.floor(where.z));
        int items = 0;
        if (flight.burner != null) {
            items += dropAndCount(store, flight.burner.takeAllItems(), dropPos);
        }
        if (flight.cargo != null) {
            items += dropAndCount(store, flight.cargo.takeAllItems(), dropPos);
        }
        // T74: animals of the cage released and put on the ground under the balloon.
        Vector3d[] flightAnimalPos = new Vector3d[flight.animals.size()];
        for (int i = 0; i < flightAnimalPos.length; i++) {
            flightAnimalPos[i] = CageAnimals.flightPos(flight.animals.get(i), flight);
        }
        CageAnimals.releaseToGround(world, flight.animals, flightAnimalPos);
        flight.animals.clear();
        BalloonResume.delete(flight.pilotUuid);
        removeEntities(store, flight);
        LOGGER.at(Level.INFO).log("Retrait par %s : montgolfière en vol en %s, %d objet(s) lâchés", admin.getUsername(),
                fmt(pivot), items);
        return null;
    }

    // ------------------------------------------------------------------ shutdown, disconnect, resume (T19)

    /**
     * T82: removes the flying entity and, with dual rendering, the observer entity (and their visibility rules). The lights and the chain
     * helper are removed by the callers (BalloonLights.removeAll, removeChain). Never throws.
     */
    private void removeEntities(Store<EntityStore> store, BalloonFlight flight) {
        VehicleParts.removeAll(store, flight.parts);
        flight.parts = null;
        Ref<EntityStore> observer = flight.observerRef;
        flight.observerRef = null;
        flight.dual = false;
        VehicleView.removeObserver(store, observer);
        if (flight.balloonRef != null) {
            ViewFilter.clear(flight.balloonRef);
            if (flight.balloonRef.isValid()) {
                store.removeEntity(flight.balloonRef, RemoveReason.REMOVE);
            }
        }
    }

    /**
     * T82: the flight has no pilot on board any more (pilot seated for the dry descent, dead, gone, left through a game mode change): one
     * entity seen by everybody is enough. The observer entity and the twin lights are removed, the entity that was mounted on the
     * pilot (now detached and moved by the server like in a pilotless flight) and its lights and chain helper become visible to everybody.
     * No effect in single rendering. On the world thread.
     */
    void collapseView(Store<EntityStore> store, BalloonFlight flight) {
        if (!flight.dual) {
            return;
        }
        flight.dual = false;
        Ref<EntityStore> observer = flight.observerRef;
        flight.observerRef = null;
        VehicleView.removeObserver(store, observer);
        VehicleParts.collapse(store, flight.parts);
        if (flight.balloonRef != null) {
            ViewFilter.clear(flight.balloonRef);
        }
        for (BalloonLights.Light l : flight.lights) {
            BalloonLights.dropTwin(store, l);
        }
        AirshipHelm.Helper chain = flight.chain;
        if (chain != null) {
            ViewFilter.clear(chain.ref);
        }
        LOGGER.at(Level.INFO).log("Affichage double terminé : une seule entité visible de tous");
    }

    private volatile boolean renderDual = true;
    /** Model parts setting (next take-off). */
    volatile VehicleParts.Mode partsMode = VehicleParts.Mode.ON;

    /** T82: dual rendering (pilot entity for the pilot, observer entity for the others) or the single entity of before. Next take-off. */
    boolean renderDual() {
        return renderDual;
    }

    void setRenderDual(boolean value) {
        renderDual = value;
    }

    /** Places the balloon where it is (last valid position). To be called on its world's thread. */
    private void landNow(BalloonFlight flight) {
        if (flights.get(flight.pilotUuid) != flight) {
            return;
        }
        Store<EntityStore> store = flight.world.getEntityStore().getStore();
        finishFlight(store, flight, flight.currentOrigin());
    }

    /**
     * Runs the task on the world thread and waits for it to finish (at most timeoutMs). Directly if we are
     * already on it. False if the world no longer accepts tasks (stopped: World.execute then throws an exception) or
     * if the task did not finish in time.
     */
    static boolean runOnWorld(World world, Runnable task, long timeoutMs) {
        if (world.isInThread()) {
            task.run();
            return true;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    task.run();
                    done.complete(null);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            done.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pose sur le fil du monde %s impossible", world.getName());
            return false;
        }
    }

    /**
     * The player disconnects (PlayerDisconnectEvent). Universe.removePlayer sends this event before
     * removing the player from the world: the balloon is placed straight away, pilot still present. In single-player,
     * this is the first link of the shutdown (the server only stops afterwards).
     */
    void onPilotGone(UUID pilotUuid) {
        BalloonFlight flight = flights.get(pilotUuid);
        if (flight == null) {
            return;
        }
        boolean ok = runOnWorld(flight.world, () -> landNow(flight), LAND_WAIT_MS);
        LOGGER.at(ok ? Level.INFO : Level.WARNING).log(ok
                ? "Pilote %s parti : montgolfière reposée"
                : "Pilote %s parti : pose impossible, le fichier de reprise la reposera au prochain chargement du monde", pilotUuid);
    }

    /**
     * Places all balloons in flight. Called by the server shutdown event, before the worlds
     * stop (priority -50, before the players' disconnection at -48 and the worlds' shutdown at -32), then by
     * the plugin's shutdown(). The latter call comes too late: the worlds are stopped and refuse
     * tasks, which is harmless since the resume files remain.
     */
    void landAll() {
        for (BalloonFlight flight : new ArrayList<>(flights.values())) {
            boolean ok = runOnWorld(flight.world, () -> landNow(flight), LAND_WAIT_MS);
            if (!ok) {
                LOGGER.at(Level.WARNING).log("Montgolfière de %s non reposée : fichier de reprise gardé", flight.pilotUuid);
            }
        }
    }

    /**
     * Resume on world load: places each flight left in the resume files, with its
     * contents, and removes the flying entity that may have been saved with the world. Called on
     * StartWorldEvent and at plugin start for worlds already running.
     */
    void recoverWorld(World world) {
        for (BalloonResume.Record rec : BalloonResume.loadAll(world.getName())) {
            if (flights.containsKey(rec.pilot()) || !recovering.add(rec.pilot())) {
                continue;
            }
            try {
                StructureShape s = rec.kind().shape();
                // Chunks covered by the balloon: they must be loaded before placing the blocks.
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (StructureShape.Cell c : s.cells()) {
                    Vector3i p = c.rotated(rec.rotation()).add(rec.origin());
                    minX = Math.min(minX, p.x);
                    maxX = Math.max(maxX, p.x);
                    minZ = Math.min(minZ, p.z);
                    maxZ = Math.max(maxZ, p.z);
                }
                List<CompletableFuture<?>> loads = new ArrayList<>();
                for (int cx = ChunkUtil.chunkCoordinate(minX); cx <= ChunkUtil.chunkCoordinate(maxX); cx++) {
                    for (int cz = ChunkUtil.chunkCoordinate(minZ); cz <= ChunkUtil.chunkCoordinate(maxZ); cz++) {
                        loads.add(world.getChunkAsync(ChunkUtil.indexChunk(cx, cz)));
                    }
                }
                LOGGER.at(Level.INFO).log("Reprise du vol de %s dans %s : origine %s, %d chunks à charger",
                        rec.pilot(), world.getName(), rec.origin(), loads.size());
                CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, t) -> {
                    if (t != null) {
                        LOGGER.at(Level.WARNING).withCause(t).log("Chargement des chunks de la reprise impossible pour %s", rec.pilot());
                    }
                    try {
                        world.execute(() -> recoverOne(world, rec));
                    } catch (RuntimeException e) {
                        recovering.remove(rec.pilot());
                        LOGGER.at(Level.WARNING).withCause(e).log("Monde %s arrêté : reprise de %s remise au prochain chargement",
                                world.getName(), rec.pilot());
                    }
                });
            } catch (IOException | RuntimeException e) {
                recovering.remove(rec.pilot());
                LOGGER.at(Level.SEVERE).withCause(e).log("Reprise du vol de %s impossible : fichier gardé", rec.pilot());
            }
        }
    }

    /** Places the prefab and puts the contents back. On the world thread, chunks loaded. */
    private void recoverOne(World world, BalloonResume.Record rec) {
        try {
            if (!BalloonResume.exists(rec.pilot())) {
                return; // already put back by another resume
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (rec.balloon() != null) {
                Ref<EntityStore> orphan = world.getEntityStore().getRefFromUUID(rec.balloon());
                if (orphan != null && orphan.isValid()) {
                    store.removeEntity(orphan, RemoveReason.REMOVE);
                    LOGGER.at(Level.INFO).log("Entité volante restée dans le monde supprimée (%s)", rec.balloon());
                }
            }
            StructureShape s = rec.kind().shape();
            // T76: a pose that would exceed the height range is lowered (or raised) to fit. If the structure cannot fit at all,
            // the file is kept untouched with a warning: nothing is emptied or placed, nothing is lost.
            int dy = heightShift(s, rec.origin(), rec.rotation());
            if (dy == Integer.MIN_VALUE) {
                LOGGER.at(Level.WARNING).log("Reprise du vol de %s impossible : la montgolfière dépasse la hauteur du monde, fichier gardé", rec.pilot());
                return;
            }
            if (dy != 0) {
                LOGGER.at(Level.WARNING).log("Reprise du vol de %s : pose hors de la limite de hauteur, décalée de %d bloc(s)", rec.pilot(), dy);
                rec = rec.shiftedBy(dy);
            }
            // T34: auxiliary lights left in a world that survived the plugin (never saved otherwise).
            BalloonLights.removeOrphans(world, store, rec.balloon(), s);
            VehicleParts.removeOrphans(world, store, rec.balloon());
            // If the world was saved before take-off, the old balloon and its contents are
            // still there: the file is authoritative, the containers are emptied to avoid duplicating items.
            BalloonCargo.takeFrom(world, s, rec.origin(), rec.rotation());
            Vector3i burnerPos = s.burner().rotated(rec.rotation()).add(rec.origin());
            ProcessingBenchBlock bench = BurnerFuel.live(world, burnerPos);
            if (bench != null) {
                BurnerFuel.takeFrom(bench);
            }
            pastePrefab(rec.kind(), world, store, rec.origin(), rec.rotation());
            restoreBurner(rec.kind(), world, store, rec.burner(), rec.origin(), rec.rotation());
            restoreCargo(world, store, rec.cargo(), rec.origin(), rec.rotation());
            // T74: animals of the cage found again by UUID, or recreated from their role.
            CageAnimals.place(world, rec.origin(), rec.rotation(), rec.kind().id, rec.animals());
            restoreStrandedPassengers(world, store, rec);
            BalloonResume.delete(rec.pilot());
            LOGGER.at(Level.INFO).log("Montgolfière de %s reposée en %s (reprise après arrêt en vol)", rec.pilot(), rec.origin());
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Reprise du vol de %s en échec : fichier gardé pour un prochain essai", rec.pilot());
        } finally {
            recovering.remove(rec.pilot());
        }
    }

    // ------------------------------------------------------------------ passengers (T21)

    /*
     * Seated passengers. Mounting choice, established in the bytecode of HytaleServer.jar (builtin.mounts):
     *
     * - MountController only has two values. BlockMount ties the player to a BLOCK (BlockMountComponent): but the
     *   balloon's blocks are removed in flight, and the seat position is sent only once to the
     *   client. With BlockMount, MountSystems$HandleMountInput also removes the mount as soon as the client
     *   sends a movement after 600 ms. Unusable in flight. Choice: Minecart, which ties the player to an ENTITY.
     *
     * - The passenger gets MountedComponent(balloon entity, seat offset, Minecart). The server
     *   then sends MountedUpdate to the clients (MountSystems$TrackerUpdate) and PlayerMount sets
     *   the passenger's PlayerInput.mountId. Chain: passenger mounted on the entity, entity mounted on the pilot
     *   (validated in game). That the client chains the two mounts this way is an assumption. At worst, the entity's
     *   server position (same position as the pilot, tick by tick) serves as a fallback on the client side.
     *
     * - The passenger cannot pilot: for the Minecart controller, HandleMountInput applies their
     *   movements to the mounted entity (position, orientation), but the balloon follows the pilot: the plugin
     *   resets its position every tick and its orientation in updatePassengers.
     *
     * - The passenger cannot get off in flight: the DismountNPC packet (MountGamePacketHandler) only removes
     *   block mounts, it does nothing for Minecart. MountInteraction (using the mount) is the
     *   only other path, and the entity cannot be used (Intangible, no Interactable). Only the game's
     *   /dismount command removes the mount: updatePassengers then mounts the passenger again.
     *
     * - Seated animation: no server data chooses it (MovementStates.sitting and mounting come
     *   from the client). The client shows the pose it gives a Minecart passenger, which is seated in the game
     *   (the game's Rail_Kart places the player at Y = 1 above the cart). Not verified.
     *
     * Fallback (TELEPORT): without a mount, the server teleports the passenger to their seat on every drift (every
     * 150 ms at most), in forced flight at zero speed so that they do not fall. Chosen with /orbishorizon balloon passengers
     * teleport, or automatically for a passenger who loses their mount more than 3 times in 10 s.
     */

    private volatile BalloonFlight.PassengerMode passengerMode = BalloonFlight.PassengerMode.FOLLOW;
    private volatile float seatHeight = SEAT_HEIGHT_DEFAULT;

    BalloonFlight.PassengerMode passengerMode() {
        return passengerMode;
    }

    void setPassengerMode(BalloonFlight.PassengerMode mode) {
        passengerMode = mode;
    }

    /** T25: seated pose method for passengers (next take-off), see BalloonFlight.Pose. */
    private volatile BalloonFlight.Pose pose = BalloonFlight.Pose.DEFAULT;

    BalloonFlight.Pose pose() {
        return pose;
    }

    void setPose(BalloonFlight.Pose value) {
        pose = value;
    }

    float seatHeight() {
        return seatHeight;
    }

    void setSeatHeight(float value) {
        seatHeight = value;
    }

    /**
     * Assigns a seat to each player in the list: the closest player/seat pairs first. If there are
     * more players than seats, the extra players are ignored (shareSeats false) or share the closest
     * seat (shareSeats true, automatic descent).
     */
    private List<BalloonFlight.Passenger> assignSeats(Store<EntityStore> store, Deployables.Kind kind, StructureShape s, Vector3i origin,
                                                       Rotation rotation, List<Occupant> people, boolean shareSeats) {
        List<BalloonFlight.Passenger> result = new ArrayList<>();
        List<StructureShape.Cell> seats = s.seats();
        if (people == null || people.isEmpty()) {
            return result;
        }
        if (seats.isEmpty()) {
            // T54: a balloon without seats (the small one). Take-off refuses passengers, but the automatic descent cannot
            // refuse: the extra players sit at the pivot, like the seated pilot of T26 (seat -1, shared).
            if (!shareSeats) {
                return result;
            }
            Vector3f pv = pivot(kind);
            Vector3i block = new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z));
            for (Occupant o : people) {
                result.add(new BalloonFlight.Passenger(o.ref(), o.player().getUuid(), o.player().getUsername(), -1, block,
                        new Vector3f(pv.x, pv.y + seatHeight, pv.z), true, passengerMode, pose));
            }
            return result;
        }
        Vector3d logical = new Vector3d(origin.x + 0.5, origin.y, origin.z + 0.5);
        float height = seatHeight;
        Vector3d[] seatPos = new Vector3d[seats.size()];
        for (int i = 0; i < seatPos.length; i++) {
            StructureShape.Cell c = seats.get(i);
            seatPos[i] = prefabPoint(logical, rotation, new Vector3f(c.x(), c.y() + height, c.z()));
        }
        record Pair(double distance, int person, int seat) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int j = 0; j < people.size(); j++) {
            TransformComponent t = store.getComponent(people.get(j).ref(), TransformComponent.getComponentType());
            Vector3d pos = t != null ? t.getPosition() : logical;
            for (int i = 0; i < seatPos.length; i++) {
                pairs.add(new Pair(pos.distance(seatPos[i]), j, i));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::distance));
        int[] seatOf = new int[people.size()];
        java.util.Arrays.fill(seatOf, -1);
        boolean[] used = new boolean[seatPos.length];
        for (Pair p : pairs) {
            if (seatOf[p.person()] < 0 && !used[p.seat()]) {
                seatOf[p.person()] = p.seat();
                used[p.seat()] = true;
            }
        }
        boolean[] shared = new boolean[people.size()];
        if (shareSeats) {
            for (Pair p : pairs) {
                if (seatOf[p.person()] < 0) {
                    seatOf[p.person()] = p.seat();
                    shared[p.person()] = true;
                }
            }
        }
        for (int j = 0; j < people.size(); j++) {
            if (seatOf[j] < 0) {
                continue;
            }
            StructureShape.Cell c = seats.get(seatOf[j]);
            Occupant o = people.get(j);
            result.add(new BalloonFlight.Passenger(o.ref(), o.player().getUuid(), o.player().getUsername(), seatOf[j],
                    new Vector3i(c.x(), c.y(), c.z()), new Vector3f(c.x(), c.y() + height, c.z()), shared[j], passengerMode, pose));
        }
        return result;
    }

    /** Seats the passengers: mount on the entity (MOUNT) or hovering flight with teleport (TELEPORT). */
    private void boardPassengers(Store<EntityStore> store, BalloonFlight flight, List<BalloonFlight.Passenger> seated) {
        long now = System.currentTimeMillis();
        for (BalloonFlight.Passenger p : seated) {
            if (!p.ref.isValid()) {
                continue;
            }
            p.boardedMs = now;
            flight.passengers.add(p);
            try {
                if (p.mode == BalloonFlight.PassengerMode.MOUNT) {
                    mountPassenger(store, flight, p);
                } else {
                    setPassengerHover(store, p, true);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Montage du passager %s impossible, repli par téléportation", p.name);
                p.mode = BalloonFlight.PassengerMode.TELEPORT;
                setPassengerHover(store, p, true);
            }
            applyPose(store, p, now);
            PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
            if (pr != null && p.seat >= 0) {
                pr.sendMessage(Texts.t("seated"));
            }
            LOGGER.at(Level.INFO).log("Passager %s : siège %d, %s, pose %s", p.name, p.seat + 1, p.mode, p.pose.describe());
        }
    }

    /**
     * T25: applies the chosen seated pose (BalloonFlight.Pose) to a passenger. Called on set-up then on
     * every tick of updatePassengers, on the world thread. states: the server sets sitting back to true when the passenger's
     * client (SetRiderMovementStates, applied by MountSystems$HandleMountInput) has set it back to false, and
     * MovementStatesSystems$TickingSystem broadcasts the change to the other clients. anim: repeated PlayAnimation
     * (AnimationUtils.playAnimation, also to the passenger themselves). An error is logged once per passenger.
     */
    void applyPose(Store<EntityStore> store, BalloonFlight.Passenger p, long now) {
        BalloonFlight.Pose pose = p.pose;
        try {
            if (pose.states()) {
                MovementStatesComponent msc = store.getComponent(p.ref, MovementStatesComponent.getComponentType());
                MovementStates st = msc != null ? msc.getMovementStates() : null;
                if (st != null) {
                    if (!st.sitting) {
                        st.sitting = true;
                    }
                    if (pose.noMount() && st.mounting) {
                        st.mounting = false;
                    }
                    p.poseStatesOn = true;
                }
            }
            if (pose.anim() && now - p.lastPoseAnimMs >= POSE_ANIM_INTERVAL_MS) {
                p.lastPoseAnimMs = now;
                AnimationUtils.playAnimation(p.ref, pose.slot(), pose.animId(), true, store);
                p.poseAnimOn = true;
            }
        } catch (RuntimeException e) {
            if (!p.poseFailed) {
                p.poseFailed = true;
                LOGGER.at(Level.WARNING).withCause(e).log("Pose assise du passager %s impossible (%s)", p.name, pose.describe());
            }
        }
    }

    /** T25: sets sitting back to false on a player whose flight was resumed (T19 resume). Never throws. */
    static void clearSittingFlag(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
            if (msc != null && msc.getMovementStates() != null) {
                msc.getMovementStates().sitting = false;
            }
        } catch (RuntimeException e) {
            // No effect: the client sends back its own states.
        }
    }

    /** T25: undoes a passenger's seated pose (landing, departure). Never throws. */
    static void clearPose(Store<EntityStore> store, BalloonFlight.Passenger p) {
        try {
            if (p.poseStatesOn) {
                MovementStatesComponent msc = store.getComponent(p.ref, MovementStatesComponent.getComponentType());
                MovementStates st = msc != null ? msc.getMovementStates() : null;
                if (st != null) {
                    st.sitting = false;
                }
                p.poseStatesOn = false;
            }
            if (p.poseAnimOn) {
                AnimationUtils.stopAnimation(p.ref, p.pose.slot(), true, store);
                p.poseAnimOn = false;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pose assise du passager %s non défaite", p.name);
        }
    }

    /**
     * Mounts the passenger on the balloon entity with their seat's offset. The offset is expressed
     * in the entity's frame: the client rotates it with the mount's yaw (seen with the pilot, and
     * the entity has a fixed yaw, that of the prefab plus pi). The model has a half turn relative to the prefab
     * (MODEL_YAW_CORRECTION), so the prefab offset (seat minus pivot) is taken with x and z inverted. The
     * 4 seats are at the corners, at (+-1, +-1) from the pivot: a wrong offset by a half turn or a quarter turn
     * gives another seat, never a position outside the gondola.
     */
    private void mountPassenger(Store<EntityStore> store, BalloonFlight flight, BalloonFlight.Passenger p) {
        Vector3f attach = attachOffset(flight.kind, p.seatLocal);
        store.putComponent(p.ref, MountedComponent.getComponentType(),
                new MountedComponent(mountEntity(flight), attach,
                        p.pose.controller() ? MountController.BlockMount : MountController.Minecart));
        p.boardedMs = System.currentTimeMillis();
    }

    /**
     * Entity a MOUNT-mode passenger is mounted on. T82: with dual rendering the entity mounted on the pilot is hidden from everybody but
     * the pilot, so passengers use the observer entity (the one they see).
     */
    private static Ref<EntityStore> mountEntity(BalloonFlight flight) {
        Ref<EntityStore> o = flight.observerRef;
        return flight.dual && o != null && o.isValid() ? o : flight.balloonRef;
    }

    /** TELEPORT mode: forced flight at zero speed (the passenger does not fall between two teleports), or back to normal. */
    private void setPassengerHover(Store<EntityStore> store, BalloonFlight.Passenger p, boolean on) {
        if (!p.ref.isValid()) {
            return;
        }
        MovementManager mm = store.getComponent(p.ref, MovementManager.getComponentType());
        PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
        if (mm == null || pr == null) {
            return;
        }
        if (on) {
            MovementSettings settings = mm.getSettings();
            settings.fly = FlyMode.Forced;
            settings.horizontalFlySpeed = 0f;
            settings.verticalFlySpeed = 0f;
            mm.update(pr.getPacketHandler());
            p.hover = true;
        } else if (p.hover) {
            if (p.velActive) {
                sendPassengerVelocity(store, p, 0, 0, 0);
            }
            mm.resetDefaultsAndUpdate(p.ref, store);
            p.hover = false;
        }
    }

    /**
     * T82, FOLLOW mode: the passenger is in forced flight at zero flight speed (setPassengerHover) and receives every tick a ChangeVelocity
     * (Set) equal to the seat's velocity plus a correction toward the seat, like the airship passengers (T64) and the pilot's carry. Beyond
     * FOLLOW_SNAP blocks from the seat a teleport puts them back. Nothing is mounted: a player mounted on an entity by the Minecart
     * controller drives that entity on their own client (seen on the airship, 5 October 2026).
     */
    private void followPassenger(Store<EntityStore> store, World world, BalloonFlight flight, BalloonFlight.Passenger p, long now) {
        Vector3d seat = prefabPoint(flight.entityPos, flight.rotation, p.seatLocal);
        double dt = p.lastSeatMs == 0 ? 0 : (now - p.lastSeatMs) / 1000.0;
        Vector3d seatVel = new Vector3d();
        if (p.lastSeat != null && dt > 1e-3) {
            seatVel.set(seat).sub(p.lastSeat).div(dt);
        }
        p.lastSeat = seat;
        p.lastSeatMs = now;
        if (now < p.pauseUntilMs) {
            return;
        }
        TransformComponent pt = store.getComponent(p.ref, TransformComponent.getComponentType());
        if (pt == null) {
            return;
        }
        Vector3d err = new Vector3d(seat).sub(pt.getPosition());
        double dist = err.length();
        if (dist > FOLLOW_SNAP) {
            if (now - p.lastActionMs >= FOLLOW_PAUSE_MS) {
                p.lastActionMs = now;
                p.pauseUntilMs = now + FOLLOW_PAUSE_MS;
                sendPassengerVelocity(store, p, 0, 0, 0);
                Rotation3f look = new Rotation3f(pt.getRotation());
                Vector3d target = new Vector3d(seat);
                world.execute(() -> {
                    if (flights.get(flight.pilotUuid) == flight && flight.passengers.contains(p) && p.ref.isValid()) {
                        store.putComponent(p.ref, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
                    }
                });
            }
            return;
        }
        Vector3d corr = new Vector3d(err).mul(FOLLOW_GAIN);
        double cl = corr.length();
        if (cl > FOLLOW_MAX_CORRECTION) {
            corr.mul(FOLLOW_MAX_CORRECTION / cl);
        }
        Vector3d v = seatVel.add(corr);
        if (v.length() > FOLLOW_VELOCITY_EPS) {
            sendPassengerVelocity(store, p, v.x, v.y, v.z);
        } else if (p.velActive) {
            sendPassengerVelocity(store, p, 0, 0, 0);
        }
    }

    /** ChangeVelocity of type Set to a passenger (same path as the airship riders and the pilot's carry). Never throws. */
    private static void sendPassengerVelocity(Store<EntityStore> store, BalloonFlight.Passenger p, double vx, double vy, double vz) {
        try {
            Velocity v = store.getComponent(p.ref, Velocity.getComponentType());
            if (v != null) {
                v.addInstruction(new Vector3d(vx, vy, vz), null, ChangeVelocityType.Set);
            } else {
                PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
                if (pr != null) {
                    pr.getPacketHandler().writeNoCache(new ChangeVelocity((float) vx, (float) vy, (float) vz, ChangeVelocityType.Set, null));
                }
            }
            p.velActive = vx != 0 || vy != 0 || vz != 0;
        } catch (RuntimeException e) {
            // A lost velocity is corrected by the next tick or by the teleport.
        }
    }

    /**
     * On every tick of a flight with passengers: resets the entity's orientation (the passenger's client sends
     * its own for the "mount", HandleMountInput applies it to the entity), mounts again a passenger who lost their
     * mount (/dismount, another seat), switches to the TELEPORT fallback after too many losses, and teleports the passengers
     * in TELEPORT mode to their seat. Operations that change components go through world.execute.
     */
    private void updatePassengers(Store<EntityStore> store, World world, BalloonFlight flight, long now) {
        if (flight.passengers.isEmpty() || flight.balloonRef == null || !flight.balloonRef.isValid()) {
            return;
        }
        float want = (float) (flight.rotation.getRadians() + MODEL_YAW_CORRECTION);
        for (Ref<EntityStore> entity : new Ref[]{flight.balloonRef, flight.observerRef}) { // T82: the observer entity too
            TransformComponent bt = entity != null && entity.isValid()
                    ? store.getComponent(entity, TransformComponent.getComponentType()) : null;
            if (bt != null) {
                Rotation3f cur = bt.getRotation();
                if (Math.abs(Math.IEEEremainder(cur.y - want, 2 * Math.PI)) > 1e-3 || Math.abs(cur.x) > 1e-3 || Math.abs(cur.z) > 1e-3) {
                    Rotation3f fixed = new Rotation3f();
                    fixed.setYaw(want);
                    bt.setRotation(fixed);
                }
            }
        }
        for (BalloonFlight.Passenger p : flight.passengers) {
            if (!p.ref.isValid()) {
                flight.passengers.remove(p); // disconnected or dead
                continue;
            }
            applyPose(store, p, now);
            if (now - p.boardedMs < PASSENGER_GRACE_MS) {
                continue;
            }
            if (p.mode == BalloonFlight.PassengerMode.MOUNT) {
                MountedComponent mc = store.getComponent(p.ref, MountedComponent.getComponentType());
                if (mc != null && mountEntity(flight).equals(mc.getMountedToEntity())) {
                    continue;
                }
                if (now - p.lastActionMs < PASSENGER_ACTION_INTERVAL_MS) {
                    continue;
                }
                p.lastActionMs = now;
                if (now - p.lossWindowMs > PASSENGER_LOSS_WINDOW_MS) {
                    p.lossWindowMs = now;
                    p.losses = 0;
                }
                p.losses++;
                boolean fallBack = p.losses > PASSENGER_MAX_LOSSES;
                if (fallBack) {
                    p.mode = BalloonFlight.PassengerMode.TELEPORT;
                    LOGGER.at(Level.WARNING).log("Passager %s : montage perdu %d fois, repli par téléportation", p.name, p.losses);
                }
                world.execute(() -> {
                    if (flights.get(flight.pilotUuid) != flight || !flight.passengers.contains(p) || !p.ref.isValid()) {
                        return;
                    }
                    if (p.mode == BalloonFlight.PassengerMode.TELEPORT) {
                        setPassengerHover(store, p, true);
                    } else {
                        mountPassenger(store, flight, p);
                    }
                });
            } else if (p.mode == BalloonFlight.PassengerMode.FOLLOW) {
                followPassenger(store, world, flight, p, now);
            } else {
                TransformComponent pt = store.getComponent(p.ref, TransformComponent.getComponentType());
                if (pt == null || now - p.lastActionMs < PASSENGER_TELEPORT_INTERVAL_MS) {
                    continue;
                }
                Vector3d target = prefabPoint(flight.entityPos, flight.rotation, p.seatLocal);
                if (pt.getPosition().distance(target) <= PASSENGER_TELEPORT_DISTANCE) {
                    continue;
                }
                p.lastActionMs = now;
                Rotation3f look = new Rotation3f(pt.getRotation());
                world.execute(() -> {
                    if (flights.get(flight.pilotUuid) == flight && flight.passengers.contains(p) && p.ref.isValid()) {
                        store.putComponent(p.ref, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
                    }
                });
            }
        }
    }

    /**
     * Point where a passenger is placed on the balloon put back at this origin: the free cell next to their seat,
     * towards the centre of the gondola (the seats are at the corners, the chests in the middle of the sides, the
     * pivot column is free), a little above the floor.
     */
    private static Vector3d landingSpot(Deployables.Kind kind, Vector3i origin, Rotation rotation, Vector3i seatBlock) {
        float x = seatBlock.x - Integer.signum(seatBlock.x - Math.round(pivot(kind).x)) * 0.75f;
        Vector3d logical = new Vector3d(origin.x + 0.5, origin.y, origin.z + 0.5);
        return prefabPoint(logical, rotation, new Vector3f(x, seatBlock.y + LANDING_LIFT, seatBlock.z));
    }

    /** Landing: dismounts the passengers and places them next to their seat (called by finishFlight, never throws). */
    private void disembarkPassengers(Store<EntityStore> store, BalloonFlight flight, Vector3i origin, Rotation rotation) {
        for (BalloonFlight.Passenger p : flight.passengers) {
            try {
                if (!p.ref.isValid()) {
                    continue;
                }
                MountUtil.unseat(store, p.ref);
                clearPose(store, p);
                setPassengerHover(store, p, false);
                TransformComponent pt = store.getComponent(p.ref, TransformComponent.getComponentType());
                Rotation3f look = pt != null ? new Rotation3f(pt.getRotation()) : new Rotation3f();
                store.putComponent(p.ref, Teleport.getComponentType(),
                        Teleport.createForPlayer(landingSpot(flight.kind, origin, rotation, p.seatBlock), look));
                PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
                if (pr != null) {
                    pr.sendMessage(Texts.t("landedStandUp"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager %s non démonté proprement", p.name);
            }
        }
        flight.passengers.clear();
    }

    /** Diagnostic text about the passengers of a flight. */
    private String passengersDebug(Store<EntityStore> store, BalloonFlight f) {
        if (f.passengers.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (BalloonFlight.Passenger p : f.passengers) {
            MountedComponent mc = p.ref.isValid() ? store.getComponent(p.ref, MountedComponent.getComponentType()) : null;
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(p.name).append(p.seat < 0 ? " seated at the pivot" : " seat " + (p.seat + 1)).append(" ").append(p.mode)
                    .append(p.mode == BalloonFlight.PassengerMode.FOLLOW ? (p.velActive ? " (velocity sent)" : " (idle)") : "")
                    .append(p.mode == BalloonFlight.PassengerMode.MOUNT ? (mc != null && f.balloonRef != null
                            && mountEntity(f).equals(mc.getMountedToEntity()) ? " mounted" : " NOT mounted") : "")
                    .append(" losses ").append(p.losses).append(" pose ").append(p.pose.describe());
            MovementStatesComponent msc = p.ref.isValid()
                    ? store.getComponent(p.ref, MovementStatesComponent.getComponentType()) : null;
            if (msc != null && msc.getMovementStates() != null) {
                sb.append(" (sitting=").append(msc.getMovementStates().sitting)
                        .append(" mounting=").append(msc.getMovementStates().mounting).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * A player disconnects (PlayerDisconnectEvent, before their removal from the world): if it is the pilot, the
     * balloon is placed and its passengers put down (finishFlight). If it is a passenger, they are dismounted and their
     * saved position is set on the ground under the balloon: otherwise they would be saved in the air and would fall
     * on reconnection.
     */
    void onPlayerGone(UUID uuid) {
        onPilotGone(uuid);
        for (BalloonFlight f : new ArrayList<>(flights.values())) {
            for (BalloonFlight.Passenger p : f.passengers) {
                if (!p.uuid.equals(uuid)) {
                    continue;
                }
                boolean ok = runOnWorld(f.world, () -> dropPassenger(f, p), LAND_WAIT_MS);
                LOGGER.at(ok ? Level.INFO : Level.WARNING).log(ok
                        ? "Passager %s parti : démonté, position enregistrée au sol"
                        : "Passager %s parti : démontage impossible", p.name);
            }
        }
    }

    private void dropPassenger(BalloonFlight f, BalloonFlight.Passenger p) {
        f.passengers.remove(p);
        if (!p.ref.isValid()) {
            return;
        }
        Store<EntityStore> store = f.world.getEntityStore().getStore();
        MountUtil.unseat(store, p.ref);
        clearPose(store, p);
        try {
            setPassengerHover(store, p, false);
        } catch (RuntimeException e) {
            // The player is leaving: flight settings will be restored on their next connection.
        }
        TransformComponent t = store.getComponent(p.ref, TransformComponent.getComponentType());
        if (t != null) {
            t.setPosition(groundBelow(f.world, prefabPoint(f.entityPos, f.rotation, p.seatLocal)));
        }
    }

    /**
     * First solid block or first liquid surface under this point (1 block above), or the point itself
     * if there is none. A player placed above water falls into it and swims, instead of being placed on the bottom.
     */
    static Vector3d groundBelow(World world, Vector3d from) {
        int x = (int) Math.floor(from.x);
        int z = (int) Math.floor(from.z);
        int top = Math.min((int) Math.floor(from.y), ChunkUtil.HEIGHT_MINUS_1);
        // T76: stays inside the height range (below it, blocksBalloon is true everywhere): without ground, the point itself.
        for (int y = top; y > top - 512 && y >= ChunkUtil.MIN_Y; y--) {
            if (blocksBalloon(world, x, y, z)) {
                return new Vector3d(from.x, y + 1.05, from.z);
            }
        }
        return new Vector3d(from);
    }

    /**
     * Resume after a stop in flight (T19): the passengers in the file are placed next to their seat. Connected, they
     * are teleported. Offline, their position is noted and applied on their next arrival (onPlayerAdded).
     */
    private void restoreStrandedPassengers(World world, Store<EntityStore> store, BalloonResume.Record rec) {
        for (BalloonResume.Seated sp : rec.passengers()) {
            try {
                Vector3d spot = landingSpot(rec.kind(), rec.origin(), rec.rotation(), new Vector3i(sp.x(), sp.y(), sp.z()));
                Ref<EntityStore> online = null;
                for (PlayerRef pr : world.getPlayerRefs()) {
                    if (pr.getUuid().equals(sp.uuid()) && pr.getReference() != null && pr.getReference().isValid()) {
                        online = pr.getReference();
                    }
                }
                if (online != null) {
                    MountUtil.unseat(store, online);
                    clearSittingFlag(store, online);
                    TransformComponent pt = store.getComponent(online, TransformComponent.getComponentType());
                    Rotation3f look = pt != null ? new Rotation3f(pt.getRotation()) : new Rotation3f();
                    store.putComponent(online, Teleport.getComponentType(), Teleport.createForPlayer(spot, look));
                } else {
                    BalloonResume.writeStranded(sp.uuid(), world.getName(), spot.x, spot.y, spot.z);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager %s de la reprise non reposé", sp.uuid());
            }
        }
    }

    /** A player arrives in a world: if they were left in the air on a resumed flight (T19), they are placed on the balloon. */
    void onPlayerAdded(Holder<EntityStore> holder, World world) {
        try {
            PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
            if (pr == null) {
                return;
            }
            BalloonResume.Stranded st = BalloonResume.takeStranded(pr.getUuid(), world.getName());
            if (st == null) {
                return;
            }
            TransformComponent t = holder.getComponent(TransformComponent.getComponentType());
            if (t != null) {
                t.setPosition(new Vector3d(st.x(), st.y(), st.z()));
                LOGGER.at(Level.INFO).log("Passager %s reposé sur la montgolfière après la reprise du vol", pr.getUsername());
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Reposer le passager arrivé a échoué");
        }
    }

    // ------------------------------------------------------------------ hovering in the air (T20)

    /** Placed balloon recognised around a burner, with its monitoring state. */
    private static final class Hover {
        /** Origin and rotation of the prefab, null if the burner does not belong to a recognised balloon. */
        Vector3i origin;
        Rotation rotation;
        /** Balloon type recognised (T54). */
        Deployables.Kind kind;
        long validatedMs;
        long nextRetryMs;
        long lastSaveMs;
        boolean warned30;
        boolean warned10;
    }

    /** A player in the gondola, with their distance to the pivot. */
    record Occupant(PlayerRef player, Ref<EntityStore> ref, double distance) {
    }

    private final Map<String, Hover> hovers = new ConcurrentHashMap<>();

    /**
     * Monitoring of a placed balloon, called by BurnerFlameSystem at most once per second per
     * burner, on the world thread. dtSeconds: time elapsed since the previous call (0 on the first, capped).
     *
     * Design choice: no list of balloons to save and reload. The burner is a
     * block saved with its chunk and BurnerFlameSystem sees it as soon as its chunk is loaded: after a
     * restart the monitoring resumes by itself, and a balloon whose chunk is unloaded burns
     * nothing (it is frozen, like everything else). The price: on every pass, the balloon is recognised (every
     * HOVER_REVALIDATE_MS) and its "in the air" test goes through its blocks (about 4,000 block reads).
     *
     * In the air (the GROUND_MARGIN_CELLS cells below it are free): the fuel of the bench in place burns
     * (BurnerFuel.burnLive), the flame stays lit through the BurnerFlameSystem rule (On state as long as
     * fuel remains). On the ground, nothing burns. When there is no fuel left, the balloon goes back to
     * flight (startDryDescent) and descends to the ground.
     */
    void hoverTick(World world, Vector3i burnerPos, double dtSeconds) {
        if (!pendingRestores.isEmpty() || !pendingCargos.isEmpty()) {
            // A landing has just taken place and its contents are not put back yet: wait.
            return;
        }
        ProcessingBenchBlock bench = BurnerFuel.live(world, burnerPos);
        if (bench == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = world.getName() + ":" + burnerPos.x + "," + burnerPos.y + "," + burnerPos.z;
        Hover h = hovers.get(key);
        if (h == null || now - h.validatedMs > HOVER_REVALIDATE_MS) {
            if (h == null) {
                h = new Hover();
                hovers.put(key, h);
            }
            h.validatedMs = now;
            h.origin = null;
            h.kind = null;
            Found f = locate(world, burnerPos);
            if (f != null) {
                h.origin = f.origin();
                h.rotation = f.rotation();
                h.kind = f.kind();
            }
        }
        if (h.origin == null) {
            return; // burner alone, balloon too damaged or chunks not loaded: nothing to monitor
        }
        StructureShape s = h.kind.shapeOrNull();
        if (s == null) {
            return;
        }
        if (!isInAir(world, s, h.origin, h.rotation)) {
            h.warned30 = false;
            h.warned10 = false;
            return; // on the ground: nothing burns
        }
        if (now < h.nextRetryMs) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (dtSeconds > 0 && BurnerFuel.hasFuel(bench)) {
            List<ItemStack> overflow = BurnerFuel.burnLive(bench, dtSeconds);
            dropItems(store, overflow, burnerPos);
            if (now - h.lastSaveMs >= HOVER_SAVE_MS) {
                h.lastSaveMs = now;
                markChunkDirty(world, burnerPos);
            }
        }
        if (!BurnerFuel.hasFuel(bench)) {
            h.nextRetryMs = now + HOVER_RETRY_MS;
            startDryDescent(world, store, s, h, bench);
            return;
        }
        double left = BurnerFuel.remainingSeconds(bench);
        if (left > 30) {
            h.warned30 = false;
        }
        if (left > 10) {
            h.warned10 = false;
        }
        String msgKey = null;
        if (left <= 10 && !h.warned10) {
            h.warned10 = true;
            h.warned30 = true;
            msgKey = "hover.fuelCritical";
        } else if (left <= 30 && !h.warned30) {
            h.warned30 = true;
            msgKey = "hover.fuelLow";
        }
        if (msgKey != null) {
            for (Occupant o : nacelleOccupants(world, store, h.kind, h.origin, h.rotation)) {
                o.player().sendMessage(Texts.t(msgKey).param("time", durationText(left)));
            }
        }
    }

    static String durationText(double seconds) {
        long left = Math.round(Math.ceil(seconds));
        return left >= 60 ? (left / 60) + " min " + (left % 60) + " s" : left + " s";
    }

    /**
     * Looks for the type, rotation and origin of the prefab whose burner is at this position (at least MATCH_RATIO of the blocks
     * in place, the best match among the balloon types, T54). A type whose chunks are not all loaded is skipped.
     */
    private Found locate(World world, Vector3i burnerPos) {
        Found best = null;
        for (Deployables.Kind kind : Deployables.BALLOONS) {
            StructureShape s = kind.shapeOrNull();
            if (s == null || !allChunksLoaded(world, s, burnerPos)) {
                continue;
            }
            for (Rotation r : Rotation.VALUES) {
                Vector3i origin = new Vector3i(burnerPos).sub(s.burner().rotated(r));
                double ratio = matchRatioRegistered(world, kind, s, origin, r);
                if (ratio >= MATCH_RATIO && (best == null || ratio > best.ratio)) {
                    best = new Found(kind, origin, r, ratio);
                }
            }
        }
        return best;
    }

    /** True if all the chunks covered by the balloon (around the burner, all rotations) are loaded. */
    private static boolean allChunksLoaded(World world, StructureShape s, Vector3i burnerPos) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructureShape.Cell c : s.cells()) {
            int dx = c.x() - s.burner().x();
            int dz = c.z() - s.burner().z();
            int r = Math.max(Math.abs(dx), Math.abs(dz));
            minX = Math.min(minX, burnerPos.x - r);
            maxX = Math.max(maxX, burnerPos.x + r);
            minZ = Math.min(minZ, burnerPos.z - r);
            maxZ = Math.max(maxZ, burnerPos.z + r);
        }
        for (int cx = ChunkUtil.chunkCoordinate(minX); cx <= ChunkUtil.chunkCoordinate(maxX); cx++) {
            for (int cz = ChunkUtil.chunkCoordinate(minZ); cz <= ChunkUtil.chunkCoordinate(maxZ); cz++) {
                if (world.getChunkIfLoaded(ChunkUtil.indexChunk(cx, cz)) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * True if the balloon placed at this origin is in the air: the GROUND_MARGIN_CELLS cells below it are
     * free (no solid block, ignoring its own blocks).
     */
    static boolean isInAir(World world, StructureShape s, Vector3i origin, Rotation rotation) {
        java.util.Set<Vector3i> own = new HashSet<>();
        for (StructureShape.Cell c : s.cells()) {
            own.add(c.rotated(rotation).add(origin));
        }
        // T24: flight cells start flightBottomGap cells higher than the bottom of the prefab (the unfolded
        // ladder hangs below). The margin grows by the same amount: the ground under the placed ladder is reached, a
        // lone ladder touching a slope does not make the balloon look landed.
        int margin = GROUND_MARGIN_CELLS + s.flightBottomGap();
        for (int d = 1; d <= margin; d++) {
            if (collides(world, s, new Vector3i(origin).add(0, -d, 0), rotation, own)) {
                return false;
            }
        }
        return true;
    }

    private static void markChunkDirty(World world, Vector3i p) {
        try {
            WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(p.x), ChunkUtil.chunkCoordinate(p.z)));
            if (chunk != null) {
                chunk.markNeedsSaving();
            }
        } catch (RuntimeException e) {
            // No effect on the flight: the burner contents will be saved at the next normal save.
        }
    }

    /**
     * Players whose feet are in the gondola of the balloon placed at this origin, the closest to the
     * pivot first. To be called on the world thread. Used to choose the pilot of a dry descent and, for
     * T21, to find the passengers.
     */
    List<Occupant> nacelleOccupants(World world, Store<EntityStore> store, Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        Vector3f nMin = kind.balloon.nacelleMin;
        Vector3f nMax = kind.balloon.nacelleMax;
        Vector3f pv = pivot(kind);
        Vector3d logical = new Vector3d(origin.x + 0.5, origin.y, origin.z + 0.5);
        Rotation back = Rotation.None.subtract(rotation);
        List<Occupant> list = new ArrayList<>();
        for (PlayerRef p : world.getPlayerRefs()) {
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null) {
                continue;
            }
            Vector3d pos = t.getPosition();
            Vector3f local = back.rotateYaw(
                    new Vector3f((float) (pos.x - logical.x), (float) (pos.y - logical.y), (float) (pos.z - logical.z)),
                    new Vector3f());
            if (local.x < nMin.x || local.x > nMax.x || local.y < nMin.y || local.y > nMax.y
                    || local.z < nMin.z || local.z > nMax.z) {
                continue;
            }
            list.add(new Occupant(p, ref, local.distance(pv)));
        }
        list.sort(Comparator.comparingDouble(Occupant::distance));
        return list;
    }

    /**
     * No more fuel in the air: the balloon goes back to flight and descends to the ground, where it is
     * placed back as blocks (finishFlight). The player closest to the pivot in the gondola becomes the pilot
     * out of fuel (same descent as the dry burner in flight). With no player, the entity is mounted on nobody
     * and the server moves it (tickDriven). The burner and chest contents are kept as at take-off.
     */
    private void startDryDescent(World world, Store<EntityStore> store, StructureShape s, Hover h, ProcessingBenchBlock bench) {
        // T73: transport balloon with its cage lowered or open: raised in one go and closed before the blocks are taken off.
        if (!TransportCage.raiseForDescent(world, h.kind, h.origin, h.rotation)) {
            return;
        }
        List<Occupant> occupants = nacelleOccupants(world, store, h.kind, h.origin, h.rotation);
        Occupant pilot = occupants.isEmpty() ? null : occupants.get(0);
        // T21: the other players become seated passengers. There is no refusal here (the balloon must
        // descend): if there are more people than seats, the seats are shared (assignSeats, dry true).
        List<Occupant> others = pilot != null ? new ArrayList<>(occupants.subList(1, occupants.size())) : new ArrayList<>();
        Found found = new Found(h.kind, new Vector3i(h.origin), h.rotation, 0);
        Message error = launch(store, world, s, found, bench,
                pilot != null ? pilot.ref() : null, pilot != null ? pilot.player() : null, true, others);
        if (error != null && pilot != null) {
            LOGGER.at(Level.WARNING).log("Descente à sec avec pilote impossible (%s), essai sans pilote", error.getMessageId());
            pilot = null;
            // Without a pilot, all players in the gondola are passengers (the entity is moved by the server).
            error = launch(store, world, s, found, bench, null, null, true, occupants);
        }
        if (error != null) {
            LOGGER.at(Level.WARNING).log("Descente à sec impossible en %s : %s", h.origin, error.getMessageId());
        }
    }

    /**
     * Automatic descent (T20 without pilot, T26 with seated pilot): the server lowers the entity by
     * flight.descentSpeed blocks per second on every tick, the client interpolates. flight.lastCheckedOrigin is the
     * last origin without collision, flight.entityPos.y the continuous height. As soon as the height drops below the current
     * cell, the cell below is tested (flight cells, folded ladder): if free, it becomes the current cell
     * (several cells in a row if the tick is long). If occupied, the entity is stopped exactly on the current cell
     * (no visible jump) and the balloon is placed back as blocks there, only at that moment. No other placement on
     * the way: the T20 "in the air" resume only looks at blocks, there are none during the flight. If the
     * occupants' lost mount forces a fallback, updatePassengers handles it.
     */
    private void tickDriven(Store<EntityStore> store, World world, StructureShape s, BalloonFlight flight, long now) {
        TransformComponent balloonTransform = store.getComponent(flight.balloonRef, TransformComponent.getComponentType());
        if (balloonTransform == null) {
            return;
        }
        if (now - flight.lastResumeMs >= RESUME_WRITE_INTERVAL_MS) {
            flight.lastResumeMs = now;
            BalloonResume.write(flight, flight.currentOrigin(), false);
        }
        double dt = Math.min((now - flight.lastDescentMs) / 1000.0, MAX_DESCENT_DT);
        flight.lastDescentMs = now;
        double y = flight.entityPos.y - flight.descentSpeed * dt;
        int cell = flight.lastCheckedOrigin.y;
        while (y < cell) {
            Vector3i next = new Vector3i(flight.lastCheckedOrigin).add(0, -1, 0);
            if (collision(world, s, next, flight.rotation) != null) {
                flight.stopping = true;
                flight.entityPos.y = cell;
                balloonTransform.setPosition(displayPosition(flight.kind, flight.entityPos, flight.rotation));
                Vector3i origin = new Vector3i(flight.lastCheckedOrigin);
                world.execute(() -> {
                    if (flights.get(flight.pilotUuid) != flight) {
                        return;
                    }
                    PlayerRef player = !flight.pilotless() && flight.pilotRef.isValid()
                            ? store.getComponent(flight.pilotRef, PlayerRef.getComponentType()) : null;
                    finishFlight(store, flight, origin);
                    if (player != null) {
                        player.sendMessage(Texts.t("fuel.dryLanded"));
                    }
                });
                return;
            }
            flight.lastCheckedOrigin.set(next);
            cell = next.y;
        }
        flight.entityPos.y = y;
        balloonTransform.setPosition(displayPosition(flight.kind, flight.entityPos, flight.rotation));
        VehicleParts.sync(store, flight.parts, flight.balloonRef, flight.observerRef);
        BalloonLights.follow(store, flight);
        CageAnimals.follow(store, flight);
    }

    // ------------------------------------------------------------------ fuel (T12) and flame (T13)

    /** Burner contents to put back into a placed block whose component does not exist yet. */
    private static final class PendingRestore {
        final World world;
        final Vector3i burnerPos;
        final BurnerFuel fuel;
        final long deadlineMs;
        volatile boolean scheduled;

        PendingRestore(World world, Vector3i burnerPos, BurnerFuel fuel, long deadlineMs) {
            this.world = world;
            this.burnerPos = burnerPos;
            this.fuel = fuel;
            this.deadlineMs = deadlineMs;
        }
    }

    private final ConcurrentLinkedQueue<PendingRestore> pendingRestores = new ConcurrentLinkedQueue<>();

    /** Chest contents to put back into placed containers whose component does not exist yet (T18). */
    private static final class PendingCargo {
        final World world;
        final Vector3i origin;
        final Rotation rotation;
        final BalloonCargo cargo;
        final long deadlineMs;
        volatile boolean scheduled;

        PendingCargo(World world, Vector3i origin, Rotation rotation, BalloonCargo cargo, long deadlineMs) {
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.cargo = cargo;
            this.deadlineMs = deadlineMs;
        }
    }

    private final ConcurrentLinkedQueue<PendingCargo> pendingCargos = new ConcurrentLinkedQueue<>();

    /**
     * Puts the chest contents back into the placed prefab (same logic as restoreBurner: retry
     * for RESTORE_TIMEOUT_MS if a container does not exist yet, then items dropped on the ground).
     * What does not fit (slot taken, smaller container) is dropped next to the chest, with
     * a warning in the logs.
     */
    void restoreCargo(World world, Store<EntityStore> store, BalloonCargo cargo, Vector3i origin, Rotation rotation) {
        if (cargo == null || cargo.isEmpty()) {
            return;
        }
        Vector3i dropPos = cargo.firstPosition(origin, rotation);
        List<ItemStack> left = cargo.putInto(world, origin, rotation, false);
        if (!left.isEmpty()) {
            LOGGER.at(Level.WARNING).log("%d piles de coffre sans place, lâchées en %s", left.size(), dropPos);
        }
        dropItems(store, left, dropPos);
        if (!cargo.isEmpty()) {
            pendingCargos.add(new PendingCargo(world, new Vector3i(origin), rotation, cargo, System.currentTimeMillis() + RESTORE_TIMEOUT_MS));
        }
    }

    private void retryCargos(Store<EntityStore> store, World world) {
        for (PendingCargo p : pendingCargos) {
            if (p.world != world || p.scheduled) {
                continue;
            }
            p.scheduled = true;
            world.execute(() -> {
                p.scheduled = false;
                boolean last = System.currentTimeMillis() > p.deadlineMs;
                Vector3i dropPos = p.cargo.firstPosition(p.origin, p.rotation);
                List<ItemStack> left = p.cargo.putInto(world, p.origin, p.rotation, last);
                if (last && !left.isEmpty()) {
                    LOGGER.at(Level.WARNING).log("Coffre introuvable ou plein près de %s : contenu lâché au sol", dropPos);
                }
                dropItems(store, left, dropPos);
                if (p.cargo.isEmpty()) {
                    pendingCargos.remove(p);
                }
            });
        }
    }

    /** Niveau de carburant traduit, par exemple "1 min 12 s of flight (9 fuel items)". */
    static Message fuelMessage(BurnerFuel fuel) {
        return Texts.t("fuel.level").param("time", durationText(fuel.remainingSeconds())).param("items", fuel.fuelItems());
    }

    /** Fuel level for /orbishorizon balloon debug (diagnostic, in English). */
    static String fuelDebug(BurnerFuel fuel) {
        return durationText(fuel.remainingSeconds()) + " of flight (" + fuel.fuelItems() + " fuel items)";
    }

    /** Fuel burning in flight, messages to the pilot and switch to dry. */
    private void updateFuel(Store<EntityStore> store, World world, BalloonFlight flight, long now) {
        BurnerFuel fuel = flight.burner;
        if (fuel == null) {
            return;
        }
        double dt = (now - flight.lastBurnMs) / 1000.0;
        flight.lastBurnMs = now;
        if (flight.dry || dt <= 0) {
            return;
        }
        fuel.burn(dt);
        double left = fuel.remainingSeconds();
        PlayerRef player = store.getComponent(flight.pilotRef, PlayerRef.getComponentType());
        if (left <= 0) {
            flight.dry = true;
            if (player != null) {
                player.sendMessage(Texts.t("fuel.dry"));
            }
            world.execute(() -> enterDry(store, flight));
            return;
        }
        String key = null;
        if (left <= 10 && !flight.warned10) {
            flight.warned10 = true;
            flight.warned30 = true;
            key = "fuel.critical";
        } else if (left <= 30 && !flight.warned30) {
            flight.warned30 = true;
            key = "fuel.low";
        } else if (now >= flight.nextFuelMsgMs) {
            key = "fuel.status";
        }
        if (key != null) {
            flight.nextFuelMsgMs = now + FUEL_MSG_INTERVAL_MS;
            if (player != null) {
                player.sendMessage(Texts.t(key).param("fuel", fuelMessage(fuel)));
            }
        }
    }

    /** Measures the pilot's vertical speed and sets the boosted flame (T17). */
    private void updateBoost(Store<EntityStore> store, World world, BalloonFlight flight, double y, long now) {
        if (flight.boostUnavailable) {
            return;
        }
        if (now - flight.takeoffMs < BOOST_START_DELAY_MS || now < flight.teleportUntilMs) {
            flight.sampleMs = 0;
            return;
        }
        if (flight.sampleMs == 0) {
            flight.sampleY = y;
            flight.sampleMs = now;
            return;
        }
        long dt = now - flight.sampleMs;
        if (dt < BOOST_SAMPLE_MS) {
            return;
        }
        double vy = (y - flight.sampleY) / (dt / 1000.0);
        flight.sampleY = y;
        flight.sampleMs = now;
        boolean want;
        if (!flight.boost) {
            want = vy > BOOST_ON_SPEED;
        } else {
            want = !(vy < BOOST_OFF_SPEED && now - flight.boostSinceMs >= BOOST_MIN_MS);
        }
        if (want == flight.boost) {
            return;
        }
        flight.boost = want;
        flight.boostSinceMs = now;
        world.execute(() -> {
            if (flights.get(flight.pilotUuid) == flight && !flight.dry && !flight.stopping && flight.boost == want) {
                setBalloonBoost(store, flight, want);
            }
        });
    }

    /** Replaces the entity's model with the version with or without boosted flame. */
    private void setBalloonBoost(Store<EntityStore> store, BalloonFlight flight, boolean boost) {
        if (flight.balloonRef == null || !flight.balloonRef.isValid() || flight.modelId == null) {
            return;
        }
        String id = boost ? flight.modelId + BOOST_MODEL_SUFFIX : flight.modelId;
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset == null) {
            flight.boost = false;
            flight.boostUnavailable = true;
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable : pas de flamme renforcée", id);
            return;
        }
        VehicleView.putModel(store, flight.balloonRef, flight.observerRef, asset); // T82: both entities
        // T35: the burner light follows the flame (stronger when climbing).
        BalloonLights.set(store, flight, BalloonLights.KEY_BURNER, BalloonLights.burnerColor(false, boost));
        // T50: the flamethrower sound follows the boosted flame (ignition and loop, then end sound).
        setBurnerRoar(store, flight, boost);
        if (!boost) {
            // Assumption: the client may not remove the particles of the previous model.
            try {
                StructureShape.Cell a = flight.kind.shape().anchor();
                Vector3d p = prefabPoint(flight.entityPos, flight.rotation, new Vector3f(a.x(), a.y() + 1, a.z()));
                cancelFlame(flight.world, p, BOOST_FLAME_CANCEL_RADIUS, flight.kind.balloon.boostSystem);
            } catch (IOException e) {
                // Unreadable shape: only the model change applies.
            }
        }
    }

    /**
     * T50: starts or stops the flamethrower sound. Applies the BOOST_EFFECT effect (Infinite) to the flying entity, or
     * removes it (RemovalBehavior.COMPLETE, the end sound then plays). No effect if the state is already the requested one. Never
     * throws: a missing asset or a vanished entity only leaves a warning.
     */
    private void setBurnerRoar(Store<EntityStore> store, BalloonFlight flight, boolean on) {
        if (flight.roar == on) {
            return;
        }
        flight.roar = on;
        Ref<EntityStore> ref = flight.balloonRef;
        if (ref == null || !ref.isValid()) {
            return;
        }
        if (!applyRoarEffect(store, ref, on) && on) {
            flight.roar = false;
        }
        // T82: the sound is played where the entity is drawn, so the observer entity carries the effect too.
        Ref<EntityStore> observer = flight.observerRef;
        if (observer != null && observer.isValid()) {
            applyRoarEffect(store, observer, on);
        }
    }

    /**
     * Adds (Infinite) or removes the burner boost sound effect on an entity that has an EffectControllerComponent (balloon and
     * airship). Returns false only when adding failed. Never throws.
     */
    boolean applyRoarEffect(Store<EntityStore> store, Ref<EntityStore> ref, boolean on) {
        try {
            EffectControllerComponent ec = store.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ec == null) {
                return !on;
            }
            if (on) {
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(BOOST_EFFECT);
                if (effect == null) {
                    LOGGER.at(Level.WARNING).log("Effet %s introuvable : pas de son de lance-flammes", BOOST_EFFECT);
                    return false;
                }
                return ec.addEffect(ref, effect, store);
            } else {
                int index = EntityEffect.getAssetMap().getIndex(BOOST_EFFECT);
                if (index != Integer.MIN_VALUE) {
                    ec.removeEffect(ref, index, RemovalBehavior.COMPLETE, store);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Son de lance-flammes : changement d'état impossible");
            return !on;
        }
        return true;
    }

    /**
     * Dry burner (T26): flame off, then automatic descent. The pilot becomes a passenger mounted on the entity,
     * in place of the pilot (gondola pivot, passenger seat height), with the same seated pose (T25).
     * The entity is no longer mounted on them: the server lowers it on every tick (tickDriven) at the pilot's
     * vertical flight speed, which the client interpolates. The pilot can no longer climb or move (forced flight, flight
     * speeds at 0, in case the mount were lost). Without a pilot (T20), there is nothing else to do but the flame.
     */
    private void enterDry(Store<EntityStore> store, BalloonFlight flight) {
        if (flights.get(flight.pilotUuid) != flight) {
            return;
        }
        setBalloonFlame(store, flight, false);
        if (flight.pilotless() || flight.driven || !flight.pilotRef.isValid()) {
            return;
        }
        MovementManager mm = store.getComponent(flight.pilotRef, MovementManager.getComponentType());
        PlayerRef player = store.getComponent(flight.pilotRef, PlayerRef.getComponentType());
        if (mm == null || player == null) {
            return;
        }
        // Actual descent speed: the pilot's vertical flight speed, read back before setting it to 0.
        float v = mm.getSettings().verticalFlySpeed;
        if (v > 0f) {
            flight.descentSpeed = v;
        }
        mm.getSettings().fly = FlyMode.Forced;
        mm.getSettings().horizontalFlySpeed = 0f;
        mm.getSettings().verticalFlySpeed = 0f;
        mm.update(player.getPacketHandler());
        // The entity leaves the pilot (otherwise the mount would be circular), then the pilot mounts the entity.
        if (flight.attached && flight.balloonRef != null && flight.balloonRef.isValid()) {
            store.tryRemoveComponent(flight.balloonRef, MountedComponent.getComponentType());
        }
        flight.attached = false;
        collapseView(store, flight); // T82: the pilot is now a seated passenger, the balloon has no pilot entity to show
        long now = System.currentTimeMillis();
        flight.lastDescentMs = now;
        Vector3f pv = pivot(flight.kind);
        Vector3i block = new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z));
        BalloonFlight.Passenger p = new BalloonFlight.Passenger(flight.pilotRef, player.getUuid(), player.getUsername(), -1,
                block, new Vector3f(pv.x, pv.y + seatHeight, pv.z), false, passengerMode, pose);
        boardPassengers(store, flight, List.of(p));
        flight.driven = true;
        LOGGER.at(Level.INFO).log("Descente automatique de %s : %.2f blocs par seconde, pilote assis", p.name, flight.descentSpeed);
    }

    /** Vertical flight speed of the pilot (after the division by 2), falling back to the default speed. */
    private static double pilotDescentSpeed(Store<EntityStore> store, Ref<EntityStore> pilotRef) {
        MovementManager mm = store.getComponent(pilotRef, MovementManager.getComponentType());
        if (mm != null && mm.getSettings().verticalFlySpeed > 0f) {
            return mm.getSettings().verticalFlySpeed;
        }
        return FALLBACK_DESCENT_SPEED;
    }

    /** Flight without a pilot: default vertical flight speed of a world player, divided by 2 as at take-off. */
    private static double defaultDescentSpeed(World world, Store<EntityStore> store) {
        try {
            for (PlayerRef pr : world.getPlayerRefs()) {
                Ref<EntityStore> ref = pr.getReference();
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                MovementManager mm = store.getComponent(ref, MovementManager.getComponentType());
                if (mm != null && mm.getDefaultSettings() != null && mm.getDefaultSettings().verticalFlySpeed > 0f) {
                    return mm.getDefaultSettings().verticalFlySpeed * FLY_SPEED_FACTOR;
                }
            }
        } catch (RuntimeException e) {
            // Fallback below.
        }
        return FALLBACK_DESCENT_SPEED;
    }

    /**
     * Flame of the flying entity: replaces the model with the version with or without particles, and
     * asks the clients to stop the flame particles around the burner (in case the
     * model change does not remove them, unverified assumption).
     */
    private void setBalloonFlame(Store<EntityStore> store, BalloonFlight flight, boolean lit) {
        if (flight.balloonRef == null || !flight.balloonRef.isValid() || flight.modelId == null) {
            return;
        }
        flight.boost = false;
        // T50: no more flamethrower sound when the flame goes out (dry burner, pilot's death).
        setBurnerRoar(store, flight, false);
        String id = lit ? flight.modelId : flight.modelId + UNLIT_MODEL_SUFFIX;
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset != null) {
            VehicleView.putModel(store, flight.balloonRef, flight.observerRef, asset); // T82: both entities
        } else {
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable : la flamme reste affichée", id);
        }
        // T35: the burner light goes out with the flame (dry burner), regardless of the model found.
        BalloonLights.set(store, flight, BalloonLights.KEY_BURNER, BalloonLights.burnerColor(!lit, false));
        if (!lit) {
            try {
                StructureShape.Cell a = flight.kind.shape().anchor();
                Vector3d p = prefabPoint(flight.entityPos, flight.rotation, new Vector3f(a.x(), a.y() + 1, a.z()));
                cancelFlame(flight.world, p, 3, flight.kind.balloon.flameSystem);
                cancelFlame(flight.world, p, BOOST_FLAME_CANCEL_RADIUS, flight.kind.balloon.boostSystem);
            } catch (IOException e) {
                // Unreadable shape: only the model change applies.
            }
        }
    }

    /**
     * Turns the mod's burner at this position on or off ("On" state, no effect on another block). Uses
     * the block state, like the game's ProcessingBenchBlock.setBlockInteractionState. To be called on the
     * world thread.
     */
    static void setBurnerLit(World world, Vector3i pos, boolean lit) {
        BlockType bt = world.getBlockType(pos.x, pos.y, pos.z);
        if (bt == null || bt.getId() == null) {
            return;
        }
        String id = bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId();
        if (!id.startsWith(StructureShape.BURNER_BLOCK)) {
            return;
        }
        boolean on = BURNER_ON_STATE.equals(bt.getCurrentInteractionState())
                || id.endsWith("_State_Definitions_" + BURNER_ON_STATE);
        if (on == lit) {
            return;
        }
        world.setBlockInteractionState(new Vector3i(pos), bt, lit ? BURNER_ON_STATE : "default");
        if (!lit) {
            // Same precaution as at take-off: the client could keep the flame displayed.
            cancelBlockFlame(world, pos);
        }
    }

    /**
     * Puts the burner contents back into the block that has just been placed, and lights its flame if
     * fuel remains. If the bench component does not exist yet (deferred block creation,
     * assumption), it retries on the following ticks for RESTORE_TIMEOUT_MS, then drops the
     * items into the world so as not to lose anything.
     */
    private void restoreBurner(Deployables.Kind kind, World world, Store<EntityStore> store, BurnerFuel fuel, Vector3i origin, Rotation rotation) {
        StructureShape s;
        try {
            s = kind.shape();
        } catch (IOException e) {
            return;
        }
        Vector3i burnerPos = s.burner().rotated(rotation).add(origin);
        setBurnerLit(world, burnerPos, fuel != null && !fuel.isEmpty());
        if (fuel == null) {
            return;
        }
        if (!tryRestore(world, store, burnerPos, fuel)) {
            pendingRestores.add(new PendingRestore(world, burnerPos, fuel, System.currentTimeMillis() + RESTORE_TIMEOUT_MS));
        }
    }

    /**
     * T57: puts the contents back into the bench (airship engine) at this position, with the same retry and drop rules as
     * the burner: retried every tick for RESTORE_TIMEOUT_MS if the component does not exist yet, then dropped on the ground.
     */
    void restoreBenchAt(World world, Store<EntityStore> store, Vector3i pos, BurnerFuel fuel) {
        if (fuel == null) {
            return;
        }
        if (!tryRestore(world, store, pos, fuel)) {
            pendingRestores.add(new PendingRestore(world, new Vector3i(pos), fuel, System.currentTimeMillis() + RESTORE_TIMEOUT_MS));
        }
    }

    private static boolean tryRestore(World world, Store<EntityStore> store, Vector3i burnerPos, BurnerFuel fuel) {
        ProcessingBenchBlock bench = BurnerFuel.live(world, burnerPos);
        if (bench == null) {
            return false;
        }
        dropItems(store, fuel.putInto(bench), burnerPos);
        return true;
    }

    private void retryRestores(Store<EntityStore> store, World world) {
        for (PendingRestore p : pendingRestores) {
            if (p.world != world || p.scheduled) {
                continue;
            }
            p.scheduled = true;
            world.execute(() -> {
                p.scheduled = false;
                if (tryRestore(world, store, p.burnerPos, p.fuel)) {
                    pendingRestores.remove(p);
                } else if (System.currentTimeMillis() > p.deadlineMs) {
                    pendingRestores.remove(p);
                    LOGGER.at(Level.WARNING).log("Brûleur introuvable en %s : contenu lâché au sol", p.burnerPos);
                    dropItems(store, p.fuel.allItems(), p.burnerPos);
                }
            });
        }
    }

    /** Drops items into the world above a cell (like the game's benches). */
    static void dropItems(ComponentAccessor<EntityStore> store, List<ItemStack> items, Vector3i pos) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(store, items,
                new Vector3d(pos.x + 0.5, pos.y + 1.5, pos.z + 0.5), new Rotation3f());
        store.addEntities(drops, AddReason.SPAWN);
    }

    /** Stops the flame particles in a cube of radius r around a point. */
    static void cancelFlame(World world, Vector3d c, double r, String... systems) {
        CancelParticleSystems packet = new CancelParticleSystems(
                new Position(c.x - r, c.y - r, c.z - r), new Position(c.x + r, c.y + r, c.z + r),
                systems, true);
        for (PlayerRef player : world.getPlayerRefs()) {
            player.getPacketHandler().writeNoCache(packet);
        }
    }
}
