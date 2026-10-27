package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers for the stone of a balloon station: rock family per zone, basalt brick renaming. */
final class BiomeStone {
    private BiomeStone() {}

    record Stone(String family) {}

    /** Stone of the prefab, used when the zone is unknown. */
    static final Stone DEFAULT = new Stone("Basalt");

    /** Rock family per world zone. */
    static final Map<String, String> FAMILY = Map.of("Zone1", "Stone", "Zone2", "Sandstone", "Zone3", "Shale", "Zone4", "Volcanic");

    // Rock_Basalt_Brick[_Half|_Stairs][_State_Definitions_...] with an optional leading '*'.
    private static final Pattern BASALT_BRICK = Pattern.compile("^(\\*?)Rock_Basalt_Brick(.*)$");
    private static final Pattern ZONE_PREFIX = Pattern.compile("^(Zone\\d+)");

    /** Stone of a zone name such as Zone2_Tier1, or DEFAULT for an unknown zone. */
    static Stone stoneOfZone(String zoneName) {
        if (zoneName == null) {
            return DEFAULT;
        }
        Matcher m = ZONE_PREFIX.matcher(zoneName);
        String family = m.find() ? FAMILY.get(m.group(1)) : null;
        return family == null ? DEFAULT : new Stone(family);
    }

    static String remap(String blockName, Stone stone) {
        return remap(blockName, stone, BiomeWood::blockExists);
    }

    /**
     * Renames a basalt brick block (brick, half, stairs and stair states) to the brick of the stone family, keeping the
     * leading '*' and the suffix. Any other block, the default stone, and a renamed block that does not exist are unchanged.
     */
    static String remap(String blockName, Stone stone, Predicate<String> exists) {
        if (blockName == null || stone == null || stone.equals(DEFAULT)) {
            return blockName;
        }
        Matcher m = BASALT_BRICK.matcher(blockName);
        if (!m.matches()) {
            return blockName;
        }
        String renamed = m.group(1) + "Rock_" + stone.family() + "_Brick" + m.group(2);
        return exists.test(renamed) ? renamed : blockName;
    }

    /** Stone of the zone at (x, z), read from the world generator. Never throws, DEFAULT on any failure. */
    static Stone stoneAt(World world, int x, int z) {
        return stoneAt(world, x, z, true);
    }

    /** The log flag is true only for a real placement (the preview calls this often). */
    static Stone stoneAt(World world, int x, int z, boolean log) {
        try {
            Object gen = world.getChunkStore().getGenerator();
            if (!(gen instanceof com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator generator)) {
                return DEFAULT;
            }
            int seed = (int) world.getWorldConfig().getSeed();
            com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult r = generator.getZoneBiomeResultAt(seed, x, z);
            if (r == null || r.getZoneResult() == null || r.getZoneResult().getZone() == null) {
                return DEFAULT;
            }
            String zone = r.getZoneResult().getZone().name();
            Stone stone = stoneOfZone(zone);
            if (log) {
                com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.INFO)
                        .log("Station de montgolfière : pierre %s, zone %s", stone.family(), zone);
            }
            return stone;
        } catch (RuntimeException | LinkageError e) {
            return DEFAULT;
        }
    }
}
