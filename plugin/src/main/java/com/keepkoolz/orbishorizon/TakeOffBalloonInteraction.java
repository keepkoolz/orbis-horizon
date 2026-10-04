package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Interaction "HotairBalloon_TakeOff": using the burner chain (Hotair_Balloon_Chain block).
 * Takes off with the balloon placed around the player. If the player is already flying, lands.
 */
public class TakeOffBalloonInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "HotairBalloon_TakeOff";

    public static final BuilderCodec<TakeOffBalloonInteraction> CODEC = BuilderCodec
            .builder(TakeOffBalloonInteraction.class, TakeOffBalloonInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Takes off with the hot air balloon the player stands in.")
            .build();

    public TakeOffBalloonInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
        // Block and entity changes are made outside the interaction processing.
        world.execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) {
                return;
            }
            BalloonManager manager = BalloonManager.get();
            if (manager.isFlying(player.getUuid())) {
                Message error = manager.land(store, player);
                player.sendMessage(error != null ? error : Texts.t("landed"));
            } else {
                Message error = manager.takeOff(store, ref, player, world);
                player.sendMessage(error != null ? error : Texts.t("takeoff.chain"));
            }
        });
    }
}
