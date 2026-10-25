package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import org.joml.Vector3d;
import org.joml.Vector3f;
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
 * Pure rule of the steamboat paddle wheels and of the shuttle's single propeller: which spin each wheel (or the propeller) wants, the animation names, and when a
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

    /**
     * Wanted spin of the single propeller of the shuttle: forward at full speed while moving ahead (turning or not), slowly astern
     * when reversing, slowly forward when it only turns in place, else still. A sideways slide is set by the caller (slideWheels).
     */
    static Spin propellerFor(boolean moving, boolean reversing, int turnDir) {
        if (moving) {
            return reversing ? Spin.REV_SLOW : Spin.FWD_FAST;
        }
        return turnDir != 0 ? Spin.FWD_SLOW : Spin.STOP;
    }

    /** Animation key of the propeller: "<node>_<suffix>" (AnimationSets of Steamboat_Public.json), null for STOP. */
    static String propellerAnimationId(String prefix, Spin spin) {
        String suffix = spin.suffix();
        return suffix == null ? null : prefix + "_" + suffix;
    }

    /** The shuttle slides sideways in or out of its berth: both wheels turn slowly forward, whatever the slide direction. */
    static Spin[] slideWheels() {
        return new Spin[]{Spin.FWD_SLOW, Spin.FWD_SLOW};
    }

    /** Global constraint name of the animation, null for STOP. */
    static String animationId(boolean right, Spin spin) {
        String suffix = spin.suffix();
        return suffix == null ? null : (right ? "Wheel_Right_" : "Wheel_Left_") + suffix;
    }

    /**
     * The wheels stay still: off, landing, or (for a ship that burns fuel) dry, without engines or without a pilot. A fuelless
     * ship (the maritime shuttle) has no engines and no pilot but its wheels follow its movement. A ship sailed by the autopilot
     * (a steamboat voyage with the NPC pilot: no player pilot) counts as piloted.
     */
    static boolean still(boolean dry, boolean off, boolean landing, boolean hasEngines, boolean pilotless, boolean fuelless,
                         boolean sailed) {
        if (off || landing) {
            return true;
        }
        return !fuelless && (dry || !hasEngines || (pilotless && !sailed));
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
        /** Name of the propeller node (single-propeller mode, only index 0 and the Movement slot are used), null for two wheels. */
        final String propeller;
        final Spin[] cur = {Spin.STOP, Spin.STOP};
        final long[] start = {0, 0};
        final Spin[] wanted = {Spin.STOP, Spin.STOP};
        final long[] wantedSince = {0, 0};
        /** Per tracked entity, the viewers and when each is due its replay (Long.MAX_VALUE: served). */
        final Map<Ref<EntityStore>, Map<Ref<EntityStore>, Long>> viewers = new HashMap<>();
        /** Per wheel, when the last splash was played. */
        final long[] lastSplash = {Long.MIN_VALUE, Long.MIN_VALUE};
        long lastLogMs;

        State(String propeller) {
            this.propeller = propeller;
        }

        State() {
            this(null);
        }

        int count() {
            return propeller != null ? 1 : 2;
        }

        String animationId(int i, Spin spin) {
            return propeller != null ? propellerAnimationId(propeller, spin) : PaddleWheels.animationId(i == 1, spin);
        }
    }

    static final String SPLASH_ID = "SFX_Steamboat_Paddle_Splash";
    /** Splash points in the prefab frame (cell coordinates, y at the waterline), left wheel (-x) then right wheel (+x). */
    private static final double[][] SPLASH_LOCAL = {{-6, -1.5, 2}, {6, -1.5, 2}};
    private static int splashIndex = Integer.MIN_VALUE;
    private static boolean splashWarned;

    /** True when the cycle running since startMs has begun after the last splash: one splash per paddle, at the start of each cycle. */
    static boolean splashDue(long periodMs, long startMs, long lastSplashMs, long nowMs) {
        if (periodMs <= 0 || nowMs < startMs) {
            return false;
        }
        long cycleStart = startMs + (nowMs - startMs) / periodMs * periodMs;
        return lastSplashMs < cycleStart;
    }

    private static int splashIndex() {
        if (splashIndex == Integer.MIN_VALUE) {
            int idx = SoundEvent.getAssetMap().getIndex(SPLASH_ID);
            if (idx <= 0 && !splashWarned) {
                splashWarned = true;
                LOGGER.at(Level.WARNING).log("Roues à aubes : son " + SPLASH_ID + " introuvable, pas d'éclaboussure");
            }
            splashIndex = idx;
        }
        return splashIndex;
    }

    private static void playSplashes(Store<EntityStore> store, AirshipFlight f, State st, long nowMs) {
        if (st.propeller != null) {
            return; // the propeller makes no paddle splash
        }
        for (int i = 0; i < 2; i++) {
            Spin sp = st.cur[i];
            if (sp == Spin.STOP || !splashDue(sp.periodMs(), st.start[i], st.lastSplash[i], nowMs)) {
                continue;
            }
            st.lastSplash[i] = nowMs;
            int idx = splashIndex();
            if (idx <= 0) {
                continue;
            }
            Vector3f pv = AirshipManager.pivot(f.kind);
            double[] l = SPLASH_LOCAL[i];
            Vector3d p = AirshipMath.rotateY(l[0] - pv.x, l[1] - pv.y, l[2] - pv.z, f.theta, new Vector3d()).add(f.pos);
            SoundUtil.playSoundEvent3d(idx, SoundCategory.SFX, p.x, p.y, p.z, store);
        }
    }

    private static AnimationSlot slot(int wheel) {
        return wheel == 0 ? AnimationSlot.Movement : AnimationSlot.Action;
    }

    private static void apply(Store<EntityStore> store, Ref<EntityStore> ref, State st, int wheel, Spin spin) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        String id = st.animationId(wheel, spin);
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
            boolean still = still(f.dry, f.off, f.landing, f.engines != null, f.pilotless(),
                    f.kind.airship != null && f.kind.airship.fuelless, f.sailState() != null);
            ShuttleState sail = f.sailState();
            boolean slide = sail != null && sail.slide != ShuttleState.Slide.NONE;
            Spin[] want;
            if (st.propeller != null) {
                want = new Spin[]{still ? Spin.STOP : slide ? Spin.FWD_SLOW : propellerFor(f.moving, f.reversing, f.turnDir)};
            } else {
                want = still ? new Spin[]{Spin.STOP, Spin.STOP} : slide ? slideWheels() : wheelsFor(f.moving, f.reversing, f.turnDir);
            }
            for (int i = 0; i < st.count(); i++) {
                if (want[i] != st.wanted[i]) {
                    st.wanted[i] = want[i];
                    st.wantedSince[i] = nowMs;
                }
                Spin next = nextSpin(st.cur[i], st.start[i], st.wanted[i], st.wantedSince[i], nowMs);
                if (next != st.cur[i]) {
                    st.cur[i] = next;
                    st.start[i] = nowMs;
                    apply(store, f.shipRef, st, i, next);
                    apply(store, f.observerRef, st, i, next);
                }
            }
            playSplashes(store, f, st, nowMs);
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
            for (int i = 0; i < st.count(); i++) {
                if (st.cur[i] != Spin.STOP) {
                    apply(store, f.shipRef, st, i, st.cur[i]);
                    apply(store, f.observerRef, st, i, st.cur[i]);
                    st.start[i] = nowMs;
                    st.lastSplash[i] = nowMs;
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
            for (int i = 0; i < st.count(); i++) {
                String id = st.animationId(i, st.cur[i]);
                if (id != null) {
                    pr.getPacketHandler().writeNoCache(new PlayAnimation(nid.getId(), null, id, slot(i)));
                }
            }
        }
    }

    /** Diagnostic line for /orbishorizon airship debug. */
    static String describe(AirshipFlight f) {
        State st = f.wheels;
        if (st == null) {
            return "wheels: none";
        }
        return st.propeller != null ? "propeller: " + st.cur[0] : "wheels: left " + st.cur[0] + ", right " + st.cur[1];
    }
}
