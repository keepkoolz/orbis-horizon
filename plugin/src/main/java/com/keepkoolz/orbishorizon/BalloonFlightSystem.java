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
        // Airship rope ladders: lowering and raising steps (never throws).
        ShipLadder.tick(store, store.getExternalData().getWorld(), System.currentTimeMillis());
        // T74: animals of the cage waiting for their entity after a world load.
        CageAnimals.tick(store.getExternalData().getWorld());
        // Transit: station masters waiting for their entity, reservations to release.
        StationMaster.tickReservations(store, store.getExternalData().getWorld());
        // Transit: destination queues and quay state machines of the stations of this world.
        StationQueue.tick(store, store.getExternalData().getWorld());
        // Lot 2: NPC pilots of landed airships waiting for their entity, outfit and wiring checks.
        try {
            AirshipCrew.tick(store, store.getExternalData().getWorld());
        } catch (RuntimeException e) {
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e).log("Pilote : tick en échec");
        }
        // T121: preview of the structure while a deployment crate is held.
        HeldCratePreview.tick(store, store.getExternalData().getWorld());
        // Passenger protection: players no longer aboard any flight lose their marker (safety net, at most once a second).
        BalloonManager.get().sweepProtection(store, store.getExternalData().getWorld());
        // T125: second teleport of a player who got up from a tent bed.
        TentBedSystem.tick(store, store.getExternalData().getWorld());
        CaptainMap.tick(store, store.getExternalData().getWorld()); // T141: never throws
        // Glider jump: passengers taking a glider in hand.
        try {
            GliderJump.tick(store, store.getExternalData().getWorld());
        } catch (RuntimeException e) {
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e).log("Saut en glider : tick en échec");
        }
    }
}
