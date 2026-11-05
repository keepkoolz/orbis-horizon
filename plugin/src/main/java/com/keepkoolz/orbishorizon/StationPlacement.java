package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.keepkoolz.orbishorizon.api.Refusal;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Placement helpers of the transit stations, shared by the crate interaction and the generated stations: biome look (wood and
 * stone), room tests, distance to the other stations, dock position and network registration.
 */
final class StationPlacement {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private StationPlacement() {
    }

    /** Wood and stone read from the generator before a paste. A field is null when it does not apply to the kind. */
    record Look(BiomeWood.Wood wood, BiomeStone.Stone stone) {
    }

    /** Reads the wood (boat station: around the pier) or the wood and stone (balloon station: at the origin) of the biome and zone. */
    static Look readLook(World world, Deployables.Kind kind, Vector3i origin) {
        if (kind == Deployables.BOAT_STATION || kind == Deployables.BOAT_STATION_PIER) {
            return new Look(BiomeWood.woodAt(world, origin.x, origin.z, true), null);
        }
        if (Deployables.STATION_KIND.equals(kind.id)) {
            return new Look(BiomeWood.stationWoodAt(world, origin.x, origin.z, true), BiomeStone.stoneAt(world, origin.x, origin.z));
        }
        if (kind == Deployables.STATION_PAD) {
            return new Look(null, BiomeStone.stoneAt(world, origin.x, origin.z));
        }
        return new Look(null, null);
    }

