package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Interaction "HotairBalloon_LearnRecipes" (T32): learns the mod's three recipes at once (crate, burner,
 * chain). The game's LearnRecipe only accepts a single ItemId and goes to Failed when the recipe is already known
 * (the Next, so the consumption of the item, is then skipped). Here the interaction succeeds as soon as at least one recipe
 * is new, and fails (item kept) only if all three were already known.
 * Known recipes are identified by the identifier of the crafted item (CraftingPlugin.learnRecipe).
 */
public class LearnBalloonRecipesInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "HotairBalloon_LearnRecipes";

    /** Items crafted by the mod's three recipes (recipe built into the item, output = the item). */
    static final String[] RECIPE_ITEMS = {
            "Hotair_Balloon_Crate", "Hotair_Balloon_Burner", "Hotair_Balloon_Chain"
    };

    public static final BuilderCodec<LearnBalloonRecipesInteraction> CODEC = BuilderCodec
            .builder(LearnBalloonRecipesInteraction.class, LearnBalloonRecipesInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Teaches the player every hot air balloon recipe (crate, burner, chain).")
            .build();

    public LearnBalloonRecipesInteraction() {
    }

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        PlayerRef player = buffer.getComponent(ref, PlayerRef.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        int learned = 0;
        for (String itemId : RECIPE_ITEMS) {
            if (CraftingPlugin.learnRecipe(ref, itemId, buffer)) {
                learned++;
            }
        }
        if (learned > 0) {
            player.sendMessage(Texts.t("recipes.learned"));
        } else {
            player.sendMessage(Texts.t("recipes.known"));
            context.getState().state = InteractionState.Failed;
        }
    }
}
