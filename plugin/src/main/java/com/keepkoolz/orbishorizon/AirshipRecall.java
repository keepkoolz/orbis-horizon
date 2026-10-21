package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Recall of a parked airship to a station (T143). The owner's airship is taken off where it is parked, appears 120 blocks from the
 * station and flies in with its pilot NPC, without fuel, then hovers above the pad. AirshipManager.tickOne calls {@link #step} on
 * every tick of a flight that has a recall.
 */
final class AirshipRecall {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double MAX_DT = 0.25;
    /** Highest block of a station above its origin (the statues). */
    static final int STATION_TOP_OFFSET = 9;
    /** Sections loaded around a point before the ship is put there. */
    private static final int LOAD_RADIUS = 20;
    private static final int LOAD_ABOVE = 24;
    /** A ship that makes no progress for this long in the descent hovers where it is. */
    private static final long STUCK_MS = 3000;
    /** Pivot horizontal tolerance to the hover pose. */
    private static final double HOVER_TOLERANCE = 0.3;
    /** Height gained before the ship flies away. */
    private static final double LEAVE_CLIMB = 10.0;

    private AirshipRecall() {
    }

    /** Where the recalled ship appears and flies: spawn, heading, heights and the approach destination (pivot). */
    record Plan(double spawnX, double spawnZ, double theta, int cruiseY, double destX, double destZ, double padX, double padZ,
                double hoverY, double hoverTheta) {
    }

    /** Pure: the plan of a recall of a ship parked with its pivot at (pivotX, pivotZ). */
    static Plan plan(TransitNetwork.Station s, double pivotX, double pivotZ) {
        Vector3d pad = StationMarkerProvider.markerPoint(s.origin, s.rotation);
        double[] sp = RecallPlanner.spawnPoint(pad.x, pad.z, pivotX, pivotZ);
        double theta = AirshipAutopilot.headingTo(pad.x - sp[0], pad.z - sp[1]);
        // T145: the ship hovers with the quarter turn whose side ladder is nearest to the boarding steps.
        int[] side = RecallPlanner.ladderSide(pad.x, pad.z, boardingPoints(s));
        double[] hover = RecallPlanner.hoverPivot(pad.x, pad.z, s.origin.y + STATION_TOP_OFFSET, side[0] * Math.PI / 2);
        int padY = StationQueue.padY(s);
        int cruise = Math.max(VoyagePlanner.cruiseY(padY, padY, BalloonManager.WORLD_MAX_Y), (int) Math.ceil(hover[1]));
        // The approach flies the pivot to the point where the ship centre (10 blocks behind the pivot) is above the pad centre.
        double destX = pad.x + RecallPlanner.PIVOT_TO_CENTRE * -Math.sin(theta);
        double destZ = pad.z + RecallPlanner.PIVOT_TO_CENTRE * -Math.cos(theta);
        return new Plan(sp[0], sp[1], theta, cruise, destX, destZ, pad.x, pad.z, hover[1], hover[3]);
    }

    /** World boarding points of a station as {x, y, z}. */
    static List<double[]> boardingPoints(TransitNetwork.Station s) {
        List<double[]> points = new ArrayList<>();
        for (Vector3d p : TransitAutopilot.boardingPoints(s)) {
            points.add(new double[] {p.x, p.y, p.z});
        }
        return points;
    }

    /** The registry entry of this player's parked airship with a pilot NPC in this world, null if none. */
    static BalloonRegistry.Entry parkedAirship(World world, UUID owner) {
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.AIRSHIP_KIND)) {
            if (owner.equals(e.owner()) && e.crew() != null) {
                return e;
            }
        }
        return null;
    }

    /** True if a flight of this player has a recall in progress, or if his ship already waits in blocks above a station. */
    private static boolean recalling(UUID owner) {
        for (AirshipFlight f : AirshipManager.get().flights()) {
            if (f.recall != null && owner.equals(f.ownership.owner())) {
                return true;
            }
        }
        for (RecallState r : PARKED) {
            if (owner.equals(r.owner)) {
                return true;
            }
        }
        return false;
    }

    /** True if a player (anyone, the owner included) stands in the box of the parked ship of this entry. */
    static boolean occupied(Store<EntityStore> store, World world, BalloonRegistry.Entry entry) {
        try {
            return !AirshipPassengers.aboard(store, world, Deployables.AIRSHIP.shape(), new Vector3i(entry.x(), entry.y(), entry.z()),
                    entry.rotation(), null).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** True if a flight owned by this player has no recall (a voyage or a piloted flight). */
    private static boolean flyingOther(UUID owner) {
        for (AirshipFlight f : AirshipManager.get().flights()) {
            if (f.recall == null && owner.equals(f.ownership.owner())) {
                return true;
            }
        }
        return false;
    }

    /** The recall button is offered to the owner of a parked airship with a pilot, or of a flight that has one (the refusals say why not). */
    static boolean canOffer(World world, UUID owner) {
        if (parkedAirship(world, owner) != null) {
            return true;
        }
        for (AirshipFlight f : AirshipManager.get().flights()) {
            if (f.crew != null && owner.equals(f.ownership.owner())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks the pilot of the owner's parked airship to come to the station. Null if the recall started (the ship appears once the
     * sections are loaded, on the world thread), otherwise the refusal of the station master. Never throws.
     */
    static Message start(Store<EntityStore> store, World world, PlayerRef owner, TransitNetwork.Station s) {
        try {
            UUID me = owner.getUuid();
            BalloonRegistry.Entry entry = parkedAirship(world, me);
            boolean recalling = recalling(me);
            boolean occupied = entry != null && !recalling && occupied(store, world, entry);
            String key = RecallPlanner.recallRefusalKey(entry != null || recalling, flyingOther(me), recalling, occupied, true);
            UUID flightId = UUID.randomUUID();
            if (key == null && !StationQueue.beginRecall(s, flightId)) {
                key = "recall.quayBusy";
            }
            if (key != null) {
                return Texts.t("crew." + key);
            }
            try {
                begin(store, world, owner, s, entry, flightId);
            } catch (RuntimeException e) {
                StationQueue.endRecall(s, flightId);
                LOGGER.at(Level.WARNING).withCause(e).log("Rappel : démarrage impossible");
                Telemetry.error("airship.recall", e, null, null);
                return Texts.t("crew.recall.noAirship");
            }
            Telemetry.count("airship.recall");
            return null;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : demande en échec");
            Telemetry.error("airship.recall", e, null, null);
            return Texts.t("crew.recall.noAirship");
        }
    }

    private static void begin(Store<EntityStore> store, World world, PlayerRef owner, TransitNetwork.Station s,
                              BalloonRegistry.Entry entry, UUID flightId) {
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        Rotation rotation = entry.rotation();
        Vector3d parked = AirshipManager.get().parkedPivot(Deployables.AIRSHIP, origin, rotation);
        Plan plan = plan(s, parked.x, parked.z);
        LOGGER.at(Level.INFO).log("Rappel : dirigeable de %s demandé à %s, apparition en (%.0f, %d, %.0f)", owner.getUsername(), s.name,
                plan.spawnX(), plan.cruiseY(), plan.spawnZ());
        int sx = (int) Math.floor(plan.spawnX());
        int sz = (int) Math.floor(plan.spawnZ());
        int[] box = AirshipManager.shipBox(origin);
        CompletableFuture<Void> parkedLoad = SectionBlocks.loadSections(world.getChunkStore(), box[0], box[1], box[2], box[3], box[4], box[5]);
        CompletableFuture<Void> spawnLoad = SectionBlocks.loadSections(world.getChunkStore(), sx - LOAD_RADIUS, plan.cruiseY() - 12,
                sz - LOAD_RADIUS, sx + LOAD_RADIUS, plan.cruiseY() + LOAD_ABOVE, sz + LOAD_RADIUS);
        CompletableFuture.allOf(parkedLoad, spawnLoad).whenComplete((v, t) -> {
            try {
                if (t != null) {
                    // Never detach a structure that is not in memory: the recall ends cleanly, the ship stays where it is.
                    LOGGER.at(Level.WARNING).withCause(t).log("Rappel : chargement des sections impossible, rappel annulé");
                    Telemetry.error("airship.recall", t, null, null);
                    world.execute(() -> {
                        StationQueue.endRecall(s, flightId);
                        owner.sendMessage(Texts.t("crew.recall.unreachable"));
                    });
                    return;
                }
                world.execute(() -> launch(world, owner, s, entry, flightId, plan));
            } catch (RuntimeException e) {
                StationQueue.endRecall(s, flightId);
                LOGGER.at(Level.WARNING).withCause(e).log("Rappel : monde arrêté, rappel annulé");
            }
        });
    }

    /** On the world thread: the airship leaves its parking place and appears in flight. */
    private static void launch(World world, PlayerRef owner, TransitNetwork.Station s, BalloonRegistry.Entry entry, UUID flightId, Plan plan) {
        Message failure = null;
        try {
            if (!TransitNetwork.get().stations(world.getName()).contains(s)) {
                LOGGER.at(Level.INFO).log("Rappel : station %s retirée avant l'apparition", s.name);
                StationQueue.endRecall(s, flightId);
                return;
            }
            BalloonRegistry.Entry current = BalloonRegistry.get(world, new Vector3i(entry.x(), entry.y(), entry.z()), entry.rotation(),
                    Deployables.AIRSHIP_KIND);
            if (current == null || current.crew() == null) {
                LOGGER.at(Level.INFO).log("Rappel : le dirigeable n'est plus posé");
                StationQueue.endRecall(s, flightId);
                owner.sendMessage(Texts.t("crew.recall.noAirship"));
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (occupied(store, world, current)) {
                // A player stepped on the ship while the sections were loading: the floor is not taken from under his feet.
                LOGGER.at(Level.INFO).log("Rappel : un joueur est à bord du dirigeable posé, rappel refusé");
                failure = Texts.t("crew.recall.occupied");
            } else {
                failure = AirshipManager.get().launchRecall(store, world, current, flightId, plan, s);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : apparition en échec");
            Telemetry.error("airship.recall", e, Telemetry.kindId(Deployables.AIRSHIP), null);
            failure = Texts.t("crew.recall.noAirship");
        }
        if (failure != null) {
            StationQueue.endRecall(s, flightId);
            owner.sendMessage(failure);
            return;
        }
        Message sent = Texts.t("crew.recall.sent");
        owner.sendMessage(sent);
        StationQueue.broadcast(world, s, sent);
    }

    // ------------------------------------------------------------------ every tick

    /** One tick of a recalled ship. Never throws. */
    static void step(Store<EntityStore> store, World world, AirshipFlight f, double dt, long now) {
        RecallState r = f.recall;
        if (r == null || f.landing || f.finishing) {
            return;
        }
        try {
            switch (r.phase) {
                case APPROACH -> AirshipAutopilot.step(store, world, f, dt, now);
                case DESCEND -> descend(store, world, f, r, Math.min(Math.max(dt, 0), MAX_DT), now);
                case WAIT, CHOOSE -> { } // the ship is in blocks then: see tickParked
                case LEAVE -> AirshipAutopilot.step(store, world, f, dt, now);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : erreur dans le tick du dirigeable %s", f.id);
            Telemetry.error("airship.recall", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** The approach is over: the ship stays an entity above the station and descends to the hover pose. */
    static void onArrived(Store<EntityStore> store, AirshipFlight f) {
        RecallState r = f.recall;
        if (r == null) {
            return;
        }
        r.phase = RecallState.Phase.DESCEND;
        r.stuckSinceMs = 0;
        f.moving = false;
        f.yawRate = 0;
        f.yawAcc = 0;
        LOGGER.at(Level.INFO).log("Rappel : arrivé au point d'approche de %s, descente jusqu'à y %.0f", r.station.name, r.hoverY);
    }

    /**
     * Vertical descent to the hover height while the ship turns to the heading of the station around its centre. The pivot is
     * kept on the arc that holds the ship centre above the pad centre, so the horizontal correction is only a slide of a block or so.
     */
    private static void descend(Store<EntityStore> store, World world, AirshipFlight f, RecallState r, double dt, long now)
            throws IOException {
        AirshipManager am = AirshipManager.get();
        AirshipManager.Hull hull = am.hullOf(f.kind, f.kind.shape());
        double yawErr = AirshipMath.wrapPi(r.hoverTheta - f.theta);
        boolean yawDone = Math.abs(yawErr) < Math.toRadians(AirshipAutopilot.TURN_DONE_DEG);
        double step = 0;
        if (yawDone) {
            f.yawRate = 0;
            f.yawAcc = 0;
        } else {
            double accel = Math.toRadians(am.yawAccelDeg);
            double desired = Math.signum(yawErr) * Math.min(Math.toRadians(am.maxYawRateDeg), am.brakingRate(Math.abs(yawErr), accel));
            am.stepRate(f, desired, accel, dt);
            step = f.yawRate * dt;
            if (Math.abs(step) >= Math.abs(yawErr)) {
                step = yawErr;
                f.yawRate = 0;
                f.yawAcc = 0;
            }
        }
        double newTheta = yawDone ? f.theta : f.theta + step;
        double tx = r.padX + RecallPlanner.PIVOT_TO_CENTRE * -Math.sin(newTheta);
        double tz = r.padZ + RecallPlanner.PIVOT_TO_CENTRE * -Math.cos(newTheta);
        double dx = tx - f.pos.x;
        double dz = tz - f.pos.z;
        double dist = Math.hypot(dx, dz);
        double yLeft = f.pos.y - r.hoverY;
        if (yLeft <= 0.05 && yawDone && dist < HOVER_TOLERANCE) {
            park(store, f, r, now, "au point de vol stationnaire");
            return;
        }
        double slide = Math.min(dist, BalloonManager.defaultHorizontalSpeed(world, store) * dt);
        double nx = dist > 1e-9 ? f.pos.x + dx / dist * slide : f.pos.x;
        double nz = dist > 1e-9 ? f.pos.z + dz / dist * slide : f.pos.z;
        double ny = f.pos.y - Math.min(BalloonManager.defaultDescentSpeed(world, store) * dt, Math.max(yLeft, 0));
        double bx = f.pos.x, by = f.pos.y, bz = f.pos.z, bt = f.theta;
        am.move(world, f, hull, new Vector3d(nx, ny, nz), newTheta, now);
        boolean progressed = Math.abs(f.pos.x - bx) + Math.abs(f.pos.y - by) + Math.abs(f.pos.z - bz) > 1e-6
                || Math.abs(f.theta - bt) > 1e-9;
        if (progressed) {
            r.stuckSinceMs = 0;
        } else if (r.stuckSinceMs == 0) {
            r.stuckSinceMs = now;
        } else if (now - r.stuckSinceMs >= STUCK_MS) {
            park(store, f, r, now, "bloqué à y " + Math.round(f.pos.y) + " (point visé y " + Math.round(r.hoverY) + ")");
            return;
        }
        am.updateThrust(store, f, now);
    }

    // ------------------------------------------------------------------ parked above the station (T145)

    /** Ships waiting in blocks above a station (WAIT, CHOOSE). In memory: the registry mark keeps what a restart needs. */
    private static final java.util.Set<RecallState> PARKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String LADDER_BLOCK = "Furniture_Crude_Ladder";
    private static final int SET_NO_PARTICLES = 4;
    private static final long LADDER_TIMEOUT_MS = 90_000;

    /**
     * The descent is over: the ship is put in blocks where it hovers (the usual landing, locked, pilot back at the helm), the rungs
     * of the temporary ladder are set under its side ladder, and the minute starts. The quay stays held. If the ship cannot be put,
     * it flies away.
     */
    private static void park(Store<EntityStore> store, AirshipFlight f, RecallState r, long now, String why) {
        AirshipManager am = AirshipManager.get();
        TransitNetwork.Station station = f.quayStation;
        f.quayStation = null; // finishLanding would free the quay
        AirshipManager.Placed placed = am.landImmediatelyAt(f);
        if (placed == null) {
            f.quayStation = station;
            LOGGER.at(Level.WARNING).log("Rappel : pose impossible au-dessus de %s, le dirigeable repart", r.station.name);
            leave(store, f, r, now);
            return;
        }
        World world = f.world;
        r.origin = placed.origin();
        r.rotation = placed.rotation();
        r.quayId = f.quayId != null ? f.quayId : f.id;
        r.sx = RecallPlanner.ladderSx(r.padX, r.padZ, boardingPoints(r.station), (int) Math.round(r.rotation.getRadians() / (Math.PI / 2)) & 3);
        r.crew = f.crew;
        ShipLadder.lower(world, r.origin, r.rotation, r.sx);
        r.phase = RecallState.Phase.LADDER; // the minute starts when the rope ladder is down
        r.waitStartMs = now;
        r.announced = Integer.MAX_VALUE;
        writeMark(world, r);
        PARKED.add(r);
        LOGGER.at(Level.INFO).log("Rappel : dirigeable posé en l'air au-dessus de %s (%s), l'échelle de corde descend", r.station.name, why);
    }

    /** Writes the recall mark (quay, station, spawn point, rungs) in the registry entry of the parked ship. */
    private static void writeMark(World world, RecallState r) {
        int[] flat = new int[0]; // the wooden rungs are gone: the mark keeps an empty array (old marks stay readable)
        BalloonRegistry.setRecall(world, r.origin, r.rotation, new BalloonRegistry.Recall(r.quayId, r.station.origin.x, r.station.origin.y,
                r.station.origin.z, r.station.rotation, r.spawnX, r.spawnZ, flat));
    }

    /** The parked ship of this origin and rotation, null if none waits there. */
    static RecallState parkedAt(World world, Vector3i origin, Rotation rotation) {
        for (RecallState r : PARKED) {
            if (r.station.world.equals(world.getName()) && r.origin != null && r.origin.equals(origin) && r.rotation == rotation) {
                return r;
            }
        }
        return null;
    }

    /** Writes the recall mark again after a refused take-off put the entry back without it. No-op if the ship no longer waits. */
    static void restoreMark(World world, Vector3i origin, Rotation rotation) {
        RecallState r = parkedAt(world, origin, rotation);
        if (r != null && r.leaveRetryMs == 0) {
            writeMark(world, r);
        }
    }

    /**
     * Resume after a crash: the rungs a ship had right before its take-off from the waiting place come back with an older world save.
     * Those still holding the ladder block are removed. Never throws.
     */
    static void removeLegacyRungs(World world, int[] flat) {
        if (flat == null || flat.length < 3) {
            return;
        }
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            int n = 0;
            for (int i = 0; i + 2 < flat.length; i += 3) {
                BlockType t = SectionBlocks.typeAt(reader, flat[i], flat[i + 1], flat[i + 2]);
                if (t != null && LADDER_BLOCK.equals(t.getId())
                        && SectionBlocks.setAt(world.getChunkStore(), flat[i], flat[i + 1], flat[i + 2], BlockType.EMPTY_KEY, SET_NO_PARTICLES)) {
                    n++;
                }
            }
            if (n > 0) {
                LOGGER.at(Level.INFO).log("Rappel : %d ancien(s) barreau(x) retiré(s) à la reprise", n);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : anciens barreaux non retirés");
            Telemetry.error("airship.recall", e, null, null);
        }
    }

    /**
     * The ship that waited above a station is now flying: it keeps the quay (freed when it leaves the zone or lands) and the wait is
     * over. No-op without a parked state.
     */
    static void takeoffDone(AirshipFlight f, RecallState r) {
        if (r == null) {
            return;
        }
        f.quayStation = r.station;
        f.quayId = r.quayId;
        f.recallInfo = r;
        PARKED.remove(r);
        closePage(r);
        LOGGER.at(Level.INFO).log("Rappel : l'attente au-dessus de %s est terminée, le dirigeable a décollé", r.station.name);
    }

    /** Every tick of a world: the minute, the captain's window and the checks of the ships waiting above a station. Never throws. */
    static void tickParked(Store<EntityStore> store, World world, long now) {
        if (PARKED.isEmpty()) {
            return;
        }
        for (RecallState r : new ArrayList<>(PARKED)) {
            if (!r.station.world.equals(world.getName())) {
                continue;
            }
            try {
                tickWait(store, world, r, now);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Rappel : erreur dans l'attente au-dessus de %s", r.station.name);
                Telemetry.error("airship.recall", e, null, null);
            }
        }
    }

    private static void tickWait(Store<EntityStore> store, World world, RecallState r, long now) {
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, r.origin, r.rotation, Deployables.AIRSHIP_KIND);
        if (entry == null) {
            LOGGER.at(Level.INFO).log("Rappel : le dirigeable en attente au-dessus de %s a disparu", r.station.name);
            finishWait(world, r, false);
            return;
        }
        if (!TransitNetwork.get().stations(world.getName()).contains(r.station)) {
            LOGGER.at(Level.INFO).log("Rappel : station %s retirée, le dirigeable reste posé en l'air", r.station.name);
            finishWait(world, r, true);
            return;
        }
        if (r.leaveRetryMs > 0) {
            // A forced departure was refused: the quay stays held, new try every 5 s.
            if (now >= r.leaveRetryMs) {
                leaveNow(store, world, r, entry);
            }
            return;
        }
        if (r.phase == RecallState.Phase.DEPART) {
            if (!ShipLadder.departing(world, r.origin, r.rotation)) {
                // The deferred departure was dropped without running: try again (counts tries, bounded).
                LOGGER.at(Level.WARNING).log("Rappel : départ en attente perdu au-dessus de %s, nouvel essai", r.station.name);
                leaveNow(store, world, r, entry);
            }
            return; // otherwise the rope ladders come up and the departure restarts by itself
        }
        if (r.phase == RecallState.Phase.LADDER) {
            boolean down = ShipLadder.phase(world, r.origin, r.rotation, r.sx) == ShipLadder.Phase.DOWN;
            if (down || now - r.waitStartMs >= LADDER_TIMEOUT_MS) {
                if (!down) {
                    LOGGER.at(Level.WARNING).log("Rappel : l'échelle de corde n'est pas descendue après %d s, la minute commence quand même",
                            LADDER_TIMEOUT_MS / 1000);
                }
                r.phase = RecallState.Phase.WAIT;
                r.waitStartMs = now;
                r.announced = Integer.MAX_VALUE;
                StationQueue.broadcast(world, r.station, Texts.pilot("crew.recall.here", r.crew));
            }
        } else if (r.phase == RecallState.Phase.WAIT) {
            long elapsed = now - r.waitStartMs;
            int threshold = RecallPlanner.waitThresholdReached(elapsed, r.announced);
            if (threshold > 0) {
                r.announced = threshold;
                StationQueue.broadcast(world, r.station, Texts.t("crew.recall.countdown").param("seconds", threshold));
            }
            if (elapsed >= RecallPlanner.WAIT_MS) {
                endWait(store, world, r, entry, now);
            }
        } else if (r.phase == RecallState.Phase.CHOOSE) {
            if (!captainAboard(store, world, r)) {
                LOGGER.at(Level.INFO).log("Rappel : le capitaine a quitté le bord, le dirigeable repart");
                leaveNow(store, world, r, entry); // first: closing the page runs onDismiss, which must find the ship gone or refused
                closePage(r);
            } else if (now - r.chooseStartMs >= RecallPlanner.CHOOSE_MS) {
                LOGGER.at(Level.INFO).log("Rappel : pas de destination choisie, le dirigeable repart");
                leaveNow(store, world, r, entry);
                closePage(r);
            }
        }
    }

    /** The wait is over without a take-off: rungs down, no mark, the quay freed. The ship stays in place. */
    private static void finishWait(World world, RecallState r, boolean shipStays) {
        PARKED.remove(r);
        closePage(r);
        if (shipStays) {
            ShipLadder.raiseAll(world, r.origin, r.rotation); // animated: the ship stays in blocks
            BalloonRegistry.setRecall(world, r.origin, r.rotation, null);
        }
        StationQueue.endRecall(r.station, r.quayId);
    }

    /** True if the owner stands in the box of the parked ship. */
    static boolean captainAboard(Store<EntityStore> store, World world, RecallState r) {
        return captainRef(store, world, r) != null;
    }

    /** The owner if he stands in the box of the parked ship, null otherwise. */
    static PlayerRef captainRef(Store<EntityStore> store, World world, RecallState r) {
        try {
            StructureShape s = Deployables.AIRSHIP.shape();
            for (PlayerRef pr : world.getPlayerRefs()) {
                Ref<EntityStore> ref = pr.getReference();
                if (r.owner != null && r.owner.equals(pr.getUuid()) && ref != null && ref.isValid()) {
                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    return t != null && AirshipManager.insideShape(s, r.origin, r.rotation, t.getPosition()) ? pr : null;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : présence du capitaine non vérifiée");
            Telemetry.error("airship.recall", e, null, ph(r));
        }
        return null;
    }

    /** End of the minute: the captain's window if he is aboard, otherwise the ship leaves (the ladders come up with the departure). */
    private static void endWait(Store<EntityStore> store, World world, RecallState r, BalloonRegistry.Entry entry, long now) {
        if (!captainAboard(store, world, r) || entry.crew() == null) {
            LOGGER.at(Level.INFO).log("Rappel : capitaine absent, le dirigeable repart");
            leaveNow(store, world, r, entry);
            return;
        }
        if (AirshipCrew.state(world, entry) != AirshipCrew.CrewState.AT_HELM) {
            LOGGER.at(Level.INFO).log("Rappel : le pilote n'est pas à la barre, pas de choix de destination, le dirigeable repart");
            leaveNow(store, world, r, entry);
            return;
        }
        r.phase = RecallState.Phase.CHOOSE;
        r.chooseStartMs = now;
        try {
            PlayerRef pr = null;
            for (PlayerRef p : world.getPlayerRefs()) {
                if (r.owner.equals(p.getUuid())) {
                    pr = p;
                }
            }
            Player player = pr != null && pr.getReference() != null ? store.getComponent(pr.getReference(), Player.getComponentType()) : null;
            if (pr == null || player == null) {
                leaveNow(store, world, r, entry);
                return;
            }
            AirshipVoyagePage page = new AirshipVoyagePage(pr, entry.crew().uuid(), r);
            r.page = page;
            player.getPageManager().openCustomPage(pr.getReference(), store, page);
            LOGGER.at(Level.INFO).log("Rappel : fenêtre des destinations ouverte pour %s", pr.getUsername());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : fenêtre des destinations impossible, le dirigeable repart");
            Telemetry.error("airship.recall", e, null, ph(r));
            leaveNow(store, world, r, entry);
        }
    }

    private static void closePage(RecallState r) {
        AirshipVoyagePage page = r.page;
        r.page = null;
        if (page != null) {
            page.closeQuietly();
        }
    }

    /**
     * The captain closed the window without choosing (called by the page). Idempotent: nothing once the ship left, and nothing for a
     * page the server closed itself (closePage forgets the page before closing it, so {@code r.page} is no longer this page).
     */
    static void onChoiceClosed(RecallState r, AirshipVoyagePage page) {
        if (r == null || page == null || r.page != page || r.phase != RecallState.Phase.CHOOSE || !PARKED.contains(r)
                || r.leaveRetryMs > 0) {
            return;
        }
        r.page = null;
        try {
            World world = com.hypixel.hytale.server.core.universe.Universe.get().getWorlds().get(r.station.world);
            if (world == null) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            BalloonRegistry.Entry entry = BalloonRegistry.get(world, r.origin, r.rotation, Deployables.AIRSHIP_KIND);
            if (entry != null) {
                leaveNow(store, world, r, entry);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : départ impossible");
            Telemetry.error("airship.recall", e, null, ph(r));
        }
    }

    /**
     * Take-off from blocks with the players aboard. A departure forced by the end of the wait must never leave the ship above the
     * station: if it cannot go yet the quay stays held and the try is repeated every 5 s.
     */
    static void leaveNow(Store<EntityStore> store, World world, RecallState r, BalloonRegistry.Entry entry) {
        if (ShipLadder.departing(world, r.origin, r.rotation)) {
            r.phase = RecallState.Phase.DEPART; // already waiting for the ladders: no try is burnt
            r.leaveRetryMs = 0;
            return;
        }
        Message refusal = AirshipManager.get().leaveFromParked(store, world, entry, r);
        if (refusal == AirshipManager.DEFERRED) {
            r.leaveRetryMs = 0;
            r.phase = RecallState.Phase.DEPART; // the rope ladders come up first: the departure restarts by itself, no try is counted
            return;
        }
        if (refusal == null || !PARKED.contains(r)) {
            return; // left, or no longer waiting (nothing to retry)
        }
        if (!RecallPlanner.departRetryAllowed(++r.departTries)) {
            // Bound: after 10 refused departures the ship stays in blocks where it waits, the quay is freed (logged).
            LOGGER.at(Level.WARNING).log("Rappel : départ refusé %d fois au-dessus de %s, le dirigeable reste posé et le quai est libéré",
                    r.departTries, r.station.name);
            finishWait(world, r, true);
            return;
        }
        LOGGER.at(Level.WARNING).log("Rappel : départ impossible pour l'instant, nouvel essai dans 5 s au-dessus de %s", r.station.name);
        r.leaveRetryMs = System.currentTimeMillis() + 5000;
    }

    /** The leave point (x, z): 70 blocks from the pad, computed from where the ship appeared, away from the other stations. */
    static double[] retreatPoint(RecallState r, World world) {
        List<double[]> others = new ArrayList<>();
        for (TransitNetwork.Station other : TransitNetwork.get().stations(world.getName())) {
            if (other != r.station) {
                Vector3d p = StationMarkerProvider.markerPoint(other.origin, other.rotation);
                others.add(new double[] {p.x, p.z});
            }
        }
        return VoyagePlanner.stationArrival(r.padX, r.padZ, r.spawnX, r.spawnZ, others);
    }

    /** What the resume file keeps of this recall (see {@link RecallState.Saved}). */
    static RecallState.Saved savedOf(RecallState r, World world) {
        double[] p = retreatPoint(r, world);
        return new RecallState.Saved(r.station.name, r.owner, r.spawnX, r.spawnZ, p[0], p[1]);
    }

    /**
     * The fuel-free flight that sends the ship away: 10 blocks up from fromY, then to the leave point (70 blocks from the pad, computed
     * from where it appeared), landing in the air there. Sets the phase to LEAVE and announces it.
     */
    static VoyageState leaveVoyage(RecallState r, World world, double fromY) {
        double[] point = retreatPoint(r, world);
        r.phase = RecallState.Phase.LEAVE;
        StationQueue.broadcast(world, r.station, Texts.t("crew.recall.leaving"));
        LOGGER.at(Level.INFO).log("Rappel : le dirigeable s'éloigne de %s vers (%.0f, %.0f)", r.station.name, point[0], point[1]);
        return new VoyageState(r.station.name, VoyagePlanner.Kind.STATION, point[0], point[1], fromY + LEAVE_CLIMB, r.owner, true,
                VoyageState.ArrivalMode.LAND);
    }

    /** The ship in flight goes away (see {@link #leaveVoyage}). */
    private static void leave(Store<EntityStore> store, AirshipFlight f, RecallState r, long now) {
        if (r.phase == RecallState.Phase.LEAVE) {
            return;
        }
        f.voyage = leaveVoyage(r, f.world, f.pos.y);
    }

    /**
     * A stop of the leave flight inside the station zone (no progress, blocked) must not put the ship down there: it climbs 3 blocks and
     * tries again (the quay stays held). True if handled. Never throws.
     */
    static boolean retryLeave(AirshipFlight f) {
        RecallState r = f.recall;
        VoyageState v = f.voyage;
        if (r == null || v == null || r.phase != RecallState.Phase.LEAVE || f.quayStation == null) {
            return false;
        }
        try {
            if (++r.leaveTries > RecallState.MAX_LEAVE_TRIES) {
                // Bound: the normal stop path (forced pose, logged) puts the ship down rather than holding the quay for ever.
                LOGGER.at(Level.INFO).log("Rappel : éloignement bloqué dans la zone de %s depuis %d essais, pose forcée", f.quayStation.name,
                        r.leaveTries - 1);
                return AirshipManager.get().landImmediately(f);
            }
            v.cruiseY = Math.min(v.cruiseY + 3, BalloonManager.WORLD_MAX_Y - 10);
            v.phase = VoyageState.Phase.CLIMB;
            v.progressMs = 0;
            LOGGER.at(Level.INFO).log("Rappel : éloignement bloqué dans la zone de %s, nouvel essai (hauteur de croisière %.0f)",
                    f.quayStation.name, v.cruiseY);
            return true;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : nouvel essai de l'éloignement impossible");
            Telemetry.error("airship.recall", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            return false;
        }
    }

    /**
     * A watchdog stop of the recall APPROACH (no progress, blocked) must not land the ship in place near the station with the quay
     * freed: near the approach point it goes on with the descent to the hover pose (which parks it, or hovers where it gets stuck),
     * otherwise it flies away fuel-free to the leave point. True if handled. Never throws.
     */
    static boolean approachStopped(AirshipFlight f) {
        RecallState r = f.recall;
        VoyageState v = f.voyage;
        if (r == null || v == null || r.phase != RecallState.Phase.APPROACH || f.landing || f.finishing) {
            return false;
        }
        try {
            Store<EntityStore> store = f.world.getEntityStore().getStore();
            if (RecallPlanner.approachNear(f.pos.x, f.pos.z, v.destX, v.destZ)) {
                LOGGER.at(Level.INFO).log("Rappel : approche bloquée près de %s, descente vers le point de vol stationnaire", r.station.name);
                onArrived(store, f);
            } else {
                LOGGER.at(Level.INFO).log("Rappel : approche bloquée loin de %s, le dirigeable s'éloigne", r.station.name);
                leave(store, f, r, System.currentTimeMillis());
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : approche bloquée non traitée");
            Telemetry.error("airship.recall", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            return false;
        }
    }

    /**
     * A converted recall (the captain chose a destination) must not stop and land in place while it still holds the quay: it goes
     * back to the recall LEAVE path (climb, fuel-free flight to the leave point, landing there). True if it was diverted. Never
     * throws.
     */
    static boolean divertToLeave(AirshipFlight f) {
        RecallState r = f.recallInfo;
        if (r == null || f.quayStation == null || f.recall != null || f.landing || f.finishing) {
            return false;
        }
        try {
            UUID captain = f.voyage != null ? f.voyage.captain : null;
            if (captain != null) {
                for (PlayerRef pr : f.world.getPlayerRefs()) {
                    if (pr.getUuid().equals(captain)) {
                        CaptainMap.removeAll(f.world.getEntityStore().getStore(), pr.getReference());
                    }
                }
                CaptainMap.forget(captain);
            }
            f.recall = r;
            leave(f.world.getEntityStore().getStore(), f, r, System.currentTimeMillis());
            return true;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : éloignement impossible");
            Telemetry.error("airship.recall", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            return false;
        }
    }

    /** True once the ship pivot is outside the protected cylinder of the station. */
    static boolean outsideZone(AirshipFlight f, TransitNetwork.Station s) {
        StationZone.Zone z = StationZone.zoneOf(s);
        double r = TransitConstants.STATION_ZONE_RADIUS;
        return Math.hypot(f.pos.x - z.cx(), f.pos.z - z.cz()) > r || f.pos.y > z.topY();
    }

    /** Every tick of a flight that holds a quay: frees it once the ship is leaving and out of the zone. Never throws. */
    static void releaseQuayIfLeft(AirshipFlight f) {
        TransitNetwork.Station s = f.quayStation;
        RecallState r = f.recall;
        if (s == null || (r != null && r.phase != RecallState.Phase.LEAVE)) {
            return;
        }
        try {
            if (outsideZone(f, s)) {
                LOGGER.at(Level.INFO).log("Rappel : le dirigeable a quitté la zone de %s, le quai est libre", s.name);
                endQuay(f);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : sortie de zone non vérifiée");
            Telemetry.error("airship.recall", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** The flight no longer holds its quay (idempotent): the station goes back to FREE with the usual gap. */
    static void endQuay(AirshipFlight f) {
        TransitNetwork.Station s = f.quayStation;
        if (s != null) {
            f.quayStation = null;
            StationQueue.endRecall(s, f.quayId != null ? f.quayId : f.id);
        }
    }

    // ------------------------------------------------------------------ restart (17 October 2026)

    private static final Set<UUID> RECOVERING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * World load: a ship still waiting in blocks above a station (registry entry with a recall mark) loses its temporary rungs and leaves
     * at once with whoever is aboard, as when the minute ends. A mark whose station is gone only loses its rungs and its mark.
     * Idempotent, never throws.
     */
    static void recoverWorld(World world) {
        try {
            for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.AIRSHIP_KIND)) {
                BalloonRegistry.Recall mark = e.recall();
                if (mark == null || !RECOVERING.add(e.id())) {
                    continue;
                }
                int lowest = e.y();
                for (int i = 1; i < mark.ladders().length; i += 3) {
                    lowest = Math.min(lowest, mark.ladders()[i]);
                }
                SectionBlocks.loadSections(world.getChunkStore(), e.x() - 18, lowest - 1, e.z() - 18, e.x() + 18, e.y() + 24, e.z() + 18)
                        .whenComplete((v, t) -> {
                            try {
                                world.execute(() -> recoverParked(world, e));
                            } catch (RuntimeException ex) {
                                RECOVERING.remove(e.id());
                                LOGGER.at(Level.WARNING).withCause(ex).log("Rappel : monde arrêté, reprise remise au prochain chargement");
                            }
                        });
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : reprise des dirigeables en attente impossible");
            Telemetry.error("airship.recover", e, null, null);
        }
    }

    private static void recoverParked(World world, BalloonRegistry.Entry old) {
        try {
            Vector3i origin = new Vector3i(old.x(), old.y(), old.z());
            BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, old.rotation(), Deployables.AIRSHIP_KIND);
            BalloonRegistry.Recall mark = entry != null ? entry.recall() : null;
            if (mark == null || parkedAt(world, origin, entry.rotation()) != null) {
                return;
            }
            TransitNetwork.Station station = null;
            for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
                if (s.origin.x == mark.stationX() && s.origin.y == mark.stationY() && s.origin.z == mark.stationZ()) {
                    station = s;
                }
            }
            Vector3d pad = station != null ? StationMarkerProvider.markerPoint(station.origin, station.rotation) : new Vector3d();
            RecallState r = new RecallState(station, entry.owner(), mark.spawnX(), mark.spawnZ(), pad.x, pad.z, 0, 0);
            r.origin = origin;
            r.rotation = entry.rotation();
            r.quayId = mark.quayId();
            if (station == null) {
                LOGGER.at(Level.INFO).log("Rappel : station du dirigeable en attente disparue, barreaux et marque retirés");
                removeLegacyRungs(world, mark.ladders());
                BalloonRegistry.setRecall(world, origin, entry.rotation(), null);
                return;
            }
            removeLegacyRungs(world, mark.ladders()); // wooden rungs of a mark written before the rope ladder
            strandOfflinePlayers(world, r, station);
            PARKED.add(r);
            StationQueue.beginRecall(station, r.quayId); // best effort: the quay is held while the ship leaves
            LOGGER.at(Level.INFO).log("Rappel : dirigeable retrouvé en attente au-dessus de %s après un redémarrage, il repart", station.name);
            leaveNow(world.getEntityStore().getStore(), world, r, entry);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : reprise du dirigeable en attente en échec");
            Telemetry.error("airship.recall", e, Telemetry.kindId(Deployables.AIRSHIP), null);
        } finally {
            RECOVERING.remove(old.id());
        }
    }

    /**
     * World load: players who are offline with a saved position inside the parked ship would reconnect in the air once it has left.
     * They get a stranded entry on the station's first boarding point (put down there at their next arrival). Asynchronous: each
     * player save is read off the world thread and the take-off never waits for it. Only a save of this world counts. Never throws.
     */
    private static void strandOfflinePlayers(World world, RecallState r, TransitNetwork.Station station) {
        try {
            com.hypixel.hytale.server.core.universe.Universe universe = com.hypixel.hytale.server.core.universe.Universe.get();
            StructureShape shape = Deployables.AIRSHIP.shape();
            Vector3d point = TransitAutopilot.boardingPoints(station).get(0);
            String worldName = world.getName();
            Vector3i origin = new Vector3i(r.origin);
            Rotation rotation = r.rotation;
            for (UUID id : universe.getPlayerStorage().getPlayers()) {
                if (universe.getPlayer(id) != null) {
                    continue; // online: the take-off handles him
                }
                universe.getPlayerStorage().load(id).whenComplete((holder, t) -> {
                    try {
                        if (t != null || holder == null) {
                            return;
                        }
                        TransformComponent tc = holder.getComponent(TransformComponent.getComponentType());
                        Player p = holder.getComponent(Player.getComponentType());
                        String saved = p != null && p.getPlayerConfigData() != null ? p.getPlayerConfigData().getWorld() : null;
                        boolean inside = tc != null && AirshipManager.insideShape(shape, origin, rotation, tc.getPosition());
                        if (RecallPlanner.strandOffline(universe.getPlayer(id) != null, saved, worldName, inside)) {
                            BalloonResume.writeStranded(id, worldName, point.x, point.y, point.z);
                            LOGGER.at(Level.INFO).log("Rappel : joueur hors ligne %s resté à bord, posé sur le quai à son retour", id);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Rappel : joueur hors ligne %s non vérifié", id);
                        Telemetry.error("airship.recall", e, null, ph(r));
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Rappel : joueurs hors ligne à bord non vérifiés");
            Telemetry.error("airship.recall", e, null, ph(r));
        }
    }

    private static String ph(RecallState r) {
        return r != null && r.phase != null ? r.phase.name() : null;
    }
}
