package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Death of a player (T39). The game adds DeathComponent to the player's entity when they die (it is the same entity,
 * its reference stays valid), the respawn systems (RespawnSystems.OnRespawnSystem) react to its
 * removal. This system follows the same model, on the add side: it warns BalloonManager, which removes the player from their
 * flight (pilot or passenger) before the respawn. The processing is deferred to the world thread.
 */
public final class BalloonDeathSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        BalloonManager.get().onPlayerDeath(store, ref);
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, DeathComponent oldDeath, DeathComponent newDeath,
                               Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        // Replacement of an existing death component: the player is already handled.
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        // Respawn: nothing to do, the player has not been in the flight since their death.
    }
}
