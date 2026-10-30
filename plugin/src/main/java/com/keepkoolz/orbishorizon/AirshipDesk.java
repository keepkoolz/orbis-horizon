package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3i;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chart on the captain's desk (Airship_Captain_Map_Desk): the captain picks a destination on a parked ship with a pilot. The pilot
 * starts the voyage at once if he is at the helm, walks to the helm first if he sits on the stool. The pending destination is
 * kept in memory per ship (cleared by any other order, a take-off, a failure and a restart).
 */
final class AirshipDesk {

    private AirshipDesk() {
    }

    enum Action { NO_PILOT, NOT_CAPTAIN, BUSY, ABSENT, START_NOW, WALK_FIRST }

    /** Decision rule of the desk. Pure: no pilot first, then only the captain, then the pilot's state. */
    static Action decide(boolean owner, boolean hasPilot, AirshipCrew.CrewState state) {
        if (!hasPilot) {
            return Action.NO_PILOT;
        }
        if (!owner) {
            return Action.NOT_CAPTAIN;
        }
        return switch (state) {
            case AT_HELM -> Action.START_NOW;
            case SEATED -> Action.WALK_FIRST;
            case ABSENT -> Action.ABSENT;
            default -> Action.BUSY;
        };
    }

    /** Longest wait for the pilot to reach the helm before the order is dropped. */
    private static final long WAIT_MS = 90_000;

    private static final class Pending {
        final String world;
        final String kind;
        final Vector3i origin;
        final com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation;
        final UUID owner;
        final VoyagePlanner.Destination dest;
        final long startMs = System.currentTimeMillis();

        Pending(String world, String kind, Vector3i origin, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation, UUID owner, VoyagePlanner.Destination dest) {
            this.world = world;
            this.kind = kind;
            this.origin = origin;
            this.rotation = rotation;
            this.owner = owner;
            this.dest = dest;
        }
    }

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    /** The two chat lines of an accepted order: the captain to the pilot, then the pilot's answer. */
    static void sayOrder(PlayerRef captain, CrewRecord crew, VoyagePlanner.Destination d) {
        captain.sendMessage(ShipVoyage.withDest(Texts.pilot("crew.desk.captainOrder", crew), d.kind(), d.name())
                .param("captain", captain.getUsername()));
        captain.sendMessage(Texts.pilot("crew.desk.ack", crew));
    }

    /** Pilot on the stool: tells him to walk to the helm and keeps the destination. Null if ordered, otherwise the refusal. */
    static Message walkFirst(Store<EntityStore> store, World world, PlayerRef captain, BalloonRegistry.Entry e, VoyagePlanner.Destination d) {
        if (!AirshipCrew.orderWalk(store, world, e, CrewRecord.Post.HELM)) {
            return Texts.pilot("crew.desk.failed", e.crew());
        }
        sayOrder(captain, e.crew(), d);
        PENDING.put(key(world.getName(), e.x(), e.y(), e.z()),
                new Pending(world.getName(), e.kind(), new Vector3i(e.x(), e.y(), e.z()), e.rotation(), captain.getUuid(), d));
        return null;
    }

    /** Drops the pending destination of a ship (another order, a take-off). */
    static void cancel(World world, BalloonRegistry.Entry e) {
        if (e != null) {
            PENDING.remove(key(world.getName(), e.x(), e.y(), e.z()));
        }
    }

    /** Each tick of a world: starts the voyage of the ships whose pilot reached the helm, drops the failed ones. World thread. */
    static void tick(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Pending> me : PENDING.entrySet()) {
            Pending p = me.getValue();
            if (!p.world.equals(world.getName())) {
                continue;
            }
            try {
                BalloonRegistry.Entry e = BalloonRegistry.get(world, p.origin, p.rotation, p.kind);
                if (e == null || e.crew() == null) {
                    PENDING.remove(me.getKey());
                    continue;
                }
                AirshipCrew.CrewState st = AirshipCrew.state(world, e);
                PlayerRef captain = null;
                for (PlayerRef pr : world.getPlayerRefs()) {
                    if (p.owner.equals(pr.getUuid())) {
                        captain = pr;
                    }
                }
                if (st == AirshipCrew.CrewState.WALKING && now - p.startMs <= WAIT_MS) {
                    continue;
                }
                PENDING.remove(me.getKey());
                if (captain == null) {
                    continue;
                }
                if (st == AirshipCrew.CrewState.AT_HELM) {
                    Message error = AirshipManager.get().startVoyage(store, world, captain, e, p.dest);
                    if (error != null && error != AirshipManager.DEFERRED) {
                        captain.sendMessage(error);
                    }
                } else {
                    captain.sendMessage(Texts.pilot("crew.desk.failed", e.crew()));
                }
            } catch (RuntimeException ex) {
                PENDING.remove(me.getKey());
                com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(ex)
                        .log("Desk map: order abandoned");
            }
        }
    }
}
