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
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import org.joml.Vector3i;

import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Like the game's SpawnPrefab interaction, but the prefab rotation follows
 * the direction of the player's gaze (rounded to a quarter turn).
 *
 * JSON fields:
 *   PrefabPath      prefab path, relative to Server/Prefabs/
 *   Offset          {X,Y,Z} offset applied in the player's frame
 *   RotationOffset  quarter turn added to the player's direction (None, Ninety, OneEighty, TwoSeventy)
 *   Force           overwrites existing blocks
 */
public class DeployBalloonInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "HotairBalloon_Deploy";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<DeployBalloonInteraction> CODEC = BuilderCodec
            .builder(DeployBalloonInteraction.class, DeployBalloonInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Spawns a prefab rotated to face the direction the player is looking.")
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

    public DeployBalloonInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        if (prefabPath == null) {
            LOGGER.at(Level.WARNING).log("HotairBalloon_Deploy: PrefabPath is missing");
            return;
        }
        Path path = PrefabStore.get().findAssetPrefabPath(prefabPath);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("HotairBalloon_Deploy: prefab not found: %s", prefabPath);
            return;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();

        Ref<EntityStore> player = context.getEntity();
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        if (transform == null) {
            LOGGER.at(Level.WARNING).log("HotairBalloon_Deploy: no transform on interacting entity");
            return;
        }

        // Rotation3f.y is the yaw in radians.
        Rotation3f bodyRotation = transform.getRotation();
        Rotation facing = Rotation.closestOfDegrees((float) Math.toDegrees(bodyRotation.y));
        Rotation rotation = facing.add(rotationOffset);

        // Origin: the targeted block, otherwise the player's position.
        BlockPosition target = context.getTargetBlock();
        Vector3i origin = target != null
                ? new Vector3i(target.x, target.y, target.z)
                : Vector3dUtil.toVector3i(transform.getPosition());

        // The offset only follows the player's direction (Z = in front of them, to be calibrated in game).
        Vector3i rotatedOffset = facing.rotateYaw(new Vector3i(offset), new Vector3i());
        origin.add(rotatedOffset);

        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        // T24: the unfolded ladder does not replace the terrain (a slope, a wall). Only the mod's crate has this prefab.
        BalloonShape shape = BalloonManager.get().shapeFor(prefabPath);
        if (shape != null) {
            BalloonManager.pasteKeepingTerrain(buffer, world, shape, origin, rotation, flags, commandBuffer);
        } else {
            PrefabUtil.paste(buffer, world, origin, rotation, new FastRandom(), flags, 0, commandBuffer);
        }
    }

    @Override
    public String toString() {
        return "DeployBalloonInteraction{prefabPath=" + prefabPath + ", offset=" + offset
                + ", rotationOffset=" + rotationOffset + ", force=" + force + "}";
    }
}
