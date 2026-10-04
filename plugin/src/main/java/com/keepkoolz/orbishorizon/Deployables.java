package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;

import org.joml.Vector3f;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Types of structures placed by the mod, locked by the registry (T43). A type is identified by a
 * string (`kind`, written in deployed.json) and gives its prefab, its marker block, its forbidden placement volume
 * (prefab frame, before rotation) and its lock messages. The shape (BalloonShape) is read only once,
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

    static final Kind BALLOON = new Kind(BALLOON_KIND, "Hotair_Balloon.prefab.json", BalloonShape.ANCHOR_BLOCK, true,
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

    static final Kind BALLOON_BASIC = new Kind(BASIC_KIND, "Hotair_Balloon_Basic.prefab.json", BalloonShape.ANCHOR_BLOCK, true,
            BASIC_PLACE_MIN, BASIC_PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place",
            new BalloonSpec("Hotair_Balloon_Basic", "Hotair_Balloon_Basic_Crate", BASIC_PIVOT, BASIC_NACELLE_MIN, BASIC_NACELLE_MAX,
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
            new TentSpec("Camping_Tent_Crate", "Camping_Tent_Crate_Empty"));

    static final String TENT_BIG_KIND = "tent_big";

    /** Forbidden placement volume of the big tent, prefab frame: the bounding box of the prefab (x -3..3, y 0..3, z -1..6). */
    private static final int[] TENT_BIG_PLACE_MIN = {-3, 0, -1};
    private static final int[] TENT_BIG_PLACE_MAX = {3, 3, 6};

    /** The big camping tent. Same marker as the small one (the campfire, unique in the prefab), same lock messages. */
    static final Kind TENT_BIG = new Kind(TENT_BIG_KIND, "Camping_Tent_Big.prefab.json", "Bench_Campfire", false,
            TENT_BIG_PLACE_MIN, TENT_BIG_PLACE_MAX,
            "lock.tent.break", "lock.tent.place", null,
            new TentSpec("Camping_Tent_Big_Crate", "Camping_Tent_Big_Crate_Empty"));

    private static final List<Kind> ALL = List.of(BALLOON, BALLOON_BASIC, TENT, TENT_BIG);

    /** Tent types, in the order they are logged at startup. */
    static final List<Kind> TENTS = List.of(TENT, TENT_BIG);

    /** Balloon types (T54), in the order recognition tries them. They share the same anchor burner. */
    static final List<Kind> BALLOONS = List.of(BALLOON, BALLOON_BASIC);

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

    /** Data specific to a tent type: the full crate (held to deploy) and the empty crate (held to pack, left after deploying). */
    static final class TentSpec {
        final String fullCrateId;
        final String emptyCrateId;

        TentSpec(String fullCrateId, String emptyCrateId) {
            this.fullCrateId = fullCrateId;
            this.emptyCrateId = emptyCrateId;
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
        private volatile BalloonShape shape;
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

        /** The shape, read on first call (the same instance afterwards). */
        BalloonShape shape() throws IOException {
            BalloonShape s = shape;
            if (s == null) {
                synchronized (this) {
                    s = shape;
                    if (s == null) {
                        s = BalloonShape.load(prefabPath, anchorBlock, requireBurner);
                        shape = s;
                    }
                }
            }
            return s;
        }

        /** The shape, or null if the prefab is unreadable (logged only once). Used for locking, called on every pickaxe hit. */
        BalloonShape shapeOrNull() {
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
