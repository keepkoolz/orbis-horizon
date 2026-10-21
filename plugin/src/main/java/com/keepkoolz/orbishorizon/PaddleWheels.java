package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Pure rule of the steamboat paddle wheels: which spin each wheel wants, the animation names, and when a
 * spin change may be applied (at the end of the current cycle, where the wheel looks like at frame 0).
 * The animation names and frame counts must stay equal to WHEEL_SPINS and WHEEL_STEP_DEG of scripts/prefab_to_model.py.
 * See docs/superpowers/notes/2026-10-20-essai-roue-aubes.md for the spike that settled these rules.
 */
final class PaddleWheels {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** A viewer who starts to see a wheel entity is sent the running animations this long after. */
    static final long VIEWER_DELAY_MS = 250;
    private static final long LOG_EVERY_MS = 10_000;
    /** Delay a wanted spin must stay stable before it is applied. */
    static final long HOLD_MS = 300;
    /** Half a server tick, tolerance around the cycle end. */
    static final long END_TOLERANCE_MS = 17;

    enum Spin {
        STOP(0), FWD_FAST(12), FWD_SLOW(24), FWD_VERY_SLOW(48), REV_FAST(12), REV_SLOW(24), REV_VERY_SLOW(48);

        private final int frames;

        Spin(int frames) {
            this.frames = frames;
        }

        int frames() {
            return frames;
        }

        long periodMs() {
            return frames == 0 ? 0 : Math.round(frames * 1000 / 60.0);
        }

        String suffix() {
            switch (this) {
                case FWD_FAST: return "FwdFast";
                case FWD_SLOW: return "FwdSlow";
                case FWD_VERY_SLOW: return "FwdVerySlow";
                case REV_FAST: return "RevFast";
                case REV_SLOW: return "RevSlow";
                case REV_VERY_SLOW: return "RevVerySlow";
                default: return null;
            }
        }
    }

    private PaddleWheels() {
    }

    /** Wanted spin of {left, right}. turnDir: 1 turning left, -1 turning right, 0 straight. */
    static Spin[] wheelsFor(boolean moving, boolean reversing, int turnDir) {
        if (!moving) {
            if (turnDir > 0) {
                return new Spin[]{Spin.REV_SLOW, Spin.FWD_SLOW};
            }
            if (turnDir < 0) {
                return new Spin[]{Spin.FWD_SLOW, Spin.REV_SLOW};
            }
            return new Spin[]{Spin.STOP, Spin.STOP};
        }
        if (reversing) {
            if (turnDir > 0) {
                return new Spin[]{Spin.REV_SLOW, Spin.REV_VERY_SLOW};
            }
            if (turnDir < 0) {
                return new Spin[]{Spin.REV_VERY_SLOW, Spin.REV_SLOW};
            }
            return new Spin[]{Spin.REV_SLOW, Spin.REV_SLOW};
        }
        if (turnDir > 0) {
            return new Spin[]{Spin.FWD_SLOW, Spin.FWD_FAST};
        }
        if (turnDir < 0) {
            return new Spin[]{Spin.FWD_FAST, Spin.FWD_SLOW};
        }
        return new Spin[]{Spin.FWD_FAST, Spin.FWD_FAST};
    }

    /** Global constraint name of the animation, null for STOP. */
    static String animationId(boolean right, Spin spin) {
        String suffix = spin.suffix();
        return suffix == null ? null : (right ? "Wheel_Right_" : "Wheel_Left_") + suffix;
    }

    static boolean atCycleEnd(long periodMs, long startMs, long nowMs, long tolMs) {
        if (periodMs == 0) {
            return true;
        }
        long remaining = periodMs - Math.floorMod(nowMs - startMs, periodMs);
        return remaining <= tolMs || remaining >= periodMs - tolMs;
    }

    static Spin nextSpin(Spin current, long currentStartMs, Spin wanted, long wantedSinceMs, long nowMs) {
        if (wanted == current) {
            return current;
        }
        if (nowMs - wantedSinceMs < HOLD_MS) {
            return current;
        }
        if (!atCycleEnd(current.periodMs(), currentStartMs, nowMs, END_TOLERANCE_MS)) {
            return current;
        }
        return wanted;
    }

    /** Per-flight wheel state: current spin of {left, right}, when it started, the wanted spin and since when. */
    static final class State {
        final Spin[] cur = {Spin.STOP, Spin.STOP};
        final long[] start = {0, 0};
        final Spin[] wanted = {Spin.STOP, Spin.STOP};
        final long[] wantedSince = {0, 0};
        /** Per tracked entity, the viewers and when each is due its replay (Long.MAX_VALUE: served). */
        final Map<Ref<EntityStore>, Map<Ref<EntityStore>, Long>> viewers = new HashMap<>();
        long lastLogMs;
    }

    private static AnimationSlot slot(int wheel) {
        return wheel == 0 ? AnimationSlot.Movement : AnimationSlot.Action;
    }

