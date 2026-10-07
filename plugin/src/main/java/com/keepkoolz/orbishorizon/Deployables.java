package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;

import org.joml.Vector3f;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Types of structures placed by the mod, locked by the registry (T43). A type is identified by a
 * string (`kind`, written in deployed.json) and gives its prefab, its marker block, its forbidden placement volume
 * (prefab frame, before rotation) and its lock messages. The shape (StructureShape) is read only once,
 * on demand.
 *
 * To add a type: create a Kind constant and add it to ALL.
 */
final class Deployables {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String BALLOON_KIND = "balloon";

    /**
     * Basket volume where placing a block is forbidden, in the prefab frame (whole cells, before
     * rotation): from inside the basket (walking floor at y = 9) up to the burner row (y = 13).
     * A block placed there would stay in the air at take-off. It follows the NACELLE_MIN and NACELLE_MAX volume of
     * BalloonManager, extended upwards (chain and burner). To be changed with the basket.
     */
    private static final int[] PLACE_MIN = {-2, 9, 6};
    private static final int[] PLACE_MAX = {2, 13, 10};

    /** Large balloon. Pivot of the model and gondola volume, prefab frame, before rotation. */
    private static final int[] BALLOON_PIVOT = {0, 9, 8};
    private static final float[] BALLOON_NACELLE_MIN = {-2.0f, 8.5f, 6.0f};
    private static final float[] BALLOON_NACELLE_MAX = {2.0f, 12.0f, 10.0f};

    static final Kind BALLOON = new Kind(BALLOON_KIND, "Hotair_Balloon.prefab.json", StructureShape.ANCHOR_BLOCK, true,
            PLACE_MIN, PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place",
            new BalloonSpec("Hotair_Balloon", "Hotair_Balloon_Crate", BALLOON_PIVOT, BALLOON_NACELLE_MIN, BALLOON_NACELLE_MAX,
                    "Campfire_New_Cartoon", "Flamethrower"));

    static final String BASIC_KIND = "balloon_basic";

    /**
     * Small one-seater balloon (T54). Its forbidden placement volume covers the basket (walking floor y = 2) up to the
     * burner row (y = 5). Same names as the large one with the BASIC_ prefix: scripts/prefab_to_model.py reads them.
     */
    private static final int[] BASIC_PIVOT = {0, 2, 4};
    private static final float[] BASIC_NACELLE_MIN = {-1.5f, 1.5f, 2.5f};
    private static final float[] BASIC_NACELLE_MAX = {1.5f, 4.0f, 5.5f};
    private static final int[] BASIC_PLACE_MIN = {-1, 2, 3};
    private static final int[] BASIC_PLACE_MAX = {1, 5, 5};

    static final Kind BALLOON_BASIC = new Kind(BASIC_KIND, "Hotair_Balloon_Basic.prefab.json", StructureShape.ANCHOR_BLOCK, true,
            BASIC_PLACE_MIN, BASIC_PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place",
            new BalloonSpec("Hotair_Balloon_Basic", "Hotair_Balloon_Basic_Crate", BASIC_PIVOT, BASIC_NACELLE_MIN, BASIC_NACELLE_MAX,
                    "Hotair_Balloon_Basic_Flame", "Hotair_Balloon_Basic_Flamethrower"));

    static final String TRANSPORT_KIND = "balloon_transport";

    /**
     * Animal transport balloon (T72): a balloon the size of the large one (pilot and one passenger on the stool) with an iron cage hanging under
     * the basket. Prefab frame, before rotation. Same names as the other balloons with the TRANSPORT_ prefix:
     * scripts/prefab_to_model.py reads them. The pivot is the pilot's cell (walking floor y = 9, burner row y = 12).
     * The forbidden placement volume is one box: it covers the basket (x -1..1, z -7..-5, up to the burner row), the
     * cage interior (y 1..3) and the column between them, so nothing can be placed in the cage or under the basket.
     */
    private static final int[] TRANSPORT_PIVOT = {0, 9, -6};
    private static final float[] TRANSPORT_NACELLE_MIN = {-1.5f, 8.5f, -7.5f};
    private static final float[] TRANSPORT_NACELLE_MAX = {1.5f, 11.0f, -4.5f};
    private static final int[] TRANSPORT_PLACE_MIN = {-1, 1, -7};
    private static final int[] TRANSPORT_PLACE_MAX = {1, 12, -5};

