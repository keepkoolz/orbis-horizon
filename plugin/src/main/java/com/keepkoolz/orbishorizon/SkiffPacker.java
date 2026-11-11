package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Packs a parked skiff back into its crate, the contents of its chests kept in the crate's metadata. Used by the placed crate's
 * Use interaction and by /orbishorizon boat pack. Call on the world thread with the world's Store (not from inside an
 * interaction's CommandBuffer step: defer with world.execute). Never throws.
 */
final class SkiffPacker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Outcome: error non-null if nothing was packed; kept = contents went into the crate; dropped = the crate fell at the player's feet. */
    record Result(Message error, boolean kept, boolean dropped) {
        static Result failure(Message error) {
            return new Result(error, false, false);
        }
    }

    private SkiffPacker() {
    }

    /**
     * Refusal known before the packing hold starts (rights, under way), so the player does not hold for nothing; occupation and the
     * rest are checked again when the hold ends. Null when the hold may start. Never throws (a failure lets the final check decide).
     */
    static String earlyRefusal(World world, BalloonRegistry.Entry entry, UUID self) {
        try {
            boolean owner = entry.owner() != null && entry.owner().equals(self);
            boolean op = !owner && StructureRemoval.isOp(self);
            boolean underway = AirshipManager.get().underwayFrom(world, new Vector3i(entry.x(), entry.y(), entry.z()), entry.kind());
            return refusal(owner, op, underway, false);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Skiff: early packing check failed");
            Telemetry.error("boat.skiff.pack", e, Deployables.STEAMBOAT_SKIFF_KIND, "check");
            return null;
        }
    }

    /**
     * Refusal key (chat key without prefix) or null. Order: rights (owner or op), then under way, then someone else aboard.
     */
    static String refusal(boolean owner, boolean op, boolean underway, boolean othersAboard) {
        if (!owner && !op) {
            return "skiff.notOwner";
        }
        if (underway) {
            return "skiff.underway";
        }
        if (othersAboard) {
            return "skiff.occupied";
        }
        return null;
    }

    /** True if the position is in the world box {minX, minY, minZ, maxX, maxY, maxZ} widened by one cell (3 in height, as StructureRemoval). */
    static boolean inWidenedBox(int[] box, double x, double y, double z) {
        return x >= box[0] - 1 && x <= box[3] + 2 && y >= box[1] - 1 && y <= box[4] + 3 && z >= box[2] - 1 && z <= box[5] + 2;
    }

    /** Crate of the skiff, carrying the kept contents as metadata when there are any. */
    static ItemStack crateStack(TentContents contents) {
        ItemStack stack = new ItemStack(AirshipManager.spec(Deployables.STEAMBOAT_SKIFF).crateItemId, 1);
        if (contents != null) {
            stack = stack.withMetadata(TentContents.METADATA_KEY, contents.toBson(Deployables.STEAMBOAT_SKIFF_KIND));
        }
        return stack;
    }

    private static Result refuse(PlayerRef playerRef, String key) {
        Message m = Texts.t(key);
        playerRef.sendMessage(m);
        return Result.failure(m);
    }

    /**
     * Packs the skiff of this registry entry for the player. checkRights: only the owner or an op may pack. Never throws.
     */
    static Result pack(Store<EntityStore> store, Ref<EntityStore> player, World world, BalloonRegistry.Entry entry, boolean checkRights) {
        PlayerRef playerRef = null;
        try {
            playerRef = store.getComponent(player, PlayerRef.getComponentType());
            Deployables.Kind kind = Deployables.get(entry.kind());
            if (playerRef == null || kind != Deployables.STEAMBOAT_SKIFF) {
                return Result.failure(Texts.t("skiff.notASkiff"));
            }
            Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
            // Deferred call: the skiff may already have been packed (two presses in the same tick). Fail quietly.
            if (BalloonRegistry.get(world, origin, entry.rotation(), kind.id) == null) {
                return Result.failure(Texts.t("skiff.notASkiff"));
            }
            UUID self = playerRef.getUuid();
            boolean owner = entry.owner() != null && entry.owner().equals(self);
            boolean op = !owner && StructureRemoval.isOp(self);
            boolean underway = AirshipManager.get().underwayFrom(world, origin, kind.id);
            StructureShape shape;
            try {
                shape = kind.shape();
            } catch (IOException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Skiff: unreadable shape");
                Telemetry.error("boat.skiff.pack", e, Deployables.STEAMBOAT_SKIFF_KIND, null);
                return Result.failure(Texts.t("skiff.notASkiff"));
            }
            int[] box = worldBox(shape, origin, entry);
            // Rights and under way come before anything else (the chunk test only matters for a legitimate pack).
            String early = refusal(owner || !checkRights, op || !checkRights, underway, false);
            if (early != null) {
                return refuse(playerRef, early);
            }
            if (!SectionBlocks.sectionsLoaded(world.getChunkStore(), box[0], box[1], box[2], box[3], box[4], box[5])) {
                return refuse(playerRef, "skiff.approach");
            }
            // Sections are loaded: an absent anchor means the registry entry is orphaned. It is dropped and no crate is given.
            if (!BalloonRegistry.stillThere(world, shape, entry)) {
                BalloonRegistry.remove(entry);
                return refuse(playerRef, "skiff.notASkiff");
            }
            boolean others = false;
            for (PlayerRef other : world.getPlayerRefs()) {
                Ref<EntityStore> ref = other.getReference();
                if (ref == null || !ref.isValid() || other.getUuid().equals(self)) {
                    continue;
                }
                // Standing in the widened box or seated on one of the skiff's blocks: both are read from the position.
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                if (t != null) {
                    Vector3d p = t.getPosition();
                    if (inWidenedBox(box, p.x, p.y, p.z)) {
                        others = true;
                        break;
                    }
                }
            }
            String refusal = refusal(owner || !checkRights, op || !checkRights, underway, others);
            if (refusal != null) {
                return refuse(playerRef, refusal);
            }
            if (!StructureComponents.unreadable(world, shape, origin, entry.rotation()).isEmpty()) {
                return refuse(playerRef, "skiff.unreadable");
            }
            StructureRemoval.Result removal = StructureRemoval.remove(store, world, kind, shape, origin, entry.rotation(), false, true, false);
            if (removal.error() != null) {
                playerRef.sendMessage(removal.error());
                return Result.failure(removal.error());
            }
            Integer line = AirshipManager.spec(kind).waterlineY;
            if (line != null) {
                SteamboatWater.flood(world, shape, origin, entry.rotation(), line);
            }
            boolean kept = removal.kept() != null && removal.items() > 0;
            boolean inInventory = CampPacker.giveOrDrop(store, player, crateStack(removal.kept()));
            if (removal.kept() != null && !removal.kept().ejected().isEmpty()) {
                for (ItemStack ejected : removal.kept().ejected()) {
                    CampPacker.giveOrDrop(store, player, ejected);
                }
                playerRef.sendMessage(Texts.t("skiff.packInChest"));
            }
            playerRef.sendMessage(Texts.t(!inInventory ? "skiff.droppedAtFeet" : kept ? "skiff.packedKept" : "skiff.packed"));
            Telemetry.count("boat.skiff.pack");
            return new Result(null, kept, !inInventory);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Skiff: packing failed");
            Telemetry.error("boat.skiff.pack", e, Deployables.STEAMBOAT_SKIFF_KIND, null);
            return Result.failure(Texts.t("skiff.notASkiff"));
        }
    }

    /** World box {minX, minY, minZ, maxX, maxY, maxZ} of the placed prefab. */
    private static int[] worldBox(StructureShape shape, Vector3i origin, BalloonRegistry.Entry entry) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (StructureShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(entry.rotation()).add(origin);
            b[0] = Math.min(b[0], p.x);
            b[1] = Math.min(b[1], p.y);
            b[2] = Math.min(b[2], p.z);
            b[3] = Math.max(b[3], p.x);
            b[4] = Math.max(b[4], p.y);
            b[5] = Math.max(b[5], p.z);
        }
        return b;
    }
}
