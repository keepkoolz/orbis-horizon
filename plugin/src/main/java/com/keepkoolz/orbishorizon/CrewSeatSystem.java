package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.logging.Level;

/**
 * Mounts an airship pilot on the cabin stool like a player sitting (lot 2, T135). The block mount needs a CommandBuffer, which
 * only a system gets: AirshipCrew queues requests, this system serves them inside the world tick.
 */
final class CrewSeatSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final Query<EntityStore> query = Query.and(new Query[]{NPCEntity.getComponentType()});

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        if (!AirshipCrew.hasMountRequests()) {
            return;
        }
        UUIDComponent id = chunk.getComponent(index, UUIDComponent.getComponentType());
        if (id == null || !AirshipCrew.takeMountRequest(id.getUuid())) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        TransformComponent tr = chunk.getComponent(index, TransformComponent.getComponentType());
        if (tr == null) {
            return;
        }
        try {
            AirshipCrew.processMount(ref, new Vector3d(tr.getPosition()), store, buffer, id.getUuid());
        } catch (RuntimeException ex) {
            // Never let an exception reach the game's entity loop: the request is dropped.
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilote : montage sur le tabouret abandonné (%s)", id.getUuid());
        }
    }
}
