package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State of an airship in flight. Same model as BalloonFlight: the pilot flies freely (forced fly mode, halved speeds), the ship
 * entity is mounted on the pilot (offset 0) and the server only follows the pilot, adds a heading (theta, radians, same
 * convention as Rotation.getRadians(), see AirshipMath) that eases toward the direction of travel, and tests collisions.
 */
final class AirshipFlight {

    /** Last head yaw sent by the client (written by AirshipInputSystem, read by the flight tick in "look" steering). */
    static final class Input {
        /** Head yaw sent by the client (radians, same convention as the player's yaw), NaN until one was seen. */
        volatile double headYaw = Double.NaN;
        volatile long headMs;
        volatile int headCount;
    }

    /** Identifier: pilot UUID, or a random one for a pilotless flight (name of the resume file). */
    volatile UUID id;
    /** Pilot, null when pilotless (dead or gone). */
    volatile UUID pilotUuid;
    volatile Ref<EntityStore> pilotRef;
    volatile String pilotName = "?";
    final World world;
    final Deployables.Kind kind;
    Ref<EntityStore> shipRef;
    UUID shipUuid;
    /** T82: observer entity (same model, never mounted, moved by the server every tick), seen by everybody but the pilot. Null in single rendering. */
    volatile Ref<EntityStore> observerRef;
    /** Model parts (nodes beyond the client's per-entity limit), null if there are none or the setting is off. */
    volatile VehicleParts.Group parts;
    /** T82: dual rendering is active (cleared when the flight has no pilot on board and collapses to one entity). */
    volatile boolean dual;

    /** Entity position: bottom centre of the pivot cell (where the pilot stands), world frame. */
    final Vector3d pos = new Vector3d();
    /** Heading in radians, continuous (not wrapped). */
    double theta;
    /** Yaw rate (rad/s). */
    double yawRate;
    /** T85: yaw acceleration (rad/s2) of the jerk-limited rate controller. */
    double yawAcc;
    /** T85: smoothness diagnostics. Per-turn values are reset when a turn starts (turnDir leaves 0). */
    long lastTickNs;
    double lastDt;
    int refusedHeadings;
    int partialSteps;
    int modelSwaps;
    long lastSwapMs;
    int turnRefused;
    int turnPartial;
    int turnSwaps;
    int turnBaseCollisions;
    int turnBaseCarryTp;
    int turnTicks;
    double turnMaxStepDeg;
    double turnDtMin = Double.MAX_VALUE;
    double turnDtMax;
    double turnDtSum;
    int turnDtN;
    int turnYawSends;

    /** Last pose accepted by the collision test (pos and theta are always that pose). */
    final Vector3d lastValidPos = new Vector3d();
    double lastValidTheta;
    /** Pivot cell of the last valid pose (resume file): rounded from lastValidPos. */
    final Vector3i lastValidPivotCell = new Vector3i();
    /** Number of blocked samples of the accepted pose (0 normally, more only if the ship started embedded). */
    int blockedNow;

    // ---- heading from the direction of travel
    /** Start of the current travel sample (ms, 0 = none) and the pilot's horizontal position then. */
    long sampleMs;
    double sampleX;
    double sampleZ;
    /** Target heading (radians) of the last sample, valid only if hasTarget. */
    double targetHeading;
    volatile boolean hasTarget;
    /** Diagnostic: horizontal speed (b/s), direction of travel (radians, as a heading) and whether it counted as reversing. */
    volatile double travelSpeed;
    volatile double travelDir = Double.NaN;
    volatile boolean reversing;

    // ---- rotation centre carry (second test of 5 October 2026)
    /** Travel carried by the carry itself in the current sample window (expected, world frame): removed from the measured travel. */
    double sampleCarryX;
    double sampleCarryZ;
    /** Teleport carry: displacement owed to the pilot, not delivered yet. */
    double carryAccX;
    double carryAccZ;
    /** Teleport carry: estimated pilot position right after a carry teleport (the reported one is stale until carryPauseUntilMs). */
    Vector3d pilotEst;
    long carryPauseUntilMs;
    /** A non-zero ChangeVelocity was sent and not yet cancelled. */
    boolean velActive;
    /** Velocity carry did not seem to work (see AirshipManager.checkCarryEffect): this flight uses the teleport carry. */
    volatile boolean carryFallback;
    /** Pilot's own horizontal velocity (b/s) measured in the last sample window without carry. */
    double ownVx;
    double ownVz;
    /** Effect check: expected carry length and its measured projection since the check started. */
    double chkExp;
    double chkRes;
    volatile double chkRatio = Double.NaN;
    /** Diagnostic: last carry displacement (blocks per tick), total carried length and number of carry teleports. */
    volatile double lastCarry;
    volatile double totalCarry;
    int carryTeleports;
    /** The helm helper entity (null if it could not be created). */
    AirshipHelm.Helper helm;

