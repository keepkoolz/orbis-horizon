package com.keepkoolz.orbishorizon;

import org.joml.Vector3d;

/**
 * Pure maths of the airship prototype: free-angle version of Rotation.rotateYaw. Verified offline against
 * Rotation.rotateY for the 4 quarter turns (see the report): yaw theta maps (x, z) to
 * (x cos + z sin, -x sin + z cos), so Ninety (theta = pi/2) sends (x, y, z) to (z, y, -x), like the game.
 * The bow of the airship (prefab -z) points to (-sin theta, -cos theta) after rotation, which is the direction a player looks
 * at when his yaw is theta (yaw 0 looks at -Z, yaw 90 degrees at -X).
 */
final class AirshipMath {

    private AirshipMath() {
    }

    /** Rotates the vector (x, y, z) around Y by theta radians, same convention as Rotation.rotateYaw. */
    static Vector3d rotateY(double x, double y, double z, double theta, Vector3d out) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        return out.set(x * c + z * s, y, -x * s + z * c);
    }

    /** World point (centre of the cell) of the prefab cell (cx, cy, cz) for a ship whose pivot "feet" point is at pos. */
    static Vector3d cellCenter(Vector3d pos, double theta, double pivotX, double pivotY, double pivotZ,
                               int cx, int cy, int cz, Vector3d out) {
        rotateY(cx - pivotX, cy - pivotY, cz - pivotZ, theta, out);
        return out.add(pos.x, pos.y + 0.5, pos.z);
    }

    /** Smallest signed angle of a, in (-pi, pi]. */
    static double wrapPi(double a) {
        return Math.IEEEremainder(a, 2 * Math.PI);
    }

    /** Moves current toward target by at most maxDelta. */
    static double approach(double current, double target, double maxDelta) {
        if (current < target) {
            return Math.min(target, current + maxDelta);
        }
        return Math.max(target, current - maxDelta);
    }
}
