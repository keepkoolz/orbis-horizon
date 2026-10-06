package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** State of a hot air balloon in flight. */
final class BalloonFlight {
    /**
     * UUID of the pilot. For a pilotless flight (T20, dry descent of a balloon stopped in mid-air
     * with nobody aboard), a random flight identifier: it is also the name of the resume file.
     * T39: when the pilot dies in flight, the flight becomes a pilotless flight and gets a new random
     * identifier (the respawned player can fly another balloon without overwriting this flight).
     */
    volatile UUID pilotUuid;
    /** Pilot, or null for a pilotless flight (T20, or pilot dead in flight, T39). */
    volatile Ref<EntityStore> pilotRef;
    final World world;
    final Rotation rotation;
    /** Balloon type of this flight (T54): prefab, pivot, gondola, model and crate. Always a balloon kind. */
    final Deployables.Kind kind;
    Ref<EntityStore> balloonRef;
    /** Entity position (centre of the prefab origin block, at the prefab ground level). */
    final Vector3d entityPos = new Vector3d();
    /** Pilot position minus entity position, fixed at take-off. */
    final Vector3d pilotOffset = new Vector3d();
    /** Last pilot position for which the balloon touched nothing. */
    final Vector3d lastValidPilotPos = new Vector3d();
    /** Last origin (in blocks) checked, so the collision test is only redone when it changes. */
    final Vector3i lastCheckedOrigin = new Vector3i(Integer.MIN_VALUE);
    /** Take-off time (ms). */
    volatile long takeoffMs;
    /** The end of the flight is being processed. */
    volatile boolean stopping;
    /** After a collision: ticks ignored until this time (teleport in progress). */
    volatile long teleportUntilMs;
    /** Entity mounted on the pilot (display stuck to the pilot on the client side). */
    volatile boolean attached;
    /** Last collision message sent to the pilot. */
    volatile long lastCollisionMsgMs;
    /** Model used at take-off (the unlit model is this name followed by BalloonManager.UNLIT_MODEL_SUFFIX). */
    String modelId;
    /** Burner contents during the flight (fuel, output, burn in progress). */
    BurnerFuel burner;
    /** Contents of the basket chests during the flight (T18). Never null after a successful take-off. */
    BalloonCargo cargo;
    /** UUID of the flying entity (T19 resume file, to find and delete it). */
    UUID balloonUuid;
    /** Chest contents encoded for the resume file (does not change during the flight). */
    BsonDocument cargoBson;
    /** Last write of the resume file (ms). */
    long lastResumeMs;
    /** Last burn update (ms). */
    long lastBurnMs;
    /** Next fuel level message (ms). */
    long nextFuelMsgMs;
    /** Low fuel alerts already sent (30 s and 10 s). */
    boolean warned30;
    boolean warned10;
    /** Burner dry: the balloon can no longer climb and descends to the ground. */
    volatile boolean dry;
    /**
     * T26: automatic descent with a pilot. The pilot became a passenger mounted on the entity (as in T25), the entity
     * is no longer mounted on them and the server lowers it every tick (tickDriven), as for a pilotless flight.
     */
    volatile boolean driven;
    /** Speed of the automatic descent, in blocks per second: the pilot's vertical flight speed (after the division by 2). */
    volatile double descentSpeed;
    /** Time of the last automatic descent update (ms). */
    long lastDescentMs;
    /** Boosted flame (T17): the balloon is climbing, the _Boost model is shown. */
    boolean boost;
    /** Moment of the switch to boosted flame (ms), for the minimum duration. */
    long boostSinceMs;
    /** Last sample of the pilot's altitude (0 ms: to be taken again) to measure vertical speed. */
    double sampleY;
    long sampleMs;
    /** T50: the flamethrower sound effect is applied to the flying entity (boosted flame). */
    boolean roar;
    /** The _Boost model does not exist: no more attempts, a single warning. */
    boolean boostUnavailable;

    /**
     * Method used to seat a passenger in flight (T21). MOUNT: the player is mounted on the balloon entity
     * (MountedComponent, Minecart controller), the client sticks them to the seat. TELEPORT: fallback, the
     * server teleports them to their seat on every drift, in forced flight at zero speed (jerks possible).
     * Adjustable with /orbishorizon balloon passengers.
     */
    enum PassengerMode { MOUNT, TELEPORT }

    /**
     * Seated pose of a passenger in flight (T25). No server data alone chooses the pose on the
     * clients: the server can only (1) broadcast a player's MovementStates, including the sitting flag,
     * (2) play a model animation on an entity (AnimationUtils, "Sit" of the Player model) and (3) choose
     * the controller of the MountedComponent (Minecart or BlockMount). Each method is a flag, so they can be compared
     * in game with /orbishorizon balloon seatpose.
     *
     * states: sitting true in the passenger's MovementStates, reset every tick (other clients see them
     * seated, the server collision box follows SittingOffset). nomount: also sets mounting to false. controller:
     * MountedComponent with the BlockMount controller (the pose of block seats) on the entity, not verified on the
     * client side (the packet is sent without block data). anim: slot/animId animation played to the clients, repeated.
     */
    record Pose(boolean states, boolean noMount, boolean controller, boolean anim, AnimationSlot slot, String animId) {
        static final Pose DEFAULT = new Pose(true, false, false, true, AnimationSlot.Movement, "Sit");

