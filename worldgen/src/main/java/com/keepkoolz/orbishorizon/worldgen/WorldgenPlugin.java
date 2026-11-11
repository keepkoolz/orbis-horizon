package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;
import com.keepkoolz.orbishorizon.api.Stations;

import java.nio.file.Path;
import java.util.logging.Level;

/** The station generation plugin: its registries, the chunk listening and the registration as an extension of Orbis Horizon. */
public final class WorldgenPlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public WorldgenPlugin(JavaPluginInit init) {
        super(init);
    }

    /** Migrates the files of an earlier location, loads the settings and the registries, reconciles them with the network, declares the counters, starts the listener and registers the extension. */
    @Override
    protected void setup() {
        try {
            startWorldgen();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated stations: setup failed");
            try {
                WorldgenApi.telemetry().error("worldgen", e, null, "setup");
            } catch (RuntimeException ignored) {
                // Telemetry is not available either.
            }
        }
    }

    /** True when every file of the earlier location is migrated or in conflict. After a failure the generation stays off for the session. */
    static boolean generationAllowed(WorldgenMigration.Result migration) {
        return migration != null && migration.failed() == 0;
    }

    /**
     * The part of the start that needs no server: migrates the files of the earlier location, loads the settings, and, only when
     * every file is migrated or in conflict, loads the registry and the chunk maps and reconciles the registry with the network.
     * Returns whether the generation may start.
     */
    static boolean prepareGeneration(Path dir, Path legacyDir, Stations stations) {
        WorldgenMigration.Result migration = WorldgenMigration.migrate(legacyDir, dir);
        boolean generation = generationAllowed(migration);
        WorldgenSettings.load(dir, legacyDir);
        if (generation) {
            GeneratedStations.get().load(dir);
            NewChunks.init(dir);
            WorldgenReconcile.run(GeneratedStations.get(), stations);
        } else {
            // The registry and the chunk maps are not loaded: neither is written to dir until the files are migrated.
            int failed = migration == null ? 0 : migration.failed();
            LOGGER.at(Level.SEVERE).log("Generated stations: %d file(s) of the earlier data folder could not be migrated, "
                    + "generation is off for this session, fix the files and restart", failed);
            WorldgenApi.telemetry().error("worldgen", new IllegalStateException("migration failed for " + failed + " file(s)"),
                    null, "migrate");
        }
        return generation;
    }

    private void startWorldgen() {
        boolean generation = prepareGeneration(getDataDirectory(), OrbisHorizonApi.get().dataDirectory(), WorldgenApi.stations());
        // The counters are declared before the first count, which ignores a name that is not registered.
        WorldgenApi.telemetry().registerCounters(WorldgenExtension.COUNTERS);
        if (generation) {
            try {
                GenerationListener.start();
                getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class, GenerationListener::onChunk);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Generated stations: chunk listening disabled");
            }
        }
        OrbisHorizonApi.get().register(new WorldgenExtension(generation));
    }

    @Override
    protected void shutdown() {
        try {
            GenerationListener.stop();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Generated stations: shutdown failed");
        }
    }
}
