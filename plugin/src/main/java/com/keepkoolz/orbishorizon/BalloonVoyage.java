package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** State and pure rules of a large hot air balloon voyage flown by the NPC pilot. */
final class BalloonVoyage {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double MAX_DT = 0.25;
    enum Phase { CLIMB, CRUISE, DESCEND }

    final String destName;
    final VoyagePlanner.Kind destKind;
    final double destX, destZ;
    final UUID captain;
    /** Where the voyage started (block centre of the origin), used to pick the landing columns on the way back. */
    volatile double fromX, fromZ;
    volatile double cruiseY;
    volatile Phase phase = Phase.CLIMB;
    /** Landing column chosen when the cruise ends ({x, z}, block coords of the origin), null before. */
    volatile int[] landing;
    volatile boolean arrived;   // DESCEND because the destination was reached
    volatile long lastErrorMs;  // rate limit of the step exception warning
    volatile boolean stopped;   // sticky: the voyage was stopped once (dry, blocked, map, captain gone)
    volatile boolean forced;    // no candidate column was clear of the station zones
    /** Movement bookkeeping shared with TransitAutopilot (progress time, look-ahead, extent). */
    final TransitFlight motion;

    BalloonVoyage(String destName, VoyagePlanner.Kind destKind, double destX, double destZ, double cruiseY, UUID captain, long now) {
        this.destName = destName;
        this.destKind = destKind;
        this.destX = destX;
        this.destZ = destZ;
        this.cruiseY = cruiseY;
        this.captain = captain;
        this.motion = new TransitFlight(null, destName, TransitFlight.Phase.CLIMB, now);
    }

    /** Stops the voyage once: phase DESCEND above where the balloon is. True only on the first call. */
    synchronized boolean stop() {
        if (stopped) {
            return false;
        }
        stopped = true;
        phase = Phase.DESCEND;
        return true;
    }

    /**
     * 9 origin columns {x, z}: the destination, 4, 8, 12 and 16 blocks back toward (fromX, fromZ), then 8 and
     * 16 blocks to the left, then to the right of the destination. Left is seen facing the way back, with
     * x east and z south: left = (back.z, -back.x). Each column is floor() of the point.
     */
    static List<int[]> landingCandidates(double destX, double destZ, double fromX, double fromZ) {
        double bx = fromX - destX, bz = fromZ - destZ;
        double len = Math.hypot(bx, bz);
        if (len < 1e-6) {
            bx = 1;
            bz = 0;
        } else {
            bx /= len;
            bz /= len;
        }
        double lx = bz, lz = -bx;
        List<int[]> out = new ArrayList<>(9);
        out.add(col(destX, destZ));
        for (int d = 4; d <= 16; d += 4) {
            out.add(col(destX + bx * d, destZ + bz * d));
        }
        for (int d = 8; d <= 16; d += 8) {
            out.add(col(destX + lx * d, destZ + lz * d));
        }
        for (int d = 8; d <= 16; d += 8) {
            out.add(col(destX - lx * d, destZ - lz * d));
        }
        return out;
    }

    private static int[] col(double x, double z) {
        return new int[] {(int) Math.floor(x), (int) Math.floor(z)};
    }

    /** First candidate whose shape at that column touches no zone (height ignored), else null. */
    static int[] firstClear(List<StationZone.Zone> zones, StructureShape s, Rotation rotation, List<int[]> candidates) {
        for (int[] c : candidates) {
            if (StationZone.blockingStationColumn(zones, s, c[0], c[1], rotation) == null) {
                return c;
            }
        }
        return null;
    }

    /** Cruise height of the entity origin: the ceiling is lowered by the top of the flight cells. */
    static int cruiseY(int departureY, int arrivalGroundY, int worldMaxY, int flightTop) {
        return Math.max(VoyagePlanner.cruiseY(departureY, arrivalGroundY, worldMaxY - flightTop), departureY);
    }

    /** Refusal key for the departure, null if it may leave. The captain occupies one of the free seats. */
    static String departureRefusalKey(boolean captainAboard, int othersAboard, int freeSeats, boolean freeHotbarSlot) {
        if (!captainAboard) {
            return "crew.voyage.noCaptain";
        }
        if (othersAboard > freeSeats - 1) {
            return "error.balloonFull";
        }
        if (!freeHotbarSlot) {
            return "crew.voyage.handsFull";
        }
        return null;
    }

