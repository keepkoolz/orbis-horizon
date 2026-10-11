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
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Destination queue and quay state machine of the transit stations. One Quay per station (identity keyed, kept in memory only: the
 * queue itself is saved with the station in stations.json).
 *
 * Cycle: FREE, then (queue not empty) APPROACH while the balloon descends onto the pad, DOCKED while it rests in blocks and the 60 s
 * countdown runs, DEPARTING while it climbs away, back to FREE once it is 20 blocks above the pad. ARRIVING is set by the automatic
 * flight (Task 8). The launches go through world.execute: the store is not written while the tick runs.
 */
final class StationQueue {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    enum QuayState { FREE, APPROACH, DOCKED, DEPARTING, ARRIVING }

    /** UNKNOWN: the destination no longer exists in the network (removed between the window opening and the click). */
    enum EnqueueResult { ADDED, ALREADY_QUEUED, FULL, SELF, UNKNOWN }

    private static final class Quay {
        volatile QuayState state = QuayState.FREE;
        /** Flight of the approaching or departing balloon. */
        volatile BalloonFlight flight;
        volatile long deadlineMs;
        /** Next countdown message threshold in seconds (-1: none left). */
        volatile int nextThreshold = -1;
        /** A launch is scheduled on the world thread. */
        volatile boolean busy;
        volatile long retryAtMs;
        /** Until then nothing comes down on the quay: gap after a departure (QUAY_GAP_SECONDS). */
        volatile long gapUntilMs;
        /** Arriving transit balloons waiting for the quay, in order of arrival (guarded by the quay monitor). */
        final List<UUID> waiting = new ArrayList<>();
        /** The arriving balloon that holds the quay (state ARRIVING), null otherwise. */
        UUID arriving;
    }

    private static final Map<TransitNetwork.Station, Quay> QUAYS = new ConcurrentHashMap<>();

    private StationQueue() {
    }

    private static Quay quay(TransitNetwork.Station s) {
        return QUAYS.computeIfAbsent(s, k -> new Quay());
    }

    // ------------------------------------------------------------------ queue rules (pure)

    /**
     * Queue rules on a plain list: the station itself is refused (SELF), a destination already queued is not added again
     * (ALREADY_QUEUED), the queue holds TransitConstants.MAX_QUEUE destinations at most (FULL). Names compare ignoring case and
     * surrounding spaces. Not synchronized: callers go through TransitNetwork.queueEnqueue.
     */
    static EnqueueResult addTo(List<String> queue, String selfName, String destination) {
        String d = TransitNetwork.normalize(destination);
        if (d.equalsIgnoreCase(TransitNetwork.normalize(selfName))) {
            return EnqueueResult.SELF;
        }
        if (positionOf(queue, d) > 0) {
            return EnqueueResult.ALREADY_QUEUED;
        }
        if (queue.size() >= TransitConstants.MAX_QUEUE) {
            return EnqueueResult.FULL;
        }
        queue.add(d);
        return EnqueueResult.ADDED;
    }

