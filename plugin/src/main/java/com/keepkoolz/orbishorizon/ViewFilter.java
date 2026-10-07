package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer entity visibility (T82). Vehicles in flight are drawn by two entities with the same model: one mounted on the pilot
 * (stuck to the pilot on the pilot's client, validated in solo) and one "observer" entity moved by the server (what the other players
 * see). Each entity must only be seen by the right players, so this registry says, for an entity, "only this player sees it" or
 * "everybody but this player sees it".
 *
 * Mechanism (bytecode of HytaleServer.jar, EntityTrackerSystems, read on 7 October 2026): for every player (EntityViewer), the
 * game's CollectVisible fills EntityViewer.visible (a public Set of refs) with the network-sendable entities in view range. The game's
 * own HideFromPlayer then removes the players hidden by the player's HiddenPlayersManager from that same set, in the same system group
 * (FIND_VISIBLE_ENTITIES_GROUP, dependency AFTER CollectVisible). The visibility components are only updated afterwards
 * (ClearPreviouslyVisible is AFTER the whole group, then EnsureVisibleComponent, then AddToVisible), and the entity updates are sent from
 * what stays in the set. This system does exactly what HideFromPlayer does, with the mod's rules instead of the hidden-players list.
 *
 * If the system cannot be registered, available() stays false and every flight uses the single-entity display (previous behaviour).
 */
final class ViewFilter {

    enum Mode {
        /** Visible only to the given player. */
        ONLY,
        /** Visible to everybody except the given player. */
        HIDE
    }

    record Rule(UUID player, Mode mode) {
    }

    private static final Map<Ref<EntityStore>, Rule> RULES = new ConcurrentHashMap<>();
    private static volatile boolean registered;

    private ViewFilter() {
    }

    /** True once the filter system has been registered: dual-entity display needs it. */
    static boolean available() {
        return registered;
    }

    static void markRegistered() {
        registered = true;
    }

    /** The entity is visible only to this player. */
    static void only(Ref<EntityStore> ref, UUID player) {
        if (ref != null && player != null) {
            RULES.put(ref, new Rule(player, Mode.ONLY));
        }
    }

    /** The entity is visible to everybody except this player. */
    static void hide(Ref<EntityStore> ref, UUID player) {
        if (ref != null && player != null) {
            RULES.put(ref, new Rule(player, Mode.HIDE));
        }
    }

    /** Removes the rule of an entity (it is visible to everybody again, or it is about to be removed). */
    static void clear(Ref<EntityStore> ref) {
        if (ref != null) {
            RULES.remove(ref);
        }
    }

    static int ruleCount() {
        return RULES.size();
    }

    static Rule ruleOf(Ref<EntityStore> ref) {
        return ref != null ? RULES.get(ref) : null;
    }

    /** Drops the rules of entities that no longer exist. */
    private static void prune() {
        RULES.keySet().removeIf(r -> !r.isValid());
    }

    /** True if this viewer must not receive this entity. */
    private static boolean hiddenFrom(Rule rule, UUID viewer) {
        return rule.mode() == Mode.ONLY ? !rule.player().equals(viewer) : rule.player().equals(viewer);
    }

    /**
     * Removes from every player's visible set the entities of the registry that this player must not see. Same group and same
     * dependency as the game's HideFromPlayer, so it runs after the visible set is collected and before it is applied and sent.
     */
    static final class FilterSystem extends EntityTickingSystem<EntityStore> {
        private final Query<EntityStore> query;
        private final Set<Dependency<EntityStore>> dependencies;
        private int sinceCleanup;

        @SuppressWarnings({"unchecked", "rawtypes"})
        FilterSystem() {
            this.query = Query.and(new Query[]{EntityTrackerSystems.EntityViewer.getComponentType(), PlayerRef.getComponentType()});
            this.dependencies = Set.of(new SystemDependency<EntityStore, EntityTrackerSystems.CollectVisible>(
                    Order.AFTER, EntityTrackerSystems.CollectVisible.class));
        }

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
        }

        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                         CommandBuffer<EntityStore> commandBuffer) {
            if (RULES.isEmpty()) {
                return;
            }
            if (++sinceCleanup >= 600) {
                sinceCleanup = 0;
                prune();
            }
            EntityTrackerSystems.EntityViewer viewer = chunk.getComponent(index, EntityTrackerSystems.EntityViewer.getComponentType());
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            if (viewer == null || player == null) {
                return;
            }
            UUID uuid = player.getUuid();
            Iterator<Ref<EntityStore>> it = viewer.visible.iterator();
            while (it.hasNext()) {
                Ref<EntityStore> ref = it.next();
                Rule rule = RULES.get(ref);
                if (rule != null && hiddenFrom(rule, uuid)) {
                    viewer.hiddenCount++;
                    it.remove();
                }
            }
        }
    }
}