    // ------------------------------------------------------------------ flight step

    /** One tick of the voyage (called by BalloonManager.tickDriven). Never throws. */
    static void step(Store<EntityStore> store, World world, StructureShape s, BalloonFlight f, long now) {
        BalloonVoyage v = f.voyage;
        if (v == null || f.stopping) {
            return;
        }
        try {
            // Watchdog: a voyage whose captain is no longer among the passengers stops (whatever path removed him).
            if (!v.stopped) {
                List<UUID> aboard = new ArrayList<>();
                for (BalloonFlight.Passenger p : f.passengers) {
                    aboard.add(p.uuid);
                }
                if (!captainAboard(aboard, v.captain) && v.stop()) {
                    BalloonManager.get().tellPassengers(f, "crew.voyage.captainGone");
                }
            }
            run(store, world, s, f, v, now);
        } catch (RuntimeException e) {
            // A recurring failure must not repeat every tick: stop the voyage (normal descent) and log at most every 10 s.
            v.stop();
            if (now - v.lastErrorMs >= 10_000) {
                v.lastErrorMs = now;
                LOGGER.at(Level.WARNING).withCause(e).log("Balloon voyage: flight step failed, voyage stopped");
                Telemetry.error("balloon.flight", e, Telemetry.kindId(f.kind), "voyage");
            }
        }
    }

    /** Pure: is the captain among the uuids aboard? */
    static boolean captainAboard(java.util.Collection<UUID> aboard, UUID captain) {
        return captain != null && aboard.contains(captain);
    }

    private static PlayerRef captainOf(Store<EntityStore> store, BalloonFlight f, BalloonVoyage v) {
        for (BalloonFlight.Passenger p : f.passengers) {
            if (p.uuid.equals(v.captain) && p.ref.isValid()) {
                return store.getComponent(p.ref, PlayerRef.getComponentType());
            }
        }
        return null;
    }

    private static void run(Store<EntityStore> store, World world, StructureShape s, BalloonFlight f, BalloonVoyage v, long now) {
        TransformComponent tr = store.getComponent(f.balloonRef, TransformComponent.getComponentType());
        if (tr == null) {
            return;
        }
        BalloonManager bm = BalloonManager.get();
        double dt = Math.min((now - f.lastDescentMs) / 1000.0, MAX_DT);
        f.lastDescentMs = now;
        burn(store, f, v, bm, now);
        boolean climbing = false;
        TransitFlight m = v.motion;
        switch (v.phase) {
            case CLIMB -> {
                climbing = !f.dry;
                boolean blocked = TransitAutopilot.moveVertical(world, s, f, m, f.descentSpeed * dt, now, false);
                if (f.entityPos.y >= v.cruiseY) {
                    v.phase = Phase.CRUISE;
                    m.lastProgressMs = now;
                } else if (blocked && now - m.lastProgressMs > TransitConstants.RESCUE_NO_PROGRESS_SECONDS * 1000L) {
                    blockedStop(store, f, v);
                }
            }
            case CRUISE -> climbing = cruise(store, world, s, f, v, dt, now);
            case DESCEND -> {
                boolean blocked = TransitAutopilot.moveVertical(world, s, f, m, -f.descentSpeed * dt, now, false);
                if (blocked || f.entityPos.y <= 1) {
                    land(store, world, f, v, tr, bm);
                    return;
                }
            }
        }
        if (!f.dry) {
            bm.setTransitBoost(store, f, climbing);
        }
        tr.setPosition(bm.displayPosition(f.kind, f.entityPos, f.rotation));
        VehicleParts.sync(store, f.parts, f.balloonRef, f.observerRef);
        AirshipCrew.syncMannequin(store, f.crewMan, now);
        BalloonLights.follow(store, f);
    }

