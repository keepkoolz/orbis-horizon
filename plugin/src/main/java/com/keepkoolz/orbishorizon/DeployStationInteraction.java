package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Interaction "Transit_DeployStation": places a balloon station from the station crate (any player when the recipe is enabled, else a crate given by an operator).
 *
 * Order (spec, "Pose"): op check, room test (every station cell outside the grass layer, then every cell of the transit balloon
 * resting at the dock, rotated, with no solid block, under the height limit, chunks loaded), distance to the other stations, then
 * the paste keeping the terrain under the grass layer, the lock registry entry and the network registration (auto name).
 * Any refusal sets the Failed state, so the Next (ModifyInventory, the crate) is skipped.
 *
 * JSON fields are the same as DeployBalloonInteraction: PrefabPath, Offset (player frame), RotationOffset, Force.
 */
public class DeployStationInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Transit_DeployStation";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<DeployStationInteraction> CODEC = BuilderCodec
            .builder(DeployStationInteraction.class, DeployStationInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Places a balloon station of the transit network, if there is room and no other station is too close.")
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

    public DeployStationInteraction() {
    }

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        if (playerRef == null || transform == null) {
            fail(context);
            return;
        }
        // Recipe enabled: anyone may place a station. Disabled: only the creative crate, and only ops.
        if (!TransitSettings.recipeEnabled() && !StructureRemoval.isOp(playerRef.getUuid())) {
            playerRef.sendMessage(Texts.t("transit.deploy.opOnly"));
            fail(context);
            return;
        }
        Deployables.Kind kind = prefabPath != null ? Deployables.forPrefab(prefabPath) : null;
        Path path = prefabPath != null ? PrefabStore.get().findAssetPrefabPath(prefabPath) : null;
        if (kind != Deployables.STATION || path == null) {
            LOGGER.at(Level.WARNING).log("Transit_DeployStation: PrefabPath inattendu ou introuvable : %s", prefabPath);
            fail(context);
            return;
        }
        StructureShape station;
        StructureShape balloon;
        try {
            station = kind.shape();
            balloon = Deployables.BALLOON_PUBLIC.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Transit_DeployStation: forme illisible");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            fail(context);
            return;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();

        // T121: same computation as the held-crate preview (shared helper).
        BlockPosition target = context.getTargetBlock();
        DeployPlacement.Placement placement = DeployPlacement.compute(
                target != null ? new Vector3i(target.x, target.y, target.z) : null,
                transform.getPosition(), transform.getRotation().y, offset, rotationOffset);
        Rotation rotation = placement.rotation();
        Vector3i origin = placement.origin();

        // Room test: the station cells above the grass layer and the balloon resting at the dock. The grass layer is the ground
        // level (the targeted block): it is never tested. Its soil yields to the existing terrain and its stone path replaces it
        // (keepTerrainAt). Testing the path cells refused every placement on ordinary ground (11 October 2026: "not enough space"
        // with Soil_Grass_Full in the path cells).
        ArrayList<Vector3i> cells = new ArrayList<>();
        for (StructureShape.Cell c : station.cells()) {
            if (c.y() != Deployables.STATION_GRASS_Y) {
                cells.add(c.rotated(rotation).add(origin));
            }
        }
        Vector3i dock = rotation.rotateYaw(new Vector3i(Deployables.STATION_DOCK_ORIGIN[0], Deployables.STATION_DOCK_ORIGIN[1],
                Deployables.STATION_DOCK_ORIGIN[2]), new Vector3i()).add(origin);
        for (StructureShape.Cell c : balloon.cells()) {
            cells.add(c.rotated(rotation).add(dock));
        }
        // No deployment under water (11 October 2026): the station (grass layer included) and the docked balloon.
        Vector3i wet = BalloonManager.underLiquid(world, station, origin, rotation);
        if (wet == null) {
            wet = BalloonManager.underLiquid(world, balloon, dock, rotation);
        }
        if (wet != null) {
            LOGGER.at(Level.INFO).log("Station refusée en (%d, %d, %d) : liquide en (%d, %d, %d)", origin.x, origin.y, origin.z, wet.x, wet.y, wet.z);
            playerRef.sendMessage(Texts.t("deploy.underwater"));
            fail(context);
            return;
        }
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (Vector3i p : cells) {
            if (BalloonManager.outOfHeight(p.y)) {
                LOGGER.at(Level.INFO).log("Station refusée en (%d, %d, %d) : la case (%d, %d, %d) dépasse la limite de hauteur",
                        origin.x, origin.y, origin.z, p.x, p.y, p.z);
                playerRef.sendMessage(Texts.t("deploy.heightLimit"));
                fail(context);
                return;
            }
            boolean loaded = reader.hasStorageInMemory(p.x, p.y, p.z);
            BlockType bt = loaded ? SectionBlocks.typeAt(reader, p.x, p.y, p.z) : null;
            if (!loaded || (bt != null && bt.getMaterial() == BlockMaterial.Solid)) {
                LOGGER.at(Level.INFO).log("Station refusée en (%d, %d, %d) : %s en (%d, %d, %d)",
                        origin.x, origin.y, origin.z, loaded ? bt.getId() : "chunk non chargé", p.x, p.y, p.z);
                playerRef.sendMessage(loaded
                        ? Texts.t("transit.deploy.noSpaceAt").param("block", bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId())
                                .param("x", p.x).param("y", p.y).param("z", p.z)
                        : Texts.t("transit.deploy.noSpace"));
                fail(context);
                return;
            }
        }
        // Distance to the other stations of this world.
        double min = TransitConstants.MIN_STATION_DISTANCE;
        for (TransitNetwork.Station other : TransitNetwork.get().stations(world.getName())) {
            if (new org.joml.Vector3d(other.origin.x, other.origin.y, other.origin.z)
                    .distanceSquared(origin.x, origin.y, origin.z) < min * min) {
                playerRef.sendMessage(Texts.t("transit.deploy.tooClose").param("name", other.name));
                fail(context);
                return;
            }
        }

        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        BalloonManager.pasteKeepingTerrain(buffer, world, station, origin, rotation, flags, commandBuffer, kind.id,
                Ownership.of(playerRef.getUuid(), null), Deployables.STATION_GRASS_Y); // station: owner kept as before, no map marker (out of scope)
        Telemetry.count("deploy.station");
        // The station is locked from the paste (registry). It joins the network when its name is chosen: the name window opens
        // here, deferred like the other entity-side work started from an interaction.
        final Vector3i placedOrigin = new Vector3i(origin);
        final Rotation placedRotation = rotation;
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) {
                    completeRegistration(world, placedOrigin, placedRotation, playerRef.getUuid(), null, playerRef);
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                Player p = store.getComponent(ref, Player.getComponentType());
                if (p == null) {
                    completeRegistration(world, placedOrigin, placedRotation, playerRef.getUuid(), null, playerRef);
                    return;
                }
                p.getPageManager().openCustomPage(ref, store, new StationNamePage(playerRef, world, placedOrigin, placedRotation));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Fenêtre du nom de la station impossible : nom automatique");
                Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
                StationNamePage.onPlayerGone(playerRef.getUuid());
                if (TransitNetwork.get().at(world.getName(), placedOrigin) == null) {
                    completeRegistration(world, placedOrigin, placedRotation, playerRef.getUuid(), null, playerRef);
                }
            }
        });
        LOGGER.at(Level.INFO).log("Station posée en (%d, %d, %d) rotation %s par %s, en attente du nom", origin.x, origin.y, origin.z,
                rotation, playerRef.getUsername());
    }

    /**
     * Registers a placed station in the network (name already checked, or null for the automatic one), then creates its master NPC
     * (deferred) and tells the player. Safe to call twice (no-op if the station is already registered). On any thread.
     */
    static void completeRegistration(World world, Vector3i origin, Rotation rotation, UUID owner, String name, PlayerRef player) {
        String chosen;
        TransitNetwork.Station placed;
        // One registration at a time (a disconnection and a Validate can come from different threads).
        synchronized (TransitNetwork.get()) {
            if (TransitNetwork.get().at(world.getName(), origin) != null) {
                return;
            }
            String wanted = name;
            if (wanted != null) {
                ArrayList<String> taken = new ArrayList<>();
                for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) taken.add(s.name);
                if (TransitNetwork.checkName(wanted, taken) != TransitNetwork.NameCheck.OK) {
                    wanted = null; // taken since the check
                }
            }
            chosen = registerStation(world, origin, rotation, owner, wanted);
            placed = TransitNetwork.get().at(world.getName(), origin);
        }
        final TransitNetwork.Station toSpawn = placed;
        if (toSpawn != null) {
            world.execute(() -> StationMaster.spawn(world.getEntityStore().getStore(), world, toSpawn));
        }
        try {
            if (player != null) {
                player.sendMessage(Texts.t("transit.deploy.registered").param("name", chosen));
            }
        } catch (RuntimeException ignored) {
            // the player may be gone
        }
        LOGGER.at(Level.INFO).log("Station « %s » enregistrée en (%d, %d, %d)", chosen, origin.x, origin.y, origin.z);
    }

    /**
     * Creates the station in the network under the given name (null = auto name "Station n"), saves it and returns the name.
     * The master NPC (masterUuid) is set later. Task 7 calls this when the master's name is validated.
     */
    static String registerStation(World world, Vector3i origin, Rotation rotation, UUID owner, String name) {
        TransitNetwork net = TransitNetwork.get();
        String world0 = world.getName();
        String chosen = name;
        if (chosen == null) {
            ArrayList<String> taken = new ArrayList<>();
            for (TransitNetwork.Station s : net.stations(world0)) taken.add(s.name);
            chosen = TransitNetwork.autoName(taken);
        }
        net.add(new TransitNetwork.Station(chosen, world0, new Vector3i(origin), rotation, null, owner, new ArrayList<>()));
        net.save();
        StationCommand.StationNameType.stationsChanged();
        return chosen;
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "DeployStationInteraction{prefabPath=" + prefabPath + ", offset=" + offset
                + ", rotationOffset=" + rotationOffset + ", force=" + force + "}";
    }
}
