package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The airship's helm in flight (lever until 7 October 2026). In flight the helm window is only part of the entity's model, so a small
 * helper entity is placed on the helm cell, mounted on the ship entity like the lights (BalloonLights) but interactable, like the
 * game's minecart: an Interactable marker (the client may target the entity) and an Interactions component whose Use entry names a
 * root interaction (Server/Item/RootInteractions/Airship/Airship_Helm_Use.json, type Airship_HelmLand). The game's own Use key
 * starts UseBlock then UseEntity: the client sends the targeted entity's network id and the server runs that entity's Use root
 * interaction with the player as the acting entity.
 *
 * The helper has a model only because the client needs a hitbox to target it: a game model (Rubble_Stone) at a tiny scale
 * (practically invisible) with a custom 0.8 block box. Nothing new is created in Server/Models.
 *
 * The hot air balloon chain uses the same helper in flight (BalloonLights.spawnChain, root Hotair_Balloon_Chain_Use).
 */
final class AirshipHelm {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String ROOT_INTERACTION_ID = "Airship_Helm_Use";
    static final String KEY = "airship_helm";
    /** Interaction hint shown by the client when the helper is targeted (both helpers land: helm and balloon chain). */
    static final String LAND_HINT = "server.orbis_horizon.interactionHints.land";
    /** Existing game model used as a carrier for the hitbox. */
    static final String MODEL_ID = "Rubble_Stone";
    static final float MODEL_SCALE = 0.02f;
    /** Half size of the hitbox, in blocks (0.8 block box). */
    static final double HALF = 0.4;

    /** When true the helper is Intangible (the game skips Intangible entities in its entity spatial structures). Tunable. */
    static volatile boolean intangible = false;

    private AirshipHelm() {
    }

    static final class Helper {
        Ref<EntityStore> ref;
        UUID uuid;
        /** Prefab frame point (centre of the helm cell). */
        Vector3f local;
    }

    /** UUID of the helper of a ship (deterministic, so a resume can find an orphan). */
    static UUID uuidFor(UUID shipUuid) {
        return BalloonLights.uuidFor(shipUuid, KEY);
    }

    /**
     * Creates the helper at a prefab point, mounted on the ship entity. Returns null (with a warning) if it cannot be made: the
     * helm is then only usable through the land command.
     */
    static Helper spawn(Store<EntityStore> store, UUID shipUuid, Ref<EntityStore> shipRef, Vector3f local, Vector3f pivot, Vector3d position) {
        Vector3f d = new Vector3f(local).sub(pivot);
        return spawn(store, uuidFor(shipUuid), ROOT_INTERACTION_ID, shipRef, local, new Vector3f(-d.x, d.y, -d.z), position);
    }

    /**
     * Generic form, also used for the hot air balloon chain in flight: helper UUID, root interaction of its Use entry, vehicle
     * entity it is mounted on, prefab point, mount offset in the vehicle entity's frame and initial world position.
     */
    static Helper spawn(Store<EntityStore> store, UUID helperUuid, String rootInteractionId, Ref<EntityStore> shipRef,
                        Vector3f local, Vector3f mountOffset, Vector3d position) {
        try {
            ModelAsset asset = ModelAsset.getAssetMap().getAsset(MODEL_ID);
            if (asset == null) {
                LOGGER.at(Level.WARNING).log("Modèle %s introuvable : pas de barre utilisable en vol", MODEL_ID);
                return null;
            }
            double s = MODEL_SCALE;
            // The box is scaled by the model scale: ask for HALF / scale to get HALF.
            Box box = new Box(-HALF / s, -HALF / s, -HALF / s, HALF / s, HALF / s, HALF / s);
            Model model = Model.createScaledModel(asset, MODEL_SCALE, null, box);
            Helper h = new Helper();
            h.local = new Vector3f(local);
            h.uuid = helperUuid;
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(position), new Rotation3f()));
            holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(h.uuid));
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
            holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
            holder.ensureComponent(Interactable.getComponentType());
            Map<InteractionType, String> uses = new EnumMap<>(InteractionType.class);
            uses.put(InteractionType.Use, rootInteractionId);
            Interactions interactions = new Interactions(uses);
            interactions.setInteractionHint(LAND_HINT);
            holder.addComponent(Interactions.getComponentType(), interactions);
            if (intangible) {
                holder.ensureComponent(Intangible.getComponentType());
            }
            h.ref = store.addEntity(holder, AddReason.SPAWN);
            if (h.ref == null) {
                return null;
            }
            store.putComponent(h.ref, MountedComponent.getComponentType(),
                    new MountedComponent(shipRef, new Vector3f(mountOffset), MountController.Minecart));
            return h;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Entité de la barre du dirigeable impossible");
            return null;
        }
    }

    static void remove(Store<EntityStore> store, Helper h) {
        if (h == null) {
            return;
        }
        try {
            ViewFilter.clear(h.ref);
            if (h.ref != null && h.ref.isValid()) {
                store.removeEntity(h.ref, RemoveReason.REMOVE);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Entité de la barre non supprimée");
        }
        h.ref = null;
    }
}
