package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Destinations of an NPC-piloted airship voyage and the pure calculations around them. */
final class VoyagePlanner {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Destinations closer than this (horizontal) are not offered. */
    static final double MIN_DISTANCE = 32;
    /** Distance of the arrival point from a station pad centre. */
    static final double ARRIVAL_DISTANCE = 70;
    static final int CRUISE_MARGIN = 40;
    static final int CEILING_MARGIN = 10;
    static final double ALIGN_SECONDS = 5;

    enum Kind { MARKER, STOP, STATION }

    /** groundY is Integer.MIN_VALUE when unknown. station identifies a balloon or boat station (null for anything else). */
    record Destination(String name, Kind kind, double x, double z, int groundY, SkyLog.Reached station) {
        Destination(String name, Kind kind, double x, double z, int groundY) {
            this(name, kind, x, z, groundY, null);
        }
    }

    private VoyagePlanner() {}

    static List<Destination> list(List<Destination> all, double fromX, double fromZ) {
        List<Destination> out = new ArrayList<>();
        for (Destination d : all) {
            if (Math.hypot(d.x() - fromX, d.z() - fromZ) >= MIN_DISTANCE) {
                out.add(d);
            }
        }
        out.sort(Comparator.comparingInt((Destination d) -> d.kind().ordinal())
                .thenComparingDouble(d -> Math.hypot(d.x() - fromX, d.z() - fromZ)));
        return out;
    }

    /**
     * Destinations a vehicle has already seen: a station whose id is in the log, a marker with a visited cell near it. A null or empty
     * log keeps nothing.
     */
    static List<Destination> known(List<Destination> all, SkyLog sky) {
        List<Destination> out = new ArrayList<>();
        if (sky == null || sky.isEmpty()) {
            return out;
        }
        for (Destination d : all) {
            boolean keep = switch (d.kind()) {
                case STATION -> d.station() != null && sky.hasReached(d.station());
                case MARKER -> sky.markerVisited(d.x(), d.z());
                default -> false;
            };
            if (keep) {
                out.add(d);
            }
        }
        return out;
    }

    static double[] stationArrival(double padX, double padZ, double fromX, double fromZ, List<double[]> otherPads) {
        double base = Math.atan2(fromZ - padZ, fromX - padX);
        double[] first = null;
        for (int i = 0; i < 24; i++) {
            int steps = i == 0 ? 0 : (i % 2 == 1 ? (i + 1) / 2 : -(i / 2));
            double a = base + Math.toRadians(15.0 * steps);
            double[] p = {padX + ARRIVAL_DISTANCE * Math.cos(a), padZ + ARRIVAL_DISTANCE * Math.sin(a)};
            if (first == null) {
                first = p;
            }
            boolean free = true;
            for (double[] o : otherPads) {
                if (Math.hypot(p[0] - o[0], p[1] - o[1]) < ARRIVAL_DISTANCE) {
                    free = false;
                    break;
                }
            }
            if (free) {
                return p;
            }
        }
        return first;
    }

    static int cruiseY(int departureY, int arrivalGroundY, int worldMaxY) {
        int high = arrivalGroundY == Integer.MIN_VALUE ? departureY : Math.max(departureY, arrivalGroundY);
        int y = Math.min(high + CRUISE_MARGIN, worldMaxY - CEILING_MARGIN);
        return Math.max(y, departureY);
    }

    static double tripSeconds(double horizontal, int departureY, int cruiseY, double hSpeed, double vSpeed) {
        return (cruiseY - departureY) / vSpeed + horizontal / hSpeed + ALIGN_SECONDS;
    }

    /** Personal and world-shared map markers and stations of the world, filtered and sorted by {@link #list}. Never throws. */
    static List<Destination> collect(Player player, World world, double fromX, double fromZ) {
        return list(collectAll(player, world, fromX, fromZ), fromX, fromZ);
    }

    /** Every destination of the world, unfiltered and unsorted (the caller can tell "none exist" from "all too close"). Never throws. */
    static List<Destination> collectAll(Player player, World world, double fromX, double fromZ) {
        List<Destination> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try { // personal markers of the player
            var data = player.getPlayerConfigData().getPerWorldData(world.getName());
            var markers = data != null ? data.getUserMapMarkers() : null;
            if (markers != null) {
                for (var m : markers) {
                    if (m != null && m.getName() != null) {
                        if (m.getId() != null) {
                            seen.add(m.getId());
                        }
                        all.add(new Destination(m.getName(), Kind.MARKER, m.getX(), m.getZ(), Integer.MIN_VALUE));
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().log("Voyage : lecture des marqueurs impossible : " + t);
        }
        try { // markers shared with the whole world (0.7: world resource SharedUserMapMarkers, like the game's SharedMarkersProvider; world thread)
            var shared = world.getChunkStore().getStore().getResource(
                    com.hypixel.hytale.server.core.universe.world.worldmap.markers.worldstore.WorldMarkersResource.getResourceType());
            var markers = shared != null ? shared.getUserMapMarkers() : null;
            if (markers != null) {
                for (var m : markers) {
                    if (m != null && m.getName() != null && (m.getId() == null || seen.add(m.getId()))) {
                        all.add(new Destination(m.getName(), Kind.MARKER, m.getX(), m.getZ(), Integer.MIN_VALUE));
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().log("Voyage : lecture des marqueurs partagés impossible : " + t);
        }
        try {
            // Destinations: every station, balloon and boat. Zones to avoid: every station too.
            all.addAll(stationDestinations(TransitNetwork.get().stations(world.getName()), fromX, fromZ));
        } catch (Throwable t) {
            LOGGER.atWarning().log("Voyage : lecture des stations impossible : " + t);
        }
        return all;
    }

    /** One destination per balloon or boat station; arrival point keeps clear of every other station. */
    static List<Destination> stationDestinations(List<TransitNetwork.Station> every, double fromX, double fromZ) {
        List<Destination> out = new ArrayList<>();
        for (TransitNetwork.Station dest : every) {
            if (!Deployables.STATION_KIND.equals(dest.kind) && !Deployables.BOAT_STATION_KIND.equals(dest.kind)) {
                continue;
            }
            Vector3d pad = StationMarkerProvider.markerPoint(dest);
            List<double[]> others = new ArrayList<>();
            for (TransitNetwork.Station other : every) {
                if (other != dest) {
                    Vector3d p = StationMarkerProvider.markerPoint(other);
                    others.add(new double[]{p.x, p.z});
                }
            }
            double[] a = stationArrival(pad.x, pad.z, fromX, fromZ, others);
            out.add(new Destination(dest.name, Kind.STATION, a[0], a[1], (int) Math.round(pad.y),
                    new SkyLog.Reached(dest.origin.x, dest.origin.y, dest.origin.z)));
        }
        return out;
    }
}
