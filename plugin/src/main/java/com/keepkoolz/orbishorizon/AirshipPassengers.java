package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.FlyMode;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Airship passengers (T64): the players aboard at take-off, other than the pilot, are put on the stools of the ship
 * (Furniture_Crude_Stool, one in front of each bench where there was room) and follow their stool through the flight. The ship
 * turns freely, so the seat point is the stool's prefab point rotated by the heading (same calculation as the lights).
 *
 * Three ways to hold a passenger on their seat (/orbishorizon airship tune passengers, next take-off):
 * - FOLLOW (default): the passenger is in forced flight at zero flight speeds (they cannot move by themselves) and the server
 *   sends them every tick a ChangeVelocity (Set) equal to the seat's velocity plus a correction toward the seat, the mechanism
 *   of the pilot's velocity carry (validated in game). Beyond passengerSnap blocks from the seat, a teleport puts them back.
 *   Chosen because the first airship test (5 October 2026) showed that a player mounted on the ship entity with the Minecart
 *   controller drives it on his own client like a minecart (gravity, own collisions), ignoring the server positions.
 * - MOUNT: the balloon's method (T21), MountedComponent on the ship entity with the seat offset (x and z inverted, like the
 *   lights). Kept to compare in game, with the risk above.
 * - TELEPORT: forced flight at zero speed, teleport to the seat on every drift beyond 0.35 block (jerky, last resort).
 *
 * The seated pose is the balloon's (T25: sitting flag and Sit animation, BalloonManager.applyPose). Every method here runs on the
 * world thread and never throws.
 */
final class AirshipPassengers {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    enum Mode { FOLLOW, MOUNT, TELEPORT }

    /** No action on a passenger during this time after their take-off teleport (the reported position is stale). */
    private static final long GRACE_MS = 400;
    /** After a correction teleport, same reason. */
    private static final long TELEPORT_PAUSE_MS = 250;
    /** TELEPORT mode: drift tolerated and least interval between two teleports (the balloon's values). */
    private static final double TELEPORT_MODE_DISTANCE = 0.35;
    private static final long TELEPORT_MODE_INTERVAL_MS = 150;
    /** MOUNT mode: least interval between two remounts, and losses before falling back to TELEPORT. */
    private static final long REMOUNT_INTERVAL_MS = 500;
    private static final int MAX_LOSSES = 3;
    /** FOLLOW: a velocity smaller than this (b/s) is not sent (a zero is sent once to stop). */
    private static final double VELOCITY_EPS = 0.02;
    /** Standing point on landing: slightly above the floor. */
    private static final float LANDING_LIFT = 0.1f;

    /** A passenger of an airship flight. */
    static final class Rider {
        /** Seat data and seated pose state, shared with the balloon's pose code. */
        final BalloonFlight.Passenger p;
        /** Cell where the passenger stands on landing (free neighbour of the stool), prefab frame, floor height included. */
        final Vector3f exitLocal;
        volatile Mode mode;
        /** Flight settings changed (forced flight at zero speed): to be restored at the end. */
        boolean hover;
        boolean velActive;
        /** Seat point (world) at the previous update, for the seat velocity. */
        Vector3d lastSeat;
        long lastSeatMs;
        long pauseUntilMs;
        long lastActionMs;
        int losses;
        int teleports;

        Rider(BalloonFlight.Passenger p, Vector3f exitLocal, Mode mode) {
            this.p = p;
            this.exitLocal = exitLocal;
            this.mode = mode;
        }
    }

    // ------------------------------------------------------------------ tuning (/orbishorizon airship tune)

    volatile Mode mode = Mode.FOLLOW;
    /** FOLLOW: correction toward the seat, per second of distance (b/s per block). */
    volatile double gain = 2.0;
    /** FOLLOW: the correction is capped to this speed (b/s). */
    volatile double maxCorrection = 4.0;
    /** FOLLOW: beyond this distance (blocks) from the seat, the passenger is teleported back. */
    volatile double snap = 1.25;

    private final AirshipManager manager;

    AirshipPassengers(AirshipManager manager) {
        this.manager = manager;
    }

    // ------------------------------------------------------------------ seats

    /** Blocks whose every cell is one passenger seat: the stool (1 cell) and the tavern bench (2 cells, 2 seats in the game). */
    static final java.util.Set<String> SEAT_BLOCKS = java.util.Set.of(BalloonShape.SEAT_BLOCK, "Furniture_Tavern_Bench");

