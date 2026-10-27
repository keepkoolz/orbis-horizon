package com.keepkoolz.orbishorizon;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A unit of work for the worldgen planner queue. Lower priority values run first, tasks of equal priority run in arrival order.
 * Tasks must be handed to the executor with execute, never submit, which would wrap them and hide the ordering.
 */
final class PlanTask implements Runnable, Comparable<PlanTask> {
    /** A cell planned because one of its chunks was just generated. */
    static final int CHUNK = 0;
    /** A cell planned ahead of the players. */
    static final int AHEAD = 1;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final int priority;
    private final long order = SEQUENCE.getAndIncrement();
    private final Runnable body;

    PlanTask(int priority, Runnable body) {
        this.priority = priority;
        this.body = body;
    }

    @Override
    public void run() {
        body.run();
    }

    @Override
    public int compareTo(PlanTask other) {
        int byPriority = Integer.compare(priority, other.priority);
        return byPriority != 0 ? byPriority : Long.compare(order, other.order);
    }
}
