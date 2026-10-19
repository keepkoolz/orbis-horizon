package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Server-driven flight of an airship whose NPC pilot takes the captain to a destination (T139). AirshipManager.tickOne calls
 * {@link #step} on every tick of a pilotless flight that has a voyage. Phases: CLIMB (vertical, to the cruise height), TURN (on the
 * spot, toward the destination, with the yaw controller of the player-piloted ship), CRUISE (straight line, obstacle look-ahead),
 * ARRIVE (landing started above the destination) RETRY (a landing was refused: climb a few blocks, wait the backoff, try again) and STOPPING (a stop landing was started).
 * Every pose goes through AirshipManager.move, so the collisions are the ones of a piloted ship.
 */
final class AirshipAutopilot {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double MAX_DT = 0.25;
    /** Distance (horizontal) at which the ship is above the destination. */
    static final double ARRIVED = 1.0;
    static final double TURN_DONE_DEG = 2.0;
    /** The heading drifted this far from the destination in cruise: aim again. */
    private static final double REAIM_DEG = 10.0;
    /** The last blocks before the destination are flown slowly. */
    private static final double SLOW_RANGE = 8.0;
    private static final double MIN_SPEED = 0.5;
    private static final double AHEAD_SECONDS = 3.0;
    private static final long AHEAD_INTERVAL_MS = 250;
    private static final double AHEAD_SPACING = 2.0;
    /** Box (blocks around the ship, below and above its pivot) whose sections must be loaded ahead. */
    private static final int LOAD_RADIUS = 16;
    private static final int LOAD_BELOW = 8;
    private static final int LOAD_ABOVE = 12;
    private static final long WINDOW_MS = 250;
    private static final long NO_PROGRESS_MS = TransitConstants.RESCUE_NO_PROGRESS_SECONDS * 1000L;
    private static final long CHUNK_WAIT_MS = TransitConstants.CHUNK_WAIT_SECONDS * 1000L;
    private static final double PROGRESS_MOVE = 0.2;

    private AirshipAutopilot() {
    }

    // ------------------------------------------------------------------ pure helpers (tested in TransitTests)

    /**
     * Chat key of the reason a voyage cannot leave, or null. Order: the captain must be aboard, then there must be seats for the
     * other players (the existing airship.error.full), then the captain needs a free hotbar slot for the map.
     */
    static String departureRefusalKey(boolean ownerAboard, int othersAboard, int seats, boolean freeHotbarSlot) {
        if (!ownerAboard) {
            return "crew.voyage.noCaptain";
        }
        if (othersAboard > seats) {
            return "airship.error.full";
        }
        if (!freeHotbarSlot) {
            return "crew.voyage.handsFull";
        }
        return null;
    }

    /** Horizontal speed at this distance from the destination: full speed, then at most twice the distance (floor 0.5) in the last blocks. */
    static double cruiseSpeed(double distance, double fullSpeed) {
        return distance < SLOW_RANGE ? Math.min(fullSpeed, Math.max(MIN_SPEED, 2 * distance)) : fullSpeed;
    }

    /** Heading (radians) that points the bow at an offset: the bow points to (-sin theta, -cos theta). */
    static double headingTo(double dx, double dz) {
        return Math.atan2(-dx, -dz);
    }

    /**
     * Sample points (x, z) ahead of the ship along the heading: every AHEAD_SPACING blocks, the first at 1 block, over
     * min(speed * AHEAD_SECONDS, remaining) blocks.
     */
    static List<double[]> lookAheadPoints(double x, double z, double dirX, double dirZ, double speed, double remaining) {
        List<double[]> pts = new ArrayList<>();
        double reach = Math.min(speed * AHEAD_SECONDS, remaining);
        for (double a = 1.0; a <= reach + 1e-9; a += AHEAD_SPACING) {
            pts.add(new double[]{x + dirX * a, z + dirZ * a});
        }
        return pts;
    }

