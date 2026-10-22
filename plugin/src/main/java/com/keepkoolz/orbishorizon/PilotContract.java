package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** The pilot contract item and its purchase from a station master (lot 2 of the NPC airship pilot). */
final class PilotContract {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String ITEM_ID = "Airship_Pilot_Contract";
    static final String GOLD = "Ingredient_Bar_Gold";
    static final String SILVER = "Ingredient_Bar_Silver";
    static final String ESSENCE = "Ingredient_Life_Essence_Concentrated";

    /** Price, in this order: gold, silver, essence. */
    static final Map<String, Integer> PRICE;

    static {
        Map<String, Integer> p = new LinkedHashMap<>();
        p.put(GOLD, 50);
        p.put(SILVER, 35);
        p.put(ESSENCE, 15);
        PRICE = Collections.unmodifiableMap(p);
    }

    private PilotContract() {
    }

    /** What is still missing for each ingredient of the price (0 when covered). Pure. */
    static Map<String, Integer> missing(Map<String, Integer> have) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : PRICE.entrySet()) {
            out.put(e.getKey(), Math.max(0, e.getValue() - have.getOrDefault(e.getKey(), 0)));
        }
        return out;
    }

    /** Buys one contract. Returns the message to show on failure, or null when bought (the success message is sent here). */
    static Message buy(Store<EntityStore> store, Ref<EntityStore> playerRef, PlayerRef player) {
        try {
            ItemContainer inv = InventoryComponent.getCombined(store, playerRef, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
            Map<String, Integer> have = new LinkedHashMap<>();
            for (short i = 0; i < inv.getCapacity(); i++) {
                ItemStack s = inv.getItemStack(i);
                if (s != null && !s.isEmpty() && PRICE.containsKey(s.getItemId())) {
                    have.merge(s.getItemId(), s.getQuantity(), Integer::sum);
                }
            }
            Map<String, Integer> lack = missing(have);
            if (lack.values().stream().anyMatch(v -> v > 0)) {
                return Texts.t("crew.contract.missing")
                        .param("gold", lack.get(GOLD)).param("silver", lack.get(SILVER)).param("essence", lack.get(ESSENCE));
            }
            List<ItemStack> cost = new ArrayList<>();
            for (Map.Entry<String, Integer> e : PRICE.entrySet()) {
                cost.add(new ItemStack(e.getKey(), e.getValue()));
            }
            if (!inv.canRemoveItemStacks(cost)) {
                LOGGER.at(Level.WARNING).log("Pilote : retrait du prix impossible malgré les comptes");
                return Texts.t("crew.contract.failed");
            }
            inv.removeItemStacks(cost);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilote : achat du contrat en échec avant le retrait");
            return Texts.t("crew.contract.failed");
        }
        // The price is paid: from here on a failure must not lose the contract.
        ItemStack contract = new ItemStack(ITEM_ID, 1);
        ItemStack rest = contract;
        try {
            ItemStackTransaction t = Player.giveItem(contract, playerRef, store);
            rest = t != null ? t.getRemainder() : contract;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilote : contrat non donné, lâché au sol");
        }
        if (rest != null && !rest.isEmpty()) {
            Vector3d at = store.getComponent(playerRef, TransformComponent.getComponentType()).getPosition();
            BalloonManager.dropItems(store, List.of(rest), new Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)));
            player.sendMessage(Texts.t("crew.contract.boughtDropped"));
        } else {
            player.sendMessage(Texts.t("crew.contract.bought"));
        }
        LOGGER.at(Level.INFO).log("Pilote : contrat acheté par %s", player.getUsername());
        return null;
    }
}
