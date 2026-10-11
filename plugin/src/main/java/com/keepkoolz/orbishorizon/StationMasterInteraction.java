package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Interaction "Transit_StationMaster": the Use interaction of a station master NPC (root Station_Master_Use). The acting entity is
 * the player. The station is found from the targeted entity's UUID, or, if the game gives no target, from the master nearest to
 * the player. Everything goes through StationMaster.onTalk, the single entry point.
 */
public class StationMasterInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Transit_StationMaster";

    public static final BuilderCodec<StationMasterInteraction> CODEC = BuilderCodec
            .builder(StationMasterInteraction.class, StationMasterInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Talks to the station master of a balloon station: chooses a destination.")
            .build();

    public StationMasterInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<EntityStore> player = context.getEntity();
        Ref<EntityStore> target = context.getTargetEntity();
        World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
        world.execute(() -> {
            if (!player.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            TransitNetwork.Station station = null;
            if (target != null && target.isValid()) {
                com.hypixel.hytale.server.core.entity.UUIDComponent id =
                        store.getComponent(target, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                if (id != null) {
                    station = StationMaster.stationOfMaster(world.getName(), id.getUuid());
                }
            }
            if (station == null) {
                TransformComponent t = store.getComponent(player, TransformComponent.getComponentType());
                if (t != null) {
                    station = StationMaster.nearestMaster(world.getName(), new Vector3d(t.getPosition()), TransitConstants.MASTER_RELEASE_DISTANCE);
                }
            }
            if (station != null) {
                StationMaster.onTalk(store, player, station);
            }
        });
    }
}
