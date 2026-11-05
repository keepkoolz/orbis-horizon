package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.keepkoolz.orbishorizon.api.Refusal;
import com.keepkoolz.orbishorizon.api.StationInfo;
import com.keepkoolz.orbishorizon.api.Stations;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;

/** The transit network and the station placement for the public API. Rules and registers are the internal ones. */
final class ApiStations implements Stations {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** A copy of a station for the API. */
    static StationInfo info(TransitNetwork.Station s) {
        return new StationInfo(s.name, s.world, new Vector3i(s.origin), s.rotation, s.kind);
    }

    /** The stations of a list that have a quay: a station without one is not a destination for the extensions. */
    private static List<TransitNetwork.Station> withQuay(List<TransitNetwork.Station> all) {
        List<TransitNetwork.Station> out = new ArrayList<>(all.size());
        for (TransitNetwork.Station s : all) {
            if (s.inNetwork()) out.add(s);
        }
        return out;
    }

    @Override
    public List<StationInfo> inWorld(String world, String kind) {
        List<TransitNetwork.Station> stations = kind == null ? withQuay(TransitNetwork.get().stations(world)) : TransitNetwork.get().stations(world, kind);
        List<StationInfo> out = new ArrayList<>(stations.size());
        for (TransitNetwork.Station s : stations) {
            out.add(info(s));
        }
        return out;
    }

    /** The API view of a station: null for a pending one (no quay), whose origin is a master cell and not a prefab origin. */
    static StationInfo visible(TransitNetwork.Station s) {
        return s == null || !s.inNetwork() ? null : info(s);
    }

    /** The station of a column as the extension sees it (a pending station is not returned). */
    static StationInfo stationAtIn(TransitNetwork net, String world, int x, int z) {
        return visible(net.atColumn(world, x, z));
    }

    @Override
    public StationInfo stationAt(String world, int x, int z) {
        return stationAtIn(TransitNetwork.get(), world, x, z);
    }

    @Override
    public boolean networkTrusted() {
        return !TransitNetwork.get().loadFailed;
    }

