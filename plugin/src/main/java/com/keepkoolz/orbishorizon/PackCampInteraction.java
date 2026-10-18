package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/**
 * Interaction "Camping_PackCamp" (T154): Use (F) on a placed bag packs its camp into the matching pack item. The targeted block must
 * be the placed bag of a camp in the registry. The work is deferred to the world thread (CampPacker works on the Store), the
 * owner/op check and the messages are in CampPacker.
 */
public class PackCampInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Camping_PackCamp";

    public static final BuilderCodec<PackCampInteraction> CODEC = BuilderCodec
            .builder(PackCampInteraction.class, PackCampInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Packs the camp whose placed bag is targeted, if the player owns it (or is an operator).")
            .build();

    public PackCampInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        BlockPosition target = context.getTargetBlock();
        if (playerRef == null || target == null) {
            fail(context);
            return;
        }
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        BalloonRegistry.Entry entry = BalloonRegistry.find(world, new Vector3i(target.x, target.y, target.z));
        if (entry == null || CampTiers.ofKind(entry.kind()) == null) {
            playerRef.sendMessage(Texts.t("camp.notACamp"));
            fail(context);
            return;
        }
        world.execute(() -> {
            if (player.isValid()) {
                CampPacker.pack(world.getEntityStore().getStore(), player, world, entry, true);
            }
        });
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "PackCampInteraction{}";
    }
}
