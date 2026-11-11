package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Recall of a parked large balloon to a balloon station. The owner's balloon is taken off where it is parked, appears 120 blocks
 * from the station and flies in without fuel (BalloonVoyage, arrival QUAY), then is placed in blocks on the quay. If the quay is
 * obstructed it lands on the ground outside the station zone instead.
 */
final class BalloonRecall {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Sections loaded around the spawn point before the balloon is put there. */
    private static final int LOAD_RADIUS = 20;

    /** Balloons waiting on a quay (WAIT, CHOOSE). In memory: the registry mark keeps what a restart needs. */
    private static final Set<RecallState> PARKED = ConcurrentHashMap.newKeySet();

    private BalloonRecall() {
    }

    /** Pure: refusal key of the recall take-off, null if it may leave. The fuel does not matter, only the burner block must exist. */
    static String launchRefusalKey(boolean burnerPresent) {
        return burnerPresent ? null : "error.noBurner";
    }

    /** True if a flight of this vehicle has a recall in progress, or if its entry carries a recall mark (it waits on a quay). */
    private static boolean recalling(UUID id, BalloonRegistry.Entry entry) {
        if (entry != null && entry.recall() != null) {
            return true;
        }
        for (BalloonFlight f : BalloonManager.get().flights()) {
            if (f.recall != null && id.equals(f.ownership.id())) {
                return true;
            }
        }
        return false;
    }

