package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.UUID;
import java.util.logging.Level;

/**
 * T82: helpers shared by the balloon and the airship for the observer entity (dual rendering, see ViewFilter).
 *
 * The pilot's entity (mounted on the pilot) is what the pilot's client draws. The observer entity has the same model, no mount, and
 * the server writes its position and yaw every tick, which the other clients interpolate (the same mechanism as the pilotless
 * descent of T20 and T26, validated in game). A model change (flame, boost, off, thrust states) must be applied to both.
 */
final class VehicleView {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private VehicleView() {
    }

    /** Deterministic UUID of the observer entity of a vehicle (resume files only store the pilot entity's UUID). */
    static UUID observerUuid(UUID mainUuid) {
        return BalloonLights.uuidFor(mainUuid, BalloonLights.KEY_OBSERVER);
    }

    /**
     * Creates the observer entity next to the pilot's entity (same position, yaw and model) and sets the visibility rules: the
     * pilot's entity only for the pilot, the observer for everybody else. Returns null (rules undone, warning) if it fails, the flight is
     * then in single rendering.
     */
    static Ref<EntityStore> spawnObserver(Store<EntityStore> store, Ref<EntityStore> main, UUID mainUuid, UUID pilot, String modelId) {
        try {
            TransformComponent t = store.getComponent(main, TransformComponent.getComponentType());
            if (t == null || mainUuid == null) {
                return null;
            }
            Ref<EntityStore> observer = BalloonManager.get().spawnModelEntity(store, new Vector3d(t.getPosition()),
                    t.getRotation().y, modelId, observerUuid(mainUuid));
            if (observer == null) {
                return null;
            }
            ViewFilter.only(main, pilot);
            ViewFilter.hide(observer, pilot);
            return observer;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Entité d'observation impossible : affichage simple");
            ViewFilter.clear(main);
            return null;
        }
    }

    /** Copies the pilot entity's position and yaw to the observer entity (to be called after the pilot entity was moved). */
    static void sync(Store<EntityStore> store, Ref<EntityStore> main, Ref<EntityStore> observer) {
        sync(store, main, observer, 1.0);
    }

    /**
     * Same, with an exponential smoothing of the observer position (alpha 1 = plain copy). T85: the pilot's position only changes
     * when a client packet arrives, so copying it raw gives observers uneven steps. A jump above 3 blocks is copied at once.
     */
    static void sync(Store<EntityStore> store, Ref<EntityStore> main, Ref<EntityStore> observer, double alpha) {
        if (observer == null || !observer.isValid() || main == null || !main.isValid()) {
            return;
        }
        TransformComponent from = store.getComponent(main, TransformComponent.getComponentType());
        TransformComponent to = store.getComponent(observer, TransformComponent.getComponentType());
        if (from == null || to == null) {
            return;
        }
        Vector3d want3 = new Vector3d(from.getPosition());
        if (alpha < 1 && alpha > 0) {
            Vector3d cur3 = to.getPosition();
            if (cur3.distanceSquared(want3) < 9) {
                want3 = new Vector3d(cur3).lerp(want3, alpha);
            }
        }
        to.setPosition(want3);
        Rotation3f cur = to.getRotation();
        Rotation3f want = from.getRotation();
        if (cur.y != want.y || cur.x != want.x || cur.z != want.z) {
            to.setRotation(new Rotation3f(want));
        }
    }

    /** Applies a model to the entity and, if there is one, to the observer. Each entity gets its own Model instance. */
    static void putModel(Store<EntityStore> store, Ref<EntityStore> main, Ref<EntityStore> observer, ModelAsset asset) {
        if (main != null && main.isValid()) {
            store.putComponent(main, ModelComponent.getComponentType(), new ModelComponent(Model.createUnitScaleModel(asset)));
        }
        if (observer != null && observer.isValid()) {
            try {
                store.putComponent(observer, ModelComponent.getComponentType(), new ModelComponent(Model.createUnitScaleModel(asset)));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Modèle non appliqué à l'entité d'observation");
            }
        }
    }

    /** Removes the observer entity and its rule. Never throws. */
    static void removeObserver(Store<EntityStore> store, Ref<EntityStore> observer) {
        if (observer == null) {
            return;
        }
        try {
            ViewFilter.clear(observer);
            if (observer.isValid()) {
                store.removeEntity(observer, RemoveReason.REMOVE);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Entité d'observation non supprimée");
        }
    }
}
