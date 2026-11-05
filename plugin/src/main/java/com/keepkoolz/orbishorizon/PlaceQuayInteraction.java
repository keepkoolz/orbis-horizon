package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.keepkoolz.orbishorizon.api.Refusal;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Interaction "Transit_PlaceQuay": a quay plan gives the nearest station without a quay that its holder owns (within 48 blocks of
 * the holder) its quay on the targeted block: the landing pad or the pier end ("Pad"), or the whole station ("Full"). The station
 * then joins the network and the name window opens.
 *
 * Order: kind and model, station, placement, refusal (owner, quay already placed, distance to the master), room test (balloon pad:
 * the free volume of the station, vegetation cleared; pier end: water at its end, the basin dug in the corridor (PierDredge), the corridor
 * in water or air, the shuttle berth;
 * whole station: the room test of a station), paste with the biome materials, registry inscription (owner = the
 * player), quay set in the network, name window. Any refusal sets the Failed state, so the Next (ModifyInventory, the plan) is
 * skipped.
 *
 * JSON fields: StationKind (Deployables.STATION_KIND or BOAT_STATION_KIND), Model (Pad or Full), then PrefabPath, Offset and
 * RotationOffset like the crates (DeployPlacement.CRATES holds the same values for the held-plan preview).
 */
public class PlaceQuayInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "Transit_PlaceQuay";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Farthest the holder of a plan may be from the master of the station that receives it, in blocks. */
    static final double STATION_REACH = 48.0;
    /** Farthest horizontal distance from the master to the centre of a pad placed on its own, in blocks. */
    static final int PAD_REACH = 16;

    public static final BuilderCodec<PlaceQuayInteraction> CODEC = BuilderCodec
            .builder(PlaceQuayInteraction.class, PlaceQuayInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Places the quay of the nearest station without a quay owned by the player: the pad or the pier end, or the whole station.")
            .appendInherited(new KeyedCodec<>("StationKind", Codec.STRING),
                    (o, v) -> o.stationKind = v, o -> o.stationKind, (o, p) -> o.stationKind = p.stationKind)
            .add()
            .appendInherited(new KeyedCodec<>("Model", Codec.STRING),
                    (o, v) -> o.model = v, o -> o.model, (o, p) -> o.model = p.model)
            .add()
            .appendInherited(new KeyedCodec<>("PrefabPath", Codec.STRING),
                    (o, v) -> o.prefabPath = v, o -> o.prefabPath, (o, p) -> o.prefabPath = p.prefabPath)
            .add()
            .appendInherited(new KeyedCodec<>("Offset", Vector3iUtil.CODEC),
                    (o, v) -> o.offset = v, o -> o.offset, (o, p) -> o.offset = p.offset)
            .add()
            .appendInherited(new KeyedCodec<>("RotationOffset", Rotation.CODEC),
                    (o, v) -> o.rotationOffset = v, o -> o.rotationOffset, (o, p) -> o.rotationOffset = p.rotationOffset)
            .add()
            .build();

    private String stationKind;
    private String model;
    private String prefabPath;
    private Vector3i offset = new Vector3i();
    private Rotation rotationOffset = Rotation.None;

    public PlaceQuayInteraction() {
    }

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    // ------------------------------------------------------------------ pure rules

    /**
     * Message key of the refusal of a quay at the given anchor for a station without a quay, null when there is none. Pure. op is
     * whether the player is an operator. A pad alone must be at most PAD_REACH blocks from the master (horizontal distance, balloon
     * station only): a whole station moves the master into its hut, and the pier end of a boat station has no reach.
     */
    static String refusal(TransitNetwork.Station pending, UUID player, boolean op, Vector3i quay, String kind, boolean full) {
        if (!op && (pending.owner == null || !pending.owner.equals(player))) {
            return "transit.quay.notOwner";
        }
        if (pending.quay != null) {
            return "transit.quay.already";
        }
        if (!full && !Deployables.BOAT_STATION_KIND.equals(kind)) {
            long dx = quay.x - pending.master.x;
            long dz = quay.z - pending.master.z;
            if (dx * dx + dz * dz > (long) PAD_REACH * PAD_REACH) {
                return "transit.quay.tooFar";
            }
        }
        return null;
    }

    /** Same refusal for a pad or a pier end alone, the operator status being read from the server. */
    static String refusal(TransitNetwork.Station pending, UUID player, Vector3i quay, String kind) {
        return refusal(pending, player, StructureRemoval.isOp(player), quay, kind, false);
    }

    /**
     * The station without a quay that receives the plan of a player standing at the given point: the nearest master within
     * maxDist blocks among the stations the player owns (every station for an operator). Null when there is none. Pure.
     */
    static TransitNetwork.Station nearestPending(List<TransitNetwork.Station> pending, UUID player, boolean op, Vector3d at, double maxDist) {
        TransitNetwork.Station best = null;
        double bestD = maxDist * maxDist;
        for (TransitNetwork.Station s : pending) {
            if (s.quay != null || (!op && (s.owner == null || !s.owner.equals(player)))) {
                continue;
            }
            double d = new Vector3d(s.master.x + 0.5, s.master.y, s.master.z + 0.5).distanceSquared(at);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * The station of the same kind, other than the one receiving its quay, whose master is under 64 blocks from the final master
     * cell of a whole station (master to master, the rule of the master contract). Null when none. Pure.
     */
    static TransitNetwork.Station tooCloseToMaster(List<TransitNetwork.Station> sameKind, TransitNetwork.Station pending, Vector3i master) {
        List<TransitNetwork.Station> others = new ArrayList<>(sameKind);
        others.remove(pending);
        return PlaceMasterInteraction.closest(others, master);
    }

    /** The type of the structure a plan places: the pad or pier end ("Pad") or the whole station ("Full"). Null for an unknown kind or model. Pure. */
    static Deployables.Kind structureKind(String stationKind, String model) {
        boolean boat = Deployables.BOAT_STATION_KIND.equals(stationKind);
        if (!boat && !Deployables.STATION_KIND.equals(stationKind)) {
            return null;
        }
        if ("Pad".equals(model)) {
            return boat ? Deployables.BOAT_STATION_PIER : Deployables.STATION_PAD;
        }
        if ("Full".equals(model)) {
            return boat ? Deployables.BOAT_STATION : Deployables.STATION;
        }
        return null;
    }

    /** What a cell holds, for the room test of a pad. */
    enum Content { EMPTY, VEGETATION, BLOCKED, UNLOADED }

    /** Content of a cell and the block that fills it (null when empty). */
    record Found(Content content, String block) {
        static final Found EMPTY = new Found(Content.EMPTY, null);
    }

    /** Reads the cells of a room test. */
    interface Probe {
        Found at(int x, int y, int z);
    }

    /** First cell that prevents the placement of a pad, or the cells of vegetation to clear when it fits. */
    record Volume(Vector3i fault, Found found, List<Vector3i> vegetation) {
        boolean fits() {
            return fault == null;
        }
    }

    /**
     * Room test of a balloon pad at the quay anchor: the 7 x 7 pad layer, then the free volume of the station
     * (StationGeometry.inBalloonClearance) from the layer above up to the top of the world, like the lock. Vegetation counts
     * as empty and is listed to be cleared, a section that is not loaded counts as empty above the pad layer. Pure.
     */
    static Volume padVolume(Vector3i quay, Probe probe) {
        List<Vector3i> vegetation = new ArrayList<>();
        int topY = BalloonManager.WORLD_MAX_Y;
        if (BalloonManager.outOfHeight(quay.y)) {
            return new Volume(new Vector3i(quay), new Found(Content.BLOCKED, "world height"), vegetation);
        }
        int reach = 12;
        for (int y = quay.y; y <= topY; y++) {
            for (int x = quay.x - reach; x <= quay.x + reach; x++) {
                for (int z = quay.z - reach; z <= quay.z + reach; z++) {
                    boolean padCell = y == quay.y && Math.abs(x - quay.x) <= 3 && Math.abs(z - quay.z) <= 3;
                    if (!padCell && !StationGeometry.inBalloonClearance(quay, x, y, z)) {
                        continue;
                    }
                    Found f = probe.at(x, y, z);
                    if (f.content() == Content.UNLOADED && y > quay.y) {
                        continue; // a section that is not loaded reads as empty above the pad
                    }
                    if (f.content() == Content.BLOCKED || f.content() == Content.UNLOADED) {
                        return new Volume(new Vector3i(x, y, z), f, vegetation);
                    }
                    if (f.content() == Content.VEGETATION) {
                        vegetation.add(new Vector3i(x, y, z));
                    }
                }
            }
        }
        return new Volume(null, null, vegetation);
    }

    /**
     * First cell of the corridor of the shuttle (StationGeometry.inBoatCorridor) that is neither water nor air, null when the corridor
     * is clear. Pure: state reads one cell as 0 empty, 1 water, 2 anything else.
     */
    static Vector3i corridorFault(Vector3i pierEnd, Rotation rotation, int surfaceY, DeployPlacement.CellState state) {
        for (int lz = 1; lz <= 20; lz++) {
            for (int lx = -8; lx <= 8; lx++) {
                Vector3i flat = rotation.rotateYaw(new Vector3i(lx, 0, lz), new Vector3i());
                for (int y = surfaceY - 1; y <= surfaceY + 8; y++) {
                    int x = pierEnd.x + flat.x;
                    int z = pierEnd.z + flat.z;
                    if (state.at(x, y, z) == 2) {
                        return new Vector3i(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ interaction

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            place(context);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Transit_PlaceQuay failed");
            Telemetry.error("deploy", e, stationKind, null);
            fail(context);
        }
    }

    private void place(InteractionContext context) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> player = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        TransformComponent transform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        Deployables.Kind kind = structureKind(stationKind, model);
        if (playerRef == null || transform == null || kind == null || prefabPath == null || !prefabPath.equals(kind.prefabPath)) {
            LOGGER.at(Level.WARNING).log("Transit_PlaceQuay: unexpected StationKind, Model or PrefabPath: %s, %s, %s", stationKind, model, prefabPath);
            fail(context);
            return;
        }
        boolean boat = Deployables.BOAT_STATION_KIND.equals(stationKind);
        boolean full = "Full".equals(model);
        BlockPosition clientTarget = context.getTargetBlock();
        // The aimed block is read by the same ray as the held-plan preview, so the plan lands where its preview stood; the block
        // sent with the interaction can be a neighbour of it, depending on the face hit.
        Vector3i ray = TargetUtil.getTargetBlock(player, HeldCratePreview.REACH, commandBuffer);
        BlockPosition target = ray != null ? new BlockPosition(ray.x, ray.y, ray.z) : clientTarget;
        if (ray != null && clientTarget != null && (ray.x != clientTarget.x || ray.y != clientTarget.y || ray.z != clientTarget.z)) {
            LOGGER.at(Level.INFO).log("Transit_PlaceQuay: aimed block (%d, %d, %d) read by the ray, interaction sent (%d, %d, %d)",
                    ray.x, ray.y, ray.z, clientTarget.x, clientTarget.y, clientTarget.z);
        }
        if (target == null) {
            playerRef.sendMessage(Texts.t("transit.quay.noTarget"));
            fail(context);
            return;
        }
        World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
        UUID uuid = playerRef.getUuid();
        boolean op = StructureRemoval.isOp(uuid);
        List<TransitNetwork.Station> sameKind = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().pending(world.getName())) {
            if (s.kind.equals(stationKind)) {
                sameKind.add(s);
            }
        }
        TransitNetwork.Station pending = nearestPending(sameKind, uuid, op, transform.getPosition(), STATION_REACH);
        if (pending == null) {
            playerRef.sendMessage(Texts.t("transit.quay.noStation"));
            fail(context);
            return;
        }

        // Same computation as the held-plan preview (shared helper).
        DeployPlacement.Placement placement = DeployPlacement.compute(new Vector3i(target.x, target.y, target.z),
                transform.getPosition(), transform.getRotation().y, offset, rotationOffset);
        Rotation rotation = !boat && !full ? Rotation.None : placement.rotation();
        Vector3i origin = placement.origin();
        int deckRotation = DeployPlacement.SLAB_UNTURNED;
        if (boat) {
            DeployPlacement.CellState cells = DeployPlacement.worldState(world);
            if (!full) {
                // The rotation follows the player's pier around the aimed block, the look only breaks ties (same as the preview).
                rotation = DeployPlacement.pierPlanRotation(cells, origin, new Vector3i(target.x, target.y, target.z), rotation);
                // The aimed block is the one behind the pier end; the origin follows the water surface under the pier end.
                origin = DeployPlacement.pierPlanSeaOrigin(cells, origin, rotation);
                // The deck slabs take the rotation of the aimed slab, else the unturned one (same as the preview).
                deckRotation = DeployPlacement.pierDeckRotation(world, new Vector3i(target.x, target.y, target.z));
            } else {
                // The origin follows the water surface under the pier end, whatever the height of the bank.
                origin = DeployPlacement.boatStationOrigin(cells, origin, rotation, Deployables.BOAT_STATION_PIER_END);
            }
            if (origin == null) {
                LOGGER.at(Level.INFO).log("Quay refused: no water under the end of the pier");
                playerRef.sendMessage(Texts.t("deploy.steamboat.noWater"));
                fail(context);
                return;
            }
        }
        StationGeometry.Converted conv = full ? StationGeometry.convert(stationKind, origin, rotation) : null;
        Vector3i quay = full ? conv.quay() : origin;

        String refusal = refusal(pending, uuid, op, quay, stationKind, full);
        if (refusal != null) {
            playerRef.sendMessage(Texts.t(refusal));
            fail(context);
            return;
        }

        if (full) {
            // The master walks into the hut: the 64-block rule is read again on its final cell.
            List<TransitNetwork.Station> sameKindAll = new ArrayList<>();
            for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
                if (s.kind.equals(stationKind)) {
                    sameKindAll.add(s);
                }
            }
            TransitNetwork.Station close = tooCloseToMaster(sameKindAll, pending, conv.master());
            if (close != null) {
                playerRef.sendMessage(Texts.t("transit.deploy.tooClose").param("name", close.name));
                fail(context);
                return;
            }
        }
        Deployables.Kind quayKind = boat ? Deployables.BOAT_STATION_PIER : Deployables.STATION_PAD;
        StructureShape shape;
        StructureShape quayShape;
        Room room;
        try {
            shape = kind.shape();
            quayShape = quayKind.shape();
            room = roomRefusal(world, shape, origin, rotation, quay, boat, full);
            if (room.refusal() != null) {
                StationPlacement.send(room.refusal(), playerRef);
                fail(context);
                return;
            }
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Transit_PlaceQuay: unreadable shape");
            Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            fail(context);
            return;
        }
        Path path = PrefabStore.get().findAssetPrefabPath(kind.prefabPath);
        Path quayPath = full ? PrefabStore.get().findAssetPrefabPath(quayKind.prefabPath) : path;
        if (path == null || quayPath == null) {
            LOGGER.at(Level.WARNING).log("Transit_PlaceQuay: prefab not found: %s", kind.prefabPath);
            fail(context);
            return;
        }

        // Everything is accepted: the vegetation of the free volume goes, then the paste.
        for (Vector3i c : room.clear()) {
            SectionBlocks.setAt(world.getChunkStore(), c.x, c.y, c.z, BlockType.EMPTY_KEY, 4); // 4: no particles
        }
        if (!room.dig().isEmpty() && !PierDredge.dig(world, room.dig(), room.water(), quay)) {
            // Nothing is pasted yet: the station is not half placed, the cells dug stay as water.
            fail(context);
            return;
        }
        boolean joined = false;
        try {
            // Wood and stone follow the biome and zone (read before the paste, applied after it).
            StationPlacement.Look look = StationPlacement.readLook(world, kind, origin);
            IPrefabBuffer buffer = PrefabBufferUtil.getCached(path);
            int keepLayerY = full && !boat ? Deployables.STATION_GRASS_Y : Integer.MIN_VALUE;
            // A whole station inscribes only its quay: the pad or pier end, pasted again below like a plan of the quay alone.
            BalloonManager.pasteKeepingTerrain(buffer, world, shape, origin, rotation, 1 | 8, commandBuffer, kind.id,
                    Ownership.of(uuid, null), keepLayerY, !full);
            StationPlacement.applyLook(world, shape, origin, rotation, kind, look);
            if (boat && !full) {
                StationPlacement.applyPierDeckRotation(world, shape, origin, rotation, deckRotation);
            }
            if (full) {
                StationPlacement.Look quayLook = StationPlacement.readLook(world, quayKind, quay);
                BalloonManager.pasteKeepingTerrain(PrefabBufferUtil.getCached(quayPath), world, quayShape, quay, rotation, 1 | 8, commandBuffer,
                        quayKind.id, Ownership.of(uuid, null), Integer.MIN_VALUE, true);
                StationPlacement.applyLook(world, quayShape, quay, rotation, quayKind, quayLook);
            }

            // The station stays out of the network until its name window ends (completeNaming).
            pending.naming = true;
            joined = full
                    ? TransitNetwork.get().setQuay(pending, quay, rotation, conv.master(), conv.masterYaw())
                    : TransitNetwork.get().setQuay(pending, quay, rotation);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Quay of '%s' not placed", pending.name);
            Telemetry.error("deploy", e, stationKind, null);
        }
        if (!joined) {
            pending.naming = false;
            LOGGER.at(Level.WARNING).log("Station '%s' did not receive its quay: the pasted quay is removed", pending.name);
            removeQuay(commandBuffer, world, quayKind, quayShape, quay, rotation);
            fail(context);
            return;
        }
        Telemetry.count("station.quayPlaced");
        LOGGER.at(Level.INFO).log("Quay of '%s' placed at (%d, %d, %d) rotation %s (%s) by %s", pending.name, quay.x, quay.y, quay.z, rotation,
                model, playerRef.getUsername());
        final TransitNetwork.Station station = pending;
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                if (full) {
                    // The master walks into the hut of the whole station: the same NPC is created again at the new cell.
                    StationMaster.moveMaster(store, world, station);
                }
                openNameWindow(world, playerRef, station);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station '%s': master or name window failed", station.name);
                Telemetry.error("deploy", e, stationKind, null);
                StationNamePage.onPlayerGone(uuid);
                StationPlacement.completeNaming(world, station, null, playerRef);
            }
        });
    }

    /** Takes a pasted pad or pier back out with its registry entry (the quay could not join the network). Never throws. */
    private static void removeQuay(CommandBuffer<EntityStore> commandBuffer, World world, Deployables.Kind quayKind, StructureShape quayShape,
                                   Vector3i quay, Rotation rotation) {
        try {
            StructureRemoval.Result r = StructureRemoval.remove(commandBuffer, world, quayKind, quayShape, quay, rotation);
            if (r.error() != null) {
                // The registry entry goes in any case: an orphan entry would lock blocks of a station that does not exist.
                BalloonRegistry.remove(world, quay, rotation, quayKind.id);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pasted quay at (%d, %d, %d) not removed", quay.x, quay.y, quay.z);
            Telemetry.error("deploy", e, Telemetry.kindId(quayKind), null);
        }
    }

    /**
     * Result of the room test: the refusal to send (null when it fits), the cells of vegetation to clear just before the paste and,
     * for a pier end, the cells of the basin to dig with their water.
     */
    private record Room(Refusal refusal, List<Vector3i> clear, Set<Vector3i> dig, SteamboatWater.Neighbour water) {
        Room(Refusal refusal, List<Vector3i> clear) {
            this(refusal, clear, Set.of(), null);
        }

        static Room refused(Refusal r) {
            return new Room(r, List.of());
        }
    }

    /** Room test of the structure at its origin. Nothing is written: the vegetation to clear is returned. */
    private static Room roomRefusal(World world, StructureShape shape, Vector3i origin, Rotation rotation, Vector3i quay, boolean boat,
                                    boolean full) throws IOException {
        if (boat) {
            // The basin in front of the pier end: a pure plan first, every test then reads its cells as water.
            int surfaceY = surfaceY(quay, rotation);
            PierDredge.Plan plan = PierDredge.plan(quay, rotation, surfaceY, PierDredge.worldCells(world), PierDredge::diggable,
                    p -> BalloonRegistry.find(world, p) != null);
            if (!plan.ok()) {
                return Room.refused(corridorRefusal(world, quay, plan.fault(), plan.faultCell().id()));
            }
            Set<Vector3i> dig = plan.dig();
            SteamboatWater.Neighbour water = null;
            if (!dig.isEmpty()) {
                water = PierDredge.chooseWater(dig, new Vector3i(quay.x, surfaceY, quay.z), p -> PierDredge.worldWater(world, p));
                if (water == null) {
                    LOGGER.at(Level.INFO).log("Pier refused at (%d, %d, %d): no water to dig the basin with", quay.x, quay.y, quay.z);
                    return Room.refused(Refusal.of("deploy.steamboat.noWater"));
                }
            }
            Refusal cells = full ? StationPlacement.boatRoomRefusal(world, shape, origin, rotation, Set.of(), dig)
                    : StationPlacement.boatCellsRefusal(world, shape, origin, rotation, Set.of());
            if (cells != null) {
                return Room.refused(cells);
            }
            Vector3i blocked = corridorFault(quay, rotation, surfaceY, DeployPlacement.withWater(DeployPlacement.worldState(world), dig));
            if (blocked != null) {
                return Room.refused(corridorRefusal(world, quay, blocked, null));
            }
            // The shuttle moored at the future berth must be able to reach open water.
            TransitNetwork.Station future = new TransitNetwork.Station("", world.getName(), new Vector3i(quay), Rotation.None, null, null,
                    new ArrayList<>(), Deployables.BOAT_STATION_KIND, false, true, new Vector3i(quay), 0f, new Vector3i(quay), rotation);
            if (!BoatBerth.hasOpenWaterExit(world, future, dig)) {
                LOGGER.at(Level.INFO).log("Pier refused at (%d, %d, %d): no passage to open water", quay.x, quay.y, quay.z);
                return Room.refused(Refusal.of("transit.deploy.boatNoOpenWater"));
            }
            Refusal berth = full ? null : StationPlacement.berthRefusal(world, StationGeometry.berthOrigin(quay, rotation),
                    StationGeometry.berthRotation(rotation, false), quay, Set.of(), dig);
            return new Room(berth, List.of(), dig, water);
        }
        if (full) {
            Refusal room = StationPlacement.balloonRoomRefusal(world, shape, Deployables.BALLOON_PUBLIC.shape(), origin, rotation,
                    StationPlacement::isVegetation);
            if (room != null) {
                return Room.refused(room);
            }
        }
        SectionReader reader = new SectionReader(world.getChunkStore());
        Volume volume = padVolume(quay, (x, y, z) -> probe(reader, x, y, z));
        if (!volume.fits()) {
            Vector3i f = volume.fault();
            LOGGER.at(Level.INFO).log("Pad refused at (%d, %d, %d): %s at (%d, %d, %d)", quay.x, quay.y, quay.z,
                    volume.found().block(), f.x, f.y, f.z);
            return Room.refused(volume.found().content() == Content.UNLOADED
                    ? Refusal.of("transit.quay.notLoaded")
                    : Refusal.at("transit.quay.blocked", volume.found().block(), f.x, f.y, f.z));
        }
        return new Room(null, volume.vegetation());
    }

    /** Height of the water surface cell in front of a pier end. */
    private static int surfaceY(Vector3i quay, Rotation rotation) {
        return StationGeometry.berthOrigin(quay, rotation).y + Deployables.STEAMBOAT_PUBLIC.airship.waterlineY;
    }

    /** Refusal for the block that stops the corridor of the shuttle at a cell (block null: read it from the world, "?" when unreadable). */
    private static Refusal corridorRefusal(World world, Vector3i quay, Vector3i blocked, String block) {
        if (block == null) {
            block = probe(new SectionReader(world.getChunkStore()), blocked.x, blocked.y, blocked.z).block();
        }
        String name = block != null ? block : "?";
        LOGGER.at(Level.INFO).log("Pier refused at (%d, %d, %d): %s in the corridor at (%d, %d, %d)", quay.x, quay.y, quay.z, name,
                blocked.x, blocked.y, blocked.z);
        return Refusal.at("transit.quay.corridor", name, blocked.x, blocked.y, blocked.z);
    }

    /** Reads a cell of the world for the room test of a pad: unloaded, vegetation, a solid block or a liquid, or empty. */
    private static Found probe(SectionReader reader, int x, int y, int z) {
        if (BalloonManager.outOfHeight(y) || !reader.hasStorageInMemory(x, y, z)) {
            return new Found(Content.UNLOADED, null);
        }
        BlockType bt = SectionBlocks.typeAt(reader, x, y, z);
        String id = bt == null ? null : (bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId());
        if (StationPlacement.isVegetation(id)) {
            return new Found(Content.VEGETATION, id);
        }
        if (bt != null && bt.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid) {
            return new Found(Content.BLOCKED, id);
        }
        String liquid = BalloonManager.liquidAt(reader, x, y, z);
        return liquid != null ? new Found(Content.BLOCKED, liquid) : Found.EMPTY;
    }

    /** Opens the name window for the owner, deferred like the other entity-side work started from an interaction. On the world thread. */
    private static void openNameWindow(World world, PlayerRef playerRef, TransitNetwork.Station station) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            StationPlacement.completeNaming(world, station, null, null);
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) {
            StationPlacement.completeNaming(world, station, null, playerRef);
            return;
        }
        p.getPageManager().openCustomPage(ref, store, new StationNamePage(playerRef, world, station));
    }

    private static void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    @Override
    public String toString() {
        return "PlaceQuayInteraction{stationKind=" + stationKind + ", model=" + model + ", prefabPath=" + prefabPath + "}";
    }
}