    /**
     * The voyage ends early (blocked, out of fuel): the riders hear why and the ship lands where it is, with the retry rule of a
     * refused landing (climb, try again, at most 10 times, then an immediate landing). Never throws.
     */
    static void stop(AirshipFlight f, String key) {
        VoyageState v = f.voyage;
        if (v == null || f.landing || f.finishing || v.stopped) {
            return;
        }
        try {
            if (AirshipRecall.approachStopped(f)) {
                return; // a stopped recall approach descends near the station or flies away, never lands in place with the quay freed
            }
            if (AirshipRecall.retryLeave(f)) {
                return; // the leave flight inside the zone tries again instead of landing there
            }
            if (AirshipRecall.divertToLeave(f)) {
                return; // still near the station: it flies away fuel-free and lands there instead of in place
            }
            v.stopped = true;
            v.arrived = false; // a stop after a refused arrival is announced as a stop only
            LOGGER.at(Level.INFO).log("Voyage : arrêt (%s)", key);
            AirshipPassengers.tell(f.world.getEntityStore().getStore(), f, key);
            v.phase = VoyageState.Phase.STOPPING;
            v.progressMs = 0;
            AirshipManager.get().startLanding(f, false);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : arrêt impossible");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    // ------------------------------------------------------------------ captain ashore (T152)

    /** Height above the ground the bottom of the fixed ladder may keep when the captain is left ashore. */
    static final int ASHORE_MAX_ABOVE = 30;

    /**
     * Ladder side (+1 or -1, prefab frame) nearest to a world offset (dx, dz) from the ship pivot, ship heading theta.
     * The offset is rotated back into the prefab frame, a tie goes to -1. Pure.
     */
    static int ashoreSide(double dx, double dz, double theta) {
        double localX = dx * Math.cos(theta) - dz * Math.sin(theta);
        return localX > 0 ? 1 : -1;
    }

    /**
     * Blocks to sink so the bottom cell of the ladder is at most maxAbove blocks over the ground (groundBlockY = y of the first
     * solid block below, its top is groundBlockY + 1). NaN while the ground is unknown. Pure.
     */
    static double ashoreDescent(double bottomY, double groundBlockY, double maxAbove) {
        if (Double.isNaN(groundBlockY)) {
            return Double.NaN;
        }
        return Math.max(0, bottomY - (groundBlockY + 1) - maxAbove);
    }

    /**
     * The captain jumped or died during the voyage: the ship sinks (no advance, no turn) until the bottom of the fixed ladder on
     * the chosen side is at most 30 blocks over the ground, then lands by the usual stop path and lowers that ladder. Never throws.
     */
    static boolean captainAshore(AirshipFlight f) {
        VoyageState v = f.voyage;
        if (v == null || f.landing || f.finishing || v.stopped || v.ashore) {
            return false;
        }
        try {
            // Same recall guards as stop(): a recall flight is handled by the recall, no ashore phase.
            if (AirshipRecall.approachStopped(f) || AirshipRecall.retryLeave(f) || AirshipRecall.divertToLeave(f)) {
                return true;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : garde du rappel en échec");
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
        v.ashore = true;
        v.phase = VoyageState.Phase.ASHORE;
        v.progressMs = 0;
        v.ashoreGroundWaitMs = 0;
        LOGGER.at(Level.INFO).log("Voyage : capitaine à terre, le dirigeable descend");
        return true;
    }

    /** ASHORE tick: sink to the mooring height, then land. */
    private static void ashore(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                               VoyageState v, double dt, long now) {
        f.yawRate = 0;
        f.yawAcc = 0;
        v.ashoreSide = am.ashoreSideNow(f);
        Vector3d bottom = am.shipPoint(f, new Vector3f(v.ashoreSide * ShipLadder.SIDE_X, ShipLadder.BOTTOM_FIXED_Y, 0));
        int cx = (int) Math.floor(bottom.x);
        int cz = (int) Math.floor(bottom.z);
        int cy = (int) Math.floor(bottom.y);
        double ground = groundBlockY(world, cx, cy, cz);
        if (Double.isNaN(ground)) {
            if (v.ashoreGroundWaitMs == 0) {
                v.ashoreGroundWaitMs = now;
            }
            if (now - v.ashoreGroundWaitMs >= CHUNK_WAIT_MS) {
                LOGGER.at(Level.INFO).log("Voyage : sol inconnu sous l'échelle, pose sur place");
                finishAshore(am, f, v);
                return;
            }
            SectionBlocks.loadSections(world.getChunkStore(), cx - 1, Math.max(0, cy - 64), cz - 1, cx + 1, cy, cz + 1);
            return;
        }
        v.ashoreGroundWaitMs = 0;
        double need = ashoreDescent(cy, ground, ASHORE_MAX_ABOVE);
        if (need <= 0.05) {
            finishAshore(am, f, v);
            return;
        }
        double dy = Math.min(BalloonManager.defaultDescentSpeed(world, store) * dt, need);
        double before = f.pos.y;
        am.move(world, f, hull, new Vector3d(f.pos.x, f.pos.y - dy, f.pos.z), f.theta, now);
        if (f.pos.y > before - 1e-9) {
            LOGGER.at(Level.INFO).log("Voyage : descente arrêtée par un obstacle, pose ici");
            finishAshore(am, f, v);
        }
    }

    /** Y of the first non-air block under the cell (x, y, z), NaN if a section on the way is not loaded. */
    private static double groundBlockY(World world, int x, int y, int z) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (int yy = y - 1; yy >= 0; yy--) {
            if (!reader.hasStorageInMemory(x, yy, z)) {
                return Double.NaN;
            }
            if (!ShipLadder.isAir(reader, x, yy, z)) {
                return yy;
            }
        }
        return -1;
    }

    private static void finishAshore(AirshipManager am, AirshipFlight f, VoyageState v) {
        v.ashoreSide = am.ashoreSideNow(f); // the side is chosen now that the ship is about to be moored
        v.stopped = true;
        v.arrived = false;
        v.phase = VoyageState.Phase.STOPPING;
        v.progressMs = 0;
        am.startLanding(f, false);
    }

    // ------------------------------------------------------------------ every tick

    /** One tick of the voyage of a pilotless flight. Never throws. */
    static void step(Store<EntityStore> store, World world, AirshipFlight f, double dt, long now) {
        VoyageState v = f.voyage;
        if (v == null || f.landing || f.finishing) {
            return;
        }
        try {
            AirshipManager am = AirshipManager.get();
            AirshipManager.Hull hull = am.hullOf(f.kind.shape());
            double d = Math.min(Math.max(dt, 0), MAX_DT);
            if (v.progressMs == 0) {
                markProgress(f, v, now);
                v.windowMs = now;
                v.windowX = f.pos.x;
                v.windowZ = f.pos.z;
                v.windowTheta = f.theta;
            }
            switch (v.phase) {
                case CLIMB -> climb(store, world, am, hull, f, v, d, now);
                case TURN -> turn(store, world, am, hull, f, v, d, now);
                case CRUISE -> cruise(store, world, am, hull, f, v, d, now);
                case ASHORE -> ashore(store, world, am, hull, f, v, d, now);
                case ARRIVE, RETRY, STOPPING -> retry(store, world, am, hull, f, v, d, now);
            }
            if (f.finishing) {
                return;
            }
            fuelWindow(store, am, f, v, now);
            if (!v.fuelFree && f.dry && !v.stopped && !v.ashore && v.phase != VoyageState.Phase.ARRIVE) {
                stop(f, "crew.voyage.dry");
            }
            am.updateThrust(store, f, now);
            watchdog(am, f, v, now);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : erreur dans le tick du dirigeable %s", f.id);
            Telemetry.error("airship.autopilot", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
        }
    }

    /** Called by AirshipManager when a landing of a voyage was refused: climb a few blocks and try again, then land where it is. */
    static void onLandingRefused(AirshipFlight f) {
        VoyageState v = f.voyage;
        if (v == null) {
            return;
        }
        v.retries++;
        v.retryY = f.pos.y + VoyageState.RETRY_CLIMB;
        v.phase = VoyageState.Phase.RETRY;
        v.progressMs = 0;
        if (v.retries >= VoyageState.MAX_RETRIES) {
            LOGGER.at(Level.INFO).log("Voyage : %d atterrissages refusés, pose sur place", v.retries);
            if (!v.forcedTried) {
                v.forcedTried = true; // a single forced landing per voyage
                if (AirshipManager.get().landImmediately(f)) {
                    return;
                }
            }
            v.retries = VoyageState.MAX_RETRIES - 1; // nothing fits here: keep trying, but only every backoff period
        }
    }

    // ------------------------------------------------------------------ phases

    private static void climb(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                              VoyageState v, double dt, long now) {
        double dy = v.cruiseY - f.pos.y;
        if (dy <= 0.05) {
            v.phase = VoyageState.Phase.TURN;
            return;
        }
        rise(store, world, am, hull, f, Math.min(BalloonManager.defaultDescentSpeed(world, store) * dt, dy), now);
    }

    private static boolean rise(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                                double dy, long now) {
        Vector3d p = new Vector3d(f.pos.x, f.pos.y + dy, f.pos.z);
        double before = f.pos.y;
        am.move(world, f, hull, p, f.theta, now);
        return f.pos.y > before + 1e-9;
    }

    private static void turn(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                             VoyageState v, double dt, long now) {
        double dx = v.destX - f.pos.x;
        double dz = v.destZ - f.pos.z;
        if (Math.hypot(dx, dz) < ARRIVED) {
            arrive(am, f, v);
            return;
        }
        double err = AirshipMath.wrapPi(headingTo(dx, dz) - f.theta);
        if (noThrust(f, v) || Math.abs(err) < Math.toRadians(TURN_DONE_DEG)) {
            f.yawRate = 0;
            f.yawAcc = 0;
            v.phase = VoyageState.Phase.CRUISE;
            return;
        }
        double maxRate = Math.toRadians(am.maxYawRateDeg);
        double accel = Math.toRadians(am.yawAccelDeg);
        double desired = Math.signum(err) * Math.min(maxRate, am.brakingRate(Math.abs(err), accel));
        am.stepRate(f, desired, accel, dt);
        double step = f.yawRate * dt;
        if (Math.abs(step) >= Math.abs(err) || (Math.signum(f.yawRate) != Math.signum(err) && f.yawRate != 0
                && Math.abs(err) < Math.toRadians(0.3))) {
            step = err;
            f.yawRate = 0;
            f.yawAcc = 0;
        }
        // Turn around the ship centre (T66), not around the helm: the pivot cell moves on the arc.
        am.move(world, f, hull, am.pivotAfterTurn(f.pos, f.theta, f.theta + step), f.theta + step, now);
    }

    private static void cruise(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                               VoyageState v, double dt, long now) {
        f.yawRate = 0;
        f.yawAcc = 0;
        double dx = v.destX - f.pos.x;
        double dz = v.destZ - f.pos.z;
        double dist = Math.hypot(dx, dz);
        if (dist < ARRIVED) {
            arrive(am, f, v);
            return;
        }
        if (!noThrust(f, v) && Math.abs(AirshipMath.wrapPi(headingTo(dx, dz) - f.theta)) > Math.toRadians(REAIM_DEG)) {
            v.phase = VoyageState.Phase.TURN;
            return;
        }
        if (noThrust(f, v)) {
            return; // no thrust: no horizontal travel (the watchdog lands the ship)
        }
        double hSpeed = BalloonManager.defaultHorizontalSpeed(world, store);
        double ux = dx / dist;
        double uz = dz / dist;
        if (now >= v.aheadNextMs) {
            v.aheadNextMs = now + AHEAD_INTERVAL_MS;
            lookAhead(world, am, hull, f, v, ux, uz, dist, hSpeed, now);
        }
        if (v.aheadBlocked) {
            // Climb until the way ahead is free, then keep the new height.
            double vSpeed = BalloonManager.defaultDescentSpeed(world, store);
            if (rise(store, world, am, hull, f, vSpeed * dt, now)) {
                v.cruiseY = Math.max(v.cruiseY, f.pos.y);
            }
            return;
        }
        double speed = cruiseSpeed(dist, hSpeed);
        if (v.unloadedSinceMs != 0) {
            if (now - v.unloadedSinceMs < CHUNK_WAIT_MS) {
                return; // waiting for the sections ahead
            }
            speed = Math.min(speed, MIN_SPEED); // waited long enough: feel the way slowly
        }
        double move = Math.min(speed * dt, dist);
        am.move(world, f, hull, new Vector3d(f.pos.x + ux * move, f.pos.y, f.pos.z + uz * move), f.theta, now);
    }

    /** Tests the hull at samples along the heading (blocked ahead) and the sections ahead (loaded or not). */
    private static void lookAhead(World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f, VoyageState v, double ux,
                                  double uz, double dist, double hSpeed, long now) {
        double reach = Math.min(dist, Math.max(hSpeed * AHEAD_SECONDS, AHEAD_SPACING));
        boolean blocked = false;
        for (double[] pt : lookAheadPoints(f.pos.x, f.pos.z, ux, uz, hSpeed, dist)) {
            if (AirshipManager.blocked(world, hull, pt[0], f.pos.y, pt[1], f.theta, 0) > 0) {
                blocked = true;
                break;
            }
        }
        v.aheadBlocked = blocked;
        int cx = (int) Math.floor(f.pos.x + ux * reach);
        int cz = (int) Math.floor(f.pos.z + uz * reach);
        int cy = (int) Math.floor(f.pos.y);
        boolean loaded = SectionBlocks.sectionsLoaded(world.getChunkStore(), cx - LOAD_RADIUS, cy - LOAD_BELOW, cz - LOAD_RADIUS,
                cx + LOAD_RADIUS, cy + LOAD_ABOVE, cz + LOAD_RADIUS);
        if (loaded) {
            v.unloadedSinceMs = 0;
        } else {
            if (v.unloadedSinceMs == 0) {
                v.unloadedSinceMs = now;
            }
            SectionBlocks.loadSections(world.getChunkStore(), cx - LOAD_RADIUS, cy - LOAD_BELOW, cz - LOAD_RADIUS,
                    cx + LOAD_RADIUS, cy + LOAD_ABOVE, cz + LOAD_RADIUS);
        }
    }

    /** No thrust: dry or without engine. A fuel-free flight (the recall) always has thrust. */
    private static boolean noThrust(AirshipFlight f, VoyageState v) {
        return !v.fuelFree && (f.dry || f.engines == null);
    }

    private static void arrive(AirshipManager am, AirshipFlight f, VoyageState v) {
        if (v.arrivalMode == VoyageState.ArrivalMode.HOVER) {
            // The recall takes over: no landing, the ship stays an entity above the destination.
            v.phase = VoyageState.Phase.ARRIVE;
            v.progressMs = 0;
            AirshipRecall.onArrived(f.world.getEntityStore().getStore(), f);
            return;
        }
        v.phase = VoyageState.Phase.ARRIVE;
        v.arrived = true;
        v.progressMs = 0;
        LOGGER.at(Level.INFO).log("Voyage : arrivé au-dessus de %s, début de l'atterrissage", v.destName);
        am.startLanding(f, false);
    }

    /** ARRIVE / RETRY: after a refused landing, climb to the retry height then land again. */
    private static void retry(Store<EntityStore> store, World world, AirshipManager am, AirshipManager.Hull hull, AirshipFlight f,
                              VoyageState v, double dt, long now) {
        double dy = v.retryY - f.pos.y;
        if (dy > 0.05 && v.phase == VoyageState.Phase.RETRY) {
            boolean rose = rise(store, world, am, hull, f, Math.min(BalloonManager.defaultDescentSpeed(world, store) * dt, dy), now);
            if (rose) {
                return;
            }
        }
        if (now < f.nextLandingRetryMs) {
            return; // refused recently: wait for the backoff
        }
        v.phase = VoyageState.Phase.ARRIVE;
        am.startLanding(f, false);
    }

    // ------------------------------------------------------------------ fuel and progress

    /** Feeds the pilot's travel sample (distance, turn) to AirshipManager.burnFuel every 250 ms, like the player-piloted ship. */
    private static void fuelWindow(Store<EntityStore> store, AirshipManager am, AirshipFlight f, VoyageState v, long now) {
        if (now - v.windowMs < WINDOW_MS) {
            return;
        }
        double seconds = (now - v.windowMs) / 1000.0;
        f.travelSpeed = Math.hypot(f.pos.x - v.windowX, f.pos.z - v.windowZ) / seconds;
        f.windowTurn = Math.abs(f.theta - v.windowTheta);
        v.windowMs = now;
        v.windowX = f.pos.x;
        v.windowZ = f.pos.z;
        v.windowTheta = f.theta;
        if (v.fuelFree) {
            // Same flames as a fuelled flight, but nothing is burnt.
            f.moving = f.travelSpeed > am.fuelMoveMin;
            f.burning = f.moving;
            return;
        }
        am.burnFuel(store, f, seconds);
    }

    private static void markProgress(AirshipFlight f, VoyageState v, long now) {
        v.progressX = f.pos.x;
        v.progressY = f.pos.y;
        v.progressZ = f.pos.z;
        v.progressTheta = f.theta;
        v.progressMs = now;
    }

    /** No progress for 30 s (blocked, dry, ceiling): the voyage stops and the ship lands (after the usual refusals, where it is). */
    private static void watchdog(AirshipManager am, AirshipFlight f, VoyageState v, long now) {
        double moved = Math.abs(f.pos.x - v.progressX) + Math.abs(f.pos.y - v.progressY) + Math.abs(f.pos.z - v.progressZ);
        if (moved > PROGRESS_MOVE || Math.abs(f.theta - v.progressTheta) > Math.toRadians(1)) {
            markProgress(f, v, now);
        } else if (!v.stopped && !v.ashore && now - v.progressMs >= NO_PROGRESS_MS) {
            LOGGER.at(Level.INFO).log("Voyage : aucun progrès depuis %d s", NO_PROGRESS_MS / 1000);
            markProgress(f, v, now);
            stop(f, "crew.voyage.blocked");
        }
    }

    /** Name of the voyage or recall phase of a flight, or null (telemetry only). */
    static String phaseOf(AirshipFlight f) {
        if (f == null) return null;
        VoyageState v = f.voyage;
        if (v != null && v.phase != null) return v.phase.name();
        RecallState r = f.recall;
        return r != null && r.phase != null ? r.phase.name() : null;
    }
}
