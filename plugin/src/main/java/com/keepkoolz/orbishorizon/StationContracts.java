package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The station contracts: items that place the master and the quay of a station (see the Transit_PlaceMaster interaction),
 * sold by the station masters, given once to the first player of a world with no station, and handed out in exchange for the
 * old station crates. A station kind is Deployables.STATION_KIND (balloon) or Deployables.BOAT_STATION_KIND (boat).
 */
final class StationContracts {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String BALLOON_ITEM = "Hotair_Balloon_Station_Contract";
    static final String BOAT_ITEM = "Hotair_Balloon_Boat_Station_Contract";
    static final String BALLOON_CRATE = "Balloon_Station_Crate";
    static final String BOAT_CRATE = "Boat_Station_Crate";

    private StationContracts() {
    }

    /** Item id of the contract of a station kind. Any kind other than the boat station gives the balloon contract. */
    static String itemFor(String kind) {
        return Deployables.BOAT_STATION_KIND.equals(kind) ? BOAT_ITEM : BALLOON_ITEM;
    }

    /** Station kind of a contract item, null when the item is not a station contract. */
    static String kindOfItem(String itemId) {
        if (BALLOON_ITEM.equals(itemId)) return Deployables.STATION_KIND;
        if (BOAT_ITEM.equals(itemId)) return Deployables.BOAT_STATION_KIND;
        return null;
    }

