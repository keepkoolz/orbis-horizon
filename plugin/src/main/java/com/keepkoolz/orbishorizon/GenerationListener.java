package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Stations generated with the world. Each newly generated chunk is marked, a temple portal in it is looked for, and the portal, the
 * sea cell and the land cell around its centre are planned on a dedicated thread. A planned station is built on the world thread once every
 * chunk of its footprint is new. Old terrain in its footprint moves a station to its next site and abandons it when none is left.
 */
final class GenerationListener {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PORTAL_BLOCK = "Forgotten_Temple_Portal_Enter";
    private static final int SLOW_CELL_MS = 2000;
    private static final int FLUSH_PERIOD_S = 30;
    private static final int STOP_WAIT_S = 5;
    private static final int AHEAD_PERIOD_S = 10;
    /** Horizontal extent of the whole docked balloon (envelope included), prefab frame x and z -12..12. */
    private static final int[] BALLOON_BOX_MIN = {-12, 0, -12};
    private static final int[] BALLOON_BOX_MAX = {12, 0, 12};

    enum Decision { WAIT, BUILD, ABANDON }

    private static final Object LOCK = new Object();
    /** Tasks must be given to the planner with execute as a PlanTask (never submit, never a bare Runnable): its queue orders PlanTasks. */
    private static ExecutorService planner;
    private static ScheduledExecutorService flusher;
    private static volatile int portalId = Integer.MIN_VALUE;
    private static volatile boolean portalResolved;
    private static final Map<String, java.util.Optional<GeneratorProbe>> PROBES = new ConcurrentHashMap<>();
    /** Entries whose build was already requested, so that several new chunks never queue it twice. */
    private static final Set<GeneratedStations.Entry> REQUESTED = ConcurrentHashMap.newKeySet();
    /** Cells queued ahead of the players and not planned yet (world name, cell x, cell z), so a busy planner is never given one twice. */
    private static final Set<String> AHEAD_QUEUED = ConcurrentHashMap.newKeySet();
    /** Cells already un-marked after a restoration this session, so a cell is planned again at most once (same key as AHEAD_QUEUED). */
    private static final Set<String> REPLANNED = ConcurrentHashMap.newKeySet();

    private GenerationListener() {
    }

