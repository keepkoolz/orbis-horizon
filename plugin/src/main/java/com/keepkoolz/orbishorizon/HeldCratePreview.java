package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
        long nextCheckMs;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** Rotated prefab encodings, per prefab and rotation (a 2,000-block selection is not recomputed every tick). */
    private static final Map<String, EditorBlocksChange> CACHE = new ConcurrentHashMap<>();

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
                    LOGGER.at(Level.WARNING).withCause(e).log("Aperçu de la caisse tenue non mis à jour");
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Aperçu des caisses tenues : tick en échec");
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
        DeployPlacement.Crate crate = heldCrate(store, ref);
        if (crate == null || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
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
        if (itemId.equals("Steamboat_Crate")) {
            // Same origin as the real placement: y follows the water surface; no water column, no preview.
            Vector3i boat = DeployPlacement.steamboatOrigin(DeployPlacement.worldState(world), target, p.origin());
            if (boat == null) {
                if (st != null) {
                    hide(pr);
                    STATES.remove(id);
                }
                return;
            }
            p = new DeployPlacement.Placement(boat, p.facing(), p.rotation());
        }
        if (itemId.equals("Boat_Station_Crate")) {
            // Same origin as the real placement: y follows the water surface under the pier end; no water, no preview.
            Vector3i station = DeployPlacement.boatStationOrigin(DeployPlacement.worldState(world), p.origin(), p.rotation());
            if (station == null) {
                if (st != null) {
                    hide(pr);
                    STATES.remove(id);
                }
                return;
            }
            p = new DeployPlacement.Placement(station, p.facing(), p.rotation());
        }
        if (st == null) {
            st = new State();
            st.ref = ref;
            STATES.put(id, st);
        }
        st.nextCheckMs = now + PERIOD_MS;
        if (itemId.equals(st.itemId) && p.origin().equals(st.origin) && p.rotation() == st.rotation) {
            return;
        }
        BiomeWood.Wood wood = null;
        BiomeStone.Stone stone = null;
        if (itemId.equals("Boat_Station_Crate")) {
            // Read only when the origin, rotation or item changed (this point is only reached then).
            wood = BiomeWood.woodAt(world, p.origin().x, p.origin().z, false);
        } else if (itemId.equals("Balloon_Station_Crate")) {
            wood = BiomeWood.stationWoodAt(world, p.origin().x, p.origin().z, false);
            stone = BiomeStone.stoneAt(world, p.origin().x, p.origin().z, false);
        }
        EditorBlocksChange blocks = encoded(crate.prefabPath(), p.rotation(), wood, stone, itemId.equals("Balloon_Station_Crate"));
        if (blocks == null) {
            return;
        }
        BlockChange[] bc = blocks.blocksChange != null ? blocks.blocksChange : new BlockChange[0];
        FluidChange[] fc = blocks.fluidsChange != null ? blocks.fluidsChange : new FluidChange[0];
        pr.getPacketHandler().writeNoCache(new ShowTriggerVolumePastePrefabPreview(
                new Vector3f(p.origin().x, p.origin().y, p.origin().z), bc, fc, new ClipboardEntityChange[0], 0, 0));
        st.itemId = itemId;
        st.worldName = world.getName();
        st.origin = new Vector3i(p.origin());
        st.rotation = p.rotation();
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
        boolean customWood = wood != null && (balloonStation || !wood.equals(BiomeWood.DEFAULT));
        boolean customStone = stone != null && !stone.equals(BiomeStone.DEFAULT);
        boolean remap = customWood || customStone;
        String key = prefabPath + "#" + rotation.getDegrees() + (customWood ? "#" + wood.species() : "")
                + (customStone ? "#" + stone.family() : "");
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
                LOGGER.at(Level.WARNING).log("Aperçu : prefab introuvable : %s", prefabPath);
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
            CACHE.put(key, pkt);
            return pkt;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Aperçu : prefab %s non encodé", prefabPath);
            return null;
        }
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
            LOGGER.at(Level.FINE).withCause(e).log("Aperçu non masqué");
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
