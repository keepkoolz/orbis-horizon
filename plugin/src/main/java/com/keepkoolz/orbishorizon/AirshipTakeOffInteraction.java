package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Interaction "Airship_TakeOff": using the Airship_Helm block (the ship's helm window) (Use interaction of its BlockType). Takes off with the
 * airship placed around the player. If the player already pilots one, it starts the landing alignment instead.
 */
public class AirshipTakeOffInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Airship_TakeOff";

    public static final BuilderCodec<AirshipTakeOffInteraction> CODEC = BuilderCodec
            .builder(AirshipTakeOffInteraction.class, AirshipTakeOffInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Takes off with the airship the player stands in (helm).")
            .build();

    public AirshipTakeOffInteraction() {
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
            AirshipManager manager = AirshipManager.get();
            Message error = manager.isFlying(player.getUuid())
                    ? manager.requestLanding(player.getUuid())
                    : manager.takeOff(store, ref, player, world);
            if (error != null) {
                player.sendMessage(error);
            }
        });
    }
}
