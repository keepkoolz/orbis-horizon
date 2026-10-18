package com.keepkoolz.orbishorizon;

import org.joml.Vector3i;

/**
 * T153: the three camp tiers (sleeping bag, small tent, large tent). Each tier has a pack item (held to deploy), a placed bag
 * block (inside the deployed prefab, used to pack the camp) and a structure type of {@link Deployables}.
 */
final class CampTiers {
    private CampTiers() {
    }

    enum Tier {
        BEDROLL("Camping_Pack_Bedroll", "Camping_Pack_Placed_Bedroll", Deployables.BEDROLL_KIND, 1),
        TENT("Camping_Pack_Tent", "Camping_Pack_Placed_Tent", Deployables.TENT_KIND, 2),
        TENT_BIG("Camping_Pack_Tent_Big", "Camping_Pack_Placed_Tent_Big", Deployables.TENT_BIG_KIND, 3);

        private final String packItemId;
        private final String placedBlockId;
        private final String kindId;
        private final int level;

        Tier(String packItemId, String placedBlockId, String kindId, int level) {
            this.packItemId = packItemId;
            this.placedBlockId = placedBlockId;
            this.kindId = kindId;
            this.level = level;
        }

        String packItemId() {
            return packItemId;
        }

        String placedBlockId() {
            return placedBlockId;
        }

        String kindId() {
            return kindId;
        }

        /** 1 (sleeping bag) to 3 (large tent). */
        int level() {
            return level;
        }

        /** The next tier up, null for the large tent. */
        Tier next() {
            Tier[] all = values();
            return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
        }

        /** The next tier down, null for the sleeping bag. */
        Tier previous() {
            return ordinal() > 0 ? values()[ordinal() - 1] : null;
        }
    }

    /** The tier whose pack item is this id, or null. */
    static Tier ofPack(String itemId) {
        for (Tier t : Tier.values()) {
            if (t.packItemId.equals(itemId)) {
                return t;
            }
        }
        return null;
    }

    /** The tier whose placed bag block is this id, or null. */
    static Tier ofPlacedBlock(String blockId) {
        for (Tier t : Tier.values()) {
            if (t.placedBlockId.equals(blockId)) {
                return t;
            }
        }
        return null;
    }

    /** The tier of this structure type id ("bedroll", "tent", "tent_big"), or null. */
    static Tier ofKind(String kindId) {
        for (Tier t : Tier.values()) {
            if (t.kindId.equals(kindId)) {
                return t;
            }
        }
        return null;
    }

    /** Cell of the placed bag in the prefab frame (before rotation) of this tier. */
    static Vector3i bagCell(Tier t) {
        return switch (t) {
            case BEDROLL -> new Vector3i(3, 0, 0);
            case TENT -> new Vector3i(-2, 0, 2);
            case TENT_BIG -> new Vector3i(-2, 0, 0);
        };
    }
}
