package com.keepkoolz.orbishorizon.api;

import java.util.List;
import java.util.function.Predicate;

/**
 * Tells whether a block id is something a player builds, so that a station is never generated next to a construction on terrain
 * that was explored before the mod generated stations. Pure: no access to the world. Shared by the plugin (digging of the pier basin) and the extension (generation next to constructions).
 *
 * <p>The rules were written from the block ids of {@code Assets.zip} (the item files of {@code Server/Item/Items} whose id is the
 * block id, read by family): {@code Wood} (species blocks and their crafted variants), {@code Rock} (and {@code Prototype_Rock}),
 * {@code Soil} (clay, hive, snow, gravel, sand, dirt variants), {@code Furniture} (and the {@code Prototype_} windows, signs and
 * candles), {@code Bench}, {@code Container}, {@code Rail}, {@code Cloth}, {@code Metal}, {@code Build}, {@code Deco},
 * {@code Plant}, {@code Rubble}, {@code Ore}, {@code Trap}, {@code Glass}, {@code Goblin} paintings, plus the block masks and
 * assignments of {@code Server/HytaleGenerator} to see which of these ids the world generator places itself. The generator places
 * village paths ({@code Soil_Pathway}), village walls and ruins of crafted rock and wood, so a generated village or ruin counts as
 * built too: a station is not generated next to one either. That is a conservative reading of the files, not a measured fact.
 *
 * <p>Every id the rules would class as built was checked against the biome generator files (see GENERATOR_MATERIALS). An id a
 * biome generator places as a layer, cover, solid or prop is natural and is listed there, ahead of the built rules.
 *
 * <p>Rules are ordered and the first match decides. Natural families come first so that vegetation, tree wood, ores and loose
 * rock are never built. An id that no rule matches is not built.
 */
public final class BuiltBlocks {
    private BuiltBlocks() {
    }

    /** A family of ids and its verdict. */
    private record Rule(String family, boolean built, Predicate<String> matches) {
    }