    /** Starts the planner thread and the periodic write of the new chunk maps. */
    static void start() {
        synchronized (LOCK) {
            if (planner == null) {
                planner = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "OrbisHorizon-Worldgen");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (flusher == null) {
                flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "OrbisHorizon-Worldgen-Flush");
                    t.setDaemon(true);
                    return t;
                });
                flusher.scheduleAtFixedRate(GenerationListener::flush, FLUSH_PERIOD_S, FLUSH_PERIOD_S, TimeUnit.SECONDS);
                flusher.scheduleWithFixedDelay(GenerationListener::planAhead, AHEAD_PERIOD_S, AHEAD_PERIOD_S, TimeUnit.SECONDS);
            }
        }
    }

    /** Lets the planner finish its queue (5 s at most), stops the threads and writes the maps one last time. */
    static void stop() {
        ExecutorService p;
        ScheduledExecutorService f;
        synchronized (LOCK) {
            p = planner;
            f = flusher;
            planner = null;
            flusher = null;
        }
        if (f != null) {
            f.shutdownNow();
        }
        if (p != null) {
            p.shutdown();
            try {
                if (!p.awaitTermination(STOP_WAIT_S, TimeUnit.SECONDS)) {
                    int dropped = p.shutdownNow().size();
                    LOGGER.at(Level.WARNING).log("Generated station: shutdown before planning finished, %d task(s) dropped", dropped);
                }
            } catch (InterruptedException e) {
                int dropped = p.shutdownNow().size();
                Thread.currentThread().interrupt();
                LOGGER.at(Level.WARNING).log("Generated station: shutdown interrupted, %d task(s) dropped", dropped);
            }
        }
        PROBES.clear();
        REQUESTED.clear();
        AHEAD_QUEUED.clear();
        REPLANNED.clear();
        flush();
    }

    private static void flush() {
        try {
            NewChunks.flushAll();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cannot write the new chunks");
            Telemetry.error("worldgen", e, null, "flush");
        }
    }

    /**
     * Chunks {cx, cz} that the station of an entry covers: its placement volume and the docked balloon (balloon station) or the
     * berth of the boat (boat station). Horizontal only, negative coordinates included.
     */
    static List<long[]> footprintChunks(GeneratedStations.Entry e) {
        WorldStationPlanner.Site site = GeneratedStations.get().activeSite(e);
        return footprintChunks(e.kind, site.x(), site.z(), site.rotation());
    }

    /** Same as {@link #footprintChunks(GeneratedStations.Entry)} for a station of a kind at a given site. */
    static List<long[]> footprintChunks(String kind, int x, int z, Rotation rotation) {
        boolean boat = Deployables.BOAT_STATION_KIND.equals(kind);
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Vector3i origin = new Vector3i(x, 0, z);
        extend(box, boat ? Deployables.BOAT_STATION.placeMin : Deployables.STATION.placeMin,
                boat ? Deployables.BOAT_STATION.placeMax : Deployables.STATION.placeMax, origin, rotation);
        if (boat) {
            int[] b = Deployables.BOAT_STATION_BERTH_ORIGIN;
            Vector3i berth = rotation.rotateYaw(new Vector3i(b[0], 0, b[2]), new Vector3i()).add(origin);
            extend(box, Deployables.STEAMBOAT_PUBLIC.placeMin, Deployables.STEAMBOAT_PUBLIC.placeMax, berth,
                    rotation.add(Deployables.BOAT_STATION_BERTH_ROTATION));
            // The dome cleared around the station: its bounding square, in the prefab frame.
            int[] dome = BoatSiteGround.domeSquare();
            extend(box, new int[] {dome[0], 0, dome[1]}, new int[] {dome[2], 0, dome[3]}, origin, rotation);
        } else {
            Vector3i dock = DeployStationInteraction.dockOrigin(origin, rotation);
            extend(box, BALLOON_BOX_MIN, BALLOON_BOX_MAX, dock, rotation);
        }
        List<long[]> out = new ArrayList<>();
        for (int cx = ChunkUtil.chunkCoordinate(box[0]); cx <= ChunkUtil.chunkCoordinate(box[2]); cx++) {
            for (int cz = ChunkUtil.chunkCoordinate(box[1]); cz <= ChunkUtil.chunkCoordinate(box[3]); cz++) {
                out.add(new long[]{cx, cz});
            }
        }
        return out;
    }

    /** Grows box {minX, minZ, maxX, maxZ} with the prefab-frame volume min..max rotated and moved to at. */
    private static void extend(int[] box, int[] min, int[] max, Vector3i at, Rotation rotation) {
        for (int[] corner : new int[][] {{min[0], min[2]}, {max[0], min[2]}, {min[0], max[2]}, {max[0], max[2]}}) {
            Vector3i p = rotation.rotateYaw(new Vector3i(corner[0], 0, corner[1]), new Vector3i()).add(at);
            box[0] = Math.min(box[0], p.x);
            box[1] = Math.min(box[1], p.z);
            box[2] = Math.max(box[2], p.x);
            box[3] = Math.max(box[3], p.z);
        }
    }

    /**
     * Setting off: ABANDON when a chunk is loaded without being new, BUILD when all are new, else WAIT.
     * Setting on (oldTerrain): never ABANDON, BUILD when every chunk is new or loaded, else WAIT.
     */
    static Decision decide(List<long[]> chunks, Predicate<long[]> isNew, Predicate<long[]> loadedOld, boolean oldTerrain) {
        boolean ready = true;
        for (long[] c : chunks) {
            if (loadedOld.test(c)) {
                if (!oldTerrain) {
                    return Decision.ABANDON;
                }
                continue;
            }
            if (!isNew.test(c)) {
                ready = false;
            }
        }
        return ready ? Decision.BUILD : Decision.WAIT;
    }

    /** The entries among those given whose footprint contains the chunk. */
    static List<GeneratedStations.Entry> touching(List<GeneratedStations.Entry> entries, int cx, int cz) {
        List<GeneratedStations.Entry> out = new ArrayList<>();
        for (GeneratedStations.Entry e : entries) {
            if (touches(e, cx, cz)) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean touches(GeneratedStations.Entry e, int cx, int cz) {
        for (long[] c : footprintChunks(e)) {
            if (c[0] == cx && c[1] == cz) {
                return true;
            }
        }
        return false;
    }

    /** Chunk event, off the world thread, before the chunk is in the store. Reads the event's holder only. */
    static void onChunk(ChunkPreLoadProcessEvent ev) {
        try {
            if (!TransitSettings.worldgenEnabled()) {
                return;
            }
            World world = ev.getWorld();
            GeneratorProbe probe = probeOf(world);
            if (probe == null) {
                return;
            }
            String name = world.getName();
            int cx = ev.getChunk().getX(), cz = ev.getChunk().getZ();
            if (!ev.isNewlyGenerated()) {
                boolean old = !newChunksOf(world).isNew(cx, cz);
                if (old && TransitSettings.worldgenOldTerrain()) {
                    onExploredChunk(world, probe, name, cx, cz, ev.getHolder());
                } else if (old) {
                    abandonOnOldChunk(world, name, cx, cz);
                } else {
                    // a new chunk reloaded: a station that was waiting for it (or lost at shutdown) is decided again.
                    List<GeneratedStations.Entry> again = touching(GeneratedStations.get().pending(name), cx, cz);
                    if (!again.isEmpty()) {
                        world.execute(() -> decideAll(world, again));
                    }
                }
                return;
            }
            if (newChunksOf(world).isNew(cx, cz)) {
                onRegenerated(world, name, cx, cz);
            }
            newChunksOf(world).mark(cx, cz);
            // stations already waiting on this chunk are decided now, without waiting for the cell planning below
            List<GeneratedStations.Entry> waiting = touching(GeneratedStations.get().pending(name), cx, cz);
            if (!waiting.isEmpty()) {
                world.execute(() -> decideAll(world, waiting));
            }
            int[] portal = findPortal(ev.getHolder(), cx, cz);
            ExecutorService ex;
            synchronized (LOCK) {
                ex = planner;
            }
            if (ex == null) {
                return;
            }
            ex.execute(new PlanTask(PlanTask.CHUNK, () -> {
                try {
                    plan(world, probe, cx, cz, portal);
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Generated station: planning interrupted around chunk (%d, %d)", cx, cz);
                    Telemetry.error("worldgen", e, null, "plan");
                }
            }));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: new chunk not handled");
            Telemetry.error("worldgen", e, null, "chunk");
        }
    }

    /**
     * Event thread, setting on: a chunk loaded from disk that is not new. The pending entries touching it are decided again on the
     * world thread and the chunk is searched for a temple portal, which is planned on the planner thread. The sea and land cells
     * are planned by the new-chunk path and by planAhead, whatever the terrain.
     */
    private static void onExploredChunk(World world, GeneratorProbe probe, String name, int cx, int cz, Holder<ChunkStore> holder) {
        List<GeneratedStations.Entry> again = touching(GeneratedStations.get().pending(name), cx, cz);
        if (!again.isEmpty()) {
            world.execute(() -> decideAll(world, again));
        }
        int[] portal = findPortal(holder, cx, cz);
        if (portal == null) {
            return;
        }
        ExecutorService ex;
        synchronized (LOCK) {
            ex = planner;
        }
        if (ex == null) {
            return;
        }
        ex.execute(new PlanTask(PlanTask.CHUNK, () -> {
            try {
                Set<GeneratedStations.Entry> touched = new LinkedHashSet<>();
                planPortalSite(world, probe, portal, touched);
                if (!touched.isEmpty()) {
                    List<GeneratedStations.Entry> list = new ArrayList<>(touched);
                    world.execute(() -> decideAll(world, list));
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Generated station: portal of chunk (%d, %d) not planned", cx, cz);
                Telemetry.error("worldgen", e, null, "plan");
            }
        }));
    }

    /**
     * A chunk generated again although the map already holds it as new: the world was restored from a backup that does not contain
     * the mod's files. Entries from before this session whose footprint contains the chunk are forgotten, and so are the built ones
     * (which also leave the network). The sea cell and the land cell are un-marked the first time only, and the portals inside the chunk are forgotten:
     * both are planned again by the normal flow that follows. Event thread: registry only, the network is handled on the world thread.
     */
    private static void onRegenerated(World world, String name, int cx, int cz) {
        try {
            GeneratedStations reg = GeneratedStations.get();
            List<GeneratedStations.Entry> forgotten = forgettable(touching(reg.entries(name), cx, cz));
            List<GeneratedStations.Entry> built = new ArrayList<>();
            for (GeneratedStations.Entry e : forgotten) {
                if (e.state == GeneratedStations.State.BUILT) {
                    built.add(e);
                }
                REQUESTED.remove(e);
                reg.forget(e);
            }
            if (!built.isEmpty()) {
                world.execute(() -> removeBuilt(world, built));
            }
            int cellX = WorldStationPlanner.cellOf(cx * 32 + 16);
            int cellZ = WorldStationPlanner.cellOf(cz * 32 + 16);
            boolean unmarkedSea = unmarkOnce(REPLANNED, reg, name, cellX, cellZ);
            boolean unmarkedLand = unmarkLandOnce(REPLANNED, reg, name, WorldStationPlanner.landCellOf(cx * 32 + 16),
                    WorldStationPlanner.landCellOf(cz * 32 + 16));
            boolean unmarked = unmarkedSea || unmarkedLand;
            reg.forgetPortals(name, cx * 32, cz * 32, cx * 32 + 31, cz * 32 + 31);
            if (!forgotten.isEmpty() || unmarked) {
                LOGGER.at(Level.INFO).log("Generated station: chunk (%d, %d) regenerated, save restored: %d station(s) forgotten, cell %s",
                        cx, cz, forgotten.size(), unmarked ? "to replan" : "already replanned");
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: restore not handled at chunk (%d, %d)", cx, cz);
            Telemetry.error("worldgen", e, null, "rollback");
        }
    }

    /**
     * Un-marks a planned cell after a restoration, the first time only in the session (replanned holds the cells already handled).
     * True when the cell was un-marked and will be planned again.
     */
    static boolean unmarkOnce(Set<String> replanned, GeneratedStations reg, String world, int cellX, int cellZ) {
        return replanned.add(cellKey(world, cellX, cellZ)) && reg.unmarkCell(world, cellX, cellZ);
    }

    /** Same as {@link #unmarkOnce} for a land cell. */
    static boolean unmarkLandOnce(Set<String> replanned, GeneratedStations reg, String world, int cellX, int cellZ) {
        return replanned.add(aheadLandKey(world, cellX, cellZ)) && reg.unmarkLandCell(world, cellX, cellZ);
    }

    /** World thread: removes the network stations of forgotten built entries, without touching any block. */
    private static void removeBuilt(World world, List<GeneratedStations.Entry> built) {
        for (GeneratedStations.Entry e : built) {
            try {
                TransitNetwork.Station s = TransitNetwork.get().atColumn(world.getName(), e.x, e.z);
                if (s == null) {
                    continue;
                }
                TransitFare.refundRemovedStation(s);
                StationMaster.remove(world.getEntityStore().getStore(), s);
                TransitNetwork.get().remove(s);
                TransitNetwork.get().save();
                StationCommand.StationNameType.stationsChanged();
                // Quay, flights heading here, shuttles and the other queues, as for a removal by command.
                StationQueue.onStationRemoved(world, s);
                BoatQuay.onStationRemoved(world, s);
                BalloonRegistry.remove(world, s.origin, s.rotation, StationSpec.of(s.kind).structure().id);
                LOGGER.at(Level.INFO).log("Station '%s' removed from the network: its terrain was regenerated", s.name);
            } catch (RuntimeException | LinkageError ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("%s: cannot remove from the network at (%d, %d)", logName(e.kind), e.x, e.z);
                Telemetry.error("worldgen", ex, e.kind, "rollback");
            }
        }
    }

    /** World start: every station still planned is decided again (a shutdown may have dropped its build). */
    static void onWorldStart(World world) {
        try {
            if (!TransitSettings.worldgenEnabled()) {
                return;
            }
            if (probeOf(world) == null) {
                return;
            }
            List<GeneratedStations.Entry> pending = GeneratedStations.get().pending(world.getName());
            if (!pending.isEmpty()) {
                world.execute(() -> decideAll(world, pending));
            }
            if (TransitSettings.worldgenOldTerrain()) {
                world.execute(() -> reviveWorld(world));
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cannot resume the pending stations for %s", world.getName());
            Telemetry.error("worldgen", e, null, "resume");
        }
    }

    /** The map of new chunks of a world. */
    static NewChunks newChunksOf(World world) {
        return NewChunks.forWorld(world.getName(), world.getWorldConfig().getUuid());
    }

    /** The probe of a world, null for a world the feature does not run in (instance, other generator). */
    private static GeneratorProbe probeOf(World world) {
        java.util.Optional<GeneratorProbe> known = PROBES.get(world.getName());
        if (known != null) {
            return known.orElse(null);
        }
        if (GeneratorProbe.availability(world) == GeneratorProbe.Availability.WAIT) {
            // The generator loads asynchronously after the world starts: nothing is remembered until it is there.
            return null;
        }
        return PROBES.computeIfAbsent(world.getName(), k -> {
            GeneratorProbe probe = GeneratorProbe.of(world);
            if (probe != null) {
                // First use of the world in this session: its files must belong to this very world.
                GeneratedStations.get().adoptWorld(world.getName(), world.getWorldConfig().getUuid());
                NewChunks.forWorld(world.getName(), world.getWorldConfig().getUuid());
            }
            return java.util.Optional.ofNullable(probe);
        }).orElse(null);
    }

    /**
     * An old chunk reloaded under the active site of planned stations. Event thread: only finds the entries, which are handled on the
     * world thread (see {@link #onOldTerrain}), where every change of site happens.
     */
    private static void abandonOnOldChunk(World world, String name, int cx, int cz) {
        List<GeneratedStations.Entry> touched = touching(GeneratedStations.get().pending(name), cx, cz);
        if (!touched.isEmpty()) {
            world.execute(() -> onOldTerrain(world, touched, cx, cz));
        }
    }

    /**
     * World thread: each entry still PLANNED whose active site still covers the old chunk. An entry whose build is not requested
     * moves to its next site and is decided again; the others, and an entry without a next site, are abandoned.
     */
    private static void onOldTerrain(World world, List<GeneratedStations.Entry> entries, int cx, int cz) {
        GeneratedStations reg = GeneratedStations.get();
        String name = world.getName();
        for (GeneratedStations.Entry e : entries) {
            WorldStationPlanner.Site site = null;
            try {
                if (e.state != GeneratedStations.State.PLANNED || !touches(e, cx, cz)) {
                    continue;
                }
                site = reg.activeSite(e);
                e.oldTerrainSeen = true;
                if (!REQUESTED.contains(e) && moveOffOldTerrain(reg, e, name)) {
                    LOGGER.at(Level.INFO).log("%s: old terrain reloaded at (%d, %d), next site %d/%d", logName(e.kind), site.x(), site.z(),
                            e.active + 1, e.sites.size());
                    decideAgain(world, e);
                } else {
                    abandon(e, "old terrain reloaded", GeneratedStations.Reason.OLD_TERRAIN);
                }
            } catch (RuntimeException | LinkageError ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("%s: old terrain not handled at (%d, %d)", logName(e.kind),
                        site == null ? e.x : site.x(), site == null ? e.z : site.z());
                Telemetry.error("worldgen", ex, e.kind, "chunk");
            }
        }
    }

    /**
     * Old terrain under the active site of a planned entry: the entry moves to its next site that keeps its distance from the
     * other stations of its kind, and true is returned. False when its sites are used up (counted as worldgen.boat.exhausted or
     * worldgen.air.exhausted): the caller abandons the entry.
     */
    static boolean moveOffOldTerrain(GeneratedStations reg, GeneratedStations.Entry e, String world) {
        if (moveToNextSite(reg, e, otherSites(world, e))) {
            return true;
        }
        Telemetry.count(Deployables.BOAT_STATION_KIND.equals(e.kind) ? "worldgen.boat.exhausted" : "worldgen.air.exhausted");
        return false;
    }

    private static void abandon(GeneratedStations.Entry e, String reason, GeneratedStations.Reason why) {
        try {
            GeneratedStations.get().abandon(e, why);
            Telemetry.count("worldgen.station.abandoned");
            LOGGER.at(Level.INFO).log("%s abandoned at (%d, %d): %s", logName(e.kind), e.x, e.z, reason);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("%s: abandon not recorded", logName(e.kind));
            Telemetry.error("worldgen", ex, e.kind, "abandon");
        }
    }

    /** First temple portal block of the chunk as {x, z} in block coordinates, or null. Skips empty sections. */
    private static int[] findPortal(Holder<ChunkStore> holder, int cx, int cz) {
        int id = portalId();
        if (id < 0 || holder == null) {
            return null;
        }
        ChunkColumn col = holder.getComponent(ChunkColumn.getComponentType());
        Holder<ChunkStore>[] sections = col == null ? null : col.getSectionHolders();
        if (sections == null) {
            return null;
        }
        for (int i = 0; i < Math.min(sections.length, ChunkUtil.HEIGHT_SECTIONS); i++) {
            BlockSection bs = sections[i] == null ? null : sections[i].getComponent(BlockSection.getComponentType());
            if (bs == null || bs.isSolidAir() || !bs.contains(id)) {
                continue;
            }
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        if (bs.get(x, y, z) == id) {
                            return new int[]{cx * 32 + x, cz * 32 + z};
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int portalId() {
        if (!portalResolved) {
            synchronized (LOCK) {
                if (!portalResolved) {
                    portalId = BlockType.getAssetMap().getIndex(PORTAL_BLOCK);
                    if (portalId < 0) {
                        LOGGER.at(Level.WARNING).log("Generated station: portal block %s not found, no station near portals", PORTAL_BLOCK);
                    }
                    portalResolved = true;
                }
            }
        }
        return portalId;
    }

    /** Planner thread: plans the portal and the sea cell of a new chunk, then decides on every station touching it. */
    private static void plan(World world, GeneratorProbe probe, int cx, int cz, int[] portal) {
        String name = world.getName();
        GeneratedStations reg = GeneratedStations.get();
        Set<GeneratedStations.Entry> touched = new LinkedHashSet<>();
        planPortalSite(world, probe, portal, touched);
        try {
            touched.addAll(planCell(world, probe, WorldStationPlanner.cellOf(cx * 32 + 16), WorldStationPlanner.cellOf(cz * 32 + 16)));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated sea station: cell around chunk (%d, %d) not planned", cx, cz);
            Telemetry.error("worldgen", e, Deployables.BOAT_STATION_KIND, "plan");
        }
        try {
            touched.addAll(planLandCell(world, probe, WorldStationPlanner.landCellOf(cx * 32 + 16), WorldStationPlanner.landCellOf(cz * 32 + 16)));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated air station: land cell around chunk (%d, %d) not planned", cx, cz);
            Telemetry.error("worldgen", e, Deployables.STATION_KIND, "plan");
        }
        try {
            touched.addAll(touching(reg.pending(name), cx, cz));
            if (!touched.isEmpty()) {
                List<GeneratedStations.Entry> list = new ArrayList<>(touched);
                world.execute(() -> decideAll(world, list));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cannot decide after chunk (%d, %d)", cx, cz);
            Telemetry.error("worldgen", e, null, "decide");
        }
    }

    /** Planner thread: plans the station of a temple portal unless one was seen nearby, adds its entry to touched. Never throws. */
    private static void planPortalSite(World world, GeneratorProbe probe, int[] portal, Set<GeneratedStations.Entry> touched) {
        if (portal == null) {
            return;
        }
        String name = world.getName();
        GeneratedStations reg = GeneratedStations.get();
        try {
            if (reg.markPortal(name, portal[0], portal[1])) {
                WorldStationPlanner.PlannedSite site = WorldStationPlanner.planPortal(portal[0], portal[1], probe,
                        existing(name, Deployables.STATION_KIND, true));
                if (site != null) {
                    touched.add(reg.add(name, site, GeneratedStations.Origin.PORTAL));
                    world.execute(() -> displaceLandEntries(world, site));
                    LOGGER.at(Level.INFO).log("Generated air station: portal (%d, %d) planned, site at (%d, %d)", portal[0], portal[1],
                            site.x(), site.z());
                } else {
                    LOGGER.at(Level.INFO).log("Generated air station: portal (%d, %d) without a site", portal[0], portal[1]);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated air station: portal at (%d, %d) not planned", portal[0], portal[1]);
            Telemetry.error("worldgen", e, Deployables.STATION_KIND, "plan");
        }
    }

    /**
     * Planner thread: plans a sea cell unless it already is, and returns the entries it added to the registry (empty when the cell
     * was planned before). The caller decides on them. Throws what the planning throws.
     */
    private static List<GeneratedStations.Entry> planCell(World world, GeneratorProbe probe, int cellX, int cellZ) {
        String name = world.getName();
        GeneratedStations reg = GeneratedStations.get();
        List<GeneratedStations.Entry> added = new ArrayList<>();
        if (reg.markCell(name, cellX, cellZ)) {
            long start = System.nanoTime();
            List<WorldStationPlanner.PlannedSite> sites = WorldStationPlanner.planSeaCell(world.getWorldConfig().getSeed(), cellX, cellZ,
                    probe, existing(name, Deployables.BOAT_STATION_KIND, false));
            for (WorldStationPlanner.PlannedSite s : sites) {
                added.add(reg.add(name, s));
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOGGER.at(Level.INFO).log("Generated sea station: cell (%d, %d) planned in %d ms, %d site(s)", cellX, cellZ, ms, sites.size());
            if (ms > SLOW_CELL_MS) {
                LOGGER.at(Level.WARNING).log("Generated sea station: cell (%d, %d) took more than %d ms", cellX, cellZ, SLOW_CELL_MS);
            }
        }
        return added;
    }

    /**
     * Planner thread: plans a land cell unless it already is, and returns the entry it added to the registry (empty when the cell was
     * planned before or has no site). The caller decides on it. Throws what the planning throws.
     */
    private static List<GeneratedStations.Entry> planLandCell(World world, GeneratorProbe probe, int cellX, int cellZ) {
        String name = world.getName();
        GeneratedStations reg = GeneratedStations.get();
        List<GeneratedStations.Entry> added = new ArrayList<>();
        if (reg.markLandCell(name, cellX, cellZ)) {
            long start = System.nanoTime();
            WorldStationPlanner.PlannedSite site = WorldStationPlanner.planLandCell(world.getWorldConfig().getSeed(), cellX, cellZ, probe,
                    existing(name, Deployables.STATION_KIND, false));
            if (site != null) {
                added.add(reg.add(name, site, GeneratedStations.Origin.LAND));
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOGGER.at(Level.INFO).log("Generated air station: land cell (%d, %d) planned in %d ms, %s", cellX, cellZ, ms,
                    site == null ? "no site" : "site at (" + site.x() + ", " + site.z() + ")");
            if (ms > SLOW_CELL_MS) {
                LOGGER.at(Level.WARNING).log("Generated air station: land cell (%d, %d) took more than %d ms", cellX, cellZ, SLOW_CELL_MS);
            }
        }
        return added;
    }

    /** The land cell of the point and its 8 neighbours as {cellX, cellZ}, the cell of the point first. */
    static List<int[]> landCellsAround(double x, double z) {
        return around(WorldStationPlanner.landCellOf((int) Math.floor(x)), WorldStationPlanner.landCellOf((int) Math.floor(z)));
    }

    /** The cell of the point and its 8 neighbours as {cellX, cellZ}, the cell of the point first. */
    static List<int[]> cellsAround(double x, double z) {
        return around(WorldStationPlanner.cellOf((int) Math.floor(x)), WorldStationPlanner.cellOf((int) Math.floor(z)));
    }

    private static List<int[]> around(int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{cx, cz});
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    out.add(new int[]{cx + dx, cz + dz});
                }
            }
        }
        return out;
    }

    /**
     * True when the cell is neither planned nor already queued, and then records it in queued. The caller queues it and removes the
     * key once its task has run.
     */
    static boolean claimAhead(Set<String> queued, GeneratedStations reg, String world, int cellX, int cellZ) {
        if (reg.cellMarked(world, cellX, cellZ)) {
            return false;
        }
        return queued.add(aheadKey(world, cellX, cellZ));
    }

    /** Same as {@link #claimAhead} for a land cell, under its own key. */
    static boolean claimAheadLand(Set<String> queued, GeneratedStations reg, String world, int cellX, int cellZ) {
        if (reg.landCellMarked(world, cellX, cellZ)) {
            return false;
        }
        return queued.add(aheadLandKey(world, cellX, cellZ));
    }

    /** Key of a land cell in the set of cells queued ahead. */
    static String aheadLandKey(String world, int cellX, int cellZ) {
        return "land:" + cellKey(world, cellX, cellZ);
    }

    /** Key of a cell in the set of cells queued ahead. */
    static String aheadKey(String world, int cellX, int cellZ) {
        return cellKey(world, cellX, cellZ);
    }

    private static String cellKey(String world, int cellX, int cellZ) {
        return world + ":" + cellX + ":" + cellZ;
    }

    /** The entries a regenerated chunk makes stale: the built ones and those loaded from disk. Entries created this session stay. */
    static List<GeneratedStations.Entry> forgettable(List<GeneratedStations.Entry> touching) {
        List<GeneratedStations.Entry> out = new ArrayList<>();
        for (GeneratedStations.Entry e : touching) {
            if (e.state == GeneratedStations.State.BUILT || !e.thisSession) {
                out.add(e);
            }
        }
        return out;
    }

    /** Every 10 s, off the world thread: queues at low priority the cells around each player that are not planned yet. Never throws. */
    static void planAhead() {
        try {
            if (!TransitSettings.worldgenEnabled()) {
                return;
            }
            for (World world : Universe.get().getWorlds().values()) {
                try {
                    GeneratorProbe probe = probeOf(world);
                    if (probe != null) {
                        world.execute(() -> queueAround(world, probe));
                    }
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cannot plan ahead for %s", world.getName());
                    Telemetry.error("worldgen", e, null, "ahead");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: planning ahead interrupted");
            Telemetry.error("worldgen", e, null, "ahead");
        }
    }

    /** World thread: reads the player positions and queues their cells on the planner. */
    private static void queueAround(World world, GeneratorProbe probe) {
        try {
            ExecutorService ex;
            synchronized (LOCK) {
                ex = planner;
            }
            if (ex == null) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            String name = world.getName();
            GeneratedStations reg = GeneratedStations.get();
            for (PlayerRef pr : world.getPlayerRefs()) {
                if (!pr.isValid()) {
                    continue;
                }
                TransformComponent t = store.getComponent(pr.getReference(), TransformComponent.getComponentType());
                if (t == null) {
                    continue;
                }
                for (int[] cell : cellsAround(t.getPosition().x, t.getPosition().z)) {
                    if (claimAhead(AHEAD_QUEUED, reg, name, cell[0], cell[1])) {
                        try {
                            ex.execute(new PlanTask(PlanTask.AHEAD, () -> planAheadCell(world, probe, cell[0], cell[1])));
                        } catch (RejectedExecutionException rejected) {
                            // the planner was stopped meanwhile: nothing more to queue
                            AHEAD_QUEUED.remove(aheadKey(name, cell[0], cell[1]));
                            return;
                        }
                    }
                }
                for (int[] cell : landCellsAround(t.getPosition().x, t.getPosition().z)) {
                    if (claimAheadLand(AHEAD_QUEUED, reg, name, cell[0], cell[1])) {
                        try {
                            ex.execute(new PlanTask(PlanTask.AHEAD, () -> planAheadLandCell(world, probe, cell[0], cell[1])));
                        } catch (RejectedExecutionException rejected) {
                            AHEAD_QUEUED.remove(aheadLandKey(name, cell[0], cell[1]));
                            return;
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cells ahead not queued for %s", world.getName());
            Telemetry.error("worldgen", e, null, "ahead");
        }
    }

    /** Planner thread: plans a cell queued ahead and decides on the entries it created. */
    private static void planAheadCell(World world, GeneratorProbe probe, int cellX, int cellZ) {
        try {
            List<GeneratedStations.Entry> added = planCell(world, probe, cellX, cellZ);
            if (!added.isEmpty()) {
                world.execute(() -> decideAll(world, added));
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated sea station: cell (%d, %d) not planned ahead", cellX, cellZ);
            Telemetry.error("worldgen", e, Deployables.BOAT_STATION_KIND, "ahead");
        } finally {
            AHEAD_QUEUED.remove(aheadKey(world.getName(), cellX, cellZ));
        }
    }

    /** Planner thread: plans a land cell queued ahead and decides on the entry it created. */
    private static void planAheadLandCell(World world, GeneratorProbe probe, int cellX, int cellZ) {
        try {
            List<GeneratedStations.Entry> added = planLandCell(world, probe, cellX, cellZ);
            if (!added.isEmpty()) {
                world.execute(() -> decideAll(world, added));
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated air station: land cell (%d, %d) not planned ahead", cellX, cellZ);
            Telemetry.error("worldgen", e, Deployables.STATION_KIND, "ahead");
        } finally {
            AHEAD_QUEUED.remove(aheadLandKey(world.getName(), cellX, cellZ));
        }
    }

    /** Log prefix naming the kind of a generated station: aerial (balloon) or maritime (boat). */
    static String logName(String kind) {
        return Deployables.BOAT_STATION_KIND.equals(kind) ? "Generated sea station" : "Generated air station";
    }

    /**
     * The positions {x, z} the planning of a station keeps away from: the network stations of the kind and the registry entries
     * PLANNED or BUILT. A portal passes skipPlannedLand: the land entries still planned give way to it (see displaceLandEntries).
     */
    private static List<int[]> existing(String world, String kind, boolean skipPlannedLand) {
        List<int[]> out = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world, kind)) {
            out.add(new int[]{s.origin.x, s.origin.z});
        }
        out.addAll(GeneratedStations.get().positions(world, kind, skipPlannedLand));
        return out;
    }

    /**
     * The land entries to move for a portal station at (px, pz): PLANNED, of land-cell origin, not requested, and whose active site is
     * closer than BALLOON_SPACING to the portal site.
     */
    static List<GeneratedStations.Entry> landEntriesNear(List<GeneratedStations.Entry> all, Set<GeneratedStations.Entry> requested,
                                                         int px, int pz) {
        long r2 = (long) WorldStationPlanner.BALLOON_SPACING * WorldStationPlanner.BALLOON_SPACING;
        List<GeneratedStations.Entry> out = new ArrayList<>();
        for (GeneratedStations.Entry e : all) {
            if (!Deployables.STATION_KIND.equals(e.kind) || e.origin != GeneratedStations.Origin.LAND
                    || e.state != GeneratedStations.State.PLANNED || requested.contains(e)) {
                continue;
            }
            long dx = e.x - px, dz = e.z - pz;
            if (dx * dx + dz * dz < r2) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * World thread: a portal station was planned at its site. The land entries still planned and too close to it move to their next
     * site that keeps its distance from the other balloon sites, or are abandoned when their sites are used up.
     */
    private static void displaceLandEntries(World world, WorldStationPlanner.PlannedSite portal) {
        GeneratedStations reg = GeneratedStations.get();
        String name = world.getName();
        try {
            for (GeneratedStations.Entry e : landEntriesNear(reg.entries(name), REQUESTED, portal.x(), portal.z())) {
                if (e.state != GeneratedStations.State.PLANNED || REQUESTED.contains(e)) {
                    continue;
                }
                int fromX = e.x, fromZ = e.z;
                if (moveOffOldTerrain(reg, e, name)) {
                    LOGGER.at(Level.INFO).log("%s: too close to the portal at (%d, %d), site (%d, %d) replaced by the next one %d/%d",
                            logName(e.kind), portal.x(), portal.z(), fromX, fromZ, e.active + 1, e.sites.size());
                    decideAgain(world, e);
                } else {
                    abandon(e, "too close to a portal station", GeneratedStations.Reason.OTHER);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Generated air station: land stations near portal (%d, %d) not moved",
                    portal.x(), portal.z());
            Telemetry.error("worldgen", ex, Deployables.STATION_KIND, "decide");
        }
    }

    /**
     * World thread only, like every change of site, so that a site never changes between a BUILD decision and the build.
     * Advances an entry to its next site that keeps its distance from the other stations of its kind: BALLOON_SPACING for a balloon
     * station, BOAT_SPACING for a main pier, PARTNER_REUSE for a partner (partners sit far from their main pier, so the main spacing would refuse most of their sites). The radius comes
     * from the entry, the caller gives the positions {x, z} to keep away from. False when the sites are used up, the caller then
     * abandons the entry.
     */
    static boolean moveToNextSite(GeneratedStations reg, GeneratedStations.Entry e, List<int[]> others) {
        while (reg.advance(e)) {
            if (keepsSpacing(e, reg.activeSite(e), others)) {
                return true;
            }
        }
        return false;
    }

    /** True when a site of the entry keeps the spacing of the entry's kind from every position {x, z} given. */
    static boolean keepsSpacing(GeneratedStations.Entry e, WorldStationPlanner.Site site, List<int[]> others) {
        int radius = e.partner ? WorldStationPlanner.PARTNER_REUSE
                : Deployables.BOAT_STATION_KIND.equals(e.kind) ? WorldStationPlanner.BOAT_SPACING : WorldStationPlanner.BALLOON_SPACING;
        long r2 = (long) radius * radius;
        for (int[] p : others) {
            long dx = p[0] - site.x(), dz = p[1] - site.z();
            if (dx * dx + dz * dz < r2) {
                return false;
            }
        }
        return true;
    }

    /** Index of the first site of the entry that keeps its spacing from the positions given, or -1 when none does. */
    static int firstFittingSite(GeneratedStations.Entry e, List<int[]> others) {
        for (int i = 0; i < e.sites.size(); i++) {
            if (keepsSpacing(e, e.sites.get(i), others)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The entries to take up again among those of a world, in order: ABANDONED with the reason OLD_TERRAIN or UNKNOWN (never REMOVED,
     * never another reason). Each goes back to PLANNED on the first of its sites that keeps its spacing from the PLANNED and BUILT
     * entries (the ones revived before it included) and from the network stations of its kind, or stays abandoned when none does.
     * Network positions come from networkOf (kind to positions {x, z}). Returns the entries taken up.
     */
    static List<GeneratedStations.Entry> reviveEntries(GeneratedStations reg, List<GeneratedStations.Entry> all,
                                                       java.util.function.Function<String, List<int[]>> networkOf) {
        List<GeneratedStations.Entry> revived = new ArrayList<>();
        for (GeneratedStations.Entry e : all) {
            if (e.state != GeneratedStations.State.ABANDONED || e.reason == GeneratedStations.Reason.OTHER) {
                continue;
            }
            List<int[]> others = otherSites(all, e, networkOf.apply(e.kind));
            int site = firstFittingSite(e, others);
            if (site >= 0) {
                reg.revive(e, site);
                REQUESTED.remove(e);
                revived.add(e);
            }
        }
        return revived;
    }

    /** Every world the feature runs in, taken up again when the setting goes from off to on. Never throws. */
    static void reviveAll() {
        try {
            if (!TransitSettings.worldgenEnabled() || Universe.get() == null) {
                return;
            }
            for (World world : Universe.get().getWorlds().values()) {
                try {
                    if (probeOf(world) != null) {
                        world.execute(() -> reviveWorld(world));
                    }
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cannot revive the abandoned stations for %s", world.getName());
                    Telemetry.error("worldgen", e, null, "revive");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: reviving the abandoned stations interrupted");
            Telemetry.error("worldgen", e, null, "revive");
        }
    }

    /** World thread: takes up the abandoned entries of the world (see {@link #reviveEntries}) and decides on them. */
    private static void reviveWorld(World world) {
        try {
            if (!TransitSettings.worldgenEnabled() || !TransitSettings.worldgenOldTerrain()) {
                return;
            }
            String name = world.getName();
            GeneratedStations reg = GeneratedStations.get();
            List<GeneratedStations.Entry> revived = reviveEntries(reg, reg.entries(name), kind -> {
                List<int[]> out = new ArrayList<>();
                for (TransitNetwork.Station s : TransitNetwork.get().stations(name, kind)) {
                    out.add(new int[]{s.origin.x, s.origin.z});
                }
                return out;
            });
            LOGGER.at(Level.INFO).log("Generated stations: %d abandoned station(s) revived in world %s", revived.size(), name);
            if (!revived.isEmpty()) {
                decideAll(world, revived);
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated station: cannot revive the abandoned stations for %s", world.getName());
            Telemetry.error("worldgen", e, null, "revive");
        }
    }

    /**
     * The positions {x, z} of the same kind an entry must keep away from when it moves to another site. For a balloon station or a
     * main pier, the active site of every other entry of the kind (PLANNED or BUILT, partners excluded for a main pier) and, for a
     * balloon station, the balloon stations of the network. For a partner, every other boat entry of the registry (PLANNED or BUILT)
     * and the boat stations of the network.
     */
    static List<int[]> otherSites(String world, GeneratedStations.Entry e) {
        List<int[]> network = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world, e.kind)) {
            network.add(new int[]{s.origin.x, s.origin.z});
        }
        return otherSites(GeneratedStations.get().entries(world), e, network);
    }

    /** Same as {@link #otherSites(String, GeneratedStations.Entry)} over the given entries and the positions {x, z} of the network stations of the entry's kind. */
    static List<int[]> otherSites(List<GeneratedStations.Entry> entries, GeneratedStations.Entry e, List<int[]> network) {
        GeneratedStations reg = GeneratedStations.get();
        List<int[]> out = new ArrayList<>();
        for (GeneratedStations.Entry o : entries) {
            if (o == e || !o.kind.equals(e.kind) || (!e.partner && o.partner)) {
                continue;
            }
            if (o.state == GeneratedStations.State.PLANNED || o.state == GeneratedStations.State.BUILT) {
                WorldStationPlanner.Site s = reg.activeSite(o);
                out.add(new int[]{s.x(), s.z()});
            }
        }
        if (e.partner || !Deployables.BOAT_STATION_KIND.equals(e.kind)) {
            out.addAll(network);
        }
        return out;
    }

    /**
     * An entry whose site was refused and that moved to its next site: it is no longer requested and is decided again on the world
     * thread, so it builds at once when its chunks are already new and loaded.
     */
    static void decideAgain(World world, GeneratedStations.Entry e) {
        REQUESTED.remove(e);
        world.execute(() -> decideAll(world, List.of(e)));
    }

    /** World thread: build or abandon each planned entry whose footprint is settled. */
    private static void decideAll(World world, List<GeneratedStations.Entry> entries) {
        NewChunks news = newChunksOf(world);
        ChunkStore store = world.getChunkStore();
        for (GeneratedStations.Entry e : entries) {
            WorldStationPlanner.Site site = null;
            try {
                if (e.state != GeneratedStations.State.PLANNED || REQUESTED.contains(e)) {
                    continue;
                }
                site = GeneratedStations.get().activeSite(e);
                Predicate<long[]> isNew = c -> news.isNew((int) c[0], (int) c[1]);
                Predicate<long[]> loadedOld = c -> {
                    var ref = store.getChunkReference(ChunkUtil.indexChunk((int) c[0], (int) c[1]));
                    return ref != null && ref.isValid() && !isNew.test(c);
                };
                switch (decide(footprintChunks(e.kind, site.x(), site.z(), site.rotation()), isNew, loadedOld, TransitSettings.worldgenOldTerrain())) {
                    case BUILD -> {
                        REQUESTED.add(e);
                        StationBuilder.build(world, e);
                    }
                    case ABANDON -> {
                        e.oldTerrainSeen = true;
                        if (moveOffOldTerrain(GeneratedStations.get(), e, world.getName())) {
                            LOGGER.at(Level.INFO).log("%s: old terrain at (%d, %d), next site %d/%d", logName(e.kind), site.x(), site.z(),
                                    e.active + 1, e.sites.size());
                            decideAgain(world, e);
                        } else {
                            abandon(e, "old terrain in the footprint", GeneratedStations.Reason.OLD_TERRAIN);
                        }
                    }
                    case WAIT -> {
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("%s: cannot decide at (%d, %d)", logName(e.kind),
                        site == null ? e.x : site.x(), site == null ? e.z : site.z());
                Telemetry.error("worldgen", ex, e.kind, "decide");
            }
        }
    }
}
