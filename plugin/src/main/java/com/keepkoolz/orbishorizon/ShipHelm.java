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
 * Animated helm of the airship and of both steamboats (25 October 2026): the node "Helm" of the flight model (hub axis of the helm block,
 * wheel axis = z) turns to the right while the ship turns right, to the left while it turns left, up to half a turn (4 steps of 45
 * degrees), holds while the turn lasts and returns to centre at the same speed. Step -4..4, positive = right.
 * The constants and the animation names must stay equal to HELM_* and helm_*_key of scripts/prefab_to_model.py (write_helm_anims):
 * Helm_Pose_<s> (static looping pose) and Helm_Step_<a>_<b> (one step in ROTATE_FRAMES then the arrival pose held), s = 0, P1..P4, M1..M4.
 * Animations play in the Status slot of both entities (ship and observer), like PaddleWheels.
 */
final class ShipHelm {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final int MAX_STEP = 4;
    static final int STEP_DEG = 45;
    /**
     * Sign of the rotation around +z of the node for a right turn (negative: clockwise seen from the pilot, who stands on the +z side of
     * the helm). Same value as HELM_RIGHT_SIGN in prefab_to_model.py. Not checked in game: both constants flip together.
     */
    static final int RIGHT_SIGN = -1;
    /** One step turns the wheel 45 degrees in this many frames (60 fps), then the arrival pose is held HOLD_FRAMES more. */
    static final int ROTATE_FRAMES = 15;
    static final int HOLD_FRAMES = 6;
    static final long ROTATE_MS = Math.round(ROTATE_FRAMES * 1000 / 60.0);
    /** Half a server tick: a step counts as done this much before its end. */
    static final long END_TOLERANCE_MS = PaddleWheels.END_TOLERANCE_MS;
    static final long VIEWER_DELAY_MS = PaddleWheels.VIEWER_DELAY_MS;
    private static final long LOG_EVERY_MS = 10_000;
    private static final AnimationSlot SLOT = AnimationSlot.Status;

    private ShipHelm() {
    }

    /** Wanted step: 0 when the ship is still or goes straight, else the full lock (turnDir 1 turns left = -MAX_STEP, -1 right = MAX_STEP). */
    static int targetFor(boolean stilled, int turnDir) {
        if (stilled || turnDir == 0) {
            return 0;
        }
        return turnDir < 0 ? MAX_STEP : -MAX_STEP;
    }

    /**
     * The ship turns by notches (its yaw rate falls to 0 between two notches of a turn), so a turn is held this long after the last
     * tick that turned: the helm stays locked through the notches and returns to centre only once the turn is really over.
     */
    static final long RELEASE_MS = 1000;

    /** Turn direction held through the pauses between notches: turnDir when turning, else lastDir until RELEASE_MS after lastTurnMs. */
    static int heldTurn(int turnDir, int lastDir, long lastTurnMs, long nowMs) {
        if (turnDir != 0) {
            return turnDir;
        }
        return lastDir != 0 && nowMs - lastTurnMs < RELEASE_MS ? lastDir : 0;
    }

    /** One step from current toward target (current when equal). */
    static int nextToward(int current, int target) {
        return Integer.compare(target, current) + current;
    }

    static String stepName(int step) {
        return step == 0 ? "0" : (step > 0 ? "P" : "M") + Math.abs(step);
    }

    static String poseId(int step) {
        return "Helm_Pose_" + stepName(step);
    }

    static String stepId(int from, int to) {
        return "Helm_Step_" + stepName(from) + "_" + stepName(to);
    }

    /** Angle of the wheel in degrees for a step, positive to the right (clockwise seen from the pilot). */
    static int degrees(int step) {
        return step * STEP_DEG;
    }

    /** True once a transition started at startMs has done its rotation. */
    static boolean transitionDone(long startMs, long nowMs) {
        return nowMs - startMs >= ROTATE_MS - END_TOLERANCE_MS;
    }

    /** Per-flight helm state. */
    static final class State {
        /** Step reached (the departure step while a transition runs). */
        int step;
        boolean running;
        int from;
        int to;
        long startMs;
        /** True once any animation was played (step 0 at the start plays nothing: rest pose). */
        boolean played;
        /** Last turn direction seen and when (see heldTurn). */
        int lastDir;
        long lastTurnMs;
        /** Per tracked entity, the viewers and when each is due its replay (Long.MAX_VALUE: served). */
        final Map<Ref<EntityStore>, Map<Ref<EntityStore>, Long>> viewers = new HashMap<>();
        long lastLogMs;

