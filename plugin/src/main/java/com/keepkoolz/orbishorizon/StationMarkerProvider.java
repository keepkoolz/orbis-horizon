package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.ContextMenuItem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * World map markers of the transit stations. A player sees the stations he knows (discovered by him, or shared with every
 * player of the world), each not yet shared one with a menu entry to share it. Registered on each world like the game's warps
 * (TeleportPlugin, AddWorldEvent then WorldMapManager.addMarkerProvider).
 */
final class StationMarkerProvider implements WorldMapManager.MarkerProvider {
    static final StationMarkerProvider INSTANCE = new StationMarkerProvider();
    /** Name of the provider in WorldMapManager. */
    static final String PROVIDER_ID = "orbis_horizon_stations";
    private StationMarkerProvider() {}

    static void register(World world) {
        world.getWorldMapManager().addMarkerProvider(PROVIDER_ID, INSTANCE);
    }

    @Override
    public void update(World world, Player player, MarkersCollector collector) {
        KnownStations known = KnownStations.get();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            SkyLog.Reached key = new SkyLog.Reached(s.origin.x, s.origin.y, s.origin.z);
            if (!known.known(world.getName(), player.getUuid(), key)) {
                continue;
            }
            Vector3d p = markerPoint(s);
            String id = PROVIDER_ID + ":" + s.origin.x + "," + s.origin.y + "," + s.origin.z;
            MapMarkerBuilder marker = new MapMarkerBuilder(id, StationSpec.of(s.kind).mapIcon(), new Transform(p.x, p.y, p.z))
                    .withCustomName(s.name);
            if (!known.isShared(world.getName(), key)) {
                // Translation key as name, command without the leading slash (the client adds its own).
                marker.withContextMenuItem(new ContextMenuItem("server.orbis_horizon.map.menu.shareStation",
                        shareCommand(s.origin.x, s.origin.y, s.origin.z)));
            }
            collector.addIgnoreViewDistance(marker.build());
        }
    }

    /** Command run by the menu entry that shares the station at this origin (the game registers command names in lower case). */
    static String shareCommand(int x, int y, int z) {
        return "orbishorizon map sharestation " + x + " " + y + " " + z;
    }

    /** World point of the map marker of a station: its marker cell rotated with the station, then the cell centre. */
    static Vector3d markerPoint(TransitNetwork.Station s) {
        int[] c = StationSpec.of(s.kind).markerCell();
        Vector3f r = s.rotation.rotateYaw(new Vector3f(c[0], c[1], c[2]), new Vector3f());
        return new Vector3d(s.origin.x + r.x + 0.5, s.origin.y + r.y, s.origin.z + r.z + 0.5);
    }
}
