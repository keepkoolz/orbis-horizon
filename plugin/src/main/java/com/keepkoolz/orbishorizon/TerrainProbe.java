package com.keepkoolz.orbishorizon;

/** Read access to the generated terrain at a block column. zone and biome are never null (empty when unknown). */
interface TerrainProbe {
    /** Height of the last solid block of the base terrain. */
    int height(int x, int z);

    String zone(int x, int z);

    /** Biome name, possibly with a Tile. or Custom. prefix. */
    String biome(int x, int z);
}
