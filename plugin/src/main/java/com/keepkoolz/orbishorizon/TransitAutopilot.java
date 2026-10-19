package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Target position of a transit balloon on every tick (BalloonManager.tickDriven calls step instead of the descent, except during
 * APPROACH). Phases: CLIMB (vertical, to the cruise height), CRUISE (straight line above the arrival pad, obstacle look-ahead), HOLD
 * (waiting at a holding point while the arrival quay is occupied), DESCEND (onto the pad), DROP (passengers on the boarding platform,
 * title), VANISH (climb, then removal). The balloon is never placed in blocks at the destination.
 *
 * Smoothness: the entity position (f.entityPos) is a continuous double advanced by speed * dt on every tick, never snapped cell to cell,
 * so the seat velocity measured by BalloonManager.followPassenger (difference of the seat position between two ticks) is the real speed.
 */
final class TransitAutopilot {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Longest step of one tick, in seconds (same cap as the automatic descent). */
    private static final double MAX_DT = 0.25;
    /** Look-ahead: seconds of travel tested in front, interval between tests (ms) and spacing of the sample cells. */
    private static final double AHEAD_SECONDS = 3.0;
    private static final long AHEAD_INTERVAL_MS = 250;
    private static final double AHEAD_SPACING = 2.0;
    /** Distance at which the balloon is considered above the target point. */
    private static final double ARRIVED = 0.3;
    /** Rough duration used for the "landing in N s" message when the quay is not docked (departure climb), in seconds. */
    private static final int HOLD_CLEAR_SECONDS = 15;
    /** Rough duration of one balloon cycle on the quay, per arrival waiting ahead (countdown plus departure), in seconds. */
    private static final int HOLD_CYCLE_SECONDS = TransitConstants.COUNTDOWN_SECONDS + HOLD_CLEAR_SECONDS;

    private TransitAutopilot() {
    }

    // ------------------------------------------------------------------ pure helpers (tested in TransitTests)

    /** Cruise height of the entity origin: CRUISE_HEIGHT_ABOVE_PLATFORMS above the higher of the two dock origins. */
    static double cruiseY(int padYA, int padYB) {
        return Math.max(padYA, padYB) + TransitConstants.CRUISE_HEIGHT_ABOVE_PLATFORMS;
    }

    /** Cruise height of the entity origin for a flight from a station to a destination (null destination: the departure alone counts). */
    static double cruiseY(World world, TransitNetwork.Station from, String destination) {
        int a = from.origin.y + Deployables.STATION_DOCK_ORIGIN[1];
        TransitNetwork.Station to = destination == null ? null : TransitNetwork.get().byName(world.getName(), destination);
        return cruiseY(a, to != null ? to.origin.y + Deployables.STATION_DOCK_ORIGIN[1] : a);
    }

    /**
     * Holding point of the index-th waiting arrival: HOLDING_DISTANCE plus HOLDING_SPACING per index blocks from the pad centre on the
     * horizontal, on the side of comingFrom, at the height of comingFrom. Index n+1 is HOLDING_SPACING blocks beyond index n.
     */
    static Vector3d holdingPoint(Vector3d padCentre, Vector3d comingFrom, int index) {
        double dx = comingFrom.x - padCentre.x;
        double dz = comingFrom.z - padCentre.z;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        double d = TransitConstants.HOLDING_DISTANCE + (double) TransitConstants.HOLDING_SPACING * index;
        return new Vector3d(padCentre.x + dx / len * d, comingFrom.y, padCentre.z + dz / len * d);
    }

