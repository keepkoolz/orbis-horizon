package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * T118: seat view of a CAMERA-mode passenger (balloons with seats, dry-descent pilot, transit balloons, airship). Two things (the camera anchor and the relative camera yaw of the airship seat view were removed on 16 October 2026: every vehicle now uses the T124 external view, see PassengerCamera), all
 * built from the same few lines and removed together (PassengerCamera.reset, the single exit point of the pose):
 *
 * - a mannequin: an entity with a copy of the passenger's model and skin, mounted on the vehicle at the seat point and playing Sit, so the
 *   others see the passenger seated as part of the vehicle. With dual rendering (T82) there is one on the pilot's entity (seen by the
 *   pilot) and a twin on the observer entity (seen by everybody but the pilot and the passenger), otherwise one seen by everybody but the passenger.
 * - the real body is hidden from everybody else (ViewFilter ONLY the passenger).
 *
 * The view is rebuilt when the entity the passenger sees changes (collapse of the dual rendering). Never throws.
 */
final class PassengerSeatView {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Delay before the camera is sent after an anchor is created: the client must know the entity first. */
    private static final long CAMERA_DELAY_MS = 150;
    /** Delays of the first Sit animations after a mannequin is created (viewers learn the entity a tick or two later), then the repeat interval. */
    private static final long ANIM_FIRST_MS = 400;
    private static final long ANIM_SECOND_MS = 1500;
    private static final long ANIM_REPEAT_MS = 4000;

    private PassengerSeatView() {
    }

    /** What the caller knows about the vehicle and the seat, for one tick. view: the vehicle as the external view sees it (T124, balloons and airship). */
    record Ctx(UUID vehicleUuid, Ref<EntityStore> main, Ref<EntityStore> observer, boolean dual, UUID pilot, Vector3f seatOffset,
               Vector3d seatWorld, List<State> registry, PassengerCamera.Source view) {
    }

    /** Entities of one passenger's seat view. */
    static final class State {
        Ref<EntityStore> mannequin;
        Ref<EntityStore> twin;
        /** Entity the anchor is mounted on and rendering mode it was built for: a change means a rebuild. */
        Ref<EntityStore> target;
        boolean builtDual;
        long createdMs;
        int anims;
        long lastAnimMs;
        boolean noMannequin;
        /** Player entity whose body rule this state owns, and the flight-level list it is registered in (drained at the end of the flight). */
        Ref<EntityStore> bodyRef;
        List<State> registry;
    }

    /** Camera anchor of the former airship seat view (removed 16 October 2026), kept to clean up entities an older version left in a world. */
    private static UUID anchorUuid(UUID vehicle, UUID player) {
        return BalloonLights.uuidFor(vehicle, "cam:" + player);
    }

    private static UUID mannequinUuid(UUID vehicle, UUID player, boolean twin) {
        return BalloonLights.uuidFor(vehicle, "man:" + player + (twin ? BalloonLights.TWIN_SUFFIX : ""));
    }

    /**
     * Every tick of a CAMERA passenger: builds or rebuilds the view when needed, keeps the entities on their world point, replays Sit on the
     * mannequins and sends the external camera once the target is known to the client.
     */
    static void sync(Store<EntityStore> store, BalloonFlight.Passenger p, Ctx c, long now) {
        try {
            boolean dual = c.dual() && c.observer() != null && c.observer().isValid();
            Ref<EntityStore> target = dual ? c.observer() : c.main();
            if (target == null || !target.isValid()) {
                return;
            }
            State s = p.seatView;
            if (s != null && (s.target != target || s.builtDual != dual || gone(s.mannequin) || gone(s.twin))) {
                // I2: the replacement is built before the old one is deleted, the body stays hidden, and the camera goes to the entity
                // the client sees right away (fixed offset) until the new target is known to it.
                State old = s;
                s = build(store, p, c, target, dual, now);
                p.seatView = s;
                dispose(store, old, true);
                PassengerCamera.attachExternal(store, c.view(), p, target);
            } else if (s == null) {
                s = build(store, p, c, target, dual, now);
                p.seatView = s;
            }
            place(store, s.mannequin, c.seatWorld());
            place(store, s.twin, c.seatWorld());
            replayAnimation(store, p, s, now);
            // T124: external view, re-sent when the travel direction changes or the entity the client sees changes.
            NetworkId tn = store.getComponent(target, NetworkId.getComponentType());
            if (tn != null && p.cameraOn && PassengerCamera.directionChanged(c.view(), p)) {
                PassengerCamera.attachExternal(store, c.view(), p, target);
            }
            if (tn != null && now - s.createdMs >= CAMERA_DELAY_MS && (!p.cameraOn || tn.getId() != p.cameraTargetId)) {
                PassengerCamera.attachExternal(store, c.view(), p, target);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vue du siège du passager %s en échec", p.name);
        }
    }

    /** An entity that was created and no longer exists (a creation that failed is null and is not retried every tick). */
    private static boolean gone(Ref<EntityStore> r) {
        return r != null && !r.isValid();
    }

    static boolean valid(Ref<EntityStore> r) {
        return r != null && r.isValid();
    }

    static void place(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pos) {
        if (!valid(ref)) {
            return;
        }
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t != null) {
            t.setPosition(new Vector3d(pos));
        }
    }

