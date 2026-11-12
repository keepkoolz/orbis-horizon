package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The furniture families of the explorer's journal and the items each one holds, in album order.
 * Every item has a recipe of the mod that the player learns by examining it. Pieces the game already makes are left out.
 */
final class FurnitureDiscovery {
    record Item(String itemId) {
    }

    record Family(String id, List<Item> items) {
    }

    private static final List<String> LOST_CIVILIZATION = List.of(
            "Furniture_Human_Ruins_Bed",
            "Furniture_Human_Ruins_Brazier",
            "Furniture_Human_Ruins_Chair",
            "Furniture_Human_Ruins_Chest_Small",
            "Furniture_Human_Ruins_Door",
            "Furniture_Human_Ruins_Ladder",
            "Furniture_Human_Ruins_Sign",
            "Furniture_Human_Ruins_Stool",
            "Furniture_Human_Ruins_Table",
            "Furniture_Human_Ruins_Wardrobe",
            "Furniture_Human_Ruins_Window",
            "Furniture_Human_Ruins_Banner",
            "Furniture_Human_Ruins_Banner_Double",
            "Furniture_Human_Ruins_Banner_Triple",
            "Furniture_Human_Ruins_Bench",
            "Furniture_Human_Ruins_Bookshelf",
            "Furniture_Human_Ruins_Candle",
            "Furniture_Human_Ruins_Coffin",
            "Furniture_Human_Ruins_Desk",
            "Furniture_Human_Ruins_Door_Large",
            "Furniture_Human_Ruins_Door_Medium",
            "Furniture_Human_Ruins_Lantern",
            "Furniture_Human_Ruins_Lantern_Ceiling",
            "Furniture_Human_Ruins_Planter",
            "Furniture_Human_Ruins_Platform",
            "Furniture_Human_Ruins_Pot",
            "Furniture_Human_Ruins_Pot_Small",
            "Furniture_Human_Ruins_Shelf",
            "Furniture_Human_Ruins_Statue",
            "Furniture_Human_Ruins_Statue_Broken",
            "Furniture_Human_Ruins_Torch",
            "Furniture_Human_Ruins_Trapdoor");

    private static final List<String> VILLAGE = List.of(
            "Furniture_Village_Bed",
            "Furniture_Village_Brazier",
            "Furniture_Village_Painting_1x1",
            "Furniture_Village_Bench",
            "Furniture_Village_Bookcase",
            "Furniture_Village_Coffin",
            "Furniture_Village_Painting_1x2",
            "Furniture_Village_Painting_2x1",
            "Furniture_Village_Painting_2x2",
            "Furniture_Village_Painting_3x2",
            "Furniture_Village_Pot",
            "Furniture_Village_Statue",
            "Furniture_Village_Tombstone");

    private static final List<String> FERAN = List.of(
            "Furniture_Feran_Bench",
            "Furniture_Feran_Bookshelf",
            "Furniture_Feran_Door",
            "Furniture_Feran_Ladder",
            "Furniture_Feran_Platform",
            "Furniture_Feran_Pot",
            "Furniture_Feran_Pot_Secondary",
            "Furniture_Feran_Shelf",
            "Furniture_Feran_Sign",
            "Furniture_Feran_Stool",
            "Furniture_Feran_Table",
            "Furniture_Feran_Trapdoor",
            "Furniture_Feran_Wardrobe",
            "Furniture_Feran_Window");

    private static final List<String> SCARAK_TEMPLE = List.of(
            "Furniture_Temple_Scarak_Brazier",
            "Furniture_Temple_Scarak_Chest_Small",
            "Furniture_Temple_Scarak_Door_Large",
            "Furniture_Temple_Scarak_Door_Medium",
            "Furniture_Temple_Scarak_Ladder",
            "Furniture_Temple_Scarak_Lamp",
            "Furniture_Temple_Scarak_Lantern",
            "Furniture_Temple_Scarak_Planter",
            "Furniture_Temple_Scarak_Platform",
            "Furniture_Temple_Scarak_Pot",
            "Furniture_Temple_Scarak_Secondary_Pot",
            "Furniture_Temple_Scarak_Statue",
            "Furniture_Temple_Scarak_Window");

    private static final List<String> KWEEBEC = List.of(
            "Furniture_Kweebec_Door",
            "Furniture_Kweebec_Ladder",
            "Furniture_Kweebec_Painting",
            "Furniture_Kweebec_Platform",
            "Furniture_Kweebec_Shelf",
            "Furniture_Kweebec_Sign",
            "Furniture_Kweebec_Statue",
            "Furniture_Kweebec_Stool",
            "Furniture_Kweebec_Table",
            "Furniture_Kweebec_Trapdoor",
            "Furniture_Kweebec_Wardrobe",
            "Furniture_Kweebec_Window");

    private static final List<String> ROYAL_MAGIC = List.of(
            "Furniture_Royal_Magic_Bed",
            "Furniture_Royal_Magic_Chair",
            "Furniture_Royal_Magic_Couch",
            "Furniture_Royal_Magic_Medium_Door",
            "Furniture_Royal_Magic_Pot",
            "Furniture_Royal_Magic_Table",
            "Furniture_Royal_Magic_Wardrobe");

    private static final List<String> LIGHT_TEMPLE = List.of(
            "Furniture_Temple_Light_Bed",
            "Furniture_Temple_Light_Bench",
            "Furniture_Temple_Light_Door_Large",
            "Furniture_Temple_Light_Door_Medilum",
            "Furniture_Temple_Light_Painting",
            "Furniture_Temple_Light_Platform",
            "Furniture_Temple_Light_Pot",
            "Furniture_Temple_Light_Statue");

