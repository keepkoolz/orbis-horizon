package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Makes balloons in flight follow their pilot, on every tick of every world. */
public final class BalloonFlightSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int index, Store<EntityStore> store) {
        BalloonManager.get().tick(store, store.getExternalData().getWorld());
        // T73: cage of the transport balloons placed in this world (steps due, registry write).
        TransportCage.tick(store.getExternalData().getWorld());
        // T74: animals of the cage waiting for their entity after a world load.
        CageAnimals.tick(store.getExternalData().getWorld());
        // Transit: station masters waiting for their entity, reservations to release.
        StationMaster.tickReservations(store, store.getExternalData().getWorld());
        // Transit: destination queues and quay state machines of the stations of this world.
        StationQueue.tick(store, store.getExternalData().getWorld());
        // T121: preview of the structure while a deployment crate is held.
        HeldCratePreview.tick(store, store.getExternalData().getWorld());
    }
}
