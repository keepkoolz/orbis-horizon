package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import it.unimi.dsi.fastutil.Pair;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonString;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Animals captured by the cage of the transport balloon (T74).
 *
 * Closing the side captures the passive animals standing in the cage (Frozen: the role no longer ticks, and invulnerable). They
 * stay visible and follow the cage: Teleport at each step of the cage (blocks), at each tick of the flight (the cage is part of the
 * flying model), then put back in the cage when the balloon lands. Opening the side releases them.
 *
 * Each animal is kept as (uuid, role, position). The position is in the prefab frame of the transport balloon, relative to the
 * centre of the origin block, with the cage raised (descent 0): the world position is therefore origin + rotation(position) - (0, descent, 0),
 * in blocks as in flight (BalloonManager.prefabPoint). The list is saved in the registry entry (BalloonRegistry) and in the resume
 * file of the flight (BalloonResume), so a restart finds them again: by UUID if the entity is still in the world, otherwise
 * recreated from its role (NPCPlugin.spawnNPC).
 *
 * Captured animals are not mounted on the flying entity (MountedComponent and Teleport remove each other, T21): they are moved by Teleport.
 */
final class CageAnimals {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Animals captured at once, at most. */
    static final int MAX_ANIMALS = 3;
    /** Height added above the cage floor when an animal is placed, so that it is never inside the floor (blocks). */
    static final double FLOOR_MARGIN = 0.05;
    /** Flight: an animal closer than this to its place is not teleported again (limits the packets), in blocks. */
    private static final double MOVE_THRESHOLD = 0.05;
    /** Margin of the capture box under the floor (feet exactly at the floor level), blocks. */
    private static final double CAPTURE_BELOW = 0.25;
    /** Groups of the game whose members can be captured (NPC/Groups): young and small prey, big prey (adults), critters. */
    private static final String[] ACCEPTED_GROUPS = {"Prey", "PreyBig", "Critters"};
    /** Groups that are never captured, even if a role were in both lists. */
    private static final String[] REFUSED_GROUPS = {"Predators", "PredatorsBig", "Undead", "Void", "Vermin"};
    private static final String TAMED_PREFIX = "Tamed_";

    /** An animal captured: entity UUID, role name, position in the prefab frame (cage raised, relative to the centre of the origin block). */
    record Animal(UUID uuid, String role, double x, double y, double z) {
        Animal withUuid(UUID other) {
            return new Animal(other, role, x, y, z);
        }
    }

    /** Result of a closing: animals captured, and animals left because of the limit. */
    record CaptureResult(int captured, int skipped) {
    }

    private CageAnimals() {
    }

    // ------------------------------------------------------------------ hook of the cage

    static final TransportCage.Listener LISTENER = new TransportCage.Listener() {
        @Override
        public void onStep(World world, BalloonRegistry.Entry entry, int from, int to) {
            try {
                step(world, entry, to);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Animaux de la cage : déplacement impossible");
            }
        }

        @Override
        public CaptureResult onClosed(World world, BalloonRegistry.Entry entry) {
            try {
                return capture(world, entry);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Animaux de la cage : capture impossible");
                return null;
            }
        }

        @Override
        public void onOpened(World world, BalloonRegistry.Entry entry) {
            try {
                release(world, entry);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Animaux de la cage : libération impossible");
            }
        }
    };

    // ------------------------------------------------------------------ rule

