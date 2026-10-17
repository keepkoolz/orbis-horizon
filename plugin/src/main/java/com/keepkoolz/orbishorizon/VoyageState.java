package com.keepkoolz.orbishorizon;

import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonString;

import java.util.UUID;

/** State of a server-driven airship voyage (the pilot NPC takes the captain to a destination fixed at departure). */
final class VoyageState {
    enum Phase { CLIMB, TURN, CRUISE, ARRIVE, RETRY, STOPPING }

    /** What the ship does above the destination: LAND starts the landing alignment, HOVER hands over to the recall (T143). */
    enum ArrivalMode { LAND, HOVER }

    final String destName;
    final VoyagePlanner.Kind destKind;
    final double destX;
    final double destZ;
    final UUID captain;
    /** The flight burns no fuel and never stops for lack of it (the recall, T143). */
    final boolean fuelFree;
    final ArrivalMode arrivalMode;
    /** Cruise height of the ship pivot; raised when an obstacle forces a climb and then kept. */
    volatile double cruiseY;
    volatile Phase phase = Phase.CLIMB;
    /** The ship reached the destination and began landing there (the arrival message is sent only then). */
    volatile boolean arrived;
    /** The voyage was stopped early (dry, blocked, captain gone): sticky, so the reason is announced once. */
    volatile boolean stopped;
    /** Landing attempts that were refused (collision, zone, height, blocked): after MAX_RETRIES the ship lands where it is. */
    int retries;
    /** The forced landing where the ship is was already tried once. */
    boolean forcedTried;
    /** RETRY: height of the pivot to reach before trying to land again. */
    double retryY;
    /** Progress watchdog: last pose that counted as progress and when. */
    double progressX;
    double progressY;
    double progressZ;
    double progressTheta;
    long progressMs;
    /** CRUISE look-ahead: next test time, and since when the sections ahead are not loaded (0 = loaded). */
    long aheadNextMs;
    long unloadedSinceMs;
    boolean aheadBlocked;
    /** Fuel window (same meaning as the player pilot's travel sample). */
    long windowMs;
    double windowX;
    double windowZ;
    double windowTheta;

    static final int MAX_RETRIES = 10;
    static final double RETRY_CLIMB = 3;

    VoyageState(String destName, VoyagePlanner.Kind destKind, double destX, double destZ, double cruiseY, UUID captain) {
        this(destName, destKind, destX, destZ, cruiseY, captain, false, ArrivalMode.LAND);
    }

    VoyageState(String destName, VoyagePlanner.Kind destKind, double destX, double destZ, double cruiseY, UUID captain,
                boolean fuelFree, ArrivalMode arrivalMode) {
        this.fuelFree = fuelFree;
        this.arrivalMode = arrivalMode;
        this.destName = destName;
        this.destKind = destKind;
        this.destX = destX;
        this.destZ = destZ;
        this.cruiseY = cruiseY;
        this.captain = captain;
    }

    /** Resume file form: name, kind, x, z, cruise height and captain. */
    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        d.put("name", new BsonString(destName));
        d.put("kind", new BsonString(destKind.name()));
        d.put("x", new BsonDouble(destX));
        d.put("z", new BsonDouble(destZ));
        d.put("cruiseY", new BsonDouble(cruiseY));
        if (captain != null) {
            d.put("captain", new BsonString(captain.toString()));
        }
        return d;
    }

    /** Reads a voyage back, null if it is malformed. */
    static VoyageState fromBson(BsonDocument d) {
        try {
            return new VoyageState(d.getString("name").getValue(), VoyagePlanner.Kind.valueOf(d.getString("kind").getValue()),
                    d.getNumber("x").doubleValue(), d.getNumber("z").doubleValue(), d.getNumber("cruiseY").doubleValue(),
                    d.containsKey("captain") ? UUID.fromString(d.getString("captain").getValue()) : null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