    /** Station kind of an old station crate, null when the item is not one. */
    static String kindOfCrate(String itemId) {
        if (BALLOON_CRATE.equals(itemId)) return Deployables.STATION_KIND;
        if (BOAT_CRATE.equals(itemId)) return Deployables.BOAT_STATION_KIND;
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // first contract

    /** The first contract goes to a world that has no station, once. Pure. */
    static boolean shouldGiveFirst(Set<String> given, String world, boolean worldHasStation) {
        return !worldHasStation && !given.contains(world);
    }

    /**
     * The first contract is given in the default world of the universe only, so that a second world never gets one. Without a known
     * default world every world may receive it. Pure.
     */
    static boolean isDefaultWorld(String defaultWorld, String world) {
        return defaultWorld == null || defaultWorld.equals(world);
    }

    /** Name of the default world of the universe, null when it cannot be read. */
    static String defaultWorldName() {
        try {
            World w = com.hypixel.hytale.server.core.universe.Universe.get().getDefaultWorld();
            return w == null ? null : w.getName();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** With a world host known only that player receives the first contract, without one any player may. Pure. */
    static boolean mayReceiveFirst(UUID host, UUID player) {
        return host == null || host.equals(player);
    }

    /** The player who created the local world (launch option --owner-uuid), null on a dedicated server or without the module. */
    static UUID hostUuid() {
        try {
            return com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule.getUuid();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** PlayerReadyEvent: offers the first contract to the player on the world thread. Never throws. */
    static void onReady(Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) return;
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                try {
                    if (!ref.isValid()) return;
                    PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
                    if (player != null) onFirstJoin(world, player);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("First station contract failed");
                    Telemetry.error("station.contract", e, null, null);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("First station contract not scheduled");
            Telemetry.error("station.contract", e, null, null);
        }
    }

    /**
     * Gives a balloon station contract when the world has no station of any kind and none was given before. With a world host
     * known, only the host receives it. The gift is noted in stations.json. World thread. Never throws.
     */
    static void onFirstJoin(World world, PlayerRef player) {
        try {
            TransitNetwork net = TransitNetwork.get();
            String name = world.getName();
            if (!isDefaultWorld(defaultWorldName(), name)) return;
            if (!shouldGiveFirst(net.firstContractWorlds(), name, !net.stations(name).isEmpty())) return;
            if (!mayReceiveFirst(hostUuid(), player.getUuid())) return;
            if (!net.setFirstContract(name, true)) return;
            Given result = give(player, Deployables.STATION_KIND);
            if (result != Given.FAILED) {
                player.sendMessage(Texts.t(result == Given.DROPPED ? "transit.contract.firstDropped" : "transit.contract.first"));
            } else {
                net.setFirstContract(name, false); // try again at the next arrival
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("First station contract failed");
            Telemetry.error("station.contract", e, null, null);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // giving

    /** What became of an item given to a player: in the inventory, dropped at their feet (inventory full), or not given at all. */
    enum Given {
        GIVEN, DROPPED, FAILED;

        /** The item reached the player, in the inventory or on the ground. */
        boolean reached() {
            return this != FAILED;
        }
    }

    /** Result of giveCrate: true when the item is in the inventory, false when it was dropped at the feet. Pure. */
    static Given givenFrom(boolean inInventory) {
        return inInventory ? Given.GIVEN : Given.DROPPED;
    }

    /** Puts a contract of the kind in the player's inventory, at their feet when it is full. World thread. */
    static Given give(PlayerRef player, String kind) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) return Given.FAILED;
            return give(ref.getStore(), ref, itemFor(kind));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station contract not given");
            Telemetry.error("station.contract", e, null, null);
            return Given.FAILED;
        }
    }

    /** Gives an item to a player, dropped at their feet when the inventory is full. World thread. */
    static Given give(Store<EntityStore> store, Ref<EntityStore> ref, String itemId) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null) return Given.FAILED;
        Vector3d at = t.getPosition();
        return givenFrom(BalloonManager.get().giveCrate(store, ref, at, itemId));
    }

    // ------------------------------------------------------------------------------------------------------------
    // purchase

    /**
     * Buys one contract of the kind from a station master: the price is checked on a simulation of the inventory, taken, then the
     * contract is given. Creative mode pays too. If the contract cannot be given the price is returned. Returns the message to show
     * on failure, null when bought (the success message is sent here). World thread.
     */
    static Message buy(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, String kind) {
        TransitFare.Payment pay;
        try {
            ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
            int essences = 0;
            int concentrated = 0;
            for (short i = 0; i < inv.getCapacity(); i++) {
                ItemStack s = inv.getItemStack(i);
                if (s == null || s.isEmpty()) continue;
                if (TransitFare.ESSENCE.equals(s.getItemId())) essences += s.getQuantity();
                else if (TransitFare.CONCENTRATED.equals(s.getItemId())) concentrated += s.getQuantity();
            }
            int needE = TransitSettings.contractEssences();
            int needC = TransitSettings.contractConcentrated();
            pay = TransitFare.choose(player.getUuid(), essences, concentrated, needE, needC);
            if (pay == null) {
                return Texts.t("transit.contract.missing")
                        .param("essence", Math.max(0, needE - essences))
                        .param("concentrated", Math.max(0, needC - concentrated));
            }
            List<ItemStack> cost = List.of(new ItemStack(pay.itemId(), pay.quantity()));
            if (!inv.canRemoveItemStacks(cost)) {
                LOGGER.at(Level.WARNING).log("Station contract: cannot withdraw the price despite the counts");
                return Texts.t("transit.contract.failed");
            }
            inv.removeItemStacks(cost);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station contract: purchase failed before the removal");
            Telemetry.error("station.contract", e, null, null);
            return Texts.t("transit.contract.failed");
        }
        Given given = Given.FAILED;
        try {
            given = give(store, ref, itemFor(kind));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station contract: contract not given");
            Telemetry.error("station.contract", e, null, null);
        }
        if (!given.reached()) {
            TransitFare.refundHere(player, pay, "transit.contract.refunded", "transit.contract.refundedDropped");
            return Texts.t("transit.contract.failed");
        }
        Telemetry.count("station.contract");
        try {
            player.sendMessage(Texts.t(given == Given.DROPPED ? "transit.contract.boughtDropped" : "transit.contract.bought"));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station contract: message not sent");
        }
        return null;
    }
}
