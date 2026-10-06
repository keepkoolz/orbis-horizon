package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.FlyMode;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.world.CancelParticleSystems;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Airship prototype (The_Cloudwork): take-off, flight on the balloon model, landing with alignment, edge cases.
 *
 * Flight model of the balloon (validated in game), plus a free heading:
 * - the pilot flies freely (forced fly mode, flight speeds halved, lever or land command to stop) and the ship entity is mounted ON the
 *   pilot (MountedComponent, Minecart controller, offset 0, pivot under the pilot). The pilot is not mounted and not seated.
 *   (Test of 5 October 2026: a rider mounted on the entity has his own client drive it like a minecart, so that was dropped.)
 * - every tick the ship takes the pilot's position. Collisions are tested for any angle against the "hull" cells of the prefab;
 *   on a collision the pilot is teleported back to the last valid position, like the balloon.
 * - the heading theta (server side) eases toward the direction the pilot travels, or toward his head yaw, with a limited turn
 *   rate. A new heading that would collide is refused.
 * - stopping (lever, command) aligns the ship to the nearest quarter turn and cell, then pastes the prefab again.
 *
 * Maths: AirshipMath (same convention as Rotation.rotateYaw, verified offline). Every method here that changes
 * components runs on the world thread (take-off by interaction or command, tick, events through runOnWorld).
 */
