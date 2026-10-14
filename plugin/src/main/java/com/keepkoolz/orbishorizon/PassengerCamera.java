package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.logging.Level;

/**
 * T124: balloons (all types) give their passengers an external view (attachExternal, behind the travel direction, looking at the balloon);
 * the airship keeps the seat view below.
 * Passenger view of a flight (transit balloons, piloted balloons, airship, shared by BalloonManager and AirshipPassengers): a first-person camera attached to the balloon entity the client sees (SetServerCamera, attachedToType
 * EntityId, like SpectatorSystems.applyFollowCamera but not locked and without Spectating), at the seat's eye point, with a fixed random horizontal
 * rotation drawn once per passenger at boarding. The camera cannot look around (an entity-attached camera gives no client look). The body is not mounted: it stays parked in
 * forced flight at zero speed, follows the balloon by itself and is teleported back to the seat only beyond TransitConstants.BODY_SAFETY_DISTANCE.
 * Never throws.
 */
final class PassengerCamera {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private PassengerCamera() {
    }

    /**
     * Sends the camera. Position offset = the offset given by the caller from the entity it attaches to (T118: zero, the target is the
     * seat camera anchor of PassengerSeatView, which already sits at the seat's eye point and turns with the vehicle). Rotation Custom and fixed: a random yaw in [0, 2 pi) drawn once per passenger at boarding, pitch 0.
     * The camera is re-sent (same yaw) when the entity the client sees changes.
     */
    static void attach(Store<EntityStore> store, BalloonFlight.Passenger p, Ref<EntityStore> target, org.joml.Vector3f off) {
        attach(store, p, target, off, false);
    }

    /** T119: camera yaw mode, true (default) = relative to the anchor (turns with the vehicle), false = fixed in the world (T118). Next camera send. */
    static volatile boolean relativeYaw = false; // relative had no visible effect in game (11 October 2026): world yaw (Custom) by default

