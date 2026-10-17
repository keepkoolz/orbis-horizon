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
import com.hypixel.hytale.server.core.universe.PlayerRef;
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
            context.getState().state = InteractionState.Failed; // the crate is not consumed (the Next is skipped)
            return;
        }
        Path path = PrefabStore.get().findAssetPrefabPath(prefabPath);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("HotairBalloon_Deploy: prefab not found: %s", prefabPath);
            context.getState().state = InteractionState.Failed; // the crate is not consumed (the Next is skipped)
            return;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();

        Ref<EntityStore> player = context.getEntity();
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        if (transform == null) {
            LOGGER.at(Level.WARNING).log("HotairBalloon_Deploy: no transform on interacting entity");
            context.getState().state = InteractionState.Failed; // the crate is not consumed (the Next is skipped)
            return;
        }

        // T121: same computation as the held-crate preview (shared helper). Rotation3f.y is the yaw in radians.
        BlockPosition target = context.getTargetBlock();
        DeployPlacement.Placement placement = DeployPlacement.compute(
                target != null ? new Vector3i(target.x, target.y, target.z) : null,
                transform.getPosition(), transform.getRotation().y, offset, rotationOffset);
        Rotation rotation = placement.rotation();
        Vector3i origin = placement.origin();

        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        // T24: the unfolded ladder does not replace the terrain (a slope, a wall). T54: the balloon type is found by the
        // prefab, and it is the one registered (the small balloon is registered as balloon_basic).
        Deployables.Kind kind = BalloonManager.get().balloonKindFor(prefabPath);
        if (kind == null) {
            // Airship prototype: the same crate interaction places the airship (its kind is registered as "airship").
            Deployables.Kind other = Deployables.forPrefab(prefabPath);
            kind = other != null && other.isAirship() ? other : null;
        }
        if (kind == null) {
            LOGGER.at(Level.WARNING).log("HotairBalloon_Deploy: prefab %s is not one of the balloon types or the airship, nothing placed", prefabPath);
            context.getState().state = InteractionState.Failed; // the crate is not consumed (the Next is skipped)
            return;
        }
        StructureShape shape = kind.shapeOrNull();
        // T76: a pose with a cell outside the world's height range would lose blocks: refused, the Failed state skips the Next
        // (ModifyInventory), so the crate is not consumed.
        if (shape != null && BalloonManager.exceedsHeight(shape, origin, rotation)) {
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(Texts.t("deploy.heightLimit"));
            }
            LOGGER.at(Level.INFO).log("Caisse %s refusée en (%d, %d, %d) : la structure dépasse la limite de hauteur", kind.id, origin.x, origin.y, origin.z);
            context.getState().state = InteractionState.Failed;
            return;
        }
        Vector3i wet = shape != null ? BalloonManager.underLiquid(world, shape, origin, rotation) : null;
        if (wet != null) {
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(Texts.t("deploy.underwater"));
            }
            LOGGER.at(Level.INFO).log("Caisse %s refusée en (%d, %d, %d) : liquide en (%d, %d, %d)", kind.id, origin.x, origin.y, origin.z,
                    wet.x, wet.y, wet.z);
            context.getState().state = InteractionState.Failed;
            return;
        }
        String zoneStation = StationZone.blockingStation(world, kind.id, shape, origin, rotation);
        if (zoneStation != null) {
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(Texts.t("deploy.stationZone").param("station", zoneStation));
            }
            LOGGER.at(Level.INFO).log("Caisse %s refusée en (%d, %d, %d) : zone de la station %s", kind.id, origin.x, origin.y, origin.z, zoneStation);
            context.getState().state = InteractionState.Failed;
            return;
        }
        if (kind.isAirship()) {
            PlayerRef owner = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (owner != null && AirshipLimit.ownsAirship(owner.getUuid())) {
                owner.sendMessage(Texts.t("airship.error.alreadyOwned"));
                LOGGER.at(Level.INFO).log("Caisse %s refusée en (%d, %d, %d) : %s possède déjà un dirigeable", kind.id, origin.x, origin.y, origin.z, owner.getUsername());
                context.getState().state = InteractionState.Failed;
                return;
            }
        }
        if (shape != null) {
            PlayerRef placer = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            Ownership own = placer != null ? Ownership.of(placer.getUuid(), placer.getUsername()) : Ownership.NONE;
            BalloonManager.pasteKeepingTerrain(buffer, world, shape, origin, rotation, flags, commandBuffer, kind.id, own);
        } else {
            // Unreadable shape: placed without terrain protection and without registry entry (logged by shapeOrNull).
            PrefabUtil.paste(buffer, world, origin, rotation, new FastRandom(), flags, 0, commandBuffer);
        }
    }

    @Override
    public String toString() {
        return "DeployBalloonInteraction{prefabPath=" + prefabPath + ", offset=" + offset
                + ", rotationOffset=" + rotationOffset + ", force=" + force + "}";
    }
}
