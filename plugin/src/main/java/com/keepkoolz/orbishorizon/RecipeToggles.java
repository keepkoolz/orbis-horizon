package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateRecipes;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Single entry point for the state of the mod's recipes: hides the disabled ones in the crafting bench (UpdateRecipes packets),
 * refuses to craft them, and brings the players back in line when they arrive in a world.
 */
final class RecipeToggles {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private RecipeToggles() {
    }

    static boolean enabled(String itemId) {
        return !TransitSettings.disabledRecipes().contains(itemId);
    }

    /** Saves the state of a recipe, then updates every connected player. An id that is not a mod item is ignored. */
    static void set(String itemId, boolean on) {
        if (!ModRecipes.isModItem(itemId)) {
            return;
        }
        TransitSettings.setRecipeDisabled(itemId, !on);
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        for (World w : universe.getWorlds().values()) {
            w.execute(() -> {
                for (PlayerRef pr : w.getPlayerRefs()) {
                    try {
                        Ref<EntityStore> r = pr.getReference();
                        if (r == null || !r.isValid()) {
                            continue;
                        }
                        if (StationSpec.CRATE_IDS.contains(itemId)) {
                            if (on) CraftingPlugin.learnRecipe(r, itemId, r.getStore());
                            else CraftingPlugin.forgetRecipe(r, itemId, r.getStore());
                        }
                        send(pr);
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Mod recipes not synced for %s", pr.getUsername());
                        Telemetry.error("recipes", e, null, null);
                    }
                }
            });
        }
    }

    /** Learns or forgets the station crate recipes and hides the disabled ones for a player arriving in a world (holder not in the store yet). */
    static void syncOnArrival(Holder<EntityStore> holder) {
        Player p = holder.getComponent(Player.getComponentType());
        if (p == null) {
            return;
        }
        Set<String> known = new HashSet<>(p.getPlayerConfigData().getKnownRecipes());
        boolean changed = false;
        for (String id : StationSpec.CRATE_IDS) {
            changed |= enabled(id) ? known.add(id) : known.remove(id);
        }
        if (changed) {
            p.getPlayerConfigData().setKnownRecipes(known);
        }
        PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
        if (pr != null) {
            send(pr);
        }
    }

    /** Sends the add/update packet for the enabled mod recipes and the remove packet for the disabled ones. Never throws. */
    private static void send(PlayerRef pr) {
        try {
            Map<String, com.hypixel.hytale.protocol.CraftingRecipe> add = new HashMap<>();
            Set<String> remove = new HashSet<>();
            for (Map.Entry<String, CraftingRecipe> e : CraftingRecipe.getAssetMap().getAssetMap().entrySet()) {
                MaterialQuantity out = e.getValue().getPrimaryOutput();
                String item = out == null ? null : out.getItemId();
                if (item == null || !ModRecipes.isModItem(item)) {
                    continue;
                }
                if (enabled(item)) {
                    add.put(e.getKey(), e.getValue().toPacket(e.getKey()));
                } else {
                    remove.add(e.getKey());
                }
            }
            if (!add.isEmpty()) {
                pr.getPacketHandler().writeNoCache(new UpdateRecipes(UpdateType.AddOrUpdate, add, null));
            }
            if (!remove.isEmpty()) {
                pr.getPacketHandler().writeNoCache(new UpdateRecipes(UpdateType.Remove, null, remove.toArray(new String[0])));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Recipe list not sent to %s", pr.getUsername());
            Telemetry.error("recipes", e, null, null);
        }
    }

    /** CraftRecipeEvent.Pre: refuses to craft a mod item whose recipe is switched off. */
    static final class Guard extends EntityEventSystem<EntityStore, CraftRecipeEvent.Pre> {
        Guard() {
            super(CraftRecipeEvent.Pre.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, CraftRecipeEvent.Pre event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                CraftingRecipe recipe = event.getCraftedRecipe();
                MaterialQuantity out = recipe == null ? null : recipe.getPrimaryOutput();
                String item = out == null ? null : out.getItemId();
                if (item == null || !ModRecipes.isModItem(item) || enabled(item)) {
                    return;
                }
                event.setCancelled(true);
                PlayerRef pr = store.getComponent(chunk.getReferenceTo(index), PlayerRef.getComponentType());
                if (pr != null) {
                    pr.sendMessage(Texts.t("config.recipeDisabled"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Disabled recipe: check failed");
                Telemetry.error("recipes", e, null, null);
            }
        }
    }
}
