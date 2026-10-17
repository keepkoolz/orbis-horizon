package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.List;

/** Pure calculations of the airship recall to a station (no world access). */
final class RecallPlanner {
    /** Distance from the pad centre at which the recalled airship appears. */
    static final double SPAWN_DISTANCE = 120.0;
    /** Distance from the pivot to the airship centre, along the bow direction. */
    static final double PIVOT_TO_CENTRE = 10.0;
    /** Air gap between the highest station block and the lowest airship block. */
    static final int AIR_GAP = 3;
    /** Blocks from the lowest airship block up to the pivot. */
    static final int PIVOT_ABOVE_LOWEST = 6;

    private RecallPlanner() {
    }

    /** Point 120 blocks from the pad centre towards (fromX, fromZ); from the centre itself the direction is +x. */
    static double[] spawnPoint(double padX, double padZ, double fromX, double fromZ) {
        double dx = fromX - padX;
        double dz = fromZ - padZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-6) {
            return new double[] {padX + SPAWN_DISTANCE, padZ};
        }
        return new double[] {padX + dx / len * SPAWN_DISTANCE, padZ + dz / len * SPAWN_DISTANCE};
    }

    /**
     * Hover pose {x, y, z, theta}: heading = station yaw (bow = (-sin t, -cos t)), airship centre above the pad centre. The pivot
     * is 10 blocks from the centre on the bow side, so pivot = centre + 10 * bow.
     */
    static double[] hoverPivot(double padX, double padZ, int stationTopY, double stationYawRad) {
        double bowX = -Math.sin(stationYawRad);
        double bowZ = -Math.cos(stationYawRad);
        double y = stationTopY + 1 + AIR_GAP + PIVOT_ABOVE_LOWEST;
        return new double[] {padX + PIVOT_TO_CENTRE * bowX, y, padZ + PIVOT_TO_CENTRE * bowZ, stationYawRad};
    }

    /** Length of the wait above the station, in milliseconds. */
    static final long WAIT_MS = 60_000L;
    /** Seconds the captain has to choose a destination. */
    static final long CHOOSE_MS = 30_000L;

    /**
     * Remaining seconds to announce (30, 20 or 10) while the elapsed wait is in the first second of that threshold, else -1. The
     * caller keeps the last announced threshold, so each one is said once.
     */
    static int waitMessageAt(long elapsedMs) {
        if (elapsedMs < 0) {
            return -1;
        }
        long sec = elapsedMs / 1000L;
        if (sec == 30) {
            return 30;
        }
        if (sec == 50) {
            return 10;
        }
        return -1;
    }

    /** Lowest threshold (30, 20 or 10) the elapsed wait has reached and that is below {@code lastAnnounced}, else -1. */
    static int waitThresholdReached(long elapsedMs, int lastAnnounced) {
        int out = -1;
        if (elapsedMs >= 30_000L && lastAnnounced > 30) {
            out = 30;
        }
        if (elapsedMs >= 50_000L && lastAnnounced > 10) {
            out = 10;
        }
        return out;
    }

    /** Horizontal distance from the ship centre to the ladder at x = sx * 5 (prefab frame) when the ship has quarter turn k. */
    private static double ladderDistance(double padX, double padZ, List<double[]> points, int k, int sx) {
        double th = k * Math.PI / 2;
        double lx = padX + 5.0 * sx * Math.cos(th);
        double lz = padZ - 5.0 * sx * Math.sin(th);
        double cx = 0;
        double cz = 0;
        for (double[] p : points) {
            cx += p[0];
            cz += p[2];
        }
        cx /= Math.max(1, points.size());
        cz /= Math.max(1, points.size());
        return Math.hypot(lx - cx, lz - cz);
    }

    /** Side (-1 or +1) of the ladder nearest to the boarding points for a ship with quarter turn k (ties: -1). */
    static int ladderSx(double padX, double padZ, List<double[]> points, int k) {
        return ladderDistance(padX, padZ, points, k, -1) <= ladderDistance(padX, padZ, points, k, 1) ? -1 : 1;
    }

    /**
     * Quarter turn k (0 to 3, the ship heading is k * 90 degrees) and ladder side sx (-1 or +1, prefab x = sx * 5) such that the ladder
     * is the nearest to the boarding points of the station (world points {x, y, z}), the ship centre being above the pad centre.
     * Ties keep the lowest k, then sx = -1.
     */
    static int[] ladderSide(double padX, double padZ, List<double[]> points) {
        int bestK = 0;
        int bestSx = -1;
        double best = Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            for (int sx = -1; sx <= 1; sx += 2) {
                double d = ladderDistance(padX, padZ, points, k, sx);
                if (d < best - 1e-9) {
                    best = d;
                    bestK = k;
                    bestSx = sx;
                }
            }
        }
        return new int[] {bestK, bestSx};
    }

    /**
     * Heights of the temporary rungs under the lowest ladder cell: from bottomY - 1 downwards while the cell is free, up to maxLen,
     * stopping before the first cell for which stopAt is true (a block, or a section that is not loaded). Empty if the cell right
     * under the ladder is already taken.
     */
    static List<Integer> ladderCells(int bottomY, java.util.function.IntPredicate stopAt, int maxLen) {
        List<Integer> out = new ArrayList<>();
        for (int y = bottomY - 1; y >= 0 && out.size() < maxLen; y--) {
            if (stopAt.test(y)) {
                break;
            }
            out.add(y);
        }
        return out;
    }

    /**
     * True if a player at (px, py, pz) is climbing the temporary rungs: within a block of the rung column horizontally, with the feet
     * more than half a block above the top of the station (a player on the boarding step or the station is not climbing) and at most
     * one and a half blocks above the highest rung. Rungs are cells {x, y, z}.
     */
    static boolean isClimbing(double px, double py, double pz, List<int[]> rungs, double stationTopY) {
        if (rungs.isEmpty()) {
            return false;
        }
        int maxY = Integer.MIN_VALUE;
        for (int[] c : rungs) {
            maxY = Math.max(maxY, c[1]);
        }
        int[] first = rungs.get(0);
        return Math.abs(px - (first[0] + 0.5)) <= 1.0 && Math.abs(pz - (first[2] + 0.5)) <= 1.0
                && py > stationTopY + 0.5 && py <= maxY + 1.5;
    }

    /** Chat key of the refusal, in the order of the decisions, null when the recall is allowed (nobody on the parked ship). */
    static String recallRefusalKey(boolean hasCrewAirship, boolean inFlight, boolean alreadyRecalling, boolean quayFree) {
        return recallRefusalKey(hasCrewAirship, inFlight, alreadyRecalling, false, quayFree);
    }

    /**
     * Chat key of the refusal, in the order of the decisions, null when the recall is allowed. {@code occupied}: a player stands in
     * the box of the parked ship (the recall would take the floor from under his feet).
     */
    static String recallRefusalKey(boolean hasCrewAirship, boolean inFlight, boolean alreadyRecalling, boolean occupied,
                                   boolean quayFree) {
        if (!hasCrewAirship) {
            return "recall.noAirship";
        }
        if (inFlight) {
            return "recall.inFlight";
        }
        if (alreadyRecalling) {
            return "recall.already";
        }
        if (occupied) {
            return "recall.occupied";
        }
        if (!quayFree) {
            return "recall.quayBusy";
        }
        return null;
    }

    /**
     * True if an offline player whose saved data says he was in world {@code savedWorld} gets a stranded entry for {@code world}: he
     * is offline, his save belongs to this world and his saved position is inside the parked ship.
     */
    static boolean strandOffline(boolean online, String savedWorld, String world, boolean insideShip) {
        return !online && savedWorld != null && savedWorld.equals(world) && insideShip;
    }

    /** Refused departures from the waiting place (5 s apart) before the ship is left in blocks and the quay freed. */
    static final int MAX_DEPART_TRIES = 10;

    /** True while a refused departure from the waiting place may still be retried (tries already made, 1 for the first refusal). */
    static boolean departRetryAllowed(int tries) {
        return tries < MAX_DEPART_TRIES;
    }

    /** A recall approach stopped by the watchdog this close (pivot to approach point, horizontally) goes on with the descent. */
    static final double APPROACH_NEAR = 16.0;

    /** True if the stopped approach is near enough to its destination to descend and hover instead of flying away. */
    static boolean approachNear(double px, double pz, double destX, double destZ) {
        return Math.hypot(px - destX, pz - destZ) <= APPROACH_NEAR;
    }
}
