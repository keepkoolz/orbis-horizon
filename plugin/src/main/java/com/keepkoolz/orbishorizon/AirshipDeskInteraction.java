package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.BlockPosition;
import org.joml.Vector3i;

/** Interaction "Airship_DeskMap": Use of the Airship_Captain_Map_Desk block. Opens the destination list for the captain. */
public class AirshipDeskInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Airship_DeskMap";

    public static final BuilderCodec<AirshipDeskInteraction> CODEC = BuilderCodec
            .builder(AirshipDeskInteraction.class, AirshipDeskInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Opens the destination list of the captain's chart on a parked airship.")
            .build();

    public AirshipDeskInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        BlockPosition target = context.getTargetBlock();
        Vector3i pos = target != null ? new Vector3i(target.x, target.y, target.z) : null;
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
        world.execute(() -> {
            try {
                if (pos == null || !ref.isValid()) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                Player p = store.getComponent(ref, Player.getComponentType());
                BalloonRegistry.Entry e = BalloonRegistry.find(world, pos);
                if (pr == null || p == null || e == null || Deployables.crewOf(e.kind()) == null || Deployables.crewOf(e.kind()).desk() == null) {
                    return;
                }
                boolean owner = e.owner() != null && e.owner().equals(pr.getUuid());
                AirshipDesk.Action a = AirshipDesk.decide(owner, e.crew() != null, AirshipCrew.state(world, e));
                switch (a) {
                    case NO_PILOT -> {
                        if (owner) {
                            pr.sendMessage(Texts.t("crew.desk.noPilot"));
                        }
                    }
                    case NOT_CAPTAIN -> pr.sendMessage(Texts.pilot("crew.talk.notCaptain", e.crew()));
                    case ABSENT -> pr.sendMessage(Texts.t("crew.desk.absent"));
                    case BUSY -> pr.sendMessage(Texts.pilot("crew.talk.busy", e.crew()));
                    default -> p.getPageManager().openCustomPage(ref, store, new AirshipVoyagePage(pr, e.crew().uuid(), true));
                }
            } catch (RuntimeException ex) {
                com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(ex)
                        .log("Desk map: window not opened");
            }
        });
    }
}
