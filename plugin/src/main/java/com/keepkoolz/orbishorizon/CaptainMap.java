package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * T141: the captain's map. It is given to the captain at the departure of a voyage with the pilot NPC. Selecting it starts a
 * 3-2-1 countdown that stops the voyage, changing item cancels it. The map is removed at every voyage end and on every player
 * arrival in a world (a crash cannot remove it from an offline player). The item is not placeable and has no interaction.
 */
final class CaptainMap {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String ITEM_ID = "Airship_Captain_Map";
    static final String STOP_KEY = "crew.voyage.stopping";
    /** Time with the map in hand before the countdown shows. */
    static final long WAIT_MS = 500;
    static final int COUNTDOWN_S = 3;
    static final long STEP_MS = 1000;

    /** A holder of a map must lose it unless they are the captain of an active voyage. */
    static boolean mustLoseMap(boolean holdsMap, UUID player, java.util.Set<UUID> activeCaptains) {
        return holdsMap && !activeCaptains.contains(player);
    }

    /** A selection waiting for its countdown. */
    private static final class Pending {
        final long selectedMs;
        final byte slot;
        int shown = -1;

        Pending(long selectedMs, byte slot) {
            this.selectedMs = selectedMs;
            this.slot = slot;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    /** Last active slot seen per captain, to detect a change (the first reading is a baseline). */
    private static final Map<UUID, Byte> LAST_SLOT = new ConcurrentHashMap<>();
    /** Voyages already stopped by the map (once per voyage). */
    private static final Map<UUID, Boolean> STOPPED = new ConcurrentHashMap<>();

    private CaptainMap() {
    }

    /** -1 while waiting, then 3, 2, 1 (STEP_MS each), then 0 at the moment of the stop. */
    static int phase(long selectedMs, long now) {
        long t = now - selectedMs - WAIT_MS;
        if (t < 0) {
            return -1;
        }
        long left = COUNTDOWN_S - t / STEP_MS;
        return left <= 0 ? 0 : (int) left;
    }

    static boolean isMap(ItemStack s) {
        return s != null && !s.isEmpty() && ITEM_ID.equals(s.getItemId());
    }

    /** Puts one map in a free hotbar slot other than the held one. Never throws. */
    static void give(Store<EntityStore> store, Ref<EntityStore> captain) {
        try {
            InventoryComponent.Hotbar hotbar = store.getComponent(captain, InventoryComponent.Hotbar.getComponentType());
            if (hotbar == null) {
                return;
            }
            int active = hotbar.getActiveSlot();
            ItemContainer inv = hotbar.getInventory();
            for (int i = 0; i < inv.getCapacity(); i++) {
                if (i == active) {
                    continue;
                }
                ItemStack st = inv.getItemStack((short) i);
                if (st == null || st.isEmpty()) {
                    inv.setItemStackForSlot((short) i, new ItemStack(ITEM_ID, 1), false);
                    return;
                }
            }
            LOGGER.at(Level.WARNING).log("Voyage : aucun emplacement libre pour la carte du capitaine");
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : carte du capitaine non donnée");
        }
    }

    /** Removes every map from the combined inventory of an online player. Never throws. */
    static void removeAll(Store<EntityStore> store, Ref<EntityStore> player) {
        try {
            if (player == null || !player.isValid()) {
                return;
            }
            for (var type : InventoryComponent.HOTBAR_STORAGE_BACKPACK) {
                InventoryComponent c = store.getComponent(player, type);
                if (c != null) {
                    removeFrom(c.getInventory());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : carte du capitaine non retirée");
        }
    }

    /** Same for a player who arrives in a world (the inventory is still on the holder). Never throws. */
    static void removeAll(Holder<EntityStore> holder) {
        try {
            if (holder == null) {
                return;
            }
            for (var type : InventoryComponent.HOTBAR_STORAGE_BACKPACK) {
                InventoryComponent c = holder.getComponent(type);
                if (c != null) {
                    removeFrom(c.getInventory());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : carte du capitaine non retirée à l'arrivée");
        }
    }

    private static void removeFrom(ItemContainer inv) {
        if (inv == null) {
            return;
        }
        for (short i = 0; i < inv.getCapacity(); i++) {
            if (isMap(inv.getItemStack(i))) {
                inv.removeItemStackFromSlot(i);
            }
        }
    }

    /** Forgets the state of a voyage (call when it starts and when it ends). */
    static void forget(UUID captain) {
        if (captain != null) {
            PENDING.remove(captain);
            LAST_SLOT.remove(captain);
            STOPPED.remove(captain);
            NEXT_SWEEP.remove(captain);
        }
    }

    /** Every tick of a world: countdown of the maps selected by the captains of this world's voyages. Never throws. */
    static void tick(Store<EntityStore> store, World world) {
        try {
            sweepHolders(store, world);
            for (AirshipFlight f : AirshipManager.get().flights()) {
                if (f.world != world || f.voyage == null) {
                    continue;
                }
                try {
                    tickVoyage(store, f);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Voyage : carte du capitaine en échec");
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : carte du capitaine en échec");
        }
    }

    /** Ground sweep period and box half sizes around the ship (a dropped map is deleted). */
    private static final long SWEEP_MS = 1000;
    private static final double SWEEP_XZ = 40;
    private static final double SWEEP_Y = 30;
    private static final Map<UUID, Long> NEXT_SWEEP = new ConcurrentHashMap<>();

    /** A map dropped on the ground near the ship is deleted, at most once a second per voyage. */
    private static void sweepGround(Store<EntityStore> store, AirshipFlight f) {
        long now = System.currentTimeMillis();
        Long next = NEXT_SWEEP.get(f.voyage.captain);
        if (next != null && now < next) {
            return;
        }
        NEXT_SWEEP.put(f.voyage.captain, now + SWEEP_MS);
        sweepAround(store, f.pos);
    }

    /** Deletes the maps on the ground around a point (one last time after a voyage ends). Never throws. */
    static void sweepAround(Store<EntityStore> store, Vector3d pos) {
        try {
            sweepBox(store, pos);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : balayage des cartes au sol en échec");
        }
    }

    private static void sweepBox(Store<EntityStore> store, Vector3d pos) {
        Vector3d min = new Vector3d(pos.x - SWEEP_XZ, pos.y - SWEEP_Y, pos.z - SWEEP_XZ);
        Vector3d max = new Vector3d(pos.x + SWEEP_XZ, pos.y + SWEEP_Y, pos.z + SWEEP_XZ);
        for (Ref<EntityStore> e : new ArrayList<>(TargetUtil.getAllEntitiesInBox(min, max, store))) {
            ItemComponent item = store.getComponent(e, ItemComponent.getComponentType());
            if (item != null && isMap(item.getItemStack())) {
                store.removeEntity(e, RemoveReason.REMOVE);
                LOGGER.at(Level.INFO).log("Voyage : carte du capitaine lâchée au sol, supprimée");
            }
        }
    }

    private static final Map<World, Long> NEXT_HOLDER_SWEEP = new ConcurrentHashMap<>();

    /** Once a second per world: an online player who holds a map without being the captain of an active voyage loses it. */
    private static void sweepHolders(Store<EntityStore> store, World world) {
        long now = System.currentTimeMillis();
        Long next = NEXT_HOLDER_SWEEP.get(world);
        if (next != null && now < next) {
            return;
        }
        NEXT_HOLDER_SWEEP.put(world, now + SWEEP_MS);
        java.util.Set<UUID> active = new java.util.HashSet<>();
        for (AirshipFlight f : AirshipManager.get().flights()) {
            if (f.voyage != null) {
                active.add(f.voyage.captain);
            }
        }
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid() || active.contains(pr.getUuid())) {
                continue;
            }
            for (var type : InventoryComponent.HOTBAR_STORAGE_BACKPACK) {
                InventoryComponent c = store.getComponent(ref, type);
                if (c == null) {
                    continue;
                }
                ItemContainer inv = c.getInventory();
                boolean holds = false;
                for (short i = 0; inv != null && i < inv.getCapacity(); i++) {
                    holds |= isMap(inv.getItemStack(i));
                }
                if (mustLoseMap(holds, pr.getUuid(), active)) {
                    removeFrom(inv);
                }
            }
        }
    }

    private static void tickVoyage(Store<EntityStore> store, AirshipFlight f) {
        sweepGround(store, f);
        UUID captain = f.voyage.captain;
        Ref<EntityStore> ref = null;
        for (AirshipPassengers.Rider r : f.riders) {
            if (r.p.uuid.equals(captain)) {
                ref = r.p.ref;
            }
        }
        if (ref == null || !ref.isValid()) {
            return;
        }
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return;
        }
        // A map dropped on the ground is deleted: the item is gone from the inventory, nothing else to do on the stack.
        byte slot = hotbar.getActiveSlot();
        Byte last = LAST_SLOT.put(captain, slot);
        boolean onMap = isMap(hotbar.getInventory().getItemStack((short) slot));
        Pending pending = PENDING.get(captain);
        if (last != null && last != slot) {
            if (onMap) {
                PENDING.put(captain, new Pending(System.currentTimeMillis(), slot));
                return;
            }
            if (pending != null) {
                PENDING.remove(captain);
                if (pending.shown != -1 && pr != null) {
                    cancelled(pr, f.crew);
                }
                return;
            }
        }
        if (pending == null) {
            return;
        }
        if (!onMap || pending.slot != slot) {
            PENDING.remove(captain);
            if (pending.shown != -1 && pr != null) {
                cancelled(pr, f.crew);
            }
            return;
        }
        int phase = phase(pending.selectedMs, System.currentTimeMillis());
        if (phase >= 1 && phase != pending.shown) {
            pending.shown = phase;
            if (pr != null) {
                EventTitleUtil.showEventTitleToPlayer(pr, Texts.t(phase == COUNTDOWN_S ? "crew.voyage.countdown" : "crew.voyage.countdownNumber").param("n", phase),
                        Texts.t("crew.voyage.countdownHint"), EventTitleStyle.Major, EventTitleUtil.DEFAULT_ZONE,
                        phase == COUNTDOWN_S ? GliderJump.FIRST_TITLE_DURATION_S : GliderJump.NEXT_TITLE_DURATION_S,
                        phase == COUNTDOWN_S ? GliderJump.FIRST_TITLE_FADE_IN_S : GliderJump.TITLE_FADE_NONE_S,
                        GliderJump.TITLE_FADE_NONE_S);
            }
        } else if (phase == 0) {
            PENDING.remove(captain);
            if (pr != null) {
                hideTitle(pr, GliderJump.JUMP_HIDE_FADE_S);
            }
            if (STOPPED.putIfAbsent(captain, Boolean.TRUE) == null) {
                LOGGER.at(Level.INFO).log("Voyage : arrêt demandé par la carte du capitaine");
                AirshipAutopilot.stop(f, STOP_KEY);
            }
        }
    }

    private static void cancelled(PlayerRef pr, CrewRecord crew) {
        pr.sendMessage(Texts.pilot("crew.voyage.stopCancelled", crew));
        hideTitle(pr, GliderJump.CANCEL_HIDE_FADE_S);
    }

    private static void hideTitle(PlayerRef pr, float fade) {
        try {
            EventTitleUtil.hideEventTitleFromPlayer(pr, fade);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Voyage : titre non masqué");
        }
    }
}
