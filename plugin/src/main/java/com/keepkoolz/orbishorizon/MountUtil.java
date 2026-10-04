package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Player mounts, shared by the balloon (BalloonManager) and structure packing
 * (StructureRemoval, T46). Moved out of BalloonManager (T46), code unchanged.
 */
final class MountUtil {

    private MountUtil() {
    }

    /** Removes a player's current mount (for example a game stool whose block is about to disappear). */
    static void unseat(ComponentAccessor<EntityStore> store, Ref<EntityStore> ref) {
        if (ref.isValid() && store.getComponent(ref, MountedComponent.getComponentType()) != null) {
            store.tryRemoveComponent(ref, MountedComponent.getComponentType());
        }
    }
}
