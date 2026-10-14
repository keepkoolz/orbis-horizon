package com.keepkoolz.orbishorizon;

/** Tunable values of the public transit system (stations, public balloons). Distances in blocks, times in seconds. */
final class TransitConstants {
    private TransitConstants() {}

    /** Radius around a station in which departure messages are sent. */
    static final double MESSAGE_RADIUS = 20.0;
    /** Countdown before departure. */
    static final int COUNTDOWN_SECONDS = 30;
    /** Interval between countdown messages. */
    static final int MESSAGE_INTERVAL_SECONDS = 10;
    /** T124 external view of balloon passengers: horizontal move (blocks) after which the travel direction is sampled again. */
    static final double VIEW_DIRECTION_MIN_MOVE = 2.0;
    /** T124 external view: change of travel direction (degrees) that re-sends the camera. */
    static final double VIEW_DIRECTION_CHANGE_DEGREES = 10.0;
    /** T124 external view: default camera distance, in times the size of the balloon. */
    static final double VIEW_DISTANCE_FACTOR = 1.2;
    /** T124 external view: default camera height above the target, in times the camera distance. */
    static final double VIEW_HEIGHT_FACTOR = 0.25;
    /** View of the passengers: eye height above the seat point (prefab seat points are about 0.5 above the cell floor), in blocks. */
    static final float VIEW_EYE_HEIGHT_ABOVE_SEAT = 1.2f;
    /** Passenger body parked in forced flight: beyond this distance (blocks) from the seat it is teleported back, so its chunks stay loaded. */
    static final double BODY_SAFETY_DISTANCE = 16.0;
    /** Maximum number of queued destinations per station. */
    static final int MAX_QUEUE = 5;
    /** Spawn height above the platform. */
    static final int SPAWN_HEIGHT_ABOVE_PLATFORM = 15;
    /** Height above the platform at which the dock is freed for the next balloon. */
    static final int DOCK_FREE_HEIGHT = 20;
    /** Despawn height above the drop-off point. */
    static final int DESPAWN_HEIGHT_ABOVE_DROP = 20;
    /** Cruise height above the higher of the two platforms. */
    static final int CRUISE_HEIGHT_ABOVE_PLATFORMS = 30;
    /** Maximum wait for chunks to load. */
    static final int CHUNK_WAIT_SECONDS = 10;
    /** Seconds without progress after which the rescue procedure starts. */
    static final int RESCUE_NO_PROGRESS_SECONDS = 30;
    /** Distance of the holding point from the platform. */
    static final int HOLDING_DISTANCE = 30;
    /** Spacing between holding points of several waiting arrivals. */
    static final int HOLDING_SPACING = 6;
    /** Distance at which the station master reservation is released. */
    static final double MASTER_RELEASE_DISTANCE = 8.0;
    /** Wait on the arrival pad after the drop before the balloon climbs away and vanishes (it left at once, 11 October 2026). */
    static final int DROP_WAIT_SECONDS = 5;
    /** Gap after a departing balloon frees the quay before the next balloon may come down on it (it went through the next one, 11 October 2026). */
    static final int QUAY_GAP_SECONDS = 10;
    /** Time after which the station master reservation is released. */
    static final int MASTER_RELEASE_SECONDS = 60;
    /** Station name length bounds. */
    /** A name page left unanswered this long registers its station with the automatic name. */
    static final int NAME_PAGE_TIMEOUT_SECONDS = 120;
    static final int NAME_MIN = 3;
    static final int NAME_MAX = 24;
    /** Minimum distance between two stations. */
    static final int MIN_STATION_DISTANCE = 64;
    /** Radius in which an admin removal looks for a station. */
    static final int REMOVE_RADIUS = 48;
    /** Passenger capacity of a public balloon. */
    static final int PASSENGERS = 12;
}
