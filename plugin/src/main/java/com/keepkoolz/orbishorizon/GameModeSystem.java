package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.ChangeGameModeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Game mode change of a player (T75). Player.setGameMode invokes ChangeGameModeEvent on the player's entity, then
 * setGameModeInternal sets the new mode, sends SetGameMode and calls MovementManager.resetFly(mode) then update(): the
 * forced flight set by the mod would be overwritten, the survival pilot would fall and the collision teleport would bring
 * them back in a loop. Same shape as FlockSystems$PlayerChangeGameModeEventSystem of the game.
 *
 * The event is not cancelled. The pilot or passenger of a balloon or airship leaves the vehicle (processed on the world
 * thread after the change, BalloonManager.onGameModeChange and AirshipManager.onGameModeChange).
 */
public final class GameModeSystem extends EntityEventSystem<EntityStore, ChangeGameModeEvent> {

    public GameModeSystem() {
        super(ChangeGameModeEvent.class);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, ChangeGameModeEvent event) {
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        BalloonManager.get().onGameModeChange(store, ref, event.getGameMode());
        AirshipManager.get().onGameModeChange(store, ref, event.getGameMode());
    }
}
