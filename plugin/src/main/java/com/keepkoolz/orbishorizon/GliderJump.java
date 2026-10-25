package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;

import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Glider jump from a vehicle in flight: a passenger who takes a glider in hand leaves the vehicle after a countdown.
 * This part detects the selection and records it; the countdown and the jump come later.
 */
final class GliderJump {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Time with the glider in hand before the countdown shows (a mouse wheel scroll shows nothing). */
    static final long WAIT_MS = 1000;
    static final int COUNTDOWN_S = 5;
    /** Time each number of the countdown stays on screen. */
    static final long STEP_MS = 1500;
    /** Title timing in seconds: each title lasts exactly one step (1.5 s), with a fade only at the start and at the end. */
    static final float FIRST_TITLE_DURATION_S = 1.3f;
    static final float FIRST_TITLE_FADE_IN_S = 0.2f;
    static final float NEXT_TITLE_DURATION_S = 1.5f;
    static final float TITLE_FADE_NONE_S = 0f;
    static final float CANCEL_HIDE_FADE_S = 0.1f;
    static final float JUMP_HIDE_FADE_S = 0.3f;

    /** A selection waiting for its countdown. */
    static final class Pending {
        final Ref<EntityStore> ref;
        final World world;
        final byte slot;
        final long selectedMs;
        /** Last second shown, -1 before the countdown. */
        int shown = -1;

        Pending(Ref<EntityStore> ref, World world, byte slot, long selectedMs) {
            this.ref = ref;
            this.world = world;
            this.slot = slot;
            this.selectedMs = selectedMs;
        }
    }

    static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private GliderJump() {
    }

    /** -1 while waiting, then the number to show (5 down to 1, STEP_MS each), then 0 at the moment of the jump. */
    static int phase(long selectedMs, long now) {
        long t = now - selectedMs - WAIT_MS;
        if (t < 0) {
            return -1;
        }
        long left = COUNTDOWN_S - t / STEP_MS;
        return left <= 0 ? 0 : (int) left;
    }

    /** Outcome of an ejection attempt. */
    enum Result { DONE, TOO_LOW, FAILED }

    /** A glider counts if the item has the Glider field and is not broken. */
    static boolean isUsableGlider(ItemStack stack) {
        return stack != null && !stack.isEmpty() && !stack.isBroken()
                && stack.getItem() != null && stack.getItem().getGlider() != null;
    }

    /** Last active hotbar slot seen per passenger: the baseline is taken when first seen (boarding triggers nothing). */
    private static final Map<UUID, Byte> LAST_SLOT = new ConcurrentHashMap<>();

    /**
     * Called every tick of a world. The hotbar changes go through ChangeActiveSlotInteraction, which fires no event, so
     * the active slot of every passenger of the world's flights is polled.
     */
    static void tick(Store<EntityStore> store, World world) {
        Set<UUID> aboard = new HashSet<>(); // all worlds: LAST_SLOT is shared
        for (BalloonFlight f : BalloonManager.get().flights()) {
            for (BalloonFlight.Passenger p : f.passengers) {
                aboard.add(p.uuid);
                if (f.world == world) {
                    poll(store, p.ref, p.uuid);
                }
            }
        }
        for (AirshipFlight f : AirshipManager.get().flights()) {
            if (!allowedFor(f.kind)) {
                continue; // not counted as aboard: a pending jump of such a rider is dropped by processPending
            }
            for (AirshipPassengers.Rider r : f.riders) {
                aboard.add(r.p.uuid);
                if (f.world == world) {
                    poll(store, r.p.ref, r.p.uuid);
                }
            }
        }
        // Players no longer aboard start from a fresh baseline when they board again.
        LAST_SLOT.keySet().removeIf(uuid -> !aboard.contains(uuid));
        processPending(store, world, aboard);
    }

    /** Glider jumps are not offered from every vehicle (decision of 21 October 2026: not from the steamboat for now). */
    static boolean allowedFor(Deployables.Kind kind) {
        return kind != Deployables.STEAMBOAT && kind != Deployables.STEAMBOAT_PUBLIC;
    }