    // Description of the cage for the next tasks (T73 moves it and opens its side, T74 captures animals). Prefab frame,
    // cage raised and closed, whole cells, bounds included. T72 only describes it: nothing reads these constants yet.
    // The prefab and the flight model always draw the cage closed and raised.

    /** Outer box of the cage: floor y = 0 (stairs and half planks), bars y 1..3, roof y = 4. */
    static final int[] TRANSPORT_CAGE_MIN = {-2, 0, -8};
    static final int[] TRANSPORT_CAGE_MAX = {2, 4, -4};
    /** Inner volume: free cells where a captured animal stands. */
    static final int[] TRANSPORT_CAGE_INSIDE_MIN = {-1, 1, -7};
    static final int[] TRANSPORT_CAGE_INSIDE_MAX = {1, 3, -5};
    /**
     * Opening side of the cage: the 9 Deco_Iron_Bars of the face z = -8, between the two corner blocks (x = +-2, kept).
     * T73 removes these blocks to open the cage and puts them back to close it. A cow (hit box 1.6 x 1.7 in
     * Server/Models/Livestock/Cow.json) fits in this 3 x 3 opening.
     */
    static final int[] TRANSPORT_GATE_MIN = {-1, 1, -8};
    static final int[] TRANSPORT_GATE_MAX = {1, 3, -8};
    /** Chain column of the cage (Deco_Iron_Chain_Small, game block): x, lowest y and z, up to TRANSPORT_CHAIN_TOP_Y, under the basket floor (y = 8). T73 lengthens it. */
    static final int[] TRANSPORT_CHAIN_COLUMN = {0, 5, -6};
    static final int TRANSPORT_CHAIN_TOP_Y = 7;
    /** Cells of the two levers of the basket: cage down and up, gate open and close. */
    static final int[] TRANSPORT_CAGE_LEVER = {0, 9, -7};
    static final int[] TRANSPORT_GATE_LEVER = {0, 9, -5};
    /** Greatest descent of the cage, in blocks. */
    static final int TRANSPORT_MAX_DESCENT = 20;

    /**
     * The transport balloon. Same burner and chain as the others (the burner is the marker). Flame systems: the small
     * balloon's copies, which follow the moving model (the flame is drawn at an intermediate scale by the model JSON files).
     */
    static final Kind BALLOON_TRANSPORT = new Kind(TRANSPORT_KIND, "Hotair_Balloon_Transport.prefab.json", StructureShape.ANCHOR_BLOCK, true,
            TRANSPORT_PLACE_MIN, TRANSPORT_PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place",
            new BalloonSpec("Hotair_Balloon_Transport", "Hotair_Balloon_Transport_Crate", TRANSPORT_PIVOT, TRANSPORT_NACELLE_MIN, TRANSPORT_NACELLE_MAX,
                    "Hotair_Balloon_Basic_Flame", "Hotair_Balloon_Basic_Flamethrower"));

    static final String TENT_KIND = "tent";

    /**
     * Forbidden placement volume of the tent (T44), prefab frame: the bounding box of the recentred prefab
     * (x from -3 to 3, y from 0 to 1, z from -1 to 2). No block can be placed in the tent, even on an empty cell.
     */
    private static final int[] TENT_PLACE_MIN = {-3, 0, -1};
    private static final int[] TENT_PLACE_MAX = {3, 1, 2};

    /** The camping tent (T44). Marker: the game's campfire, unique in the prefab, no burner. */
    static final Kind TENT = new Kind(TENT_KIND, "Camping_Tent.prefab.json", "Bench_Campfire", false,
            TENT_PLACE_MIN, TENT_PLACE_MAX,
            "lock.tent.break", "lock.tent.place", null,
            new TentSpec("Camping_Tent_Crate", "Camping_Tent_Crate_Empty",
                    new float[] {-2f, 0f, 0.5f}, new float[] {-1f, 0f, 0f}));

    static final String TENT_BIG_KIND = "tent_big";