    /** Burns the fuel and shows the fuel window to the captain; dry: flame off, the voyage stops and the pilot says so. */
    private static void burn(Store<EntityStore> store, BalloonFlight f, BalloonVoyage v, BalloonManager bm, long now) {
        BurnerFuel fuel = f.burner;
        if (fuel == null) {
            return;
        }
        PlayerRef captain = captainOf(store, f, v);
        if (f.dry) {
            FuelHud.showDry(store, captain, FuelHud.Vehicle.BALLOON);
            return;
        }
        double dt = (now - f.lastBurnMs) / 1000.0;
        f.lastBurnMs = now;
        if (dt <= 0) {
            return;
        }
        fuel.burn(dt);
        double left = fuel.remainingSeconds();
        if (left <= 0) {
            f.dry = true;
            bm.setBalloonFlame(store, f, false);
            FuelHud.showDry(store, captain, FuelHud.Vehicle.BALLOON);
            if (v.stop() && captain != null) {
                captain.sendMessage(Texts.pilot("crew.voyage.dryBalloon", f.crew));
            }
            return;
        }
        FuelHud.show(store, captain, FuelHud.Vehicle.BALLOON, left, fuel.fuelItems());
    }

    private static void blockedStop(Store<EntityStore> store, BalloonFlight f, BalloonVoyage v) {
        if (v.stop()) {
            PlayerRef captain = captainOf(store, f, v);
            if (captain != null) {
                captain.sendMessage(Texts.pilot("crew.voyage.blocked", f.crew));
            }
        }
    }

    /** Straight flight to the destination, then to the chosen landing column. Returns true while climbing to clear an obstacle. */
    private static boolean cruise(Store<EntityStore> store, World world, StructureShape s, BalloonFlight f, BalloonVoyage v, double dt,
                                  long now) {
        TransitFlight m = v.motion;
        if (now - m.lastProgressMs > TransitConstants.RESCUE_NO_PROGRESS_SECONDS * 1000L) {
            blockedStop(store, f, v);
            return false;
        }
        if (f.dry) {
            return false;
        }
        if (v.landing == null) {
            boolean climbing = TransitAutopilot.flyToward(world, s, f, m, v.destX, v.destZ, dt, now, false);
            if (Math.hypot(v.destX - f.entityPos.x, v.destZ - f.entityPos.z) < TransitAutopilot.ARRIVED) {
                chooseLanding(world, s, f, v);
            }
            return climbing;
        }
        double tx = v.landing[0] + 0.5;
        double tz = v.landing[1] + 0.5;
        boolean climbing = TransitAutopilot.flyToward(world, s, f, m, tx, tz, dt, now, false);
        if (Math.hypot(tx - f.entityPos.x, tz - f.entityPos.z) < TransitAutopilot.ARRIVED) {
            v.arrived = true;
            v.phase = Phase.DESCEND;
        }
        return climbing;
    }

    /** First of the 9 columns whose shape touches no station zone, else the destination column (forced). */
    private static void chooseLanding(World world, StructureShape s, BalloonFlight f, BalloonVoyage v) {
        List<StationZone.Zone> zones = new ArrayList<>();
        for (TransitNetwork.Station st : TransitNetwork.get().stations(world.getName())) {
            zones.add(StationZone.zoneOf(st));
        }
        List<int[]> candidates = landingCandidates(v.destX, v.destZ, v.fromX, v.fromZ);
        int[] c = firstClear(zones, s, f.rotation, candidates);
        if (c == null) {
            c = candidates.get(0);
            v.forced = true;
        }
        v.landing = c;
    }

    /** Contact (or y 1): the balloon is placed in blocks here, on the next world task. */
    private static void land(Store<EntityStore> store, World world, BalloonFlight f, BalloonVoyage v, TransformComponent tr,
                             BalloonManager bm) {
        f.stopping = true;
        tr.setPosition(bm.displayPosition(f.kind, f.entityPos, f.rotation));
        Vector3i origin = new Vector3i(f.lastCheckedOrigin);
        PlayerRef captain = captainOf(store, f, v);
        Ref<EntityStore> capRef = captain != null ? captain.getReference() : null;
        if (v.arrived && captain != null) {
            captain.sendMessage(ShipVoyage.withDest(Texts.pilot("crew.voyage.arrivedBalloon", f.crew), v.destKind, v.destName));
        }
        if (v.forced) {
            LOGGER.at(Level.INFO).log("Forced landing in a station zone: balloon voyage to %s, origin %s", v.destName, origin);
        }
        CaptainMap.forget(v.captain);
        if (capRef != null && capRef.isValid()) {
            CaptainMap.removeAll(store, capRef);
        }
        world.execute(() -> {
            if (!bm.active(f)) {
                return;
            }
            try {
                bm.finishFlight(store, f, origin);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Balloon voyage: landing failed");
                Telemetry.error("balloon.flight", e, Telemetry.kindId(f.kind), "voyage");
            }
        });
    }
}