    private static final List<String> ANCIENT = List.of(
            "Furniture_Ancient_Barrel",
            "Furniture_Ancient_Coffin",
            "Furniture_Ancient_Crate",
            "Furniture_Ancient_Painting",
            "Furniture_Ancient_Pot",
            "Furniture_Ancient_Sack",
            "Furniture_Ancient_Statue",
            "Furniture_Ancient_Torch");

    private static final List<String> SCARAK_HIVE = List.of(
            "Furniture_Scarak_Hive_Door_Large",
            "Furniture_Scarak_Hive_Door_Medium",
            "Furniture_Scarak_Hive_Ladder",
            "Furniture_Scarak_Hive_Lamp",
            "Furniture_Scarak_Hive_Lantern",
            "Furniture_Scarak_Hive_Platform",
            "Furniture_Scarak_Hive_Window");

    private static final List<String> DARK_TEMPLE = List.of(
            "Furniture_Temple_Dark_Candle",
            "Furniture_Temple_Dark_Coffin",
            "Furniture_Temple_Dark_Door_Large",
            "Furniture_Temple_Dark_Door_Medium",
            "Furniture_Temple_Dark_Pot",
            "Furniture_Temple_Dark_Statue",
            "Furniture_Temple_Dark_Statue_Gaia");

    private static final List<String> FROZEN_CASTLE = List.of(
            "Furniture_Frozen_Castle_Bed",
            "Furniture_Frozen_Castle_Bench",
            "Furniture_Frozen_Castle_Lamp",
            "Furniture_Frozen_Castle_Pot",
            "Furniture_Frozen_Castle_Secondary_Lamp",
            "Furniture_Frozen_Castle_Secondary_Pot");

    private static final List<String> JUNGLE = List.of(
            "Furniture_Jungle_Bookshelf",
            "Furniture_Jungle_Brazier",
            "Furniture_Jungle_Painting",
            "Furniture_Jungle_Pot",
            "Furniture_Jungle_Pot_Secondary");

    private static final List<String> WIND_TEMPLE = List.of(
            "Furniture_Temple_Wind_Door_Large",
            "Furniture_Temple_Wind_Door_Medium",
            "Furniture_Temple_Wind_Pot",
            "Furniture_Temple_Wind_Statue",
            "Furniture_Temple_Wind_Statue_Gaia");

    private static final List<String> GOBLIN = List.of(
            "Furniture_Goblin_Coffer");

    private static final List<String> CRUDE = List.of(
            "Furniture_Crude_Brazier");

    private static final List<String> EMERALD_TEMPLE = List.of(
            "Furniture_Temple_Emerald_Bed",
            "Furniture_Temple_Emerald_Door_Medium",
            "Furniture_Temple_Emerald_Statue");

    private static final List<String> TAVERN = List.of(
            "Furniture_Tavern_Barrel",
            "Furniture_Tavern_Painting");

    private static final List<String> DESERT = List.of(
            "Furniture_Desert_Bed");

    private static final List<String> LUMBERJACK = List.of(
            "Furniture_Lumberjack_Painting");

    static final List<Family> FAMILIES = List.of(
            new Family("lost_civilization", items(LOST_CIVILIZATION)),
            new Family("village", items(VILLAGE)),
            new Family("feran", items(FERAN)),
            new Family("scarak_temple", items(SCARAK_TEMPLE)),
            new Family("kweebec", items(KWEEBEC)),
            new Family("royal_magic", items(ROYAL_MAGIC)),
            new Family("light_temple", items(LIGHT_TEMPLE)),
            new Family("ancient", items(ANCIENT)),
            new Family("scarak_hive", items(SCARAK_HIVE)),
            new Family("dark_temple", items(DARK_TEMPLE)),
            new Family("frozen_castle", items(FROZEN_CASTLE)),
            new Family("jungle", items(JUNGLE)),
            new Family("wind_temple", items(WIND_TEMPLE)),
            new Family("goblin", items(GOBLIN)),
            new Family("crude", items(CRUDE)),
            new Family("emerald_temple", items(EMERALD_TEMPLE)),
            new Family("tavern", items(TAVERN)),
            new Family("desert", items(DESERT)),
            new Family("lumberjack", items(LUMBERJACK)));

    private static final Map<String, Item> ITEMS = new HashMap<>();
    private static final Map<String, Family> FAMILY_OF_ITEM = new HashMap<>();
    private static final Map<String, Family> BY_ID = new HashMap<>();

    static {
        for (Family f : FAMILIES) {
            BY_ID.put(f.id(), f);
            for (Item it : f.items()) {
                ITEMS.put(it.itemId(), it);
                FAMILY_OF_ITEM.put(it.itemId(), f);
            }
        }
    }

    private FurnitureDiscovery() {
    }

    private static List<Item> items(List<String> ids) {
        List<Item> out = new ArrayList<>();
        for (String id : ids) {
            out.add(new Item(id));
        }
        return List.copyOf(out);
    }

    /** The family holding the item, or null. */
    static Family familyOf(String itemId) {
        return itemId == null ? null : FAMILY_OF_ITEM.get(itemId);
    }

    /** The entry of the item, or null. */
    static Item item(String itemId) {
        return itemId == null ? null : ITEMS.get(itemId);
    }

    /** The family with this id, or null. */
    static Family family(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    static String familyNameKey(String id) {
        return "discovery.family." + id + ".name";
    }

    static String familyLoreKey(String id) {
        return "discovery.family." + id + ".lore";
    }

    static String familyTitleKey(String id) {
        return "discovery.family." + id + ".title";
    }

    static String itemLoreKey(String itemId) {
        return "discovery.item." + itemId;
    }

    /** Short name of an item in the album (the type of object, without the family). */
    static String itemNameKey(String itemId) {
        return "discovery.name." + itemId;
    }
}
