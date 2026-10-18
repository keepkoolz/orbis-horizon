package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Map;

/**
 * T121: the single place computing where a deployment crate puts its structure. The three deploy interactions and the held-crate
 * preview call {@link #compute}, so the preview and the real placement can never disagree.
 */
final class DeployPlacement {
    private DeployPlacement() {
    }

    /** Result: the paste origin, the player's facing (quarter turn, it turns the offset) and the paste rotation (facing plus the crate's RotationOffset). */
    record Placement(Vector3i origin, Rotation facing, Rotation rotation) {
    }

    /**
     * @param target    the targeted block, or null (the origin is then the player's position)
     * @param position  the player's position
     * @param yawRadians the player's body yaw (Rotation3f.y)
     */
    static Placement compute(Vector3i target, Vector3d position, double yawRadians, Vector3i offset, Rotation rotationOffset) {
        Rotation facing = Rotation.closestOfDegrees((float) Math.toDegrees(yawRadians));
        Rotation rotation = facing.add(rotationOffset);
        // Origin: the targeted block, otherwise the player's position.
        Vector3i origin = target != null ? new Vector3i(target) : Vector3dUtil.toVector3i(position);
        // The offset only follows the player's direction (Z = in front of them).
        origin.add(facing.rotateYaw(new Vector3i(offset), new Vector3i()));
        return new Placement(origin, facing, rotation);
    }

    /** Placement parameters of one crate, as written in its JSON (Interactions.Secondary). */
    record Crate(String prefabPath, Vector3i offset, Rotation rotationOffset) {
    }

    /**
     * Crate item id to its deployment parameters. The values are those of the crates' JSON files (the interaction reads them from
     * its own configuration, which is not reachable from an item at runtime): TransitTests.deployPlacement compares this table to
     * the JSON files so the two cannot drift apart.
     */
    static final Map<String, Crate> CRATES = Map.of(
            "Hotair_Balloon_Crate", new Crate("Hotair_Balloon.prefab.json", new Vector3i(0, -4, 0), Rotation.OneEighty),
            "Hotair_Balloon_Basic_Crate", new Crate("Hotair_Balloon_Basic.prefab.json", new Vector3i(0, 0, 0), Rotation.OneEighty),
            "Hotair_Balloon_Transport_Crate", new Crate("Hotair_Balloon_Transport.prefab.json", new Vector3i(0, 1, 0), Rotation.None),
            "Airship_Cloudwork_Crate", new Crate("The_Cloudwork.prefab.json", new Vector3i(0, 0, -15), Rotation.None),
            "Camping_Tent_Crate", new Crate("Camping_Tent.prefab.json", new Vector3i(0, 1, -3), Rotation.Ninety),
            "Camping_Tent_Big_Crate", new Crate("Camping_Tent_Big.prefab.json", new Vector3i(0, 1, -1), Rotation.OneEighty),
            "Balloon_Station_Crate", new Crate("Balloon_Landing_zone.prefab.json", new Vector3i(0, 1, 0), Rotation.Ninety),
            // T153: the camp packs. Same values as the legacy tent crates; the sleeping bag is shifted by one block in X so the
            // 3 x 3 camp is centred on the targeted block once turned.
            "Camping_Pack_Bedroll", new Crate("Camping_Bedroll.prefab.json", new Vector3i(-1, 1, 0), Rotation.Ninety),
            "Camping_Pack_Tent", new Crate("Camping_Tent.prefab.json", new Vector3i(0, 1, -3), Rotation.Ninety),
            "Camping_Pack_Tent_Big", new Crate("Camping_Tent_Big.prefab.json", new Vector3i(0, 1, -1), Rotation.OneEighty));
}