    private static void apply(Store<EntityStore> store, Ref<EntityStore> ref, int wheel, Spin spin) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        String id = animationId(wheel == 1, spin);
        if (id == null) {
            AnimationUtils.stopAnimation(ref, slot(wheel), store);
        } else {
            AnimationUtils.playAnimation(ref, slot(wheel), id, true, store);
        }
    }

    /** Applies the wheel spins of the flight: starts, changes and stops at the cycle end, then serves new viewers. Never throws. */
    static void update(Store<EntityStore> store, AirshipFlight f, long nowMs) {
        State st = f.wheels;
        if (st == null) {
            return;
        }
        try {
            boolean still = f.dry || f.off || f.landing || f.engines == null || f.pilotless();
            Spin[] want = still ? new Spin[]{Spin.STOP, Spin.STOP} : wheelsFor(f.moving, f.reversing, f.turnDir);
            for (int i = 0; i < 2; i++) {
                if (want[i] != st.wanted[i]) {
                    st.wanted[i] = want[i];
                    st.wantedSince[i] = nowMs;
                }
                Spin next = nextSpin(st.cur[i], st.start[i], st.wanted[i], st.wantedSince[i], nowMs);
                if (next != st.cur[i]) {
                    st.cur[i] = next;
                    st.start[i] = nowMs;
                    apply(store, f.shipRef, i, next);
                    apply(store, f.observerRef, i, next);
                }
            }
            serveViewers(store, st, f.shipRef, nowMs);
            serveViewers(store, st, f.observerRef, nowMs);
        } catch (RuntimeException e) {
            if (nowMs - st.lastLogMs >= LOG_EVERY_MS) {
                st.lastLogMs = nowMs;
                LOGGER.at(Level.WARNING).withCause(e).log("Roues à aubes : mise à jour en échec");
                Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            }
        }
    }

    /** Replays the running animations on both entities after a model change, and restarts the cycle clocks. Never throws. */
    static void onModelChanged(Store<EntityStore> store, AirshipFlight f, long nowMs) {
        State st = f.wheels;
        if (st == null) {
            return;
        }
        try {
            for (int i = 0; i < 2; i++) {
                if (st.cur[i] != Spin.STOP) {
                    apply(store, f.shipRef, i, st.cur[i]);
                    apply(store, f.observerRef, i, st.cur[i]);
                    st.start[i] = nowMs;
                }
            }
            // The replay above goes out at once, but the ModelUpdate of putModel is queued and sent later in the tick: it may wipe
            // the animations. Every viewer is therefore served again after the delay.
            st.viewers.values().forEach(m -> m.replaceAll((k, v) -> nowMs + VIEWER_DELAY_MS));
        } catch (RuntimeException e) {
            if (nowMs - st.lastLogMs >= LOG_EVERY_MS) {
                st.lastLogMs = nowMs;
                LOGGER.at(Level.WARNING).withCause(e).log("Roues à aubes : rejeu après changement de modèle en échec");
            }
        }
    }

    /**
     * Sends the running animations to each player who started to see the entity {@link #VIEWER_DELAY_MS} ago, once while he sees it.
     * The queue of an EntityViewer only takes component updates, so the packet goes straight to the player.
     */
    private static void serveViewers(Store<EntityStore> store, State st, Ref<EntityStore> ref, long nowMs) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return;
        }
        EntityTrackerSystems.Visible visible = store.getComponent(ref, EntityTrackerSystems.Visible.getComponentType());
        NetworkId nid = store.getComponent(ref, NetworkId.getComponentType());
        if (visible == null || visible.visibleTo == null || nid == null) {
            return;
        }
        boolean first = !st.viewers.containsKey(ref);
        Map<Ref<EntityStore>, Long> known = st.viewers.computeIfAbsent(ref, k -> new HashMap<>());
        known.keySet().removeIf(v -> !visible.visibleTo.containsKey(v));
        for (Ref<EntityStore> viewer : visible.visibleTo.keySet()) {
            // the viewers there when we first look got every animation change as it happened
            known.putIfAbsent(viewer, first ? Long.MAX_VALUE : nowMs + VIEWER_DELAY_MS);
        }
        for (Map.Entry<Ref<EntityStore>, Long> v : known.entrySet()) {
            if (v.getValue() == Long.MAX_VALUE || v.getValue() > nowMs) {
                continue;
            }
            v.setValue(Long.MAX_VALUE);
            PlayerRef pr = store.getComponent(v.getKey(), PlayerRef.getComponentType());
            if (pr == null) {
                continue;
            }
            for (int i = 0; i < 2; i++) {
                String id = animationId(i == 1, st.cur[i]);
                if (id != null) {
                    pr.getPacketHandler().writeNoCache(new PlayAnimation(nid.getId(), null, id, slot(i)));
                }
            }
        }
    }

    /** Diagnostic line for /orbishorizon airship debug. */
    static String describe(AirshipFlight f) {
        State st = f.wheels;
        return st == null ? "wheels: none" : "wheels: left " + st.cur[0] + ", right " + st.cur[1];
    }
}
