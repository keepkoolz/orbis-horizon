package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;

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

    static final Kind BALLOON = new Kind(BALLOON_KIND, BalloonManager.PREFAB_PATH, BalloonShape.ANCHOR_BLOCK, true,
            PLACE_MIN, PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place");

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
            "lock.tent.break", "lock.tent.place");

    private static final List<Kind> ALL = List.of(BALLOON, TENT);

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
        private volatile BalloonShape shape;
        private boolean warned;

        Kind(String id, String prefabPath, String anchorBlock, boolean requireBurner, int[] placeMin, int[] placeMax,
             String messageBreak, String messagePlace) {
            this.id = id;
            this.prefabPath = prefabPath;
            this.anchorBlock = anchorBlock;
            this.requireBurner = requireBurner;
            this.placeMin = placeMin;
            this.placeMax = placeMax;
            this.messageBreak = messageBreak;
            this.messagePlace = messagePlace;
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
