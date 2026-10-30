package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/** Builds a generated station without a player: same room tests and paste as the crate, then registration with a generated name and no owner. */
final class StationBuilder {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** The ground scan starts this far above the generator height and stops this far below it. */
    private static final int SCAN_UP = 8;
    private static final int SCAN_DOWN = 24;
    /** Vertical margin of the loaded box around the estimated station, covering the ground scan and the boat origin search. */
    private static final int BOX_MARGIN_UP = 12;
    private static final int BOX_MARGIN_DOWN = SCAN_DOWN + 4;
    /** A section load that has not completed after this long counts as failed. */
    private static final int LOAD_TIMEOUT_S = 60;
    /**
     * Horizontal margin, in blocks, added around the box the build writes when the scan for player constructions runs. A
     * construction closer than this to the station is refused.
     */
    static final int BUILT_MARGIN = 4;
    /** Block write flag: no particles. */
    private static final int SET_NO_PARTICLES = 4;

    private StationBuilder() {
    }

    /** Builds the station of a planned entry. Call on the world thread. Never throws. */
    static void build(World world, GeneratedStations.Entry e) {
        build(world, e, true);
    }

    /** Timed: the section load is asynchronous and ends the call, only the work done on the world thread is measured. */
    private static void build(World world, GeneratedStations.Entry e, boolean mayLoad) {
        long t0 = Telemetry.start();
        try {
            buildUntimed(world, e, mayLoad);
        } finally {
            Telemetry.timing("worldgen.station", t0, e.kind);
        }
    }