    /** True if a flight of this vehicle has no recall (a voyage or a piloted flight). */
    private static boolean flyingOther(UUID id) {
        for (BalloonFlight f : BalloonManager.get().flights()) {
            if (f.recall == null && id.equals(f.ownership.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks the pilot of this large balloon (structure id {@code id}, its registry entry, null when it is in the air) to come to the
     * station. Null if the recall started (the balloon appears once the sections are loaded, on the world thread), otherwise the
     * refusal of the station master. Never throws.
     */
    static Message start(Store<EntityStore> store, World world, PlayerRef owner, TransitNetwork.Station s, UUID id,
                         BalloonRegistry.Entry entry) {
        String name = VehicleRecall.nameOf(id, Deployables.BALLOON_KIND, owner);
        UUID quayId = null;
        try {
            Deployables.Kind kind = entry != null ? Deployables.get(entry.kind()) : null;
            boolean recalling = recalling(id, entry);
            boolean flying = flyingOther(id);
            boolean occupied = kind != null && kind.balloon != null && !recalling
                    && !BalloonManager.get().nacelleOccupants(world, store, kind, new Vector3i(entry.x(), entry.y(), entry.z()),
                    entry.rotation()).isEmpty();
            boolean known = entry != null && entry.sky() != null
                    && entry.sky().hasReached(new SkyLog.Reached(s.origin.x, s.origin.y, s.origin.z));
            String key = RecallPlanner.recallRefusalKey(entry != null || recalling || flying, flying, recalling, known || recalling,
                    occupied, true);
            if (key == null && (s.quay == null || kind == null || kind.balloon == null)) {
                key = "recall.noVehicle";
            }
            quayId = UUID.randomUUID();
            if (key == null && !StationQueue.beginRecall(s, quayId)) {
                key = "recall.quayBusy";
            }
            if (key != null) {
                return Texts.t("crew." + key).param("vehicle", name);
            }
            try {
                begin(world, owner, s, entry, kind, quayId, name);
            } catch (RuntimeException e) {
                StationQueue.endRecall(s, quayId);
                LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: start failed");
                Telemetry.error("recall", e, "balloon", "recall");
                return Texts.t("crew.recall.noVehicle");
            }
            return null;
        } catch (RuntimeException e) {
            if (quayId != null) {
                StationQueue.endRecall(s, quayId);
            }
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: request failed");
            Telemetry.error("recall", e, "balloon", "recall");
            return Texts.t("crew.recall.noVehicle");
        }
    }

    /** Loads the sections of the parked balloon and of the spawn area, then launches it on the world thread. */
    private static void begin(World world, PlayerRef owner, TransitNetwork.Station s, BalloonRegistry.Entry entry, Deployables.Kind kind,
                              UUID quayId, String name) {
        StructureShape shape = kind.shapeOrNull();
        if (shape == null) {
            throw new IllegalStateException("prefab unreadable: " + kind.prefabPath);
        }
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        int[] b = shape.bounds();
        Vector3i c1 = entry.rotation().rotateYaw(new Vector3i(b[0], b[1], b[2]), new Vector3i()).add(origin);
        Vector3i c2 = entry.rotation().rotateYaw(new Vector3i(b[3], b[4], b[5]), new Vector3i()).add(origin);
        Vector3d pad = StationGeometry.markerPoint(s);
        double[] sp = RecallPlanner.spawnPoint(pad.x, pad.z, origin.x + 0.5, origin.z + 0.5);
        int cruise = cruiseY(shape, s);
        int top = flightTop(shape);
        int sx = (int) Math.floor(sp[0]);
        int sz = (int) Math.floor(sp[1]);
        CompletableFuture<Void> parkedLoad = SectionBlocks.loadSections(world.getChunkStore(), Math.min(c1.x, c2.x), Math.min(c1.y, c2.y),
                Math.min(c1.z, c2.z), Math.max(c1.x, c2.x), Math.max(c1.y, c2.y), Math.max(c1.z, c2.z));
        CompletableFuture<Void> spawnLoad = SectionBlocks.loadSections(world.getChunkStore(), sx - LOAD_RADIUS, cruise - 12, sz - LOAD_RADIUS,
                sx + LOAD_RADIUS, cruise + top + 2, sz + LOAD_RADIUS);
        CompletableFuture.allOf(parkedLoad, spawnLoad).whenComplete((v, t) -> {
            try {
                world.execute(() -> {
                    if (t != null) {
                        // Never detach a structure that is not in memory: the recall ends cleanly, the balloon stays where it is.
                        LOGGER.at(Level.WARNING).withCause(t).log("Balloon recall: loading the sections failed, recall cancelled");
                        Telemetry.error("recall", t, "balloon", "recall");
                        StationQueue.endRecall(s, quayId);
                        owner.sendMessage(Texts.t("crew.recall.unreachable").param("vehicle", name));
                        return;
                    }
                    launch(world, owner, s, entry, quayId, sp[0], sp[1], cruise, name);
                });
            } catch (Throwable e) {
                // Whatever fails (a stopped world included), the quay must not stay in RECALL.
                StationQueue.endRecall(s, quayId);
                LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: world stopped, recall cancelled");
                Telemetry.error("recall", e, "balloon", "recall");
            }
        });
    }

    /** Cruise height of the recall flight: the voyage rule, from the docking height to the quay height. */
    private static int cruiseY(StructureShape shape, TransitNetwork.Station s) {
        Vector3i dock = StationGeometry.bigBalloonDockOrigin(s.quay, s.quayRotation);
        return BalloonVoyage.cruiseY(dock.y, StationQueue.padY(s), BalloonManager.WORLD_MAX_Y, flightTop(shape));
    }

    private static int flightTop(StructureShape shape) {
        int top = Integer.MIN_VALUE;
        for (StructureShape.Cell c : shape.flightCells()) {
            top = Math.max(top, c.y());
        }
        return top;
    }

    /** On the world thread: the balloon leaves its parking place and appears in flight. */
    private static void launch(World world, PlayerRef owner, TransitNetwork.Station s, BalloonRegistry.Entry entry, UUID quayId,
                               double spawnX, double spawnZ, int cruise, String name) {
        Message failure = null;
        try {
            if (!TransitNetwork.get().stations(world.getName()).contains(s)) {
                StationQueue.endRecall(s, quayId);
                owner.sendMessage(Texts.t("crew.recall.unreachable").param("vehicle", name));
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            BalloonManager.RecallLaunch made = BalloonManager.get().launchRecall(store, world, entry, s, quayId, spawnX, spawnZ, cruise, name);
            failure = made.error();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: spawn failed");
            Telemetry.error("recall", e, "balloon", "recall");
            failure = Texts.t("crew.recall.noVehicle");
        }
        if (failure != null) {
            StationQueue.endRecall(s, quayId);
            owner.sendMessage(failure);
            return;
        }
        Message sent = Texts.t("crew.recall.sent");
        owner.sendMessage(sent);
        StationQueue.broadcast(world, s, sent);
    }

    /** The owner of the recall if he is in this world, else null. */
    static PlayerRef ownerOnline(World world, UUID owner) {
        for (PlayerRef pr : world.getPlayerRefs()) {
            if (pr.getUuid().equals(owner)) {
                return pr;
            }
        }
        return null;
    }

    /**
     * The balloon is placed in blocks on the quay (called right after BalloonManager.finishFlight): the recall mark goes on its registry
     * entry, the wait starts and the pilot greets the owner. Never throws.
     */
    static void onDocked(Store<EntityStore> store, World world, BalloonFlight f) {
        RecallState r = f.recall;
        if (r == null) {
            return;
        }
        try {
            Vector3i origin = StationGeometry.bigBalloonDockOrigin(r.station.quay, r.station.quayRotation);
            Rotation rotation = f.rotation;
            r.origin = origin;
            r.rotation = rotation;
            r.crew = f.crew;
            BalloonRegistry.setRecall(world, origin, rotation, Deployables.BALLOON_KIND, new BalloonRegistry.Recall(r.quayId,
                    r.station.origin.x, r.station.origin.y, r.station.origin.z, r.station.rotation, r.spawnX, r.spawnZ, new int[0]));
            r.phase = RecallState.Phase.WAIT;
            r.waitStartMs = System.currentTimeMillis();
            r.announced = Integer.MAX_VALUE;
            PARKED.add(r);
            PlayerRef owner = ownerOnline(world, r.owner);
            if (owner != null) {
                owner.sendMessage(Texts.pilot("crew.recall.here", f.crew));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: docking bookkeeping failed");
            if (!PARKED.contains(r)) {
                endQuay(r);
            }
            Telemetry.error("recall", e, "balloon", "recall");
        }
    }

    /**
     * The descent onto the quay is blocked: nothing is placed there. The flight climbs back to the cruise height and lands on a free
     * column 70 blocks from the pad on the side it came from (an ordinary voyage landing), the quay is freed and the owner told.
     * Never throws.
     */
    static void obstructed(Store<EntityStore> store, World world, BalloonFlight f, BalloonVoyage v, long now) {
        RecallState r = f.recall;
        if (r == null) {
            return;
        }
        f.recall = null;
        v.arrival = BalloonVoyage.Arrival.GROUND;
        StationQueue.endRecall(r.station, r.quayId);
        try {
            double[] p = AirshipRecall.retreatPoint(r, world);
            v.destX = p[0];
            v.destZ = p[1];
            v.fromX = r.spawnX;
            v.fromZ = r.spawnZ;
            v.landing = null;
            v.phase = BalloonVoyage.Phase.CLIMB;
            v.motion.lastProgressMs = now;
            PlayerRef owner = ownerOnline(world, r.owner);
            if (owner != null) {
                owner.sendMessage(Texts.t("crew.recall.obstructed").param("vehicle",
                        VehicleRecall.nameOf(f.ownership.id(), Deployables.BALLOON_KIND, owner)));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: obstruction not handled");
            Telemetry.error("recall", e, "balloon", "recall");
        }
    }

    // ------------------------------------------------------------------ waiting on the quay

    /** Every tick of a world: the minute, the captain's window and the checks of the balloons waiting on a quay. Never throws. */
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
                LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: error while waiting at %s", r.station.name);
                Telemetry.error("recall", e, "balloon", ph(r));
            }
        }
    }

    private static void tickWait(Store<EntityStore> store, World world, RecallState r, long now) {
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, r.origin, r.rotation, Deployables.BALLOON_KIND);
        if (entry == null) {
            finishWait(world, r, false);
            return;
        }
        if (!TransitNetwork.get().stations(world.getName()).contains(r.station)) {
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
        if (r.phase == RecallState.Phase.WAIT) {
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
            if (captainRef(store, world, r) == null) {
                leaveNow(store, world, r, entry); // first: closing the page runs onDismiss, which must find the balloon gone or refused
                closePage(r);
            } else if (now - r.chooseStartMs >= RecallPlanner.CHOOSE_MS) {
                leaveNow(store, world, r, entry);
                closePage(r);
            }
        }
    }

    /** The wait is over without a take-off: no mark, the quay freed. The balloon stays in place. */
    private static void finishWait(World world, RecallState r, boolean balloonStays) {
        PARKED.remove(r);
        closePage(r);
        if (balloonStays && r.origin != null) {
            BalloonRegistry.setRecall(world, r.origin, r.rotation, Deployables.BALLOON_KIND, null);
        }
        endQuay(r);
    }

    /** The recall no longer holds the quay (idempotent): the station goes back to FREE with the usual gap. */
    static void endQuay(RecallState r) {
        if (!r.quayFreed) {
            r.quayFreed = true;
            StationQueue.endRecall(r.station, r.quayId);
        }
    }

    /** The owner if he stands in the gondola of the waiting balloon, null otherwise. */
    private static PlayerRef captainRef(Store<EntityStore> store, World world, RecallState r) {
        try {
            Deployables.Kind kind = Deployables.BALLOON;
            for (BalloonManager.Occupant o : BalloonManager.get().nacelleOccupants(world, store, kind, r.origin, r.rotation)) {
                if (o.player().getUuid().equals(r.owner)) {
                    return o.player();
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: captain presence not checked");
            Telemetry.error("recall", e, "balloon", ph(r));
        }
        return null;
    }

    /** End of the minute: the captain's window if he is aboard and the pilot at the helm, otherwise the balloon leaves. */
    private static void endWait(Store<EntityStore> store, World world, RecallState r, BalloonRegistry.Entry entry, long now) {
        PlayerRef pr = captainRef(store, world, r);
        if (pr == null || entry.crew() == null) {
            leaveNow(store, world, r, entry);
            return;
        }
        if (AirshipCrew.state(world, entry) != AirshipCrew.CrewState.AT_HELM) {
            leaveNow(store, world, r, entry);
            return;
        }
        r.phase = RecallState.Phase.CHOOSE;
        r.chooseStartMs = now;
        try {
            Ref<EntityStore> ref = pr.getReference();
            Player player = ref != null ? store.getComponent(ref, Player.getComponentType()) : null;
            if (player == null) {
                leaveNow(store, world, r, entry);
                return;
            }
            AirshipVoyagePage page = new AirshipVoyagePage(pr, entry.crew().uuid(), r);
            r.page = page;
            player.getPageManager().openCustomPage(ref, store, page);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: destinations window failed, the balloon leaves");
            Telemetry.error("recall", e, "balloon", ph(r));
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
     * The captain closed the window without choosing (called by the page). Idempotent: nothing once the balloon left, and nothing for a
     * page the server closed itself (closePage forgets the page before closing it).
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
            BalloonRegistry.Entry entry = BalloonRegistry.get(world, r.origin, r.rotation, Deployables.BALLOON_KIND);
            if (entry != null) {
                leaveNow(store, world, r, entry);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: departure failed");
            Telemetry.error("recall", e, "balloon", ph(r));
        }
    }

    /**
     * Fuel-free departure with the players of the gondola, to the leave point 70 blocks from the pad. A departure that is refused is
     * tried again every 5 s, at most MAX_DEPART_TRIES times, then the balloon stays on its quay and the quay is freed.
     */
    static void leaveNow(Store<EntityStore> store, World world, RecallState r, BalloonRegistry.Entry entry) {
        double[] p = AirshipRecall.retreatPoint(r, world);
        Message refusal;
        try {
            refusal = BalloonManager.get().launchLeave(store, world, entry, r, p[0], p[1]);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: departure threw, treated as a refusal");
            Telemetry.error("recall", e, "balloon", ph(r));
            refusal = Message.raw("");
        }
        if (refusal == null) {
            StationQueue.broadcast(world, r.station, Texts.t("crew.recall.leaving"));
            return;
        }
        if (!PARKED.contains(r)) {
            return;
        }
        if (!RecallPlanner.departRetryAllowed(++r.departTries)) {
            LOGGER.at(Level.WARNING).log("Balloon recall: departure refused %d times at %s, the balloon stays on its quay and the quay is freed",
                    r.departTries, r.station.name);
            finishWait(world, r, true);
            return;
        }
        r.leaveRetryMs = System.currentTimeMillis() + 5000;
    }

    /**
     * The balloon that waited on the quay is now flying (called from the take-off hook of the flight, world thread): the wait is over,
     * the flight keeps the recall in phase LEAVE and with it the quay. The mark went with the registry entry.
     */
    static void onTakeoff(BalloonFlight f, RecallState r) {
        r.phase = RecallState.Phase.LEAVE;
        r.leaveRetryMs = 0;
        PARKED.remove(r);
        closePage(r);
        f.recall = r;
    }

    /** True once the balloon is outside the protected cylinder of the station. */
    static boolean outsideZone(BalloonFlight f, TransitNetwork.Station s) {
        StationZone.Zone z = StationZone.zoneOf(s);
        return Math.hypot(f.entityPos.x - z.cx(), f.entityPos.z - z.cz()) > TransitConstants.STATION_ZONE_RADIUS || f.entityPos.y > z.topY();
    }

    /** Every tick of a departing recall flight: frees the quay once the balloon is out of the zone. Never throws. */
    static void releaseQuayIfLeft(BalloonFlight f) {
        RecallState r = f.recall;
        if (r == null || r.phase != RecallState.Phase.LEAVE || r.quayFreed) {
            return;
        }
        try {
            if (outsideZone(f, r.station)) {
                endQuay(r);
                if (f.voyage != null && !f.voyage.fuelFree) {
                    f.recall = null; // the captain's ordinary voyage goes on without the recall
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: zone exit not checked");
            Telemetry.error("recall", e, "balloon", ph(r));
        }
    }

    /** A departing recall flight is put down (any path of finishFlight): the quay is freed if still held and the recall forgotten. */
    static void onLeftFlightEnd(BalloonFlight f) {
        RecallState r = f.recall;
        if (r == null || r.phase != RecallState.Phase.LEAVE) {
            return;
        }
        f.recall = null;
        endQuay(r);
    }

    /** A flight that carries a recall ends by any path (removal by an administrator, transit end): the quay is freed and the recall forgotten. */
    static void onFlightEnded(BalloonFlight f) {
        RecallState r = f.recall;
        if (r == null) {
            return;
        }
        f.recall = null;
        try {
            endQuay(r);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: quay not freed at the end of the flight");
            Telemetry.error("recall", e, "balloon", ph(r));
        }
    }

    /**
     * A departing flight made no progress for 30 s away from the quay: it is put down where it is (the voyage stops and descends) rather
     * than holding the quay and its passengers for ever. True if handled (the recall LEAVE flight, first stop only). Never throws.
     */
    static boolean leaveStuck(BalloonFlight f, BalloonVoyage v) {
        RecallState r = f.recall;
        if (r == null || r.phase != RecallState.Phase.LEAVE || r.quayFreed || v.stopped || !v.fuelFree) {
            return false;
        }
        try {
            if (!outsideZone(f, r.station) && r.leaveTries < RecallState.MAX_LEAVE_TRIES) {
                // Still inside the zone: never put down on the pad, one more try toward the leave point (bounded).
                r.leaveTries++;
                double[] p = AirshipRecall.retreatPoint(r, f.world);
                v.destX = p[0];
                v.destZ = p[1];
                v.landing = null;
                v.phase = BalloonVoyage.Phase.CLIMB;
                v.motion.lastProgressMs = System.currentTimeMillis();
                return true;
            }
            LOGGER.at(Level.WARNING).log("Balloon recall: moving away from %s made no progress, forced landing in place at (%.0f, %.0f, %.0f)", r.station.name,
                    f.entityPos.x, f.entityPos.y, f.entityPos.z);
            v.stop();
            return true;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: forced landing failed");
            Telemetry.error("recall", e, "balloon", ph(r));
            return false;
        }
    }

    // ------------------------------------------------------------------ station removed

    /**
     * A station is removed (the network no longer contains it): a balloon of this world that flies a recall to it, or away from it,
     * is put down in place (its quay went with the station), the pilot back at the helm. A balloon waiting on its quay stays there,
     * its mark removed. Never throws.
     */
    static void onStationRemoved(World world, TransitNetwork.Station removed) {
        try {
            List<BalloonFlight> flying = new ArrayList<>();
            BalloonManager.get().flights().forEach(flying::add);
            for (BalloonFlight f : flying) {
                RecallState r = f.recall;
                if (f.world != world || r == null || r.station != removed || f.stopping) {
                    continue;
                }
                try {
                    f.recall = null;
                    r.quayFreed = true; // no endRecall: the quay of the removed station was dropped with it
                    BalloonVoyage v = f.voyage;
                    if (v != null && v.fuelFree) {
                        v.arrival = BalloonVoyage.Arrival.GROUND;
                        v.stop();
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: balloon not landed after the station was removed");
                    Telemetry.error("recall", e, "balloon", ph(r));
                }
            }
            for (RecallState r : new ArrayList<>(PARKED)) {
                if (r.station == removed) {
                    finishWait(world, r, true);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: removal of station %s not handled", removed.name);
            Telemetry.error("recall", e, "balloon", null);
        }
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Server shutdown while a balloon flies a recall: it is moved to its leave point (70 blocks from the pad), never put down above the
     * station. If the sections there are not loaded and the balloon is inside the zone, nothing is placed (false) and the resume file
     * (key recall) puts it down at the leave point at the next load. True if the balloon may be landed now. Never throws.
     */
    static boolean placeForShutdown(BalloonFlight f) {
        RecallState r = f.recall;
        if (r == null) {
            return true;
        }
        try {
            StructureShape shape = f.kind.shapeOrNull();
            double[] p = AirshipRecall.retreatPoint(r, f.world);
            int px = (int) Math.floor(p[0]);
            int pz = (int) Math.floor(p[1]);
            int y = f.lastCheckedOrigin.y;
            if (shape != null && SectionBlocks.sectionsLoaded(f.world.getChunkStore(), px - 16, y - 12, pz - 16, px + 16,
                    y + flightTop(shape) + 10 + LIFT_MAX, pz + 16)) {
                Vector3i cell = new Vector3i(px, y, pz);
                for (int tries = 0; tries < LIFT_MAX / 8 && BalloonManager.collision(f.world, shape, cell, f.rotation, false) != null
                        && cell.y + 8 < BalloonManager.WORLD_MAX_Y; tries++) {
                    cell.y += 8;
                }
                f.entityPos.set(px + 0.5, cell.y, pz + 0.5);
                f.lastCheckedOrigin.set(cell);
            } else if (!outsideZone(f, r.station)) {
                LOGGER.at(Level.WARNING).log("Balloon recall: leave point not loaded, balloon of %s not landed (resume file kept)", f.pilotUuid);
                return false;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: leave point not computed");
            Telemetry.error("recall", e, "balloon", ph(r));
        }
        return true;
    }

    // ------------------------------------------------------------------ restart

    /** Highest lift (blocks, steps of 8) of a shutdown or resume pose at the leave point: the sections up to it are checked or loaded. */
    static final int LIFT_MAX = 24;

    private static final Set<UUID> RECOVERING = ConcurrentHashMap.newKeySet();

    /**
     * World load: a balloon still on a quay with a recall mark in the registry leaves at once with whoever is aboard, as when the minute
     * ends (the quay is taken back when possible). A mark whose station is gone is removed. Idempotent, never throws.
     */
    static void recoverWorld(World world) {
        try {
            for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.BALLOON_KIND)) {
                if (e.recall() == null || !RECOVERING.add(e.id())) {
                    continue;
                }
                try {
                    StructureShape shape = Deployables.BALLOON.shapeOrNull();
                    if (shape == null) {
                        throw new IllegalStateException("prefab unreadable: " + Deployables.BALLOON.prefabPath);
                    }
                    Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
                    int[] b = shape.bounds();
                    Vector3i c1 = e.rotation().rotateYaw(new Vector3i(b[0], b[1], b[2]), new Vector3i()).add(origin);
                    Vector3i c2 = e.rotation().rotateYaw(new Vector3i(b[3], b[4], b[5]), new Vector3i()).add(origin);
                    SectionBlocks.loadSections(world.getChunkStore(), Math.min(c1.x, c2.x), Math.min(c1.y, c2.y), Math.min(c1.z, c2.z),
                            Math.max(c1.x, c2.x), Math.max(c1.y, c2.y), Math.max(c1.z, c2.z)).whenComplete((v, t) -> {
                        try {
                            world.execute(() -> recoverParked(world, e));
                        } catch (RuntimeException ex) {
                            RECOVERING.remove(e.id());
                            LOGGER.at(Level.WARNING).withCause(ex).log("Balloon recall: world stopped, resume postponed to the next load");
                        }
                    });
                } catch (RuntimeException ex) {
                    RECOVERING.remove(e.id());
                    LOGGER.at(Level.WARNING).withCause(ex).log("Balloon recall: resuming the waiting balloon failed");
                    Telemetry.error("recall", ex, "balloon", null);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: resuming the waiting balloons failed");
            Telemetry.error("recall", e, "balloon", null);
        }
    }

    private static void recoverParked(World world, BalloonRegistry.Entry old) {
        try {
            Vector3i origin = new Vector3i(old.x(), old.y(), old.z());
            BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, old.rotation(), Deployables.BALLOON_KIND);
            BalloonRegistry.Recall mark = entry != null ? entry.recall() : null;
            if (mark == null) {
                return;
            }
            for (RecallState parked : PARKED) {
                if (origin.equals(parked.origin) && entry.rotation() == parked.rotation) {
                    return; // already tracked (the recall of this session)
                }
            }
            TransitNetwork.Station station = null;
            for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName(), Deployables.STATION_KIND)) {
                if (s.origin.x == mark.stationX() && s.origin.y == mark.stationY() && s.origin.z == mark.stationZ()) {
                    station = s;
                }
            }
            if (station == null) {
                BalloonRegistry.setRecall(world, origin, entry.rotation(), Deployables.BALLOON_KIND, null);
                return;
            }
            Vector3d pad = StationGeometry.markerPoint(station);
            RecallState r = new RecallState(station, entry.owner(), Deployables.BALLOON_KIND, entry.ownership().id(), mark.spawnX(),
                    mark.spawnZ(), pad.x, pad.z, 0, 0);
            r.origin = origin;
            r.rotation = entry.rotation();
            r.quayId = mark.quayId();
            r.crew = entry.crew();
            Deployables.Kind kind = Deployables.BALLOON;
            Rotation rot = r.rotation;
            VehicleRecall.strandOfflinePlayers(world, station, p -> BalloonManager.inNacelle(kind, origin, rot, p), "recall", "balloon",
                    ph(r));
            PARKED.add(r);
            StationQueue.beginRecall(station, r.quayId); // best effort: the quay is held while the balloon leaves
            leaveNow(world.getEntityStore().getStore(), world, r, entry);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Balloon recall: resuming the waiting balloon failed");
            Telemetry.error("recall", e, "balloon", null);
        } finally {
            RECOVERING.remove(old.id());
        }
    }

    private static String ph(RecallState r) {
        return r.phase.name().toLowerCase(java.util.Locale.ROOT);
    }
}
