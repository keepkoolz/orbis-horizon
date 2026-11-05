package com.keepkoolz.orbishorizon;

import java.util.Collection;

/** The telemetry facade for the public API. */
final class ApiTelemetry implements com.keepkoolz.orbishorizon.api.Telemetry {
    @Override
    public void count(String name) {
        Telemetry.count(name);
    }

    @Override
    public void error(String zone, Throwable t, String kind, String phase) {
        Telemetry.error(zone, t, kind, phase);
    }

    @Override
    public long start() {
        return Telemetry.start();
    }

    @Override
    public void timing(String name, long startNanos, String kind) {
        Telemetry.timing(name, startNanos, kind);
    }

    @Override
    public void registerCounters(Collection<String> names) {
        Telemetry.registerCounters(names);
    }
}
