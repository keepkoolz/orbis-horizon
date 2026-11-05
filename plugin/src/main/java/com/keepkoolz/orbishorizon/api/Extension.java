package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.List;

/** What an extension gives Orbis Horizon. Orbis Horizon calls every method inside a try/catch. */
public interface Extension {
    /** Name shown in the server log. */
    String name();

    /** A world started (any thread, use world.execute for world access). */
    default void onWorldStart(World world) {
    }

    /** A world is being removed (any thread, use world.execute for world access). */
    default void onWorldStop(World world) {
    }

    /** An operator removed a station with the station commands (any thread, use world.execute for world access). The station is already out of the network. */
    default void onStationRemovedByOperator(World world, StationInfo station) {
    }

    /** Rows added to the configuration page. */
    default List<ConfigRow> configRows() {
        return List.of();
    }

    /** Sub-commands added under /orbishorizon station. */
    default List<AbstractCommand> stationSubCommands() {
        return List.of();
    }
}
