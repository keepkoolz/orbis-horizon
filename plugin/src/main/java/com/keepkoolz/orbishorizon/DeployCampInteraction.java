package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
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
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
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
 * Interaction "Camping_DeployCamp": places a camp (sleeping bag, small or large tent) from its pack. Like
 * DeployBalloonInteraction (same JSON fields, same rotation and origin computation), with three differences:
 *
 * 1. Room test before placement: if a tent chunk is not loaded or if a prefab cell contains a
 *    solid block (BlockMaterial.Solid, the pasteKeepingTerrain test), placement is refused with a message and
 *    the Failed state (the pack is not touched, and no block is placed). Grass and plants are overwritten.
 * 2. After placement, the tent is entered in the lock registry (tent type, owner = the player).
 * 3. The held pack is consumed here (no empty crate any more: the placed bag packs the camp).
 * A camping pack is pitched only while crouching. Standing, a tent pack opens its chests (PackChests) and the bedroll pack
 * sends a reminder. Neither places anything.
 *
 * JSON fields:
 *   PrefabPath      prefab path, relative to Server/Prefabs/ (the tent's)
 *   Offset          {X,Y,Z} offset applied in the player's frame (negative Z = in front of them)
 *   RotationOffset  quarter turn added to the player's direction (None, Ninety, OneEighty, TwoSeventy)
 *   Force           overwrites existing non-solid blocks
 */
public class DeployCampInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Camping_DeployCamp";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<DeployCampInteraction> CODEC = BuilderCodec
            .builder(DeployCampInteraction.class, DeployCampInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Pitches the camping tent facing the player, if there is room, and consumes the held pack.")
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

    public DeployCampInteraction() {
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
        if (playerRef == null) {
            fail(context);
            return;
        }
        // The camp type comes from the prefab (sleeping bag, small or large tent): it gives the shape and the registry kind.
        Deployables.Kind kind = prefabPath != null ? Deployables.forPrefab(prefabPath) : null;
        if (kind == null || !kind.isTent()) {
            LOGGER.at(Level.WARNING).log("Camping_DeployCamp: unexpected PrefabPath: %s", prefabPath);
            fail(context);
            return;
        }
        Path path = PrefabStore.get().findAssetPrefabPath(prefabPath);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("Camping_DeployCamp: prefab not found: %s", prefabPath);
            fail(context);
            return;
        }
        StructureShape shape;
        try {
            shape = kind.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camping_DeployCamp: unreadable tent shape");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            fail(context);
            return;
        }
        // The held item must be the pack of this camp type.
        ItemStack held = context.getHeldItem();
        ItemContainer heldContainer = context.getHeldItemContainer();
        CampTiers.Tier tier = held != null ? CampTiers.ofPack(held.getItemId()) : null;
        if (held == null || heldContainer == null || tier == null || !tier.kindId().equals(kind.id)) {
            LOGGER.at(Level.WARNING).log("Camping_DeployCamp: unexpected held item (%s)", held != null ? held.getItemId() : "none");
            fail(context);
            return;
        }
        // A camping pack is pitched only while crouching. Standing, a tent pack opens the chests kept in it and the
        // bedroll pack reminds the player to crouch. Nothing is placed and the pack is not touched.
        boolean crouching = false;
        try {
            MovementStatesComponent movement = commandBuffer.getComponent(player, MovementStatesComponent.getComponentType());
            crouching = movement != null && movement.getMovementStates() != null && movement.getMovementStates().crouching;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camping_DeployCamp: crouch check failed");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
        }
        if (CampTiers.pitchedOnlyCrouching(held.getItemId()) && !crouching) {
            if (!PackChests.open(context, kind, shape)) {
                if (Deployables.BEDROLL_KIND.equals(kind.id)) {
                    playerRef.sendMessage(Texts.t("camp.crouchToPitch"));
                }
                fail(context);
            }
            return;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);

        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        if (transform == null) {
            LOGGER.at(Level.WARNING).log("Camping_DeployCamp: no transform on interacting entity");
            fail(context);
            return;
        }

        // same computation as the held-crate preview (shared helper). Rotation3f.y is the yaw in radians.
        BlockPosition target = context.getTargetBlock();
        DeployPlacement.Placement placement = DeployPlacement.compute(
                target != null ? new Vector3i(target.x, target.y, target.z) : null,
                transform.getPosition(), transform.getRotation().y, offset, rotationOffset);
        Rotation rotation = placement.rotation();
        Vector3i origin = placement.origin();

        // No deployment under water.
        Vector3i wet = BalloonManager.underLiquid(world, shape, origin, rotation);
        if (wet != null) {
            LOGGER.at(Level.INFO).log("Camp refused at (%d, %d, %d): liquid at (%d, %d, %d)", origin.x, origin.y, origin.z, wet.x, wet.y, wet.z);
            playerRef.sendMessage(Texts.t("deploy.underwater"));
            fail(context);
            return;
        }
        String zoneStation = StationZone.blockingStation(world, kind.id, shape, origin, rotation);
        if (zoneStation != null) {
            LOGGER.at(Level.INFO).log("Camp refused at (%d, %d, %d): station zone %s", origin.x, origin.y, origin.z, zoneStation);
            playerRef.sendMessage(Texts.t("deploy.stationZone").param("station", zoneStation));
            fail(context);
            return;
        }
        // Room test: no prefab cell may contain a solid block, all chunks must be loaded.
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (StructureShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            if (BalloonManager.outOfHeight(p.y)) {
                // a cell outside the world's height range would be lost.
                LOGGER.at(Level.INFO).log("Camp refused at (%d, %d, %d): cell (%d, %d, %d) exceeds the height limit",
                        origin.x, origin.y, origin.z, p.x, p.y, p.z);
                playerRef.sendMessage(Texts.t("deploy.heightLimit"));
                fail(context);
                return;
            }
            boolean unloaded = !reader.hasStorageInMemory(p.x, p.y, p.z);
            BlockType bt = unloaded ? null : SectionBlocks.typeAt(reader, p.x, p.y, p.z);
            if (unloaded || (bt != null && bt.getMaterial() == BlockMaterial.Solid)) {
                LOGGER.at(Level.INFO).log("Camp refused at (%d, %d, %d): %s blocked at (%d, %d, %d)",
                        origin.x, origin.y, origin.z, unloaded ? "chunk not loaded" : bt.getId(), p.x, p.y, p.z);
                playerRef.sendMessage(Texts.t("tent.noRoom"));
                fail(context);
                return;
            }
        }

        // The pack is consumed from the held slot first: if that fails nothing is placed (no extra pack can be obtained).
        boolean swapped = heldContainer.removeItemStackFromSlot((short) context.getHeldItemSlot(), 1).succeeded();
        if (!swapped) {
            LOGGER.at(Level.WARNING).log("Camping_DeployCamp: bag could not be removed from the hand, nothing placed");
            fail(context);
            return;
        }
        if (held.getQuantity() > 1) {
            context.setHeldItem(held.withQuantity(held.getQuantity() - 1));
        }
        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        PrefabUtil.paste(buffer, world, new Vector3i(origin), rotation, new FastRandom(), flags, 0, commandBuffer);
        BalloonRegistry.add(world, origin, rotation, kind.id, Ownership.of(playerRef.getUuid(), playerRef.getUsername()));
        Telemetry.count("deploy." + kind.id);
        CampChestFilter.installWithRetry(world, shape, origin, rotation);

        // The pack may carry the contents kept when the camp was packed: put them back (deferred out of the
        // interaction; the benches and chests retry for 10 s if their components are not created yet).
        try {
            org.bson.BsonDocument meta = held.getMetadata();
            if (meta != null && meta.containsKey(TentContents.METADATA_KEY) && meta.get(TentContents.METADATA_KEY).isDocument()) {
                {
                    TentContents contents = TentContents.fromBson(meta.getDocument(TentContents.METADATA_KEY), kind.id);
                    if (contents != null) {
                        final Vector3i at = new Vector3i(origin);
                        final int count = contents.itemCount();
                        world.execute(() -> {
                            try {
                                contents.restore(world, world.getEntityStore().getStore(), at, rotation);
                                LOGGER.at(Level.INFO).log("Camp: %d item(s) put back from the bag", count);
                            } catch (RuntimeException e) {
                                LOGGER.at(Level.WARNING).withCause(e).log("Camp: bag contents not put back");
                                Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
                            }
                        });
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camp: unreadable bag contents");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
        }
        LOGGER.at(Level.INFO).log("Camp \"%s\" placed at (%d, %d, %d) rotation %s by %s", kind.id, origin.x, origin.y, origin.z, rotation, playerRef.getUsername());
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "DeployCampInteraction{prefabPath=" + prefabPath + ", offset=" + offset
                + ", rotationOffset=" + rotationOffset + ", force=" + force + "}";
    }
}
