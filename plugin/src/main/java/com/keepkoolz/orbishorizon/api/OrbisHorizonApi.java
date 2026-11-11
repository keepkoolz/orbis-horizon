package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Entry point of the public API: the services of Orbis Horizon and the registration of one extension. */
public final class OrbisHorizonApi {
    /** The services Orbis Horizon installs, the data folder, and what runs when an extension registers. */
    public record Services(Stations stations, StationKinds kinds, WorldAccess world, Terrain terrain, Telemetry telemetry,
                           Path dataDirectory, Consumer<Extension> onRegistered) {
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile OrbisHorizonApi instance;

    private final Services services;
    private volatile Extension extension;

    private OrbisHorizonApi(Services services) {
        this.services = services;
    }

    /** Reserved for Orbis Horizon. Replaces the whole state, the registered extension included. */
    public static void install(Services services) {
        instance = new OrbisHorizonApi(Objects.requireNonNull(services, "services"));
    }

    /** The installed API. IllegalStateException before install. */
    public static OrbisHorizonApi get() {
        OrbisHorizonApi api = instance;
        if (api == null) {
            throw new IllegalStateException("Orbis Horizon API not installed");
        }
        return api;
    }

    /**
     * Registers the extension. False for null or when another extension is already registered, true for the same one. Otherwise
     * stores it, logs it and runs onRegistered (a failure there is logged and does not undo the registration).
     */
    public synchronized boolean register(Extension extension) {
        if (extension == null) {
            return false;
        }
        if (this.extension != null) {
            if (this.extension == extension) {
                return true;
            }
            LOGGER.at(Level.WARNING).log("Extension %s refused: %s is already registered", nameOf(extension), nameOf(this.extension));
            return false;
        }
        this.extension = extension;
        try {
            services.onRegistered().accept(extension);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Extension %s: registration callback failed", nameOf(extension));
        }
        return true;
    }

    /** The registered extension, null without one. */
    public Extension extension() {
        return extension;
    }

    public Stations stations() {
        return services.stations();
    }

    public StationKinds kinds() {
        return services.kinds();
    }

    public WorldAccess world() {
        return services.world();
    }

    public Terrain terrain() {
        return services.terrain();
    }

    public Telemetry telemetry() {
        return services.telemetry();
    }

    /** Data folder of Orbis Horizon. */
    public Path dataDirectory() {
        return services.dataDirectory();
    }

    private static String nameOf(Extension e) {
        try {
            return e.name();
        } catch (RuntimeException ex) {
            return e.getClass().getName();
        }
    }
}
