package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Makes the airships in flight follow their pilot and steer, on every tick of every world (AirshipManager.tick). */
public final class AirshipFlightSystem extends TickingSystem<EntityStore> {

    @Override
    public void tick(float dt, int index, Store<EntityStore> store) {
        AirshipManager.get().tick(store, store.getExternalData().getWorld());
    }
}
