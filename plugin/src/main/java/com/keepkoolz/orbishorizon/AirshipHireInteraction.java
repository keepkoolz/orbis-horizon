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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Interaction "Airship_HireCrew" (secondary action of the pilot contract, lot 2 T135): hires the NPC pilot of the landed airship
 * whose helm is targeted. Refused if the block is not the helm of a registered airship, if the player is not its owner or if it
 * already has a pilot. The contract is consumed (one of the stack) only once the checks pass; the pilot is created on the world
 * thread and, if that fails, the contract is given back.
 */
public class AirshipHireInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Airship_HireCrew";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<AirshipHireInteraction> CODEC = BuilderCodec
            .builder(AirshipHireInteraction.class, AirshipHireInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Hires the NPC pilot of the landed airship whose helm is targeted, with the held pilot contract.")
            .build();

    public AirshipHireInteraction() {
    }

    /** The interaction changes the inventory, the server decides. */
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        ItemStack held = context.getHeldItem();
        ItemContainer heldContainer = context.getHeldItemContainer();
        if (playerRef == null || held == null || heldContainer == null || !PilotContract.ITEM_ID.equals(held.getItemId())) {
            fail(context);
            return;
        }
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        BlockPosition target = context.getTargetBlock();
        BalloonRegistry.Entry entry = null;
        if (target != null) {
            Vector3i pos = new Vector3i(target.x, target.y, target.z);
            BlockType bt = SectionBlocks.typeAt(new SectionReader(world.getChunkStore()), pos.x, pos.y, pos.z);
            BalloonRegistry.Entry found = BalloonRegistry.find(world, pos);
            if (bt != null && bt.getId() != null && bt.getId().startsWith("Airship_Helm")
                    && found != null && Deployables.AIRSHIP_KIND.equals(found.kind())) {
                entry = found;
            }
        }
        if (entry == null) {
            playerRef.sendMessage(Texts.t("crew.hire.notHelm"));
            fail(context);
            return;
        }
        if (entry.owner() == null || !entry.owner().equals(playerRef.getUuid())) {
            playerRef.sendMessage(Texts.t("crew.hire.notOwner"));
            fail(context);
            return;
        }
        if (entry.crew() != null) {
            playerRef.sendMessage(Texts.t("crew.hire.already"));
            fail(context);
            return;
        }
        // Checks passed: consume one contract, keep the rest of the stack.
        short slot = (short) context.getHeldItemSlot();
        if (!heldContainer.removeItemStackFromSlot(slot, 1, true, false).succeeded()) {
            fail(context);
            return;
        }
        ItemStack left = heldContainer.getItemStack(slot);
        if (left != null) {
            context.setHeldItem(left);
        }
        BalloonRegistry.Entry hired = entry;
        world.execute(() -> hire(world, player, playerRef, hired));
    }

    private static void hire(World world, Ref<EntityStore> player, PlayerRef playerRef, BalloonRegistry.Entry entry) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        UUID uuid = null;
        CrewRecord hired = null;
        BalloonRegistry.Entry current = BalloonRegistry.get(world, origin, entry.rotation(), Deployables.AIRSHIP_KIND);
        if (current != null && current.crew() == null) {
            CrewRecord record = new CrewRecord(null, new java.util.Random().nextLong(), CrewRecord.Post.HELM);
            hired = record;
            uuid = AirshipCrew.spawn(store, world, current, record);
            if (uuid != null) {
                BalloonRegistry.setCrew(world, origin, entry.rotation(), record.withUuid(uuid));
            }
        }
        if (uuid == null) {
            // Not hired: give the contract back.
            LOGGER.at(Level.WARNING).log("Pilote : engagement impossible en (%d, %d, %d), contrat rendu", entry.x(), entry.y(), entry.z());
            try {
                if (player.isValid()) {
                    ItemStack back = new ItemStack(PilotContract.ITEM_ID, 1);
                    com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction t = Player.giveItem(back, player, store);
                    ItemStack rest = t != null ? t.getRemainder() : back;
                    if (rest != null && !rest.isEmpty()) {
                        org.joml.Vector3d at = store.getComponent(player,
                                com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition();
                        BalloonManager.dropItems(store, java.util.List.of(rest), new Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)));
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("Pilote : contrat non rendu");
            }
            playerRef.sendMessage(Texts.t("crew.contract.failed"));
            return;
        }
        playerRef.sendMessage(Texts.pilot("crew.hire.welcome", hired));
        LOGGER.at(Level.INFO).log("Pilote : engagé par %s en (%d, %d, %d)", playerRef.getUsername(), entry.x(), entry.y(), entry.z());
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "AirshipHireInteraction{}";
    }
}
