package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Interaction "Airship_CrewTalk": the Use interaction of an airship pilot NPC (root Airship_Pilot_Use). */
public class AirshipCrewTalkInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Airship_CrewTalk";

    public static final BuilderCodec<AirshipCrewTalkInteraction> CODEC = BuilderCodec
            .builder(AirshipCrewTalkInteraction.class, AirshipCrewTalkInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Talks to the pilot of an airship: gives him orders.")
            .build();

    public AirshipCrewTalkInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> player = context.getEntity();
        Ref<EntityStore> target = context.getTargetEntity();
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
        world.execute(() -> {
            try {
                if (!player.isValid() || target == null || !target.isValid()) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                UUIDComponent id = store.getComponent(target, UUIDComponent.getComponentType());
                PlayerRef pr = store.getComponent(player, PlayerRef.getComponentType());
                Player p = store.getComponent(player, Player.getComponentType());
                if (id == null || pr == null || p == null) {
                    return;
                }
                BalloonRegistry.Entry e = AirshipCrew.entryOfPilot(world.getName(), id.getUuid());
                if (e == null) {
                    return;
                }
                if (e.owner() == null || !e.owner().equals(pr.getUuid())) {
                    pr.sendMessage(Texts.pilot("crew.talk.notCaptain", e.crew()));
                    return;
                }
                if (AirshipCrew.state(world, e) == AirshipCrew.CrewState.WALKING) {
                    pr.sendMessage(Texts.pilot("crew.talk.busy", e.crew()));
                    return;
                }
                p.getPageManager().openCustomPage(player, store, new AirshipVoyagePage(pr, id.getUuid()));
            } catch (RuntimeException ex) {
                com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(ex)
                        .log("Pilote : dialogue non ouvert");
            }
        });
    }
}
