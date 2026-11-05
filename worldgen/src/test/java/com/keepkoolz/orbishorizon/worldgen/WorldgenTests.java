package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.keepkoolz.orbishorizon.api.ConfigRow;
import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;
import com.keepkoolz.orbishorizon.api.StationInfo;
import com.keepkoolz.orbishorizon.api.Stations;
import com.keepkoolz.orbishorizon.api.StationKinds;
import com.keepkoolz.orbishorizon.api.Terrain;
import com.keepkoolz.orbishorizon.api.TerrainProbe;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.PriorityBlockingQueue;
import com.keepkoolz.orbishorizon.TestSupport;
import com.keepkoolz.orbishorizon.TestSupport.GridProbe;
import com.keepkoolz.orbishorizon.TestSupport.CountingProbe;

/** Plain test harness of the worldgen extension: one static method per test, no library. */
public class WorldgenTests {
    private static int passed = 0;
    public static void main(String[] args) throws Exception {
        TestSupport.installApi();
        run("nextSiteSkipsTooClose", WorldgenTests::nextSiteSkipsTooClose);
        run("namesDeterministic", WorldgenTests::namesDeterministic);
        run("namesKindPrefix", WorldgenTests::namesKindPrefix);
        run("namesTooClose", WorldgenTests::namesTooClose);
        run("facingMatchesPierEnd", WorldgenTests::facingMatchesPierEnd);
        run("seaCellPicksShore", WorldgenTests::seaCellPicksShore);
        run("seaCellRejectsInland", WorldgenTests::seaCellRejectsInland);
        run("seaCellSkipsFarColumns", WorldgenTests::seaCellSkipsFarColumns);
        run("seaCellAnyShoreOffset", WorldgenTests::seaCellAnyShoreOffset);
        run("seaCellDeterministic", WorldgenTests::seaCellDeterministic);
        run("seaCellIgnoresLake", WorldgenTests::seaCellIgnoresLake);
        run("seaCellRespectsNetwork", WorldgenTests::seaCellRespectsNetwork);
        run("seaCellSkipsSpacedMain", WorldgenTests::seaCellSkipsSpacedMain);
        run("seaCellNeighboursKeepSpacing", WorldgenTests::seaCellNeighboursKeepSpacing);
        run("portalPicksFlatSpot", WorldgenTests::portalPicksFlatSpot);
        run("portalAvoidsWater", WorldgenTests::portalAvoidsWater);
        run("portalRespectsNetwork", WorldgenTests::portalRespectsNetwork);
        run("portalSecondInRangeRefused", WorldgenTests::portalSecondInRangeRefused);
        run("portalNoFlatSpot", WorldgenTests::portalNoFlatSpot);
        run("boatSiteFitsJaggedCoast", WorldgenTests::boatSiteFitsJaggedCoast);
        run("repairCandidatesOrder", WorldgenTests::repairCandidatesOrder);
        run("repairFindsNeighbour", WorldgenTests::repairFindsNeighbour);
        run("repairSkipsUnready", WorldgenTests::repairSkipsUnready);
        run("builtBlocksTable", WorldgenTests::builtBlocksTable);
        run("builtBlocksScan", WorldgenTests::builtBlocksScan);
        run("boatSiteFitsShallowBerth", WorldgenTests::boatSiteFitsShallowBerth);
        run("boatSiteFitsPierEndLand", WorldgenTests::boatSiteFitsPierEndLand);
        run("boatSiteFitsFloorOnLand", WorldgenTests::boatSiteFitsFloorOnLand);
        run("boatSiteFitsLandInPlaceBox", WorldgenTests::boatSiteFitsLandInPlaceBox);
        run("shuttleFloorMatchesPrefab", WorldgenTests::shuttleFloorMatchesPrefab);
        run("seaCellSkipsBlockedShore", WorldgenTests::seaCellSkipsBlockedShore);
        run("partnerShiftsAlongShore", WorldgenTests::partnerShiftsAlongShore);
        run("seaCellRanksSites", WorldgenTests::seaCellRanksSites);
        run("crossingKeepsFallbacks", WorldgenTests::crossingKeepsFallbacks);
        run("crossingFindsPartners", WorldgenTests::crossingFindsPartners);
        run("crossingTooFar", WorldgenTests::crossingTooFar);
        run("crossingStopsAtInlandWater", WorldgenTests::crossingStopsAtInlandWater);
        run("crossingReusesNearby", WorldgenTests::crossingReusesNearby);
        run("namesNoNearDuplicates", WorldgenTests::namesNoNearDuplicates);
        run("namesFallbackAlwaysValid", WorldgenTests::namesFallbackAlwaysValid);
        run("namesSyllableLists", WorldgenTests::namesSyllableLists);
        run("genClearRemovable", WorldgenTests::genClearRemovable);
        run("planTaskOrder", WorldgenTests::planTaskOrder);
        run("footprintCoversDome", WorldgenTests::footprintCoversDome);
        run("domeShape", WorldgenTests::domeShape);
        run("domeCellsRotated", WorldgenTests::domeCellsRotated);
        run("fillPlanColumns", WorldgenTests::fillPlanColumns);
        run("attachColumnsToShore", WorldgenTests::attachColumnsToShore);
        run("masterCheck", WorldgenTests::masterCheck);
        run("boatFootprintCoversBerthedBoat", WorldgenTests::boatFootprintCoversBerthedBoat);
        run("dredgeSelection", WorldgenTests::dredgeSelection);
        run("crossMaxValue", WorldgenTests::crossMaxValue);
        run("newChunksWorldMismatch", WorldgenTests::newChunksWorldMismatch);
        run("generatedWorldMismatch", WorldgenTests::generatedWorldMismatch);
        run("newChunksMarkAndQuery", WorldgenTests::newChunksMarkAndQuery);
        run("newChunksRoundTrip", WorldgenTests::newChunksRoundTrip);
        run("newChunksBadFile", WorldgenTests::newChunksBadFile);
        run("generatedRegistryRoundTrip", WorldgenTests::generatedRegistryRoundTrip);
        run("generatedPortalOnce", WorldgenTests::generatedPortalOnce);
        run("generatedForgetAndUnmark", WorldgenTests::generatedForgetAndUnmark);
        run("regeneratedKeepsSessionEntries", WorldgenTests::regeneratedKeepsSessionEntries);
        run("generatedCellOnce", WorldgenTests::generatedCellOnce);
        run("generatedCellSizeConversion", WorldgenTests::generatedCellSizeConversion);
        run("generatedOrigin", WorldgenTests::generatedOrigin);
        run("cellsAroundPlayer", WorldgenTests::cellsAroundPlayer);
        run("landCellPlansMainAndFallbacks", WorldgenTests::landCellPlansMainAndFallbacks);
        run("landCellAllWater", WorldgenTests::landCellAllWater);
        run("landCellRespectsExistingAir", WorldgenTests::landCellRespectsExistingAir);
        run("landCellSkipsExcludedWithoutTries", WorldgenTests::landCellSkipsExcludedWithoutTries);
        run("landCellDeterministic", WorldgenTests::landCellDeterministic);
        run("portalFallbacksSpaced", WorldgenTests::portalFallbacksSpaced);
        run("balloonNextSiteSpacing", WorldgenTests::balloonNextSiteSpacing);
        run("generatedLandCells", WorldgenTests::generatedLandCells);
        run("planAheadOffWhenDisabled", WorldgenTests::planAheadOffWhenDisabled);
        run("planAheadDedupe", WorldgenTests::planAheadDedupe);
        run("generatedBadFile", WorldgenTests::generatedBadFile);
        run("generatedBuiltSite", WorldgenTests::generatedBuiltSite);
        run("generatedRegistryActiveClamped", WorldgenTests::generatedRegistryActiveClamped);
        run("footprintFollowsAdvance", WorldgenTests::footprintFollowsAdvance);
        run("replannedOncePerSession", WorldgenTests::replannedOncePerSession);
        run("oldTerrainMovesBoat", WorldgenTests::oldTerrainMovesBoat);
        run("generatedRegistrySites", WorldgenTests::generatedRegistrySites);
        run("generatedRegistryOldFormat", WorldgenTests::generatedRegistryOldFormat);
        run("footprintChunksCover", WorldgenTests::footprintChunksCover);
        run("nearestUnbuiltEntry", WorldgenTests::nearestUnbuiltEntry);
        run("decideRules", WorldgenTests::decideRules);
        run("decideOldTerrain", WorldgenTests::decideOldTerrain);
        run("reviveSelection", WorldgenTests::reviveSelection);
        run("exhaustedReasonChoice", WorldgenTests::exhaustedReasonChoice);
        run("generatedRegistryReason", WorldgenTests::generatedRegistryReason);
        run("settingsOldTerrain", WorldgenTests::settingsOldTerrain);
        run("newChunksOldLoadAbandons", WorldgenTests::newChunksOldLoadAbandons);
        run("worldgenSettingDefault", WorldgenTests::worldgenSettingDefault);
        run("worldgenSettingsReviveRules", WorldgenTests::worldgenSettingsReviveRules);
        run("footprintContainsShapes", WorldgenTests::footprintContainsShapes);
        run("reloadedNewChunkRetriggers", WorldgenTests::reloadedNewChunkRetriggers);
        run("worldgenCountersDeclared", WorldgenTests::worldgenCountersDeclared);
        run("worldgenExtensionRows", WorldgenTests::worldgenExtensionRows);
        run("berthWaterBetter", WorldgenTests::berthWaterBetter);
        runQuiet("worldgenSettingsFromLegacy", WorldgenTests::worldgenSettingsFromLegacy);
        runQuiet("worldgenSettingsBadFile", WorldgenTests::worldgenSettingsBadFile);
        runQuiet("migrationCopiesAndRenames", WorldgenTests::migrationCopiesAndRenames);
        runQuiet("migrationRetryAfterFailure", WorldgenTests::migrationRetryAfterFailure);
        runQuiet("migrationNeverOverwrites", WorldgenTests::migrationNeverOverwrites);
        runQuiet("migrationSymbolicLinkNoop", WorldgenTests::migrationSymbolicLinkNoop);
        runQuiet("migrationUnreadableSource", WorldgenTests::migrationUnreadableSource);
        runQuiet("migrationFailureDisablesGeneration", WorldgenTests::migrationFailureDisablesGeneration);
        runQuiet("prepareGenerationWiring", WorldgenTests::prepareGenerationWiring);
        runQuiet("migrationSameDirectoryNoop", WorldgenTests::migrationSameDirectoryNoop);
        runQuiet("migrationLegacyFolderMissing", WorldgenTests::migrationLegacyFolderMissing);
        runQuiet("reconcileMarksRemoved", WorldgenTests::reconcileMarksRemoved);
        runQuiet("reconcileSkipsUntrustedNetwork", WorldgenTests::reconcileSkipsUntrustedNetwork);
        run("manifestsAligned", WorldgenTests::manifestsAligned);
        run("worldgenLangKeysComplete", WorldgenTests::worldgenLangKeysComplete);
        System.out.println("WorldgenTests: " + passed + " OK");
    }

    /** The two manifests share the version and the server range, and the extension depends on exactly that version of the mod. */
    static void manifestsAligned() throws Exception {
        org.bson.BsonDocument mod = org.bson.BsonDocument.parse(Files.readString(Path.of("manifest.json")));
        org.bson.BsonDocument ext = org.bson.BsonDocument.parse(Files.readString(Path.of("worldgen/manifest.json")));
        String version = mod.getString("Version").getValue();
        eq(version, ext.getString("Version").getValue());
        eq(mod.getString("ServerVersion").getValue(), ext.getString("ServerVersion").getValue());
        org.bson.BsonDocument dependencies = ext.getDocument("Dependencies");
        eq(1, dependencies.size());
        eq(version, dependencies.getString("KeepKoolz:Orbis Horizon").getValue());
        eq("KeepKoolz", ext.getString("Group").getValue());
        eq("Orbis Horizon Worldgen", ext.getString("Name").getValue());
        eq(true, ext.getBoolean("IncludesAssetPack").getValue());
        eq(false, ext.getBoolean("DisabledByDefault").getValue());
        String main = ext.getString("Main").getValue();
        check(Files.isRegularFile(Path.of("dist/worldgen-classes", main.replace('.', '/') + ".class")), "Main class compiled in dist/worldgen-classes");
        check(java.lang.reflect.Modifier.isPublic(Class.forName(main).getModifiers()), "Main class is public");
    }

    /** The extension carries its own texts in both languages: the same keys in both, none left in the mod, no em dash. */
    static void worldgenLangKeysComplete() throws Exception {
        Set<String> en = langKeys(Path.of("worldgen/Server/Languages/en-US/server.lang"));
        Set<String> fr = langKeys(Path.of("worldgen/Server/Languages/fr-FR/server.lang"));
        eq(22, en.size());
        eq(en, fr);
        for (String lang : new String[] {"en-US", "fr-FR"}) {
            String ext = Files.readString(Path.of("worldgen/Server/Languages/" + lang + "/server.lang"));
            check(!ext.contains("\u2014"), lang + " extension texts hold no em dash");
            Set<String> mod = langKeys(Path.of("Server/Languages/" + lang + "/server.lang"));
            for (String k : en) {
                check(!mod.contains(k), lang + " mod texts do not hold " + k);
            }
        }
    }

