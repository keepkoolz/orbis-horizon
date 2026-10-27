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
    /** Block write flag: no particles. */
    private static final int SET_NO_PARTICLES = 4;

    private StationBuilder() {
    }

    /** Builds the station of a planned entry. Call on the world thread. Never throws. */
    static void build(World world, GeneratedStations.Entry e) {
        build(world, e, true);
    }

    private static void build(World world, GeneratedStations.Entry e, boolean mayLoad) {
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
                abandon(e, "prefab introuvable");
                return;
            }
            if (!(world.getChunkStore().getGenerator() instanceof ChunkGenerator generator)) {
                abandon(e, "générateur de monde inconnu");
                return;
            }
            int seed = (int) world.getWorldConfig().getSeed();
            int estimatedY = boat ? SeaRules.SEA_LEVEL + 1 : generator.getHeight(seed, site.x(), site.z()) + 1;
            Vector3i guess = new Vector3i(site.x(), estimatedY, site.z());

            int[] box = box(station, balloon, shuttle, guess, site.rotation());
            ChunkStoreBox cb = new ChunkStoreBox(box);
            if (!cb.loaded(world)) {
                if (!mayLoad) {
                    if (boat) {
                        nextSiteOrAbandon(world, e, site, "sections non chargées");
                    } else {
                        abandon(e, "sections non chargées");
                    }
                    return;
                }
                cb.load(world).copy().orTimeout(LOAD_TIMEOUT_S, TimeUnit.SECONDS).whenComplete((v, t) -> {
                    try {
                        if (t != null) {
                            Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                            if (cause instanceof TimeoutException) {
                                LOGGER.at(Level.WARNING).log("Station générée : chargement des sections non terminé après %d s en (%d, %d)",
                                        LOAD_TIMEOUT_S, site.x(), site.z());
                            } else {
                                LOGGER.at(Level.WARNING).withCause(cause).log("Station générée : chargement des sections impossible en (%d, %d)",
                                        site.x(), site.z());
                            }
                            Telemetry.error("worldgen", cause, e.kind, "load");
                        }
                        world.execute(() -> resumeAfterLoad(world, e, site));
                    } catch (RuntimeException ex) {
                        LOGGER.at(Level.WARNING).withCause(ex).log("Station générée : reprise impossible en (%d, %d)", site.x(), site.z());
                        Telemetry.error("worldgen", ex, e.kind, "build");
                        abandon(e, "reprise impossible");
                    }
                });
                return;
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
                    BoatRepair repair = repairBoat(world, e, station, shuttle, site);
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
                    abandon(e, "aucun sol sous la station");
                    return;
                }
                origin = new Vector3i(site.x(), ground + 1, site.z());
                // No irreversible change before every refusal is known: distance, then room (vegetation counts as empty), then clearing.
                TransitNetwork.Station nearAir = DeployStationInteraction.tooClose(TransitNetwork.get().stations(world.getName()), kind.id,
                        origin, TransitConstants.MIN_STATION_DISTANCE);
                if (nearAir != null) {
                    abandon(e, "trop près de « " + nearAir.name + " »");
                    return;
                }
                DeployStationInteraction.Refusal refusal = DeployStationInteraction.balloonRoomRefusal(world, station, balloon, origin,
                        site.rotation(), true);
                if (refusal != null) {
                    abandon(e, refusal.key() != null ? refusal.key() : "refus sans clé");
                    return;
                }
                int cleared = GenClear.clearBox(world, Deployables.STATION.placeMin, Deployables.STATION.placeMax, origin, site.rotation());
                cleared += GenClear.clear(world, balloon, DeployStationInteraction.dockOrigin(origin, site.rotation()), site.rotation());
                LOGGER.at(Level.INFO).log("Station générée : %d case(s) de végétation vidées en (%d, %d, %d)", cleared, origin.x, origin.y, origin.z);
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
                    LOGGER.at(Level.WARNING).log("Station générée : chef de station sans sol en (%d, %d, %d), site %d/%d refusé", fault.x, fault.y,
                            fault.z, e.active + 1, e.sites.size());
                    Telemetry.error("worldgen", new IllegalStateException("chef de station sans sol en (" + fault.x + ", " + fault.y + ", "
                            + fault.z + ")"), e.kind, "master");
                    // The pasted structure is registered and locked: release it so that no locked ruin stays behind.
                    BalloonRegistry.remove(world, origin, at.rotation(), StationSpec.of(kind.id).structure().id);
                    advanceBoat(world, e);
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
            }
            LOGGER.at(Level.INFO).log("Station générée « %s » construite en (%d, %d, %d)", name, origin.x, origin.y, origin.z);
        } catch (IOException | RuntimeException | LinkageError ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Station générée : construction impossible en (%d, %d)",
                    planned == null ? e.x : planned.x(), planned == null ? e.z : planned.z());
            Telemetry.error("worldgen", ex, e.kind, "build");
            abandon(e, "erreur de construction");
        }
    }

    /**
     * World thread, after the section load requested for a site: builds without loading again, or, when the entry moved to another
     * site meanwhile, releases it and decides it again on its new site. Never throws.
     */
    private static void resumeAfterLoad(World world, GeneratedStations.Entry e, WorldStationPlanner.Site requested) {
        try {
            if (e.state == GeneratedStations.State.PLANNED && !requested.equals(GeneratedStations.get().activeSite(e))) {
                LOGGER.at(Level.INFO).log("Station générée : site changé pendant le chargement en (%d, %d), nouvelle décision",
                        requested.x(), requested.z());
                GenerationListener.decideAgain(world, e);
                return;
            }
            build(world, e, false);
        } catch (RuntimeException | LinkageError ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Station générée : reprise impossible en (%d, %d)", requested.x(), requested.z());
            Telemetry.error("worldgen", ex, e.kind, "build");
            abandon(e, "reprise impossible");
        }
    }

    /**
     * The outcome of the checks of one boat site. The origin is null on a refusal, the other fields then name the faulty block, its
     * cell and the part of the boat it hits. The site is the one checked. A successful attempt also holds the cells to clear and the
     * blocks to fill, both null on a refusal.
     */
    private record BoatAttempt(WorldStationPlanner.Site site, Vector3i origin, String block, Vector3i at, String face,
                               Set<Vector3i> clear, Map<Vector3i, String> fill) {
        BoatAttempt(WorldStationPlanner.Site site, Vector3i origin, String block, Vector3i at, String face) {
            this(site, origin, block, at, face, null, null);
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
            return new BoatAttempt(site, null, blockNameAt(world, cell), cell, "hors de l'eau");
        }
        DeployPlacement.CellState ground = groundState(world);
        SectionReader reader = new SectionReader(world.getChunkStore());
        Set<Vector3i> clear = BoatSiteGround.clearCells(origin, site.rotation(), shuttle);
        Map<Vector3i, String> fill = BoatSiteGround.fillPlan(origin, site.rotation(), ground, p -> {
            BlockType t = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
            return t != null ? t.getId() : null;
        });
        DeployStationInteraction.Refusal refusal = DeployStationInteraction.boatRoomRefusal(world, station, origin, site.rotation(), clear);
        if (refusal != null) {
            Vector3i cell = refusal.at() != null ? refusal.at() : origin;
            String face = refusal.hull() == null ? "emprise de la station" : refusal.hull() ? "coque" : "hors de l'eau";
            String block = refusal.block() != null ? refusal.block() : refusal.key() != null ? refusal.key() : "forme du bateau illisible";
            return new BoatAttempt(site, null, block, cell, face);
        }
        TransitNetwork.Station near = DeployStationInteraction.tooClose(TransitNetwork.get().stations(world.getName()), kind.id, origin,
                TransitConstants.MIN_STATION_DISTANCE);
        if (near != null) {
            return new BoatAttempt(site, null, "station « " + near.name + " »", near.origin, "trop près");
        }
        // Every cell of the station prefab counts as solid (hut floor, planks under it, posts): they are placed with the station.
        Set<Vector3i> solid = new java.util.HashSet<>(fill.keySet());
        for (StructureShape.Cell c : station.cells()) {
            solid.add(c.rotated(site.rotation()).add(origin));
        }
        Vector3i fault = BoatSiteGround.masterFault(origin, site.rotation(), BoatSiteGround.simulated(ground, clear, solid));
        if (fault != null) {
            return new BoatAttempt(site, null, blockNameAt(world, fault), fault, "chef de station");
        }
        return new BoatAttempt(site, origin, null, null, null, clear, fill);
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
        LOGGER.at(Level.INFO).log("Station générée : %d case(s) dégagées et %d comblées en %d ms en (%d, %d, %d)", cleared, filled, elapsedMs,
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

    /** True when every chunk of the site footprint is new and the sections of its box are loaded. Loads nothing. */
    private static boolean siteReady(World world, NewChunks news, String kind, StructureShape station, StructureShape shuttle,
                                     WorldStationPlanner.Site c) {
        for (long[] chunk : GenerationListener.footprintChunks(kind, c.x(), c.z(), c.rotation())) {
            if (!news.isNew((int) chunk[0], (int) chunk[1])) {
                return false;
            }
        }
        Vector3i guess = new Vector3i(c.x(), SeaRules.SEA_LEVEL + 1, c.z());
        return new ChunkStoreBox(box(station, null, shuttle, guess, c.rotation())).loaded(world);
    }

    /**
     * Searches the shifted sites around the refused planned boat site and returns the attempt of the one that fits. Only sites whose
     * chunks are all new and whose sections are all loaded are tried, nothing is loaded and nothing is written.
     */
    private static BoatRepair repairBoat(World world, GeneratedStations.Entry e, StructureShape station, StructureShape shuttle,
                                         WorldStationPlanner.Site site) {
        NewChunks news = GenerationListener.newChunksOf(world);
        BoatAttempt[] fitting = {null};
        BoatSiteRepair.Result result = BoatSiteRepair.search(site, c -> siteReady(world, news, e.kind, station, shuttle, c), c -> {
            BoatAttempt a = boatAttempt(world, station, shuttle, Deployables.BOAT_STATION, c);
            fitting[0] = a.origin() != null ? a : null;
            return fitting[0] != null;
        });
        if (fitting[0] != null) {
            LOGGER.at(Level.INFO).log("Station générée : ponton (%d, %d) refusé, réparé en (%d, %d) après %d essai(s)", site.x(), site.z(),
                    result.site().x(), result.site().z(), result.tries());
        }
        return new BoatRepair(fitting[0], result.tries());
    }

    /** No way to build on the active boat site: logs the refusal and goes to the next site or abandons the entry. */
    private static void refuseBoat(World world, GeneratedStations.Entry e, WorldStationPlanner.Site site, int tries, BoatAttempt refused) {
        LOGGER.at(Level.INFO).log("Station générée : site %d/%d refusé en (%d, %d) après %d essais : %s en (%d, %d, %d), %s",
                e.active + 1, e.sites.size(), site.x(), site.z(), tries, refused.block(), refused.at().x, refused.at().y,
                refused.at().z, refused.face());
        advanceBoat(world, e);
    }

    /** The active boat site cannot be read: logs the reason, then goes to the next site or abandons the entry. */
    private static void nextSiteOrAbandon(World world, GeneratedStations.Entry e, WorldStationPlanner.Site site, String reason) {
        LOGGER.at(Level.INFO).log("Station générée : site %d/%d écarté en (%d, %d) : %s", e.active + 1, e.sites.size(), site.x(), site.z(),
                reason);
        advanceBoat(world, e);
    }

    /** Moves the entry to its next boat site, decided again at once, or abandons it when the sites are used up. */
    private static void advanceBoat(World world, GeneratedStations.Entry e) {
        if (GenerationListener.moveToNextSite(GeneratedStations.get(), e, GenerationListener.otherBoatSites(world.getName(), e))) {
            GenerationListener.decideAgain(world, e);
        } else {
            Telemetry.count("worldgen.boat.exhausted");
            abandon(e, "aucun site de ponton ne convient");
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
        try {
            WorldStationPlanner.Site site = GeneratedStations.get().activeSite(e);
            LOGGER.at(Level.INFO).log("Station générée abandonnée en (%d, %d) (%s) : %s", site.x(), site.z(), e.kind, reason);
            GeneratedStations.get().setState(e, GeneratedStations.State.ABANDONED);
            Telemetry.count("worldgen.station.abandoned");
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Station générée : abandon non enregistré");
            Telemetry.error("worldgen", ex, e.kind, "build");
        }
    }
}
