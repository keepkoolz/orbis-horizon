package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Burner flame on the ground (T13, T16): for each placed burner (processing bench whose Id is
 * BURNER_BENCH_ID), sets the burner block itself to the "On" state (flame) if fuel remains,
 * and to "default" otherwise.
 *
 * Same query as the game's BenchSystems$ProcessingBenchTick (ProcessingBenchBlock + BlockStateInfo).
 * The test is done at most once per second per burner, and the block change is
 * deferred with world.execute (blocks are not modified during the ChunkStore tick).
 *
 * T58: the same pass also lights the firebox block under an airship engine (Airship_Fuel_Tank bench, the firebox being the Airship_Engine block since T61) when the engine has fuel in its
 * inputs or a combustion in progress, and puts it out otherwise (AirshipEngines.setFireboxLit). Nothing else is done for engines.
 *
 * T20: the same pass, at the same rate, calls BalloonManager.hoverTick. It is what spots a
 * balloon placed in mid-air (the burner is the source of truth, it is saved with the chunk:
 * no list to reload after a restart), burns its fuel and triggers the dry descent.
 * The time elapsed since the previous pass is capped: a chunk left unloaded does not burn.
 */
public final class BurnerFlameSystem extends EntityTickingSystem<ChunkStore> {

    /** "Id" of the Bench field of Hotair_Balloon_Burner.json. */
    static final String BURNER_BENCH_ID = "Hotair_Balloon_Burner";
    private static final long CHECK_INTERVAL_MS = 1000;
    /** Maximum burn time counted per pass (seconds), after a chunk absence for example. */
    private static final double MAX_BURN_DT_SECONDS = 2.0;

    private final ComponentType<ChunkStore, ProcessingBenchBlock> benchType = ProcessingBenchBlock.getComponentType();
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> infoType = BlockModule.BlockStateInfo.getComponentType();
    private final Query<ChunkStore> query = Query.and(benchType, infoType);
    /** Time of the last pass per burner (key: world and position). */
    private final Map<String, Long> lastCheck = new ConcurrentHashMap<>();

    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
        ProcessingBenchBlock bench = chunk.getComponent(index, benchType);
        if (bench == null) {
            return;
        }
        Bench config = bench.getBench();
        if (config == null) {
            return;
        }
        // T58: the airship engine drives the glow of the firebox under it (no burning on the ground, hover and fuel are the burner's job).
        boolean engine = AirshipEngines.TANK_BLOCK.equals(config.getId());
        if (!engine && !BURNER_BENCH_ID.equals(config.getId())) {
            return;
        }
        BlockModule.BlockStateInfo info = chunk.getComponent(index, infoType);
        Vector3i p = new Vector3i();
        if (info == null || !info.fillWorldPos(store, p)) {
            return;
        }
        World world = store.getExternalData().getWorld();
        String key = world.getName() + ":" + p.x + "," + p.y + "," + p.z;
        long now = System.currentTimeMillis();
        Long last = lastCheck.get(key);
        if (last != null && now < last + CHECK_INTERVAL_MS) {
            return;
        }
        lastCheck.put(key, now);
        if (engine) {
            boolean glow = BurnerFuel.hasFuel(bench);
            Vector3i enginePos = new Vector3i(p);
            world.execute(() -> AirshipEngines.setFireboxLit(world, enginePos, glow));
            return;
        }
        // First pass (block placed or chunk loaded): nothing burns.
        double dtSeconds = last == null ? 0 : Math.min((now - last) / 1000.0, MAX_BURN_DT_SECONDS);
        boolean lit = BurnerFuel.hasFuel(bench);
        Vector3i burner = new Vector3i(p);
        world.execute(() -> {
            BalloonManager.setBurnerLit(world, burner, lit);
            BalloonManager.get().hoverTick(world, burner, dtSeconds);
        });
    }
}
