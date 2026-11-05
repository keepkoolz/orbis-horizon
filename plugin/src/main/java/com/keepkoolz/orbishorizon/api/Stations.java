package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** The transit network and the placement of stations. */
public interface Stations {
    /** The stations of a world, of one kind or (kind null) of every kind. */
    List<StationInfo> inWorld(String world, String kind);

    /** The station of this column (origin x and z, any height) of a world, null when there is none. */
    StationInfo stationAt(String world, int x, int z);

    /** False when the network could not be read at start (it then looks empty), so an absence proves nothing. */
    boolean networkTrusted();

    /** The names of the stations of a world. */
    List<String> names(String world);

    /** The first station of the same kind closer than the minimum distance, else null. */
    StationInfo nearStation(String world, String kind, Vector3i origin);

    /** True when the name is valid and not in taken. */
    boolean nameAvailable(String name, Collection<String> taken);

    /** An automatic name that is not in taken. */
    String autoName(Collection<String> taken);

    /** World thread. Room test of a balloon station at origin (station cells above the grass layer and the docked balloon, no liquid). Null when there is room. */
    Refusal balloonRoomRefusal(World world, Vector3i origin, Rotation rotation, Predicate<String> treatAsEmpty);

    /** World thread. Room test of a sea station at origin: the pier, then the berth. Cells of emptied read as empty, cells of dredged as water. Null when there is room. */
    Refusal boatRoomRefusal(World world, Vector3i origin, Rotation rotation, Set<Vector3i> emptied, Set<Vector3i> dredged);

    /** World thread. Origin of a sea station computed at the given position, snapped to the water surface under the pier end. Null when there is no water. */
    Vector3i boatOrigin(World world, Vector3i computed, Rotation rotation);

    /** World thread. The cell that stops the search of boatOrigin, null when none does. */
    Vector3i boatOriginBlocker(World world, Vector3i computed, Rotation rotation);

    /** True when the prefab of the kind is available. */
    boolean prefabAvailable(String kind);

    /** World thread. Reads the biome look, pastes the prefab without an owner and applies the look. False when it could not be placed. */
    boolean paste(World world, String kind, Vector3i origin, Rotation rotation);

    /** World thread. Registers a pasted station in the network without an owner. A null name takes an automatic one. */
    void register(World world, String kind, Vector3i origin, Rotation rotation, String name);

    /** World thread. Removes the structure of the registry of placed structures. */
    void release(World world, String kind, Vector3i origin, Rotation rotation);

    /** World thread. Removes from the network the station of this column and its registry entry. Null when there is none. */
    StationInfo removeAt(World world, int x, int z);

    /** Teleports the player to target once the sections around are loaded, then sends done. Never throws. */
    void teleport(CommandContext ctx, Ref<EntityStore> ref, World world, Vector3d target, Message done, String label);
}
