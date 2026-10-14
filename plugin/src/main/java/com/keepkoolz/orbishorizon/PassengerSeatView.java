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
 * T118: seat view of a CAMERA-mode passenger (balloons with seats, dry-descent pilot, transit balloons, airship). Three things, all
 * built from the same few lines and removed together (PassengerCamera.reset, the single exit point of the pose):
 *
 * - a camera anchor: an invisible entity (like a light) mounted on the entity the passenger sees, at the seat's eye point, visible to
 *   this passenger only. The camera is attached to it with a zero offset. A fixed attach offset sent in the camera packet is NOT rotated by the
 *   client with the entity yaw (the airship's view drifted when it turned), whereas the mount offset of an entity is (validated for the airship lights).
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

    /** What the caller knows about the vehicle and the seat, for one tick. airship: only the airship turns, so only it gets the relative camera yaw (T119). flight: T124, the balloon flight (null for the airship), balloons get the external view. */
    record Ctx(UUID vehicleUuid, Ref<EntityStore> main, Ref<EntityStore> observer, boolean dual, UUID pilot, Vector3f seatOffset,
               Vector3d seatWorld, List<State> registry, boolean airship, BalloonFlight flight) {
    }

    /** Entities of one passenger's seat view. */
    static final class State {
        Ref<EntityStore> anchor;
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

    /** Anchor point of the camera for a seat offset (entity frame): the seat offset plus the eye height. Pure. */
    static Vector3f eyeOffset(Vector3f seatOffset) {
        return new Vector3f(seatOffset.x, seatOffset.y + TransitConstants.VIEW_EYE_HEIGHT_ABOVE_SEAT, seatOffset.z);
    }

    /**
     * Camera position offset when the camera is attached to the vehicle entity itself (balloons, airship fallback): the client adds
     * positionOffset in the world frame, without turning it with the entity yaw. So the world offset from the entity to the seat is
     * used, plus the eye height. The mount offset (entity frame, x and z inverted for the balloon's yaw of rotation + pi) put the
     * camera on the opposite seat of the ring: a passenger found himself inside the mannequin of the passenger seated there
     * (11 October 2026, two passengers on seats 2 and 7 of a transit balloon). Falls back to the mount offset if the entity has no transform.
     */
    static Vector3f worldEyeOffset(Store<EntityStore> store, Ref<EntityStore> entity, Vector3d seatWorld, Vector3f seatOffset) {
        TransformComponent t = valid(entity) ? store.getComponent(entity, TransformComponent.getComponentType()) : null;
        if (t == null) {
            return eyeOffset(seatOffset);
        }
        Vector3d e = t.getPosition();
        return new Vector3f((float) (seatWorld.x - e.x), (float) (seatWorld.y - e.y) + TransitConstants.VIEW_EYE_HEIGHT_ABOVE_SEAT,
                (float) (seatWorld.z - e.z));
    }

    private static UUID anchorUuid(UUID vehicle, UUID player) {
        return BalloonLights.uuidFor(vehicle, "cam:" + player);
    }

    private static UUID mannequinUuid(UUID vehicle, UUID player, boolean twin) {
        return BalloonLights.uuidFor(vehicle, "man:" + player + (twin ? BalloonLights.TWIN_SUFFIX : ""));
    }

    /**
     * Every tick of a CAMERA passenger: builds or rebuilds the view when needed, keeps the entities on their world point, replays Sit on the
     * mannequins and sends the camera once the anchor is known to the client.
     */
    static void sync(Store<EntityStore> store, BalloonFlight.Passenger p, Ctx c, long now) {
        try {
            boolean dual = c.dual() && c.observer() != null && c.observer().isValid();
            Ref<EntityStore> target = dual ? c.observer() : c.main();
            if (target == null || !target.isValid()) {
                return;
            }
            State s = p.seatView;
            if (s != null && (s.target != target || s.builtDual != dual || gone(s.anchor) || gone(s.mannequin) || gone(s.twin))) {
                // I2: the replacement is built before the old one is deleted, the body stays hidden, and the camera goes to the entity
                // the client sees right away (fixed offset) until the new anchor is known to it.
                State old = s;
                s = build(store, p, c, target, dual, now);
                p.seatView = s;
                dispose(store, old, true);
                if (c.flight() != null && !c.airship()) {
                    PassengerCamera.attachExternal(store, c.flight(), p, target);
                } else {
                    PassengerCamera.attach(store, p, target, worldEyeOffset(store, target, c.seatWorld(), c.seatOffset()), false);
                }
            } else if (s == null) {
                s = build(store, p, c, target, dual, now);
                p.seatView = s;
            }
            Vector3d eye = new Vector3d(c.seatWorld()).add(0, TransitConstants.VIEW_EYE_HEIGHT_ABOVE_SEAT, 0);
            place(store, s.anchor, eye);
            copyYaw(store, s.anchor, target); // T119: the anchor turns with the entity the passenger sees (camera yaw relative to the vehicle)
            place(store, s.mannequin, c.seatWorld());
            place(store, s.twin, c.seatWorld());
            replayAnimation(store, p, s, now);
            if (c.flight() != null && !c.airship()) {
                // T124: external view for balloons, re-sent when the travel direction changes or the entity the client sees changes.
                NetworkId tn = store.getComponent(target, NetworkId.getComponentType());
                if (tn != null && p.cameraOn && PassengerCamera.directionChanged(c.flight(), p)) {
                    PassengerCamera.attachExternal(store, c.flight(), p, target);
                }
                if (tn != null && now - s.createdMs >= CAMERA_DELAY_MS && (!p.cameraOn || tn.getId() != p.cameraTargetId)) {
                    PassengerCamera.attachExternal(store, c.flight(), p, target);
                }
                return;
            }
            // Without an anchor (airship creation failed) the camera goes to the vehicle entity with the eye offset (fixed, drifts in airship turns).
            Ref<EntityStore> camRef = valid(s.anchor) ? s.anchor : target;
            NetworkId an = store.getComponent(camRef, NetworkId.getComponentType());
            if (an != null && now - s.createdMs >= CAMERA_DELAY_MS && (!p.cameraOn || an.getId() != p.cameraTargetId)) {
                PassengerCamera.attach(store, p, camRef, valid(s.anchor) ? new Vector3f() : worldEyeOffset(store, camRef, c.seatWorld(), c.seatOffset()),
                        valid(s.anchor) && c.airship());
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
        Vector3d eye = new Vector3d(c.seatWorld()).add(0, TransitConstants.VIEW_EYE_HEIGHT_ABOVE_SEAT, 0);
        // Balloons keep a fixed yaw: their camera goes straight to the vehicle entity with the eye offset (T117, validated smooth). A camera
        // on a mounted anchor made the transit balloon shake for its passengers (same cause as the chain-mount shake of T115). Only the
        // airship, which turns, needs the anchor.
        s.anchor = c.airship() ? spawn(store, null, anchorUuid(c.vehicleUuid(), p.uuid), eye, target, eyeOffset(c.seatOffset()), null) : null;
        if (s.anchor != null) {
            ViewFilter.only(s.anchor, p.uuid);
        }
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
                } else if (c.airship()) {
                    ViewFilter.hide(s.mannequin, p.uuid);
                } // T124: balloons, no pilot: the passenger sees his own mannequin from outside

            }
            s.twin = spawn(store, model, mannequinUuid(c.vehicleUuid(), p.uuid, true), c.seatWorld(), c.observer(), c.seatOffset(), skin);
            if (s.twin != null) {
                Set<UUID> hidden = new HashSet<>();
                if (c.airship()) {
                    hidden.add(p.uuid); // T124: a balloon passenger sees his twin (he sees the observer entity from outside)
                }
                if (c.pilot() != null) {
                    hidden.add(c.pilot());
                }
                if (!hidden.isEmpty()) {
                    ViewFilter.hideAll(s.twin, hidden);
                }
            }
        } else {
            s.mannequin = spawn(store, model, mannequinUuid(c.vehicleUuid(), p.uuid, false), c.seatWorld(), target, c.seatOffset(), skin);
            if (s.mannequin != null && c.airship()) {
                ViewFilter.hide(s.mannequin, p.uuid); // T124: balloon passengers see their own mannequin
            }
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
            for (Ref<EntityStore> r : java.util.Arrays.asList(s.anchor, s.mannequin, s.twin)) {
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
        return "seat view anchor " + (valid(s.anchor) ? "ok" : s.anchor == null ? "none" : "MISSING") + ", mannequin "
                + (s.noMannequin ? "skipped (no model or skin)" : (valid(s.mannequin) ? "ok" : "MISSING") + (s.builtDual ? ", twin " + (valid(s.twin) ? "ok" : "MISSING") : ""))
                + ", body hidden " + (ViewFilter.ruleOf(p.ref) != null);
    }
}
