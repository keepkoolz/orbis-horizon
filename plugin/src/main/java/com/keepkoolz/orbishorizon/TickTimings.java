package com.keepkoolz.orbishorizon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Per-system tick durations of the flight systems: count, total and worst tick, no player or position data. */
final class TickTimings {
    static final List<String> NAMES = List.of("tick.balloon", "tick.airship");

    /** The ticks seen since the last reset, in nanoseconds. */
    record Snapshot(long ticks, long totalNanos, long maxNanos) {
        /** The worst tick in whole milliseconds, rounded up. */
        int maxMs() {
            return ceilMs(maxNanos);
        }

        /** The mean tick in milliseconds, 0 when there was no tick. */
        double meanMs() {
            return ticks <= 0 ? 0 : totalNanos / (double) ticks / 1_000_000.0;
        }
    }

    private static final class Slot {
        final LongAdder ticks = new LongAdder();
        final LongAdder total = new LongAdder();
        final AtomicLong max = new AtomicLong();
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    TickTimings() {
        for (String n : NAMES) slots.put(n, new Slot());
    }

    /** Adds one tick of the given duration; unknown names and negative durations are ignored. */
    void record(String name, long nanos) {
        Slot s = slots.get(name);
        if (s == null || nanos < 0) return;
        s.ticks.increment();
        s.total.add(nanos);
        s.max.accumulateAndGet(nanos, Math::max);
    }

    /** Returns the systems that ticked, in NAMES order, and resets each one. */
    Map<String, Snapshot> snapshotAndReset() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            Slot s = e.getValue();
            long ticks = s.ticks.sumThenReset();
            long total = s.total.sumThenReset();
            long max = s.max.getAndSet(0);
            if (ticks > 0) out.put(e.getKey(), new Snapshot(ticks, total, max));
        }
        return out;
    }

    /** Nanoseconds to whole milliseconds, rounded up and capped to an int. */
    static int ceilMs(long nanos) {
        if (nanos <= 0) return 0;
        long ms = nanos / 1_000_000L + (nanos % 1_000_000L == 0 ? 0 : 1);
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }
}
