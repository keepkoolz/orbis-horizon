package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.ContextMenuItem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import org.joml.Vector3i;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Replaces the game's "respawn" marker provider (RespawnMarkerProvider): same markers (id name + index, Home.png, custom name,
 * ignores the view distance), plus a context menu entry to forget the point when it is a camp point of the viewer.
 * The game registers its own provider in the WorldMapManager constructor, ours is put under the same name afterwards.
 */
final class RespawnPointMarkerProvider implements WorldMapManager.MarkerProvider {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final RespawnPointMarkerProvider INSTANCE = new RespawnPointMarkerProvider();
    /** Same name as the game's provider, so that ours replaces it. */
    static final String PROVIDER_ID = "respawn";

    private static volatile boolean errorLogged;

    private RespawnPointMarkerProvider() {}

    static void register(World world) {
        world.getWorldMapManager().addMarkerProvider(PROVIDER_ID, INSTANCE);
    }

    /** Marker id of the game: point name followed by its index, no separator. */
    static String markerId(String name, int index) {
        return name + index;
    }

    /** Whether a point gets the "forget" menu entry: it is the camp point of the viewer in this world. */
    static boolean forgettable(UUID viewer, String world, Vector3i block) {
        return viewer != null && CampRespawn.isCampBlock(viewer, world, block);
    }

    static String forgetCommand(Vector3i b) {
        return "orbishorizon camping forget " + b.x + " " + b.y + " " + b.z;
    }

    @Override
    public void update(World world, Player player, MarkersCollector collector) {
        try {
            // Runs on the WorldMap thread: only what the game's own provider reads (no Store access).
            if (!world.getGameplayConfig().getWorldMapConfig().isDisplayHome()) {
                return;
            }
            PlayerWorldData data = player.getPlayerConfigData().getPerWorldData(world.getName());
            PlayerRespawnPointData[] points = data == null ? null : data.getRespawnPoints();
            if (points == null) {
                return;
            }
            UUID viewer = null;
            try {
                viewer = player.getUuid();
            } catch (RuntimeException e) {
                logOnce(e);
            }
            for (int i = 0; i < points.length; i++) {
                try {
                    PlayerRespawnPointData p = points[i];
                    Vector3i block = p.getBlockPosition();
                    MapMarkerBuilder marker = new MapMarkerBuilder(markerId(p.getName(), i), "Home.png", new Transform(block))
                            .withCustomName(p.getName());
                    if (forgettable(viewer, world.getName(), block)) {
                        marker.withContextMenuItem(new ContextMenuItem("server.orbis_horizon.map.menu.forgetRespawn", forgetCommand(block)));
                    }
                    collector.addIgnoreViewDistance(marker.build());
                } catch (RuntimeException e) {
                    logOnce(e);
                }
            }
        } catch (RuntimeException e) {
            logOnce(e);
        }
    }

    private static void logOnce(RuntimeException e) {
        if (!errorLogged) {
            errorLogged = true;
            LOGGER.at(Level.WARNING).withCause(e).log("Respawn marker ignored");
        }
    }
}
