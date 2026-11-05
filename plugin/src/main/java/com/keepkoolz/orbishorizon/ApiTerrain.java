package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.World;
import com.keepkoolz.orbishorizon.api.Terrain;
import com.keepkoolz.orbishorizon.api.TerrainProbe;
import org.joml.Vector3i;

/** The terrain and sea rules for the public API, backed by SeaRules and GeneratorProbe. */
final class ApiTerrain implements Terrain {
    @Override
    public boolean generatorLoading(World world) {
        return GeneratorProbe.availability(world) == GeneratorProbe.Availability.WAIT;
    }

    @Override
    public TerrainProbe probeOf(World world) {
        return GeneratorProbe.of(world);
    }

    @Override
    public boolean isWater(TerrainProbe t, int x, int z) {
        return SeaRules.isWater(t, x, z);
    }

    @Override
    public boolean isLand(TerrainProbe t, int x, int z) {
        return SeaRules.isLand(t, x, z);
    }

    @Override
    public boolean isSea(TerrainProbe t, int x, int z) {
        return SeaRules.isSea(t, x, z);
    }

    @Override
    public boolean isSeaZone(String zone) {
        return SeaRules.isSeaZone(zone);
    }

    @Override
    public boolean atSeaLevel(Vector3i boatStationOrigin) {
        return SeaRules.atSeaLevel(boatStationOrigin);
    }
}
