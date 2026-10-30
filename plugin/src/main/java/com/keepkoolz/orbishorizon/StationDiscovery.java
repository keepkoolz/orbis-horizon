package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Discovery of stations: the vehicle logs learn the stations they pass, each player discovers the stations he comes near and sees a big title. */
final class StationDiscovery {
    /** Time between two looks at the stations of the world (ms). */
    static final long SCAN_INTERVAL_MS = 1000;
    /** Horizontal distance (blocks) at which a player on foot or aboard discovers a station, set apart from the vehicle reach. */
    static final double DISCOVERY_RADIUS = 40;
    private static final float TITLE_DURATION_S = 4f;
    private static final float TITLE_FADE_IN_S = 0.5f;
    private static final float TITLE_FADE_OUT_S = 1.5f;

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Time of the next scan of each world (ms). */
    private static final Map<String, Long> NEXT_SCAN = new ConcurrentHashMap<>();

    private StationDiscovery() {}

    /** True for a station that vehicles and players discover (balloon and boat stations). */
    private static boolean discoverable(TransitNetwork.Station s) {
        return Deployables.STATION_KIND.equals(s.kind) || Deployables.BOAT_STATION_KIND.equals(s.kind);
    }

    /**
     * Once a second per world: every online player within reach of a station he does not know yet discovers it and sees the big
     * title. On foot, aboard a vehicle or a shuttle alike. World thread. Never throws.
     */
    static void tick(Store<EntityStore> store, World world) {
        try {
            long now = System.currentTimeMillis();
            Long next = NEXT_SCAN.get(world.getName());
            if (next != null && now < next) {
                return;
            }
            NEXT_SCAN.put(world.getName(), now + SCAN_INTERVAL_MS);
            List<TransitNetwork.Station> stations = TransitNetwork.get().stations(world.getName());
            if (stations.isEmpty()) {
                return;
            }
            KnownStations known = KnownStations.get();
            for (PlayerRef pr : world.getPlayerRefs()) {
                if (!pr.isValid()) {
                    continue;
                }
                TransformComponent t = store.getComponent(pr.getReference(), TransformComponent.getComponentType());
                if (t == null) {
                    continue;
                }
                double px = t.getPosition().x;
                double pz = t.getPosition().z;
                for (TransitNetwork.Station s : stations) {
                    if (!discoverable(s)) {
                        continue;
                    }
                    Vector3d p = StationMarkerProvider.markerPoint(s);
                    if (SkyLog.stationInReach(px, pz, p.x, p.z, DISCOVERY_RADIUS) && KnownStations.learn(world.getName(), pr.getUuid(), s)) {
                        show(pr, s.name);
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station discovery: scan failed");
            Telemetry.error("station.discovery", e, null, "scan");
        }
    }

    /** Adds to the log every balloon or boat station within reach of the centre (cx, cz) and returns the names of the new ones. */
    static List<String> scan(SkyLog sky, List<TransitNetwork.Station> stations, double cx, double cz) {
        List<String> found = new ArrayList<>();
        for (TransitNetwork.Station s : stations) {
            if (!discoverable(s)) {
                continue;
            }
            Vector3d p = StationMarkerProvider.markerPoint(s);
            if (SkyLog.stationInReach(cx, cz, p.x, p.z) && sky.addReached(s.origin.x, s.origin.y, s.origin.z)) {
                found.add(s.name);
            }
        }
        return found;
    }

    /** Big title "Station discovered" with the station name below, for one player. */
    static void show(PlayerRef pr, String stationName) {
        show(pr, Texts.t("station.discoveredName").param("station", stationName));
    }

    /** Big title "Station discovered" with the station name and the name of the player who shared it below. */
    static void showShared(PlayerRef pr, String stationName, String sharedBy) {
        show(pr, Texts.t("station.discoveredShared").param("station", stationName).param("player", sharedBy));
    }

    private static void show(PlayerRef pr, com.hypixel.hytale.server.core.Message subtitle) {
        EventTitleUtil.showEventTitleToPlayer(pr, Texts.t("station.discovered"), subtitle, EventTitleStyle.Major,
                EventTitleUtil.DEFAULT_ZONE, TITLE_DURATION_S, TITLE_FADE_IN_S, TITLE_FADE_OUT_S);
    }
}
