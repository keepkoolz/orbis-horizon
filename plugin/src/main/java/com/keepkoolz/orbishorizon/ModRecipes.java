package com.keepkoolz.orbishorizon;

import java.util.List;

/**
 * The craftable items of the mod, in display order. A recipe is identified by the item it produces.
 */
final class ModRecipes {
    static final List<String> ITEMS = List.of(
            "Orbis_Explorer_Bench",
            "Hotair_Balloon_Basic_Crate", "Hotair_Balloon_Crate", "Hotair_Balloon_Transport_Crate",
            "Airship_Cloudwork_Crate", "Steamboat_Crate",
            "Balloon_Station_Crate", "Boat_Station_Crate",
            "Camping_Pack_Bedroll", "Camping_Pack_Tent", "Camping_Pack_Tent_Big",
            "Armor_Diving_Crude_Head", "Armor_Diving_Crude_Chest", "Armor_Diving_Crude_Hands", "Armor_Diving_Crude_Legs");

    private ModRecipes() {
    }

    static boolean isModItem(String itemId) {
        return itemId != null && ITEMS.contains(itemId);
    }
}
