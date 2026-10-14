package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.ContextMenuItem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.logging.Level;

/**
 * World map markers of the balloons, airships and tents of their owner (and of every player when the owner shares
 * them). Stations and public balloons never get one. Registered on each world like StationMarkerProvider.
 */
final class OwnedMarkerProvider implements WorldMapManager.MarkerProvider {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final OwnedMarkerProvider INSTANCE = new OwnedMarkerProvider();
    /** Name of the provider in WorldMapManager. */
    static final String PROVIDER_ID = "orbis_horizon_owned";

    private static volatile boolean errorLogged;

    private OwnedMarkerProvider() {}

    static void register(World world) {
        world.getWorldMapManager().addMarkerProvider(PROVIDER_ID, INSTANCE);
    }

    /** True if the viewer may see the marker: owner, or anyone when shared. A structure without owner has none. */
    static boolean visibleTo(Ownership own, UUID viewer) {
        return own.owner() != null && (own.shared() || own.owner().equals(viewer));
    }

    /** Map icon file of a structure type, null when it gets no marker (stations, public balloons). */
    static String icon(String kindId) {
        if (kindId == null) {
            return null;
        }
        return switch (kindId) {
            case Deployables.BALLOON_KIND -> "Owned_Balloon.png";
            case Deployables.BASIC_KIND -> "Owned_Balloon_Basic.png";
            case Deployables.TRANSPORT_KIND -> "Owned_Balloon_Transport.png";
            case Deployables.AIRSHIP_KIND -> "Owned_Airship.png";
            case Deployables.TENT_KIND -> "Owned_Tent.png";
            case Deployables.TENT_BIG_KIND -> "Owned_Tent_Big.png";
            default -> null;
        };
    }

    /** Full translation key of the marker name of a type, the shared variant when seen by someone else. */
    static String nameKey(String kindId, boolean other) {
        return "server.orbis_horizon.map.kind." + kindId + (other ? ".of" : "");
    }

    static String markerId(UUID id) {
        return PROVIDER_ID + ":" + id;
    }

    @Override
    public void update(World world, Player player, MarkersCollector collector) {
        try {
            UUID viewer = player.getUuid();
            for (BalloonRegistry.Entry e : BalloonRegistry.allIn(world)) {
                try {
                    String icon = icon(e.kind());
                    Deployables.Kind kind = Deployables.get(e.kind());
                    Ownership own = e.ownership();
                    if (icon == null || kind == null || own.id() == null || !visibleTo(own, viewer)) {
                        continue;
                    }
                    Vector3d p = entryPoint(kind, e.x(), e.y(), e.z(), e.rotation());
                    if (p != null) {
                        add(collector, e.kind(), own, viewer, p);
                    }
                } catch (RuntimeException ex) {
                    logOnce(ex);
                }
            }
            for (BalloonFlight f : BalloonManager.get().flights()) {
                try {
                    Ownership own = f.ownership;
                    if (f.world != world || own.id() == null || icon(f.kind.id) == null || !visibleTo(own, viewer)) {
                        continue;
                    }
                    Vector3d p = BalloonManager.prefabPoint(f.entityPos, f.rotation, BalloonManager.pivot(f.kind));
                    add(collector, f.kind.id, own, viewer, p);
                } catch (RuntimeException ex) {
                    logOnce(ex);
                }
            }
            for (AirshipFlight f : AirshipManager.get().flights()) {
                try {
                    Ownership own = f.ownership;
                    if (f.world != world || own.id() == null || !visibleTo(own, viewer)) {
                        continue;
                    }
                    add(collector, Deployables.AIRSHIP_KIND, own, viewer, new Vector3d(f.pos));
                } catch (RuntimeException ex) {
                    logOnce(ex);
                }
            }
        } catch (RuntimeException ex) {
            logOnce(ex);
        }
    }

    private static void add(MarkersCollector collector, String kindId, Ownership own, UUID viewer, Vector3d p) {
        String icon = icon(kindId);
        Message name;
        if (own.owner().equals(viewer) || own.ownerName() == null) {
            name = Message.translation(nameKey(kindId, false));
        } else {
            name = Message.translation(nameKey(kindId, true)).param("owner", own.ownerName());
        }
        MapMarkerBuilder marker = new MapMarkerBuilder(markerId(own.id()), icon, new Transform(p.x, p.y, p.z))
                .withName(name);
        if (own.owner().equals(viewer)) {
            // Menu entry: translation key as name, command without the leading slash (the client adds its own).
            String action = own.shared() ? "unshare" : "share";
            marker.withContextMenuItem(new ContextMenuItem("server.orbis_horizon.map.menu." + action,
                    "orbishorizon map " + action + " " + own.id()));
        }
        collector.addIgnoreViewDistance(marker.build());
    }

    /** World point of a deployed structure: type pivot (vehicles) or anchor cell (tents), rotated, cell centre. */
    static Vector3d entryPoint(Deployables.Kind kind, int x, int y, int z, Rotation rotation) {
        Vector3f local;
        if (kind.balloon != null) {
            local = new Vector3f(kind.balloon.pivot);
        } else if (kind.airship != null) {
            local = new Vector3f(kind.airship.pivot);
        } else {
            StructureShape shape = kind.shapeOrNull();
            if (shape == null || shape.anchor() == null) {
                return null;
            }
            local = new Vector3f(shape.anchor().x(), shape.anchor().y(), shape.anchor().z());
        }
        Vector3f r = rotation.rotateYaw(local, new Vector3f());
        return new Vector3d(x + r.x + 0.5, y + r.y, z + r.z + 0.5);
    }

    private static void logOnce(RuntimeException ex) {
        if (!errorLogged) {
            errorLogged = true;
            LOGGER.at(Level.WARNING).withCause(ex).log("Marqueur de carte d'un véhicule ou d'une tente ignoré");
        }
    }
}