    /** Countdown of the pending jumps of this world: cancels on every broken condition, shows the title, then jumps. */
    private static void processPending(Store<EntityStore> store, World world, Set<UUID> aboard) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
            UUID uuid = e.getKey();
            Pending pending = e.getValue();
            if (pending.ref == null || !pending.ref.isValid()) { // any world's tick drops it (a world may be gone)
                PENDING.remove(uuid, pending);
                continue;
            }
            if (pending.world != world) {
                continue;
            }
            try {
                PlayerRef pr = store.getComponent(pending.ref, PlayerRef.getComponentType());
                if (!aboard.contains(uuid)) { // landed, vehicle removed, or no longer a passenger
                    PENDING.remove(uuid, pending);
                    if (pending.shown != -1 && pr != null) {
                        cancelled(pr);
                    }
                    continue;
                }
                InventoryComponent.Hotbar hotbar = store.getComponent(pending.ref, InventoryComponent.Hotbar.getComponentType());
                boolean dead = store.getComponent(pending.ref, DeathComponent.getComponentType()) != null;
                boolean ok = !dead && hotbar != null && hotbar.getActiveSlot() == pending.slot
                        && isUsableGlider(hotbar.getInventory().getItemStack((short) pending.slot));
                if (!ok) {
                    PENDING.remove(uuid, pending);
                    if (pending.shown != -1 && pr != null) {
                        cancelled(pr);
                    }
                    continue;
                }
                int phase = phase(pending.selectedMs, now);
                if (phase >= 1 && phase != pending.shown) {
                    pending.shown = phase;
                    if (pr != null) {
                        EventTitleUtil.showEventTitleToPlayer(pr, Texts.t("glider.countdown").param("n", phase),
                                Texts.t("glider.countdownHint"), EventTitleStyle.Major, EventTitleUtil.DEFAULT_ZONE,
                                phase == COUNTDOWN_S ? FIRST_TITLE_DURATION_S : NEXT_TITLE_DURATION_S,
                                phase == COUNTDOWN_S ? FIRST_TITLE_FADE_IN_S : TITLE_FADE_NONE_S, TITLE_FADE_NONE_S);
                    }
                } else if (phase == 0) {
                    jump(store, pending, uuid);
                }
            } catch (RuntimeException ex) {
                PENDING.remove(uuid, pending);
                LOGGER.at(Level.WARNING).withCause(ex).log("Saut en glider : décompte en échec");
            }
        }
    }

    /** Point under the vehicle where the passenger leaves, in the prefab frame: below the lowest flight cell, over their seat. */
    static Vector3f exitLocal(Vector3f seatLocal, int lowestFlightY) {
        return new Vector3f(seatLocal.x, lowestFlightY - 2.5f, seatLocal.z);
    }

    /** Lowest y of the flight cells of a shape (the bottom of the vehicle in flight). */
    static int lowestFlightY(StructureShape shape) {
        int min = Integer.MAX_VALUE;
        for (StructureShape.Cell c : shape.flightCells()) {
            min = Math.min(min, c.y());
        }
        return min;
    }

    /** True if the exit point (feet cell or the one above) is in terrain, liquid or out of height: the jump is refused. */
    static boolean exitBlocked(World world, Vector3d exit) {
        SectionReader reader = new SectionReader(world.getChunkStore());
        int x = (int) Math.floor(exit.x);
        int y = (int) Math.floor(exit.y);
        int z = (int) Math.floor(exit.z);
        return BalloonManager.blocksBalloon(reader, x, y, z) || BalloonManager.blocksBalloon(reader, x, y + 1, z);
    }

    /** A shown countdown is cancelled: tells the player and hides the title. */
    private static void cancelled(PlayerRef pr) {
        pr.sendMessage(Texts.t("glider.cancelled"));
        hideTitle(pr, CANCEL_HIDE_FADE_S);
    }

    private static void hideTitle(PlayerRef pr, float fade) {
        try {
            EventTitleUtil.hideEventTitleFromPlayer(pr, fade);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Saut en glider : titre non masqué");
        }
    }

    /** The countdown ended: the passenger leaves the vehicle below it. The vehicle keeps flying whatever happens. */
    static void jump(Store<EntityStore> store, Pending pending, UUID uuid) {
        PENDING.remove(uuid, pending);
        Result result = null;
        for (BalloonFlight f : BalloonManager.get().flights()) {
            if (f.world != pending.world) {
                continue;
            }
            for (BalloonFlight.Passenger p : f.passengers) {
                if (p.uuid.equals(uuid)) {
                    result = BalloonManager.get().ejectPassenger(store, f, uuid);
                    break;
                }
            }
            if (result != null) {
                break;
            }
        }
        if (result == null) {
            for (AirshipFlight f : AirshipManager.get().flights()) {
                if (f.world != pending.world) {
                    continue;
                }
                for (AirshipPassengers.Rider r : f.riders) {
                    if (r.p.uuid.equals(uuid)) {
                        result = AirshipManager.get().ejectRider(store, f, uuid);
                        break;
                    }
                }
                if (result != null) {
                    break;
                }
            }
        }
        if (result == Result.DONE) {
            Telemetry.count("glider.jump");
        }
        PlayerRef pr = pending.ref.isValid() ? store.getComponent(pending.ref, PlayerRef.getComponentType()) : null;
        if (pr != null) {
            hideTitle(pr, result == Result.DONE ? JUMP_HIDE_FADE_S : CANCEL_HIDE_FADE_S);
            if (result == Result.DONE) {
                pr.sendMessage(Texts.t("glider.jumped"));
            } else if (result == Result.TOO_LOW) {
                pr.sendMessage(Texts.t("glider.tooLow"));
            } else if (pending.shown != -1) {
                pr.sendMessage(Texts.t("glider.cancelled"));
            }
        }
        LOGGER.at(Level.INFO).log("Saut en glider : %s, %s", pr == null ? uuid.toString() : pr.getUsername(),
                result == Result.DONE ? "éjecté" : result == Result.TOO_LOW ? "refusé (trop près du sol)" : "éjection impossible");
    }

    private static void poll(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid) {
        try {
            if (ref == null || !ref.isValid()) {
                return;
            }
            InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (hotbar == null) {
                return;
            }
            byte slot = hotbar.getActiveSlot();
            Byte last = LAST_SLOT.put(uuid, slot);
            if (last == null || last == slot) {
                return;
            }
            onSlotChange(store, ref, uuid, slot, hotbar.getInventory().getItemStack((short) slot));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Saut en glider : lecture de l'emplacement en échec");
        }
    }

    private static void onSlotChange(Store<EntityStore> store, Ref<EntityStore> ref, UUID uuid, byte slot, ItemStack stack) {
        if (!isUsableGlider(stack)) {
            Pending old = PENDING.remove(uuid);
            if (old != null && old.shown != -1) {
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr != null) {
                    cancelled(pr);
                }
            }
            return;
        }
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        PENDING.put(uuid, new Pending(ref, store.getExternalData().getWorld(), slot, System.currentTimeMillis()));
        LOGGER.at(Level.INFO).log("Saut en glider : %s a pris un glider en main (emplacement %d)",
                pr == null ? uuid.toString() : pr.getUsername(), slot + 1);
    }
}
