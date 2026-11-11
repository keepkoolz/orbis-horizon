package com.keepkoolz.orbishorizon;

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
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Interaction "Transit_PlaceArrival": the arrival point plan sets the cell where the travellers of the nearest maritime station
 * (within PlaceQuayInteraction.STATION_REACH of the holder) are set down: the cell above the aimed block. The refusal rules are
 * StationGeometry.arrivalRefusal. Any refusal sets the Failed state, so the Next (ModifyInventory, the plan) is skipped.
 */
public class PlaceArrivalInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Transit_PlaceArrival";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<PlaceArrivalInteraction> CODEC = BuilderCodec
            .builder(PlaceArrivalInteraction.class, PlaceArrivalInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Sets the arrival point of the nearest maritime station to the cell above the aimed block.")
            .build();

    public PlaceArrivalInteraction() {
    }

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    // ------------------------------------------------------------------ pure rules

    /** The arrival cell of an aimed block: the cell above it. Pure. */
    static Vector3i cellAbove(Vector3i aimed) {
        return new Vector3i(aimed.x, aimed.y + 1, aimed.z);
    }

    /**
     * The maritime station nearest to a point within maxDist blocks (its quay anchor, else its master cell), null when there is none.
     * The nearest one owned by the player (any one for an operator) wins, else the nearest of anyone, so a non-owner is still refused
     * by the owner check. Other kinds are ignored. Pure.
     */
    static TransitNetwork.Station nearestBoatStation(List<TransitNetwork.Station> stations, Vector3d at, double maxDist,
                                                     UUID player, boolean op) {
        TransitNetwork.Station best = null;
        double bestD = maxDist * maxDist;
        TransitNetwork.Station bestOwn = null;
        double bestOwnD = maxDist * maxDist;
        for (TransitNetwork.Station s : stations) {
            if (!Deployables.BOAT_STATION_KIND.equals(s.kind)) {
                continue;
            }
            Vector3i p = s.quay != null ? s.quay : s.master;
            double d = new Vector3d(p.x + 0.5, p.y, p.z + 0.5).distanceSquared(at);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
            if ((op || (player != null && player.equals(s.owner))) && d <= bestOwnD) {
                bestOwnD = d;
                bestOwn = s;
            }
        }
        return bestOwn != null ? bestOwn : best;
    }

    // ------------------------------------------------------------------ interaction

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            place(context);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Transit_PlaceArrival failed");
            Telemetry.error("deploy", e, null, null);
            fail(context);
        }
    }

    private void place(InteractionContext context) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        if (playerRef == null || transform == null) {
            fail(context);
            return;
        }
        // Same ray as the held-plan preview, so the point lands where the ghost lantern stood.
        Vector3i ray = TargetUtil.getTargetBlock(player, HeldCratePreview.REACH, commandBuffer);
        BlockPosition clientTarget = context.getTargetBlock();
        Vector3i aimed = ray != null ? ray : clientTarget != null ? new Vector3i(clientTarget.x, clientTarget.y, clientTarget.z) : null;
        if (aimed == null) {
            playerRef.sendMessage(Texts.t("transit.arrival.noTarget"));
            fail(context);
            return;
        }
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        UUID uuid = playerRef.getUuid();
        TransitNetwork.Station station = nearestBoatStation(TransitNetwork.get().stations(world.getName()),
                transform.getPosition(), PlaceQuayInteraction.STATION_REACH, uuid, StructureRemoval.isOp(uuid));
        if (station == null) {
            playerRef.sendMessage(Texts.t("transit.arrival.noStation"));
            fail(context);
            return;
        }
        Vector3i cell = cellAbove(aimed);
        boolean owner = uuid.equals(station.owner) || StructureRemoval.isOp(uuid);
        boolean standable = StationGeometry.standable(world).at(cell.x, cell.y, cell.z);
        String refusal = StationGeometry.arrivalRefusal(cell, station.quay, owner, standable);
        if (refusal != null) {
            playerRef.sendMessage(Texts.t(refusal));
            fail(context);
            return;
        }
        TransitNetwork.get().setArrival(station, cell);
        playerRef.sendMessage(Texts.t("transit.arrival.placed").param("name", station.name));
    }

    /**
     * Hands the arrival point plan to a player and tells them what became of it. World thread. Never throws.
     */
    static void giveArrivalPlan(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player) {
        try {
            StationContracts.Given given = StationContracts.give(store, ref, QuayPlanPage.BOAT_ARRIVAL);
            player.sendMessage(Texts.t(switch (given) {
                case FAILED -> "transit.arrival.planFailed";
                case DROPPED -> "transit.arrival.planGivenDropped";
                case GIVEN -> "transit.arrival.planGiven";
            }));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Arrival point plan not given");
            Telemetry.error("transit", e, null, null);
        }
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "PlaceArrivalInteraction{}";
    }
}
