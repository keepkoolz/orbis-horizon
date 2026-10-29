package com.keepkoolz.orbishorizon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Anonymous usage counters, one per feature; no player or position data. */
final class UsageCounters {
    static final List<String> NAMES = List.of(
            "deploy.balloon", "deploy.balloon_basic", "deploy.balloon_transport", "deploy.airship", "deploy.steamboat",
            "deploy.bedroll", "deploy.tent", "deploy.tent_big", "deploy.station", "deploy.boatStation",
            "takeoff.balloon", "takeoff.balloon_basic", "takeoff.balloon_transport", "takeoff.airship", "takeoff.steamboat",
            "airship.voyage", "balloon.voyage", "airship.recall", "airship.pilot_hired", "transit.trip", "transit.boatTrip",
            "cage.capture", "glider.jump", "camp.upgrade",
            "worldgen.station.air", "worldgen.station.boat", "worldgen.station.abandoned",
            "worldgen.boat.built.first", "worldgen.boat.built.fallback", "worldgen.boat.built.repaired", "worldgen.boat.exhausted",
            "worldgen.boat.dredged",
            "worldgen.air.built.first", "worldgen.air.built.fallback", "worldgen.air.exhausted");

    private final Map<String, LongAdder> counters = new LinkedHashMap<>();

    UsageCounters() {
        for (String n : NAMES) counters.put(n, new LongAdder());
    }

    /** Adds one to a counter; unknown names are ignored. */
    void increment(String name) {
        LongAdder a = counters.get(name);
        if (a != null) a.increment();
    }

    /** Returns the non-zero counters in NAMES order and resets each one atomically. */
    Map<String, Long> snapshotAndReset() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, LongAdder> e : counters.entrySet()) {
            long v = e.getValue().sumThenReset();
            if (v != 0) out.put(e.getKey(), v);
        }
        return out;
    }
}
