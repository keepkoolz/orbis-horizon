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

        // same computation as the held-crate preview (shared helper). Rotation3f.y is the yaw in radians.
        BlockPosition target = context.getTargetBlock();
        DeployPlacement.Placement placement = DeployPlacement.compute(
                target != null ? new Vector3i(target.x, target.y, target.z) : null,
                transform.getPosition(), transform.getRotation().y, offset, rotationOffset);
        Rotation rotation = placement.rotation();
        Vector3i origin = placement.origin();
        Deployables.Kind boatKind = Deployables.forPrefab(prefabPath);
        boolean boat = isCrateBoat(boatKind);
        int waterline = boat ? boatKind.airship.waterlineY : 0;
        DeployPlacement.CellState waterState = boat ? DeployPlacement.worldState(world) : null;
        if (boat) {
            // A boat floats: the origin follows the water surface under the targeted block.
            // The stern is the targeted cell (the player's own cell when nothing is targeted), pushed forward to stay clear of the player.
            Vector3i aimed = target != null ? new Vector3i(target.x, target.y, target.z) : null;
            Vector3i playerCell = Vector3dUtil.toVector3i(transform.getPosition());
            int push = DeployPlacement.boatPush(aimed != null ? aimed : playerCell, playerCell, placement.facing());
            Vector3i boatOrigin = DeployPlacement.steamboatOrigin(waterState, DeployPlacement.forward(aimed, placement.facing(), push),
                    DeployPlacement.forward(origin, placement.facing(), push), waterline);
            if (boatOrigin == null) {
                refuseBoat(commandBuffer, player, refusalKey(boatKind, "deploy.steamboat.noWater"), context);
                return;
            }
            origin = boatOrigin;
        }

        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        // the unfolded ladder does not replace the terrain (a slope, a wall). The balloon type is found by the
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
        // a pose with a cell outside the world's height range would lose blocks: refused, the Failed state skips the Next
        // (ModifyInventory), so the crate is not consumed.
        if (shape != null && BalloonManager.exceedsHeight(shape, origin, rotation)) {
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(Texts.t("deploy.heightLimit"));
            }
            context.getState().state = InteractionState.Failed;
            return;
        }
        if (boat && shape != null) {
            String refusal = DeployPlacement.steamboatFit(shape, origin, rotation, waterState, waterline);
            if (refusal != null) {
                refuseBoat(commandBuffer, player, refusalKey(boatKind, refusal), context);
                return;
            }
        }
        Vector3i wet = shape != null && !boat ? BalloonManager.underLiquid(world, shape, origin, rotation) : null;
        if (wet != null) {
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(Texts.t("deploy.underwater"));
            }
            context.getState().state = InteractionState.Failed;
            return;
        }
        String zoneStation = StationZone.blockingStation(world, kind.id, shape, origin, rotation);
        if (zoneStation != null) {
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(Texts.t("deploy.stationZone").param("station", zoneStation));
            }
            context.getState().state = InteractionState.Failed;
            return;
        }
        if (shape != null) {
            PlayerRef placer = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            Ownership own = placer != null ? Ownership.of(placer.getUuid(), placer.getUsername()) : Ownership.NONE;
            BalloonManager.pasteKeepingTerrain(buffer, world, shape, origin, rotation, flags, commandBuffer, kind.id, own);
            Telemetry.count("deploy." + kind.id);
            if (Deployables.AIRSHIP_KIND.equals(kind.id)) {
                AirshipCaptainChest.give(world, origin, rotation);
            }
            if (kind == Deployables.STEAMBOAT_SKIFF) {
                Telemetry.count("boat.skiff.deploy");
                putBackSkiffContents(context, world, origin, rotation);
            }
            if (isNamedKind(kind.id)) {
                openNameWindow(world, player, commandBuffer, kind.id, origin, rotation);
            }
        } else {
            // Unreadable shape: placed without terrain protection and without registry entry (logged by shapeOrNull).
            PrefabUtil.paste(buffer, world, origin, rotation, new FastRandom(), flags, 0, commandBuffer);
        }
    }

    /** True for the vehicle types that carry a name. */
    private static boolean isNamedKind(String kindId) {
        return Deployables.AIRSHIP_KIND.equals(kindId) || Deployables.STEAMBOAT_KIND.equals(kindId) || Deployables.BALLOON_KIND.equals(kindId);
    }

    /** Opens the name window of the vehicle just placed (on the world thread, once the registry entry exists). Never throws. */
    private static void openNameWindow(World world, Ref<EntityStore> player, CommandBuffer<EntityStore> commandBuffer, String kindId,
                                       Vector3i origin, Rotation rotation) {
        PlayerRef pr = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        if (pr == null) {
            return;
        }
        final Vector3i at = new Vector3i(origin);
        world.execute(() -> {
            try {
                if (!player.isValid()) {
                    return;
                }
                BalloonRegistry.Entry entry = BalloonRegistry.get(world, at, rotation, kindId);
                if (entry != null) {
                    VehicleNamePage.open(world.getEntityStore().getStore(), player, pr, entry.ownership().id(), kindId, null);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Vehicle name window not opened");
                Telemetry.error("deploy", e, kindId, "name");
            }
        });
    }

    /** True for a boat placed from a crate (the steamboat and the skiff; the shuttle has no crate). */
    static boolean isCrateBoat(Deployables.Kind kind) {
        return kind != null && kind.airship != null && kind.airship.waterborne() && kind.airship.crateItemId != null;
    }

    /** Refusal text key of a boat: the placement checks answer with the steamboat keys, the skiff has its own texts. */
    static String refusalKey(Deployables.Kind kind, String steamboatKey) {
        return kind == Deployables.STEAMBOAT_SKIFF ? steamboatKey.replace("deploy.steamboat.", "deploy.skiff.") : steamboatKey;
    }

    /**
     * The held skiff crate may carry the contents of the chests kept when the skiff was packed: they are put back once the blocks
     * exist (deferred out of the interaction, the chests retry for a few seconds if their components are not created yet).
     * A crate without contents, or with those of another type, places an empty skiff. Never throws.
     */
    private static void putBackSkiffContents(InteractionContext context, World world, Vector3i placedOrigin, Rotation rotation) {
        try {
            com.hypixel.hytale.server.core.inventory.ItemStack held = context.getHeldItem();
            org.bson.BsonDocument meta = held != null ? held.getMetadata() : null;
            if (meta == null || !meta.containsKey(TentContents.METADATA_KEY) || !meta.get(TentContents.METADATA_KEY).isDocument()) {
                return;
            }
            TentContents contents = TentContents.fromBson(meta.getDocument(TentContents.METADATA_KEY), Deployables.STEAMBOAT_SKIFF_KIND);
            if (contents == null) {
                return;
            }
            final Vector3i at = new Vector3i(placedOrigin);
            world.execute(() -> {
                try {
                    contents.restore(world, world.getEntityStore().getStore(), at, rotation);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Skiff: crate contents not put back");
                    Telemetry.error("deploy", e, Deployables.STEAMBOAT_SKIFF_KIND, null);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Skiff: unreadable crate contents");
            Telemetry.error("deploy", e, Deployables.STEAMBOAT_SKIFF_KIND, null);
        }
    }

    private static void refuseBoat(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> player, String key, InteractionContext context) {
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        if (playerRef != null) {
            playerRef.sendMessage(Texts.t(key));
        }
        context.getState().state = InteractionState.Failed; // the crate is not consumed
    }

    @Override
    public String toString() {
        return "DeployBalloonInteraction{prefabPath=" + prefabPath + ", offset=" + offset
                + ", rotationOffset=" + rotationOffset + ", force=" + force + "}";
    }
}
