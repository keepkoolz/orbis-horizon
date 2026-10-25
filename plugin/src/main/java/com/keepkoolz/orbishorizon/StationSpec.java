package com.keepkoolz.orbishorizon;

/**
 * Everything that differs between the kinds of station (balloon, boat): structure, master and marker cells in the prefab frame,
 * bottom of the protected zone, ground layer to refill on removal, crate and map icon. Unknown kinds use the balloon spec.
 */
record StationSpec(String kind, Deployables.Kind structure, int[] masterPos, float[] masterLook, int[] markerCell,
                   int zoneBottomY, Integer soilLayerY, String crateId, String mapIcon) {
    /** Bottom of the posts of the boat station prefab (prefab y). */
    static final int BOAT_POSTS_BOTTOM_Y = -10;

    static final StationSpec BALLOON = new StationSpec(Deployables.STATION_KIND, Deployables.STATION,
            Deployables.STATION_MASTER_POS, new float[]{5f, 0f, 0f},
            new int[]{(Deployables.STATION_PAD_MIN[0] + Deployables.STATION_PAD_MAX[0]) / 2,
                    Deployables.STATION_PAD_MAX[1] + 1,
                    (Deployables.STATION_PAD_MIN[2] + Deployables.STATION_PAD_MAX[2]) / 2},
            Deployables.STATION_GRASS_Y, Deployables.STATION_GRASS_Y, "Balloon_Station_Crate", "Balloon_Station.png");

    static final StationSpec BOAT = new StationSpec(Deployables.BOAT_STATION_KIND, Deployables.BOAT_STATION,
            Deployables.BOAT_STATION_MASTER_POS, Deployables.BOAT_STATION_MASTER_LOOK, Deployables.BOAT_STATION_MARKER_CELL,
            BOAT_POSTS_BOTTOM_Y, null, "Boat_Station_Crate", "Boat_Station.png");

    static final java.util.List<String> CRATE_IDS = java.util.List.of(BALLOON.crateId, BOAT.crateId);

    private boolean isBoat() {
        return Deployables.BOAT_STATION_KIND.equals(kind);
    }

    /** Key (after "ui.") of the title of the master window. */
    String titleKey() {
        return isBoat() ? "dest.titleBoat" : "dest.title";
    }

    /** Key (after "chat.") of the display name of the master. */
    String masterNameKey() {
        return isBoat() ? "transit.master.nameBoat" : "transit.master.name";
    }

    /**
     * Where an operator teleported to this station lands. Balloon: the first boarding point of the platform. Boat: the pier marker
     * cell, one block up (feet at y + 1.1), cell centre.
     */
    static org.joml.Vector3d teleportPoint(TransitNetwork.Station s) {
        StationSpec spec = of(s.kind);
        if (!spec.isBoat()) {
            return TransitAutopilot.dropPoints(s.origin, s.rotation, 1).get(0);
        }
        int[] c = spec.markerCell();
        org.joml.Vector3f r = s.rotation.rotateYaw(new org.joml.Vector3f(c[0], c[1], c[2]), new org.joml.Vector3f());
        return new org.joml.Vector3d(s.origin.x + r.x + 0.5, s.origin.y + r.y + 1.1, s.origin.z + r.z + 0.5);
    }

    static StationSpec of(String kind) {
        return Deployables.BOAT_STATION_KIND.equals(kind) ? BOAT : BALLOON;
    }
}
