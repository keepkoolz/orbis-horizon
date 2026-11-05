package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3i;

/** The generated terrain of a world and the sea rules. */
public interface Terrain {
    int SEA_LEVEL = 114;

    /** True while the generator of the world is not loaded yet. */
    boolean generatorLoading(World world);

    /** The probe of the world, or null for a throwaway world, another generator, or a generator that is not ready. */
    TerrainProbe probeOf(World world);

    /** True when the terrain of the column lies under the water surface. */
    boolean isWater(TerrainProbe t, int x, int z);

    boolean isLand(TerrainProbe t, int x, int z);

    /** Water of an ocean or a shore zone that is neither a lake nor a river. */
    boolean isSea(TerrainProbe t, int x, int z);

    /** True for the zone names of oceans and shores. */
    boolean isSeaZone(String zone);

    /** True when a sea station origin stands on the sea surface. */
    boolean atSeaLevel(Vector3i boatStationOrigin);
}
