package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3i;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * The sea charts of the running worlds and the single thread that fills them from the berth of each maritime station. A fill labels
 * the water around the berth, then the ocean number of every maritime station of the world is read again from the chart, since a
 * fill can merge two oceans.
 */
final class SeaCharts {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Delay before a fill is tried again while the generator of the world is still loading. */
    private static final long RETRY_SECONDS = 5;
    private static final Object LOCK = new Object();
    private static final Map<String, SeaChart> CHARTS = new ConcurrentHashMap<>();
    /** Worlds deleted when they are removed: their chart stays in memory. */
    private static final Set<String> THROWAWAY = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    /** Worlds whose start was handled by this class, to react once per world object. */
    private static final Map<String, World> STARTED = new ConcurrentHashMap<>();
    /** Worlds whose chart changed since it was last written. */
    private static final Set<String> DIRTY = ConcurrentHashMap.newKeySet();
    /** Held while a chart file is written, so two writes of the same file never overlap. */
    /** Held while the chart of a world is made, so a world never gets two chart objects. */
    private static final Object CHART_LOCK = new Object();
    private static final Object SAVE_LOCK = new Object();
    /** What the shuttles learned (segments, closed cells, incomplete pairs): one instance, written by the chart thread only. */
    private static final SeaChartBook BOOK = new SeaChartBook();
    /** The book changed since it was last written. */
    private static final AtomicBoolean BOOK_DIRTY = new AtomicBoolean();
    /** A write of the book is waiting on the chart thread: later changes ride on it. */
    private static final AtomicBoolean BOOK_SAVE_QUEUED = new AtomicBoolean();
    private static volatile boolean stopped;
    private static volatile Path dir;
    private static ScheduledThreadPoolExecutor thread;

    private SeaCharts() {
    }

    /** The shared memory of the sailed segments, closed cells and incomplete pairs. */
    static SeaChartBook book() {
        return BOOK;
    }

