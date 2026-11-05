package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Everything that differs between the kinds of station (balloon, boat): structure, master cell and look point in the prefab frame,
 * ground layer to refill on removal and map icon. Unknown kinds use the balloon spec.
 */
record StationSpec(String kind, Deployables.Kind structure, int[] masterPos, float[] masterLook,
                   Integer soilLayerY, String mapIcon) {
    static final StationSpec BALLOON = new StationSpec(Deployables.STATION_KIND, Deployables.STATION,
            Deployables.STATION_MASTER_POS, new float[]{5f, 0f, 0f},
            Deployables.STATION_GRASS_Y, "Balloon_Station.png");

    static final StationSpec BOAT = new StationSpec(Deployables.BOAT_STATION_KIND, Deployables.BOAT_STATION,
            Deployables.BOAT_STATION_MASTER_POS, Deployables.BOAT_STATION_MASTER_LOOK,
            null, "Boat_Station.png");

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

    /** Where an operator teleported to this station lands: its first drop point, near the master. */
    static org.joml.Vector3d teleportPoint(World world, TransitNetwork.Station s) {
        if (Deployables.BOAT_STATION_KIND.equals(s.kind)) {
            return BoatQuay.dropPoints(world, s).get(0);
        }
        return TransitAutopilot.dropPoints(world, s, 1).get(0);
    }

    static StationSpec of(String kind) {
        return Deployables.BOAT_STATION_KIND.equals(kind) ? BOAT : BALLOON;
    }
}