    /** T119: sets the yaw of an auxiliary entity to the one of another entity (pitch and roll zero). */
    static void copyYaw(Store<EntityStore> store, Ref<EntityStore> ref, Ref<EntityStore> from) {
        if (!valid(ref) || !valid(from)) {
            return;
        }
        TransformComponent src = store.getComponent(from, TransformComponent.getComponentType());
        TransformComponent dst = store.getComponent(ref, TransformComponent.getComponentType());
        if (src != null && dst != null && dst.getRotation().y != src.getRotation().y) {
            Rotation3f r = new Rotation3f();
            r.setYaw(src.getRotation().y);
            dst.setRotation(r);
        }
    }

    private static State build(Store<EntityStore> store, BalloonFlight.Passenger p, Ctx c, Ref<EntityStore> target, boolean dual, long now) {
        State s = new State();
        s.target = target;
        s.builtDual = dual;
        s.createdMs = now;
        s.lastAnimMs = now;
        s.bodyRef = p.ref;
        s.registry = c.registry();
        if (s.registry != null) {
            s.registry.add(s);
        }
        // The real body is hidden from the others whatever happens next.
        ViewFilter.only(p.ref, p.uuid);
        ModelComponent mc = valid(p.ref) ? store.getComponent(p.ref, ModelComponent.getComponentType()) : null;
        PlayerSkinComponent skin = valid(p.ref) ? store.getComponent(p.ref, PlayerSkinComponent.getComponentType()) : null;
        if (mc == null || mc.getModel() == null || skin == null) {
            s.noMannequin = true;
            if (!p.noMannequinLogged) {
                p.noMannequinLogged = true;
                LOGGER.at(Level.INFO).log("Passager %s : pas de modèle ou d'apparence, pas de mannequin", p.name);
            }
            return s;
        }
        Model model = mc.getModel();
        if (dual) {
            // Original on the pilot's entity, seen by the pilot only. Twin on the observer entity, seen by all but the pilot and the passenger.
            s.mannequin = spawn(store, model, mannequinUuid(c.vehicleUuid(), p.uuid, false), c.seatWorld(), c.main(), c.seatOffset(), skin);
            if (s.mannequin != null) {
                if (c.pilot() != null) {
                    ViewFilter.only(s.mannequin, c.pilot());
                } // T124: no pilot: the passenger sees his own mannequin from outside

            }
            s.twin = spawn(store, model, mannequinUuid(c.vehicleUuid(), p.uuid, true), c.seatWorld(), c.observer(), c.seatOffset(), skin);
            if (s.twin != null) {
                Set<UUID> hidden = new HashSet<>();
                if (c.pilot() != null) {
                    hidden.add(c.pilot());
                }
                if (!hidden.isEmpty()) {
                    ViewFilter.hideAll(s.twin, hidden);
                }
            }
        } else {
            s.mannequin = spawn(store, model, mannequinUuid(c.vehicleUuid(), p.uuid, false), c.seatWorld(), target, c.seatOffset(), skin);
        }
        if (s.mannequin == null) {
            s.noMannequin = true; // creation failed (logged), not retried every tick
        }
        return s;
    }