    /**
     * Same. anchored: the target is the camera anchor of PassengerSeatView, whose yaw copies the vehicle's: with relativeYaw the rotation
     * is AttachedToPlusOffset with the random yaw as offset, so the client composes it with the anchor's rotation. The fallback camera
     * (no anchor yet) stays Custom.
     */
    static void attach(Store<EntityStore> store, BalloonFlight.Passenger p, Ref<EntityStore> target, org.joml.Vector3f off, boolean anchored) {
        try {
            PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
            NetworkId nid = target != null && target.isValid() ? store.getComponent(target, NetworkId.getComponentType()) : null;
            if (pr == null || nid == null) {
                return;
            }
            if (!p.camYawSet) { // drawn once per passenger at boarding, kept when the camera is re-sent
                p.camYaw = randomSeatYaw(java.util.concurrent.ThreadLocalRandom.current());
                p.camYawSet = true;
            }
            ServerCameraSettings s = new ServerCameraSettings();
            s.attachedToType = AttachedToType.EntityId;
            s.attachedToEntityId = nid.getId();
            s.followAttachedEntity = true;
            s.eyeOffset = false;
            s.isFirstPerson = true;
            s.positionType = PositionType.AttachedToPlusOffset;
            s.positionOffset = new Position(off.x, off.y, off.z);
            s.positionLerpSpeed = 1f;
            s.rotationType = RotationType.Custom;
            s.applyLookType = ApplyLookType.LocalPlayerLookOrientation;
            s.allowPitchControls = false;
            s.displayReticle = false;
            s.skipCharacterPhysics = true;
            s.rotation = new Direction(p.camYaw, 0f, 0f);
            if (anchored && relativeYaw) {
                s.rotationType = RotationType.AttachedToPlusOffset;
                s.rotationOffset = new Direction(p.camYaw, 0f, 0f);
            }
            pr.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, s));
            p.cameraOn = true;
            p.cameraTargetId = nid.getId();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Caméra du passager %s non envoyée", p.name);
        }
    }

    /** T124: camera distance (times the balloon size) and height (times the distance) of the external view, op setting, not saved. */
    static volatile double distanceFactor = TransitConstants.VIEW_DISTANCE_FACTOR;
    static volatile double heightFactor = TransitConstants.VIEW_HEIGHT_FACTOR;

    /** External view result: camera offset from the target point (world frame) and look yaw and pitch (radians). */
    record ExternalView(float x, float y, float z, float yaw, float pitch) {
    }

    /**
     * T124: pure framing of the external view. (dirX, dirZ) is the horizontal direction from the target to the camera (any length),
     * size the largest dimension of the balloon: the camera sits distanceFactor * size away horizontally and heightFactor times that
     * above the target. Rotation convention of the T115 external view (validated): yaw = atan2(wx, wz), pitch = atan2(-dy, horizontal).
     * Pinned by TransitTests.externalView.
     */
    static ExternalView externalView(double dirX, double dirZ, double size, double distanceFactor, double heightFactor) {
        double len = Math.max(1e-6, Math.hypot(dirX, dirZ));
        double back = distanceFactor * size;
        double wx = dirX / len * back;
        double wz = dirZ / len * back;
        double dy = heightFactor * back;
        return new ExternalView((float) wx, (float) dy, (float) wz, (float) Math.atan2(wx, wz), (float) Math.atan2(-dy, Math.hypot(wx, wz)));
    }

    /** Aim point (vertical middle of the flight cells relative to the pivot) and size (largest of height, width x, width z) of a balloon type. */
    record Framing(double centreY, double size) {
    }

    private static final java.util.Map<Deployables.Kind, Framing> FRAMING = new java.util.concurrent.ConcurrentHashMap<>();

    /** Framing of a type from its flight cells, computed once per type. */
    static Framing framing(Deployables.Kind kind) {
        return FRAMING.computeIfAbsent(kind, k -> {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            java.util.List<StructureShape.Cell> cells;
            try {
                cells = k.shape().flightCells();
            } catch (java.io.IOException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Forme de %s illisible pour la vue extérieure", k);
                return new Framing(0, 10);
            }
            for (StructureShape.Cell c : cells) {
                minX = Math.min(minX, c.x());
                maxX = Math.max(maxX, c.x());
                minY = Math.min(minY, c.y());
                maxY = Math.max(maxY, c.y());
                minZ = Math.min(minZ, c.z());
                maxZ = Math.max(maxZ, c.z());
            }
            if (minX > maxX) {
                return new Framing(0, 10);
            }
            double h = maxY - minY + 1;
            double size = Math.max(h, Math.max(maxX - minX + 1, maxZ - minZ + 1));
            return new Framing((minY + maxY + 1) / 2.0 - BalloonManager.pivot(k).y, size);
        });
    }

    /**
     * T124: sends the external view of a balloon passenger: camera attached to the entity the client sees, behind the travel direction
     * and above, looking at the middle of the balloon. The position offset is in the world frame (not turned by the client). Direction:
     * opposite to the horizontal move of the entity (before the first move: behind the flight orientation, forward is the prefab -z axis).
     * Re-sent by directionChanged. Never throws.
     */
    static void attachExternal(Store<EntityStore> store, BalloonFlight flight, BalloonFlight.Passenger p, Ref<EntityStore> target) {
        try {
            PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
            NetworkId nid = target != null && target.isValid() ? store.getComponent(target, NetworkId.getComponentType()) : null;
            if (pr == null || nid == null) {
                return;
            }
            if (!p.camDirSet) {
                org.joml.Vector3f fwd = flight.rotation.rotateYaw(new org.joml.Vector3f(0, 0, -1), new org.joml.Vector3f());
                p.camDirX = -fwd.x;
                p.camDirZ = -fwd.z;
                p.camDirSet = true;
                p.camLastX = flight.entityPos.x;
                p.camLastZ = flight.entityPos.z;
            }
            Framing f = framing(flight.kind);
            ExternalView v = externalView(p.camDirX, p.camDirZ, f.size(), distanceFactor, heightFactor);
            ServerCameraSettings s = new ServerCameraSettings();
            s.attachedToType = AttachedToType.EntityId;
            s.attachedToEntityId = nid.getId();
            s.followAttachedEntity = true;
            s.eyeOffset = false;
            s.isFirstPerson = false;
            s.positionType = PositionType.AttachedToPlusOffset;
            // The entity sits at the pivot: the aim point is (0, centreY, 0) from it, the camera is the view offset from the aim point.
            s.positionOffset = new Position(v.x(), f.centreY() + v.y(), v.z());
            s.positionLerpSpeed = 1f;
            s.rotationType = RotationType.Custom;
            s.applyLookType = ApplyLookType.LocalPlayerLookOrientation;
            s.allowPitchControls = false;
            s.displayReticle = false;
            s.skipCharacterPhysics = true;
            s.rotation = new Direction(v.yaw(), v.pitch(), 0f);
            pr.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, s));
            p.cameraOn = true;
            p.cameraTargetId = nid.getId();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Vue extérieure du passager %s non envoyée", p.name);
        }
    }

    /** True when the balloon's travel direction changed enough (after enough horizontal move) to re-send the external view. */
    static boolean directionChanged(BalloonFlight flight, BalloonFlight.Passenger p) {
        if (!p.camDirSet) {
            return false;
        }
        double mx = flight.entityPos.x - p.camLastX;
        double mz = flight.entityPos.z - p.camLastZ;
        if (Math.hypot(mx, mz) < TransitConstants.VIEW_DIRECTION_MIN_MOVE) {
            return false;
        }
        p.camLastX = flight.entityPos.x;
        p.camLastZ = flight.entityPos.z;
        double nx = -mx;
        double nz = -mz;
        double cos = (nx * p.camDirX + nz * p.camDirZ) / (Math.hypot(nx, nz) * Math.max(1e-6, Math.hypot(p.camDirX, p.camDirZ)));
        if (Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) > TransitConstants.VIEW_DIRECTION_CHANGE_DEGREES) {
            p.camDirX = nx;
            p.camDirZ = nz;
            return true;
        }
        return false;
    }

    /** Largest turn of a transit passenger's view away from the travel direction, toward the outside of his seat. */
    static final double TRANSIT_VIEW_SPREAD_DEG = 40;

    /**
     * Camera yaw of a transit passenger: the travel direction (departure to destination, horizontal), turned toward the outside of his
     * seat (seat minus nacelle centre) by at most TRANSIT_VIEW_SPREAD_DEG, so that every passenger sees where the balloon goes. Yaw
     * convention of the mod (forward = (-sin yaw, -cos yaw)), assumed to be the camera's once the camera sits on its own seat (the pi
     * added in October 2026 made up for the camera placed on the opposite seat). Pure, pinned by TransitTests.transitViewYaw.
     */
    static float towardYaw(double travelDx, double travelDz, double outDx, double outDz) {
        double h = Math.atan2(-travelDx, -travelDz);
        if (Math.hypot(outDx, outDz) < 0.1) {
            return (float) h;
        }
        double o = Math.atan2(-outDx, -outDz);
        double max = Math.toRadians(TRANSIT_VIEW_SPREAD_DEG);
        double d = Math.max(-max, Math.min(max, Math.IEEEremainder(o - h, 2 * Math.PI)));
        return (float) (h + d);
    }

    /**
     * Camera yaw of a passenger of a piloted vehicle: from his seat toward the centre of the vehicle (world points, horizontal), so that
     * he faces the inside and not a wall. A seat at the centre (less than 0.1 block) looks along forward. Pure, pinned by
     * TransitTests.transitViewYaw.
     */
    static float inwardYaw(Vector3d seat, Vector3d centre, org.joml.Vector3f forward) {
        double dx = centre.x - seat.x, dz = centre.z - seat.z;
        if (Math.hypot(dx, dz) < 0.1) {
            dx = forward.x;
            dz = forward.z;
        }
        return towardYaw(dx, dz, 0, 0);
    }

    /**
     * A player already seated on a seat block when the vehicle takes off keeps the direction he was looking at: his head yaw becomes
     * the camera yaw (decision of the user, 11 October 2026). To be called before he is unseated. Otherwise the yaw is assigned
     * automatically later (inside of the vehicle, or destination for a transit flight). Never throws.
     */
    static void keepSeatedLook(Store<EntityStore> store, BalloonFlight.Passenger p) {
        try {
            if (p.camYawSet || p.ref == null || !p.ref.isValid()) {
                return;
            }
            com.hypixel.hytale.builtin.mounts.MountedComponent mc =
                    store.getComponent(p.ref, com.hypixel.hytale.builtin.mounts.MountedComponent.getComponentType());
            if (mc == null || mc.getMountedToBlock() == null) {
                return;
            }
            com.hypixel.hytale.server.core.modules.entity.component.HeadRotation h =
                    store.getComponent(p.ref, com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType());
            TransformComponent t = store.getComponent(p.ref, TransformComponent.getComponentType());
            Float yaw = h != null ? Float.valueOf(h.getRotation().y) : t != null ? Float.valueOf(t.getRotation().y) : null;
            if (yaw != null) {
                p.camYaw = yaw;
                p.camYawSet = true;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Regard du passager %s non repris", p.name);
        }
    }

    /** Random camera yaw in [0, 2 pi), horizontal view. Pure (the generator is given), pinned by TransitTests.randomSeatYawRange. */
    static float randomSeatYaw(java.util.random.RandomGenerator rng) {
        float y = (float) (rng.nextDouble() * 2 * Math.PI);
        return y >= (float) (2 * Math.PI) ? 0f : y;
    }

    /** The body follows the balloon by itself; beyond the safety distance it is teleported back to the seat (keeps its chunks loaded). */
    static void safetyTeleport(Store<EntityStore> store, BalloonFlight.Passenger p, Vector3d seat) {
        TransformComponent ct = store.getComponent(p.ref, TransformComponent.getComponentType());
        if (ct == null) {
            return;
        }
        if (ct.getPosition().distance(seat) > TransitConstants.BODY_SAFETY_DISTANCE) {
            store.putComponent(p.ref, Teleport.getComponentType(), Teleport.createForPlayer(seat, new Rotation3f(ct.getRotation())));
        }
    }

    /** Gives the normal camera back (CameraManager.resetCamera), to be called before any teleport to a drop point. Never throws. */
    static void reset(Store<EntityStore> store, BalloonFlight.Passenger p) {
        PassengerSeatView.remove(store, p); // T118: anchor, mannequins and hidden body go with the camera, on every exit path
        if (!p.cameraOn) {
            return;
        }
        p.cameraOn = false;
        p.cameraTargetId = -1;
        try {
            if (!p.ref.isValid()) {
                return;
            }
            PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
            CameraManager cm = store.getComponent(p.ref, CameraManager.getComponentType());
            if (pr == null) {
                return;
            }
            if (cm != null) {
                cm.resetCamera(pr);
            } else {
                pr.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Caméra du passager %s non remise", p.name);
        }
    }
}
