package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Removal of a placed structure (T46, first use: the tent), in the order of BalloonManager.despawnPosed
 * (T40): chunks loaded, players mounted on a block dismounted, contents copied then emptied (StructureContents), registry
 * entry removed, blocks removed (BalloonManager.removeBlocks), items dropped. A single way of dropping, the
 * plugin's. The crate and the messages are the caller's business. Call on the world thread, with a valid
 * ComponentAccessor (the interaction's CommandBuffer).
 */
final class StructureRemoval {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Result: error non-null if nothing was removed, otherwise the number of blocks removed, items dropped or kept in the crate and the marker location. */
    record Result(Message error, int removed, int items, Vector3i anchorPos, TentContents kept, int ejected) {
        Result(Message error, int removed, int items, Vector3i anchorPos, TentContents kept) {
            this(error, removed, items, anchorPos, kept, 0);
        }

        static Result failure(Message error) {
            return new Result(error, 0, 0, null, null);
        }
    }

    private StructureRemoval() {
    }

    /**
     * True if the player is an op. Test chosen: membership of the GROUP_ADMIN ("hytale:Admin") group of
     * HytalePermissionsProvider, read by PermissionsModule.getGroupsForUser. It is the group that /op add
     * and /op self give, and the one /orbishorizon balloon despawn requires (T40). hasPermission(uuid, "*") would give the same result
     * for this group (its only permission is "*"), but would also test a "*" right given to a single player.
     */
    static boolean isOp(UUID uuid) {
        try {
            return PermissionsModule.get().getGroupsForUser(uuid).contains(HytalePermissionsProvider.GROUP_ADMIN);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Droits de %s non lus : traité comme non op", uuid);
            return false;
        }
    }

    /**
     * T154: whether a cell may be cleared. A cell that is not the camp's placed bag follows the usual rule (it is cleared, the caller
     * only offers cells it already matched); the bag cell is cleared only when the world block there really is the placed bag.
     */
    static boolean removeCell(boolean isBagCell, boolean worldMatchesPrefab) {
        return !isBagCell || worldMatchesPrefab;
    }

    /** Removes the structure placed at this origin. The registry, blocks, contents and mounts are handled here. */
    static Result remove(ComponentAccessor<EntityStore> accessor, World world, Deployables.Kind kind, StructureShape shape,
                         Vector3i origin, Rotation rotation) {
        return remove(accessor, world, kind, shape, origin, rotation, false);
    }

    /** Same, broadAttached true removes everything that is not a structural block first (airship prototype). */
    static Result remove(ComponentAccessor<EntityStore> accessor, World world, Deployables.Kind kind, StructureShape shape,
                         Vector3i origin, Rotation rotation, boolean broadAttached) {
        return remove(accessor, world, kind, shape, origin, rotation, broadAttached, false);
    }

    /** Same, keepContents true captures the contents for the crate (tent packing) instead of dropping them. */
    static Result remove(ComponentAccessor<EntityStore> accessor, World world, Deployables.Kind kind, StructureShape shape,
                         Vector3i origin, Rotation rotation, boolean broadAttached, boolean keepContents) {
        // Box of the structure, in world cells.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructureShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        // a. All the structure's chunks must be loaded (nothing is removed halfway).
        if (!SectionBlocks.sectionsLoaded(world.getChunkStore(), minX, minY, minZ, maxX, maxY, maxZ)) {
            return Result.failure(Texts.t(kind.isAirship() ? "airship.approach" : CampTiers.ofKind(kind.id) != null ? "camp.approach" : "tent.approach"));
        }
        // a bis. Packing keeps the contents: a chest or bench whose component cannot be read would lose them.
        if (keepContents && !StructureComponents.unreadable(world, shape, origin, rotation).isEmpty()) {
            return Result.failure(Texts.t("structure.componentsMissing"));
        }
        // b. Players mounted on a block (stool, bed) near the structure are dismounted. The game does this too
        // when the block disappears (MountSystems$RemoveBlockSeat), this leaves no mount on a removed block.
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }
            MountedComponent mounted = accessor.getComponent(ref, MountedComponent.getComponentType());
            TransformComponent t = accessor.getComponent(ref, TransformComponent.getComponentType());
            if (mounted == null || mounted.getMountedToBlock() == null || t == null) {
                continue;
            }
            Vector3d pos = t.getPosition();
            if (pos.x >= minX - 1 && pos.x <= maxX + 2 && pos.y >= minY - 1 && pos.y <= maxY + 3
                    && pos.z >= minZ - 1 && pos.z <= maxZ + 2) {
                MountUtil.unseat(accessor, ref);
            }
        }
        // c. Contents copied then emptied, before the blocks are removed.
        TentContents kept = null;
        List<StructureContents.Found> contents = List.of();
        if (keepContents) {
            kept = TentContents.capture(world, shape, origin, rotation);
        } else {
            contents = StructureContents.takeAll(world, shape, origin, rotation);
        }
        // d. Registry first (the plugin's removals do not go through the lock events).
        BalloonRegistry.remove(world, origin, rotation, kind.id);
        // e. Blocks: the marker is removed with the default options then its particles are cancelled.
        int removed = BalloonManager.removeBlocks(world, shape, origin, rotation, broadAttached);
        // f. Items dropped at the location of their container.
        int items = kept != null ? kept.itemCount() : 0;
        Map<Vector3i, List<com.hypixel.hytale.server.core.inventory.ItemStack>> byPos = new LinkedHashMap<>();
        for (StructureContents.Found f : contents) {
            items += f.stack().getQuantity();
            byPos.computeIfAbsent(f.pos(), k -> new java.util.ArrayList<>()).add(f.stack());
        }
        for (Map.Entry<Vector3i, List<com.hypixel.hytale.server.core.inventory.ItemStack>> e : byPos.entrySet()) {
            BalloonManager.dropItems(accessor, e.getValue(), e.getKey());
        }
        int ejectedCount = 0;
        if (kept != null && !kept.ejected().isEmpty()) {
            Vector3i at = kept.ejectedPos() != null ? rotation.rotateYaw(kept.ejectedPos(), new Vector3i()).add(origin) : shape.anchor().rotated(rotation).add(origin);
            for (com.hypixel.hytale.server.core.inventory.ItemStack st : kept.ejected()) {
                ejectedCount += st.getQuantity();
            }
            BalloonManager.dropItems(accessor, kept.ejected(), at);
        }
        return new Result(null, removed, items, shape.anchor().rotated(rotation).add(origin), kept, ejectedCount);
    }
}
