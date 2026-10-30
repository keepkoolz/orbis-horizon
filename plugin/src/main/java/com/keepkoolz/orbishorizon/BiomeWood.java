package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers for the wood of a boat station: species per biome, choice among samples, block renaming. */
final class BiomeWood {
    private BiomeWood() {}

    record Wood(String species, String family) {}

    /** Wood used when no tree is found around the station. */
    static final Wood DEFAULT = new Wood("Ash", "Hardwood");

    private static final String FALLBACK_FAMILY = "Hardwood";

    // Wood_<Family>_<Part>[_State_Definitions_...] with an optional leading '*'.
    private static final Pattern FAMILY_BLOCK = familyPattern("Hardwood");
    private static final Pattern STATION_FAMILY_BLOCK = familyPattern("Softwood");

    private static Pattern familyPattern(String source) {
        return Pattern.compile(
                "^(\\*?)Wood_" + source + "_((?:Planks_Half|Planks|Beam|Stairs|Roof_Shallow)(?:_State_Definitions_.*)?)$");
    }
    private static final Pattern TRUNK_BLOCK = Pattern.compile("^(\\*?)Wood_Ash_Trunk(_State_Definitions_.*)?$");

    /** Species of a biome, or null when unknown or without trees. Accepts a biome name with a Tile. or Custom. prefix. */
    static String speciesOf(String zone, String biome) {
        if (zone == null || biome == null) {
            return null;
        }
        String b = biome;
        if (b.startsWith("Tile.")) {
            b = b.substring(5);
        } else if (b.startsWith("Custom.")) {
            b = b.substring(7);
        }
        return BiomeWoodTable.SPECIES.get(zone + "/" + b);
    }

    static Wood woodOf(String species) {
        if (species == null) {
            return DEFAULT;
        }
        return new Wood(species, BiomeWoodTable.FAMILY.getOrDefault(species, FALLBACK_FAMILY));
    }

    /** Most frequent non-null sample, the first met on a tie (samples go from the nearest to the farthest). */
    static String chooseSpecies(List<String> samples) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String s : samples) {
            if (s != null) {
                count.merge(s, 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /**
     * Renames a Hardwood prefab block to the family of the wood and the Ash trunk to the species trunk.
     * Keeps a leading '*' and a state suffix. Any other block, and the default wood, are returned unchanged.
     */
    static String remap(String blockName, Wood wood) {
        return remap(blockName, wood, BiomeWood::blockExists);
    }

    /** True when the game has a block of this name. The single existence check of the wood fallback. */
    static boolean blockExists(String name) {
        return com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getIndex(name) >= 0;
    }

    /** Pure form: a renamed block that does not exist keeps its original (Hardwood) name. */
    static String remap(String blockName, Wood wood, java.util.function.Predicate<String> exists) {
        String renamed = rename(blockName, wood);
        return renamed.equals(blockName) || exists.test(renamed) ? renamed : blockName;
    }

    /**
     * Same renaming for the balloon station, whose prefab wood is Softwood (planks, beams, stairs). Its Darkwood roof and any
     * other block are unchanged. A null wood (no tree found) keeps the prefab; any species renames, Ash included.
     */
    static String remapStation(String blockName, Wood wood, java.util.function.Predicate<String> exists) {
        if (blockName == null || wood == null) {
            return blockName;
        }
        Matcher f = STATION_FAMILY_BLOCK.matcher(blockName);
        if (!f.matches()) {
            return blockName;
        }
        String renamed = f.group(1) + "Wood_" + wood.family() + "_" + f.group(2);
        return exists.test(renamed) ? renamed : blockName;
    }

    static String remapStation(String blockName, Wood wood) {
        return remapStation(blockName, wood, BiomeWood::blockExists);
    }

    private static String rename(String blockName, Wood wood) {
        if (blockName == null || wood == null || wood.equals(DEFAULT)) {
            return blockName;
        }
        Matcher t = TRUNK_BLOCK.matcher(blockName);
        if (t.matches()) {
            return t.group(1) + "Wood_" + wood.species() + "_Trunk" + (t.group(2) == null ? "" : t.group(2));
        }
        Matcher f = FAMILY_BLOCK.matcher(blockName);
        if (f.matches()) {
            return f.group(1) + "Wood_" + wood.family() + "_" + f.group(2);
        }
        return blockName;
    }

    /** Offsets of the 25 sample points (5 x 5 grid, step 16 blocks) from the nearest to the farthest. */
    static int[][] samplePoints() {
        List<int[]> pts = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                pts.add(new int[] {dx * 16, dz * 16});
            }
        }
        pts.sort(java.util.Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        return pts.toArray(new int[0][]);
    }

    /**
     * Wood of the biome around (x, z), read from the world generator (25 samples, most frequent species).
     * The log flag is true only for a real placement (the preview calls this often).
     * Any other generator, no tree, or any failure gives DEFAULT: this never throws.
     */
    static Wood woodAt(World world, int x, int z, boolean log) {
        String species = speciesAt(world, x, z, log, "Sea station");
        return species == null ? DEFAULT : woodOf(species);
    }

    /** Wood for the balloon station: null when no tree species is found (the prefab wood is kept), Ash included otherwise. */
    static Wood stationWoodAt(World world, int x, int z, boolean log) {
        String species = speciesAt(world, x, z, log, "Balloon station");
        return species == null ? null : woodOf(species);
    }

    /** Most frequent tree species around (x, z), null when there is none, another generator is used, or on failure. Never throws. */
    static String speciesAt(World world, int x, int z, boolean log, String station) {
        try {
            Object gen = world.getChunkStore().getGenerator();
            if (!(gen instanceof com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator generator)) {
                return null;
            }
            int seed = (int) world.getWorldConfig().getSeed();
            List<String> samples = new ArrayList<>();
            String firstBiome = null;
            for (int[] o : samplePoints()) {
                com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult r = generator.getZoneBiomeResultAt(seed, x + o[0], z + o[1]);
                if (r == null || r.getBiome() == null || r.getZoneResult() == null || r.getZoneResult().getZone() == null) {
                    samples.add(null);
                    continue;
                }
                String zone = r.getZoneResult().getZone().name();
                String biome = r.getBiome().getName();
                if (firstBiome == null) {
                    firstBiome = zone + "/" + biome;
                }
                samples.add(speciesOf(zone, biome));
            }
            String species = chooseSpecies(samples);
            if (log) {
                var l = com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.INFO);
                if (firstBiome == null) {
                    l.log("%s: no biome read, original wood", station);
                } else if (species == null) {
                    l.log("%s: no tree, original wood, biome %s", station, firstBiome);
                } else {
                    l.log("%s: wood %s, biome %s", station, species, firstBiome);
                }
            }
            return species;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
