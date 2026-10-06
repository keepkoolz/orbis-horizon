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

    /** Result: error non-null if nothing was removed, otherwise the number of blocks removed, items dropped and the marker location. */
    record Result(Message error, int removed, int items, Vector3i anchorPos) {
        static Result failure(Message error) {
            return new Result(error, 0, 0, null);
        }
    }

    private StructureRemoval() {
    }

    /**
     * True if the player is an op. Test chosen: membership of the OP_GROUP ("hytale:Admin") group of
     * HytalePermissionsProvider, read by PermissionsModule.getGroupsForUser. It is the group that /op add
     * and /op self give, and the one /orbishorizon balloon despawn requires (T40). hasPermission(uuid, "*") would give the same result
     * for this group (its only permission is "*"), but would also test a "*" right given to a single player.
     */
    static boolean isOp(UUID uuid) {
        try {
            return PermissionsModule.get().getGroupsForUser(uuid).contains(HytalePermissionsProvider.OP_GROUP);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Droits de %s non lus : traité comme non op", uuid);
            return false;
        }
    }

    /** Removes the structure placed at this origin. The registry, blocks, contents and mounts are handled here. */
    static Result remove(ComponentAccessor<EntityStore> accessor, World world, Deployables.Kind kind, BalloonShape shape,
                         Vector3i origin, Rotation rotation) {
        return remove(accessor, world, kind, shape, origin, rotation, false);
    }

    /** Same, broadAttached true removes everything that is not a structural block first (airship prototype). */
    static Result remove(ComponentAccessor<EntityStore> accessor, World world, Deployables.Kind kind, BalloonShape shape,
                         Vector3i origin, Rotation rotation, boolean broadAttached) {
        // Box of the structure, in world cells.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BalloonShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        // a. All the structure's chunks must be loaded (nothing is removed halfway).
        for (int cx = ChunkUtil.chunkCoordinate(minX); cx <= ChunkUtil.chunkCoordinate(maxX); cx++) {
            for (int cz = ChunkUtil.chunkCoordinate(minZ); cz <= ChunkUtil.chunkCoordinate(maxZ); cz++) {
                if (world.getChunkIfLoaded(ChunkUtil.indexChunk(cx, cz)) == null) {
                    return Result.failure(Texts.t(kind.isAirship() ? "airship.approach" : "tent.approach"));
                }
            }
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
        List<StructureContents.Found> contents = StructureContents.takeAll(world, shape, origin, rotation);
        // d. Registry first (the plugin's removals do not go through the lock events).
        BalloonRegistry.remove(world, origin, rotation, kind.id);
        // e. Blocks: the marker is removed with the default options then its particles are cancelled.
        int removed = BalloonManager.removeBlocks(world, shape, origin, rotation, broadAttached);
        // f. Items dropped at the location of their container.
        int items = 0;
        Map<Vector3i, List<com.hypixel.hytale.server.core.inventory.ItemStack>> byPos = new LinkedHashMap<>();
        for (StructureContents.Found f : contents) {
            items += f.stack().getQuantity();
            byPos.computeIfAbsent(f.pos(), k -> new java.util.ArrayList<>()).add(f.stack());
        }
        for (Map.Entry<Vector3i, List<com.hypixel.hytale.server.core.inventory.ItemStack>> e : byPos.entrySet()) {
            BalloonManager.dropItems(accessor, e.getValue(), e.getKey());
        }
        return new Result(null, removed, items, shape.anchor().rotated(rotation).add(origin));
    }
}