    /**
     * Where a flight goes when its destination station no longer exists: the departure station if it still exists, else the station
     * nearest to the balloon, else null (no station left: the balloon goes down to the ground). stations must not contain the removed one.
     */
    static TransitNetwork.Station rerouteTarget(List<TransitNetwork.Station> stations, String from, Vector3d here) {
        TransitNetwork.Station best = null;
        double bestD = Double.MAX_VALUE;
        for (TransitNetwork.Station s : stations) {
            if (s.name.equals(from)) {
                return s;
            }
            double d = here.distanceSquared(s.origin.x, s.origin.y, s.origin.z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * Station a player who leaves a transit flight early (disconnect, server stop, crash) is sent back to: the departure station if it still
     * exists, else the nearest one other than the destination, else the destination if it is the only one left, else null (the ground).
     * Never the destination when another choice exists, so quitting the game cannot be used to arrive instantly.
     */
    static TransitNetwork.Station returnStation(List<TransitNetwork.Station> stations, String from, String to, Vector3d here) {
        TransitNetwork.Station best = null;
        TransitNetwork.Station dest = null;
        double bestD = Double.MAX_VALUE;
        for (TransitNetwork.Station s : stations) {
            if (s.name.equals(from)) {
                return s;
            }
            if (s.name.equals(to)) {
                dest = s;
                continue;
            }
            double d = here.distanceSquared(s.origin.x, s.origin.y, s.origin.z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best != null ? best : dest;
    }

    /** Order of the flight among the waiting arrivals: its index, or the number of waiting ones (behind them) if it is not registered. */
    static int holdIndexFor(List<UUID> waitingOrder, UUID flight) {
        int i = waitingOrder.indexOf(flight);
        return i >= 0 ? i : waitingOrder.size();
    }

    /**
     * Drop points of the passengers on the boarding platform, in the world, feet position: the prefab-frame integer corners of
     * STATION_BOARDING_POINTS rotated with the station, moved to the cell centre (+0.5 on x and z after the rotation), circular over the list.
     */
    static List<Vector3d> dropPoints(Vector3i stationOrigin, Rotation rotation, int count) {
        List<Vector3d> out = new ArrayList<>();
        float[][] pts = Deployables.STATION_BOARDING_POINTS;
        for (int i = 0; i < count; i++) {
            float[] p = pts[i % pts.length];
            Vector3f r = rotation.rotateYaw(new Vector3f(p[0], p[1], p[2]), new Vector3f());
            out.add(new Vector3d(stationOrigin.x + r.x + 0.5, stationOrigin.y + r.y, stationOrigin.z + r.z + 0.5));
        }
        return out;
    }

    /** Boarding points of a station in the world (feet position), one per prefab point. */
    static List<Vector3d> boardingPoints(TransitNetwork.Station s) {
        return dropPoints(s.origin, s.rotation, Deployables.STATION_BOARDING_POINTS.length);
    }

    // ------------------------------------------------------------------ flight

    /** One tick of a transit flight in any phase but APPROACH. */
    static void step(Store<EntityStore> store, World world, StructureShape s, BalloonFlight f, long now) {
        TransitFlight t = f.transit;
        if (t == null || f.stopping) {
            return;
        }
        TransformComponent tr = store.getComponent(f.balloonRef, TransformComponent.getComponentType());
        if (tr == null) {
            return;
        }
        double dt = Math.min((now - f.lastDescentMs) / 1000.0, MAX_DT);
        f.lastDescentMs = now;
        TransitNetwork net = TransitNetwork.get();
        TransitNetwork.Station dest = t.to == null ? null : net.byName(world.getName(), t.to);
        boolean climbing = false;
        switch (t.phase) {
            case CLIMB -> {
                climbing = true;
                boolean blocked = moveVertical(world, s, f, f.descentSpeed * dt, now);
                if (f.entityPos.y >= t.cruiseY || stuck(t, blocked, now)) {
                    if (dest == null) {
                        // Nowhere to go: back on the departure platform (the rerouting of removed stations is Task 9).
                        TransitNetwork.Station from = net.byName(world.getName(), t.from);
                        BalloonManager.get().releaseTransitPassengers(store, f, from != null ? boardingPoints(from) : null);
                        beginVanish(f, t, now);
                    } else {
                        t.setPhase(TransitFlight.Phase.CRUISE, now);
                    }
                }
            }
            case CRUISE, HOLD -> {
                if (dest == null) {
                    rescue(store, world, f, t, null);
                    return;
                }
                climbing = cruise(store, world, s, f, t, dest, dt, now);
                if (t.phase == TransitFlight.Phase.CRUISE && now - t.lastProgressMs > TransitConstants.RESCUE_NO_PROGRESS_SECONDS * 1000L) {
                    rescue(store, world, f, t, dest);
                    return;
                }
            }
            case DESCEND -> {
                if (t.groundLanding) {
                    // No station left to go to: straight down until a block or liquid stops the balloon, then the passengers are set down.
                    boolean blocked = moveVertical(world, s, f, -f.descentSpeed * dt, now);
                    if (blocked || f.entityPos.y <= 1) {
                        BalloonManager.get().groundTransitPassengers(store, f);
                        finish(store, world, f, t);
                        return;
                    }
                    break;
                }
                Vector3i dock = dest != null ? StationQueue.dockOrigin(dest) : null;
                boolean blocked = moveVertical(world, s, f, -f.descentSpeed * dt, now);
                if (dock == null || f.entityPos.y <= dock.y || blocked) {
                    if (dock != null && f.entityPos.y < dock.y) {
                        f.entityPos.y = dock.y;
                    }
                    drop(store, world, f, t, dest, now);
                }
            }
            case VANISH -> {
                if (now - t.phaseSinceMs < TransitConstants.DROP_WAIT_SECONDS * 1000L) {
                    t.lastProgressMs = now; // waits on the pad after the drop before climbing away
                    break;
                }
                climbing = true;
                boolean blocked = moveVertical(world, s, f, f.descentSpeed * dt, now);
                if (f.entityPos.y >= t.vanishY || stuck(t, blocked, now)) {
                    finish(store, world, f, t);
                    return;
                }
            }
            default -> {
                LOGGER.at(Level.WARNING).log("Phase %s non gérée, la montgolfière disparaît", t.phase);
                finish(store, world, f, t);
                return;
            }
        }
        BalloonManager.get().setTransitBoost(store, f, climbing);
        tr.setPosition(BalloonManager.get().displayPosition(f.kind, f.entityPos, f.rotation));
        VehicleParts.sync(store, f.parts, f.balloonRef, f.observerRef);
        BalloonLights.follow(store, f);
    }

    /** A vertical move that stays blocked for the rescue delay ends the climb (nothing more can be done). */
    private static boolean stuck(TransitFlight t, boolean blocked, long now) {
        return blocked && now - t.lastProgressMs > TransitConstants.RESCUE_NO_PROGRESS_SECONDS * 1000L;
    }

    private static void beginVanish(BalloonFlight f, TransitFlight t, long now) {
        t.vanishY = f.entityPos.y + TransitConstants.DESPAWN_HEIGHT_ABOVE_DROP;
        t.setPhase(TransitFlight.Phase.VANISH, now);
    }

    /** Ends the flight (on the next world task: the flights map is being iterated) and frees the arrival quay. */
    private static void finish(Store<EntityStore> store, World world, BalloonFlight f, TransitFlight t) {
        f.stopping = true;
        world.execute(() -> BalloonManager.get().endTransit(store, f));
    }

    // ------------------------------------------------------------------ cruise and hold

    /**
     * CRUISE and HOLD. Returns true while the balloon climbs (boosted flame). CRUISE flies straight to the pad column; at HOLDING_DISTANCE
     * from the pad it must take the quay (StationQueue.tryBeginArrival), else it holds at its holding point.
     */
    private static boolean cruise(Store<EntityStore> store, World world, StructureShape s, BalloonFlight f, TransitFlight t,
                                  TransitNetwork.Station dest, double dt, long now) {
        Vector3i dock = StationQueue.dockOrigin(dest);
        Vector3d pad = new Vector3d(dock.x + 0.5, f.entityPos.y, dock.z + 0.5);
        double dist = Math.hypot(pad.x - f.entityPos.x, pad.z - f.entityPos.z);
        if (t.phase == TransitFlight.Phase.CRUISE && !t.claimed && dist <= TransitConstants.HOLDING_DISTANCE) {
            if (StationQueue.tryBeginArrival(dest, f.pilotUuid)) {
                t.claimed = true;
            } else {
                StationQueue.registerArrival(dest, f.pilotUuid);
                t.comingFrom = new Vector3d(f.entityPos);
                t.nextHoldMsgMs = now;
                t.setPhase(TransitFlight.Phase.HOLD, now);
            }
        }
        if (t.phase == TransitFlight.Phase.HOLD) {
            if (StationQueue.tryBeginArrival(dest, f.pilotUuid)) {
                t.claimed = true;
                t.setPhase(TransitFlight.Phase.CRUISE, now);
            } else {
                holdMessage(store, f, t, dest, now);
                int idx = StationQueue.holdOrder(dest, f.pilotUuid);
                Vector3d hp = holdingPoint(pad, t.comingFrom != null ? t.comingFrom : f.entityPos, idx);
                return flyToward(world, s, f, t, hp.x, hp.z, dt, now);
            }
        }
        boolean climbing = flyToward(world, s, f, t, pad.x, pad.z, dt, now);
        if (t.claimed && Math.hypot(pad.x - f.entityPos.x, pad.z - f.entityPos.z) < ARRIVED) {
            t.setPhase(TransitFlight.Phase.DESCEND, now);
        }
        return climbing;
    }

    /** "Quay occupied, landing in N s" to the passengers, every MESSAGE_INTERVAL_SECONDS. */
    private static void holdMessage(Store<EntityStore> store, BalloonFlight f, TransitFlight t, TransitNetwork.Station dest, long now) {
        if (now < t.nextHoldMsgMs) {
            return;
        }
        t.nextHoldMsgMs = now + TransitConstants.MESSAGE_INTERVAL_SECONDS * 1000L;
        int ahead = StationQueue.holdOrder(dest, f.pilotUuid);
        int left = StationQueue.secondsLeft(dest);
        int seconds = (left > 0 ? left : 0) + HOLD_CLEAR_SECONDS + ahead * HOLD_CYCLE_SECONDS;
        for (BalloonFlight.Passenger p : f.passengers) {
            PlayerRef pr = p.ref.isValid() ? store.getComponent(p.ref, PlayerRef.getComponentType()) : null;
            if (pr != null) {
                pr.sendMessage(Texts.t("transit.arrival.waiting").param("seconds", seconds));
            }
        }
    }

    /**
     * One tick toward (tx, tz) at the cruise height. Returns true while climbing to clear an obstacle. Order: obstacle seen ahead (climb,
     * stop advancing, keep the new height), unloaded chunks ahead (wait up to CHUNK_WAIT_SECONDS then advance cell by cell), then advance.
     */
    private static boolean flyToward(World world, StructureShape s, BalloonFlight f, TransitFlight t, double tx, double tz, double dt,
                                     long now) {
        double dx = tx - f.entityPos.x;
        double dz = tz - f.entityPos.z;
        double dist = Math.hypot(dx, dz);
        if (dist < ARRIVED) {
            t.lastProgressMs = now; // holding at its point or above the pad: not stuck
            return false;
        }
        double ux = dx / dist;
        double uz = dz / dist;
        if (now - t.lastAheadMs >= AHEAD_INTERVAL_MS) {
            t.lastAheadMs = now;
            lookAhead(world, s, f, t, ux, uz, Math.min(f.cruiseSpeed * AHEAD_SECONDS, dist));
        }
        if (t.aheadBlocked) {
            boolean blocked = moveVertical(world, s, f, f.descentSpeed * dt, now);
            t.cruiseY = Math.max(t.cruiseY, f.entityPos.y);
            return !blocked;
        }
        if (t.aheadUnloaded) {
            if (t.chunkWaitSinceMs == 0) {
                t.chunkWaitSinceMs = now;
            }
            if (now - t.chunkWaitSinceMs < TransitConstants.CHUNK_WAIT_SECONDS * 1000L) {
                return false; // waiting for the chunks (not counted as stuck: 10 s is below the 30 s rescue delay)
            }
        } else {
            t.chunkWaitSinceMs = 0;
        }
        // Eased arrival: full speed, slowing over the last blocks so the passengers' follow velocity does not jump.
        double speed = Math.min(f.cruiseSpeed, Math.max(0.5, dist * 2));
        double step = Math.min(speed * dt, dist);
        if (moveHorizontal(world, s, f, ux * step, uz * step)) {
            t.lastProgressMs = now;
        } else {
            t.aheadBlocked = true; // the next cell is not free: climb on the next ticks, the look-ahead re-tests in 250 ms
        }
        return false;
    }

    /** Tests the flight shape along the direction (every AHEAD_SPACING blocks up to maxDist) and sets aheadBlocked and aheadUnloaded. */
    private static void lookAhead(World world, StructureShape s, BalloonFlight f, TransitFlight t, double ux, double uz, double maxDist) {
        boolean blocked = false;
        boolean unloaded = false;
        int[] ext = extent(s, f, t);
        for (double d = 1; d <= maxDist + 1; d += AHEAD_SPACING) {
            double dd = Math.min(d, Math.max(maxDist, 1));
            Vector3i cell = new Vector3i((int) Math.floor(f.entityPos.x + ux * dd), f.lastCheckedOrigin.y,
                    (int) Math.floor(f.entityPos.z + uz * dd));
            if (!areaLoaded(world, cell, ext)) {
                unloaded = true;
                break;
            }
            if (BalloonManager.collision(world, s, cell, f.rotation, true) != null) {
                blocked = true;
                break;
            }
        }
        t.aheadBlocked = blocked;
        t.aheadUnloaded = unloaded;
    }

    /** Extent of the flight cells, rotated (minX, maxX, minZ, maxZ, minY, maxY), cached in the flight. */
    private static int[] extent(StructureShape s, BalloonFlight f, TransitFlight t) {
        if (t.extent == null) {
            int[] e = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
            for (StructureShape.Cell c : s.flightCells()) {
                Vector3i r = c.rotated(f.rotation);
                e[0] = Math.min(e[0], r.x);
                e[1] = Math.max(e[1], r.x);
                e[2] = Math.min(e[2], r.z);
                e[3] = Math.max(e[3], r.z);
                e[4] = Math.min(e[4], r.y);
                e[5] = Math.max(e[5], r.y);
            }
            t.extent = e;
        }
        return t.extent;
    }

    /** True if every chunk section crossed by the flight cells at this origin is loaded (0.7: sections, not columns). */
    private static boolean areaLoaded(World world, Vector3i origin, int[] ext) {
        return SectionBlocks.sectionsLoaded(world.getChunkStore(), origin.x + ext[0], origin.y + ext[4], origin.z + ext[2],
                origin.x + ext[1], origin.y + ext[5], origin.z + ext[3]);
    }

    // ------------------------------------------------------------------ movement

    /**
     * Moves the entity vertically by dy blocks (continuous), testing each new cell crossed with the flight shape (cells above the
     * construction limit are free). Stops exactly at the last free cell. Returns true if a block stopped the move. Progress time is updated.
     */
    private static boolean moveVertical(World world, StructureShape s, BalloonFlight f, double dy, long now) {
        TransitFlight t = f.transit;
        double y = f.entityPos.y + dy;
        int cell = f.lastCheckedOrigin.y;
        boolean blocked = false;
        if (dy >= 0) {
            while (y >= cell + 1) {
                Vector3i next = new Vector3i(f.lastCheckedOrigin).add(0, 1, 0);
                if (BalloonManager.collision(world, s, next, f.rotation, true) != null) {
                    blocked = true;
                    y = cell + 0.999;
                    break;
                }
                f.lastCheckedOrigin.set(next);
                cell = next.y;
            }
        } else {
            while (y < cell) {
                Vector3i next = new Vector3i(f.lastCheckedOrigin).add(0, -1, 0);
                if (BalloonManager.collision(world, s, next, f.rotation, true) != null) {
                    blocked = true;
                    y = cell;
                    break;
                }
                f.lastCheckedOrigin.set(next);
                cell = next.y;
            }
        }
        if (Math.abs(y - f.entityPos.y) > 1e-6) {
            t.lastProgressMs = now;
        }
        f.entityPos.y = y;
        return blocked;
    }

    /** Moves the entity horizontally (continuous). If the move enters a new origin cell, that cell must be free: otherwise nothing moves. */
    private static boolean moveHorizontal(World world, StructureShape s, BalloonFlight f, double dx, double dz) {
        double nx = f.entityPos.x + dx;
        double nz = f.entityPos.z + dz;
        int cx = (int) Math.floor(nx);
        int cz = (int) Math.floor(nz);
        if (cx != f.lastCheckedOrigin.x || cz != f.lastCheckedOrigin.z) {
            Vector3i next = new Vector3i(cx, f.lastCheckedOrigin.y, cz);
            if (BalloonManager.collision(world, s, next, f.rotation, true) != null) {
                return false;
            }
            f.lastCheckedOrigin.set(next);
        }
        f.entityPos.x = nx;
        f.entityPos.z = nz;
        return true;
    }

    // ------------------------------------------------------------------ arrival

    /**
     * DROP: passengers released and teleported on the boarding platform of the destination (spread over its drop points), welcome message
     * and big title with the station name, then VANISH (climb of DESPAWN_HEIGHT_ABOVE_DROP).
     */
    private static void drop(Store<EntityStore> store, World world, BalloonFlight f, TransitFlight t, TransitNetwork.Station dest, long now) {
        List<PlayerRef> people = new ArrayList<>();
        for (BalloonFlight.Passenger p : f.passengers) {
            PlayerRef pr = p.ref.isValid() ? store.getComponent(p.ref, PlayerRef.getComponentType()) : null;
            if (pr != null) {
                people.add(pr);
            }
        }
        List<Vector3d> points = dest != null ? dropPoints(dest.origin, dest.rotation, Math.max(1, f.passengers.size())) : null;
        BalloonManager.get().releaseTransitPassengers(store, f, points);
        if (dest != null) {
            for (PlayerRef pr : people) {
                welcome(pr, dest);
            }
        }
        beginVanish(f, t, now);
    }

    private static void welcome(PlayerRef pr, TransitNetwork.Station dest) {
        try {
            pr.sendMessage(Texts.t("transit.arrival.welcome").param("name", dest.name));
            // The station name is a proper noun chosen by a player, not translatable text.
            EventTitleUtil.showEventTitleToPlayer(pr, Message.raw(dest.name), Message.raw("Orbis Horizon"),
                    com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle.Major);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Titre d'arrivée non affiché");
            Telemetry.error("transit", e, null, null);
        }
    }

    /**
     * Rescue: 30 s without progress (or no destination left to head for). The passengers are told, teleported to the destination drop
     * points (or put on the ground below their seat if there is none), and the flight ends. Nothing is placed in blocks.
     */
    private static void rescue(Store<EntityStore> store, World world, BalloonFlight f, TransitFlight t, TransitNetwork.Station dest) {
        LOGGER.at(Level.WARNING).log("Montgolfière de transport sans progrès en %s : secours", f.entityPos);
        List<PlayerRef> people = new ArrayList<>();
        for (BalloonFlight.Passenger p : f.passengers) {
            PlayerRef pr = p.ref.isValid() ? store.getComponent(p.ref, PlayerRef.getComponentType()) : null;
            if (pr != null) {
                people.add(pr);
            }
        }
        List<Vector3d> points = dest != null ? dropPoints(dest.origin, dest.rotation, Math.max(1, f.passengers.size())) : null;
        BalloonManager.get().releaseTransitPassengers(store, f, points);
        for (PlayerRef pr : people) {
            pr.sendMessage(Texts.t("transit.rescue").param("name", dest != null ? dest.name : ""));
        }
        finish(store, world, f, t);
    }
}
