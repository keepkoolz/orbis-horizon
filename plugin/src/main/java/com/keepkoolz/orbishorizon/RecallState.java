package com.keepkoolz.orbishorizon;

import java.util.UUID;

/** State of an airship recall to a station (T143): where it appeared, where it hovers and which phase it is in. */
final class RecallState {
    /**
     * APPROACH: flown by the voyage autopilot. DESCEND: vertical descent to the hover pose. WAIT: hovering above the station, the
     * players on the platform board. CHOOSE: the captain picks a destination. LEAVE: flown away by the autopilot.
     */
    enum Phase { APPROACH, DESCEND, WAIT, CHOOSE, LEAVE }

    final TransitNetwork.Station station;
    final UUID owner;
    /** Where the ship appeared (the leave point is computed from it). */
    final double spawnX;
    final double spawnZ;
    /** Centre of the top of the pad. */
    final double padX;
    final double padZ;
    /** Hover pose of the pivot (height) and heading. */
    final double hoverY;
    final double hoverTheta;
    volatile Phase phase = Phase.APPROACH;
    /** DESCEND: since when the ship made no progress (0 = progressing). */
    long stuckSinceMs;
    boolean waitLogged;
    long waitStartMs;
    /** Last announced threshold of the wait (30, 20, 10). */
    int announced = Integer.MAX_VALUE;
    long chooseStartMs;
    /** The ship in blocks above the station (WAIT, CHOOSE): origin, rotation, ladder side and temporary rungs {x, y, z}. */
    volatile org.joml.Vector3i origin;
    volatile com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation;
    int sx = -1;
    final java.util.List<int[]> ladders = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Flight id that holds the quay of the station (kept across the take-off from blocks). */
    UUID quayId;
    /** A forced departure was refused: when to try again (0 = none). */
    volatile long leaveRetryMs;
    /** The minute is over (the rungs are down for good): a refused departure must not put them back. */
    volatile boolean minuteOver;
    /** Retries of the leave flight blocked inside the zone (after MAX_LEAVE_TRIES the ship is put down by the forced pose). */
    int leaveTries;
    static final int MAX_LEAVE_TRIES = 10;
    /** Refused departures from the waiting place (bounded by RecallPlanner.MAX_DEPART_TRIES). */
    int departTries;
    /** Rung cells {x, y, z} there were right before the take-off from blocks (kept in the resume file for a crash, M3). */
    volatile int[] departedRungs;
    /** The destination window opened for the captain (closed when the choice times out). */
    volatile AirshipVoyagePage page;

    RecallState(TransitNetwork.Station station, UUID owner, double spawnX, double spawnZ, double padX, double padZ, double hoverY,
                double hoverTheta) {
        this.station = station;
        this.owner = owner;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        this.padX = padX;
        this.padZ = padZ;
        this.hoverY = hoverY;
        this.hoverTheta = hoverTheta;
    }

    /**
     * What the resume file keeps of a recall: the station (by name, in the world of the file), the owner, where the ship appeared and
     * the leave point where a resumed or shut-down ship is put down (never above the station).
     */
    record Saved(String station, UUID owner, double spawnX, double spawnZ, double retreatX, double retreatZ) {
        org.bson.BsonDocument toBson() {
            org.bson.BsonDocument d = new org.bson.BsonDocument();
            d.put("station", new org.bson.BsonString(station));
            if (owner != null) {
                d.put("owner", new org.bson.BsonString(owner.toString()));
            }
            d.put("spawnX", new org.bson.BsonDouble(spawnX));
            d.put("spawnZ", new org.bson.BsonDouble(spawnZ));
            d.put("retreatX", new org.bson.BsonDouble(retreatX));
            d.put("retreatZ", new org.bson.BsonDouble(retreatZ));
            return d;
        }

        static Saved fromBson(org.bson.BsonDocument d) {
            return new Saved(d.getString("station").getValue(),
                    d.containsKey("owner") ? UUID.fromString(d.getString("owner").getValue()) : null,
                    d.getNumber("spawnX").doubleValue(), d.getNumber("spawnZ").doubleValue(), d.getNumber("retreatX").doubleValue(),
                    d.getNumber("retreatZ").doubleValue());
        }
    }
}
