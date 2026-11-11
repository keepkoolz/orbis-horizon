package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.interface_.BlockChange;
import com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange;
import com.hypixel.hytale.protocol.packets.interface_.FluidChange;
import com.hypixel.hytale.protocol.packets.player.HideTriggerVolumePastePrefabPreview;
import com.hypixel.hytale.protocol.packets.player.ShowTriggerVolumePastePrefabPreview;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * while a player holds a deployment crate of the mod, shows them (only them) where and how the structure would be placed,
 * with the builder tools' paste preview (ShowTriggerVolumePastePrefabPreview, built from the prefab's BlockSelection like the game).
 * Called by the world tick, every PERIOD_MS per player; the packet is only resent when the origin or rotation changed. Hidden when the
 * held item is no longer a crate (also after a deployment: the crate is consumed or swapped), on death, world change, disconnection.
 */
final class HeldCratePreview {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Reach of the block targeting: the client's block interaction range is not exposed server side, 6 blocks (to confirm in game). */
    static final double REACH = 6.0;
    private static final long PERIOD_MS = 150;

    /** What a player currently sees. */
    private static final class State {
        Ref<EntityStore> ref;
        String itemId;
        String worldName;
        Vector3i origin;
        Rotation rotation;
        int deck = -1;
        long nextCheckMs;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** Rotated prefab encodings, per prefab and rotation (a 2,000-block selection is not recomputed every tick). */
    private static final Map<String, EditorBlocksChange> CACHE = new ConcurrentHashMap<>();
    /** Boat plan previews with the shuttle merged in, per item, rotation and wood. */
    private static final Map<String, EditorBlocksChange> MERGED = new ConcurrentHashMap<>();

    private HeldCratePreview() {
    }