    private static Predicate<String> prefix(String... prefixes) {
        return n -> {
            for (String p : prefixes) {
                if (n.startsWith(p)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Predicate<String> regex(String pattern) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        return n -> p.matcher(n).matches();
    }

    /** Crafted shape or finish of a rock or soil block: brick, cobble, smooth, half, stairs, beam, roof, wall, pillar, pipe. */
    private static final Predicate<String> CRAFTED_STONE = regex(
            ".*(_Brick|_Cobble|_Smooth|_Beam|_Roof|_Wall|_Pillar_|_Ornate|_Decorative|_Pipe|_Gutter|_Slab|_Fence)(_.*)?|.*_(Half|Stairs)");

    /**
     * Ids found in the biome, layer, cover and mask files of Assets.zip (Server/HytaleGenerator/Biomes and Assignments,
     * Server/World/Default/Zones Tile, Layers, Masks and Zone files, without villages, ruins, huts, cave cities, cave entrances and
     * ocean cities), checked against every id the rules below would class as built.
     */
    private static final java.util.Set<String> GENERATOR_MATERIALS = java.util.Set.of(
            "Soil_Pathway", "Soil_Clay_Black", "Soil_Clay_Grey", "Soil_Clay_Orange", "Soil_Clay_Red", "Soil_Clay_Smooth_Black",
            "Soil_Clay_White", "Soil_Clay_Yellow", "Soil_Clay_Brick",
            "Rock_Stone_Cobble", "Rock_Stone_Cobble_Mossy", "Rock_Lime_Cobble", "Rock_Volcanic_Brick_Smooth",
            "Rock_Quartzite_Brick_Stairs", "Rock_Quartzite_Cobble_Stairs",
            "Furniture_Ancient_Barrel", "Furniture_Ancient_Crate", "Deco_Fire", "Build_Lightsource_Green",
            "Cloth_Block_Wool_Black", "Cloth_Block_Wool_Orange", "Cloth_Block_Wool_Orange_Light", "Cloth_Block_Wool_Purple",
            "Cloth_Block_Wool_Red", "Cloth_Block_Wool_White");

    /** The ordered rules. Natural families first, then the built ones, one family per line. */
    private static final List<Rule> RULES = List.of(
            // Trunks, branches, roots, sticks, nests, webs: what the mod already clears as vegetation, never built.
            new Rule("vegetation et bois d'arbre", false, BuiltBlocks::removable),
            // Ids the biome generators of Assets.zip use as layers, covers, solids or props (so natural terrain holds them), even
            // though they look crafted: village paths, coloured and brick clay, cobble, wool, sea-floor crates, fire.
            new Rule("matériaux du générateur de biome", false, GENERATOR_MATERIALS::contains),
            // Plants, leaves, crops, grass, moss, corals, seaweed.
            new Rule("plantes", false, prefix("Plant_")),
            // Loose rubble and boulders.
            new Rule("gravats", false, prefix("Rubble_")),
            // Ores in the rock.
            new Rule("minerais", false, prefix("Ore_")),
            // Fluids.
            new Rule("fluides", false, prefix("Fluid_")),
            // Stalactites and icicles of caves.
            new Rule("stalactites", false, n -> n.contains("_Stalactite") || n.startsWith("Rock_Ice_Icicles")),
            // Natural decorations: bones, shells, spider and scarak nests, hives.
            new Rule("décors naturels", false, prefix("Deco_Bone", "Deco_Coral", "Deco_Starfish", "Deco_Scarak", "Deco_Hive",
                    "Deco_Nest", "Deco_SpiderWeb", "Deco_Spider_Cocoon", "Spider_Cocoon")),

            // Planks, halves, beams, roofs, stairs, fences and ornaments of every wood species.
            new Rule("bois travaillé", true, regex("Wood_[A-Za-z]+_(Planks(_Half)?|Beam|Roof(_[A-Za-z]+)?|Stairs|Fence(_[A-Za-z]+)?|Ornate|Decorative|Deco)")),
            // Decorative trunk variants (suffix Deco), village walls and the wall torch.
            new Rule("bois décoratif et village", true, n -> n.endsWith("_Deco") || n.startsWith("Wood_Village_") || n.equals("Wood_Torch_Wall")),
            // Platforms and fences of the prototype wood set.
            new Rule("plateformes de bois", true, n -> n.startsWith("Prototype_Wood_") && n.contains("_Fence")),
            // Roof gutters of the prototype set.
            new Rule("gouttières", true, n -> n.startsWith("Prototype_") && n.contains("_Roof_Gutter")),
            // Crafted rock: brick, cobble, smooth, halves, stairs, beams, roofs, walls, pillars (any rock family and the prototype ones).
            new Rule("pierre taillée", true, n -> (n.startsWith("Rock_") || n.startsWith("Prototype_Rock_")) && CRAFTED_STONE.test(n)),
            // The decorative iridescent rock.
            new Rule("roche iridescente travaillée", true, prefix("Rock_Iridescent_Processed")),
            // Path slabs (the full path block is natural) and tilled soil.
            new Rule("chemins et terre labourée", true, n -> n.startsWith("Soil_Pathway_") || n.equals("Soil_Dirt_Tilled")),
            // Clay, hive and snow bricks, halves and stairs of soil.
            new Rule("briques et demi-blocs de terre", true, n -> n.startsWith("Soil_") && (CRAFTED_STONE.test(n) || n.endsWith("_Path_Half"))),
            // Furniture of every style: tables, beds, benches, chests, crates, barrels, doors, windows, signs, ladders, lamps, lanterns, torches, candles, carpets, statues.
            new Rule("meubles", true, prefix("Furniture_")),
            // Windows, signs and candles of the prototype set.
            new Rule("fenêtres, enseignes et bougies", true, n -> n.startsWith("Prototype_Window_") || n.startsWith("Prototype_NumericalState_Candle")
                    || (n.startsWith("Prototype_") && n.contains("_Sign"))),
            // Crafting stations (workbench, furnace, loom, campfire and the others).
            new Rule("établis", true, prefix("Bench_", "GoblinLab_Tinkering_Bench")),
            // Containers and coops.
            new Rule("conteneurs", true, prefix("Container_", "Coop_")),
            // Rails.
            new Rule("rails", true, n -> n.equals("Rail") || n.startsWith("Rail_")),
            // Cloth: wool blocks, roofs, modern cloth (carpets and awnings included).
            new Rule("tissus", true, prefix("Cloth_")),
            // Metal blocks: ingot blocks, bars, roofs, pipes, stairs.
            new Rule("métal", true, prefix("Metal_")),
            // The Build set: plain coloured cubes, halves, fences, pipes and light sources.
            new Rule("blocs de construction", true, prefix("Build_")),
            // Glass.
            new Rule("verre", true, prefix("Glass_")),
            // Decorations placed by hand: lanterns, campfires, pots, chains, iron bars, ropes, shutters, scarecrows, trash piles, treasure.
            new Rule("décors posés", true, prefix("Deco_")),
            // Paintings and graffiti.
            new Rule("peintures", true, prefix("Goblin_Painting_", "Goblin_Graffiti_", "Lobby_Wall_Poster")),
            // Traps set on the ground.
            new Rule("pièges", true, prefix("Survival_Trap_", "Trap_")),
            // Portals placed by hand.
            new Rule("portails", true, prefix("Portal_Device")),
            // Signs that are not furniture.
            new Rule("enseignes", true, n -> n.endsWith("_Sign"))
    );

    /**
     * True when the block id is a block that does not occur in natural terrain and that players place. A leading * (block state) is
     * ignored. Null and empty are not built.
     */
    public static boolean isBuilt(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        String n = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        for (Rule r : RULES) {
            if (r.matches().test(n)) {
                return r.built();
            }
        }
        return false;
    }

    /**
     * True for a trunk, foliage, plant, decorative pebble or tree decoration block (vegetation that a station clears). A leading *
     * (block state) is ignored.
     */
    public static boolean removable(String blockId) {
        if (blockId == null) {
            return false;
        }
        String n = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        return n.startsWith("Plant_") || n.startsWith("Rubble_") || n.equals("Wood_Sticks") || n.startsWith("Deco_Nest")
                || n.startsWith("Deco_SpiderWeb") || n.startsWith("Deco_Spider_Cocoon")
                || (n.startsWith("Wood_") && n.matches("Wood_[A-Za-z]+_(Trunk(_Full|_Half|_Stairs)?|Branch_(Short|Long|Corner)|Roots)"));
    }

    /** True when the block is natural terrain or vegetation that may be dug: not null, not empty and not built. */
    public static boolean isNatural(String blockId) {
        return blockId != null && !blockId.isEmpty() && !isBuilt(blockId);
    }
}