    /** Registers then rewrites the wood (and stone) of a pasted station, registry first so a failure leaves no renamed blocks unrecorded. */
    static void applyLook(World world, StructureShape station, Vector3i origin, Rotation rotation, Deployables.Kind kind, Look look) {
        if (kind == Deployables.BOAT_STATION || kind == Deployables.BOAT_STATION_PIER) {
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
                    LOGGER.at(Level.INFO).log("Sea station: %d block(s) rewritten as %s", changed, wood.species());
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Sea station wood not applied");
                Telemetry.error("deploy", e, Telemetry.kindId(kind), null);
            }
        } else if (Deployables.STATION_KIND.equals(kind.id) || kind == Deployables.STATION_PAD) {
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
            LOGGER.at(Level.INFO).log("Station: %d block(s) rewritten (wood %s, stone %s)", changed,
                    customWood ? wood.species() : "d'origine", customStone ? stone.family() : "d'origine");
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station wood and stone not applied");
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

    /**
     * Sets the rotation index of the deck slabs of a pasted pier end plan (the cells at local height 0 whose prefab block is a
     * half slab), keeping the block each cell holds after the biome look. The block ids and the registries are untouched: the
     * cells stay the same blocks of the structure. Runs on the world thread, after the paste and {@link #applyLook}. Returns the
     * number of cells rewritten. Never throws.
     */
    static int applyPierDeckRotation(World world, StructureShape base, Vector3i origin, Rotation rotation, int slabRotation) {
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            int changed = 0;
            for (StructureShape.Cell c : base.cells()) {
                if (c.filler() != 0 || c.y() != 0 || !DeployPlacement.isPierDeckSlab(c.baseName())) {
                    continue;
                }
                Vector3i p = c.rotated(rotation).add(origin);
                BlockType type = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
                if (type == null || !DeployPlacement.isPierDeckSlab(type.getId())) {
                    continue;
                }
                if (SectionBlocks.setAt(world.getChunkStore(), p.x, p.y, p.z, reader.getBlock(p.x, p.y, p.z), type, slabRotation, 0, 4)) { // 4: no particles
                    changed++;
                }
            }
            LOGGER.at(Level.INFO).log("Pier end: %d deck slab(s) set to rotation index %d", changed, slabRotation);
            return changed;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pier end deck slabs not turned");
            Telemetry.error("deploy", e, "pier-deck-rotation", null);
            return 0;
        }
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
                LOGGER.at(Level.WARNING).log("Station: unknown block %s", name);
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

    /** Sends the refusal messages to the player. */
    static void send(Refusal r, PlayerRef player) {
        if (r.key() == null) {
            return;
        }
        com.hypixel.hytale.server.core.Message m = Texts.t(r.key());
        if (r.block() != null) {
            m = m.param("block", r.block());
        }
        if (r.at() != null) {
            m = m.param("x", r.at().x).param("y", r.at().y).param("z", r.at().z);
        }
        player.sendMessage(m);
        if (r.detailKey() != null) {
            player.sendMessage(Texts.t(r.detailKey()));
        }
    }

    /**
     * Room test of the balloon station: station cells above the grass layer and the docked balloon, no liquid. Null when there is
     * room, else the refusal.
     */
    static Refusal balloonRoomRefusal(World world, StructureShape station, StructureShape balloon, Vector3i origin, Rotation rotation) {
        return balloonRoomRefusal(world, station, balloon, origin, rotation, id -> false);
    }

    /** Position of the docked balloon (prefab origin of the balloon) for a station at origin. */
    static Vector3i dockOrigin(Vector3i origin, Rotation rotation) {
        return rotation.rotateYaw(new Vector3i(Deployables.STATION_DOCK_ORIGIN[0], Deployables.STATION_DOCK_ORIGIN[1],
                Deployables.STATION_DOCK_ORIGIN[2]), new Vector3i()).add(origin);
    }

    /** Pure rule: a block refuses the room when it is solid, unless the predicate treats its id as empty (vegetation to be cleared). */
    static boolean blocksRoom(String blockId, boolean solid, Predicate<String> treatAsEmpty) {
        return solid && !treatAsEmpty.test(blockId);
    }

    /** Same test; blocks whose id satisfies treatAsEmpty count as empty (nothing is cleared by the test). */
    static Refusal balloonRoomRefusal(World world, StructureShape station, StructureShape balloon, Vector3i origin, Rotation rotation,
                                      Predicate<String> treatAsEmpty) {
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
            LOGGER.at(Level.INFO).log("Station refused at (%d, %d, %d): liquid at (%d, %d, %d)", origin.x, origin.y, origin.z, wet.x, wet.y, wet.z);
            return Refusal.of("deploy.underwater");
        }
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (Vector3i p : cells) {
            if (BalloonManager.outOfHeight(p.y)) {
                LOGGER.at(Level.INFO).log("Station refused at (%d, %d, %d): cell (%d, %d, %d) exceeds the height limit",
                        origin.x, origin.y, origin.z, p.x, p.y, p.z);
                return Refusal.of("deploy.heightLimit");
            }
            boolean loaded = reader.hasStorageInMemory(p.x, p.y, p.z);
            BlockType bt = loaded ? SectionBlocks.typeAt(reader, p.x, p.y, p.z) : null;
            if (!loaded || (bt != null && blocksRoom(bt.getId(), bt.getMaterial() == BlockMaterial.Solid, treatAsEmpty))) {
                LOGGER.at(Level.INFO).log("Station refused at (%d, %d, %d): %s at (%d, %d, %d)",
                        origin.x, origin.y, origin.z, loaded ? bt.getId() : "chunk not loaded", p.x, p.y, p.z);
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
        Refusal cells = boatCellsRefusal(world, station, origin, rotation, emptied);
        if (cells != null) {
            return cells;
        }
        Vector3i berth = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                Deployables.BOAT_STATION_BERTH_ORIGIN[2]), new Vector3i()).add(origin);
        return berthRefusal(world, berth, rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION), origin, emptied, dredged);
    }

    /** Cell test of a boat structure (a pier): the cells at y 0 and above hold no solid block and no liquid, the cells under y 0 (posts) accept anything. Null when it fits. */
    static Refusal boatCellsRefusal(World world, StructureShape station, Vector3i origin, Rotation rotation, Set<Vector3i> emptied) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        for (StructureShape.Cell c : station.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            if (BalloonManager.outOfHeight(p.y)) {
                LOGGER.at(Level.INFO).log("Sea station refused at (%d, %d, %d): cell (%d, %d, %d) exceeds the height limit",
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
                LOGGER.at(Level.INFO).log("Sea station refused at (%d, %d, %d): %s at (%d, %d, %d)",
                        origin.x, origin.y, origin.z, solid ? bt.getId() : liquidId, p.x, p.y, p.z);
                String what = solid ? (bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId()) : liquidId;
                return Refusal.at("transit.deploy.boatNoSpaceAt", what, p.x, p.y, p.z);
            }
        }
        return null;
    }

    /**
     * Test of the berth of the station boat: the shuttle laid at berth with its rotation must fit on clear water (hull cells in
     * water, the rest empty). origin is only the station origin named in the log. Null when the shuttle fits, else the refusal.
     */
    static Refusal berthRefusal(World world, Vector3i berth, Rotation berthRotation, Vector3i origin, Set<Vector3i> emptied,
                                Set<Vector3i> dredged) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        StructureShape boat;
        try {
            boat = Deployables.STEAMBOAT_PUBLIC.shape();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Sea station: unreadable shuttle shape");
            Telemetry.error("deploy", e, Telemetry.kindId(Deployables.BOAT_STATION), null);
            return new Refusal(null, null, null, null);
        }
        DeployPlacement.CellState state = DeployPlacement.worldState(world);
        DeployPlacement.CellState read = emptied.isEmpty() ? state : DeployPlacement.withEmptied(state, emptied);
        DeployPlacement.Fault fault = DeployPlacement.steamboatFault(boat, berth, berthRotation,
                dredged.isEmpty() ? read : DeployPlacement.withWater(read, dredged), 0);
        if (fault != null) {
            Vector3i at = fault.at();
            BlockType bt = SectionBlocks.typeAt(reader, at.x, at.y, at.z);
            String liquidId = BalloonManager.liquidAt(reader, at.x, at.y, at.z);
            String found = bt != null && bt.getMaterial() == BlockMaterial.Solid ? bt.getId() : liquidId != null ? liquidId
                    : bt != null ? bt.getId() : "air";
            String block = found.startsWith("*") ? found.substring(1) : found;
            LOGGER.at(Level.INFO).log("Sea station refused at (%d, %d, %d): docking post (%s), %s at (%d, %d, %d), %s",
                    origin.x, origin.y, origin.z, fault.key(), block, at.x, at.y, at.z, fault.hull() ? "hull" : "out of the water");
            return new Refusal("transit.deploy.boatBerth", block, new Vector3i(at), fault.key(), fault.hull());
        }
        return null;
    }

    /** True for a trunk, foliage, plant, decorative pebble or tree decoration block (a leading * is ignored): vegetation that is cleared where a quay is placed. Same rule as the generated stations. */
    static boolean isVegetation(String blockId) {
        return com.keepkoolz.orbishorizon.api.BuiltBlocks.removable(blockId);
    }

    /**
     * Bookkeeping of a station that just joined the network once its name window ended: command suggestions, the owner knows it, the
     * sea chart of a boat station. On any thread.
     */
    static void quayJoined(World world, TransitNetwork.Station s) {
        StationCommand.StationNameType.stationsChanged();
        if (s.owner != null) {
            KnownStations.learn(world.getName(), s.owner, s);
        }
        SeaCharts.stationAdded(world, s);
    }

    /**
     * Ends the naming of a station that received its quay: the given name when it is still valid (null or refused: the automatic
     * name stays), the master's display name follows, the station joins the network (its naming flag is cleared), then the player is told. On any thread, the master update goes to the world thread.
     */
    static void completeNaming(World world, TransitNetwork.Station station, String name, PlayerRef player) {
        try {
            if (name != null && !name.equals(station.name) && TransitNetwork.get().rename(station, name)) {
                StationCommand.StationNameType.stationsChanged();
                world.execute(() -> StationMaster.refreshName(world.getEntityStore().getStore(), world, station));
            }
        } finally {
            // The name window is over: the station now joins the network (destinations, queues, map, discovery).
            station.naming = false;
            quayJoined(world, station);
        }
        try {
            if (player != null) {
                player.sendMessage(Texts.t("transit.deploy.registered").param("name", station.name));
            }
        } catch (RuntimeException ignored) {
            // the player may be gone
        }
        if (player != null && station.owner != null && Deployables.BOAT_STATION_KIND.equals(station.kind)) {
            // The owner of a new maritime station receives the arrival point plan (world thread).
            world.execute(() -> {
                try {
                    Ref<EntityStore> ref = player.getReference();
                    if (ref != null && ref.isValid()) {
                        PlaceArrivalInteraction.giveArrivalPlan(ref.getStore(), ref, player);
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Arrival point plan not given for '%s'", station.name);
                    Telemetry.error("transit", e, null, null);
                }
            });
        }
        LOGGER.at(Level.INFO).log("Station \"%s\" named", station.name);
    }

    /** First station of the same kind closer than min blocks (master cell of the other station to the given cell), null if none. Other kinds are ignored. */
    static TransitNetwork.Station tooClose(List<TransitNetwork.Station> stations, String kind, Vector3i origin, double min) {
        for (TransitNetwork.Station other : stations) {
            if (!other.kind.equals(kind)) continue;
            if (new org.joml.Vector3d(other.master.x, other.master.y, other.master.z)
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
            chosen = registerStation(world.getName(), origin, rotation, owner, wanted, kind);
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
        LOGGER.at(Level.INFO).log("Station \"%s\" registered at (%d, %d, %d)", chosen, origin.x, origin.y, origin.z);
    }

    /**
     * Creates the station in the network under the given name (null = auto name "Station n"), saves it and returns the name.
     * The master NPC (masterUuid) is set later. completeRegistration calls this once the master's name is validated.
     */
    static String registerStation(String world0, Vector3i origin, Rotation rotation, UUID owner, String name, String kind) {
        TransitNetwork net = TransitNetwork.get();
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
}
