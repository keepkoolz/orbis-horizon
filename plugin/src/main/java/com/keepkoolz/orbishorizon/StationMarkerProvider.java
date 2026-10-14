package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * World map markers of the transit stations, one per station of the world, shown to every player (the map's fog of
 * war already hides what the player has not explored). Registered on each world like the game's warps
 * (TeleportPlugin, AddWorldEvent then WorldMapManager.addMarkerProvider).
 */
final class StationMarkerProvider implements WorldMapManager.MarkerProvider {
    static final StationMarkerProvider INSTANCE = new StationMarkerProvider();
    /** Name of the provider in WorldMapManager. */
    static final String PROVIDER_ID = "orbis_horizon_stations";
    /** Icon under Common/UI/WorldMap/MapMarkers/ (drawn by scripts/make_crate_icons.py). */
    static final String ICON = "Balloon_Station.png";

    private StationMarkerProvider() {}

    static void register(World world) {
        world.getWorldMapManager().addMarkerProvider(PROVIDER_ID, INSTANCE);
    }

    @Override
    public void update(World world, Player player, MarkersCollector collector) {
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            Vector3d p = markerPoint(s.origin, s.rotation);
            String id = PROVIDER_ID + ":" + s.origin.x + "," + s.origin.y + "," + s.origin.z;
            collector.addIgnoreViewDistance(new MapMarkerBuilder(id, ICON, new Transform(p.x, p.y, p.z))
                    .withCustomName(s.name)
                    .build());
        }
    }

    /** Centre of the top of the landing pad, in the world (cell rotated with the station, then its centre). */
    static Vector3d markerPoint(Vector3i origin, Rotation rotation) {
        int[] min = Deployables.STATION_PAD_MIN;
        int[] max = Deployables.STATION_PAD_MAX;
        Vector3f cell = new Vector3f((min[0] + max[0]) / 2, max[1] + 1, (min[2] + max[2]) / 2);
        Vector3f r = rotation.rotateYaw(cell, new Vector3f());
        return new Vector3d(origin.x + r.x + 0.5, origin.y + r.y, origin.z + r.z + 0.5);
    }
}
