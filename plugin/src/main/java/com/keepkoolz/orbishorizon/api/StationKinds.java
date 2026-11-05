package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;

/** Ids and geometry of the station structures. Positions are in the prefab frame unless stated. */
public interface StationKinds {
    /** Balloon station. */
    String AIR = "station";
    /** Sea station (kiosk and pier). */
    String BOAT = "boat_station";
    /** Steamboat that serves the sea stations. */
    String SHUTTLE = "steamboat_public";
    /** Balloon docked at a balloon station. */
    String PUBLIC_BALLOON = "balloon_public";
    /** Prefab y of the grass layer of the balloon station. */
    int GRASS_Y = -1;
    /** Rotation of the shuttle in its berth, added to the station rotation. */
    Rotation BOAT_BERTH_ROTATION = Rotation.Ninety;

    /** Minimum corner of the volume where nothing can be placed, for AIR, BOAT, SHUTTLE and PUBLIC_BALLOON (else IllegalArgumentException). A copy. */
    int[] placeMin(String kind);

    /** Maximum corner of the same volume. A copy. */
    int[] placeMax(String kind);

    /** Origin of the shuttle in its berth, station frame. */
    int[] boatBerthOrigin();

    /** Last deck cell of the pier on the water side, station frame. */
    int[] boatPierEnd();

    /** Cell of the station master of a sea station. */
    int[] boatMasterPos();

    /** First boarding point of the balloon station, rounded to {x, y, z}. */
    int[] airBoardingPoint();

    /** Origin of the balloon docked at a balloon station placed at origin with this rotation. */
    Vector3i dockOrigin(Vector3i origin, Rotation rotation);

    /** The cells of the prefab of this kind, prefab frame, no rotation. */
    List<Vector3i> cells(String kind) throws IOException;
}