    private static void buildUntimed(World world, GeneratedStations.Entry e, boolean mayLoad) {
        WorldStationPlanner.Site planned = null;
        try {
            if (e.state != GeneratedStations.State.PLANNED) {
                return;
            }
            // One snapshot of the active site: the registry moves x, z and rotation separately.
            final WorldStationPlanner.Site site = GeneratedStations.get().activeSite(e);
            planned = site;
            boolean boat = Deployables.BOAT_STATION_KIND.equals(e.kind);
            Deployables.Kind kind = boat ? Deployables.BOAT_STATION : Deployables.STATION;
            StructureShape station = kind.shape();
            StructureShape balloon = boat ? null : Deployables.BALLOON_PUBLIC.shape();
            StructureShape shuttle = boat ? Deployables.STEAMBOAT_PUBLIC.shape() : null;
            Path path = PrefabStore.get().findAssetPrefabPath(kind.prefabPath);
            if (path == null) {
                abandon(e, "prefab not found");
                return;
            }
            if (!(world.getChunkStore().getGenerator() instanceof ChunkGenerator generator)) {
                abandon(e, "unknown world generator");
                return;
            }
            int seed = (int) world.getWorldConfig().getSeed();
            int estimatedY = boat ? SeaRules.SEA_LEVEL + 1 : generator.getHeight(seed, site.x(), site.z()) + 1;
            Vector3i guess = new Vector3i(site.x(), estimatedY, site.z());

            int[] box = box(station, balloon, shuttle, guess, site.rotation());
            // On terrain explored before the mod, the box is read for player constructions: the loaded box then holds the margin too.
            boolean scan = scanWanted(GenerationListener.newChunksOf(world), e.kind, site);
            // One read of the area per build attempt (lazy), shared by the site check and every repair try of a pier.
            BuiltScan builtScan = new BuiltScan(world, () -> scanArea(station, balloon, shuttle, guess, site, boat));
            ChunkStoreBox cb = new ChunkStoreBox(scan ? widen(box, BUILT_MARGIN) : box);
            if (!cb.loaded(world)) {
                if (!mayLoad) {
                    nextSiteOrAbandon(world, e, site, "sections not loaded");
                    return;
                }
                cb.load(world).copy().orTimeout(LOAD_TIMEOUT_S, TimeUnit.SECONDS).whenComplete((v, t) -> {
                    try {
                        if (t != null) {
                            Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                            if (cause instanceof TimeoutException) {
                                LOGGER.at(Level.WARNING).log("%s: section loading not finished after %d s at (%d, %d)", GenerationListener.logName(e.kind),
                                        LOAD_TIMEOUT_S, site.x(), site.z());
                            } else {
                                LOGGER.at(Level.WARNING).withCause(cause).log("%s: cannot load the sections at (%d, %d)", GenerationListener.logName(e.kind),
                                        site.x(), site.z());
                            }
                            Telemetry.error("worldgen", cause, e.kind, "load");
                        }
                        world.execute(() -> resumeAfterLoad(world, e, site));
                    } catch (RuntimeException ex) {
                        LOGGER.at(Level.WARNING).withCause(ex).log("%s: cannot resume at (%d, %d)", GenerationListener.logName(e.kind), site.x(), site.z());
                        Telemetry.error("worldgen", ex, e.kind, "build");
                        abandon(e, "cannot resume");
                    }
                });
                return;
            }

            if (scan) {
                BuiltBlocks.Found built = builtNear(world, builtScan, box);
                if (built != null) {
                    LOGGER.at(Level.INFO).log("%s: site %d/%d refused at (%d, %d): construction %s at (%d, %d, %d)", GenerationListener.logName(e.kind),
                            e.active + 1, e.sites.size(), site.x(), site.z(), built.block(), built.x(), built.y(), built.z());
                    nextSite(world, e);
                    return;
                }
            }

            // The site the station is built at, and for a boat how it was found: first, fallback or repaired.
            WorldStationPlanner.Site at = site;
            String boatOutcome = null;
            Vector3i origin;
            BoatAttempt boatPlan = null;
            if (boat) {
                BoatAttempt attempt = boatAttempt(world, station, shuttle, kind, site);
                boatOutcome = e.active > 0 ? "fallback" : "first";
                if (attempt.origin() == null) {
                    BoatAttempt refused = attempt;
                    BoatRepair repair = repairBoat(world, e, station, shuttle, site, builtScan);
                    attempt = repair.found();
                    if (attempt != null) {
                        boatOutcome = "repaired";
                    }
                    if (attempt == null) {
                        refuseBoat(world, e, site, repair.tries(), refused);
                        return;
                    }
                    at = attempt.site();
                }
                origin = attempt.origin();
                boatPlan = attempt;
                writeBoatGround(world, origin, boatPlan);
            } else {
                int ground = groundY(world, site.x(), site.z(), generator.getHeight(seed, site.x(), site.z()));
                if (ground == Integer.MIN_VALUE) {
                    nextSiteOrAbandon(world, e, site, "no ground under the station");
                    return;
                }
                origin = new Vector3i(site.x(), ground + 1, site.z());
                // No irreversible change before every refusal is known: distance, then room (vegetation counts as empty), then clearing.
                TransitNetwork.Station nearAir = DeployStationInteraction.tooClose(TransitNetwork.get().stations(world.getName()), kind.id,
                        origin, TransitConstants.MIN_STATION_DISTANCE);
                if (nearAir != null) {
                    nextSiteOrAbandon(world, e, site, "too close to \"" + nearAir.name + "\"");
                    return;
                }
                DeployStationInteraction.Refusal refusal = DeployStationInteraction.balloonRoomRefusal(world, station, balloon, origin,
                        site.rotation(), true);
                if (refusal != null) {
                    nextSiteOrAbandon(world, e, site, refusal.key() != null ? refusal.key() : "refusal without key");
                    return;
                }
                int cleared = GenClear.clearBox(world, Deployables.STATION.placeMin, Deployables.STATION.placeMax, origin, site.rotation());
                cleared += GenClear.clear(world, balloon, DeployStationInteraction.dockOrigin(origin, site.rotation()), site.rotation());
                LOGGER.at(Level.INFO).log("Generated air station: %d cell(s) of vegetation cleared at (%d, %d, %d)", cleared, origin.x, origin.y, origin.z);
            }

            DeployStationInteraction.Look look = DeployStationInteraction.readLook(world, kind, origin);
            // Always forced: the room was checked above, so the paste must not skip cells.
            BalloonManager.pasteKeepingTerrain(PrefabBufferUtil.getCached(path), world, station, origin, at.rotation(), 1 | 8,
                    world.getEntityStore().getStore(), kind.id, Ownership.of(null, null),
                    boat ? Integer.MIN_VALUE : Deployables.STATION_GRASS_Y);
            DeployStationInteraction.applyLook(world, station, origin, at.rotation(), kind, look);

            if (boat) {
                // The check on the real blocks, after the paste: the station master must stand on solid ground with two free cells.
                Vector3i fault = BoatSiteGround.masterFault(origin, at.rotation(), groundState(world));
                if (fault != null) {
                    LOGGER.at(Level.WARNING).log("%s: station master without ground at (%d, %d, %d), site %d/%d refused", GenerationListener.logName(e.kind), fault.x, fault.y,
                            fault.z, e.active + 1, e.sites.size());
                    Telemetry.error("worldgen", new IllegalStateException("station master without ground at (" + fault.x + ", " + fault.y + ", "
                            + fault.z + ")"), e.kind, "master");
                    // The pasted structure is registered and locked: release it so that no locked ruin stays behind.
                    BalloonRegistry.remove(world, origin, at.rotation(), StationSpec.of(kind.id).structure().id);
                    nextSite(world, e);
                    return;
                }
            }

            String name = nameFor(world, generator, seed, e.kind, at.x(), at.z(), origin);
            DeployStationInteraction.completeRegistration(world, origin, at.rotation(), null, name, null, kind.id);
            if (!at.equals(site)) {
                // A repaired station stands elsewhere than planned: the registry follows it (lookups, spacing, footprint).
                GeneratedStations.get().setBuiltSite(e, at.x(), at.z(), at.rotation());
            }
            GeneratedStations.get().setState(e, GeneratedStations.State.BUILT);
            Telemetry.count(boat ? "worldgen.station.boat" : "worldgen.station.air");
            if (boat) {
                Telemetry.count("worldgen.boat.built." + boatOutcome);
            } else {
                Telemetry.count("worldgen.air.built." + (e.active > 0 ? "fallback" : "first"));
            }
            LOGGER.at(Level.INFO).log("%s '%s' built at (%d, %d, %d)", GenerationListener.logName(e.kind), name, origin.x, origin.y, origin.z);
        } catch (IOException | RuntimeException | LinkageError ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("%s: cannot build at (%d, %d)", GenerationListener.logName(e.kind),
                    planned == null ? e.x : planned.x(), planned == null ? e.z : planned.z());
            Telemetry.error("worldgen", ex, e.kind, "build");
            abandon(e, "build error");
        }
    }