    private static Set<String> langKeys(Path file) throws Exception {
        Set<String> keys = new java.util.TreeSet<>();
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf(" =");
            if (eq > 0 && !line.startsWith("#")) {
                keys.add(line.substring(0, eq).trim());
            }
        }
        return keys;
    }

    interface Test { void run() throws Exception; }

    private static void run(String name, Test t) {
        try {
            t.run();
            passed++;
        } catch (Throwable e) {
            System.err.println("FAILED: " + name);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Runs a case that provokes logged failures with the loggers of the extension and of the API silenced, then restores them. */
    private static void runQuiet(String name, Test t) {
        String[] names = {"OrbisHorizonApi", "WorldgenSettings", "WorldgenMigration", "WorldgenReconcile", "TransitNetwork"};
        java.util.logging.Level[] saved = new java.util.logging.Level[names.length];
        for (int i = 0; i < names.length; i++) {
            saved[i] = com.hypixel.hytale.logger.HytaleLogger.get(names[i]).getLevel();
            com.hypixel.hytale.logger.HytaleLogger.get(names[i]).setLevel(java.util.logging.Level.OFF);
        }
        try {
            run(name, t);
        } finally {
            for (int i = 0; i < names.length; i++) {
                com.hypixel.hytale.logger.HytaleLogger.get(names[i]).setLevel(saved[i]);
            }
        }
    }

    private static StationKinds kinds() {
        return OrbisHorizonApi.get().kinds();
    }

    private static Stations stations() {
        return OrbisHorizonApi.get().stations();
    }

    private static Terrain terrain() {
        return OrbisHorizonApi.get().terrain();
    }

    private static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /** One row of the given runs: a character repeated for a number of 16 block cells. */
    private static GridProbe strip(Object... runs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length; i += 2) {
            sb.append(String.valueOf(runs[i]).repeat((Integer) runs[i + 1]));
        }
        return new GridProbe(sb.toString());
    }

    private static void deleteAll(Path dir) throws Exception {
        try (var w = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }


    private static List<WorldStationPlanner.PlannedSite> plan(TerrainProbe t, int cellX, List<int[]> existing) {
        return WorldStationPlanner.planSeaCell(5L, cellX, 0, t, existing);
    }

    static void facingMatchesPierEnd() {
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Vector3i v = WorldStationPlanner.facing(d[0], d[1]).rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
            eq(d[0], v.x);
            eq(d[1], v.z);
        }
        eq(Rotation.None, WorldStationPlanner.facing(0, 1));
        eq(Rotation.OneEighty, WorldStationPlanner.facing(0, -1));
        eq(0, WorldStationPlanner.cellOf(1023));
        eq(1, WorldStationPlanner.cellOf(1024));
        eq(-1, WorldStationPlanner.cellOf(-1));
    }

    static void seaCellPicksShore() {
        GridProbe g = strip('~', 32, '#', 32);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 0, List.of());
        check(!sites.isEmpty(), "a station is planned");
        WorldStationPlanner.PlannedSite s = sites.get(0);
        eq(StationKinds.BOAT, s.kind());
        eq(512, s.x());
        check(!s.partner(), "not a partner");
        eq(WorldStationPlanner.facing(-1, 0), s.rotation());
        check(terrain().isLand(g, s.x(), s.z()), "on land");
        check(terrain().isSea(g, s.x() - 8, s.z()), "sea ahead");
    }

    static void seaCellRejectsInland() {
        CountingProbe p = new CountingProbe(Integer.MAX_VALUE);
        check(plan(p, 0, List.of()).isEmpty(), "no station inland");
        eq(0, p.heightCalls);
        check(WorldStationPlanner.seaSamples(p, 0, 0).isEmpty(), "no sea sample inland");
    }

    static void seaCellSkipsFarColumns() {
        CountingProbe p = new CountingProbe(900);
        check(!WorldStationPlanner.seaSamples(p, 0, 0).isEmpty(), "sea sampled");
        List<WorldStationPlanner.PlannedSite> sites = plan(p, 0, List.of());
        check(!sites.isEmpty(), "a station is planned");
        check(p.minHeightX >= 900 - WorldStationPlanner.SEA_NEAR - WorldStationPlanner.ZONE_STEP,
            "no height asked far from the sea: " + p.minHeightX);
        WorldStationPlanner.PlannedSite s = sites.get(0);
        check(terrain().isLand(p, s.x(), s.z()), "on land");
        check(s.x() > 800 && s.x() < 900, "on the coast: " + s.x());
    }

    private static boolean pierEndIsSea(TerrainProbe t, WorldStationPlanner.PlannedSite s) {
        Vector3i end = s.rotation().rotateYaw(new Vector3i(kinds().boatPierEnd()[0],
                kinds().boatPierEnd()[1], kinds().boatPierEnd()[2]), new Vector3i());
        return terrain().isSea(t, s.x() + end.x, s.z() + end.z);
    }

    /** Straight shore along one axis: land where (coordinate * sign) is below edge * sign, sea beyond. */
    private static TerrainProbe straightShore(boolean alongX, int edge, int sign) {
        return new TerrainProbe() {
            private boolean sea(int x, int z) {
                int c = alongX ? x : z;
                return sign > 0 ? c >= edge : c < edge;
            }

            @Override
            public int height(int x, int z) {
                return sea(x, z) ? 100 : 120;
            }

            @Override
            public String zone(int x, int z) {
                return sea(x, z) ? "Oceans" : "Zone1_Tier1";
            }

            @Override
            public String biome(int x, int z) {
                return "";
            }
        };
    }

    static void seaCellAnyShoreOffset() {
        Object[][] cases = {
            {true, 1, 509, 1, 0}, {true, -1, 510, -1, 0}, {false, 1, 509, 0, 1}, {false, -1, 510, 0, -1}};
        for (Object[] c : cases) {
            boolean alongX = (Boolean) c[0];
            int sign = (Integer) c[1];
            TerrainProbe t = straightShore(alongX, 510, sign);
            List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
            check(!sites.isEmpty(), "station for shore " + alongX + " " + sign);
            WorldStationPlanner.PlannedSite s = sites.get(0);
            eq((Integer) c[2], alongX ? s.x() : s.z());
            eq(WorldStationPlanner.facing((Integer) c[3], (Integer) c[4]), s.rotation());
            check(pierEndIsSea(t, s), "pier end on sea " + alongX + " " + sign);
        }
    }

    static void seaCellDeterministic() {
        GridProbe g = strip('~', 32, '#', 32);
        eq(plan(g, 0, List.of()), plan(g, 0, List.of()));
    }

    static void seaCellIgnoresLake() {
        eq(0, plan(strip('#', 64, 'l', 64), 0, List.of()).size());
        eq(0, plan(strip('l', 128), 0, List.of()).size());
    }

    private interface Heights {
        int at(int x, int z);
    }

    private static TerrainProbe heights(Heights h) {
        return new TerrainProbe() {
            @Override
            public int height(int x, int z) {
                return h.at(x, z);
            }

            @Override
            public String zone(int x, int z) {
                return "Zone1_Tier1";
            }

            @Override
            public String biome(int x, int z) {
                return "";
            }
        };
    }

    /** True when the station footprint (rotated box) lies outside the square of half PORTAL_HALF around the portal. */
    private static boolean outsidePortal(WorldStationPlanner.PlannedSite s, int px, int pz) {
        Vector3i a = s.rotation().rotateYaw(new Vector3i(kinds().placeMin(StationKinds.AIR)[0], 0, kinds().placeMin(StationKinds.AIR)[2]), new Vector3i());
        Vector3i b = s.rotation().rotateYaw(new Vector3i(kinds().placeMax(StationKinds.AIR)[0], 0, kinds().placeMax(StationKinds.AIR)[2]), new Vector3i());
        int h = WorldStationPlanner.PORTAL_HALF;
        return s.x() + Math.max(a.x, b.x) < px - h || s.x() + Math.min(a.x, b.x) > px + h
                || s.z() + Math.max(a.z, b.z) < pz - h || s.z() + Math.min(a.z, b.z) > pz + h;
    }

    static void portalPicksFlatSpot() {
        TerrainProbe t = heights((x, z) -> 120 + Math.max(0, 30 - z));
        WorldStationPlanner.PlannedSite s = WorldStationPlanner.planPortal(0, 0, t, List.of());
        check(s != null, "a station is planned");
        eq(StationKinds.AIR, s.kind());
        check(!s.partner(), "not a partner");
        check(s.z() >= 30, "south of the portal, off the hill");
        check(outsidePortal(s, 0, 0), "outside the portal square");
        long d2 = (long) s.x() * s.x() + (long) s.z() * s.z();
        check(d2 >= 40L * 40 && d2 <= 90L * 90, "within the ring");
    }

    static void portalAvoidsWater() {
        TerrainProbe t = heights((x, z) -> x >= 30 ? 100 : 120);
        WorldStationPlanner.PlannedSite s = WorldStationPlanner.planPortal(0, 0, t, List.of());
        check(s != null, "a station is planned");
        check(s.x() < 30, "on the dry side");
        check(outsidePortal(s, 0, 0), "outside the portal square");
    }

    static void portalRespectsNetwork() {
        TerrainProbe t = heights((x, z) -> 120);
        check(WorldStationPlanner.planPortal(0, 0, t, List.of(new int[] {800, 0})) == null, "air station at 800");
        WorldStationPlanner.PlannedSite s = WorldStationPlanner.planPortal(0, 0, t, List.of(new int[] {900, 0}));
        check(s != null, "air station at 900 from the portal");
        long dx = s.x() - 900L, dz = s.z();
        check(dx * dx + dz * dz >= (long) WorldStationPlanner.BALLOON_SPACING * WorldStationPlanner.BALLOON_SPACING,
                "the site keeps its spacing, not only the portal");
        check(WorldStationPlanner.planPortal(0, 0, t, List.of(new int[] {WorldStationPlanner.BALLOON_SPACING + WorldStationPlanner.RING_MAX, 0})) != null,
                "air station beyond the ring");
    }

    static void portalSecondInRangeRefused() {
        TerrainProbe t = heights((x, z) -> 120);
        WorldStationPlanner.PlannedSite first = WorldStationPlanner.planPortal(0, 0, t, List.of());
        check(first != null, "first portal planned");
        List<int[]> air = new ArrayList<>();
        air.add(new int[] {first.x(), first.z()});
        check(WorldStationPlanner.planPortal(600, 0, t, air) == null, "second portal at 600 refused");
    }

    static void portalNoFlatSpot() {
        TerrainProbe t = heights((x, z) -> 120 + 3 * Math.floorDiv(x, 6));
        check(WorldStationPlanner.planPortal(0, 0, t, List.of()) == null, "staircase everywhere");
    }

    static void seaCellRespectsNetwork() {
        GridProbe g = strip('~', 32, '#', 32);
        WorldStationPlanner.PlannedSite s = plan(g, 0, List.of()).get(0);
        // a station in the middle of the cell's shore is within BOAT_SPACING of every candidate
        eq(0, plan(g, 0, List.of(new int[] {s.x(), 512})).size());
        int[] near = {s.x() + 500, s.z()};
        for (WorldStationPlanner.PlannedSite p : plan(g, 0, List.of(near))) {
            if (!p.partner()) {
                long dx = p.x() - near[0], dz = p.z() - near[1];
                check(dx * dx + dz * dz >= (long) WorldStationPlanner.BOAT_SPACING * WorldStationPlanner.BOAT_SPACING, "main site keeps its spacing");
            }
        }
        check(!plan(g, 0, List.<int[]>of(new int[] {s.x() + 800, s.z()})).isEmpty(), "far station does not block");
    }

    static void seaCellSkipsSpacedMain() {
        TerrainProbe t = straightShore(true, 510, 1);
        WorldStationPlanner.PlannedSite s = plan(t, 0, List.of()).get(0);
        // a station just within BOAT_SPACING of the closest candidate, on the side away from the rest of the shore
        int[] other = {s.x(), s.z() < WorldStationPlanner.CELL / 2 ? s.z() - 690 : s.z() + 690};
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of(other));
        check(!sites.isEmpty(), "a farther candidate is planned");
        WorldStationPlanner.PlannedSite main = sites.get(0);
        check(!main.partner(), "main site first");
        check(main.x() != s.x() || main.z() != s.z(), "the closest candidate is skipped");
        long dx = main.x() - other[0], dz = main.z() - other[1];
        check(dx * dx + dz * dz >= (long) WorldStationPlanner.BOAT_SPACING * WorldStationPlanner.BOAT_SPACING, "main site keeps its spacing");
        check(WorldStationPlanner.boatSiteFits(t, main.x(), main.z(), main.rotation()), "the main site fits");
        for (WorldStationPlanner.Site f : main.fallbacks()) {
            long fx = f.x() - other[0], fz = f.z() - other[1];
            check(fx * fx + fz * fz >= (long) WorldStationPlanner.BOAT_SPACING * WorldStationPlanner.BOAT_SPACING, "fallback keeps its spacing");
        }
    }

    static void seaCellNeighboursKeepSpacing() {
        // shore along x: every cell has a shore, main sites in neighbouring cells must be BOAT_SPACING apart
        TerrainProbe t = straightShore(false, 510, 1);
        List<WorldStationPlanner.PlannedSite> first = plan(t, 0, List.of());
        check(!first.isEmpty(), "first cell planned");
        WorldStationPlanner.PlannedSite a = first.get(0);
        List<WorldStationPlanner.PlannedSite> second = plan(t, 1, List.of(new int[] {a.x(), a.z()}));
        check(!second.isEmpty(), "neighbour cell planned");
        WorldStationPlanner.PlannedSite b = second.get(0);
        long dx = b.x() - a.x(), dz = b.z() - a.z();
        check(dx * dx + dz * dz >= (long) WorldStationPlanner.BOAT_SPACING * WorldStationPlanner.BOAT_SPACING, "neighbour main piers keep 700 blocks");
        eq(700, WorldStationPlanner.BOAT_SPACING);
    }

    private interface LandAt {
        boolean at(int x, int z);
    }

    /** Land at height 120 where the predicate is true, open ocean at height 100 elsewhere. */
    private static TerrainProbe coast(LandAt land) {
        return new TerrainProbe() {
            @Override
            public int height(int x, int z) {
                return land.at(x, z) ? 120 : 100;
            }

            @Override
            public String zone(int x, int z) {
                return land.at(x, z) ? "Zone1_Tier1" : "Oceans";
            }

            @Override
            public String biome(int x, int z) {
                return "";
            }
        };
    }

    static void repairCandidatesOrder() {
        WorldStationPlanner.Site planned = new WorldStationPlanner.Site(1000, 500, Rotation.None);
        List<WorldStationPlanner.Site> c = BoatSiteRepair.candidates(planned);
        eq(new WorldStationPlanner.Site(1008, 500, Rotation.None), c.get(0));
        eq(new WorldStationPlanner.Site(992, 500, Rotation.None), c.get(1));
        check(c.size() <= 22, "at most 22 candidates");
        eq(c.size(), new java.util.HashSet<>(c).size());
        check(!c.contains(planned), "planned site not a candidate");
    }

    static void repairFindsNeighbour() {
        WorldStationPlanner.Site planned = new WorldStationPlanner.Site(1000, 500, Rotation.None);
        WorldStationPlanner.Site target = new WorldStationPlanner.Site(984, 500, Rotation.None);
        BoatSiteRepair.Result r = BoatSiteRepair.search(planned, s -> true, s -> s.equals(target));
        eq(target, r.site());
        eq(4, r.tries());
    }

    static void repairSkipsUnready() {
        WorldStationPlanner.Site planned = new WorldStationPlanner.Site(1000, 500, Rotation.None);
        int[] calls = {0};
        BoatSiteRepair.Result r = BoatSiteRepair.search(planned, s -> s.x() >= planned.x(), s -> {
            calls[0]++;
            check(s.x() >= planned.x(), "fits called on an unready site");
            return false;
        });
        check(r.site() == null, "nothing fits");
        eq(calls[0], r.tries());
        check(calls[0] > 0, "ready sites were tried");
    }

    static void boatSiteFitsJaggedCoast() {
        Rotation east = WorldStationPlanner.facing(1, 0);
        TerrainProbe t = coast((x, z) -> x < 1010 || (x < 1030 && z >= 1000 && z <= 1002));
        check(!WorldStationPlanner.boatSiteFits(t, 1009, 1001, east), "site centred on the tooth is blocked");
        check(WorldStationPlanner.boatSiteFits(t, 1009, 1021, east), "site 20 blocks away fits");
        check(WorldStationPlanner.boatSiteFits(t, 1009, 981, east), "site 20 blocks the other way fits");
    }

    static void boatSiteFitsShallowBerth() {
        Rotation east = WorldStationPlanner.facing(1, 0);
        for (int[] c : new int[][] {{114, 0}, {113, 1}, {106, 1}}) {
            int seabed = c[0];
            TerrainProbe t = new TerrainProbe() {
                @Override
                public int height(int x, int z) {
                    return x < 1010 ? 120 : seabed;
                }

                @Override
                public String zone(int x, int z) {
                    return x < 1010 ? "Zone1_Tier1" : "Oceans";
                }

                @Override
                public String biome(int x, int z) {
                    return "";
                }
            };
            check(WorldStationPlanner.boatSiteFits(t, 1009, 1000, east) == (c[1] == 1), "seabed at " + seabed);
        }
    }

    /** The probe with a single column set to the given height, every other column unchanged. */
    private static TerrainProbe withColumn(TerrainProbe base, int cx, int cz, int height) {
        return new TerrainProbe() {
            @Override
            public int height(int x, int z) {
                return x == cx && z == cz ? height : base.height(x, z);
            }

            @Override
            public String zone(int x, int z) {
                return base.zone(x, z);
            }

            @Override
            public String biome(int x, int z) {
                return base.biome(x, z);
            }
        };
    }

    static void boatSiteFitsPierEndLand() {
        Rotation east = WorldStationPlanner.facing(1, 0);
        TerrainProbe base = coast((x, z) -> x < 1010);
        Vector3i end = east.rotateYaw(new Vector3i(kinds().boatPierEnd()[0], 0,
                kinds().boatPierEnd()[2]), new Vector3i());
        int ex = 1009 + end.x, ez = 1000 + end.z;
        check(WorldStationPlanner.boatSiteFits(base, 1009, 1000, east), "the plain coast fits");
        check(!WorldStationPlanner.boatSiteFits(withColumn(base, ex, ez, 114), 1009, 1000, east), "land at the pier end blocks the site");
        check(WorldStationPlanner.boatSiteFits(withColumn(base, ex, ez, 100), 1009, 1000, east), "sea at the pier end fits");
    }

    static void boatSiteFitsFloorOnLand() {
        Rotation east = WorldStationPlanner.facing(1, 0);
        TerrainProbe base = coast((x, z) -> x < 1010);
        check(WorldStationPlanner.boatSiteFits(base, 1009, 1000, east), "the plain coast fits");
        Vector3i berth = east.rotateYaw(new Vector3i(kinds().boatBerthOrigin()[0], 0,
                kinds().boatBerthOrigin()[2]), new Vector3i());
        Rotation berthRotation = east.add(StationKinds.BOAT_BERTH_ROTATION);
        check(!WorldStationPlanner.shuttleFloorColumns().isEmpty(), "the shuttle has floor columns");
        for (Vector3i cell : WorldStationPlanner.shuttleFloorColumns()) {
            Vector3i p = berthRotation.rotateYaw(cell, new Vector3i());
            TerrainProbe t = withColumn(base, 1009 + berth.x + p.x, 1000 + berth.z + p.z, 115);
            check(!WorldStationPlanner.boatSiteFits(t, 1009, 1000, east), "land under the floor column " + cell + " blocks the site");
        }
    }

    static void boatSiteFitsLandInPlaceBox() {
        Rotation east = WorldStationPlanner.facing(1, 0);
        Vector3i hut = east.rotateYaw(new Vector3i(-4, 0, 4), new Vector3i());
        int hx = 1009 + hut.x, hz = 1000 + hut.z;
        TerrainProbe t = withColumn(coast((x, z) -> x < 1010), hx, hz, 130);
        check(WorldStationPlanner.boatSiteFits(t, 1009, 1000, east), "land under the hut does not block the site");
    }

    static void shuttleFloorMatchesPrefab() throws Exception {
        Set<Vector3i> expected = new java.util.HashSet<>();
        for (Vector3i c : TestSupport.prefabCells("Steamboat_Public.prefab.json")) {
            if (c.y == 0) {
                expected.add(new Vector3i(c.x, 0, c.z));
            }
        }
        eq(expected, WorldStationPlanner.shuttleFloorColumns());
    }

    static void seaCellSkipsBlockedShore() {
        // An offshore bar blocks the pier box everywhere in the cell except along z 500..800.
        TerrainProbe t = coast((x, z) -> x < 510 || (x >= 518 && x <= 520 && (z < 500 || z > 800)));
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
        check(!sites.isEmpty(), "a station is planned on the straight stretch");
        WorldStationPlanner.PlannedSite s = sites.get(0);
        check(WorldStationPlanner.boatSiteFits(t, s.x(), s.z(), s.rotation()), "the site fits");
        check(s.z() >= 500 && s.z() <= 800, "the station is on the straight stretch");
    }

    static void partnerShiftsAlongShore() {
        TerrainProbe base = coast((x, z) -> x < 510 || x >= 1000);
        List<WorldStationPlanner.PlannedSite> before = plan(base, 0, List.of());
        eq(2, before.size());
        WorldStationPlanner.PlannedSite p0 = before.get(1);
        Vector3i f = p0.rotation().rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
        int lx = Math.abs(f.z), lz = Math.abs(f.x);
        // Land under the pier end column of the first landing.
        Vector3i end = p0.rotation().rotateYaw(new Vector3i(kinds().boatPierEnd()[0], 0,
                kinds().boatPierEnd()[2]), new Vector3i());
        TerrainProbe t = coast((x, z) -> {
            if (base.height(x, z) > Terrain.SEA_LEVEL) {
                return true;
            }
            return x == p0.x() + end.x && z == p0.z() + end.z;
        });
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
        eq(2, sites.size());
        WorldStationPlanner.PlannedSite p = sites.get(1);
        check(p.partner(), "partner flag");
        eq(p0.rotation(), p.rotation());
        check(!WorldStationPlanner.boatSiteFits(t, p0.x(), p0.z(), p0.rotation()), "the crossing landing is blocked");
        int shift = Math.abs((p.x() - p0.x()) * lx + (p.z() - p0.z()) * lz);
        check(shift > 0 && shift % 8 == 0 && shift <= 48, "shifted along the shore by a multiple of 8");
        check(WorldStationPlanner.boatSiteFits(t, p.x(), p.z(), p.rotation()), "the partner fits");
    }

    static void seaCellRanksSites() {
        TerrainProbe t = straightShore(true, 510, 1);
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
        check(!sites.isEmpty(), "a station is planned");
        WorldStationPlanner.PlannedSite main = sites.get(0);
        eq(WorldStationPlanner.MAX_SITES - 1, main.fallbacks().size());
        // Mirrors the seed of planSeaCell for cell (0, 0).
        SplittableRandom rnd = new SplittableRandom(5L ^ (0 * 0x9E3779B97F4A7C15L) ^ (0 * 0xC2B2AE3D27D4EB4FL) ^ 0x5EA);
        int drawX = rnd.nextInt(WorldStationPlanner.CELL), drawZ = rnd.nextInt(WorldStationPlanner.CELL);
        long last = (long) (main.x() - drawX) * (main.x() - drawX) + (long) (main.z() - drawZ) * (main.z() - drawZ);
        for (WorldStationPlanner.Site f : main.fallbacks()) {
            check(WorldStationPlanner.boatSiteFits(t, f.x(), f.z(), f.rotation()), "the fallback fits at " + f.x() + "," + f.z());
            check(pierEndIsSea(t, new WorldStationPlanner.PlannedSite(main.kind(), f.x(), f.z(), f.rotation(), false)),
                    "the fallback pier end is sea");
            check(f.x() != main.x() || f.z() != main.z(), "the fallback differs from the main site");
            long d = (long) (f.x() - drawX) * (f.x() - drawX) + (long) (f.z() - drawZ) * (f.z() - drawZ);
            check(d >= last, "the fallbacks follow the candidate order");
            last = d;
        }
        List<WorldStationPlanner.Site> all = new ArrayList<>(main.fallbacks());
        all.add(0, new WorldStationPlanner.Site(main.x(), main.z(), main.rotation()));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                check(Math.max(Math.abs(all.get(i).x() - all.get(j).x()), Math.abs(all.get(i).z() - all.get(j).z()))
                        >= WorldStationPlanner.FALLBACK_GAP, "kept sites are at least FALLBACK_GAP apart");
            }
        }
    }

    static void crossingKeepsFallbacks() {
        TerrainProbe base = coast((x, z) -> x < 510 || x >= 1000);
        List<WorldStationPlanner.PlannedSite> before = plan(base, 0, List.of());
        eq(2, before.size());
        WorldStationPlanner.PlannedSite p0 = before.get(1);
        Vector3i f = p0.rotation().rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
        int lx = Math.abs(f.z), lz = Math.abs(f.x);
        // Land under the pier end column of the first landing.
        Vector3i end = p0.rotation().rotateYaw(new Vector3i(kinds().boatPierEnd()[0], 0,
                kinds().boatPierEnd()[2]), new Vector3i());
        TerrainProbe t = coast((x, z) -> {
            if (base.height(x, z) > Terrain.SEA_LEVEL) {
                return true;
            }
            return x == p0.x() + end.x && z == p0.z() + end.z;
        });
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
        eq(2, sites.size());
        WorldStationPlanner.PlannedSite p = sites.get(1);
        check(p.partner(), "partner flag");
        check(!p.fallbacks().isEmpty(), "the partner keeps lateral fallbacks");
        eq(WorldStationPlanner.PARTNER_FALLBACKS, p.fallbacks().size());
        check(p.fallbacks().size() <= WorldStationPlanner.PARTNER_FALLBACKS, "at most PARTNER_FALLBACKS fallbacks");
        for (WorldStationPlanner.Site s : p.fallbacks()) {
            check(WorldStationPlanner.boatSiteFits(t, s.x(), s.z(), s.rotation()), "the partner fallback fits");
            check(Math.max(Math.abs(s.x() - p.x()), Math.abs(s.z() - p.z())) >= WorldStationPlanner.FALLBACK_GAP,
                    "the partner fallback is at least FALLBACK_GAP from the partner");
        }
    }

    static void crossingFindsPartners() {
        GridProbe g = strip('#', 27, '~', 37, '#', 64);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 0, List.of());
        eq(2, sites.size());
        WorldStationPlanner.PlannedSite partner = sites.get(1);
        check(partner.partner(), "partner flag");
        eq(StationKinds.BOAT, partner.kind());
        eq(WorldStationPlanner.facing(-1, 0), partner.rotation());
        check(terrain().isLand(g, partner.x(), partner.z()), "partner on land");
        eq(1024, partner.x());
        check(pierEndIsSea(g, partner), "partner pier end on sea");
    }

    static void crossingTooFar() {
        GridProbe g = strip('#', 167, '~', 281, '#', 64);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 7, List.of());
        eq(1, sites.size());
    }

    static void crossingStopsAtInlandWater() {
        GridProbe g = strip('#', 20, 'l', 10, '~', 34, '#', 64);
        eq(1, plan(g, 1, List.of()).size());
    }

    static void crossingReusesNearby() {
        GridProbe g = strip('#', 292, '~', 156, '#', 64);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 7, List.of());
        eq(2, sites.size());
        WorldStationPlanner.PlannedSite partner = sites.get(1);
        List<int[]> existing = List.<int[]>of(new int[] {partner.x() - 300, partner.z()});
        eq(1, plan(g, 7, existing).size());
    }

    static void namesDeterministic() {
        List<String> taken = List.of("Port Olhane");
        eq(StationNames.generate(7L, 100, -200, StationKinds.AIR, "Zone3_Tier1", taken),
            StationNames.generate(7L, 100, -200, StationKinds.AIR, "Zone3_Tier1", taken));
    }

    static void namesKindPrefix() {
        String sea = StationNames.generate(1L, 5, 9, StationKinds.BOAT, "Oceans", List.of());
        String air = StationNames.generate(1L, 5, 9, StationKinds.AIR, "Zone1_Tier1", List.of());
        check(sea.startsWith("Port "), "sea name has the Port prefix");
        check(!air.startsWith("Port "), "air name has no prefix");
        check(Character.isUpperCase(StationNames.core(sea).charAt(0)), "capital letter");
        eq("Valmère", StationNames.core("Port Valmère III"));
    }

    static void namesTooClose() {
        check(StationNames.tooClose("Valmère", "Valmere"), "accent only");
        check(StationNames.tooClose("Kerath", "Kerasse"), "four first letters");
        check(!StationNames.tooClose("Olhane", "Brivat"), "different names");
    }

    static void namesNoNearDuplicates() {
        String[] zones = {"Zone1_Tier1", "Zone2_Tier1", "Zone3_Tier1", "Zone4_Tier1", "Oceans"};
        List<String> taken = new ArrayList<>();
        int roman = 0;
        for (int i = 0; i < 500; i++) {
            String kind = i % 3 == 0 ? StationKinds.BOAT : StationKinds.AIR;
            String name = StationNames.generate(42L, i * 2048, (i % 7) * 2048, kind, zones[i % zones.length], taken);
            check(stations().nameAvailable(name, taken), "name available");
            if (name.matches(".* [IVXL]+$")) {
                roman++;
            } else {
                for (String t : taken) {
                    if (!t.matches(".* [IVXL]+$")) {
                        check(!StationNames.tooClose(StationNames.core(name), StationNames.core(t)), name + " close to " + t);
                    }
                }
            }
            taken.add(name);
        }
        check(roman < 5, "roman numerals: " + roman);
    }

    static void namesFallbackAlwaysValid() {
        for (String kind : new String[] {StationKinds.AIR, StationKinds.BOAT}) {
            List<String> cores = StationNames.candidates(5L, 300, 400, "Zone3_Tier1");
            List<String> taken = new ArrayList<>(cores);
            String suffixed = StationNames.generate(5L, 300, 400, kind, "Zone3_Tier1", taken);
            check(stations().nameAvailable(suffixed, taken), "name available");
            check(suffixed.matches(".* [IVXL]+$"), "roman suffix: " + suffixed);
            List<String> full = new ArrayList<>(taken);
            for (String c : cores) {
                for (String r : new String[] {"II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV",
                        "XV", "XVI", "XVII", "XVIII", "XIX", "XX"}) {
                    full.add(c + " " + r);
                    full.add("Port " + c + " " + r);
                }
            }
            String auto = StationNames.generate(5L, 300, 400, kind, "Zone3_Tier1", full);
            check(stations().nameAvailable(auto, full), "name available");
            eq(stations().autoName(full), auto);
        }
        List<String> taken = new ArrayList<>(StationNames.candidates(9L, 1, 1, "Zone1_Tier1"));
        check(stations().nameAvailable(StationNames.generate(9L, 1, 1, StationKinds.BOAT, "Zone1_Tier1", taken), taken), "name available");
    }

    static void namesSyllableLists() {
        check(StationNames.minSyllables() >= 12, "at least 12 syllables per list");
    }

    static void worldgenSettingsFromLegacy() throws Exception {
        Path legacy = Files.createTempDirectory("wg-legacy");
        Path dir = Files.createTempDirectory("wg-new");
        try {
            Files.writeString(legacy.resolve("transit-settings.json"), "{\"worldgenEnabled\": false, \"worldgenOldTerrain\": true}");
            WorldgenSettings.load(dir, legacy);
            eq(false, WorldgenSettings.enabled());
            eq(true, WorldgenSettings.oldTerrain());
            check(Files.isRegularFile(dir.resolve("worldgen-settings.json")), "worldgen-settings.json written");
            // The new file wins over the old one.
            Files.writeString(legacy.resolve("transit-settings.json"), "{\"worldgenEnabled\": true, \"worldgenOldTerrain\": false}");
            WorldgenSettings.load(dir, legacy);
            eq(false, WorldgenSettings.enabled());
            eq(true, WorldgenSettings.oldTerrain());
            // No key: the defaults.
            Path dir2 = Files.createTempDirectory("wg-new2");
            try {
                Files.writeString(legacy.resolve("transit-settings.json"), "{\"packBackView\": true}");
                WorldgenSettings.load(dir2, legacy);
                eq(true, WorldgenSettings.enabled());
                eq(false, WorldgenSettings.oldTerrain());
            } finally {
                deleteAll(dir2);
            }
            // Unreadable old file: defaults, nothing written.
            Path dir3 = Files.createTempDirectory("wg-new3");
            try {
                Files.writeString(legacy.resolve("transit-settings.json"), "{");
                WorldgenSettings.load(dir3, legacy);
                eq(true, WorldgenSettings.enabled());
                eq(false, WorldgenSettings.oldTerrain());
                check(!Files.exists(dir3.resolve("worldgen-settings.json")), "nothing written for an unreadable old file");
            } finally {
                deleteAll(dir3);
            }
        } finally {
            Path reset = Files.createTempDirectory("wg-reset");
            WorldgenSettings.load(reset, legacy);
            deleteAll(reset);
            deleteAll(legacy);
            deleteAll(dir);
        }
    }

    static void worldgenSettingsBadFile() throws Exception {
        Path dir = Files.createTempDirectory("wg-bad");
        try {
            Files.writeString(dir.resolve("worldgen-settings.json"), "{");
            WorldgenSettings.load(dir, dir);
            eq(true, WorldgenSettings.enabled());
            eq(false, WorldgenSettings.oldTerrain());
            check(Files.exists(dir.resolve("worldgen-settings.json.bad")), "renamed to .bad");
            check(Files.isRegularFile(dir.resolve("worldgen-settings.json")), "defaults written");
            WorldgenSettings.load(dir, dir);
            eq(true, WorldgenSettings.enabled());
            eq(false, WorldgenSettings.oldTerrain());
            // An old setting of the legacy file does not come back once the defaults are written.
            Files.writeString(dir.resolve("transit-settings.json"), "{\"worldgenEnabled\": false}");
            WorldgenSettings.load(dir, dir);
            eq(true, WorldgenSettings.enabled());
        } finally {
            deleteAll(dir);
        }
    }

    static void migrationCopiesAndRenames() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("mig-legacy");
        Path dir = Files.createTempDirectory("mig-dir");
        try {
            Files.writeString(legacy.resolve("generated-stations.json"), "{\"a\":1}");
            Files.write(legacy.resolve("generated-chunks-a.bin"), new byte[] {1, 2, 3});
            Files.write(legacy.resolve("generated-chunks-b.bin"), new byte[] {4, 5});
            Files.write(legacy.resolve("generated-chunks-a.bin.old"), new byte[] {9});
            eq(3, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(Arrays.equals(new byte[] {1, 2, 3}, Files.readAllBytes(dir.resolve("generated-chunks-a.bin"))), "chunks a copied");
            check(Arrays.equals(new byte[] {4, 5}, Files.readAllBytes(dir.resolve("generated-chunks-b.bin"))), "chunks b copied");
            eq("{\"a\":1}", Files.readString(dir.resolve("generated-stations.json")));
            for (String n : List.of("generated-stations.json", "generated-chunks-a.bin", "generated-chunks-b.bin")) {
                check(!Files.exists(legacy.resolve(n)), n + " original renamed");
                check(Files.exists(legacy.resolve(n + ".migrated")), n + ".migrated exists");
            }
            check(Files.exists(legacy.resolve("generated-chunks-a.bin.old")), ".old untouched");
            check(!Files.exists(dir.resolve("generated-chunks-a.bin.old")), ".old not copied");
            eq(0, WorldgenMigration.copyLegacyFiles(legacy, dir));
        } finally {
            deleteAll(legacy);
            deleteAll(dir);
        }
    }

    static void migrationRetryAfterFailure() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("mig-retry-legacy");
        Path dir = Files.createTempDirectory("mig-retry-dir");
        try {
            // A rename that failed in an earlier run: the copy is there and identical, the original is still there.
            Files.writeString(legacy.resolve("generated-stations.json"), "same");
            Files.writeString(dir.resolve("generated-stations.json"), "same");
            eq(1, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(!Files.exists(legacy.resolve("generated-stations.json")), "original renamed without a copy");
            check(Files.exists(legacy.resolve("generated-stations.json.migrated")), "migrated marker");
            eq("same", Files.readString(dir.resolve("generated-stations.json")));
            // A copy that fails (the destination is a folder) leaves its original, the others are migrated.
            Files.writeString(legacy.resolve("generated-chunks-a.bin"), "a");
            Files.writeString(legacy.resolve("generated-chunks-b.bin"), "b");
            Files.createDirectory(dir.resolve("generated-chunks-a.bin"));
            Files.writeString(dir.resolve("generated-chunks-a.bin").resolve("keep"), "x");
            eq(1, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(Files.exists(legacy.resolve("generated-chunks-a.bin")), "failed file keeps its original");
            check(!Files.exists(legacy.resolve("generated-chunks-b.bin")), "other file migrated");
            eq("b", Files.readString(dir.resolve("generated-chunks-b.bin")));
            // Once corrected, the next run finishes.
            Files.delete(dir.resolve("generated-chunks-a.bin").resolve("keep"));
            Files.delete(dir.resolve("generated-chunks-a.bin"));
            eq(1, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(!Files.exists(legacy.resolve("generated-chunks-a.bin")), "second run renames");
            eq("a", Files.readString(dir.resolve("generated-chunks-a.bin")));
        } finally {
            deleteAll(legacy);
            deleteAll(dir);
        }
    }

    static void migrationNeverOverwrites() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("mig-over-legacy");
        Path dir = Files.createTempDirectory("mig-over-dir");
        try {
            Files.writeString(legacy.resolve("generated-stations.json"), "old");
            Files.writeString(dir.resolve("generated-stations.json"), "new");
            eq(0, WorldgenMigration.copyLegacyFiles(legacy, dir));
            eq("old", Files.readString(legacy.resolve("generated-stations.json")));
            eq("new", Files.readString(dir.resolve("generated-stations.json")));
            check(!Files.exists(legacy.resolve("generated-stations.json.migrated")), "no marker");
        } finally {
            deleteAll(legacy);
            deleteAll(dir);
        }
    }

    static void migrationSymbolicLinkNoop() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("mig-link-legacy");
        Path base = Files.createTempDirectory("mig-link-base");
        Path dir = base.resolve("linked");
        try {
            try {
                Files.createSymbolicLink(dir, legacy);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                return;
            }
            Files.writeString(legacy.resolve("generated-stations.json"), "x");
            eq(0, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(Files.exists(legacy.resolve("generated-stations.json")), "file stays in place");
            check(!Files.exists(legacy.resolve("generated-stations.json.migrated")), "no marker");
            // A file reached through a link to the same file is not renamed either.
            Path other = Files.createTempDirectory("mig-link-other");
            try {
                Files.createSymbolicLink(other.resolve("generated-stations.json"), legacy.resolve("generated-stations.json"));
                eq(0, WorldgenMigration.copyLegacyFiles(legacy, other));
                check(Files.exists(legacy.resolve("generated-stations.json")), "file reached by a link stays");
            } finally {
                deleteAll(other);
            }
        } finally {
            Files.deleteIfExists(dir);
            deleteAll(legacy);
            deleteAll(base);
        }
    }

    /** Makes the copy of the named file fail whatever the rights: a non-empty folder occupies the place of its temporary copy. */
    private static Path blockCopy(Path dir, String name) throws Exception {
        Path block = dir.resolve(name + ".tmp");
        Files.createDirectories(block);
        Files.writeString(block.resolve("keep"), "x");
        return block;
    }

    static void migrationUnreadableSource() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("mig-unread-legacy");
        Path dir = Files.createTempDirectory("mig-unread-dir");
        try {
            Path bad = legacy.resolve("generated-chunks-a.bin");
            Files.writeString(bad, "a");
            Files.writeString(legacy.resolve("generated-chunks-b.bin"), "b");
            // The copy of the first file cannot be made: the others are still migrated.
            Path block = blockCopy(dir, "generated-chunks-a.bin");
            eq(1, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(Files.exists(bad), "original of the failed file stays");
            check(!Files.exists(legacy.resolve("generated-chunks-b.bin")), "other file migrated");
            eq("b", Files.readString(dir.resolve("generated-chunks-b.bin")));
            deleteAll(block);
            eq(1, WorldgenMigration.copyLegacyFiles(legacy, dir));
            check(Files.exists(legacy.resolve("generated-chunks-a.bin.migrated")), "failed file migrated once the place is free");
        } finally {
            deleteAll(legacy);
            deleteAll(dir);
        }
        // Same case with a source the process cannot read, when the system honours the right.
        Path legacy2 = Files.createTempDirectory("mig-unread-legacy2");
        Path dir2 = Files.createTempDirectory("mig-unread-dir2");
        try {
            Path bad = legacy2.resolve("generated-chunks-a.bin");
            Files.writeString(bad, "a");
            Files.writeString(legacy2.resolve("generated-chunks-b.bin"), "b");
            if (!bad.toFile().setReadable(false) || Files.isReadable(bad)) {
                System.out.println("SKIPPED: migrationUnreadableSource (setReadable ignored)");
                return;
            }
            try {
                eq(1, WorldgenMigration.copyLegacyFiles(legacy2, dir2));
                check(Files.exists(bad), "unreadable original stays");
                check(!Files.exists(legacy2.resolve("generated-chunks-b.bin")), "other file migrated");
                eq("b", Files.readString(dir2.resolve("generated-chunks-b.bin")));
            } finally {
                bad.toFile().setReadable(true);
            }
        } finally {
            deleteAll(legacy2);
            deleteAll(dir2);
        }
    }

    static void migrationLegacyFolderMissing() throws Exception {
        TestSupport.installApi();
        Path parent = Files.createTempDirectory("mig-missing");
        Path dir = Files.createTempDirectory("mig-missing-dir");
        try {
            // A new world: the data folder of Orbis Horizon is not created yet when the extension starts.
            WorldgenMigration.Result r = WorldgenMigration.migrate(parent.resolve("KeepKoolz_Orbis Horizon"), dir);
            eq(0, r.migrated());
            eq(0, r.failed());
            check(WorldgenPlugin.generationAllowed(r), "a missing earlier folder allows the generation");
        } finally {
            deleteAll(parent);
            deleteAll(dir);
        }
    }

    static void migrationFailureDisablesGeneration() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("mig-dis-legacy");
        Path dir = Files.createTempDirectory("mig-dis-dir");
        try {
            Path bad = legacy.resolve("generated-stations.json");
            Files.writeString(bad, "{\"a\":1}");
            Files.writeString(legacy.resolve("generated-chunks-b.bin"), "b");
            // Nothing to migrate, or all migrated: generation allowed.
            Path empty = Files.createTempDirectory("mig-dis-empty");
            try {
                check(WorldgenPlugin.generationAllowed(WorldgenMigration.migrate(empty, dir)), "nothing to migrate allows generation");
            } finally {
                deleteAll(empty);
            }
            Path block = blockCopy(dir, "generated-stations.json");
            WorldgenMigration.Result failed = WorldgenMigration.migrate(legacy, dir);
            eq(1, failed.migrated());
            eq(1, failed.failed());
            check(!WorldgenPlugin.generationAllowed(failed), "a failed file disables the generation for the session");
            eq("{\"a\":1}", Files.readString(bad));
            check(!Files.exists(legacy.resolve("generated-stations.json.migrated")), "original intact after the failure");
            deleteAll(block);
            WorldgenMigration.Result fixed = WorldgenMigration.migrate(legacy, dir);
            eq(1, fixed.migrated());
            eq(0, fixed.failed());
            check(WorldgenPlugin.generationAllowed(fixed), "generation allowed once the place is free");
            eq("{\"a\":1}", Files.readString(dir.resolve("generated-stations.json")));
            check(Files.exists(legacy.resolve("generated-stations.json.migrated")), "original renamed after the fix");
        } finally {
            deleteAll(legacy);
            deleteAll(dir);
        }
        // Same case with a source the process cannot read, when the system honours the right.
        Path legacy2 = Files.createTempDirectory("mig-dis-legacy2");
        Path dir2 = Files.createTempDirectory("mig-dis-dir2");
        try {
            Path bad = legacy2.resolve("generated-stations.json");
            Files.writeString(bad, "{\"a\":1}");
            if (!bad.toFile().setReadable(false) || Files.isReadable(bad)) {
                System.out.println("SKIPPED: migrationFailureDisablesGeneration (setReadable ignored)");
                return;
            }
            try {
                WorldgenMigration.Result failed = WorldgenMigration.migrate(legacy2, dir2);
                eq(1, failed.failed());
                check(!WorldgenPlugin.generationAllowed(failed), "an unreadable file disables the generation");
                check(Files.exists(bad), "unreadable original intact");
            } finally {
                bad.toFile().setReadable(true);
            }
            WorldgenMigration.Result fixed = WorldgenMigration.migrate(legacy2, dir2);
            eq(0, fixed.failed());
            check(WorldgenPlugin.generationAllowed(fixed), "generation allowed once the source is readable");
            check(Files.exists(legacy2.resolve("generated-stations.json.migrated")), "original renamed after the fix");
        } finally {
            deleteAll(legacy2);
            deleteAll(dir2);
        }
    }

    /** A registry file with three built stations, as the earlier data folder holds it. */
    private static void writeLegacyRegistry(Path legacy) throws Exception {
        Path tmp = Files.createTempDirectory("prep-reg");
        try {
            registryOf3(tmp);
            Files.copy(tmp.resolve("generated-stations.json"), legacy.resolve("generated-stations.json"));
        } finally {
            deleteAll(tmp);
        }
    }

    static void prepareGenerationWiring() throws Exception {
        TestSupport.installApi();
        Path legacy = Files.createTempDirectory("prep-legacy");
        Path dir = Files.createTempDirectory("prep-dir");
        Path stale = Files.createTempDirectory("prep-stale");
        try {
            Files.writeString(legacy.resolve("generated-chunks-b.bin"), "b");
            // Earlier state pointing at another folder: a failed start must not touch dir through it nor load into it.
            GeneratedStations.get().load(stale);
            NewChunks.init(stale);
            Path block = blockCopy(dir, "generated-chunks-b.bin");
            var network = fakeStations(Set.of("0:0", "100:0", "200:0"), true);
            check(!WorldgenPlugin.prepareGeneration(dir, legacy, network), "a failed migration keeps the generation off");
            check(Files.exists(dir.resolve("worldgen-settings.json")), "settings are written even after a failure");
            GeneratedStations.get().add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 5, 5, Rotation.None, false));
            NewChunks.forWorld("w", UUID.randomUUID()).mark(1, 1);
            NewChunks.flushAll();
            check(!Files.exists(dir.resolve("generated-stations.json")), "no registry written in dir after a failure");
            check(!Files.exists(dir.resolve("generated-chunks-w.bin")), "no chunk map written in dir after a failure");
            deleteAll(block);
            writeLegacyRegistry(legacy);
            // Success: the migrated registry is loaded and the chunk maps go to dir.
            check(WorldgenPlugin.prepareGeneration(dir, legacy, network), "generation starts once the files are migrated");
            eq(3, GeneratedStations.get().entries("w").size());
            for (var e : GeneratedStations.get().entries("w")) {
                eq(GeneratedStations.State.BUILT, e.state);
            }
            NewChunks.forWorld("w2", UUID.randomUUID()).mark(2, 2);
            NewChunks.flushAll();
            check(Files.exists(dir.resolve("generated-chunks-w2.bin")), "chunk maps are written in dir after a success");
        } finally {
            deleteAll(legacy);
            deleteAll(dir);
            deleteAll(stale);
        }
    }

    static void migrationSameDirectoryNoop() throws Exception {
        TestSupport.installApi();
        Path dir = Files.createTempDirectory("mig-same");
        try {
            Files.writeString(dir.resolve("generated-stations.json"), "x");
            eq(0, WorldgenMigration.copyLegacyFiles(dir, dir));
            eq("x", Files.readString(dir.resolve("generated-stations.json")));
            check(!Files.exists(dir.resolve("generated-stations.json.migrated")), "no marker");
        } finally {
            deleteAll(dir);
        }
    }

    private static GeneratedStations registryOf3(Path dir) throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        for (int i = 0; i < 3; i++) {
            g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, i * 100, 0, Rotation.None, false));
            g.setState(g.entries("w").get(i), GeneratedStations.State.BUILT);
        }
        return g;
    }

    static void reconcileMarksRemoved() throws Exception {
        TestSupport.installApi();
        Path dir = Files.createTempDirectory("reconcile");
        try {
            GeneratedStations g = registryOf3(dir);
            g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 900, 0, Rotation.None, false));
            Set<String> found = Set.of("0:0");
            var stations = fakeStations(found, true);
            eq(2, WorldgenReconcile.run(g, stations));
            eq(GeneratedStations.State.BUILT, g.entries("w").get(0).state);
            eq(GeneratedStations.State.REMOVED, g.entries("w").get(1).state);
            eq(GeneratedStations.State.REMOVED, g.entries("w").get(2).state);
            eq(GeneratedStations.State.PLANNED, g.entries("w").get(3).state);
            check(g.worlds().contains("w"), "worlds lists the world");
        } finally {
            Path reset = Files.createTempDirectory("reconcile-reset");
            GeneratedStations.get().load(reset);
            deleteAll(reset);
            deleteAll(dir);
        }
    }

    /** Stations that find only the columns in the set (as x:z) and report the given trust. Other methods are not expected. */
    private static com.keepkoolz.orbishorizon.api.Stations fakeStations(Set<String> columns, boolean trusted) {
        return (com.keepkoolz.orbishorizon.api.Stations) java.lang.reflect.Proxy.newProxyInstance(
                WorldgenTests.class.getClassLoader(), new Class<?>[] {com.keepkoolz.orbishorizon.api.Stations.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "networkTrusted" -> trusted;
                    case "stationAt" -> columns.contains(args[1] + ":" + args[2])
                            ? new StationInfo("s", (String) args[0], new Vector3i((Integer) args[1], 64, (Integer) args[2]),
                                    Rotation.None, StationKinds.AIR) : null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    static void reconcileSkipsUntrustedNetwork() throws Exception {
        TestSupport.installApi();
        Path dir = Files.createTempDirectory("reconcile-untrusted");
        Path net = Files.createTempDirectory("reconcile-net");
        try {
            GeneratedStations g = registryOf3(dir);
            eq(0, WorldgenReconcile.run(g, fakeStations(Set.of(), false)));
            for (int i = 0; i < 3; i++) {
                eq(GeneratedStations.State.BUILT, g.entries("w").get(i).state);
            }
            // The real case: stations.json cannot be read.
            Files.writeString(net.resolve("stations.json"), "{");
            TestSupport.loadNetwork(net);
            check(!stations().networkTrusted(), "network not trusted after an unreadable stations.json");
            check(Files.exists(net.resolve("stations.json.bad")), "stations.json kept as .bad");
            eq(0, WorldgenReconcile.run(g, stations()));
            for (int i = 0; i < 3; i++) {
                eq(GeneratedStations.State.BUILT, g.entries("w").get(i).state);
            }
        } finally {
            Path reset1 = Files.createTempDirectory("reconcile-net-reset");
            Path reset2 = Files.createTempDirectory("reconcile-reset2");
            TestSupport.loadNetwork(reset1);
            GeneratedStations.get().load(reset2);
            deleteAll(reset1);
            deleteAll(reset2);
            deleteAll(dir);
            deleteAll(net);
        }
    }

    static void worldgenCountersDeclared() throws Exception {
        List<String> counters = WorldgenExtension.COUNTERS;
        check(counters.size() == 11, "11 worldgen counters");
        String project = Files.readString(Path.of("Server/Beacon/project.json"));
        String usageCounters = Files.readString(Path.of("plugin/src/main/java/com/keepkoolz/orbishorizon/UsageCounters.java"));
        for (String n : counters) {
            String quoted = "\"" + n + "\"";
            int count = 0;
            for (int i = project.indexOf(quoted); i >= 0; i = project.indexOf(quoted, i + 1)) {
                count++;
            }
            check(count == 2, n + " declared twice in project.json, found " + count);
            check(!usageCounters.contains(quoted), n + " not in UsageCounters.NAMES");
        }
    }

    static void worldgenExtensionRows() throws Exception {
        Path dir = Files.createTempDirectory("worldgen-rows");
        try {
            WorldgenSettings.load(dir, dir);
            List<com.keepkoolz.orbishorizon.api.ConfigRow> rows = new WorldgenExtension().configRows();
            eq(2, rows.size());
            eq("worldgen", rows.get(0).key());
            eq("worldgenOldTerrain", rows.get(1).key());
            for (com.keepkoolz.orbishorizon.api.ConfigRow r : rows) {
                eq("server.orbis_horizon.ui.config.section.world", r.sectionKey());
            }
            eq("server.orbis_horizon.ui.config.worldgen", rows.get(0).labelKey());
            eq("server.orbis_horizon.ui.config.worldgenOldTerrain", rows.get(1).labelKey());
            eq(WorldgenSettings.enabled(), rows.get(0).read().getAsBoolean());
            eq(WorldgenSettings.oldTerrain(), rows.get(1).read().getAsBoolean());
            rows.get(0).write().accept(false);
            eq(false, WorldgenSettings.enabled());
            rows.get(0).write().accept(true);
            eq(true, WorldgenSettings.enabled());
            rows.get(1).write().accept(false);
            eq(false, WorldgenSettings.oldTerrain());
        } finally {
            WorldgenSettings.setEnabled(true);
            deleteAll(dir);
        }
    }

    /** The berth water choice follows the same rules as the steamboat neighbour choice. */
    private static void berthWaterBetter() {
        StationBuilder.BerthWater flow = new StationBuilder.BerthWater(1, (byte) 3, false);
        StationBuilder.BerthWater hi = new StationBuilder.BerthWater(1, (byte) 5, false);
        StationBuilder.BerthWater src = new StationBuilder.BerthWater(2, (byte) 8, true);
        check(StationBuilder.BerthWater.better(null, flow) == flow, "first kept");
        check(StationBuilder.BerthWater.better(flow, hi) == hi, "higher level wins");
        check(StationBuilder.BerthWater.better(hi, flow) == hi, "lower level loses");
        check(StationBuilder.BerthWater.better(hi, src) == src, "source wins over flow");
        check(StationBuilder.BerthWater.better(src, hi) == src, "source kept");
        check(StationBuilder.BerthWater.better(flow, new StationBuilder.BerthWater(1, (byte) 3, false)) == flow, "tie keeps the first");
    }

    private static void builtBlocksTable() {
        String[] built = {"Wood_Oak_Planks_Half", "Wood_Softwood_Planks", "Wood_Redwood_Beam", "Wood_Softwood_Stairs", "Wood_Redwood_Fence",
                "Wood_Village_Wall_Full", "Wood_Torch_Wall", "Rock_Stone_Brick", "Rock_Basalt_Cobble", "Rock_Stone_Brick_Smooth_Half",
                "Rock_Sandstone_Half", "Rock_Volcanic_Brick_Roof", "Furniture_Crude_Torch", "Furniture_Village_Chest_Small",
                "Furniture_Tavern_Bench", "Furniture_Crude_Bed", "Furniture_Village_Window", "Furniture_Crude_Ladder", "Bench_WorkBench",
                "Bench_Furnace", "Deco_Lantern", "Cloth_Block_Wool_Green", "Metal_Iron", "Metal_Copper_Roof", "Build_Grey_Cube", "Rail",
                "Glass_Test_Blue", "Soil_Pathway_Half", "Soil_Clay_Brick_Half", "Soil_Dirt_Tilled", "Rock_Stone_Cobble_Half", "Cloth_Block_Wool_Blue", "Prototype_Window_Single", "*Furniture_Crude_Door"};
        for (String id : built) {
            check(BuiltBlocks.isBuilt(id), "built: " + id);
        }
        String[] natural = {"Wood_Oak_Trunk", "Wood_Oak_Trunk_Full", "Wood_Oak_Trunk_Stairs", "Wood_Birch_Branch_Long", "Wood_Oak_Roots",
                "Plant_Leaves_Oak", "Plant_Grass_Tall", "Plant_Crop_Wheat", "Rock_Stone", "Rock_Basalt", "Rock_Stone_Mossy", "Rock_Sandstone_Red",
                "Rock_Gem_Diamond", "Rock_Crystal_Blue_Large", "Rock_Stone_Stalactite_Large", "Rock_Ice", "Soil_Grass", "Soil_Dirt", "Soil_Sand",
                "Soil_Gravel", "Soil_Clay", "Soil_Snow", "Ore_Iron_Stone", "Rubble_Stone", "Wood_Sticks", "Deco_Bone_Pile", "Deco_Nest",
                "Fluid_Water", "Empty", "Soil_Pathway", "Soil_Clay_Red", "Soil_Clay_Yellow", "Soil_Clay_Grey", "Soil_Clay_Smooth_Black", "Soil_Clay_Brick",
                "Rock_Stone_Cobble", "Rock_Stone_Cobble_Mossy", "Furniture_Ancient_Barrel", "Furniture_Ancient_Crate", "Deco_Fire", "Cloth_Block_Wool_Red", "*Rock_Stone", "*Plant_Grass_Tall"};
        for (String id : natural) {
            check(!BuiltBlocks.isBuilt(id), "natural: " + id);
        }
        check(!BuiltBlocks.isBuilt(null), "null is not built");
        check(!BuiltBlocks.isBuilt(""), "empty is not built");
        check(BuiltBlocks.isBuilt("*Wood_Softwood_Stairs"), "a leading * is ignored for a built block");
    }

    private static void builtBlocksScan() {
        java.util.Map<String, String> cells = new java.util.HashMap<>();
        cells.put("3,5,3", "Rock_Stone");
        cells.put("-3,4,2", "*Furniture_Crude_Torch");
        BuiltBlocks.CellReader reader = (x, y, z) -> cells.get(x + "," + y + "," + z);
        int[] box = {0, 0, 0, 1, 10, 1};
        BuiltBlocks.Found far = BuiltBlocks.firstBuilt(box, 1, reader);
        check(far == null, "a construction beyond the margin is not seen");
        BuiltBlocks.Found near = BuiltBlocks.firstBuilt(box, 4, reader);
        check(near != null && near.block().equals("Furniture_Crude_Torch") && near.x() == -3 && near.y() == 4 && near.z() == 2,
                "a construction inside the margin is found with its cell, got " + near);
        cells.put("1,2,1", "Wood_Softwood_Planks");
        BuiltBlocks.Found low = BuiltBlocks.firstBuilt(box, 4, reader);
        check(low != null && low.y() == 2, "the lowest row is scanned first, got " + low);
        // The cells of an area collected once answer any box inside it, same result as a scan of that box.
        java.util.List<BuiltBlocks.Found> collected = BuiltBlocks.collect(new int[] {-5, 0, -5, 6, 10, 6}, reader);
        check(collected.size() == 2, "two built cells collected, got " + collected.size());
        BuiltBlocks.Found again = BuiltBlocks.firstIn(collected, box, 4);
        check(again != null && again.equals(low), "firstIn agrees with firstBuilt, got " + again);
        check(BuiltBlocks.firstIn(collected, new int[] {20, 0, 20, 21, 10, 21}, 4) == null, "a box away from the cells finds nothing");
        check(BuiltBlocks.overlaps(new int[] {0, 0, 0, 5, 5, 5}, new int[] {5, 5, 5, 8, 8, 8}), "boxes sharing a cell overlap");
        check(!BuiltBlocks.overlaps(new int[] {0, 0, 0, 5, 5, 5}, new int[] {6, 0, 0, 8, 5, 5}), "boxes one block apart do not overlap");
    }

    private static void genClearRemovable() {
        check(GenClear.removable("Wood_Ash_Trunk"), "trunk");
        check(GenClear.removable("*Wood_Oak_Trunk_Full"), "trunk with a state prefix");
        check(GenClear.removable("Wood_Birch_Branch_Corner"), "branch");
        check(GenClear.removable("Wood_Sticks"), "sticks");
        check(GenClear.removable("Plant_Leaves_Oak"), "foliage");
        check(GenClear.removable("Plant_Grass_Tall"), "plant");
        check(GenClear.removable("Rubble_Stone"), "pebbles");
        check(GenClear.removable("Deco_SpiderWeb_Corner"), "tree decoration");
        check(!GenClear.removable("Rock_Stone"), "rock stays");
        check(!GenClear.removable("Soil_Grass"), "soil stays");
        check(!GenClear.removable("Soil_Leaves"), "soil leaves stay");
        check(!GenClear.removable("Wood_Ash_Planks"), "planks stay");
        check(!GenClear.removable("Forgotten_Temple_Portal_Enter"), "portal stays");
        check(!GenClear.removable("Rock_Stone_Brick_Stairs"), "ruin bricks stay");
        check(!GenClear.removable(null), "null");
    }

    private static void planTaskOrder() {
        List<String> ran = new ArrayList<>();
        PriorityBlockingQueue<PlanTask> queue = new PriorityBlockingQueue<>();
        queue.add(new PlanTask(PlanTask.AHEAD, () -> ran.add("a")));
        queue.add(new PlanTask(PlanTask.CHUNK, () -> ran.add("b")));
        queue.add(new PlanTask(PlanTask.AHEAD, () -> ran.add("c")));
        queue.add(new PlanTask(PlanTask.CHUNK, () -> ran.add("d")));
        PlanTask task;
        while ((task = queue.poll()) != null) {
            task.run();
        }
        check(ran.equals(List.of("b", "d", "a", "c")), "chunk tasks first, arrival order kept: " + ran);
    }

    private static void footprintCoversDome() throws Exception {
        List<Vector3i> shuttle = TestSupport.prefabCells("Steamboat_Public.prefab.json");
        for (Rotation r : Rotation.values()) {
            for (int off : new int[] {0, 5, 11, 16, 22, 31}) {
                int ox = 1000 + off, oz = 2000 + off;
                Set<Long> chunks = new java.util.HashSet<>();
                for (long[] c : GenerationListener.footprintChunks(StationKinds.BOAT, ox, oz, r)) {
                    chunks.add(c[0] * 1_000_003L + c[1]);
                }
                for (Vector3i p : BoatSiteGround.clearCells(new Vector3i(ox, 115, oz), r, shuttle)) {
                    long key = (long) com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(p.x) * 1_000_003L
                            + com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(p.z);
                    check(chunks.contains(key), r + "+" + off + ": dome cell " + p + " inside the footprint chunks");
                }
            }
        }
    }

    private static void domeShape() {
        eq(24, BoatSiteGround.domeHeight(0));
        eq(-1, BoatSiteGround.domeHeight(10));
        eq(20, BoatSiteGround.domeHeight(5)); // floor(24 * sqrt(0.75)) = 20
        eq(0, BoatSiteGround.domeHeight(9.999));
        eq(23, BoatSiteGround.domeHeight(0.5)); // the centre lies between columns: the highest column is 23
    }

    private static void domeCellsRotated() throws Exception {
        List<Vector3i> shuttle = TestSupport.prefabCells("Steamboat_Public.prefab.json");
        Vector3i o = new Vector3i(100, 115, 200);
        for (Rotation r : Rotation.values()) {
            Set<Vector3i> cells = BoatSiteGround.clearCells(o, r, shuttle);
            Vector3i berth = r.rotateYaw(new Vector3i(kinds().boatBerthOrigin()[0], kinds().boatBerthOrigin()[1],
                    kinds().boatBerthOrigin()[2]), new Vector3i()).add(o);
            for (Vector3i c : shuttle) {
                Vector3i p = r.add(StationKinds.BOAT_BERTH_ROTATION).rotateYaw(c, new Vector3i()).add(berth);
                check(cells.contains(new Vector3i(p.x, 115 + 5, p.z)), r + ": shuttle column " + p + " cleared to the safety floor");
            }
            Vector3i centre = r.rotateYaw(new Vector3i(0, 0, 6), new Vector3i()).add(o);
            check(cells.contains(new Vector3i(centre.x, 115 + 23, centre.z)), r + ": centre column cleared to 23");
            check(!cells.contains(new Vector3i(centre.x, 115 + 24, centre.z)), r + ": nothing above the dome");
            check(cells.stream().noneMatch(p -> p.y < 115), r + ": nothing under the deck level");
        }
    }

    private static void fillPlanColumns() {
        Vector3i o = new Vector3i(0, 115, 0);
        // water down to 110, sand at 109
        Map<Vector3i, String> plan = BoatSiteGround.fillPlan(o, Rotation.None, (x, y, z) -> y >= 110 ? (y <= 114 ? 1 : 0) : 2,
                p -> "Soil_Sand_White");
        Vector3i master = new Vector3i(-4, 114, 5);
        eq("Soil_Sand_White", plan.get(master));
        eq("Soil_Sand_White", plan.get(new Vector3i(-4, 110, 5)));
        check(!plan.containsKey(new Vector3i(-4, 109, 5)), "the solid block stays");
        check(!plan.containsKey(new Vector3i(1, 114, 8)), "the pier on posts is never filled");
        // no solid within FILL_DEPTH anywhere: default block on FILL_DEPTH cells
        Map<Vector3i, String> deep = BoatSiteGround.fillPlan(o, Rotation.None, (x, y, z) -> 1, p -> null);
        eq(BoatSiteGround.FILL_DEFAULT, deep.get(new Vector3i(-4, 114 - BoatSiteGround.FILL_DEPTH + 1, 5)));
        check(!deep.containsKey(new Vector3i(-4, 114 - BoatSiteGround.FILL_DEPTH, 5)), "at most FILL_DEPTH cells");
        // 20 cells of water over the sea floor: filled down to the floor
        Map<Vector3i, String> sea = BoatSiteGround.fillPlan(o, Rotation.None, (x, y, z) -> y >= 95 ? 1 : 2, p -> "Soil_Sand");
        eq("Soil_Sand", sea.get(new Vector3i(-4, 95, 5)));
        eq("Soil_Sand", sea.get(new Vector3i(-4, 114, 5)));
        check(!sea.containsKey(new Vector3i(-4, 94, 5)), "the sea floor stays");
        // one deep column takes the most frequent block of the others
        Map<Vector3i, String> mixed = BoatSiteGround.fillPlan(o, Rotation.None,
                (x, y, z) -> x == -4 && z == 5 ? 1 : y < 114 ? 2 : 0, p -> "Rock_Stone");
        eq("Rock_Stone", mixed.get(new Vector3i(-4, 107, 5)));
    }

    /** Land at the given prefab cell columns: solid at y 114 only there, water elsewhere above 100, solid below. */
    private static BoatSiteGround.CellState shoreAt(Vector3i o, Rotation r, java.util.function.BiPredicate<Integer, Integer> landPrefab) {
        return (x, y, z) -> {
            if (y < 100) return 2;
            for (int px = -12; px <= 11; px++) {
                for (int pz = -6; pz <= 18; pz++) {
                    Vector3i w = r.rotateYaw(new Vector3i(px, 0, pz), new Vector3i()).add(o.x, 0, o.z);
                    if (w.x == x && w.z == z) {
                        return landPrefab.test(px, pz) && y <= 114 ? 2 : y <= 114 ? 1 : 0;
                    }
                }
            }
            return y <= 114 ? 1 : 0;
        };
    }

    private static void attachColumnsToShore() {
        Vector3i o = new Vector3i(0, 115, 0);
        // land from pz -2 backwards: the columns pz 0 and -1 behind the back edge are the gap
        var state = shoreAt(o, Rotation.None, (px, pz) -> pz <= -2);
        var cols = BoatSiteGround.attachColumns(o, Rotation.None, state);
        check(cols.contains(new Vector3i(-3, 114, 0)), "gap behind the back edge, pz 0");
        check(cols.contains(new Vector3i(-3, 114, -1)), "gap behind the back edge, pz -1");
        check(!cols.contains(new Vector3i(-3, 114, -2)), "the land column is not filled");
        eq(12 * 2, cols.size());
        Map<Vector3i, String> plan = BoatSiteGround.fillPlan(o, Rotation.None, state, p -> "Soil_Sand");
        eq("Soil_Sand", plan.get(new Vector3i(-3, 114, 0)));
        eq("Soil_Sand", plan.get(new Vector3i(-3, 101, -1)));
        // no land within ATTACH_REACH: nothing added
        var far = shoreAt(o, Rotation.None, (px, pz) -> pz <= -1 - BoatSiteGround.ATTACH_REACH);
        eq(0, BoatSiteGround.attachColumns(o, Rotation.None, far).size());
        // land right next to the edge: no gap
        eq(0, BoatSiteGround.attachColumns(o, Rotation.None, shoreAt(o, Rotation.None, (px, pz) -> pz <= 0)).size());
        // side edges: land at px <= -8 on the left, px >= 7 on the right
        var sides = BoatSiteGround.attachColumns(o, Rotation.None, shoreAt(o, Rotation.None, (px, pz) -> px <= -8 || px >= 7));
        check(sides.contains(new Vector3i(-7, 114, 3)), "left gap");
        check(sides.contains(new Vector3i(6, 114, 3)), "right gap");
        check(!sides.contains(new Vector3i(6, 114, 7)), "no right line beyond pz 5");
        // rotated: same shape in the prefab frame, world columns turned
        for (Rotation r : new Rotation[] {Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            var rc = BoatSiteGround.attachColumns(o, r, shoreAt(o, r, (px, pz) -> pz <= -2));
            eq(24, rc.size());
            Vector3i w = r.rotateYaw(new Vector3i(-3, 0, 0), new Vector3i()).add(o.x, 114, o.z);
            check(rc.contains(w), r + ": rotated gap column");
        }
    }

    private static void masterCheck() {
        Vector3i o = new Vector3i(0, 115, 0);
        check(BoatSiteGround.masterFault(o, Rotation.None, (x, y, z) -> y < 115 ? 2 : 0) == null, "solid ground and two free cells");
        eq(new Vector3i(-4, 114, 5), BoatSiteGround.masterFault(o, Rotation.None, (x, y, z) -> y < 115 ? 1 : 0));
        eq(new Vector3i(-4, 116, 5), BoatSiteGround.masterFault(o, Rotation.None, (x, y, z) -> y < 115 || y == 116 ? 2 : 0));
        BoatSiteGround.CellState sim = BoatSiteGround.simulated((x, y, z) -> 1, Set.of(), Set.of(new Vector3i(-4, 114, 5)));
        eq(new Vector3i(-4, 115, 5), BoatSiteGround.masterFault(o, Rotation.None, sim)); // water still in the master cell
    }

    /** Every chunk touched by the pier and by the moored steamboat shape is in the footprint, for the four rotations. */
    private static void boatFootprintCoversBerthedBoat() throws Exception {
        List<Vector3i> pier = TestSupport.prefabCells("Boat_Station.prefab.json");
        List<Vector3i> boat = TestSupport.prefabCells("Steamboat_Public.prefab.json");
        int[] b = kinds().boatBerthOrigin();
        for (Rotation rot : Rotation.values()) {
            for (int[] at : new int[][] {{1000, 500}, {-1003, 77}, {15, -1021}}) {
                Set<Long> chunks = new java.util.HashSet<>();
                for (long[] c : GenerationListener.footprintChunks(StationKinds.BOAT, at[0], at[1], rot)) {
                    chunks.add(c[0] * 1_000_000L + c[1]);
                }
                Vector3i origin = new Vector3i(at[0], 62, at[1]);
                Vector3i berth = rot.rotateYaw(new Vector3i(b[0], b[1], b[2]), new Vector3i()).add(origin);
                List<Vector3i> cells = new ArrayList<>();
                for (Vector3i c : pier) {
                    cells.add(rot.rotateYaw(c, new Vector3i()).add(origin));
                }
                for (Vector3i c : boat) {
                    cells.add(rot.add(StationKinds.BOAT_BERTH_ROTATION).rotateYaw(c, new Vector3i()).add(berth));
                }
                for (Vector3i p : cells) {
                    long key = (long) com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(p.x) * 1_000_000L
                            + com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(p.z);
                    check(chunks.contains(key), "chunk of " + p + " covered at " + at[0] + "," + at[1] + " rotation " + rot);
                }
            }
        }
    }

    private static void dredgeSelection() {
        Vector3i water = new Vector3i(0, 5, 0), rock = new Vector3i(1, 5, 0), air = new Vector3i(2, 5, 0), unloaded = new Vector3i(3, 5, 0);
        BoatSiteGround.CellState ground = (x, y, z) -> x == 0 ? 1 : x == 1 ? 2 : x == 2 ? 0 : 2;
        Set<Vector3i> dug = BoatSiteGround.dredgeCells(java.util.List.of(water, rock, air, unloaded), p -> p.x != 3, ground);
        eq(Set.of(rock, air), dug);
        eq(0, BoatSiteGround.dredgeCells(java.util.List.of(water), p -> true, ground).size());
        check(BoatSiteGround.dredgeConflicts(dug, Set.of(rock), Set.of()), "a filled cell conflicts");
        check(BoatSiteGround.dredgeConflicts(dug, Set.of(), Set.of(air)), "a prefab cell conflicts");
        check(!BoatSiteGround.dredgeConflicts(dug, Set.of(water), Set.of(unloaded)), "no shared cell, no conflict");
    }

    private static void crossMaxValue() {
        eq(3800, WorldStationPlanner.CROSS_MAX);
    }

    static void newChunksMarkAndQuery() {
        NewChunks n = new NewChunks();
        check(!n.dirty(), "empty map is clean");
        int[][] marked = {{0, 0}, {31, 31}, {32, 32}, {-1, -1}, {-32, -33}, {-1, 0}, {1000, -2000}};
        for (int[] c : marked) {
            check(!n.isNew(c[0], c[1]), "not new before mark " + c[0] + "," + c[1]);
        }
        for (int[] c : marked) {
            n.mark(c[0], c[1]);
        }
        check(n.dirty(), "dirty after mark");
        for (int[] c : marked) {
            check(n.isNew(c[0], c[1]), "new after mark " + c[0] + "," + c[1]);
        }
        // Neighbours across the region edges stay unmarked.
        check(!n.isNew(30, 31), "30,31");
        check(!n.isNew(31, 32), "31,32");
        check(!n.isNew(32, 31), "32,31");
        check(!n.isNew(0, -1), "0,-1");
        check(!n.isNew(-2, -1), "-2,-1");
        check(!n.isNew(1, 0), "1,0");
    }

    static void newChunksRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("new-chunks");
        Path f = dir.resolve("sub").resolve("generated-chunks-w.bin");
        NewChunks n = new NewChunks();
        int[][] marked = {{0, 0}, {31, 31}, {32, 32}, {-1, -1}, {-33, 64}, {5000, -5000}};
        for (int[] c : marked) {
            n.mark(c[0], c[1]);
        }
        n.write(f);
        check(!n.dirty(), "clean after write");
        check(Files.exists(f), "file written");
        check(!Files.exists(f.resolveSibling(f.getFileName() + ".tmp")), "no tmp left");
        NewChunks r = NewChunks.read(f);
        check(!r.dirty(), "read map is clean");
        for (int[] c : marked) {
            check(r.isNew(c[0], c[1]), "read new " + c[0] + "," + c[1]);
        }
        check(!r.isNew(1, 1), "1,1 not new");
        check(!r.isNew(-1, 0), "-1,0 not new");
        check(NewChunks.read(dir.resolve("absent.bin")).isNew(0, 0) == false, "absent file is empty");
    }

    static void newChunksBadFile() throws Exception {
        Path dir = Files.createTempDirectory("new-chunks-bad");
        Path f = dir.resolve("generated-chunks-w.bin");
        NewChunks n = new NewChunks();
        n.mark(3, 4);
        n.mark(100, 100);
        n.write(f);
        byte[] all = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(all, all.length - 10));
        NewChunks r = NewChunks.read(f);
        check(!r.isNew(3, 4) && !r.isNew(100, 100), "truncated file gives an empty map");
        check(!Files.exists(f), "bad file moved away");
        check(Files.exists(f.resolveSibling(f.getFileName() + ".bad")), "renamed to .bad");
        Files.write(f, new byte[] {1, 2, 3});
        check(!NewChunks.read(f).isNew(0, 0), "garbage gives an empty map");
    }

    static void newChunksWorldMismatch() throws Exception {
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        Path dir = Files.createTempDirectory("new-chunks-uuid");
        Path f = dir.resolve("generated-chunks-w.bin");
        NewChunks n = NewChunks.read(f, a);
        n.mark(7, 8);
        n.write(f);
        check(NewChunks.read(f, a).isNew(7, 8), "same identity keeps the map");
        check(NewChunks.read(f, null).isNew(7, 8), "unknown current identity keeps the map");
        NewChunks other = NewChunks.read(f, b);
        check(!other.isNew(7, 8), "other identity gives an empty map");
        check(other.dirty(), "empty map is written next time");
        check(!Files.exists(f) && Files.exists(f.resolveSibling(f.getFileName() + ".old")), "old file renamed .old");
        other.mark(1, 1);
        other.write(f);
        check(NewChunks.read(f, b).isNew(1, 1) && !NewChunks.read(f, b).isNew(7, 8), "new identity stored");
        // A file without identity (first format) is adopted.
        Path legacy = dir.resolve("legacy.bin");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(Files.newOutputStream(legacy))) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(0);
            out.writeInt(0);
            for (int i = 0; i < 16; i++) out.writeLong(i == 0 ? 1L : 0L);
        }
        NewChunks adopted = NewChunks.read(legacy, a);
        check(adopted.isNew(0, 0) && adopted.dirty(), "file without identity is adopted");
        adopted.write(legacy);
        check(!NewChunks.read(legacy, b).isNew(0, 0), "adopted identity is then enforced");
    }

    static void generatedWorldMismatch() throws Exception {
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        Path dir = Files.createTempDirectory("generated-uuid");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        check(!g.adoptWorld("w", a), "first use adopts");
        g.adoptWorld("other", a);
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 1, 2, Rotation.None, false));
        g.add("other", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 3, 4, Rotation.None, false));
        g.markPortal("w", 0, 0);
        g.markCell("w", 1, 1);
        g.markPortal("other", 0, 0);
        g.load(dir);
        check(!g.adoptWorld("w", a), "same identity after reload");
        eq(1, g.entries("w").size());
        check(g.adoptWorld("w", b), "other identity drops the world");
        eq(0, g.entries("w").size());
        check(g.markPortal("w", 0, 0) && g.markCell("w", 1, 1), "portals and cells dropped");
        eq(1, g.entries("other").size());
        check(!g.markPortal("other", 0, 0), "other world keeps its portal");
        g.load(dir);
        check(!g.adoptWorld("w", b), "new identity persisted");
        eq(1, g.entries("other").size());
    }

    static void generatedRegistryRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("generated-stations");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        GeneratedStations.Entry a = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 100, 200, Rotation.None, false));
        GeneratedStations.Entry b = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, -50, 70, Rotation.Ninety, true));
        check(g.markPortal("w", 10, 20), "portal marked");
        check(g.markCell("w", 3, -4), "cell marked");
        g.setState(a, GeneratedStations.State.BUILT);
        eq(2, g.pending("w").size() + 1);
        eq(b, g.pending("w").get(0));
        eq(a, g.at("w", 103, 198));
        check(g.at("w", 105, 200) == null, "at is limited to 4 blocks");
        g.load(dir);
        List<GeneratedStations.Entry> es = g.entries("w");
        eq(2, es.size());
        eq(StationKinds.AIR, es.get(0).kind);
        eq(100, es.get(0).x);
        eq(200, es.get(0).z);
        eq(Rotation.None, es.get(0).rotation);
        check(!es.get(0).partner, "first not partner");
        eq(GeneratedStations.State.BUILT, es.get(0).state);
        eq(StationKinds.BOAT, es.get(1).kind);
        eq(Rotation.Ninety, es.get(1).rotation);
        check(es.get(1).partner, "second partner");
        eq(GeneratedStations.State.PLANNED, es.get(1).state);
        check(!g.markPortal("w", 12, 22), "portal remembered");
        check(!g.markCell("w", 3, -4), "cell remembered");
        eq(1, g.positions("w", StationKinds.AIR).size());
        g.setState(es.get(0), GeneratedStations.State.REMOVED);
        eq(0, g.positions("w", StationKinds.AIR).size());
        eq(0, g.entries("other").size());
    }

    static void generatedRegistrySites() throws Exception {
        Path dir = Files.createTempDirectory("generated-sites");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        WorldStationPlanner.Site s1 = new WorldStationPlanner.Site(150, 210, Rotation.Ninety);
        WorldStationPlanner.Site s2 = new WorldStationPlanner.Site(-30, 400, Rotation.OneEighty);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 100, 200,
                Rotation.None, true, List.of(s1, s2)));
        check(e.thisSession, "entry added this session");
        eq(3, e.sites.size());
        eq(0, e.active);
        check(g.advance(e), "first advance");
        check(g.advance(e), "second advance");
        check(!g.advance(e), "list exhausted");
        eq(-30, e.x);
        eq(400, e.z);
        eq(Rotation.OneEighty, e.rotation);
        eq(2, e.active);
        g.load(dir);
        GeneratedStations.Entry r = g.entries("w").get(0);
        check(!r.thisSession, "entry read from disk");
        eq(2, r.active);
        eq(e.sites, r.sites);
        eq(-30, r.x);
        eq(400, r.z);
        eq(Rotation.OneEighty, r.rotation);
        check(r.partner, "partner kept");
        check(!g.advance(r), "still exhausted after reload");
    }

    static void nextSiteSkipsTooClose() throws Exception {
        Path dir = Files.createTempDirectory("generated-next-site");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        WorldStationPlanner.Site s1 = new WorldStationPlanner.Site(5000, 0, Rotation.None);
        WorldStationPlanner.Site s2 = new WorldStationPlanner.Site(-5000, 0, Rotation.Ninety);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 0, 0,
                Rotation.None, false, List.of(s1, s2)));
        check(GenerationListener.moveToNextSite(g, e, List.of(new int[] {5400, 0})), "site 2 is far enough");
        eq(2, e.active);
        eq(-5000, e.x);
        GeneratedStations.Entry f = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 0, 0,
                Rotation.None, false, List.of(s1, s2)));
        check(!GenerationListener.moveToNextSite(g, f, List.of(new int[] {5400, 0}, new int[] {-4600, 0})), "both fallbacks too close");
        GeneratedStations.Entry p = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 0, 0,
                Rotation.None, true, List.of(s1, s2)));
        check(GenerationListener.moveToNextSite(g, p, List.of(new int[] {5400, 0})), "a partner only keeps its reuse distance");
        eq(1, p.active);
    }

    static void generatedBuiltSite() throws Exception {
        Path dir = Files.createTempDirectory("generated-built-site");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        WorldStationPlanner.Site fallback = new WorldStationPlanner.Site(5000, 0, Rotation.None);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 0, 0,
                Rotation.None, false, List.of(fallback)));
        g.setBuiltSite(e, 40, 8, Rotation.Ninety);
        g.setState(e, GeneratedStations.State.BUILT);
        eq(e, g.at("w", 41, 7));
        check(g.at("w", 0, 0) == null, "planned site no longer matches");
        eq(1, g.positions("w", StationKinds.BOAT).size());
        eq(40, g.positions("w", StationKinds.BOAT).get(0)[0]);
        eq(8, g.positions("w", StationKinds.BOAT).get(0)[1]);
        eq(new WorldStationPlanner.Site(40, 8, Rotation.Ninety), g.activeSite(e));
        eq(chunkSet(GenerationListener.footprintChunks(StationKinds.BOAT, 40, 8, Rotation.Ninety)),
                chunkSet(GenerationListener.footprintChunks(e)));
        g.load(dir);
        GeneratedStations.Entry r = g.entries("w").get(0);
        eq(List.of(new WorldStationPlanner.Site(40, 8, Rotation.Ninety), fallback), r.sites);
        eq(0, r.active);
        eq(40, r.x);
        eq(8, r.z);
        eq(Rotation.Ninety, r.rotation);
        eq(r, g.at("w", 40, 8));
    }

    static void generatedRegistryActiveClamped() throws Exception {
        Path dir = Files.createTempDirectory("generated-active-clamped");
        String sites = "\"sites\": [{\"x\": 1, \"z\": 2, \"rotation\": \"None\"}, {\"x\": 300, \"z\": 400, \"rotation\": \"Ninety\"}]";
        String json = "{\"version\": 1, \"entries\": ["
                + "{\"world\": \"w\", \"kind\": \"" + StationKinds.BOAT + "\", \"x\": 1, \"z\": 2, \"rotation\": \"None\", "
                + sites + ", \"active\": 7, \"partner\": false, \"state\": \"PLANNED\"},"
                + "{\"world\": \"w\", \"kind\": \"" + StationKinds.BOAT + "\", \"x\": 1, \"z\": 2, \"rotation\": \"None\", "
                + sites + ", \"active\": -3, \"partner\": false, \"state\": \"PLANNED\"},"
                + "{\"world\": \"w\", \"kind\": \"" + StationKinds.BOAT + "\", \"x\": 1, \"z\": 2, \"rotation\": \"None\", "
                + sites + ", \"partner\": false, \"state\": \"PLANNED\"}],"
                + " \"portals\": [], \"cells\": [], \"worlds\": []}";
        Files.writeString(dir.resolve("generated-stations.json"), json);
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        check(!Files.exists(dir.resolve("generated-stations.json.bad")), "no .bad file");
        List<GeneratedStations.Entry> es = g.entries("w");
        eq(3, es.size());
        eq(1, es.get(0).active);
        eq(300, es.get(0).x);
        eq(Rotation.Ninety, es.get(0).rotation);
        eq(0, es.get(1).active);
        eq(1, es.get(1).x);
        eq(0, es.get(2).active);
        eq(2, es.get(2).z);
    }

    static void footprintFollowsAdvance() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("footprint-advance"));
        WorldStationPlanner.Site fallback = new WorldStationPlanner.Site(5000, -3000, Rotation.OneEighty);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 0, 0,
                Rotation.None, false, List.of(fallback)));
        eq(chunkSet(GenerationListener.footprintChunks(StationKinds.BOAT, 0, 0, Rotation.None)),
                chunkSet(GenerationListener.footprintChunks(e)));
        check(g.advance(e), "advanced");
        Set<String> moved = chunkSet(GenerationListener.footprintChunks(e));
        eq(chunkSet(GenerationListener.footprintChunks(StationKinds.BOAT, 5000, -3000, Rotation.OneEighty)), moved);
        check(!moved.contains("0,0"), "the old site is left");
        long[] first = GenerationListener.footprintChunks(e).get(0);
        eq(List.of(e), GenerationListener.touching(g.entries("w"), (int) first[0], (int) first[1]));
        eq(0, GenerationListener.touching(g.entries("w"), 0, 0).size());
    }

    static void replannedOncePerSession() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("replanned-once"));
        Set<String> replanned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        g.markCell("w", 1, 2);
        check(GenerationListener.unmarkOnce(replanned, g, "w", 1, 2), "first restoration un-marks");
        check(!g.cellMarked("w", 1, 2), "cell un-marked");
        g.markCell("w", 1, 2);
        check(!GenerationListener.unmarkOnce(replanned, g, "w", 1, 2), "second restoration keeps the cell");
        check(g.cellMarked("w", 1, 2), "cell still planned");
        g.markCell("other", 1, 2);
        check(GenerationListener.unmarkOnce(replanned, g, "other", 1, 2), "other world is its own cell");
        check(!GenerationListener.unmarkOnce(replanned, g, "w", 3, 3), "unknown cell is not un-marked");
    }

    static void oldTerrainMovesBoat() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("old-terrain-moves"));
        WorldStationPlanner.Site s1 = new WorldStationPlanner.Site(5000, 0, Rotation.None);
        WorldStationPlanner.Site s2 = new WorldStationPlanner.Site(-5000, 0, Rotation.Ninety);
        GeneratedStations.Entry boat = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 0, 0,
                Rotation.None, false, List.of(s1, s2)));
        // another main pier near the first fallback
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 5500, 0, Rotation.None, false));
        check(GenerationListener.moveOffOldTerrain(g, boat, "w"), "boat moves to a site far from the other pier");
        eq(2, boat.active);
        eq(-5000, boat.x);
        eq(GeneratedStations.State.PLANNED, boat.state);
        check(!GenerationListener.moveOffOldTerrain(g, boat, "w"), "sites used up");
        eq(2, boat.active);
        GeneratedStations.Entry air = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false,
                List.of(s1)));
        check(GenerationListener.moveOffOldTerrain(g, air, "w"), "a balloon station moves to its next site");
        eq(1, air.active);
        check(!GenerationListener.moveOffOldTerrain(g, air, "w"), "balloon sites used up");
        eq(1, air.active);
    }

    static void generatedRegistryOldFormat() throws Exception {
        Path dir = Files.createTempDirectory("generated-old-format");
        String json = "{\"version\": 1, \"entries\": [{\"world\": \"w\", \"kind\": \"" + StationKinds.AIR
                + "\", \"x\": 12, \"z\": -34, \"rotation\": \"Ninety\", \"partner\": false, \"state\": \"BUILT\"}],"
                + " \"portals\": [], \"cells\": [], \"worlds\": []}";
        Files.writeString(dir.resolve("generated-stations.json"), json);
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        check(!Files.exists(dir.resolve("generated-stations.json.bad")), "no .bad file");
        eq(1, g.entries("w").size());
        GeneratedStations.Entry e = g.entries("w").get(0);
        eq(List.of(new WorldStationPlanner.Site(12, -34, Rotation.Ninety)), e.sites);
        eq(0, e.active);
        eq(12, e.x);
        eq(-34, e.z);
        eq(Rotation.Ninety, e.rotation);
        eq(GeneratedStations.State.BUILT, e.state);
        check(!g.advance(e), "single site cannot advance");
    }

    static void generatedForgetAndUnmark() throws Exception {
        Path dir = Files.createTempDirectory("generated-forget");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        GeneratedStations.Entry a = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false));
        GeneratedStations.Entry far = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 5000, 5000, Rotation.None, false));
        g.setState(a, GeneratedStations.State.BUILT);
        // the entries a regenerated chunk forgets are those whose footprint contains it, whatever their state
        eq(List.of(a), GenerationListener.touching(g.entries("w"), 0, 0));
        eq(0, GenerationListener.touching(g.entries("w"), 40, 40).size());
        g.forget(a);
        eq(List.of(far), g.entries("w"));
        g.load(dir);
        eq(1, g.entries("w").size());
        check(g.at("w", 0, 0) == null, "forgotten entry stays forgotten after reload");
        check(g.markCell("w", 1, 2), "cell marked");
        check(!g.unmarkCell("w", 2, 1), "unknown cell");
        check(g.unmarkCell("w", 1, 2), "cell unmarked");
        check(g.markCell("w", 1, 2), "cell planned again");
        g.markPortal("w", 10, 10);
        g.markPortal("w", 500, 500);
        eq(1, g.forgetPortals("w", 0, 0, 31, 31));
        check(g.markPortal("w", 10, 10), "portal inside the box forgotten");
        check(!g.markPortal("w", 500, 500), "portal outside the box kept");
        eq(0, g.forgetPortals("other", 0, 0, 31, 31));
    }

    static void regeneratedKeepsSessionEntries() throws Exception {
        Path dir = Files.createTempDirectory("generated-regenerated");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false));
        // reloaded from disk: no longer an entry of this session
        g.load(dir);
        GeneratedStations.Entry old = g.at("w", 0, 0);
        check(old != null && !old.thisSession, "entry loaded from disk");
        GeneratedStations.Entry fresh = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 20, 20, Rotation.None, false));
        check(fresh.thisSession, "entry added after the load");
        eq(List.of(old), GenerationListener.forgettable(List.of(old, fresh)));
        g.setState(fresh, GeneratedStations.State.BUILT);
        eq(List.of(old, fresh), GenerationListener.forgettable(List.of(old, fresh)));
    }

    static void generatedPortalOnce() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("generated-portal"));
        check(g.markPortal("w", 0, 0), "first portal");
        check(!g.markPortal("w", 40, 40), "within 64 blocks");
        check(g.markPortal("w", 64, 0), "64 blocks away is new");
        check(g.markPortal("other", 0, 0), "other world is new");
    }

    private static void writeCellsFile(Path dir, String head, int x, int z) throws Exception {
        Files.writeString(dir.resolve("generated-stations.json"), "{" + head + "\"entries\": [], \"portals\": [], \"cells\": "
                + "[{\"world\": \"w\", \"x\": " + x + ", \"z\": " + z + "}]}");
    }

    static void generatedCellSizeConversion() throws Exception {
        eq(1024, WorldStationPlanner.CELL);
        eq(4, GeneratedStations.convertCell(3, -4, 2048, 1024).size());
        List<String> got = new ArrayList<>();
        for (int[] c : GeneratedStations.convertCell(3, -4, 2048, 1024)) got.add(c[0] + ":" + c[1]);
        eq(List.of("6:-8", "7:-8", "6:-7", "7:-7"), got);
        eq(16, GeneratedStations.convertCell(0, 0, 4096, 1024).size());
        eq(1, GeneratedStations.convertCell(5, 6, 1024, 1024).size());
        eq(1, GeneratedStations.convertCell(5, 6, 1500, 1024).size());
        eq(5, GeneratedStations.convertCell(5, 6, 1500, 1024).get(0)[0]);
        GeneratedStations g = GeneratedStations.get();
        // version 1, no cellSize: 2048 block cells
        Path dir = Files.createTempDirectory("generated-cells-v1");
        writeCellsFile(dir, "\"version\": 1, ", 3, -4);
        g.load(dir);
        for (int[] c : new int[][] {{6, -8}, {7, -8}, {6, -7}, {7, -7}}) check(!g.markCell("w", c[0], c[1]), "converted cell " + c[0] + ":" + c[1]);
        check(g.markCell("w", 3, -4), "old index is another area now");
        check(g.markCell("w", 8, -8), "neighbouring area is free");
        // the conversion is written back as version 2 with the new size, and is not applied twice
        g.load(dir);
        String json = Files.readString(dir.resolve("generated-stations.json"));
        check(json.contains("\"cellSize\": 1024") && json.contains("\"version\": 2"), "rewritten as version 2");
        check(!g.markCell("w", 7, -7) && !g.markCell("w", 3, -4) && !g.markCell("w", 8, -8), "second load keeps the cells");
        check(g.markCell("w", 14, -14), "no second conversion");
        // version 2 with the current size: cells kept as they are
        Path dir2 = Files.createTempDirectory("generated-cells-v2");
        writeCellsFile(dir2, "\"version\": 2, \"cellSize\": 1024, ", 3, -4);
        g.load(dir2);
        check(!g.markCell("w", 3, -4), "current size kept");
        check(g.markCell("w", 6, -8), "no conversion");
        // another divisible size
        Path dir3 = Files.createTempDirectory("generated-cells-4096");
        writeCellsFile(dir3, "\"version\": 2, \"cellSize\": 4096, ", 1, 0);
        g.load(dir3);
        check(!g.markCell("w", 7, 3), "4096 cell covers 4 by 4 cells");
        check(g.markCell("w", 8, 0), "outside the 4096 cell");
        // size that does not divide: cells kept
        Path dir4 = Files.createTempDirectory("generated-cells-odd");
        writeCellsFile(dir4, "\"version\": 2, \"cellSize\": 1500, ", 2, 2);
        g.load(dir4);
        check(!g.markCell("w", 2, 2), "unknown size kept as is");
        check(g.markCell("w", 4, 4), "not converted");
        // a huge multiple: cells kept, nothing allocated
        eq(1, GeneratedStations.convertCell(1, 1, 65 * 1024, 1024).size());
        eq(1, GeneratedStations.convertCell(1, 1, 1 << 30, 1024).size());
        eq(4096, GeneratedStations.convertCell(1, 1, 64 * 1024, 1024).size());
        Path dir5 = Files.createTempDirectory("generated-cells-huge");
        writeCellsFile(dir5, "\"version\": 2, \"cellSize\": 2000000000, ", 1, 1);
        g.load(dir5);
        check(!g.markCell("w", 1, 1), "huge size kept as is");
        check(g.markCell("w", 2, 2), "huge size not converted");
    }

    static void generatedOrigin() throws Exception {
        Path dir = Files.createTempDirectory("generated-origin");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false), GeneratedStations.Origin.LAND);
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 2000, 0, Rotation.None, false), GeneratedStations.Origin.PORTAL);
        GeneratedStations.Entry builtLand = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 4000, 0, Rotation.None,
                false), GeneratedStations.Origin.LAND);
        g.setState(builtLand, GeneratedStations.State.BUILT);
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 6000, 0, Rotation.None, false), GeneratedStations.Origin.LAND);
        g.load(dir);
        List<GeneratedStations.Entry> es = g.entries("w");
        eq(GeneratedStations.Origin.LAND, es.get(0).origin);
        eq(GeneratedStations.Origin.PORTAL, es.get(1).origin);
        eq(GeneratedStations.Origin.LAND, es.get(2).origin);
        // portal selection: PLANNED land entries left out, the others kept
        eq(3, g.positions("w", StationKinds.AIR, false).size());
        List<int[]> forPortal = g.positions("w", StationKinds.AIR, true);
        eq(2, forPortal.size());
        eq(2000, forPortal.get(0)[0]);
        eq(4000, forPortal.get(1)[0]);
        // an absent origin reads as portal
        Path dir2 = Files.createTempDirectory("generated-origin-absent");
        Files.writeString(dir2.resolve("generated-stations.json"), "{\"version\": 2, \"cellSize\": 1024, \"entries\": [{\"world\": \"w\", "
                + "\"kind\": \"" + StationKinds.AIR + "\", \"x\": 5, \"z\": 6, \"rotation\": \"None\", \"partner\": false, "
                + "\"state\": \"PLANNED\"}], \"portals\": [], \"cells\": []}");
        g.load(dir2);
        eq(GeneratedStations.Origin.PORTAL, g.entries("w").get(0).origin);
        eq(1, g.positions("w", StationKinds.AIR, true).size());
        // land entries to displace for a portal at (500, 0)
        g.load(dir);
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 700, 0, Rotation.None, false), GeneratedStations.Origin.LAND);
        GeneratedStations.Entry requested = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 100, Rotation.None,
                false), GeneratedStations.Origin.LAND);
        List<GeneratedStations.Entry> near = GenerationListener.landEntriesNear(g.entries("w"), Set.of(requested), 500, 0);
        eq(2, near.size());
        eq(0, near.get(0).x);
        eq(700, near.get(1).x);
        check(near.get(0) != requested, "requested entry is left alone");
    }

    static void generatedCellOnce() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("generated-cell"));
        check(g.markCell("w", 1, 2), "first cell");
        check(!g.markCell("w", 1, 2), "same cell");
        check(g.markCell("w", 2, 1), "other cell");
        check(g.markCell("other", 1, 2), "other world");
    }

    static void landCellPlansMainAndFallbacks() {
        TerrainProbe t = heights((x, z) -> 120);
        WorldStationPlanner.PlannedSite s = WorldStationPlanner.planLandCell(42L, 2, -1, t, List.of());
        check(s != null, "a land station is planned");
        eq(StationKinds.AIR, s.kind());
        eq(WorldStationPlanner.MAX_SITES - 1, s.fallbacks().size());
        int c = WorldStationPlanner.LAND_CELL;
        check(s.x() >= 2 * c && s.x() < 3 * c && s.z() >= -c && s.z() < 0, "main site inside the cell");
        List<WorldStationPlanner.Site> all = new ArrayList<>();
        all.add(new WorldStationPlanner.Site(s.x(), s.z(), s.rotation()));
        all.addAll(s.fallbacks());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                check(Math.max(Math.abs(all.get(i).x() - all.get(j).x()), Math.abs(all.get(i).z() - all.get(j).z()))
                        >= WorldStationPlanner.FALLBACK_GAP, "sites " + i + " and " + j + " spaced by FALLBACK_GAP");
            }
        }
    }

    static void landCellAllWater() {
        check(WorldStationPlanner.planLandCell(42L, 0, 0, new GridProbe("~"), List.of()) == null, "all water gives no site");
    }

    static void landCellRespectsExistingAir() {
        TerrainProbe t = heights((x, z) -> 120);
        int c = WorldStationPlanner.LAND_CELL;
        // stations at the four quarter points leave no column of the cell at BALLOON_SPACING from all of them
        List<int[]> around = List.of(new int[] {c / 4, c / 4}, new int[] {3 * c / 4, c / 4}, new int[] {c / 4, 3 * c / 4},
                new int[] {3 * c / 4, 3 * c / 4});
        check(WorldStationPlanner.planLandCell(42L, 0, 0, t, around) == null, "stations near the whole cell give no site");
        long min2 = (long) WorldStationPlanner.BALLOON_SPACING * WorldStationPlanner.BALLOON_SPACING;
        int planned = 0;
        for (long seed = 1; seed <= 10; seed++) {
            WorldStationPlanner.PlannedSite s = WorldStationPlanner.planLandCell(seed, 0, 0, t, List.of(new int[] {c / 2, c / 2}));
            if (s == null) {
                continue;
            }
            planned++;
            List<WorldStationPlanner.Site> all = new ArrayList<>(s.fallbacks());
            all.add(new WorldStationPlanner.Site(s.x(), s.z(), s.rotation()));
            for (WorldStationPlanner.Site f : all) {
                long fx = f.x() - c / 2, fz = f.z() - c / 2;
                check(fx * fx + fz * fz >= min2, "every site keeps its spacing from the existing station");
            }
        }
        check(planned > 0, "a site is found when the draw is away from the existing station");
    }

    static void landCellSkipsExcludedWithoutTries() {
        TerrainProbe t = heights((x, z) -> 120);
        int c = WorldStationPlanner.LAND_CELL;
        // the station sits on the drawn point of every seed tried: the columns around it are skipped without using the tries
        for (long seed = 1; seed <= 10; seed++) {
            java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed ^ (0 * 0x9E3779B97F4A7C15L) ^ (0 * 0xC2B2AE3D27D4EB4FL) ^ 0x1A4D);
            int drawX = rnd.nextInt(c), drawZ = rnd.nextInt(c);
            WorldStationPlanner.PlannedSite s = WorldStationPlanner.planLandCell(seed, 0, 0, t, List.of(new int[] {drawX, drawZ}));
            long dx = drawX - (s == null ? 0 : s.x()), dz = drawZ - (s == null ? 0 : s.z());
            if (s != null) {
                check(dx * dx + dz * dz >= (long) WorldStationPlanner.BALLOON_SPACING * WorldStationPlanner.BALLOON_SPACING, "main site keeps its spacing");
            }
            // a draw in the middle leaves clear land (corners are at 1086 from the centre): a site must be found when the corner is clear
            if (Math.abs(drawX - c / 2) < 100 && Math.abs(drawZ - c / 2) < 100) {
                check(s != null, "far side of the cell is used");
            }
        }
        // a station near the centre: the clear corners hold land, so a site is found whatever the draw
        for (long seed = 1; seed <= 10; seed++) {
            check(WorldStationPlanner.planLandCell(seed, 0, 0, t, List.of(new int[] {c / 2, c / 2})) != null, "site found for seed " + seed);
        }
    }

    static void landCellDeterministic() {
        TerrainProbe t = heights((x, z) -> 120 + (Math.floorDiv(x, 64) + Math.floorDiv(z, 64)) % 3);
        WorldStationPlanner.PlannedSite a = WorldStationPlanner.planLandCell(7L, -3, 4, t, List.of());
        WorldStationPlanner.PlannedSite b = WorldStationPlanner.planLandCell(7L, -3, 4, t, List.of());
        check(a != null, "planned");
        eq(a, b);
    }

    static void portalFallbacksSpaced() {
        TerrainProbe t = heights((x, z) -> 120);
        WorldStationPlanner.PlannedSite s = WorldStationPlanner.planPortal(0, 0, t, List.of());
        check(s != null, "a station is planned");
        eq(WorldStationPlanner.MAX_SITES - 1, s.fallbacks().size());
        List<WorldStationPlanner.Site> all = new ArrayList<>();
        all.add(new WorldStationPlanner.Site(s.x(), s.z(), s.rotation()));
        all.addAll(s.fallbacks());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                check(Math.max(Math.abs(all.get(i).x() - all.get(j).x()), Math.abs(all.get(i).z() - all.get(j).z()))
                        >= WorldStationPlanner.PORTAL_FALLBACK_GAP, "portal sites " + i + " and " + j + " spaced");
            }
        }
    }

    static void balloonNextSiteSpacing() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("balloon-next-site"));
        WorldStationPlanner.Site s1 = new WorldStationPlanner.Site(5000, 0, Rotation.None);
        WorldStationPlanner.Site s2 = new WorldStationPlanner.Site(-5000, 0, Rotation.Ninety);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false,
                List.of(s1, s2)));
        // 5800 is 800 from s1, closer than BALLOON_SPACING, while 5000 + 1000 is exactly clear
        check(GenerationListener.moveToNextSite(g, e, List.of(new int[] {5800, 0})) && e.active == 2, "site 1 is within the balloon spacing");
        GeneratedStations.Entry f = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false,
                List.of(s1, s2)));
        check(GenerationListener.moveToNextSite(g, f, List.of(new int[] {5900, 0})) && f.active == 1, "900 away is clear");
        GeneratedStations.Entry h = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 0, 0, Rotation.None, false,
                List.of(s1, s2)));
        check(!GenerationListener.moveToNextSite(g, h, List.of(new int[] {5800, 0}, new int[] {-5800, 0})), "both within the spacing");
        // otherSites: other balloon entries only, not boats
        GeneratedStations.Entry other = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 77, 88, Rotation.None, false));
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 99, 99, Rotation.None, false));
        List<int[]> sites = GenerationListener.otherSites("w", other);
        check(sites.stream().noneMatch(p -> p[0] == 99), "boat entries are not balloon neighbours");
        check(sites.stream().noneMatch(p -> p[0] == 77), "the entry itself is excluded");
        check(sites.size() >= 3, "other balloon entries are listed");
    }

    static void generatedLandCells() throws Exception {
        Path dir = Files.createTempDirectory("generated-land-cells");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        check(g.markLandCell("w", 1, 2), "first land cell");
        check(!g.markLandCell("w", 1, 2), "same land cell");
        check(g.markCell("w", 1, 2), "sea cells are separate");
        check(g.landCellMarked("w", 1, 2) && !g.landCellMarked("w", 2, 1), "marked lookup");
        g.load(dir);
        check(g.landCellMarked("w", 1, 2), "land cell survives a reload");
        check(!g.landCellMarked("other", 1, 2), "other world");
        check(g.unmarkLandCell("w", 1, 2) && !g.landCellMarked("w", 1, 2), "unmarked");
        check(!g.unmarkLandCell("w", 1, 2), "nothing to unmark");
        // a file without landCells reads as empty
        Path old = Files.createTempDirectory("generated-no-land");
        writeCellsFile(old, "\"version\": 2, \"cellSize\": 1024, ", 3, 3);
        g.load(old);
        check(!g.landCellMarked("w", 3, 3), "no land cells in an older file");
        check(g.markLandCell("w", 3, 3), "land cell free");
        // the world identity change drops them
        g.adoptWorld("w", java.util.UUID.randomUUID());
        g.adoptWorld("w", java.util.UUID.randomUUID());
        check(!g.landCellMarked("w", 3, 3), "dropped with the world");
        Set<String> queued = new java.util.HashSet<>();
        check(GenerationListener.claimAheadLand(queued, g, "w", 5, 5), "first land claim");
        check(!GenerationListener.claimAheadLand(queued, g, "w", 5, 5), "already queued");
        check(GenerationListener.claimAhead(queued, g, "w", 5, 5), "sea key is separate");
        Set<String> replanned = new java.util.HashSet<>();
        g.markLandCell("w", 6, 6);
        check(GenerationListener.unmarkLandOnce(replanned, g, "w", 6, 6), "land un-marked once");
        check(!GenerationListener.unmarkLandOnce(replanned, g, "w", 6, 6), "not twice");
    }

    static void cellsAroundPlayer() {
        List<int[]> cells = GenerationListener.cellsAround(-1, 2047);
        eq(9, cells.size());
        eq(-1, cells.get(0)[0]);
        eq(1, cells.get(0)[1]);
        Set<String> seen = new java.util.HashSet<>();
        for (int[] c : cells) {
            seen.add(c[0] + ":" + c[1]);
            check(Math.abs(c[0] + 1) <= 1 && Math.abs(c[1] - 1) <= 1, "within one cell");
        }
        eq(9, seen.size());
    }

    static void planAheadOffWhenDisabled() throws Exception {
        Path dir = Files.createTempDirectory("plan-ahead-off");
        try {
            WorldgenSettings.load(dir, dir);
            WorldgenSettings.setEnabled(false);
            GenerationListener.planAhead();
        } finally {
            WorldgenSettings.setEnabled(true);
            deleteAll(dir);
        }
    }

    static void planAheadDedupe() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("ahead-dedupe"));
        Set<String> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();
        check(GenerationListener.claimAhead(queued, g, "w", 1, 2), "first claim");
        check(!GenerationListener.claimAhead(queued, g, "w", 1, 2), "already queued");
        check(GenerationListener.claimAhead(queued, g, "other", 1, 2), "other world");
        queued.remove(GenerationListener.aheadKey("w", 1, 2));
        check(GenerationListener.claimAhead(queued, g, "w", 1, 2), "claimed again after release");
        queued.clear();
        g.markCell("w", 1, 2);
        check(!GenerationListener.claimAhead(queued, g, "w", 1, 2), "already planned");
        eq(0, queued.size());
    }

    static void generatedBadFile() throws Exception {
        Path dir = Files.createTempDirectory("generated-bad");
        Files.writeString(dir.resolve("generated-stations.json"), "{ not json");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        check(Files.exists(dir.resolve("generated-stations.json.bad")), "corrupt file renamed .bad");
        check(!Files.exists(dir.resolve("generated-stations.json")), "corrupt file moved away");
        eq(0, g.entries("w").size());
        g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 1, 2, Rotation.None, false));
        check(Files.exists(dir.resolve("generated-stations.json")), "new file written");
        eq("{ not json", Files.readString(dir.resolve("generated-stations.json.bad")));
    }
    private static java.util.Set<String> chunkSet(List<long[]> chunks) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (long[] c : chunks) out.add(c[0] + "," + c[1]);
        return out;
    }

    private static void nearestUnbuiltEntry() throws Exception {
        Path dir = Files.createTempDirectory("nearest-unbuilt");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        GeneratedStations.Entry built = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 10, 0, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, false));
        GeneratedStations.Entry far = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.BOAT, 500, 0, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, false));
        GeneratedStations.Entry abandoned = g.add("w", new WorldStationPlanner.PlannedSite(StationKinds.AIR, 100, 0, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, false));
        g.setState(built, GeneratedStations.State.BUILT);
        g.setState(abandoned, GeneratedStations.State.ABANDONED);
        eq(abandoned, WorldgenCommands.nearestUnbuilt(g.entries("w"), 0, 0));
        eq(far, WorldgenCommands.nearestUnbuilt(g.entries("w"), 450, 0));
        eq(null, WorldgenCommands.nearestUnbuilt(g.entries("other"), 0, 0));
    }

    static void footprintChunksCover() {
        GeneratedStations.Entry rotated = new GeneratedStations.Entry("w", StationKinds.AIR, 0, 0, Rotation.Ninety, false,
                GeneratedStations.State.PLANNED);
        eq(java.util.Set.of("-1,-1", "0,-1"), chunkSet(GenerationListener.footprintChunks(rotated)));
        GeneratedStations.Entry plain = new GeneratedStations.Entry("w", StationKinds.AIR, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        eq(java.util.Set.of("0,-1", "0,0"), chunkSet(GenerationListener.footprintChunks(plain)));
        GeneratedStations.Entry boat = new GeneratedStations.Entry("w", StationKinds.BOAT, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        eq(java.util.Set.of("-1,-1", "-1,0", "0,-1", "0,0"), chunkSet(GenerationListener.footprintChunks(boat)));
        GeneratedStations.Entry far = new GeneratedStations.Entry("w", StationKinds.AIR, -4000, 3000, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        check(chunkSet(GenerationListener.footprintChunks(far)).contains("-125,93"), "negative x chunk of the origin");
    }

    static void decideRules() {
        List<long[]> chunks = List.of(new long[]{0, 0}, new long[]{1, 0});
        java.util.function.Predicate<long[]> all = c -> true;
        java.util.function.Predicate<long[]> none = c -> false;
        java.util.function.Predicate<long[]> firstOnly = c -> c[0] == 0;
        eq(GenerationListener.Decision.BUILD, GenerationListener.decide(chunks, all, none, false));
        eq(GenerationListener.Decision.ABANDON, GenerationListener.decide(chunks, firstOnly, c -> c[0] == 1, false));
        eq(GenerationListener.Decision.WAIT, GenerationListener.decide(chunks, firstOnly, none, false));
        eq(GenerationListener.Decision.ABANDON, GenerationListener.decide(chunks, all, c -> c[0] == 0, false));
    }

    static void decideOldTerrain() {
        List<long[]> chunks = List.of(new long[]{0, 0}, new long[]{1, 0});
        java.util.function.Predicate<long[]> all = c -> true;
        java.util.function.Predicate<long[]> none = c -> false;
        java.util.function.Predicate<long[]> firstOnly = c -> c[0] == 0;
        // setting on: a loaded old chunk never abandons
        eq(GenerationListener.Decision.BUILD, GenerationListener.decide(chunks, firstOnly, c -> c[0] == 1, true));
        eq(GenerationListener.Decision.BUILD, GenerationListener.decide(chunks, none, all, true));
        eq(GenerationListener.Decision.BUILD, GenerationListener.decide(chunks, all, none, true));
        // an old chunk that is not loaded waits
        eq(GenerationListener.Decision.WAIT, GenerationListener.decide(chunks, firstOnly, none, true));
        eq(GenerationListener.Decision.WAIT, GenerationListener.decide(chunks, none, c -> c[0] == 0, true));
        // the same chunks with the setting off
        eq(GenerationListener.Decision.ABANDON, GenerationListener.decide(chunks, firstOnly, c -> c[0] == 1, false));
    }

    private static GeneratedStations.Entry abandonedEntry(GeneratedStations g, String kind, boolean partner, GeneratedStations.Reason reason,
                                                          WorldStationPlanner.Site... sites) {
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(kind, sites[0].x(), sites[0].z(), sites[0].rotation(), partner,
                List.of(sites).subList(1, sites.length)));
        g.abandon(e, reason);
        return e;
    }

    static void reviveSelection() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("revive-selection"));
        String air = StationKinds.AIR;
        WorldStationPlanner.Site near = new WorldStationPlanner.Site(100, 0, Rotation.None);
        WorldStationPlanner.Site far = new WorldStationPlanner.Site(6000, 0, Rotation.Ninety);
        WorldStationPlanner.Site far2 = new WorldStationPlanner.Site(9000, 0, Rotation.None);
        GeneratedStations.Entry built = g.add("w", new WorldStationPlanner.PlannedSite(air, 0, 0, Rotation.None, false));
        g.setState(built, GeneratedStations.State.BUILT);
        GeneratedStations.Entry old = abandonedEntry(g, air, false, GeneratedStations.Reason.OLD_TERRAIN, near, far, far2);
        GeneratedStations.Entry unknown = abandonedEntry(g, air, false, GeneratedStations.Reason.UNKNOWN, far2);
        GeneratedStations.Entry other = abandonedEntry(g, air, false, GeneratedStations.Reason.OTHER, far);
        GeneratedStations.Entry removed = g.add("w", new WorldStationPlanner.PlannedSite(air, 20000, 0, Rotation.None, false));
        g.setState(removed, GeneratedStations.State.REMOVED);
        GeneratedStations.Entry stuck = abandonedEntry(g, air, false, GeneratedStations.Reason.OLD_TERRAIN, near);
        // a network station sits next to the third site
        List<int[]> network = List.of(new int[]{9000, 100});
        List<GeneratedStations.Entry> revived = GenerationListener.reviveEntries(g, g.entries("w"), kind -> kind.equals(air) ? network : List.of());
        // the first site is 100 blocks from the built station, the second is the first that fits
        eq(List.of(old), revived);
        eq(GeneratedStations.State.PLANNED, old.state);
        eq(1, old.active);
        eq(6000, old.x);
        eq(Rotation.Ninety, old.rotation);
        eq(GeneratedStations.Reason.UNKNOWN, old.reason);
        // unknown: its only site is next to the network station
        eq(GeneratedStations.State.ABANDONED, unknown.state);
        eq(GeneratedStations.State.ABANDONED, other.state);
        eq(GeneratedStations.State.REMOVED, removed.state);
        eq(GeneratedStations.State.ABANDONED, stuck.state);
        // without the network station the unknown entry is revived, and spacing counts the entry revived before it
        List<GeneratedStations.Entry> second = GenerationListener.reviveEntries(g, g.entries("w"), kind -> List.of());
        eq(List.of(unknown), second);
        eq(GeneratedStations.State.PLANNED, unknown.state);
        eq(9000, unknown.x);
        // a PLANNED entry close to a site keeps a later entry off it
        GeneratedStations.Entry late = abandonedEntry(g, air, false, GeneratedStations.Reason.OLD_TERRAIN,
                new WorldStationPlanner.Site(6100, 0, Rotation.None), new WorldStationPlanner.Site(-9000, 0, Rotation.None));
        eq(List.of(late), GenerationListener.reviveEntries(g, g.entries("w"), kind -> List.of()));
        eq(1, late.active);
        eq(-9000, late.x);
    }

    static void exhaustedReasonChoice() {
        eq(GeneratedStations.Reason.OLD_TERRAIN, GeneratedStations.exhaustedReason(true));
        eq(GeneratedStations.Reason.OTHER, GeneratedStations.exhaustedReason(false));
    }

    static void generatedRegistryReason() throws Exception {
        Path dir = Files.createTempDirectory("generated-reason");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        String air = StationKinds.AIR;
        GeneratedStations.Entry a = abandonedEntry(g, air, false, GeneratedStations.Reason.OLD_TERRAIN, new WorldStationPlanner.Site(0, 0, Rotation.None));
        GeneratedStations.Entry b = abandonedEntry(g, air, false, GeneratedStations.Reason.OTHER, new WorldStationPlanner.Site(5000, 0, Rotation.None));
        GeneratedStations.Entry c = g.add("w", new WorldStationPlanner.PlannedSite(air, 10000, 0, Rotation.None, false));
        g.load(dir);
        List<GeneratedStations.Entry> es = g.entries("w");
        eq(GeneratedStations.Reason.OLD_TERRAIN, es.get(0).reason);
        eq(GeneratedStations.Reason.OTHER, es.get(1).reason);
        eq(GeneratedStations.Reason.UNKNOWN, es.get(2).reason);
        eq(GeneratedStations.State.ABANDONED, es.get(0).state);
        // a file without the field, or with an unknown value, reads as unknown
        String json = Files.readString(dir.resolve("generated-stations.json"));
        check(json.contains("\"reason\""), "reason written");
        Files.writeString(dir.resolve("generated-stations.json"), json.replace("OLD_TERRAIN", "SOMETHING"));
        g.load(dir);
        eq(GeneratedStations.Reason.UNKNOWN, g.entries("w").get(0).reason);
        g.revive(g.entries("w").get(0), 0);
        eq(GeneratedStations.State.PLANNED, g.entries("w").get(0).state);
        g.load(dir);
        eq(GeneratedStations.State.PLANNED, g.entries("w").get(0).state);
        eq(GeneratedStations.Reason.OTHER, g.entries("w").get(1).reason);
    }

    static void settingsOldTerrain() throws Exception {
        Path dir = Files.createTempDirectory("worldgen-settings-old-terrain");
        try {
            WorldgenSettings.load(dir, dir);
            eq(false, WorldgenSettings.oldTerrain());
            WorldgenSettings.setOldTerrain(true);
            eq(true, WorldgenSettings.oldTerrain());
            WorldgenSettings.load(dir, dir);
            eq(true, WorldgenSettings.oldTerrain());
            WorldgenSettings.setOldTerrain(false);
            WorldgenSettings.load(dir, dir);
            eq(false, WorldgenSettings.oldTerrain());
        } finally {
            Path reset = Files.createTempDirectory("worldgen-settings-old-terrain-reset");
            WorldgenSettings.load(reset, dir);
            deleteAll(reset);
            deleteAll(dir);
        }
    }

    static void newChunksOldLoadAbandons() {
        GeneratedStations.Entry e = new GeneratedStations.Entry("w", StationKinds.AIR, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        NewChunks news = new NewChunks();
        news.mark(0, -1);
        List<long[]> chunks = GenerationListener.footprintChunks(e);
        // chunk (0, 0) is loaded and was never marked new: old terrain.
        java.util.function.Predicate<long[]> loaded = c -> c[0] == 0 && c[1] == 0;
        GenerationListener.Decision d = GenerationListener.decide(chunks, c -> news.isNew((int) c[0], (int) c[1]),
                c -> loaded.test(c) && !news.isNew((int) c[0], (int) c[1]), false);
        eq(GenerationListener.Decision.ABANDON, d);
    }

    static void worldgenSettingsReviveRules() {
        eq(true, WorldgenSettings.reviveOnEnable(false, true, true));
        eq(false, WorldgenSettings.reviveOnEnable(false, true, false));
        eq(false, WorldgenSettings.reviveOnEnable(true, true, true));
        eq(false, WorldgenSettings.reviveOnEnable(false, false, true));
        eq(true, WorldgenSettings.reviveOnOldTerrain(false, true));
        eq(false, WorldgenSettings.reviveOnOldTerrain(true, true));
        eq(false, WorldgenSettings.reviveOnOldTerrain(true, false));
    }

    static void worldgenSettingDefault() throws Exception {
        Path dir = Files.createTempDirectory("worldgen-settings");
        try {
            WorldgenSettings.load(dir, dir);
            eq(true, WorldgenSettings.enabled());
            WorldgenSettings.setEnabled(false);
            WorldgenSettings.load(dir, dir);
            eq(false, WorldgenSettings.enabled());
            WorldgenSettings.setEnabled(true);
            WorldgenSettings.load(dir, dir);
            eq(true, WorldgenSettings.enabled());
        } finally {
            deleteAll(dir);
        }
    }

    static void footprintContainsShapes() throws Exception {
        List<Vector3i> station = TestSupport.prefabCells("Balloon_Landing_zone.prefab.json");
        List<Vector3i> balloon = TestSupport.prefabCells("Balloon_Public_Transport.prefab.json");
        List<Vector3i> boatStation = TestSupport.prefabCells("Boat_Station.prefab.json");
        List<Vector3i> shuttle = TestSupport.prefabCells("Steamboat_Public.prefab.json");
        // origins near a chunk corner, on both sides of zero
        int[][] origins = {{31, 31}, {0, 0}, {-1, -1}, {-33, 62}};
        for (Rotation r : new Rotation[] {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            for (int[] o : origins) {
                Vector3i origin = new Vector3i(o[0], 70, o[1]);
                GeneratedStations.Entry air = new GeneratedStations.Entry("w", StationKinds.AIR, o[0], o[1], r, false,
                        GeneratedStations.State.PLANNED);
                Set<String> airChunks = chunkSet(GenerationListener.footprintChunks(air));
                for (Vector3i c : station) {
                    check(airChunks.contains(chunkOf(r.rotateYaw(c, new Vector3i()).add(origin))), "station cell in footprint " + r + " " + c);
                }
                Vector3i dock = kinds().dockOrigin(origin, r);
                for (Vector3i c : balloon) {
                    check(airChunks.contains(chunkOf(r.rotateYaw(c, new Vector3i()).add(dock))), "docked balloon cell in footprint " + r + " " + c);
                }
                GeneratedStations.Entry boat = new GeneratedStations.Entry("w", StationKinds.BOAT, o[0], o[1], r, false,
                        GeneratedStations.State.PLANNED);
                Set<String> boatChunks = chunkSet(GenerationListener.footprintChunks(boat));
                for (Vector3i c : boatStation) {
                    check(boatChunks.contains(chunkOf(r.rotateYaw(c, new Vector3i()).add(origin))), "boat station cell in footprint " + r + " " + c);
                }
                int[] b = kinds().boatBerthOrigin();
                Vector3i berth = r.rotateYaw(new Vector3i(b[0], b[1], b[2]), new Vector3i()).add(origin);
                Rotation boatRot = r.add(StationKinds.BOAT_BERTH_ROTATION);
                for (Vector3i c : shuttle) {
                    check(boatChunks.contains(chunkOf(boatRot.rotateYaw(c, new Vector3i()).add(berth))), "shuttle cell in footprint " + r + " " + c);
                }
            }
        }
    }

    private static String chunkOf(Vector3i p) {
        return Math.floorDiv(p.x, 32) + "," + Math.floorDiv(p.z, 32);
    }

    static void reloadedNewChunkRetriggers() {
        GeneratedStations.Entry a = new GeneratedStations.Entry("w", StationKinds.AIR, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        GeneratedStations.Entry far = new GeneratedStations.Entry("w", StationKinds.AIR, 5000, 5000, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        List<GeneratedStations.Entry> pending = List.of(a, far);
        eq(List.of(a), GenerationListener.touching(pending, 0, 0));
        eq(List.of(far), GenerationListener.touching(pending, 5000 / 32, 5000 / 32));
        eq(0, GenerationListener.touching(pending, 40, 40).size());
    }}
