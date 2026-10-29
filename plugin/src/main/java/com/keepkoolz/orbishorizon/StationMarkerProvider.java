package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.ContextMenuItem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.logging.Level;

/**
 * World map markers of the transit stations. A station the player knows (discovered by him, or shared with every player of
 * the world) is shown at any distance, a not yet shared one with a menu entry to share it. A station he does not know is shown
 * with its name and no menu only while it is within the map view distance around him (the game filters it, like the block
 * markers of the temple portals). Registered on each world like the game's warps
 * (TeleportPlugin, AddWorldEvent then WorldMapManager.addMarkerProvider).
 */
final class StationMarkerProvider implements WorldMapManager.MarkerProvider {
    static final StationMarkerProvider INSTANCE = new StationMarkerProvider();
    /** Name of the provider in WorldMapManager. */
    static final String PROVIDER_ID = "orbis_horizon_stations";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile boolean errorLogged;

    /** How a station is shown to a player. */
    enum Display {
        /** Known, visible at any distance, with the share menu entry. */
        KNOWN_SHAREABLE,
        /** Known, visible at any distance, no menu. */
        KNOWN,
        /** Not known, visible only within the map view distance, no menu. */
        NEARBY
    }

    private StationMarkerProvider() {}

    /** Display mode of a station from what the player knows and whether sharing is on. */
    static Display display(boolean known, boolean shared, boolean sharingOn) {
        if (!known) {
            return Display.NEARBY;
        }
        return sharingOn && !shared ? Display.KNOWN_SHAREABLE : Display.KNOWN;
    }

    static void register(World world) {
        world.getWorldMapManager().addMarkerProvider(PROVIDER_ID, INSTANCE);
    }

    @Override
    public void update(World world, Player player, MarkersCollector collector) {
        try {
            fill(world, player, collector);
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.at(Level.WARNING).withCause(e).log("Marqueurs de stations ignorés");
            }
            Telemetry.error("map.stations", e, null, null);
        }
    }

    private static void fill(World world, Player player, MarkersCollector collector) {
        KnownStations known = KnownStations.get();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            SkyLog.Reached key = new SkyLog.Reached(s.origin.x, s.origin.y, s.origin.z);
            boolean isKnown = known.known(world.getName(), player.getUuid(), key);
            Display mode = display(isKnown, isKnown && known.isShared(world.getName(), key), TransitSettings.markerSharing());
            Vector3d p = markerPoint(s);
            String id = PROVIDER_ID + ":" + s.origin.x + "," + s.origin.y + "," + s.origin.z;
            MapMarkerBuilder marker = new MapMarkerBuilder(id, StationSpec.of(s.kind).mapIcon(), new Transform(p.x, p.y, p.z))
                    .withCustomName(s.name);
            if (mode == Display.KNOWN_SHAREABLE) {
                // Translation key as name, command without the leading slash (the client adds its own).
                marker.withContextMenuItem(new ContextMenuItem("server.orbis_horizon.map.menu.shareStation",
                        shareCommand(s.origin.x, s.origin.y, s.origin.z)));
            }
            if (mode == Display.NEARBY) {
                collector.add(marker.build());
            } else {
                collector.addIgnoreViewDistance(marker.build());
            }
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
