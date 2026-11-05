package com.keepkoolz.orbishorizon.api;

import java.util.Collection;

/** Anonymous usage counters, errors and timings of Orbis Horizon. Every call is safe when telemetry is off. */
public interface Telemetry {
    /** Adds one to a counter. A name that is not registered is ignored. */
    void count(String name);

    /** Reports an error. kind and phase may be null. */
    void error(String zone, Throwable t, String kind, String phase);

    /** Start mark for timing, 0 when telemetry is off. */
    long start();

    /** Ends a timing begun by start(). kind may be null. */
    void timing(String name, long startNanos, String kind);

    /** Adds counter names (names already known are ignored). */
    void registerCounters(Collection<String> names);
}
