package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Terrain of a world read from the game's chunk generator with the world seed. Safe from any thread. */
final class GeneratorProbe implements TerrainProbe {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final ChunkGenerator generator;
    private final int seed;

    private GeneratorProbe(ChunkGenerator generator, int seed) {
        this.generator = generator;
        this.seed = seed;
    }

    /** Whether the feature can run in a world: READY, WAIT while the generator is not loaded yet, OFF for good. */
    enum Availability { READY, WAIT, OFF }

    static Availability availability(boolean deleteOnRemove, Object generator) {
        if (deleteOnRemove) {
            return Availability.OFF;
        }
        if (generator == null) {
            return Availability.WAIT;
        }
        return generator instanceof ChunkGenerator ? Availability.READY : Availability.OFF;
    }

    static Availability availability(World world) {
        return availability(world.getWorldConfig().isDeleteOnRemove(), world.getChunkStore().getGenerator());
    }

    /**
     * The probe of a world, or null when the world is deleted when it is removed (an instance, a throwaway world), when its
     * generator is not the game's chunk generator (one log line per world), or while the generator is still loading.
     */
    static GeneratorProbe of(World world) {
        Object generator = world.getChunkStore().getGenerator();
        Availability a = availability(world.getWorldConfig().isDeleteOnRemove(), generator);
        if (a == Availability.READY) {
            return new GeneratorProbe((ChunkGenerator) generator, (int) world.getWorldConfig().getSeed());
        }
        if (a == Availability.OFF && WARNED.add(world.getName())) {
            LOGGER.at(Level.INFO).log("Stations générées : le monde %s est supprimé à sa fermeture ou n'utilise pas le générateur du jeu, fonction inactive pour ce monde", world.getName());
        }
        return null;
    }

    @Override
    public int height(int x, int z) {
        return generator.getHeight(seed, x, z);
    }

    @Override
    public String zone(int x, int z) {
        ZoneBiomeResult r = generator.getZoneBiomeResultAt(seed, x, z);
        if (r == null || r.getZoneResult() == null || r.getZoneResult().getZone() == null) {
            return "";
        }
        return r.getZoneResult().getZone().name();
    }

    @Override
    public String biome(int x, int z) {
        ZoneBiomeResult r = generator.getZoneBiomeResultAt(seed, x, z);
        if (r == null || r.getBiome() == null || r.getBiome().getName() == null) {
            return "";
        }
        return r.getBiome().getName();
    }
}