    /** Forbidden placement volume of the big tent, prefab frame: the bounding box of the prefab (x -3..3, y 0..3, z -1..6). */
    private static final int[] TENT_BIG_PLACE_MIN = {-3, 0, -1};
    private static final int[] TENT_BIG_PLACE_MAX = {3, 3, 6};

    /** The big camping tent. Same marker as the small one (the campfire, unique in the prefab), same lock messages. */
    static final Kind TENT_BIG = new Kind(TENT_BIG_KIND, "Camping_Tent_Big.prefab.json", "Bench_Campfire", false,
            TENT_BIG_PLACE_MIN, TENT_BIG_PLACE_MAX,
            "lock.tent.break", "lock.tent.place", null,
            new TentSpec("Camping_Tent_Big_Crate", "Camping_Tent_Big_Crate_Empty"));

    static final String AIRSHIP_KIND = "airship";

    /**
     * Airship prototype (The_Cloudwork). Prefab frame, before rotation. Same names as the other types: scripts/prefab_to_model.py
     * parses AIRSHIP_PIVOT, AIRSHIP_PLACE_MIN and AIRSHIP_PLACE_MAX. The pivot is the cell where the pilot stands (the model's pivot, no stool), the
     * forbidden placement volume is the whole box of the (recentred) prefab: nothing can be added to a deployed airship.
     */
    static final int[] AIRSHIP_PIVOT = {0, 8, -10};
    static final int[] AIRSHIP_PLACE_MIN = {-5, 2, -15};
    static final int[] AIRSHIP_PLACE_MAX = {5, 18, 15};

    /** The airship prototype: marker = the helm block (Furniture_Crude_Window copy), no burner. Not a balloon (not in BALLOONS), not a tent. */
    static final Kind AIRSHIP = new Kind(AIRSHIP_KIND, "The_Cloudwork.prefab.json", "Airship_Helm", false,
            AIRSHIP_PLACE_MIN, AIRSHIP_PLACE_MAX,
            "lock.airship.break", "lock.airship.place", null, null,
            new AirshipSpec("Airship_Cloudwork", "Airship_Cloudwork_Idle", "Airship_Cloudwork_Off", "Airship_Cloudwork_Crate", AIRSHIP_PIVOT));

    private static final List<Kind> ALL = List.of(BALLOON, BALLOON_BASIC, BALLOON_TRANSPORT, TENT, TENT_BIG, AIRSHIP);

    /** Tent types, in the order they are logged at startup. */
    static final List<Kind> TENTS = List.of(TENT, TENT_BIG);

    /** Balloon types (T54), in the order recognition tries them. They share the same anchor burner. */
    static final List<Kind> BALLOONS = List.of(BALLOON, BALLOON_BASIC, BALLOON_TRANSPORT);

    private Deployables() {
    }

    /** The type with this name, null if unknown. */
    static Kind get(String kind) {
        for (Kind k : ALL) {
            if (k.id.equals(kind)) {
                return k;
            }
        }
        return null;
    }

    /** The type whose prefab is this path (relative to Server/Prefabs/), null if none (T54: crate deployment). */
    static Kind forPrefab(String prefabPath) {
        for (Kind k : ALL) {
            if (k.prefabPath.equals(prefabPath)) {
                return k;
            }
        }
        return null;
    }

    /** Data specific to a balloon type (T54): flight model, crate item, model pivot and gondola volume (prefab frame). */
    static final class BalloonSpec {
        final String modelId;
        final String crateItemId;
        /** Model pivot: the pilot's feet at the centre of the gondola, under the burner. Same as PIVOT in prefab_to_model.py. */
        final Vector3f pivot;
        /** Gondola volume (relative to the centre of the origin block, before rotation): a player whose feet are in it is aboard. */
        final Vector3f nacelleMin;
        final Vector3f nacelleMax;
        /** Particle systems of the flight model (Server/Models/Vehicles/<model>*.json): burner flame and climbing jet,
         * named in CancelParticleSystems. The small balloon has its own copies that follow the model (T54). */
        final String flameSystem;
        final String boostSystem;