        /** Animation to show now: the arrival pose of a running transition, else the current pose (null: nothing played yet). */
        String displayId() {
            if (running) {
                return poseId(to);
            }
            return played ? poseId(step) : null;
        }
    }

    private static void play(Store<EntityStore> store, AirshipFlight f, String id) {
        for (Ref<EntityStore> ref : java.util.Arrays.asList(f.shipRef, f.observerRef)) {
            if (ref != null && ref.isValid()) {
                AnimationUtils.playAnimation(ref, SLOT, id, true, store);
            }
        }
    }

    /** Moves the helm toward the wheel position the turn asks for, then serves new viewers. Never throws. */
    static void update(Store<EntityStore> store, AirshipFlight f, long nowMs) {
        State st = f.helm();
        if (st == null) {
            return;
        }
        try {
            boolean still = PaddleWheels.still(f.dry, f.off, f.landing, f.engines != null, f.pilotless(),
                    f.kind.airship != null && f.kind.airship.fuelless, f.sailState() != null);
            if (f.turnDir != 0) {
                st.lastDir = f.turnDir;
                st.lastTurnMs = nowMs;
            }
            int target = targetFor(still, still ? 0 : heldTurn(f.turnDir, st.lastDir, st.lastTurnMs, nowMs));
            boolean arrived = false;
            if (st.running && transitionDone(st.startMs, nowMs)) {
                st.step = st.to;
                st.running = false;
                arrived = true;
            }
            if (!st.running) {
                if (st.step != target) {
                    st.from = st.step;
                    st.to = nextToward(st.step, target);
                    st.startMs = nowMs;
                    st.running = true;
                    st.played = true;
                    play(store, f, stepId(st.from, st.to));
                } else if (arrived) {
                    play(store, f, poseId(st.step));
                }
            }
            serveViewers(store, st, f.shipRef, nowMs);
            serveViewers(store, st, f.observerRef, nowMs);
        } catch (RuntimeException e) {
            if (nowMs - st.lastLogMs >= LOG_EVERY_MS) {
                st.lastLogMs = nowMs;
                LOGGER.at(Level.WARNING).withCause(e).log("Barre animée : mise à jour en échec");
                Telemetry.error("airship.flight", e, Telemetry.kindId(f.kind), AirshipAutopilot.phaseOf(f));
            }
        }
    }

    /** Replays the current pose on both entities after a model change, and serves every viewer again after the delay. Never throws. */
    static void onModelChanged(Store<EntityStore> store, AirshipFlight f, long nowMs) {
        State st = f.helm();
        if (st == null) {
            return;
        }
        try {
            String id = st.displayId();
            if (id != null) {
                play(store, f, id);
            }
            // The ModelUpdate of putModel is queued and sent later in the tick: it may wipe the animation (same as PaddleWheels).
            st.viewers.values().forEach(m -> m.replaceAll((k, v) -> nowMs + VIEWER_DELAY_MS));
        } catch (RuntimeException e) {
            if (nowMs - st.lastLogMs >= LOG_EVERY_MS) {
                st.lastLogMs = nowMs;
                LOGGER.at(Level.WARNING).withCause(e).log("Barre animée : rejeu après changement de modèle en échec");
            }
        }
    }

    /** Sends the pose to each player who started to see the entity {@link #VIEWER_DELAY_MS} ago, once while he sees it. */
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
            known.putIfAbsent(viewer, first ? Long.MAX_VALUE : nowMs + VIEWER_DELAY_MS);
        }
        for (Map.Entry<Ref<EntityStore>, Long> v : known.entrySet()) {
            if (v.getValue() == Long.MAX_VALUE || v.getValue() > nowMs) {
                continue;
            }
            v.setValue(Long.MAX_VALUE);
            PlayerRef pr = store.getComponent(v.getKey(), PlayerRef.getComponentType());
            String id = st.displayId();
            if (pr != null && id != null) {
                pr.getPacketHandler().writeNoCache(new PlayAnimation(nid.getId(), null, id, SLOT));
            }
        }
    }

    /** Diagnostic fragment for /orbishorizon airship debug: wheel angle in degrees, positive to the right. */
    static String describe(AirshipFlight f) {
        State st = f.helm();
        if (st == null) {
            return "helm: none";
        }
        return "helm: " + degrees(st.running ? st.to : st.step) + (st.running ? " (moving)" : "");
    }
}
