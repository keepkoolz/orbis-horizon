package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.keepkoolz.orbishorizon.api.StationKinds;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The station structures for the public API, backed by Deployables. */
final class ApiStationKinds implements StationKinds {

    /** The structure of one of the four station kinds, else IllegalArgumentException. */
    private static Deployables.Kind kindOf(String kind) {
        if (AIR.equals(kind)) return Deployables.STATION;
        if (BOAT.equals(kind)) return Deployables.BOAT_STATION;
        if (SHUTTLE.equals(kind)) return Deployables.STEAMBOAT_PUBLIC;
        if (PUBLIC_BALLOON.equals(kind)) return Deployables.BALLOON_PUBLIC;
        throw new IllegalArgumentException("not a station kind: " + kind);
    }

    @Override
    public int[] placeMin(String kind) {
        return kindOf(kind).placeMin.clone();
    }

    @Override
    public int[] placeMax(String kind) {
        return kindOf(kind).placeMax.clone();
    }

    @Override
    public int[] boatBerthOrigin() {
        return Deployables.BOAT_STATION_BERTH_ORIGIN.clone();
    }

    @Override
    public int[] boatPierEnd() {
        return Deployables.BOAT_STATION_PIER_END.clone();
    }

    @Override
    public int[] boatMasterPos() {
        return Deployables.BOAT_STATION_MASTER_POS.clone();
    }

    @Override
    public int[] airBoardingPoint() {
        float[] p = Deployables.STATION_BOARDING_POINTS[0];
        return new int[] {Math.round(p[0]), Math.round(p[1]), Math.round(p[2])};
    }

    @Override
    public Vector3i dockOrigin(Vector3i origin, Rotation rotation) {
        return StationPlacement.dockOrigin(origin, rotation);
    }

    @Override
    public List<Vector3i> cells(String kind) throws IOException {
        return positions(kindOf(kind).shape());
    }

    /** The cell positions of a shape, in the order of its cells. */
    static List<Vector3i> positions(StructureShape shape) {
        List<Vector3i> out = new ArrayList<>(shape.cells().size());
        for (StructureShape.Cell c : shape.cells()) {
            out.add(new Vector3i(c.x(), c.y(), c.z()));
        }
        return out;
    }
}
