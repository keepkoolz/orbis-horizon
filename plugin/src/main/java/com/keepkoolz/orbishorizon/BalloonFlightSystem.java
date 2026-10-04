package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Makes balloons in flight follow their pilot, on every tick of every world. */
public final class BalloonFlightSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int index, Store<EntityStore> store) {
        BalloonManager.get().tick(store, store.getExternalData().getWorld());
    }
}