    /**
     * Rule of the captured animals: a living NPC (no DeathComponent, no player) whose role is in the groups Prey, PreyBig or Critters of
     * the game (NPC/Groups, role names with wildcards: cow, sheep, pig, chicken, turkey, goat, horse, rabbit, deer... and their
     * young, and the Tamed_* roles that match the same patterns), or whose role name starts with "Tamed_" (tamed livestock that
     * no group lists, such as Tamed_Mosshorn). Never a member of Predators, PredatorsBig, Undead, Void or Vermin.
     */
    static boolean isCapturable(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid() || store.getComponent(ref, Player.getComponentType()) != null) {
            return false;
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return false;
        }
        int role = npc.getRoleIndex();
        for (String g : REFUSED_GROUPS) {
            if (member(g, role)) {
                return false;
            }
        }
        String name = npc.getRoleName();
        if (name != null && name.startsWith(TAMED_PREFIX)) {
            return true;
        }
        for (String g : ACCEPTED_GROUPS) {
            if (member(g, role)) {
                return true;
            }
        }
        return false;
    }

    private static boolean member(String group, int roleIndex) {
        int g = NPCGroup.getAssetMap().getIndex(group);
        return g >= 0 && WorldSupport.hasTagInGroup(g, roleIndex);
    }

    // ------------------------------------------------------------------ positions

    /** World position of an animal for a cage lowered by descent blocks, structure at this origin and rotation. */
    static Vector3d worldPos(Animal a, Vector3i origin, Rotation rotation, int descent) {
        Vector3f r = rotation.rotateYaw(new Vector3f((float) a.x(), (float) (a.y() - descent), (float) a.z()), new Vector3f());
        return new Vector3d(origin.x + 0.5 + r.x, origin.y + r.y + FLOOR_MARGIN, origin.z + 0.5 + r.z);
    }

    /** World position of an animal in flight: from the flight's entity position (centre of the origin block). */
    static Vector3d flightPos(Animal a, BalloonFlight flight) {
        Vector3d p = BalloonManager.prefabPoint(flight.entityPos, flight.rotation, new Vector3f((float) a.x(), (float) a.y(), (float) a.z()));
        return p.add(0, FLOOR_MARGIN, 0);
    }

    private static void moveTo(World world, Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pos, double threshold) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null || t.getPosition().distanceSquared(pos) <= threshold * threshold) {
            return;
        }
        Teleport tp = new Teleport(new Vector3d(pos), new Rotation3f(t.getRotation())).withoutVelocityReset();
        world.execute(() -> {
            if (ref.isValid()) {
                store.putComponent(ref, Teleport.getComponentType(), tp);
            }
        });
    }

    private static Ref<EntityStore> refOf(World world, UUID uuid) {
        Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(uuid);
        return ref != null && ref.isValid() ? ref : null;
    }

    // ------------------------------------------------------------------ freeze

    private static void freeze(Store<EntityStore> store, Ref<EntityStore> ref) {
        store.putComponent(ref, Frozen.getComponentType(), Frozen.get());
        boolean already = store.getComponent(ref, Invulnerable.getComponentType()) != null;
        if (!already) {
            store.putComponent(ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        }
    }

    private static void unfreeze(Store<EntityStore> store, Ref<EntityStore> ref) {
        store.tryRemoveComponent(ref, Frozen.getComponentType());
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        // An animal whose role is invulnerable by itself stays so.
        boolean natural = npc != null && npc.getRole() != null && npc.getRole().isInvulnerable();
        if (!natural) {
            store.tryRemoveComponent(ref, Invulnerable.getComponentType());
        }
    }

    // ------------------------------------------------------------------ capture, release

    private static void box(Vector3i origin, Rotation rotation, int descent, Vector3d min, Vector3d max) {
        int[] a = Deployables.TRANSPORT_CAGE_INSIDE_MIN;
        int[] b = Deployables.TRANSPORT_CAGE_INSIDE_MAX;
        Vector3i p = rotation.rotateYaw(new Vector3i(a[0], a[1] - descent, a[2]), new Vector3i()).add(origin);
        Vector3i q = rotation.rotateYaw(new Vector3i(b[0], b[1] - descent, b[2]), new Vector3i()).add(origin);
        min.set(Math.min(p.x, q.x), Math.min(p.y, q.y) - CAPTURE_BELOW, Math.min(p.z, q.z));
        max.set(Math.max(p.x, q.x) + 1, Math.max(p.y, q.y) + 1, Math.max(p.z, q.z) + 1);
    }

    /**
     * Closing of the side: the passive animals in the inside of the cage (3 x 3 x 3) are frozen and noted in the entry, up to
     * MAX_ANIMALS in all. Those beyond the limit are left free (counted in skipped). On the world thread.
     */
    static CaptureResult capture(World world, BalloonRegistry.Entry e) {
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        Vector3d min = new Vector3d();
        Vector3d max = new Vector3d();
        box(origin, e.rotation(), e.descent(), min, max);
        Store<EntityStore> store = world.getEntityStore().getStore();
        List<Animal> kept = new ArrayList<>(e.animals());
        int captured = 0;
        int skipped = 0;
        Vector3f centre = new Vector3f();
        for (Ref<EntityStore> ref : new ArrayList<>(TargetUtil.getAllEntitiesInBox(min, max, store))) {
            if (!isCapturable(store, ref)) {
                continue;
            }
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (id == null || t == null || npc == null) {
                continue;
            }
            boolean known = false;
            for (Animal a : kept) {
                known |= a.uuid().equals(id.getUuid());
            }
            if (known) {
                continue;
            }
            if (kept.size() >= MAX_ANIMALS) {
                skipped++;
                continue;
            }
            Vector3d pos = t.getPosition();
            Vector3f local = e.rotation().toInverse().rotateYaw(
                    new Vector3f((float) (pos.x - origin.x - 0.5), (float) (pos.y - origin.y), (float) (pos.z - origin.z - 0.5)), centre);
            // Prefab frame with the cage raised. Feet never under the floor top (y = 1).
            double ly = Math.max(local.y + e.descent() - FLOOR_MARGIN, 1.0);
            Animal animal = new Animal(id.getUuid(), npc.getRoleName(), local.x, ly, local.z);
            freeze(store, ref);
            kept.add(animal);
            captured++;
            LOGGER.at(Level.INFO).log("Cage en %s : animal capturé (%s, %s)", origin, animal.role(), shortId(animal.uuid()));
        }
        if (captured > 0) {
            BalloonRegistry.setAnimals(world, origin, e.rotation(), e.kind(), kept);
            // They are put back at their place (feet on the floor), as the capture position can be anywhere in the box.
            BalloonRegistry.Entry now = BalloonRegistry.get(world, origin, e.rotation(), e.kind());
            if (now != null) {
                step(world, now, e.descent());
            }
        }
        return new CaptureResult(captured, skipped);
    }

    /** Opening of the side: the animals are no longer frozen and are forgotten. */
    static void release(World world, BalloonRegistry.Entry e) {
        if (e.animals().isEmpty()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (Animal a : e.animals()) {
            Ref<EntityStore> ref = refOf(world, a.uuid());
            if (ref != null) {
                unfreeze(store, ref);
            }
        }
        BalloonRegistry.setAnimals(world, new Vector3i(e.x(), e.y(), e.z()), e.rotation(), e.kind(), List.of());
        LOGGER.at(Level.INFO).log("Cage en (%d, %d, %d) : %d animal(aux) libéré(s)", e.x(), e.y(), e.z(), e.animals().size());
    }

    // ------------------------------------------------------------------ cage movement (blocks)

    /**
     * After a step of the cage (the blocks are already in their new place): each animal is put at its place for the new descent. The
     * floor top is at the level of the stored height (1 in the prefab), shifted by the same descent, and the blocks are moved before the
     * animals: the animal is above the new floor, never in it (the margin of FLOOR_MARGIN keeps it off the surface).
     */
    static void step(World world, BalloonRegistry.Entry e, int descent) {
        if (e.animals().isEmpty()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        for (Animal a : e.animals()) {
            Ref<EntityStore> ref = refOf(world, a.uuid());
            if (ref != null) {
                moveTo(world, store, ref, worldPos(a, origin, e.rotation(), descent), 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ flight

    /** Take-off: the animals in the entry of the structure about to fly. Empty if none. */
    static List<Animal> takeFromEntry(World world, Vector3i origin, Rotation rotation, String kind) {
        BalloonRegistry.Entry e = BalloonRegistry.get(world, origin, rotation, kind);
        return e == null ? new ArrayList<>() : new ArrayList<>(e.animals());
    }

    /** Each tick of the flight: the animals are brought back to their place in the cage (teleport if the gap exceeds the threshold). */
    static void follow(Store<EntityStore> store, BalloonFlight flight) {
        if (flight.animals.isEmpty()) {
            return;
        }
        for (Animal a : flight.animals) {
            Ref<EntityStore> ref = refOf(flight.world, a.uuid());
            if (ref != null) {
                moveTo(flight.world, store, ref, flightPos(a, flight), MOVE_THRESHOLD);
            }
        }
    }

    // ------------------------------------------------------------------ landing, resume

    /**
     * After the structure is placed again (landing, failed take-off, resume): puts each animal in the cage and notes them in the
     * registry entry. An animal found by UUID is frozen again and moved; a lost one is recreated from its role (a new UUID).
     * Returns the list actually noted. No duplicate: an animal found is never recreated. On the world thread.
     */
    static List<Animal> place(World world, Vector3i origin, Rotation rotation, String kind, List<Animal> animals) {
        return place(world, origin, rotation, kind, animals, 0);
    }

    /** Same for a cage lowered by descent blocks (world load: the registry entry keeps the cage where it was). */
    static List<Animal> place(World world, Vector3i origin, Rotation rotation, String kind, List<Animal> animals, int descent) {
        if (animals == null || animals.isEmpty()) {
            return List.of();
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        List<Animal> out = new ArrayList<>();
        for (Animal a : animals) {
            Vector3d pos = worldPos(a, origin, rotation, descent);
            Ref<EntityStore> ref = refOf(world, a.uuid());
            if (ref != null) {
                freeze(store, ref);
                moveTo(world, store, ref, pos, 0.0);
                out.add(a);
                continue;
            }
            Animal made = recreate(world, store, a, pos);
            if (made != null) {
                out.add(made);
            }
        }
        BalloonRegistry.setAnimals(world, origin, rotation, kind, out);
        return out;
    }

    /** Recreates an animal from its role, frozen, at this position. Null if the role no longer exists. */
    static Animal recreate(World world, Store<EntityStore> store, Animal a, Vector3d pos) {
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (a.role() == null || plugin == null || plugin.getIndex(a.role()) < 0) {
                LOGGER.at(Level.WARNING).log("Animal de la cage perdu (rôle « %s » inconnu), oublié", a.role());
                return null;
            }
            Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> p =
                    plugin.spawnNPC(store, a.role(), null, pos, new Rotation3f());
            if (p == null || p.first() == null || !p.first().isValid()) {
                LOGGER.at(Level.WARNING).log("Animal de la cage perdu (%s) : recréation impossible, oublié", a.role());
                return null;
            }
            Ref<EntityStore> ref = p.first();
            freeze(store, ref);
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            LOGGER.at(Level.INFO).log("Animal de la cage recréé (%s, ancien %s)", a.role(), shortId(a.uuid()));
            return id == null ? a : a.withUuid(id.getUuid());
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Animal de la cage (%s) non recréé", a.role());
            return null;
        }
    }

    // ------------------------------------------------------------------ world load

    /** Placed transport balloon whose animals must be found again after a world load. */
    private record Pending(World world, Vector3i origin, Rotation rotation, String kind, long deadlineMs) {
    }

    private static final java.util.Queue<Pending> PENDING = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /**
     * Time left to the world to load the entities of the cage chunks before an animal not found by UUID is recreated (ms). Without
     * this wait, a restart could recreate an animal that is still being loaded and duplicate it.
     */
    private static final long RECOVER_WAIT_MS = 10_000;

    /**
     * World load (StartWorldEvent, plugin start): for each placed transport balloon of the registry that holds animals, the chunks of
     * the cage are loaded, then the animals are looked up by UUID for up to RECOVER_WAIT_MS (tick): found, they are frozen again and
     * put back in the cage, not found after the wait, they are recreated from their role.
     */
    static void recoverWorld(World world) {
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.TRANSPORT_KIND)) {
            if (e.animals().isEmpty()) {
                continue;
            }
            try {
                List<java.util.concurrent.CompletableFuture<?>> loads = new ArrayList<>();
                for (int cx = com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(e.x() - 10);
                     cx <= com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(e.x() + 10); cx++) {
                    for (int cz = com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(e.z() - 10);
                         cz <= com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(e.z() + 10); cz++) {
                        loads.add(world.getChunkAsync(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(cx, cz)));
                    }
                }
                java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0])).whenComplete((v, t) -> {
                    try {
                        world.execute(() -> PENDING.add(new Pending(world, new Vector3i(e.x(), e.y(), e.z()), e.rotation(), e.kind(),
                                System.currentTimeMillis() + RECOVER_WAIT_MS)));
                    } catch (RuntimeException ex) {
                        LOGGER.at(Level.WARNING).withCause(ex).log("Monde %s arrêté : animaux de la cage en (%d, %d, %d) non retrouvés", world.getName(), e.x(), e.y(), e.z());
                    }
                });

            } catch (RuntimeException ex) {
                // At plugin start the chunk store of a world that has not finished starting is not ready: StartWorldEvent calls again.
                LOGGER.at(Level.INFO).log("Monde %s pas encore prêt : animaux de la cage en (%d, %d, %d) repris au démarrage du monde (%s)",
                        world.getName(), e.x(), e.y(), e.z(), ex.getClass().getSimpleName());
            }
        }
    }

    /** Each tick of the world: resolves the animals waiting for their entity (all found, or wait over). */
    static void tick(World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Pending p : new ArrayList<>(PENDING)) {
            if (p.world() != world) {
                continue;
            }
            BalloonRegistry.Entry e = BalloonRegistry.get(world, p.origin(), p.rotation(), p.kind());
            if (e == null || e.animals().isEmpty()) {
                PENDING.remove(p);
                continue;
            }
            boolean all = true;
            for (Animal a : e.animals()) {
                all &= refOf(world, a.uuid()) != null;
            }
            if (all || now >= p.deadlineMs()) {
                PENDING.remove(p);
                try {
                    place(world, p.origin(), p.rotation(), p.kind(), e.animals(), e.descent());
                } catch (RuntimeException ex) {
                    LOGGER.at(Level.WARNING).withCause(ex).log("Animaux de la cage en %s non remis en place", p.origin());
                }
            }
        }
    }

    // ------------------------------------------------------------------ administration

    /**
     * Releases every animal of the list (no longer frozen) and puts it on the ground under the cage (BalloonManager.groundBelow).
     * Used by the removal by an administrator (balloon placed or in flight). On the world thread.
     */
    static void releaseToGround(World world, List<Animal> animals, Vector3d[] positions) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (int i = 0; i < animals.size(); i++) {
            Ref<EntityStore> ref = refOf(world, animals.get(i).uuid());
            if (ref == null) {
                continue;
            }
            unfreeze(store, ref);
            if (positions != null && positions[i] != null) {
                moveTo(world, store, ref, BalloonManager.groundBelow(world, positions[i]), 0.0);
            }
        }
    }

    /** Short identifier for the logs and the diagnostic. */
    static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** Diagnostic text (English): role, short UUID, present or lost. */
    static String describe(World world, List<Animal> animals) {
        if (animals.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Animal a : animals) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a.role()).append(" ").append(shortId(a.uuid())).append(refOf(world, a.uuid()) != null ? " present" : " lost");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ files

    static BsonArray toBson(List<Animal> animals) {
        BsonArray list = new BsonArray();
        for (Animal a : animals) {
            BsonDocument d = new BsonDocument();
            d.put("uuid", new BsonString(a.uuid().toString()));
            d.put("role", new BsonString(a.role() == null ? "" : a.role()));
            d.put("x", new BsonDouble(a.x()));
            d.put("y", new BsonDouble(a.y()));
            d.put("z", new BsonDouble(a.z()));
            list.add(d);
        }
        return list;
    }

    static List<Animal> fromBson(BsonArray array) {
        List<Animal> list = new ArrayList<>();
        for (org.bson.BsonValue v : array) {
            BsonDocument d = v.asDocument();
            list.add(new Animal(UUID.fromString(d.getString("uuid").getValue()), d.getString("role").getValue(),
                    d.getNumber("x").doubleValue(), d.getNumber("y").doubleValue(), d.getNumber("z").doubleValue()));
        }
        return List.copyOf(list);
    }
}