    /**
     * World thread, after the section load requested for a site: builds without loading again, or, when the entry moved to another
     * site meanwhile, releases it and decides it again on its new site. Never throws.
     */
    private static void resumeAfterLoad(World world, GeneratedStations.Entry e, WorldStationPlanner.Site requested) {
        try {
            if (e.state == GeneratedStations.State.PLANNED && !requested.equals(GeneratedStations.get().activeSite(e))) {
                LOGGER.at(Level.INFO).log("%s: site changed during loading at (%d, %d), deciding again", GenerationListener.logName(e.kind),
                        requested.x(), requested.z());
                GenerationListener.decideAgain(world, e);
                return;
            }
            build(world, e, false);
        } catch (RuntimeException | LinkageError ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("%s: cannot resume at (%d, %d)", GenerationListener.logName(e.kind), requested.x(), requested.z());
            Telemetry.error("worldgen", ex, e.kind, "build");
            abandon(e, "cannot resume");
        }
    }

    /**
     * The outcome of the checks of one boat site. The origin is null on a refusal, the other fields then name the faulty block, its
     * cell and the part of the boat it hits. The site is the one checked. A successful attempt also holds the cells to clear and the
     * blocks to fill, both null on a refusal.
     */
    private record BoatAttempt(WorldStationPlanner.Site site, Vector3i origin, String block, Vector3i at, String face,
                               Set<Vector3i> clear, Map<Vector3i, String> fill, Set<Vector3i> dredged, SteamboatWater.Neighbour water) {
        BoatAttempt(WorldStationPlanner.Site site, Vector3i origin, String block, Vector3i at, String face) {
            this(site, origin, block, at, face, null, null, Set.of(), null);
        }
    }

    /** Origin of the pier on the water at the end of the pier column, null when there is none. */
    private static Vector3i boatOriginAt(World world, WorldStationPlanner.Site site) {
        return DeployPlacement.boatStationOrigin(DeployPlacement.worldState(world), new Vector3i(site.x(), SeaRules.SEA_LEVEL, site.z()),
                site.rotation());
    }

