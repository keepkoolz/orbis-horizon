package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/**
 * Interaction "HotairBalloon_CageGate" (T73): using the cage gate lever (Hotair_Balloon_Cage_Gate_Lever block) of a placed transport
 * balloon. Opens or closes the side of the cage (TransportCage.pullGateLever), cage immobile only. The balloon is the one
 * in the registry that has the targeted block.
 */
public class CageGateInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "HotairBalloon_CageGate";

    public static final BuilderCodec<CageGateInteraction> CODEC = BuilderCodec
            .builder(CageGateInteraction.class, CageGateInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Opens or closes the side of the cage of the transport balloon.")
            .build();

    public CageGateInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        BlockPosition target = context.getTargetBlock();
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
        Vector3i pos = target != null ? new Vector3i(target.x, target.y, target.z) : null;
        // Block changes are made outside the interaction processing, on the world thread.
        world.execute(() -> {
            if (!ref.isValid() || pos == null) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) {
                return;
            }
            BalloonRegistry.Entry entry = BalloonRegistry.find(world, pos);
            player.sendMessage(entry == null ? Texts.t("cage.unknown") : TransportCage.pullGateLever(world, entry));
        });
    }
}
