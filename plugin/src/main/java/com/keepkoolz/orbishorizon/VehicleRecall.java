package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The vehicles a station master can recall (piloted airships and large balloons): the list shown by RecallPage and the start of
 * the recall of the chosen one. The vehicle is found by the id of its structure.
 */
final class VehicleRecall {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private VehicleRecall() {
    }

    /** A vehicle in the list: structure id, type, name, distance and compass point from the station, and whether it is in the air. */
    record Candidate(UUID id, String kind, String name, double distance, String direction, boolean flying) {
    }

    /** Pure: a recallable vehicle is an airship or a large balloon with a pilot, of this player, in this world. */
    static boolean eligible(String kind, boolean hasCrew, boolean sameOwner, boolean sameWorld) {
        return (Deployables.AIRSHIP_KIND.equals(kind) || Deployables.BALLOON_KIND.equals(kind)) && hasCrew && sameOwner && sameWorld;
    }

    /** Pure: the candidates by increasing distance. */
    static List<Candidate> sortAndFilter(List<Candidate> raw) {
        List<Candidate> out = new ArrayList<>(raw);
        out.sort(Comparator.comparingDouble(Candidate::distance));
        return out;
    }

    /** The name of a vehicle: its own, else the automatic one (computed, not written), else the type label. */
    static String nameOf(UUID id, String kind, PlayerRef player) {
        String name = VehicleNames.get(id);
        if (name != null) {
            return name;
        }
        return player != null ? VehicleNames.autoName(player, kind) : VehicleNames.typeLabel(null, kind);
    }