    /** 1-based position of the destination in the queue, 0 if absent. */
    static int positionOf(List<String> queue, String destination) {
        String d = TransitNetwork.normalize(destination);
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).equalsIgnoreCase(d)) {
                return i + 1;
            }
        }
        return 0;
    }

    static int positionOf(TransitNetwork.Station s, String destination) {
        synchronized (TransitNetwork.get()) {
            return positionOf(s.queue, destination);
        }
    }

    // ------------------------------------------------------------------ public interface

    /**
     * Adds a destination to the station's queue. ADDED is announced to the players near the station, the chooser is told about the
     * other results. An unknown destination is refused like the station itself (SELF): the pages only offer existing stations.
     */
    static EnqueueResult enqueue(World world, TransitNetwork.Station s, String destination, PlayerRef chooser) {
        TransitNetwork net = TransitNetwork.get();
        TransitNetwork.Station target = net.byName(world.getName(), destination);
        if (target == null) {
            LOGGER.at(Level.INFO).log("File de %s : destination inconnue « %s »", s.name, destination);
            if (chooser != null) chooser.sendMessage(Texts.t("transit.network.removed").param("name", TransitNetwork.normalize(destination)));
            return EnqueueResult.UNKNOWN;
        }
        EnqueueResult r = net.queueEnqueue(s, target.name);
        int position = positionOf(s, target.name);
        switch (r) {
            case ADDED -> broadcast(world, s, Texts.t("transit.queue.added").param("name", target.name).param("position", position));
            case ALREADY_QUEUED -> {
                if (chooser != null) chooser.sendMessage(Texts.t("transit.queue.already").param("position", position));
            }
            case FULL -> {
                if (chooser != null) chooser.sendMessage(Texts.t("transit.queue.full"));
            }
            case SELF, UNKNOWN -> {
            }
        }
        return r;
    }

    static QuayState state(TransitNetwork.Station s) {
        Quay q = QUAYS.get(s);
        return q == null ? QuayState.FREE : q.state;
    }

    /** Seconds left before departure while DOCKED, 0 otherwise. */
    static int secondsLeft(TransitNetwork.Station s) {
        Quay q = QUAYS.get(s);
        if (q == null || q.state != QuayState.DOCKED) {
            return 0;
        }
        return (int) Math.max(0, Math.ceil((q.deadlineMs - System.currentTimeMillis()) / 1000.0));
    }

    /** World cell of the origin of the transit balloon resting at the dock of this station (same conversion as DeployStationInteraction). */
    static Vector3i dockOrigin(TransitNetwork.Station s) {
        return s.rotation.rotateYaw(new Vector3i(Deployables.STATION_DOCK_ORIGIN[0], Deployables.STATION_DOCK_ORIGIN[1],
                Deployables.STATION_DOCK_ORIGIN[2]), new Vector3i()).add(s.origin);
    }

    /** Height of the top layer of the pad. */
    static int padY(TransitNetwork.Station s) {
        return s.origin.y + Deployables.STATION_PAD_MIN[1];
    }

    // ------------------------------------------------------------------ tick

    /** Called on every tick of every world. */
    static void tick(Store<EntityStore> store, World world) {
        List<TransitNetwork.Station> stations = TransitNetwork.get().stations(world.getName());
        if (stations.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TransitNetwork.Station s : stations) {
            Quay q = quay(s);
            switch (q.state) {
                case FREE -> tickFree(store, world, s, q, now);
                case APPROACH -> {
                    if (q.flight != null && !BalloonManager.get().active(q.flight)) {
                        LOGGER.at(Level.WARNING).log("Station %s : montgolfière d'approche perdue, quai libéré", s.name);
                        q.flight = null;
                        q.retryAtMs = now + 5000;
                        q.state = QuayState.FREE;
                    }
                }
                case DOCKED -> tickDocked(store, world, s, q, now);
                case DEPARTING -> tickDeparting(s, q);
                case ARRIVING -> {
                }
            }
        }
    }

    private static void tickFree(Store<EntityStore> store, World world, TransitNetwork.Station s, Quay q, long now) {
        // Arrivals have priority over the queue: no new approach while one is waiting for the quay.
        if (arrivalPending(s)) {
            return;
        }
        if (q.busy || now < q.retryAtMs || now < q.gapUntilMs || TransitNetwork.get().queueHead(s) == null) {
            return;
        }
        if (!dockLoaded(world, s)) {
            q.retryAtMs = now + 2000;
            return;
        }
        q.busy = true;
        world.execute(() -> {
            try {
                if (q.state != QuayState.FREE) {
                    return;
                }
                Message error = BalloonManager.get().launchTransitApproach(store, world, s);
                if (error != null) {
                    LOGGER.at(Level.WARNING).log("Station %s : montgolfière d'approche impossible (%s)", s.name, error.getMessageId());
                    q.retryAtMs = System.currentTimeMillis() + 5000;
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station %s : montgolfière d'approche en échec", s.name);
                q.retryAtMs = System.currentTimeMillis() + 5000;
            } finally {
                q.busy = false;
            }
        });
    }

    private static void tickDocked(Store<EntityStore> store, World world, TransitNetwork.Station s, Quay q, long now) {
        if (q.busy) {
            return;
        }
        if (BalloonRegistry.get(world, dockOrigin(s), s.rotation, Deployables.BALLOON_PUBLIC.id) == null) {
            LOGGER.at(Level.INFO).log("Station %s : montgolfière à quai disparue, quai libéré", s.name);
            q.state = QuayState.FREE;
            return;
        }
        long leftMs = q.deadlineMs - now;
        int seconds = (int) Math.ceil(leftMs / 1000.0);
        int announce = -1;
        while (q.nextThreshold > 0 && seconds <= q.nextThreshold) {
            announce = q.nextThreshold;
            q.nextThreshold -= TransitConstants.MESSAGE_INTERVAL_SECONDS;
        }
        if (announce > 0 && leftMs > 0) {
            String head = TransitNetwork.get().queueHead(s);
            if (head != null) {
                broadcast(world, s, Texts.t("transit.quay.countdown").param("name", head).param("seconds", announce));
            }
        }
        if (leftMs > 0) {
            return;
        }
        q.busy = true;
        world.execute(() -> {
            try {
                if (q.state != QuayState.DOCKED) {
                    return;
                }
                // Read now, not on the tick that scheduled this: the queue may have changed (station removed).
                String destination = TransitNetwork.get().queueHead(s);
                Message error = BalloonManager.get().launchTransitDeparture(store, world, s, destination);
                if (error != null) {
                    LOGGER.at(Level.WARNING).log("Station %s : départ impossible (%s), nouvel essai", s.name, error.getMessageId());
                    q.deadlineMs = System.currentTimeMillis() + 5000;
                } else if (destination != null) {
                    TransitNetwork.get().queueRemoveFirst(s, destination);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station %s : départ en échec", s.name);
                q.deadlineMs = System.currentTimeMillis() + 5000;
            } finally {
                q.busy = false;
            }
        });
    }

    private static void tickDeparting(TransitNetwork.Station s, Quay q) {
        BalloonFlight f = q.flight;
        if (f == null || !BalloonManager.get().active(f) || f.entityPos.y >= padY(s) + TransitConstants.DOCK_FREE_HEIGHT) {
            onQuayCleared(s);
        }
    }

    // ------------------------------------------------------------------ arrivals (automatic flight, Task 8)

    /** True while an arriving balloon holds the quay or waits for it. */
    static boolean arrivalPending(TransitNetwork.Station s) {
        Quay q = QUAYS.get(s);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            return q.arriving != null || !q.waiting.isEmpty();
        }
    }

    /** Adds the flight to the waiting arrivals (once) and returns its 0-based order. */
    static int registerArrival(TransitNetwork.Station s, UUID flight) {
        Quay q = quay(s);
        synchronized (q) {
            int i = q.waiting.indexOf(flight);
            if (i < 0) {
                q.waiting.add(flight);
                i = q.waiting.size() - 1;
            }
            return i;
        }
    }

    /** 0-based order of the flight among the waiting arrivals (the number of waiting ones if it is not registered). */
    static int holdOrder(TransitNetwork.Station s, UUID flight) {
        Quay q = QUAYS.get(s);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            return TransitAutopilot.holdIndexFor(q.waiting, flight);
        }
    }

    /**
     * The flight takes the quay (state ARRIVING) if it is FREE, no approach is being launched and no other arrival is ahead in the waiting
     * order. Idempotent for the flight that already holds it.
     */
    static boolean tryBeginArrival(TransitNetwork.Station s, UUID flight) {
        Quay q = quay(s);
        synchronized (q) {
            if (flight.equals(q.arriving)) {
                return true;
            }
            if (q.arriving != null || q.state != QuayState.FREE || q.busy || System.currentTimeMillis() < q.gapUntilMs) {
                return false;
            }
            if (!q.waiting.isEmpty() && !q.waiting.get(0).equals(flight)) {
                return false;
            }
            q.waiting.remove(flight);
            q.arriving = flight;
            q.state = QuayState.ARRIVING;
            return true;
        }
    }

    /** The flight no longer needs the quay (done, rescued or removed): frees it if it held it and forgets it in the waiting order. */
    static void endArrival(TransitNetwork.Station s, UUID flight) {
        Quay q = QUAYS.get(s);
        if (q == null) {
            return;
        }
        synchronized (q) {
            q.waiting.remove(flight);
            if (flight.equals(q.arriving)) {
                q.arriving = null;
                if (q.state == QuayState.ARRIVING) {
                    q.state = QuayState.FREE;
                }
            }
        }
    }

    // ------------------------------------------------------------------ callbacks

    /** The approaching balloon was created. */
    static void onApproach(TransitNetwork.Station s, BalloonFlight flight) {
        Quay q = quay(s);
        q.flight = flight;
        q.state = QuayState.APPROACH;
    }

    /**
     * The approaching balloon stopped above the dock (something was built over the pad): it was removed without being placed. The
     * destination stays at the head of the queue, the quay is free again and retried in 30 s, nearby players are told.
     */
    static void onApproachFailed(World world, TransitNetwork.Station s) {
        Quay q = quay(s);
        q.flight = null;
        q.retryAtMs = System.currentTimeMillis() + 30000;
        q.state = QuayState.FREE;
        broadcast(world, s, Texts.t("transit.quay.obstructed"));
    }

    /** The balloon rests in blocks on the pad (called by BalloonManager.finishFlight): the countdown starts. */
    static void onDocked(World world, TransitNetwork.Station s, Vector3i origin) {
        Quay q = quay(s);
        q.flight = null;
        startCountdown(world, s, q);
    }

    private static void startCountdown(World world, TransitNetwork.Station s, Quay q) {
        q.state = QuayState.DOCKED;
        q.deadlineMs = System.currentTimeMillis() + TransitConstants.COUNTDOWN_SECONDS * 1000L;
        q.nextThreshold = TransitConstants.COUNTDOWN_SECONDS - TransitConstants.MESSAGE_INTERVAL_SECONDS;
        String head = TransitNetwork.get().queueHead(s);
        if (head != null) {
            broadcast(world, s, Texts.t("transit.quay.docked").param("name", head).param("seconds", TransitConstants.COUNTDOWN_SECONDS));
        }
    }

    /** The departing balloon was created (called by BalloonManager.launchTransitDeparture). */
    static void onDeparting(TransitNetwork.Station s, BalloonFlight flight) {
        Quay q = quay(s);
        q.flight = flight;
        q.state = QuayState.DEPARTING;
    }

    /** The departing balloon is 20 blocks above the pad: the next destination can bring the next balloon. */
    static void onQuayCleared(TransitNetwork.Station s) {
        Quay q = quay(s);
        q.flight = null;
        q.gapUntilMs = System.currentTimeMillis() + TransitConstants.QUAY_GAP_SECONDS * 1000L;
        q.state = QuayState.FREE;
    }

    /**
     * A station is removed: its quay is dropped (the approaching balloon is removed, the docked one is taken apart), the removed
     * station leaves every queue of the world and the players near the stations concerned are told. Station block removal is not
     * done here.
     */
    static void onStationRemoved(World world, TransitNetwork.Station removed) {
        Quay q = QUAYS.remove(removed);
        if (q != null) {
            BalloonFlight f = q.flight;
            // An approaching balloon and an empty departing one (still within 20 blocks of the pad) are cancelled.
            // A departing balloon with passengers is not stopped: it keeps flying, its destination (or the rerouting below when the
            // destination is the removed station) takes them, and the quay is freed. Without passengers it is just removed.
            boolean keepsFlying = f != null && q.state == QuayState.DEPARTING && !f.passengers.isEmpty();
            if (f != null && !keepsFlying && (q.state == QuayState.APPROACH || q.state == QuayState.DEPARTING) && BalloonManager.get().active(f)) {
                f.stopping = true;
                Store<EntityStore> store = world.getEntityStore().getStore();
                BalloonManager.get().endTransit(store, f);
            }
            q.flight = null;
            // A launch already scheduled on the world thread sees FREE and does nothing.
            q.state = QuayState.FREE;
        }
        // The docked balloon is found through the lock registry, whatever the quay state says (quay lost, not recovered yet).
        try {
            Vector3i dock = dockOrigin(removed);
            if (BalloonRegistry.get(world, dock, removed.rotation, Deployables.BALLOON_PUBLIC.id) != null) {
                StructureShape shape = Deployables.BALLOON_PUBLIC.shape();
                BalloonRegistry.remove(world, dock, removed.rotation, Deployables.BALLOON_PUBLIC.id);
                BalloonManager.removeBlocks(world, shape, dock, removed.rotation);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Montgolfière à quai de %s non retirée", removed.name);
        }
        // Flights in the air heading to the removed station (the network no longer contains it when this is called).
        BalloonManager.get().rerouteTransitTo(world, removed);
        for (TransitNetwork.Station other : TransitNetwork.get().stations(world.getName())) {
            if (other == removed) {
                continue;
            }
            if (positionOf(other, removed.name) > 0) {
                TransitNetwork.get().queueRemoveAll(other, removed.name);
                broadcast(world, other, Texts.t("transit.network.removed").param("name", removed.name));
            }
        }
    }

    /**
     * Server start (world load): a transit balloon found resting in blocks at a station's dock, in the lock registry, sets the quay to
     * DOCKED with a fresh countdown. Nothing else in flight is restored.
     */
    static void recoverWorld(World world) {
        // Block, registry and store access: on the world thread. Idempotent (called at world start and at plugin start):
        // a quay that is no longer FREE is left alone.
        try {
            world.execute(() -> recoverOnThread(world));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Reprise des quais de %s impossible", world.getName());
        }
    }

    private static void recoverOnThread(World world) {
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            if (quay(s).state == QuayState.FREE
                    && BalloonRegistry.get(world, dockOrigin(s), s.rotation, Deployables.BALLOON_PUBLIC.id) != null) {
                LOGGER.at(Level.INFO).log("Station %s : montgolfière à quai retrouvée, compte à rebours de %d s", s.name,
                        TransitConstants.COUNTDOWN_SECONDS);
                startCountdown(world, s, quay(s));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean dockLoaded(World world, TransitNetwork.Station s) {
        Vector3i d = dockOrigin(s);
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dz = -2; dz <= 2; dz += 4) {
                if (world.getChunkIfLoaded(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(d.x + dx),
                        ChunkUtil.chunkCoordinate(d.z + dz))) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Sends the message to the players within TransitConstants.MESSAGE_RADIUS of the pad centre. */
    static void broadcast(World world, TransitNetwork.Station s, Message message) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3i dock = dockOrigin(s);
        double r2 = TransitConstants.MESSAGE_RADIUS * TransitConstants.MESSAGE_RADIUS;
        for (PlayerRef p : world.getPlayerRefs()) {
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t != null && t.getPosition().distanceSquared(new Vector3d(dock.x + 0.5, dock.y, dock.z + 0.5)) <= r2) {
                p.sendMessage(message);
            }
        }
    }
}
