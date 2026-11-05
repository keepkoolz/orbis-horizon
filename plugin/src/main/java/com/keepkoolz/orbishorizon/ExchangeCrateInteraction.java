package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * Interaction "Transit_ExchangeCrate" (secondary action of the old station crates): replaces the held crate by the station
 * contract of its type. On the world thread the contract is given first, then one crate of the stack is taken: if the contract
 * cannot be given the crate stays where it is.
 */
public class ExchangeCrateInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Transit_ExchangeCrate";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<ExchangeCrateInteraction> CODEC = BuilderCodec
            .builder(ExchangeCrateInteraction.class, ExchangeCrateInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Exchanges the held old station crate for the station contract of its type.")
            .build();

    public ExchangeCrateInteraction() {
    }

    /** The interaction changes the inventory, the server decides. */
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            Ref<EntityStore> player = context.getEntity();
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            ItemStack held = context.getHeldItem();
            ItemContainer heldContainer = context.getHeldItemContainer();
            String kind = held == null ? null : StationContracts.kindOfCrate(held.getItemId());
            if (playerRef == null || heldContainer == null || kind == null) {
                fail(context);
                return;
            }
            String crateId = held.getItemId();
            short slot = (short) context.getHeldItemSlot();
            World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
            world.execute(() -> exchange(heldContainer, slot, player, playerRef, kind, crateId));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station crate exchange failed");
            Telemetry.error("station.contract", e, null, null);
            fail(context);
        }
    }

    /**
     * On the world thread: the contract is given first, and only then is one crate taken from its slot. The slot is read again
     * just before (the crate must still be there), so the crate is never lost and never kept together with the contract.
     */
    private static void exchange(ItemContainer container, short slot, Ref<EntityStore> player, PlayerRef playerRef, String kind,
                                 String crateId) {
        try {
            if (!player.isValid()) return;
            ItemStack now = container.getItemStack(slot);
            if (now == null || now.isEmpty() || !crateId.equals(now.getItemId())) return;
            Store<EntityStore> store = player.getStore();
            StationContracts.Given given = StationContracts.give(store, player, StationContracts.itemFor(kind));
            if (!given.reached()) {
                playerRef.sendMessage(Texts.t("transit.contract.failed"));
                return;
            }
            if (!container.removeItemStackFromSlot(slot, 1, true, false).succeeded()) {
                LOGGER.at(Level.WARNING).log("Station crate exchange: the crate of %s could not be taken after the contract was given",
                        playerRef.getUsername());
                Telemetry.error("station.contract", new IllegalStateException("crate not removed"), null, null);
            }
            playerRef.sendMessage(Texts.t(given == StationContracts.Given.DROPPED ? "transit.crate.exchangedDropped" : "transit.crate.exchanged"));
            LOGGER.at(Level.INFO).log("Station crate exchanged for a contract by %s", playerRef.getUsername());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station crate exchange failed");
            Telemetry.error("station.contract", e, null, null);
        }
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "ExchangeCrateInteraction{}";
    }
}
