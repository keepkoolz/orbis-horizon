package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;

import org.joml.Vector3f;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Types of structures placed by the mod, locked by the registry. A type is identified by a
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

    /** Posts of the NPC pilot of the large balloon: at the pivot, resting on a stool, no door, no desk, no fixed captain seat. */
    static final CrewSpec BALLOON_CREW = new CrewSpec(new int[]{0, 9, 8}, new int[]{1, 9, 9}, null, null, null, AirshipCrew::straightLegs);

    static final Kind BALLOON = new Kind(BALLOON_KIND, "Hotair_Balloon.prefab.json", StructureShape.ANCHOR_BLOCK, true,
            PLACE_MIN, PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place",
            new BalloonSpec("Hotair_Balloon", "Hotair_Balloon_Crate", BALLOON_PIVOT, BALLOON_NACELLE_MIN, BALLOON_NACELLE_MAX,
                    "Campfire_New_Cartoon", "Flamethrower", null, BALLOON_CREW, true));

    static final String BASIC_KIND = "balloon_basic";

    /**
     * Small one-seater balloon. Its forbidden placement volume covers the basket (walking floor y = 2) up to the
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
     * Animal transport balloon: a balloon the size of the large one (pilot and one passenger on the stool) with an iron cage hanging under
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

    // Description of the cage (TransportCage moves it and opens its side, CageAnimals captures animals). Prefab frame,
    // cage raised and closed, whole cells, bounds included. The constants are read by TransportCage and CageAnimals.
    // The prefab and the flight model always draw the cage closed and raised.

    /** Outer box of the cage: floor y = 0 (stairs and half planks), bars y 1..3, roof y = 4. */
    static final int[] TRANSPORT_CAGE_MIN = {-2, 0, -8};
    static final int[] TRANSPORT_CAGE_MAX = {2, 4, -4};
    /** Inner volume: free cells where a captured animal stands. */
    static final int[] TRANSPORT_CAGE_INSIDE_MIN = {-1, 1, -7};
    static final int[] TRANSPORT_CAGE_INSIDE_MAX = {1, 3, -5};
    /**
     * Opening side of the cage: the 9 Deco_Iron_Bars of the face z = -8, between the two corner blocks (x = +-2, kept).
     * TransportCage removes these blocks to open the cage and puts them back to close it. A cow (hit box 1.6 x 1.7 in
     * Server/Models/Livestock/Cow.json) fits in this 3 x 3 opening.
     */
    static final int[] TRANSPORT_GATE_MIN = {-1, 1, -8};
    static final int[] TRANSPORT_GATE_MAX = {1, 3, -8};
    /** Chain column of the cage (Deco_Iron_Chain_Small, game block): x, lowest y and z, up to TRANSPORT_CHAIN_TOP_Y, under the basket floor (y = 8). TransportCage lengthens it. */
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
                    "Hotair_Balloon_Basic_Flame", "Hotair_Balloon_Basic_Flamethrower", "Hotair_Balloon_Transport_NoCage"));

    static final String TENT_KIND = "tent";

    /**
     * Forbidden placement volume of the tent, prefab frame: the bounding box of the recentred prefab
     * (x from -3 to 3, y from 0 to 1, z from -1 to 3, the outside chest of the small tent at z = 3 included). No block can be placed in the tent, even on an empty cell.
     */
    static final int[] TENT_PLACE_MIN = {-3, 0, -1};
    static final int[] TENT_PLACE_MAX = {3, 1, 3};

    /** The camping tent. Marker: the game's campfire, unique in the prefab, no burner. */
    static final Kind TENT = new Kind(TENT_KIND, "Camping_Tent.prefab.json", "Bench_Campfire", false,
            TENT_PLACE_MIN, TENT_PLACE_MAX,
            "lock.tent.break", "lock.tent.place", null,
            new TentSpec("Camping_Pack_Tent", "Camping_Tent_Crate", "Camping_Tent_Crate_Empty",
                    new float[] {-2f, 0f, 0.5f}, new float[] {-1f, 0f, 0f}));

    static final String TENT_BIG_KIND = "tent_big";

    /** Forbidden placement volume of the big tent, prefab frame: the bounding box of the prefab (x -3..3, y 0..3, z -1..6). */
    private static final int[] TENT_BIG_PLACE_MIN = {-3, 0, -1};
    private static final int[] TENT_BIG_PLACE_MAX = {3, 3, 6};

    /** The big camping tent. Same marker as the small one (the campfire, unique in the prefab), same lock messages. */
    static final Kind TENT_BIG = new Kind(TENT_BIG_KIND, "Camping_Tent_Big.prefab.json", "Bench_Campfire", false,
            TENT_BIG_PLACE_MIN, TENT_BIG_PLACE_MAX,
            "lock.tent.break", "lock.tent.place", null,
            new TentSpec("Camping_Pack_Tent_Big", "Camping_Tent_Big_Crate", "Camping_Tent_Big_Crate_Empty", null, null));

    static final String BEDROLL_KIND = "bedroll";

    /** Forbidden placement volume of the bedroll camp, prefab frame: the prefab box (x 1..3, y 0..1, z 0..2). */
    private static final int[] BEDROLL_PLACE_MIN = {1, 0, 0};
    private static final int[] BEDROLL_PLACE_MAX = {3, 1, 2};

    /**
     * The sleeping-bag camp: a bed, a bivouac fire and the placed bag. The marker is the placed bag (unique in the prefab).
     * It has no legacy crates.
     */
    static final Kind BEDROLL = new Kind(BEDROLL_KIND, "Camping_Bedroll.prefab.json", "Camping_Pack_Placed_Bedroll", false,
            BEDROLL_PLACE_MIN, BEDROLL_PLACE_MAX,
            "lock.tent.break", "lock.tent.place", null,
            new TentSpec("Camping_Pack_Bedroll", null, null, null, null));

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
            new AirshipSpec("Airship_Cloudwork", "Airship_Cloudwork_Idle", "Airship_Cloudwork_Off", "Airship_Cloudwork_Crate", AIRSHIP_PIVOT,
                    "Airship_Cloudwork_TurnLeft", "Airship_Cloudwork_TurnRight",
                    new String[]{"Airship_Burner_Flame_Weak_Left", "Airship_Burner_Flame_Strong_Right"},
                    new String[]{"Airship_Burner_Flame_Strong_Left", "Airship_Burner_Flame_Weak_Right"},
                    "Airship_Exhaust_Smoke", "Airship_Burner_Flame",
                    true, true, true, true, null, 1f, false, null, null, "dirigeable", false,
                    new CrewSpec(new int[]{0, 8, -10}, new int[]{-2, 8, 4}, new int[]{0, 8, 2}, new int[]{0, 8, 9}, new int[]{0, 9, 8},
                            AirshipCrew::walkLegs)));

    static final String STEAMBOAT_KIND = "steamboat";
    /** Steamboat, prefab frame before rotation: pivot = pilot cell, forbidden placement volume = whole prefab box. */
    static final int[] STEAMBOAT_PIVOT = {0, 4, 5};
    static final int[] STEAMBOAT_PLACE_MIN = {-7, -4, -15};
    static final int[] STEAMBOAT_PLACE_MAX = {7, 9, 12};

    /** The steamboat: same helm marker as the airship, no burner, a pilot and voyages but no recall, ladders nor per-player limit. */
    static final Kind STEAMBOAT = new Kind(STEAMBOAT_KIND, "Steamboat.prefab.json", "Airship_Helm", false,
            STEAMBOAT_PLACE_MIN, STEAMBOAT_PLACE_MAX,
            "lock.steamboat.break", "lock.steamboat.place", null, null,
            new AirshipSpec("Steamboat", "Steamboat_Idle", "Steamboat_Off", "Steamboat_Crate", STEAMBOAT_PIVOT,
                    "Steamboat", "Steamboat", new String[]{"Airship_Exhaust_Smoke"}, new String[]{"Airship_Exhaust_Smoke"},
                    "Airship_Exhaust_Smoke", "Steamboat_Chimney_Smoke_Thick",
                    true, false, false, false, -2, 0.5f, true, "Steamboat", null, "bateau", false,
                    new CrewSpec(STEAMBOAT_PIVOT, new int[]{0, 4, 8}, null, new int[]{0, 1, 8}, new int[]{0, 2, 7}, AirshipCrew::straightLegs)));

    static final String STEAMBOAT_PUBLIC_KIND = "steamboat_public";
    /**
     * Public shuttle boat (boat stations), prefab frame before rotation, a small boat of 127 blocks (x -2..2, y 0..5, z -5..6, bow
     * toward -z) with 6 tavern stools, one chimney, one stern propeller and no paddle wheels, ladders or hull under the floor (the floor of half planks
     * is y 0). The volume keeps z 6 although the last block is at z 5: cell z 6 has no block and keeps the lake water. Pivot = (0, 1, -1), the pilot cell right behind the helm (helm at (0, 2, -2), filler (0, 1, -2)), with the floor
     * under it and the cells above free, the same relation to the helm as STEAMBOAT_PIVOT. Forbidden volume = whole prefab box.
     */
    static final int[] STEAMBOAT_PUBLIC_PIVOT = {0, 1, -1};
    static final int[] STEAMBOAT_PUBLIC_PLACE_MIN = {-2, 0, -5};
    static final int[] STEAMBOAT_PUBLIC_PLACE_MAX = {2, 5, 6};

    /** The shuttle: a driverless small boat (no crate, crew, voyage, recall, ladders, paddle wheels, one propeller), burns no fuel (wired by the shuttle autopilot). */
    static final Kind STEAMBOAT_PUBLIC = new Kind(STEAMBOAT_PUBLIC_KIND, "Steamboat_Public.prefab.json", "Airship_Helm", false,
            STEAMBOAT_PUBLIC_PLACE_MIN, STEAMBOAT_PUBLIC_PLACE_MAX,
            "lock.steamboat.break", "lock.steamboat.place", null, null,
            new AirshipSpec("Steamboat_Public", "Steamboat_Public_Idle", "Steamboat_Public_Off", null, STEAMBOAT_PUBLIC_PIVOT,
                    "Steamboat_Public", "Steamboat_Public", new String[]{"Airship_Exhaust_Smoke"}, new String[]{"Airship_Exhaust_Smoke"},
                    "Airship_Exhaust_Smoke", "Steamboat_Chimney_Smoke_Thick",
                    false, false, false, false, 0, 0.5f, true, null, "Propeller", "navette", true, null));

    static final String PUBLIC_KIND = "balloon_public";

    /**
     * Transit balloon (public transport between stations): a driverless balloon the size of the large one with a 5 x 5 gondola
     * and 12 stools, no chain and no crate. Prefab frame (recentred on the gondola: x -2..2, z -2..2), before rotation.
     * Same names as the other balloons with the PUBLIC_ prefix: scripts/prefab_to_model.py reads them. The pivot is the
     * walking cell above the gondola floor (floor y = -2), burner row y = 3. The forbidden placement volume covers the
     * gondola from the pivot up to the burner row.
     */
    private static final int[] PUBLIC_PIVOT = {0, -1, 0};
    private static final float[] PUBLIC_NACELLE_MIN = {-2.0f, -1.5f, -2.0f};
    private static final float[] PUBLIC_NACELLE_MAX = {2.0f, 2.0f, 2.0f};
    private static final int[] PUBLIC_PLACE_MIN = {-2, -1, -2};
    private static final int[] PUBLIC_PLACE_MAX = {2, 3, 2};

    /**
     * The transit balloon. The anchor is its own burner block (no bench, no interaction), not the chain: it is in ALL but
     * deliberately NOT in BALLOONS, so that the take-off recognition by the chain can never pick it. No crate (crateItemId null).
     * Flame systems: the small balloon's copies (they follow the moving model, this balloon flies all the time).
     */
    static final Kind BALLOON_PUBLIC = new Kind(PUBLIC_KIND, "Balloon_Public_Transport.prefab.json", "Hotair_Balloon_Public_Burner", true,
            PUBLIC_PLACE_MIN, PUBLIC_PLACE_MAX,
            "lock.balloon.break", "lock.balloon.place",
            new BalloonSpec("Hotair_Balloon_Public", null, PUBLIC_PIVOT, PUBLIC_NACELLE_MIN, PUBLIC_NACELLE_MAX,
                    "Hotair_Balloon_Basic_Flame", "Hotair_Balloon_Basic_Flamethrower"));

    static final String STATION_KIND = "station";

    /**
     * Balloon station (transit network). All positions are in the frame of Balloon_Landing_zone.prefab.json as stored in the file
     * (it is NOT recentred: x starts at 2), whole cells, before rotation. Measured on the prefab; if the prefab changes, update
     * them. The station is placed by its prefab origin, like the other structures.
     *
     * Landing pad: the 5 x 5 Rock_Stone_Brick blocks of the raised walkway, top layer y = 6
     * (STATION_PAD_MIN to STATION_PAD_MAX, bounds included), in the room the user left between the boarding steps (walls at x = 15)
     * and the statues (x = 24): the gondola rim (y = -1 of its prefab, one cell wider than the floor on each side) fits from x = 16 to
     * 22 without touching any station block. The cells above the pad (y = 7) are free. Dock: the gondola floor (y = -2 in its prefab)
     * sits on the cells above the pad (y = 7), centred on the pad: origin = (19, 9, 0).
     */
    static final int[] STATION_PAD_MIN = {17, 6, -2};
    static final int[] STATION_PAD_MAX = {21, 6, 2};
    static final int[] STATION_DOCK_ORIGIN = {19, 9, 0};
    /**
     * Drop points of the arriving passengers at the top of the stair ramp, before the boarding steps: the Rock_Stone_Brick row
     * x = 13 (top y = 6) and the last stair row x = 12 (Rock_Stone_Brick_Stairs at y = 6), z -2..2, prefab frame, feet height
     * (cell y = 7 plus 0.1, cell centres at integers), plus two on the first boarding step (x = 14, Rock_Stone_Brick_Stairs at y = 7,
     * z = -1 and 1, feet y = 8.1). Each is a standable cell: solid block below, two free cells above. One point per seat (12).
     */
    static final float[][] STATION_BOARDING_POINTS = {
            {13f, 7.1f, -2f}, {13f, 7.1f, -1f}, {13f, 7.1f, 0f}, {13f, 7.1f, 1f}, {13f, 7.1f, 2f},
            {12f, 7.1f, -2f}, {12f, 7.1f, -1f}, {12f, 7.1f, 0f}, {12f, 7.1f, 1f}, {12f, 7.1f, 2f},
            {14f, 8.1f, -1f}, {14f, 8.1f, 1f}};
    /**
     * Cell of the station master (NPC) inside the hut (counter x 2..5, z 6..9), behind its front counter, basalt floor y = -1: free
     * cell (the Lobby_Wall_Poster01 that was there is removed from the prefab), two free cells above.
     */
    static final int[] STATION_MASTER_POS = {4, 0, 7};
    /** y of the Soil_Grass / Soil_Dirt base layer: not tested when checking free space for a station. */
    static final int STATION_GRASS_Y = -1;

    /** Bounding box of the whole prefab (x 1..25, y -1..9, z -6..10): nothing can be placed inside the station. */
    private static final int[] STATION_PLACE_MIN = {1, -1, -6};
    private static final int[] STATION_PLACE_MAX = {25, 9, 10};

    /** The balloon station: marker = a brazier of the mod (one of the 6 braziers of the prefab), no burner, locked like the tents. */
    static final Kind STATION = new Kind(STATION_KIND, "Balloon_Landing_zone.prefab.json", "Balloon_Station_Marker", false,
            STATION_PLACE_MIN, STATION_PLACE_MAX,
            "lock.station.break", "lock.station.place");

    static final String BOAT_STATION_KIND = "boat_station";

    /**
     * Boat station (shore kiosk and pier), prefab frame of Boat_Station.prefab.json, whole cells, before rotation. The pier deck
     * (Wood_Hardwood_Planks_Half, y 0) is x 0..3, z 5..11; the original terrain water surface is y -1. Master NPC cell inside the
     * kiosk, looking toward the shore end of the pier; the marker cell is the middle of the pier.
     */
    static final int[] BOAT_STATION_MASTER_POS = {-4, 0, 5};
    static final float[] BOAT_STATION_MASTER_LOOK = {2f, 0f, 5f};
    static final int[] BOAT_STATION_MARKER_CELL = {1, 0, 8};
    /** Last deck cell of the pier on the water side: the column where the water surface is read. */
    static final int[] BOAT_STATION_PIER_END = {1, 0, 11};
    /**
     * Berth of the shuttle (Steamboat_Public.prefab.json) in the station frame. Found by brute force over the 4 rotations and
     * origins near the pier (a brute-force search, not kept): no hull cell on a station block, every cell at station z >= 6
     * (the water side of the prefab, the kiosk is on the shore at z 1..7), and the boarding step of the hull (the half plank
     * Wood_Softwood_Planks_Half in the gap of each gunwale, prefab (+-2, 1, 0)) right against a deck half plank of the pier at the
     * same height. Only berths across the end of the pier qualify (the lantern posts at x -1 and 4, z 6 and 10 are in the way of a
     * boat moored along the pier). The shuttle lies across the end of the pier with its side to it (quarter turn, long axis along x,
     * bow toward the station -x), its step at world (1, 0, 12) next to the last deck cell (1, 0, 11): the passengers walk from the
     * pier onto the boat without a ladder. Hull at station z 12..16, closest of the valid berths to the pier end (the other ones
     * only shift it by one column, or turn it the other way round). Origin y = -1: the waterline layer (prefab y 0, the floor) lands
     * on the water surface y -1. The world rotation of the boat is the station rotation plus BOAT_STATION_BERTH_ROTATION.
     */
    static final int[] BOAT_STATION_BERTH_ORIGIN = {1, -1, 14};
    /**
     * Drop points of the shuttle passengers arriving at a boat station: the shore half of the pier deck (Wood_Hardwood_Planks_Half
     * at y 0, x 0..3, z 6..8), away from the boarding step at the water end. Station frame, whole cells (the cell centre +0.5 is added
     * when converted), feet on the top of the half plank plus 0.1. Every cell has two free cells above. Two points per seat (12).
     */
    static final float[][] BOAT_STATION_DROP_POINTS = {
            {0f, 0.6f, 6f}, {1f, 0.6f, 6f}, {2f, 0.6f, 6f}, {3f, 0.6f, 6f},
            {0f, 0.6f, 7f}, {1f, 0.6f, 7f}, {2f, 0.6f, 7f}, {3f, 0.6f, 7f},
            {0f, 0.6f, 8f}, {1f, 0.6f, 8f}, {2f, 0.6f, 8f}, {3f, 0.6f, 8f}};
    static final Rotation BOAT_STATION_BERTH_ROTATION = Rotation.Ninety;
    private static final int[] BOAT_STATION_PLACE_MIN = {-6, -10, 1};
    private static final int[] BOAT_STATION_PLACE_MAX = {5, 3, 11};

    /** The boat station: marker = Boat_Station_Map, no burner, locked like the tents. */
    static final Kind BOAT_STATION = new Kind(BOAT_STATION_KIND, "Boat_Station.prefab.json", "Boat_Station_Map", false,
            BOAT_STATION_PLACE_MIN, BOAT_STATION_PLACE_MAX,
            "lock.boatStation.break", "lock.boatStation.place");

    /** Vehicle types flown by AirshipManager (free-heading ships). */
    static final List<Kind> SHIPS = List.of(AIRSHIP, STEAMBOAT);

    private static final List<Kind> ALL = List.of(BALLOON, BALLOON_BASIC, BALLOON_TRANSPORT, BEDROLL, TENT, TENT_BIG, AIRSHIP, STEAMBOAT,
            STEAMBOAT_PUBLIC, BALLOON_PUBLIC, STATION, BOAT_STATION);

    /** Tent types, in the order they are logged at startup. */
    /** Every structure kind. */
    static List<Kind> all() {
        return ALL;
    }

    static final List<Kind> TENTS = List.of(BEDROLL, TENT, TENT_BIG);

    /** Balloon types, in the order recognition tries them. They share the same anchor burner. */
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

    /** The type whose prefab is this path (relative to Server/Prefabs/), null if none (crate deployment). */
    static Kind forPrefab(String prefabPath) {
        for (Kind k : ALL) {
            if (k.prefabPath.equals(prefabPath)) {
                return k;
            }
        }
        return null;
    }

    /** Data specific to a balloon type: flight model, crate item, model pivot and gondola volume (prefab frame). */
    static final class BalloonSpec {
        final String modelId;
        final String crateItemId;
        /** Model pivot: the pilot's feet at the centre of the gondola, under the burner. Same as PIVOT in prefab_to_model.py. */
        final Vector3f pivot;
        /** Gondola volume (relative to the centre of the origin block, before rotation): a player whose feet are in it is aboard. */
        final Vector3f nacelleMin;
        final Vector3f nacelleMax;
        /** Particle systems of the flight model (Server/Models/Vehicles/<model>*.json): burner flame and climbing jet,
         * named in CancelParticleSystems. The small balloon has its own copies that follow the model. */
        final String flameSystem;
        final String boostSystem;
        /**
         * flight model without the cage and the chain column (transport balloon only, else null), used by the dry descent onto a
         * cage that rests on the ground. Its _Off variant is shown. Generated by the transport_nocage profile of prefab_to_model.py.
         */
        final String groundModelId;
        /** Posts of the NPC pilot (null except for the large balloon) and whether the pilot can take the captain on voyages. */
        final CrewSpec crewSpec;
        final boolean voyage;

        BalloonSpec(String modelId, String crateItemId, int[] pivot, float[] nacelleMin, float[] nacelleMax,
                    String flameSystem, String boostSystem) {
            this(modelId, crateItemId, pivot, nacelleMin, nacelleMax, flameSystem, boostSystem, null);
        }

        BalloonSpec(String modelId, String crateItemId, int[] pivot, float[] nacelleMin, float[] nacelleMax,
                    String flameSystem, String boostSystem, String groundModelId) {
            this(modelId, crateItemId, pivot, nacelleMin, nacelleMax, flameSystem, boostSystem, groundModelId, null, false);
        }

        BalloonSpec(String modelId, String crateItemId, int[] pivot, float[] nacelleMin, float[] nacelleMax,
                    String flameSystem, String boostSystem, String groundModelId, CrewSpec crewSpec, boolean voyage) {
            this.crewSpec = crewSpec;
            this.voyage = voyage;
            this.groundModelId = groundModelId;
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
        volatile String smokeSystemId;
        /**
         * Particle system of the horizontal burner flames (with the travel smoke at their tips), on the same nodes of the
         * travelling model: cancelled when the ship stops travelling, lands or is removed.
         */
        volatile String flameSystemId;

        /** Models shown while the ship turns: bow swinging to the pilot's left (strong flame on the right burner) or right. */
        final String turnLeftModelId;
        final String turnRightModelId;
        /** Exhaust particle systems (flames and smoke) of the turning models, cancelled when the model goes away. */
        final String[] turnLeftSystems;
        final String[] turnRightSystems;

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
                case LEFT -> turnLeftSystems;
                case RIGHT -> turnRightSystems;
            };
        }

        /** Feature flags: which airship-only systems a ship kind takes part in. */
        final boolean voyage;
        final boolean recall;
        final boolean ladders;
        final boolean onePerPlayer;
        /** Posts of the NPC pilot on this ship, null for a ship without a pilot. */
        final CrewSpec crewSpec;
        /** Prefab-frame y of the highest layer under water when afloat (cells with y at or under it are submerged) (null: a dry ship, the airship). */
        final Integer waterlineY;
        /** Speed factor when going astern. */
        final float reverseSpeedFactor;
        /** True if the stern follows the pilot's course when reversing (a boat), false if the ship turns as in forward. */
        final boolean sternFollowsInReverse;
        /** Name of the model that carries the paddle wheel animations (null: no wheels, the airship). */
        final String wheelAnimPrefix;
        /**
         * Name of the stern propeller node (null: no propeller). The shuttle has one propeller instead of two wheels: its animations
         * are the model's AnimationSets keys "<prefix>_<Spin>" (see PaddleWheels.propellerAnimationId).
         */
        final String propellerAnimPrefix;
        /** True when the flight model has the animated "Helm" node (the helm block turns with the ship, see ShipHelm). All ships. */
        final boolean helmAnimated = true;
        /** Word used for this ship in server log lines (lower case: "dirigeable", "bateau"). */
        final String logLabel;
        /** True for a ship that burns no fuel (the public shuttle): the engine and the tank are not used. */
        final boolean fuelless;

        /** True for a ship that can have an NPC pilot. */
        boolean hasCrew() {
            return crewSpec != null;
        }

        /** True for a ship that floats on water (it has a waterline). */
        boolean waterborne() {
            return waterlineY != null;
        }

        AirshipSpec(String modelId, String idleModelId, String offModelId, String crateItemId, int[] pivot,
                    String turnLeftModelId, String turnRightModelId, String[] turnLeftSystems, String[] turnRightSystems,
                    String smokeSystemId, String flameSystemId,
                    boolean voyage, boolean recall, boolean ladders, boolean onePerPlayer,
                    Integer waterlineY, float reverseSpeedFactor, boolean sternFollowsInReverse, String wheelAnimPrefix,
                    String propellerAnimPrefix, String logLabel, boolean fuelless, CrewSpec crewSpec) {
            this.crewSpec = crewSpec;
            this.logLabel = logLabel;
            this.fuelless = fuelless;
            this.wheelAnimPrefix = wheelAnimPrefix;
            this.propellerAnimPrefix = propellerAnimPrefix;
            this.waterlineY = waterlineY;
            this.reverseSpeedFactor = reverseSpeedFactor;
            this.sternFollowsInReverse = sternFollowsInReverse;
            this.turnLeftModelId = turnLeftModelId;
            this.turnRightModelId = turnRightModelId;
            this.turnLeftSystems = turnLeftSystems;
            this.turnRightSystems = turnRightSystems;
            this.smokeSystemId = smokeSystemId;
            this.flameSystemId = flameSystemId;
            this.voyage = voyage;
            this.recall = recall;
            this.ladders = ladders;
            this.onePerPlayer = onePerPlayer;
            this.modelId = modelId;
            this.idleModelId = idleModelId;
            this.offModelId = offModelId;
            this.crateItemId = crateItemId;
            this.pivot = new Vector3f(pivot[0], pivot[1], pivot[2]);
        }
    }

    /** Where an NPC pilot stands, rests and walks on a ship, and where the captain sits during a voyage (prefab frame). */
    record CrewSpec(int[] helm, int[] rest, int[] door, int[] captainSeat, int[] desk, WalkPlan walk) {
        /** Steps (prefab frame, block units) from a position to a post, excluding the post itself. */
        interface WalkPlan {
            List<double[]> legs(double[] from, CrewRecord.Post target);
        }
    }

    /** Whether the NPC pilot of a vehicle type can take the captain on voyages (false for an unknown type). */
    static boolean voyageOf(String kindId) {
        for (Kind k : ALL) {
            if (k.id.equals(kindId)) {
                if (k.airship != null) {
                    return k.airship.voyage;
                }
                return k.balloon != null && k.balloon.voyage;
            }
        }
        return false;
    }

    /** Crew spec of a vehicle type, or null (unknown type, no pilot). */
    static CrewSpec crewOf(String kindId) {
        for (Kind k : ALL) {
            if (k.id.equals(kindId)) {
                return k.crew();
            }
        }
        return null;
    }

    /**
     * Data specific to a camp type: the pack item (held to deploy) and the legacy crates (null for the
     * sleeping bag, which never had crates).
     */
    static final class TentSpec {
        final String packItemId;
        final String legacyFullCrateId;
        final String legacyEmptyCrateId;
        /**
         * Exit spot, prefab frame before rotation, in blocks relative to the centre of the origin cell (same convention
         * as BalloonManager.prefabPoint): where a player who gets up from a bed of the tent is put, feet height. Null if the
         * tent has none (the client then places the player next to the bed).
         */
        final Vector3f exitPoint;
        /** Direction the player faces at the exit spot, prefab frame before rotation (horizontal). Null with exitPoint. */
        final Vector3f exitFacing;

        TentSpec(String packItemId, String legacyFullCrateId, String legacyEmptyCrateId, float[] exitPoint, float[] exitFacing) {
            this.packItemId = packItemId;
            this.legacyFullCrateId = legacyFullCrateId;
            this.legacyEmptyCrateId = legacyEmptyCrateId;
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

        /** Posts of the NPC pilot of this type, or null if it has none. */
        CrewSpec crew() {
            return airship != null ? airship.crewSpec : balloon != null ? balloon.crewSpec : null;
        }

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
