package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Map;
import java.util.function.IntPredicate;

/**
 * the single place computing where a deployment crate puts its structure. The three deploy interactions and the held-crate
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
     * Boat placement check. Prefab layers at or under the boat's waterline (-2 for the steamboat, 0 for the skiff)
     * are the hull under water and must be water, the layers above must be empty (no block, no liquid). Every cell is tested,
     * filler cells included.
     * @return null if the boat fits, otherwise the refusal key
     */
    static String steamboatFit(StructureShape shape, Vector3i origin, Rotation rotation, CellState state, int waterline) {
        Fault fault = steamboatFault(shape, origin, rotation, state, waterline);
        return fault == null ? null : fault.key();
    }

    /** First faulty cell of a steamboat fit: the refusal key, the cell in world coordinates and whether it is a hull cell. */
    record Fault(String key, Vector3i at, boolean hull) {
    }

    /**
     * Same check as {@link #steamboatFit} but returns the first faulty cell: prefab layers at or under the waterline must be water,
     * the layers above must be empty. A faulty hull cell wins over a blocked cell above water and returns at once.
     * @return null if the boat fits
     */
    static Fault steamboatFault(StructureShape shape, Vector3i origin, Rotation rotation, CellState state, int waterline) {
        Fault blocked = null;
        for (StructureShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            int st = state.at(p.x, p.y, p.z);
            if (c.y() <= waterline) {
                if (st != 1) {
                    return new Fault("deploy.steamboat.noWater", p, true);
                }
            } else if (st != 0 && blocked == null) {
                blocked = new Fault("deploy.steamboat.blocked", p, false);
            }
        }
        return blocked;
    }

    /** The same state with the given cells read as empty (0). Used to check a site as it would be once those cells are cleared. */
    static CellState withEmptied(CellState base, java.util.Set<Vector3i> emptied) {
        return (x, y, z) -> emptied.contains(new Vector3i(x, y, z)) ? 0 : base.at(x, y, z);
    }

    /** The same state with the given cells read as water (1), whatever they hold. Used to check a berth as it would be once dredged. */
    static CellState withWater(CellState base, java.util.Set<Vector3i> water) {
        return (x, y, z) -> water.contains(new Vector3i(x, y, z)) ? 1 : base.at(x, y, z);
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
     * Same reading as {@link #worldState} for an ice-breaker boat: floe ice and a broken empty cell under the waterline read as
     * water, floe ice above it as empty, iceberg ice as blocked ({@link IceBreaker#placementState}). surfaceY is the world height
     * of the waterline layer; ice above surfaceY + {@link IceBreaker#MAX_FLOE_HEIGHT} makes an iceberg.
     */
    static CellState iceState(com.hypixel.hytale.server.core.universe.world.World world, int surfaceY) {
        com.hypixel.hytale.server.core.universe.world.accessor.SectionReader reader =
                new com.hypixel.hytale.server.core.universe.world.accessor.SectionReader(world.getChunkStore());
        IceBreaker.CellReader cells = (x, y, z) -> IceBreaker.kindAt(reader, x, y, z);
        return (x, y, z) -> {
            if (!reader.hasStorageInMemory(x, y, z) && !BalloonManager.outOfHeight(y)) {
                return 2; // an unloaded cell refuses a placement, as worldState does
            }
            return IceBreaker.placementState(cells, x, y, z, y <= surfaceY, surfaceY);
        };
    }

    /**
     * Boat origin: the crate's computed origin with y replaced by surface - waterline (the waterline layer lands on the highest water
     * cell: surface + 2 for the steamboat, the surface itself for the skiff). The search column is the targeted block's x,z (the computed origin's when nothing is targeted), starting just above
     * the targeted block. If the targeted block is itself water, the search starts at its y so aiming at the surface works.
     * When the start cell is air, the search goes down through air, BOAT_SURFACE_SCAN_DOWN cells at most, to the first water cell:
     * with nothing targeted the start is the player's feet, which may stand at or above the water level.
     * @return the origin, or null when there is no water column (refusal deploy.steamboat.noWater)
     */
    static final int BOAT_SURFACE_SCAN_DOWN = 4;

    /** A boat's stern lands at least this many blocks in front of the player, so the hull never wraps around them. */
    static final int BOAT_MIN_AHEAD = 2;

    /**
     * Blocks to push a boat forward (along the player's facing, -z of the facing quarter turn) so that its stern, at the targeted
     * cell or at the player's own cell when nothing is targeted, is at least BOAT_MIN_AHEAD blocks in front of the player.
     */
    static int boatPush(Vector3i stern, Vector3i playerCell, Rotation facing) {
        Vector3i forward = facing.rotateYaw(new Vector3i(0, 0, -1), new Vector3i());
        int ahead = (stern.x - playerCell.x) * forward.x + (stern.z - playerCell.z) * forward.z;
        return Math.max(0, BOAT_MIN_AHEAD - ahead);
    }

    /** The cell moved n blocks forward along the facing (a new vector, the argument is left as is; null stays null). */
    static Vector3i forward(Vector3i cell, Rotation facing, int n) {
        if (cell == null) {
            return null;
        }
        Vector3i f = facing.rotateYaw(new Vector3i(0, 0, -n), new Vector3i());
        return new Vector3i(cell.x + f.x, cell.y + f.y, cell.z + f.z);
    }

    static Vector3i steamboatOrigin(CellState state, Vector3i target, Vector3i computed, int waterline) {
        int cx = target != null ? target.x : computed.x;
        int cz = target != null ? target.z : computed.z;
        int ty = target != null ? target.y : computed.y;
        int startY = state.at(cx, ty, cz) == 1 ? ty : ty + 1;
        // Air at the start (nothing targeted and the player's feet at or above the water level): go down through air to the water.
        for (int y = startY; y >= startY - BOAT_SURFACE_SCAN_DOWN && state.at(cx, y, cz) == 0; y--) {
            if (state.at(cx, y - 1, cz) == 1) {
                startY = y - 1;
                break;
            }
        }
        Integer surface = waterSurfaceY(y -> state.at(cx, y, cz) == 1, startY, BalloonManager.WORLD_MAX_Y);
        if (surface == null) {
            return null;
        }
        return new Vector3i(computed.x, surface - waterline, computed.z);
    }

    /**
     * Boat station origin: x and z as computed, y snapped to the water surface under the pier end (prefab cell
     * BOAT_STATION_PIER_END), so the prefab water surface (y -1) lands on the real one: origin.y = surface + 1.
     * The column is scanned downward from 3 above to 10 below the computed y: air is skipped, the first water cell starts the
     * climb to the surface, any other block or liquid stops the search.
     * @return the origin, or null when there is no water there (refusal deploy.steamboat.noWater)
     */
    static Vector3i boatStationOrigin(CellState state, Vector3i computed, Rotation rotation) {
        return boatStationOrigin(state, computed, rotation, Deployables.BOAT_STATION_PIER_END);
    }

    /** Same search with the pier end given in the prefab frame: the whole station's pier end, or {0, 0, 0} for the pier end plan, whose anchor is the pier end. */
    static Vector3i boatStationOrigin(CellState state, Vector3i computed, Rotation rotation, int[] pierEnd) {
        Vector3i end = rotation.rotateYaw(new Vector3i(pierEnd[0], pierEnd[1], pierEnd[2]), new Vector3i());
        int cx = computed.x + end.x, cz = computed.z + end.z;
        for (int y = computed.y + 3; y >= computed.y - 10; y--) {
            int st = state.at(cx, y, cz);
            if (st == 1) {
                Integer surface = waterSurfaceY(yy -> state.at(cx, yy, cz) == 1, y, BalloonManager.WORLD_MAX_Y);
                return surface == null ? null : new Vector3i(computed.x, surface + 1, computed.z);
            }
            if (st != 0) {
                return null;
            }
        }
        return null;
    }

    /** Prefab-frame shift from the aimed cell to the pier end plan origin: the aimed cell sits at local (0, y, -4), one row behind the land row of the deck (z -3). */
    static final int[] PIER_PLAN_AIM_SHIFT = {0, 0, 4};

    /**
     * Origin of the pier end plan from the origin computed for the aimed cell. The player aims at the last block of their own
     * pier, so the deck must start right after it toward the sea: the origin moves by {@link #PIER_PLAN_AIM_SHIFT} turned with
     * the plan, which leaves the aimed cell just behind the land row of the deck and covers none of the player's blocks.
     */
    static Vector3i pierPlanOrigin(Vector3i computed, Rotation rotation) {
        Vector3i shift = rotation.rotateYaw(new Vector3i(PIER_PLAN_AIM_SHIFT[0], PIER_PLAN_AIM_SHIFT[1], PIER_PLAN_AIM_SHIFT[2]), new Vector3i());
        return new Vector3i(computed).add(shift);
    }

    /** Rotations tried by {@link #pierPlanRotation}, as quarter turns added to the look rotation: the look itself, then a quarter turn forward, a quarter turn back, then the opposite. */
    private static final Rotation[] PIER_PLAN_TURNS = {Rotation.None, Rotation.Ninety, Rotation.TwoSeventy, Rotation.OneEighty};

    /**
     * Rotation of the pier end plan, read from the player's construction around the aimed block so that aiming at the same block
     * gives the same result whatever the angle of the look. A rotation is a candidate when the four cells beyond the aimed block
     * in its seaward direction (the line of the deck, at the aimed height) hold no block or other liquid, and when
     * {@link #boatStationOrigin} finds water under the sea-side row for the origin that {@link #pierPlanOrigin} gives. Candidates
     * whose land side (the cell behind the aimed block) is a block are preferred: the construction continues behind the aimed
     * block, so the pier end continues it. Among the preferred candidates, or among all of them when none is preferred, the look
     * breaks the tie: the rotation closest to it, in the order of {@link #PIER_PLAN_TURNS} (the look, a quarter turn forward, a
     * quarter turn back, the opposite). Without any candidate the look rotation is returned, so the usual refusals apply.
     *
     * @param computed     the origin {@link #compute} gives for the aimed block (only its height shift matters, it does not depend on the rotation)
     * @param aimed        the targeted block, the last block of the player's pier
     * @param lookRotation the rotation from the player's look, including the crate's rotation offset
     */
    static Rotation pierPlanRotation(CellState state, Vector3i computed, Vector3i aimed, Rotation lookRotation) {
        Rotation best = null;
        boolean bestPreferred = false;
        for (Rotation turn : PIER_PLAN_TURNS) {
            Rotation r = lookRotation.add(turn);
            Vector3i d = r.rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
            boolean room = true;
            for (int k = 1; k <= 4 && room; k++) {
                room = state.at(aimed.x + d.x * k, aimed.y, aimed.z + d.z * k) != 2;
            }
            if (!room || pierPlanSeaOrigin(state, computed, r) == null) {
                continue;
            }
            boolean preferred = state.at(aimed.x - d.x, aimed.y, aimed.z - d.z) == 2;
            if (best == null || (preferred && !bestPreferred)) {
                best = r;
                bestPreferred = preferred;
            }
        }
        return best != null ? best : lookRotation;
    }

    /** Block name suffix of the deck slabs of the pier end plan (the prefab names, and the names the biome look gives them). */
    static final String PIER_DECK_SLAB_SUFFIX = "_Planks_Half";

    /** Rotation index of a slab that is not turned (RotationTuple.NONE_INDEX). */
    static final int SLAB_UNTURNED = 0;

    /** True when a block name is a deck slab of the pier end plan. */
    static boolean isPierDeckSlab(String blockName) {
        return blockName != null && blockName.endsWith(PIER_DECK_SLAB_SUFFIX);
    }

    /**
     * Rotation index of the deck slabs of the pier end plan: the one of the aimed block when it is a half slab lying flat in the
     * lower half of its cell (index 0 to 3: yaw only), else the unturned slab, so a slab on its side or upside down is not copied onto the deck (a slab placed on the ground is stored unturned: VariantRotation.DoublePipe.verify drops the yaw of a slab whose pitch is None).
     * The paste would otherwise turn them with the paste rotation (PrefabRotation adds it to the yaw of the block).
     */
    static int pierDeckSlabRotation(boolean aimedIsHalfSlab, int aimedRotationIndex) {
        return aimedIsHalfSlab && aimedRotationIndex >= 0 && aimedRotationIndex <= 3 ? aimedRotationIndex : SLAB_UNTURNED;
    }

    /**
     * True for a half slab: a model block with the half block hitbox whose rotation is the double pipe one (the asset of
     * Wood_*_Planks_Half: DrawType Model, HitboxType Block_Half, VariantRotation DoublePipe).
     */
    static boolean isHalfSlab(com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType type) {
        return type != null
                && type.getVariantRotation() == com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation.DoublePipe
                && "Block_Half".equals(type.getHitboxType());
    }

    /**
     * Rotation index of the deck slabs of the pier end plan for the aimed block, read from the world (a section not loaded
     * counts as no slab). Never throws.
     */
    static int pierDeckRotation(com.hypixel.hytale.server.core.universe.world.World world, Vector3i aimed) {
        try {
            com.hypixel.hytale.server.core.universe.world.accessor.SectionReader reader =
                    new com.hypixel.hytale.server.core.universe.world.accessor.SectionReader(world.getChunkStore());
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType type = SectionBlocks.typeAt(reader, aimed.x, aimed.y, aimed.z);
            boolean slab = isHalfSlab(type);
            return pierDeckSlabRotation(slab, slab ? reader.getRotationIndex(aimed.x, aimed.y, aimed.z) : SLAB_UNTURNED);
        } catch (RuntimeException e) {
            Telemetry.error("deploy", e, "pier-deck-rotation", null);
            return SLAB_UNTURNED;
        }
    }

    /** Origin of the pier end plan for a rotation: {@link #pierPlanOrigin} then the water surface under the pier end ({@link #boatStationOrigin}), or null without water. */
    static Vector3i pierPlanSeaOrigin(CellState state, Vector3i computed, Rotation rotation) {
        return boatStationOrigin(state, pierPlanOrigin(computed, rotation), rotation, new int[]{0, 0, 0});
    }

    /** The cell that stops the {@link #boatStationOrigin} search (first state 2 going down the column), or null when none does. */
    static Vector3i boatStationOriginBlocker(CellState state, Vector3i computed, Rotation rotation) {
        Vector3i end = rotation.rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], Deployables.BOAT_STATION_PIER_END[1],
                Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
        int cx = computed.x + end.x, cz = computed.z + end.z;
        for (int y = computed.y + 3; y >= computed.y - 10; y--) {
            int st = state.at(cx, y, cz);
            if (st == 1) {
                return null;
            }
            if (st != 0) {
                return new Vector3i(cx, y, cz);
            }
        }
        return null;
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
            // the camp packs. Same values as the legacy tent crates; the sleeping bag is shifted by one block in X so the
            // 3 x 3 camp is centred on the targeted block once turned.
            Map.entry("Camping_Pack_Bedroll", new Crate("Camping_Bedroll.prefab.json", new Vector3i(-1, 1, 0), Rotation.Ninety)),
            Map.entry("Camping_Pack_Tent", new Crate("Camping_Tent.prefab.json", new Vector3i(0, 1, -3), Rotation.Ninety)),
            Map.entry("Camping_Pack_Tent_Big", new Crate("Camping_Tent_Big.prefab.json", new Vector3i(0, 1, -1), Rotation.OneEighty)),
            Map.entry("Steamboat_Crate", new Crate("Steamboat.prefab.json", new Vector3i(0, 0, -12), Rotation.None)),
            // the skiff: the targeted cell is the stern, as for the steamboat (its stern is at z 4, the box has a free cell behind it).
            Map.entry("Steamboat_Skiff_Crate", new Crate("Steamboat_Skiff.prefab.json", new Vector3i(0, 0, -4), Rotation.None)),
            // the quay plans of the stations (PlaceQuayInteraction): the pad and the pier end sit on the targeted block, the whole
            // stations are turned so that their pad or pier end faces away from the player.
            Map.entry("Hotair_Balloon_Station_Plan_Pad", new Crate("Balloon_Station_Pad.prefab.json", new Vector3i(0, 1, 0), Rotation.None)),
            Map.entry("Hotair_Balloon_Station_Plan_Full", new Crate("Balloon_Landing_zone.prefab.json", new Vector3i(0, 1, 0), Rotation.Ninety)),
            Map.entry("Hotair_Balloon_Boat_Station_Plan_Pier", new Crate("Boat_Station_Pier.prefab.json", new Vector3i(0, 1, 0), Rotation.OneEighty)),
            Map.entry("Hotair_Balloon_Boat_Station_Plan_Full", new Crate("Boat_Station.prefab.json", new Vector3i(0, 1, 0), Rotation.OneEighty)));
}
