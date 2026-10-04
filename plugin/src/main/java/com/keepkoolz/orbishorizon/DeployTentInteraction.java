package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Interaction "Camping_DeployTent" (T45): places the camping tent from the full crate. Like
 * DeployBalloonInteraction (same JSON fields, same rotation and origin computation), with three differences:
 *
 * 1. Room test before placement: if a tent chunk is not loaded or if a prefab cell contains a
 *    solid block (BlockMaterial.Solid, the pasteKeepingTerrain test), placement is refused with a message and
 *    the Failed state (the Next, so the crate, is not touched, and no block is placed). Grass and plants are overwritten.
 * 2. After placement, the tent is entered in the lock registry (type "tent", owner = the player).
 * 3. The full crate is replaced in place by the empty crate in the held slot. The game's ModifyInventory
 *    does not do this reliably (AdjustHeldItemQuantity then ItemToAdd by addOrDropItemStack, which puts the empty
 *    crate in the first free slot, not necessarily the crate's own), so the swap is done here.
 *
 * JSON fields:
 *   PrefabPath      prefab path, relative to Server/Prefabs/ (the tent's)
 *   Offset          {X,Y,Z} offset applied in the player's frame (negative Z = in front of them)
 *   RotationOffset  quarter turn added to the player's direction (None, Ninety, OneEighty, TwoSeventy)
 *   Force           overwrites existing non-solid blocks
 */
public class DeployTentInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Camping_DeployTent";
    /** Crate identifiers: the full one is held on click, the empty one replaces it. */
    static final String FULL_CRATE = "Camping_Tent_Crate";
    static final String EMPTY_CRATE = "Camping_Tent_Crate_Empty";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<DeployTentInteraction> CODEC = BuilderCodec
            .builder(DeployTentInteraction.class, DeployTentInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Pitches the camping tent facing the player, if there is room, and swaps the held crate for an empty one.")
            .appendInherited(new KeyedCodec<>("PrefabPath", Codec.STRING),
                    (o, v) -> o.prefabPath = v, o -> o.prefabPath, (o, p) -> o.prefabPath = p.prefabPath)
            .add()
            .appendInherited(new KeyedCodec<>("Offset", Vector3iUtil.CODEC),
                    (o, v) -> o.offset = v, o -> o.offset, (o, p) -> o.offset = p.offset)
            .add()
            .appendInherited(new KeyedCodec<>("RotationOffset", Rotation.CODEC),
                    (o, v) -> o.rotationOffset = v, o -> o.rotationOffset, (o, p) -> o.rotationOffset = p.rotationOffset)
            .add()
            .appendInherited(new KeyedCodec<>("Force", Codec.BOOLEAN),
                    (o, v) -> o.force = v, o -> o.force, (o, p) -> o.force = p.force)
            .add()
            .build();

    private String prefabPath;
    private Vector3i offset = new Vector3i();
    private Rotation rotationOffset = Rotation.None;
    private boolean force = true;

    public DeployTentInteraction() {
    }

    /** Like LearnBalloonRecipesInteraction: the interaction changes the inventory, the server decides. */
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
        if (prefabPath == null || !prefabPath.equals(Deployables.TENT.prefabPath)) {
            LOGGER.at(Level.WARNING).log("Camping_DeployTent: PrefabPath inattendu : %s", prefabPath);
            fail(context);
            return;
        }
        Path path = PrefabStore.get().findAssetPrefabPath(prefabPath);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("Camping_DeployTent: prefab not found: %s", prefabPath);
            fail(context);
            return;
        }
        BalloonShape shape;
        try {
            shape = Deployables.TENT.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camping_DeployTent: forme de la tente illisible");
            fail(context);
            return;
        }
        // The crate swap is done in the held slot: without it, nothing is placed (no tent without a swapped crate).
        ItemStack held = context.getHeldItem();
        ItemContainer heldContainer = context.getHeldItemContainer();
        if (held == null || heldContainer == null || !FULL_CRATE.equals(held.getItemId())) {
            LOGGER.at(Level.WARNING).log("Camping_DeployTent: objet tenu inattendu (%s)", held != null ? held.getItemId() : "aucun");
            fail(context);
            return;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);

        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        if (transform == null) {
            LOGGER.at(Level.WARNING).log("Camping_DeployTent: no transform on interacting entity");
            fail(context);
            return;
        }

        // Same computation as DeployBalloonInteraction. Rotation3f.y is the yaw in radians.
        Rotation3f bodyRotation = transform.getRotation();
        Rotation facing = Rotation.closestOfDegrees((float) Math.toDegrees(bodyRotation.y));
        Rotation rotation = facing.add(rotationOffset);

        BlockPosition target = context.getTargetBlock();
        Vector3i origin = target != null
                ? new Vector3i(target.x, target.y, target.z)
                : Vector3dUtil.toVector3i(transform.getPosition());
        Vector3i rotatedOffset = facing.rotateYaw(new Vector3i(offset), new Vector3i());
        origin.add(rotatedOffset);

        // Room test: no prefab cell may contain a solid block, all chunks must be loaded.
        for (BalloonShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(p.x), ChunkUtil.chunkCoordinate(p.z)));
            BlockType bt = chunk != null ? world.getBlockType(p.x, p.y, p.z) : null;
            if (chunk == null || (bt != null && bt.getMaterial() == BlockMaterial.Solid)) {
                LOGGER.at(Level.INFO).log("Tente refusée en (%d, %d, %d) : %s bloquée en (%d, %d, %d)",
                        origin.x, origin.y, origin.z, chunk == null ? "chunk non chargé" : bt.getId(), p.x, p.y, p.z);
                playerRef.sendMessage(Texts.t("tent.noRoom"));
                fail(context);
                return;
            }
        }

        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        PrefabUtil.paste(buffer, world, new Vector3i(origin), rotation, new FastRandom(), flags, 0, commandBuffer);
        BalloonRegistry.add(world, origin, rotation, Deployables.TENT_KIND, playerRef.getUuid());

        // The full crate becomes the empty crate, in the same slot.
        ItemStack empty = new ItemStack(EMPTY_CRATE, 1);
        if (heldContainer.setItemStackForSlot((short) context.getHeldItemSlot(), empty).succeeded()) {
            context.setHeldItem(empty);
        } else {
            LOGGER.at(Level.WARNING).log("Camping_DeployTent: la caisse n'a pas pu être remplacée par la caisse vide");
        }
        LOGGER.at(Level.INFO).log("Tente posée en (%d, %d, %d) rotation %s par %s", origin.x, origin.y, origin.z, rotation, playerRef.getUsername());
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "DeployTentInteraction{prefabPath=" + prefabPath + ", offset=" + offset
                + ", rotationOffset=" + rotationOffset + ", force=" + force + "}";
    }
}