        BalloonSpec(String modelId, String crateItemId, int[] pivot, float[] nacelleMin, float[] nacelleMax,
                    String flameSystem, String boostSystem) {
            this.flameSystem = flameSystem;
            this.boostSystem = boostSystem;
            this.modelId = modelId;
            this.crateItemId = crateItemId;
            this.pivot = new Vector3f(pivot[0], pivot[1], pivot[2]);
            this.nacelleMin = new Vector3f(nacelleMin[0], nacelleMin[1], nacelleMin[2]);
            this.nacelleMax = new Vector3f(nacelleMax[0], nacelleMax[1], nacelleMax[2]);
        }
    }

    /**
     * Data specific to the airship type: flight models, crate item, pivot (the cell where the pilot stands, prefab frame).
     * Three models share one blockymodel: modelId while the ship travels (burner flames with full smoke at their tips),
     * idleModelId while it does not (half smoke at the burner mouths), offModelId when the engine is dry or nobody pilots.
     */
    static final class AirshipSpec {
        final String modelId;
        final String idleModelId;
        final String offModelId;
        final String crateItemId;
        /** Model pivot = pilot's standing cell, prefab frame before rotation. Same as PIVOT in prefab_to_model.py. */
        final Vector3f pivot;
        /**
         * Particle system of the idle smoke at the burner mouths (Metal_Iron_Pipe_Short cells), on the Exhaust_Left and
         * Exhaust_Right nodes of the idle model: cancelled when the ship starts travelling, lands or is removed.
         */
        volatile String smokeSystemId = "Airship_Exhaust_Smoke";
        /**
         * Particle system of the horizontal burner flames (with the travel smoke at their tips), on the same nodes of the
         * travelling model: cancelled when the ship stops travelling, lands or is removed.
         */
        volatile String flameSystemId = "Airship_Burner_Flame";

        /** Models shown while the ship turns: bow swinging to the pilot's left (strong flame on the right burner) or right. */
        final String turnLeftModelId = "Airship_Cloudwork_TurnLeft";
        final String turnRightModelId = "Airship_Cloudwork_TurnRight";
        /** Exhaust particle systems (flames and smoke) of the turning models, cancelled when the model goes away. */
        static final String[] TURN_LEFT_SYSTEMS = {"Airship_Burner_Flame_Weak_Left", "Airship_Burner_Flame_Strong_Right"};
        static final String[] TURN_RIGHT_SYSTEMS = {"Airship_Burner_Flame_Strong_Left", "Airship_Burner_Flame_Weak_Right"};

        /** Model shown for a thrust state (IDLE, FORWARD, LEFT, RIGHT). */
        String modelFor(AirshipFlight.Thrust t) {
            return switch (t) {
                case IDLE -> idleModelId;
                case FORWARD -> modelId;
                case LEFT -> turnLeftModelId;
                case RIGHT -> turnRightModelId;
            };
        }

        /** Exhaust particle system ids carried by the model of a thrust state (the firebox embers are not exhaust). */
        String[] exhaustSystems(AirshipFlight.Thrust t) {
            return switch (t) {
                case IDLE -> new String[]{smokeSystemId};
                case FORWARD -> new String[]{flameSystemId};
                case LEFT -> TURN_LEFT_SYSTEMS;
                case RIGHT -> TURN_RIGHT_SYSTEMS;
            };
        }

        AirshipSpec(String modelId, String idleModelId, String offModelId, String crateItemId, int[] pivot) {
            this.modelId = modelId;
            this.idleModelId = idleModelId;
            this.offModelId = offModelId;
            this.crateItemId = crateItemId;
            this.pivot = new Vector3f(pivot[0], pivot[1], pivot[2]);
        }
    }

    /** Data specific to a tent type: the full crate (held to deploy) and the empty crate (held to pack, left after deploying). */
    static final class TentSpec {
        final String fullCrateId;
        final String emptyCrateId;
        /**
         * Exit spot (T93), prefab frame before rotation, in blocks relative to the centre of the origin cell (same convention
         * as BalloonManager.prefabPoint): where a player who gets up from a bed of the tent is put, feet height. Null if the
         * tent has none (the client then places the player next to the bed).
         */
        final Vector3f exitPoint;
        /** Direction the player faces at the exit spot, prefab frame before rotation (horizontal). Null with exitPoint. */
        final Vector3f exitFacing;

