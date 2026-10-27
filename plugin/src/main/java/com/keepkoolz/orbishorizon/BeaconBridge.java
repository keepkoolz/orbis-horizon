package com.keepkoolz.orbishorizon;

import com.alechilles.beacon.api.TelemetryEventContext;
import com.alechilles.beacon.embedded.EmbeddedTelemetryBootstrap;
import com.alechilles.beacon.embedded.EmbeddedTelemetryService;
import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

/** The only class that imports Beacon. Loaded lazily by Telemetry so a missing runtime cannot break the mod. */
final class BeaconBridge {
    private final EmbeddedTelemetryService service;
    private final String gameVersion;

    private BeaconBridge(EmbeddedTelemetryService service, String gameVersion) {
        this.service = service;
        this.gameVersion = gameVersion;
    }

    static BeaconBridge open(JavaPlugin plugin) {
        String version = ManifestUtil.getImplementationVersion();
        EmbeddedTelemetryService service = EmbeddedTelemetryBootstrap.bootstrap(plugin);
        service.start();
        return new BeaconBridge(service, version == null ? "unknown" : version);
    }

    boolean enabled() {
        return service.isEnabled();
    }

    void usage(String name, long count) {
        service.recordUsageWithContext(name, TelemetryEventContext.usage().detail("count", count).build());
    }

    /** Beacon drops it under the descriptor threshold. kind and ticks may be null and are then left out. */
    void performance(String name, int durationMs, Double value, String kind, Long ticks) {
        TelemetryEventContext.Builder b = TelemetryEventContext.performance();
        if (kind != null) b.detail("kind", cap(kind, 32));
        if (ticks != null) b.detail("ticks", ticks);
        service.recordPerformanceWithContext(name, durationMs, value, b.build());
    }

    /** Null values are never passed on: Beacon rejects them. */
    void error(String zone, RuntimeException redacted, String kind, String phase, long repeats) {
        TelemetryEventContext.Builder b = TelemetryEventContext.error();
        if (phase != null) b.phase(phase);
        b.detail("zone", cap(zone, 64));
        if (kind != null) b.detail("kind", cap(kind, 32));
        if (phase != null) b.detail("phase", cap(phase, 32));
        if (redacted.getMessage() != null) b.detail("exception", cap(redacted.getMessage(), 120));
        b.detail("repeats", repeats);
        b.detail("gameVersion", cap(gameVersion, 32));
        service.recordErrorWithContext("orbis.error", redacted, b.build());
    }

    /** Keeps a string detail within the maxLength of its Beacon descriptor. */
    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    void flush() {
        service.requestFlush();
    }

    record FlushState(boolean inProgress, int pending, String lastResult) {
    }

    FlushState state() {
        var d = service.diagnostics();
        return new FlushState(d.flushInProgress(), d.pendingReports(), d.lastFlushResult());
    }

    void shutdown() {
        service.shutdown();
    }
}
