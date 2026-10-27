package com.keepkoolz.orbishorizon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;

import java.util.function.Predicate;
import java.util.logging.Level;

/** Removes from the crafting bench window data the tabs whose recipes are all disabled mod recipes. */
final class BenchTabs {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static PacketFilter filter;

    private BenchTabs() {
    }

    /** Registers the outbound watcher that rewrites the windowData of OpenWindow and UpdateWindow packets. */
    static synchronized void register() {
        if (filter != null) {
            return;
        }
        filter = PacketAdapters.registerOutbound((PlayerPacketWatcher) (pr, packet) -> {
            try {
                if (packet instanceof OpenWindow ow) {
                    ow.windowData = rewrite(ow.windowData);
                } else if (packet instanceof UpdateWindow uw) {
                    uw.windowData = rewrite(uw.windowData);
                }
            } catch (Throwable e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Onglets d'établi non filtrés");
                Telemetry.error("recipes", e, null, null);
            }
        });
    }

    static synchronized void unregister() {
        if (filter == null) {
            return;
        }
        try {
            PacketAdapters.deregisterOutbound(filter);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Filtre des onglets d'établi non retiré");
        }
        filter = null;
    }

    private static String rewrite(String windowData) {
        if (windowData == null || !windowData.contains("categories")) {
            return windowData;
        }
        return filterTabs(windowData, BenchTabs::isDisabledModRecipe);
    }

    /** True when the recipe exists, produces a mod item and that item's recipe is switched off. */
    private static boolean isDisabledModRecipe(String recipeId) {
        CraftingRecipe recipe = CraftingRecipe.getAssetMap().getAsset(recipeId);
        MaterialQuantity out = recipe == null ? null : recipe.getPrimaryOutput();
        String item = out == null ? null : out.getItemId();
        return item != null && ModRecipes.isModItem(item) && !RecipeToggles.enabled(item);
    }

    /**
     * Drops each category whose craftableRecipes is non-empty and made only of disabled recipes. Returns the input
     * itself when nothing changes or when it cannot be parsed. Never throws.
     */
    static String filterTabs(String windowData, Predicate<String> recipeDisabled) {
        try {
            if (windowData == null) {
                return null;
            }
            JsonElement root = JsonParser.parseString(windowData);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("categories")
                    || !root.getAsJsonObject().get("categories").isJsonArray()) {
                return windowData;
            }
            JsonObject obj = root.getAsJsonObject();
            JsonArray kept = new JsonArray();
            boolean removed = false;
            for (JsonElement cat : obj.getAsJsonArray("categories")) {
                if (allDisabled(cat, recipeDisabled)) {
                    removed = true;
                } else {
                    kept.add(cat);
                }
            }
            if (!removed) {
                return windowData;
            }
            obj.add("categories", kept);
            return obj.toString();
        } catch (RuntimeException e) {
            return windowData;
        }
    }

    private static boolean allDisabled(JsonElement cat, Predicate<String> recipeDisabled) {
        if (!cat.isJsonObject()) {
            return false;
        }
        JsonElement list = cat.getAsJsonObject().get("craftableRecipes");
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty()) {
            return false;
        }
        for (JsonElement id : list.getAsJsonArray()) {
            if (!id.isJsonPrimitive() || !recipeDisabled.test(id.getAsString())) {
                return false;
            }
        }
        return true;
    }
}
