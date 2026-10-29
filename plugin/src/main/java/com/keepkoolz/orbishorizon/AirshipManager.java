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
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
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
 * - the pilot flies freely (forced fly mode, flight speeds halved, helm or land command to stop) and the ship entity is mounted ON the
 *   pilot (MountedComponent, Minecart controller, offset 0, pivot under the pilot). The pilot is not mounted and not seated.
 *   (A rider mounted on the entity has his own client drive it like a minecart, so that was dropped.)
 * - every tick the ship takes the pilot's position. Collisions are tested for any angle against the "hull" cells of the prefab;
 *   on a collision the pilot is teleported back to the last valid position, like the balloon.
 * - the heading theta (server side) eases toward the direction the pilot travels, or toward his head yaw, with a limited turn
 *   rate. A new heading that would collide is refused.
 * - stopping (helm, command) aligns the ship to the nearest quarter turn and cell, then pastes the prefab again.
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
    /** Factor applied to the pilot's flight speeds at the next take-off (the balloon uses 0.6). */
    volatile float flySpeedFactor = 0.6f;
    volatile Steer steer = Steer.TRAVEL;
    /**
     * Smoothness. yawJerk (deg/s3, 0 = off: linear rate ramp as before) limits the change of the yaw acceleration, so the turn
     * starts and stops with rounded corners. retrySmaller: a heading step refused by the hull test is retried at half and a quarter
     * (the rate is scaled down) before falling back to "keep the old heading, rate 0". thrustModels: 2 = all burner models, 1 = turns use
     * the FORWARD model (no LEFT/RIGHT swaps), 0 = never swap the model in flight. swapMinMs: minimum time between two model swaps.
     * observerSmoothMs: time constant of the observer entity position smoothing (0 = raw copy).
     */
    volatile double yawJerkDeg = 60;
    volatile boolean retrySmaller = true;
    volatile int thrustModels = 2;
    volatile long swapMinMs = 800;
    volatile double observerSmoothMs = 60;
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
    /** Z (prefab frame, x = 0) of the rotation centre C: -15 is the bow, 15 the stern. 0 = middle (default), 7 = start of the rear quarter. */
    volatile double rotationCenterZ = 0;
    /** Velocity carry: factor on the velocity sent (in case the client applies it more or less strongly). */
    volatile double carryGain = 1.0;
    /** Teleport carry: smallest displacement (blocks) that triggers a teleport. */
    volatile double carryStep = 0.3;
    /** Teleport carry: time (ms) the pilot's reported position is not trusted after a carry teleport. */
    volatile long carryPauseMs = 100;
    /**
     * Fuel. The ship burns fuel only while it moves horizontally: the pilot's horizontal travel speed (measured like the
     * heading samples, carry excluded) must be above fuelMoveMin (b/s). The burn time is the sample length times fuelFactor.
     */
    volatile double fuelFactor = 2;
    volatile double fuelMoveMin = 0.5;
    /** Turning also burns fuel: mean absolute turn rate (deg/s) over a sample window above which the ship counts as burning. */
    volatile double turnFuelMinDeg = 0.2;
    /**
     * Rest turn (Steer.TRAVEL): while the pilot is not travelling the ship turns toward his head yaw. Starts after the head stayed
     * more than restDeadZoneDeg away from the heading for restHoldMs, stops when within restStopDeg.
     */
    volatile boolean restTurn = true;
    volatile double restDeadZoneDeg = 25;
    volatile long restHoldMs = 500;
    volatile double restStopDeg = 3;
    /** Thrust state thresholds on the yaw rate (deg/s): a turn state is entered at or above ON and left below OFF. */
    private static final double TURN_FLAME_ON_DEG = 2;
    private static final double TURN_FLAME_OFF_DEG = 1;
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
    /** Light entities in flight: 17 lit blocks and the firebox since the deck lanterns (it was 16). */
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
    private final Map<StructureShape, Hull> hulls = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> particleIdsByBlock = new ConcurrentHashMap<>();
    /** Passengers seated on the ship's stools. */
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

    /** The voyages of this world seen by the captain's map. */
    List<CaptainMap.VoyageView> voyageViews(World world) {
        List<CaptainMap.VoyageView> out = new ArrayList<>();
        for (AirshipFlight f : flights()) {
            if (f.world != world || f.voyage == null) {
                continue;
            }
            UUID captain = f.voyage.captain;
            Ref<EntityStore> ref = null;
            for (AirshipPassengers.Rider r : f.riders) {
                if (r.p.uuid.equals(captain)) {
                    ref = r.p.ref;
                }
            }
            out.add(new CaptainMap.VoyageView(captain, ref, f.pos, f.crew, f.voyage.ridersReleased,
                    () -> AirshipAutopilot.stop(f, CaptainMap.STOP_KEY)));
        }
        return out;
    }

    /** The flights in progress. */
    Iterable<AirshipFlight> flights() {
        return flights.values();
    }

    boolean needsInput() {
        return !flights.isEmpty() || !watches.isEmpty();
    }

    /** Airship spec of a vehicle type handled by this manager (see {@link Deployables#SHIPS}). */
    static Deployables.AirshipSpec spec(Deployables.Kind kind) {
        if (kind == null || kind.airship == null) {
            throw new IllegalArgumentException("not a ship kind: " + (kind == null ? "null" : kind.id));
        }
        return kind.airship;
    }

    static Vector3f pivot(Deployables.Kind kind) {
        return spec(kind).pivot;
    }

    private double visualYaw(double theta) {
        return yawSign * theta + BalloonManager.MODEL_YAW_CORRECTION;
    }

    /** Collision hull of a shape: cell centres relative to the pivot (prefab frame, before rotation). */
    static final class Hull {
        final double[] dx;
        final double[] dy;
        final double[] dz;
        /** True for a hull cell under the waterline: it must be in water, not just free of blocks. */
        final boolean[] wet;
        /** True for a waterborne hull, which breaks ice floes (see {@link IceBreaker}). */
        final boolean icebreaker;
        /** Waterline height relative to the pivot, same frame as dy. 0 for an airship. */
        final double surfaceDy;
        /** Height of the highest cell of the shape relative to the pivot, same frame as dy. 0 for an airship. */
        final double topDy;
        /** Height of the lowest cell of the shape relative to the pivot, same frame as dy. 0 for an airship. */
        final double bottomDy;

        Hull(double[] dx, double[] dy, double[] dz, boolean[] wet, boolean icebreaker, double surfaceDy, double topDy, double bottomDy) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.wet = wet;
            this.icebreaker = icebreaker;
            this.surfaceDy = surfaceDy;
            this.topDy = topDy;
            this.bottomDy = bottomDy;
        }

        int size() {
            return dx.length;
        }

        /** World height of the waterline cell for a pose at height py. */
        int surfaceY(double py) {
            return (int) Math.floor(py + 0.5 + surfaceDy);
        }

        /** World height of the highest hull cell for a pose at height py. */
        int topY(double py) {
            return (int) Math.floor(py + 0.5 + topDy);
        }

        /** World height of the lowest cell of the shape for a pose at height py. */
        int bottomY(double py) {
            return (int) Math.floor(py + 0.5 + bottomDy);
        }
    }

    /**
     * Hull of a shape: prefab cells that touch (6-neighbourhood) the empty space reachable from outside the prefab's box. Cells that
     * only face a closed interior (cabins) are left out: they cannot touch the scenery before the outer shell does, and the
     * test is paid every tick. Positions are relative to the pivot.
     */
    Hull hullOf(Deployables.Kind kind, StructureShape s) {
        return hulls.computeIfAbsent(s, shape -> {
            Hull h = hullFor(shape, pivot(kind), spec(kind).waterlineY);
            LOGGER.at(Level.INFO).log("%s : %d case(s), %d case(s) de coque extérieure pour les collisions",
                    cap(spec(kind).logLabel), shape.cells().size(), h.size());
            return h;
        });
    }

    /** Hull computation of hullOf, without cache. Cells with y at or under waterlineY (when not null) are marked wet. */
    static Hull hullFor(StructureShape shape, Vector3f pv, Integer waterlineY) {
        SteamboatWater.Outside out = SteamboatWater.outside(shape);
        int ox = out.ox(), oy = out.oy(), oz = out.oz();
        List<StructureShape.Cell> hull = new ArrayList<>();
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (StructureShape.Cell c : shape.cells()) {
            for (int[] d : dirs) {
                if (out.isOutside(c.x() + d[0], c.y() + d[1], c.z() + d[2])) {
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
        boolean[] wet = new boolean[dx.length];
        if (waterlineY != null) {
            for (int i = 0; i < wet.length; i++) {
                wet[i] = hull.get(i).y() <= waterlineY;
            }
        }
        if (waterlineY == null) {
            return new Hull(dx, dy, dz, wet, false, 0, 0, 0);
        }
        return new Hull(dx, dy, dz, wet, true, waterlineY - pv.y, IceBreaker.maxCellY(shape) - pv.y,
                IceBreaker.minCellY(shape) - pv.y);
    }

    /**
     * Number of distinct world blocks (solid or liquid, BalloonManager.blocksBalloon) touched by the hull for this pose. Samples of
     * a hull cell: its centre and its 4 horizontal corners shrunk by 0.05, at the cell's mid, top and bottom heights (9 points,
     * deduplicated). Stops as soon as more than limit are found. Unloaded chunks never block.
     */
    static int blocked(World world, Hull h, double px, double py, double pz, double theta, int limit) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        return blocked(cellTest(reader, h, py), h, px, py, pz, theta, limit);
    }

    /**
     * Default cell test of a hull pose at height py. An airship hull blocks on any solid or liquid block, or on non-water under the
     * waterline. An ice-breaker hull follows {@link IceBreaker#hullCellBlocks}, where ice above the hull top (an iceberg) blocks.
     */
    static CellTest cellTest(SectionReader reader, Hull h, double py) {
        if (!h.icebreaker) {
            return (x, y, z, wet) -> wet ? !SteamboatWater.isWaterCell(reader, x, y, z) : BalloonManager.blocksBalloon(reader, x, y, z);
        }
        int surfaceY = h.surfaceY(py);
        IceBreaker.CellReader cells = (x, y, z) -> IceBreaker.kindAt(reader, x, y, z);
        return (x, y, z, wet) -> IceBreaker.hullCellBlocks(cells, x, y, z, wet, surfaceY);
    }

    /**
     * First world cell refusing this hull pose, as {x, y, z, wet (1 = a cell under the waterline that is not water)}, or null when the pose is
     * free. For logs and for the cells a shuttle route must avoid; same samples and tests as {@link #blocked}.
     */
    static int[] firstBlockingCell(World world, Hull h, double px, double py, double pz, double theta, int allowed) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        int[][] hit = new int[1][];
        int[] seen = new int[1];
        CellTest base = cellTest(reader, h, py);
        blocked((x, y, z, wet) -> {
            boolean b = base.blocks(x, y, z, wet);
            if (b && ++seen[0] == allowed + 1) {
                hit[0] = new int[] {x, y, z, wet ? 1 : 0}; // the first cell beyond the ones the hull may already touch
            }
            return b;
        }, h, px, py, pz, theta, allowed);
        return hit[0];
    }

    /** Text of a blocking cell of {@link #firstBlockingCell} for the log: the world block (or liquid) found there. */
    static String describeCell(World world, int[] cell) {
        if (cell == null) {
            return "aucune case";
        }
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            BlockType bt = SectionBlocks.typeAt(reader, cell[0], cell[1], cell[2]);
            String liquid = BalloonManager.liquidAt(reader, cell[0], cell[1], cell[2]);
            return String.format(Locale.ROOT, "case (%d, %d, %d) %s : bloc %s, fluide %s", cell[0], cell[1], cell[2],
                    cell[3] == 1 ? "sous la ligne de flottaison sans eau" : "à l'air libre", bt != null ? bt.getId() : "aucun",
                    liquid != null ? liquid : "aucun");
        } catch (RuntimeException e) {
            return String.format(Locale.ROOT, "case (%d, %d, %d)", cell[0], cell[1], cell[2]);
        }
    }

    /** Whether a world cell blocks a hull sample; wet is true for a sample of a cell under the waterline (it needs water). */
    interface CellTest {
        boolean blocks(int x, int y, int z, boolean wet);
    }

    static int blocked(CellTest test, Hull h, double px, double py, double pz, double theta, int limit) {
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
            boolean wet = h.wet[k];
            double ccx = px + h.dx[k] * c + h.dz[k] * s;
            double ccz = pz - h.dx[k] * s + h.dz[k] * c;
            double ccy = py + 0.5 + h.dy[k];
            if (testPoint(test, seen, wet, ccx, ccy, ccz)) {
                if (++count > limit) {
                    return count;
                }
            }
            for (int i = 0; i < 4; i++) {
                for (int level = -1; level <= 1; level += 2) {
                    if (testPoint(test, seen, wet, ccx + cox[i], ccy + level * HALF, ccz + coz[i])) {
                        if (++count > limit) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** true if a hull cell of a pose at this height reaches outside the world's height range (samples of testPoint). */
    private static boolean heightExceeded(Hull h, double py) {
        for (int k = 0; k < h.size(); k++) {
            double ccy = py + 0.5 + h.dy[k];
            if (BalloonManager.outOfHeight((int) Math.floor(ccy + HALF)) || BalloonManager.outOfHeight((int) Math.floor(ccy - HALF))) {
                return true;
            }
        }
        return false;
    }

    private static boolean testPoint(CellTest test, LongOpenHashSet seen, boolean wet, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        long key = ((long) (bx & 0x3FFFFFF) << 38) | ((long) (bz & 0x3FFFFFF) << 12) | (by & 0xFFF);
        // the wet flag is part of the key: a wet and a dry cell sampling the same world cell are different tests
        if (!seen.add(wet ? ~key : key)) {
            return false;
        }
        return test.blocks(bx, by, bz, wet);
    }

    /** Computes the collision hull of the shape now (logged), instead of at the first take-off. */
    void warmUp(Deployables.Kind kind, StructureShape s) {
        hullOf(kind, s);
    }

    // ------------------------------------------------------------------ take-off

    /**
     * Sentinel (compared by identity) returned by takeOff, startVoyage and leaveFromParked when the departure waits for the rope
     * ladders to come up: not an error, callers must neither send it nor count it as a refusal.
     */
    static final Message DEFERRED = Message.raw("");

    /** Null if all went well, otherwise an error message for the player (nothing is removed then). */
    Message takeOff(Store<EntityStore> store, Ref<EntityStore> pilotRef, PlayerRef player, World world) {
        if (flights.containsKey(player.getUuid()) || BalloonManager.get().isFlying(player.getUuid())) {
            AirshipFlight existing = flights.get(player.getUuid());
            return Texts.ship(existing == null ? null : existing.kind, "error.alreadyPiloting");
        }
        TransformComponent pt = store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (pt == null) {
            return Texts.t("error.noPosition");
        }
        BalloonManager.Found found = BalloonManager.get().find(world, new Vector3d(pt.getPosition()), Deployables.SHIPS);
        if (found == null) {
            return Texts.ship(null, "error.noAirship");
        }
        Deployables.Kind kind = found.kind();
        StructureShape s;
        try {
            s = kind.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : lecture du prefab du dirigeable impossible");
            Telemetry.error("airship.takeoff", e, null, null);
            return Texts.ship(kind, "error.prefabUnreadable").param("error", String.valueOf(e.getMessage()));
        }
        Vector3i origin = found.origin();
        Rotation rotation = found.rotation();
        Message preflight = preflight(kind, world, s, origin, rotation);
        if (preflight != null) {
            return preflight;
        }
        // the helm refuses while the pilot NPC holds it or is not seated yet.
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, kind.id);
        CrewRecord crew = entry != null ? entry.crew() : null;
        String refusal = spec(kind).hasCrew() ? AirshipCrew.helmRefusalKey(crew, AirshipCrew.state(world, entry)) : null;
        if (refusal != null) {
            return Texts.pilot(refusal, crew);
        }
        // A pilot NPC sits on the cabin stool: it is no passenger seat (14 places instead of 15).
        Set<Vector3i> excluded = spec(kind).hasCrew() && crew != null ? Set.of(new Vector3i(spec(kind).crewSpec.rest()[0], spec(kind).crewSpec.rest()[1], spec(kind).crewSpec.rest()[2])) : Set.of();
        // the other players aboard become passengers on the stools; more players than stools: take-off refused.
        // the refusals come before anything is changed above a station (the climbers on the rungs count as aboard).
        int seats = AirshipPassengers.seats(s, excluded).size(); // stools and tavern benches (2 seats each)
        List<BalloonManager.Occupant> inside = AirshipPassengers.aboard(store, world, s, origin, rotation, player.getUuid());
        int onBoard = inside.size();
        if (onBoard > seats) {
            return Texts.ship(kind, "error.full").param("max", seats + 1).param("extra", onBoard - seats);
        }
        // The rope ladders come up before the ship leaves; the take-off then goes on by itself.
        Vector3d parkedPivot = parkedPivot(kind, origin, rotation);
        Player pl = store.getComponent(pilotRef,
                Player.getComponentType());
        GameMode mode = pl != null ? pl.getGameMode() : null;
        ShipLadder.Defer defer = !spec(kind).ladders ? ShipLadder.Defer.PROCEED : ShipLadder.deferDeparture(world, origin, rotation,
                () -> stillAtHelm(store, pilotRef, parkedPivot, mode),
                () -> {
                    Message later = takeOff(store, pilotRef, player, world);
                    if (later != null && later != DEFERRED) {
                        player.sendMessage(later);
                    }
                });
        if (defer == ShipLadder.Defer.ALREADY_PENDING) {
            return Texts.t("airship.ladder.departing");
        }
        if (defer == ShipLadder.Defer.DEFERRED) {
            player.sendMessage(Texts.t("airship.ladder.raisingBeforeTakeoff"));
            return DEFERRED;
        }
        List<BalloonManager.Occupant> aboard = AirshipPassengers.aboard(store, world, s, origin, rotation, player.getUuid());
        Message launched = launch(kind, store, world, s, origin, rotation, pilotRef, player, passengers.assign(store, s, origin, rotation, aboard, excluded));
        if (launched != null) {
            if (spec(kind).recall) {
                AirshipRecall.restoreMark(world, origin, rotation); // refused after the rungs came down: the wait goes on
            }
        } else {
            Telemetry.count("takeoff." + Telemetry.kindId(kind));
        }
        return launched;
    }

    /** True while the player is alive, near the parked ship's pivot and still in the game mode of the request. */
    private static boolean stillAtHelm(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pivot,
                                       GameMode mode) {
        if (ref == null || !ref.isValid() || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return false;
        }
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null || t.getPosition().distance(pivot) > 1.5) {
            return false;
        }
        Player p = store.getComponent(ref,
                Player.getComponentType());
        return p != null && p.getGameMode() == mode;
    }

    /**
     * Models present: null if all is well. Processing benches no longer need to be empty: like the chests and the
     * fuel tank, their contents are kept through the flight.
     */
    private Message preflight(Deployables.Kind kind, World world, StructureShape s, Vector3i origin, Rotation rotation) {
        Message models = modelCheck(kind);
        return models != null ? models : componentCheck(world, s, origin, rotation);
    }

    /** Refuses before anything is removed if a chest or bench of the structure gives no component (section without players near). */
    private static Message componentCheck(World world, StructureShape s, Vector3i origin, Rotation rotation) {
        return StructureComponents.unreadable(world, s, origin, rotation).isEmpty() ? null : Texts.t("structure.componentsMissing");
    }

    private Message modelCheck(Deployables.Kind kind) {
        for (String id : new String[]{spec(kind).modelId, spec(kind).idleModelId, spec(kind).offModelId, spec(kind).turnLeftModelId,
                spec(kind).turnRightModelId}) {
            if (ModelAsset.getAssetMap().getAsset(id) == null) {
                return Texts.ship(kind, "error.modelMissing").param("model", id);
            }
        }
        return null;
    }

    /** after the pose of a voyage whose captain is ashore, lowers the ladder of the chosen side and tells the riders. Never throws. */
    private static void lowerAshoreLadder(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        VoyageState v = f.voyage;
        if (v == null || !v.ashore || v.ashoreAnnounced) {
            return;
        }
        try {
            v.ashoreAnnounced = true;
            ShipLadder.lower(f.world, origin, rotation, v.ashoreSide);
            java.util.Set<UUID> told = new java.util.HashSet<>();
            for (AirshipPassengers.Rider r : f.riders) {
                PlayerRef pr = playerOf(store, r.p.ref);
                if (pr != null && told.add(pr.getUuid())) {
                    pr.sendMessage(Texts.pilot("crew.voyage.captainOverboard", f.crew));
                }
            }
            if (v.captain != null && !told.contains(v.captain)) {
                for (PlayerRef pr : f.world.getPlayerRefs()) {
                    if (v.captain.equals(pr.getUuid())) {
                        pr.sendMessage(Texts.pilot("crew.voyage.captainOverboard", f.crew));
                    }
                }
            }
            LOGGER.at(Level.INFO).log("Voyage : échelle du côté %d déployée pour le capitaine à terre", v.ashoreSide);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : échelle du capitaine à terre non déployée");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** the end of a voyage that reached its destination: the riders hear the pilot (the pilot NPC is recreated at the helm by finishLanding). */
    private static void announceArrival(Store<EntityStore> store, AirshipFlight f) {
        VoyageState v = f.voyage;
        if (v == null || !v.arrived) {
            return;
        }
        for (AirshipPassengers.Rider r : f.riders) {
            PlayerRef pr = playerOf(store, r.p.ref);
            if (pr != null) {
                pr.sendMessage(ShipVoyage.withDest(Texts.pilot(spec(f.kind).waterborne() ? "crew.voyage.arrivedBoat" : "crew.voyage.arrived",
                        f.crew), v.destKind, v.destName));
            }
        }
    }

    // ------------------------------------------------------------------ voyage with the pilot NPC

    /** True if the player has a hotbar slot other than the held one with nothing in it (room for the captain's map). */
    static boolean hasFreeHotbarSlot(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (hotbar == null) {
                return false;
            }
            int active = hotbar.getActiveSlot();
            for (int i = 0; i < hotbar.getInventory().getCapacity(); i++) {
                if (i == active) {
                    continue;
                }
                ItemStack st = hotbar.getInventory().getItemStack((short) i);
                if (st == null || st.isEmpty()) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : barre d'action illisible");
        }
        return false;
    }

    /**
     * The captain asks the pilot NPC at the helm to fly to a destination. Null if the voyage left, otherwise the refusal for the
     * captain (nothing was removed). The ship takes off without a player pilot: the captain sits on the chair, the other players
     * aboard on the 15 seats, and the pilot is shown by a standing mannequin at the helm.
     */
    Message startVoyage(Store<EntityStore> store, World world, PlayerRef captain, BalloonRegistry.Entry e, VoyagePlanner.Destination d) {
        return startVoyage(store, world, captain, e, d, null);
    }

    /** Same, with a callback run once every refusal is passed, just before anything is removed (nullable). */
    Message startVoyage(Store<EntityStore> store, World world, PlayerRef captain, BalloonRegistry.Entry e, VoyagePlanner.Destination d, Runnable onAccepted) {
        Ref<EntityStore> capRef = captain.getReference();
        if (capRef == null || !capRef.isValid()) {
            return Texts.t("error.noPosition");
        }
        // The ship type is the entry's (the airship, or the steamboat); the airship when the entry is unknown.
        Deployables.Kind entryKind = e != null ? Deployables.get(e.kind()) : null;
        Deployables.Kind kind = entryKind != null && entryKind.airship != null ? entryKind : Deployables.AIRSHIP;
        if (flights.containsKey(captain.getUuid()) || BalloonManager.get().isFlying(captain.getUuid())) {
            return Texts.ship(kind, "error.alreadyPiloting");
        }
        if (!spec(kind).voyage) {
            return Texts.ship(kind, "error.noAirship");
        }
        StructureShape s;
        try {
            s = kind.shape();
        } catch (IOException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Voyage : lecture du prefab du dirigeable impossible");
            Telemetry.error("airship.autopilot", ex, Telemetry.kindId(kind), null);
            return Texts.ship(kind, "error.prefabUnreadable").param("error", String.valueOf(ex.getMessage()));
        }
        TransformComponent pt = store.getComponent(capRef, TransformComponent.getComponentType());
        if (pt == null) {
            return Texts.t("error.noPosition");
        }
        // the ship is the one of the given entry, not the nearest to the captain. The captain's
        // desk is about 19 blocks from the helm, outside the search radius around the anchor.
        Vector3i origin;
        Rotation rotation;
        if (e != null && world.getName().equals(e.world())) {
            origin = new Vector3i(e.x(), e.y(), e.z());
            rotation = e.rotation();
        } else {
            BalloonManager.Found found = BalloonManager.get().find(world, new Vector3d(pt.getPosition()), List.of(kind));
            if (found == null) {
                return Texts.ship(kind, "error.noAirship");
            }
            origin = found.origin();
            rotation = found.rotation();
        }
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, kind.id);
        if (entry == null || entry.crew() == null || e == null || e.crew() == null || !entry.crew().uuid().equals(e.crew().uuid())
                || !captain.getUuid().equals(entry.owner()) || AirshipCrew.state(world, entry) != AirshipCrew.CrewState.AT_HELM) {
            return Texts.ship(kind, "error.noAirship");
        }
        AirshipDesk.cancel(world, entry);
        Message preflight = preflight(kind, world, s, origin, rotation);
        if (preflight != null) {
            return preflight;
        }
        // every refusal is checked before the rungs come down (the climbers count as aboard).
        int seats = AirshipPassengers.seats(s).size();
        List<BalloonManager.Occupant> inside = AirshipPassengers.aboard(store, world, s, origin, rotation, captain.getUuid());
        int onBoard = inside.size();
        boolean ownerAboard = insideShape(s, origin, rotation, pt.getPosition());
        String refusal = AirshipAutopilot.departureRefusalKey(ownerAboard, onBoard, seats, hasFreeHotbarSlot(store, capRef));
        if (refusal != null) {
            Message m = Texts.pilot(refusal, entry.crew());
            return "airship.error.full".equals(refusal) ? m.param("max", seats + 1).param("extra", onBoard - seats) : m;
        }
        // A boat sails a path planned on its water log: planned first (over a few ticks), then this method runs again with it.
        List<int[]> plan = null;
        if (spec(kind).waterborne()) {
            plan = ShipVoyage.takePlan(world, entry, d);
            if (plan == null) {
                Message refused = ShipVoyage.plan(store, world, captain, entry, d, onAccepted);
                return refused != null ? refused : DEFERRED;
            }
        }
        // The rope ladders come up first; the voyage then starts by itself (the refusals are passed, onAccepted runs once).
        ShipLadder.Defer defer = !spec(kind).ladders ? ShipLadder.Defer.PROCEED : ShipLadder.deferDeparture(world, origin, rotation,
                () -> capRef.isValid() && captainInside(store, capRef, s, origin, rotation),
                () -> {
                    Message later = startVoyage(store, world, captain, e, d, null);
                    if (later != null && later != DEFERRED) {
                        captain.sendMessage(later);
                    }
                });
        if (defer == ShipLadder.Defer.ALREADY_PENDING) {
            return Texts.t("airship.ladder.departing");
        }
        if (onAccepted != null) {
            onAccepted.run();
        }
        if (defer == ShipLadder.Defer.DEFERRED) {
            captain.sendMessage(Texts.pilot("airship.ladder.raisingBeforeTakeoffPilot", entry.crew()));
            return DEFERRED;
        }
        List<BalloonManager.Occupant> aboard = AirshipPassengers.aboard(store, world, s, origin, rotation, captain.getUuid());
        List<AirshipPassengers.Rider> riders = new ArrayList<>(passengers.assign(store, s, origin, rotation, aboard, Set.of()));
        AirshipPassengers.Rider chair = passengers.chairRider(kind, s, new BalloonManager.Occupant(captain, capRef, 0));
        if (chair != null) {
            riders.add(0, chair);
        }
        Message launched = launchVoyage(kind, store, world, s, origin, rotation, captain, riders, d, plan, null, captain.getUuid(),
                captain.getUsername());
        if (launched != null) {
            if (spec(kind).recall) {
                AirshipRecall.restoreMark(world, origin, rotation);
            }
        } else {
            Telemetry.count("airship.voyage");
        }
        return launched;
    }

    private static boolean captainInside(Store<EntityStore> store, Ref<EntityStore> ref, StructureShape s, Vector3i origin, Rotation rotation) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        return t != null && insideShape(s, origin, rotation, t.getPosition());
    }

    /**
     * the minute above the station is over (or the captain's window closed): the ship waiting in blocks takes off with the players
     * aboard as passengers (the captain, if aboard, on the chair) and flies away without fuel to the leave point. Null if it left,
     * otherwise the refusal (nothing was removed).
     */
    Message leaveFromParked(Store<EntityStore> store, World world, BalloonRegistry.Entry entry, RecallState r) {
        StructureShape s;
        try {
            s = Deployables.AIRSHIP.shape();
        } catch (IOException ex) {
            return Texts.ship(Deployables.AIRSHIP, "error.prefabUnreadable").param("error", String.valueOf(ex.getMessage()));
        }
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        Rotation rotation = entry.rotation();
        // The ship must still wait in blocks here: a second call (window closed by the server) must not launch a ship that already left.
        if (AirshipRecall.parkedAt(world, origin, rotation) != r
                || BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND) == null) {
            LOGGER.at(Level.INFO).log("Rappel : départ ignoré, le dirigeable n'attend plus au-dessus de %s", r.station.name);
            return Texts.ship(Deployables.AIRSHIP, "error.noAirship");
        }
        if (!shipSectionsLoaded(world, origin)) {
            // Never detach a structure that is not in memory (nothing would be removed, the ship would be duplicated).
            loadShipSections(world, origin);
            LOGGER.at(Level.INFO).log("Rappel : sections du dirigeable en attente non chargées, chargement demandé");
            return Texts.t("crew.recall.unreachable");
        }
        Message models = modelCheck(Deployables.AIRSHIP);
        if (models != null) {
            return models;
        }
        Message unreadable = componentCheck(world, s, origin, rotation);
        if (unreadable != null) {
            return unreadable;
        }
        ShipLadder.Defer defer = ShipLadder.deferDeparture(world, origin, rotation, () -> true, () -> {
            BalloonRegistry.Entry again = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
            if (again != null) {
                AirshipRecall.leaveNow(world.getEntityStore().getStore(), world, r, again);
            }
        });
        if (defer == ShipLadder.Defer.ALREADY_PENDING) {
            return Texts.t("airship.ladder.departing");
        }
        if (defer == ShipLadder.Defer.DEFERRED) {
            return DEFERRED;
        }
        PlayerRef captain = AirshipRecall.captainRef(store, world, r);
        List<BalloonManager.Occupant> aboard = AirshipPassengers.aboard(store, world, s, origin, rotation, r.owner);
        List<AirshipPassengers.Rider> riders = new ArrayList<>(passengers.assign(store, s, origin, rotation, aboard, Set.of()));
        if (riders.size() < aboard.size()) {
            // More players than seats: the extras go to the station's boarding points before the floor goes, nobody falls.
            List<Vector3d> points = TransitAutopilot.boardingPoints(r.station);
            int i = 0;
            for (BalloonManager.Occupant o : aboard) {
                boolean seated = false;
                for (AirshipPassengers.Rider rd : riders) {
                    if (rd.p.ref.equals(o.ref())) {
                        seated = true;
                    }
                }
                if (!seated) {
                    AirshipPassengers.teleport(store, o.ref(), points.get(i++ % points.size()));
                }
            }
            LOGGER.at(Level.WARNING).log("Rappel : %d joueur(s) sans place posé(s) sur le quai", aboard.size() - riders.size());
        }
        if (captain != null) {
            AirshipPassengers.Rider chair = passengers.chairRider(Deployables.AIRSHIP, s, new BalloonManager.Occupant(captain, captain.getReference(), 0));
            if (chair != null) {
                riders.add(0, chair);
            }
        }
        return launchVoyage(Deployables.AIRSHIP, store, world, s, origin, rotation, captain, riders, null, null, r, entry.owner(),
                entry.ownerName());
    }

    /** plan: the waypoints of a boat (null for the airship). */
    private Message launchVoyage(Deployables.Kind kind, Store<EntityStore> store, World world, StructureShape s, Vector3i origin,
                                 Rotation rotation, PlayerRef captain, List<AirshipPassengers.Rider> riders, VoyagePlanner.Destination d,
                                 List<int[]> plan, RecallState leave, UUID ownerId, String ownerName) {
        Deployables.AirshipSpec spec = spec(kind);
        if (spec.ladders && !ShipLadder.allUp(world, origin, rotation)) {
            LOGGER.at(Level.WARNING).log("Décollage refusé : échelle encore descendue");
            return Texts.ship(kind, "error.noAirship");
        }
        boolean boat = spec.waterborne() && plan != null && leave == null;
        UUID id = UUID.randomUUID();
        AirshipFlight f = new AirshipFlight(id, id, null, "pilote PNJ", world, kind);
        initFlight(f, spec, origin, rotation);
        // The riders are held in the air before the floor goes, and listed in the flight for the resume file.
        passengers.prepare(store, riders);
        f.riders.addAll(riders);
        RecallState parked = spec.recall ? AirshipRecall.parkedAt(world, origin, rotation) : null; // ship waiting above a station
        Detached detached = detachStructure(store, world, s, origin, rotation, f);
        Ref<EntityStore> ship = spawnShip(store, f, spec.idleModelId);
        if (ship == null) {
            restoreStructure(store, f, origin, rotation, ownerId, ownerName);
            passengers.disembark(store, f, origin, rotation);
            AirshipResume.delete(f.id);
            if (parked != null) {
                // The ship is back in blocks above the station: its recall mark goes back and, in the minute only, the rungs.
                AirshipRecall.restoreMark(world, origin, rotation);
            }
            return Texts.t("error.modelMissing").param("model", spec.idleModelId);
        }
        // No pilot aboard: one entity seen by everybody (no observer entity, no helm helper: nobody lands at the helm during a voyage).
        f.parts = VehicleParts.spawn(store, partsMode, spec.modelId, ship, f.shipUuid, null, null);
        long now = System.currentTimeMillis();
        if (detached.crewLook() != null) {
            // The pilot stands at the helm post of the crew spec (the pivot cell of the airship), feet on the deck, no seat offset.
            int[] helm = spec.crewSpec != null ? spec.crewSpec.helm() : null;
            Vector3f post = helm != null ? new Vector3f(helm[0], helm[1], helm[2]) : pivot(kind);
            f.crewMan = AirshipCrew.createMannequin(store, f.shipUuid, ship, null, null, shipPoint(f, post), new Vector3f(),
                    detached.crewLook(), now, AirshipCrew.POSE_STAND);
        }
        f.takeoffMs = now;
        spawnLights(store, f, s);
        int departureY = f.lastValidPivotCell.y;
        if (boat) {
            // The boat keeps its waterline and sails the planned path; the destination is the last waypoint (rotation centre column).
            f.lockedY = f.pos.y;
            int[] goal = plan.get(plan.size() - 1);
            double[] g = ShipVoyage.goalOf(f.water, d, null, null); // a stop gives its heading, a station none (NaN)
            // Same column convention as the destinations: dest x, z = the rotation centre column.
            f.voyage = new VoyageState(d.name(), d.kind(), goal[0], goal[1], f.pos.y, captain.getUuid());
            Vector3d home = centerWorld(kind, f.pos, f.theta); // where it was moored: a pose known to fit (back-off, last point)
            f.voyage.homeColumn = new int[] {(int) Math.floor(home.x), (int) Math.floor(home.z)};
            f.voyage.homeTheta = f.theta;
            f.voyage.phase = VoyageState.Phase.CRUISE;
            f.voyage.water = ShipVoyage.sailing(f, plan, g != null ? g[2] : Double.NaN);
        } else if (leave == null) {
            f.voyage = new VoyageState(d.name(), d.kind(), d.x(), d.z(),
                    VoyagePlanner.cruiseY(departureY, d.groundY(), BalloonManager.WORLD_MAX_Y), captain.getUuid());
        } else {
            f.voyage = AirshipRecall.leaveVoyage(leave, world, departureY); // fuel-free flight to the leave point
            f.recall = leave;
        }
        passengers.board(store, f);
        flights.put(f.id, f);
        AirshipRecall.takeoffDone(f, parked);
        f.lastResumeMs = f.lastTickMs;
        AirshipResume.write(f, false);
        if (leave == null) {
            CaptainMap.forget(captain.getUuid());
            CaptainMap.removeAll(store, captain.getReference()); // no stale map before the new one
            CaptainMap.give(store, captain.getReference());
            if (f.engines.isEmpty()) {
                enterDry(store, f, false);
            }
            for (AirshipPassengers.Rider r : f.riders) {
                PlayerRef pr = playerOf(store, r.p.ref);
                if (pr != null) {
                    pr.sendMessage(ShipVoyage.withDest(Texts.pilot("crew.voyage.depart", f.crew), d.kind(), d.name()));
                }
            }
            LOGGER.at(Level.INFO).log("Voyage : décollage du %s de %s vers %s (%s, %.0f, %.0f), croisière à y %.0f, %d blocs retirés, "
                            + "%d joueur(s) à bord%s", spec.logLabel, ownerName, d.name(), d.kind(), f.voyage.destX, f.voyage.destZ,
                    f.voyage.cruiseY, detached.removed(), f.riders.size(), boat ? ", " + plan.size() + " point(s) de passage" : "");
        } else {
            LOGGER.at(Level.INFO).log("Rappel : le dirigeable de %s décolle de %s, %d blocs retirés, %d joueur(s) à bord", ownerName,
                    leave.station.name, detached.removed(), f.riders.size());
        }
        return null;
    }

    /**
     * Maritime shuttle: a pilotless STEAMBOAT_PUBLIC flight that appears at this pivot and heading on the water (no blocks to remove:
     * the departure from a moored shuttle in blocks removes them first), its height locked, sailed by BoatAutopilot. The riders
     * (possibly none) are seated like voyage passengers. The helm mannequin stands at the pivot with the look of the departure
     * station master (no mannequin if helmLook is null). No resume file is written for the shuttle. Returns the flight, or null if
     * the prefab or the model is missing (the riders are then released). Call on the world thread.
     */
    AirshipFlight launchShuttle(Store<EntityStore> store, World world, Vector3d pivot, double theta, ShuttleState state,
                                List<AirshipPassengers.Rider> riders, Object[] helmLook) {
        Deployables.Kind kind = Deployables.STEAMBOAT_PUBLIC;
        Deployables.AirshipSpec spec = spec(kind);
        StructureShape s;
        try {
            s = kind.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : lecture du prefab impossible");
            Telemetry.error("transit", e, Telemetry.kindId(kind), null);
            passengers.releaseAll(store, riders);
            return null;
        }
        UUID id = UUID.randomUUID();
        AirshipFlight f = new AirshipFlight(id, id, null, "navette", world, kind);
        long now = System.currentTimeMillis();
        f.pos.set(pivot);
        f.theta = theta;
        f.lockedY = pivot.y; // the hull stays on the waterline
        if (spec.wheelAnimPrefix != null || spec.propellerAnimPrefix != null) {
            f.wheels = new PaddleWheels.State(spec.propellerAnimPrefix);
        }
        if (spec.helmAnimated) {
            f.helmState = new ShipHelm.State();
        }
        f.modelId = spec.idleModelId;
        f.lastValidPos.set(f.pos);
        f.lastValidTheta = f.theta;
        f.lastValidPivotCell.set(f.pivotCell(f.pos));
        f.takeoffOrigin.set(f.lastValidPivotCell);
        f.lastTickMs = now;
        // A shuttle that appears slightly embedded may still move out (accept keeps the count up to date).
        f.blockedNow = blocked(world, hullOf(kind, s), f.pos.x, f.pos.y, f.pos.z, f.theta, Integer.MAX_VALUE);
        passengers.prepare(store, riders);
        f.riders.addAll(riders);
        Ref<EntityStore> ship = spawnShip(store, f, spec.idleModelId);
        if (ship == null) {
            LOGGER.at(Level.WARNING).log("Navette : modèle %s introuvable", spec.idleModelId);
            passengers.releaseAll(store, f.riders);
            f.riders.clear();
            return null;
        }
        try {
            // No pilot aboard: one entity seen by everybody (no observer entity, no helm helper).
            f.parts = VehicleParts.spawn(store, partsMode, spec.modelId, ship, f.shipUuid, null, null);
            if (helmLook != null) {
                f.crewMan = AirshipCrew.createMannequin(store, f.shipUuid, ship, null, null, shipPoint(f, pivot(kind)), new Vector3f(),
                        helmLook, now, AirshipCrew.POSE_STAND);
                if (f.crewMan == null) {
                    LOGGER.at(Level.WARNING).log("Navette %s : mannequin du pilote non créé (création refusée), nouvel essai en route", f.id);
                }
            } else {
                LOGGER.at(Level.WARNING).log("Navette %s : départ sans mannequin de pilote (apparence du chef de gare introuvable), nouvel essai en route", f.id);
            }
            f.takeoffMs = now;
            spawnLights(store, f, s);
            f.shuttle = state;
            passengers.board(store, f);
            flights.put(f.id, f);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : départ impossible, vol retiré");
            Telemetry.error("transit", e, Telemetry.kindId(kind), null);
            endShuttle(store, f, "départ impossible"); // ship entity, parts, mannequin, lights and prepared riders
            return null;
        }
        LOGGER.at(Level.INFO).log("Navette : départ de %s vers %s en (%.1f, %.1f, %.1f), %d point(s), %d passager(s)",
                state.from != null ? state.from.name : "?", state.to != null ? state.to.name : "?", f.pos.x, f.pos.y, f.pos.z,
                state.waypoints.size(), f.riders.size());
        return f;
    }

    /** True while this flight is the registered flight of its id and is not ending. */
    boolean isActive(AirshipFlight f) {
        return f != null && !f.finishing && flights.get(f.id) == f;
    }

    /**
     * Ends a shuttle flight without putting it down in blocks: the riders are released and put on the surface under their seat,
     * every entity of the flight (ship, parts, mannequin, lights) is removed. Never throws.
     */
    void endShuttle(Store<EntityStore> store, AirshipFlight f, String reason) {
        if (f.finishing) {
            return;
        }
        f.finishing = true;
        flights.remove(f.id);
        ShuttleNav.removed(f.shuttle); // a trip in progress learns nothing
        try {
            passengers.releaseAll(store, f);
            removeLights(store, f);
            removeShipEntities(store, f);
            LOGGER.at(Level.INFO).log("Navette %s retirée (%s)", f.id, reason);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette %s : retrait incomplet", f.id);
            Telemetry.error("transit", e, Telemetry.kindId(f.kind), null);
        }
    }

    /**
     * Maritime shuttle at its berth: the flight ends (riders released, every entity and light removed) and the shuttle is put down in
     * blocks at this berth origin and rotation, without owner, its hold drained. None of the cargo, engine, bench, crew or ladder
     * steps of a landing. Returns true if the structure is registered afterwards. Call on the world thread. Never throws.
     */
    boolean moorShuttle(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        endShuttle(store, f, "amarrage");
        return pasteShuttle(store, f.world, origin, rotation);
    }

    /** Pastes the shuttle prefab without owner at this origin and rotation and drains its hold. True if registered. Never throws. */
    boolean pasteShuttle(Store<EntityStore> store, World world, Vector3i origin, Rotation rotation) {
        Deployables.Kind kind = Deployables.STEAMBOAT_PUBLIC;
        try {
            BalloonManager.get().pastePrefab(kind, world, store, origin, rotation, Ownership.NONE);
            StructureShape s = kind.shapeOrNull();
            if (s != null) {
                drainHull(world, kind, s, origin, rotation);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette : pose à quai en échec en %s", origin);
            Telemetry.error("transit", e, Telemetry.kindId(kind), null);
        }
        return BalloonRegistry.get(world, origin, rotation, kind.id) != null;
    }

    /**
     * Moored shuttle leaving its berth: the registry entry and the blocks are removed (no particles left in the sky) and the hold gives
     * its water back to the sea. Nothing else is kept (the shuttle has no cargo, fuel, bench or crew). Returns the number of blocks
     * removed. Call on the world thread with the sections loaded.
     */
    int unmoorShuttle(World world, Vector3i origin, Rotation rotation) throws IOException {
        Deployables.Kind kind = Deployables.STEAMBOAT_PUBLIC;
        StructureShape s = kind.shape();
        List<Fx> fx = collectParticles(world, s, origin, rotation);
        MooredPilot.remove(world, origin);
        BalloonRegistry.remove(world, origin, rotation, kind.id);
        int removed = BalloonManager.removeBlocks(world, s, origin, rotation, true);
        floodHull(world, kind, s, origin, rotation);
        cancelParticles(world, fx);
        return removed;
    }

    /** Box around a parked airship that holds all its cells for any rotation (prefab x -5..5, y 2..18, z -15..15). */
    static int[] shipBox(Vector3i origin) {
        return new int[] {origin.x - 18, origin.y - 2, origin.z - 18, origin.x + 18, origin.y + 24, origin.z + 18};
    }

    /** True if every section over the box of the airship parked at this origin is in memory. */
    static boolean shipSectionsLoaded(World world, Vector3i origin) {
        int[] b = shipBox(origin);
        return SectionBlocks.sectionsLoaded(world.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** Asks for the sections over the box of the airship parked at this origin (for a later try). */
    static void loadShipSections(World world, Vector3i origin) {
        int[] b = shipBox(origin);
        SectionBlocks.loadSections(world.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** Pivot position (cell centre) of the airship parked at this origin and rotation. */
    Vector3d parkedPivot(Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        Vector3f pv = pivot(kind);
        Vector3i cell = rotation.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()).add(origin);
        return new Vector3d(cell.x + 0.5, cell.y, cell.z + 0.5);
    }

    /**
     * the parked airship of a registry entry leaves its parking place (same steps as a voyage take-off, without passengers or
     * map) and appears in flight at the recall spawn point, bow to the station, flown by the pilot NPC without fuel. Null if it left,
     * otherwise the refusal (the structure is then back in place). Call on the world thread with the sections loaded.
     */
    Message launchRecall(Store<EntityStore> store, World world, BalloonRegistry.Entry entry, UUID flightId, AirshipRecall.Plan plan,
                         TransitNetwork.Station station) {
        Deployables.Kind kind = Deployables.AIRSHIP;
        Deployables.AirshipSpec spec = spec(kind);
        StructureShape s;
        try {
            s = kind.shape();
        } catch (IOException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Rappel : lecture du prefab du dirigeable impossible");
            Telemetry.error("airship.recall", ex, Telemetry.kindId(kind), null);
            return Texts.ship(Deployables.AIRSHIP, "error.prefabUnreadable").param("error", String.valueOf(ex.getMessage()));
        }
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        Rotation rotation = entry.rotation();
        Message preflight = preflight(kind, world, s, origin, rotation);
        if (preflight != null) {
            return preflight;
        }
        // The owner is far away: a lowered ladder is removed at once (players on the column are put on the ground).
        ShipLadder.removeNow(store, world, origin, rotation);
        if (!ShipLadder.allUp(world, origin, rotation)) {
            // Safety net: rungs remain (section not loaded): ask for the sections so the next try can remove them.
            ShipLadder.raiseAll(world, origin, rotation);
            ShipLadder.requestRungSections(world, origin, rotation);
            LOGGER.at(Level.WARNING).log("Rappel refusé : échelle encore descendue, remontée lancée");
            return Texts.t("airship.ladder.raisingBeforeTakeoff");
        }
        if (!shipSectionsLoaded(world, origin)) {
            // Never detach a structure that is not in memory (nothing would be removed, the ship would be duplicated).
            LOGGER.at(Level.WARNING).log("Rappel : sections du dirigeable posé non chargées, rappel refusé");
            return Texts.t("crew.recall.unreachable");
        }
        Hull hull = hullOf(kind, s);
        AirshipFlight f = new AirshipFlight(flightId, flightId, null, "pilote PNJ", world, kind);
        initFlight(f, spec, origin, rotation);
        Detached detached = detachStructure(store, world, s, origin, rotation, f);
        try {
            // The flight pose: at the spawn point, raised until the hull fits (the sections there were loaded).
            double y = plan.cruiseY();
            int tries = 0;
            while (blocked(world, hull, plan.spawnX(), y, plan.spawnZ(), plan.theta(), 0) > 0) {
                y += SPAWN_RAISE_BLOCKS;
                if (++tries > SPAWN_RAISE_TRIES || y > BalloonManager.WORLD_MAX_Y - 10) {
                    throw new IllegalStateException("point d'apparition bloqué");
                }
            }
            f.pos.set(plan.spawnX(), y, plan.spawnZ());
            f.theta = plan.theta();
            f.lastValidPos.set(f.pos);
            f.lastValidTheta = f.theta;
            f.lastValidPivotCell.set(f.pivotCell(f.pos));
            Ref<EntityStore> ship = spawnShip(store, f, spec.idleModelId);
            if (ship == null) {
                throw new IllegalStateException("modèle introuvable : " + spec.idleModelId);
            }
            f.parts = VehicleParts.spawn(store, partsMode, spec.modelId, ship, f.shipUuid, null, null);
            long now = System.currentTimeMillis();
            if (detached.crewLook() != null) {
                f.crewMan = AirshipCrew.createMannequin(store, f.shipUuid, ship, null, null, shipPoint(f, pivot(kind)), new Vector3f(),
                        detached.crewLook(), now, AirshipCrew.POSE_STAND);
            }
            f.takeoffMs = now;
            spawnLights(store, f, s);
            f.quayStation = station;
            f.quayId = flightId;
            f.recall = f.recallInfo = new RecallState(station, entry.owner(), plan.spawnX(), plan.spawnZ(), plan.padX(), plan.padZ(), plan.hoverY(),
                    plan.hoverTheta());
            f.voyage = new VoyageState(station.name, VoyagePlanner.Kind.STATION, plan.destX(), plan.destZ(), y, entry.owner(), true,
                    VoyageState.ArrivalMode.HOVER);
            flights.put(f.id, f);
            f.lastResumeMs = f.lastTickMs;
            AirshipResume.write(f, false);
            LOGGER.at(Level.INFO).log("Rappel : dirigeable de %s apparu en (%.0f, %.0f, %.0f) vers %s, %d blocs retirés", entry.ownerName(),
                    f.pos.x, f.pos.y, f.pos.z, station.name, detached.removed());
            return null;
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Rappel : apparition impossible, le dirigeable est reposé");
            Telemetry.error("airship.recall", ex, Telemetry.kindId(kind), null);
            flights.remove(f.id);
            f.recall = null;
            f.voyage = null;
            removeShipEntities(store, f);
            restoreStructure(store, f, origin, rotation, entry.owner(), entry.ownerName());
            AirshipResume.delete(f.id);
            return Texts.ship(Deployables.AIRSHIP, "error.noAirship");
        }
    }

    private static final double SPAWN_RAISE_BLOCKS = 2.0;
    private static final int SPAWN_RAISE_TRIES = 40;

    /** True if the world point is inside the box of the prefab placed at this origin and rotation. */
    static boolean insideShape(StructureShape s, Vector3i origin, Rotation rotation, Vector3d p) {
        Vector3d local = AirshipMath.rotateY(p.x - origin.x - 0.5, p.y - origin.y, p.z - origin.z - 0.5,
                -rotation.getRadians(), new Vector3d());
        // The cell (x, y, z) covers x - 0.5 .. x + 0.5 horizontally around its centre (centres at origin + R c + 0.5).
        return s.inBounds((int) Math.round(local.x), (int) Math.floor(local.y), (int) Math.round(local.z));
    }

    /** Timed take-off (prefab to entity). */
    private Message launch(Deployables.Kind kind, Store<EntityStore> store, World world, StructureShape s, Vector3i origin, Rotation rotation,
                           Ref<EntityStore> pilotRef, PlayerRef player, List<AirshipPassengers.Rider> riders) {
        long t0 = Telemetry.start();
        try {
            return launchUntimed(kind, store, world, s, origin, rotation, pilotRef, player, riders);
        } finally {
            Telemetry.timing("takeoff", t0, Telemetry.kindId(kind));
        }
    }

    private Message launchUntimed(Deployables.Kind kind, Store<EntityStore> store, World world, StructureShape s, Vector3i origin, Rotation rotation,
                           Ref<EntityStore> pilotRef, PlayerRef player, List<AirshipPassengers.Rider> riders) {
        Deployables.AirshipSpec spec = spec(kind);
        if (spec.ladders && !ShipLadder.allUp(world, origin, rotation)) {
            LOGGER.at(Level.WARNING).log("Décollage refusé : échelle encore descendue");
            return Texts.ship(kind, "error.noAirship");
        }
        TransformComponent pilotTransform = store.getComponent(pilotRef, TransformComponent.getComponentType());
        if (pilotTransform == null) {
            return Texts.t("error.noPosition");
        }
        AirshipFlight f = new AirshipFlight(player.getUuid(), player.getUuid(), pilotRef, player.getUsername(), world, kind);
        initFlight(f, spec, origin, rotation);

        // 1. The pilot switches to flight before the blocks are removed, to avoid falling (same as the balloon).
        // A player seated on a block mount is dismounted first: the block is going to disappear.
        MountUtil.unseat(store, pilotRef);
        if (!BalloonManager.get().setPilotFlying(store, pilotRef, player, true, flySpeedFactor)) {
            return Texts.t("error.movement");
        }
        f.baseHorizontalSpeed = readHorizontalSpeed(store, pilotRef);
        // the passengers too (held in the air at zero flight speed), and listed in the flight for the resume file.
        passengers.prepare(store, riders);
        f.riders.addAll(riders);

        RecallState parked = spec.recall ? AirshipRecall.parkedAt(world, origin, rotation) : null; // ship waiting above a station
        Detached detached = detachStructure(store, world, s, origin, rotation, f);
        Object[] crewLook = detached.crewLook();
        int removed = detached.removed();

        Ref<EntityStore> ship = spawnShip(store, f, spec.idleModelId);
        if (ship == null) {
            restoreStructure(store, f, origin, rotation, player.getUuid(), player.getUsername());
            BalloonManager.get().setPilotFlying(store, pilotRef, player, false, 1f);
            passengers.disembark(store, f, origin, rotation);
            AirshipResume.delete(f.id);
            return Texts.t("error.modelMissing").param("model", spec.idleModelId);
        }

        // 2. The pilot is teleported to the pivot (the entity's pivot is under him) and the entity is mounted on him, offset 0.
        Rotation3f look = new Rotation3f(pilotTransform.getRotation());
        store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(new Vector3d(f.pos), look));
        attachShip(store, f);
        if (spec.waterborne()) {
            f.lockedY = f.pos.y;
            applyShipSpeeds(store, f);
        }
        // dual rendering. The ship entity mounted on the pilot is seen by the pilot only, an observer entity (same model, never
        // mounted, moved by the server every tick) by everybody else.
        if (renderDual && ViewFilter.available()) {
            Ref<EntityStore> observer = VehicleView.spawnObserver(store, ship, f.shipUuid, f.pilotUuid, spec.idleModelId);
            if (observer != null) {
                f.observerRef = observer;
                f.dual = true;
                // standing mannequin of the pilot on the observer entity.
                f.pilotMan = PilotMannequin.create(store, f.shipUuid, observer, pilotRef, f.pilotUuid, System.currentTimeMillis());
            }
        }
        // Model parts (entities that carry the nodes beyond the client's limit), on the ship entity and on the observer entity if any.
        f.parts = VehicleParts.spawn(store, partsMode, spec.modelId, ship, f.shipUuid, f.dual ? f.observerRef : null,
                f.dual ? f.pilotUuid : null);
        long now = System.currentTimeMillis();
        if (spec.hasCrew() && crewLook != null) {
            // The mannequin sits on the stool like a passenger would: same seat offset (turned with the ship by the client).
            Vector3f stool = crewStoolLocal(kind);
            f.crewMan = AirshipCrew.createMannequin(store, f.shipUuid, ship, f.dual ? f.observerRef : null, f.pilotUuid,
                    shipPoint(f, stool), crewSeatOffset(kind, stool), crewLook, now);
            if (f.crewMan != null) {
                f.crewMan.localYaw = AirshipCrew.seatYaw(spec.crewSpec.restRotation());
            }
        }
        f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
        f.takeoffMs = now;
        spawnLights(store, f, s);
        spawnHelm(store, f, s);
        passengers.board(store, f);
        flights.put(f.id, f);
        if (spec.recall) {
            AirshipRecall.takeoffDone(f, parked);
        }
        f.lastResumeMs = f.lastTickMs;
        AirshipResume.write(f, false);
        player.sendMessage(Texts.ship(f.kind, "takeoff"));
        if (f.engines.isEmpty()) {
            // No fuel: the ship still climbs, descends and hovers (gas lift), but cannot travel.
            enterDry(store, f, false);
            player.sendMessage(Texts.ship(f.kind, "fuel.none"));
        } else {
            player.sendMessage(fuelMessage(f));
        }
        LOGGER.at(Level.INFO).log("Décollage du " + spec(f.kind).logLabel + " par %s : origine %s, rotation %s, %d blocs retirés, %d objet(s) de coffre, "
                        + "%d moteur(s), %d objet(s) de moteur, %.0f s de combustible, établis améliorés : %s, %d passager(s)",
                player.getUsername(), origin, rotation, removed, f.cargo.itemCount(), f.engines.entryCount(), f.engines.itemCount(),
                f.engines.remainingSeconds(), f.benches.describe(), f.riders.size());
        return null;
    }

    /** Heading chosen from the travel direction: reversing flag and target heading (NaN: keep the current heading). */
    record TravelTarget(boolean reversing, double heading) {
    }

    /**
     * Outside the reverse cone the bow follows the travel direction. Inside it the ship is reversing: the airship keeps its heading
     * (NaN), a ship whose stern follows turns so that the stern leads the travel direction.
     */
    static TravelTarget travelTarget(double travelDir, double theta, double coneRad, boolean sternFollows) {
        double fromStern = AirshipMath.wrapPi(travelDir - (theta + Math.PI));
        if (Math.abs(fromStern) < coneRad) {
            return new TravelTarget(true, sternFollows ? AirshipMath.wrapPi(travelDir + Math.PI) : Double.NaN);
        }
        return new TravelTarget(false, travelDir);
    }

    /** What detachStructure read: the look of the pilot NPC (null without one) and the number of blocks removed. */
    private record Detached(Object[] crewLook, int removed) {
    }

    /** Pose and model of a new flight at the pivot cell of the prefab placed at this origin and rotation. */
    private void initFlight(AirshipFlight f, Deployables.AirshipSpec spec, Vector3i origin, Rotation rotation) {
        Vector3f pv = spec.pivot;
        Vector3i pivotCell = rotation.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()).add(origin);
        f.pos.set(pivotCell.x + 0.5, pivotCell.y, pivotCell.z + 0.5);
        f.theta = rotation.getRadians();
        if (spec.wheelAnimPrefix != null || spec.propellerAnimPrefix != null) {
            f.wheels = new PaddleWheels.State(spec.propellerAnimPrefix);
        }
        if (spec.helmAnimated) {
            f.helmState = new ShipHelm.State();
        }
        f.modelId = spec.idleModelId; // the travelling model (burner flames) replaces it once the ship moves
        f.lastValidPos.set(f.pos);
        f.lastValidTheta = f.theta;
        f.lastValidPivotCell.set(pivotCell);
        f.takeoffOrigin.set(origin);
        f.takeoffRotation = rotation;
        f.lastTickMs = System.currentTimeMillis();
    }

    /**
     * Steps shared by every take-off (piloted or voyage): the contents of the chests, engines and benches are kept, the ownership and
     * the pilot NPC are read from the registry entry, the resume file is written (fsync: from now on the contents only exist in
     * memory), then the entry and the blocks are removed and the particles of the removed blocks cancelled.
     */
    private Detached detachStructure(Store<EntityStore> store, World world, StructureShape s, Vector3i origin, Rotation rotation,
                                     AirshipFlight f) {
        Deployables.Kind kind = f.kind;
        // Particle systems declared by the blocks, collected BEFORE removal (cancelled afterwards).
        List<Fx> fx = collectParticles(world, s, origin, rotation);
        // The contents of the chests are copied then emptied (the game would drop them when the blocks disappear).
        f.cargo = BalloonCargo.takeFrom(world, s, origin, rotation);
        // The engines' contents (fuel, charcoal) are copied then emptied the same way.
        f.engines = AirshipEngines.takeFrom(world, s, origin, rotation);
        // The contents of the other processing benches (furnaces, tannery...) are kept the same way, frozen during the flight.
        f.benchContents = AirshipBenchContents.takeFrom(world, s, origin, rotation);
        // the level and the upgrade items of the upgraded benches are kept (the game would drop the items as a refund).
        f.benches = AirshipBenches.takeFrom(world, s, origin, rotation);
        // The resume file is written (fsync) before the blocks are removed: from now on the contents only exist in memory.
        // Ownership copied from the registry entry before it is removed (landing registers it again).
        BalloonRegistry.Entry registered = BalloonRegistry.get(world, origin, rotation, kind.id);
        f.ownership = registered != null ? registered.ownership() : Ownership.NONE;
        f.crew = spec(kind).hasCrew() && registered != null ? registered.crew() : null;
        // A boat keeps a log of the water it sails (the public shuttle has none): the registry copy, or a new one.
        f.water = spec(kind).waterborne() && kind != Deployables.STEAMBOAT_PUBLIC
                ? (registered != null && registered.water() != null ? registered.water().copy() : new WaterLog()) : null;
        // A ship with a pilot that flies keeps what the pilot sees: the registry copy, or a new log (the take-off cell is recorded too).
        f.sky = spec(kind).hasCrew() && !spec(kind).waterborne()
                ? (registered != null && registered.sky() != null ? registered.sky().copy() : new SkyLog()) : null;
        // the pilot NPC is replaced by a mannequin during the flight: its look is read, then the NPC is removed.
        Object[] crewLook = null;
        if (f.crew != null) {
            crewLook = AirshipCrew.lookOf(store, AirshipCrew.refOf(world, registered));
            if (crewLook == null) {
                LOGGER.at(Level.INFO).log("Pilote : absent au décollage, pas de mannequin de vol");
            }
            AirshipCrew.remove(store, world, registered);
        }
        AirshipResume.write(f, true);
        BalloonRegistry.remove(world, origin, rotation, kind.id);
        int removed = BalloonManager.removeBlocks(world, s, origin, rotation, true);
        floodHull(world, kind, s, origin, rotation); // a boat gives its hold back to the lake
        if (spec(kind).recall) {
            removed += removeLegacyHelmBeam(world, origin, rotation); // legacy airship helm only
        }
        cancelParticles(world, fx);
        return new Detached(crewLook, removed);
    }

    /** Cell of the prefab (repository frame) that held a beam under the helm window before the statue helm. */
    private static final Vector3i LEGACY_HELM_BEAM = new Vector3i(0, 8, -11);

    /**
     * An airship placed before the statue helm still has a Wood_Softwood_Beam under the helm. It is no longer part of the prefab, so
     * removeBlocks would leave it in the air at take-off: it is removed here, without particles. Returns 1 if a beam was removed.
     * Never throws.
     */
    private static int removeLegacyHelmBeam(World world, Vector3i origin, Rotation rotation) {
        try {
            Vector3i p = rotation.rotateYaw(new Vector3i(LEGACY_HELM_BEAM), new Vector3i()).add(origin);
            BlockType t = SectionBlocks.typeAt(new SectionReader(world.getChunkStore()), p.x, p.y, p.z);
            String id = t != null ? t.getId() : null;
            if (id != null && id.startsWith("Wood_Softwood_Beam")
                    && SectionBlocks.setAt(world.getChunkStore(), p.x, p.y, p.z, BlockType.EMPTY_KEY, 4)) {
                LOGGER.at(Level.INFO).log("Dirigeable : ancienne poutre sous la barre retirée en (%d, %d, %d)", p.x, p.y, p.z);
                return 1;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Dirigeable : ancienne poutre sous la barre non retirée");
            Telemetry.error("airship.takeoff", e, null, null);
        }
        return 0;
    }

    /** Creates the ship entity at the flight's pose and records it in the flight. Null if the model is missing. */
    private Ref<EntityStore> spawnShip(Store<EntityStore> store, AirshipFlight f, String modelId) {
        Ref<EntityStore> ship = BalloonManager.get().spawnModelEntity(store, new Vector3d(f.pos), visualYaw(f.theta), modelId);
        if (ship == null) {
            return null;
        }
        f.shipRef = ship;
        UUIDComponent uc = store.getComponent(ship, UUIDComponent.getComponentType());
        f.shipUuid = uc != null ? uc.getUuid() : null;
        return ship;
    }

    /** The take-off failed after the blocks were removed: the prefab, the pilot NPC and the contents come back. */
    private void restoreStructure(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation, UUID ownerUuid,
                                  String ownerName) {
        BalloonManager.get().pastePrefab(f.kind, f.world, store, origin, rotation,
                Ownership.landingOwner(f.ownership, ownerUuid, ownerName,
                        !AirshipLimit.countsForLimit(f.kind) || !AirshipLimit.ownsAirship(ownerUuid)));
        StructureShape hull = f.kind.shapeOrNull(); // flood and drain stay symmetric: the failed take-off flooded the hold
        if (hull != null) {
            drainHull(f.world, f.kind, hull, origin, rotation);
        }
        if (spec(f.kind).hasCrew() && f.crew != null) {
            BalloonRegistry.setCrew(f.world, origin, rotation, f.kind.id, f.crew);
            AirshipCrew.ensure(f.world, BalloonRegistry.get(f.world, origin, rotation, f.kind.id));
        }
        keepWater(f, origin, rotation);
        keepSky(f, origin, rotation);
        BalloonManager.get().restoreCargo(f.world, store, f.cargo, origin, rotation);
        f.engines.restore(f.world, store, origin, rotation);
        f.benches.restore(f.world, store, origin, rotation); // the level first: it sets the bench slots
        f.benchContents.restore(f.world, store, origin, rotation);
    }

    /** Seating point of the cabin stool in the prefab frame, as a passenger seat. */
    private static Vector3f crewStoolLocal(Deployables.Kind kind) {
        int[] rest = spec(kind).crewSpec.rest();
        return AirshipPassengers.seatLocal(new StructureShape.Cell(rest[0], rest[1], rest[2],
                "Airship_Stool", 0));
    }

    /** Mount offset of the pilot mannequin for a seat point of the prefab: (seat - pivot) with x and z inverted, as passenger mannequins. */
    private static Vector3f crewSeatOffset(Deployables.Kind kind, Vector3f seatLocal) {
        Vector3f d = new Vector3f(seatLocal).sub(pivot(kind));
        return new Vector3f(-d.x, d.y, -d.z);
    }

    /** dual rendering (pilot entity for the pilot, observer entity for the others) or the single entity of before. Next take-off. */
    volatile boolean renderDual = true;
    /** Model parts setting (next take-off). */
    volatile VehicleParts.Mode partsMode = VehicleParts.Mode.ON;

    /**
     * the ship has no pilot on board any more (dead, gone, left through a game mode change): one entity seen by everybody is enough.
     * The observer entity and the twin lights are removed, the ship entity, its lights and the helm helper become visible to everybody.
     * The landing alignment with a pilot keeps both entities (the pilot is still aboard). No effect in single rendering.
     */
    void collapseView(Store<EntityStore> store, AirshipFlight f) {
        if (!f.dual) {
            return;
        }
        PilotMannequin.remove(store, f.pilotMan, false); // the mannequin lives with the dual rendering
        f.pilotMan = null;
        f.dual = false;
        // the crew mannequin twin goes with the observer entity, the original becomes visible to everybody.
        AirshipCrew.collapseMannequin(store, f.crewMan);
        Ref<EntityStore> observer = f.observerRef;
        f.observerRef = null;
        VehicleView.removeObserver(store, observer);
        VehicleParts.collapse(store, f.parts);
        if (f.shipRef != null) {
            ViewFilter.clear(f.shipRef);
        }
        for (BalloonLights.Light l : f.lights) {
            BalloonLights.dropTwin(store, l);
        }
        if (f.helm != null) {
            ViewFilter.clear(f.helm.ref);
        }
        LOGGER.at(Level.INFO).log("Dirigeable de %s : affichage double terminé, une seule entité visible de tous", f.pilotName);
    }

    /**
     * a voyage ends: the captain's map goes (found by UUID among the world's players, the rider list may already be empty)
     * and the maps on the ground around the ship are swept one last time. Call before the riders are disembarked. Never throws.
     */
    private static void endVoyageCleanup(Store<EntityStore> store, AirshipFlight f) {
        try {
            if (f.voyage == null) {
                return;
            }
            UUID captain = f.voyage.captain;
            for (PlayerRef pr : f.world.getPlayerRefs()) {
                if (pr.getUuid().equals(captain)) {
                    CaptainMap.removeAll(store, pr.getReference());
                }
            }
            CaptainMap.sweepAround(store, f.pos);
            CaptainMap.forget(captain);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : nettoyage de fin en échec");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** removes the ship entity and its observer entity (and their visibility rules). Never throws. */
    private void removeShipEntities(Store<EntityStore> store, AirshipFlight f) {
        endVoyageCleanup(store, f);
        PilotMannequin.remove(store, f.pilotMan, false);
        f.pilotMan = null;
        AirshipCrew.removeMannequin(store, f.crewMan);
        f.crewMan = null;
        for (AirshipPassengers.Rider r : f.riders) {
            PassengerSeatView.remove(store, r.p);
        }
        PassengerSeatView.drain(store, f.seatViews);
        setEngineSound(store, f, false); // the end of the loop plays before the entity goes
        VehicleParts.removeAll(store, f.parts);
        f.parts = null;
        f.wheels = null;
        f.helmState = null;
        Ref<EntityStore> observer = f.observerRef;
        f.observerRef = null;
        f.dual = false;
        VehicleView.removeObserver(store, observer);
        if (f.shipRef != null) {
            ViewFilter.clear(f.shipRef);
            if (f.shipRef.isValid()) {
                store.removeEntity(f.shipRef, RemoveReason.REMOVE);
            }
        }
    }

    /** Mounts the ship entity on the pilot with a zero offset (the balloon's attach mode: the client glues it to the pilot). */
    private void attachShip(Store<EntityStore> store, AirshipFlight f) {
        store.putComponent(f.shipRef, MountedComponent.getComponentType(),
                new MountedComponent(f.pilotRef, new Vector3f(), MountController.Minecart));
        f.attached = true;
    }

    /** Takes the ship entity off the pilot (it is then moved by the server writes only, like the balloon's dry descent). */
    private void detachShip(Store<EntityStore> store, AirshipFlight f) {
        if (f.attached && f.shipRef != null && f.shipRef.isValid()) {
            store.tryRemoveComponent(f.shipRef, MountedComponent.getComponentType());
        }
        f.attached = false;
    }

    // ------------------------------------------------------------------ engines and fuel

    /** Travel time left with the fuel of the engines, in seconds of the ship's own travel (burn seconds divided by the factor). */
    private double travelSecondsLeft(AirshipFlight f) {
        return f.engines != null ? f.engines.remainingSeconds() / Math.max(0.01, fuelFactor) : 0;
    }

    private Message fuelMessage(AirshipFlight f) {
        return Texts.ship(f.kind, "fuel.level").param("time", BalloonManager.durationText(travelSecondsLeft(f)))
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
        applyShipSpeeds(store, f);
        if (announce) {
            PlayerRef pr = playerOf(store, f.pilotRef);
            if (pr != null) {
                pr.sendMessage(Texts.ship(f.kind, "fuel.dry"));
            }
        }
        LOGGER.at(Level.INFO).log("%s de %s : moteurs à sec, vitesse horizontale du pilote à 0", cap(spec(f.kind).logLabel), f.pilotName);
    }

    /** Horizontal flight speed of the pilot, or 0 if unreadable. Never throws. */
    private float readHorizontalSpeed(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            MovementManager mm = store.getComponent(ref, MovementManager.getComponentType());
            return mm == null ? 0f : mm.getSettings().horizontalFlySpeed;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vitesse de vol du pilote illisible");
            return 0f;
        }
    }

    /** Horizontal speed to give the pilot: 0 when dry, else the base speed, scaled when reversing. */
    static float pilotHorizontalSpeed(float base, boolean dry, boolean reversing, float reverseFactor) {
        if (dry) {
            return 0f;
        }
        return base * (reversing ? reverseFactor : 1f);
    }

    /**
     * Sets the pilot's flight speeds: horizontal 0 when dry (airship: the only thing it ever changes), for a waterborne ship the
     * base speed scaled by the reverse factor, and vertical 0 (the height is locked). Never throws.
     */
    private void applyShipSpeeds(Store<EntityStore> store, AirshipFlight f) {
        try {
            if (f.pilotless() || !f.pilotRef.isValid()) {
                return;
            }
            MovementManager mm = store.getComponent(f.pilotRef, MovementManager.getComponentType());
            PlayerRef pr = store.getComponent(f.pilotRef, PlayerRef.getComponentType());
            if (mm == null || pr == null) {
                return;
            }
            Deployables.AirshipSpec sp = spec(f.kind);
            if (sp != null && sp.waterborne()) {
                mm.getSettings().horizontalFlySpeed = pilotHorizontalSpeed(f.baseHorizontalSpeed, f.dry, f.reversing,
                        sp.reverseSpeedFactor);
                mm.getSettings().verticalFlySpeed = 0f;
            } else {
                mm.getSettings().horizontalFlySpeed = 0f;
            }
            mm.update(pr.getPacketHandler());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vitesses du pilote %s non réglées", f.pilotName);
            Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /**
     * Burns the engines' fuel for one travel sample. Called with the sample length and the measured travel speed (carry
     * excluded). Burns when travelling (speed above fuelMoveMin) or turning (mean turn rate above turnFuelMinDeg), nothing while
     * hovering, climbing or descending. Warns at 30 s and 10 s of travel
     * left, and goes dry when the fuel runs out.
     */
    void burnFuel(Store<EntityStore> store, AirshipFlight f, double seconds) {
        if (f.dry || f.engines == null) {
            f.burning = false;
            return;
        }
        boolean moving = f.travelSpeed > fuelMoveMin;
        double turnRateDeg = Math.toDegrees(f.windowTurn) / Math.max(seconds, 1e-3);
        f.moving = moving;
        // Turning needs thrust too: the mean turn rate over the window counts like travelling.
        boolean burns = moving || turnRateDeg > turnFuelMinDeg;
        f.burning = burns;
        if (!burns) {
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
            pr.sendMessage(Texts.ship(f.kind, "fuel.critical").param("time", BalloonManager.durationText(left)));
        } else if (left <= FUEL_LOW_S && !f.warned30) {
            f.warned30 = true;
            pr.sendMessage(Texts.ship(f.kind, "fuel.low").param("time", BalloonManager.durationText(left)));
        }
    }

    /**
     * Burner state machine, evaluated every tick while fuelled and lit. Wanted state: LEFT or RIGHT while the yaw rate (with
     * hysteresis, on at 2 deg/s, off below 1 deg/s) says the ship turns (yaw rate above 0 = bow toward -x = the pilot's left),
     * else FORWARD while the last travel sample burned fuel, else IDLE. Turning has priority over FORWARD. Leaving IDLE or entering
     * a turn is immediate, any other change waits until the wanted state has been stable for THRUST_HOLD_MS (no flicker).
     */
    void updateThrust(Store<EntityStore> store, AirshipFlight f, long now) {
        if (f.off || f.landing || (!spec(f.kind).fuelless && (f.dry || f.engines == null))) {
            return; // a fuelless ship (the shuttle) has no engines and is never dry: its thrust follows its movement
        }
        double rate = Math.toDegrees(f.yawRate);
        if (Math.abs(rate) >= TURN_FLAME_ON_DEG) {
            if (f.turnDir == 0) {
                // A new turn starts: per-turn diagnostics restart.
                f.turnRefused = f.refusedHeadings;
                f.turnPartial = f.partialSteps;
                f.turnSwaps = f.modelSwaps;
                f.turnBaseCollisions = f.collisionCount;
                f.turnBaseCarryTp = f.carryTeleports;
                f.turnTicks = 0;
                f.turnYawSends = 0;
                f.turnMaxStepDeg = 0;
                f.turnDtMin = Double.MAX_VALUE;
                f.turnDtMax = 0;
                f.turnDtSum = 0;
                f.turnDtN = 0;
            }
            f.turnDir = rate > 0 ? 1 : -1;
        } else if (Math.abs(rate) < TURN_FLAME_OFF_DEG) {
            f.turnDir = 0;
        }
        AirshipFlight.Thrust wanted = f.turnDir > 0 ? AirshipFlight.Thrust.LEFT : f.turnDir < 0 ? AirshipFlight.Thrust.RIGHT
                : f.moving ? AirshipFlight.Thrust.FORWARD : AirshipFlight.Thrust.IDLE;
        if (thrustModels == 0) {
            wanted = f.thrust; // diagnostic: never swap the model in flight
        } else if (thrustModels == 1 && (wanted == AirshipFlight.Thrust.LEFT || wanted == AirshipFlight.Thrust.RIGHT)) {
            wanted = AirshipFlight.Thrust.FORWARD; // turns keep the forward flames
        }
        if (wanted != f.thrustWanted) {
            f.thrustWanted = wanted;
            f.thrustWantedSinceMs = now;
        }
        if (wanted == f.thrust) {
            return;
        }
        boolean immediate = f.thrust == AirshipFlight.Thrust.IDLE || wanted == AirshipFlight.Thrust.LEFT
                || wanted == AirshipFlight.Thrust.RIGHT;
        if ((immediate || now - f.thrustWantedSinceMs >= THRUST_HOLD_MS) && now - f.lastSwapMs >= swapMinMs) {
            setThrust(store, f, wanted);
        }
    }

    /**
     * Swaps the burner model (only while fuelled and lit), cancels every exhaust particle system of the old model that the new
     * one does not carry, and turns the burner sound on in any flame state (off in IDLE). The firebox embers are in every model.
     * Never throws.
     */
    private void setThrust(Store<EntityStore> store, AirshipFlight f, AirshipFlight.Thrust to) {
        AirshipFlight.Thrust from = f.thrust;
        if (from == to) {
            return;
        }
        if (f.off || f.dry || f.shipRef == null || !f.shipRef.isValid()) {
            f.thrust = to;
            return;
        }
        String id = spec(f.kind).modelFor(to);
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset == null) {
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable : le modèle actuel reste affiché", id);
            return;
        }
        try {
            // Lot 4: the steamboat turn states share one model, no ModelUpdate (it could also reset the wheel animations).
            if (!id.equals(f.modelId)) {
                VehicleView.putModel(store, f.shipRef, f.observerRef, asset); // both entities
                f.modelId = id;
                if (f.wheels != null) {
                    PaddleWheels.onModelChanged(store, f, System.currentTimeMillis());
                }
                ShipHelm.onModelChanged(store, f, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Modèle %s non appliqué au dirigeable", id);
            Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            return;
        }
        f.thrust = to;
        f.modelSwaps++;
        f.lastSwapMs = System.currentTimeMillis();
        Set<String> keep = Set.of(spec(f.kind).exhaustSystems(to));
        for (String old : spec(f.kind).exhaustSystems(from)) {
            if (!keep.contains(old)) {
                cancelExhaust(f, old);
            }
        }
        setRoar(store, f, to != AirshipFlight.Thrust.IDLE);
    }

    /** Burner sound: the balloon's boost effect (infinite) on the ship entity while flames come out. Never throws. */
    private void setRoar(Store<EntityStore> store, AirshipFlight f, boolean on) {
        if (f.roar == on) {
            return;
        }
        f.roar = on;
        if (f.shipRef == null || !f.shipRef.isValid()) {
            return;
        }
        if (!BalloonManager.get().applyRoarEffect(store, f.shipRef, on) && on) {
            f.roar = false;
        }
        // the sound is played where the entity is drawn, so the observer entity carries the effect too.
        Ref<EntityStore> observer = f.observerRef;
        if (observer != null && observer.isValid()) {
            BalloonManager.get().applyRoarEffect(store, observer, on);
        }
    }

    static final String ENGINE_EFFECT = "Ship_Engine";

    /** The engine runs while the flight has engines and is not dry (pilot or not). */
    static boolean engineSoundWanted(boolean hasEngines, boolean dry) {
        return hasEngines && !dry;
    }

    /** Same, and a fuelless ship (the maritime shuttle) runs its engine for as long as it exists. */
    static boolean engineSoundWanted(boolean hasEngines, boolean dry, boolean fuelless) {
        return fuelless || engineSoundWanted(hasEngines, dry);
    }

    /** Engine sound: the Ship_Engine effect (infinite) on the ship entity and the observer entity. No-op if unchanged. Never throws. */
    private void setEngineSound(Store<EntityStore> store, AirshipFlight f, boolean on) {
        if (f.engineSound == on) {
            return;
        }
        f.engineSound = on;
        try {
            if (f.shipRef != null && f.shipRef.isValid()
                    && !BalloonManager.get().applyLoopEffect(store, f.shipRef, on, ENGINE_EFFECT) && on) {
                f.engineSound = false;
            }
            Ref<EntityStore> observer = f.observerRef;
            if (observer != null && observer.isValid()) {
                BalloonManager.get().applyLoopEffect(store, observer, on, ENGINE_EFFECT);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Son du moteur : changement d'état impossible");
        }
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
            Telemetry.error("airship.land", e, null, null);
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
                Telemetry.error("airship.flight", e, null, null);
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

    private List<Fx> collectParticles(World world, StructureShape s, Vector3i origin, Rotation rotation) {
        List<Fx> list = new ArrayList<>();
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (StructureShape.Cell c : s.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            Set<String> ids = new java.util.HashSet<>(particleIds(c.baseName()));
            BlockType actual = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
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

    /** Stops every burner flame and smoke system (all thrust models) and the firebox embers of a ship at this pose. */
    private void cancelSmoke(AirshipFlight f) {
        cancelEmbers(f);
        for (AirshipFlight.Thrust t : AirshipFlight.Thrust.values()) {
            for (String id : spec(f.kind).exhaustSystems(t)) {
                cancelExhaust(f, id);
            }
        }
    }

    /** Stops one particle system around the burner pipes (Metal_Iron_Pipe_Short cells) of a ship at this pose. */
    private void cancelExhaust(AirshipFlight f, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            StructureShape s = f.kind.shape();
            Vector3f pv = pivot(f.kind);
            Vector3d out = new Vector3d();
            for (StructureShape.Cell c : s.cells()) {
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

    private void spawnLights(Store<EntityStore> store, AirshipFlight f, StructureShape s) {
        if (!BalloonLights.enabled() || f.shipRef == null || !f.shipRef.isValid()) {
            return;
        }
        // one slot of the cap is kept for the firebox light, the block lights share the others.
        StructureShape.Cell firebox = fireboxCell(s);
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
            Vector3f d = new Vector3f(light.local).sub(pivot(f.kind));
            store.putComponent(light.ref, MountedComponent.getComponentType(),
                    new MountedComponent(f.shipRef, new Vector3f(-d.x, d.y, -d.z), MountController.Minecart));
            // this light is seen by the pilot only, its twin (on the observer entity) by everybody else.
            if (f.dual && f.observerRef != null && f.observerRef.isValid()) {
                BalloonLights.attachTwin(store, light, f.shipUuid, f.observerRef, new Vector3f(-d.x, d.y, -d.z),
                        lightPosition(f, light.local), color, f.pilotUuid);
            }
            f.lights.add(light);
            return light;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Lumière %s du dirigeable impossible", key);
            Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            return null;
        }
    }

    // ------------------------------------------------------------------ firebox glow

    /** Flicker period: the light colour is re-evaluated every FIREBOX_MIN_MS to FIREBOX_MIN_MS + FIREBOX_SPREAD_MS. */
    private static final long FIREBOX_MIN_MS = 100;
    /** The burner flames stay on this long after the last travel sample that burned fuel (no flicker at each 250 ms sample). */
    private static final long THRUST_HOLD_MS = 750;
    private static final long FIREBOX_SPREAD_MS = 100;
    /** Share of the distance to a new random target covered at each update (smooth flicker, no jumps from 0 to 1). */
    private static final double FIREBOX_SMOOTH = 0.55;

    private static StructureShape.Cell fireboxCell(StructureShape s) {
        for (StructureShape.Cell c : s.cells()) {
            if (AirshipEngines.ENGINE_BLOCK.equals(c.baseName())) {
                return c;
            }
        }
        return null;
    }

    /** Colour the firebox light should have now: null when dry (out), else the flicker colour of the current mode. */
    private static ColorLight fireboxWanted(AirshipFlight f) {
        if (!spec(f.kind).fuelless && (f.dry || f.engines == null || f.engines.isEmpty())) {
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
                    BalloonLights.setTwinColor(store, l, color);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Lumière du foyer du dirigeable non mise à jour");
                Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
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
            StructureShape.Cell c = fireboxCell(f.kind.shape());
            if (c == null) {
                return;
            }
            Vector3f pv = pivot(f.kind);
            Vector3d out = new Vector3d();
            AirshipMath.cellCenter(f.pos, f.theta, pv.x, pv.y, pv.z, c.x(), c.y(), c.z(), out);
            AirshipEngines.cancelEmbers(f.world.getPlayerRefs(), out.x, out.y, out.z);
        } catch (IOException | RuntimeException e) {
            // Cosmetic only.
        }
    }

    /** The helm helper entity (interactable, mounted on the ship): created next to the lights. */
    private void spawnHelm(Store<EntityStore> store, AirshipFlight f, StructureShape s) {
        if (f.shipRef == null || !f.shipRef.isValid() || s.anchor() == null) {
            return;
        }
        StructureShape.Cell a = s.anchor();
        Vector3f local = new Vector3f(a.x(), a.y() + 0.5f, a.z());
        f.helm = AirshipHelm.spawn(store, f.shipUuid, f.shipRef, local, pivot(f.kind), lightPosition(f, local));
        if (f.dual && f.helm != null) {
            ViewFilter.only(f.helm.ref, f.pilotUuid); // only the pilot can use it
        }
    }

    /** Glider jump: ejects the rider below the ship. False (nothing changed) if the player is not a rider of this flight. Never throws. */
    GliderJump.Result ejectRider(Store<EntityStore> store, AirshipFlight f, UUID uuid) {
        try {
            for (AirshipPassengers.Rider r : f.riders) {
                if (r.p.uuid.equals(uuid)) {
                    Vector3d exit = shipPoint(f, GliderJump.exitLocal(r.p.seatLocal,
                            GliderJump.lowestFlightY(f.kind.shape())));
                    if (GliderJump.exitBlocked(f.world, exit)) {
                        return GliderJump.Result.TOO_LOW;
                    }
                    if (!passengers.eject(store, f, r, exit)) {
                        return GliderJump.Result.FAILED;
                    }
                    LOGGER.at(Level.INFO).log("Saut en glider : passager du dirigeable %s éjecté en (%.1f, %.1f, %.1f)",
                            r.p.name, exit.x, exit.y, exit.z);
                    if (f.voyage != null && f.recall == null && uuid.equals(f.voyage.captain)) {
                        // The captain left the chair for good: his map is deleted and the voyage stops.
                        try {
                            CaptainMap.removeAll(store, r.p.ref);
                            CaptainMap.forget(uuid);
                        } catch (RuntimeException e) {
                            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : carte du capitaine non reprise");
                            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
                        }
                        AirshipAutopilot.captainAshore(f);
                    }
                    return GliderJump.Result.DONE;
                }
            }
            return GliderJump.Result.FAILED;
        } catch (RuntimeException | java.io.IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Saut en glider : éjection du passager du dirigeable en échec");
            Telemetry.error("airship.passenger", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            return GliderJump.Result.FAILED;
        }
    }

    /** World point of a prefab point of the ship at its current pose (pivot at pos, heading theta). */
    Vector3d shipPoint(AirshipFlight f, Vector3f local) {
        return lightPosition(f, local);
    }

    private Vector3d lightPosition(AirshipFlight f, Vector3f local) {
        Vector3f pv = pivot(f.kind);
        Vector3d r = AirshipMath.rotateY(local.x - pv.x, local.y - pv.y, local.z - pv.z, f.theta, new Vector3d());
        return r.add(f.pos);
    }

    private void followLights(Store<EntityStore> store, AirshipFlight f) {
        if (f.helm != null && f.helm.ref != null && f.helm.ref.isValid()) {
            TransformComponent lt = store.getComponent(f.helm.ref, TransformComponent.getComponentType());
            if (lt != null) {
                lt.setPosition(lightPosition(f, f.helm.local));
            }
        }
        for (BalloonLights.Light l : f.lights) {
            BalloonLights.positionBoth(store, l, lightPosition(f, l.local));
        }
    }

    private static void removeLights(Store<EntityStore> store, AirshipFlight f) {
        AirshipHelm.remove(store, f.helm);
        f.helm = null;
        for (BalloonLights.Light l : f.lights) {
            BalloonLights.removeBoth(store, l); // the twin too
        }
        f.lights.clear();
    }

    // ------------------------------------------------------------------ flight (every tick)

    void tick(Store<EntityStore> store, World world) {
        expireWatches();
        AirshipBenches.retry(store, world);
        AirshipBenchContents.retry(store, world);
        AirshipRecall.tickParked(store, world, System.currentTimeMillis()); // ships waiting above a station
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
                    passengers.update(store, world, f, now); // after this tick's pose
                }
            } catch (RuntimeException e) {
                if (now - f.lastCollisionLogMs > COLLISION_LOG_INTERVAL_MS) {
                    f.lastCollisionLogMs = now;
                    LOGGER.at(Level.WARNING).withCause(e).log("Erreur dans le tick du dirigeable %s", f.id);
                    Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
                }
            }
        }
    }

    private void tickOne(Store<EntityStore> store, World world, AirshipFlight f, long now) {
        if (f.shipRef == null || !f.shipRef.isValid()) {
            if (f.shuttle != null) {
                LOGGER.at(Level.WARNING).log("Entité de la navette %s perdue : secours", f.id);
                BoatAutopilot.rescue(store, f);
                return;
            }
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
        StructureShape s;
        try {
            s = f.kind.shape();
        } catch (IOException e) {
            return;
        }
        double dt = f.lastTickMs == 0 ? 0 : Math.min((now - f.lastTickMs) / 1000.0, MAX_DT);
        long nowNs = System.nanoTime();
        if (f.lastTickNs != 0) {
            // sub-millisecond tick interval (the millisecond clock jitters by +-3 % at 30 ticks per second).
            dt = Math.min((nowNs - f.lastTickNs) / 1e9, MAX_DT);
        }
        f.lastTickNs = nowNs;
        f.lastDt = dt;
        f.lastTickMs = now;
        if (f.turnDir != 0 && dt > 0) {
            f.turnDtMin = Math.min(f.turnDtMin, dt);
            f.turnDtMax = Math.max(f.turnDtMax, dt);
            f.turnDtSum += dt;
            f.turnDtN++;
        }

        Hull hull = hullOf(f.kind, s);
        if (f.quayStation != null) {
            AirshipRecall.releaseQuayIfLeft(f);
        }
        if (f.landing) {
            landingStep(store, world, f, s, hull, dt, now);
        } else if (f.pilotless() && f.shuttle != null) {
            // Maritime shuttle: sails its water route between two boat stations, never lands by itself.
            BoatAutopilot.step(store, world, f, dt, now);
        } else if (f.pilotless() && f.recall != null) {
            // the recalled ship flies to the station and hovers above it.
            AirshipRecall.step(store, world, f, dt, now);
        } else if (f.pilotless() && f.voyage != null && f.voyage.water != null) {
            // Steamboat voyage: the pilot NPC sails the planned path (BoatAutopilot), lands like an airship voyage.
            ShipVoyage.step(store, world, f, dt, now);
        } else if (f.pilotless() && f.voyage != null) {
            // the pilot NPC flies the ship to its destination.
            AirshipAutopilot.step(store, world, f, dt, now);
        } else if (f.pilotless()) {
            // A pilotless ship (dead pilot): it holds still, aligns itself and lands, retried every 5 s if impossible.
            if (!f.automatic || now >= f.nextLandingRetryMs) {
                f.automatic = true;
                startLanding(f, true);
            }
        } else if (!pilotStep(store, world, f, hull, dt, now)) {
            PaddleWheels.update(store, f, now); // the wheels keep their rules during the pause
            ShipHelm.update(store, f, now);
            return; // collision teleport or a stop was requested: the entity is not written this tick
        }
        if (f.finishing) {
            return; // landed during this tick: the entity is gone
        }
        if (hull.icebreaker) {
            IceBreaker.breakUnderHull(store, world, hull, f);
        }
        if (f.landing || f.pilotless()) {
            writeEntity(store, f);
        }
        recordWater(f, now);
        recordSky(f, now);
        followLights(store, f);
        flickerFirebox(store, f, now);
        setEngineSound(store, f, engineSoundWanted(f.engines != null, f.dry, spec(f.kind).fuelless));
        PaddleWheels.update(store, f, now);
        ShipHelm.update(store, f, now);
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
    private Vector3d pivotMinusCenter(Deployables.Kind kind) {
        return new Vector3d(pivot(kind).x, 0, pivot(kind).z - rotationCenterZ);
    }

    /** Pivot position after the heading goes from theta to newTheta around the rotation centre (the centre stays fixed). */
    Vector3d pivotAfterTurn(Deployables.Kind kind, Vector3d pos, double theta, double newTheta) {
        Vector3d c = centerWorld(kind, pos, theta);
        Vector3d pc = pivotMinusCenter(kind);
        Vector3d r = AirshipMath.rotateY(pc.x, 0, pc.z, newTheta, new Vector3d());
        return new Vector3d(c.x + r.x, pos.y, c.z + r.z);
    }

    /** Registers the log of a landed boat with its entry (a copy: the flight's log is world-thread only) and forgets the last cell. */
    private void keepWater(AirshipFlight f, Vector3i origin, Rotation rotation) {
        WaterLog w = f.water;
        if (w == null) {
            return;
        }
        BalloonRegistry.setWater(f.world, origin, rotation, f.kind.id, w.copy());
        w.forgetLastPosition();
    }

    /** Records the cell of the rotation centre every tick, and once a second the boat stations whose pier it passes. Never throws. */
    private void recordWater(AirshipFlight f, long now) {
        WaterLog w = f.water;
        if (w == null) {
            return;
        }
        try {
            Vector3d c = centerWorld(f.kind, f.pos, f.theta);
            w.record(c.x, c.z);
            if (now - f.waterScanMs < 1000) {
                return;
            }
            f.waterScanMs = now;
            for (TransitNetwork.Station s : TransitNetwork.get().stations(f.world.getName(), Deployables.BOAT_STATION_KIND)) {
                Vector3d p = StationMarkerProvider.markerPoint(s);
                double dx = p.x - c.x;
                double dz = p.z - c.z;
                if (dx * dx + dz * dz > (double) WaterLog.STATION_REACH * WaterLog.STATION_REACH) {
                    continue;
                }
                w.addReached(s.origin.x, s.origin.y, s.origin.z);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Journal des eaux : enregistrement impossible");
            Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), "water");
        }
    }

    /** Registers the sky log of a landed ship with its entry (a copy: the flight's log is world-thread only) and forgets the last cell. */
    private void keepSky(AirshipFlight f, Vector3i origin, Rotation rotation) {
        SkyLog k = f.sky;
        if (k == null) {
            return;
        }
        BalloonRegistry.setSky(f.world, origin, rotation, f.kind.id, k.copy());
        k.forgetLastPosition();
    }

    /** Records the cell of the rotation centre every tick, and once a second the stations within reach of it. Never throws. */
    private void recordSky(AirshipFlight f, long now) {
        SkyLog k = f.sky;
        if (k == null) {
            return;
        }
        try {
            Vector3d c = centerWorld(f.kind, f.pos, f.theta);
            k.record(c.x, c.z);
            if (now - f.skyScanMs < StationDiscovery.SCAN_INTERVAL_MS) {
                return;
            }
            f.skyScanMs = now;
            StationDiscovery.scan(k, TransitNetwork.get().stations(f.world.getName()), c.x, c.z);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Journal du ciel : enregistrement impossible");
            Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), "sky");
        }
    }

    /** World position of the rotation centre C for a ship whose pivot (pilot) is at pos with heading theta: C = P - R(theta)(P - C). */
    Vector3d centerWorld(Deployables.Kind kind, Vector3d pos, double theta) {
        Vector3d pc = pivotMinusCenter(kind);
        Vector3d r = AirshipMath.rotateY(pc.x, 0, pc.z, theta, new Vector3d());
        return new Vector3d(pos.x - r.x, pos.y, pos.z - r.z);
    }

    /** Pivot position for a rotation centre at c (world, y kept) and heading theta: P = C + R(theta)(P - C). */
    Vector3d pivotFromCenter(Deployables.Kind kind, Vector3d c, double theta) {
        Vector3d pc = pivotMinusCenter(kind);
        Vector3d r = AirshipMath.rotateY(pc.x, 0, pc.z, theta, new Vector3d());
        return new Vector3d(c.x + r.x, c.y, c.z + r.z);
    }

    /**
     * Displacement of the pilot (P, world frame) needed when the heading goes from theta0 to theta1 around C:
     * R(theta1)(P - C) - R(theta0)(P - C). The pilot's own flying moves C and P together and is not part of this.
     */
    private Vector3d carryDelta(Deployables.Kind kind, double theta0, double theta1) {
        Vector3d pc = pivotMinusCenter(kind);
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
            Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
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
        // The fly mode stays forced for the whole flight: only the helm (helper entity) or the land command ends it.
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
        Vector3d d = carryFor(f.kind, mode, f.theta, newTheta);
        Vector3d candidate = candidateFor(f, mode, pp, d);
        if (!Double.isNaN(f.lockedY)) {
            // Waterborne ship: the height is locked. A pilot that drifted off is put back, like a collision return.
            if (Math.abs(pp.y - f.lockedY) > 0.25) {
                f.yawRate = 0; f.yawAcc = 0;
                stopCarry(store, f);
                resetSample(f, f.pos, now);
                f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
                Vector3d fixed = new Vector3d(pp.x, f.lockedY, pp.z);
                Rotation3f lookLock = new Rotation3f(pt.getRotation());
                world.execute(() -> {
                    if (pilotRef.isValid()) {
                        store.putComponent(pilotRef, Teleport.getComponentType(), Teleport.createForPlayer(fixed, lookLock));
                    }
                });
                return false;
            }
            // Every later path (heading-only test, partial steps, fallback, carry target) uses the locked height too.
            pp.y = f.lockedY;
            candidate.y = f.lockedY;
        }
        int allowed = f.blockedNow;
        boolean changed = candidate.distanceSquared(f.pos) > 1e-12 || Math.abs(newTheta - f.theta) > 1e-9;
        if (changed && blocked(world, hull, candidate.x, candidate.y, candidate.z, newTheta, allowed) > allowed) {
            f.refusedHeadings++;
            boolean done = false;
            if (Math.abs(newTheta - f.theta) > 1e-9
                    && blocked(world, hull, pp.x, pp.y, pp.z, f.theta, allowed) <= allowed) {
                // Only the new heading collides. First try half and a quarter of the step (the rate follows), so the turn slows
                // instead of stopping dead and restarting from 0.
                double full = newTheta - f.theta;
                for (double frac = 0.5; retrySmaller && frac >= 0.2 && !done; frac *= 0.5) {
                    double th = f.theta + full * frac;
                    Vector3d d2 = carryFor(f.kind, mode, f.theta, th);
                    Vector3d c2 = candidateFor(f, mode, pp, d2);
                    if (blocked(world, hull, c2.x, c2.y, c2.z, th, allowed) <= allowed) {
                        newTheta = th;
                        d = d2;
                        candidate = c2;
                        f.yawRate *= frac;
                        f.yawAcc = 0;
                        f.partialSteps++;
                        done = true;
                    }
                }
                if (!done) {
                    // Keep the old heading, no carry this tick.
                    f.yawRate = 0;
                    f.yawAcc = 0;
                    newTheta = f.theta;
                    d = new Vector3d();
                    candidate = candidateFor(f, mode, pp, d);
                    done = true;
                }
            }
            if (!done) {
                collisionBack(store, world, f, hull, pt, pp, newTheta, now);
                return false;
            }
        }
        if (mode == Carry.TELEPORT) {
            f.carryAccX += d.x;
            f.carryAccZ += d.z;
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
        f.windowTurn += Math.abs(newTheta - f.theta); // applied heading change (after collision refusal), for the fuel burn
        if (f.turnDir != 0 || Math.abs(newTheta - f.theta) > 1e-9) {
            double stepDeg = Math.toDegrees(Math.abs(newTheta - f.theta));
            f.turnMaxStepDeg = Math.max(f.turnMaxStepDeg, stepDeg);
            f.turnTicks++;
            if (stepDeg > 1e-6) {
                f.turnYawSends++;
            }
        }
        accept(world, f, hull, candidate, newTheta);
        writeEntity(store, f);
        return true;
    }

    private Vector3d carryFor(Deployables.Kind kind, Carry mode, double from, double to) {
        return mode == Carry.OFF || Math.abs(to - from) < 1e-12 ? new Vector3d() : carryDelta(kind, from, to);
    }

    /** Pose candidate for a heading change with the carry d (velocity: pilot moves by d, teleport: owed carry plus d). */
    private Vector3d candidateFor(AirshipFlight f, Carry mode, Vector3d pp, Vector3d d) {
        Vector3d c = new Vector3d(pp);
        if (mode == Carry.VELOCITY) {
            c.add(d.x, 0, d.z);
        } else if (mode == Carry.TELEPORT) {
            c.add(f.carryAccX + d.x, 0, f.carryAccZ + d.z);
        }
        return c;
    }

    /** The pose is blocked: the pilot goes back to the last valid position (balloon logic: pause, throttled log). */
    private void collisionBack(Store<EntityStore> store, World world, AirshipFlight f, Hull hull, TransformComponent pt, Vector3d pp,
                               double newTheta, long now) {
        f.collisionCount++;
        f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
        f.yawRate = 0; f.yawAcc = 0;
        stopCarry(store, f);
        resetSample(f, f.pos, now);
        if (now - f.lastCollisionLogMs > BalloonManager.COLLISION_MSG_INTERVAL_MS) {
            f.lastCollisionLogMs = now;
            f.lastCollision = String.format(Locale.ROOT, "pos %.2f %.2f %.2f theta %.1f deg", pp.x, pp.y, pp.z, Math.toDegrees(newTheta));
            LOGGER.at(Level.INFO).log("Collision du dirigeable de %s : %s", f.pilotName, f.lastCollision);
        }
        // when the pose leaves the world's height range, the pilot is told (at most every 3 s).
        if (heightExceeded(hull, pp.y) && now - f.lastHeightMsgMs >= BalloonManager.HEIGHT_MSG_INTERVAL_MS) {
            f.lastHeightMsgMs = now;
            PlayerRef pilotPlayer = playerOf(store, f.pilotRef);
            if (pilotPlayer != null) {
                pilotPlayer.sendMessage(Texts.t("heightLimit.reached"));
            }
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
        f.restActive = false;
        f.restSinceMs = 0;
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
            f.windowTurn = 0;
            f.sampleMs = now;
            f.sampleX = pp.x;
            f.sampleZ = pp.z;
            f.sampleCarryX = 0;
            f.sampleCarryZ = 0;
            if (mode == Steer.TRAVEL) {
                f.hasTarget = false;
                boolean wasReversing = f.reversing;
                f.reversing = false;
                if (f.travelSpeed > turnSpeedMin) {
                    // The bow points to (-sin theta, -cos theta), so a travel direction (dx, dz) is the heading atan2(-dx, -dz).
                    f.travelDir = Math.atan2(-dx, -dz);
                    Deployables.AirshipSpec sp = spec(f.kind);
                    TravelTarget tt = travelTarget(f.travelDir, f.theta, Math.toRadians(reverseConeDeg),
                            sp != null && sp.sternFollowsInReverse);
                    f.reversing = tt.reversing();
                    if (!Double.isNaN(tt.heading())) {
                        f.targetHeading = tt.heading();
                        f.hasTarget = true;
                    }
                }
                if (wasReversing != f.reversing) {
                    Deployables.AirshipSpec sp = spec(f.kind);
                    if (sp != null && sp.waterborne()) {
                        applyShipSpeeds(store, f); // half speed while reversing
                    }
                }
            }
        }
        boolean noThrust = f.dry || f.engines == null;
        if (mode == Steer.TRAVEL && !noThrust && restTurn && f.sampleMs != 0 && now - f.takeoffMs >= TAKEOFF_IGNORE_MS) {
            restTurnStep(store, f, now);
        } else if (mode == Steer.TRAVEL) {
            f.restActive = false;
            f.restSinceMs = 0;
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
        if (noThrust) {
            // No thrust (dry or no engine data): the ship does not turn in any mode.
            f.hasTarget = false;
            f.restActive = false;
            f.restSinceMs = 0;
        }
        if (f.hasTarget) {
            double err = AirshipMath.wrapPi(f.targetHeading - f.theta);
            desired = Math.signum(err) * Math.min(maxRate, brakingRate(Math.abs(err), accel));
            stepRate(f, desired, accel, dt);
            step = f.yawRate * dt;
            if (Math.abs(step) >= Math.abs(err) || (Math.signum(f.yawRate) != Math.signum(err) && f.yawRate != 0
                    && Math.abs(err) < Math.toRadians(0.3))) {
                step = err;
                f.yawRate = 0;
                f.yawAcc = 0;
            }
        } else {
            stepRate(f, 0, accel, dt);
            step = f.yawRate * dt;
        }
        updateThrust(store, f, now);
        return f.theta + step;
    }

    /**
     * yaw rate controller. yawJerk 0: the rate approaches the wanted rate linearly (acceleration limit only). Otherwise the yaw
     * acceleration is itself limited in change (jerk), and eased toward zero as the rate nears the wanted one (sqrt profile), so there
     * is no abrupt start of the acceleration nor abrupt end. The rate never crosses the wanted rate.
     */
    void stepRate(AirshipFlight f, double desired, double accel, double dt) {
        double jerk = Math.toRadians(yawJerkDeg);
        if (jerk <= 0 || dt <= 1e-5) {
            f.yawRate = AirshipMath.approach(f.yawRate, desired, accel * dt);
            f.yawAcc = 0;
            return;
        }
        double diff = desired - f.yawRate;
        double aWanted = Math.signum(diff) * Math.min(accel, Math.sqrt(2 * jerk * Math.abs(diff)));
        f.yawAcc = AirshipMath.approach(f.yawAcc, aWanted, jerk * dt);
        double next = f.yawRate + f.yawAcc * dt;
        if ((next - desired) * (f.yawRate - desired) <= 0) {
            next = desired; // reached (or would cross) the wanted rate
            f.yawAcc = 0;
        }
        f.yawRate = next;
    }

    /**
     * Largest rate (rad/s) from which the ship can still stop within err radians braking at accel, with the jerk ramp
     * (stopping distance v^2/(2a) + v a/(2j), solved for v). Without jerk limit the plain sqrt(2 a err).
     */
    double brakingRate(double err, double accel) {
        double jerk = Math.toRadians(yawJerkDeg);
        if (jerk <= 0) {
            return Math.sqrt(2 * accel * err);
        }
        double k = accel * accel / jerk;
        return (-k + Math.sqrt(k * k + 8 * accel * err)) / 2;
    }

    /**
     * Rest turn. Not travelling (sampled travel speed at most turnSpeedMin): once the pilot's head yaw has stayed more than
     * restDeadZoneDeg away from the heading for restHoldMs, the heading target follows the head yaw until the error is under
     * restStopDeg. A head within reverseConeDeg of the stern drops the target (looking behind does not turn the ship). When the
     * pilot travels, the travel logic of the sample window decides and this does nothing.
     */
    private void restTurnStep(Store<EntityStore> store, AirshipFlight f, long now) {
        if (f.travelSpeed > turnSpeedMin) {
            f.restActive = false;
            f.restSinceMs = 0;
            return;
        }
        double head = headYaw(store, f);
        if (Double.isNaN(head)) {
            f.restActive = false;
            f.restSinceMs = 0;
            f.hasTarget = false;
            return;
        }
        double err = Math.abs(AirshipMath.wrapPi(head - f.theta));
        double fromStern = Math.abs(AirshipMath.wrapPi(head - (f.theta + Math.PI)));
        if (fromStern < Math.toRadians(reverseConeDeg)) {
            f.restActive = false;
            f.restSinceMs = 0;
            f.hasTarget = false;
            return;
        }
        if (!f.restActive) {
            if (err > Math.toRadians(restDeadZoneDeg)) {
                if (f.restSinceMs == 0) {
                    f.restSinceMs = now;
                } else if (now - f.restSinceMs >= restHoldMs) {
                    f.restActive = true;
                }
            } else {
                f.restSinceMs = 0;
            }
        } else if (err < Math.toRadians(restStopDeg)) {
            f.restActive = false;
            f.restSinceMs = 0;
        }
        f.hasTarget = f.restActive;
        if (f.restActive) {
            f.targetHeading = head;
        }
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
     * Gives a shuttle that left without one its pilot mannequin, standing at the helm (look from the station master). No effect when it
     * already has one or has no ship entity. Never throws.
     */
    void attachShuttleMannequin(Store<EntityStore> store, AirshipFlight f, Object[] look, long now) {
        try {
            if (f.crewMan != null || look == null || f.shipRef == null || !f.shipRef.isValid()) {
                return;
            }
            f.crewMan = AirshipCrew.createMannequin(store, f.shipUuid, f.shipRef, null, null, shipPoint(f, pivot(f.kind)), new Vector3f(),
                    look, now, AirshipCrew.POSE_STAND);
            if (f.crewMan != null) {
                LOGGER.at(Level.INFO).log("Navette %s : mannequin du pilote ajouté en route", f.id);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Navette %s : mannequin du pilote non ajouté", f.id);
        }
    }

    /**
     * Tries the candidate pose, then position-only, then yaw-only (landing alignment, the ship is moved by the server). Returns
     * true if the full candidate was refused (a collision). Nothing is tested if the pose did not change.
     */
    boolean move(World world, AirshipFlight f, Hull hull, Vector3d newPos, double newTheta, long now) {
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
            ShuttleState sh = f.sailState();
            if (sh != null) {
                // Evidence for route problems: which hull cell hits which world block, and whether the pose was a turn.
                boolean turn = Math.abs(newTheta - f.theta) > 1e-9;
                int[] cell = firstBlockingCell(world, hull, newPos.x, newPos.y, newPos.z, newTheta, allowed);
                sh.lastHit = cell;
                LOGGER.at(Level.INFO).log("Collision de la navette : %s, coque %s, cap %.1f -> %.1f deg, phase %s",
                        describeCell(world, cell),
                        turn ? "en rotation" : "en translation", Math.toDegrees(f.theta), Math.toDegrees(newTheta), sh.phase);
            }
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
    void writeEntity(Store<EntityStore> store, AirshipFlight f) {
        TransformComponent bt = store.getComponent(f.shipRef, TransformComponent.getComponentType());
        if (bt != null) {
            bt.setPosition(new Vector3d(f.pos));
            Rotation3f r = new Rotation3f();
            r.setYaw((float) visualYaw(f.theta));
            bt.setRotation(r);
            double alpha = observerSmoothMs <= 0 || f.lastDt <= 0 ? 1.0 : 1 - Math.exp(-f.lastDt * 1000.0 / observerSmoothMs);
            VehicleView.sync(store, f.shipRef, f.observerRef, alpha); // the observer entity follows the ship's pose
            VehicleParts.sync(store, f.parts, f.shipRef, f.observerRef); // after the smoothing: the parts copy their host
            // the pilot faces the bow, which points to (-sin theta, -cos theta): the entity yaw of a model faces the same way at yaw theta.
            PilotMannequin.sync(store, f.pilotMan, f.observerRef, (float) (yawSign * f.theta), null, System.currentTimeMillis());
            if (f.crewMan != null) {
                // a mounted entity keeps its own yaw, so the mannequin is turned with the ship: standing at the helm (in front of him,
                // bow side) he looks to the bow like the player's pilot, on the stool he looks the way the seat faces (localYaw)
                AirshipCrew.faceYaw(store, f.crewMan, (float) (yawSign * f.theta) + f.crewMan.localYaw);
            }
            AirshipCrew.syncMannequin(store, f.crewMan, System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------ landing

    /** /orbishorizon airship land, the helm: starts the alignment. */
    Message requestLanding(UUID pilot) {
        AirshipFlight f = flights.get(pilot);
        if (f == null) {
            return Texts.ship(null, "error.notPiloting");
        }
        if (f.landing) {
            return Texts.ship(f.kind, "landing.already");
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

    void startLanding(AirshipFlight f, boolean automatic) {
        // The pilot is held still for the alignment: no more carry. The alignment turns the ship around the pilot (he is frozen on
        // his deck), so C is simply re-derived from the pose afterwards.
        stopCarry(f.world.getEntityStore().getStore(), f);
        f.reversing = false; // a waterborne ship must not keep its half speed after an aborted landing
        Rotation[] byCloseness = quartersByCloseness(f.theta); // computed once: [0] is the closest quarter turn
        f.targetRotation = byCloseness[0];
        StructureShape bs = f.kind.shapeOrNull();
        if (bs != null && spec(f.kind).waterborne()) {
            // A boat takes the closest quarter turn whose pose at the pivot cell is not blocked (else the closest, refused at the end).
            Vector3f bpv = pivot(f.kind);
            Vector3i pc = f.pivotCell(f.pos);
            for (Rotation cand : byCloseness) {
                Vector3i o = new Vector3i(pc)
                        .sub(cand.rotateYaw(new Vector3i(Math.round(bpv.x), Math.round(bpv.y), Math.round(bpv.z)), new Vector3i()));
                if (!poseBlocked(f.world, f.kind, bs, o, cand)) {
                    f.targetRotation = cand;
                    break;
                }
            }
        }
        f.targetTheta = f.theta + AirshipMath.wrapPi(f.targetRotation.getRadians() - f.theta);
        f.targetPivot.set(f.pivotCell(f.pos));
        f.landing = true;
        f.automatic = automatic || f.automatic;
        // No propulsion during the alignment: back to the idle smoke.
        f.restActive = false;
        f.restSinceMs = 0;
        f.turnDir = 0;
        setThrust(f.world.getEntityStore().getStore(), f, AirshipFlight.Thrust.IDLE);
        f.landingStartMs = System.currentTimeMillis();
        f.landingStuckMs = 0;
        f.yawRate = 0; f.yawAcc = 0;
        f.hasTarget = false;
        if (!f.pilotless() && f.pilotRef.isValid()) {
            holdPilot(f);
        }
        if (!f.pilotless() && !automatic && f.pilotRef.isValid()) {
            PlayerRef pr = playerOf(f.world.getEntityStore().getStore(), f.pilotRef);
            if (pr != null) {
                pr.sendMessage(Texts.ship(f.kind, "landing.start"));
            }
        }
        LOGGER.at(Level.INFO).log("%s de %s : alignement pour l'atterrissage, quart de tour %s, case du pivot %s%s",
                cap(spec(f.kind).logLabel), f.pilotName,
                f.targetRotation, f.targetPivot, automatic ? " (automatique)" : "");
    }

    private static PlayerRef playerOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        return ref != null && ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : null;
    }

    private void landingStep(Store<EntityStore> store, World world, AirshipFlight f, StructureShape s, Hull hull, double dt, long now) {
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
        f.yawRate = 0; f.yawAcc = 0;
        if (before - after < 1e-6) {
            f.landingStuckMs += (long) (dt * 1000);
            if (f.landingStuckMs >= LANDING_STUCK_MS) {
                abortLanding(store, f, "airship.landing.stuck");
            }
        } else {
            f.landingStuckMs = 0;
        }
    }

    private void finalLanding(Store<EntityStore> store, World world, AirshipFlight f, StructureShape s) {
        Rotation q = f.targetRotation;
        Vector3f pv = pivot(f.kind);
        Vector3i origin = new Vector3i(f.targetPivot)
                .sub(q.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
        if (poseBlocked(world, f.kind, s, origin, q)) {
            abortLanding(store, f, "airship.landing.noRoom");
            return;
        }
        String zone = safeZone(world, f.kind.id, s, origin, q);
        if (zone != null) {
            if (!f.automatic) {
                abortLanding(store, f, Texts.t("deploy.stationZone").param("station", zone), "zone de la station " + zone);
                return;
            }
            logForcedZone(f, zone, origin);
        }
        // Exact pose before the blocks come back.
        f.theta = f.targetTheta;
        f.pos.set(f.targetPivot.x + 0.5, f.targetPivot.y, f.targetPivot.z + 0.5);
        finishLanding(store, f, origin, q);
    }

    private void abortLanding(Store<EntityStore> store, AirshipFlight f, String messageKey) {
        abortLanding(store, f, Texts.t(Texts.shipKey(f.kind, messageKey)), messageKey);
    }

    /** Zone lookup that can never break a pose: on failure it is logged and treated as "no zone". */
    private static String safeZone(World world, String kindId, StructureShape s, Vector3i origin, Rotation q) {
        try {
            return StationZone.blockingStation(world, kindId, s, origin, q);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Zone des stations non vérifiée pour le dirigeable (%s) : traitée comme absente", kindId);
            return null;
        }
    }

    private static void logForcedZone(AirshipFlight f, String station, Vector3i origin) {
        LOGGER.at(Level.INFO).log("Pose forcée dans la zone de la station %s (%s, origine %s)", station, f.kind.id, origin);
    }

    private void abortLanding(Store<EntityStore> store, AirshipFlight f, Message message, String reason) {
        f.landing = false;
        f.landingStuckMs = 0;
        long now = System.currentTimeMillis();
        f.nextLandingRetryMs = now + LANDING_RETRY_MS;
        if (f.voyage != null) {
            // a voyage climbs a few blocks and tries again (at most 10 times, then it lands where it is).
            LOGGER.at(Level.INFO).log("Voyage : atterrissage refusé (%s), nouvel essai dans %d s", reason, LANDING_RETRY_MS / 1000);
            AirshipAutopilot.onLandingRefused(f);
            return;
        }
        if (!f.pilotless()) {
            // Back to free flight: the entity is mounted on the pilot again, his flight speeds are restored and the
            // flight goes on (like the balloon when it cannot land).
            PlayerRef pr = playerOf(store, f.pilotRef);
            if (pr != null) {
                try {
                    BalloonManager manager = BalloonManager.get();
                    manager.setPilotFlying(store, f.pilotRef, pr, false, 1f);
                    manager.setPilotFlying(store, f.pilotRef, pr, true, flySpeedFactor);
                    f.baseHorizontalSpeed = readHorizontalSpeed(store, f.pilotRef);
                    Deployables.AirshipSpec sp = spec(f.kind);
                    if (f.dry || (sp != null && sp.waterborne())) {
                        applyShipSpeeds(store, f); // dry: no horizontal travel; boat: height locked again
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Vol libre du pilote %s non rétabli", f.pilotName);
                    Telemetry.error("airship.land", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
                }
                pr.sendMessage(message);
            }
            attachShip(store, f);
            f.takeoffMs = now;
            f.teleportUntilMs = now + BalloonManager.COLLISION_PAUSE_MS;
        }
        LOGGER.at(Level.INFO).log("Atterrissage du " + spec(f.kind).logLabel + " de %s annulé (%s)%s", f.pilotName, reason,
                f.automatic ? ", nouvel essai dans 5 s" : "");
    }

    /**
     * Pastes the prefab back at this origin and rotation, restores the chests, dismounts the pilot and puts him next to the seat,
     * removes the entity and the lights, deletes the resume file. Used by every way of ending the flight.
     */
    private void finishLanding(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        long t0 = Telemetry.start();
        try {
            finishLandingUntimed(store, f, origin, rotation);
        } finally {
            Telemetry.timing("land", t0, Telemetry.kindId(f.kind));
        }
    }

    private void finishLandingUntimed(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        if (flights.get(f.id) != f) {
            return;
        }
        f.finishing = true;
        flights.remove(f.id);
        stopCarry(store, f);
        removeLights(store, f);
        cancelSmoke(f);
        breakLandingIce(store, f.world, f.kind, origin, rotation);
        BalloonManager.get().pastePrefab(f.kind, f.world, store, origin, rotation,
                Ownership.landingOwner(f.ownership, f.pilotRef != null ? f.pilotUuid : null, f.pilotRef != null ? f.pilotName : null,
                        f.pilotRef == null || !AirshipLimit.countsForLimit(f.kind) || !AirshipLimit.ownsAirship(f.pilotUuid)));
        StructureShape hull = f.kind.shapeOrNull(); // a boat's hold is emptied of water
        if (hull != null) {
            drainHull(f.world, f.kind, hull, origin, rotation);
        }
        if (spec(f.kind).hasCrew() && f.crew != null) {
            if (f.recall != null || f.quayStation != null) {
                f.crew = f.crew.withPost(CrewRecord.Post.HELM); // the pilot is back at the helm after a recall
            }
            BalloonRegistry.setCrew(f.world, origin, rotation, f.kind.id, f.crew);
            // the pilot is recreated seated on the stool (found, adopted or created once its section is loaded).
            AirshipCrew.ensure(f.world, BalloonRegistry.get(f.world, origin, rotation, f.kind.id));
        }
        keepWater(f, origin, rotation);
        keepSky(f, origin, rotation);
        if (spec(f.kind).recall) {
            AirshipRecall.endQuay(f); // the quay is free again, whatever ended the recall
        }
        BalloonManager.get().restoreCargo(f.world, store, f.cargo, origin, rotation);
        if (f.engines != null) {
            f.engines.restore(f.world, store, origin, rotation); // fuel left and charcoal go back into the engines
        }
        if (f.benches != null) {
            f.benches.restore(f.world, store, origin, rotation); // upgraded benches get their level back (first: extra slots)
        }
        if (f.benchContents != null) {
            f.benchContents.restore(f.world, store, origin, rotation); // the contents of the other processing benches
        }
        releasePilot(store, f, origin, rotation);
        lowerAshoreLadder(store, f, origin, rotation); // the captain went down, one ladder is lowered
        announceArrival(store, f);
        endVoyageCleanup(store, f); // before the riders are released
        passengers.disembark(store, f, origin, rotation); // each passenger stands next to their stool
        AirshipResume.delete(f.id);
        setRoar(store, f, false); // the end sound plays before the entity goes
        removeShipEntities(store, f);
        LOGGER.at(Level.INFO).log("Atterrissage du " + spec(f.kind).logLabel + " de %s : origine %s, rotation %s", f.pilotName, origin, rotation);
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
            Vector3d spot = pilotSpot(f.kind, origin, rotation);
            if (pt != null) {
                pt.setPosition(spot); // also now: a deferred teleport may not be applied before a disconnecting player is saved
            }
            store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(spot, look));
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            if (pr != null) {
                pr.sendMessage(Texts.ship(f.kind, "landed"));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilote %s non reposé proprement", f.pilotName);
            Telemetry.error("airship.land", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** The pivot cell of the placed prefab, slightly above its floor: free since the stool was removed. */
    private static Vector3d pilotSpot(Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        Vector3f pv = pivot(kind);
        Vector3i cell = rotation.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()).add(origin);
        return new Vector3d(cell.x + 0.5, cell.y + LANDING_LIFT, cell.z + 0.5);
    }

    /**
     * Landing without animation at the nearest quarter turn (pilot disconnect, shutdown, lost entity): the current origin, then
     * up to SEARCH_UP blocks upward. Returns false if nothing fits (the flight is kept, the resume file stays).
     */
    boolean landImmediately(AirshipFlight f) {
        return landImmediatelyAt(f) != null;
    }

    /** Where a ship was put in blocks. */
    record Placed(Vector3i origin, Rotation rotation) {
    }

    /** {@link #landImmediately} that tells where the prefab was placed, null if it was not. */
    Placed landImmediatelyAt(AirshipFlight f) {
        if (flights.get(f.id) != f || f.finishing) {
            return null;
        }
        Store<EntityStore> store = f.world.getEntityStore().getStore();
        try {
            StructureShape s = f.kind.shape();
            Vector3f pv = pivot(f.kind);
            Vector3i pivotCell = f.pivotCell(f.pos);
            // An airship keeps the closest quarter turn and searches upward; a boat tries the four quarters at the current height.
            Rotation[] quarters = spec(f.kind).waterborne() ? quartersByCloseness(f.theta) : new Rotation[]{nearestQuarter(f.theta)};
            int maxUp = spec(f.kind).waterborne() ? 0 : SEARCH_UP;
            for (Rotation q : quarters) {
                Vector3i base = new Vector3i(pivotCell)
                        .sub(q.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
                for (int up = 0; up <= maxUp; up++) {
                    Vector3i origin = new Vector3i(base).add(0, up, 0);
                    if (!poseBlocked(f.world, f.kind, s, origin, q)) {
                        String zone = safeZone(f.world, f.kind.id, s, origin, q);
                        if (zone != null) {
                            logForcedZone(f, zone, origin);
                        }
                        f.theta = q.getRadians();
                        f.pos.set(pivotCell.x + 0.5, pivotCell.y + up, pivotCell.z + 0.5);
                        finishLanding(store, f, origin, q);
                        return new Placed(origin, q);
                    }
                }
            }
            LOGGER.at(Level.WARNING).log("Pose immédiate du dirigeable %s impossible : fichier de reprise gardé", f.id);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pose immédiate du dirigeable %s en échec", f.id);
            Telemetry.error("airship.land", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
        return null;
    }

    /** Capitalises the first letter of a log label. */
    static String cap(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static Rotation nearestQuarter(double theta) {
        return quartersByCloseness(theta)[0];
    }

    /** The four quarter turns, the closest to theta first, then by growing gap (tie: counterclockwise first). */
    static Rotation[] quartersByCloseness(double theta) {
        double deg = Math.toDegrees(theta);
        Integer[] idx = {0, 1, 2, 3};
        double[] gap = new double[4];
        double[] signed = new double[4];
        for (int i = 0; i < 4; i++) {
            double d = Math.floorMod(Math.round((i * 90.0 - deg) * 1e6), 360_000_000L) / 1e6; // 0..360, quarter minus theta
            if (d > 180.0) {
                d -= 360.0;
            }
            signed[i] = d;
            gap[i] = Math.abs(d);
        }
        // signed > 0: the quarter is reached by increasing the angle (counterclockwise), it wins ties
        java.util.Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(Math.round(gap[a] * 1e6), Math.round(gap[b] * 1e6));
            if (c != 0) {
                return c;
            }
            return Double.compare(-signed[a], -signed[b]);
        });
        Rotation[] out = new Rotation[4];
        for (int i = 0; i < 4; i++) {
            out[i] = Rotation.ofDegrees(idx[i] * 90);
        }
        return out;
    }

    /** Whether the pose is refused: a boat needs water under its waterline and room above it, any other ship just room. */
    boolean poseBlocked(World world, Deployables.Kind kind, StructureShape s, Vector3i origin, Rotation rotation) {
        if (spec(kind).waterborne()) {
            return BalloonManager.exceedsHeight(s, origin, rotation)
                    || DeployPlacement.steamboatFault(s, origin, rotation, DeployPlacement.iceState(world, origin.y
                    + Deployables.STEAMBOAT.airship.waterlineY),
                    Deployables.STEAMBOAT.airship.waterlineY) != null;
        }
        return BalloonManager.collides(world, s, origin, rotation);
    }

    /** A boat about to be pasted on floe ice breaks it first, as the hull does when sailing. Airships are left alone. */
    private static void breakLandingIce(Store<EntityStore> store, World world, Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        StructureShape s = kind.shapeOrNull();
        if (s != null && spec(kind).waterborne()) {
            IceBreaker.breakFootprint(store, world, kind, s, origin, rotation);
        }
    }

    private static void floodHull(World world, Deployables.Kind kind, StructureShape s, Vector3i origin, Rotation rotation) {
        Integer line = spec(kind).waterlineY;
        if (line != null) {
            SteamboatWater.flood(world, s, origin, rotation, line);
        }
    }

    private static void drainHull(World world, Deployables.Kind kind, StructureShape s, Vector3i origin, Rotation rotation) {
        Integer line = spec(kind).waterlineY;
        if (line != null) {
            SteamboatWater.drain(world, s, origin, rotation, line);
        }
    }

    // ------------------------------------------------------------------ pilot's death, disconnect, shutdown

    /** the voyage flights whose captain is this player (the captain is a rider, the flight id is random). */
    private List<AirshipFlight> voyagesOf(UUID captain) {
        List<AirshipFlight> out = new ArrayList<>();
        for (AirshipFlight f : flights.values()) {
            if (f.voyage != null && f.recall == null && captain != null && captain.equals(f.voyage.captain)) {
                out.add(f); // a recall goes on without its owner
            }
        }
        return out;
    }

    /**
     * ladder side (+1 or -1) nearest to the captain now, chosen when the ship is moored. -1 if he is offline, dead, in another
     * world or unreadable. Never throws.
     */
    int ashoreSideNow(AirshipFlight f) {
        try {
            UUID cap = f.voyage != null ? f.voyage.captain : null;
            if (cap == null) {
                return -1;
            }
            for (PlayerRef pr : f.world.getPlayerRefs()) {
                if (!cap.equals(pr.getUuid())) {
                    continue;
                }
                Ref<EntityStore> ref = pr.getReference();
                Store<EntityStore> store = f.world.getEntityStore().getStore();
                if (ref == null || !ref.isValid() || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                    return -1;
                }
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                if (t != null) {
                    return AirshipAutopilot.ashoreSide(t.getPosition().x - f.pos.x, t.getPosition().z - f.pos.z, f.theta);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : côté de l'échelle du capitaine illisible");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
        return -1;
    }

    /** the captain left (death, disconnect, game mode): the ship is put down in place. Call on the world thread. */
    private void landVoyagesOf(UUID captain, String why) {
        landVoyagesOf(captain, why, false);
    }

    /** ashore false = put down in place at once, true = descend to the ground first and lower a ladder. */
    private void landVoyagesOf(UUID captain, String why, boolean ashore) {
        for (AirshipFlight f : voyagesOf(captain)) {
            if (f.finishing) {
                continue;
            }
            try {
                if (ashore && f.recall == null && AirshipAutopilot.captainAshore(f)) {
                    LOGGER.at(Level.INFO).log("Voyage : capitaine %s (%s), le dirigeable descend avant de se poser", why, captain);
                    continue;
                }
                if (AirshipRecall.divertToLeave(f)) {
                    LOGGER.at(Level.INFO).log("Voyage : capitaine %s (%s), le dirigeable s'éloigne de la station avant de se poser", why, captain);
                    continue;
                }
                boolean ok = landImmediately(f);
                LOGGER.at(ok ? Level.INFO : Level.WARNING).log("Voyage : capitaine %s (%s), dirigeable %s", why, captain,
                        ok ? "posé sur place" : "pose impossible, arrêt du voyage");
                if (!ok) {
                    AirshipAutopilot.stop(f, "crew.voyage.captainGone");
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Voyage : pose après départ du capitaine en échec");
                Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            }
        }
    }

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
                        passengers.releaseDead(store, f, r); // the flight goes on, the player respawns normally
                        LOGGER.at(Level.INFO).log("Passager du dirigeable %s mort en vol : libéré", r.p.name);
                    }
                }
                try {
                    PlayerRef pr = ref.isValid() ? playerOf(store, ref) : null;
                    if (pr != null && f.voyage != null && pr.getUuid().equals(f.voyage.captain)) {
                        landVoyagesOf(pr.getUuid(), "mort", true);
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Voyage : mort du capitaine non traitée");
                    Telemetry.error("airship.passenger", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
                }
            }
        });
    }

    /**
     * a pilot or rider changed game mode (called by GameModeSystem while the event is handled, the mode has not changed yet).
     * Processed on the world thread after the change, only if the mode really changed. Pilot: immediate landing in place
     * (landImmediately, nearest quarter turn, up to SEARCH_UP blocks upward), else pilotless automatic landing (pilotDied without the
     * death). Rider: released and removed from the flight. The player is put on the ground if the new mode cannot fly, and the
     * new mode's movement settings are re-applied after the mod's resets (BalloonManager.reapplyGameMode).
     */
    void onGameModeChange(Store<EntityStore> store, Ref<EntityStore> ref, GameMode newMode) {
        boolean involved = false;
        for (AirshipFlight f : flights.values()) {
            if (ref.equals(f.pilotRef)) {
                involved = true;
            }
            for (AirshipPassengers.Rider r : f.riders) {
                if (ref.equals(r.p.ref)) {
                    involved = true;
                }
            }
        }
        if (!involved) {
            return;
        }
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getGameMode() != newMode) {
                return; // change cancelled
            }
            for (AirshipFlight f : new ArrayList<>(flights.values())) {
                if (f.world != world || f.finishing) {
                    continue;
                }
                if (ref.equals(f.pilotRef)) {
                    TransformComponent pt = store.getComponent(ref, TransformComponent.getComponentType());
                    Vector3d pilotPos = pt != null ? new Vector3d(pt.getPosition()) : null;
                    boolean landed = landImmediately(f);
                    if (!landed) {
                        pilotDied(store, f, false);
                    }
                    boolean canFly = BalloonManager.reapplyGameMode(store, ref, newMode);
                    if (!landed && !canFly && pilotPos != null) {
                        BalloonManager.putOnGround(store, world, ref, pilotPos);
                    }
                    PlayerRef pr = playerOf(store, ref);
                    if (pr != null) {
                        pr.sendMessage(Texts.t("gameMode.left"));
                    }
                    LOGGER.at(Level.INFO).log("Pilote %s : changement de mode de jeu (%s), dirigeable %s", f.pilotName, newMode,
                            landed ? "posé sur place" : "sans pilote");
                    continue;
                }
                for (AirshipPassengers.Rider r : new ArrayList<>(f.riders)) {
                    if (ref.equals(r.p.ref)) {
                        passengers.leaveForGameMode(store, f, r, newMode);
                        LOGGER.at(Level.INFO).log("Passager du dirigeable %s : changement de mode de jeu (%s), libéré", r.p.name, newMode);
                    }
                }
                try {
                    PlayerRef cpr = playerOf(store, ref);
                    if (cpr != null && f.voyage != null && cpr.getUuid().equals(f.voyage.captain)) {
                        landVoyagesOf(cpr.getUuid(), "changement de mode de jeu");
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Voyage : changement de mode du capitaine non traité");
                    Telemetry.error("airship.passenger", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
                }
            }
        });
    }

    /**
     * The pilot died (mirrors the balloon's): the ship entity leaves him, his movement settings go back to normal, the ship
     * loses its pilot, its flight gets a new identifier (the respawned player can fly something else), the off model is shown
     * and the ship aligns itself and lands in place, retried every 5 s if impossible.
     */
    private void pilotDied(Store<EntityStore> store, AirshipFlight f) {
        pilotDied(store, f, true);
    }

    /** Same: dead false when the pilot leaves because of a game mode change (other message, same handling). */
    private void pilotDied(Store<EntityStore> store, AirshipFlight f, boolean dead) {
        if (f.pilotless() || flights.get(f.id) != f) {
            return;
        }
        Ref<EntityStore> ref = f.pilotRef;
        stopCarry(store, f);
        detachShip(store, f);
        collapseView(store, f);
        restorePilotMovement(store, ref);
        if (dead) {
            // Take-over landing: pilotRef is nulled first so releasePilot neither teleports nor messages the dead pilot.
            f.takeOver = true;
            f.pilotRef = null;
            f.yawRate = 0; f.yawAcc = 0;
            f.hasTarget = false;
            if (landImmediately(f)) {
                LOGGER.at(Level.INFO).log("Pilote %s mort en vol : dirigeable posé sur place", f.pilotName);
                return;
            }
            f.takeOver = false;
        }
        UUID oldId = f.id;
        flights.remove(oldId);
        f.id = UUID.randomUUID();
        f.pilotRef = null;
        f.yawRate = 0; f.yawAcc = 0;
        f.hasTarget = false;
        f.landing = false;
        f.automatic = true;
        f.nextLandingRetryMs = 0;
        showOffModel(store, f);
        AirshipPassengers.tell(store, f, dead ? "airship.passenger.pilotDied" : "airship.passenger.pilotLeft");
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
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(spec(f.kind).offModelId);
        if (asset != null) {
            VehicleView.putModel(store, f.shipRef, f.observerRef, asset); // both entities
            f.modelId = spec(f.kind).offModelId;
            f.off = true;
            if (f.wheels != null) {
                PaddleWheels.onModelChanged(store, f, System.currentTimeMillis());
            }
            ShipHelm.onModelChanged(store, f, System.currentTimeMillis());
            f.thrust = AirshipFlight.Thrust.IDLE;
            f.thrustWanted = AirshipFlight.Thrust.IDLE;
            f.turnDir = 0;
            f.restActive = false;
        } else {
            LOGGER.at(Level.WARNING).log("Modèle %s introuvable : le modèle allumé reste affiché", spec(f.kind).offModelId);
        }
        setFireboxColor(store, f, null); // the dark firebox of the off model has no light
        setRoar(store, f, false);
        setEngineSound(store, f, false);
        cancelSmoke(f);
    }

    /**
     * PlayerDisconnectEvent: a pilot puts the ship down (onPilotGone), a passenger is released and saved on the ground under their
     * stool, the flight goes on.
     */
    void onPlayerGone(UUID uuid) {
        onPilotGone(uuid);
        List<AirshipFlight> voyages = voyagesOf(uuid);
        for (AirshipFlight f : new ArrayList<>(flights.values())) {
            if (voyages.contains(f)) {
                continue; // the captain is handled below: the ship is put down first, he is saved on its deck if it was
            }
            for (AirshipPassengers.Rider r : new ArrayList<>(f.riders)) {
                if (!r.p.uuid.equals(uuid)) {
                    continue;
                }
                boolean ok = BalloonManager.runOnWorld(f.world, () -> {
                    if (f.shuttle != null) {
                        BoatQuay.dropRider(f, r); // back on the departure pier, never an instant arrival
                    } else {
                        passengers.dropOne(f.world.getEntityStore().getStore(), f, r);
                    }
                }, LAND_WAIT_MS);
                LOGGER.at(ok ? Level.INFO : Level.WARNING).log(ok
                        ? "Passager du dirigeable %s parti : libéré, position enregistrée au sol"
                        : "Passager du dirigeable %s parti : libération impossible", r.p.name);
            }
        }
        for (AirshipFlight f : voyages) {
            try {
                boolean[] landed = {false};
                boolean[] diverted = {false};
                boolean ok = BalloonManager.runOnWorld(f.world, () -> {
                    if (AirshipRecall.divertToLeave(f)) {
                        landed[0] = true; // flies away from the station first, lands at the leave point
                        diverted[0] = true;
                        return;
                    }
                    landed[0] = landImmediately(f); // disembark puts the captain on the deck next to the chair
                    if (!landed[0]) {
                        AirshipAutopilot.stop(f, "crew.voyage.captainGone");
                    }
                }, LAND_WAIT_MS);
                if (leaveSpot(ok && landed[0], diverted[0]) == LeaveSpot.GROUND) {
                    for (AirshipPassengers.Rider r : new ArrayList<>(f.riders)) {
                        if (r.p.uuid.equals(uuid)) {
                            BalloonManager.runOnWorld(f.world, () -> passengers.dropOne(f.world.getEntityStore().getStore(), f, r), LAND_WAIT_MS);
                        }
                    }
                }
                LOGGER.at(ok && landed[0] ? Level.INFO : Level.WARNING).log(ok && landed[0]
                        ? "Voyage : capitaine %s parti, dirigeable reposé"
                        : "Voyage : capitaine %s parti, pose impossible : arrêt du voyage", uuid);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Voyage : départ du capitaine non traité");
                Telemetry.error("airship.passenger", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            }
        }
    }

    /** Where a disconnecting captain is saved. */
    enum LeaveSpot { DECK, GROUND }

    /** The ship is in blocks (posed, not diverted): the captain stays on its deck, else on the ground under his seat. */
    static LeaveSpot leaveSpot(boolean posed, boolean diverted) {
        return posed && !diverted ? LeaveSpot.DECK : LeaveSpot.GROUND;
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
            boolean ok = BalloonManager.runOnWorld(f.world, () -> landForShutdown(f), LAND_WAIT_MS);
            if (!ok) {
                LOGGER.at(Level.WARNING).log("Dirigeable %s non reposé : fichier de reprise gardé", f.id);
            }
        }
        AirshipBenchContents.flushAll(LAND_WAIT_MS); // bench contents still waiting for their bench are not lost with the resume file
    }

    /**
     * Shutdown landing. A recalled ship (or a converted voyage that still holds the quay of a station) is first moved to its leave point,
     * never put down above the station: if the sections there are not loaded and the ship is inside the zone, nothing is placed and the
     * resume file (key "recall") puts it down at the leave point at the next load.
     */
    private boolean landForShutdown(AirshipFlight f) {
        if (f.shuttle != null) {
            // The shuttle is never put down in blocks at a shutdown: its riders go back to the departure pier first.
            BoatQuay.dropAllRiders(f);
            endShuttle(f.world.getEntityStore().getStore(), f, "arrêt du serveur");
            return true;
        }
        RecallState r = f.recall != null ? f.recall : (f.quayStation != null ? f.recallInfo : null);
        if (r != null) {
            try {
                double[] p = AirshipRecall.retreatPoint(r, f.world);
                int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
                int y = (int) Math.floor(f.pos.y);
                if (SectionBlocks.sectionsLoaded(f.world.getChunkStore(), px - 16, y - 12, pz - 16, px + 16, y + 14 + SEARCH_UP, pz + 16)) {
                    f.pos.set(px + 0.5, f.pos.y, pz + 0.5);
                    LOGGER.at(Level.INFO).log("Rappel : arrêt du serveur, le dirigeable est posé à son point d'éloignement (%d, %d)", px, pz);
                } else if (f.quayStation != null && !AirshipRecall.outsideZone(f, f.quayStation)) {
                    LOGGER.at(Level.WARNING).log("Rappel : point d'éloignement non chargé, dirigeable %s non posé (fichier de reprise gardé)", f.id);
                    return false;
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Rappel : point d'éloignement non calculé pour %s", f.id);
                Telemetry.error("airship.land", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            }
        }
        return landImmediately(f);
    }

    /**
     * A station is removed while a ship of this world flies a recall (or a converted voyage that holds its quay): it is put down in
     * place (the zone is gone with the station), its pilot back at the helm. Falls back to the pilotless automatic landing.
     */
    void onStationRemoved(World world, TransitNetwork.Station removed) {
        for (AirshipFlight f : new ArrayList<>(flights.values())) {
            if (f.world != world || f.finishing) {
                continue;
            }
            RecallState r = f.recall != null ? f.recall : (f.quayStation != null ? f.recallInfo : null);
            if (r == null || r.station != removed) {
                continue;
            }
            try {
                LOGGER.at(Level.INFO).log("Rappel : station %s retirée, le dirigeable %s se pose sur place", removed.name, f.id);
                f.quayStation = null; // no endRecall: the quay of the removed station was dropped with it
                f.recall = null;
                f.recallInfo = null;
                if (f.crew != null) {
                    f.crew = f.crew.withPost(CrewRecord.Post.HELM);
                }
                if (!landImmediately(f)) {
                    f.voyage = null; // the pilotless automatic landing takes over
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Rappel : dirigeable %s non posé après le retrait de la station", f.id);
                Telemetry.error("airship.recall", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            }
        }
    }

    // ------------------------------------------------------------------ resume (world start)

    void recoverWorld(World world) {
        AirshipRecall.recoverWorld(world); // ships waiting in blocks above a station
        if (Deployables.SHIPS.stream().anyMatch(k -> spec(k).ladders)) {
            ShipLadder.recoverWorld(world); // rope ladders kept in the registry
        }
        for (AirshipResume.Record rec : AirshipResume.loadAll(world.getName())) {
            if (flights.containsKey(rec.id()) || !recovering.add(rec.id())) {
                continue;
            }
            try {
                // Vertical span of the ship around the pivot (the landing search can go up to 8 blocks higher).
                Deployables.Kind kind = rec.kind();
                StructureShape ship = kind.shape();
                Vector3f pv = pivot(kind);
                int dyMin = Integer.MAX_VALUE, dyMax = Integer.MIN_VALUE;
                for (StructureShape.Cell c : ship.cells()) {
                    dyMin = Math.min(dyMin, c.y() - Math.round(pv.y));
                    dyMax = Math.max(dyMax, c.y() - Math.round(pv.y));
                }
                // recoverOne may shift the pose to fit the height range (down near the top of the world): the sections of the
                // shifted pose are loaded too. A ship that cannot fit at all is refused by recoverOne, its pose is loaded as it is.
                int shift = resumeHeightShift(kind, rec, ship);
                if (shift == Integer.MIN_VALUE) {
                    shift = 0;
                }
                // A recalled ship is put down at its leave point, never above the station.
                int px = rec.recall() != null ? (int) Math.floor(rec.recall().retreatX()) : rec.pivotX();
                int pz = rec.recall() != null ? (int) Math.floor(rec.recall().retreatZ()) : rec.pivotZ();
                LOGGER.at(Level.INFO).log("Reprise du vol de dirigeable %s dans %s : pivot (%d, %d, %d), sections à charger",
                        rec.id(), world.getName(), px, rec.pivotY(), pz);
                SectionBlocks.loadSections(world.getChunkStore(), px - 16, rec.pivotY() + dyMin + Math.min(0, shift), pz - 16,
                        px + 16, rec.pivotY() + dyMax + Math.max(0, shift) + 8, pz + 16).whenComplete((v, t) -> {
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
            } catch (RuntimeException | java.io.IOException e) {
                recovering.remove(rec.id());
                LOGGER.at(Level.SEVERE).withCause(e).log("Reprise du vol de dirigeable %s impossible : fichier gardé", rec.id());
                Telemetry.error("airship.recover", e, null, null);
            }
        }
    }

    /** vertical shift that brings the resumed pose (nearest quarter turn) back into the height range, as BalloonManager.heightShift. */
    private static int resumeHeightShift(Deployables.Kind kind, AirshipResume.Record rec, StructureShape s) {
        Rotation q0 = nearestQuarter(rec.heading());
        Vector3f pv0 = pivot(kind);
        Vector3i base0 = new Vector3i(rec.pivotX(), rec.pivotY(), rec.pivotZ())
                .sub(q0.rotateYaw(new Vector3i(Math.round(pv0.x), Math.round(pv0.y), Math.round(pv0.z)), new Vector3i()));
        return BalloonManager.heightShift(s, base0, q0);
    }

    private void recoverOne(World world, AirshipResume.Record rec) {
        try {
            if (!AirshipResume.exists(rec.id())) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            Deployables.Kind kind = rec.kind();
            StructureShape s = kind.shape();
            // a pose outside the world's height range is shifted to fit (the search below then goes on from there). If the
            // ship cannot fit at all, the file is kept untouched with a warning, before anything is emptied or placed.
            int heightDy = 0;
            {
                heightDy = resumeHeightShift(kind, rec, s);
                if (heightDy == Integer.MIN_VALUE) {
                    LOGGER.at(Level.WARNING).log("Reprise du dirigeable %s impossible : il dépasse la hauteur du monde, fichier gardé", rec.id());
                    return;
                }
                if (heightDy != 0) {
                    LOGGER.at(Level.WARNING).log("Reprise du dirigeable %s : pose hors de la limite de hauteur, décalée de %d bloc(s)", rec.id(), heightDy);
                }
            }
            if (rec.ship() != null) {
                Ref<EntityStore> orphan = world.getEntityStore().getRefFromUUID(rec.ship());
                if (orphan != null && orphan.isValid()) {
                    store.removeEntity(orphan, RemoveReason.REMOVE);
                    LOGGER.at(Level.INFO).log("Entité du dirigeable restée dans le monde supprimée (%s)", rec.ship());
                }
            }
            BalloonLights.removeOrphans(world, store, rec.ship(), s);
            VehicleParts.removeOrphans(world, store, rec.ship());
            PilotMannequin.removeOrphan(world, store, rec.ship());
            AirshipCrew.removeMannequinOrphan(world, store, rec.ship());
            if (rec.riders() != null) {
                PassengerSeatView.removeOrphans(world, store, rec.ship(), rec.riders().stream().map(AirshipPassengers.Saved::uuid).toList());
            }
            if (rec.ship() != null) {
                Ref<EntityStore> helm = world.getEntityStore().getRefFromUUID(AirshipHelm.uuidFor(rec.ship()));
                if (helm != null && helm.isValid()) {
                    store.removeEntity(helm, RemoveReason.REMOVE);
                }
            }
            // If the world was saved before take-off, the old ship and its contents are still there: the file is authoritative.
            removeOldShip(kind, world, s, rec);
            Rotation q = nearestQuarter(rec.heading());
            Vector3f pv = pivot(kind);
            boolean boat = spec(kind).waterborne();
            int pvx = rec.recall() != null ? (int) Math.floor(rec.recall().retreatX()) : rec.pivotX();
            int pvz = rec.recall() != null ? (int) Math.floor(rec.recall().retreatZ()) : rec.pivotZ();
            Vector3i base = new Vector3i(pvx, rec.pivotY(), pvz)
                    .sub(q.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
            base.add(0, heightDy, 0);
            Vector3i origin = new Vector3i(base);
            if (boat) {
                // A boat tries the four quarters at the saved height only; none fits: the saved pose is pasted as is.
                Vector3i pcell = new Vector3i(pvx, rec.pivotY() + heightDy, pvz);
                for (Rotation cand : quartersByCloseness(rec.heading())) {
                    Vector3i o = new Vector3i(pcell)
                            .sub(cand.rotateYaw(new Vector3i(Math.round(pv.x), Math.round(pv.y), Math.round(pv.z)), new Vector3i()));
                    if (!poseBlocked(world, kind, s, o, cand)) {
                        origin = o;
                        q = cand;
                        break;
                    }
                }
            } else {
                for (int up = 0; up <= SEARCH_UP; up++) {
                    Vector3i o = new Vector3i(base).add(0, up, 0);
                    if (!poseBlocked(world, kind, s, o, q)) {
                        origin = o;
                        break;
                    }
                }
            }
            breakLandingIce(store, world, kind, origin, q);
            BalloonManager.get().pastePrefab(kind, world, store, origin, q,
                    Ownership.landingOwner(rec.ownership(), rec.pilot(), null, rec.pilot() == null || !AirshipLimit.countsForLimit(kind) || !AirshipLimit.ownsAirship(rec.pilot())));
            drainHull(world, kind, s, origin, q);
            if (spec(kind).hasCrew() && rec.crew() != null) {
                // after a voyage the pilot is recreated once, at the helm.
                CrewRecord crew = rec.voyage() != null || rec.recall() != null ? rec.crew().withPost(CrewRecord.Post.HELM) : rec.crew();
                BalloonRegistry.setCrew(world, origin, q, kind.id, crew);
                AirshipCrew.ensure(world, BalloonRegistry.get(world, origin, q, kind.id));
            }
            if (rec.water() != null) {
                BalloonRegistry.setWater(world, origin, q, kind.id, rec.water());
            }
            if (rec.sky() != null) {
                BalloonRegistry.setSky(world, origin, q, kind.id, rec.sky());
            }
            BalloonManager.get().restoreCargo(world, store, rec.cargo(), origin, q);
            if (rec.engines() != null) {
                rec.engines().restore(world, store, origin, q); // absent in older resume files
            }
            if (rec.benches() != null) {
                rec.benches().restore(world, store, origin, q); // absent in older resume files (level first)
            }
            rec.benchContents().restore(world, store, origin, q); // empty for older resume files
            if (rec.pilot() != null) {
                restorePilot(kind, world, store, s, rec.pilot(), origin, q);
            }
            if (rec.riders() != null) {
                AirshipPassengers.restoreSaved(world, store, s, rec.riders(), origin, q); // absent in older resume files
            }
            AirshipResume.delete(rec.id());
            LOGGER.at(Level.INFO).log("Dirigeable %s reposé en %s (reprise après arrêt en vol)", rec.id(), origin);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Reprise du dirigeable %s en échec : fichier gardé pour un prochain essai", rec.id());
            Telemetry.error("airship.recover", e, null, null);
        } finally {
            recovering.remove(rec.id());
        }
    }

    /** Removes the ship still standing at its take-off place (world saved before the removal), contents emptied first. */
    private void removeOldShip(Deployables.Kind kind, World world, StructureShape s, AirshipResume.Record rec) {
        // The take-off origin is not stored in the file: the old ship, if any, is recognised from the take-off anchor kept in rec.
        // (Nothing to do when the file has no take-off data.)
        if (rec.takeoffOrigin() == null || rec.takeoffRotation() == null) {
            return;
        }
        Vector3i o = rec.takeoffOrigin();
        Rotation r = rec.takeoffRotation();
        Vector3i anchor = s.anchor().rotated(r).add(o);
        BlockType bt = SectionBlocks.typeAt(new SectionReader(world.getChunkStore()), anchor.x, anchor.y, anchor.z);
        if (bt != null && BalloonManager.sameBlock(bt, s.anchor())) {
            BalloonCargo.takeFrom(world, s, o, r);
            AirshipEngines.takeFrom(world, s, o, r); // emptied: the engine contents of the file are authoritative
            if (rec.benchContents().entryCount() > 0) {
                AirshipBenchContents.takeFrom(world, s, o, r); // emptied: the contents of the file are authoritative
            }
            if (rec.benches() != null) {
                AirshipBenches.takeFrom(world, s, o, r); // upgrade items cleared: the levels of the file are authoritative
            }
            BalloonRegistry.remove(world, o, r, kind.id);
            int n = BalloonManager.removeBlocks(world, s, o, r, true);
            floodHull(world, kind, s, o, r);
            if (spec(kind).recall) {
                AirshipRecall.removeLegacyRungs(world, rec.takeoffRungs()); // the temporary ladder of a recall came back with the old save
            }
            LOGGER.at(Level.INFO).log("Ancien dirigeable resté au point de décollage retiré (%d blocs)", n);
        }
    }

    private void restorePilot(Deployables.Kind kind, World world, Store<EntityStore> store, StructureShape s, UUID pilot, Vector3i origin, Rotation q) {
        Vector3d spot = pilotSpot(kind, origin, q);
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
        return despawn(store, adminRef, admin, world, null);
    }

    /** Same, restricted to one ship kind id when onlyKind is not null (steamboat despawn). */
    BalloonManager.DespawnResult despawn(Store<EntityStore> store, Ref<EntityStore> adminRef, PlayerRef admin, World world, String onlyKind) {
        List<Deployables.Kind> kinds = new ArrayList<>();
        for (Deployables.Kind k : Deployables.SHIPS) {
            if (onlyKind == null || onlyKind.equals(k.id)) {
                kinds.add(k);
            }
        }
        Map<Deployables.Kind, StructureShape> shapes = new java.util.LinkedHashMap<>();
        for (Deployables.Kind k : kinds) {
            try {
                shapes.put(k, k.shape());
            } catch (IOException e) {
                return new BalloonManager.DespawnResult(false, Texts.ship(k, "error.prefabUnreadable").param("error", String.valueOf(e.getMessage())));
            }
        }
        TransformComponent at = store.getComponent(adminRef, TransformComponent.getComponentType());
        if (at == null) {
            return new BalloonManager.DespawnResult(false, Texts.t("error.noPosition"));
        }
        Vector3d here = new Vector3d(at.getPosition());

        AirshipFlight flight = null;
        double flightDist = Double.MAX_VALUE;
        for (AirshipFlight f : flights.values()) {
            if (f.world != world || f.shuttle != null || (onlyKind != null && !onlyKind.equals(f.kind.id))) {
                continue; // the shuttle is not a player's ship: never removed by these commands
            }
            double d = admin.getUuid().equals(f.pilotUuid) ? 0 : f.pos.distance(here);
            if (d <= DESPAWN_RADIUS && d < flightDist) {
                flight = f;
                flightDist = d;
            }
        }
        Deployables.Kind posedKind = null;
        Vector3i posedOrigin = null;
        Rotation posedRotation = null;
        double posedDist = Double.MAX_VALUE;
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (Deployables.Kind kind : kinds) {
          StructureShape s = shapes.get(kind);
          for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, kind.id)) {
            Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
            Vector3i anchor = s.anchor().rotated(e.rotation()).add(origin);
            BlockType bt = SectionBlocks.typeAt(reader, anchor.x, anchor.y, anchor.z);
            if (bt == null || !BalloonManager.sameBlock(bt, s.anchor())) {
                continue;
            }
            double d = new Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5).distance(here);
            if (d <= DESPAWN_RADIUS && d < posedDist) {
                posedKind = kind;
                posedOrigin = origin;
                posedRotation = e.rotation();
                posedDist = d;
            }
          }
        }
        if (posedOrigin == null) {
            BalloonManager.Found found = BalloonManager.get().find(world, here, kinds);
            if (found != null) {
                posedKind = found.kind();
                posedOrigin = found.origin();
                posedRotation = found.rotation();
                StructureShape fs = shapes.get(found.kind());
                Vector3i anchor = fs.anchor().rotated(found.rotation()).add(found.origin());
                posedDist = new Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5).distance(here);
            }
        }
        if (flight == null && posedOrigin == null) {
            return new BalloonManager.DespawnResult(false, Texts.ship(Deployables.get(onlyKind), "despawn.none").param("radius", (int) DESPAWN_RADIUS));
        }
        Message error;
        if (flight != null && (posedOrigin == null || flightDist <= posedDist)) {
            error = despawnFlight(store, flight, admin, here);
        } else {
            error = despawnPosed(store, world, posedKind, shapes.get(posedKind), posedOrigin, posedRotation, admin);
        }
        if (error != null) {
            return new BalloonManager.DespawnResult(false, error);
        }
        Deployables.Kind removedKind = error == null && flight != null && (posedOrigin == null || flightDist <= posedDist) ? flight.kind : posedKind;
        boolean given = BalloonManager.get().giveCrate(store, adminRef, here, spec(removedKind).crateItemId);
        return new BalloonManager.DespawnResult(true, Texts.ship(removedKind, given ? "despawn.done" : "despawn.doneInventoryFull"));
    }

    private Message despawnPosed(Store<EntityStore> store, World world, Deployables.Kind kind, StructureShape s, Vector3i origin, Rotation rotation, PlayerRef admin) {
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
        // Kept before the removal, which deletes the registry entry: the pilot is removed only if the removal succeeds.
        BalloonRegistry.Entry posedEntry = BalloonRegistry.get(world, origin, rotation, kind.id);
        if (spec(kind).ladders && ShipLadder.removeNow(store, world, origin, rotation)) { // rope ladders first, no animation
            ShipLadder.requestRungSections(world, origin, rotation);
            return Texts.ship(kind, "approach"); // a rung is out of the loaded chunks: nothing was removed
        }
        StructureRemoval.Result result = StructureRemoval.remove(store, world, kind, s, origin, rotation, true);
        if (result.error() != null) {
            return result.error();
        }
        floodHull(world, kind, s, origin, rotation); // removal succeeded: the lake gets its water back
        try {
            if (spec(kind).hasCrew() && posedEntry != null) {
                AirshipCrew.remove(store, world, posedEntry);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilote non retiré avec le dirigeable");
            Telemetry.error("airship.despawn", e, null, null);
        }
        for (int i = 0; i < inside.size(); i++) {
            try {
                BalloonManager.putOnGround(store, world, inside.get(i), insidePos.get(i));
                PlayerRef pr = store.getComponent(inside.get(i), PlayerRef.getComponentType());
                if (pr != null && !pr.getUuid().equals(admin.getUuid())) {
                    pr.sendMessage(Texts.ship(kind, "despawn.removedByAdmin"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Joueur du dirigeable non posé au sol");
                Telemetry.error("airship.despawn", e, null, null);
            }
        }
        LOGGER.at(Level.INFO).log("Retrait par %s : dirigeable posé en %s (rotation %s), %d blocs retirés, %d objet(s) lâchés",
                admin.getUsername(), origin, rotation, result.removed(), result.items());
        return null;
    }

    private Message despawnFlight(Store<EntityStore> store, AirshipFlight f, PlayerRef admin, Vector3d adminPos) {
        if (flights.get(f.id) != f) {
            return Texts.ship(f.kind, "despawn.alreadyLanding");
        }
        f.finishing = true;
        flights.remove(f.id);
        if (spec(f.kind).recall) {
            AirshipRecall.endQuay(f); // a recall flight frees its quay
        }
        stopCarry(store, f);
        removeLights(store, f);
        cancelSmoke(f);
        World world = f.world;
        Vector3d pos = new Vector3d(f.pos);
        passengers.dropAll(store, f, admin); // passengers released and put on the ground under their stool
        if (!f.pilotless() && f.pilotRef.isValid()) {
            try {
                restorePilotMovement(store, f.pilotRef);
                if (!BalloonManager.isDead(store, f.pilotRef)) {
                    BalloonManager.putOnGround(store, world, f.pilotRef, pos);
                }
                PlayerRef pp = store.getComponent(f.pilotRef, PlayerRef.getComponentType());
                if (pp != null && !pp.getUuid().equals(admin.getUuid())) {
                    pp.sendMessage(Texts.ship(f.kind, "despawn.removedByAdmin"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Pilote non remis au sol proprement");
                Telemetry.error("airship.despawn", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
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
        if (f.benchContents != null) {
            items += BalloonManager.dropAndCount(store, f.benchContents.takeAllItems(), dropPos);
        }
        if (f.benches != null) {
            // the upgrade items are refunded, as when a placed bench is broken.
            items += BalloonManager.dropAndCount(store, f.benches.takeAllItems(), dropPos);
        }
        AirshipResume.delete(f.id);
        setRoar(store, f, false); // the end sound plays before the entity goes
        removeShipEntities(store, f);
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
                        + "carryStep %.2f, carryPauseMs %d, helmIntangible %s, fuelFactor %.2f, fuelMoveMin %.2f, restTurn %s, restDeadZone %.0f deg, restHoldMs %d, restStop %.1f deg, "
                        + "turnFuelMin %.2f deg/s",
                steer, yawSign, maxYawRateDeg, yawAccelDeg, turnSpeedMin, reverseConeDeg, flySpeedFactor, rotationCenterZ, carry,
                carryGain, carryStep, carryPauseMs, AirshipHelm.intangible, fuelFactor, fuelMoveMin, restTurn, restDeadZoneDeg,
                restHoldMs, restStopDeg, turnFuelMinDeg)
                + ", " + passengers.tuning();
        tuning = "rendering setting " + (renderDual ? "dual" : "single") + ", model parts setting " + partsMode.name().toLowerCase()
                + " (view filter " + (ViewFilter.available() ? "registered" : "NOT registered")
                + ", " + ViewFilter.ruleCount() + " rule(s)), " + tuning;
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
        TransformComponent ot = f.observerRef != null && f.observerRef.isValid()
                ? store.getComponent(f.observerRef, TransformComponent.getComponentType()) : null;
        tuning = PilotMannequin.describe(f.pilotMan)
                + ", model parts " + VehicleParts.describe(f.parts, partsMode) + ", flight rendering " + (f.dual ? "dual (ship entity rule " + ViewFilter.ruleOf(f.shipRef) + "; observer entity "
                + (ot != null ? fmt(ot.getPosition()) : "missing") + ", rule " + ViewFilter.ruleOf(f.observerRef) + ")"
                : "single entity (seen by everybody)") + ", " + tuning;
        double head = headYaw(store, f);
        Vector3d cw = centerWorld(f.kind, f.pos, f.theta);
        String carryText = String.format(Locale.ROOT,
                "rotation centre C %s (P - C = %.1f blocks), carry mode %s%s, carry this tick %.3f b, total carried %.2f b, "
                        + "carry teleports %d, owed %.2f b, velocity sent %s, effect ratio %s, own velocity %.2f,%.2f b/s, helm helper %s",
                fmt(cw), Math.abs(pivot(f.kind).z - rotationCenterZ), effectiveCarry(f), f.carryFallback ? " (velocity judged ineffective, fallback)" : "",
                f.lastCarry, f.totalCarry, f.carryTeleports, Math.hypot(f.carryAccX, f.carryAccZ), f.velActive,
                Double.isNaN(f.chkRatio) ? "n/a" : String.format(Locale.ROOT, "%.2f", f.chkRatio), f.ownVx, f.ownVz,
                f.helm != null && f.helm.ref != null && f.helm.ref.isValid() ? "present" : "absent");
        carryText += ", steer " + steer + ", " + PaddleWheels.describe(f) + ", " + ShipHelm.describe(f);
        carryText += String.format(Locale.ROOT, ", smoothness: yawJerk %.0f deg/s3, retrySmaller %s, thrustModels %d, swapMinMs %d, "
                        + "observerSmoothMs %.0f | totals: refused headings %d, partial steps %d, model swaps %d, collisions %d | this turn (since the "
                        + "rate last crossed %.0f deg/s): refused %d, partial %d, collision teleports %d, carry teleports %d, "
                        + "model swaps %d, ticks %d, yaw writes %d, max yaw step %.3f deg/tick, tick interval min/mean/max %.1f/%.1f/%.1f ms",
                yawJerkDeg, retrySmaller, thrustModels, swapMinMs, observerSmoothMs, f.refusedHeadings, f.partialSteps, f.modelSwaps,
                f.collisionCount, TURN_FLAME_ON_DEG, f.refusedHeadings - f.turnRefused, f.partialSteps - f.turnPartial,
                f.collisionCount - f.turnBaseCollisions, f.carryTeleports - f.turnBaseCarryTp,
                f.modelSwaps - f.turnSwaps, f.turnTicks, f.turnYawSends, f.turnMaxStepDeg,
                f.turnDtN == 0 ? 0 : f.turnDtMin * 1000, f.turnDtN == 0 ? 0 : f.turnDtSum / f.turnDtN * 1000, f.turnDtMax * 1000);
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
                + ", thrust state " + f.thrust + " (model " + f.modelId + "), burner sound " + (f.roar ? "on" : "off") + ", engine sound " + (f.engineSound ? "on" : "off")
                + ", moving " + f.moving + ", turn flame direction " + (f.turnDir > 0 ? "left" : f.turnDir < 0 ? "right" : "none")
                + ", rest turn " + (restTurn ? "enabled" : "disabled") + ", rest turn active " + f.restActive
                + String.format(Locale.ROOT, " (dead zone %.0f deg, hold %d ms, stop %.1f deg), turnFuelMin %.2f deg/s",
                        restDeadZoneDeg, restHoldMs, restStopDeg, turnFuelMinDeg) + fireboxDebug(f);
    }

    /** Firebox light part of the debug text (English). */
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
            return "passengers takes follow, mount, teleport or camera. Current: " + passengers.mode;
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
            case "helmintangible" -> AirshipHelm.intangible = value != 0;
            case "fuelfactor" -> fuelFactor = Math.max(0.01, value);
            case "fuelmovemin" -> fuelMoveMin = Math.max(0, value);
            case "turnfuelmin" -> turnFuelMinDeg = Math.max(0, value);
            case "restturn" -> restTurn = value != 0;
            case "restdeadzone" -> restDeadZoneDeg = Math.max(0, Math.min(179, value));
            case "restholdms" -> restHoldMs = Math.max(0, Math.round(value));
            case "reststop" -> restStopDeg = Math.max(0.1, value);
            case "yawjerk" -> yawJerkDeg = Math.max(0, value);
            case "retrysmaller" -> retrySmaller = value != 0;
            case "thrustmodels" -> thrustModels = (int) Math.max(0, Math.min(2, Math.round(value)));
            case "swapminms" -> swapMinMs = Math.max(0, Math.round(value));
            case "observersmoothms" -> observerSmoothMs = Math.max(0, value);
            case "passengergain" -> passengers.gain = Math.max(0, value);
            case "passengermaxcorrection" -> passengers.maxCorrection = Math.max(0, value);
            case "passengersnap" -> passengers.snap = Math.max(0.3, value);
            default -> {
                return "Unknown parameter " + name + ". Parameters: maxYawRate (deg/s), yawAccel (deg/s2), turnSpeedMin (b/s), "
                        + "reverseCone (deg), flySpeedFactor (applies at the next take-off), pivotZ (rotation centre, prefab z from -15 "
                        + "bow to 15 stern, default 0 = middle, 7 = rear quarter), carry (velocity|teleport|off), carryGain, carryStep (blocks), "
                        + "carryPauseMs, helmIntangible (0|1, next take-off), fuelFactor (burn seconds per second of travel, default 2), "
                        + "fuelMoveMin (b/s of horizontal travel above which fuel burns, default 0.5), turnFuelMin (deg/s of mean turn rate above "
                        + "which fuel burns, default 1), restTurn (0|1, ship turns toward the head yaw at rest, default 1), restDeadZone "
                        + "(deg, default 25), restHoldMs (default 500), restStop (deg, default 3), "
                        + "yawJerk (deg/s3, limits the change of the yaw acceleration for a smooth start and stop, 0 = "
                        + "off, default 60), retrySmaller (0|1, a refused heading step is retried at half and a quarter instead of stopping the turn, default 1), "
                        + "thrustModels (2 = all burner models, 1 = no left/right models, 0 = never swap the model in flight, default 2), swapMinMs "
                        + "(minimum time between two model swaps, default 800), observerSmoothMs (smoothing of the observer entity position, 0 = raw, "
                        + "default 60), passengers (follow|mount|teleport, "
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
