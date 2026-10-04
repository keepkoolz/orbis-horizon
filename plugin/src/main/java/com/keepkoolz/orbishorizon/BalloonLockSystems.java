package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locking of placed balloons (T27): cancels breaking, break damage and block placement on
 * a balloon in the registry (BalloonRegistry).
 *
 * These are ECS event systems (EntityEventSystem), like NoBuild and NoDestroyBlockBreak of the
 * game's trigger volumes: the game invokes the event on the acting entity (BlockHarvestUtils,
 * BlockPlaceUtils), reads isCancelled() after the systems, then cancels the action and sends the block state back to the
 * client (BlockSection.invalidateBlock). The plugin's placements and removals (World.setBlock, PrefabUtil.paste,
 * World.setBlockInteractionState) do not go through these events. Neither do "Use" interactions (chests,
 * burner, stools, chain, ladder).
 *
 * Limit: destruction without an entity (fire, explosions without a source, block physics) sends
 * EnvironmentBreakBlockEvent, which cannot be cancelled. It is not blocked.
 */
final class BalloonLockSystems {

    private static final long MESSAGE_INTERVAL_MS = 2000;
    private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

    private BalloonLockSystems() {
    }

    /** True if the action must be cancelled. Warns the player (at most every 2 s). */
    private static boolean deny(Store<EntityStore> store, ArchetypeChunk<EntityStore> chunk, int index, Vector3i target,
                                BlockType broken, boolean placing) {
        World world = store.getExternalData().getWorld();
        BalloonRegistry.Entry entry = BalloonRegistry.lockingEntry(world, target, broken, placing);
        if (entry == null) {
            return false;
        }
        Deployables.Kind kind = Deployables.get(entry.kind());
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = LAST_MESSAGE.get(player.getUuid());
            if (last == null || now - last >= MESSAGE_INTERVAL_MS) {
                LAST_MESSAGE.put(player.getUuid(), now);
                player.sendMessage(Texts.t(placing ? kind.messagePlace : kind.messageBreak));
            }
        }
        return true;
    }

    /** Breaking a block (completed). */
    static final class Break extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        Break() {
            super(BreakBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, BreakBlockEvent event) {
            if (deny(store, chunk, index, event.getTargetBlock(), event.getBlockType(), false)) {
                event.setCancelled(true);
            }
        }
    }

    /** Break damage (every pickaxe hit, and instant breaking in creative). */
    static final class Damage extends EntityEventSystem<EntityStore, DamageBlockEvent> {
        Damage() {
            super(DamageBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, DamageBlockEvent event) {
            if (deny(store, chunk, index, event.getTargetBlock(), event.getBlockType(), false)) {
                event.setCancelled(true);
            }
        }
    }

    /** Placing a block. */
    static final class Place extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        Place() {
            super(PlaceBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, PlaceBlockEvent event) {
            if (deny(store, chunk, index, event.getTargetBlock(), null, true)) {
                event.setCancelled(true);
            }
        }
    }
}