    /** Creates an auxiliary entity mounted on the given entity. Model and skin null: invisible (camera anchor). Returns null on failure. */
    static Ref<EntityStore> spawn(Store<EntityStore> store, Model model, UUID uuid, Vector3d pos, Ref<EntityStore> mountOn,
                                          Vector3f mountOffset, PlayerSkinComponent skin) {
        try {
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(pos), new Rotation3f()));
            holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(uuid));
            if (model != null) {
                holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
                holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
            }
            if (skin != null) {
                holder.addComponent(PlayerSkinComponent.getComponentType(), (PlayerSkinComponent) skin.clone());
            }
            holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(Intangible.getComponentType());
            holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
            Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
            if (ref == null) {
                return null;
            }
            store.putComponent(ref, MountedComponent.getComponentType(),
                    new MountedComponent(mountOn, new Vector3f(mountOffset), MountController.Minecart));
            return ref;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Entité de vue de siège impossible (%s)", model == null ? "ancre" : "mannequin");
            return null;
        }
    }

    private static void replayAnimation(Store<EntityStore> store, BalloonFlight.Passenger p, State s, long now) {
        if (s.noMannequin || (!valid(s.mannequin) && !valid(s.twin))) {
            return;
        }
        long due = s.anims == 0 ? ANIM_FIRST_MS : s.anims == 1 ? ANIM_SECOND_MS : ANIM_REPEAT_MS;
        if (now - (s.anims < 2 ? s.createdMs : s.lastAnimMs) < due) {
            return;
        }
        s.anims++;
        s.lastAnimMs = now;
        BalloonFlight.Pose pose = BalloonFlight.Pose.DEFAULT;
        for (Ref<EntityStore> m : java.util.Arrays.asList(s.mannequin, s.twin)) {
            if (valid(m)) {
                try {
                    AnimationUtils.playAnimation(m, pose.slot(), pose.animId(), true, store);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Pose assise du mannequin de %s impossible", p.name);
                }
            }
        }
    }

    /**
     * Removes the anchor, the mannequins and the hidden-body rule of a passenger. Safe to call at any time and several times (it is called by
     * PassengerCamera.reset on every exit path, and when the dual rendering collapses). Never throws.
     */
    static void remove(Store<EntityStore> store, BalloonFlight.Passenger p) {
        State s = p.seatView;
        p.seatView = null;
        try {
            ViewFilter.clear(p.ref);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Règle du corps du passager %s non retirée", p.name);
        }
        dispose(store, s, false);
    }

    /** Deletes the entities of a state (and its body rule unless keepBody). Works with an invalid player ref. Never throws. */
    private static void dispose(Store<EntityStore> store, State s, boolean keepBody) {
        if (s == null) {
            return;
        }
        try {
            if (s.registry != null) {
                s.registry.remove(s);
            }
            if (!keepBody && s.bodyRef != null) {
                ViewFilter.clear(s.bodyRef);
            }
            for (Ref<EntityStore> r : java.util.Arrays.asList(s.mannequin, s.twin)) {
                if (r != null) {
                    ViewFilter.clear(r);
                    if (r.isValid()) {
                        store.removeEntity(r, RemoveReason.REMOVE);
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vue du siège non supprimée");
        }
    }

    /** End of a flight: deletes every seat view registered for it, whatever happened to the passenger list. Never throws. */
    static void drain(Store<EntityStore> store, List<State> registry) {
        for (State s : List.copyOf(registry)) {
            dispose(store, s, false);
        }
        registry.clear();
    }

    /** Removes the seat views of the passengers of a flight (end of flight, collapse of the dual rendering). */
    static void removeAll(Store<EntityStore> store, Collection<BalloonFlight.Passenger> passengers) {
        for (BalloonFlight.Passenger p : passengers) {
            remove(store, p);
        }
    }

    /** Removes the entities an interrupted flight may have left in a world that stayed loaded (resume, deterministic UUIDs). Never throws. */
    static void removeOrphans(World world, Store<EntityStore> store, UUID vehicleUuid, Collection<UUID> players) {
        if (vehicleUuid == null || players == null) {
            return;
        }
        for (UUID player : players) {
            for (UUID id : new UUID[]{anchorUuid(vehicleUuid, player), mannequinUuid(vehicleUuid, player, false),
                    mannequinUuid(vehicleUuid, player, true)}) {
                try {
                    Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(id);
                    if (ref != null && ref.isValid()) {
                        ViewFilter.clear(ref);
                        store.removeEntity(ref, RemoveReason.REMOVE);
                        LOGGER.at(Level.INFO).log("Entité de vue de siège restée dans le monde supprimée (%s)", id);
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Entité de vue de siège restée dans le monde non supprimée");
                }
            }
        }
    }

    /** Diagnostic text (English, short). */
    static String describe(BalloonFlight.Passenger p) {
        State s = p.seatView;
        if (s == null) {
            return "seat view none";
        }
        return "seat view mannequin "
                + (s.noMannequin ? "skipped (no model or skin)" : (valid(s.mannequin) ? "ok" : "MISSING") + (s.builtDual ? ", twin " + (valid(s.twin) ? "ok" : "MISSING") : ""))
                + ", body hidden " + (ViewFilter.ruleOf(p.ref) != null);
    }
}