    /** Tick of one world: updates the preview of each player of that world. Never throws. */
    static void tick(Store<EntityStore> store, World world) {
        try {
            long now = System.currentTimeMillis();
            for (PlayerRef pr : world.getPlayerRefs()) {
                try {
                    update(store, world, pr, now);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Held crate preview not updated");
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Held crate preview: tick failed");
        }
    }

    private static void update(Store<EntityStore> store, World world, PlayerRef pr, long now) {
        UUID id = pr.getUuid();
        State st = STATES.get(id);
        Ref<EntityStore> current = pr.getReference();
        if (st != null && current != null && !current.equals(st.ref)) {
            // Stale state of a previous connection (a tick can recreate it right after the disconnection): start again.
            STATES.remove(id);
            st = null;
        }
        if (st != null && now < st.nextCheckMs) {
            return;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        // The player left the world where the preview was shown: hide it, then start again here if still holding a crate.
        if (st != null && !world.getName().equals(st.worldName)) {
            hide(pr);
            STATES.remove(id);
            st = null;
        }
        if (QuayPlanPage.BOAT_ARRIVAL.equals(heldItemId(store, ref)) && store.getComponent(ref, DeathComponent.getComponentType()) == null) {
            updateArrival(store, world, pr, ref, id, st, now);
            return;
        }
        DeployPlacement.Crate crate = heldCrate(store, ref);
        if (crate == null || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            if (st != null) {
                hide(pr);
                STATES.remove(id);
            }
            return;
        }
        if (CampTiers.pitchedOnlyCrouching(heldItemId(store, ref)) && !crouching(store, ref)) {
            // A camping pack is pitched only while crouching: no preview while standing (or without movement states).
            if (st != null) {
                hide(pr);
                STATES.remove(id);
            }
            return;
        }
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null) {
            return;
        }
        Vector3i target = TargetUtil.getTargetBlock(ref, REACH, store);
        DeployPlacement.Placement p = DeployPlacement.compute(target, t.getPosition(), t.getRotation().y, crate.offset(), crate.rotationOffset());
        String itemId = heldItemId(store, ref);
        Deployables.Kind crateBoat = Deployables.forPrefab(crate.prefabPath());
        if (DeployBalloonInteraction.isCrateBoat(crateBoat)) {
            // Same origin as the real placement: y follows the water surface; no water column, no preview.
            Vector3i playerCell = Vector3dUtil.toVector3i(t.getPosition());
            int push = DeployPlacement.boatPush(target != null ? target : playerCell, playerCell, p.facing());
            Vector3i boat = DeployPlacement.steamboatOrigin(DeployPlacement.worldState(world), DeployPlacement.forward(target, p.facing(), push),
                    DeployPlacement.forward(p.origin(), p.facing(), push), crateBoat.airship.waterlineY);
            if (boat == null) {
                if (st != null) {
                    hide(pr);
                    STATES.remove(id);
                }
                return;
            }
            p = new DeployPlacement.Placement(boat, p.facing(), p.rotation());
        }
        int deck = -1; // rotation index of the deck slabs of the pier end plan, -1 for the other plans
        boolean padPlan = itemId.equals(QuayPlanPage.BALLOON_PAD);
        boolean boatPlan = itemId.equals(QuayPlanPage.BOAT_PIER) || itemId.equals(QuayPlanPage.BOAT_FULL);
        if (padPlan) {
            p = new DeployPlacement.Placement(p.origin(), p.facing(), Rotation.None); // the pad is placed without a turn
        } else if (boatPlan) {
            // Same origin as the real placement: y follows the water surface under the pier end; no water, no preview.
            DeployPlacement.CellState cells = DeployPlacement.worldState(world);
            Vector3i boat;
            if (itemId.equals(QuayPlanPage.BOAT_PIER)) {
                // Same rotation as the real placement: the player's pier around the aimed block, the look breaks ties.
                if (target != null) {
                    p = new DeployPlacement.Placement(p.origin(), p.facing(), DeployPlacement.pierPlanRotation(cells, p.origin(), target, p.rotation()));
                }
                boat = DeployPlacement.pierPlanSeaOrigin(cells, p.origin(), p.rotation());
                deck = target != null ? DeployPlacement.pierDeckRotation(world, target) : DeployPlacement.SLAB_UNTURNED;
            } else {
                boat = DeployPlacement.boatStationOrigin(cells, p.origin(), p.rotation(), Deployables.BOAT_STATION_PIER_END);
            }
            if (boat == null) {
                if (st != null) {
                    hide(pr);
                    STATES.remove(id);
                }
                return;
            }
            p = new DeployPlacement.Placement(boat, p.facing(), p.rotation());
        }
        if (st == null) {
            st = new State();
            st.ref = ref;
            STATES.put(id, st);
        }
        st.nextCheckMs = now + PERIOD_MS;
        if (itemId.equals(st.itemId) && p.origin().equals(st.origin) && p.rotation() == st.rotation && deck == st.deck) {
            return;
        }
        BiomeWood.Wood wood = null;
        BiomeStone.Stone stone = null;
        boolean pad = padPlan;
        boolean balloonStation = itemId.equals(QuayPlanPage.BALLOON_FULL);
        if (boatPlan) {
            // Read only when the origin, rotation or item changed (this point is only reached then).
            wood = BiomeWood.woodAt(world, p.origin().x, p.origin().z, false);
        } else if (balloonStation) {
            wood = BiomeWood.stationWoodAt(world, p.origin().x, p.origin().z, false);
            stone = BiomeStone.stoneAt(world, p.origin().x, p.origin().z, false);
        } else if (pad) {
            stone = BiomeStone.stoneAt(world, p.origin().x, p.origin().z, false);
        }
        EditorBlocksChange blocks = encoded(crate.prefabPath(), p.rotation(), wood, stone, balloonStation || pad, deck);
        if (blocks == null) {
            return;
        }
        if (boatPlan) {
            // The public shuttle at its berth, so the player sees on which side it arrives. Never fatal for the preview.
            int[] end = itemId.equals(QuayPlanPage.BOAT_FULL) ? Deployables.BOAT_STATION_PIER_END : new int[]{0, 0, 0};
            blocks = withShuttle(blocks, itemId + "#" + p.rotation().getDegrees() + "#d" + deck + (wood != null && !wood.equals(BiomeWood.DEFAULT) ? "#" + wood.species() : ""),
                    p.rotation(), end);
        }
        BlockChange[] bc = blocks.blocksChange != null ? blocks.blocksChange : new BlockChange[0];
        FluidChange[] fc = blocks.fluidsChange != null ? blocks.fluidsChange : new FluidChange[0];
        pr.getPacketHandler().writeNoCache(new ShowTriggerVolumePastePrefabPreview(
                new Vector3f(p.origin().x, p.origin().y, p.origin().z), bc, fc, new ClipboardEntityChange[0], 0, 0));
        st.itemId = itemId;
        st.worldName = world.getName();
        st.origin = new Vector3i(p.origin());
        st.rotation = p.rotation();
        st.deck = deck;
    }

    /**
     * Preview of the arrival point plan: a single ghost lantern on the cell above the aimed block (the cell PlaceArrivalInteraction
     * sets), nothing without an aimed block. Resent only when the cell changes.
     */
    private static void updateArrival(Store<EntityStore> store, World world, PlayerRef pr, Ref<EntityStore> ref, UUID id, State st, long now) {
        Vector3i target = TargetUtil.getTargetBlock(ref, REACH, store);
        if (target == null) {
            if (st != null) {
                hide(pr);
                STATES.remove(id);
            }
            return;
        }
        Vector3i cell = PlaceArrivalInteraction.cellAbove(target);
        if (st == null) {
            st = new State();
            st.ref = ref;
            STATES.put(id, st);
        }
        st.nextCheckMs = now + PERIOD_MS;
        if (QuayPlanPage.BOAT_ARRIVAL.equals(st.itemId) && cell.equals(st.origin)) {
            return;
        }
        int index = BlockType.getAssetMap().getIndex("Deco_Lantern");
        if (index < 0) {
            return;
        }
        BlockChange[] ghost = {new BlockChange(0, 0, 0, index, (byte) 0)};
        pr.getPacketHandler().writeNoCache(new ShowTriggerVolumePastePrefabPreview(
                new Vector3f(cell.x, cell.y, cell.z), ghost, new FluidChange[0], new ClipboardEntityChange[0], 0, 0));
        st.itemId = QuayPlanPage.BOAT_ARRIVAL;
        st.worldName = world.getName();
        st.origin = cell;
        st.rotation = Rotation.None;
        st.deck = -1;
    }

    private static boolean crouching(Store<EntityStore> store, Ref<EntityStore> ref) {
        MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
        return msc != null && msc.getMovementStates() != null && msc.getMovementStates().crouching;
    }

    private static String heldItemId(Store<EntityStore> store, Ref<EntityStore> ref) {
        ItemStack held = heldItem(store, ref);
        return held == null ? "" : held.getItemId();
    }

    private static ItemStack heldItem(Store<EntityStore> store, Ref<EntityStore> ref) {
        // 0.7: the hotbar is its own component (Player.getInventory() is deprecated for removal).
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return hotbar == null ? null : hotbar.getActiveItem();
    }

    private static DeployPlacement.Crate heldCrate(Store<EntityStore> store, Ref<EntityStore> ref) {
        ItemStack held = heldItem(store, ref);
        return held == null ? null : DeployPlacement.CRATES.get(held.getItemId());
    }

    /**
     * The prefab turned like PrefabUtil.paste turns it (BlockSelection.rotate(Y, degrees) and Rotation use the same convention,
     * checked on a block: Ninety sends (x, z) to (z, -x) in both), in its network form. Null if the prefab is unreadable.
     */
    private static EditorBlocksChange encoded(String prefabPath, Rotation rotation, BiomeWood.Wood wood, BiomeStone.Stone stone,
                                              boolean balloonStation) {
        return encoded(prefabPath, rotation, wood, stone, balloonStation, -1);
    }

    /** As above, with the rotation index of the deck slabs of the pier end plan (the pasted slabs would turn with the prefab), -1 to leave the blocks as the turn gives them. */
    private static EditorBlocksChange encoded(String prefabPath, Rotation rotation, BiomeWood.Wood wood, BiomeStone.Stone stone,
                                              boolean balloonStation, int deckRotation) {
        boolean customWood = wood != null && (balloonStation || !wood.equals(BiomeWood.DEFAULT));
        boolean customStone = stone != null && !stone.equals(BiomeStone.DEFAULT);
        boolean remap = customWood || customStone;
        String key = prefabPath + "#" + rotation.getDegrees() + (customWood ? "#" + wood.species() : "")
                + (customStone ? "#" + stone.family() : "") + (deckRotation >= 0 ? "#d" + deckRotation : "");
        java.util.function.UnaryOperator<String> rename = balloonStation
                ? n -> BiomeStone.remap(BiomeWood.remapStation(n, wood), stone)
                : n -> BiomeWood.remap(n, wood);
        EditorBlocksChange cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Path path = PrefabStore.get().findAssetPrefabPath(prefabPath);
            BlockSelection sel = path == null ? null : PrefabStore.get().getPrefab(path);
            if (sel == null) {
                LOGGER.at(Level.WARNING).log("Preview: prefab not found: %s", prefabPath);
                return null;
            }
            sel = sel.cloneSelection();
            if (rotation.getDegrees() != 0) {
                sel = sel.rotate(Axis.Y, rotation.getDegrees());
            }
            EditorBlocksChange pkt = sel.toPacket();
            if (remap) {
                pkt = remapped(pkt, rename);
            }
            if (deckRotation >= 0) {
                pkt = deckTurned(pkt, deckRotation);
            }
            CACHE.put(key, pkt);
            return pkt;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Preview: prefab %s not encoded", prefabPath);
            return null;
        }
    }

    /**
     * The preview packet plus the blocks of the public shuttle at its berth. The shuttle origin relative to the preview origin is the
     * pier end plus (0, -1, 3), all turned by the quay rotation (StationGeometry.berthOrigin from the origin), its own rotation is
     * StationGeometry.berthRotation. Cached per item, rotation and wood. Returns the packet unchanged when the shuttle is unavailable.
     */
    static EditorBlocksChange withShuttle(EditorBlocksChange base, String key, Rotation quayRotation, int[] pierEnd) {
        try {
            EditorBlocksChange cached = MERGED.get(key);
            if (cached != null) {
                return cached;
            }
            Deployables.Kind shuttle = Deployables.STEAMBOAT_PUBLIC;
            EditorBlocksChange boat = encoded(shuttle.prefabPath, StationGeometry.berthRotation(quayRotation, false), null, null, false);
            if (boat == null || boat.blocksChange == null) {
                return base;
            }
            Vector3i end = quayRotation.rotateYaw(new Vector3i(pierEnd[0], pierEnd[1], pierEnd[2]), new Vector3i());
            Vector3i at = StationGeometry.berthOrigin(end, quayRotation);
            EditorBlocksChange merged = merge(base, boat, at.x, at.y, at.z);
            MERGED.put(key, merged);
            return merged;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Preview: shuttle not added");
            return base;
        }
    }

    /** New packet with the blocks of extra shifted by (dx, dy, dz) appended to base (neither source is modified). */
    static EditorBlocksChange merge(EditorBlocksChange base, EditorBlocksChange extra, int dx, int dy, int dz) {
        BlockChange[] a = base.blocksChange != null ? base.blocksChange : new BlockChange[0];
        BlockChange[] b = extra.blocksChange != null ? extra.blocksChange : new BlockChange[0];
        BlockChange[] out = new BlockChange[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        for (int i = 0; i < b.length; i++) {
            BlockChange c = new BlockChange(b[i]);
            c.x += dx;
            c.y += dy;
            c.z += dz;
            out[a.length + i] = c;
        }
        EditorBlocksChange copy = new EditorBlocksChange(base);
        copy.blocksChange = out;
        return copy;
    }

    /** Copy of the packet whose deck slabs (blocks at height 0 named *_Planks_Half) have the given rotation index. */
    static EditorBlocksChange deckTurned(EditorBlocksChange src, int deckRotation) {
        if (src.blocksChange == null) {
            return src;
        }
        Map<Integer, Boolean> slabs = new java.util.HashMap<>();
        BlockChange[] out = new BlockChange[src.blocksChange.length];
        for (int i = 0; i < out.length; i++) {
            BlockChange b = new BlockChange(src.blocksChange[i]);
            if (b.y == 0 && slabs.computeIfAbsent(b.block, id -> {
                BlockType type = BlockType.getAssetMap().getAsset(id);
                return type != null && DeployPlacement.isPierDeckSlab(type.getId());
            })) {
                b.rotation = (byte) deckRotation;
            }
            out[i] = b;
        }
        EditorBlocksChange copy = new EditorBlocksChange(src);
        copy.blocksChange = out;
        return copy;
    }

    /** Copy of the packet whose block ids are renamed by the biome wood and zone stone (the cached unmapped packet is left untouched). */
    private static EditorBlocksChange remapped(EditorBlocksChange src, java.util.function.UnaryOperator<String> rename) {
        if (src.blocksChange == null) {
            return src;
        }
        Map<Integer, Integer> ids = new java.util.HashMap<>();
        BlockChange[] out = new BlockChange[src.blocksChange.length];
        for (int i = 0; i < out.length; i++) {
            BlockChange b = new BlockChange(src.blocksChange[i]);
            b.block = ids.computeIfAbsent(b.block, id -> {
                BlockType type =
                        BlockType.getAssetMap().getAsset(id);
                if (type == null) {
                    return id;
                }
                String name = rename.apply(type.getId());
                int renamed = name.equals(type.getId()) ? id
                        : BlockType.getAssetMap().getIndex(name);
                return renamed < 0 ? id : renamed;
            });
            out[i] = b;
        }
        EditorBlocksChange copy = new EditorBlocksChange(src);
        copy.blocksChange = out;
        return copy;
    }

    private static void hide(PlayerRef pr) {
        try {
            pr.getPacketHandler().writeNoCache(new HideTriggerVolumePastePrefabPreview());
        } catch (RuntimeException e) {
            LOGGER.at(Level.FINE).withCause(e).log("Preview not hidden");
        }
    }

    /** Disconnection: nothing to hide (the client is gone), the state is forgotten. */
    static void onPlayerGone(UUID id) {
        STATES.remove(id);
    }

    /** Forgets what the player was shown (command clear): the next tick shows it again if they still hold a crate. */
    static void forget(UUID id) {
        STATES.remove(id);
    }
}
