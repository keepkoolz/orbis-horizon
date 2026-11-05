package com.keepkoolz.orbishorizon.worldgen;

import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;
import com.keepkoolz.orbishorizon.api.StationKinds;
import com.keepkoolz.orbishorizon.api.Stations;
import com.keepkoolz.orbishorizon.api.Terrain;
import com.keepkoolz.orbishorizon.api.WorldAccess;

/** The services of the public API as the station generation uses them. */
final class WorldgenApi {
    private WorldgenApi() {
    }

    static Stations stations() {
        return OrbisHorizonApi.get().stations();
    }

    static StationKinds kinds() {
        return OrbisHorizonApi.get().kinds();
    }

    static WorldAccess world() {
        return OrbisHorizonApi.get().world();
    }

    static Terrain terrain() {
        return OrbisHorizonApi.get().terrain();
    }

    static com.keepkoolz.orbishorizon.api.Telemetry telemetry() {
        return OrbisHorizonApi.get().telemetry();
    }
}
