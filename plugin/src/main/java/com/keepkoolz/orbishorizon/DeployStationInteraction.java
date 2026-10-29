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
import java.util.List;
import java.util.Set;
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
        Deployables.Kind kind = prefabPath != null ? Deployables.forPrefab(prefabPath) : null;
        // Recipe enabled: anyone may place a station. Disabled: only the creative crate, and only ops.
        String crateId = StationSpec.of(kind == null ? null : kind.id).crateId();
        if (!RecipeToggles.enabled(crateId) && !StructureRemoval.isOp(playerRef.getUuid())) {
            playerRef.sendMessage(Texts.t("transit.deploy.opOnly"));
            fail(context);
            return;
        }
        Path path = prefabPath != null ? PrefabStore.get().findAssetPrefabPath(prefabPath) : null;
        boolean boatStation = kind == Deployables.BOAT_STATION;
        if ((kind != Deployables.STATION && !boatStation) || path == null) {
            LOGGER.at(Level.WARNING).log("Transit_DeployStation: PrefabPath inattendu ou introuvable : %s", prefabPath);
            fail(context);
            return;
        }
        StructureShape station;
        StructureShape balloon;
        try {
            station = kind.shape();
            balloon = boatStation ? null : Deployables.BALLOON_PUBLIC.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Transit_DeployStation: forme illisible");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            fail(context);
            return;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();

        // same computation as the held-crate preview (shared helper).
        BlockPosition target = context.getTargetBlock();
        DeployPlacement.Placement placement = DeployPlacement.compute(
                target != null ? new Vector3i(target.x, target.y, target.z) : null,
                transform.getPosition(), transform.getRotation().y, offset, rotationOffset);
        Rotation rotation = placement.rotation();
        Vector3i origin = placement.origin();

        if (boatStation) {
            // The origin follows the water surface under the pier end, whatever the height of the bank.
            origin = DeployPlacement.boatStationOrigin(DeployPlacement.worldState(world), origin, rotation);
            if (origin == null) {
                LOGGER.at(Level.INFO).log("Station maritime refusée : pas d'eau sous le bout du ponton");
                playerRef.sendMessage(Texts.t("deploy.steamboat.noWater"));
                fail(context);
                return;
            }
            Refusal refusal = boatRoomRefusal(world, station, origin, rotation);
            if (refusal != null) {
                refusal.send(playerRef);
                fail(context);
                return;
            }
        } else {
            Refusal refusal = balloonRoomRefusal(world, station, balloon, origin, rotation);
            if (refusal != null) {
                refusal.send(playerRef);
                fail(context);
                return;
            }
        }
        // Distance to the other stations of this world.
        double min = TransitConstants.MIN_STATION_DISTANCE;
        TransitNetwork.Station tooClose = tooClose(TransitNetwork.get().stations(world.getName()), kind.id, origin, min);
        if (tooClose != null) {
            playerRef.sendMessage(Texts.t("transit.deploy.tooClose").param("name", tooClose.name));
            fail(context);
            return;
        }
        if (boatStation && !boatHasOpenWater(world, origin, rotation, kind)) {
            playerRef.sendMessage(Texts.t("transit.deploy.boatNoOpenWater"));
            fail(context);
            return;
        }

        // Wood and stone follow the biome and zone (read before the paste, applied after it).
        Look look = readLook(world, kind, origin);
        int flags = (force ? 1 : 0) | 8; // same options as SpawnPrefab
        BalloonManager.pasteKeepingTerrain(buffer, world, station, origin, rotation, flags, commandBuffer, kind.id,
                Ownership.of(playerRef.getUuid(), null), boatStation ? Integer.MIN_VALUE : Deployables.STATION_GRASS_Y); // station: owner kept as before, no map marker (out of scope)
        applyLook(world, station, origin, rotation, kind, look);
        Telemetry.count(boatStation ? "deploy.boatStation" : "deploy.station");
        // The station is locked from the paste (registry). It joins the network when its name is chosen: the name window opens
        // here, deferred like the other entity-side work started from an interaction.
        final Vector3i placedOrigin = new Vector3i(origin);
        final Rotation placedRotation = rotation;
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) {
                    completeRegistration(world, placedOrigin, placedRotation, playerRef.getUuid(), null, playerRef, kind.id);
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                Player p = store.getComponent(ref, Player.getComponentType());
                if (p == null) {
                    completeRegistration(world, placedOrigin, placedRotation, playerRef.getUuid(), null, playerRef, kind.id);
                    return;
                }
                p.getPageManager().openCustomPage(ref, store, new StationNamePage(playerRef, world, placedOrigin, placedRotation, kind.id));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Fenêtre du nom de la station impossible : nom automatique");
                Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
                StationNamePage.onPlayerGone(playerRef.getUuid());
                if (TransitNetwork.get().at(world.getName(), placedOrigin) == null) {
                    completeRegistration(world, placedOrigin, placedRotation, playerRef.getUuid(), null, playerRef, kind.id);
                }
            }
        });
        LOGGER.at(Level.INFO).log("Station posée en (%d, %d, %d) rotation %s par %s, en attente du nom", origin.x, origin.y, origin.z,
                rotation, playerRef.getUsername());
    }

    /**
     * True when the shuttle moored at the berth of a boat station placed at origin can sail out to open water. A failure of the
     * check itself counts as a refusal. Never throws.
     */
    private static boolean boatHasOpenWater(World world, Vector3i origin, Rotation rotation, Deployables.Kind kind) {
        try {
            TransitNetwork.Station probe = new TransitNetwork.Station("", world.getName(), new Vector3i(origin), rotation, null, null,
                    new ArrayList<>(), kind.id);
            if (BoatBerth.hasOpenWaterExit(world, probe)) {
                return true;
            }
            LOGGER.at(Level.INFO).log("Station maritime refusée en (%d, %d, %d) : pas de sortie vers le large", origin.x, origin.y, origin.z);
            return false;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station maritime : recherche d'une sortie vers le large en échec, pose refusée");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            return false;
        }
    }

    /** Wood and stone read from the generator before a paste. A field is null when it does not apply to the kind. */
    record Look(BiomeWood.Wood wood, BiomeStone.Stone stone) {
    }

    /** Reads the wood (boat station: around the pier) or the wood and stone (balloon station: at the origin) of the biome and zone. */
    static Look readLook(World world, Deployables.Kind kind, Vector3i origin) {
        if (kind == Deployables.BOAT_STATION) {
            return new Look(BiomeWood.woodAt(world, origin.x, origin.z, true), null);
        }
        if (Deployables.STATION_KIND.equals(kind.id)) {
            return new Look(BiomeWood.stationWoodAt(world, origin.x, origin.z, true), BiomeStone.stoneAt(world, origin.x, origin.z));
        }
        return new Look(null, null);
    }

    /** Registers then rewrites the wood (and stone) of a pasted station, registry first so a failure leaves no renamed blocks unrecorded. */
    static void applyLook(World world, StructureShape station, Vector3i origin, Rotation rotation, Deployables.Kind kind, Look look) {
        if (kind == Deployables.BOAT_STATION) {
            BiomeWood.Wood wood = look.wood();
            if (wood == null) {
                return;
            }
            try {
                boolean custom = !wood.equals(BiomeWood.DEFAULT);
                // Registry first (null for the default wood, so a stale entry cannot keep an old wood): a failure halfway through
                // the rewrite must not leave renamed blocks with a registry saying "no wood".
                BalloonRegistry.setWood(world, origin, rotation, kind.id, custom ? wood.species() : null);
                if (custom) {
                    int changed = applyWood(world, station, origin, rotation, wood);
                    LOGGER.at(Level.INFO).log("Station maritime : %d bloc(s) réécrits en %s", changed, wood.species());
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Bois de la station maritime non appliqué");
                Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            }
        } else if (Deployables.STATION_KIND.equals(kind.id)) {
            applyBalloonLook(world, station, origin, rotation, kind, look.wood(), look.stone());
        }
    }

    private static void applyBalloonLook(World world, StructureShape station, Vector3i origin, Rotation rotation, Deployables.Kind kind,
                                         BiomeWood.Wood wood, BiomeStone.Stone stone) {
        try {
            boolean customWood = wood != null;
            boolean customStone = stone != null && !stone.equals(BiomeStone.DEFAULT);
            BalloonRegistry.setWood(world, origin, rotation, kind.id, customWood ? wood.species() : null);
            BalloonRegistry.setStone(world, origin, rotation, kind.id, customStone ? stone.family() : null);
            int changed = 0;
            if (customWood) {
                changed += rewrite(world, station, origin, rotation, n -> BiomeWood.remapStation(n, wood));
            }
            if (customStone) {
                changed += applyStone(world, station, origin, rotation, stone);
            }
            LOGGER.at(Level.INFO).log("Station : %d bloc(s) réécrits (bois %s, pierre %s)", changed,
                    customWood ? wood.species() : "d'origine", customStone ? stone.family() : "d'origine");
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Bois et pierre de la station non appliqués");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
        }
    }

    /** Rewrites the basalt bricks of a pasted balloon station to the stone of its zone. Returns the number of cells rewritten. */
    static int applyStone(World world, StructureShape base, Vector3i origin, Rotation rotation, BiomeStone.Stone stone) {
        return rewrite(world, base, origin, rotation, n -> BiomeStone.remap(n, stone));
    }

    /**
     * Rewrites the origin cells (filler 0) of a pasted boat station whose name changes with the wood. The rotation index is
     * read back from the world, where the paste already composed the cell rotation with the station rotation. BlockOperations
     * writes the filler cells of a multi-cell block itself. Returns the number of cells rewritten.
     */
    static int applyWood(World world, StructureShape base, Vector3i origin, Rotation rotation, BiomeWood.Wood wood) {
        return rewrite(world, base, origin, rotation, n -> BiomeWood.remap(n, wood));
    }

    private static int rewrite(World world, StructureShape base, Vector3i origin, Rotation rotation,
                               java.util.function.UnaryOperator<String> rename) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        int changed = 0;
        for (StructureShape.Cell c : base.cells()) {
            if (c.filler() != 0) {
                continue;
            }
            String name = rename.apply(c.name());
            if (name.equals(c.name())) {
                continue;
            }
            int id = BlockType.getAssetMap().getIndex(name);
            BlockType type = id < 0 ? null : BlockType.getAssetMap().getAsset(id);
            if (type == null) {
                LOGGER.at(Level.WARNING).log("Station : bloc inconnu %s", name);
                continue;
            }
            Vector3i p = c.rotated(rotation).add(origin);
            if (!reader.hasStorageInMemory(p.x, p.y, p.z)) {
                continue;
            }
            int rot = reader.getRotationIndex(p.x, p.y, p.z);
            if (SectionBlocks.setAt(world.getChunkStore(), p.x, p.y, p.z, id, type, rot, 0, 4)) { // 4: no particles
                changed++;
            }
        }
        return changed;
    }

    /**
     * A room test refusal: the chat key, the offending block and cell when the message names them, and a second key sent after
     * the first (boat berth), and for a berth refusal whether the faulty cell is a hull cell (null for the other refusals). A null key
     * is a refusal with no message to the player.
     */
    record Refusal(String key, String block, Vector3i at, String detailKey, Boolean hull) {
        Refusal(String key, String block, Vector3i at, String detailKey) {
            this(key, block, at, detailKey, null);
        }

        static Refusal of(String key) {
            return new Refusal(key, null, null, null);
        }

        static Refusal at(String key, String block, int x, int y, int z) {
            return new Refusal(key, block, new Vector3i(x, y, z), null);
        }

        /** Sends the refusal messages to the player. */
        void send(PlayerRef player) {
            if (key == null) {
                return;
            }
            com.hypixel.hytale.server.core.Message m = Texts.t(key);
            if (block != null) {
                m = m.param("block", block);
            }
            if (at != null) {
                m = m.param("x", at.x).param("y", at.y).param("z", at.z);
            }
            player.sendMessage(m);
            if (detailKey != null) {
                player.sendMessage(Texts.t(detailKey));
            }
        }
    }

    /**
     * Room test of the balloon station: station cells above the grass layer and the docked balloon, no liquid. Null when there is
     * room, else the refusal.
     */
    static Refusal balloonRoomRefusal(World world, StructureShape station, StructureShape balloon, Vector3i origin, Rotation rotation) {
        return balloonRoomRefusal(world, station, balloon, origin, rotation, false);
    }

    /** Position of the docked balloon (prefab origin of the balloon) for a station at origin. */
    static Vector3i dockOrigin(Vector3i origin, Rotation rotation) {
        return rotation.rotateYaw(new Vector3i(Deployables.STATION_DOCK_ORIGIN[0], Deployables.STATION_DOCK_ORIGIN[1],
                Deployables.STATION_DOCK_ORIGIN[2]), new Vector3i()).add(origin);
    }

    /** Pure rule: a block refuses the room when it is solid, unless vegetation to be cleared is treated as empty. */
    static boolean blocksRoom(String blockId, boolean solid, boolean removableIsEmpty) {
        return solid && !(removableIsEmpty && GenClear.removable(blockId));
    }

    /** Same test; with removableIsEmpty, blocks GenClear would clear count as empty (nothing is cleared by the test). */
    static Refusal balloonRoomRefusal(World world, StructureShape station, StructureShape balloon, Vector3i origin, Rotation rotation,
                                      boolean removableIsEmpty) {
        // Room test: the station cells above the grass layer and the balloon resting at the dock. The grass layer is the ground
        // level (the targeted block): it is never tested. Its soil yields to the existing terrain and its stone path replaces it
        // (keepTerrainAt). Testing the path cells refused every placement on ordinary ground ("not enough space"
        // with Soil_Grass_Full in the path cells).
        ArrayList<Vector3i> cells = new ArrayList<>();
        for (StructureShape.Cell c : station.cells()) {
            if (c.y() != Deployables.STATION_GRASS_Y) {
                cells.add(c.rotated(rotation).add(origin));
            }
        }
        Vector3i dock = dockOrigin(origin, rotation);
        for (StructureShape.Cell c : balloon.cells()) {
            cells.add(c.rotated(rotation).add(dock));
        }
        // No deployment under water: the station (grass layer included) and the docked balloon.
        Vector3i wet = BalloonManager.underLiquid(world, station, origin, rotation);
        if (wet == null) {
            wet = BalloonManager.underLiquid(world, balloon, dock, rotation);
        }
        if (wet != null) {
            LOGGER.at(Level.INFO).log("Station refusée en (%d, %d, %d) : liquide en (%d, %d, %d)", origin.x, origin.y, origin.z, wet.x, wet.y, wet.z);
            return Refusal.of("deploy.underwater");
        }
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (Vector3i p : cells) {
            if (BalloonManager.outOfHeight(p.y)) {
                LOGGER.at(Level.INFO).log("Station refusée en (%d, %d, %d) : la case (%d, %d, %d) dépasse la limite de hauteur",
                        origin.x, origin.y, origin.z, p.x, p.y, p.z);
                return Refusal.of("deploy.heightLimit");
            }
            boolean loaded = reader.hasStorageInMemory(p.x, p.y, p.z);
            BlockType bt = loaded ? SectionBlocks.typeAt(reader, p.x, p.y, p.z) : null;
            if (!loaded || (bt != null && blocksRoom(bt.getId(), bt.getMaterial() == BlockMaterial.Solid, removableIsEmpty))) {
                LOGGER.at(Level.INFO).log("Station refusée en (%d, %d, %d) : %s en (%d, %d, %d)",
                        origin.x, origin.y, origin.z, loaded ? bt.getId() : "chunk non chargé", p.x, p.y, p.z);
                return loaded
                        ? Refusal.at("transit.deploy.noSpaceAt", bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId(), p.x, p.y, p.z)
                        : Refusal.of("transit.deploy.noSpace");
            }
        }
        return null;
    }

    /** Pure rule: a prefab cell below y 0 (posts) accepts anything, a cell at y 0 or above (deck, kiosk) refuses a solid block and a liquid. */
    static boolean boatCellAllowed(int prefabY, boolean solid, boolean liquid) {
        return prefabY < 0 || (!solid && !liquid);
    }

    /**
     * Room test of the boat station: every cell of the pier (no grass layer), then the berth, where the steamboat must fit on clear
     * water. Null when there is room, else the refusal.
     */
    static Refusal boatRoomRefusal(World world, StructureShape station, Vector3i origin, Rotation rotation) {
        return boatRoomRefusal(world, station, origin, rotation, Set.of());
    }

    /** Same test with the cells of emptied read as empty solid-free cells, to check a site as it would be once they are cleared. */
    static Refusal boatRoomRefusal(World world, StructureShape station, Vector3i origin, Rotation rotation, Set<Vector3i> emptied) {
        return boatRoomRefusal(world, station, origin, rotation, emptied, Set.of());
    }

    /** Same test with the cells of dredged read as water too: generated stations dig their berth, players never do. */
    static Refusal boatRoomRefusal(World world, StructureShape station, Vector3i origin, Rotation rotation, Set<Vector3i> emptied,
                                   Set<Vector3i> dredged) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (StructureShape.Cell c : station.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            if (BalloonManager.outOfHeight(p.y)) {
                LOGGER.at(Level.INFO).log("Station maritime refusée en (%d, %d, %d) : la case (%d, %d, %d) dépasse la limite de hauteur",
                        origin.x, origin.y, origin.z, p.x, p.y, p.z);
                return Refusal.of("deploy.heightLimit");
            }
            if (!reader.hasStorageInMemory(p.x, p.y, p.z)) {
                return Refusal.of("transit.deploy.boatNoSpace");
            }
            BlockType bt = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
            boolean solid = bt != null && bt.getMaterial() == BlockMaterial.Solid && !emptied.contains(p);
            String liquidId = BalloonManager.liquidAt(reader, p.x, p.y, p.z);
            boolean liquid = liquidId != null && !emptied.contains(p);
            if (!boatCellAllowed(c.y(), solid, liquid)) {
                LOGGER.at(Level.INFO).log("Station maritime refusée en (%d, %d, %d) : %s en (%d, %d, %d)",
                        origin.x, origin.y, origin.z, solid ? bt.getId() : liquidId, p.x, p.y, p.z);
                String what = solid ? (bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId()) : liquidId;
                return Refusal.at("transit.deploy.boatNoSpaceAt", what, p.x, p.y, p.z);
            }
        }
        StructureShape boat;
        try {
            boat = Deployables.STEAMBOAT_PUBLIC.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station maritime : forme de la navette illisible");
            Telemetry.error("deploy", e, Telemetry.kindId(Deployables.BOAT_STATION), null);
            return new Refusal(null, null, null, null);
        }
        Vector3i berth = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                Deployables.BOAT_STATION_BERTH_ORIGIN[2]), new Vector3i()).add(origin);
        DeployPlacement.CellState state = DeployPlacement.worldState(world);
        DeployPlacement.CellState read = emptied.isEmpty() ? state : DeployPlacement.withEmptied(state, emptied);
        DeployPlacement.Fault fault = DeployPlacement.steamboatFault(boat, berth, rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION),
                dredged.isEmpty() ? read : DeployPlacement.withWater(read, dredged), 0);
        if (fault != null) {
            Vector3i at = fault.at();
            BlockType bt = SectionBlocks.typeAt(reader, at.x, at.y, at.z);
            String liquidId = BalloonManager.liquidAt(reader, at.x, at.y, at.z);
            String found = bt != null && bt.getMaterial() == BlockMaterial.Solid ? bt.getId() : liquidId != null ? liquidId
                    : bt != null ? bt.getId() : "air";
            String block = found.startsWith("*") ? found.substring(1) : found;
            LOGGER.at(Level.INFO).log("Station maritime refusée en (%d, %d, %d) : poste d'amarrage (%s), %s en (%d, %d, %d), %s",
                    origin.x, origin.y, origin.z, fault.key(), block, at.x, at.y, at.z, fault.hull() ? "coque" : "hors de l'eau");
            return new Refusal("transit.deploy.boatBerth", block, new Vector3i(at), fault.key(), fault.hull());
        }
        return null;
    }

    /** First station of the same kind closer than min blocks (origin to origin), null if none. Other kinds are ignored. */
    static TransitNetwork.Station tooClose(List<TransitNetwork.Station> stations, String kind, Vector3i origin, double min) {
        for (TransitNetwork.Station other : stations) {
            if (!other.kind.equals(kind)) continue;
            if (new org.joml.Vector3d(other.origin.x, other.origin.y, other.origin.z)
                    .distanceSquared(origin.x, origin.y, origin.z) < min * min) {
                return other;
            }
        }
        return null;
    }

    /**
     * Registers a placed station in the network (name already checked, or null for the automatic one), then creates its master NPC
     * (deferred) and tells the player. Safe to call twice (no-op if the station is already registered). On any thread.
     */
    static void completeRegistration(World world, Vector3i origin, Rotation rotation, UUID owner, String name, PlayerRef player, String kind) {
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
            chosen = registerStation(world, origin, rotation, owner, wanted, kind);
            placed = TransitNetwork.get().at(world.getName(), origin);
        }
        final TransitNetwork.Station toSpawn = placed;
        if (toSpawn != null) {
            if (owner != null) {
                KnownStations.learn(world.getName(), owner, toSpawn); // whoever places a station knows it
            }
            world.execute(() -> StationMaster.spawn(world.getEntityStore().getStore(), world, toSpawn));
            SeaCharts.stationAdded(world, toSpawn); // boat stations only: the water around the berth is charted
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
    static String registerStation(World world, Vector3i origin, Rotation rotation, UUID owner, String name, String kind) {
        TransitNetwork net = TransitNetwork.get();
        String world0 = world.getName();
        String chosen = name;
        if (chosen == null) {
            ArrayList<String> taken = new ArrayList<>();
            for (TransitNetwork.Station s : net.stations(world0)) taken.add(s.name);
            chosen = TransitNetwork.autoName(taken);
        }
        net.add(new TransitNetwork.Station(chosen, world0, new Vector3i(origin), rotation, null, owner, new ArrayList<>(), kind));
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
