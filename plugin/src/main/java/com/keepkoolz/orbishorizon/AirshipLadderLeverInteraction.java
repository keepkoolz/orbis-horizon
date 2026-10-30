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
 * Interaction "Airship_LadderLever": using a ladder lever (Airship_Ladder_Lever block) of a placed airship. Lowers the rope ladder of
 * that side, or raises it (ShipLadder.pullLever). The side is the sign of the lever cell's local x.
 */
public class AirshipLadderLeverInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Airship_LadderLever";

    public static final BuilderCodec<AirshipLadderLeverInteraction> CODEC = BuilderCodec
            .builder(AirshipLadderLeverInteraction.class, AirshipLadderLeverInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Lowers or raises the rope ladder of the airship on the lever's side.")
            .build();

    public AirshipLadderLeverInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        BlockPosition target = context.getTargetBlock();
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
        Vector3i pos = target != null ? new Vector3i(target.x, target.y, target.z) : null;
        // Block changes are made outside the interaction processing, on the world thread.
        world.execute(() -> {
            try {
                if (!ref.isValid() || pos == null) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                if (player == null) {
                    return;
                }
                BalloonRegistry.Entry entry = BalloonRegistry.find(world, pos);
                if (entry == null) {
                    player.sendMessage(Texts.t("airship.ladder.notParked"));
                    return;
                }
                Vector3i local = entry.rotation().toInverse().rotateYaw(
                        new Vector3i(pos.x - entry.x(), pos.y - entry.y(), pos.z - entry.z()), new Vector3i());
                com.hypixel.hytale.server.core.Message refusal = ShipLadder.pullLever(world, entry, Integer.signum(local.x));
                if (refusal != null) {
                    player.sendMessage(refusal);
                }
            } catch (RuntimeException e) {
                com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e)
                        .log("Airship ladder lever: failed");
            }
        });
    }
}