    /** True if at least one vehicle of this player can be offered, without building the list. */
    static boolean canOffer(World world, UUID owner) {
        for (String kind : new String[] {Deployables.AIRSHIP_KIND, Deployables.BALLOON_KIND}) {
            for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, kind)) {
                if (e.ownership().id() != null && eligible(kind, e.crew() != null, owner.equals(e.owner()), true)) {
                    return true;
                }
            }
        }
        for (AirshipFlight f : AirshipManager.get().flights()) {
            if (f.world == world && f.ownership.id() != null && eligible(f.kind.id, f.crew != null, owner.equals(f.ownership.owner()), true)) {
                return true;
            }
        }
        for (BalloonFlight f : BalloonManager.get().flights()) {
            if (f.world == world && f.ownership.id() != null && eligible(f.kind.id, f.crew != null, owner.equals(f.ownership.owner()), true)) {
                return true;
            }
        }
        return false;
    }

    /** The vehicles of this player in this world that the master can be asked about, nearest to the station first. */
    static List<Candidate> candidates(World world, UUID owner, TransitNetwork.Station s, PlayerRef pr) {
        Vector3d pad = StationGeometry.markerPoint(s);
        List<Candidate> raw = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (String kind : new String[] {Deployables.AIRSHIP_KIND, Deployables.BALLOON_KIND}) {
            for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, kind)) {
                UUID id = e.ownership().id();
                if (id != null && eligible(kind, e.crew() != null, owner.equals(e.owner()), true) && seen.add(id)) {
                    raw.add(candidate(id, kind, e.x() + 0.5, e.z() + 0.5, pad, pr, false));
                }
            }
        }
        for (AirshipFlight f : AirshipManager.get().flights()) {
            UUID id = f.ownership.id();
            if (f.world == world && id != null && eligible(f.kind.id, f.crew != null, owner.equals(f.ownership.owner()), true)
                    && seen.add(id)) {
                raw.add(candidate(id, f.kind.id, f.pos.x, f.pos.z, pad, pr, true));
            }
        }
        for (BalloonFlight f : BalloonManager.get().flights()) {
            UUID id = f.ownership.id();
            if (f.world == world && id != null && eligible(f.kind.id, f.crew != null, owner.equals(f.ownership.owner()), true)
                    && seen.add(id)) {
                raw.add(candidate(id, f.kind.id, f.entityPos.x, f.entityPos.z, pad, pr, true));
            }
        }
        return sortAndFilter(raw);
    }

    private static Candidate candidate(UUID id, String kind, double x, double z, Vector3d pad, PlayerRef pr, boolean flying) {
        double dx = x - pad.x;
        double dz = z - pad.z;
        return new Candidate(id, kind, nameOf(id, kind, pr), Math.hypot(dx, dz), DestinationPage.compass(dx, dz), flying);
    }

    /**
     * Recalls the vehicle with this structure id to the station: reads it again and checks it is still recallable, then hands over to
     * the recall of its type. Null if accepted, otherwise the refusal of the station master. Never throws.
     */
    static Message start(Store<EntityStore> store, World world, PlayerRef owner, TransitNetwork.Station s, UUID entryId) {
        try {
            UUID me = owner.getUuid();
            BalloonRegistry.Entry entry = BalloonRegistry.byId(entryId);
            String kind = null;
            boolean ok = false;
            if (entry != null) {
                kind = entry.kind();
                ok = eligible(kind, entry.crew() != null, me.equals(entry.owner()), entry.world().equals(world.getName()));
            } else {
                for (AirshipFlight f : AirshipManager.get().flights()) {
                    if (entryId.equals(f.ownership.id())) {
                        kind = f.kind.id;
                        ok = eligible(kind, f.crew != null, me.equals(f.ownership.owner()), f.world == world);
                    }
                }
                for (BalloonFlight f : BalloonManager.get().flights()) {
                    if (entryId.equals(f.ownership.id())) {
                        kind = f.kind.id;
                        ok = eligible(kind, f.crew != null, me.equals(f.ownership.owner()), f.world == world);
                    }
                }
            }
            if (!ok) {
                return Texts.t("crew.recall.noVehicle");
            }
            if (Deployables.AIRSHIP_KIND.equals(kind)) {
                return AirshipRecall.start(store, world, owner, s, entryId, entry);
            }
            return BalloonRecall.start(store, world, owner, s, entryId, entry);
        } catch (RuntimeException e) {
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e)
                    .log("Recall: choice failed");
            Telemetry.error("vehicle.recall", e, null, null);
            return Texts.t("crew.recall.noVehicle");
        }
    }

    /**
     * World load: players who are offline with a saved position inside the parked vehicle (the test {@code inside}, on the saved
     * position) would reconnect in the air once it has left. They get a stranded entry on the station's first boarding point (put
     * down there at their next arrival). Asynchronous: each player save is read off the world thread and the take-off never waits
     * for it. Only a save of this world counts. Never throws.
     */
    static void strandOfflinePlayers(World world, TransitNetwork.Station station, Predicate<Vector3d> inside, String zone, String type, String phase) {
        try {
            com.hypixel.hytale.server.core.universe.Universe universe = com.hypixel.hytale.server.core.universe.Universe.get();
            Vector3d point = TransitAutopilot.boardingPoints(world, station).get(0);
            String worldName = world.getName();
            for (UUID id : universe.getPlayerStorage().getPlayers()) {
                if (universe.getPlayer(id) != null) {
                    continue; // online: the take-off handles him
                }
                universe.getPlayerStorage().load(id).whenComplete((holder, t) -> {
                    try {
                        if (t != null || holder == null) {
                            return;
                        }
                        TransformComponent tc = holder.getComponent(TransformComponent.getComponentType());
                        Player p = holder.getComponent(Player.getComponentType());
                        String saved = p != null && p.getPlayerConfigData() != null ? p.getPlayerConfigData().getWorld() : null;
                        boolean in = tc != null && inside.test(tc.getPosition());
                        if (RecallPlanner.strandOffline(universe.getPlayer(id) != null, saved, worldName, in)) {
                            BalloonResume.writeStranded(id, worldName, point.x, point.y, point.z);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Recall: offline player %s not checked", id);
                        Telemetry.error(zone, e, type, phase);
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Recall: offline players aboard not checked");
            Telemetry.error(zone, e, type, phase);
        }
    }
}
