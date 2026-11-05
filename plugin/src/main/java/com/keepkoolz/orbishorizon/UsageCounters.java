package com.keepkoolz.orbishorizon;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/** Anonymous usage counters, one per feature; no player or position data. */
final class UsageCounters {
    static final List<String> NAMES = List.of(
            "deploy.balloon", "deploy.balloon_basic", "deploy.balloon_transport", "deploy.airship", "deploy.steamboat",
            "deploy.bedroll", "deploy.tent", "deploy.tent_big",
            "takeoff.balloon", "takeoff.balloon_basic", "takeoff.balloon_transport", "takeoff.airship", "takeoff.steamboat",
            "airship.voyage", "balloon.voyage", "airship.recall", "airship.pilot_hired", "transit.trip", "transit.boatTrip",
            "cage.capture", "glider.jump", "camp.upgrade", "station.contract", "station.masterPlaced", "station.quayPlaced");

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    /** Counter names in report order: NAMES, then the registered names in the order they arrived. */
    private final List<String> order = new CopyOnWriteArrayList<>();

    UsageCounters() {
        for (String n : NAMES) {
            counters.put(n, new LongAdder());
            order.add(n);
        }
    }

    /** Adds the names that are not known yet, in the order given. Names already known (and repeats) are ignored, and so is null. */
    synchronized void register(Collection<String> names) {
        if (names == null) return;
        for (String n : names) {
            if (n != null && !counters.containsKey(n)) {
                counters.put(n, new LongAdder());
                order.add(n);
            }
        }
    }

    /** Adds one to a counter; unknown names are ignored. */
    void increment(String name) {
        if (name == null) return;
        LongAdder a = counters.get(name);
        if (a != null) a.increment();
    }

    /** Returns the non-zero counters in NAMES then registration order and resets each one atomically. */
    Map<String, Long> snapshotAndReset() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String name : order) {
            long v = counters.get(name).sumThenReset();
            if (v != 0) out.put(name, v);
        }
        return out;
    }
}