        /** Reads a list separated by + or spaces: none, default, states, nomount, controller, anim, slot:Name, id:Name. */
        static Pose parse(String spec) {
            boolean states = false;
            boolean noMount = false;
            boolean controller = false;
            boolean anim = false;
            AnimationSlot slot = DEFAULT.slot;
            String animId = DEFAULT.animId;
            for (String raw : spec.split("[+,\\s]+")) {
                String t = raw.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String l = t.toLowerCase(java.util.Locale.ROOT);
                if (l.equals("none")) {
                    states = false;
                    noMount = false;
                    controller = false;
                    anim = false;
                } else if (l.equals("default")) {
                    return DEFAULT;
                } else if (l.equals("states")) {
                    states = true;
                } else if (l.equals("nomount")) {
                    states = true;
                    noMount = true;
                } else if (l.equals("controller")) {
                    controller = true;
                } else if (l.equals("anim")) {
                    anim = true;
                } else if (l.startsWith("slot:")) {
                    AnimationSlot found = null;
                    for (AnimationSlot a : AnimationSlot.values()) {
                        if (a.name().equalsIgnoreCase(t.substring(5))) {
                            found = a;
                        }
                    }
                    if (found == null) {
                        throw new IllegalArgumentException("Unknown animation slot: " + t.substring(5)
                                + " (Movement, Status, Action, Face, Emote, ServerAction)");
                    }
                    slot = found;
                    anim = true;
                } else if (l.startsWith("id:")) {
                    if (t.length() <= 3) {
                        throw new IllegalArgumentException("id: needs an animation name (Sit, Sit2, SitGround)");
                    }
                    animId = t.substring(3);
                    anim = true;
                } else {
                    throw new IllegalArgumentException("Unknown option: " + t);
                }
            }
            return new Pose(states, noMount, controller, anim, slot, animId);
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (states) {
                sb.append(noMount ? "nomount" : "states");
            }
            if (controller) {
                sb.append(sb.length() > 0 ? "+" : "").append("controller");
            }
            if (anim) {
                sb.append(sb.length() > 0 ? "+" : "").append("anim(").append(slot).append(":").append(animId).append(")");
            }
            return sb.length() > 0 ? sb.toString() : "none";
        }
    }

    /** A player seated on a balloon seat during the flight (T21). */
    static final class Passenger {
        final Ref<EntityStore> ref;
        final UUID uuid;
        final String name;
        /** Seat number (0 to 3), in the order of BalloonShape.seats(). */
        final int seat;
        /** Stool cell, prefab frame. */
        final Vector3i seatBlock;
        /** Seating point, prefab frame (blocks, before rotation), seat height included. */
        final Vector3f seatLocal;
        /** Seat shared with another player (automatic descent with more people than seats). */
        final boolean shared;
        volatile PassengerMode mode;
        /** Time of the last placement (ms), so a seating that has not arrived yet is not judged. */
        long boardedMs;
        /** Last server action on this passenger (remount, teleport), in ms. */
        long lastActionMs;
        /** Mount losses counted in the current window, and the start of that window (ms). */
        int losses;
        long lossWindowMs;
        /** Flight settings changed (TELEPORT mode): to be restored on landing. */
        boolean hover;
        /** Seated pose method chosen at take-off (T25). */
        final Pose pose;
        /** Last send of the pose animation (ms), and state set by the server (to undo on landing). */
        long lastPoseAnimMs;
        boolean poseAnimOn;
        boolean poseStatesOn;
        /** A pose error has already been logged for this passenger. */
        boolean poseFailed;

        Passenger(Ref<EntityStore> ref, UUID uuid, String name, int seat, Vector3i seatBlock, Vector3f seatLocal,
                  boolean shared, PassengerMode mode, Pose pose) {
            this.ref = ref;
            this.uuid = uuid;
            this.name = name;
            this.seat = seat;
            this.seatBlock = seatBlock;
            this.seatLocal = seatLocal;
            this.shared = shared;
            this.mode = mode;
            this.pose = pose;
        }
    }

    /** Seated passengers (T21). Empty for a flight without passengers. */
    final List<Passenger> passengers = new CopyOnWriteArrayList<>();

    /** Lights attached to the flying entity (T34: yellow crystals, T35: burner flame). See BalloonLights. */
    final List<BalloonLights.Light> lights = new CopyOnWriteArrayList<>();
    /** Interactable helper on the chain cell: using it in flight lands the balloon, like the airship lever. See BalloonLights.spawnChain. */
    volatile AirshipLever.Helper chain;


    BalloonFlight(UUID pilotUuid, Ref<EntityStore> pilotRef, World world, Rotation rotation, Deployables.Kind kind) {
        this.kind = kind;
        this.pilotUuid = pilotUuid;
        this.pilotRef = pilotRef;
        this.world = world;
        this.rotation = rotation;
    }

    /** Pilotless flight: the entity is moved by the server, nobody is mounted on it. */
    boolean pilotless() {
        return pilotRef == null;
    }

    /** The entity is moved by the server: pilotless flight (T20) or automatic descent with a seated pilot (T26). */
    boolean serverDriven() {
        return pilotRef == null || driven;
    }

    /**
     * Origin (in blocks) where to put the balloon back down. Pilotless flight: last origin checked without
     * collision (the entity position is continuous and its rounding could fall into the ground).
     */
    Vector3i currentOrigin() {
        return serverDriven() ? new Vector3i(lastCheckedOrigin) : originCell(entityPos);
    }

    /** Prefab origin (corner of block 0,0,0) closest to the current position. */
    Vector3i originCell(Vector3d entityPosition) {
        return new Vector3i((int) Math.round(entityPosition.x - 0.5), (int) Math.round(entityPosition.y),
                (int) Math.round(entityPosition.z - 0.5));
    }
}
