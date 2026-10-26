package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import java.util.List;
import java.util.logging.Level;

/**
 * The four gems of the airship crate recipe, handed back in the chest of the captain's cabin when the crate is deployed.
 * Only the crate deployment goes through here (not landing, not resume), and the prefab's own container stays empty.
 * The chest is filled by BalloonCargo, so the retry for a component that does not exist yet and the drop on the ground
 * of what finds no room are the ones of the landing contents.
 */
final class AirshipCaptainChest {

    /** Chest of the captain's cabin (Furniture_Temple_Wind_Chest_Small), prefab frame of The_Cloudwork.prefab.json. */
    static final Vector3i CHEST_LOCAL = new Vector3i(-2, 9, 3);
    static final String CHEST_BLOCK = "Furniture_Temple_Wind_Chest_Small";
    static final short CHEST_CAPACITY = 18;

    /** The gems of the crate recipe, one of each. */
    static final List<String> GEM_IDS = List.of("Rock_Gem_Zephyr", "Rock_Gem_Sapphire", "Rock_Gem_Ruby", "Rock_Gem_Emerald");

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private AirshipCaptainChest() {
    }

    /** The gems as the contents of the chest, one per slot from the first. Needs the item assets (not available in the tests). */
    static BalloonCargo gemsCargo() {
        ItemStack[] slots = new ItemStack[CHEST_CAPACITY];
        for (int i = 0; i < GEM_IDS.size(); i++) {
            slots[i] = new ItemStack(GEM_IDS.get(i), 1);
        }
        return new BalloonCargo(new java.util.ArrayList<>(List.of(
                new BalloonCargo.Entry(new Vector3i(CHEST_LOCAL), CHEST_BLOCK, CHEST_CAPACITY, slots))));
    }

    /** World position of the chest for a prefab placed at this origin and rotation (same transform as the paste). */
    static Vector3i worldPosition(Vector3i origin, Rotation rotation) {
        return rotation.rotateYaw(new Vector3i(CHEST_LOCAL), new Vector3i()).add(origin);
    }

    /**
     * Puts the gems in the chest of the airship just pasted at this origin and rotation. Run on the world thread, after the
     * interaction: the chest is looked for again on the following ticks if its component does not exist yet, and the gems
     * that find no chest or no room are dropped next to it.
     */
    static void give(World world, Vector3i origin, Rotation rotation) {
        Vector3i o = new Vector3i(origin);
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                BalloonManager.get().restoreCargo(world, store, gemsCargo(), o, rotation);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Gemmes de la caisse du dirigeable non rangées dans le coffre du capitaine");
                Telemetry.error("airship.captainChest", e, Deployables.AIRSHIP_KIND, null);
            }
        });
    }
}