    @Override
    public List<String> names(String world) {
        List<String> out = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world)) {
            out.add(s.name);
        }
        return out;
    }

    @Override
    public StationInfo nearStation(String world, String kind, Vector3i origin) {
        return nearIn(TransitNetwork.get().stations(world), kind, origin);
    }

    /**
     * The first station of the same kind whose master is closer than the minimum distance to the master of a prefab placed at
     * origin (with the rotation None, which the master distance does not depend on much). Pending stations count.
     */
    static StationInfo nearIn(List<TransitNetwork.Station> stations, String kind, Vector3i origin) {
        Vector3i master = StationGeometry.convert(kind, origin, Rotation.None).master();
        TransitNetwork.Station near = StationPlacement.tooClose(stations, kind, master, TransitConstants.MIN_STATION_DISTANCE);
        return near == null ? null : info(near);
    }

    @Override
    public boolean nameAvailable(String name, Collection<String> taken) {
        return TransitNetwork.checkName(name, taken) == TransitNetwork.NameCheck.OK;
    }

    @Override
    public String autoName(Collection<String> taken) {
        return TransitNetwork.autoName(taken);
    }

    @Override
    public Refusal balloonRoomRefusal(World world, Vector3i origin, Rotation rotation, Predicate<String> treatAsEmpty) {
        try {
            return StationPlacement.balloonRoomRefusal(world, Deployables.STATION.shape(), Deployables.BALLOON_PUBLIC.shape(), origin,
                    rotation, treatAsEmpty);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station: unreadable prefab shape");
            Telemetry.error("deploy", e, Deployables.STATION_KIND, null);
            return new Refusal(null, null, null, null);
        }
    }

    @Override
    public Refusal boatRoomRefusal(World world, Vector3i origin, Rotation rotation, Set<Vector3i> emptied, Set<Vector3i> dredged) {
        try {
            return StationPlacement.boatRoomRefusal(world, Deployables.BOAT_STATION.shape(), origin, rotation, emptied, dredged);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Sea station: unreadable prefab shape");
            Telemetry.error("deploy", e, Deployables.BOAT_STATION_KIND, null);
            return new Refusal(null, null, null, null);
        }
    }

    @Override
    public Vector3i boatOrigin(World world, Vector3i computed, Rotation rotation) {
        return DeployPlacement.boatStationOrigin(DeployPlacement.worldState(world), computed, rotation);
    }

    @Override
    public Vector3i boatOriginBlocker(World world, Vector3i computed, Rotation rotation) {
        return DeployPlacement.boatStationOriginBlocker(DeployPlacement.worldState(world), computed, rotation);
    }

    @Override
    public boolean prefabAvailable(String kind) {
        Deployables.Kind k = Deployables.get(kind);
        return k != null && PrefabStore.get().findAssetPrefabPath(k.prefabPath) != null;
    }

    /** World thread. Only the balloon station and the sea station can be pasted. */
    @Override
    public boolean paste(World world, String kind, Vector3i origin, Rotation rotation) {
        Deployables.Kind k = Deployables.get(kind);
        if (k != Deployables.STATION && k != Deployables.BOAT_STATION) {
            return false;
        }
        try {
            Path path = PrefabStore.get().findAssetPrefabPath(k.prefabPath);
            if (path == null) {
                return false;
            }
            StructureShape shape = k.shape();
            StationPlacement.Look look = StationPlacement.readLook(world, k, origin);
            BalloonManager.pasteKeepingTerrain(PrefabBufferUtil.getCached(path), world, shape, origin, rotation, 1 | 8,
                    world.getEntityStore().getStore(), k.id, Ownership.of(null, null),
                    k == Deployables.STATION ? Deployables.STATION_GRASS_Y : Integer.MIN_VALUE);
            StationPlacement.applyLook(world, shape, origin, rotation, k, look);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station '%s' not pasted at (%d, %d, %d)", kind, origin.x, origin.y, origin.z);
            Telemetry.error("deploy", e, kind, "paste");
            return false;
        }
    }

    @Override
    public void register(World world, String kind, Vector3i origin, Rotation rotation, String name) {
        StationPlacement.completeRegistration(world, origin, rotation, null, name, null, kind);
    }

    @Override
    public void release(World world, String kind, Vector3i origin, Rotation rotation) {
        BalloonRegistry.remove(world, origin, rotation, StationSpec.of(kind).structure().id);
    }

    /** World thread. A pending station (no quay) is ignored: it is not the extension's, and its origin is a master cell. */
    @Override
    public StationInfo removeAt(World world, int x, int z) {
        TransitNetwork.Station s = TransitNetwork.get().atColumn(world.getName(), x, z);
        if (s == null || s.quay == null) {
            return null;
        }
        StationInfo info = info(s);
        StationRemoval.fromNetwork(world, s);
        if (s.fullPrefab) {
            release(world, s.kind, s.origin, s.rotation);
        } else if (s.quay != null && s.quayRotation != null) {
            // A player station is registered by its pad or pier (a pending one by nothing).
            BalloonRegistry.remove(world, s.quay, s.quayRotation, StationCommand.quayKind(s).id);
        }
        return info;
    }

    @Override
    public void teleport(CommandContext ctx, Ref<EntityStore> ref, World world, Vector3d target, Message done, String label) {
        teleportTo(ctx, ref, world, target, done, label);
    }

    /** Teleports the player to target once the sections around it are loaded, then sends done. Never throws. */
    static void teleportTo(CommandContext ctx, Ref<EntityStore> ref, World world, Vector3d target, Message done, String label) {
        try {
            int bx = (int) Math.floor(target.x), by = (int) Math.floor(target.y), bz = (int) Math.floor(target.z);
            SectionBlocks.loadSections(world.getChunkStore(), bx, by - 2, bz, bx, by + 2, bz).whenComplete((c, t) -> {
                try {
                    world.execute(() -> {
                        try {
                            if (!ref.isValid()) return;
                            Store<EntityStore> st = ref.getStore();
                            TransformComponent tr = st.getComponent(ref, TransformComponent.getComponentType());
                            Rotation3f look = tr != null ? new Rotation3f(tr.getRotation()) : new Rotation3f();
                            MountUtil.unseat(st, ref);
                            st.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
                            ctx.sendMessage(done);
                        } catch (RuntimeException e) {
                            LOGGER.at(Level.WARNING).withCause(e).log("Cannot teleport to '%s'", label);
                            Telemetry.error("transit", e, null, "teleport");
                        }
                    });
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Cannot teleport to '%s'", label);
                    Telemetry.error("transit", e, null, "teleport");
                }
            });
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot teleport to '%s'", label);
            Telemetry.error("transit", e, null, "teleport");
        }
    }
}
