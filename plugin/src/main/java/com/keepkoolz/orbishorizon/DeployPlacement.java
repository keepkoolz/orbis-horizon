package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Map;
import java.util.function.IntPredicate;

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

    /** Content of one world cell for the steamboat fit: 0 empty, 1 water, 2 block or any other liquid. */
    @FunctionalInterface
    interface CellState {
        int at(int x, int y, int z);
    }

    /**
     * Highest water cell of a continuous water column. Starts at startY (which must be water) and climbs up to the absolute height maxY.
     * Returns null when startY itself is not water (no water above the targeted block).
     */
    static Integer waterSurfaceY(IntPredicate isWater, int startY, int maxY) {
        if (!isWater.test(startY)) {
            return null;
        }
        int y = startY;
        while (y < maxY && isWater.test(y + 1)) {
            y++;
        }
        return y;
    }

    /**
     * Steamboat placement check. Prefab layers y <= -3 are the hull under the waterline and must be water, layers y >= -2
     * must be empty (no block, no liquid). Every cell is tested, filler cells included.
     * @return null if the boat fits, otherwise the refusal key
     */
    static String steamboatFit(StructureShape shape, Vector3i origin, Rotation rotation, CellState state) {
        boolean blocked = false;
        for (StructureShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            int st = state.at(p.x, p.y, p.z);
            if (c.y() <= -3) {
                if (st != 1) {
                    return "deploy.steamboat.noWater";
                }
            } else if (st != 0) {
                blocked = true;
            }
        }
        return blocked ? "deploy.steamboat.blocked" : null;
    }

    /** CellState reading the world (0.7 section reader). Out of height, solid blocks and non-water liquids count as 2. */
    static CellState worldState(com.hypixel.hytale.server.core.universe.world.World world) {
        com.hypixel.hytale.server.core.universe.world.accessor.SectionReader reader =
                new com.hypixel.hytale.server.core.universe.world.accessor.SectionReader(world.getChunkStore());
        return (x, y, z) -> {
            if (BalloonManager.outOfHeight(y)) {
                return 2;
            }
            if (!reader.hasStorageInMemory(x, y, z)) {
                return 2;
            }
            String liquid = BalloonManager.liquidAt(reader, x, y, z);
            if (liquid == null) {
                return BalloonManager.blocksBalloon(reader, x, y, z) ? 2 : 0;
            }
            return SteamboatWater.isWaterCell(reader, x, y, z, liquid) ? 1 : 2;
        };
    }

    /**
     * Steamboat origin: the crate's computed origin with y replaced by surface + 3 (prefab layer -3 lands on the highest water
     * cell). The search column is the targeted block's x,z (the computed origin's when nothing is targeted), starting just above
     * the targeted block. If the targeted block is itself water, the search starts at its y so aiming at the surface works.
     * @return the origin, or null when there is no water column (refusal deploy.steamboat.noWater)
     */
    static Vector3i steamboatOrigin(CellState state, Vector3i target, Vector3i computed) {
        int cx = target != null ? target.x : computed.x;
        int cz = target != null ? target.z : computed.z;
        int ty = target != null ? target.y : computed.y;
        int startY = state.at(cx, ty, cz) == 1 ? ty : ty + 1;
        Integer surface = waterSurfaceY(y -> state.at(cx, y, cz) == 1, startY, BalloonManager.WORLD_MAX_Y);
        if (surface == null) {
            return null;
        }
        return new Vector3i(computed.x, surface + 3, computed.z);
    }

    /** Placement parameters of one crate, as written in its JSON (Interactions.Secondary). */
    record Crate(String prefabPath, Vector3i offset, Rotation rotationOffset) {
    }

    /**
     * Crate item id to its deployment parameters. The values are those of the crates' JSON files (the interaction reads them from
     * its own configuration, which is not reachable from an item at runtime): TransitTests.deployPlacement compares this table to
     * the JSON files so the two cannot drift apart.
     */
    static final Map<String, Crate> CRATES = Map.ofEntries(
            Map.entry("Hotair_Balloon_Crate", new Crate("Hotair_Balloon.prefab.json", new Vector3i(0, -4, 0), Rotation.OneEighty)),
            Map.entry("Hotair_Balloon_Basic_Crate", new Crate("Hotair_Balloon_Basic.prefab.json", new Vector3i(0, 0, 0), Rotation.OneEighty)),
            Map.entry("Hotair_Balloon_Transport_Crate", new Crate("Hotair_Balloon_Transport.prefab.json", new Vector3i(0, 1, 0), Rotation.None)),
            Map.entry("Airship_Cloudwork_Crate", new Crate("The_Cloudwork.prefab.json", new Vector3i(0, 0, -15), Rotation.None)),
            Map.entry("Camping_Tent_Crate", new Crate("Camping_Tent.prefab.json", new Vector3i(0, 1, -3), Rotation.Ninety)),
            Map.entry("Camping_Tent_Big_Crate", new Crate("Camping_Tent_Big.prefab.json", new Vector3i(0, 1, -1), Rotation.OneEighty)),
            Map.entry("Balloon_Station_Crate", new Crate("Balloon_Landing_zone.prefab.json", new Vector3i(0, 1, 0), Rotation.Ninety)),
            // T153: the camp packs. Same values as the legacy tent crates; the sleeping bag is shifted by one block in X so the
            // 3 x 3 camp is centred on the targeted block once turned.
            Map.entry("Camping_Pack_Bedroll", new Crate("Camping_Bedroll.prefab.json", new Vector3i(-1, 1, 0), Rotation.Ninety)),
            Map.entry("Camping_Pack_Tent", new Crate("Camping_Tent.prefab.json", new Vector3i(0, 1, -3), Rotation.Ninety)),
            Map.entry("Camping_Pack_Tent_Big", new Crate("Camping_Tent_Big.prefab.json", new Vector3i(0, 1, -1), Rotation.OneEighty)),
            Map.entry("Steamboat_Crate", new Crate("Steamboat.prefab.json", new Vector3i(0, 0, -12), Rotation.None)));
}
