package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Telemetry facade (Beacon). Never imports Beacon itself: every call into it goes through
 * BeaconBridge inside a try/catch (Throwable), so a missing or failing runtime only disables telemetry.
 */
final class Telemetry {
    private static final UsageCounters COUNTERS = new UsageCounters();
    private static final TickTimings TICKS = new TickTimings();
    private static final ErrorThrottle THROTTLE = new ErrorThrottle(ErrorThrottle.WINDOW_MS, ErrorThrottle.MAX_ENTRIES);
    private static final long FLUSH_PERIOD_S = 3600;
    private static final long SHUTDOWN_FLUSH_CAP_MS = 5000;

    private static volatile BeaconBridge bridge;
    private static volatile boolean unavailable;
    private static volatile boolean warned;
    private static ScheduledExecutorService executor;
    private static JavaPlugin owner;

    private Telemetry() {
    }

    static synchronized void start(JavaPlugin plugin) {
        if (bridge != null || executor != null) return;
        owner = plugin;
        try {
            bridge = BeaconBridge.open(plugin);
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "OrbisHorizon-Telemetry");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(Telemetry::flushUsage, FLUSH_PERIOD_S, FLUSH_PERIOD_S, TimeUnit.SECONDS);
        } catch (Throwable t) {
            fail(t);
        }
    }

    static void count(String name) {
        COUNTERS.increment(name);
    }

    /** Start mark for timing and tick, 0 when telemetry is off so that nothing is measured. */
    static long start() {
        return bridge == null || unavailable ? 0L : (System.nanoTime() | 1L);
    }

    /** Ends a timing begun by start() (call it in a finally): one performance event, kept by Beacon above its threshold. kind may be null. */
    static void timing(String name, long startNanos, String kind) {
        if (startNanos == 0L) return;
        BeaconBridge b = bridge;
        if (b == null || unavailable) return;
        try {
            b.performance(name, TickTimings.ceilMs(System.nanoTime() - startNanos), null, kind, null);
        } catch (Throwable e) {
            fail(e);
        }
    }

    /** Ends a tick begun by start(): added to the hourly aggregate of the system. */
    static void tick(String name, long startNanos) {
        if (startNanos == 0L) return;
        try {
            TICKS.record(name, System.nanoTime() - startNanos);
        } catch (Throwable e) {
            fail(e);
        }
    }

    /** kind and phase may be null. */
    static void error(String zone, Throwable t, String kind, String phase) {
        BeaconBridge b = bridge;
        if (b == null || unavailable || t == null) return;
        try {
            long n = THROTTLE.admit(ErrorThrottle.fingerprint(zone, t), System.currentTimeMillis());
            if (n >= 0) b.error(zone, ErrorThrottle.redact(t), kind, phase, n);
        } catch (Throwable e) {
            fail(e);
        }
    }

    static String kindId(Deployables.Kind kind) {
        return kind == null ? null : kind.id;
    }

    private static void flushUsage() {
        BeaconBridge b = bridge;
        if (b == null || unavailable) return;
        try {
            for (Map.Entry<String, Long> e : COUNTERS.snapshotAndReset().entrySet()) b.usage(e.getKey(), e.getValue());
            for (Map.Entry<String, TickTimings.Snapshot> e : TICKS.snapshotAndReset().entrySet()) {
                TickTimings.Snapshot t = e.getValue();
                b.performance(e.getKey(), t.maxMs(), t.meanMs(), null, t.ticks());
            }
            b.flush();
        } catch (Throwable e) {
            fail(e);
        }
    }

    static synchronized void stop() {
        BeaconBridge b = bridge;
        if (b == null) return;
        long started = System.currentTimeMillis();
        String last = null;
        int pending = 0;
        try {
            // Let a running periodic flush finish instead of interrupting it mid-call.
            if (executor != null) {
                executor.shutdown();
                try {
                    executor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            String before = b.state().lastResult();
            flushUsage();
            long flushStarted = System.currentTimeMillis();
            // requestFlush is asynchronous: wait for Beacon to upload before it is shut down.
            while (true) {
                long elapsed = System.currentTimeMillis() - flushStarted;
                BeaconBridge.FlushState st = b.state();
                last = st.lastResult();
                pending = st.pending();
                if (flushSettled(before, last, st.inProgress(), pending, elapsed) || elapsed >= SHUTDOWN_FLUSH_CAP_MS) break;
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            fail(e);
        }
        bridge = null;
        try {
            b.shutdown();
        } catch (Throwable e) {
            fail(e);
        }
        try {
            if (owner != null) {
                owner.getLogger().at(Level.INFO).log("Télémétrie : envoi d'arrêt terminé en %d ms (en attente : %d, résultat : %s)",
                        System.currentTimeMillis() - started, pending, last);
            }
        } catch (Throwable ignored) {
            // logging must never break the mod
        }
    }

    /** True when the shutdown wait can end: Beacon is idle and either empty, finished a new pass, or did nothing for 1 s. */
    static boolean flushSettled(String resultBefore, String resultNow, boolean inProgress, int pending, long elapsedMs) {
        if (inProgress) return false;
        if (pending == 0) return true;
        if (resultNow != null && !resultNow.equals(resultBefore)) return true;
        return elapsedMs >= 1000;
    }

    private static void fail(Throwable t) {
        unavailable = true;
        if (warned) return;
        warned = true;
        try {
            if (owner != null) owner.getLogger().at(Level.WARNING).withCause(t).log("Télémétrie indisponible : %s", t);
        } catch (Throwable ignored) {
            // logging must never break the mod
        }
    }
}