    /**
     * Origin search, then the dome to clear and the blocks to fill under the hut, room of the pier and of the shuttle with the dome
     * read as empty, distance to the other boat stations and ground under the station master (all simulated). Writes nothing.
     */
    private static BoatAttempt boatAttempt(World world, StructureShape station, StructureShape shuttle, Deployables.Kind kind,
                                           WorldStationPlanner.Site site) {
        Vector3i computed = new Vector3i(site.x(), SeaRules.SEA_LEVEL, site.z());
        Vector3i origin = boatOriginAt(world, site);
        if (origin == null) {
            DeployPlacement.CellState state = DeployPlacement.worldState(world);
            Vector3i blocker = DeployPlacement.boatStationOriginBlocker(state, computed, site.rotation());
            Vector3i cell = blocker != null ? blocker : computed;
            return new BoatAttempt(site, null, blockNameAt(world, cell), cell, "out of the water");
        }
        if (!SeaRules.atSeaLevel(origin)) {
            Vector3i end = site.rotation().rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], Deployables.BOAT_STATION_PIER_END[1],
                    Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
            Vector3i cell = new Vector3i(site.x() + end.x, SeaRules.SEA_LEVEL, site.z() + end.z);
            return new BoatAttempt(site, null, blockNameAt(world, cell), cell, "water at y " + (origin.y - 1) + ", not at sea level");
        }
        DeployPlacement.CellState ground = groundState(world);
        SectionReader reader = new SectionReader(world.getChunkStore());
        Set<Vector3i> clear = BoatSiteGround.clearCells(origin, site.rotation(), shuttle);
        Map<Vector3i, String> fill = BoatSiteGround.fillPlan(origin, site.rotation(), ground, p -> {
            BlockType t = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
            return t != null ? t.getId() : null;
        });
        // Every cell of the station prefab counts as solid (hut floor, planks under it, posts): they are placed with the station.
        Set<Vector3i> prefabCells = new java.util.HashSet<>();
        for (StructureShape.Cell c : station.cells()) {
            prefabCells.add(c.rotated(site.rotation()).add(origin));
        }
        // The shuttle floor cells that are not water are dug and filled with water. They sit under the dome, which starts at origin.y.
        Set<Vector3i> dredged = BoatSiteGround.dredgeCells(shuttleFloor(shuttle, origin, site.rotation()),
                p -> !BalloonManager.outOfHeight(p.y) && reader.hasStorageInMemory(p.x, p.y, p.z), ground);
        if (BoatSiteGround.dredgeConflicts(dredged, fill.keySet(), prefabCells)) {
            dredged = Set.of();
        }
        SteamboatWater.Neighbour water = null;
        if (!dredged.isEmpty()) {
            water = berthWater(reader, dredged, origin, site.rotation());
            if (water == null) {
                Vector3i cell = dredged.iterator().next();
                return new BoatAttempt(site, null, blockNameAt(world, cell), cell, "out of the water");
            }
        }
        DeployStationInteraction.Refusal refusal = DeployStationInteraction.boatRoomRefusal(world, station, origin, site.rotation(), clear,
                dredged);
        if (refusal != null) {
            Vector3i cell = refusal.at() != null ? refusal.at() : origin;
            String face = refusal.hull() == null ? "station footprint" : refusal.hull() ? "hull" : "out of the water";
            String block = refusal.block() != null ? refusal.block() : refusal.key() != null ? refusal.key() : "boat shape unreadable";
            return new BoatAttempt(site, null, block, cell, face);
        }
        TransitNetwork.Station near = DeployStationInteraction.tooClose(TransitNetwork.get().stations(world.getName()), kind.id, origin,
                TransitConstants.MIN_STATION_DISTANCE);
        if (near != null) {
            return new BoatAttempt(site, null, "station \"" + near.name + "\"", near.origin, "too close");
        }
        Set<Vector3i> solid = new java.util.HashSet<>(fill.keySet());
        solid.addAll(prefabCells);
        Vector3i fault = BoatSiteGround.masterFault(origin, site.rotation(), BoatSiteGround.simulated(ground, clear, solid));
        if (fault != null) {
            return new BoatAttempt(site, null, blockNameAt(world, fault), fault, "station master");
        }
        return new BoatAttempt(site, origin, null, null, null, clear, fill, dredged, water);
    }

    /** Cells of the shuttle at its berth whose prefab y is at most 0 (the floor, held in water), in world coordinates. */
    private static List<Vector3i> shuttleFloor(StructureShape shuttle, Vector3i origin, Rotation rotation) {
        Vector3i berth = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                Deployables.BOAT_STATION_BERTH_ORIGIN[2]), new Vector3i()).add(origin);
        Rotation boatRotation = rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION);
        List<Vector3i> out = new ArrayList<>();
        for (StructureShape.Cell c : shuttle.cells()) {
            if (c.y() <= 0) {
                out.add(c.rotated(boatRotation).add(berth));
            }
        }
        return out;
    }

    /**
     * Fluid and level to dig the berth with: a water cell next to a dredged cell (same layer, outside the dredge), a source fluid
     * first then the highest level, else the water at the foot of the pier end column. Null when there is none.
     */
    private static SteamboatWater.Neighbour berthWater(SectionReader reader, Set<Vector3i> dredged, Vector3i origin, Rotation rotation) {
        SteamboatWater.Neighbour best = null;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Vector3i p : dredged) {
            for (int[] d : dirs) {
                best = better(best, reader, dredged, p.x + d[0], p.y, p.z + d[1]);
            }
        }
        if (best == null) {
            Vector3i end = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], Deployables.BOAT_STATION_PIER_END[1],
                    Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
            best = better(null, reader, dredged, origin.x + end.x, origin.y - 1, origin.z + end.z);
        }
        return best;
    }

    private static SteamboatWater.Neighbour better(SteamboatWater.Neighbour best, SectionReader reader, Set<Vector3i> dredged, int x, int y, int z) {
        if (dredged.contains(new Vector3i(x, y, z)) || BalloonManager.outOfHeight(y) || !reader.hasStorageInMemory(x, y, z)
                || !SteamboatWater.isWaterCell(reader, x, y, z)) {
            return best;
        }
        int id = reader.getFluidId(x, y, z);
        Fluid f = Fluid.getAssetMap().getAsset(id);
        boolean source = f != null && f.getId().endsWith("Source");
        return SteamboatWater.betterNeighbour(best, new SteamboatWater.Neighbour(id, reader.getFluidLevel(x, y, z), source));
    }

    /**
     * World thread: empties the dome (block and fluid), then fills the columns under the hut, removing their fluid first. Nothing
     * is checked here, the attempt did it all.
     */
    private static void writeBoatGround(World world, Vector3i origin, BoatAttempt plan) {
        long started = System.nanoTime();
        SectionReader reader = new SectionReader(world.getChunkStore());
        int cleared = 0;
        // Highest cell of the dome in each column, from which trees cut by the dome are cleared upward.
        Map<Long, Vector3i> tops = new HashMap<>();
        for (Vector3i p : plan.clear()) {
            tops.merge(((long) p.x << 32) ^ (p.z & 0xffffffffL), p, (a, b) -> b.y > a.y ? b : a);
        }
        for (Vector3i p : plan.clear()) {
            if (BalloonManager.outOfHeight(p.y)) {
                continue;
            }
            // Only cells that hold a block or a fluid are written, so the count is the cells really emptied.
            if (!reader.hasStorageInMemory(p.x, p.y, p.z)) {
                continue;
            }
            boolean block = reader.getBlock(p.x, p.y, p.z) != 0;
            boolean fluid = reader.getFluidId(p.x, p.y, p.z) != Fluid.EMPTY_ID;
            if (!block && !fluid) {
                continue;
            }
            if (block) {
                GenClear.erase(world, p.x, p.y, p.z);
            }
            emptyFluid(world, reader, p);
            cleared++;
        }
        int ceiling = origin.y + BoatSiteGround.DOME_HEIGHT + 12;
        for (Vector3i top : tops.values()) {
            // Trunks and leaves left above the dome would float: keep clearing while the cell is removable and loaded.
            for (int y = top.y + 1; y <= ceiling && !BalloonManager.outOfHeight(y); y++) {
                if (!reader.hasStorageInMemory(top.x, y, top.z) || !GenClear.removableAt(reader, top.x, y, top.z)) {
                    break;
                }
                if (GenClear.erase(world, top.x, y, top.z)) {
                    cleared++;
                }
            }
        }
        int dredged = 0;
        for (Vector3i p : plan.dredged()) {
            if (BalloonManager.outOfHeight(p.y) || !reader.hasStorageInMemory(p.x, p.y, p.z)) {
                continue;
            }
            if (reader.getBlock(p.x, p.y, p.z) != 0) {
                GenClear.erase(world, p.x, p.y, p.z);
            }
            dredged += SectionBlocks.setFluidAt(world.getChunkStore(), p.x, p.y, p.z, plan.water().fluidId(), plan.water().level()) ? 1 : 0;
        }
        if (dredged > 0) {
            Telemetry.count("worldgen.boat.dredged");
        }
        int filled = 0;
        for (Map.Entry<Vector3i, String> f : plan.fill().entrySet()) {
            Vector3i p = f.getKey();
            if (BalloonManager.outOfHeight(p.y)) {
                continue;
            }
            emptyFluid(world, reader, p);
            filled += SectionBlocks.setAt(world.getChunkStore(), p.x, p.y, p.z, f.getValue(), SET_NO_PARTICLES) ? 1 : 0;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        LOGGER.at(Level.INFO).log("Generated sea station: %d cell(s) cleared, %d filled and %d dug in %d ms at (%d, %d, %d)", cleared, filled, dredged, elapsedMs,
                origin.x, origin.y, origin.z);
    }

    private static void emptyFluid(World world, SectionReader reader, Vector3i p) {
        if (reader.hasStorageInMemory(p.x, p.y, p.z) && reader.getFluidId(p.x, p.y, p.z) != Fluid.EMPTY_ID) {
            SectionBlocks.setFluidAt(world.getChunkStore(), p.x, p.y, p.z, Fluid.EMPTY_ID, (byte) 0);
        }
    }

    /**
     * Ground as the boat site checks read it: 2 for a solid block that is not removable vegetation and for a section that is not
     * loaded, 1 for a liquid, 0 otherwise.
     */
    private static DeployPlacement.CellState groundState(World world) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        return (x, y, z) -> {
            if (BalloonManager.outOfHeight(y) || !reader.hasStorageInMemory(x, y, z)) {
                return 2;
            }
            BlockType t = SectionBlocks.typeAt(reader, x, y, z);
            if (t != null && t.getMaterial() == BlockMaterial.Solid && !GenClear.removable(t.getId())) {
                return 2;
            }
            return BalloonManager.liquidAt(reader, x, y, z) != null ? 1 : 0;
        };
    }

    /** The outcome of the repair search: the attempt of the site that fits (null when none does) and the number of sites tried. */
    private record BoatRepair(BoatAttempt found, int tries) {
    }

    /**
     * True when every chunk of the site footprint is new (or the setting allows old terrain) and the sections of its box are loaded,
     * with the margin of the scan when a chunk is old. Loads nothing.
     */
    private static boolean siteReady(World world, NewChunks news, String kind, StructureShape station, StructureShape shuttle,
                                     WorldStationPlanner.Site c) {
        boolean old = hasOldChunk(news, kind, c);
        if (old && !TransitSettings.worldgenOldTerrain()) {
            return false;
        }
        int[] box = repairBox(station, shuttle, c);
        return new ChunkStoreBox(old ? widen(box, BUILT_MARGIN) : box).loaded(world);
    }

    private static int[] repairBox(StructureShape station, StructureShape shuttle, WorldStationPlanner.Site c) {
        Vector3i guess = new Vector3i(c.x(), SeaRules.SEA_LEVEL + 1, c.z());
        return box(station, null, shuttle, guess, c.rotation());
    }

    /** True when a chunk of the footprint of the site is not marked new. */
    private static boolean hasOldChunk(NewChunks news, String kind, WorldStationPlanner.Site c) {
        for (long[] chunk : GenerationListener.footprintChunks(kind, c.x(), c.z(), c.rotation())) {
            if (!news.isNew((int) chunk[0], (int) chunk[1])) {
                return true;
            }
        }
        return false;
    }

    /** True when the scan for player constructions runs for this site: the setting allows old terrain and a footprint chunk is old. */
    private static boolean scanWanted(NewChunks news, String kind, WorldStationPlanner.Site c) {
        return TransitSettings.worldgenOldTerrain() && hasOldChunk(news, kind, c);
    }

    /**
     * The area read once for the scan of an attempt: the widened box of the site, and for a pier the union of the boxes of every
     * repair candidate, so that no repair try reads the world again.
     */
    private static int[] scanArea(StructureShape station, StructureShape balloon, StructureShape shuttle, Vector3i guess,
                                  WorldStationPlanner.Site site, boolean boat) {
        int[] area = box(station, balloon, shuttle, guess, site.rotation());
        if (boat) {
            for (WorldStationPlanner.Site c : BoatSiteRepair.candidates(site)) {
                int[] b = repairBox(station, shuttle, c);
                area[0] = Math.min(area[0], b[0]);
                area[1] = Math.min(area[1], b[1]);
                area[2] = Math.min(area[2], b[2]);
                area[3] = Math.max(area[3], b[3]);
                area[4] = Math.max(area[4], b[4]);
                area[5] = Math.max(area[5], b[5]);
            }
        }
        return widen(area, BUILT_MARGIN);
    }

    /** The built cells of an area, read on first use. */
    private static final class BuiltScan {
        private final World world;
        private final java.util.function.Supplier<int[]> area;
        private java.util.List<BuiltBlocks.Found> cells;

        BuiltScan(World world, java.util.function.Supplier<int[]> area) {
            this.world = world;
            this.area = area;
        }

        BuiltBlocks.Found first(int[] box) {
            if (cells == null) {
                SectionReader reader = new SectionReader(world.getChunkStore());
                cells = BuiltBlocks.collect(area.get(), (x, y, z) -> {
                    if (BalloonManager.outOfHeight(y)) {
                        return null;
                    }
                    BlockType t = SectionBlocks.typeAt(reader, x, y, z);
                    return t == null ? null : t.getId();
                });
            }
            return BuiltBlocks.firstIn(cells, box, BUILT_MARGIN);
        }
    }

    /** The box grown by margin blocks on x and z. */
    private static int[] widen(int[] b, int margin) {
        return new int[] {b[0] - margin, b[1], b[2] - margin, b[3] + margin, b[4], b[5] + margin};
    }

    /**
     * The first player construction in the box widened by BUILT_MARGIN: a built block (BuiltBlocks.isBuilt) or a structure of the
     * registry whose box touches the widened box. Reads loaded sections only, an unloaded cell holds nothing. World thread.
     */
    private static BuiltBlocks.Found builtNear(World world, BuiltScan scan, int[] box) {
        BuiltBlocks.Found block = scan.first(box);
        if (block != null) {
            return block;
        }
        int[] wide = widen(box, BUILT_MARGIN);
        for (BalloonRegistry.Entry r : BalloonRegistry.entriesIn(world)) {
            int[] at = structureBox(r);
            if (at != null && BuiltBlocks.overlaps(wide, at)) {
                return new BuiltBlocks.Found(r.kind(), r.x(), r.y(), r.z());
            }
        }
        return null;
    }

    /** World box of a deployed structure (its shape bounds rotated and placed at its origin), null when its shape is unknown. */
    private static int[] structureBox(BalloonRegistry.Entry r) {
        StructureShape shape = BalloonRegistry.shapeOf(r);
        if (shape == null) {
            return null;
        }
        int[] b = shape.bounds();
        if (b[0] > b[3]) {
            return null;
        }
        Vector3i origin = new Vector3i(r.x(), r.y(), r.z());
        int[] out = {origin.x, origin.y, origin.z, origin.x, origin.y, origin.z};
        boolean first = true;
        for (int x : new int[] {b[0], b[3]}) {
            for (int y : new int[] {b[1], b[4]}) {
                for (int z : new int[] {b[2], b[5]}) {
                    Vector3i p = r.rotation().rotateYaw(new Vector3i(x, y, z), new Vector3i()).add(origin);
                    if (first) {
                        out = new int[] {p.x, p.y, p.z, p.x, p.y, p.z};
                        first = false;
                    } else {
                        extend(out, p);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Searches the shifted sites around the refused planned boat site and returns the attempt of the one that fits. Only sites whose
     * chunks are all new and whose sections are all loaded are tried, nothing is loaded and nothing is written.
     */
    private static BoatRepair repairBoat(World world, GeneratedStations.Entry e, StructureShape station, StructureShape shuttle,
                                         WorldStationPlanner.Site site, BuiltScan builtScan) {
        NewChunks news = GenerationListener.newChunksOf(world);
        BoatAttempt[] fitting = {null};
        BoatSiteRepair.Result result = BoatSiteRepair.search(site, c -> {
            boolean ready = siteReady(world, news, e.kind, station, shuttle, c);
            if (!ready && !TransitSettings.worldgenOldTerrain() && hasOldChunk(news, e.kind, c)) {
                e.oldTerrainSeen = true;
            }
            return ready;
        }, c -> {
            if (scanWanted(news, e.kind, c) && builtNear(world, builtScan, repairBox(station, shuttle, c)) != null) {
                fitting[0] = null;
                return false;
            }
            BoatAttempt a = boatAttempt(world, station, shuttle, Deployables.BOAT_STATION, c);
            fitting[0] = a.origin() != null ? a : null;
            return fitting[0] != null;
        });
        if (fitting[0] != null) {
            LOGGER.at(Level.INFO).log("%s: pier (%d, %d) refused, repaired at (%d, %d) after %d try(ies)", GenerationListener.logName(e.kind), site.x(), site.z(),
                    result.site().x(), result.site().z(), result.tries());
        }
        return new BoatRepair(fitting[0], result.tries());
    }

    /** No way to build on the active boat site: logs the refusal and goes to the next site or abandons the entry. */
    private static void refuseBoat(World world, GeneratedStations.Entry e, WorldStationPlanner.Site site, int tries, BoatAttempt refused) {
        LOGGER.at(Level.INFO).log("%s: site %d/%d refused at (%d, %d) after %d tries: %s at (%d, %d, %d), %s", GenerationListener.logName(e.kind),
                e.active + 1, e.sites.size(), site.x(), site.z(), tries, refused.block(), refused.at().x, refused.at().y,
                refused.at().z, refused.face());
        nextSite(world, e);
    }

    /** The active site is refused or cannot be read: logs the reason, then goes to the next site or abandons the entry. */
    private static void nextSiteOrAbandon(World world, GeneratedStations.Entry e, WorldStationPlanner.Site site, String reason) {
        LOGGER.at(Level.INFO).log("%s: site %d/%d discarded at (%d, %d): %s", GenerationListener.logName(e.kind), e.active + 1, e.sites.size(), site.x(), site.z(),
                reason);
        nextSite(world, e);
    }

    /** Moves the entry to its next site, decided again at once, or abandons it when the sites are used up. */
    private static void nextSite(World world, GeneratedStations.Entry e) {
        boolean boat = Deployables.BOAT_STATION_KIND.equals(e.kind);
        if (GenerationListener.moveToNextSite(GeneratedStations.get(), e, GenerationListener.otherSites(world.getName(), e))) {
            GenerationListener.decideAgain(world, e);
        } else {
            Telemetry.count(boat ? "worldgen.boat.exhausted" : "worldgen.air.exhausted");
            abandon(e, boat ? "no pier site fits" : "no station site fits",
                    GeneratedStations.exhaustedReason(e.oldTerrainSeen));
        }
    }

    /** Name from the world seed, the zone at the origin (empty when unknown) and the names already in the network of this world. */
    private static String nameFor(World world, ChunkGenerator generator, int seed, String kind, int x, int z, Vector3i origin) {
        String zone = "";
        try {
            ZoneBiomeResult r = generator.getZoneBiomeResultAt(seed, origin.x, origin.z);
            if (r != null && r.getZoneResult() != null && r.getZoneResult().getZone() != null) {
                zone = r.getZoneResult().getZone().name();
            }
        } catch (RuntimeException ex) {
            // the zone stays unknown
        }
        List<String> taken = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            taken.add(s.name);
        }
        return StationNames.generate(world.getWorldConfig().getSeed(), x, z, kind, zone, taken);
    }

    /**
     * Y of the highest solid block of the column that is not removable vegetation, scanning down from the generator height plus
     * SCAN_UP. Integer.MIN_VALUE when there is none.
     */
    private static int groundY(World world, int x, int z, int generatorHeight) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        int top = Math.min(generatorHeight + SCAN_UP, BalloonManager.WORLD_MAX_Y);
        int bottom = Math.max(generatorHeight - SCAN_DOWN, ChunkUtil.MIN_Y);
        for (int y = top; y >= bottom; y--) {
            BlockType t = SectionBlocks.typeAt(reader, x, y, z);
            if (t != null && t.getMaterial() == BlockMaterial.Solid && !GenClear.removable(t.getId())) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Box {minX, minY, minZ, maxX, maxY, maxZ} around every cell the build reads or writes, with a vertical margin. */
    private static int[] box(StructureShape station, StructureShape balloon, StructureShape shuttle, Vector3i origin, Rotation rotation) {
        int[] b = {origin.x, origin.y, origin.z, origin.x, origin.y, origin.z};
        extend(b, station, origin, rotation);
        if (balloon != null) {
            extend(b, balloon, DeployStationInteraction.dockOrigin(origin, rotation), rotation);
            for (int[] corner : new int[][] {Deployables.STATION.placeMin, Deployables.STATION.placeMax}) {
                Vector3i p = rotation.rotateYaw(new Vector3i(corner[0], corner[1], corner[2]), new Vector3i()).add(origin);
                extend(b, p);
            }
        }
        if (shuttle != null) {
            Vector3i berth = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                    Deployables.BOAT_STATION_BERTH_ORIGIN[2]), new Vector3i()).add(origin);
            extend(b, shuttle, berth, rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION));
            // The dome around the station: its bounding columns in the prefab frame, up to its top.
            int[] dome = BoatSiteGround.domeSquare();
            for (int px : new int[] {dome[0], dome[2]}) {
                for (int pz : new int[] {dome[1], dome[3]}) {
                    Vector3i p = rotation.rotateYaw(new Vector3i(px, 0, pz), new Vector3i()).add(origin);
                    extend(b, new Vector3i(p.x, origin.y + BoatSiteGround.DOME_HEIGHT, p.z));
                }
            }
        }
        b[1] -= BOX_MARGIN_DOWN;
        b[4] += BOX_MARGIN_UP;
        return b;
    }

    private static void extend(int[] b, StructureShape shape, Vector3i at, Rotation rotation) {
        for (StructureShape.Cell c : shape.cells()) {
            extend(b, c.rotated(rotation).add(at));
        }
    }

    private static void extend(int[] b, Vector3i p) {
        b[0] = Math.min(b[0], p.x);
        b[1] = Math.min(b[1], p.y);
        b[2] = Math.min(b[2], p.z);
        b[3] = Math.max(b[3], p.x);
        b[4] = Math.max(b[4], p.y);
        b[5] = Math.max(b[5], p.z);
    }

    /** A block box whose sections must be in memory before the build reads it. */
    private record ChunkStoreBox(int[] b) {
        boolean loaded(World w) {
            return SectionBlocks.sectionsLoaded(w.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5]);
        }

        java.util.concurrent.CompletableFuture<Void> load(World w) {
            return SectionBlocks.loadSections(w.getChunkStore(), b[0], b[1], b[2], b[3], b[4], b[5]);
        }
    }

    /** Block or liquid at a cell as a short name, "air" when empty. */
    private static String blockNameAt(World world, Vector3i p) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        BlockType t = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
        String liquid = BalloonManager.liquidAt(reader, p.x, p.y, p.z);
        String id = t != null && t.getMaterial() == BlockMaterial.Solid ? t.getId() : liquid != null ? liquid : t != null ? t.getId() : "air";
        return id.startsWith("*") ? id.substring(1) : id;
    }

    private static void abandon(GeneratedStations.Entry e, String reason) {
        abandon(e, reason, GeneratedStations.Reason.OTHER);
    }

    private static void abandon(GeneratedStations.Entry e, String reason, GeneratedStations.Reason why) {
        try {
            WorldStationPlanner.Site site = GeneratedStations.get().activeSite(e);
            LOGGER.at(Level.INFO).log("%s abandoned at (%d, %d): %s", GenerationListener.logName(e.kind), site.x(), site.z(), reason);
            GeneratedStations.get().abandon(e, why);
            Telemetry.count("worldgen.station.abandoned");
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("%s: abandon not recorded", GenerationListener.logName(e.kind));
            Telemetry.error("worldgen", ex, e.kind, "build");
        }
    }
}