    // ---- balloon flight model (same fields as BalloonFlight)
    /** The ship entity is mounted on the pilot (MountedComponent on the entity). False while aligning for the landing. */
    volatile boolean attached;
    long takeoffMs;
    /** Tracking suspended until this time (take-off teleport, collision teleport). */
    long teleportUntilMs;

    /** Origin and rotation of the prefab at take-off (resume file: removes the old ship if the world was saved before take-off). */
    final Vector3i takeoffOrigin = new Vector3i();
    Rotation takeoffRotation;

    BalloonCargo cargo;
    BsonDocument cargoBson;

    // ---- engines and fuel (T57)
    /** Contents of the engines, kept during the flight (null only for a flight that has none to keep). */
    AirshipEngines engines;
    /** Level and upgrade items of the upgraded benches aboard, put back at landing (T63, null for an older resume file). */
    AirshipBenches benches;
    /** No fuel left (or none at take-off): the pilot's horizontal speed is 0 and the off model is shown. */
    volatile boolean dry;
    /** The ship moved horizontally in the last travel sample, so fuel was burned (diagnostic). */
    volatile boolean burning;
    /** Fuel warnings already sent (30 s and 10 s of travel left). */
    boolean warned30;
    boolean warned10;
    final AirshipFlight.Input input = new Input();

    /** Model currently shown (travelling, idle or off). */
    volatile String modelId;
    volatile boolean off;
    /** Burner state shown by the model: idle smoke, forward flames, or an asymmetric turn (LEFT = bow swings to the pilot's left). */
    enum Thrust { IDLE, FORWARD, LEFT, RIGHT }

    volatile Thrust thrust = Thrust.IDLE;
    /** State wanted by the last evaluation and since when (hold before leaving a flame state). */
    Thrust thrustWanted = Thrust.IDLE;
    long thrustWantedSinceMs;
    /** Turning hysteresis: +1 turning left (yaw rate > 0), -1 turning right, 0 not turning. */
    int turnDir;
    /** The last travel sample was above fuelMoveMin (forward flames). */
    volatile boolean moving;
    /** Absolute heading change (rad) applied since the start of the current sample window. */
    double windowTurn;
    /** The sound effect of the burners (Hotair_Balloon_Burner_Boost) is on the ship entity. */
    volatile boolean roar;
    /** Rest turn: the ship turns toward the pilot's head yaw. restSinceMs: when the head first left the dead zone (0 = not). */
    volatile boolean restActive;
    long restSinceMs;

    final List<BalloonLights.Light> lights = new CopyOnWriteArrayList<>();
    /** Passengers seated on the stools (T64). Empty without passengers. */
    final List<AirshipPassengers.Rider> riders = new CopyOnWriteArrayList<>();
    /** Firebox light flicker (T58): next update time and the smoothed level (0 to 1). */
    long fireboxNextMs;
    double fireboxLevel = 0.5;

    long lastTickMs;
    long lastResumeMs;
    long lastCollisionLogMs;
    /** T76: last "height limit reached" message sent to the pilot. */
    long lastHeightMsgMs;

    // ---- landing alignment
    volatile boolean landing;
    /** The landing was triggered by the pilot's death (or departure): retried every 5 s if impossible. */
    volatile boolean automatic;
    Rotation targetRotation;
    double targetTheta;
    final Vector3i targetPivot = new Vector3i();
    long landingStartMs;
    long landingStuckMs;
    long nextLandingRetryMs;
    /** The end of the flight is being processed (no more ticks). */
    volatile boolean finishing;

    /** Diagnostic: last collision description and the number of rejected moves. */
    volatile String lastCollision = "none";
    int collisionCount;

    AirshipFlight(UUID id, UUID pilotUuid, Ref<EntityStore> pilotRef, String pilotName, World world, Deployables.Kind kind) {
        this.id = id;
        this.pilotUuid = pilotUuid;
        this.pilotRef = pilotRef;
        this.pilotName = pilotName;
        this.world = world;
        this.kind = kind;
    }

    boolean pilotless() {
        return pilotRef == null;
    }

    /** Pivot cell for the current position (the cell whose bottom centre is pos). */
    Vector3i pivotCell(Vector3d p) {
        return new Vector3i((int) Math.round(p.x - 0.5), (int) Math.round(p.y), (int) Math.round(p.z - 0.5));
    }
}
