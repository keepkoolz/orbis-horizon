package com.keepkoolz.orbishorizon;

/** Tells sea from land, lakes and rivers on the generated terrain. */
final class SeaRules {
    static final int SEA_LEVEL = 114;

    private SeaRules() {
    }

    /** True when the terrain of the column lies under the water surface. */
    static boolean isWater(TerrainProbe t, int x, int z) {
        return t.height(x, z) < SEA_LEVEL;
    }

    static boolean isLand(TerrainProbe t, int x, int z) {
        return !isWater(t, x, z);
    }

    /** Water of an ocean or a shore zone that is neither a lake nor a river. */
    static boolean isSea(TerrainProbe t, int x, int z) {
        if (!isWater(t, x, z)) {
            return false;
        }
        if (!isSeaZone(t.zone(x, z))) {
            return false;
        }
        String biome = stripPrefix(t.biome(x, z));
        return !(biome.startsWith("Lake") || biome.startsWith("River"));
    }

    /** True for the zone names of oceans and shores. */
    static boolean isSeaZone(String zone) {
        return zone.equals("Oceans") || zone.contains("Shallow_Ocean") || zone.contains("Shore");
    }

    private static String stripPrefix(String biome) {
        if (biome.startsWith("Tile.")) {
            return biome.substring(5);
        }
        if (biome.startsWith("Custom.")) {
            return biome.substring(7);
        }
        return biome;
    }
}