    /**
     * Passenger seats of the ship: every cell (origin and filler) of a seat block, sorted by z then x. A tavern bench gives 2
     * seats, one per cell, like the 2 Seats of its asset (local x 0 and -1, which are its 2 cells).
     */
    static List<BalloonShape.Cell> seats(BalloonShape s) {
        List<BalloonShape.Cell> list = new ArrayList<>();
        for (BalloonShape.Cell c : s.cells()) {
            if (SEAT_BLOCKS.contains(c.baseName())) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparingInt(BalloonShape.Cell::z).thenComparingInt(BalloonShape.Cell::x));
        return list;
    }

    /** Seating point of a seat cell, prefab frame: centre of the cell, at the stool's seat height (T21 value, bench too). */
    static Vector3f seatLocal(BalloonShape.Cell c) {
        return new Vector3f(c.x(), c.y() + BalloonManager.get().seatHeight(), c.z());
    }

    /**
     * Where a passenger stands on landing: a horizontal neighbour of the stool with 2 free cells and a floor, preferably the one
     * opposite a block (the bench in front of the stool, so the passenger stands in the aisle). Else on top of the stool.
     */
    static Vector3f exitLocal(BalloonShape s, BalloonShape.Cell c) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Vector3f fallback = null;
        for (int[] d : dirs) {
            int x = c.x() + d[0];
            int z = c.z() + d[1];
            if (s.cellAt(x, c.y(), z) != null || s.cellAt(x, c.y() + 1, z) != null || s.cellAt(x, c.y() - 1, z) == null) {
                continue;
            }
            Vector3f spot = new Vector3f(x, c.y() + LANDING_LIFT, z);
            if (s.cellAt(c.x() - d[0], c.y(), c.z() - d[1]) != null) {
                return spot;
            }
            if (fallback == null) {
                fallback = spot;
            }
        }
        return fallback != null ? fallback : new Vector3f(c.x(), c.y() + 0.6f, c.z());
    }

    /** Players inside the ship's box at take-off, other than the pilot. */
    static List<BalloonManager.Occupant> aboard(Store<EntityStore> store, World world, BalloonShape s, Vector3i origin, Rotation rotation,
                                                UUID pilot) {
        List<BalloonManager.Occupant> list = new ArrayList<>();
        for (PlayerRef other : world.getPlayerRefs()) {
            if (other.getUuid().equals(pilot)) {
                continue;
            }
            Ref<EntityStore> ref = other.getReference();
            TransformComponent t = ref != null && ref.isValid() ? store.getComponent(ref, TransformComponent.getComponentType()) : null;
            if (t != null && AirshipManager.insideShape(s, origin, rotation, t.getPosition())) {
                list.add(new BalloonManager.Occupant(other, ref, 0));
            }
        }
        return list;
    }

    /** Pairs each player with a stool, closest pairs first. The caller has checked there are enough stools. */
    List<Rider> assign(Store<EntityStore> store, BalloonShape s, Vector3i origin, Rotation rotation, List<BalloonManager.Occupant> people) {
        List<Rider> result = new ArrayList<>();
        List<BalloonShape.Cell> seats = seats(s);
        if (people.isEmpty() || seats.isEmpty()) {
            return result;
        }
        Vector3d logical = new Vector3d(origin.x + 0.5, origin.y, origin.z + 0.5);
        record Pair(double distance, int person, int seat) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int j = 0; j < people.size(); j++) {
            TransformComponent t = store.getComponent(people.get(j).ref(), TransformComponent.getComponentType());
            Vector3d pos = t != null ? t.getPosition() : logical;
            for (int i = 0; i < seats.size(); i++) {
                pairs.add(new Pair(pos.distance(BalloonManager.prefabPoint(logical, rotation, seatLocal(seats.get(i)))), j, i));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::distance));
        int[] seatOf = new int[people.size()];
        java.util.Arrays.fill(seatOf, -1);
        boolean[] used = new boolean[seats.size()];
        for (Pair p : pairs) {
            if (seatOf[p.person()] < 0 && !used[p.seat()]) {
                seatOf[p.person()] = p.seat();
                used[p.seat()] = true;
            }
        }
        Mode m = mode;
        BalloonFlight.PassengerMode legacy = m == Mode.MOUNT ? BalloonFlight.PassengerMode.MOUNT : BalloonFlight.PassengerMode.TELEPORT;
        for (int j = 0; j < people.size(); j++) {
            if (seatOf[j] < 0) {
                continue;
            }
            BalloonShape.Cell c = seats.get(seatOf[j]);
            BalloonManager.Occupant o = people.get(j);
            BalloonFlight.Passenger p = new BalloonFlight.Passenger(o.ref(), o.player().getUuid(), o.player().getUsername(), seatOf[j],
                    new Vector3i(c.x(), c.y(), c.z()), seatLocal(c), false, legacy, BalloonManager.get().pose());
            result.add(new Rider(p, exitLocal(s, c), m));
        }
        return result;
    }

    // ------------------------------------------------------------------ boarding and every tick

    /**
     * Before the blocks are removed at take-off: each rider is taken off a block seat (the stool is going to disappear) and held
     * in the air at zero flight speed, so that nobody falls when the floor goes (MOUNT riders too, until they are mounted).
     */
    void prepare(Store<EntityStore> store, List<Rider> riders) {
        for (Rider r : riders) {
            try {
                if (r.p.ref.isValid()) {
                    MountUtil.unseat(store, r.p.ref);
                    setHover(store, r, true);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager du dirigeable %s non tenu en l'air", r.p.name);
            }
        }
    }

    /** Seats the riders of the flight once the ship entity exists: teleported to the stool (or mounted), seated pose, message. */
    void board(Store<EntityStore> store, AirshipFlight f) {
        long now = System.currentTimeMillis();
        for (Rider r : f.riders) {
            BalloonFlight.Passenger p = r.p;
            if (!p.ref.isValid()) {
                f.riders.remove(r);
                continue;
            }
            try {
                Vector3d seat = manager.shipPoint(f, p.seatLocal);
                if (r.mode == Mode.MOUNT) {
                    setHover(store, r, false);
                    mount(store, f, r);
                } else {
                    teleport(store, p.ref, seat);
                }
                r.lastSeat = seat;
                r.lastSeatMs = now;
                r.pauseUntilMs = now + GRACE_MS;
                p.boardedMs = now;
                BalloonManager.get().applyPose(store, p, now);
                PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
                if (pr != null) {
                    pr.sendMessage(Texts.t("airship.passenger.seated"));
                }
                LOGGER.at(Level.INFO).log("Passager du dirigeable %s : tabouret %s, %s", p.name, p.seatBlock, r.mode);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager du dirigeable %s non installé", p.name);
            }
        }
    }

    /** Every tick, after the ship pose of this tick is known: dead or gone riders are released, the others held on their seat. */
    void update(Store<EntityStore> store, World world, AirshipFlight f, long now) {
        if (f.riders.isEmpty()) {
            return;
        }
        for (Rider r : f.riders) {
            BalloonFlight.Passenger p = r.p;
            try {
                if (!p.ref.isValid()) {
                    f.riders.remove(r); // disconnected without the event, or another world
                    continue;
                }
                if (BalloonManager.isDead(store, p.ref)) {
                    release(store, r);
                    f.riders.remove(r);
                    LOGGER.at(Level.INFO).log("Passager du dirigeable %s mort en vol : libéré, le vol continue", p.name);
                    continue;
                }
                BalloonManager.get().applyPose(store, p, now);
                hold(store, world, f, r, now);
            } catch (RuntimeException e) {
                if (now - r.lastActionMs > 2000) {
                    r.lastActionMs = now;
                    LOGGER.at(Level.WARNING).withCause(e).log("Passager du dirigeable %s : mise à jour en échec", p.name);
                }
            }
        }
    }

    private void hold(Store<EntityStore> store, World world, AirshipFlight f, Rider r, long now) {
        BalloonFlight.Passenger p = r.p;
        Vector3d seat = manager.shipPoint(f, p.seatLocal);
        double dt = r.lastSeatMs == 0 ? 0 : (now - r.lastSeatMs) / 1000.0;
        Vector3d seatVel = new Vector3d();
        if (r.lastSeat != null && dt > 1e-3) {
            seatVel.set(seat).sub(r.lastSeat).div(dt);
        }
        r.lastSeat = seat;
        r.lastSeatMs = now;
        if (now < r.pauseUntilMs) {
            return;
        }
        if (r.mode == Mode.MOUNT) {
            MountedComponent mc = store.getComponent(p.ref, MountedComponent.getComponentType());
            if (mc != null && f.shipRef != null && f.shipRef.equals(mc.getMountedToEntity())) {
                return;
            }
            if (now - r.lastActionMs < REMOUNT_INTERVAL_MS) {
                return;
            }
            r.lastActionMs = now;
            if (++r.losses > MAX_LOSSES) {
                r.mode = Mode.TELEPORT;
                LOGGER.at(Level.WARNING).log("Passager du dirigeable %s : montage perdu %d fois, repli par téléportation", p.name, r.losses);
                world.execute(() -> {
                    if (f.riders.contains(r) && p.ref.isValid()) {
                        MountUtil.unseat(store, p.ref);
                        setHover(store, r, true);
                    }
                });
            } else {
                world.execute(() -> {
                    if (f.riders.contains(r) && p.ref.isValid() && f.shipRef != null && f.shipRef.isValid()) {
                        mount(store, f, r);
                    }
                });
            }
            return;
        }
        TransformComponent pt = store.getComponent(p.ref, TransformComponent.getComponentType());
        if (pt == null) {
            return;
        }
        Vector3d err = new Vector3d(seat).sub(pt.getPosition());
        double dist = err.length();
        if (r.mode == Mode.TELEPORT) {
            if (dist > TELEPORT_MODE_DISTANCE && now - r.lastActionMs >= TELEPORT_MODE_INTERVAL_MS) {
                r.lastActionMs = now;
                r.teleports++;
                scheduleTeleport(world, store, f, r, seat, pt);
            }
            return;
        }
        // FOLLOW
        if (dist > snap) {
            if (now - r.lastActionMs >= TELEPORT_PAUSE_MS) {
                r.lastActionMs = now;
                r.teleports++;
                r.pauseUntilMs = now + TELEPORT_PAUSE_MS;
                sendVelocity(store, r, 0, 0, 0);
                scheduleTeleport(world, store, f, r, seat, pt);
            }
            return;
        }
        Vector3d corr = new Vector3d(err).mul(gain);
        double cl = corr.length();
        if (cl > maxCorrection) {
            corr.mul(maxCorrection / cl);
        }
        Vector3d v = seatVel.add(corr);
        if (v.length() > VELOCITY_EPS) {
            sendVelocity(store, r, v.x, v.y, v.z);
        } else if (r.velActive) {
            sendVelocity(store, r, 0, 0, 0);
        }
    }

    private static void scheduleTeleport(World world, Store<EntityStore> store, AirshipFlight f, Rider r, Vector3d seat,
                                         TransformComponent pt) {
        Rotation3f look = new Rotation3f(pt.getRotation());
        Vector3d target = new Vector3d(seat);
        world.execute(() -> {
            if (f.riders.contains(r) && r.p.ref.isValid()) {
                store.putComponent(r.p.ref, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
            }
        });
    }

    private static void teleport(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d to) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        Rotation3f look = t != null ? new Rotation3f(t.getRotation()) : new Rotation3f();
        store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(new Vector3d(to), look));
    }

    /** MOUNT: the rider is mounted on the ship entity, offset in the entity's frame (prefab offset with x and z inverted). */
    private void mount(Store<EntityStore> store, AirshipFlight f, Rider r) {
        Vector3f d = new Vector3f(r.p.seatLocal).sub(Deployables.AIRSHIP.airship.pivot);
        store.putComponent(r.p.ref, MountedComponent.getComponentType(),
                new MountedComponent(f.shipRef, new Vector3f(-d.x, d.y, -d.z), MountController.Minecart));
    }

    /** Forced flight at zero flight speeds (the rider cannot fly away, nor fall), or back to the normal settings. */
    private static void setHover(Store<EntityStore> store, Rider r, boolean on) {
        Ref<EntityStore> ref = r.p.ref;
        if (!ref.isValid()) {
            return;
        }
        MovementManager mm = store.getComponent(ref, MovementManager.getComponentType());
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (mm == null || pr == null) {
            return;
        }
        if (on) {
            MovementSettings settings = mm.getSettings();
            settings.fly = FlyMode.Forced;
            settings.horizontalFlySpeed = 0f;
            settings.verticalFlySpeed = 0f;
            mm.update(pr.getPacketHandler());
            r.hover = true;
        } else if (r.hover) {
            mm.resetDefaultsAndUpdate(ref, store);
            r.hover = false;
        }
    }

    /** ChangeVelocity of type Set to the rider (same path as the pilot's velocity carry). */
    private static void sendVelocity(Store<EntityStore> store, Rider r, double vx, double vy, double vz) {
        try {
            Ref<EntityStore> ref = r.p.ref;
            Velocity v = store.getComponent(ref, Velocity.getComponentType());
            if (v != null) {
                v.addInstruction(new Vector3d(vx, vy, vz), null, ChangeVelocityType.Set);
            } else {
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr != null) {
                    pr.getPacketHandler().writeNoCache(new ChangeVelocity((float) vx, (float) vy, (float) vz, ChangeVelocityType.Set, null));
                }
            }
            r.velActive = vx != 0 || vy != 0 || vz != 0;
        } catch (RuntimeException e) {
            // A lost velocity is corrected by the next tick or by the teleport.
        }
    }

    // ------------------------------------------------------------------ end of the flight for a rider

    /** Undoes everything the flight changed on a rider (mount, velocity, flight settings, pose), without moving them. */
    private static void release(Store<EntityStore> store, Rider r) {
        try {
            if (!r.p.ref.isValid()) {
                return;
            }
            if (r.velActive) {
                sendVelocity(store, r, 0, 0, 0);
            }
            MountUtil.unseat(store, r.p.ref);
            BalloonManager.clearPose(store, r.p);
            setHover(store, r, false);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Passager du dirigeable %s non libéré proprement", r.p.name);
        }
    }

    /** Landing: every rider is released and stands next to their stool on the placed prefab. */
    void disembark(Store<EntityStore> store, AirshipFlight f, Vector3i origin, Rotation rotation) {
        Vector3d logical = new Vector3d(origin.x + 0.5, origin.y, origin.z + 0.5);
        for (Rider r : f.riders) {
            try {
                if (!r.p.ref.isValid()) {
                    continue;
                }
                release(store, r);
                if (BalloonManager.isDead(store, r.p.ref)) {
                    continue;
                }
                teleport(store, r.p.ref, BalloonManager.prefabPoint(logical, rotation, r.exitLocal));
                PlayerRef pr = store.getComponent(r.p.ref, PlayerRef.getComponentType());
                if (pr != null) {
                    pr.sendMessage(Texts.t("airship.passenger.landed"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager du dirigeable %s non reposé proprement", r.p.name);
            }
        }
        f.riders.clear();
    }

    /** Removal by an administrator: every rider is released and put on the ground under their seat. */
    void dropAll(Store<EntityStore> store, AirshipFlight f, PlayerRef admin) {
        for (Rider r : f.riders) {
            try {
                if (!r.p.ref.isValid()) {
                    continue;
                }
                release(store, r);
                if (!BalloonManager.isDead(store, r.p.ref)) {
                    BalloonManager.putOnGround(store, f.world, r.p.ref, manager.shipPoint(f, r.p.seatLocal));
                }
                if (admin == null || !admin.getUuid().equals(r.p.uuid)) {
                    PlayerRef pr = store.getComponent(r.p.ref, PlayerRef.getComponentType());
                    if (pr != null) {
                        pr.sendMessage(Texts.t("airship.despawn.removedByAdmin"));
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager du dirigeable %s non posé au sol", r.p.name);
            }
        }
        f.riders.clear();
    }

    /** A rider who disconnects: released, their saved position set on the ground under their seat (else saved in the air). */
    void dropOne(Store<EntityStore> store, AirshipFlight f, Rider r) {
        f.riders.remove(r);
        if (!r.p.ref.isValid()) {
            return;
        }
        release(store, r);
        TransformComponent t = store.getComponent(r.p.ref, TransformComponent.getComponentType());
        if (t != null) {
            t.setPosition(BalloonManager.groundBelow(f.world, manager.shipPoint(f, r.p.seatLocal)));
        }
    }

    /** A rider who died: released without being moved (the respawn moves them). */
    void releaseDead(Store<EntityStore> store, AirshipFlight f, Rider r) {
        release(store, r);
        f.riders.remove(r);
    }

    /** Sends a message to every rider. */
    static void tell(Store<EntityStore> store, AirshipFlight f, String key) {
        for (Rider r : f.riders) {
            PlayerRef pr = r.p.ref.isValid() ? store.getComponent(r.p.ref, PlayerRef.getComponentType()) : null;
            if (pr != null) {
                pr.sendMessage(Texts.t(key));
            }
        }
    }

    // ------------------------------------------------------------------ resume file

    /** Riders for the resume file: uuid and stool cell (prefab frame). */
    static BsonArray toBson(AirshipFlight f) {
        BsonArray a = new BsonArray();
        for (Rider r : f.riders) {
            BsonDocument d = new BsonDocument();
            d.put("uuid", new BsonString(r.p.uuid.toString()));
            d.put("x", new BsonInt32(r.p.seatBlock.x));
            d.put("y", new BsonInt32(r.p.seatBlock.y));
            d.put("z", new BsonInt32(r.p.seatBlock.z));
            a.add(d);
        }
        return a;
    }

    record Saved(UUID uuid, int x, int y, int z) {
    }

    static List<Saved> fromBson(BsonArray a) {
        List<Saved> list = new ArrayList<>();
        for (BsonValue v : a) {
            BsonDocument d = v.asDocument();
            list.add(new Saved(UUID.fromString(d.getString("uuid").getValue()), d.getNumber("x").intValue(),
                    d.getNumber("y").intValue(), d.getNumber("z").intValue()));
        }
        return list;
    }

    /**
     * Resume after a stop in flight: each saved rider stands next to their stool of the placed prefab. Connected, they are
     * teleported (sitting flag cleared), offline, the spot is noted and applied on their next arrival (same "stranded" files as
     * the balloon, BalloonManager.onPlayerAdded).
     */
    static void restoreSaved(World world, Store<EntityStore> store, BalloonShape s, List<Saved> saved, Vector3i origin, Rotation q) {
        Vector3d logical = new Vector3d(origin.x + 0.5, origin.y, origin.z + 0.5);
        for (Saved sv : saved) {
            try {
                BalloonShape.Cell c = s.cellAt(sv.x(), sv.y(), sv.z());
                Vector3f local = c != null ? exitLocal(s, c) : new Vector3f(sv.x(), sv.y() + 0.6f, sv.z());
                Vector3d spot = BalloonManager.prefabPoint(logical, q, local);
                Ref<EntityStore> online = null;
                for (PlayerRef pr : world.getPlayerRefs()) {
                    if (pr.getUuid().equals(sv.uuid()) && pr.getReference() != null && pr.getReference().isValid()) {
                        online = pr.getReference();
                    }
                }
                if (online != null) {
                    MountUtil.unseat(store, online);
                    BalloonManager.clearSittingFlag(store, online);
                    MovementManager mm = store.getComponent(online, MovementManager.getComponentType());
                    if (mm != null) {
                        mm.resetDefaultsAndUpdate(online, store);
                    }
                    teleport(store, online, spot);
                } else {
                    BalloonResume.writeStranded(sv.uuid(), world.getName(), spot.x, spot.y, spot.z);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Passager %s de la reprise du dirigeable non reposé", sv.uuid());
            }
        }
    }

    // ------------------------------------------------------------------ diagnostics and tuning

    String describe(Store<EntityStore> store, AirshipFlight f) {
        if (f.riders.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Rider r : f.riders) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(r.p.name).append(" stool ").append(r.p.seatBlock.x).append(',').append(r.p.seatBlock.y).append(',')
                    .append(r.p.seatBlock.z).append(' ').append(r.mode);
            TransformComponent t = r.p.ref.isValid() ? store.getComponent(r.p.ref, TransformComponent.getComponentType()) : null;
            if (t != null) {
                sb.append(String.format(Locale.ROOT, " %.2f b from seat", t.getPosition().distance(manager.shipPoint(f, r.p.seatLocal))));
            }
            sb.append(" teleports ").append(r.teleports);
            if (r.mode == Mode.MOUNT) {
                sb.append(" losses ").append(r.losses);
            }
        }
        return sb.toString();
    }

    String tuning() {
        return String.format(Locale.ROOT, "passengers %s, passengerGain %.2f, passengerMaxCorrection %.2f, passengerSnap %.2f",
                mode.name().toLowerCase(Locale.ROOT), gain, maxCorrection, snap);
    }
}
