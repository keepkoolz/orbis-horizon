package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.keepkoolz.orbishorizon.api.ConfigRow;
import com.keepkoolz.orbishorizon.api.Extension;
import com.keepkoolz.orbishorizon.api.StationInfo;

import java.util.List;

/** The station generation as an extension of Orbis Horizon: world events, configuration rows and station sub-commands. */
final class WorldgenExtension implements Extension {
    /** Usage counters of the generation, declared to the telemetry when the extension registers. */
    static final List<String> COUNTERS = List.of(
            "worldgen.station.air", "worldgen.station.boat", "worldgen.station.abandoned",
            "worldgen.boat.built.first", "worldgen.boat.built.fallback", "worldgen.boat.built.repaired", "worldgen.boat.exhausted",
            "worldgen.boat.dredged",
            "worldgen.air.built.first", "worldgen.air.built.fallback", "worldgen.air.exhausted");

    private static final String SECTION = "server.orbis_horizon.ui.config.section.world";
    private static final String UI = "server.orbis_horizon.ui.config.";

    private final boolean generation;

    WorldgenExtension() {
        this(true);
    }

    /** With generation false (migration failed) the world events do nothing, commands and configuration rows stay. */
    WorldgenExtension(boolean generation) {
        this.generation = generation;
    }

    @Override
    public String name() {
        return "Orbis Horizon Worldgen";
    }

    @Override
    public void onWorldStart(World world) {
        if (generation) GenerationListener.onWorldStart(world);
    }

    @Override
    public void onWorldStop(World world) {
        GenerationListener.onWorldStop(world);
    }

    @Override
    public void onStationRemovedByOperator(World world, StationInfo station) {
        GenerationListener.onStationRemovedByOperator(world, station);
    }

    @Override
    public List<ConfigRow> configRows() {
        return List.of(
                new ConfigRow("worldgen", SECTION, UI + "worldgen", WorldgenSettings::enabled, WorldgenSettings::setEnabled),
                new ConfigRow("worldgenOldTerrain", SECTION, UI + "worldgenOldTerrain", WorldgenSettings::oldTerrain,
                        WorldgenSettings::setOldTerrain));
    }

    @Override
    public List<AbstractCommand> stationSubCommands() {
        return List.of(new WorldgenCommands.Worldgen(), new WorldgenCommands.Planned());
    }
}
