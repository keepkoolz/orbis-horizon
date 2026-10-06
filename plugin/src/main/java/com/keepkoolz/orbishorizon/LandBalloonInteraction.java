package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Interaction "HotairBalloon_Land": the Use interaction of the chain helper entity in flight (BalloonLights.spawnChain, root
 * Hotair_Balloon_Chain_Use). The acting entity is the player. If they pilot a balloon, it is put down in place (same rules as
 * /orbishorizon balloon land). Anybody else is ignored.
 */
public class LandBalloonInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "HotairBalloon_Land";

    public static final BuilderCodec<LandBalloonInteraction> CODEC = BuilderCodec
            .builder(LandBalloonInteraction.class, LandBalloonInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Lands the hot air balloon the player is piloting.")
            .build();

    public LandBalloonInteraction() {
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
            BalloonManager.get().chainLand(store, player);
        });
    }
}
