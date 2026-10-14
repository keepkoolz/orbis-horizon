package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.builtin.mounts.BlockMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.BlockMountType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Bed of the small tent (T93). The game never places a player who gets up or wakes up: it only removes MountedComponent
 * (DismountNPC packet, end of slumber) and the client picks its own position next to the bed, which is outside the tent
 * (the roof is too low beside the bed). This system, modelled on the game's WakeUpOnDismountSystem
 * (RefChangeSystem on MountedComponent), puts the player at the exit spot of the tent (Deployables.TentSpec.exitPoint), facing
 * outwards, and moves the bed's respawn point to the same spot. A bed is recognised through the registry (BalloonRegistry.find),
 * so tents already placed are covered and no new bed block is needed.
 */
public final class TentBedSystem extends RefChangeSystem<EntityStore, MountedComponent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Height added to the exit spot (the feet are put a little above the floor, like BalloonManager.LANDING_LIFT). */
    private static final float EXIT_LIFT = 0.1f;

    /** Delay before the second teleport, in ms. */
    private static final long SECOND_TELEPORT_MS = 300L;
    /** A pending teleport older than this is dropped, in ms. */
    private static final long STALE_MS = 5000L;
    /** The second teleport is only sent if the player is still this close to the bed, in blocks. */
    private static final double MAX_BED_DISTANCE = 3.0;

    /** Second teleport waiting for its due time. */
    private record Pending(World world, Vector3i bed, Vector3d target, float yaw, long due, long created) {
    }

    private static final Map<Ref<EntityStore>, Pending> PENDING = new ConcurrentHashMap<>();

    /** Bed block of each player currently in a bed, memorised on mount (the block's component can be gone at removal). */
    private static final Map<Ref<EntityStore>, Vector3i> BEDS = new ConcurrentHashMap<>();

    @Override
    public ComponentType<EntityStore, MountedComponent> componentType() {
        return MountedComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, MountedComponent mount, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        if (mount.getBlockMountType() != BlockMountType.Bed) {
            return;
        }
        try {
            Vector3i bed = bedPosition(mount);
            if (bed != null) {
                BEDS.put(ref, bed);
            }
            World world = store.getExternalData().getWorld();
            world.execute(() -> patchRespawnPoints(store, ref, world));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Lit de tente : montage non traité");
        }
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, MountedComponent oldMount, MountedComponent newMount,
                               Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        // Replacement of an existing mount: nothing to do (the bed memorised on the first mount stays valid).
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, MountedComponent mount, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        if (mount.getBlockMountType() != BlockMountType.Bed) {
            return;
        }
        try {
            Vector3i bed = bedPosition(mount);
            String source = "composant";
            Vector3i remembered = BEDS.remove(ref);
            if (bed == null) {
                bed = remembered;
                source = "mémorisée";
            }
            if (bed == null) {
                source = "position du joueur";
                // Last resort: the player's own block (they lay on the bed).
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                if (t != null) {
                    Vector3d p = t.getPosition();
                    bed = new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
                }
            }
            if (bed == null) {
                return;
            }
            Vector3i bedPos = bed;
            String bedSource = source;
            World world = store.getExternalData().getWorld();
            // Deferred: after the dismount, and after a tent packing of the same tick (which removes the registry entry).
            world.execute(() -> getUp(store, ref, world, bedPos, bedSource));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Lit de tente : démontage non traité");
        }
    }

    /** Position of the bed block of a bed mount, null if its block no longer carries the component. */
    private static Vector3i bedPosition(MountedComponent mount) {
        Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> block = mount.getMountedToBlock();
        if (block == null || !block.isValid()) {
            return null;
        }
        BlockMountComponent c = block.getStore().getComponent(block, BlockMountComponent.getComponentType());
        return c != null ? c.getBlockPos() : null;
    }

    /** Result of a tent lookup: the entry, or null with the reason why there is none. */
    private record Lookup(BalloonRegistry.Entry entry, String why) {
    }

    /** The registry entry of the tent whose bed is at this cell, if its type has an exit spot. Null otherwise. */
    private static BalloonRegistry.Entry tentOfBed(World world, Vector3i pos) {
        return lookup(world, pos).entry();
    }

    /** Same as tentOfBed, with the reason when there is no tent (for the log). */
    private static Lookup lookup(World world, Vector3i pos) {
        BalloonRegistry.Entry e = BalloonRegistry.find(world, pos);
        if (e == null) {
            return new Lookup(null, "pas d'entrée au registre");
        }
        Deployables.Kind kind = Deployables.get(e.kind());
        if (kind == null || kind.tent == null || kind.tent.exitPoint == null) {
            return new Lookup(null, "type sans point de sortie (" + e.kind() + ")");
        }
        StructureShape shape = BalloonRegistry.shapeOf(e);
        if (shape == null) {
            return new Lookup(null, "forme introuvable");
        }
        Vector3i local = e.rotation().toInverse().rotateYaw(new Vector3i(pos.x - e.x(), pos.y - e.y(), pos.z - e.z()), new Vector3i());
        StructureShape.Cell cell = shape.inBounds(local.x, local.y, local.z) ? shape.cellAt(local.x, local.y, local.z) : null;
        if (cell == null || !cell.baseName().startsWith("Furniture_Crude_Bed")) {
            return new Lookup(null, "la case du prefab n'est pas un lit");
        }
        BlockType bt = SectionBlocks.typeAt(new SectionReader(world.getChunkStore()), pos.x, pos.y, pos.z);
        if (bt == null || !BalloonManager.sameBlock(bt, cell)) {
            return new Lookup(null, "bloc différent dans le monde");
        }
        return new Lookup(e, null);
    }

    /** World exit spot of a tent (feet), after the entry's rotation. */
    private static Vector3d exitPosition(BalloonRegistry.Entry e, Deployables.TentSpec spec) {
        Vector3d logical = new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5);
        Vector3f local = new Vector3f(spec.exitPoint).add(0f, EXIT_LIFT, 0f);
        return BalloonManager.prefabPoint(logical, e.rotation(), local);
    }

    /** Yaw (radians) of a player facing outwards at the exit: yaw 0 looks towards -Z, so a direction (dx, dz) gives atan2(-dx, -dz). */
    private static float exitYaw(BalloonRegistry.Entry e, Deployables.TentSpec spec) {
        Vector3f d = e.rotation().rotateYaw(new Vector3f(spec.exitFacing), new Vector3f());
        return (float) Math.atan2(-d.x, -d.z);
    }

    /** The player got up or woke up: puts them at the exit of the tent, if the bed belongs to a tent that has one. */
    private static void getUp(Store<EntityStore> store, Ref<EntityStore> ref, World world, Vector3i bedPos, String source) {
        String bedText = bedPos.x + ", " + bedPos.y + ", " + bedPos.z + " (" + source + ")";
        try {
            if (!ref.isValid()) {
                LOGGER.at(Level.INFO).log("Lit de tente : lit en %s, abandon : joueur invalide", bedText);
                return;
            }
            // Dead, or already on another mount (bed, seat, vehicle): not our business.
            if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                LOGGER.at(Level.INFO).log("Lit de tente : lit en %s, abandon : joueur mort", bedText);
                return;
            }
            if (store.getComponent(ref, MountedComponent.getComponentType()) != null) {
                LOGGER.at(Level.INFO).log("Lit de tente : lit en %s, abandon : joueur remonté", bedText);
                return;
            }
            Lookup found = lookup(world, bedPos);
            BalloonRegistry.Entry e = found.entry();
            if (e == null) {
                LOGGER.at(Level.INFO).log("Lit de tente : lit en %s, abandon : %s", bedText, found.why());
                return;
            }
            Deployables.TentSpec spec = Deployables.get(e.kind()).tent;
            Vector3d pos = exitPosition(e, spec);
            float yaw = exitYaw(e, spec);
            Rotation3f look = new Rotation3f();
            look.setYaw(yaw);
            store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(pos, look));
            LOGGER.at(Level.INFO).log("Lit de tente : lit en %s, téléportation vers %.2f, %.2f, %.2f (lacet %.2f)",
                    bedText, pos.x, pos.y, pos.z, yaw);
            PENDING.put(ref, new Pending(world, bedPos, pos, yaw, System.currentTimeMillis() + SECOND_TELEPORT_MS,
                    System.currentTimeMillis()));
            patchRespawnPoints(store, ref, world);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Lit de tente : sortie non traitée");
        }
    }

    /**
     * Second teleport, sent shortly after the first one: the client may still be handling the end of the mount when the first
     * teleport arrives, then puts the player back next to the bed. Called on every tick of each world. Never throws.
     */
    static void tick(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Ref<EntityStore>, Pending> entry : PENDING.entrySet()) {
                Ref<EntityStore> ref = entry.getKey();
                Pending p = entry.getValue();
                if (!ref.isValid() || now - p.created > STALE_MS) {
                    PENDING.remove(ref, p);
                    continue;
                }
                if (p.world != world || now < p.due) {
                    continue;
                }
                PENDING.remove(ref, p);
                world.execute(() -> secondTeleport(store, ref, p));
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Lit de tente : seconde téléportation non traitée");
        }
    }

    private static void secondTeleport(Store<EntityStore> store, Ref<EntityStore> ref, Pending p) {
        try {
            if (!ref.isValid()) {
                LOGGER.at(Level.INFO).log("Lit de tente : seconde téléportation abandonnée : joueur invalide");
                return;
            }
            if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                LOGGER.at(Level.INFO).log("Lit de tente : seconde téléportation abandonnée : joueur mort");
                return;
            }
            if (store.getComponent(ref, MountedComponent.getComponentType()) != null) {
                LOGGER.at(Level.INFO).log("Lit de tente : seconde téléportation abandonnée : joueur remonté");
                return;
            }
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null) {
                LOGGER.at(Level.INFO).log("Lit de tente : seconde téléportation abandonnée : pas de position");
                return;
            }
            Vector3d cur = t.getPosition();
            double dx = cur.x - (p.bed.x + 0.5);
            double dy = cur.y - p.bed.y;
            double dz = cur.z - (p.bed.z + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > MAX_BED_DISTANCE) {
                LOGGER.at(Level.INFO).log("Lit de tente : seconde téléportation abandonnée : joueur à %.2f bloc(s) du lit", dist);
                return;
            }
            Rotation3f look = new Rotation3f();
            look.setYaw(p.yaw);
            store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(p.target, look));
            LOGGER.at(Level.INFO).log("Lit de tente : seconde téléportation envoyée vers %.2f, %.2f, %.2f (joueur à %.2f bloc(s) du lit)",
                    p.target.x, p.target.y, p.target.z, dist);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Lit de tente : seconde téléportation non traitée");
        }
    }

    /**
     * Moves the player's respawn points that sit on a bed of a tent with an exit spot to that spot (the game computes the point
     * once, at the bed's hitbox centre, and reads it as is at respawn: it ends up outside the tent). Cheap: only the player's
     * points of this world are looked at. Never throws. Called on bed mount, on getting up and on death.
     */
    static void patchRespawnPoints(Store<EntityStore> store, Ref<EntityStore> ref, World world) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            PlayerWorldData data = player.getPlayerConfigData().getPerWorldData(world.getName());
            PlayerRespawnPointData[] points = data != null ? data.getRespawnPoints() : null;
            if (points == null || points.length == 0) {
                return;
            }
            boolean changed = false;
            PlayerRespawnPointData[] result = points.clone();
            for (int i = 0; i < result.length; i++) {
                PlayerRespawnPointData p = result[i];
                if (p == null || p.getBlockPosition() == null) {
                    continue;
                }
                BalloonRegistry.Entry e = tentOfBed(world, p.getBlockPosition());
                if (e == null) {
                    continue;
                }
                Vector3d target = exitPosition(e, Deployables.get(e.kind()).tent);
                Vector3d current = p.getRespawnPosition();
                if (current != null && current.distanceSquared(target) < 1.0e-4) {
                    continue;
                }
                result[i] = new PlayerRespawnPointData(p.getBlockPosition(), target, p.getName());
                changed = true;
            }
            if (changed) {
                data.setRespawnPoints(result);
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Lit de tente : point de réapparition non corrigé");
        }
    }
}
