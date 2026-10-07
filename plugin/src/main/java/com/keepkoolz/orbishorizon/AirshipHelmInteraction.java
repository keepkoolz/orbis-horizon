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
 * Interaction "Airship_HelmLand": the Use interaction of the helm helper entity (AirshipHelm) in flight. The acting entity is
 * the player. If he pilots an airship, the landing starts (same path as /orbishorizon airship land). Anybody else is ignored.
 */
public class AirshipHelmInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Airship_HelmLand";

    public static final BuilderCodec<AirshipHelmInteraction> CODEC = BuilderCodec
            .builder(AirshipHelmInteraction.class, AirshipHelmInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Starts the landing of the airship the player pilots (helm entity in flight).")
            .build();

    public AirshipHelmInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
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
            if (!manager.isFlying(player.getUuid())) {
                return; // not the pilot: ignored
            }
            Message error = manager.requestLanding(player.getUuid());
            if (error != null) {
                player.sendMessage(error);
            }
        });
    }
}
