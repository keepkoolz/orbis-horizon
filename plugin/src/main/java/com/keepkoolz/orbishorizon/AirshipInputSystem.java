package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the pilot's head yaw without consuming anything (airship). The packet handler queues InputUpdate objects in
 * PlayerInput. This system runs before PlayerSystems$ProcessPlayerInput and only copies what it needs from a snapshot of the
 * list: nothing is removed or changed.
 *
 * Facts of the test of 5 October 2026 (/orbishorizon airship input): the client never sends WishMovement, and sends no rider
 * states or relative movement. Not mounted it sends AbsoluteMovement continuously, SetHead following the mouse and
 * SetMovementStates when the states change. So the only input used is the head yaw (SetHead), for the "look" steering.
 * It also feeds /orbishorizon airship input (raw log of every update seen, kept as a diagnostic).
 */
public final class AirshipInputSystem extends EntityTickingSystem<EntityStore> {

    private final Query<EntityStore> query;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public AirshipInputSystem() {
        this.query = Query.and(new Query[]{Player.getComponentType(), PlayerInput.getComponentType()});
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<EntityStore, PlayerSystems.ProcessPlayerInput>(
                Order.BEFORE, PlayerSystems.ProcessPlayerInput.class));
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> commandBuffer) {
        AirshipManager manager = AirshipManager.get();
        if (!manager.needsInput()) {
            return;
        }
        PlayerRef pr = chunk.getComponent(index, PlayerRef.getComponentType());
        if (pr == null) {
            return;
        }
        UUID uuid = pr.getUuid();
        AirshipFlight flight = manager.flightOf(uuid);
        AirshipManager.Watch watch = manager.watchOf(uuid);
        if (flight == null && watch == null) {
            return;
        }
        PlayerInput input = chunk.getComponent(index, PlayerInput.getComponentType());
        if (input == null) {
            return;
        }
        List<PlayerInput.InputUpdate> snapshot = new ArrayList<>(input.getMovementUpdateQueue());
        if (snapshot.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        AirshipFlight.Input in = flight != null ? flight.input : null;
        StringBuilder log = watch != null ? new StringBuilder() : null;
        for (PlayerInput.InputUpdate u : snapshot) {
            if (u instanceof PlayerInput.WishMovement w) {
                if (log != null) {
                    log.append(String.format(java.util.Locale.ROOT, " wish(%.2f %.2f %.2f)", w.getX(), w.getY(), w.getZ()));
                }
            } else if (u instanceof PlayerInput.SetRiderMovementStates r) {
                if (log != null && r.movementStates() != null) {
                    log.append(" rider[").append(statesText(r.movementStates())).append("]");
                }
            } else if (u instanceof PlayerInput.SetMovementStates m) {
                if (log != null && m.movementStates() != null) {
                    log.append(" states[").append(statesText(m.movementStates())).append("]");
                }
            } else if (u instanceof PlayerInput.SetHead h) {
                if (in != null && h.direction() != null) {
                    in.headYaw = h.direction().yaw;
                    in.headMs = now;
                    in.headCount++;
                }
                if (log != null && h.direction() != null) {
                    log.append(String.format(java.util.Locale.ROOT, " head(yaw %.1f deg)", Math.toDegrees(h.direction().yaw)));
                }
            } else if (u instanceof PlayerInput.RelativeMovement rm) {
                if (log != null) {
                    log.append(String.format(java.util.Locale.ROOT, " rel(%.3f %.3f %.3f)", rm.getX(), rm.getY(), rm.getZ()));
                }
            } else if (u instanceof PlayerInput.AbsoluteMovement) {
                if (log != null) {
                    log.append(" abs");
                }
            }
        }
        if (watch != null && log.length() > 0) {
            manager.watchSample(watch, pr, log.toString(), input.getMountId(), now);
        }
    }

    private static String statesText(MovementStates s) {
        StringBuilder sb = new StringBuilder();
        if (s.jumping) sb.append("jump ");
        if (s.crouching) sb.append("crouch ");
        if (s.sitting) sb.append("sit ");
        if (s.mounting) sb.append("mount ");
        if (s.walking) sb.append("walk ");
        if (s.running) sb.append("run ");
        if (s.sprinting) sb.append("sprint ");
        if (s.flying) sb.append("fly ");
        if (s.idle) sb.append("idle ");
        return sb.toString().trim();
    }
}
