package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.logging.Level;

/**
 * standing mannequin of the pilot of a piloted vehicle (three balloons and the airship). The others see a copy of the pilot's model
 * and skin standing at the pivot (Idle animation) instead of the pilot flying with the creative flight animation: the real body is hidden
 * from everybody else (ViewFilter ONLY the pilot) and the mannequin is hidden from the pilot. Only in dual rendering: the mannequin is
 * mounted on the observer entity (which is not mounted on the pilot, so hiding the pilot's body cannot hide the vehicle). Without dual
 * rendering there is none. It lives exactly as long as the dual rendering: removed on every end of the flight and when the view collapses
 * (pilot dead, gone, left, seated for a dry descent). Never throws.
 */
final class PilotMannequin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long ANIM_FIRST_MS = 400;
    private static final long ANIM_SECOND_MS = 1500;
    private static final long ANIM_REPEAT_MS = 4000;

    /** Switch (next take-off), shared by the balloons and the airship. */
    static volatile boolean enabled = true;

    private PilotMannequin() {
    }

    static final class State {
        Ref<EntityStore> mannequin;
        Ref<EntityStore> body;
        UUID pilotUuid;
        long createdMs;
        long lastAnimMs;
        int anims;
        boolean failed;
    }

    private static UUID uuid(UUID vehicle) {
        return BalloonLights.uuidFor(vehicle, "pilot-mannequin");
    }

    /**
     * Hides the pilot's body and creates the mannequin on the observer entity at its position. Returns null if disabled, if the pilot has no
     * model or skin, or if the creation failed (nothing hidden then).
     */
    static State create(Store<EntityStore> store, UUID vehicleUuid, Ref<EntityStore> observer, Ref<EntityStore> pilotRef, UUID pilotUuid,
                        long now) {
        if (!enabled || vehicleUuid == null || !PassengerSeatView.valid(observer) || !PassengerSeatView.valid(pilotRef) || pilotUuid == null) {
            return null;
        }
        try {
            ModelComponent mc = store.getComponent(pilotRef, ModelComponent.getComponentType());
            PlayerSkinComponent skin = store.getComponent(pilotRef, PlayerSkinComponent.getComponentType());
            TransformComponent ot = store.getComponent(observer, TransformComponent.getComponentType());
            if (mc == null || mc.getModel() == null || skin == null || ot == null) {
                LOGGER.at(Level.INFO).log("Pilote %s : pas de modèle ou d'apparence, pas de mannequin de pilote", pilotUuid);
                return null;
            }
            Model model = mc.getModel();
            Ref<EntityStore> m = PassengerSeatView.spawn(store, model, uuid(vehicleUuid), new Vector3d(ot.getPosition()), observer, new Vector3f(), skin);
            if (m == null) {
                return null;
            }
            ViewFilter.hide(m, pilotUuid);
            ViewFilter.only(pilotRef, pilotUuid); // the real body is hidden from the others only once the mannequin exists
            State s = new State();
            s.mannequin = m;
            s.body = pilotRef;
            s.pilotUuid = pilotUuid;
            s.createdMs = now;
            s.lastAnimMs = now;
            return s;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Mannequin du pilote impossible");
            return null;
        }
    }

    /** Pilot yaw read from the head (the component SetHead updates), else from the body. */
    static float headYaw(Store<EntityStore> store, Ref<EntityStore> pilotRef) {
        if (PassengerSeatView.valid(pilotRef)) {
            HeadRotation hr = store.getComponent(pilotRef, HeadRotation.getComponentType());
            if (hr != null) {
                return hr.getRotation().y;
            }
            TransformComponent t = store.getComponent(pilotRef, TransformComponent.getComponentType());
            if (t != null) {
                return t.getRotation().y;
            }
        }
        return 0f;
    }

    /**
     * Every tick: copies the observer's position, sets the yaw, replays Idle. If the mannequin disappeared the body is shown again (the pilot
     * is never left invisible).
     */
    static void sync(Store<EntityStore> store, State s, Ref<EntityStore> observer, Ref<EntityStore> pilotRef, long now) {
        sync(store, s, observer, Float.NaN, pilotRef, now);
    }

    /** Same with an explicit yaw (airship: the ship's heading), the pilot ref is only read when the yaw is NaN. */
    static void sync(Store<EntityStore> store, State s, Ref<EntityStore> observer, float yawIn, Ref<EntityStore> pilotRef, long now) {
        if (s == null || s.failed) {
            return;
        }
        try {
            if (!PassengerSeatView.valid(s.mannequin)) {
                s.failed = true;
                ViewFilter.clear(s.body);
                LOGGER.at(Level.WARNING).log("Mannequin du pilote %s disparu : corps du pilote de nouveau visible", s.pilotUuid);
                return;
            }
            float yaw = Float.isNaN(yawIn) ? headYaw(store, pilotRef) : yawIn;
            TransformComponent ot = PassengerSeatView.valid(observer) ? store.getComponent(observer, TransformComponent.getComponentType()) : null;
            TransformComponent mt = store.getComponent(s.mannequin, TransformComponent.getComponentType());
            if (mt != null) {
                if (ot != null) {
                    mt.setPosition(new Vector3d(ot.getPosition()));
                }
                if (mt.getRotation().y != yaw) {
                    Rotation3f r = new Rotation3f();
                    r.setYaw(yaw);
                    mt.setRotation(r);
                }
            }
            long due = s.anims == 0 ? ANIM_FIRST_MS : s.anims == 1 ? ANIM_SECOND_MS : ANIM_REPEAT_MS;
            if (now - (s.anims < 2 ? s.createdMs : s.lastAnimMs) >= due) {
                s.anims++;
                s.lastAnimMs = now;
                AnimationUtils.playAnimation(s.mannequin, AnimationSlot.Movement, "Idle", true, store);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Mannequin du pilote : mise à jour en échec");
        }
    }

    /**
     * Removes the mannequin and, unless keepBody (the pilot becomes a seated passenger whose seat view owns the rule), the hidden-body rule.
     * Safe to call with null, several times, or with an invalid pilot ref. Never throws.
     */
    static void remove(Store<EntityStore> store, State s, boolean keepBody) {
        if (s == null) {
            return;
        }
        try {
            if (!keepBody) {
                ViewFilter.clear(s.body);
            }
            if (s.mannequin != null) {
                ViewFilter.clear(s.mannequin);
                if (s.mannequin.isValid()) {
                    store.removeEntity(s.mannequin, RemoveReason.REMOVE);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Mannequin du pilote non supprimé");
        }
    }

    /** Removes the mannequin an interrupted flight may have left in a world that stayed loaded (resume). Never throws. */
    static void removeOrphan(World world, Store<EntityStore> store, UUID vehicleUuid) {
        if (vehicleUuid == null) {
            return;
        }
        try {
            Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(uuid(vehicleUuid));
            if (ref != null && ref.isValid()) {
                ViewFilter.clear(ref);
                store.removeEntity(ref, RemoveReason.REMOVE);
                LOGGER.at(Level.INFO).log("Mannequin de pilote resté dans le monde supprimé (%s)", vehicleUuid);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Mannequin de pilote resté dans le monde non supprimé");
        }
    }

    /** Diagnostic text (English, short). */
    static String describe(State s) {
        if (s == null) {
            return "pilot mannequin " + (enabled ? "none" : "disabled") + " (setting " + (enabled ? "on" : "off") + ")";
        }
        return "pilot mannequin " + (PassengerSeatView.valid(s.mannequin) ? "ok" : "MISSING") + ", body hidden "
                + (s.body != null && ViewFilter.ruleOf(s.body) != null) + (s.failed ? ", FAILED" : "");
    }
}