        TentSpec(String fullCrateId, String emptyCrateId) {
            this(fullCrateId, emptyCrateId, null, null);
        }

        TentSpec(String fullCrateId, String emptyCrateId, float[] exitPoint, float[] exitFacing) {
            this.fullCrateId = fullCrateId;
            this.emptyCrateId = emptyCrateId;
            this.exitPoint = exitPoint == null ? null : new Vector3f(exitPoint[0], exitPoint[1], exitPoint[2]);
            this.exitFacing = exitFacing == null ? null : new Vector3f(exitFacing[0], exitFacing[1], exitFacing[2]);
        }
    }

    /** A structure type. */
    static final class Kind {
        final String id;
        final String prefabPath;
        final String anchorBlock;
        final boolean requireBurner;
        /** Forbidden placement volume, prefab frame, bounds included (in addition to the prefab cells). */
        final int[] placeMin;
        final int[] placeMax;
        /** Keys of the lock messages (Texts.t), break and place. */
        final String messageBreak;
        final String messagePlace;
        /** Balloon data, null for a type that is not a balloon (the tent). */
        final BalloonSpec balloon;
        /** Tent data, null for a type that is not a tent. */
        final TentSpec tent;
        /** Airship data, null for a type that is not the airship. */
        final AirshipSpec airship;
        private volatile StructureShape shape;
        private boolean warned;

        Kind(String id, String prefabPath, String anchorBlock, boolean requireBurner, int[] placeMin, int[] placeMax,
             String messageBreak, String messagePlace) {
            this(id, prefabPath, anchorBlock, requireBurner, placeMin, placeMax, messageBreak, messagePlace, null, null);
        }

        Kind(String id, String prefabPath, String anchorBlock, boolean requireBurner, int[] placeMin, int[] placeMax,
             String messageBreak, String messagePlace, BalloonSpec balloon) {
            this(id, prefabPath, anchorBlock, requireBurner, placeMin, placeMax, messageBreak, messagePlace, balloon, null);
        }

        Kind(String id, String prefabPath, String anchorBlock, boolean requireBurner, int[] placeMin, int[] placeMax,
             String messageBreak, String messagePlace, BalloonSpec balloon, TentSpec tent) {
            this(id, prefabPath, anchorBlock, requireBurner, placeMin, placeMax, messageBreak, messagePlace, balloon, tent, null);
        }

        Kind(String id, String prefabPath, String anchorBlock, boolean requireBurner, int[] placeMin, int[] placeMax,
             String messageBreak, String messagePlace, BalloonSpec balloon, TentSpec tent, AirshipSpec airship) {
            this.airship = airship;
            this.balloon = balloon;
            this.tent = tent;
            this.id = id;
            this.prefabPath = prefabPath;
            this.anchorBlock = anchorBlock;
            this.requireBurner = requireBurner;
            this.placeMin = placeMin;
            this.placeMax = placeMax;
            this.messageBreak = messageBreak;
            this.messagePlace = messagePlace;
        }

        boolean isBalloon() {
            return balloon != null;
        }

        boolean isTent() {
            return tent != null;
        }

        boolean isAirship() {
            return airship != null;
        }

        /** The shape, read on first call (the same instance afterwards). */
        StructureShape shape() throws IOException {
            StructureShape s = shape;
            if (s == null) {
                synchronized (this) {
                    s = shape;
                    if (s == null) {
                        s = StructureShape.load(prefabPath, anchorBlock, requireBurner);
                        shape = s;
                    }
                }
            }
            return s;
        }

        /** The shape, or null if the prefab is unreadable (logged only once). Used for locking, called on every pickaxe hit. */
        StructureShape shapeOrNull() {
            try {
                return shape();
            } catch (IOException e) {
                if (!warned) {
                    warned = true;
                    LOGGER.at(Level.WARNING).withCause(e).log("Prefab illisible : verrouillage de « %s » inactif", id);
                }
                return null;
            }
        }

        boolean inPlaceVolume(int x, int y, int z) {
            return x >= placeMin[0] && x <= placeMax[0]
                    && y >= placeMin[1] && y <= placeMax[1]
                    && z >= placeMin[2] && z <= placeMax[2];
        }
    }
}
