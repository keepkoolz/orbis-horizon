package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.logging.Level;

/**
 * Interaction "Transit_PlaceMaster": the station contract places the station master on the targeted block. The station then
 * exists without a quay (pending): its master offers the quay plan to the owner (QuayPlanPage).
 *
 * Order: kind, target block, standable cell above it, distance to the stations of the same kind (with or without a quay), then
 * the station is registered and its master created (deferred to the world thread). Any refusal sets the Failed state, so the Next
 * (ModifyInventory, the contract) is skipped.
 *
 * JSON field: StationKind, Deployables.STATION_KIND or Deployables.BOAT_STATION_KIND.
 */
public class PlaceMasterInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Transit_PlaceMaster";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<PlaceMasterInteraction> CODEC = BuilderCodec
            .builder(PlaceMasterInteraction.class, PlaceMasterInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Places the master of a transit station on the targeted block, if the spot is free and no station of the same type is too close.")
            .appendInherited(new KeyedCodec<>("StationKind", Codec.STRING),
                    (o, v) -> o.stationKind = v, o -> o.stationKind, (o, p) -> o.stationKind = p.stationKind)
            .add()
            .build();

    private String stationKind;

    public PlaceMasterInteraction() {
    }

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** The station of the list closest to the spot that is under the minimum distance, null if none. Every station counts from its master cell. Pure. */
    static TransitNetwork.Station closest(List<TransitNetwork.Station> sameKind, Vector3i at) {
        double min = TransitConstants.MIN_STATION_DISTANCE;
        TransitNetwork.Station best = null;
        double bestD = min * min;
        for (TransitNetwork.Station s : sameKind) {
            Vector3i ref = s.master;
            double d = new Vector3d(ref.x, ref.y, ref.z).distanceSquared(at.x, at.y, at.z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** Message key of the refusal when a station of the same kind is under 64 blocks from the spot, null otherwise. Pure. */
    static String refusal(List<TransitNetwork.Station> sameKind, Vector3i at) {
        return closest(sameKind, at) != null ? "transit.deploy.tooClose" : null;
    }

    /** Yaw (degrees, StationMaster.facingYaw convention) that turns a master at the cell towards a point. Pure. */
    static float yawToward(Vector3i master, double x, double z) {
        double dx = x - (master.x + 0.5);
        double dz = z - (master.z + 0.5);
        if (dx * dx + dz * dz < 1e-6) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, -dz));
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            place(context);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Transit_PlaceMaster failed");
            Telemetry.error("deploy", e, stationKind, null);
            fail(context);
        }
    }

    private void place(InteractionContext context) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        BlockPosition target = context.getTargetBlock();
        boolean known = Deployables.STATION_KIND.equals(stationKind) || Deployables.BOAT_STATION_KIND.equals(stationKind);
        if (playerRef == null || transform == null || !known) {
            LOGGER.at(Level.WARNING).log("Transit_PlaceMaster: unexpected StationKind: %s", stationKind);
            fail(context);
            return;
        }
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        if (target == null) {
            playerRef.sendMessage(Texts.t("transit.master.badSpot"));
            fail(context);
            return;
        }
        Vector3i cell = new Vector3i(target.x, target.y + 1, target.z);
        if (!StationGeometry.standable(world).at(cell.x, cell.y, cell.z)) {
            playerRef.sendMessage(Texts.t("transit.master.badSpot"));
            fail(context);
            return;
        }
        TransitNetwork net = TransitNetwork.get();
        List<TransitNetwork.Station> sameKind = new java.util.ArrayList<>();
        for (TransitNetwork.Station s : net.stations(world.getName())) {
            if (s.kind.equals(stationKind)) sameKind.add(s);
        }
        TransitNetwork.Station tooClose = closest(sameKind, cell);
        if (tooClose != null) {
            playerRef.sendMessage(Texts.t("transit.deploy.tooClose").param("name", tooClose.name));
            fail(context);
            return;
        }
        Vector3d at = transform.getPosition();
        float yaw = yawToward(cell, at.x, at.z);
        TransitNetwork.Station station = net.addPending(world.getName(), cell, yaw, playerRef.getUuid(), stationKind);
        Telemetry.count("station.masterPlaced");
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                StationMaster.spawn(store, world, station);
                playerRef.sendMessage(Texts.t("transit.master.placed").param("name", station.name));
                // The quay plan window opens at once for the placer (the same window as a talk to the master).
                if (player.isValid()) {
                    StationMaster.onTalk(store, player, station);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station master of '%s' not created", station.name);
                Telemetry.error("deploy", e, stationKind, null);
            }
        });
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "PlaceMasterInteraction{stationKind=" + stationKind + "}";
    }
}
