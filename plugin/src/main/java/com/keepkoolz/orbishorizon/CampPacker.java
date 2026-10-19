package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * T154: packs a deployed camp (sleeping bag, small or large tent) into its pack item, contents kept in the item's metadata.
 * Used by the placed bag's Use interaction and by the sleeping bag's automatic packing when its owner wakes after a full night.
 * Call on the world thread with the world's Store (not from inside an interaction's CommandBuffer step: defer with world.execute).
 */
final class CampPacker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Outcome: error non-null if nothing was packed; kept = contents went into the pack; dropped = the pack fell at the player's feet. */
    record Result(Message error, boolean kept, boolean dropped) {
        static Result failure(Message error) {
            return new Result(error, false, false);
        }
    }

    private CampPacker() {
    }

    /** True if a pack can be received into an inventory with this many free slots. */
    static boolean fits(int freeSlots) {
        return freeSlots > 0;
    }

    /** Only the sleeping bag packs itself, only after a full night, only for the player who owns it. */
    static boolean shouldAutoPack(boolean fullNight, boolean sleeperIsOwner, String kindId) {
        return fullNight && sleeperIsOwner && Deployables.BEDROLL_KIND.equals(kindId);
    }

    /** The pack item of a tier, carrying the kept contents as metadata when there are any. */
    static ItemStack packStack(CampTiers.Tier tier, TentContents contents) {
        ItemStack stack = new ItemStack(tier.packItemId(), 1);
        if (contents != null) {
            stack = stack.withMetadata(TentContents.METADATA_KEY, contents.toBson(tier.kindId()));
        }
        return stack;
    }

    /** Puts the stack in the player's inventory (Player.giveItem), otherwise drops it at their feet. True if it is in the inventory. */
    static boolean giveOrDrop(Store<EntityStore> store, Ref<EntityStore> player, ItemStack stack) {
        ItemStack rest = stack;
        try {
            ItemStackTransaction t = Player.giveItem(stack, player, store);
            rest = t != null ? t.getRemainder() : stack;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Sac non donné au joueur, lâché au sol");
            Telemetry.error("pack", e, null, null);
        }
        if (rest != null && !rest.isEmpty()) {
            TransformComponent tr = store.getComponent(player, TransformComponent.getComponentType());
            Vector3d at = tr != null ? tr.getPosition() : new Vector3d();
            BalloonManager.dropItems(store, List.of(rest), new Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)));
            return false;
        }
        return true;
    }

    /**
     * Packs the camp of this registry entry for the player. checkRights: only the owner or an op may pack (the automatic packing
     * of the sleeping bag passes false, it already checked the owner). Never throws.
     */
    static Result pack(Store<EntityStore> store, Ref<EntityStore> player, World world, BalloonRegistry.Entry entry, boolean checkRights) {
        try {
            PlayerRef playerRef = store.getComponent(player, PlayerRef.getComponentType());
            Deployables.Kind kind = Deployables.get(entry.kind());
            CampTiers.Tier tier = CampTiers.ofKind(entry.kind());
            if (playerRef == null || kind == null || tier == null) {
                return Result.failure(Texts.t("camp.notACamp"));
            }
            if (checkRights) {
                boolean owner = entry.owner() != null && entry.owner().equals(playerRef.getUuid());
                if (!owner && !StructureRemoval.isOp(playerRef.getUuid())) {
                    Message m = Texts.t("camp.notOwner");
                    playerRef.sendMessage(m);
                    return Result.failure(m);
                }
            }
            StructureShape shape;
            try {
                shape = kind.shape();
            } catch (IOException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Campement : forme illisible");
                Telemetry.error("pack", e, null, null);
                return Result.failure(Texts.t("camp.notACamp"));
            }
            Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
            // Deferred call: the camp may already have been packed (two presses in the same tick). Fail quietly.
            Vector3i bag = entry.rotation().rotateYaw(CampTiers.bagCell(tier), new Vector3i()).add(origin);
            if (BalloonRegistry.find(world, bag) == null) {
                return Result.failure(Texts.t("camp.notACamp"));
            }
            StructureRemoval.Result removal = StructureRemoval.remove(store, world, kind, shape, origin, entry.rotation(), false, true);
            if (removal.error() != null) {
                playerRef.sendMessage(removal.error());
                return Result.failure(removal.error());
            }
            CampRespawn.afterRemoval(world, entry);
            boolean kept = removal.kept() != null && removal.items() > 0;
            boolean inInventory = giveOrDrop(store, player, packStack(tier, removal.kept()));
            if (removal.ejected() > 0) {
                playerRef.sendMessage(Texts.t("camp.packInChest"));
            }
            playerRef.sendMessage(Texts.t(!inInventory ? "camp.droppedAtFeet" : kept ? "camp.packedKept" : "camp.packed"));
            LOGGER.at(Level.INFO).log("Campement « %s » rangé par %s en (%d, %d, %d) : %d bloc(s) retirés%s",
                    kind.id, playerRef.getUsername(), origin.x, origin.y, origin.z, removal.removed(),
                    kept ? ", " + removal.items() + " objet(s) gardés dans le sac" : "");
            return new Result(null, kept, !inInventory);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Campement : rangement en échec");
            Telemetry.error("pack", e, null, null);
            return Result.failure(Texts.t("camp.notACamp"));
        }
    }
}