final class AirshipManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AirshipManager INSTANCE = new AirshipManager();

    static AirshipManager get() {
        return INSTANCE;
    }

    /** How the heading is chosen (/orbishorizon airship steer). */
    enum Steer {
        /** The ship turns toward the horizontal direction the pilot travels (default). */
        TRAVEL,
        /** The ship turns toward the pilot's head yaw. */
        LOOK,
        /** Fixed heading. */
        OFF
    }

    // ------------------------------------------------------------------ tuning (all adjustable with /orbishorizon airship tune)

    /** Halved after the first in-game test (it was 20 deg/s and 40 deg/s2). */
    volatile double maxYawRateDeg = 10;
    volatile double yawAccelDeg = 20;
    /** Minimum horizontal speed (b/s) for the travel direction to steer the ship. */
    volatile double turnSpeedMin = 0.8;
    /** Travel within this angle (degrees) of the stern direction counts as reversing: no rotation. */
    volatile double reverseConeDeg = 100;
    /** Factor applied to the pilot's flight speeds at the next take-off (the balloon uses 0.5). */
    volatile float flySpeedFactor = 0.5f;
    volatile Steer steer = Steer.TRAVEL;
    /** How the pilot is moved along the arc when the ship turns around its rotation centre. */
    enum Carry {
        /** The server sends the pilot a ChangeVelocity (Set) with the arc velocity every tick while turning (default). */
        VELOCITY,
        /** The server teleports the pilot by the accumulated arc displacement once it exceeds carryStep. */
        TELEPORT,
        /** No carry: the ship turns around the pilot (behaviour of the first version). */
        OFF
    }

    volatile Carry carry = Carry.VELOCITY;
    /** Z (prefab frame, x = 0) of the rotation centre C: -15 is the bow, 15 the stern. 7 = start of the rear quarter, 0 = middle. */
    volatile double rotationCenterZ = 7;
    /** Velocity carry: factor on the velocity sent (in case the client applies it more or less strongly). */
    volatile double carryGain = 1.0;
    /** Teleport carry: smallest displacement (blocks) that triggers a teleport. */
    volatile double carryStep = 0.3;
    /** Teleport carry: time (ms) the pilot's reported position is not trusted after a carry teleport. */
    volatile long carryPauseMs = 100;
    /**
     * Fuel (T57). The ship burns fuel only while it moves horizontally: the pilot's horizontal travel speed (measured like the
     * heading samples, carry excluded) must be above fuelMoveMin (b/s). The burn time is the sample length times fuelFactor.
     */
    volatile double fuelFactor = 2;
    volatile double fuelMoveMin = 0.5;
    /** Travel time left (s) at which the low and critical fuel messages are sent. */
    private static final double FUEL_LOW_S = 30;
    private static final double FUEL_CRITICAL_S = 10;

    /** +1 or -1: sign of the heading in the entity's visual yaw (diagnostic, in case the client turns the opposite way). */
    volatile double yawSign = 1;

    /** Length of a travel sample used to measure the direction of travel (ms). */
    private static final long SAMPLE_MS = 250;
    /** The travel direction is ignored this long after take-off (ms). */
    private static final long TAKEOFF_IGNORE_MS = 1000;
    /** Longest step simulated in one tick (a late tick does not skip through a wall). */
    private static final double MAX_DT = 0.1;
    /** Hull cell sampling: half size of a cell shrunk by 0.05, as asked. */
    private static final double HALF = 0.45;
    private static final long RESUME_INTERVAL_MS = 3000;
    private static final long COLLISION_LOG_INTERVAL_MS = 2000;
    private static final double LANDING_YAW_RATE_DEG = 60;
    private static final double LANDING_SPEED = 2;
    private static final long LANDING_STUCK_MS = 1500;
    private static final long LANDING_RETRY_MS = 5000;
    /** Light entities in flight: 17 lit blocks and the firebox since the deck lanterns (T64, it was 16). */
    private static final int LIGHT_CAP = 18;
    private static final double DESPAWN_RADIUS = 48;
    private static final int SEARCH_UP = 8;
    private static final long LAND_WAIT_MS = 4000;
    private static final float LANDING_LIFT = 0.1f;
    private static final long WATCH_MS = 10000;
    /** Velocity carry effect check: expected carry (blocks) to observe before judging, and the least acceptable measured ratio. */
    private static final double CARRY_CHECK_BLOCKS = 2.0;
    private static final double CARRY_MIN_RATIO = 0.25;
    /** A carry shorter than this per sample window counts as "not turning". */
    private static final double CARRY_EPS = 0.02;

    private final Map<UUID, AirshipFlight> flights = new ConcurrentHashMap<>();
    private final Set<UUID> recovering = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
    private final Map<BalloonShape, Hull> hulls = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> particleIdsByBlock = new ConcurrentHashMap<>();
    /** Passengers seated on the ship's stools (T64). */
    final AirshipPassengers passengers = new AirshipPassengers(this);

    private AirshipManager() {
    }

    // ------------------------------------------------------------------ queries used by the other classes

    AirshipFlight flightOf(UUID id) {
        return flights.get(id);
    }

    boolean isFlying(UUID pilot) {
        return flights.containsKey(pilot);
    }

    boolean needsInput() {
        return !flights.isEmpty() || !watches.isEmpty();
    }

    private static Deployables.AirshipSpec spec() {
        return Deployables.AIRSHIP.airship;
    }

    private static Vector3f pivot() {
        return spec().pivot;
    }

    private double visualYaw(double theta) {
        return yawSign * theta + BalloonManager.MODEL_YAW_CORRECTION;
    }

    /** Collision hull of a shape: cell centres relative to the pivot (prefab frame, before rotation). */
    private static final class Hull {
        final double[] dx;
        final double[] dy;
        final double[] dz;

        Hull(double[] dx, double[] dy, double[] dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        int size() {
            return dx.length;
        }
    }

    /**
     * Hull of a shape: prefab cells that touch (6-neighbourhood) the empty space reachable from outside the prefab's box. Cells that
     * only face a closed interior (cabins) are left out: they cannot touch the scenery before the outer shell does, and the
     * test is paid every tick. Positions are relative to the pivot.
     */
    private Hull hullOf(BalloonShape s) {
        return hulls.computeIfAbsent(s, shape -> {
            Vector3f pv = pivot();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BalloonShape.Cell c : shape.cells()) {
                minX = Math.min(minX, c.x());
                minY = Math.min(minY, c.y());
                minZ = Math.min(minZ, c.z());
                maxX = Math.max(maxX, c.x());
                maxY = Math.max(maxY, c.y());
                maxZ = Math.max(maxZ, c.z());
            }
            // Flood fill of the empty cells of the box extended by one cell, from a corner.
            int sx = maxX - minX + 3, sy = maxY - minY + 3, sz = maxZ - minZ + 3;
            boolean[] outside = new boolean[sx * sy * sz];
            int ox = minX - 1, oy = minY - 1, oz = minZ - 1;
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            outside[0] = true;
            queue.add(new int[]{0, 0, 0});
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                for (int[] d : dirs) {
                    int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                        continue;
                    }
                    int idx = (x * sy + y) * sz + z;
                    if (outside[idx] || shape.cellAt(x + ox, y + oy, z + oz) != null) {
                        continue;
                    }
                    outside[idx] = true;
                    queue.add(new int[]{x, y, z});
                }
            }
            List<BalloonShape.Cell> hull = new ArrayList<>();
            for (BalloonShape.Cell c : shape.cells()) {
                int x = c.x() - ox, y = c.y() - oy, z = c.z() - oz;
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                    if (outside[(nx * sy + ny) * sz + nz]) {
                        hull.add(c);
                        break;
                    }
                }
            }
            double[] dx = new double[hull.size()];
            double[] dy = new double[hull.size()];
            double[] dz = new double[hull.size()];
            for (int i = 0; i < dx.length; i++) {
                dx[i] = hull.get(i).x() - pv.x;
                dy[i] = hull.get(i).y() - pv.y;
                dz[i] = hull.get(i).z() - pv.z;
            }
            LOGGER.at(Level.INFO).log("Dirigeable : %d case(s), %d case(s) de coque extérieure pour les collisions", shape.cells().size(), dx.length);
            return new Hull(dx, dy, dz);
        });
    }

    /**
     * Number of distinct world blocks (solid or liquid, BalloonManager.blocksBalloon) touched by the hull for this pose. Samples of
     * a hull cell: its centre and its 4 horizontal corners shrunk by 0.05, at the cell's mid, top and bottom heights (9 points,
     * deduplicated). Stops as soon as more than limit are found. Unloaded chunks never block.
     */
    private static int blocked(World world, Hull h, double px, double py, double pz, double theta, int limit) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double[] cox = new double[4];
        double[] coz = new double[4];
        double[][] sg = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int i = 0; i < 4; i++) {
            double ox = sg[i][0] * HALF;
            double oz = sg[i][1] * HALF;
            cox[i] = ox * c + oz * s;
            coz[i] = -ox * s + oz * c;
        }
        LongOpenHashSet seen = new LongOpenHashSet(4096);
        int count = 0;
        for (int k = 0; k < h.size(); k++) {
            double ccx = px + h.dx[k] * c + h.dz[k] * s;
            double ccz = pz - h.dx[k] * s + h.dz[k] * c;
            double ccy = py + 0.5 + h.dy[k];
            if (testPoint(world, seen, ccx, ccy, ccz)) {
                if (++count > limit) {
                    return count;
                }
            }
            for (int i = 0; i < 4; i++) {
                for (int level = -1; level <= 1; level += 2) {
                    if (testPoint(world, seen, ccx + cox[i], ccy + level * HALF, ccz + coz[i])) {
                        if (++count > limit) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    private static boolean testPoint(World world, LongOpenHashSet seen, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        long key = ((long) (bx & 0x3FFFFFF) << 38) | ((long) (bz & 0x3FFFFFF) << 12) | (by & 0xFFF);
        if (!seen.add(key)) {
            return false;
        }
        return BalloonManager.blocksBalloon(world, bx, by, bz);
    }

    /** Computes the collision hull of the shape now (logged), instead of at the first take-off. */
    void warmUp(BalloonShape s) {
        hullOf(s);
    }

    // ------------------------------------------------------------------ take-off

    /** Null if all went well, otherwise an error message for the player (nothing is removed then). */
    Message takeOff(Store<EntityStore> store, Ref<EntityStore> pilotRef, PlayerRef player, World world) {
        if (flights.containsKey(player.getUuid()) || BalloonManager.get().isFlying(player.getUuid())) {
            return Texts.t("airship.error.alreadyPiloting");
        }
        Deployables.Kind kind = Deployables.AIRSHIP;
        BalloonShape s;
        try {
            s = kind.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Lecture du prefab du dirigeable impossible");
            return Texts.t("airship.error.prefabUnreadable").param("error", String.valueOf(e.getMessage()));
        }
        TransformComponent pt = store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (pt == null) {
            return Texts.t("error.noPosition");
        }
        BalloonManager.Found found = BalloonManager.get().find(world, new Vector3d(pt.getPosition()), List.of(kind));
        if (found == null) {
            return Texts.t("airship.error.noAirship");
        }
        Vector3i origin = found.origin();
        Rotation rotation = found.rotation();
        for (String id : new String[]{spec().modelId, spec().idleModelId, spec().offModelId}) {
            if (ModelAsset.getAssetMap().getAsset(id) == null) {
                return Texts.t("airship.error.modelMissing").param("model", id);
            }
        }
        // Processing benches (tannery...) other than the engines must be empty: their contents are not carried (prototype rule).
        for (BalloonShape.Cell c : s.cells()) {
            if (AirshipEngines.isEngineCell(c)) {
                continue; // T57: the engines carry their contents (fuel, charcoal) through the flight
            }
            Vector3i p = c.rotated(rotation).add(origin);
            ProcessingBenchBlock bench = BurnerFuel.live(world, p);
            if (bench != null && !benchEmpty(bench)) {
                return Texts.t("airship.error.benchNotEmpty").param("block", c.baseName());
            }
        }
        // T64: the other players aboard become passengers on the stools; more players than stools: take-off refused.
        List<BalloonManager.Occupant> aboard = AirshipPassengers.aboard(store, world, s, origin, rotation, player.getUuid());
        int seats = AirshipPassengers.seats(s).size(); // stools and tavern benches (2 seats each)
        if (aboard.size() > seats) {
            return Texts.t("airship.error.full").param("max", seats + 1).param("extra", aboard.size() - seats);
        }
        return launch(store, world, s, origin, rotation, pilotRef, player, passengers.assign(store, s, origin, rotation, aboard));
    }

    private static boolean benchEmpty(ProcessingBenchBlock b) {
        ItemContainer in = b.getInputContainer();
        ItemContainer fuel = b.getFuelContainer();
        ItemContainer out = b.getOutputContainer();
        return (in == null || in.isEmpty()) && (fuel == null || fuel.getCapacity() == 0 || fuel.isEmpty())
                && (out == null || out.isEmpty()) && b.getFuelTime() <= 0;
    }

    /** True if the world point is inside the box of the prefab placed at this origin and rotation. */
    static boolean insideShape(BalloonShape s, Vector3i origin, Rotation rotation, Vector3d p) {
        Vector3d local = AirshipMath.rotateY(p.x - origin.x - 0.5, p.y - origin.y, p.z - origin.z - 0.5,
                -rotation.getRadians(), new Vector3d());
        // The cell (x, y, z) covers x - 0.5 .. x + 0.5 horizontally around its centre (centres at origin + R c + 0.5).
        return s.inBounds((int) Math.round(local.x), (int) Math.floor(local.y), (int) Math.round(local.z));
    }

    private Message launch(Store<EntityStore> store, World world, BalloonShape s, Vector3i origin, Rotation rotation,
                           Ref<EntityStore> pilotRef, PlayerRef player, List<AirshipPassengers.Rider> riders) {
        Deployables.Kind kind = Deployables.AIRSHIP;
        Deployables.AirshipSpec spec = spec();
        Vector3f pv = pivot();
        TransformComponent pilotTransform = store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (pilotTransform == null) {
            return Texts.t("error.noPosition");
        }
        AirshipFlight f = new AirshipFlight(player.getUuid(), player.getUuid(), pilotRef, player.getUsername(), world, kind);
        Vector3i pivotCell = rotation.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()).add(origin);
        f.pos.set(pivotCell.x + 0.5, pivotCell.y, pivotCell.z + 0.5);
        f.theta = rotation.getRadians();
        f.modelId = spec.idleModelId; // the travelling model (burner flames) replaces it once the ship moves
        f.lastValidPos.set(f.pos);
        f.lastValidTheta = f.theta;
        f.lastValidPivotCell.set(pivotCell);
        f.takeoffOrigin.set(origin);
        f.takeoffRotation = rotation;
        f.lastTickMs = System.currentTimeMillis();

        // 1. The pilot switches to flight before the blocks are removed, to avoid falling (same as the balloon).
        // A player seated on a block mount is dismounted first: the block is going to disappear.
        MountUtil.unseat(store, pilotRef);
        if (!BalloonManager.get().setPilotFlying(store, pilotRef, player, true, flySpeedFactor)) {
            return Texts.t("error.movement");
        }
        // T64: the passengers too (held in the air at zero flight speed), and listed in the flight for the resume file.
        passengers.prepare(store, riders);
        f.riders.addAll(riders);

        // Particle systems declared by the blocks, collected BEFORE removal (cancelled afterwards).
        List<Fx> fx = collectParticles(world, s, origin, rotation);
        // The contents of the chests are copied then emptied (the game would drop them when the blocks disappear).
        f.cargo = BalloonCargo.takeFrom(world, s, origin, rotation);
        // The engines' contents (fuel, charcoal) are copied then emptied the same way (T57).
        f.engines = AirshipEngines.takeFrom(world, s, origin, rotation);
        // T63: the level and the upgrade items of the upgraded benches are kept (the game would drop the items as a refund).
        f.benches = AirshipBenches.takeFrom(world, s, origin, rotation);
        // The resume file is written (fsync) before the blocks are removed: from now on the contents only exist in memory.
        AirshipResume.write(f, true);
        BalloonRegistry.remove(world, origin, rotation, kind.id);
        int removed = BalloonManager.removeBlocks(world, s, origin, rotation, true);
        cancelParticles(world, fx);

        Ref<EntityStore> ship = BalloonManager.get().spawnModelEntity(store, new Vector3d(f.pos), visualYaw(f.theta), spec.idleModelId);
        if (ship == null) {
            BalloonManager.get().pastePrefab(kind, world, store, origin, rotation, player.getUuid());
            BalloonManager.get().restoreCargo(world, store, f.cargo, origin, rotation);
            f.engines.restore(world, store, origin, rotation);
            f.benches.restore(world, store, origin, rotation);
            BalloonManager.get().setPilotFlying(store, pilotRef, player, false, 1f);
            passengers.disembark(store, f, origin, rotation);
            AirshipResume.delete(f.id);
            return Texts.t("error.modelMissing").param("model", spec.idleModelId);
        }
        f.shipRef = ship;
        UUIDComponent uc = store.getComponent(ship, UUIDComponent.getComponentType());
        f.shipUuid = uc != null ? uc.getUuid() : null;

        // 2. The pilot is teleported to the pivot (the entity's pivot is under him) and the entity is mounted on him, offset 0.
        Rotation3f look = new Rotation3f(pilotTransform.getRotation());
        store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(new Vector3d(f.pos), look));
        attachShip(store, f);
        long now = System.currentTimeMillis();
        f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
        f.takeoffMs = now;
        spawnLights(store, f, s);
        spawnLever(store, f, s);
        passengers.board(store, f);
        flights.put(f.id, f);
        f.lastResumeMs = f.lastTickMs;
        AirshipResume.write(f, false);
        player.sendMessage(Texts.t("airship.takeoff"));
        if (f.engines.isEmpty()) {
            // No fuel: the ship still climbs, descends and hovers (gas lift), but cannot travel.
            enterDry(store, f, false);
            player.sendMessage(Texts.t("airship.fuel.none"));
        } else {
            player.sendMessage(fuelMessage(f));
        }
        LOGGER.at(Level.INFO).log("Décollage du dirigeable par %s : origine %s, rotation %s, %d blocs retirés, %d objet(s) de coffre, "
                        + "%d moteur(s), %d objet(s) de moteur, %.0f s de combustible, établis améliorés : %s, %d passager(s)",
                player.getUsername(), origin, rotation, removed, f.cargo.itemCount(), f.engines.entryCount(), f.engines.itemCount(),
                f.engines.remainingSeconds(), f.benches.describe(), f.riders.size());
        return null;
    }

    /** Mounts the ship entity on the pilot with a zero offset (the balloon's attach mode: the client glues it to the pilot). */
    private void attachShip(Store<EntityStore> store, AirshipFlight f) {
        store.putComponent(f.shipRef, MountedComponent.getComponentType(),
                new MountedComponent(f.pilotRef, new Vector3f(), MountController.Minecart));
        f.attached = true;
    }

    /** Takes the ship entity off the pilot (it is then moved by the server writes only, like the balloon's T26 descent). */
    private void detachShip(Store<EntityStore> store, AirshipFlight f) {
        if (f.attached && f.shipRef != null && f.shipRef.isValid()) {
            store.tryRemoveComponent(f.shipRef, MountedComponent.getComponentType());
        }
        f.attached = false;
    }

    // ------------------------------------------------------------------ engines and fuel (T57)

    /** Travel time left with the fuel of the engines, in seconds of the ship's own travel (burn seconds divided by the factor). */
    private double travelSecondsLeft(AirshipFlight f) {
        return f.engines != null ? f.engines.remainingSeconds() / Math.max(0.01, fuelFactor) : 0;
    }

    private Message fuelMessage(AirshipFlight f) {
        return Texts.t("airship.fuel.level").param("time", BalloonManager.durationText(travelSecondsLeft(f)))
                .param("items", f.engines.fuelItems());
    }

    /**
     * No more fuel to travel: the pilot's horizontal flight speed goes to 0 (climbing, descending and landing stay possible),
     * the off model replaces the smoke model and the smoke is cancelled. Nothing else changes: the ship stays in the air (gas lift).
     * The engines are part of the entity: they cannot be refuelled until the ship lands. Never throws.
     */
    private void enterDry(Store<EntityStore> store, AirshipFlight f, boolean announce) {
        if (f.dry) {
            return;
        }
        f.dry = true;
        f.burning = false;
        showOffModel(store, f);
        applyDrySpeed(store, f);
        if (announce) {
            PlayerRef pr = playerOf(store, f.pilotRef);
            if (pr != null) {
                pr.sendMessage(Texts.t("airship.fuel.dry"));
            }
        }
        LOGGER.at(Level.INFO).log("Dirigeable de %s : moteurs à sec, vitesse horizontale du pilote à 0", f.pilotName);
    }

    /** Sets the pilot's horizontal flight speed to 0 (vertical unchanged). Never throws. */
    private void applyDrySpeed(Store<EntityStore> store, AirshipFlight f) {
        try {
            if (f.pilotless() || !f.pilotRef.isValid()) {
                return;
            }
            MovementManager mm = store.getComponent(f.pilotRef, MovementManager.getComponentType());
            PlayerRef pr = store.getComponent(f.pilotRef, PlayerRef.getComponentType());
            if (mm == null || pr == null) {
                return;
            }
            mm.getSettings().horizontalFlySpeed = 0f;
            mm.update(pr.getPacketHandler());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vitesse horizontale du pilote %s non mise à 0", f.pilotName);
        }
    }

    /**
     * Burns the engines' fuel for one travel sample (T57). Called with the sample length and the measured travel speed (carry
     * excluded). Nothing burns while hovering, climbing or descending (speed under fuelMoveMin). Warns at 30 s and 10 s of travel
     * left, and goes dry when the fuel runs out.
     */
    private void burnFuel(Store<EntityStore> store, AirshipFlight f, double seconds) {
        if (f.dry || f.engines == null) {
            f.burning = false;
            return;
        }
        boolean moving = f.travelSpeed > fuelMoveMin;
        f.burning = moving;
        updateThrust(store, f, moving);
        if (!moving) {
            return;
        }
        double unburned = f.engines.burn(seconds * fuelFactor);
        double left = travelSecondsLeft(f);
        if (unburned > 0 || f.engines.isEmpty()) {
            enterDry(store, f, true);
            return;
        }
        PlayerRef pr = playerOf(store, f.pilotRef);
        if (pr == null) {
            return;
        }
        if (left <= FUEL_CRITICAL_S && !f.warned10) {
            f.warned10 = true;
            f.warned30 = true;
            pr.sendMessage(Texts.t("airship.fuel.critical").param("time", BalloonManager.durationText(left)));
        } else if (left <= FUEL_LOW_S && !f.warned30) {
            f.warned30 = true;
            pr.sendMessage(Texts.t("airship.fuel.low").param("time", BalloonManager.durationText(left)));
        }
    }

    /**
     * Burner flames follow the fuel use: the travelling model (horizontal flames, full smoke at their tips) as soon as a travel
     * sample burns fuel, the idle model (half smoke at the burner mouths) once no sample has burned for THRUST_HOLD_MS.
     */
    private void updateThrust(Store<EntityStore> store, AirshipFlight f, boolean moving) {
        long now = System.currentTimeMillis();
        if (moving) {
            f.thrustLastMs = now;
            if (!f.thrust) {
                setThrust(store, f, true);
            }
        } else if (f.thrust && now - f.thrustLastMs >= THRUST_HOLD_MS) {
            setThrust(store, f, false);
        }
    }

    /**
     * Swaps the travelling and idle models (only while fuelled and lit) and cancels the particle system of the model that goes
     * away: the flames when the ship stops, the mouth smoke when it starts. The firebox embers are in both models. Never throws.
     */
    private void setThrust(Store<EntityStore> store, AirshipFlight f, boolean on) {
        if (f.thrust == on) {
            return;
        }
        f.thrust = on;
        if (f.off || f.dry || f.shipRef == null || !f.shipRef.isValid()) {
            return;
        }
        String id = on ? spec().modelId : spec().idleModelId;
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset == null) {
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable : le modèle actuel reste affiché", id);
            return;
        }
        try {
            store.putComponent(f.shipRef, ModelComponent.getComponentType(), new ModelComponent(Model.createUnitScaleModel(asset)));
            f.modelId = id;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Modèle %s non appliqué au dirigeable", id);
            return;
        }
        cancelExhaust(f, on ? spec().smokeSystemId : spec().flameSystemId);
    }

    /** Gives the pilot back his normal movement (also after a death). Never throws. */
    private void restorePilotMovement(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            PlayerRef pr = ref != null && ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : null;
            if (pr != null) {
                BalloonManager.get().setPilotFlying(store, ref, pr, false, 1f);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Réglages de déplacement du pilote non remis à la normale");
        }
    }

    // ------------------------------------------------------------------ particles of the removed blocks

    private record Fx(Vector3i cell, Set<String> ids) {
    }

    /** Ids of the particle systems of a block type and of all its states (cached by base name). */
    private Set<String> particleIds(String baseName) {
        return particleIdsByBlock.computeIfAbsent(baseName, n -> {
            Set<String> ids = new java.util.HashSet<>();
            BlockType base = BlockType.getAssetMap().getAsset(n);
            if (base == null) {
                return ids;
            }
            addParticles(ids, base);
            try {
                StateData st = base.getState();
                if (st != null) {
                    for (String name : st.getStateNames()) {
                        BlockType variant = base.getBlockForState(name);
                        if (variant != null) {
                            addParticles(ids, variant);
                        }
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("États de %s non lus pour les particules", n);
            }
            return ids;
        });
    }

    private static void addParticles(Set<String> ids, BlockType bt) {
        ModelParticle[] particles = bt.getParticles();
        if (particles != null) {
            for (ModelParticle p : particles) {
                if (p != null && p.getSystemId() != null) {
                    ids.add(p.getSystemId());
                }
            }
        }
    }

    private List<Fx> collectParticles(World world, BalloonShape s, Vector3i origin, Rotation rotation) {
        List<Fx> list = new ArrayList<>();
        for (BalloonShape.Cell c : s.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            Set<String> ids = new java.util.HashSet<>(particleIds(c.baseName()));
            BlockType actual = world.getBlockType(p.x, p.y, p.z);
            if (actual != null) {
                addParticles(ids, actual);
            }
            if (!ids.isEmpty()) {
                list.add(new Fx(p, ids));
            }
        }
        return list;
    }

    /** Asks the clients to stop the particle systems the removed blocks declared (otherwise flames stay in the sky). */
    private static void cancelParticles(World world, List<Fx> fx) {
        if (fx.isEmpty()) {
            return;
        }
        List<CancelParticleSystems> packets = new ArrayList<>();
        if (fx.size() <= 64) {
            for (Fx e : fx) {
                Vector3i p = e.cell();
                packets.add(new CancelParticleSystems(new Position(p.x - 0.5, p.y - 0.5, p.z - 0.5),
                        new Position(p.x + 1.5, p.y + 3.5, p.z + 1.5), e.ids().toArray(new String[0]), true));
            }
        } else {
            // Many sources: one packet per system id over the bounding box of its cells.
            Map<String, int[]> boxes = new java.util.HashMap<>();
            for (Fx e : fx) {
                for (String id : e.ids()) {
                    int[] b = boxes.computeIfAbsent(id, k -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
                    b[0] = Math.min(b[0], e.cell().x);
                    b[1] = Math.min(b[1], e.cell().y);
                    b[2] = Math.min(b[2], e.cell().z);
                    b[3] = Math.max(b[3], e.cell().x);
                    b[4] = Math.max(b[4], e.cell().y);
                    b[5] = Math.max(b[5], e.cell().z);
                }
            }
            for (Map.Entry<String, int[]> e : boxes.entrySet()) {
                int[] b = e.getValue();
                packets.add(new CancelParticleSystems(new Position(b[0] - 0.5, b[1] - 0.5, b[2] - 0.5),
                        new Position(b[3] + 1.5, b[4] + 3.5, b[5] + 1.5), new String[]{e.getKey()}, true));
            }
        }
        for (PlayerRef player : world.getPlayerRefs()) {
            for (CancelParticleSystems packet : packets) {
                player.getPacketHandler().writeNoCache(packet);
            }
        }
    }

    /** Stops the burner flames, the burner smoke and the firebox embers of a ship at this pose. */
    private void cancelSmoke(AirshipFlight f) {
        cancelEmbers(f);
        cancelExhaust(f, spec().smokeSystemId);
        cancelExhaust(f, spec().flameSystemId);
    }

    /** Stops one particle system around the burner pipes (Metal_Iron_Pipe_Short cells) of a ship at this pose. */
    private void cancelExhaust(AirshipFlight f, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            BalloonShape s = f.kind.shape();
            Vector3f pv = pivot();
            Vector3d out = new Vector3d();
            for (BalloonShape.Cell c : s.cells()) {
                if (!c.baseName().equals("Metal_Iron_Pipe_Short")) {
                    continue;
                }
                AirshipMath.cellCenter(f.pos, f.theta, pv.x, pv.y, pv.z, c.x(), c.y(), c.z(), out);
                CancelParticleSystems packet = new CancelParticleSystems(new Position(out.x - 4, out.y - 4, out.z - 4),
                        new Position(out.x + 4, out.y + 6, out.z + 4), new String[]{id}, true);
                for (PlayerRef player : f.world.getPlayerRefs()) {
                    player.getPacketHandler().writeNoCache(packet);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Cosmetic only.
        }
    }

    // ------------------------------------------------------------------ lights (BalloonLights helpers, yaw-aware follow)

    private void spawnLights(Store<EntityStore> store, AirshipFlight f, BalloonShape s) {
        if (!BalloonLights.enabled() || f.shipRef == null || !f.shipRef.isValid()) {
            return;
        }
        // T58: one slot of the cap is kept for the firebox light, the block lights share the others.
        BalloonShape.Cell firebox = fireboxCell(s);
        int cap = firebox != null ? LIGHT_CAP - 1 : LIGHT_CAP;
        List<BalloonLights.Spec> specs = BalloonLights.specsFor(s);
        int step = Math.max(1, (int) Math.ceil(specs.size() / (double) cap));
        int made = 0;
        for (int i = 0; i < specs.size() && made < cap; i += step) {
            BalloonLights.Spec spec = specs.get(i);
            if (spawnLight(store, f, spec.key(), new Vector3f(spec.local()), BalloonLights.effective(spec.light())) != null) {
                made++;
            }
        }
        if (firebox != null && fireboxWanted(f) != null) { // a ship that takes off dry never gets its firebox light back
            spawnLight(store, f, BalloonLights.KEY_FIREBOX, new Vector3f(firebox.x(), firebox.y() + 0.5f, firebox.z()), fireboxWanted(f));
        }
    }

    /** Creates one light entity mounted on the ship at this prefab point. Returns null (logged) if it fails. */
    private BalloonLights.Light spawnLight(Store<EntityStore> store, AirshipFlight f, String key, Vector3f local, ColorLight color) {
        try {
            BalloonLights.Light light = new BalloonLights.Light(key, local);
            light.uuid = BalloonLights.uuidFor(f.shipUuid, key);
            light.color = color;
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(),
                    new TransformComponent(lightPosition(f, light.local), new Rotation3f()));
            holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(light.uuid));
            holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(Intangible.getComponentType());
            holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
            holder.addComponent(DynamicLight.getComponentType(), new DynamicLight(color));
            light.ref = store.addEntity(holder, AddReason.SPAWN);
            if (light.ref == null) {
                return null;
            }
            // Offset in the entity's frame: the client rotates it with the entity's yaw (theta plus pi), so it is the
            // prefab offset (point minus pivot) with x and z inverted, whatever the heading.
            Vector3f d = new Vector3f(light.local).sub(pivot());
            store.putComponent(light.ref, MountedComponent.getComponentType(),
                    new MountedComponent(f.shipRef, new Vector3f(-d.x, d.y, -d.z), MountController.Minecart));
            f.lights.add(light);
            return light;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Lumière %s du dirigeable impossible", key);
            return null;
        }
    }

    // ------------------------------------------------------------------ firebox glow (T58)

    /** Flicker period: the light colour is re-evaluated every FIREBOX_MIN_MS to FIREBOX_MIN_MS + FIREBOX_SPREAD_MS. */
    private static final long FIREBOX_MIN_MS = 100;
    /** The burner flames stay on this long after the last travel sample that burned fuel (no flicker at each 250 ms sample). */
    private static final long THRUST_HOLD_MS = 750;
    private static final long FIREBOX_SPREAD_MS = 100;
    /** Share of the distance to a new random target covered at each update (smooth flicker, no jumps from 0 to 1). */
    private static final double FIREBOX_SMOOTH = 0.55;

    private static BalloonShape.Cell fireboxCell(BalloonShape s) {
        for (BalloonShape.Cell c : s.cells()) {
            if (AirshipEngines.ENGINE_BLOCK.equals(c.baseName())) {
                return c;
            }
        }
        return null;
    }

    /** Colour the firebox light should have now: null when dry (out), else the flicker colour of the current mode. */
    private static ColorLight fireboxWanted(AirshipFlight f) {
        if (f.dry || f.engines == null || f.engines.isEmpty()) {
            return null;
        }
        return BalloonLights.effective(BalloonLights.fireboxColor(f.burning, f.fireboxLevel));
    }

    /** Sets the firebox light colour (null: out), only sending when the channels actually change. Never throws. */
    private void setFireboxColor(Store<EntityStore> store, AirshipFlight f, ColorLight color) {
        for (BalloonLights.Light l : f.lights) {
            if (!BalloonLights.KEY_FIREBOX.equals(l.key) || l.ref == null || !l.ref.isValid()) {
                continue;
            }
            ColorLight old = l.color;
            if (old == null ? color == null : (color != null && old.radius == color.radius && old.red == color.red
                    && old.green == color.green && old.blue == color.blue)) {
                return;
            }
            try {
                DynamicLight d = store.getComponent(l.ref, DynamicLight.getComponentType());
                if (d != null) {
                    d.setColorLight(color);
                    l.color = color;
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Lumière du foyer du dirigeable non mise à jour");
            }
            return;
        }
    }

    /**
     * Smooth random flicker of the firebox light: every 100 to 200 ms a new random target level is drawn and the level moves part
     * of the way toward it, then the colour is computed from the level (dim while hovering with fuel, bright while the engine burns,
     * out when dry). Quantised on 0 to 15 channels, so the colour changes only every few updates: a packet is sent only then.
     */
    private void flickerFirebox(Store<EntityStore> store, AirshipFlight f, long now) {
        if (now < f.fireboxNextMs) {
            return;
        }
        f.fireboxNextMs = now + FIREBOX_MIN_MS + java.util.concurrent.ThreadLocalRandom.current().nextLong(FIREBOX_SPREAD_MS + 1);
        double target = java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        f.fireboxLevel += (target - f.fireboxLevel) * FIREBOX_SMOOTH;
        setFireboxColor(store, f, fireboxWanted(f));
    }

    /** Stops the firebox ember particles of the model, around the firebox cell at the ship's current pose. */
    private void cancelEmbers(AirshipFlight f) {
        try {
            BalloonShape.Cell c = fireboxCell(f.kind.shape());
            if (c == null) {
                return;
            }
            Vector3f pv = pivot();
            Vector3d out = new Vector3d();
            AirshipMath.cellCenter(f.pos, f.theta, pv.x, pv.y, pv.z, c.x(), c.y(), c.z(), out);
            AirshipEngines.cancelEmbers(f.world.getPlayerRefs(), out.x, out.y, out.z);
        } catch (IOException | RuntimeException e) {
            // Cosmetic only.
        }
    }

    /** The lever helper entity (interactable, mounted on the ship): created next to the lights. */
    private void spawnLever(Store<EntityStore> store, AirshipFlight f, BalloonShape s) {
        if (f.shipRef == null || !f.shipRef.isValid() || s.anchor() == null) {
            return;
        }
        BalloonShape.Cell a = s.anchor();
        Vector3f local = new Vector3f(a.x(), a.y() + 0.5f, a.z());
        f.lever = AirshipLever.spawn(store, f.shipUuid, f.shipRef, local, pivot(), lightPosition(f, local));
    }

    /** World point of a prefab point of the ship at its current pose (pivot at pos, heading theta). */
    Vector3d shipPoint(AirshipFlight f, Vector3f local) {
        return lightPosition(f, local);
    }

    private Vector3d lightPosition(AirshipFlight f, Vector3f local) {
        Vector3f pv = pivot();
        Vector3d r = AirshipMath.rotateY(local.x - pv.x, local.y - pv.y, local.z - pv.z, f.theta, new Vector3d());
        return r.add(f.pos);
    }

    private void followLights(Store<EntityStore> store, AirshipFlight f) {
        if (f.lever != null && f.lever.ref != null && f.lever.ref.isValid()) {
            TransformComponent lt = store.getComponent(f.lever.ref, TransformComponent.getComponentType());
            if (lt != null) {
                lt.setPosition(lightPosition(f, f.lever.local));
            }
        }
        for (BalloonLights.Light l : f.lights) {
            if (l.ref == null || !l.ref.isValid()) {
                continue;
            }
            TransformComponent t = store.getComponent(l.ref, TransformComponent.getComponentType());
            if (t != null) {
                t.setPosition(lightPosition(f, l.local));
            }
        }
    }

    private static void removeLights(Store<EntityStore> store, AirshipFlight f) {
        AirshipLever.remove(store, f.lever);
        f.lever = null;
        for (BalloonLights.Light l : f.lights) {
            try {
                if (l.ref != null && l.ref.isValid()) {
                    store.removeEntity(l.ref, RemoveReason.REMOVE);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Lumière %s non supprimée", l.key);
            }
        }
        f.lights.clear();
    }

    // ------------------------------------------------------------------ flight (every tick)

    void tick(Store<EntityStore> store, World world) {
        expireWatches();
        AirshipBenches.retry(store, world);
        if (flights.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (AirshipFlight f : new ArrayList<>(flights.values())) {
            if (f.world != world || f.finishing) {
                continue;
            }
            try {
                tickOne(store, world, f, now);
                if (!f.finishing && flights.get(f.id) == f) {
                    passengers.update(store, world, f, now); // T64: after this tick's pose
                }
            } catch (RuntimeException e) {
                if (now - f.lastCollisionLogMs > COLLISION_LOG_INTERVAL_MS) {
                    f.lastCollisionLogMs = now;
                    LOGGER.at(Level.WARNING).withCause(e).log("Erreur dans le tick du dirigeable %s", f.id);
                }
            }
        }
    }

    private void tickOne(Store<EntityStore> store, World world, AirshipFlight f, long now) {
        if (f.shipRef == null || !f.shipRef.isValid()) {
            LOGGER.at(Level.WARNING).log("Entité du dirigeable %s perdue : pose immédiate", f.id);
            landImmediately(f);
            return;
        }
        if (!f.pilotless() && !f.pilotRef.isValid()) {
            // Pilot gone (other world, or a disconnect the event did not handle).
            landImmediately(f);
            return;
        }
        if (!f.pilotless() && BalloonManager.isDead(store, f.pilotRef)) {
            pilotDied(store, f);
            return;
        }
        BalloonShape s;
        try {
            s = f.kind.shape();
        } catch (IOException e) {
            return;
        }
        double dt = f.lastTickMs == 0 ? 0 : Math.min((now - f.lastTickMs) / 1000.0, MAX_DT);
        f.lastTickMs = now;

        Hull hull = hullOf(s);
        if (f.landing) {
            landingStep(store, world, f, s, hull, dt, now);
        } else if (f.pilotless()) {
            // A pilotless ship (dead pilot): it holds still, aligns itself and lands, retried every 5 s if impossible.
            if (!f.automatic || now >= f.nextLandingRetryMs) {
                f.automatic = true;
                startLanding(f, true);
            }
        } else if (!pilotStep(store, world, f, hull, dt, now)) {
            return; // collision teleport or a stop was requested: the entity is not written this tick
        }
        if (f.finishing) {
            return; // landed during this tick: the entity is gone
        }
        if (f.landing || f.pilotless()) {
            writeEntity(store, f);
        }
        followLights(store, f);
        flickerFirebox(store, f, now);
        if (now - f.lastResumeMs >= RESUME_INTERVAL_MS) {
            f.lastResumeMs = now;
            AirshipResume.write(f, false);
        }
    }

    /** Carry mode in force for this flight (the velocity carry falls back to the teleport carry once it was judged ineffective). */
    private Carry effectiveCarry(AirshipFlight f) {
        Carry c = carry;
        return c == Carry.VELOCITY && f.carryFallback ? Carry.TELEPORT : c;
    }

    /** Rotation centre minus pivot, prefab frame (x = 0, y = 0): P - C. */
    private Vector3d pivotMinusCenter() {
        return new Vector3d(pivot().x, 0, pivot().z - rotationCenterZ);
    }

    /** World position of the rotation centre C for a ship whose pivot (pilot) is at pos with heading theta: C = P - R(theta)(P - C). */
    private Vector3d centerWorld(Vector3d pos, double theta) {
        Vector3d pc = pivotMinusCenter();
        Vector3d r = AirshipMath.rotateY(pc.x, 0, pc.z, theta, new Vector3d());
        return new Vector3d(pos.x - r.x, pos.y, pos.z - r.z);
    }

    /**
     * Displacement of the pilot (P, world frame) needed when the heading goes from theta0 to theta1 around C:
     * R(theta1)(P - C) - R(theta0)(P - C). The pilot's own flying moves C and P together and is not part of this.
     */
    private Vector3d carryDelta(double theta0, double theta1) {
        Vector3d pc = pivotMinusCenter();
        Vector3d a = AirshipMath.rotateY(pc.x, 0, pc.z, theta0, new Vector3d());
        Vector3d b = AirshipMath.rotateY(pc.x, 0, pc.z, theta1, new Vector3d());
        return b.sub(a);
    }

    /** Cancels the carry state (pending teleport displacement, velocity already sent). */
    private void stopCarry(Store<EntityStore> store, AirshipFlight f) {
        f.carryAccX = 0;
        f.carryAccZ = 0;
        f.pilotEst = null;
        f.carryPauseUntilMs = 0;
        if (f.velActive) {
            sendCarryVelocity(store, f, 0, 0);
        }
    }

    /**
     * Sends the pilot a horizontal ChangeVelocity of type Set (blocks per second, vertical 0). The game does the same for knockback
     * and the trigger volumes: Velocity.addInstruction, which PlayerVelocityInstructionSystem turns into a ChangeVelocity packet for
     * the player. Without a Velocity component the packet is written directly. The config is null (client defaults), as the game's
     * SetVelocityEffect does. Never throws.
     */
    private void sendCarryVelocity(Store<EntityStore> store, AirshipFlight f, double vx, double vz) {
        try {
            if (f.pilotRef == null || !f.pilotRef.isValid()) {
                return;
            }
            Velocity v = store.getComponent(f.pilotRef, Velocity.getComponentType());
            if (v != null) {
                v.addInstruction(new Vector3d(vx, 0, vz), null, ChangeVelocityType.Set);
            } else {
                PlayerRef pr = playerOf(store, f.pilotRef);
                if (pr != null) {
                    pr.getPacketHandler().writeNoCache(new ChangeVelocity((float) vx, 0f, (float) vz, ChangeVelocityType.Set, null));
                }
            }
            f.velActive = vx != 0 || vz != 0;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vitesse de portage du dirigeable non envoyée");
        }
    }

    /**
     * One tick of free flight with a pilot, same as the balloon's tick: the ship follows the pilot
     * (pilot position = pivot, offset 0) if the pose is free, else the pilot is teleported back to the last valid position.
     * When the heading changes the ship turns around the rotation centre C, not around the pilot: the pilot is carried along the
     * arc (carryDelta) by a velocity or by teleports. The ship pose is always (P world, theta) with P = C + R(theta)(P - C), so the
     * hull test, the lights and the entity use that pose. Returns false if nothing was moved this tick (paused, collision, stop).
     */
    private boolean pilotStep(Store<EntityStore> store, World world, AirshipFlight f, Hull hull, double dt, long now) {
        Ref<EntityStore> pilotRef = f.pilotRef;
        TransformComponent pt = store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (pt == null) {
            return false;
        }
        // The fly mode stays forced for the whole flight: only the lever (helper entity) or the land command ends it.
        Vector3d pp = new Vector3d(pt.getPosition());
        if (now < f.teleportUntilMs) {
            // Take-off or collision teleport in progress: the reported position is stale.
            f.carryAccX = 0;
            f.carryAccZ = 0;
            f.pilotEst = null;
            f.carryPauseUntilMs = 0;
            if (f.velActive) {
                sendCarryVelocity(store, f, 0, 0);
            }
            resetSample(f, pp, now);
            return false;
        }
        boolean carryPause = now < f.carryPauseUntilMs && f.pilotEst != null;
        if (carryPause) {
            // Just after a carry teleport the reported x and z are stale: use the destination.
            pp.set(f.pilotEst.x, pp.y, f.pilotEst.z);
        }
        double newTheta = headingStep(store, f, pp, dt, now);
        Carry mode = effectiveCarry(f);
        double dTheta = newTheta - f.theta;
        Vector3d d = mode == Carry.OFF || Math.abs(dTheta) < 1e-12 ? new Vector3d() : carryDelta(f.theta, newTheta);
        if (mode == Carry.TELEPORT) {
            f.carryAccX += d.x;
            f.carryAccZ += d.z;
        }
        Vector3d candidate = new Vector3d(pp);
        if (mode == Carry.VELOCITY) {
            candidate.add(d.x, 0, d.z);
        } else if (mode == Carry.TELEPORT) {
            candidate.add(f.carryAccX, 0, f.carryAccZ);
        }
        int allowed = f.blockedNow;
        boolean changed = candidate.distanceSquared(f.pos) > 1e-12 || Math.abs(dTheta) > 1e-9;
        if (changed && blocked(world, hull, candidate.x, candidate.y, candidate.z, newTheta, allowed) > allowed) {
            if (Math.abs(dTheta) > 1e-9 && blocked(world, hull, pp.x, pp.y, pp.z, f.theta, allowed) <= allowed) {
                // Only the new heading (with its carry) collides: keep the old heading, no carry this tick.
                f.yawRate = 0;
                newTheta = f.theta;
                if (mode == Carry.TELEPORT) {
                    f.carryAccX -= d.x;
                    f.carryAccZ -= d.z;
                }
                d.set(0, 0, 0);
                candidate.set(pp);
                if (mode == Carry.TELEPORT) {
                    candidate.add(f.carryAccX, 0, f.carryAccZ);
                }
            } else {
                collisionBack(store, world, f, pt, pp, newTheta, now);
                return false;
            }
        }
        // Delivering the carry.
        if (mode == Carry.VELOCITY) {
            f.sampleCarryX += d.x;
            f.sampleCarryZ += d.z;
            double len = Math.hypot(d.x, d.z);
            f.lastCarry = len;
            f.totalCarry += len;
            if (len > 0 && dt > 1e-4) {
                sendCarryVelocity(store, f, d.x / dt * carryGain, d.z / dt * carryGain);
            } else if (len == 0 && f.velActive) {
                sendCarryVelocity(store, f, 0, 0);
            }
        } else if (mode == Carry.TELEPORT) {
            double owed = Math.hypot(f.carryAccX, f.carryAccZ);
            boolean turning = d.x != 0 || d.z != 0;
            f.lastCarry = Math.hypot(d.x, d.z);
            if (!carryPause && (owed >= carryStep || (!turning && owed > CARRY_EPS))) {
                Vector3d target = new Vector3d(pp.x + f.carryAccX, pp.y, pp.z + f.carryAccZ);
                f.sampleCarryX += f.carryAccX;
                f.sampleCarryZ += f.carryAccZ;
                f.totalCarry += owed;
                f.carryTeleports++;
                f.carryAccX = 0;
                f.carryAccZ = 0;
                f.pilotEst = new Vector3d(target);
                f.carryPauseUntilMs = now + carryPauseMs;
                candidate.set(target);
                Rotation3f look = new Rotation3f(pt.getRotation());
                world.execute(() -> {
                    if (pilotRef.isValid()) {
                        store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
                    }
                });
            }
        } else {
            f.lastCarry = 0;
        }
        accept(world, f, hull, candidate, newTheta);
        writeEntity(store, f);
        return true;
    }

    /** The pose is blocked: the pilot goes back to the last valid position (balloon logic: pause, throttled log). */
    private void collisionBack(Store<EntityStore> store, World world, AirshipFlight f, TransformComponent pt, Vector3d pp,
                               double newTheta, long now) {
        f.collisionCount++;
        f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
        f.yawRate = 0;
        stopCarry(store, f);
        resetSample(f, f.pos, now);
        if (now - f.lastCollisionLogMs > BalloonManager.COLLISION_MSG_INTERVAL_MS) {
            f.lastCollisionLogMs = now;
            f.lastCollision = String.format(Locale.ROOT, "pos %.2f %.2f %.2f theta %.1f deg", pp.x, pp.y, pp.z, Math.toDegrees(newTheta));
            LOGGER.at(Level.INFO).log("Collision du dirigeable de %s : %s", f.pilotName, f.lastCollision);
        }
        Vector3d back = new Vector3d(f.lastValidPos);
        Rotation3f look = new Rotation3f(pt.getRotation());
        Ref<EntityStore> pilotRef = f.pilotRef;
        world.execute(() -> {
            if (pilotRef.isValid()) {
                store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(back, look));
            }
        });
    }

    private static void resetSample(AirshipFlight f, Vector3d p, long now) {
        f.sampleMs = now;
        f.sampleX = p.x;
        f.sampleZ = p.z;
        f.sampleCarryX = 0;
        f.sampleCarryZ = 0;
        f.hasTarget = false;
    }

    /**
     * Velocity carry effect check. The client sends only absolute positions, so the pilot's own flying cannot be told apart from the
     * carry. Heuristic: the own velocity measured in the last window without carry is assumed constant during the turn, and the
     * measured displacement beyond it is projected on the expected carry. After CARRY_CHECK_BLOCKS of expected carry, if less
     * than CARRY_MIN_RATIO of it was seen, the client ignored the velocity and this flight switches to the teleport carry. A pilot
     * who changes direction while the ship turns adds displacement along the carry, which biases the ratio upward: the check can
     * miss an ineffective velocity, it should not fire without reason.
     */
    private void checkCarryEffect(AirshipFlight f, double obsX, double obsZ, double carryX, double carryZ, double seconds) {
        double mag = Math.hypot(carryX, carryZ);
        if (mag < CARRY_EPS) {
            f.ownVx = obsX / seconds;
            f.ownVz = obsZ / seconds;
            f.chkExp = 0;
            f.chkRes = 0;
            return;
        }
        if (effectiveCarry(f) != Carry.VELOCITY) {
            return;
        }
        double rx = obsX - f.ownVx * seconds;
        double rz = obsZ - f.ownVz * seconds;
        f.chkExp += mag;
        f.chkRes += (rx * carryX + rz * carryZ) / mag;
        if (f.chkExp >= CARRY_CHECK_BLOCKS) {
            double ratio = f.chkRes / f.chkExp;
            f.chkRatio = ratio;
            if (ratio < CARRY_MIN_RATIO) {
                f.carryFallback = true;
                LOGGER.at(Level.INFO).log("Portage par vitesse du dirigeable de %s sans effet mesurable (%.2f de %.1f bloc(s) attendus) : "
                        + "portage par téléportation pour ce vol", f.pilotName, f.chkRes, f.chkExp);
            }
            f.chkExp = 0;
            f.chkRes = 0;
        }
    }

    /**
     * Heading controller. Measures the pilot's horizontal travel over SAMPLE_MS windows (ignored right after take-off and during
     * collision pauses), picks a target heading, then eases the yaw rate toward it (limited rate and acceleration, smooth stop).
     * The displacement made by the carry itself is removed from the measured travel. Returns the candidate heading for this tick
     * (the caller tests it against the scenery).
     */
    private double headingStep(Store<EntityStore> store, AirshipFlight f, Vector3d pp, double dt, long now) {
        double maxRate = Math.toRadians(maxYawRateDeg);
        double accel = Math.toRadians(yawAccelDeg);
        Steer mode = steer;
        if (f.sampleMs == 0 || now - f.takeoffMs < TAKEOFF_IGNORE_MS) {
            resetSample(f, pp, now);
        } else if (now - f.sampleMs >= SAMPLE_MS) {
            double obsX = pp.x - f.sampleX;
            double obsZ = pp.z - f.sampleZ;
            double seconds = (now - f.sampleMs) / 1000.0;
            double cx = f.sampleCarryX;
            double cz = f.sampleCarryZ;
            checkCarryEffect(f, obsX, obsZ, cx, cz, seconds);
            double dx = obsX - cx;
            double dz = obsZ - cz;
            f.travelSpeed = Math.hypot(dx, dz) / seconds;
            burnFuel(store, f, seconds);
            f.sampleMs = now;
            f.sampleX = pp.x;
            f.sampleZ = pp.z;
            f.sampleCarryX = 0;
            f.sampleCarryZ = 0;
            if (mode == Steer.TRAVEL) {
                f.hasTarget = false;
                f.reversing = false;
                if (f.travelSpeed > turnSpeedMin) {
                    // The bow points to (-sin theta, -cos theta), so a travel direction (dx, dz) is the heading atan2(-dx, -dz).
                    f.travelDir = Math.atan2(-dx, -dz);
                    double fromStern = AirshipMath.wrapPi(f.travelDir - (f.theta + Math.PI));
                    if (Math.abs(fromStern) < Math.toRadians(reverseConeDeg)) {
                        f.reversing = true;
                    } else {
                        f.targetHeading = f.travelDir;
                        f.hasTarget = true;
                    }
                }
            }
        }
        if (mode == Steer.LOOK) {
            double head = headYaw(store, f);
            f.hasTarget = !Double.isNaN(head);
            if (f.hasTarget) {
                f.targetHeading = head;
            }
        } else if (mode == Steer.OFF) {
            f.hasTarget = false;
        }
        double desired = 0;
        double step;
        if (f.hasTarget) {
            double err = AirshipMath.wrapPi(f.targetHeading - f.theta);
            desired = Math.signum(err) * Math.min(maxRate, Math.sqrt(2 * accel * Math.abs(err)));
            f.yawRate = AirshipMath.approach(f.yawRate, desired, accel * dt);
            step = f.yawRate * dt;
            if (Math.abs(step) > Math.abs(err)) {
                step = err;
                f.yawRate = 0;
            }
        } else {
            f.yawRate = AirshipMath.approach(f.yawRate, 0, accel * dt);
            step = f.yawRate * dt;
        }
        return f.theta + step;
    }

    /** Head yaw of the pilot: the component updated by SetHead, else the last yaw seen in the queue, else NaN. */
    private static double headYaw(Store<EntityStore> store, AirshipFlight f) {
        if (f.pilotRef != null && f.pilotRef.isValid()) {
            HeadRotation hr = store.getComponent(f.pilotRef, HeadRotation.getComponentType());
            if (hr != null) {
                return hr.getRotation().y;
            }
        }
        return f.input.headYaw;
    }

    /**
     * Tries the candidate pose, then position-only, then yaw-only (landing alignment, the ship is moved by the server). Returns
     * true if the full candidate was refused (a collision). Nothing is tested if the pose did not change.
     */
    private boolean move(World world, AirshipFlight f, Hull hull, Vector3d newPos, double newTheta, long now) {
        if (newPos.distanceSquared(f.pos) < 1e-12 && Math.abs(newTheta - f.theta) < 1e-9) {
            return false;
        }
        int allowed = f.blockedNow;
        if (blocked(world, hull, newPos.x, newPos.y, newPos.z, newTheta, allowed) <= allowed) {
            accept(world, f, hull, newPos, newTheta);
            return false;
        }
        f.collisionCount++;
        if (now - f.lastCollisionLogMs > BalloonManager.COLLISION_MSG_INTERVAL_MS) {
            f.lastCollisionLogMs = now;
            f.lastCollision = String.format(Locale.ROOT, "pos %.2f %.2f %.2f theta %.1f deg", newPos.x, newPos.y, newPos.z,
                    Math.toDegrees(newTheta));
            LOGGER.at(Level.INFO).log("Collision du dirigeable de %s : %s", f.pilotName, f.lastCollision);
        }
        if (Math.abs(newTheta - f.theta) > 1e-9 && blocked(world, hull, newPos.x, newPos.y, newPos.z, f.theta, allowed) <= allowed) {
            accept(world, f, hull, newPos, f.theta);
            return true;
        }
        if (newPos.distanceSquared(f.pos) > 1e-12 && blocked(world, hull, f.pos.x, f.pos.y, f.pos.z, newTheta, allowed) <= allowed) {
            accept(world, f, hull, new Vector3d(f.pos), newTheta);
            return true;
        }
        return true;
    }

    private void accept(World world, AirshipFlight f, Hull hull, Vector3d pos, double theta) {
        f.pos.set(pos);
        f.theta = theta;
        f.lastValidPos.set(pos);
        f.lastValidTheta = theta;
        f.lastValidPivotCell.set(f.pivotCell(pos));
        if (f.blockedNow > 0) {
            f.blockedNow = blocked(world, hull, pos.x, pos.y, pos.z, theta, Integer.MAX_VALUE);
        }
    }

    /** Overwrites the entity's position and heading (the client glues an attached entity to the pilot, this keeps the server in step). */
    private void writeEntity(Store<EntityStore> store, AirshipFlight f) {
        TransformComponent bt = store.getComponent(f.shipRef, TransformComponent.getComponentType());
        if (bt != null) {
            bt.setPosition(new Vector3d(f.pos));
            Rotation3f r = new Rotation3f();
            r.setYaw((float) visualYaw(f.theta));
            bt.setRotation(r);
        }
    }

    // ------------------------------------------------------------------ landing

    /** /orbishorizon airship land, the lever: starts the alignment. */
    Message requestLanding(UUID pilot) {
        AirshipFlight f = flights.get(pilot);
        if (f == null) {
            return Texts.t("airship.error.notPiloting");
        }
        if (f.landing) {
            return Texts.t("airship.landing.already");
        }
        startLanding(f, false);
        return null;
    }

    /**
     * Holds the pilot during the alignment: forced fly mode with flight speeds at 0 (restored when the flight resumes or ends)
     * and the ship entity taken off him, so that the server can move it. The pilot is teleported once, at the end.
     */
    private void holdPilot(AirshipFlight f) {
        Store<EntityStore> store = f.world.getEntityStore().getStore();
        detachShip(store, f);
        MovementManager mm = store.getComponent(f.pilotRef, MovementManager.getComponentType());
        PlayerRef pr = store.getComponent(f.pilotRef, PlayerRef.getComponentType());
        if (mm == null || pr == null) {
            return;
        }
        mm.getSettings().fly = FlyMode.Forced;
        mm.getSettings().horizontalFlySpeed = 0f;
        mm.getSettings().verticalFlySpeed = 0f;
        mm.update(pr.getPacketHandler());
    }

    private void startLanding(AirshipFlight f, boolean automatic) {
        // The pilot is held still for the alignment: no more carry. The alignment turns the ship around the pilot (he is frozen on
        // his deck), so C is simply re-derived from the pose afterwards.
        stopCarry(f.world.getEntityStore().getStore(), f);
        double deg = Math.floorMod((long) Math.round(Math.toDegrees(f.theta) / 90.0), 4L) * 90.0;
        f.targetRotation = Rotation.ofDegrees((int) deg);
        f.targetTheta = f.theta + AirshipMath.wrapPi(f.targetRotation.getRadians() - f.theta);
        f.targetPivot.set(f.pivotCell(f.pos));
        f.landing = true;
        f.automatic = automatic || f.automatic;
        // No propulsion during the alignment: back to the idle smoke.
        setThrust(f.world.getEntityStore().getStore(), f, false);
        f.landingStartMs = System.currentTimeMillis();
        f.landingStuckMs = 0;
        f.yawRate = 0;
        f.hasTarget = false;
        if (!f.pilotless() && f.pilotRef.isValid()) {
            holdPilot(f);
        }
        if (!f.pilotless() && !automatic && f.pilotRef.isValid()) {
            PlayerRef pr = playerOf(f.world.getEntityStore().getStore(), f.pilotRef);
            if (pr != null) {
                pr.sendMessage(Texts.t("airship.landing.start"));
            }
        }
        LOGGER.at(Level.INFO).log("Dirigeable de %s : alignement pour l'atterrissage, quart de tour %s, case du pivot %s%s", f.pilotName,
                f.targetRotation, f.targetPivot, automatic ? " (automatique)" : "");
    }

    private static PlayerRef playerOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        return ref != null && ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : null;
    }

    private void landingStep(Store<EntityStore> store, World world, AirshipFlight f, BalloonShape s, Hull hull, double dt, long now) {
        double dTheta = f.targetTheta - f.theta;
        Vector3d target = new Vector3d(f.targetPivot.x + 0.5, f.targetPivot.y, f.targetPivot.z + 0.5);
        Vector3d delta = target.sub(f.pos, new Vector3d());
        double len = delta.length();
        if (Math.abs(dTheta) < 1e-4 && len < 1e-3) {
            finalLanding(store, world, f, s);
            return;
        }
        double maxTheta = Math.toRadians(LANDING_YAW_RATE_DEG) * dt;
        double maxStep = LANDING_SPEED * dt;
        double newTheta = Math.abs(dTheta) <= maxTheta ? f.targetTheta : f.theta + Math.signum(dTheta) * maxTheta;
        Vector3d newPos = len <= maxStep ? new Vector3d(target) : new Vector3d(f.pos).add(delta.mul(maxStep / len));
        double before = Math.abs(dTheta) + len;
        move(world, f, hull, newPos, newTheta, now);
        double after = Math.abs(f.targetTheta - f.theta) + target.distance(f.pos);
        f.yawRate = 0;
        if (before - after < 1e-6) {
            f.landingStuckMs += (long) (dt * 1000);
            if (f.landingStuckMs >= LANDING_STUCK_MS) {
                abortLanding(store, f, "airship.landing.stuck");
            }
        } else {
            f.landingStuckMs = 0;
        }
    }

    private void finalLanding(Store<EntityStore> store, World world, AirshipFlight f, BalloonShape s) {
        Rotation q = f.targetRotation;
        Vector3f pv = pivot();
        Vector3i origin = new Vector3i(f.targetPivot)
                .sub(q.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
        if (BalloonManager.collides(world, s, origin, q)) {
            abortLanding(store, f, "airship.landing.noRoom");
            return;
        }
        // Exact pose before the blocks come back.
        f.theta = f.targetTheta;
        f.pos.set(f.targetPivot.x + 0.5, f.targetPivot.y, f.targetPivot.z + 0.5);
        finishLanding(store, f, origin, q);
    }

    private void abortLanding(Store<EntityStore> store, AirshipFlight f, String messageKey) {
        f.landing = false;
        f.landingStuckMs = 0;
        long now = System.currentTimeMillis();
        f.nextLandingRetryMs = now + LANDING_RETRY_MS;
        if (!f.pilotless()) {
            // Back to free flight: the entity is mounted on the pilot again, his flight speeds are restored and the
            // flight goes on (like the balloon when it cannot land).
            PlayerRef pr = playerOf(store, f.pilotRef);
            if (pr != null) {
                try {
                    BalloonManager manager = BalloonManager.get();
                    manager.setPilotFlying(store, f.pilotRef, pr, false, 1f);
                    manager.setPilotFlying(store, f.pilotRef, pr, true, flySpeedFactor);
                    if (f.dry) {
                        applyDrySpeed(store, f); // the engines are still empty: no horizontal travel
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Vol libre du pilote %s non rétabli", f.pilotName);
                }
                pr.sendMessage(Texts.t(messageKey));
            }
            attachShip(store, f);
            f.takeoffMs = now;
            f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
        }
        LOGGER.at(Level.INFO).log("Atterrissage du dirigeable de %s annulé (%s)%s", f.pilotName, messageKey,
                f.automatic ? ", nouvel essai dans 5 s" : "");
    }

    /**
     * Pastes the prefab back at this origin and rotation, restores the chests, dismounts the pilot and puts him next to the seat,
     * removes the entity and the lights, deletes the resume file. Used by every way of ending the flight.
     */
    private void finishLanding(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        if (flights.get(f.id) != f) {
            return;
        }
        f.finishing = true;
        flights.remove(f.id);
        stopCarry(store, f);
        removeLights(store, f);
        cancelSmoke(f);
        BalloonManager.get().pastePrefab(f.kind, f.world, store, origin, rotation, f.pilotUuid);
        BalloonManager.get().restoreCargo(f.world, store, f.cargo, origin, rotation);
        if (f.engines != null) {
            f.engines.restore(f.world, store, origin, rotation); // T57: fuel left and charcoal go back into the engines
        }
        if (f.benches != null) {
            f.benches.restore(f.world, store, origin, rotation); // T63: upgraded benches get their level back
        }
        releasePilot(store, f, origin, rotation);
        passengers.disembark(store, f, origin, rotation); // T64: each passenger stands next to their stool
        AirshipResume.delete(f.id);
        if (f.shipRef != null && f.shipRef.isValid()) {
            store.removeEntity(f.shipRef, RemoveReason.REMOVE);
        }
        LOGGER.at(Level.INFO).log("Atterrissage du dirigeable de %s : origine %s, rotation %s", f.pilotName, origin, rotation);
    }

    /** Restores the pilot's movement and puts him on the pivot cell of the placed prefab (free: no stool). Never throws. */
    private void releasePilot(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        if (f.pilotless() || !f.pilotRef.isValid()) {
            return;
        }
        try {
            Ref<EntityStore> ref = f.pilotRef;
            restorePilotMovement(store, ref);
            TransformComponent pt = store.getComponent(ref, TransformComponent.getComponentType());
            Rotation3f look = pt != null ? new Rotation3f(pt.getRotation()) : new Rotation3f();
            store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(pilotSpot(origin, rotation), look));
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            if (pr != null) {
                pr.sendMessage(Texts.t("airship.landed"));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilote %s non reposé proprement", f.pilotName);
        }
    }

    /** The pivot cell of the placed prefab, slightly above its floor: free since the stool was removed. */
    private static Vector3d pilotSpot(Vector3i origin, Rotation rotation) {
        Vector3f pv = pivot();
        Vector3i cell = rotation.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()).add(origin);
        return new Vector3d(cell.x + 0.5, cell.y + LANDING_LIFT, cell.z + 0.5);
    }

    /**
     * Landing without animation at the nearest quarter turn (pilot disconnect, shutdown, lost entity): the current origin, then
     * up to SEARCH_UP blocks upward. Returns false if nothing fits (the flight is kept, the resume file stays).
     */
    private boolean landImmediately(AirshipFlight f) {
        if (flights.get(f.id) != f || f.finishing) {
            return false;
        }
        Store<EntityStore> store = f.world.getEntityStore().getStore();
        try {
            BalloonShape s = f.kind.shape();
            Rotation q = nearestQuarter(f.theta);
            Vector3f pv = pivot();
            Vector3i pivotCell = f.pivotCell(f.pos);
            Vector3i base = new Vector3i(pivotCell)
                    .sub(q.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
            for (int up = 0; up <= SEARCH_UP; up++) {
                Vector3i origin = new Vector3i(base).add(0, up, 0);
                if (!BalloonManager.collides(f.world, s, origin, q)) {
                    f.theta = q.getRadians();
                    f.pos.set(pivotCell.x + 0.5, pivotCell.y + up, pivotCell.z + 0.5);
                    finishLanding(store, f, origin, q);
                    return true;
                }
            }
            LOGGER.at(Level.WARNING).log("Pose immédiate du dirigeable %s impossible : fichier de reprise gardé", f.id);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pose immédiate du dirigeable %s en échec", f.id);
        }
        return false;
    }

    private static Rotation nearestQuarter(double theta) {
        long q = Math.floorMod(Math.round(Math.toDegrees(theta) / 90.0), 4L);
        return Rotation.ofDegrees((int) (q * 90));
    }

    // ------------------------------------------------------------------ pilot's death, disconnect, shutdown

    /** Called by BalloonManager.onPlayerDeath (BalloonDeathSystem). */
    void onPlayerDeath(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (flights.isEmpty()) {
            return;
        }
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            for (AirshipFlight f : new ArrayList<>(flights.values())) {
                if (f.world != world) {
                    continue;
                }
                if (ref.equals(f.pilotRef)) {
                    pilotDied(store, f);
                }
                for (AirshipPassengers.Rider r : f.riders) {
                    if (ref.equals(r.p.ref)) {
                        passengers.releaseDead(store, f, r); // T64: the flight goes on, the player respawns normally
                        LOGGER.at(Level.INFO).log("Passager du dirigeable %s mort en vol : libéré", r.p.name);
                    }
                }
            }
        });
    }

    /**
     * The pilot died (mirrors the balloon's T39): the ship entity leaves him, his movement settings go back to normal, the ship
     * loses its pilot, its flight gets a new identifier (the respawned player can fly something else), the off model is shown
     * and the ship aligns itself and lands in place, retried every 5 s if impossible.
     */
    private void pilotDied(Store<EntityStore> store, AirshipFlight f) {
        if (f.pilotless() || flights.get(f.id) != f) {
            return;
        }
        Ref<EntityStore> ref = f.pilotRef;
        stopCarry(store, f);
        detachShip(store, f);
        restorePilotMovement(store, ref);
        UUID oldId = f.id;
        flights.remove(oldId);
        f.id = UUID.randomUUID();
        f.pilotRef = null;
        f.yawRate = 0;
        f.hasTarget = false;
        f.landing = false;
        f.automatic = true;
        f.nextLandingRetryMs = 0;
        showOffModel(store, f);
        AirshipPassengers.tell(store, f, "airship.passenger.pilotDied");
        flights.put(f.id, f);
        f.lastResumeMs = System.currentTimeMillis();
        AirshipResume.write(f, true);
        AirshipResume.delete(oldId);
        LOGGER.at(Level.INFO).log("Pilote %s mort en vol : dirigeable sans pilote, alignement puis atterrissage automatique", f.pilotName);
    }

    private void showOffModel(Store<EntityStore> store, AirshipFlight f) {
        if (f.shipRef == null || !f.shipRef.isValid()) {
            return;
        }
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(spec().offModelId);
        if (asset != null) {
            store.putComponent(f.shipRef, ModelComponent.getComponentType(), new ModelComponent(Model.createUnitScaleModel(asset)));
            f.modelId = spec().offModelId;
            f.off = true;
            f.thrust = false;
        } else {
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable : le modèle allumé reste affiché", spec().offModelId);
        }
        setFireboxColor(store, f, null); // T58: the dark firebox of the off model has no light
        cancelSmoke(f);
    }

    /**
     * PlayerDisconnectEvent: a pilot puts the ship down (onPilotGone), a passenger is released and saved on the ground under their
     * stool (T64), the flight goes on.
     */
    void onPlayerGone(UUID uuid) {
        onPilotGone(uuid);
        for (AirshipFlight f : new ArrayList<>(flights.values())) {
            for (AirshipPassengers.Rider r : f.riders) {
                if (!r.p.uuid.equals(uuid)) {
                    continue;
                }
                boolean ok = BalloonManager.runOnWorld(f.world,
                        () -> passengers.dropOne(f.world.getEntityStore().getStore(), f, r), LAND_WAIT_MS);
                LOGGER.at(ok ? Level.INFO : Level.WARNING).log(ok
                        ? "Passager du dirigeable %s parti : libéré, position enregistrée au sol"
                        : "Passager du dirigeable %s parti : libération impossible", r.p.name);
            }
        }
    }

    /** PlayerDisconnectEvent: the world still runs and the player is still in it, the ship is put down at once. */
    void onPilotGone(UUID pilotUuid) {
        AirshipFlight f = flights.get(pilotUuid);
        if (f == null) {
            return;
        }
        boolean ok = BalloonManager.runOnWorld(f.world, () -> landImmediately(f), LAND_WAIT_MS);
        LOGGER.at(ok ? Level.INFO : Level.WARNING).log(ok
                ? "Pilote %s parti : dirigeable reposé"
                : "Pilote %s parti : pose impossible, le fichier de reprise le reposera au prochain chargement du monde", pilotUuid);
    }

    /** ShutdownEvent (-50) and plugin shutdown: every ship in flight is put down at once. */
    void landAll() {
        for (AirshipFlight f : new ArrayList<>(flights.values())) {
            boolean ok = BalloonManager.runOnWorld(f.world, () -> landImmediately(f), LAND_WAIT_MS);
            if (!ok) {
                LOGGER.at(Level.WARNING).log("Dirigeable %s non reposé : fichier de reprise gardé", f.id);
            }
        }
    }

    // ------------------------------------------------------------------ resume (world start)

    void recoverWorld(World world) {
        for (AirshipResume.Record rec : AirshipResume.loadAll(world.getName())) {
            if (flights.containsKey(rec.id()) || !recovering.add(rec.id())) {
                continue;
            }
            try {
                List<CompletableFuture<?>> loads = new ArrayList<>();
                for (int cx = ChunkUtil.chunkCoordinate(rec.pivotX() - 16); cx <= ChunkUtil.chunkCoordinate(rec.pivotX() + 16); cx++) {
                    for (int cz = ChunkUtil.chunkCoordinate(rec.pivotZ() - 16); cz <= ChunkUtil.chunkCoordinate(rec.pivotZ() + 16); cz++) {
                        loads.add(world.getChunkAsync(ChunkUtil.indexChunk(cx, cz)));
                    }
                }
                LOGGER.at(Level.INFO).log("Reprise du vol de dirigeable %s dans %s : pivot (%d, %d, %d), %d chunks à charger",
                        rec.id(), world.getName(), rec.pivotX(), rec.pivotY(), rec.pivotZ(), loads.size());
                CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, t) -> {
                    if (t != null) {
                        LOGGER.at(Level.WARNING).withCause(t).log("Chargement des chunks de la reprise impossible pour %s", rec.id());
                    }
                    try {
                        world.execute(() -> recoverOne(world, rec));
                    } catch (RuntimeException e) {
                        recovering.remove(rec.id());
                        LOGGER.at(Level.WARNING).withCause(e).log("Monde %s arrêté : reprise de %s remise au prochain chargement",
                                world.getName(), rec.id());
                    }
                });
            } catch (RuntimeException e) {
                recovering.remove(rec.id());
                LOGGER.at(Level.SEVERE).withCause(e).log("Reprise du vol de dirigeable %s impossible : fichier gardé", rec.id());
            }
        }
    }

    private void recoverOne(World world, AirshipResume.Record rec) {
        try {
            if (!AirshipResume.exists(rec.id())) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            BalloonShape s = Deployables.AIRSHIP.shape();
            if (rec.ship() != null) {
                Ref<EntityStore> orphan = world.getEntityStore().getRefFromUUID(rec.ship());
                if (orphan != null && orphan.isValid()) {
                    store.removeEntity(orphan, RemoveReason.REMOVE);
                    LOGGER.at(Level.INFO).log("Entité du dirigeable restée dans le monde supprimée (%s)", rec.ship());
                }
            }
            BalloonLights.removeOrphans(world, store, rec.ship(), s);
            if (rec.ship() != null) {
                Ref<EntityStore> lever = world.getEntityStore().getRefFromUUID(AirshipLever.uuidFor(rec.ship()));
                if (lever != null && lever.isValid()) {
                    store.removeEntity(lever, RemoveReason.REMOVE);
                }
            }
            // If the world was saved before take-off, the old ship and its contents are still there: the file is authoritative.
            removeOldShip(world, s, rec);
            Rotation q = nearestQuarter(rec.heading());
            Vector3f pv = pivot();
            Vector3i base = new Vector3i(rec.pivotX(), rec.pivotY(), rec.pivotZ())
                    .sub(q.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
            Vector3i origin = new Vector3i(base);
            for (int up = 0; up <= SEARCH_UP; up++) {
                Vector3i o = new Vector3i(base).add(0, up, 0);
                if (!BalloonManager.collides(world, s, o, q)) {
                    origin = o;
                    break;
                }
            }
            BalloonManager.get().pastePrefab(Deployables.AIRSHIP, world, store, origin, q, rec.pilot());
            BalloonManager.get().restoreCargo(world, store, rec.cargo(), origin, q);
            if (rec.engines() != null) {
                rec.engines().restore(world, store, origin, q); // T57: absent in older resume files
            }
            if (rec.benches() != null) {
                rec.benches().restore(world, store, origin, q); // T63: absent in older resume files
            }
            if (rec.pilot() != null) {
                restorePilot(world, store, s, rec.pilot(), origin, q);
            }
            if (rec.riders() != null) {
                AirshipPassengers.restoreSaved(world, store, s, rec.riders(), origin, q); // T64: absent in older resume files
            }
            AirshipResume.delete(rec.id());
            LOGGER.at(Level.INFO).log("Dirigeable %s reposé en %s (reprise après arrêt en vol)", rec.id(), origin);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Reprise du dirigeable %s en échec : fichier gardé pour un prochain essai", rec.id());
        } finally {
            recovering.remove(rec.id());
        }
    }

    /** Removes the ship still standing at its take-off place (world saved before the removal), contents emptied first. */
    private void removeOldShip(World world, BalloonShape s, AirshipResume.Record rec) {
        // The take-off origin is not stored in the file: the old ship, if any, is recognised from the take-off anchor kept in rec.
        // (Nothing to do when the file has no take-off data.)
        if (rec.takeoffOrigin() == null || rec.takeoffRotation() == null) {
            return;
        }
        Vector3i o = rec.takeoffOrigin();
        Rotation r = rec.takeoffRotation();
        Vector3i anchor = s.anchor().rotated(r).add(o);
        BlockType bt = world.getBlockType(anchor.x, anchor.y, anchor.z);
        if (bt != null && BalloonManager.sameBlock(bt, s.anchor())) {
            BalloonCargo.takeFrom(world, s, o, r);
            AirshipEngines.takeFrom(world, s, o, r); // emptied: the engine contents of the file are authoritative (T57)
            if (rec.benches() != null) {
                AirshipBenches.takeFrom(world, s, o, r); // upgrade items cleared: the levels of the file are authoritative (T63)
            }
            BalloonRegistry.remove(world, o, r, Deployables.AIRSHIP_KIND);
            int n = BalloonManager.removeBlocks(world, s, o, r, true);
            LOGGER.at(Level.INFO).log("Ancien dirigeable resté au point de décollage retiré (%d blocs)", n);
        }
    }

    private void restorePilot(World world, Store<EntityStore> store, BalloonShape s, UUID pilot, Vector3i origin, Rotation q) {
        Vector3d spot = pilotSpot(origin, q);
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> ref = pr.getReference();
            if (pr.getUuid().equals(pilot) && ref != null && ref.isValid()) {
                TransformComponent pt = store.getComponent(ref, TransformComponent.getComponentType());
                Rotation3f look = pt != null ? new Rotation3f(pt.getRotation()) : new Rotation3f();
                store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(spot, look));
                return;
            }
        }
        // Offline: put down on his next arrival (BalloonManager.onPlayerAdded reads the same "stranded" files).
        BalloonResume.writeStranded(pilot, world.getName(), spot.x, spot.y, spot.z);
    }

    // ------------------------------------------------------------------ removal by an administrator

    /** /orbishorizon airship despawn: the nearest airship within DESPAWN_RADIUS, placed or in flight. World thread. */
    BalloonManager.DespawnResult despawn(Store<EntityStore> store, Ref<EntityStore> adminRef, PlayerRef admin, World world) {
        BalloonShape s;
        try {
            s = Deployables.AIRSHIP.shape();
        } catch (IOException e) {
            return new BalloonManager.DespawnResult(false, Texts.t("airship.error.prefabUnreadable").param("error", String.valueOf(e.getMessage())));
        }
        TransformComponent at = store.getComponent(adminRef, TransformComponent.getComponentType());
        if (at == null) {
            return new BalloonManager.DespawnResult(false, Texts.t("error.noPosition"));
        }
        Vector3d here = new Vector3d(at.getPosition());

        AirshipFlight flight = null;
        double flightDist = Double.MAX_VALUE;
        for (AirshipFlight f : flights.values()) {
            if (f.world != world) {
                continue;
            }
            double d = admin.getUuid().equals(f.pilotUuid) ? 0 : f.pos.distance(here);
            if (d <= DESPAWN_RADIUS && d < flightDist) {
                flight = f;
                flightDist = d;
            }
        }
        Vector3i posedOrigin = null;
        Rotation posedRotation = null;
        double posedDist = Double.MAX_VALUE;
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.AIRSHIP_KIND)) {
            Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
            Vector3i anchor = s.anchor().rotated(e.rotation()).add(origin);
            BlockType bt = world.getBlockType(anchor.x, anchor.y, anchor.z);
            if (bt == null || !BalloonManager.sameBlock(bt, s.anchor())) {
                continue;
            }
            double d = new Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5).distance(here);
            if (d <= DESPAWN_RADIUS && d < posedDist) {
                posedOrigin = origin;
                posedRotation = e.rotation();
                posedDist = d;
            }
        }
        if (posedOrigin == null) {
            BalloonManager.Found found = BalloonManager.get().find(world, here, List.of(Deployables.AIRSHIP));
            if (found != null) {
                posedOrigin = found.origin();
                posedRotation = found.rotation();
                Vector3i anchor = s.anchor().rotated(found.rotation()).add(found.origin());
                posedDist = new Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5).distance(here);
            }
        }
        if (flight == null && posedOrigin == null) {
            return new BalloonManager.DespawnResult(false, Texts.t("airship.despawn.none").param("radius", (int) DESPAWN_RADIUS));
        }
        Message error;
        if (flight != null && (posedOrigin == null || flightDist <= posedDist)) {
            error = despawnFlight(store, flight, admin, here);
        } else {
            error = despawnPosed(store, world, s, posedOrigin, posedRotation, admin);
        }
        if (error != null) {
            return new BalloonManager.DespawnResult(false, error);
        }
        boolean given = BalloonManager.get().giveCrate(store, adminRef, here, spec().crateItemId);
        return new BalloonManager.DespawnResult(true, Texts.t(given ? "airship.despawn.done" : "airship.despawn.doneInventoryFull"));
    }

    private Message despawnPosed(Store<EntityStore> store, World world, BalloonShape s, Vector3i origin, Rotation rotation, PlayerRef admin) {
        // Players aboard lose their floor: put on the ground afterwards.
        List<Ref<EntityStore>> inside = new ArrayList<>();
        List<Vector3d> insidePos = new ArrayList<>();
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> ref = pr.getReference();
            TransformComponent t = ref != null && ref.isValid() ? store.getComponent(ref, TransformComponent.getComponentType()) : null;
            if (t != null && insideShape(s, origin, rotation, t.getPosition())) {
                inside.add(ref);
                insidePos.add(new Vector3d(t.getPosition()));
            }
        }
        StructureRemoval.Result result = StructureRemoval.remove(store, world, Deployables.AIRSHIP, s, origin, rotation, true);
        if (result.error() != null) {
            return result.error();
        }
        for (int i = 0; i < inside.size(); i++) {
            try {
                BalloonManager.putOnGround(store, world, inside.get(i), insidePos.get(i));
                PlayerRef pr = store.getComponent(inside.get(i), PlayerRef.getComponentType());
                if (pr != null && !pr.getUuid().equals(admin.getUuid())) {
                    pr.sendMessage(Texts.t("airship.despawn.removedByAdmin"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Joueur du dirigeable non posé au sol");
            }
        }
        LOGGER.at(Level.INFO).log("Retrait par %s : dirigeable posé en %s (rotation %s), %d blocs retirés, %d objet(s) lâchés",
                admin.getUsername(), origin, rotation, result.removed(), result.items());
        return null;
    }

    private Message despawnFlight(Store<EntityStore> store, AirshipFlight f, PlayerRef admin, Vector3d adminPos) {
        if (flights.get(f.id) != f) {
            return Texts.t("airship.despawn.alreadyLanding");
        }
        f.finishing = true;
        flights.remove(f.id);
        stopCarry(store, f);
        removeLights(store, f);
        cancelSmoke(f);
        World world = f.world;
        Vector3d pos = new Vector3d(f.pos);
        passengers.dropAll(store, f, admin); // T64: passengers released and put on the ground under their stool
        if (!f.pilotless() && f.pilotRef.isValid()) {
            try {
                restorePilotMovement(store, f.pilotRef);
                if (!BalloonManager.isDead(store, f.pilotRef)) {
                    BalloonManager.putOnGround(store, world, f.pilotRef, pos);
                }
                PlayerRef pp = store.getComponent(f.pilotRef, PlayerRef.getComponentType());
                if (pp != null && !pp.getUuid().equals(admin.getUuid())) {
                    pp.sendMessage(Texts.t("airship.despawn.removedByAdmin"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Pilote non remis au sol proprement");
            }
        }
        Vector3d ground = BalloonManager.groundBelow(world, pos);
        Vector3d where = ground.y != pos.y ? ground : adminPos;
        Vector3i dropPos = new Vector3i((int) Math.floor(where.x), (int) Math.floor(where.y), (int) Math.floor(where.z));
        int items = 0;
        if (f.cargo != null) {
            items += BalloonManager.dropAndCount(store, f.cargo.takeAllItems(), dropPos);
        }
        if (f.engines != null) {
            items += BalloonManager.dropAndCount(store, f.engines.takeAllItems(), dropPos);
        }
        if (f.benches != null) {
            // T63: the upgrade items are refunded, as when a placed bench is broken.
            items += BalloonManager.dropAndCount(store, f.benches.takeAllItems(), dropPos);
        }
        AirshipResume.delete(f.id);
        if (f.shipRef != null && f.shipRef.isValid()) {
            store.removeEntity(f.shipRef, RemoveReason.REMOVE);
        }
        LOGGER.at(Level.INFO).log("Retrait par %s : dirigeable en vol en %s, %d objet(s) lâchés", admin.getUsername(), fmt(pos), items);
        return null;
    }

    // ------------------------------------------------------------------ diagnostics

    static String fmt(Vector3d v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** English diagnostic text about the player's flight, or a short line if none. */
    String debug(Store<EntityStore> store, PlayerRef player) {
        AirshipFlight f = flights.get(player.getUuid());
        String tuning = String.format(Locale.ROOT,
                "steer %s, yawSign %+.0f, maxYawRate %.1f deg/s, yawAccel %.1f deg/s2, turnSpeedMin %.2f b/s, reverseCone %.0f deg, "
                        + "flySpeedFactor %.2f, rotation centre pivotZ %.2f (prefab z, -15 bow, 15 stern), carry %s, carryGain %.2f, "
                        + "carryStep %.2f, carryPauseMs %d, leverIntangible %s, fuelFactor %.2f, fuelMoveMin %.2f",
                steer, yawSign, maxYawRateDeg, yawAccelDeg, turnSpeedMin, reverseConeDeg, flySpeedFactor, rotationCenterZ, carry,
                carryGain, carryStep, carryPauseMs, AirshipLever.intangible, fuelFactor, fuelMoveMin) + ", " + passengers.tuning();
        if (f == null) {
            for (AirshipFlight g : flights.values()) {
                for (AirshipPassengers.Rider r : g.riders) {
                    if (r.p.uuid.equals(player.getUuid())) {
                        return "Passenger of airship " + g.id + ": " + passengers.describe(store, g) + " | " + tuning;
                    }
                }
            }
            return "No airship flight in progress. " + tuning;
        }
        TransformComponent bt = f.shipRef != null && f.shipRef.isValid()
                ? store.getComponent(f.shipRef, TransformComponent.getComponentType()) : null;
        TransformComponent pt = f.pilotRef != null && f.pilotRef.isValid()
                ? store.getComponent(f.pilotRef, TransformComponent.getComponentType()) : null;
        double head = headYaw(store, f);
        Vector3d cw = centerWorld(f.pos, f.theta);
        String carryText = String.format(Locale.ROOT,
                "rotation centre C %s (P - C = %.1f blocks), carry mode %s%s, carry this tick %.3f b, total carried %.2f b, "
                        + "carry teleports %d, owed %.2f b, velocity sent %s, effect ratio %s, own velocity %.2f,%.2f b/s, lever helper %s",
                fmt(cw), Math.abs(pivot().z - rotationCenterZ), effectiveCarry(f), f.carryFallback ? " (velocity judged ineffective, fallback)" : "",
                f.lastCarry, f.totalCarry, f.carryTeleports, Math.hypot(f.carryAccX, f.carryAccZ), f.velActive,
                Double.isNaN(f.chkRatio) ? "n/a" : String.format(Locale.ROOT, "%.2f", f.chkRatio), f.ownVx, f.ownVz,
                f.lever != null && f.lever.ref != null && f.lever.ref.isValid() ? "present" : "absent");
        return String.format(Locale.ROOT,
                "Airship %s: theta %.1f deg (%.3f rad), yaw rate %.1f deg/s, travel speed %.2f b/s, travel direction %s%s, "
                        + "target heading %s, head yaw %s deg, ship %s, pos %s, pivot cell %s, entity %s yaw %s, pilot %s, "
                        + "model %s, collisions %d (last: %s), blocked samples now %d, lights %d, chest items %d, | %s | %s",
                f.id, Math.toDegrees(f.theta), f.theta, Math.toDegrees(f.yawRate), f.travelSpeed,
                Double.isNaN(f.travelDir) ? "none" : String.format(Locale.ROOT, "%.1f deg", Math.toDegrees(f.travelDir)),
                f.reversing ? " (reversing, no rotation)" : "",
                f.hasTarget ? String.format(Locale.ROOT, "%.1f deg", Math.toDegrees(f.targetHeading)) : "none",
                Double.isNaN(head) ? "none" : String.format(Locale.ROOT, "%.1f", Math.toDegrees(head)),
                f.landing ? "aligning for landing" + (f.automatic ? " (automatic)" : "") : f.attached ? "attached to the pilot" : "detached",
                fmt(f.pos), f.pivotCell(f.pos), bt != null ? fmt(bt.getPosition()) : "missing",
                bt != null ? String.valueOf(bt.getRotation().y) : "?",
                pt != null ? fmt(pt.getPosition()) : (f.pilotless() ? "none (pilotless)" : "?"),
                f.modelId, f.collisionCount, f.lastCollision, f.blockedNow, f.lights.size(),
                f.cargo != null ? f.cargo.itemCount() : 0,
                carryText + ", " + fuelDebug(f) + ", upgraded benches " + (f.benches != null ? f.benches.describe() : "none")
                        + ", passengers " + passengers.describe(store, f), tuning);
    }

    /** Fuel part of the debug text (English): burn seconds and travel seconds left, burning or not, dry. */
    private String fuelDebug(AirshipFlight f) {
        if (f.engines == null) {
            return "fuel: no engine data";
        }
        return String.format(Locale.ROOT, "fuel: %d engine(s), %.0f burn s left = %.0f s of travel (fuelFactor %.2f), %d fuel item(s), "
                        + "%s, %s, travel speed %.2f b/s (burns above %.2f)", f.engines.entryCount(), f.engines.remainingSeconds(),
                travelSecondsLeft(f), fuelFactor, f.engines.fuelItems(), f.burning ? "burning" : "not burning",
                f.dry ? "DRY (horizontal speed 0)" : "fuelled", f.travelSpeed, fuelMoveMin)
                + (f.thrust ? ", burner flames on" : ", burner flames off") + fireboxDebug(f);
    }

    /** Firebox light part of the debug text (English, T58). */
    private static String fireboxDebug(AirshipFlight f) {
        for (BalloonLights.Light l : f.lights) {
            if (BalloonLights.KEY_FIREBOX.equals(l.key)) {
                ColorLight c = l.color;
                return String.format(Locale.ROOT, ", firebox light %s, flicker level %.2f", c == null ? "out"
                        : "(" + c.red + " " + c.green + " " + c.blue + ")", f.fireboxLevel);
            }
        }
        return ", no firebox light";
    }

    /** Sets a tuning parameter by name (case-insensitive), value as text (a number, or velocity|teleport|off for carry). */
    String tune(String name, String text) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.equals("carry")) {
            for (Carry c : Carry.values()) {
                if (c.name().equalsIgnoreCase(text)) {
                    carry = c;
                    return "carry = " + c + (c == Carry.OFF ? " (the ship turns around the pilot)" : "");
                }
            }
            return "carry takes velocity, teleport or off. Current: " + carry;
        }
        if (key.equals("passengers")) {
            for (AirshipPassengers.Mode m : AirshipPassengers.Mode.values()) {
                if (m.name().equalsIgnoreCase(text)) {
                    passengers.mode = m;
                    return "passengers = " + m + " (next take-off)";
                }
            }
            return "passengers takes follow, mount or teleport. Current: " + passengers.mode;
        }
        double value;
        try {
            value = Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            return "Not a number: " + text;
        }
        switch (key) {
            case "maxyawrate" -> maxYawRateDeg = Math.max(0, value);
            case "yawaccel" -> yawAccelDeg = Math.max(0.1, value);
            case "turnspeedmin" -> turnSpeedMin = Math.max(0, value);
            case "reversecone" -> reverseConeDeg = Math.max(0, Math.min(180, value));
            case "flyspeedfactor" -> flySpeedFactor = (float) Math.max(0.05, value);
            case "pivotz" -> rotationCenterZ = Math.max(-15, Math.min(15, value));
            case "carrygain" -> carryGain = Math.max(0, value);
            case "carrystep" -> carryStep = Math.max(0.02, value);
            case "carrypausems" -> carryPauseMs = Math.max(0, Math.round(value));
            case "leverintangible" -> AirshipLever.intangible = value != 0;
            case "fuelfactor" -> fuelFactor = Math.max(0.01, value);
            case "fuelmovemin" -> fuelMoveMin = Math.max(0, value);
            case "passengergain" -> passengers.gain = Math.max(0, value);
            case "passengermaxcorrection" -> passengers.maxCorrection = Math.max(0, value);
            case "passengersnap" -> passengers.snap = Math.max(0.3, value);
            default -> {
                return "Unknown parameter " + name + ". Parameters: maxYawRate (deg/s), yawAccel (deg/s2), turnSpeedMin (b/s), "
                        + "reverseCone (deg), flySpeedFactor (applies at the next take-off), pivotZ (rotation centre, prefab z from -15 "
                        + "bow to 15 stern, default 7, 0 = middle), carry (velocity|teleport|off), carryGain, carryStep (blocks), "
                        + "carryPauseMs, leverIntangible (0|1, next take-off), fuelFactor (burn seconds per second of travel, default 2), "
                        + "fuelMoveMin (b/s of horizontal travel above which fuel burns, default 0.5), passengers (follow|mount|teleport, "
                        + "next take-off), passengerGain (b/s per block toward the seat, default 2), passengerMaxCorrection (b/s, "
                        + "default 4), passengerSnap (blocks from the seat before a teleport, default 1.25).";
            }
        }
        return String.format(Locale.ROOT, "%s = %.3f", name, value);
    }

    /** Parses /orbishorizon airship steer: travel, look or off. Returns null if the name is unknown. */
    Steer parseSteer(String name) {
        for (Steer s : Steer.values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    double flipYawSign() {
        yawSign = -yawSign;
        return yawSign;
    }

    // ------------------------------------------------------------------ input watch (/orbishorizon airship input)

    /** A player whose raw input is logged for WATCH_MS. */
    static final class Watch {
        final PlayerRef player;
        final long startMs;
        final long untilMs;
        long lastSendMs;
        String lastText = "";
        final StringBuilder pending = new StringBuilder();
        int samples;

        Watch(PlayerRef player, long now) {
            this.player = player;
            this.startMs = now;
            this.untilMs = now + WATCH_MS;
        }
    }

    Watch watchOf(UUID uuid) {
        return watches.get(uuid);
    }

    void startWatch(PlayerRef player) {
        watches.put(player.getUuid(), new Watch(player, System.currentTimeMillis()));
    }

    /** Called by AirshipInputSystem with the raw updates seen in a player's queue this tick. */
    void watchSample(Watch w, PlayerRef pr, String text, int mountId, long now) {
        if (text.equals(w.lastText)) {
            return;
        }
        w.lastText = text;
        w.samples++;
        String line = String.format(Locale.ROOT, "[+%.1fs mountId=%d]%s", (now - w.startMs) / 1000.0, mountId, text);
        LOGGER.at(Level.INFO).log("Entrée de %s : %s", pr.getUsername(), line);
        w.pending.append(line).append('\n');
        if (now - w.lastSendMs >= 300) {
            flushWatch(w, now);
        }
    }

    private void flushWatch(Watch w, long now) {
        if (w.pending.length() > 0) {
            w.player.sendMessage(Message.raw(w.pending.toString().trim()));
            w.pending.setLength(0);
        }
        w.lastSendMs = now;
    }

    private void expireWatches() {
        if (watches.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Watch> e : new ArrayList<>(watches.entrySet())) {
            Watch w = e.getValue();
            if (now >= w.untilMs) {
                flushWatch(w, now);
                w.player.sendMessage(Message.raw("Input watch over: " + w.samples + " distinct sample(s) logged."));
                watches.remove(e.getKey());
            }
        }
    }
}
