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
 * Interaction "HotairBalloon_CageLever" (T73): using the cage lever (Hotair_Balloon_Cage_Lever block) of a placed transport
 * balloon. Lowers the cage, raises it, or stops it if it is moving (TransportCage.pullCageLever). The balloon is the one
 * in the registry that has the targeted block.
 */
public class CageLeverInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "HotairBalloon_CageLever";

    public static final BuilderCodec<CageLeverInteraction> CODEC = BuilderCodec
            .builder(CageLeverInteraction.class, CageLeverInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Lowers or raises the cage of the transport balloon, or stops it while it moves.")
            .build();

    public CageLeverInteraction() {
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
            player.sendMessage(entry == null ? Texts.t("cage.unknown") : TransportCage.pullCageLever(world, entry, player));
        });
    }
}
