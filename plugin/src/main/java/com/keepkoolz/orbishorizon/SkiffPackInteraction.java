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
 * Interaction "Steamboat_Skiff_Pack": Use (F) on the placed crate of a skiff packs the skiff into its crate. The targeted block must
 * belong to a skiff in the registry. The work is deferred to the world thread (SkiffPacker works on the Store), the
 * owner/op check and the messages are in SkiffPacker. A press refuses at once what is already known (rights, under way, see
 * SkiffPacker.earlyRefusal), otherwise it starts the packing countdown, or cancels the running one (SkiffPackCountdown).
 */
public class SkiffPackInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Steamboat_Skiff_Pack";

    public static final BuilderCodec<SkiffPackInteraction> CODEC = BuilderCodec
            .builder(SkiffPackInteraction.class, SkiffPackInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Packs the skiff whose placed crate is targeted, if the player owns it (or is an operator).")
            .build();


    public SkiffPackInteraction() {
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
        if (entry == null || !Deployables.STEAMBOAT_SKIFF_KIND.equals(entry.kind())) {
            playerRef.sendMessage(Texts.t("skiff.notASkiff"));
            fail(context);
            return;
        }
        String refusal = SkiffPacker.earlyRefusal(world, entry, playerRef.getUuid());
        if (refusal != null) {
            playerRef.sendMessage(Texts.t(refusal));
            fail(context);
            return;
        }
        SkiffPackCountdown.press(playerRef, player, world, entry, new Vector3i(target.x, target.y, target.z));
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "SkiffPackInteraction{}";
    }
}
