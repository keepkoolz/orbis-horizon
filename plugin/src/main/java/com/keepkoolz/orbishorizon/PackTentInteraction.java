package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockPosition;
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
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Interaction "Camping_PackTent" (T46): right click with the empty crate on a placed tent, the tent is packed and
 * the empty crate becomes full again, in the same slot (the DeployTentInteraction swap in the other direction).
 *
 * The targeted block must belong to a tent in the registry (BalloonRegistry.find, any tent kind). Only the player who
 * placed it (owner of the entry) or an op packs it, an entry without an owner is only packed by an op. The removal
 * (chunks loaded, players dismounted, campfire contents dropped, blocks removed) is in StructureRemoval.
 * A refusal sets the Failed state and nothing is changed.
 */
public class PackTentInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Camping_PackTent";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<PackTentInteraction> CODEC = BuilderCodec
            .builder(PackTentInteraction.class, PackTentInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Packs the targeted camping tent away if the player owns it (or is an operator), and swaps the held empty crate for a full one.")
            .build();

    public PackTentInteraction() {
    }

    /** Like DeployTentInteraction: the interaction changes the inventory, the server decides. */
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        if (playerRef == null) {
            fail(context);
            return;
        }
        ItemStack held = context.getHeldItem();
        ItemContainer heldContainer = context.getHeldItemContainer();
        if (held == null || heldContainer == null || !isEmptyCrate(held.getItemId())) {
            LOGGER.at(Level.WARNING).log("Camping_PackTent: objet tenu inattendu (%s)", held != null ? held.getItemId() : "aucun");
            fail(context);
            return;
        }
        BlockPosition target = context.getTargetBlock();
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        BalloonRegistry.Entry entry = target != null ? BalloonRegistry.find(world, new Vector3i(target.x, target.y, target.z)) : null;
        Deployables.Kind kind = entry != null ? Deployables.get(entry.kind()) : null;
        if (kind == null || !kind.isTent()) {
            playerRef.sendMessage(Texts.t("tent.aimTent"));
            fail(context);
            return;
        }
        // The empty crate must be that of the targeted tent type (small crate for the small tent, big for the big).
        if (!kind.tent.emptyCrateId.equals(held.getItemId())) {
            playerRef.sendMessage(Texts.t("tent.wrongCrate"));
            fail(context);
            return;
        }
        // Permissions: the entry owner or an op. Without an owner (old entry), only an op.
        boolean owner = entry.owner() != null && entry.owner().equals(playerRef.getUuid());
        if (!owner && !StructureRemoval.isOp(playerRef.getUuid())) {
            playerRef.sendMessage(Texts.t("tent.notOwner"));
            fail(context);
            return;
        }
        BalloonShape shape;
        try {
            shape = kind.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camping_PackTent: forme de la tente illisible");
            fail(context);
            return;
        }
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        StructureRemoval.Result result = StructureRemoval.remove(commandBuffer, world, kind, shape, origin, entry.rotation());
        if (result.error() != null) {
            playerRef.sendMessage(result.error());
            fail(context);
            return;
        }

        // The empty crate becomes the full crate again, in the same slot. If the swap fails, the full crate
        // is dropped at the foot of the campfire: the tent is already removed, the crate must not be lost.
        ItemStack full = new ItemStack(kind.tent.fullCrateId, 1);
        if (heldContainer.setItemStackForSlot((short) context.getHeldItemSlot(), full).succeeded()) {
            context.setHeldItem(full);
        } else {
            LOGGER.at(Level.WARNING).log("Camping_PackTent: la caisse n'a pas pu être remplacée, caisse pleine lâchée au sol");
            BalloonManager.dropItems(commandBuffer, List.of(full), result.anchorPos());
        }
        playerRef.sendMessage(Texts.t(result.items() > 0 ? "tent.packedDropped" : "tent.packed"));
        LOGGER.at(Level.INFO).log("Tente « %s » rangée par %s en (%d, %d, %d) : %d bloc(s) retirés, %d objet(s) lâchés",
                kind.id, playerRef.getUsername(), origin.x, origin.y, origin.z, result.removed(), result.items());
    }

    /** True if this item is the empty crate of some tent type. */
    private static boolean isEmptyCrate(String itemId) {
        for (Deployables.Kind k : Deployables.TENTS) {
            if (k.tent.emptyCrateId.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "PackTentInteraction{}";
    }
}