    /** Starts the chart thread; charts are saved under dataDir/sea-chart, the book in dataDir/sea-chart.json. Not on the world thread. */
    static void start(Path dataDir) {
        BOOK.setChangeListener(null);
        BOOK.load(dataDir.resolve("sea-chart.json"));
        BOOK_DIRTY.set(false);
        BOOK_SAVE_QUEUED.set(false);
        BOOK.setChangeListener(SeaCharts::bookChanged);
        synchronized (LOCK) {
            dir = dataDir.resolve("sea-chart");
            stopped = false;
            if (thread == null) {
                ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread th = new Thread(r, "OrbisHorizon-SeaChart");
                    th.setDaemon(true);
                    th.setPriority(Thread.MIN_PRIORITY);
                    return th;
                });
                t.setRemoveOnCancelPolicy(true);
                t.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                thread = t;
            }
        }
    }

    /** Stops the thread (the fill in progress ends, 5 s at most) and saves every chart that is kept. */
    static void stop() {
        ScheduledThreadPoolExecutor t;
        synchronized (LOCK) {
            stopped = true;
            t = thread;
            thread = null;
        }
        if (t != null) {
            // A fill in progress sees the interruption and ends at once without publishing; waiting jobs are dropped.
            t.shutdownNow();
            try {
                t.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Map.Entry<String, SeaChart> e : CHARTS.entrySet()) {
            if (!THROWAWAY.contains(e.getKey()) && DIRTY.contains(e.getKey())) {
                save(e.getKey(), e.getValue());
            }
        }
        // Always written: a queued write may have been dropped, or interrupted by the shutdown above.
        BOOK_DIRTY.set(false);
        BOOK.save();
    }

    /** A mutation of the book (any thread, under its lock): one write is queued on the chart thread, later mutations ride on it. */
    private static void bookChanged() {
        BOOK_DIRTY.set(true);
        ScheduledThreadPoolExecutor t;
        synchronized (LOCK) {
            t = thread;
        }
        if (t == null || !BOOK_SAVE_QUEUED.compareAndSet(false, true)) {
            return; // stopped (stop() writes it), or a write is already queued
        }
        try {
            t.execute(SeaCharts::saveBook);
        } catch (RejectedExecutionException e) {
            BOOK_SAVE_QUEUED.set(false); // stopped: stop() writes it
        }
    }

    /** Chart thread. Never throws. */
    private static void saveBook() {
        BOOK_SAVE_QUEUED.set(false); // a change during the write queues the next one
        try {
            if (BOOK_DIRTY.getAndSet(false)) {
                BOOK.save();
            }
        } catch (RuntimeException e) {
            failed(e, "sea-chart.json");
        }
    }

    /** The chart of a world, or null while it does not exist. */
    static SeaChart chart(String world) {
        return CHARTS.get(world);
    }

    /** Ocean number of the berth of a maritime station according to its world's chart, 0 when unknown. Reads the berth geometry: world thread. */
    static int oceanOf(TransitNetwork.Station s) {
        SeaChart c = CHARTS.get(s.world);
        if (c == null) {
            return 0;
        }
        Vector3i b = BoatBerth.berthCentreCell(s);
        return c.ocean(SeaChart.cellOf(b.x), SeaChart.cellOf(b.z));
    }

    /**
     * The chart of a world, made (or read from its file, except for a throwaway world) at the first call. Null when the world's
     * generator is not the game's: such a world has no chart.
     */
    private static SeaChart ensureChart(World world) {
        synchronized (CHART_LOCK) {
            return ensureChartLocked(world);
        }
    }

    private static SeaChart ensureChartLocked(World world) {
        String name = world.getName();
        long seed = world.getWorldConfig().getSeed();
        SeaChart existing = CHARTS.get(name);
        if (existing != null && existing.seed() == seed) {
            return existing;
        }
        if (GeneratorProbe.chartAvailability(world.getChunkStore().getGenerator()) == GeneratorProbe.Availability.OFF) {
            return null;
        }
        boolean throwaway = world.getWorldConfig().isDeleteOnRemove();
        if (throwaway) {
            THROWAWAY.add(name);
        } else {
            THROWAWAY.remove(name);
        }
        SeaChart loaded = throwaway || dir == null ? null : SeaChartFile.read(file(name), seed);
        SeaChart chart = loaded != null ? loaded : new SeaChart(seed);
        CHARTS.put(name, chart);
        return chart;
    }

    /** A world starts: its saved chart is loaded (not for a throwaway world), then each maritime station without a label is filled. Once per world object. */
    static void onWorldStart(World world) {
        try {
            String name = world.getName();
            if (STARTED.put(name, world) == world) {
                return;
            }
            SeaChart chart = ensureChart(world);
            if (chart == null) {
                return;
            }
            for (TransitNetwork.Station s : TransitNetwork.get().stations(name, Deployables.BOAT_STATION_KIND)) {
                try {
                    Vector3i b = BoatBerth.berthCentreCell(s);
                    if (chart.ocean(SeaChart.cellOf(b.x), SeaChart.cellOf(b.z)) != 0) {
                        s.oceanId = chart.ocean(SeaChart.cellOf(b.x), SeaChart.cellOf(b.z));
                    } else {
                        submit(world, chart, b.x, b.z, 0);
                    }
                } catch (RuntimeException e) {
                    failed(e, s.name);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            failed(e, null);
        }
    }

    /** A station was registered: a maritime one is filled from its berth. */
    static void stationAdded(World world, TransitNetwork.Station s) {
        try {
            if (!Deployables.BOAT_STATION_KIND.equals(s.kind)) {
                return;
            }
            if (STARTED.get(s.world) != world) {
                onWorldStart(world);
                return; // onWorldStart fills every station of the world that has no label, this one included
            }
            SeaChart chart = ensureChart(world);
            if (chart == null) {
                return;
            }
            Vector3i b = BoatBerth.berthCentreCell(s);
            submit(world, chart, b.x, b.z, 0);
        } catch (RuntimeException | LinkageError e) {
            failed(e, s.name);
        }
    }

    private static void submit(World world, SeaChart chart, int x, int z, long delaySeconds) {
        ScheduledThreadPoolExecutor t;
        synchronized (LOCK) {
            t = thread;
        }
        if (t == null) {
            return;
        }
        try {
            t.schedule(() -> fill(world, chart, x, z), delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // stopped: nothing more to fill
        }
    }

    /** Chart thread. Never throws. */
    private static void fill(World world, SeaChart chart, int x, int z) {
        try {
            Object generator = world.getChunkStore().getGenerator();
            GeneratorProbe.Availability a = GeneratorProbe.chartAvailability(generator);
            if (a == GeneratorProbe.Availability.WAIT) {
                submit(world, chart, x, z, RETRY_SECONDS);
                return;
            }
            GeneratorProbe probe = a == GeneratorProbe.Availability.READY ? GeneratorProbe.forChart(world) : null;
            if (probe == null) {
                if (WARNED.add(world.getName())) {
                    LOGGER.at(Level.INFO).log("Carte de l'océan : le monde %s n'utilise pas le générateur du jeu, aucune carte pour ce monde", world.getName());
                }
                return;
            }
            long t0 = System.nanoTime();
            int ocean = chart.fill(probe, x, z);
            DIRTY.add(world.getName());
            if (stopped) {
                return; // an interrupted fill published nothing, and stop() saves what the chart holds
            }
            LOGGER.at(Level.INFO).log("Carte de l'océan : remplissage depuis (%d, %d) dans %s en %d ms, océan %d", x, z, world.getName(),
                    (System.nanoTime() - t0) / 1_000_000L, ocean);
            if (!THROWAWAY.contains(world.getName()) && !stopped) {
                save(world.getName(), chart);
            }
            if (!stopped) {
                world.execute(() -> relabel(world));
            }
        } catch (RuntimeException | LinkageError e) {
            failed(e, world.getName());
        }
    }

    /** World thread: every maritime station of the world takes the canonical number of the ocean of its berth. */
    private static void relabel(World world) {
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND)) {
            try {
                s.oceanId = oceanOf(s);
            } catch (RuntimeException e) {
                failed(e, s.name);
            }
        }
    }

    private static Path file(String world) {
        return dir.resolve(world.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin");
    }

    private static void save(String world, SeaChart chart) {
        Path d = dir;
        if (d == null) {
            return;
        }
        try {
            synchronized (SAVE_LOCK) {
                SeaChartFile.write(file(world), chart);
                DIRTY.remove(world);
            }
        } catch (java.nio.channels.ClosedByInterruptException e) {
            // The chart thread was interrupted by stop() during a write: the chart stays dirty and stop() writes it.
            if (!stopped) {
                failed(e, world);
            }
        } catch (java.io.IOException | RuntimeException e) {
            failed(e, world);
        }
    }

    private static void failed(Throwable e, String what) {
        LOGGER.at(Level.WARNING).withCause(e).log("Carte de l'océan : échec (%s), l'océan reste inconnu", what);
        Telemetry.error("boat.chart", e, Deployables.BOAT_STATION_KIND, null);
    }
}
