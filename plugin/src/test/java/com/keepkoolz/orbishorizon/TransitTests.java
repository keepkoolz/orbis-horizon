package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3d;
import org.joml.Vector3f;
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

/** Plain test harness: one static method per test, no library. */
public class TransitTests {
    private static int passed = 0;

    static void ladderLeverPhases() {
        eq(ShipLadder.Phase.LOWERING, ShipLadder.onLever(ShipLadder.Phase.UP));
        eq(ShipLadder.Phase.RAISING, ShipLadder.onLever(ShipLadder.Phase.LOWERING));
        eq(ShipLadder.Phase.RAISING, ShipLadder.onLever(ShipLadder.Phase.DOWN));
        eq(ShipLadder.Phase.LOWERING, ShipLadder.onLever(ShipLadder.Phase.RAISING));
        eq(ShipLadder.Phase.LOWERING, ShipLadder.afterLowerStep(true, 10));
        eq(ShipLadder.Phase.DOWN, ShipLadder.afterLowerStep(true, 48));
        eq(ShipLadder.Phase.DOWN, ShipLadder.afterLowerStep(false, 3));
    }

    static void ladderAdoptPhase() {
        for (ShipLadder.Phase ph : ShipLadder.Phase.values()) {
            eq(ph, ShipLadder.adoptPhase(ph, 0));
        }
        eq(ShipLadder.Phase.LOWERING, ShipLadder.adoptPhase(ShipLadder.Phase.UP, 3));
        eq(ShipLadder.Phase.LOWERING, ShipLadder.adoptPhase(ShipLadder.Phase.LOWERING, 3));
        eq(ShipLadder.Phase.DOWN, ShipLadder.adoptPhase(ShipLadder.Phase.DOWN, 3));
        eq(ShipLadder.Phase.RAISING, ShipLadder.adoptPhase(ShipLadder.Phase.RAISING, 3));
    }

    static void ladderCaught() {
        eq(true, ShipLadder.caught(40.0, 40));
        eq(true, ShipLadder.caught(40.9, 40));
        eq(false, ShipLadder.caught(41.0, 40));
        eq(false, ShipLadder.caught(39.5, 40));
    }

    static void ladderColumnCheck() {
        // column cell (10, 20), lowest rung y 40, bottom fixed rung y 44 -> feet in [40, 45)
        eq(true, ShipLadder.inColumn(10.5, 42.0, 20.5, 10, 20, 40, 44));
        eq(true, ShipLadder.inColumn(10.0, 40.0, 20.9, 10, 20, 40, 44));
        eq(false, ShipLadder.inColumn(11.2, 42.0, 20.5, 10, 20, 40, 44)); // next cell
        eq(false, ShipLadder.inColumn(9.9, 42.0, 20.5, 10, 20, 40, 44));
        eq(false, ShipLadder.inColumn(10.5, 39.9, 20.5, 10, 20, 40, 44)); // below the lowest rung
        eq(false, ShipLadder.inColumn(10.5, 45.0, 20.5, 10, 20, 40, 44)); // on the deck
        eq(true, ShipLadder.inColumn(-0.5, 42.0, -0.5, -1, -1, 40, 44));  // negative coords floor
    }

    static void raiseSkipsForeign() {
        int[] rungs = {5, 60, 0, 5, 59, 0, 5, 58, 0};       // top to bottom
        int[] out = ShipLadder.foreignSafeRaise(rungs, c -> c[1] != 59); // the y = 59 cell holds a foreign block
        eq(6, out.length);
        eq(58, out[1]);   // bottom first
        eq(60, out[4]);
    }

    static void resumeKindRead() {
        org.bson.BsonDocument empty = new org.bson.BsonDocument();
        check(AirshipResume.kindOf(empty) == Deployables.AIRSHIP, "absent -> airship");
        check(AirshipResume.kindOf(new org.bson.BsonDocument("kind", new org.bson.BsonString("airship"))) == Deployables.AIRSHIP, "airship");
        boolean threw = false;
        try { AirshipResume.kindOf(new org.bson.BsonDocument("kind", new org.bson.BsonString("tent"))); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "non-ship kind refused");
        threw = false;
        try { AirshipResume.kindOf(new org.bson.BsonDocument("kind", new org.bson.BsonString("nope"))); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "unknown kind refused");
    }

    static void registryKeepsWater() {
        WaterLog w = new WaterLog();
        w.record(1, 1); w.record(9, 1);
        w.addStop(40, 62, 0, Rotation.None, 41, 1);
        BalloonRegistry.Entry e = new BalloonRegistry.Entry("w", 1, 62, 3, Rotation.None, Deployables.STEAMBOAT_KIND, null, 0, false,
                List.of(), null, null, false, null, null, List.of(), null, w);
        BalloonRegistry.Entry back = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(e));
        eq(w.size(), back.water().size());
        eq(1, back.water().stops().size());
        BalloonRegistry.Entry old = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(
                new BalloonRegistry.Entry("w", 1, 62, 3, Rotation.None, Deployables.STEAMBOAT_KIND, null)));
        eq(null, old.water());
    }

    static void laddersRoundTrip() {
        ShipLadder.Saved s = new ShipLadder.Saved(-1, ShipLadder.Phase.RAISING, new int[] {10, 60, 5, 10, 59, 5});
        ShipLadder.Saved back = ShipLadder.Saved.fromBson(s.toBson());
        eq(-1, back.side());
        eq(ShipLadder.Phase.RAISING, back.phase());
        eq(6, back.rungs().length);
        eq(59, back.rungs()[4]);
        BalloonRegistry.Entry e = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(
                new BalloonRegistry.Entry("w", 0, 50, 0, Rotation.None, Deployables.AIRSHIP_KIND, null)));
        eq(0, e.ladders().size());
        BalloonRegistry.Entry withL = new BalloonRegistry.Entry("w", 0, 50, 0, Rotation.None, Deployables.AIRSHIP_KIND, null,
                0, false, java.util.List.of(), null, null, false, null, null, java.util.List.of(s));
        BalloonRegistry.Entry rt = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(withL));
        eq(1, rt.ladders().size());
        eq(ShipLadder.Phase.RAISING, rt.ladders().get(0).phase());
        eq(true, withL.equals(e));
        BalloonRegistry.Recall r = new BalloonRegistry.Recall(java.util.UUID.randomUUID(), 1, 2, 3, Rotation.None, 0.5, 0.5, new int[] {1, 2, 3});
        eq(3, BalloonRegistry.Recall.fromBson(r.toBson()).ladders().length);
    }

    static void legacyCrateConversion() {
        eq("Camping_Pack_Tent", LegacyCamps.convertedItem("Camping_Tent_Crate"));
        eq("Camping_Pack_Tent_Big", LegacyCamps.convertedItem("Camping_Tent_Big_Crate"));
        eq("", LegacyCamps.convertedItem("Camping_Tent_Crate_Empty"));
        eq("", LegacyCamps.convertedItem("Camping_Tent_Big_Crate_Empty"));
        eq(null, LegacyCamps.convertedItem("Camping_Pack_Tent"));
    }

    static void legacyBagCellFree() {
        check(LegacyCamps.bagCellFree(false, false), "air or grass");
        check(!LegacyCamps.bagCellFree(true, false), "player block");
        check(!LegacyCamps.bagCellFree(false, true), "water");
    }

    /** Fake terrain from a character map: one cell is 16 blocks, x grows along a row, z grows down the rows. */
    static class GridProbe implements TerrainProbe {
        private final String[] rows;

        GridProbe(String... rows) {
            this.rows = rows;
        }

        private char cell(int x, int z) {
            int r = Math.max(0, Math.min(rows.length - 1, Math.floorDiv(z, 16)));
            String row = rows[r];
            int c = Math.max(0, Math.min(row.length() - 1, Math.floorDiv(x, 16)));
            return row.charAt(c);
        }

        @Override
        public int height(int x, int z) {
            return cell(x, z) == '#' ? 120 : 100;
        }

        @Override
        public String zone(int x, int z) {
            return switch (cell(x, z)) {
                case '~' -> "Oceans";
                case 's' -> "Zone1_Shore";
                default -> "Zone1_Tier1";
            };
        }

        @Override
        public String biome(int x, int z) {
            return switch (cell(x, z)) {
                case 'l' -> "Lake";
                case 'r' -> "River";
                case 's' -> "Tile.Beach";
                default -> "";
            };
        }
    }

    static void seaRulesSeaVsLake() {
        GridProbe g = new GridProbe("#~lrs");
        int[] x = {8, 24, 40, 56, 72};
        boolean[] sea = {false, true, false, false, true};
        boolean[] water = {false, true, true, true, true};
        for (int i = 0; i < x.length; i++) {
            check(SeaRules.isSea(g, x[i], 8) == sea[i], "isSea cell " + i);
            check(SeaRules.isWater(g, x[i], 8) == water[i], "isWater cell " + i);
            check(SeaRules.isLand(g, x[i], 8) == !water[i], "isLand cell " + i);
        }
        eq(114, SeaRules.SEA_LEVEL);
    }


    /** One row of the given runs: a character repeated for a number of 16 block cells. */
    private static GridProbe strip(Object... runs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length; i += 2) {
            sb.append(String.valueOf(runs[i]).repeat((Integer) runs[i + 1]));
        }
        return new GridProbe(sb.toString());
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
        eq(0, WorldStationPlanner.cellOf(2047));
        eq(-1, WorldStationPlanner.cellOf(-1));
    }

    static void seaCellPicksShore() {
        GridProbe g = strip('~', 64, '#', 64);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 0, List.of());
        check(!sites.isEmpty(), "a station is planned");
        WorldStationPlanner.PlannedSite s = sites.get(0);
        eq(Deployables.BOAT_STATION_KIND, s.kind());
        eq(1024, s.x());
        check(!s.partner(), "not a partner");
        eq(WorldStationPlanner.facing(-1, 0), s.rotation());
        check(SeaRules.isLand(g, s.x(), s.z()), "on land");
        check(SeaRules.isSea(g, s.x() - 8, s.z()), "sea ahead");
    }

    /** Fake terrain recording the heights asked for: land everywhere, or sea (zone Oceans) from x seaFromX on. */
    private static class CountingProbe implements TerrainProbe {
        final int seaFromX;
        int heightCalls;
        int minHeightX = Integer.MAX_VALUE;

        CountingProbe(int seaFromX) {
            this.seaFromX = seaFromX;
        }

        @Override
        public int height(int x, int z) {
            heightCalls++;
            minHeightX = Math.min(minHeightX, x);
            return x >= seaFromX ? 100 : 120;
        }

        @Override
        public String zone(int x, int z) {
            return x >= seaFromX ? "Oceans" : "Zone1_Tier1";
        }

        @Override
        public String biome(int x, int z) {
            return "";
        }
    }

    static void seaCellRejectsInland() {
        CountingProbe p = new CountingProbe(Integer.MAX_VALUE);
        check(plan(p, 0, List.of()).isEmpty(), "no station inland");
        eq(0, p.heightCalls);
        check(WorldStationPlanner.seaSamples(p, 0, 0).isEmpty(), "no sea sample inland");
    }

    static void seaCellSkipsFarColumns() {
        CountingProbe p = new CountingProbe(1900);
        check(!WorldStationPlanner.seaSamples(p, 0, 0).isEmpty(), "sea sampled");
        List<WorldStationPlanner.PlannedSite> sites = plan(p, 0, List.of());
        check(!sites.isEmpty(), "a station is planned");
        check(p.minHeightX >= 1900 - WorldStationPlanner.SEA_NEAR - WorldStationPlanner.ZONE_STEP,
            "no height asked far from the sea: " + p.minHeightX);
        WorldStationPlanner.PlannedSite s = sites.get(0);
        check(SeaRules.isLand(p, s.x(), s.z()), "on land");
        check(s.x() > 1800 && s.x() < 1900, "on the coast: " + s.x());
    }

    private static boolean pierEndIsSea(TerrainProbe t, WorldStationPlanner.PlannedSite s) {
        Vector3i end = s.rotation().rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0],
                Deployables.BOAT_STATION_PIER_END[1], Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
        return SeaRules.isSea(t, s.x() + end.x, s.z() + end.z);
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
            {true, 1, 1009, 1, 0}, {true, -1, 1010, -1, 0}, {false, 1, 1009, 0, 1}, {false, -1, 1010, 0, -1}};
        for (Object[] c : cases) {
            boolean alongX = (Boolean) c[0];
            int sign = (Integer) c[1];
            TerrainProbe t = straightShore(alongX, 1010, sign);
            List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
            check(!sites.isEmpty(), "station for shore " + alongX + " " + sign);
            WorldStationPlanner.PlannedSite s = sites.get(0);
            eq((Integer) c[2], alongX ? s.x() : s.z());
            eq(WorldStationPlanner.facing((Integer) c[3], (Integer) c[4]), s.rotation());
            check(pierEndIsSea(t, s), "pier end on sea " + alongX + " " + sign);
        }
    }

    static void seaCellDeterministic() {
        GridProbe g = strip('~', 64, '#', 64);
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
        Vector3i a = s.rotation().rotateYaw(new Vector3i(Deployables.STATION_PLACE_MIN[0], 0, Deployables.STATION_PLACE_MIN[2]), new Vector3i());
        Vector3i b = s.rotation().rotateYaw(new Vector3i(Deployables.STATION_PLACE_MAX[0], 0, Deployables.STATION_PLACE_MAX[2]), new Vector3i());
        int h = WorldStationPlanner.PORTAL_HALF;
        return s.x() + Math.max(a.x, b.x) < px - h || s.x() + Math.min(a.x, b.x) > px + h
                || s.z() + Math.max(a.z, b.z) < pz - h || s.z() + Math.min(a.z, b.z) > pz + h;
    }

    static void portalPicksFlatSpot() {
        TerrainProbe t = heights((x, z) -> 120 + Math.max(0, 30 - z));
        WorldStationPlanner.PlannedSite s = WorldStationPlanner.planPortal(0, 0, t, List.of());
        check(s != null, "a station is planned");
        eq(Deployables.STATION_KIND, s.kind());
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
        check(WorldStationPlanner.planPortal(0, 0, t, List.of(new int[] {1300, 0})) == null, "air station at 1300");
        check(WorldStationPlanner.planPortal(0, 0, t, List.of(new int[] {1500, 0})) != null, "air station at exactly 1500");
    }

    static void portalSecondInRangeRefused() {
        TerrainProbe t = heights((x, z) -> 120);
        WorldStationPlanner.PlannedSite first = WorldStationPlanner.planPortal(0, 0, t, List.of());
        check(first != null, "first portal planned");
        List<int[]> air = new ArrayList<>();
        air.add(new int[] {first.x(), first.z()});
        check(WorldStationPlanner.planPortal(900, 0, t, air) == null, "second portal at 900 refused");
    }

    static void portalNoFlatSpot() {
        TerrainProbe t = heights((x, z) -> 120 + 3 * Math.floorDiv(x, 6));
        check(WorldStationPlanner.planPortal(0, 0, t, List.of()) == null, "staircase everywhere");
    }

    static void seaCellRespectsNetwork() {
        GridProbe g = strip('~', 64, '#', 64);
        WorldStationPlanner.PlannedSite s = plan(g, 0, List.of()).get(0);
        // a station in the middle of the cell's shore is within SPACING of every candidate
        eq(0, plan(g, 0, List.of(new int[] {s.x(), 1024})).size());
        int[] near = {s.x() + 1000, s.z()};
        for (WorldStationPlanner.PlannedSite p : plan(g, 0, List.of(near))) {
            if (!p.partner()) {
                long dx = p.x() - near[0], dz = p.z() - near[1];
                check(dx * dx + dz * dz >= (long) WorldStationPlanner.SPACING * WorldStationPlanner.SPACING, "main site keeps its spacing");
            }
        }
        check(!plan(g, 0, List.<int[]>of(new int[] {s.x() + 1600, s.z()})).isEmpty(), "far station does not block");
    }

    static void seaCellSkipsSpacedMain() {
        TerrainProbe t = straightShore(true, 1010, 1);
        WorldStationPlanner.PlannedSite s = plan(t, 0, List.of()).get(0);
        // a station just within SPACING of the closest candidate, on the side away from the rest of the shore
        int[] other = {s.x(), s.z() < WorldStationPlanner.CELL / 2 ? s.z() - 1490 : s.z() + 1490};
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of(other));
        check(!sites.isEmpty(), "a farther candidate is planned");
        WorldStationPlanner.PlannedSite main = sites.get(0);
        check(!main.partner(), "main site first");
        check(main.x() != s.x() || main.z() != s.z(), "the closest candidate is skipped");
        long dx = main.x() - other[0], dz = main.z() - other[1];
        check(dx * dx + dz * dz >= (long) WorldStationPlanner.SPACING * WorldStationPlanner.SPACING, "main site keeps its spacing");
        check(WorldStationPlanner.boatSiteFits(t, main.x(), main.z(), main.rotation()), "the main site fits");
        for (WorldStationPlanner.Site f : main.fallbacks()) {
            long fx = f.x() - other[0], fz = f.z() - other[1];
            check(fx * fx + fz * fz >= (long) WorldStationPlanner.SPACING * WorldStationPlanner.SPACING, "fallback keeps its spacing");
        }
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
        Vector3i end = east.rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], 0,
                Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
        int ex = 1009 + end.x, ez = 1000 + end.z;
        check(WorldStationPlanner.boatSiteFits(base, 1009, 1000, east), "the plain coast fits");
        check(!WorldStationPlanner.boatSiteFits(withColumn(base, ex, ez, 114), 1009, 1000, east), "land at the pier end blocks the site");
        check(WorldStationPlanner.boatSiteFits(withColumn(base, ex, ez, 100), 1009, 1000, east), "sea at the pier end fits");
    }

    static void boatSiteFitsFloorOnLand() {
        Rotation east = WorldStationPlanner.facing(1, 0);
        TerrainProbe base = coast((x, z) -> x < 1010);
        check(WorldStationPlanner.boatSiteFits(base, 1009, 1000, east), "the plain coast fits");
        Vector3i berth = east.rotateYaw(new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], 0,
                Deployables.BOAT_STATION_BERTH_ORIGIN[2]), new Vector3i());
        Rotation berthRotation = east.add(Deployables.BOAT_STATION_BERTH_ROTATION);
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
        for (StructureShape.Cell c : prefabShape("Steamboat_Public.prefab.json").cells()) {
            if (c.y() == 0) {
                expected.add(new Vector3i(c.x(), 0, c.z()));
            }
        }
        eq(expected, WorldStationPlanner.shuttleFloorColumns());
    }

    static void seaCellSkipsBlockedShore() {
        // An offshore bar blocks the pier box everywhere in the cell except along z 1000..1300.
        TerrainProbe t = coast((x, z) -> x < 1010 || (x >= 1018 && x <= 1020 && (z < 1000 || z > 1300)));
        List<WorldStationPlanner.PlannedSite> sites = plan(t, 0, List.of());
        check(!sites.isEmpty(), "a station is planned on the straight stretch");
        WorldStationPlanner.PlannedSite s = sites.get(0);
        check(WorldStationPlanner.boatSiteFits(t, s.x(), s.z(), s.rotation()), "the site fits");
        check(s.z() >= 1000 && s.z() <= 1300, "the station is on the straight stretch");
    }

    static void partnerShiftsAlongShore() {
        TerrainProbe base = coast((x, z) -> x < 1010 || x >= 1500);
        List<WorldStationPlanner.PlannedSite> before = plan(base, 0, List.of());
        eq(2, before.size());
        WorldStationPlanner.PlannedSite p0 = before.get(1);
        Vector3i f = p0.rotation().rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
        int lx = Math.abs(f.z), lz = Math.abs(f.x);
        // Land under the pier end column of the first landing.
        Vector3i end = p0.rotation().rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], 0,
                Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
        TerrainProbe t = coast((x, z) -> {
            if (base.height(x, z) > SeaRules.SEA_LEVEL) {
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
        TerrainProbe t = straightShore(true, 1010, 1);
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
        TerrainProbe base = coast((x, z) -> x < 1010 || x >= 1500);
        List<WorldStationPlanner.PlannedSite> before = plan(base, 0, List.of());
        eq(2, before.size());
        WorldStationPlanner.PlannedSite p0 = before.get(1);
        Vector3i f = p0.rotation().rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
        int lx = Math.abs(f.z), lz = Math.abs(f.x);
        // Land under the pier end column of the first landing.
        Vector3i end = p0.rotation().rotateYaw(new Vector3i(Deployables.BOAT_STATION_PIER_END[0], 0,
                Deployables.BOAT_STATION_PIER_END[2]), new Vector3i());
        TerrainProbe t = coast((x, z) -> {
            if (base.height(x, z) > SeaRules.SEA_LEVEL) {
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
        eq(Deployables.BOAT_STATION_KIND, partner.kind());
        eq(WorldStationPlanner.facing(1, 0), partner.rotation());
        check(SeaRules.isLand(g, partner.x(), partner.z()), "partner on land");
        eq(431, partner.x());
        check(pierEndIsSea(g, partner), "partner pier end on sea");
    }

    static void crossingTooFar() {
        GridProbe g = strip('#', 167, '~', 281, '#', 64);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 3, List.of());
        eq(1, sites.size());
    }

    static void crossingStopsAtInlandWater() {
        GridProbe g = strip('#', 20, 'l', 10, '~', 34, '#', 64);
        eq(1, plan(g, 0, List.of()).size());
    }

    static void crossingReusesNearby() {
        GridProbe g = strip('#', 292, '~', 156, '#', 64);
        List<WorldStationPlanner.PlannedSite> sites = plan(g, 3, List.of());
        eq(2, sites.size());
        WorldStationPlanner.PlannedSite partner = sites.get(1);
        List<int[]> existing = List.<int[]>of(new int[] {partner.x() - 300, partner.z()});
        eq(1, plan(g, 3, existing).size());
    }

    static void namesDeterministic() {
        List<String> taken = List.of("Port Olhane");
        eq(StationNames.generate(7L, 100, -200, Deployables.STATION_KIND, "Zone3_Tier1", taken),
            StationNames.generate(7L, 100, -200, Deployables.STATION_KIND, "Zone3_Tier1", taken));
    }

    static void namesKindPrefix() {
        String sea = StationNames.generate(1L, 5, 9, Deployables.BOAT_STATION_KIND, "Oceans", List.of());
        String air = StationNames.generate(1L, 5, 9, Deployables.STATION_KIND, "Zone1_Tier1", List.of());
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
            String kind = i % 3 == 0 ? Deployables.BOAT_STATION_KIND : Deployables.STATION_KIND;
            String name = StationNames.generate(42L, i * 2048, (i % 7) * 2048, kind, zones[i % zones.length], taken);
            eq(TransitNetwork.NameCheck.OK, TransitNetwork.checkName(name, taken));
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
        for (String kind : new String[] {Deployables.STATION_KIND, Deployables.BOAT_STATION_KIND}) {
            List<String> cores = StationNames.candidates(5L, 300, 400, "Zone3_Tier1");
            List<String> taken = new ArrayList<>(cores);
            String suffixed = StationNames.generate(5L, 300, 400, kind, "Zone3_Tier1", taken);
            eq(TransitNetwork.NameCheck.OK, TransitNetwork.checkName(suffixed, taken));
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
            eq(TransitNetwork.NameCheck.OK, TransitNetwork.checkName(auto, full));
            eq(TransitNetwork.autoName(full), auto);
        }
        List<String> taken = new ArrayList<>(StationNames.candidates(9L, 1, 1, "Zone1_Tier1"));
        eq(TransitNetwork.NameCheck.OK, TransitNetwork.checkName(
            StationNames.generate(9L, 1, 1, Deployables.BOAT_STATION_KIND, "Zone1_Tier1", taken), taken));
    }

    static void namesSyllableLists() {
        check(StationNames.minSyllables() >= 12, "at least 12 syllables per list");
    }

    static void seaRulesBiomePrefix() {
        TerrainProbe t = new TerrainProbe() {
            public int height(int x, int z) { return 100; }
            public String zone(int x, int z) { return "Zone1_Shore"; }
            public String biome(int x, int z) { return "Custom.Lake_Moss"; }
        };
        check(SeaRules.isWater(t, 0, 0), "under water");
        check(!SeaRules.isSea(t, 0, 0), "Custom.Lake_Moss is not sea");
        TerrainProbe beach = new TerrainProbe() {
            public int height(int x, int z) { return 100; }
            public String zone(int x, int z) { return "Zone2_Shallow_Ocean_Tier1"; }
            public String biome(int x, int z) { return "Tile.Reef"; }
        };
        check(SeaRules.isSea(beach, 0, 0), "shallow ocean with prefixed biome is sea");
    }

    public static void main(String[] args) throws Exception {
        run("airshipCaptainChest", TransitTests::airshipCaptainChest);
        run("nextSiteSkipsTooClose", TransitTests::nextSiteSkipsTooClose);
        run("namesDeterministic", TransitTests::namesDeterministic);
        run("namesKindPrefix", TransitTests::namesKindPrefix);
        run("namesTooClose", TransitTests::namesTooClose);
        run("facingMatchesPierEnd", TransitTests::facingMatchesPierEnd);
        run("seaCellPicksShore", TransitTests::seaCellPicksShore);
        run("seaCellRejectsInland", TransitTests::seaCellRejectsInland);
        run("seaCellSkipsFarColumns", TransitTests::seaCellSkipsFarColumns);
        run("seaCellAnyShoreOffset", TransitTests::seaCellAnyShoreOffset);
        run("seaCellDeterministic", TransitTests::seaCellDeterministic);
        run("seaCellIgnoresLake", TransitTests::seaCellIgnoresLake);
        run("seaCellRespectsNetwork", TransitTests::seaCellRespectsNetwork);
        run("seaCellSkipsSpacedMain", TransitTests::seaCellSkipsSpacedMain);
        run("portalPicksFlatSpot", TransitTests::portalPicksFlatSpot);
        run("portalAvoidsWater", TransitTests::portalAvoidsWater);
        run("portalRespectsNetwork", TransitTests::portalRespectsNetwork);
        run("portalSecondInRangeRefused", TransitTests::portalSecondInRangeRefused);
        run("portalNoFlatSpot", TransitTests::portalNoFlatSpot);
        run("boatSiteFitsJaggedCoast", TransitTests::boatSiteFitsJaggedCoast);
        run("repairCandidatesOrder", TransitTests::repairCandidatesOrder);
        run("repairFindsNeighbour", TransitTests::repairFindsNeighbour);
        run("repairSkipsUnready", TransitTests::repairSkipsUnready);
        run("boatSiteFitsShallowBerth", TransitTests::boatSiteFitsShallowBerth);
        run("boatSiteFitsPierEndLand", TransitTests::boatSiteFitsPierEndLand);
        run("boatSiteFitsFloorOnLand", TransitTests::boatSiteFitsFloorOnLand);
        run("boatSiteFitsLandInPlaceBox", TransitTests::boatSiteFitsLandInPlaceBox);
        run("shuttleFloorMatchesPrefab", TransitTests::shuttleFloorMatchesPrefab);
        run("seaCellSkipsBlockedShore", TransitTests::seaCellSkipsBlockedShore);
        run("partnerShiftsAlongShore", TransitTests::partnerShiftsAlongShore);
        run("seaCellRanksSites", TransitTests::seaCellRanksSites);
        run("crossingKeepsFallbacks", TransitTests::crossingKeepsFallbacks);
        run("crossingFindsPartners", TransitTests::crossingFindsPartners);
        run("crossingTooFar", TransitTests::crossingTooFar);
        run("crossingStopsAtInlandWater", TransitTests::crossingStopsAtInlandWater);
        run("crossingReusesNearby", TransitTests::crossingReusesNearby);
        run("namesNoNearDuplicates", TransitTests::namesNoNearDuplicates);
        run("namesFallbackAlwaysValid", TransitTests::namesFallbackAlwaysValid);
        run("namesSyllableLists", TransitTests::namesSyllableLists);
        run("seaRulesSeaVsLake", TransitTests::seaRulesSeaVsLake);
        run("seaRulesBiomePrefix", TransitTests::seaRulesBiomePrefix);
        run("balloonCrewSpec", TransitTests::balloonCrewSpec);
        run("balloonNoCrewUnchanged", TransitTests::balloonNoCrewUnchanged);
        run("balloonResumeKeepsCrew", TransitTests::balloonResumeKeepsCrew);
        run("hireBlockRule", TransitTests::hireBlockRule);
        run("pilotOwnedByOther", TransitTests::pilotOwnedByOther);
        run("usageCountersSnapshot", TransitTests::usageCountersSnapshot);
        run("tickTimingsAggregate", TransitTests::tickTimingsAggregate);
        run("tickTimingsRounding", TransitTests::tickTimingsRounding);
        run("performanceDescriptor", TransitTests::performanceDescriptor);
        run("genClearRemovable", TransitTests::genClearRemovable);
        run("planTaskOrder", TransitTests::planTaskOrder);
        run("footprintCoversDome", TransitTests::footprintCoversDome);
        run("domeShape", TransitTests::domeShape);
        run("domeCellsRotated", TransitTests::domeCellsRotated);
        run("fillPlanColumns", TransitTests::fillPlanColumns);
        run("attachColumnsToShore", TransitTests::attachColumnsToShore);
        run("boatStationHutFoundation", TransitTests::boatStationHutFoundation);
        run("masterCheck", TransitTests::masterCheck);
        run("refusalCarriesHullFlag", TransitTests::refusalCarriesHullFlag);
        run("boatFootprintCoversBerthedBoat", TransitTests::boatFootprintCoversBerthedBoat);
        run("emptiedCellsReadAsEmpty", TransitTests::emptiedCellsReadAsEmpty);
        run("roomRefusalKeysUnchanged", TransitTests::roomRefusalKeysUnchanged);
        run("errorThrottleWindow", TransitTests::errorThrottleWindow);
        run("telemetryWithoutBeacon", TransitTests::telemetryWithoutBeacon);
        run("shutdownFlushSettled", TransitTests::shutdownFlushSettled);
        run("usageNamesMatchDescriptor", TransitTests::usageNamesMatchDescriptor);
        run("wheelAnimationsDeclared", TransitTests::wheelAnimationsDeclared);
        run("redactedErrorHasNoMessage", TransitTests::redactedErrorHasNoMessage);
        run("travelTargetReverse", TransitTests::travelTargetReverse);
        run("pilotSpeedReverse", TransitTests::pilotSpeedReverse);
        run("engineSoundRule", TransitTests::engineSoundRule);
        run("campWakeRule", TransitTests::campWakeRule);
        run("campChestForbidden", TransitTests::campChestForbidden);
        run("campChestSplit", TransitTests::campChestSplit);
        run("legacyCrateConversion", TransitTests::legacyCrateConversion);
        run("legacyBagCellFree", TransitTests::legacyBagCellFree);
        run("campPackerGiveFallback", TransitTests::campPackerGiveFallback);
        run("removalKeepsForeignBagCell", TransitTests::removalKeepsForeignBagCell);
        run("slumberTargetReached", TransitTests::slumberTargetReached);
        run("pendingDepartureSingle", TransitTests::pendingDepartureSingle);
        run("ladderLeverPhases", TransitTests::ladderLeverPhases);
        run("ladderAdoptPhase", TransitTests::ladderAdoptPhase);
        run("ladderCaught", TransitTests::ladderCaught);
        run("ladderColumnCheck", TransitTests::ladderColumnCheck);
        run("raiseSkipsForeign", TransitTests::raiseSkipsForeign);
        run("registryKeepsWater", TransitTests::registryKeepsWater);
        run("laddersRoundTrip", TransitTests::laddersRoundTrip);
        run("resumeKindRead", TransitTests::resumeKindRead);
        run("nameRules", TransitTests::nameRules);
        run("componentGuard", TransitTests::componentGuard);
        run("tentContentsRoundTrip", TransitTests::tentContentsRoundTrip);
        run("tentContentsMissing", TransitTests::tentContentsMissing);
        run("tentContentsVersion", TransitTests::tentContentsVersion);
        run("stationFileRoundTrip", TransitTests::stationFileRoundTrip);
        run("fareChoice", TransitTests::fareChoice);
        run("refundsFileRoundTrip", TransitTests::refundsFileRoundTrip);
        run("refundsTakeOnce", TransitTests::refundsTakeOnce);
        run("refundsCorruptKept", TransitTests::refundsCorruptKept);
        run("paymentsRoundTrip", TransitTests::paymentsRoundTrip);
        run("paymentsOldFile", TransitTests::paymentsOldFile);
        run("paymentsRemovedWithQueue", TransitTests::paymentsRemovedWithQueue);
        run("stationsOfKind", TransitTests::stationsOfKind);
        run("stationDistanceSameKind", TransitTests::stationDistanceSameKind);
        run("corruptFileIsKept", TransitTests::corruptFileIsKept);
        run("grassLayerCells", TransitTests::grassLayerCells);
        run("settingsRoundTrip", TransitTests::settingsRoundTrip);
        run("markerSharingSetting", TransitTests::markerSharingSetting);
        run("ownedMarkerSharingSwitch", TransitTests::ownedMarkerSharingSwitch);
        run("knownStationsSharingSwitch", TransitTests::knownStationsSharingSwitch);
        run("settingsDisabledRecipes", TransitTests::settingsDisabledRecipes);
        run("recipeTogglesWithoutUniverse", TransitTests::recipeTogglesWithoutUniverse);
        run("configSearch", TransitTests::configSearch);
        run("packBackShownTier", TransitTests::packBackShownTier);
        run("reservationIsExclusive", TransitTests::reservationIsExclusive);
        run("queueRules", TransitTests::queueRules);
        run("cruiseAndHolding", TransitTests::cruiseAndHolding);
        run("holdingPoints", TransitTests::holdingPoints);
        run("rerouteTarget", TransitTests::rerouteTarget);
        run("returnStation", TransitTests::returnStation);
        run("shuttleReturnStation", TransitTests::shuttleReturnStation);
        run("shuttleRedirectTargets", TransitTests::shuttleRedirectTargets);
        run("boatApproachGiveUp", TransitTests::boatApproachGiveUp);
        run("leaveSpot", TransitTests::leaveSpot);
        run("followNoTeleportLoop", TransitTests::followNoTeleportLoop);
        run("randomSeatYawRange", TransitTests::randomSeatYawRange);
        run("viewFilterRules", TransitTests::viewFilterRules);
        run("compassAndTrip", TransitTests::compassAndTrip);
        run("transitViewYaw", TransitTests::transitViewYaw);
        run("groundCageRemaining", TransitTests::groundCageRemaining);
        run("deployPlacement", TransitTests::deployPlacement);
        run("boatStationRoomRule", TransitTests::boatStationRoomRule);
        run("waterSurfaceSearch", TransitTests::waterSurfaceSearch);
        run("steamboatFitCheck", TransitTests::steamboatFitCheck);
        run("steamboatFaultCell", TransitTests::steamboatFaultCell);
        run("shuttleBerthWaterline", TransitTests::shuttleBerthWaterline);
        run("steamboatWaterlineLayer", TransitTests::steamboatWaterlineLayer);
        run("gliderAllowedKinds", TransitTests::gliderAllowedKinds);
        run("steamboatAnchor", TransitTests::steamboatAnchor);
        run("steamboatCrewSpec", TransitTests::steamboatCrewSpec);
        run("crewSeatYaw", TransitTests::crewSeatYaw);
        run("crewRestRotationMatchesPrefab", TransitTests::crewRestRotationMatchesPrefab);
        run("crewOptionsWithoutVoyage", TransitTests::crewOptionsWithoutVoyage);
        run("boatCrewOrders", TransitTests::boatCrewOrders);
        run("boatStationBerth", TransitTests::boatStationBerth);
        run("steamboatPublicPrefab", TransitTests::steamboatPublicPrefab);
        run("steamboatSeats", TransitTests::steamboatSeats);
        run("markerIgnoresFiller", TransitTests::markerIgnoresFiller);
        run("wheelSpecFlags", TransitTests::wheelSpecFlags);
        run("neighbourFluidChoice", TransitTests::neighbourFluidChoice);
        run("quartersByCloseness", TransitTests::quartersByCloseness);
        run("smallTentChest", TransitTests::smallTentChest);
        run("campTiersTable", TransitTests::campTiersTable);
        run("campTiersJson", TransitTests::campTiersJson);
        run("campUpgradeRefusal", TransitTests::campUpgradeRefusal);
        run("campUpgradeQueue", TransitTests::campUpgradeQueue);
        run("campUpgradeCarryAfterPost", TransitTests::campUpgradeCarryAfterPost);
        run("campUpgradeReconcile", TransitTests::campUpgradeReconcile);
        run("tentContentsConvert", TransitTests::tentContentsConvert);
        run("campRecipesJson", TransitTests::campRecipesJson);
        run("explorerBenchJson", TransitTests::explorerBenchJson);
        run("recipesOnExplorerBench", TransitTests::recipesOnExplorerBench);
        run("creativeVisibility", TransitTests::creativeVisibility);
        run("oxygenHud", TransitTests::oxygenHud);
        run("boatStationPrefab", TransitTests::boatStationPrefab);
        run("helmetHaircut", TransitTests::helmetHaircut);
        run("externalView", TransitTests::externalView);
        run("behindHeading", TransitTests::behindHeading);
        run("viewResend", TransitTests::viewResend);
        run("protectionRules", TransitTests::protectionRules);
        run("protectedFileRoundTrip", TransitTests::protectedFileRoundTrip);
        run("passengerProtectionModePersists", TransitTests::passengerProtectionModePersists);
        run("registryOwnershipRoundTrip", TransitTests::registryOwnershipRoundTrip);
        run("registryWoodRoundTrip", TransitTests::registryWoodRoundTrip);
        run("boatStationShapeOfWood", TransitTests::boatStationShapeOfWood);
        run("stationMarkerPoint", TransitTests::stationMarkerPoint);
        run("balloonVoyageRefusal", TransitTests::balloonVoyageRefusal);
        run("balloonLandingCandidates", TransitTests::balloonLandingCandidates);
        run("balloonLandingCandidatesAllBlocked", TransitTests::balloonLandingCandidatesAllBlocked);
        run("balloonVoyageStopOnce", TransitTests::balloonVoyageStopOnce);
        run("balloonCruiseCeiling", TransitTests::balloonCruiseCeiling);
        run("stationZoneCell", TransitTests::stationZoneCell);
        run("stationZoneCorner", TransitTests::stationZoneCorner);
        run("stationZoneOtherWorld", TransitTests::stationZoneOtherWorld);
        run("ownershipRules", TransitTests::ownershipRules);
        run("ownedMarkerRules", TransitTests::ownedMarkerRules);
        run("mapShareRules", TransitTests::mapShareRules);
        run("gliderJumpPhase", TransitTests::gliderJumpPhase);
        run("gliderExitLocal", TransitTests::gliderExitLocal);
        run("airshipLimitCounts", TransitTests::airshipLimitCounts);
        run("shipFeatureFlags", TransitTests::shipFeatureFlags);
        run("airshipCrewSpecUnchanged", TransitTests::airshipCrewSpecUnchanged);
        run("limitIgnoresOtherShips", TransitTests::limitIgnoresOtherShips);
        run("walkLegsAisle", TransitTests::walkLegsAisle);
        run("staleEntryUnknownIsPresent", TransitTests::staleEntryUnknownIsPresent);
        run("landingOwnerLimit", TransitTests::landingOwnerLimit);
        run("contractMissing", TransitTests::contractMissing);
        run("crewBsonRoundTrip", TransitTests::crewBsonRoundTrip);
        run("crewPostAfterReload", TransitTests::crewPostAfterReload);
        run("pilotSeatPoint", TransitTests::pilotSeatPoint);
        run("crewPostPoint", TransitTests::crewPostPoint);
        run("helmCellFree", TransitTests::helmCellFree);
        run("crewDialogueOptions", TransitTests::crewDialogueOptions);
        run("helmRefusal", TransitTests::helmRefusal);
        run("deskDecision", TransitTests::deskDecision);
        run("seatsExcludeStool", TransitTests::seatsExcludeStool);
        run("pilotGenderAndName", TransitTests::pilotGenderAndName);
        run("pilotApplyGender", TransitTests::pilotApplyGender);
        run("voyageList", TransitTests::voyageList);
        run("arrivalNearStation", TransitTests::arrivalNearStation);
        run("arrivalRotatesOutOfZones", TransitTests::arrivalRotatesOutOfZones);
        run("cruiseHeight", TransitTests::cruiseHeight);
        run("voyageTrip", TransitTests::voyageTrip);
        run("departureRefusal", TransitTests::departureRefusal);
        run("captainMapPhase", TransitTests::captainMapPhase);
        run("voyageIncludesBoatStations", TransitTests::voyageIncludesBoatStations);
        run("mapHolderRule", TransitTests::mapHolderRule);
        run("voyageCaptainRule", TransitTests::voyageCaptainRule);
        run("autopilotLookAhead", TransitTests::autopilotLookAhead);
        run("voyageStateBson", TransitTests::voyageStateBson);
        run("recallSpawnPoint", TransitTests::recallSpawnPoint);
        run("hoverPivotClearsStation", TransitTests::hoverPivotClearsStation);
        run("recallLadderSide", TransitTests::recallLadderSide);
        run("recallLadderCells", TransitTests::recallLadderCells);
        run("recallClimbing", TransitTests::recallClimbing);
        run("recallMarkRoundTrip", TransitTests::recallMarkRoundTrip);
        run("recallRefusal", TransitTests::recallRefusal);
        run("recallWaitPhase", TransitTests::recallWaitPhase);
        run("recallWaitCrossed", TransitTests::recallWaitCrossed);
        run("quayRecallBlocks", TransitTests::quayRecallBlocks);
        run("recallVoyageState", TransitTests::recallVoyageState);
        run("recallSavedRoundTrip", TransitTests::recallSavedRoundTrip);
        run("benchContentsKeys", TransitTests::benchContentsKeys);
        run("recallRefusalOccupied", TransitTests::recallRefusalOccupied);
        run("recallStrandOffline", TransitTests::recallStrandOffline);
        run("recallDepartRetryBound", TransitTests::recallDepartRetryBound);
        run("recallApproachNear", TransitTests::recallApproachNear);
        run("recallTakeoffRungsRoundTrip", TransitTests::recallTakeoffRungsRoundTrip);
        run("legacyLadderAccepted", TransitTests::legacyLadderAccepted);
        run("ashoreSide", TransitTests::ashoreSide);
        run("ashoreDescent", TransitTests::ashoreDescent);
        run("gateRule", TransitTests::gateRule);
        run("campRespawnShouldRescue", TransitTests::campRespawnShouldRescue);
        run("campRespawnForget", TransitTests::campRespawnForget);
        run("commandRights", TransitTests::commandRights);
        run("campRespawnGuard", TransitTests::campRespawnGuard);
        run("campRespawnFreeSpot", TransitTests::campRespawnFreeSpot);
        run("campRespawnOwnersOfBed", TransitTests::campRespawnOwnersOfBed);
        run("campRespawnFileRoundTrip", TransitTests::campRespawnFileRoundTrip);
        run("shipKinds", TransitTests::shipKinds);
        run("submergedCells", TransitTests::submergedCells);
        run("steamboatKind", TransitTests::steamboatKind);
        run("smokeExhaustCells", TransitTests::smokeExhaustCells);
        run("smokeLegacyAlias", TransitTests::smokeLegacyAlias);
        run("steamboatThickSmoke", TransitTests::steamboatThickSmoke);
        run("hullOnWater", TransitTests::hullOnWater);
        run("shipKindsOrder", TransitTests::shipKindsOrder);
        run("wheelTable", TransitTests::wheelTable);
        run("wheelNames", TransitTests::wheelNames);
        run("propellerRules", TransitTests::propellerRules);
        run("propellerNames", TransitTests::propellerNames);
        run("propellerWater", TransitTests::propellerWater);
        run("propellerAnimationsDeclared", TransitTests::propellerAnimationsDeclared);
        run("helmRules", TransitTests::helmRules);
        run("helmAnimationFiles", TransitTests::helmAnimationFiles);
        run("helmDeclared", TransitTests::helmDeclared);
        run("paddleSplashDue", TransitTests::paddleSplashDue);
        run("wheelHoldAndCycle", TransitTests::wheelHoldAndCycle);
        run("shipTextKeys", TransitTests::shipTextKeys);
        run("stationSpecPoints", TransitTests::stationSpecPoints);
        run("boatStationOrigin", TransitTests::boatStationOrigin);
        run("boatStationOriginBlockerCell", TransitTests::boatStationOriginBlockerCell);
        run("biomeWoodLookup", TransitTests::biomeWoodLookup);
        run("chooseSpecies", TransitTests::chooseSpecies);
        run("woodRemap", TransitTests::woodRemap);
        run("stoneRemap", TransitTests::stoneRemap);
        run("stoneTableZones", TransitTests::stoneTableZones);
        run("stationWoodRemap", TransitTests::stationWoodRemap);
        run("stationWoodAsh", TransitTests::stationWoodAsh);
        run("registryStoneRoundTrip", TransitTests::registryStoneRoundTrip);
        run("shortcutOpenWater", TransitTests::shortcutOpenWater);
        run("shortcutKeepsUnknown", TransitTests::shortcutKeepsUnknown);
        run("shortcutAroundLand", TransitTests::shortcutAroundLand);
        run("shortcutDogLeg", TransitTests::shortcutDogLeg);
        run("waterRouteOpenLake", TransitTests::waterRouteOpenLake);
        run("waterRouteIsland", TransitTests::waterRouteIsland);
        run("waterRouteSCurve", TransitTests::waterRouteSCurve);
        run("waterRouteNarrow", TransitTests::waterRouteNarrow);
        run("waterRouteSeparateLakes", TransitTests::waterRouteSeparateLakes);
        run("waterRouteNeedsData", TransitTests::waterRouteNeedsData);
        run("waterRouteBerthNearShore", TransitTests::waterRouteBerthNearShore);
        run("waterRouteNegativeOffset", TransitTests::waterRouteNegativeOffset);
        run("waterRouteResumedSame", TransitTests::waterRouteResumedSame);
        run("waterRouteTooLong", TransitTests::waterRouteTooLong);
        run("waterRouteLongCrossing", TransitTests::waterRouteLongCrossing);
        run("waterRouteTurnClearance", TransitTests::waterRouteTurnClearance);
        run("waterRouteAvoid", TransitTests::waterRouteAvoid);
        run("waterRouteFreeStart", TransitTests::waterRouteFreeStart);
        run("waterRouteTurnData", TransitTests::waterRouteTurnData);
        run("waterExitOpen", TransitTests::waterExitOpen);
        run("waterExitPond", TransitTests::waterExitPond);
        run("waterExitShore", TransitTests::waterExitShore);
        run("waterExitNarrowChannel", TransitTests::waterExitNarrowChannel);
        run("waterExitWideChannel", TransitTests::waterExitWideChannel);
        run("waterExitUnknown", TransitTests::waterExitUnknown);
        run("boatLookAhead", TransitTests::boatLookAhead);
        run("boatDetour", TransitTests::boatDetour);
        run("paddleSlideWheels", TransitTests::paddleSlideWheels);
        run("boatRoutesFileRoundTrip", TransitTests::boatRoutesFileRoundTrip);
        run("crossMaxLeavesRoomForRoute", TransitTests::crossMaxLeavesRoomForRoute);
        run("newChunksWorldMismatch", TransitTests::newChunksWorldMismatch);
        run("generatedWorldMismatch", TransitTests::generatedWorldMismatch);
        run("boatRoutesInvalidate", TransitTests::boatRoutesInvalidate);
        run("berthPivotRotation", TransitTests::berthPivotRotation);
        run("mooredPilot", TransitTests::mooredPilot);
        run("mooredPilotFlipped", TransitTests::mooredPilotFlipped);
        run("berthStepBothHeadings", TransitTests::berthStepBothHeadings);
        run("berthHeadingChoice", TransitTests::berthHeadingChoice);
        run("berthCentreRotation", TransitTests::berthCentreRotation);
        run("boatDropPoints", TransitTests::boatDropPoints);
        run("boatQuayRules", TransitTests::boatQuayRules);
        run("voyageEndAction", TransitTests::voyageEndAction);
        run("waterLogLineFill", TransitTests::waterLogLineFill);
        run("waterLogUPath", TransitTests::waterLogUPath);
        run("waterLogStops", TransitTests::waterLogStops);
        run("waterLogRoundTrip", TransitTests::waterLogRoundTrip);
        run("stationShareCommand", TransitTests::stationShareCommand);
        run("knownStationsAddOnce", TransitTests::knownStationsAddOnce);
        run("knownStationsSeparation", TransitTests::knownStationsSeparation);
        run("knownStationsShared", TransitTests::knownStationsShared);
        run("knownStationsRoundTrip", TransitTests::knownStationsRoundTrip);
        run("knownStationsMalformed", TransitTests::knownStationsMalformed);
        run("skyLogCells", TransitTests::skyLogCells);
        run("skyLogStationDiscovery", TransitTests::skyLogStationDiscovery);
        run("destinationKnownOthers", TransitTests::destinationKnownOthers);
        run("skyLogMarkerDistance", TransitTests::skyLogMarkerDistance);
        run("skyLogRoundTrip", TransitTests::skyLogRoundTrip);
        run("skyLogDestinationFilter", TransitTests::skyLogDestinationFilter);
        run("waterLogEmptyStart", TransitTests::waterLogEmptyStart);
        run("reachedStationsDropRemoved", TransitTests::reachedStationsDropRemoved);
        run("waterLogStationTarget", TransitTests::waterLogStationTarget);
        run("boatBackoffAction", TransitTests::boatBackoffAction);
        run("boatRouteLeg", TransitTests::boatRouteLeg);
        run("boatRoutesOldVersion", TransitTests::boatRoutesOldVersion);
        run("boatAutopilotHeading", TransitTests::boatAutopilotHeading);
        run("boatAutopilotReached", TransitTests::boatAutopilotReached);
        run("boatAutopilotSpeed", TransitTests::boatAutopilotSpeed);
        run("boatAutopilotHeadingChange", TransitTests::boatAutopilotHeadingChange);
        run("boatBerthManoeuvre", TransitTests::boatBerthManoeuvre);
        run("shuttleFuellessRules", TransitTests::shuttleFuellessRules);
        run("boatDestinations", TransitTests::boatDestinations);
        run("boatGoalOfStop", TransitTests::boatGoalOfStop);
        run("shipLandingRetryNeverClimbs", TransitTests::shipLandingRetryNeverClimbs);
        run("boatBackoffPoints", TransitTests::boatBackoffPoints);
        run("newChunksMarkAndQuery", TransitTests::newChunksMarkAndQuery);
        run("newChunksRoundTrip", TransitTests::newChunksRoundTrip);
        run("newChunksBadFile", TransitTests::newChunksBadFile);
        run("generatedRegistryRoundTrip", TransitTests::generatedRegistryRoundTrip);
        run("generatedPortalOnce", TransitTests::generatedPortalOnce);
        run("generatedForgetAndUnmark", TransitTests::generatedForgetAndUnmark);
        run("regeneratedKeepsSessionEntries", TransitTests::regeneratedKeepsSessionEntries);
        run("generatedCellOnce", TransitTests::generatedCellOnce);
        run("cellsAroundPlayer", TransitTests::cellsAroundPlayer);
        run("planAheadOffWhenDisabled", TransitTests::planAheadOffWhenDisabled);
        run("planAheadDedupe", TransitTests::planAheadDedupe);
        run("generatedBadFile", TransitTests::generatedBadFile);
        run("generatedBuiltSite", TransitTests::generatedBuiltSite);
        run("generatedRegistryActiveClamped", TransitTests::generatedRegistryActiveClamped);
        run("footprintFollowsAdvance", TransitTests::footprintFollowsAdvance);
        run("replannedOncePerSession", TransitTests::replannedOncePerSession);
        run("oldTerrainMovesBoat", TransitTests::oldTerrainMovesBoat);
        run("generatedRegistrySites", TransitTests::generatedRegistrySites);
        run("generatedRegistryOldFormat", TransitTests::generatedRegistryOldFormat);
        run("footprintChunksCover", TransitTests::footprintChunksCover);
        run("generatorAvailability", TransitTests::generatorAvailability);
        run("nearestUnbuiltEntry", TransitTests::nearestUnbuiltEntry);
        run("decideRules", TransitTests::decideRules);
        run("newChunksOldLoadAbandons", TransitTests::newChunksOldLoadAbandons);
        run("worldgenSettingDefault", TransitTests::worldgenSettingDefault);
        run("modRecipesMatchAssets", TransitTests::modRecipesMatchAssets);
        run("footprintContainsShapes", TransitTests::footprintContainsShapes);
        run("reloadedNewChunkRetriggers", TransitTests::reloadedNewChunkRetriggers);
        run("benchTabsFilter", TransitTests::benchTabsFilter);
        System.out.println("TransitTests: " + passed + " OK");
    }

    private static void biomeWoodLookup() {
        String birch = BiomeWood.speciesOf("Zone1_Tier1", "Forest_Birch");
        check(birch != null, "Forest_Birch has no species");
        eq(birch, BiomeWood.speciesOf("Zone1_Tier1", "Tile.Forest_Birch"));
        eq(birch, BiomeWood.speciesOf("Zone1_Tier1", "Custom.Forest_Birch"));
        eq("Palm", BiomeWood.speciesOf("Zone2_Shore", "Beach_Palm"));
        check(BiomeWood.speciesOf("Nowhere", "Nothing") == null, "unknown key");
        eq(BiomeWood.DEFAULT, BiomeWood.woodOf(null));
        eq(new BiomeWood.Wood("Birch", "Lightwood"), BiomeWood.woodOf("Birch"));
        eq(new BiomeWood.Wood("Azure", "Hardwood"), BiomeWood.woodOf("Azure"));
        List<String> families = List.of("Hardwood", "Softwood", "Lightwood", "Darkwood", "Redwood", "Goldenwood",
                "Drywood", "Greenwood", "Tropicalwood", "Blackwood", "Deadwood");
        for (String f : BiomeWoodTable.FAMILY.values()) {
            check(families.contains(f), "unknown family " + f);
        }
    }

    private static void chooseSpecies() {
        eq("Birch", BiomeWood.chooseSpecies(java.util.Arrays.asList(null, "Oak", "Birch", "Birch")));
        eq("Oak", BiomeWood.chooseSpecies(java.util.Arrays.asList("Oak", "Birch")));
        check(BiomeWood.chooseSpecies(java.util.Arrays.asList((String) null, null)) == null, "no sample");
    }

    private static void stoneRemap() {
        var sand = new BiomeStone.Stone("Sandstone");
        eq("Rock_Sandstone_Brick", BiomeStone.remap("Rock_Basalt_Brick", sand, x -> true));
        eq("Rock_Sandstone_Brick_Half", BiomeStone.remap("Rock_Basalt_Brick_Half", sand, x -> true));
        eq("Rock_Sandstone_Brick_Stairs", BiomeStone.remap("Rock_Basalt_Brick_Stairs", sand, x -> true));
        eq("*Rock_Sandstone_Brick_Stairs_State_Definitions_Corner_Left",
                BiomeStone.remap("*Rock_Basalt_Brick_Stairs_State_Definitions_Corner_Left", sand, x -> true));
        eq("Rock_Basalt_Brick", BiomeStone.remap("Rock_Basalt_Brick", sand, x -> false));
        eq("Rock_Stone_Brick", BiomeStone.remap("Rock_Stone_Brick", sand, x -> true));
        eq("Soil_Grass_Full", BiomeStone.remap("Soil_Grass_Full", sand, x -> true));
        eq("Rock_Basalt_Brick", BiomeStone.remap("Rock_Basalt_Brick", BiomeStone.DEFAULT, x -> true));
        eq("Rock_Basalt_Brick", BiomeStone.remap("Rock_Basalt_Brick", null, x -> true));
    }

    private static void stoneTableZones() {
        eq("Stone", BiomeStone.FAMILY.get("Zone1"));
        eq("Sandstone", BiomeStone.FAMILY.get("Zone2"));
        eq("Shale", BiomeStone.FAMILY.get("Zone3"));
        eq("Volcanic", BiomeStone.FAMILY.get("Zone4"));
        eq(4, BiomeStone.FAMILY.size());
        eq(new BiomeStone.Stone("Shale"), BiomeStone.stoneOfZone("Zone3_Tier2"));
        eq(BiomeStone.DEFAULT, BiomeStone.stoneOfZone("Nowhere"));
        eq(BiomeStone.DEFAULT, BiomeStone.stoneOfZone(null));
    }

    private static void stationWoodRemap() throws Exception {
        var birch = BiomeWood.woodOf("Birch");
        eq("Wood_Lightwood_Planks", BiomeWood.remapStation("Wood_Softwood_Planks", birch, x -> true));
        eq("Wood_Lightwood_Beam", BiomeWood.remapStation("Wood_Softwood_Beam", birch, x -> true));
        eq("Wood_Lightwood_Stairs", BiomeWood.remapStation("Wood_Softwood_Stairs", birch, x -> true));
        eq("Wood_Softwood_Planks", BiomeWood.remapStation("Wood_Softwood_Planks", birch, x -> false));
        eq("Wood_Softwood_Planks", BiomeWood.remapStation("Wood_Softwood_Planks", null, x -> true));
        // The shape of the real prefab: wood and stone renamed, nothing else touched, same cell count.
        StructureShape base = prefabShape("Balloon_Landing_zone.prefab.json");
        StructureShape out = BalloonRegistry.remapStationShape(base, birch, new BiomeStone.Stone("Sandstone"), x -> true);
        eq(base.cells().size(), out.cells().size());
        int wood = 0;
        int stone = 0;
        for (int i = 0; i < base.cells().size(); i++) {
            String a = base.cells().get(i).name();
            String b = out.cells().get(i).name();
            if (a.startsWith("Wood_Softwood_")) {
                check(b.startsWith("Wood_Lightwood_"), "softwood renamed " + a);
                wood++;
            } else if (a.contains("Rock_Basalt_Brick")) {
                check(b.contains("Rock_Sandstone_Brick"), "basalt renamed " + a);
                stone++;
            } else {
                eq(a, b);
            }
        }
        check(wood > 0 && stone > 0, "prefab has softwood and basalt");
    }

    private static void stationWoodAsh() {
        var ash = BiomeWood.woodOf("Ash");
        eq("Wood_Hardwood_Planks", BiomeWood.remapStation("Wood_Softwood_Planks", ash, x -> true));
        eq("Wood_Hardwood_Stairs", BiomeWood.remapStation("Wood_Softwood_Stairs", ash, x -> true));
        eq("Wood_Softwood_Planks", BiomeWood.remapStation("Wood_Softwood_Planks", null, x -> true));
        // shapeOf follows the registry: Ash renames, no wood keeps the prefab, and it matches the post-paste rewrite.
        StructureShape base = StructureShape.ofCells(java.util.List.of(
                new StructureShape.Cell(0, 0, 0, "Wood_Softwood_Planks"),
                new StructureShape.Cell(1, 0, 0, "Wood_Darkwood_Roof_Shallow")));
        StructureShape out = BalloonRegistry.remapStationShape(base, ash, null, x -> true);
        eq("Wood_Hardwood_Planks", out.cells().get(0).name());
        eq("Wood_Darkwood_Roof_Shallow", out.cells().get(1).name());
        StructureShape kept = BalloonRegistry.remapStationShape(base, null, null, x -> true);
        eq("Wood_Softwood_Planks", kept.cells().get(0).name());
    }

    private static void registryStoneRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("registry-stone");
        try {
            BalloonRegistry.init(dir);
            org.joml.Vector3i o = new org.joml.Vector3i(1, 2, 3);
            var rot = com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None;
            BalloonRegistry.addNamed("w", o, rot, Deployables.STATION_KIND, Ownership.of(java.util.UUID.randomUUID(), "Paul"));
            eq(null, BalloonRegistry.entriesOfNameForTest("w").get(0).stone());
            BalloonRegistry.setStoneNamed("w", o, rot, Deployables.STATION_KIND, "Shale");
            BalloonRegistry.setWoodNamed("w", o, rot, Deployables.STATION_KIND, "Birch");
            BalloonRegistry.init(dir);
            eq("Shale", BalloonRegistry.entriesOfNameForTest("w").get(0).stone());
            eq("Birch", BalloonRegistry.entriesOfNameForTest("w").get(0).wood());
        } finally {
            BalloonRegistry.init(Files.createTempDirectory("registry-stone-reset"));
        }
    }

    private static void woodRemap() {
        var w = new BiomeWood.Wood("Birch", "Lightwood");
        eq("Wood_Birch_Trunk", BiomeWood.remap("Wood_Ash_Trunk", w, x -> true));
        eq("Wood_Lightwood_Planks_Half", BiomeWood.remap("Wood_Hardwood_Planks_Half", w, x -> true));
        eq("Wood_Lightwood_Roof_Shallow", BiomeWood.remap("Wood_Hardwood_Roof_Shallow", w, x -> true));
        eq("*Wood_Lightwood_Stairs_State_Definitions_Corner",
                BiomeWood.remap("*Wood_Hardwood_Stairs_State_Definitions_Corner", w, x -> true));
        eq("Boat_Station_Gate", BiomeWood.remap("Boat_Station_Gate", w, x -> true));
        eq("Furniture_Village_Crate", BiomeWood.remap("Furniture_Village_Crate", w, x -> true));
        eq("Wood_Hardwood_Planks", BiomeWood.remap("Wood_Hardwood_Planks", BiomeWood.DEFAULT, x -> true));
        // Fallback: a variant that does not exist keeps its Hardwood name (shared by world, preview and registry).
        eq("Wood_Hardwood_Beam", BiomeWood.remap("Wood_Hardwood_Beam", w, x -> false));
        eq("Wood_Birch_Trunk", BiomeWood.remap("Wood_Ash_Trunk", w, x -> x.equals("Wood_Birch_Trunk")));
        eq("Wood_Ash_Trunk", BiomeWood.remap("Wood_Ash_Trunk", w, x -> false));
    }

    private static void pilotGenderAndName() {
        int f = 0, m = 0;
        for (long seed = 0; seed < 200; seed++) {
            PilotLook.Gender g = PilotLook.gender(seed);
            String n = PilotLook.firstName(seed);
            List<String> names = g == PilotLook.Gender.FEMALE ? PilotLook.FEMALE_NAMES : PilotLook.MALE_NAMES;
            check(names.contains(n), "name " + n + " not in the list of " + g);
            check(g == PilotLook.gender(seed) && n.equals(PilotLook.firstName(seed)), "unstable for seed " + seed);
            if (g == PilotLook.Gender.FEMALE) f++; else m++;
        }
        check(f >= 50 && m >= 50, "gender split " + f + "/" + m);
    }

    private static void pilotApplyGender() {
        long fs = -1, ms = -1;
        for (long seed = 0; seed < 50 && (fs < 0 || ms < 0); seed++) {
            if (PilotLook.gender(seed) == PilotLook.Gender.FEMALE) { if (fs < 0) fs = seed; } else if (ms < 0) ms = seed;
        }
        com.hypixel.hytale.protocol.PlayerSkin a = new com.hypixel.hytale.protocol.PlayerSkin();
        a.haircut = "Quiff.BrownDark"; a.face = "Face_Stubble"; a.facialHair = "Goatee.Brown";
        PilotLook.applyGender(a, fs);
        check(a.facialHair == null, "female keeps facial hair");
        check(a.haircut.endsWith(".BrownDark") && PilotLook.FEMALE_HAIR.contains(a.haircut.substring(0, a.haircut.indexOf('.'))), "female haircut " + a.haircut);
        check(PilotLook.FEMALE_FACES.contains(a.face), "female face " + a.face);
        com.hypixel.hytale.protocol.PlayerSkin b = new com.hypixel.hytale.protocol.PlayerSkin();
        b.haircut = "Quiff.BrownDark"; b.face = "Face_Stubble"; b.facialHair = "Goatee.Brown";
        PilotLook.applyGender(b, ms);
        check("Goatee.Brown".equals(b.facialHair), "male lost facial hair");
        check(b.haircut.endsWith(".BrownDark") && PilotLook.MALE_HAIR.contains(b.haircut.substring(0, b.haircut.indexOf('.'))), "male haircut " + b.haircut);
        check(PilotLook.MALE_FACES.contains(b.face), "male face " + b.face);
        check("Bun.BrownDark".equals(PilotLook.replaceId("Slickback.BrownDark.SlickbackClean", "Bun")), "variant kept");
        check(!PilotLook.MALE_HAIR.contains("Slickback"), "Slickback listed");
        check("Bun".equals(PilotLook.replaceId(null, "Bun")) && "Bun".equals(PilotLook.replaceId("Quiff", "Bun")), "bare id");
    }

    interface Test { void run() throws Exception; }

    private static void pilotOwnedByOther() {
        java.util.UUID mine = java.util.UUID.randomUUID(), theirs = java.util.UUID.randomUUID(), orphan = java.util.UUID.randomUUID();
        java.util.Set<java.util.UUID> others = java.util.Set.of(theirs);
        check(AirshipCrew.ownedByOther(theirs, others), "pilot of another entry is excluded");
        check(!AirshipCrew.ownedByOther(mine, others), "own pilot is kept");
        check(!AirshipCrew.ownedByOther(orphan, others), "orphan is kept");
        check(!AirshipCrew.ownedByOther(null, others), "no uuid is kept");
    }

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

    private static void gliderExitLocal() {
        Vector3f e = GliderJump.exitLocal(new Vector3f(1.5f, 9.5f, -6.5f), 5);
        eq(1.5f, e.x);
        eq(2.5f, e.y);
        eq(-6.5f, e.z);
    }

    private static void gliderJumpPhase() {
        eq(-1, GliderJump.phase(1000, 1000));
        eq(-1, GliderJump.phase(1000, 1999));
        eq(5, GliderJump.phase(1000, 2000));
        eq(5, GliderJump.phase(1000, 3499));
        eq(4, GliderJump.phase(1000, 3500));
        eq(1, GliderJump.phase(1000, 9499));
        eq(0, GliderJump.phase(1000, 9500));
        eq(0, GliderJump.phase(1000, 60000));
    }

    static void pendingDepartureSingle() {
        java.util.Map<Object, Runnable> pending = new java.util.HashMap<>();
        eq(true, ShipLadder.registerPending(pending, "ship", () -> {}));
        eq(false, ShipLadder.registerPending(pending, "ship", () -> {}));  // second departure during the same raise
        eq(1, pending.size());
        eq(ShipLadder.Defer.PROCEED, ShipLadder.deferResult(true, false));
        eq(ShipLadder.Defer.DEFERRED, ShipLadder.deferResult(false, true));
        eq(ShipLadder.Defer.ALREADY_PENDING, ShipLadder.deferResult(false, false));
    }

    static void contractMissing() {
        Map<String, Integer> have = new HashMap<>(Map.of("Ingredient_Bar_Gold", 51, "Ingredient_Bar_Silver", 5));
        Map<String, Integer> m = PilotContract.missing(have);
        eq(0, m.get("Ingredient_Bar_Gold"));
        eq(30, m.get("Ingredient_Bar_Silver"));
        eq(15, m.get("Ingredient_Life_Essence_Concentrated"));
        eq(3, m.size());
        check(PilotContract.missing(Map.of("Ingredient_Bar_Gold", 50, "Ingredient_Bar_Silver", 35,
                "Ingredient_Life_Essence_Concentrated", 15)).values().stream().allMatch(v -> v == 0), "exact price is enough");
    }

    private static void crewBsonRoundTrip() {
        CrewRecord c = new CrewRecord(java.util.UUID.fromString("00000000-0000-0000-0000-000000000042"), 77L, CrewRecord.Post.STOOL);
        eq(c, CrewRecord.fromBson(c.toBson()));
        eq("stool", c.toBson().getString("post").getValue());
        eq(null, CrewRecord.fromBson(new org.bson.BsonDocument("post", new org.bson.BsonString("deck"))));
    }

    private static void pilotSeatPoint() {
        Vector3d p = AirshipCrew.seatPoint(new Vector3d(10.5, 64.05, -3.5), 0.5);
        near(10.5, p.x);
        near(64.55, p.y);
        near(-3.5, p.z);
    }

    private static void crewPostAfterReload() {
        BalloonRegistry.Entry e = new BalloonRegistry.Entry("w", 1, 2, 3, Rotation.None, "airship", null, 0, false,
                List.of(), java.util.UUID.randomUUID(), null, false,
                new CrewRecord(java.util.UUID.randomUUID(), 5L, CrewRecord.Post.HELM));
        BalloonRegistry.Entry back = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(e));
        eq(e.crew(), back.crew());
        BalloonRegistry.Entry none = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(
                new BalloonRegistry.Entry("w", 1, 2, 3, Rotation.None, "airship", null)));
        eq(null, none.crew());
    }

    private static void autopilotLookAhead() {
        java.util.List<double[]> p = AirshipAutopilot.lookAheadPoints(0, 0, 1, 0, 5, 100);
        eq(8, p.size());
        for (int i = 0; i < p.size(); i++) {
            check(Math.abs(p.get(i)[0] - (1 + 2 * i)) < 1e-9 && Math.abs(p.get(i)[1]) < 1e-9, "point " + i);
        }
        p = AirshipAutopilot.lookAheadPoints(0, 0, 1, 0, 5, 4);
        eq(2, p.size());
        check(Math.abs(p.get(1)[0] - 3) < 1e-9, "second point at 3");
        p = AirshipAutopilot.lookAheadPoints(10, 20, 0, -1, 5, 100);
        check(Math.abs(p.get(0)[0] - 10) < 1e-9 && Math.abs(p.get(0)[1] - 19) < 1e-9, "direction respected");
    }

    private static void voyageStateBson() {
        java.util.UUID c = java.util.UUID.randomUUID();
        VoyageState v = new VoyageState("Port Nord", VoyagePlanner.Kind.STATION, 120.5, -33.0, 150.0, c);
        VoyageState r = VoyageState.fromBson(v.toBson());
        check(r != null, "read back");
        eq("Port Nord", r.destName);
        eq(VoyagePlanner.Kind.STATION, r.destKind);
        check(r.destX == 120.5 && r.destZ == -33.0 && r.cruiseY == 150.0, "numbers");
        eq(c, r.captain);
        check(VoyageState.fromBson(new org.bson.BsonDocument()) == null, "malformed gives null");
    }

    private static void departureRefusal() {
        eq(null, AirshipAutopilot.departureRefusalKey(true, 3, 15, true));
        eq(null, AirshipAutopilot.departureRefusalKey(true, 15, 15, true));
        eq("crew.voyage.noCaptain", AirshipAutopilot.departureRefusalKey(false, 3, 15, true));
        eq("airship.error.full", AirshipAutopilot.departureRefusalKey(true, 16, 15, true));
        eq("crew.voyage.handsFull", AirshipAutopilot.departureRefusalKey(true, 3, 15, false));
        // Order: captain first, then seats, then the hotbar.
        eq("crew.voyage.noCaptain", AirshipAutopilot.departureRefusalKey(false, 16, 15, false));
        eq("airship.error.full", AirshipAutopilot.departureRefusalKey(true, 16, 15, false));
    }

    private static void voyageIncludesBoatStations() {
        TransitNetwork.Station balloon = new TransitNetwork.Station("Gare", "w", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station boat = new TransitNetwork.Station("Port", "w", new Vector3i(400, 62, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        java.util.List<VoyagePlanner.Destination> d = VoyagePlanner.stationDestinations(java.util.List.of(balloon, boat), -300, 0);
        eq(2, d.size());
        VoyagePlanner.Destination port = d.stream().filter(x -> x.name().equals("Port")).findFirst().orElseThrow();
        eq(VoyagePlanner.Kind.STATION, port.kind());
        Vector3d pad = StationMarkerProvider.markerPoint(boat);
        check(Math.abs(Math.hypot(port.x() - pad.x, port.z() - pad.z) - VoyagePlanner.ARRIVAL_DISTANCE) < 1e-6, "arrival at 70 from the pier");
    }

    private static void captainMapPhase() {
        eq(-1, CaptainMap.phase(0, 499));
        eq(3, CaptainMap.phase(0, 500));
        eq(3, CaptainMap.phase(0, 1499));
        eq(2, CaptainMap.phase(0, 1500));
        eq(1, CaptainMap.phase(0, 2500));
        eq(1, CaptainMap.phase(0, 3499));
        eq(0, CaptainMap.phase(0, 3500));
    }

    private static void voyageCaptainRule() {
        java.util.UUID cap = java.util.UUID.randomUUID();
        BalloonFlight f = BalloonFlight.forTest(Deployables.BALLOON);
        check(!BalloonManager.isVoyageCaptain(f, cap), "no voyage");
        f.voyage = new BalloonVoyage("M", VoyagePlanner.Kind.MARKER, 0, 0, 100, cap, 0L);
        check(BalloonManager.isVoyageCaptain(f, cap) && !BalloonManager.isVoyageCaptain(f, java.util.UUID.randomUUID()), "captain");
        check(BalloonVoyage.captainAboard(java.util.List.of(cap), cap) && !BalloonVoyage.captainAboard(java.util.List.of(), cap)
                && !BalloonVoyage.captainAboard(java.util.List.of(cap), null), "captain watchdog");
    }

    private static void mapHolderRule() {
        java.util.UUID cap = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        java.util.Set<java.util.UUID> active = java.util.Set.of(cap);
        eq(false, CaptainMap.mustLoseMap(true, cap, active));
        eq(true, CaptainMap.mustLoseMap(true, other, active));
        eq(true, CaptainMap.mustLoseMap(true, cap, java.util.Set.of()));
        eq(false, CaptainMap.mustLoseMap(false, other, active));
    }

    private static void crewDialogueOptions() {
        eq(List.of(), AirshipVoyagePage.dialogueOptions(false, AirshipCrew.CrewState.AT_HELM));
        eq(List.of(AirshipVoyagePage.CrewOrder.VOYAGE, AirshipVoyagePage.CrewOrder.TAKE_HELM),
                AirshipVoyagePage.dialogueOptions(true, AirshipCrew.CrewState.AT_HELM));
        eq(List.of(AirshipVoyagePage.CrewOrder.RETURN_HELM), AirshipVoyagePage.dialogueOptions(true, AirshipCrew.CrewState.SEATED));
        eq(List.of(), AirshipVoyagePage.dialogueOptions(true, AirshipCrew.CrewState.WALKING));
        eq(List.of(), AirshipVoyagePage.dialogueOptions(true, AirshipCrew.CrewState.ABSENT));
    }

    private static void helmCellFree() {
        org.joml.Vector3d helm = new org.joml.Vector3d(0.5, 72.05, -9.5);
        check(AirshipCrew.helmCellFree(List.of(), helm), "empty deck");
        check(!AirshipCrew.helmCellFree(List.of(new org.joml.Vector3d(0.9, 72.0, -9.3)), helm), "player on helm");
        check(AirshipCrew.helmCellFree(List.of(new org.joml.Vector3d(2.0, 72.0, -9.5)), helm), "player beside");
        check(AirshipCrew.helmCellFree(List.of(new org.joml.Vector3d(0.5, 80.0, -9.5)), helm), "player far above");
    }

    private static void crewPostPoint() {
        BalloonRegistry.Entry none = new BalloonRegistry.Entry("w", 100, 64, 200, Rotation.None, "airship", null);
        org.joml.Vector3d p = AirshipCrew.postPoint(none, Deployables.crewOf(Deployables.AIRSHIP_KIND).helm());
        check(Math.abs(p.x - 100.5) < 1e-6 && Math.abs(p.y - 72.05) < 1e-6 && Math.abs(p.z - 190.5) < 1e-6, "helm None " + p);
        BalloonRegistry.Entry flip = new BalloonRegistry.Entry("w", 100, 64, 200, Rotation.OneEighty, "airship", null);
        org.joml.Vector3d q = AirshipCrew.postPoint(flip, Deployables.crewOf(Deployables.AIRSHIP_KIND).helm());
        check(Math.abs(q.x - 100.5) < 1e-6 && Math.abs(q.y - 72.05) < 1e-6 && Math.abs(q.z - 210.5) < 1e-6, "helm OneEighty " + q);
    }

    private static void near(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.01) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void tentContentsRoundTrip() {
        BalloonCargo cargo = new BalloonCargo(List.of(new BalloonCargo.Entry(new Vector3i(1, 0, 3),
                "Furniture_Kweebec_Chest_Small", (short) 18, new com.hypixel.hytale.server.core.inventory.ItemStack[18])));
        TentContents t = new TentContents(AirshipBenchContents.fromBson(null), cargo);
        org.bson.BsonDocument d = t.toBson("tent");
        eq(1, d.getInt32("v").getValue());
        eq("tent", d.getString("kind").getValue());
        TentContents back = TentContents.fromBson(d, "tent");
        check(back != null, "relu");
        eq(d, back.toBson("tent"));
    }

    private static void tentContentsMissing() {
        eq(null, TentContents.fromBson(null, "tent"));
    }

    /** The guard lists the expected cells that gave no component, in order. */
    private static void componentGuard() {
        List<Vector3i> expected = List.of(new Vector3i(0, 1, 2), new Vector3i(3, 4, 5), new Vector3i(6, 7, 8));
        check(StructureComponents.missing(expected, Set.copyOf(expected)).isEmpty(), "all found: nothing missing");
        List<Vector3i> missing = StructureComponents.missing(expected, Set.of(new Vector3i(3, 4, 5)));
        check(missing.equals(List.of(new Vector3i(0, 1, 2), new Vector3i(6, 7, 8))), "missing cells: " + missing);
        check(StructureComponents.missing(List.of(), Set.of()).isEmpty(), "no expected cell");
    }

    private static void tentContentsVersion() {
        TentContents t = new TentContents(AirshipBenchContents.fromBson(null), new BalloonCargo(List.of()));
        org.bson.BsonDocument d = t.toBson("tent");
        eq(null, TentContents.fromBson(d, "tent_big"));
        d.put("v", new org.bson.BsonInt32(2));
        eq(null, TentContents.fromBson(d, "tent"));
    }

    static void campRespawnForget() {
        org.joml.Vector3i a = new org.joml.Vector3i(1, 2, 3);
        org.joml.Vector3i b = new org.joml.Vector3i(4, 5, 6);
        com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[] pts = {
                new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData(a, new org.joml.Vector3d(1, 2, 3), "A", 0f),
                new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData(b, new org.joml.Vector3d(4, 5, 6), "B", 0f)};
        com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[] left = CampRespawn.withoutBlock(pts, a);
        eq(1, left.length);
        eq("B", left[0].getName());
        eq(2, CampRespawn.withoutBlock(pts, new org.joml.Vector3i(9, 9, 9)).length);
        eq(null, CampRespawn.withoutBlock(null, a));
        // Not a recorded camp point: no menu, nothing forgotten.
        eq(false, RespawnPointMarkerProvider.forgettable(java.util.UUID.randomUUID(), "w", a));
        eq(false, CampRespawn.forget(java.util.UUID.randomUUID(), "w", a));
        eq("bed3", RespawnPointMarkerProvider.markerId("bed", 3));
        eq("orbishorizon camping forget 1 2 3", RespawnPointMarkerProvider.forgetCommand(a));
    }

    static void stationShareCommand() {
        eq("orbishorizon map sharestation 1 -2 3", StationMarkerProvider.shareCommand(1, -2, 3));
    }

    static void commandRights() throws Exception {
        java.util.Map<String, Boolean> open = new java.util.TreeMap<>();
        commandRights(new OrbisHorizonCommand(), "", open);
        // A command neither open nor in a group gets a generated permission that only ops hold.
        for (java.util.Map.Entry<String, Boolean> e : open.entrySet()) {
            check(e.getValue() != null, "no rights set: " + e.getKey());
        }
        eq(true, open.get("orbishorizon camping forget"));
        eq(true, open.get("orbishorizon camping pack"));
        eq(true, open.get("orbishorizon map share"));
        eq(true, open.get("orbishorizon map sharestation"));
        eq(false, open.get("orbishorizon camping back"));
    }

    private static void commandRights(com.hypixel.hytale.server.core.command.system.AbstractCommand c, String prefix,
            java.util.Map<String, Boolean> open) throws Exception {
        java.lang.reflect.Field f = com.hypixel.hytale.server.core.command.system.AbstractCommand.class.getDeclaredField("openToEveryone");
        f.setAccessible(true);
        String path = prefix + c.getName();
        open.put(path, f.getBoolean(c) ? Boolean.TRUE : c.getPermissionGroups() != null ? Boolean.FALSE : null);
        for (com.hypixel.hytale.server.core.command.system.AbstractCommand sub : c.getSubCommands().values()) {
            commandRights(sub, path + " ", open);
        }
    }

    static void campRespawnGuard() {
        java.util.function.IntFunction<com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData> mk =
                a -> new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData(new Vector3i(a, 0, 0), new org.joml.Vector3d(a, 0, 0), "p" + a, 0f);
        var home = mk.apply(1);
        var campPt = mk.apply(50);
        var other = mk.apply(70);
        var newCamp = mk.apply(90);
        CampRespawn.Point camp = new CampRespawn.Point(java.util.UUID.randomUUID(), "w", new Vector3i(50, 0, 0),
                new org.joml.Vector3d(50, 0, 0), "Camp", 0f);
        java.util.function.Predicate<Vector3i> isCamp = b -> b.x == 50 || b.x == 90;
        java.util.function.Predicate<Vector3i> there = b -> b.x != 70; // bed 70 was broken
        // Claiming a game bed removed the camp point: camp put back.
        var back = CampRespawn.pointsToRestore(new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{campPt, other}, new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{home, other}, camp, isCamp, there);
        eq(1, back.size());
        eq("Camp", back.get(0).getName());
        // Claiming a camp bed removed home and a (broken) bed 70: home put back, 70 not.
        var prev = new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{home, other};
        back = CampRespawn.pointsToRestore(prev, new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{newCamp}, null, isCamp, there);
        eq(1, back.size());
        eq("p1", back.get(0).getName());
        // No camp claim: a vanished game point is the game's business.
        var cur = new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{mk.apply(3)};
        eq(0, CampRespawn.pointsToRestore(prev, cur, null, isCamp, there).size());
        eq(0, CampRespawn.pointsToRestore(null, cur, null, isCamp, there).size());
        eq(0, CampRespawn.pointsToRestore(new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{campPt}, new com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData[]{campPt}, camp, isCamp, there).size());
    }

    static void campRespawnShouldRescue() {
        eq(false, CampRespawn.shouldRescue(1.0, Double.POSITIVE_INFINITY));
        eq(true, CampRespawn.shouldRescue(30.0, Double.POSITIVE_INFINITY));
        eq(false, CampRespawn.shouldRescue(30.0, 1.5));
        eq(true, CampRespawn.shouldRescue(30.0, 20.0));
        eq(false, CampRespawn.shouldRescue(2.0, 50.0));
    }

    static void campRespawnFreeSpot() {
        Vector3i c = new Vector3i(0, 64, 0);
        eq(c, CampRespawn.freeSpotNear(c, 8, p -> true));
        eq(new Vector3i(1, 64, 0), CampRespawn.freeSpotNear(c, 8, p -> p.x == 1 && p.y == 64 && p.z == 0));
        eq(null, CampRespawn.freeSpotNear(c, 8, p -> false));
        eq(null, CampRespawn.freeSpotNear(c, 2, p -> p.x == 5));
        // Same distance: lowest y, then x, then z.
        eq(new Vector3i(0, 63, 0), CampRespawn.freeSpotNear(c, 2, p -> p.x == 0 && p.z == 0 && p.y != 64));
    }

    static void campRespawnOwnersOfBed() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Map<UUID, Vector3i> pts = Map.of(a, new Vector3i(1, 0, 0), b, new Vector3i(9, 0, 9));
        eq(Set.of(a), CampRespawn.ownersOfBed(pts, new Vector3i(1, 0, 0)));
    }

    static void campRespawnFileRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("camp-respawn");
        UUID a = UUID.randomUUID();
        CampRespawn.Point p = new CampRespawn.Point(a, "w", new Vector3i(1, 2, 3), new org.joml.Vector3d(1.5, 2.1, 3.5), "Tent", 0.5f);
        eq(List.of(p), CampRespawn.fromBson(CampRespawn.toBson(List.of(p))));
        Files.writeString(dir.resolve("camp-respawn.json"), "{ not json");
        CampRespawn.load(dir);
        check(Files.exists(dir.resolve("camp-respawn.json.bad")), "corrupt file renamed .bad");
        check(!Files.exists(dir.resolve("camp-respawn.json")), "corrupt file moved away");
    }

    private static void balloonNoCrewUnchanged() {
        // Deployables.BALLOON.shape() needs the game assets: the four stools of the prefab, built by hand.
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(-1, 9, 7, "Furniture_Crude_Stool"), new StructureShape.Cell(1, 9, 7, "Furniture_Crude_Stool"),
                new StructureShape.Cell(-1, 9, 9, "Furniture_Crude_Stool"), new StructureShape.Cell(1, 9, 9, "Furniture_Crude_Stool")));
        eq(4, BalloonManager.freeSeats(s, null).size());
        eq(3, BalloonManager.freeSeats(s, new int[]{1, 9, 9}).size());
        check(AirshipCrew.helmRefusalKey(null, AirshipCrew.CrewState.ABSENT) == null, "no pilot: chain never refused");
    }

    private static void balloonResumeKeepsCrew() {
        CrewRecord crew = new CrewRecord(java.util.UUID.randomUUID(), 42L, CrewRecord.Post.STOOL);
        org.bson.BsonDocument d = BalloonResume.crewToBson(crew);
        CrewRecord back = BalloonResume.crewFromBson(d);
        check(back != null && back.uuid().equals(crew.uuid()) && back.post() == CrewRecord.Post.STOOL, "crew round trip");
        check(BalloonResume.crewFromBson(null) == null, "absent key");
    }

    /** Bench tabs: a category whose recipes are all disabled is dropped, anything else is kept. */
    static void benchTabsFilter() {
        java.util.function.Predicate<String> off = Set.of("a", "b", "c")::contains;
        String json = "{\"title\":\"Bench\",\"categories\":["
                + "{\"id\":\"Camp\",\"craftableRecipes\":[\"a\",\"b\"]},"
                + "{\"id\":\"Half\",\"craftableRecipes\":[\"c\",\"x\"]},"
                + "{\"id\":\"Vanilla\",\"craftableRecipes\":[\"x\"]},"
                + "{\"id\":\"Empty\",\"craftableRecipes\":[]},"
                + "{\"id\":\"Missing\"}],\"extra\":7}";
        com.google.gson.JsonObject out = com.google.gson.JsonParser.parseString(BenchTabs.filterTabs(json, off)).getAsJsonObject();
        java.util.List<String> ids = new ArrayList<>();
        for (com.google.gson.JsonElement e : out.getAsJsonArray("categories")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        eq(List.of("Half", "Vanilla", "Empty", "Missing"), ids);
        eq("Bench", out.get("title").getAsString());
        eq(7, out.get("extra").getAsInt());
        String none = "{\"title\":\"x\"}";
        check(BenchTabs.filterTabs(none, off) == none, "no categories: same instance");
        String keep = "{\"categories\":[{\"id\":\"V\",\"craftableRecipes\":[\"x\"]}]}";
        check(BenchTabs.filterTabs(keep, off) == keep, "nothing removed: same instance");
        String bad = "{\"categories\":[";
        check(BenchTabs.filterTabs(bad, off) == bad, "malformed json unchanged");
        check(BenchTabs.filterTabs(null, off) == null, "null unchanged");
    }

    private static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    /** Random seat view yaw: always in [0, 2 pi), two draws differ (seeded generator). */
    static void randomSeatYawRange() {
        java.util.random.RandomGenerator rng = new java.util.Random(42);
        float a = PassengerCamera.randomSeatYaw(rng);
        float b = PassengerCamera.randomSeatYaw(rng);
        eq(true, a >= 0 && a < 2 * Math.PI && b >= 0 && b < 2 * Math.PI);
        eq(true, a != b);
        for (int i = 0; i < 1000; i++) {
            float y = PassengerCamera.randomSeatYaw(rng);
            eq(true, y >= 0 && y < (float) (2 * Math.PI) + 1e-6f);
        }
    }

    /** ONLY, HIDE and HIDE_SET visibility rules. */
    static void viewFilterRules() {
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID(), c = java.util.UUID.randomUUID();
        ViewFilter.Rule only = new ViewFilter.Rule(a, ViewFilter.Mode.ONLY);
        eq(false, ViewFilter.hiddenFrom(only, a));
        eq(true, ViewFilter.hiddenFrom(only, b));
        ViewFilter.Rule hide = new ViewFilter.Rule(a, ViewFilter.Mode.HIDE);
        eq(true, ViewFilter.hiddenFrom(hide, a));
        eq(false, ViewFilter.hiddenFrom(hide, b));
        ViewFilter.Rule set = new ViewFilter.Rule(null, ViewFilter.Mode.HIDE_SET, java.util.Set.of(a, b));
        eq(true, ViewFilter.hiddenFrom(set, a));
        eq(true, ViewFilter.hiddenFrom(set, b));
        eq(false, ViewFilter.hiddenFrom(set, c));
    }

    /** cage left on the ground, balloon home at y 100 with the cage lowered by 12 (target 88). */
    /** external view framing, camera opposite to the given direction, above the aim point, looking down at it. */
    static void externalView() {
        PassengerCamera.ExternalView v = PassengerCamera.externalView(0, 3, 40, 1.2, 0.25);
        check(Math.abs(v.x()) < 1e-4 && Math.abs(v.z() - 48f) < 1e-3, "camera on the direction side at factor * size");
        check(Math.abs(v.y() - 12f) < 1e-3, "height = heightFactor * distance");
        check(Math.abs(v.yaw()) < 1e-4, "yaw looks from +z toward the target (atan2(wx, wz))");
        check(v.pitch() < 0 && Math.abs(v.pitch() - (float) Math.atan2(-12.0, 48.0)) < 1e-4, "pitch looks down");
        PassengerCamera.ExternalView w = PassengerCamera.externalView(-1, -1, 10, 2, 0);
        check(Math.abs(Math.hypot(w.x(), w.z()) - 20) < 1e-3, "horizontal distance = factor * size");
        check(w.x() < 0 && w.z() < 0 && Math.abs(w.pitch()) < 1e-6, "same side as the direction, level at height 0");
    }

    /** Airship external view: behind the ship is opposite to its bow (-sin t, -cos t). */
    static void viewResend() {
        PassengerCamera.ExternalView a = PassengerCamera.externalView(0, 1, 10, 1, 0.25, 0);
        PassengerCamera.ExternalView b = PassengerCamera.externalView(0, 1, 10, 1, 0.25, 8);
        check(Math.abs(b.z() - a.z() - 8) < 1e-3 && Math.abs(b.y() - 0.25 * 18) < 1e-3, "extra recoil keeps the height ratio");
        check(!PassengerCamera.shouldResend(true, 5, 90), "no resend while turning");
        check(!PassengerCamera.shouldResend(false, 1, 90), "no resend before enough travel");
        check(!PassengerCamera.shouldResend(false, 5, 5), "no resend for a small direction change");
        check(PassengerCamera.shouldResend(false, 5, 30), "resend once moving with a changed direction");
    }

    static void behindHeading() {
        double[] b = PassengerCamera.behindHeading(0);
        check(Math.abs(b[0]) < 1e-9 && Math.abs(b[1] - 1) < 1e-9, "heading 0: bow toward -z, behind toward +z");
        double[] c = PassengerCamera.behindHeading(Math.PI / 2);
        check(Math.abs(c[0] - 1) < 1e-9 && Math.abs(c[1]) < 1e-9, "heading 90 degrees: behind toward +x");
        double theta = 1.0;
        double[] d = PassengerCamera.behindHeading(theta);
        check(Math.abs(d[0] + (-Math.sin(theta))) < 1e-9 && Math.abs(d[1] + (-Math.cos(theta))) < 1e-9, "behind = minus bow");
    }

    static void groundCageRemaining() {
        eq(0, TransportCage.remainingDescent(88, 100, 12));
        eq(5, TransportCage.remainingDescent(93, 100, 12));
        eq(12, TransportCage.remainingDescent(100, 100, 12));
        eq(0, TransportCage.remainingDescent(80, 100, 12));
        eq(Deployables.TRANSPORT_MAX_DESCENT, TransportCage.remainingDescent(200, 100, 12));
    }

    /** Seat moving at 5.2 b/s, player 5.3 blocks behind: one snap, then velocity only (no teleport every pause). */
    static void followNoTeleportLoop() {
        double dt = 0.033, speed = 5.2;
        org.joml.Vector3d seat = new org.joml.Vector3d(0, 0, 0);
        org.joml.Vector3d seatVel = new org.joml.Vector3d(speed, 0, 0);
        org.joml.Vector3d player = new org.joml.Vector3d(-5.3, 0, 0);
        long now = 0, pauseUntil = 0;
        int snaps = 0, vels = 0;
        org.joml.Vector3d landing = null;
        long landAt = 0;
        for (int i = 0; i < 40; i++) {
            now = (long) (i * dt * 1000);
            seat.add(speed * dt, 0, 0);
            if (landing != null && now >= landAt) {
                player.set(landing);
                landing = null;
            }
            BalloonManager.FollowAction a = BalloonManager.followAction(seat, seatVel, player, now < pauseUntil, BalloonManager.FOLLOW_SNAP,
                    BalloonManager.FOLLOW_ARRIVAL_LEAD_S, BalloonManager.FOLLOW_GAIN, BalloonManager.FOLLOW_MAX_CORRECTION);
            if (a.snap()) {
                snaps++;
                pauseUntil = now + 250;
                landing = a.target();
                landAt = now + 33;
            } else if (a.velocity().length() > 0.02) {
                vels++;
            }
            if (!a.snap()) {
                player.add(a.velocity().x * dt, a.velocity().y * dt, a.velocity().z * dt);
            }
        }
        eq(1, snaps);
        eq(true, vels > 30);
        // static seat: no velocity during the pause, unchanged
        BalloonManager.FollowAction st = BalloonManager.followAction(new org.joml.Vector3d(), new org.joml.Vector3d(), new org.joml.Vector3d(), true, BalloonManager.FOLLOW_SNAP,
                BalloonManager.FOLLOW_ARRIVAL_LEAD_S, BalloonManager.FOLLOW_GAIN, BalloonManager.FOLLOW_MAX_CORRECTION);
        eq(false, st.snap());
        eq(0.0, st.velocity().length());
    }

    static void cruiseAndHolding() {
        eq(110.0, TransitAutopilot.cruiseY(64, 80));
        eq(110.0, TransitAutopilot.cruiseY(80, 64));
        org.joml.Vector3d pad = new org.joml.Vector3d(100.5, 70, 200.5);
        org.joml.Vector3d from = new org.joml.Vector3d(100.5 + 400, 110, 200.5);
        org.joml.Vector3d h0 = TransitAutopilot.holdingPoint(pad, from, 0);
        org.joml.Vector3d h1 = TransitAutopilot.holdingPoint(pad, from, 1);
        eq(30.0, Math.round(Math.hypot(h0.x - pad.x, h0.z - pad.z) * 1000) / 1000.0);
        eq(true, h0.x > pad.x); // on the side it comes from
        eq(110.0, h0.y);
        eq(6.0, Math.round(h0.distance(h1) * 1000) / 1000.0);
        List<org.joml.Vector3d> drops = TransitAutopilot.dropPoints(new Vector3i(10, 64, 20), Rotation.None, 12);
        eq(12, drops.size());
        eq(12, new java.util.HashSet<>(drops).size());
        eq(23.5, drops.get(0).x);
        eq(64 + 7.1f, (float) drops.get(0).y);
        eq(18.5, drops.get(0).z);
        List<org.joml.Vector3d> rot = TransitAutopilot.dropPoints(new Vector3i(10, 64, 20), Rotation.Ninety, 14);
        eq(14, rot.size());
        eq(rot.get(0), rot.get(12)); // circular over the list
        eq(true, !rot.get(0).equals(drops.get(0)));
    }

    static void transitViewYaw() {
        double north = 0; // travel towards -z: yaw 0
        eq(true, Math.abs(PassengerCamera.towardYaw(0, -100, 0, 0) - north) < 1e-6); // seat at the centre: straight ahead
        eq(true, Math.abs(PassengerCamera.towardYaw(0, -100, 0, -2) - north) < 1e-6); // front seat: straight ahead
        double east = Math.atan2(-1, 0); // outward to +x
        double max = Math.toRadians(PassengerCamera.TRANSIT_VIEW_SPREAD_DEG);
        eq(true, Math.abs(PassengerCamera.towardYaw(0, -100, 2, 0) - (north + Math.signum(east) * max)) < 1e-6); // side seat: spread capped
        eq(true, Math.abs(PassengerCamera.towardYaw(0, -100, 0, 2) - north) < max + 1e-6); // rear seat: never more than the spread
        // Inward: a seat at +z of the centre looks towards -z (yaw 0), a seat on the centre looks along forward.
        org.joml.Vector3d c = new org.joml.Vector3d(0, 0, 0);
        eq(true, Math.abs(PassengerCamera.inwardYaw(new org.joml.Vector3d(0, 0, 2), c, new org.joml.Vector3f(1, 0, 0))) < 1e-6);
        eq(true, Math.abs(PassengerCamera.inwardYaw(c, c, new org.joml.Vector3f(0, 0, -1))) < 1e-6);
    }

    static void compassAndTrip() {
        eq("n", DestinationPage.compass(0, -100));
        eq("e", DestinationPage.compass(100, 0));
        eq("s", DestinationPage.compass(0, 100));
        eq("w", DestinationPage.compass(-100, 0));
        eq("ne", DestinationPage.compass(70, -70));
        eq("sw", DestinationPage.compass(-70, 70));
        // Same height: 30 up, 300 across, 30 down at 5 blocks/s, plus the wait on the pad.
        double t = DestinationPage.tripSeconds(70, 70, 300, 5, 5);
        eq(true, Math.abs(t - (6 + 60 + 6 + TransitConstants.DROP_WAIT_SECONDS)) < 1e-9);
    }

    static void rerouteTarget() {
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station b = new TransitNetwork.Station("B", "w", new Vector3i(500, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station c = new TransitNetwork.Station("C", "w", new Vector3i(100, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        org.joml.Vector3d here = new org.joml.Vector3d(450, 110, 0);
        eq(a, TransitAutopilot.rerouteTarget(List.of(a, b), "A", here)); // departure still there: back to it
        eq(b, TransitAutopilot.rerouteTarget(List.of(b, c), "A", here)); // departure removed: nearest
        eq(c, TransitAutopilot.rerouteTarget(List.of(c), "A", here));
        eq(null, TransitAutopilot.rerouteTarget(List.of(), "A", here)); // nothing left: the ground
    }

    static void leaveSpot() {
        eq(AirshipManager.LeaveSpot.DECK, AirshipManager.leaveSpot(true, false));
        eq(AirshipManager.LeaveSpot.GROUND, AirshipManager.leaveSpot(true, true));
        eq(AirshipManager.LeaveSpot.GROUND, AirshipManager.leaveSpot(false, false));
    }

    static void returnStation() {
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station b = new TransitNetwork.Station("B", "w", new Vector3i(500, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station c = new TransitNetwork.Station("C", "w", new Vector3i(900, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        org.joml.Vector3d here = new org.joml.Vector3d(480, 110, 0); // right above B, the destination
        eq(a, TransitAutopilot.returnStation(List.of(a, b, c), "A", "B", here)); // departure still there: back to it
        eq(c, TransitAutopilot.returnStation(List.of(b, c), "A", "B", here)); // departure removed: nearest other than the destination
        eq(b, TransitAutopilot.returnStation(List.of(b), "A", "B", here)); // the destination only if it is the last one
        eq(null, TransitAutopilot.returnStation(List.of(), "A", "B", here)); // nothing left: the ground
    }

    static void shuttleReturnStation() {
        TransitNetwork.Station a = boatStation("A", 0);
        TransitNetwork.Station b = boatStation("B", 500);
        TransitNetwork.Station c = boatStation("C", 900);
        TransitNetwork.Station balloon = new TransitNetwork.Station("N", "w", new Vector3i(470, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND); // a balloon station right next to the destination
        org.joml.Vector3d here = new org.joml.Vector3d(480, 63, 0);
        eq(a, BoatQuay.returnStation(List.of(a, b, c, balloon), "A", "B", here)); // departure still there: back to it
        eq(c, BoatQuay.returnStation(List.of(b, c, balloon), "A", "B", here)); // departure removed: nearest boat station but the destination
        eq(b, BoatQuay.returnStation(List.of(b, balloon), "A", "B", here)); // the destination only if it is the last boat station
        eq(null, BoatQuay.returnStation(List.of(balloon), "A", "B", here)); // balloon stations never count: the surface
        eq(null, BoatQuay.returnStation(List.of(), "A", "B", here));
    }

    static void boatApproachGiveUp() {
        BoatQuay.Quay q = new BoatQuay.Quay();
        for (int i = 0; i < BoatQuay.MAX_APPROACH_TRIES - 1; i++) {
            q.approach();
            q.approachFailed(1000L * i, 30_000L);
            check(!q.shouldGiveUp(), "still trying after " + (i + 1) + " failure(s)");
        }
        q.approach();
        q.approachFailed(100_000L, 30_000L);
        check(q.shouldGiveUp(), "gives up after the cap");
        q.docked(200_000L); // a successful docking resets the count
        check(!q.shouldGiveUp(), "docking resets the failures");
    }

    static void shuttleRedirectTargets() {
        TransitNetwork.Station a = boatStation("A", 0);
        TransitNetwork.Station b = boatStation("B", 500);
        TransitNetwork.Station c = boatStation("C", 900);
        TransitNetwork.Station d = boatStation("D", 600);
        TransitNetwork.Station balloon = new TransitNetwork.Station("N", "w", new Vector3i(470, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND);
        org.joml.Vector3d here = new org.joml.Vector3d(480, 63, 0);
        // departure first, then the others from the nearest, never the removed one nor a balloon station
        eq(List.of(a, d, c), BoatQuay.redirectTargets(List.of(c, a, b, d, balloon), "A", "B", here));
        eq(List.of(d, c), BoatQuay.redirectTargets(List.of(c, b, d), "A", "B", here)); // departure gone: nearest first
        eq(List.of(), BoatQuay.redirectTargets(List.of(b, balloon), "A", "B", here)); // none left: the rescue
    }

    private static TransitNetwork.Station boatStation(String name, int x) {
        return new TransitNetwork.Station(name, "w", new Vector3i(x, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(),
                Deployables.BOAT_STATION_KIND);
    }

    static void holdingPoints() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        eq(0, TransitAutopilot.holdIndexFor(List.of(a, b), a));
        eq(1, TransitAutopilot.holdIndexFor(List.of(a, b), b));
        eq(2, TransitAutopilot.holdIndexFor(List.of(a, b), c)); // not registered yet: behind the others
        TransitNetwork.Station s = new TransitNetwork.Station("Port Sud", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        eq(0, StationQueue.registerArrival(s, a));
        eq(1, StationQueue.registerArrival(s, b));
        eq(0, StationQueue.registerArrival(s, a)); // idempotent
        org.joml.Vector3d pad = new org.joml.Vector3d(0.5, 70, 0.5);
        org.joml.Vector3d from = new org.joml.Vector3d(300, 110, 0);
        eq(false, TransitAutopilot.holdingPoint(pad, from, StationQueue.holdOrder(s, a))
                .equals(TransitAutopilot.holdingPoint(pad, from, StationQueue.holdOrder(s, b))));
        eq(false, StationQueue.tryBeginArrival(s, b)); // a registered first and goes first
        eq(true, StationQueue.tryBeginArrival(s, a));
        eq(StationQueue.QuayState.ARRIVING, StationQueue.state(s));
        eq(false, StationQueue.tryBeginArrival(s, b)); // quay busy
        eq(0, StationQueue.holdOrder(s, b));
        StationQueue.endArrival(s, a);
        eq(StationQueue.QuayState.FREE, StationQueue.state(s));
        eq(true, StationQueue.tryBeginArrival(s, b));
        StationQueue.endArrival(s, b);
        eq(false, StationQueue.arrivalPending(s));
    }

    static void queueRules() {
        List<String> q = new java.util.ArrayList<>();
        eq(StationQueue.EnqueueResult.ADDED, StationQueue.addTo(q, "Port Nord", "A"));
        eq(StationQueue.EnqueueResult.ALREADY_QUEUED, StationQueue.addTo(q, "Port Nord", "a "));
        eq(1, StationQueue.positionOf(q, "A"));
        for (String d : List.of("B", "C", "D", "E")) {
            eq(StationQueue.EnqueueResult.ADDED, StationQueue.addTo(q, "Port Nord", d));
        }
        eq(5, StationQueue.positionOf(q, "e"));
        eq(StationQueue.EnqueueResult.FULL, StationQueue.addTo(q, "Port Nord", "F"));
        eq(0, StationQueue.positionOf(q, "F"));
        eq(5, q.size());
        eq(StationQueue.EnqueueResult.SELF, StationQueue.addTo(new java.util.ArrayList<>(), "Port Nord", " port nord"));
    }

    static void reservationIsExclusive() {
        TransitNetwork.Station s = new TransitNetwork.Station("Port Nord", "w", new org.joml.Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        eq(true, StationMaster.reserve(s, a));
        eq(false, StationMaster.reserve(s, b));
        eq(true, StationMaster.reserve(s, a));
        StationMaster.release(s, b); // not the holder: no effect
        eq(false, StationMaster.reserve(s, b));
        StationMaster.release(s, a);
        eq(true, StationMaster.reserve(s, b));
        StationMaster.release(s, b);
    }

    static void passengerProtectionModePersists() throws Exception {
        Path dir = Files.createTempDirectory("passenger-mode");
        try {
            PassengerProtection.load(dir);
            eq(PassengerProtection.Mode.FULL, PassengerProtection.mode());
            PassengerProtection.setMode(PassengerProtection.Mode.DAMAGE);
            java.util.UUID a = java.util.UUID.randomUUID();
            PassengerProtection.mark(a);
            PassengerProtection.load(dir);
            eq(PassengerProtection.Mode.DAMAGE, PassengerProtection.mode());
            eq(true, PassengerProtection.isMarked(a));
            Files.writeString(dir.resolve("protected.json"), "{\"players\": [], \"mode\": \"BOGUS\"}");
            PassengerProtection.load(dir);
            eq(PassengerProtection.Mode.FULL, PassengerProtection.mode());
            PassengerProtection.setMode(PassengerProtection.Mode.OFF);
            Files.delete(dir.resolve("protected.json"));
            PassengerProtection.load(dir);
            eq(PassengerProtection.Mode.FULL, PassengerProtection.mode());
        } finally {
            PassengerProtection.setMode(PassengerProtection.Mode.FULL);
            deleteAll(dir);
        }
    }

    static void protectedFileRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("passenger-protection");
        try {
            java.util.UUID a = java.util.UUID.randomUUID();
            java.util.UUID b = java.util.UUID.randomUUID();
            PassengerProtection.load(dir);
            eq(false, PassengerProtection.isMarked(a));
            PassengerProtection.mark(a);
            PassengerProtection.mark(b);
            PassengerProtection.unmark(a);
            PassengerProtection.load(dir);
            eq(false, PassengerProtection.isMarked(a));
            eq(true, PassengerProtection.isMarked(b));
            Files.writeString(dir.resolve("protected.json"), "{garbage");
            PassengerProtection.load(dir);
            eq(false, PassengerProtection.isMarked(b));
            eq("{garbage", Files.readString(dir.resolve("protected.json.bad")));
        } finally {
            deleteAll(dir);
        }
    }

    static void registryWoodRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("registry-wood");
        try {
            BalloonRegistry.init(dir);
            org.joml.Vector3i o = new org.joml.Vector3i(1, 2, 3);
            com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rot =
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None;
            BalloonRegistry.addNamed("w", o, rot, Deployables.BOAT_STATION_KIND, Ownership.of(java.util.UUID.randomUUID(), "Paul"));
            eq(null, BalloonRegistry.entriesOfNameForTest("w").get(0).wood());
            BalloonRegistry.setWoodNamed("w", o, rot, Deployables.BOAT_STATION_KIND, "Birch");
            BalloonRegistry.init(dir);
            eq("Birch", BalloonRegistry.entriesOfNameForTest("w").get(0).wood());
            // The wood does not count in equality.
            BalloonRegistry.Entry a = new BalloonRegistry.Entry("w", 1, 2, 3, rot, "boat_station", null);
            eq(true, a.equals(BalloonRegistry.entriesOfNameForTest("w").get(0)));
        } finally {
            BalloonRegistry.init(Files.createTempDirectory("registry-wood-reset"));
        }
    }

    static void boatStationShapeOfWood() {
        StructureShape base = StructureShape.ofCells(java.util.List.of(
                new StructureShape.Cell(0, 0, 0, "Wood_Ash_Trunk"),
                new StructureShape.Cell(1, 0, 0, "Wood_Hardwood_Planks"),
                new StructureShape.Cell(2, 0, 0, "Rock_Stone")));
        StructureShape birch = BalloonRegistry.remapShape(base, BiomeWood.woodOf("Birch"), x -> true);
        eq("Wood_Birch_Trunk", birch.cells().get(0).name());
        for (StructureShape.Cell c : birch.cells()) {
            eq(false, c.name().contains("Ash_Trunk"));
        }
        eq("Rock_Stone", birch.cells().get(2).name());
        eq("Wood_Ash_Trunk", BalloonRegistry.remapShape(base, BiomeWood.DEFAULT, x -> true).cells().get(0).name());
    }

    static void registryOwnershipRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("registry-ownership");
        try {
            java.util.UUID a = java.util.UUID.randomUUID();
            BalloonRegistry.init(dir);
            org.joml.Vector3i o = new org.joml.Vector3i(1, 2, 3);
            com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rot =
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None;
            BalloonRegistry.addNamed("w", o, rot, Deployables.BALLOON_KIND, Ownership.of(a, "Paul"));
            java.util.UUID id = null;
            for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfNameForTest("w")) {
                id = e.id();
            }
            eq(true, id != null);
            BalloonRegistry.setShared(id, true);
            BalloonRegistry.init(dir);
            BalloonRegistry.Entry e = BalloonRegistry.byId(id);
            eq(a, e.owner());
            eq("Paul", e.ownerName());
            eq(true, e.shared());
            eq(id, e.id());
            // Older file: version 2 without structureId or ownerName.
            Files.writeString(dir.resolve("deployed.json"),
                    "{\"version\":2,\"balloons\":[{\"world\":\"w\",\"x\":1,\"y\":2,\"z\":3,\"rotation\":\"None\",\"kind\":\"balloon\"}]}");
            BalloonRegistry.init(dir);
            BalloonRegistry.Entry old = BalloonRegistry.entriesOfNameForTest("w").get(0);
            eq(true, old.id() != null);
            eq(null, old.ownerName());
            eq(false, old.shared());
            BalloonRegistry.init(dir);
            eq(old.id(), BalloonRegistry.entriesOfNameForTest("w").get(0).id());
        } finally {
            deleteAll(dir);
        }
    }

    static void packBackShownTier() {
        eq(null, PackBackView.shownTier(List.of("Tool_Pickaxe_Iron")));
        eq(CampTiers.Tier.TENT, PackBackView.shownTier(List.of("Camping_Pack_Bedroll", "Camping_Pack_Tent")));
        eq(CampTiers.Tier.TENT_BIG, PackBackView.shownTier(List.of("Camping_Pack_Tent_Big", "Camping_Pack_Bedroll")));
        eq(null, PackBackView.shownTier(List.of("Camping_Tent_Crate")));
        eq("Items/Camping/Camping_Pack_Tent_Big_Texture.png", PackBackView.textureOf(CampTiers.Tier.TENT_BIG));
    }

    static void markerSharingSetting() throws Exception {
        Path dir = Files.createTempDirectory("transit-settings-sharing");
        try {
            TransitSettings.load(dir);
            eq(true, TransitSettings.markerSharing());
            TransitSettings.setMarkerSharing(false);
            eq(false, TransitSettings.markerSharing());
            TransitSettings.load(dir);
            eq(false, TransitSettings.markerSharing());
            TransitSettings.setMarkerSharing(true);
            TransitSettings.load(dir);
            eq(true, TransitSettings.markerSharing());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"packBackView\": false}");
            TransitSettings.load(dir);
            eq(true, TransitSettings.markerSharing());
            Files.writeString(dir.resolve("transit-settings.json"), "{garbage");
            TransitSettings.load(dir);
            eq(true, TransitSettings.markerSharing());
        } finally {
            TransitSettings.load(Files.createTempDirectory("transit-settings-sharing-reset"));
        }
    }

    static void ownedMarkerSharingSwitch() {
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        Ownership mine = Ownership.of(a, "A");
        for (boolean on : new boolean[]{true, false}) {
            check(OwnedMarkerProvider.visibleTo(mine, a, on), "owner sees own, sharing " + on);
            check(OwnedMarkerProvider.visibleTo(mine.withShared(true), a, on), "owner sees own shared, sharing " + on);
            check(!OwnedMarkerProvider.visibleTo(mine, b, on), "other never sees an unshared one, sharing " + on);
            check(!OwnedMarkerProvider.visibleTo(Ownership.NONE, a, on), "NONE hidden, sharing " + on);
        }
        check(OwnedMarkerProvider.visibleTo(mine.withShared(true), b, true), "other sees a shared one when sharing is on");
        check(!OwnedMarkerProvider.visibleTo(mine.withShared(true), b, false), "other does not see a shared one when sharing is off");
    }

    static void knownStationsSharingSwitch() {
        KnownStations k = new KnownStations();
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        SkyLog.Reached mine = new SkyLog.Reached(1, 64, 2), shared = new SkyLog.Reached(9, 64, 9);
        k.add("w", a, mine);
        k.share("w", shared);
        for (boolean on : new boolean[]{true, false}) {
            check(k.known("w", a, mine, on), "personal always known, sharing " + on);
            check(!k.known("w", b, mine, on), "personal of another never known, sharing " + on);
        }
        check(k.known("w", b, shared, true), "shared known when sharing is on");
        check(!k.known("w", b, shared, false), "shared ignored when sharing is off");
        k.add("w", b, shared);
        check(k.known("w", b, shared, false), "a shared station discovered personally stays known");
        check(k.isShared("w", shared), "shared set untouched");
    }

    static void settingsRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("transit-settings");
        try {
            TransitSettings.load(dir);
            eq(new java.util.HashSet<>(StationSpec.CRATE_IDS), TransitSettings.disabledRecipes());
            for (String id : StationSpec.CRATE_IDS) TransitSettings.setRecipeDisabled(id, false);
            eq(Set.of(), TransitSettings.disabledRecipes());
            TransitSettings.load(dir);
            eq(Set.of(), TransitSettings.disabledRecipes());
            eq(true, TransitSettings.packBackView());
            TransitSettings.setPackBackView(false);
            TransitSettings.load(dir);
            eq(false, TransitSettings.packBackView());
            eq(Set.of(), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": true}");
            TransitSettings.load(dir);
            eq(true, TransitSettings.packBackView());
            Files.writeString(dir.resolve("transit-settings.json"), "{garbage");
            TransitSettings.load(dir);
            eq(new java.util.HashSet<>(StationSpec.CRATE_IDS), TransitSettings.disabledRecipes());
            eq("{garbage", Files.readString(dir.resolve("transit-settings.json.bad")));
        } finally {
            deleteAll(dir);
        }
    }

    static void recipeTogglesWithoutUniverse() throws Exception {
        Path dir = Files.createTempDirectory("recipe-toggles");
        try {
            TransitSettings.load(dir);
            eq(true, RecipeToggles.enabled("Steamboat_Crate"));
            RecipeToggles.set("Steamboat_Crate", false);
            eq(false, RecipeToggles.enabled("Steamboat_Crate"));
            eq(false, RecipeToggles.enabled("Balloon_Station_Crate"));
            RecipeToggles.set("Balloon_Station_Crate", true);
            eq(true, RecipeToggles.enabled("Balloon_Station_Crate"));
            RecipeToggles.set("Not_A_Mod_Item", false);
            eq(false, TransitSettings.disabledRecipes().contains("Not_A_Mod_Item"));
            TransitSettings.load(dir);
            eq(false, RecipeToggles.enabled("Steamboat_Crate"));
            eq(true, RecipeToggles.enabled("Balloon_Station_Crate"));
        } finally {
            deleteAll(dir);
        }
    }

    static void configSearch() {
        eq(true, ConfigSearch.matches("Montgolfière de transport", "montgolfiere"));
        eq(true, ConfigSearch.matches("Grande tente", "TENTE"));
        eq(true, ConfigSearch.matches("Grande tente", ""));
        eq(true, ConfigSearch.matches("Grande tente", "   "));
        eq(true, ConfigSearch.matches("Grande tente", null));
        eq(false, ConfigSearch.matches("Grande tente", "dirigeable"));
        eq("eleve", ConfigSearch.normalize(" Élève "));
    }

    static void settingsDisabledRecipes() throws Exception {
        Path dir = Files.createTempDirectory("transit-settings-recipes");
        try {
            TransitSettings.load(dir);                                    // no file
            eq(new java.util.HashSet<>(StationSpec.CRATE_IDS), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": true}");
            TransitSettings.load(dir);
            eq(Set.of(), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": false}");
            TransitSettings.load(dir);
            eq(new java.util.HashSet<>(StationSpec.CRATE_IDS), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"),
                    "{\"recipeEnabled\": false, \"disabledRecipes\": [\"Steamboat_Crate\", \"Gone_Item\"]}");
            TransitSettings.load(dir);
            eq(Set.of("Steamboat_Crate"), TransitSettings.disabledRecipes());
            TransitSettings.setRecipeDisabled("Camping_Pack_Tent", true);
            String json = Files.readString(dir.resolve("transit-settings.json"));
            eq(false, json.contains("Gone_Item"));
            eq(false, json.contains("recipeEnabled"));
            TransitSettings.load(dir);
            eq(Set.of("Steamboat_Crate", "Camping_Pack_Tent"), TransitSettings.disabledRecipes());
            TransitSettings.setRecipeDisabled("Steamboat_Crate", false);
            eq(Set.of("Camping_Pack_Tent"), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"), "{garbage");
            TransitSettings.load(dir);
            eq(new java.util.HashSet<>(StationSpec.CRATE_IDS), TransitSettings.disabledRecipes());
        } finally {
            deleteAll(dir);
        }
    }

    static void grassLayerCells() {
        var solid = com.hypixel.hytale.protocol.BlockMaterial.Solid;
        var empty = com.hypixel.hytale.protocol.BlockMaterial.Empty;
        eq(true, BalloonManager.keepTerrainAt("Soil_Grass", -1, solid));
        eq(true, BalloonManager.keepTerrainAt("Soil_Dirt", -1, solid));
        eq(false, BalloonManager.keepTerrainAt("Rock_Basalt_Brick", -1, solid));
        eq(false, BalloonManager.keepTerrainAt("Soil_Grass", -1, empty));
        eq(false, BalloonManager.keepTerrainAt("Soil_Grass", -1, null));
        eq(false, BalloonManager.keepTerrainAt("Soil_Grass", 0, solid));
        eq(false, BalloonManager.keepTerrainAt("Rock_Basalt_Brick", 4, solid));
    }

    static void nameRules() {
        eq(TransitNetwork.NameCheck.TOO_SHORT, TransitNetwork.checkName("  Ab  ", Set.of()));
        eq(TransitNetwork.NameCheck.OK, TransitNetwork.checkName("Abc", Set.of()));
        eq(TransitNetwork.NameCheck.TOO_LONG, TransitNetwork.checkName("a".repeat(25), Set.of()));
        eq(TransitNetwork.NameCheck.TAKEN, TransitNetwork.checkName("port nord", Set.of("Port Nord")));
        eq("Port Nord", TransitNetwork.normalize("  Port Nord "));
        eq("Station 2", TransitNetwork.autoName(Set.of("Station 1", "Station 3")));
    }

    static void stationFileRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("transit-test");
        try {
            roundTrip(dir);
        } finally {
            deleteAll(dir);
        }
    }

    private static void deleteAll(Path dir) throws Exception {
        try (var w = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }

    static void corruptFileIsKept() throws Exception {
        Path dir = Files.createTempDirectory("transit-test");
        try {
            Files.writeString(dir.resolve("stations.json"), "{garbage");
            TransitNetwork net = TransitNetwork.get();
            net.load(dir);
            eq(0, net.stations("default").size());
            eq("{garbage", Files.readString(dir.resolve("stations.json.bad")));
            if (Files.exists(dir.resolve("stations.json"))) throw new AssertionError("original not moved");
        } finally {
            deleteAll(dir);
        }
    }

    private static void roundTrip(Path dir) throws Exception {
        TransitNetwork net = TransitNetwork.get();
        net.load(dir);
        UUID master = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        TransitNetwork.Station s = new TransitNetwork.Station("Port Nord", "default", new Vector3i(10, 64, -20),
                Rotation.Ninety, master, owner, new java.util.ArrayList<>(List.of("Gare Sud", "Plaine")), Deployables.STATION_KIND);
        TransitNetwork.Station boat = new TransitNetwork.Station("Quai Est", "default", new Vector3i(50, 62, 8),
                Rotation.None, null, owner, new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        net.add(s);
        net.add(boat);
        net.save();
        net.remove(s);
        net.remove(boat);
        eq(0, net.stations("default").size());
        net.load(dir);
        TransitNetwork.Station r = net.byName("default", "port nord");
        if (r == null) throw new AssertionError("station not reloaded");
        eq("Port Nord", r.name);
        eq("default", r.world);
        eq(new Vector3i(10, 64, -20), r.origin);
        eq(Rotation.Ninety, r.rotation);
        eq(master, r.masterUuid);
        eq(owner, r.owner);
        eq(List.of("Gare Sud", "Plaine"), r.queue);
        eq(r, net.at("default", new Vector3i(10, 64, -20)));
        eq(Deployables.STATION_KIND, r.kind);
        eq(Deployables.BOAT_STATION_KIND, net.byName("default", "Quai Est").kind);
        // A document without "kind" (written before boat stations) is a balloon station.
        Path f = dir.resolve("stations.json");
        Files.writeString(f, Files.readString(f).replace("\"kind\": \"station\", ", "").replace("\"kind\": \"station\"", "\"x0\": 0"));
        net.load(dir);
        eq(Deployables.STATION_KIND, net.byName("default", "Port Nord").kind);
    }

    static void fareChoice() {
        UUID p = UUID.randomUUID();
        eq(new TransitFare.Payment(p, TransitFare.ESSENCE, 100), TransitFare.choose(p, 100, 1));
        eq(new TransitFare.Payment(p, TransitFare.ESSENCE, 100), TransitFare.choose(p, 250, 0));
        eq(new TransitFare.Payment(p, TransitFare.CONCENTRATED, 1), TransitFare.choose(p, 99, 1));
        eq(null, TransitFare.choose(p, 99, 0));
        eq(null, TransitFare.choose(p, 0, 0));
    }

    private static TransitNetwork.Station fareStation(String name, String... queue) {
        return new TransitNetwork.Station(name, "default", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(List.of(queue)), Deployables.STATION_KIND);
    }

    static void refundsFileRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("refund-test");
        try {
            TransitFare.loadRefunds(dir);
            UUID u = UUID.randomUUID();
            TransitFare.Payment a = new TransitFare.Payment(u, TransitFare.ESSENCE, 100);
            TransitFare.Payment b = new TransitFare.Payment(u, TransitFare.CONCENTRATED, 1);
            TransitFare.refund(a, "Nord");
            TransitFare.refund(b, "Sud");
            TransitFare.loadRefunds(dir);
            eq(List.of(a, b), TransitFare.takeRefunds(u));
        } finally {
            deleteTree(dir);
        }
    }

    static void refundsTakeOnce() throws Exception {
        Path dir = Files.createTempDirectory("refund-test");
        try {
            TransitFare.loadRefunds(dir);
            UUID u = UUID.randomUUID();
            TransitFare.refund(new TransitFare.Payment(u, TransitFare.ESSENCE, 100), "Nord");
            eq(1, TransitFare.takeRefunds(u).size());
            eq(0, TransitFare.takeRefunds(u).size());
            TransitFare.loadRefunds(dir);
            eq(0, TransitFare.takeRefunds(u).size());
        } finally {
            deleteTree(dir);
        }
    }

    static void refundsCorruptKept() throws Exception {
        Path dir = Files.createTempDirectory("refund-test");
        try {
            Files.writeString(dir.resolve("refunds.json"), "{garbage");
            TransitFare.loadRefunds(dir);
            eq("{garbage", Files.readString(dir.resolve("refunds.json.bad")));
            eq(0, TransitFare.takeRefunds(UUID.randomUUID()).size());
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    static void paymentsRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("transit-test");
        try {
            TransitNetwork net = TransitNetwork.get();
            net.load(dir);
            UUID payer = UUID.randomUUID();
            TransitNetwork.Station a = fareStation("A");
            TransitNetwork.Station b = fareStation("B");
            b.origin.x = 200;
            net.add(a);
            net.add(b);
            TransitFare.Payment pay = new TransitFare.Payment(payer, TransitFare.ESSENCE, 100);
            eq(StationQueue.EnqueueResult.ADDED, net.queueEnqueue(a, "Gare Sud", pay));
            eq(StationQueue.EnqueueResult.ADDED, net.queueEnqueue(a, "Plaine"));
            eq(StationQueue.EnqueueResult.ADDED, net.queueEnqueue(b, "Gare Sud"));
            net.save();
            net.load(dir);
            TransitNetwork.Station ra = net.byName("default", "A");
            TransitNetwork.Station rb = net.byName("default", "B");
            eq(List.of("Gare Sud", "Plaine"), ra.queue);
            eq(1, ra.payments.size());
            eq(pay, ra.payments.get("gare sud"));
            eq(0, rb.payments.size());
            eq(List.of("Gare Sud"), rb.queue);
        } finally {
            deleteAll(dir);
        }
    }

    static void paymentsOldFile() throws Exception {
        Path dir = Files.createTempDirectory("transit-test");
        try {
            TransitNetwork net = TransitNetwork.get();
            net.load(dir);
            net.add(fareStation("A", "Gare Sud"));
            net.save();
            if (Files.readString(dir.resolve("stations.json")).contains("payments")) throw new AssertionError("empty table written");
            net.load(dir);
            TransitNetwork.Station r = net.byName("default", "A");
            eq(List.of("Gare Sud"), r.queue);
            eq(0, r.payments.size());
        } finally {
            deleteAll(dir);
        }
    }

    static void paymentsRemovedWithQueue() throws Exception {
        Path dir = Files.createTempDirectory("transit-test");
        try {
            TransitNetwork net = TransitNetwork.get();
            net.load(dir);
            UUID payer = UUID.randomUUID();
            TransitNetwork.Station s = fareStation("A");
            net.add(s);
            TransitFare.Payment pay = new TransitFare.Payment(payer, TransitFare.CONCENTRATED, 1);
            net.queueEnqueue(s, "Gare Sud", pay);
            eq(pay, net.queueRemoveFirst(s, "gare sud"));
            eq(null, net.queueRemoveFirst(s, "Gare Sud"));
            net.queueEnqueue(s, "Plaine", pay);
            eq(List.of(pay), net.queueRemoveAll(s, "PLAINE"));
            eq(List.of(), net.queueRemoveAll(s, "Plaine"));
            net.queueEnqueue(s, "Port", pay);
            eq(1, net.takeAllPayments(s).size());
            eq(0, net.takeAllPayments(s).size());
        } finally {
            deleteAll(dir);
        }
    }

    static void stationsOfKind() {
        TransitNetwork net = TransitNetwork.get();
        net.load(Path.of("/nonexistent-orbis-test"));
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station b = new TransitNetwork.Station("B", "w", new Vector3i(100, 62, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        TransitNetwork.Station c = new TransitNetwork.Station("C", "other", new Vector3i(0, 62, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        net.add(a); net.add(b); net.add(c);
        try {
            eq(List.of(b), net.stations("w", Deployables.BOAT_STATION_KIND));
            eq(List.of(a), net.stations("w", Deployables.STATION_KIND));
            eq(2, net.stations("w").size());
        } finally {
            net.remove(a); net.remove(b); net.remove(c);
        }
    }

    static void stationDistanceSameKind() {
        TransitNetwork.Station balloon = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station boat = new TransitNetwork.Station("B", "w", new Vector3i(0, 64, 10), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        Vector3i origin = new Vector3i(0, 64, 0);
        eq(null, DeployStationInteraction.tooClose(List.of(balloon), Deployables.BOAT_STATION_KIND, new Vector3i(0, 64, 10), 64));
        eq(boat, DeployStationInteraction.tooClose(List.of(balloon, boat), Deployables.BOAT_STATION_KIND, origin, 64));
        eq(balloon, DeployStationInteraction.tooClose(List.of(balloon, boat), Deployables.STATION_KIND, new Vector3i(0, 64, 10), 64));
    }

    private static void quartersByCloseness() {
        Rotation[] q = AirshipManager.quartersByCloseness(Math.toRadians(80));
        check(q[0] == Rotation.Ninety && q[3] == Rotation.TwoSeventy, "80 deg: 90 first, 270 last");
        check(q.length == 4 && java.util.Set.of(q).size() == 4, "four distinct quarters");
        check(AirshipManager.quartersByCloseness(Math.toRadians(-10))[0] == Rotation.None, "-10 deg: none first");
        check(AirshipManager.quartersByCloseness(Math.toRadians(45))[0] == Rotation.Ninety, "45 deg tie: counterclockwise (90) first");
    }

    /** Lot 2: a filler cell of the helm is not the anchor. */
    private static void neighbourFluidChoice() {
        SteamboatWater.Neighbour flow = new SteamboatWater.Neighbour(1, (byte) 3, false);
        SteamboatWater.Neighbour hi = new SteamboatWater.Neighbour(1, (byte) 5, false);
        SteamboatWater.Neighbour src = new SteamboatWater.Neighbour(2, (byte) 8, true);
        check(SteamboatWater.betterNeighbour(null, flow) == flow, "first kept");
        check(SteamboatWater.betterNeighbour(flow, hi) == hi, "higher level wins");
        check(SteamboatWater.betterNeighbour(hi, flow) == hi, "lower level loses");
        check(SteamboatWater.betterNeighbour(hi, src) == src, "source wins over flow");
        check(SteamboatWater.betterNeighbour(src, hi) == src, "source kept");
    }

    /** Lot 4: only the steamboat has paddle wheels. */
    private static void wheelSpecFlags() {
        check("Steamboat".equals(Deployables.STEAMBOAT.airship.wheelAnimPrefix), "steamboat has wheels");
        check(Deployables.AIRSHIP.airship.wheelAnimPrefix == null, "airship has none");
        check(Deployables.STEAMBOAT.airship.propellerAnimPrefix == null && Deployables.AIRSHIP.airship.propellerAnimPrefix == null,
                "steamboat and airship have no propeller");
        check("Propeller".equals(Deployables.STEAMBOAT_PUBLIC.airship.propellerAnimPrefix)
                && Deployables.STEAMBOAT_PUBLIC.airship.wheelAnimPrefix == null, "shuttle: one propeller, no wheels");
    }

    private static void markerIgnoresFiller() {
        check(StructureShape.isMarker(new StructureShape.Cell(0, 5, 4, "Airship_Helm", 0), "Airship_Helm"), "origin is marker");
        check(!StructureShape.isMarker(new StructureShape.Cell(0, 4, 4, "Airship_Helm", 31744), "Airship_Helm"), "filler is not");
        check(!StructureShape.isMarker(new StructureShape.Cell(0, 5, 4, "Other", 0), "Airship_Helm"), "other name");
    }

    /** Lot 2: water surface search in a column. */
    private static void waterSurfaceSearch() {
        java.util.function.IntPredicate col = y -> y >= 60 && y <= 64;
        check(Integer.valueOf(64).equals(DeployPlacement.waterSurfaceY(col::test, 60, 64)), "surface");
        check(DeployPlacement.waterSurfaceY(col::test, 66, 64) == null, "no water above target");
        check(DeployPlacement.waterSurfaceY(y -> false, 10, 64) == null, "dry");
        check(Integer.valueOf(64).equals(DeployPlacement.waterSurfaceY(y -> y >= 60, 60, 64)), "maxY is an absolute height");
    }

    private static void steamboatFitCheck() {
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(0, -2, 0, "A"), new StructureShape.Cell(0, -3, 0, "A"), new StructureShape.Cell(0, 0, 0, "B")));
        Vector3i o = new Vector3i(0, 62, 0); // surface 60: waterline layer -2 at y 60 (water top)
        // water up to y 60, air above
        check(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : 0) == null, "fit");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y == 60 ? 2 : y < 60 ? 1 : 0)), "block in hull");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y < 60 ? 1 : 0)), "air in hull");
        check("deploy.steamboat.blocked".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : y == 62 ? 2 : 0)), "block above");
        check("deploy.steamboat.blocked".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y <= 60 || y == 62 ? 1 : 0)), "water above");
        // Quarter turn: Ninety sends prefab (x, z) to (z, -x), so the hull cell (2, -2, 0) lands at (0, 60, -2) from the origin.
        StructureShape t = StructureShape.ofCells(List.of(
                new StructureShape.Cell(2, -2, 0, "A"), new StructureShape.Cell(0, 0, 0, "B")));
        Vector3i to = new Vector3i(0, 62, 0);
        check(DeployPlacement.steamboatFit(t, to, Rotation.Ninety, (x, y, z) -> y <= 60 && x == 0 && z == -2 ? 1 : 0) == null, "turned fit");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(t, to, Rotation.Ninety,
                (x, y, z) -> y <= 60 && x == 2 && z == 0 ? 1 : 0)), "turned hull is not at the unturned place");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(t, to, Rotation.Ninety,
                (x, y, z) -> x == 0 && z == -2 && y == 60 ? 2 : y <= 60 ? 1 : 0)), "block in turned hull cell");
    }

    private static void shuttleBerthWaterline() {
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(0, 0, 0, "Floor"), new StructureShape.Cell(0, 1, 0, "Wall")));
        Vector3i o = new Vector3i(0, 60, 0); // surface 60: the floor layer lands on the water top
        check(DeployPlacement.steamboatFault(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : 0, 0) == null, "floor on water fits");
        DeployPlacement.Fault above = DeployPlacement.steamboatFault(s, o, Rotation.None, (x, y, z) -> y <= 61 ? 1 : 0, 0);
        eq("deploy.steamboat.blocked", above.key());
        eq(61, above.at().y);
        DeployPlacement.Fault floor = DeployPlacement.steamboatFault(s, o, Rotation.None, (x, y, z) -> y == 60 ? 2 : y < 60 ? 1 : 0, 0);
        eq("deploy.steamboat.noWater", floor.key());
        check(floor.hull(), "a solid floor cell is a hull fault");
    }

    private static void steamboatFaultCell() {
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(0, -2, 0, "A"), new StructureShape.Cell(0, -3, 0, "A"), new StructureShape.Cell(0, 0, 0, "B")));
        Vector3i o = new Vector3i(0, 62, 0);
        DeployPlacement.Fault above = DeployPlacement.steamboatFault(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : y == 62 ? 2 : 0);
        eq("deploy.steamboat.blocked", above.key());
        eq(62, above.at().y);
        check(!above.hull(), "the block above water is not a hull cell");
        DeployPlacement.Fault hull = DeployPlacement.steamboatFault(s, o, Rotation.None, (x, y, z) -> y == 60 ? 2 : y < 60 ? 1 : 0);
        eq("deploy.steamboat.noWater", hull.key());
        eq(60, hull.at().y);
        check(hull.hull(), "the block in the hull is a hull cell");
        check(DeployPlacement.steamboatFault(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : 0) == null, "fits: no fault");
    }

    /** the steamboat sits one block lower (waterline -2), nothing that lets water in or keeps it out (rope ladder) stays under it and the hold is dry. */
    private static void steamboatWaterlineLayer() throws Exception {
        check(Deployables.STEAMBOAT.airship.waterlineY == -2, "waterline layer -2");
        StructureShape shape = prefabShape("Steamboat.prefab.json");
        for (StructureShape.Cell c : shape.cells()) {
            if (c.y() <= -2) {
                check(!c.baseName().contains("Window") && !c.baseName().contains("Trapdoor"), "no window or trapdoor under water: " + c.x() + "," + c.y() + "," + c.z());
                check(!"Steamboat_Paddle_Wheel".equals(c.baseName()), "no paddle wheel cell under water: " + c.x() + "," + c.y() + "," + c.z());
                check(!"Airship_Rope_Ladder".equals(c.baseName()), "no rope ladder under water: " + c.x() + "," + c.y() + "," + c.z());
            }
        }
        check(SteamboatWater.submergedCells(shape, -2).contains(new Vector3i(0, -2, 0)), "the hold at the waterline is a closed cavity");
    }

    private static void gliderAllowedKinds() {
        check(!GliderJump.allowedFor(Deployables.STEAMBOAT), "no glider jump from the boat");
        check(GliderJump.allowedFor(Deployables.AIRSHIP) && GliderJump.allowedFor(Deployables.BALLOON), "glider jump elsewhere");
    }

    private static void steamboatAnchor() throws Exception {
        // Deployables.STEAMBOAT.shape() needs the game assets, so the prefab file is read directly: the origin cell of the marker block.
        var anchors = prefabShape("Steamboat.prefab.json").cells().stream()
                .filter(c -> "Airship_Helm".equals(c.baseName()) && c.filler() == 0).toList();
        check(anchors.size() == 1, "one helm origin cell");
        StructureShape.Cell a = anchors.get(0);
        check(a.x() == 0 && a.y() == 5 && a.z() == 4, "anchor " + a.x() + "," + a.y() + "," + a.z());
    }

    private static void steamboatCrewSpec() throws Exception {
        Deployables.CrewSpec c = Deployables.crewOf(Deployables.STEAMBOAT_KIND);
        check(c != null, "steamboat has a crew spec");
        check(java.util.Arrays.equals(Deployables.STEAMBOAT_PIVOT, c.helm()), "helm is the pivot");
        check(c.door() == null, "no door");
        check(java.util.Arrays.equals(new int[]{0, 2, 7}, c.desk()), "desk in the crew spec");
        // The prefab file is read directly (StructureShape needs no game assets here): block name per origin cell.
        java.util.Map<String, String> cells = new java.util.HashMap<>();
        for (StructureShape.Cell cell : prefabShape("Steamboat.prefab.json").cells()) {
            cells.put(cell.x() + "," + cell.y() + "," + cell.z(), cell.baseName());
        }
        eq("Furniture_Village_Chair", cells.get("0,4,8"));
        eq("Airship_Chair", cells.get("0,1,8"));
        eq("Airship_Captain_Map_Desk", cells.get("0,2,7"));
        check(!cells.containsKey("0,4,6") && !cells.containsKey("0,4,7"), "straight walk from the helm to the chair");
        eq(0, c.walk().legs(new double[]{0, 4, 5}, CrewRecord.Post.STOOL).size());
        check(Deployables.voyageOf(Deployables.STEAMBOAT_KIND) && Deployables.voyageOf(Deployables.AIRSHIP_KIND), "voyage flags");
        check(Deployables.crewOf(Deployables.STEAMBOAT_PUBLIC_KIND) == null, "the shuttle has no pilot spec");
    }

    /** Entity forward (-sin yaw, -cos yaw) of the ship-frame seat yaw matches the documented facing of each block rotation. */
    private static void crewSeatYaw() {
        int[][] expected = {{0, 0, 1}, {1, 1, 0}, {2, 0, -1}, {3, -1, 0}}; // rotation, facing x, facing z
        for (int[] e : expected) {
            float yaw = AirshipCrew.seatYaw(e[0]);
            near(e[1], -Math.sin(yaw));
            near(e[2], -Math.cos(yaw));
        }
        near(AirshipCrew.seatYaw(1), AirshipCrew.seatYaw(5));
        // with a ship heading theta the world facing is the seat yaw plus theta: the bow (yaw 0) of a ship turned a quarter looks to -x
        near(-1, -Math.sin(Math.PI / 2 + AirshipCrew.seatYaw(2)));
    }

    /** The rest rotation of each crew spec is the rotation of the seat block at the rest cell in the prefab file. */
    private static void crewRestRotationMatchesPrefab() throws Exception {
        String[][] kinds = {{Deployables.AIRSHIP_KIND, "The_Cloudwork.prefab.json"}, {Deployables.STEAMBOAT_KIND, "Steamboat.prefab.json"}};
        for (String[] k : kinds) {
            Deployables.CrewSpec c = Deployables.crewOf(k[0]);
            int[] r = c.rest();
            boolean found = false;
            var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs", k[1])));
            for (var e : root.getArray("blocks")) {
                var b = e.asDocument();
                if (b.getInt32("x").getValue() == r[0] && b.getInt32("y").getValue() == r[1] && b.getInt32("z").getValue() == r[2]) {
                    found = true;
                    eq(b.containsKey("rotation") ? b.getInt32("rotation").getValue() : 0, c.restRotation());
                }
            }
            check(found, k[0] + " has a block at the rest cell");
        }
    }

    private static void boatCrewOrders() {
        eq(List.of(AirshipVoyagePage.CrewOrder.VOYAGE, AirshipVoyagePage.CrewOrder.NOTE_STOP, AirshipVoyagePage.CrewOrder.TAKE_HELM),
                AirshipVoyagePage.crewOptions(AirshipCrew.CrewState.AT_HELM, true, true));
        eq(List.of(AirshipVoyagePage.CrewOrder.RETURN_HELM), AirshipVoyagePage.crewOptions(AirshipCrew.CrewState.SEATED, true, true));
        check(AirshipVoyagePage.keepsLog(Deployables.STEAMBOAT_KIND) && !AirshipVoyagePage.keepsLog(Deployables.AIRSHIP_KIND), "log only on water");
    }

    private static void crewOptionsWithoutVoyage() {
        eq(List.of(AirshipVoyagePage.CrewOrder.TAKE_HELM), AirshipVoyagePage.crewOptions(AirshipCrew.CrewState.AT_HELM, false));
        eq(List.of(AirshipVoyagePage.CrewOrder.VOYAGE, AirshipVoyagePage.CrewOrder.TAKE_HELM),
                AirshipVoyagePage.crewOptions(AirshipCrew.CrewState.AT_HELM, true));
        eq(List.of(AirshipVoyagePage.CrewOrder.RETURN_HELM), AirshipVoyagePage.crewOptions(AirshipCrew.CrewState.SEATED, false));
    }

    /** The shuttle berth: the small hull placed by the berth constants fits against the pier of the boat station, step to deck. */
    private static void boatStationBerth() throws Exception {
        // the player steamboat has its own mooring rules (crate, DeployPlacement.steamboatFit), the berth constants
        // are the shuttle's, so only the shuttle prefab is checked against them.
        boatBerth("Steamboat_Public.prefab.json");
    }

    private static void boatBerth(String boatFile) throws Exception {
        StructureShape station = prefabShape("Boat_Station.prefab.json");
        StructureShape boat = prefabShape(boatFile);
        int[] o = Deployables.BOAT_STATION_BERTH_ORIGIN;
        Rotation rot = Deployables.BOAT_STATION_BERTH_ROTATION;
        check(o[1] == -1, "berth origin y is the water surface (waterline layer 0 lands on it)");
        check(Deployables.STEAMBOAT_PUBLIC.airship.waterlineY == 0, "waterline is the floor layer");
        Set<String> taken = new java.util.HashSet<>();
        Set<String> deck = new java.util.HashSet<>();
        for (StructureShape.Cell c : station.cells()) {
            taken.add(c.x() + "," + c.y() + "," + c.z());
            if (c.y() == 0 && "Wood_Hardwood_Planks_Half".equals(c.baseName())) deck.add(c.x() + "," + c.z());
        }
        List<Vector3i> steps = new ArrayList<>();
        for (StructureShape.Cell c : boat.cells()) {
            Vector3i r = c.rotated(rot);
            Vector3i w = new Vector3i(r.x + o[0], c.y() + o[1], r.z + o[2]);
            check(w.z >= 6, "every boat cell has station z >= 6, got " + w);
            check(!taken.contains(w.x + "," + w.y + "," + w.z), "boat cell overlaps the station at " + w);
            if (c.y() <= 0) check(w.y <= -1, "hull under the waterline stays in the water surface layer, cell " + w);
            if (c.y() == 1 && Math.abs(c.x()) == 2 && c.z() == 0) {
                check("Wood_Softwood_Planks_Half".equals(c.baseName()), "boarding step is a half plank");
                steps.add(w);
            }
        }
        eq(2, steps.size());
        boolean touches = false;
        for (Vector3i l : steps) {
            check(l.y == 0, "boarding step at the pier deck height, got " + l);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int dx = l.x + d[0], dz = l.z + d[1];
                if (deck.contains(dx + "," + dz) && !taken.contains(dx + ",1," + dz) && !taken.contains(dx + ",2," + dz)) touches = true;
            }
        }
        check(touches, "a boarding step is next to a free deck cell of the pier");
    }

    /** The shuttle prefab (small boat, 127 blocks): benches, one helm, one chimney, one propeller, no wheel, no ladder, hull above the floor. */
    private static void steamboatPublicPrefab() throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs", "Steamboat_Public.prefab.json")));
        Map<String, String> cells = new java.util.HashMap<>();
        int helms = 0, minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE,
                minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            int x = b.getInt32("x").getValue(), y = b.getInt32("y").getValue(), z = b.getInt32("z").getValue();
            String n = b.getString("name").getValue();
            cells.put(x + "," + y + "," + z, n);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            if ("Airship_Helm".equals(n) && !b.containsKey("filler")) helms++;
            check(!"Steamboat_Paddle_Wheel".equals(n) && !"Airship_Rope_Ladder".equals(n) && !"Ship_Wheel".equals(n), "no wheel, ladder or decor wheel: " + n);
            check(!"Airship_Fuel_Tank".equals(n), "no fuel tank: " + n);
        }
        eq(127, cells.size());
        int[] mn = Deployables.STEAMBOAT_PUBLIC_PLACE_MIN, mx = Deployables.STEAMBOAT_PUBLIC_PLACE_MAX;
        // The last prefab block is at z 5, the place box keeps z 6 (no block there: it keeps the lake water).
        check(minX == mn[0] && minY == mn[1] && minZ == mn[2] && maxX == mx[0] && maxY == mx[1] && maxZ + 1 == mx[2],
                "place box is the prefab box plus the empty cell z 6: " + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ);
        check(minX == -2 && maxX == 2 && minY == 0 && minZ == -5 && maxZ == 5, "centred on x, floor at y 0");
        eq("Steamboat_Propeller", cells.get("0,0,5")); // the shaft and propeller block, replaces the beam and the window
        check(!cells.containsKey("0,0,6"), "no window behind the propeller: the water stays out of the hull only");
        check(helms == 1, "one helm origin, got " + helms);
        eq("Airship_Helm", cells.get("0,2,-2"));
        eq("Airship_Helm", cells.get("0,1,-2"));
        eq("*Airship_Engine_State_Definitions_On", cells.get("0,1,4")); // the lit firebox block, no fuel tank anywhere
        check(new StructureShape.Cell(0, 1, 4, cells.get("0,1,4")).baseName().equals(AirshipEngines.ENGINE_BLOCK),
                "the lit firebox is the engine block for the flight light");
        eq("Wood_Softwood_Planks_Half", cells.get("2,1,0"));
        eq("Wood_Softwood_Planks_Half", cells.get("-2,1,0"));
        int n = AirshipPassengers.seats(prefabShape("Steamboat_Public.prefab.json")).size();
        check(n == 6, "6 tavern stools = 6 seats, got " + n);
        // Fluids: only Empty entries, one per block cell (the cells the boat replaces in the water).
        Set<String> fluids = new java.util.HashSet<>();
        for (var e : root.getArray("fluids")) {
            var f = e.asDocument();
            boolean propeller = f.getInt32("x").getValue() == 0 && f.getInt32("y").getValue() == 0 && f.getInt32("z").getValue() == 5;
            // The propeller cell holds a water source (the block is Material Empty), every other entry is Empty.
            check(propeller ? "Water_Source".equals(f.getString("name").getValue()) && f.getInt32("level").getValue() == 1
                    : "Empty".equals(f.getString("name").getValue()), "fluid entry " + f);
            fluids.add(f.getInt32("x").getValue() + "," + f.getInt32("y").getValue() + "," + f.getInt32("z").getValue());
        }
        eq(cells.keySet(), fluids);
        Deployables.AirshipSpec sp = Deployables.STEAMBOAT_PUBLIC.airship;
        check(Deployables.forPrefab("Steamboat_Public.prefab.json") == Deployables.STEAMBOAT_PUBLIC, "forPrefab");
        check(!Deployables.SHIPS.contains(Deployables.STEAMBOAT_PUBLIC), "not in SHIPS");
        check(sp.fuelless && !Deployables.STEAMBOAT.airship.fuelless && !Deployables.AIRSHIP.airship.fuelless, "fuelless flag");
        check(sp.waterborne() && sp.crateItemId == null && sp.wheelAnimPrefix == null && "Propeller".equals(sp.propellerAnimPrefix) && sp.waterlineY == 0,
                "spec: propeller, no wheels, waterline 0");
        check(cells.containsKey("0,0,-1") && !cells.containsKey("0,1,-1") && !cells.containsKey("0,2,-1")
                && Deployables.STEAMBOAT_PUBLIC_PIVOT[0] == 0 && Deployables.STEAMBOAT_PUBLIC_PIVOT[1] == 1
                && Deployables.STEAMBOAT_PUBLIC_PIVOT[2] == -1, "pivot behind the helm: floor below, two free cells above");
        var kind = AirshipResume.kindOf(new org.bson.BsonDocument("kind", new org.bson.BsonString("steamboat_public")));
        check(kind == Deployables.STEAMBOAT_PUBLIC, "kindOf accepts the shuttle");
    }

    private static void steamboatSeats() throws Exception {
        int n = AirshipPassengers.seats(prefabShape("Steamboat.prefab.json")).size();
        check(n == 12, "12 seats, got " + n);
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
        StructureShape shuttle = prefabShape("Steamboat_Public.prefab.json");
        for (Rotation r : Rotation.values()) {
            for (int off : new int[] {0, 5, 11, 16, 22, 31}) {
                int ox = 1000 + off, oz = 2000 + off;
                Set<Long> chunks = new java.util.HashSet<>();
                for (long[] c : GenerationListener.footprintChunks(Deployables.BOAT_STATION_KIND, ox, oz, r)) {
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
        StructureShape shuttle = prefabShape("Steamboat_Public.prefab.json");
        Vector3i o = new Vector3i(100, 115, 200);
        for (Rotation r : Rotation.values()) {
            Set<Vector3i> cells = BoatSiteGround.clearCells(o, r, shuttle);
            Vector3i berth = r.rotateYaw(new Vector3i(Deployables.BOAT_STATION_BERTH_ORIGIN[0], Deployables.BOAT_STATION_BERTH_ORIGIN[1],
                    Deployables.BOAT_STATION_BERTH_ORIGIN[2]), new Vector3i()).add(o);
            for (StructureShape.Cell c : shuttle.cells()) {
                Vector3i p = c.rotated(r.add(Deployables.BOAT_STATION_BERTH_ROTATION)).add(berth);
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
    private static DeployPlacement.CellState shoreAt(Vector3i o, Rotation r, java.util.function.BiPredicate<Integer, Integer> landPrefab) {
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
        DeployPlacement.CellState sim = BoatSiteGround.simulated((x, y, z) -> 1, Set.of(), Set.of(new Vector3i(-4, 114, 5)));
        eq(new Vector3i(-4, 115, 5), BoatSiteGround.masterFault(o, Rotation.None, sim)); // water still in the master cell
    }

    /** Every chunk touched by the pier and by the moored steamboat shape is in the footprint, for the four rotations. */
    private static void boatFootprintCoversBerthedBoat() throws Exception {
        StructureShape pier = prefabShape(Deployables.BOAT_STATION.prefabPath);
        StructureShape boat = prefabShape("Steamboat_Public.prefab.json");
        int[] b = Deployables.BOAT_STATION_BERTH_ORIGIN;
        for (Rotation rot : Rotation.values()) {
            for (int[] at : new int[][] {{1000, 500}, {-1003, 77}, {15, -1021}}) {
                Set<Long> chunks = new java.util.HashSet<>();
                for (long[] c : GenerationListener.footprintChunks(Deployables.BOAT_STATION_KIND, at[0], at[1], rot)) {
                    chunks.add(c[0] * 1_000_000L + c[1]);
                }
                Vector3i origin = new Vector3i(at[0], 62, at[1]);
                Vector3i berth = rot.rotateYaw(new Vector3i(b[0], b[1], b[2]), new Vector3i()).add(origin);
                List<Vector3i> cells = new ArrayList<>();
                for (StructureShape.Cell c : pier.cells()) {
                    cells.add(c.rotated(rot).add(origin));
                }
                for (StructureShape.Cell c : boat.cells()) {
                    cells.add(c.rotated(rot.add(Deployables.BOAT_STATION_BERTH_ROTATION)).add(berth));
                }
                for (Vector3i p : cells) {
                    long key = (long) com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(p.x) * 1_000_000L
                            + com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(p.z);
                    check(chunks.contains(key), "chunk of " + p + " covered at " + at[0] + "," + at[1] + " rotation " + rot);
                }
            }
        }
    }

    private static void emptiedCellsReadAsEmpty() {
        DeployPlacement.CellState base = (x, y, z) -> 2;
        DeployPlacement.CellState sim = DeployPlacement.withEmptied(base, Set.of(new Vector3i(1, 2, 3)));
        eq(0, sim.at(1, 2, 3));
        eq(2, sim.at(1, 2, 4));
    }

    private static void refusalCarriesHullFlag() {
        eq(null, DeployStationInteraction.Refusal.of("k").hull());
        eq(null, DeployStationInteraction.Refusal.at("k", "b", 1, 2, 3).hull());
        eq(null, new DeployStationInteraction.Refusal(null, null, null, null).hull());
        eq(Boolean.TRUE, new DeployStationInteraction.Refusal("k", "b", new Vector3i(), "d", true).hull());
    }

    /** The refusals keep the keys and parameters the crate path sent before the extraction. */
    private static void roomRefusalKeysUnchanged() {
        DeployStationInteraction.Refusal at = DeployStationInteraction.Refusal.at("transit.deploy.noSpaceAt", "Rock_Stone", 1, 2, 3);
        eq("transit.deploy.noSpaceAt", at.key());
        eq("Rock_Stone", at.block());
        eq(new org.joml.Vector3i(1, 2, 3), at.at());
        eq(null, at.detailKey());
        eq("deploy.underwater", DeployStationInteraction.Refusal.of("deploy.underwater").key());
        check(DeployStationInteraction.blocksRoom("Plant_Leaves_Oak", true, false), "crate: foliage blocks");
        check(!DeployStationInteraction.blocksRoom("Plant_Leaves_Oak", true, true), "generated: foliage counts as empty");
        check(DeployStationInteraction.blocksRoom("Rock_Stone", true, true), "generated: rock blocks");
        check(!DeployStationInteraction.blocksRoom("Rock_Stone", false, false), "non solid never blocks");
        check(DeployStationInteraction.boatCellAllowed(-1, true, true), "boat rule kept");
        check(!DeployStationInteraction.boatCellAllowed(0, true, false), "boat deck rule kept");
    }

    /** Boat station room rule: the posts (prefab y below 0) may stand in the lake bed or the water, the deck and above must be free. */
    private static void boatStationRoomRule() {
        check(DeployStationInteraction.boatCellAllowed(-3, true, true), "post in the lake bed");
        check(DeployStationInteraction.boatCellAllowed(-1, false, true), "post in the water");
        check(!DeployStationInteraction.boatCellAllowed(0, false, true), "deck in the water");
        check(!DeployStationInteraction.boatCellAllowed(0, true, false), "deck in a block");
        check(DeployStationInteraction.boatCellAllowed(1, false, false), "free cell");
        check(DeployPlacement.CRATES.containsKey("Boat_Station_Crate"), "boat crate in the table");
    }

    /** the placement helper and the crate table (compared with the crate JSON files). */
    private static void deployPlacement() throws Exception {
        // No target: the player's position. Facing 0 (yaw 0), offset (0, 1, -3), Ninety.
        DeployPlacement.Placement p = DeployPlacement.compute(null, new org.joml.Vector3d(10.4, 64.2, 20.9), 0.0,
                new Vector3i(0, 1, -3), Rotation.Ninety);
        check(p.rotation() == Rotation.Ninety && p.facing() == Rotation.None, "rotation");
        // Facing None keeps the offset: the target block plus (0, 1, -3).
        p = DeployPlacement.compute(new Vector3i(5, 70, 5), new org.joml.Vector3d(0, 0, 0), 0.0, new Vector3i(0, 1, -3), Rotation.None);
        check(p.origin().equals(new Vector3i(5, 71, 2)), "origin facing none " + p.origin());
        // Facing a quarter turn: the offset turns with the player, the rotation adds the crate's quarter turn.
        p = DeployPlacement.compute(new Vector3i(0, 0, 0), new org.joml.Vector3d(0, 0, 0), Math.PI, new Vector3i(0, 0, -15), Rotation.None);
        check(p.facing() == Rotation.OneEighty && p.origin().equals(new Vector3i(0, 0, 15)), "origin facing 180 " + p.origin());
        check(DeployPlacement.compute(new Vector3i(), new org.joml.Vector3d(), Math.PI, new Vector3i(), Rotation.OneEighty).rotation()
                == Rotation.None, "rotation wraps");
        // The table equals the crate JSON files.
        for (var e : DeployPlacement.CRATES.entrySet()) {
            Path f = null;
            for (String dir : new String[]{"Vehicles", "Camping"}) {
                Path c = Path.of("Server/Item/Items/Containers", dir, e.getKey() + ".json");
                if (Files.exists(c)) {
                    f = c;
                }
            }
            check(f != null, "JSON of " + e.getKey());
            String j = Files.readString(f).replaceAll("\\s", "");
            var m = java.util.regex.Pattern.compile(
                    "\"PrefabPath\":\"([^\"]+)\",\"Offset\":\\{\"X\":(-?\\d+),\"Y\":(-?\\d+),\"Z\":(-?\\d+)\\},\"RotationOffset\":\"(\\w+)\"").matcher(j);
            check(m.find(), "interaction of " + e.getKey());
            DeployPlacement.Crate c = e.getValue();
            check(c.prefabPath().equals(m.group(1)), e.getKey() + " prefab");
            check(c.offset().equals(new Vector3i(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)))),
                    e.getKey() + " offset");
            check(c.rotationOffset().name().equals(m.group(5)), e.getKey() + " rotation offset");
        }
    }

    /** the tier table, the lookups and the bag cells. */
    private static void campTiersTable() {
        eq(CampTiers.Tier.TENT, CampTiers.Tier.BEDROLL.next());
        eq(CampTiers.Tier.TENT, CampTiers.Tier.TENT_BIG.previous());
        eq(null, CampTiers.Tier.TENT_BIG.next());
        eq(null, CampTiers.Tier.BEDROLL.previous());
        eq(CampTiers.Tier.TENT_BIG, CampTiers.ofPack("Camping_Pack_Tent_Big"));
        eq(CampTiers.Tier.BEDROLL, CampTiers.ofKind("bedroll"));
        eq(CampTiers.Tier.TENT, CampTiers.ofPlacedBlock("Camping_Pack_Placed_Tent"));
        eq(null, CampTiers.ofPack("Camping_Tent_Crate"));
        eq(new Vector3i(-2, 0, 2), CampTiers.bagCell(CampTiers.Tier.TENT));
        eq(new Vector3i(-2, 0, 1), CampTiers.bagCell(CampTiers.Tier.TENT_BIG));
        eq(Deployables.BEDROLL, Deployables.forPrefab("Camping_Bedroll.prefab.json"));
    }

    /** the pack and placed-bag JSON files, and the bag cell of each prefab. */
    private static void campTiersJson() throws Exception {
        for (CampTiers.Tier t : CampTiers.Tier.values()) {
            Path dir = Path.of("Server/Item/Items/Containers/Camping");
            String pack = Files.readString(dir.resolve(t.packItemId() + ".json")).replaceAll("\\s", "");
            check(pack.contains("\"MaxStack\":1"), t + " MaxStack");
            check(pack.contains("\"ItemLevel\":" + t.level() + ","), t + " ItemLevel");
            check(!pack.contains("\"BlockType\""), t + " pack has no BlockType");
            check(pack.contains("\"Type\":\"Camping_DeployCamp\""), t + " deploy interaction");
            String placed = Files.readString(dir.resolve(t.placedBlockId() + ".json")).replaceAll("\\s", "");
            check(placed.contains("\"Type\":\"Camping_PackCamp\""), t + " pack interaction on the placed bag");
            check(placed.contains("\"Material\":\"Empty\""), t + " placed bag is not solid");
            check(DeployPlacement.CRATES.containsKey(t.packItemId()), t + " in DeployPlacement.CRATES");
            Deployables.Kind kind = null;
            for (Deployables.Kind k : Deployables.TENTS) {
                if (k.id.equals(t.kindId())) {
                    kind = k;
                }
            }
            check(kind != null && kind.isTent(), t + " kind");
            check(t.packItemId().equals(kind.tent.packItemId), t + " TentSpec pack id");
            String prefab = Files.readString(Path.of("Server/Prefabs", kind.prefabPath));
            var root = org.bson.BsonDocument.parse(prefab);
            int found = 0;
            Vector3i cell = CampTiers.bagCell(t);
            for (var e : root.getArray("blocks")) {
                var b = e.asDocument();
                if (b.getString("name").getValue().equals(t.placedBlockId())) {
                    found++;
                    check(b.getInt32("x").getValue() == cell.x && b.getInt32("y").getValue() == cell.y
                            && b.getInt32("z").getValue() == cell.z, t + " bag cell");
                }
            }
            eq(1, found);
        }
    }

    /** refusal rule of a pack upgrade. */
    private static void campUpgradeRefusal() {
        eq(CampUpgrade.Refusal.NO_BAG, CampUpgrade.check(0, 0));
        eq(CampUpgrade.Refusal.NONE, CampUpgrade.check(1, 1));
        eq(CampUpgrade.Refusal.NONE, CampUpgrade.check(3, 1));
        eq(CampUpgrade.Refusal.SEVERAL_FULL, CampUpgrade.check(2, 2));
    }

    private static void campUpgradeCarryAfterPost() {
        org.bson.BsonDocument c = new org.bson.BsonDocument();
        var full = new CampUpgrade.Note(CampTiers.Tier.TENT, c, c, 0, (short) 3);
        var empty = new CampUpgrade.Note(CampTiers.Tier.TENT, null, null, -1, (short) -1);
        eq(CampUpgrade.Outcome.APPLY, CampUpgrade.outcome(full, false));
        eq(CampUpgrade.Outcome.GIVE_BACK, CampUpgrade.outcome(full, true));
        eq(CampUpgrade.Outcome.NOTHING, CampUpgrade.outcome(empty, false));
        eq(CampUpgrade.Outcome.NOTHING, CampUpgrade.outcome(empty, true));
        eq(CampUpgrade.Outcome.NOTHING, CampUpgrade.outcome(null, false));
    }

    /** Reconcile rule: idle queue gives contents back and clears every note, a running queue keeps them, null notes are discarded. */
    private static void campUpgradeReconcile() {
        org.bson.BsonDocument c = new org.bson.BsonDocument();
        var full = new CampUpgrade.Note(CampTiers.Tier.TENT, c, c, 0, (short) 3);
        var empty = new CampUpgrade.Note(CampTiers.Tier.TENT, null, null, -1, (short) -1);
        java.util.List<CampUpgrade.Note> q = new java.util.ArrayList<>(java.util.List.of(empty, full));
        eq(0, CampUpgrade.reconcile(q, 1, 5).size());
        eq(2, q.size());
        eq(0, CampUpgrade.reconcile(q, 0, 1).size());
        eq(2, q.size());
        var back = CampUpgrade.reconcile(q, 0, CampUpgrade.IDLE_TICKS);
        eq(1, back.size());
        check(back.get(0) == full, "contents given back");
        eq(0, q.size());
    }

    /** FIFO note rule: Pre and Post fire in the same order, contents go to the first output of their tier, once. */
    private static void campUpgradeQueue() {
        org.bson.BsonDocument c = new org.bson.BsonDocument();
        java.util.List<CampUpgrade.Note> q = new java.util.ArrayList<>();
        var empty = new CampUpgrade.Note(CampTiers.Tier.TENT, null, null, -1, (short) -1);
        var full = new CampUpgrade.Note(CampTiers.Tier.TENT, c, c, 0, (short) 3);
        var other = new CampUpgrade.Note(CampTiers.Tier.TENT_BIG, null, null, -1, (short) -1);
        // Two queued upgrades of the same tier (first with the empty pack, second with the full one) and another tier in between.
        q.add(empty);
        q.add(other);
        q.add(full);
        check(CampUpgrade.take(q, CampTiers.Tier.TENT) == empty, "first output takes the first note (empty)");
        check(CampUpgrade.take(q, CampTiers.Tier.TENT_BIG) == other, "other tier takes only its own note");
        eq(1, q.size());
        check(CampUpgrade.take(q, CampTiers.Tier.TENT) == full, "second output gets the contents, once");
        eq(0, q.size());
        check(CampUpgrade.take(q, CampTiers.Tier.TENT) == null, "no note left: nothing to carry");
        // A cancelled craft returns its own contents, nothing else.
        q.add(full);
        CampUpgrade.Note n = CampUpgrade.take(q, CampTiers.Tier.TENT);
        eq(CampUpgrade.Outcome.GIVE_BACK, CampUpgrade.outcome(n, true));
        eq(0, q.size());
    }

    /** small tent contents re-addressed to the large tent (campfire, chest, surplus chest). */
    private static void tentContentsConvert() throws Exception {
        // ItemStack needs the asset store (absent in these tests): the containers are empty, positions and capacity are checked.
        var slots1 = new com.hypixel.hytale.server.core.inventory.ItemStack[18];
        var slots2 = new com.hypixel.hytale.server.core.inventory.ItemStack[12];
        BalloonCargo cargo = new BalloonCargo(List.of(
                new BalloonCargo.Entry(new Vector3i(1, 0, 3), "Furniture_Kweebec_Chest_Small", (short) 18, slots1),
                new BalloonCargo.Entry(new Vector3i(1, 0, 5), "Furniture_Kweebec_Chest_Small", (short) 12, slots2),
                new BalloonCargo.Entry(new Vector3i(1, 0, 7), "Furniture_Kweebec_Chest_Small", (short) 18, new com.hypixel.hytale.server.core.inventory.ItemStack[18])));
        org.bson.BsonDocument benchDoc = new org.bson.BsonDocument();
        org.bson.BsonDocument e = new org.bson.BsonDocument();
        e.put("x", new org.bson.BsonInt32(-3));
        e.put("y", new org.bson.BsonInt32(0));
        e.put("z", new org.bson.BsonInt32(1));
        e.put("bench", new org.bson.BsonDocument());
        org.bson.BsonArray arr = new org.bson.BsonArray();
        arr.add(e);
        benchDoc.put("benchContents", arr);
        TentContents small = new TentContents(AirshipBenchContents.fromBson(benchDoc), cargo);
        TentContents big = small.convert(prefabShape("Camping_Tent_Big.prefab.json"));
        org.bson.BsonDocument d = big.toBson("tent_big");
        eq("tent_big", d.getString("kind").getValue());
        var bench = d.getDocument("benches").getArray("benchContents").get(0).asDocument();
        eq(2, bench.getInt32("x").getValue());
        eq(0, bench.getInt32("y").getValue());
        eq(0, bench.getInt32("z").getValue());
        var chests = d.getDocument("cargo").getArray("containers");
        eq(3, chests.size());
        int[][] expect = {{-2, 0, 3}, {2, 0, 3}, {2, 0, 3}};
        for (int i = 0; i < 3; i++) {
            var c = chests.get(i).asDocument();
            eq(expect[i][0], c.getInt32("x").getValue());
            eq(expect[i][1], c.getInt32("y").getValue());
            eq(expect[i][2], c.getInt32("z").getValue());
        }
        eq(18, chests.get(0).asDocument().getInt32("capacity").getValue());
        eq(12, chests.get(1).asDocument().getInt32("capacity").getValue());
        eq("Furniture_Kweebec_Chest_Small", chests.get(2).asDocument().getString("block").getValue());
    }

    /** Shape read from a prefab file (the real shapes need the asset module, absent in these tests). */
    private static StructureShape prefabShape(String file) throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs", file)));
        List<StructureShape.Cell> cells = new java.util.ArrayList<>();
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            cells.add(new StructureShape.Cell(b.getInt32("x").getValue(), b.getInt32("y").getValue(), b.getInt32("z").getValue(),
                    b.getString("name").getValue(), b.containsKey("filler") ? b.getInt32("filler").getValue() : 0));
        }
        return StructureShape.ofCells(cells);
    }

    /** Only the listed items show in the creative library, every other item JSON is hidden. */
    /** Two layers of planks under every column of the hut, at y -1 and -2. */
    private static void boatStationHutFoundation() throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs/Boat_Station.prefab.json")));
        java.util.Set<String> planks = new java.util.HashSet<>();
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            if (b.getString("name").getValue().equals("Wood_Hardwood_Planks")) {
                planks.add(b.getInt32("x").getValue() + "," + b.getInt32("y").getValue() + "," + b.getInt32("z").getValue());
            }
        }
        for (int y = -2; y <= -1; y++) {
            for (int x = -5; x <= -3; x++) {
                for (int z = 3; z <= 6; z++) check(planks.contains(x + "," + y + "," + z), "plank " + x + "," + y + "," + z);
            }
        }
    }

    /** Cleaned boat station prefab: no terrain, no fluids, extended posts, decor blocks swapped. */
    private static void boatStationPrefab() throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs/Boat_Station.prefab.json")));
        check(!root.containsKey("fluids") || root.getArray("fluids").isEmpty(), "no fluids");
        java.util.Set<String> banned = java.util.Set.of("Rock_Stone", "Soil_Gravel_Sand_White", "Soil_Sand", "Rock_Calcite",
                "Ore_Copper_Stone", "Empty", "Deco_Map", "Deco_Inkwell", "Furniture_Kweebec_Candle", "Deco_Coral_Shell_Swirly",
                "Deco_Treasure", "Wood_Hardwood_Fence_Gate");
        java.util.Map<String, java.util.List<org.bson.BsonDocument>> byName = new java.util.HashMap<>();
        java.util.Set<String> cells = new java.util.HashSet<>();
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            String n = b.getString("name").getValue();
            check(!banned.contains(n), "banned block " + n);
            byName.computeIfAbsent(n, k -> new java.util.ArrayList<>()).add(b);
            cells.add(b.getInt32("x").getValue() + "," + b.getInt32("y").getValue() + "," + b.getInt32("z").getValue());
        }
        java.util.Set<String> trunks = new java.util.HashSet<>();
        for (var t : byName.getOrDefault("Wood_Ash_Trunk", java.util.List.of()))
            trunks.add(t.getInt32("x").getValue() + "," + t.getInt32("y").getValue() + "," + t.getInt32("z").getValue());
        int[][] posts = {{-1, 6, -8}, {-1, 10, -10}, {4, 6, -7}, {4, 10, -9}};
        for (int[] p : posts) {
            for (int y = p[2]; y <= 0; y++) check(trunks.contains(p[0] + "," + y + "," + p[1]), "post " + p[0] + "," + p[1] + " y=" + y);
            check(!trunks.contains(p[0] + "," + (p[2] - 1) + "," + p[1]), "post bottom " + p[0] + "," + p[1]);
        }
        Object[][] decor = {{"Boat_Station_Map", -3, 1, 5, 1}, {"Boat_Station_Inkwell", -3, 1, 4, 0}, {"Boat_Station_Candle", -4, 1, 3, 0},
                {"Boat_Station_Shell", -5, 1, 5, 0}, {"Boat_Station_Treasure", -4, 1, 6, 0}, {"Boat_Station_Gate", -5, 0, 4, 3}};
        for (Object[] d : decor) {
            var l = byName.get((String) d[0]);
            check(l != null && l.size() == 1, "exactly one " + d[0]);
            var b = l.get(0);
            eq((int) d[1], b.getInt32("x").getValue());
            eq((int) d[2], b.getInt32("y").getValue());
            eq((int) d[3], b.getInt32("z").getValue());
            eq((int) d[4], b.containsKey("rotation") ? b.getInt32("rotation").getValue() : 0);
        }
        for (String c : new String[] {"-4,0,4", "-4,0,5", "-4,1,4", "-4,1,5"}) check(!cells.contains(c), "station master cell " + c + " free");
    }

    private static void oxygenHud() throws Exception {
        check(!OxygenHud.wanted(100f), "100 is the game's own bar");
        check(!OxygenHud.wanted(100.4f), "float noise");
        check(OxygenHud.wanted(101f), "above 100");
        check(OxygenHud.wanted(600f), "diving mask");
        check(OxygenHud.fraction(50f, 200f) == 0.25f, "fraction");
        check(OxygenHud.fraction(-5f, 200f) == 0f, "fraction below 0");
        check(OxygenHud.fraction(300f, 200f) == 1f, "fraction above 1");
        check(OxygenHud.fraction(5f, 0f) == 0f, "fraction with no maximum");
        String ui = Files.readString(Path.of("Common/UI/Custom/" + OxygenHud.UI_FILE));
        check(ui.contains("#OxygenBar"), "bar id in the .ui file");
        for (String line : ui.split("\n")) {
            int i = line.indexOf("%server");
            check(i < 0 || !line.substring(i).split("[\\s;]")[0].contains("_"), "no % key with underscore: " + line);
        }
        check(Files.exists(Path.of("Common/UI/Custom/Hud/OrbisHorizon_OxygenFill.png")), "fill texture");
    }

    private static void creativeVisibility() throws Exception {
        Set<String> visible = Set.of("Orbis_Explorer_Bench", "Camping_Pack_Bedroll", "Camping_Pack_Tent",
                "Camping_Pack_Tent_Big", "Hotair_Balloon_Crate", "Hotair_Balloon_Basic_Crate",
                "Hotair_Balloon_Transport_Crate", "Airship_Cloudwork_Crate", "Steamboat_Crate",
                "Balloon_Station_Crate", "Boat_Station_Crate", "Airship_Pilot_Contract",
                "Ship_Wheel");
        Set<String> found = new java.util.HashSet<>();
        int hidden = 0;
        List<Path> files;
        try (var w = Files.walk(Path.of("Server/Item/Items"))) {
            files = w.filter(p -> p.toString().endsWith(".json")).toList();
        }
        for (Path p : files) {
            String name = p.getFileName().toString();
            String id = name.substring(0, name.length() - 5);
            found.add(id);
            var root = org.bson.BsonDocument.parse(Files.readString(p));
            if (visible.contains(id)) {
                check(root.containsKey("Categories") && !root.getArray("Categories").isEmpty(), "Categories " + id);
                check(!root.containsKey("Quality")
                        || !"OrbisHorizon_Hidden".equals(root.getString("Quality").getValue()), "visible quality " + id);
            } else {
                check(!root.containsKey("Categories"), "no Categories " + id);
                eq("OrbisHorizon_Hidden", root.getString("Quality").getValue());
                hidden++;
            }
        }
        check(found.containsAll(visible), "visible ids exist");
        eq(39, hidden);
        var q = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Item/Qualities/OrbisHorizon_Hidden.json")));
        eq(true, q.getBoolean("HideFromSearch").getValue());
        eq("server.general.qualities.Common", q.getString("LocalizationKey").getValue());
        eq(1, q.getNumber("QualityValue").intValue());
    }

    /** The explorer bench item JSON: bench id, categories and texts. */
    private static void explorerBenchJson() throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Item/Items/Bench/Orbis_Explorer_Bench.json")));
        var block = root.getDocument("BlockType");
        var bench = block.getDocument("Bench");
        eq("OrbisHorizon_Bench", bench.getString("Id").getValue());
        eq("Crafting", bench.getString("Type").getValue());
        var cats = bench.getArray("Categories");
        eq(3, cats.size());
        eq("OrbisHorizon_Camp", cats.get(0).asDocument().getString("Id").getValue());
        eq("OrbisHorizon_Transport", cats.get(1).asDocument().getString("Id").getValue());
        eq("OrbisHorizon_Gear", cats.get(2).asDocument().getString("Id").getValue());
        eq("Icons/CraftingCategories/OrbisHorizon/Gear.png", cats.get(2).asDocument().getString("Icon").getValue());
        var tiers = bench.getArray("TierLevels");
        eq(3, tiers.size());
        double[] red = {0, 0.15, 0.3};
        for (int i = 0; i < 3; i++) {
            var t = tiers.get(i).asDocument();
            double v = t.containsKey("CraftingTimeReductionModifier") ? t.getNumber("CraftingTimeReductionModifier").doubleValue() : 0;
            check(Math.abs(v - red[i]) < 1e-9, "reduction " + i);
        }
        java.util.Set<String> zipNames = new java.util.HashSet<>();
        Path zip = Path.of(System.getProperty("user.home"), "repositories/hytale/server-ref/Assets.zip");
        if (Files.exists(zip)) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
                z.stream().forEach(en -> zipNames.add(en.getName().substring(en.getName().lastIndexOf('/') + 1)));
            }
        }
        for (int i = 0; i < 3; i++) {
            String icon = cats.get(i).asDocument().getString("Icon").getValue();
            // The game refuses tab icons outside Icons/CraftingCategories/ (asset validation fails and the mod does not load).
            check(icon.startsWith("Icons/CraftingCategories/"), "category icon under Icons/CraftingCategories " + icon);
            check(Files.exists(Path.of("Common", icon)), "category icon exists " + icon);
        }
        Object[][] up = {
                {0, new String[] {"Ingredient_Bar_Iron", "Ingredient_Bar_Copper", "Ingredient_Hide_Medium", "Cloth_Block_Wool_White", "Ingredient_Charcoal"}, new int[] {30, 20, 15, 40, 10}, 5.0},
                {1, new String[] {"Ingredient_Bar_Cobalt", "Ingredient_Bar_Thorium", "Ingredient_Leather_Heavy", "Ingredient_Fire_Essence", "Cloth_Block_Wool_White"}, new int[] {20, 20, 15, 10, 80}, 10.0},
        };
        for (Object[] u : up) {
            var req = tiers.get((Integer) u[0]).asDocument().getDocument("UpgradeRequirement");
            var mats = req.getArray("Material");
            String[] ids = (String[]) u[1];
            int[] qty = (int[]) u[2];
            eq(ids.length, mats.size());
            for (int i = 0; i < ids.length; i++) {
                eq(ids[i], mats.get(i).asDocument().getString("ItemId").getValue());
                eq(qty[i], mats.get(i).asDocument().getInt32("Quantity").getValue());
                check(zipNames.isEmpty() || zipNames.contains(ids[i] + ".json"), "id exists " + ids[i]);
            }
            check(Math.abs(req.getNumber("TimeSeconds").doubleValue() - (Double) u[3]) < 1e-9, "upgrade time");
        }
        eq("Blocks/Benches/Explorer_Bench.blockymodel", block.getString("CustomModel").getValue());
        eq("Bench_Workbench", block.getString("HitboxType").getValue());
        var states = block.getDocument("State").getDocument("Definitions");
        check(!states.containsKey("CraftCompleted"), "no CraftCompleted");
        String[] names = {"Tier2", "Tier3"};
        String[] models = {"Explorer_Bench2", "Explorer_Bench3"};
        for (int i = 0; i < 2; i++) {
            var st = states.getDocument(names[i]);
            eq("Blocks/Benches/" + models[i] + ".blockymodel", st.getString("CustomModel").getValue());
            eq("Bench_Workbench", st.getString("HitboxType").getValue());
            eq("Blocks/Benches/Explorer_Bench_Float.blockyanim", st.getString("CustomModelAnimation").getValue());
            check(st.getBoolean("Looping").getValue(), "looping " + names[i]);
            check(!st.getDocument("State").getDocument("Definitions").containsKey("CraftCompleted"), "no nested CraftCompleted");
            check(Files.exists(Path.of("Common/Blocks/Benches/" + models[i] + ".blockymodel")), "model file " + models[i]);
            String tex = st.getArray("CustomModelTexture").get(0).asDocument().getString("Texture").getValue();
            check(Files.exists(Path.of("Common/" + tex)), "texture file " + tex);
        }
        check(Files.exists(Path.of("Common/Blocks/Benches/Explorer_Bench_Float.blockyanim")), "anim file");
        check(Files.exists(Path.of("Common/" + block.getArray("CustomModelTexture").get(0).asDocument().getString("Texture").getValue())), "texture 1");
        check(Files.exists(Path.of("Common/" + root.getString("Icon").getValue())), "icon file");
        var r = root.getDocument("Recipe");
        var rb = r.getArray("BenchRequirement").get(0).asDocument();
        eq("Workbench", rb.getString("Id").getValue());
        eq("Crafting", rb.getString("Type").getValue());
        eq("Workbench_Crafting", rb.getArray("Categories").get(0).asString().getValue());
        eq(1, rb.getInt32("RequiredTierLevel").getValue());
        check(Math.abs(r.getNumber("TimeSeconds").doubleValue() - 3.0) < 1e-9, "recipe time");
        var in = r.getArray("Input");
        eq(5, in.size());
        eq("Wood_Trunk", in.get(0).asDocument().getString("ResourceTypeId").getValue());
        eq(6, in.get(0).asDocument().getInt32("Quantity").getValue());
        eq("Rock", in.get(1).asDocument().getString("ResourceTypeId").getValue());
        eq(4, in.get(1).asDocument().getInt32("Quantity").getValue());
        String[] rid = {"Ingredient_Fibre", "Ingredient_Hide_Light", "Ingredient_Stick"};
        int[] rq = {20, 4, 10};
        for (int i = 0; i < 3; i++) {
            eq(rid[i], in.get(i + 2).asDocument().getString("ItemId").getValue());
            eq(rq[i], in.get(i + 2).asDocument().getInt32("Quantity").getValue());
            check(zipNames.isEmpty() || zipNames.contains(rid[i] + ".json"), "id exists " + rid[i]);
        }
        String raw = Files.readString(Path.of("Server/Item/Items/Bench/Orbis_Explorer_Bench.json"));
        for (String k : new String[] {"items.orbis_explorer_bench.name", "items.orbis_explorer_bench.description", "benchCategories.camp", "benchCategories.transport"}) {
            check(raw.contains("server.orbis_horizon." + k), "json key " + k);
            for (String lang : new String[] {"en-US", "fr-FR"}) {
                check(Files.readString(Path.of("Server/Languages/" + lang + "/server.lang")).contains("orbis_horizon." + k + " ="), lang + " " + k);
            }
        }
    }

    /** Every mod recipe except the bench's own is made on the explorer bench, in the expected category and tier. */
    private static void recipesOnExplorerBench() throws Exception {
        java.util.Map<String, Object[]> table = new java.util.HashMap<>();
        String camp = "OrbisHorizon_Camp", tr = "OrbisHorizon_Transport";
        table.put("Camping_Pack_Bedroll", new Object[] {camp, 1});
        table.put("Camping_Pack_Tent", new Object[] {camp, 2});
        table.put("Camping_Pack_Tent_Big", new Object[] {camp, 3});
        table.put("Hotair_Balloon_Basic_Crate", new Object[] {tr, 2});
        table.put("Hotair_Balloon_Transport_Crate", new Object[] {tr, 2});
        table.put("Hotair_Balloon_Crate", new Object[] {tr, 3});
        table.put("Airship_Cloudwork_Crate", new Object[] {tr, 3});
        table.put("Steamboat_Crate", new Object[] {tr, 3});
        table.put("Balloon_Station_Crate", new Object[] {tr, 3});
        table.put("Boat_Station_Crate", new Object[] {tr, 3});
        java.util.List<Path> files;
        try (var w = Files.walk(Path.of("Server/Item/Items"))) {
            files = w.filter(p -> p.toString().endsWith(".json")).toList();
        }
        int seen = 0;
        for (Path p : files) {
            var root = org.bson.BsonDocument.parse(Files.readString(p));
            if (!root.containsKey("Recipe")) {
                continue;
            }
            String name = p.getFileName().toString().replace(".json", "");
            var r = root.getDocument("Recipe");
            var bench = r.getArray("BenchRequirement").get(0).asDocument();
            String[] qualities = {"Uncommon", "Rare", "Legendary"};
            // Exceptions: the large tent is tier 3 but only rare, and the
            // explorer bench is common like the game's own benches.
            eq(name.equals("Camping_Pack_Tent_Big") ? "Rare"
                    : name.equals("Orbis_Explorer_Bench") ? "Common"
                    : qualities[bench.getInt32("RequiredTierLevel").getValue() - 1],
                    root.getString("Quality").getValue());
            if (name.equals("Orbis_Explorer_Bench")) {
                eq("Workbench", bench.getString("Id").getValue());
                continue;
            }
            Object[] exp = table.get(name);
            check(exp != null, "recipe not in table: " + name);
            seen++;
            eq("OrbisHorizon_Bench", bench.getString("Id").getValue());
            eq(exp[0], bench.getArray("Categories").get(0).asString().getValue());
            eq(exp[1], bench.getInt32("RequiredTierLevel").getValue());
            int piles = 0;
            for (var in : r.getArray("Input")) {
                piles += (in.asDocument().getInt32("Quantity").getValue() + 99) / 100;
            }
            check(piles <= 40, name + " piles");
            if (name.equals("Balloon_Station_Crate") || name.equals("Boat_Station_Crate")) {
                check(r.getBoolean("KnowledgeRequired").getValue(), "station knowledge");
            }
            if (name.equals("Hotair_Balloon_Transport_Crate")) {
                java.util.Map<String, Integer> m = new java.util.HashMap<>();
                for (var in : r.getArray("Input")) {
                    m.put(in.asDocument().getString("ItemId").getValue(), in.asDocument().getInt32("Quantity").getValue());
                }
                eq(35, m.get("Ingredient_Bar_Iron"));
                eq(10, m.get("Ingredient_Bar_Thorium"));
                eq(4, m.get("Ingredient_Life_Essence_Concentrated"));
                check(!m.containsKey("Ingredient_Bar_Cobalt") && !m.containsKey("Ingredient_Fire_Essence"), "no cobalt or fire essence");
                eq(150, m.get("Cloth_Block_Wool_White"));
                eq(150, m.get("Cloth_Block_Wool_Green"));
                eq(24, m.get("Wood_Hardwood_Planks"));
            }
        }
        eq(table.size(), seen);
        // Standalone recipes for the game's own diving armour: the quality rule above does not apply
        // (the items belong to the game and keep their own quality).
        int gear = 0;
        try (var w = Files.list(Path.of("Server/Item/Recipes/OrbisHorizon"))) {
            for (Path p : w.filter(f -> f.toString().endsWith(".json")).toList()) {
                var root = org.bson.BsonDocument.parse(Files.readString(p));
                var bench = root.getArray("BenchRequirement").get(0).asDocument();
                eq("OrbisHorizon_Bench", bench.getString("Id").getValue());
                eq("OrbisHorizon_Gear", bench.getArray("Categories").get(0).asString().getValue());
                eq(2, bench.getInt32("RequiredTierLevel").getValue());
                int piles = 0;
                for (var in : root.getArray("Input")) {
                    piles += (in.asDocument().getInt32("Quantity").getValue() + 99) / 100;
                }
                check(piles <= 40, p.getFileName() + " piles");
                gear++;
            }
        }
        eq(4, gear);
    }

    /** the pack recipes match the spec, the lower pack comes first, ids exist, piles <= 40, legacy crates have no recipe. */
    private static void campRecipesJson() throws Exception {
        Path dir = Path.of("Server/Item/Items/Containers/Camping");
        Object[][] spec = {
                {"Camping_Pack_Bedroll", 1, new String[] {"Furniture_Crude_Bed", "Ingredient_Fibre", "Ingredient_Hide_Light", "Ingredient_Stick"}, new int[] {1, 20, 6, 10}},
                {"Camping_Pack_Tent", 2, new String[] {"Camping_Pack_Bedroll", "Ingredient_Fibre", "Ingredient_Hide_Medium", "Ingredient_Stick", "Bench_Campfire", "Furniture_Crude_Chest_Small"}, new int[] {1, 40, 10, 20, 1, 1}},
                {"Camping_Pack_Tent_Big", 3, new String[] {"Camping_Pack_Tent", "Ingredient_Fibre", "Ingredient_Hide_Medium", "Ingredient_Stick", "Furniture_Crude_Bed", "Furniture_Crude_Chest_Small"}, new int[] {1, 60, 15, 20, 1, 1}},
        };
        java.util.Set<String> zipNames = new java.util.HashSet<>();
        Path zip = Path.of(System.getProperty("user.home"), "repositories/hytale/server-ref/Assets.zip");
        if (Files.exists(zip)) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
                z.stream().forEach(en -> zipNames.add(en.getName().substring(en.getName().lastIndexOf('/') + 1)));
            }
        }
        for (Object[] row : spec) {
            var root = org.bson.BsonDocument.parse(Files.readString(dir.resolve(row[0] + ".json")));
            var r = root.getDocument("Recipe");
            var in = r.getArray("Input");
            String[] ids = (String[]) row[2];
            int[] qty = (int[]) row[3];
            eq(ids.length, in.size());
            int piles = 0;
            for (int i = 0; i < ids.length; i++) {
                var inDoc = in.get(i).asDocument();
                boolean lowerPack = i == 0 && ids[i].startsWith("Camping_Pack_");
                if (lowerPack) {
                    // The lower pack carries its contents in metadata, so only the resource type path matches it.
                    eq(ids[i], inDoc.getString("ResourceTypeId").getValue());
                    check(Files.exists(Path.of("Server/Item/ResourceTypes", ids[i] + ".json")), "resource type exists " + ids[i]);
                    String rtIcon = Files.readString(Path.of("Server/Item/ResourceTypes", ids[i] + ".json")).replaceAll("\\s", "");
                    check(rtIcon.contains("\"Icon\":\"Icons/ResourceTypes/"), "resource type icon under Icons/ResourceTypes " + ids[i]);
                    var packItem = org.bson.BsonDocument.parse(Files.readString(dir.resolve(ids[i] + ".json")));
                    eq(ids[i], packItem.getArray("ResourceTypes").get(0).asDocument().getString("Id").getValue());
                } else {
                    eq(ids[i], inDoc.getString("ItemId").getValue());
                }
                eq(qty[i], in.get(i).asDocument().getInt32("Quantity").getValue());
                piles += (qty[i] + 99) / 100;
                String file = ids[i] + ".json";
                check(lowerPack || zipNames.isEmpty() || zipNames.contains(file) || Files.exists(dir.resolve(file)), "id exists " + ids[i]);
            }
            check(piles <= 40, row[0] + " piles");
            var bench = r.getArray("BenchRequirement").get(0).asDocument();
            eq("Crafting", bench.getString("Type").getValue());
            eq("OrbisHorizon_Bench", bench.getString("Id").getValue());
            eq("OrbisHorizon_Camp", bench.getArray("Categories").get(0).asString().getValue());
            eq((Integer) row[1], bench.getInt32("RequiredTierLevel").getValue());
            eq(3, r.getInt32("TimeSeconds").getValue());
            check(!r.containsKey("KnowledgeRequired"), row[0] + " no knowledge");
        }
        for (String crate : new String[] {"Camping_Tent_Crate", "Camping_Tent_Big_Crate"}) {
            check(!Files.readString(dir.resolve(crate + ".json")).contains("\"Recipe\""), crate + " has no recipe");
        }
    }

    /** Small tent: the outside Kweebec chest exists and every block fits the no-placement box. */
    private static void smallTentChest() throws Exception {
        String j = Files.readString(Path.of("Server/Prefabs/Camping_Tent.prefab.json"));
        String blocks = j.substring(j.indexOf("\"blocks\""), j.indexOf("\"fluids\""));
        var m = java.util.regex.Pattern.compile(
                "\\{\\s*\"x\": (-?\\d+),\\s*\"y\": (-?\\d+),\\s*\"z\": (-?\\d+),\\s*\"name\": \"([^\"]+)\"(?:(?!\"x\")[\\s\\S])*?(?:\"rotation\": (\\d+))?\\s*\\}(?=,\\s*\\{|\\s*\\])").matcher(blocks);
        boolean chest = false;
        int[] mn = Deployables.TENT_PLACE_MIN, mx = Deployables.TENT_PLACE_MAX;
        while (m.find()) {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
            if (m.group(4).equals("Empty")) {
                continue;
            }
            check(x >= mn[0] && x <= mx[0] && y >= mn[1] && y <= mx[1] && z >= mn[2] && z <= mx[2],
                    "block in box " + m.group(4) + " " + x + "," + y + "," + z);
            if (m.group(4).equals("Furniture_Kweebec_Chest_Small") && x == 1 && y == 0 && z == 3) {
                chest = true;
                check(m.group(5) == null || m.group(5).equals("0"), "chest rotation");
            }
        }
        check(chest, "chest at (1, 0, 3)");
    }

    private static void balloonVoyageRefusal() {
        eq("crew.voyage.noCaptain", BalloonVoyage.departureRefusalKey(false, 0, 3, true));
        check(BalloonVoyage.departureRefusalKey(true, 2, 3, true) == null, "captain plus 2 on 3 seats");
        eq("error.balloonFull", BalloonVoyage.departureRefusalKey(true, 3, 3, true));
        eq("crew.voyage.handsFull", BalloonVoyage.departureRefusalKey(true, 0, 3, false));
    }

    private static void balloonLandingCandidates() {
        List<int[]> c = BalloonVoyage.landingCandidates(100.5, 0.5, 0.5, 0.5); // coming from the west
        eq(9, c.size());
        check(c.get(0)[0] == 100 && c.get(0)[1] == 0, "destination first");
        check(c.get(4)[0] == 84 && c.get(4)[1] == 0, "16 blocks back last of the back row");
        for (int[] p : c) {
            check(Math.hypot(p[0] + 0.5 - 100.5, p[1] + 0.5 - 0.5) <= 16.5, "within 16 blocks");
        }
    }

    private static void balloonLandingCandidatesAllBlocked() {
        StructureShape s = StructureShape.ofCells(List.of(new StructureShape.Cell(0, 9, 8, "A"), new StructureShape.Cell(12, 30, 20, "B")));
        List<int[]> c = BalloonVoyage.landingCandidates(0.5, 0.5, 100.5, 0.5);
        List<StationZone.Zone> zones = List.of(new StationZone.Zone("A", 0, 0, -1000, 1000));
        check(BalloonVoyage.firstClear(zones, s, Rotation.None, c) == null, "every candidate in the zone");
        check(BalloonVoyage.firstClear(List.of(), s, Rotation.None, c) == c.get(0), "no zone: destination");
    }

    private static void balloonVoyageStopOnce() {
        BalloonVoyage v = new BalloonVoyage("M", VoyagePlanner.Kind.MARKER, 0, 0, 120, java.util.UUID.randomUUID(), 0L);
        check(v.stop(), "first stop");
        check(!v.stop(), "second stop ignored");
        check(v.phase == BalloonVoyage.Phase.DESCEND && v.stopped, "descends");
    }

    private static void balloonCruiseCeiling() {
        eq(160, BalloonVoyage.cruiseY(120, Integer.MIN_VALUE, 319, 36)); // 120 + 40
        eq(273, BalloonVoyage.cruiseY(260, Integer.MIN_VALUE, 319, 36)); // 319 - 10 - 36
    }

    private static void stationZoneCell() {
        StationZone.Zone z = new StationZone.Zone("Gare", 0.5, 0.5, 9, 76);
        check(StationZone.cellTouches(z, 31, 20, 0), "inside radius");
        check(!StationZone.cellTouches(z, 33, 20, 0), "outside radius");
        check(!StationZone.cellTouches(z, 0, 8, 0), "below bottom");
        check(!StationZone.cellTouches(z, 0, 77, 0), "above top");
        check(StationZone.cellTouches(z, 0, 76, 0), "top included");
    }

    private static void stationZoneCorner() {
        // Corner cell (23, 0, 23) is within 32 of (0.5, 0.5), the next diagonal one is not.
        StationZone.Zone z = new StationZone.Zone("Gare", 0.5, 0.5, -5, 60);
        check(StationZone.cellTouches(z, 23, 0, 23), "corner touches");
        check(!StationZone.cellTouches(z, 24, 0, 24), "next one does not");
    }

    private static void stationZoneOtherWorld() {
        check(StationZone.blockingStation(List.of(), null, new Vector3i(0, 0, 0), Rotation.None) == null,
                "no zone, no block");
    }

    private static void staleEntryUnknownIsPresent() {
        // World not loaded (no universe in tests): the entry is unknown, so it counts as present and is never dropped.
        BalloonRegistry.Entry e = new BalloonRegistry.Entry("no_such_world", 0, 0, 0,
                com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, Deployables.AIRSHIP_KIND, UUID.randomUUID());
        check(!BalloonRegistry.removeIfGone(e), "unknown entry is kept");
    }

    private static void walkLegsAisle() {
        // From the stool to the helm: aisle west, aisle east, door leg on the stool side, door leg on the helm side.
        java.util.List<double[]> a = AirshipCrew.walkLegs(new double[]{-2, 8, 4}, CrewRecord.Post.HELM);
        eq(4, a.size());
        eq(-1.0, a.get(0)[0]);
        eq(4.4, a.get(0)[2]);
        eq(0.0, a.get(1)[0]);
        eq(4.4, a.get(1)[2]);
        eq(3.5, a.get(2)[2]);
        eq(0.5, a.get(3)[2]);
        // From the helm to the stool: door legs, then aisle east, then aisle west.
        java.util.List<double[]> b = AirshipCrew.walkLegs(new double[]{0, 8, -10}, CrewRecord.Post.STOOL);
        eq(4, b.size());
        eq(0.5, b.get(0)[2]);
        eq(3.5, b.get(1)[2]);
        eq(0.0, b.get(2)[0]);
        eq(-1.0, b.get(3)[0]);
        // Already on the east aisle point: only the two door legs remain.
        eq(2, AirshipCrew.walkLegs(new double[]{0, 8, 4.4}, CrewRecord.Post.HELM).size());
        // Just south of the door going to the stool: back out to the wide aisle (east, then west), clear of the counter.
        eq(2, AirshipCrew.walkLegs(new double[]{0, 8, 3.5}, CrewRecord.Post.STOOL).size());
    }

    private static void airshipLimitCounts() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        check(AirshipLimit.ownsAny(a, java.util.Arrays.asList(null, b, a)), "owned among others");
        check(!AirshipLimit.ownsAny(a, java.util.Arrays.asList(null, b)), "not owned");
        check(!AirshipLimit.ownsAny(null, java.util.Arrays.asList((UUID) null)), "unowned never counts");
    }

    private static void airshipCrewSpecUnchanged() {
        Deployables.CrewSpec c = Deployables.crewOf(Deployables.AIRSHIP_KIND);
        check(c != null, "airship has a crew spec");
        check(java.util.Arrays.equals(new int[]{0, 8, -10}, c.helm()), "helm");
        check(java.util.Arrays.equals(new int[]{-2, 8, 4}, c.rest()), "rest");
        check(java.util.Arrays.equals(new int[]{0, 8, 2}, c.door()), "door");
        check(java.util.Arrays.equals(new int[]{0, 8, 9}, c.captainSeat()), "captain chair");
        check(java.util.Arrays.equals(new int[]{0, 9, 8}, c.desk()), "desk");
        eq(4, c.walk().legs(new double[]{-2, 8, 4}, CrewRecord.Post.HELM).size());
        eq(null, Deployables.crewOf(Deployables.STEAMBOAT_PUBLIC_KIND));
        eq(null, Deployables.crewOf(Deployables.BASIC_KIND));
    }

    private static void shipFeatureFlags() {
        Deployables.AirshipSpec s = Deployables.AIRSHIP.airship;
        check(s.hasCrew() && s.voyage && s.recall && s.ladders && s.onePerPlayer, "airship declares all its features");
    }

    private static void limitIgnoresOtherShips() {
        check(AirshipLimit.countsForLimit(Deployables.AIRSHIP), "airship counts");
        check(!AirshipLimit.countsForLimit(Deployables.TENT), "tent does not count");
        check(!AirshipLimit.countsForLimit(Deployables.BALLOON), "balloon does not count");
    }

    private static void landingOwnerLimit() {
        UUID p = UUID.randomUUID();
        check(Ownership.landingOwner(Ownership.NONE, p, "Ann", false).owner() == null, "already owns one: stays unowned");
        check(p.equals(Ownership.landingOwner(Ownership.NONE, p, "Ann", true).owner()), "free: becomes owner");
        check(Ownership.landingOwner(Ownership.NONE, p, "Ann", false).id() != null, "always an id");
    }

    /** Under armour that hides the haircut, the game swaps it for the generic haircut of the same length (HaircutFallbacks). */
    private static void helmetHaircut() {
        check("Morning".equals(SkinAttachments.helmetHaircutId("Morning", false, true, "Short")), "not hidden: own haircut");
        check("GenericShort".equals(SkinAttachments.helmetHaircutId("Morning", true, true, "Short")), "hidden, needs generic: GenericShort");
        check("GenericLong".equals(SkinAttachments.helmetHaircutId("Braid", true, true, "Long")), "hidden, long: GenericLong");
        check("Puffy".equals(SkinAttachments.helmetHaircutId("Puffy", true, false, "Short")), "hidden, fits under armour: own haircut");
        check("Morning".equals(SkinAttachments.helmetHaircutId("Morning", true, true, null)), "no hair type: own haircut");
    }

    private static void balloonCrewSpec() {
        Deployables.CrewSpec c = Deployables.crewOf(Deployables.BALLOON_KIND);
        check(c != null, "large balloon has a crew spec");
        check(java.util.Arrays.equals(new int[]{0, 9, 8}, c.helm()) && java.util.Arrays.equals(new int[]{1, 9, 9}, c.rest()), "posts");
        check(c.door() == null && c.desk() == null && c.captainSeat() == null, "no door, desk or fixed seat");
        check(Deployables.crewOf(Deployables.BASIC_KIND) == null && Deployables.crewOf(Deployables.TRANSPORT_KIND) == null, "no pilot");
        check(Deployables.voyageOf(Deployables.BALLOON_KIND) && !Deployables.voyageOf(Deployables.BASIC_KIND), "voyage flag");
        check(Deployables.voyageOf(Deployables.AIRSHIP_KIND), "airship unchanged");
    }

    private static void hireBlockRule() {
        check(AirshipHireInteraction.isHireBlock("Hotair_Balloon_Chain", Deployables.BALLOON_KIND), "chain hires");
        check(!AirshipHireInteraction.isHireBlock("Airship_Helm", Deployables.BALLOON_KIND), "helm id on a balloon");
        check(AirshipHireInteraction.isHireBlock("Airship_Helm", Deployables.AIRSHIP_KIND), "airship helm");
        check(!AirshipHireInteraction.isHireBlock("Hotair_Balloon_Chain", Deployables.BASIC_KIND), "small balloon has no pilot");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void protectionRules() {
        eq(true, PassengerProtection.shouldAdd(false, PassengerProtection.Mode.FULL));
        eq(false, PassengerProtection.shouldAdd(true, PassengerProtection.Mode.FULL));
        eq(false, PassengerProtection.shouldAdd(false, PassengerProtection.Mode.DAMAGE));
        eq(false, PassengerProtection.shouldAdd(false, PassengerProtection.Mode.OFF));
        eq(true, PassengerProtection.shouldRemove(true, false));
        eq(false, PassengerProtection.shouldRemove(false, false));
        eq(false, PassengerProtection.shouldRemove(true, true));
        eq(PassengerProtection.Mode.FULL, PassengerProtection.mode());
    }

    /** Map marker of a station: centre of the landing pad, on top of it, rotated with the station. */
    static void ownershipRules() {
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID p = java.util.UUID.randomUUID();
        Ownership l = Ownership.landingOwner(Ownership.NONE, p, "Ann");
        eq(p, l.owner());
        eq("Ann", l.ownerName());
        if (l.id() == null) throw new AssertionError("id expected");
        Ownership shared = Ownership.of(a, "Paul").withShared(true);
        Ownership k = Ownership.landingOwner(shared, p, "Ann");
        eq(a, k.owner());
        eq("Paul", k.ownerName());
        eq(true, k.shared());
        eq(shared.id(), k.id());
        Ownership n = Ownership.landingOwner(Ownership.NONE, null, null);
        eq(null, n.owner());
        if (n.id() == null) throw new AssertionError("id expected");
        org.bson.BsonDocument d = new org.bson.BsonDocument();
        shared.writeTo(d);
        eq(shared, Ownership.readFrom(d));
        eq(Ownership.NONE, Ownership.readFrom(new org.bson.BsonDocument()));
    }

    static void ownedMarkerRules() {
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        Ownership own = Ownership.of(a, "A");
        check(OwnedMarkerProvider.visibleTo(own, a), "owner sees it");
        check(!OwnedMarkerProvider.visibleTo(own, b), "other does not see unshared");
        check(OwnedMarkerProvider.visibleTo(own.withShared(true), b), "other sees shared");
        check(!OwnedMarkerProvider.visibleTo(Ownership.NONE, a), "NONE hidden");
        check(!OwnedMarkerProvider.visibleTo(Ownership.NONE.withShared(true), a), "NONE shared hidden");
        String[][] table = {{"balloon", "Owned_Balloon.png"}, {"balloon_basic", "Owned_Balloon_Basic.png"},
                {"balloon_transport", "Owned_Balloon_Transport.png"}, {"airship", "Owned_Airship.png"}, {"steamboat", "Owned_Steamboat.png"},
                {"tent", "Owned_Tent.png"}, {"tent_big", "Owned_Tent_Big.png"}};
        String en, fr;
        try {
            en = Files.readString(Path.of("Server/Languages/en-US/server.lang"));
            fr = Files.readString(Path.of("Server/Languages/fr-FR/server.lang"));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        for (String[] row : table) {
            eq(row[1], OwnedMarkerProvider.icon(row[0]));
            for (boolean other : new boolean[]{false, true}) {
                String key = OwnedMarkerProvider.nameKey(row[0], other).substring("server.".length());
                check(en.contains("\n" + key + " = "), "en " + key);
                check(fr.contains("\n" + key + " = "), "fr " + key);
            }
        }
        check(OwnedMarkerProvider.icon("balloon_public") == null, "public");
        check(OwnedMarkerProvider.icon("station") == null, "station");
        check(OwnedMarkerProvider.markerId(a).startsWith("orbis_horizon_owned:"), "marker id");
    }

    static void mapShareRules() {
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        check(MapCommand.parseId("abc") == null, "bad id");
        eq(a, MapCommand.parseId(a.toString()));
        check(MapCommand.canToggle(Ownership.of(a, "P"), a, false), "owner");
        check(!MapCommand.canToggle(Ownership.of(a, "P"), b, false), "other");
        check(MapCommand.canToggle(Ownership.of(a, "P"), b, true), "op");
        check(!MapCommand.canToggle(Ownership.NONE, b, false), "none");
        check(Ownership.NONE.owner() == null, "no owner is refused by the command even for an op");
    }

    private static TransitNetwork.Station testStation(String kind) {
        return new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), Rotation.Ninety, null, null,
                new java.util.ArrayList<>(), kind);
    }

    static void stationMarkerPoint() {
        // Ninety sends the cell (19, 7, 0) to (0, 7, -19), centre (0.5, 7, -18.5)
        eq(new org.joml.Vector3d(100.5, 71, 181.5), StationMarkerProvider.markerPoint(testStation(Deployables.STATION_KIND)));
    }

    static void stationSpecPoints() {
        TransitNetwork.Station balloon = testStation(Deployables.STATION_KIND);
        // Ninety sends (x, z) to (z, -x): master (4, 0, 7) -> (7, 0, -4), marker (19, 7, 0) -> (0, 7, -19)
        eq(new org.joml.Vector3d(107.5, 64.05, 196.5), StationMaster.masterPoint(balloon));
        eq(new org.joml.Vector3d(100.5, 71, 181.5), StationMarkerProvider.markerPoint(balloon));
        TransitNetwork.Station boat = testStation(Deployables.BOAT_STATION_KIND);
        // master (-4, 0, 5) -> (5, 0, 4), marker (1, 0, 8) -> (8, 0, -1)
        eq(new org.joml.Vector3d(105.5, 64.05, 204.5), StationMaster.masterPoint(boat));
        eq(new org.joml.Vector3d(108.5, 64, 199.5), StationMarkerProvider.markerPoint(boat));
        check(StationSpec.of("unknown") == StationSpec.BALLOON, "unknown kind uses the balloon spec");
        check(StationSpec.of(Deployables.BOAT_STATION_KIND).soilLayerY() == null, "boat station has no soil layer");
        eq(64 - 10, StationZone.zoneOf(boat).bottomY());
        eq(64 + Deployables.STATION_GRASS_Y, StationZone.zoneOf(balloon).bottomY());
    }

    static void boatStationOrigin() {
        TransitNetwork.Station boat = testStation(Deployables.BOAT_STATION_KIND);
        // marker (1, 0, 8) -> (8, 0, -1) with Ninety, feet one block up
        eq(new org.joml.Vector3d(108.5, 65.1, 199.5), StationSpec.teleportPoint(boat));
        eq(TransitAutopilot.dropPoints(testStation(Deployables.STATION_KIND).origin, Rotation.Ninety, 1).get(0),
                StationSpec.teleportPoint(testStation(Deployables.STATION_KIND)));
        // pier end (1, 0, 11) with Ninety -> (11, 0, -1); computed origin (100, 70, 200) -> column (111, 199)
        org.joml.Vector3i computed = new org.joml.Vector3i(100, 70, 200);
        // water surface at y 66 (bank higher than the water): origin y = 67
        DeployPlacement.CellState lake = (x, y, z) -> x == 111 && z == 199 ? (y <= 66 && y >= 60 ? 1 : (y < 60 ? 2 : 0)) : 2;
        eq(new org.joml.Vector3i(100, 67, 200), DeployPlacement.boatStationOrigin(lake, computed, Rotation.Ninety));
        DeployPlacement.CellState dry = (x, y, z) -> y < 64 ? 2 : 0;
        check(DeployPlacement.boatStationOrigin(dry, computed, Rotation.Ninety) == null, "no water: refused");
        DeployPlacement.CellState walled = (x, y, z) -> 2;
        check(DeployPlacement.boatStationOrigin(walled, computed, Rotation.Ninety) == null, "solid column: refused");
    }

    static void boatStationOriginBlockerCell() {
        org.joml.Vector3i computed = new org.joml.Vector3i(100, 70, 200);
        DeployPlacement.CellState lake = (x, y, z) -> x == 111 && z == 199 ? (y <= 66 && y >= 60 ? 1 : (y < 60 ? 2 : 0)) : 2;
        check(DeployPlacement.boatStationOriginBlocker(lake, computed, Rotation.Ninety) == null, "lake: nothing blocks");
        DeployPlacement.CellState walled = (x, y, z) -> 2;
        eq(new org.joml.Vector3i(111, 73, 199), DeployPlacement.boatStationOriginBlocker(walled, computed, Rotation.Ninety));
    }

    private static void shipKinds() throws Exception {
        check(Deployables.SHIPS.contains(Deployables.AIRSHIP), "SHIPS has AIRSHIP");
        check(AirshipManager.spec(Deployables.AIRSHIP) == Deployables.AIRSHIP.airship, "spec(kind)");
        check(AirshipManager.pivot(Deployables.AIRSHIP).equals(Deployables.AIRSHIP.airship.pivot), "pivot(kind)");
        boolean threw = false;
        try { AirshipManager.spec(Deployables.TENT); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "spec(non-ship) throws");
        StructureShape.Cell boatAnchor = prefabShape("Steamboat.prefab.json").cells().stream()
                .filter(c -> "Airship_Helm".equals(c.baseName()) && c.filler() == 0).findFirst().orElse(null);
        check(boatAnchor != null, "steamboat helm cell found");
        check(boatAnchor != null && boatAnchor.x() == 0 && boatAnchor.y() == 5 && boatAnchor.z() == 4, "steamboat anchor at (0, 5, 4)");
    }

    private static void submergedCells() {
        // Tank 3 x 2 x 3 (floor y -4, walls y -3, empty centre) closed by a lid at y -2, plus a block at y 0 (above the waterline).
        List<StructureShape.Cell> cells = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.add(new StructureShape.Cell(x, -4, z, "Wood_Hardwood_Planks", 0));
                cells.add(new StructureShape.Cell(x, -2, z, "Wood_Hardwood_Planks", 0));
                if (x != 0 || z != 0) {
                    cells.add(new StructureShape.Cell(x, -3, z, "Wood_Hardwood_Planks", 0));
                }
            }
        }
        cells.add(new StructureShape.Cell(0, 0, 0, "Wood_Hardwood_Planks", 0));
        StructureShape tank = StructureShape.ofCells(cells);
        List<org.joml.Vector3i> sub = SteamboatWater.submergedCells(tank, -3);
        check(sub.size() == 18, "submergedCells counts 9 + 8 + 1 cavity, got " + sub.size());
        check(sub.contains(new org.joml.Vector3i(0, -3, 0)), "submergedCells has the closed cavity");
        check(sub.stream().noneMatch(v -> v.y > -3), "submergedCells excludes cells above the waterline");
        // Without a lid the hold is open to the outside: only blocks count.
        List<StructureShape.Cell> open = new ArrayList<>();
        for (StructureShape.Cell c : cells) {
            if (c.y() != -2) {
                open.add(c);
            }
        }
        check(SteamboatWater.submergedCells(StructureShape.ofCells(open), -3).size() == 17, "open hold is not a cavity");
    }

    private static void hullOnWater() {
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(0, -4, 0, "Wood_Hardwood_Planks", 0),
                new StructureShape.Cell(0, -3, 0, "Wood_Hardwood_Planks", 0),
                new StructureShape.Cell(0, -2, 0, "Wood_Hardwood_Planks", 0)));
        AirshipManager.Hull hull = AirshipManager.hullFor(s, new Vector3f(0, 0, 0), -3);
        check(hull.size() == 3 && hull.wet.length == 3, "hull has 3 cells");
        int wetCount = 0;
        for (boolean w : hull.wet) {
            if (w) {
                wetCount++;
            }
        }
        check(wetCount == 2, "two cells at or under the waterline are wet, got " + wetCount);
        // Lake: water at y -4 and -3 for |x|, |z| <= 5, air above, block elsewhere.
        AirshipManager.CellTest lake = (x, y, z, wet) -> {
            boolean inside = Math.abs(x) <= 5 && Math.abs(z) <= 5;
            boolean water = inside && (y == -4 || y == -3);
            boolean air = inside && y >= -2;
            return wet ? !water : !air;
        };
        AirshipManager.CellTest higher = (x, y, z, wet) -> {
            boolean inside = Math.abs(x) <= 5 && Math.abs(z) <= 5;
            boolean water = inside && (y == -4 || y == -3 || y == -2);
            boolean air = inside && y >= -1;
            return wet ? !water : !air;
        };
        AirshipManager.CellTest unknown = (x, y, z, wet) -> wet;
        check(AirshipManager.blocked(lake, hull, 0.5, 0, 0.5, 0, 0) == 0, "afloat");
        check(AirshipManager.blocked(lake, hull, 0.5, 0, 0.5, Math.toRadians(37), 0) == 0, "afloat turned");
        check(AirshipManager.blocked(lake, hull, 5.6, 0, 0.5, 0, 0) > 0, "shore beside: a corner sample is in the bank");
        check(AirshipManager.blocked(lake, hull, 0.5, 1, 0.5, 0, 0) > 0, "lifted: wet cell in air");
        check(AirshipManager.blocked(higher, hull, 0.5, 0, 0.5, 0, 0) > 0, "higher water at the dry layer");
        check(AirshipManager.blocked(unknown, hull, 0.5, 0, 0.5, 0, 0) > 0, "unloaded wet cell blocks");
        AirshipManager.Hull dry = AirshipManager.hullFor(s, new Vector3f(0, 0, 0), null);
        boolean anyWet = false;
        for (boolean w : dry.wet) {
            anyWet |= w;
        }
        check(!anyWet, "a dry hull has no wet cell");
        // Dry hull in the same lake: water blocks a dry cell (liquids block), modelled by a test that blocks any water.
        AirshipManager.CellTest waterBlocks = (x, y, z, wet) -> y == -4 || y == -3;
        check(AirshipManager.blocked(waterBlocks, dry, 0.5, 0, 0.5, 0, 0) > 0, "water blocks a dry hull");
    }

    private static void wheelTable() {
        check(Arrays.equals(PaddleWheels.wheelsFor(false, false, 0), new PaddleWheels.Spin[]{PaddleWheels.Spin.STOP, PaddleWheels.Spin.STOP}), "arrêt");
        check(Arrays.equals(PaddleWheels.wheelsFor(true, false, 0), new PaddleWheels.Spin[]{PaddleWheels.Spin.FWD_FAST, PaddleWheels.Spin.FWD_FAST}), "avant");
        check(Arrays.equals(PaddleWheels.wheelsFor(true, false, 1), new PaddleWheels.Spin[]{PaddleWheels.Spin.FWD_SLOW, PaddleWheels.Spin.FWD_FAST}), "avant, virage à gauche");
        check(Arrays.equals(PaddleWheels.wheelsFor(true, false, -1), new PaddleWheels.Spin[]{PaddleWheels.Spin.FWD_FAST, PaddleWheels.Spin.FWD_SLOW}), "avant, virage à droite");
        check(Arrays.equals(PaddleWheels.wheelsFor(true, true, 0), new PaddleWheels.Spin[]{PaddleWheels.Spin.REV_SLOW, PaddleWheels.Spin.REV_SLOW}), "arrière");
        check(Arrays.equals(PaddleWheels.wheelsFor(true, true, 1), new PaddleWheels.Spin[]{PaddleWheels.Spin.REV_SLOW, PaddleWheels.Spin.REV_VERY_SLOW}), "arrière, poupe vers la droite");
        check(Arrays.equals(PaddleWheels.wheelsFor(true, true, -1), new PaddleWheels.Spin[]{PaddleWheels.Spin.REV_VERY_SLOW, PaddleWheels.Spin.REV_SLOW}), "arrière, poupe vers la gauche");
        check(Arrays.equals(PaddleWheels.wheelsFor(false, false, 1), new PaddleWheels.Spin[]{PaddleWheels.Spin.REV_SLOW, PaddleWheels.Spin.FWD_SLOW}), "sur place à gauche");
        check(Arrays.equals(PaddleWheels.wheelsFor(false, false, -1), new PaddleWheels.Spin[]{PaddleWheels.Spin.FWD_SLOW, PaddleWheels.Spin.REV_SLOW}), "sur place à droite");
    }

    private static void paddleSplashDue() {
        long never = Long.MIN_VALUE;
        check(PaddleWheels.splashDue(200, 1000, never, 1000), "premier coup de pale dès le départ");
        check(!PaddleWheels.splashDue(200, 1000, 1000, 1150), "pas deux fois dans le même cycle");
        check(PaddleWheels.splashDue(200, 1000, 1000, 1200), "cycle suivant");
        check(PaddleWheels.splashDue(200, 1000, 1000, 1390), "retard : un seul coup dû");
        check(!PaddleWheels.splashDue(200, 1000, 1390, 1399), "déjà joué dans ce cycle");
        check(!PaddleWheels.splashDue(0, 1000, never, 1500), "roue arrêtée");
        check(!PaddleWheels.splashDue(200, 1000, never, 900), "avant le départ");
    }

    /** The shuttle's propeller: forward at full speed, astern slowly, slowly forward when turning in place, otherwise still. */
    private static void propellerRules() {
        PaddleWheels.Spin f = PaddleWheels.Spin.FWD_FAST, sl = PaddleWheels.Spin.FWD_SLOW, st = PaddleWheels.Spin.STOP;
        check(PaddleWheels.propellerFor(true, false, 0) == f, "avant");
        check(PaddleWheels.propellerFor(true, false, 1) == f && PaddleWheels.propellerFor(true, false, -1) == f, "avant en virage");
        check(PaddleWheels.propellerFor(true, true, 0) == PaddleWheels.Spin.REV_SLOW, "arrière");
        check(PaddleWheels.propellerFor(false, false, 1) == sl && PaddleWheels.propellerFor(false, false, -1) == sl, "sur place");
        check(PaddleWheels.propellerFor(false, false, 0) == st, "arrêt");
        PaddleWheels.State shuttle = new PaddleWheels.State("Propeller");
        check(shuttle.count() == 1 && new PaddleWheels.State().count() == 2, "un seul nœud pour la navette, deux roues sinon");
        check("Propeller_FwdFast".equals(shuttle.animationId(0, f)) && shuttle.animationId(0, st) == null, "nom de l'hélice");
        check("Wheel_Right_FwdFast".equals(new PaddleWheels.State().animationId(1, f)), "noms des roues inchangés");
        // The hold and cycle-end rules are the wheels' (same nextSpin): a change waits 300 ms and the end of the cycle.
        check(PaddleWheels.nextSpin(f, 0, st, 1000, 1100) == f && PaddleWheels.nextSpin(f, 0, st, 1000, 1400) == st, "arrêt à la fin du cycle");
        // The shuttle is fuelless, pilotless and without engines: its propeller turns, unless off or landing.
        check(!PaddleWheels.still(false, false, false, false, true, true, true), "navette en route : l'hélice tourne");
    }

    /** The propeller cell is waterlogged: kept by drain, in the prefab's submerged cells, the block is Material Empty. */
    private static void propellerWater() throws Exception {
        StructureShape shape = prefabShape("Steamboat_Public.prefab.json");
        List<Vector3i> kept = SteamboatWater.waterloggedCells(shape, 0);
        eq(1, kept.size());
        check(kept.get(0).x == 0 && kept.get(0).y == 0 && kept.get(0).z == 5, "waterlogged cell is (0, 0, 5)");
        check(SteamboatWater.submergedCells(shape, 0).contains(new Vector3i(0, 0, 5)), "the propeller cell is submerged (flooded after removal)");
        check(!SteamboatWater.submergedCells(shape, 0).contains(new Vector3i(0, 0, 6)), "cell z 6 is outside the hull: never drained");
        check(SteamboatWater.waterloggedCells(shape, -1).isEmpty(), "above the waterline nothing is kept");
        check(SteamboatWater.waterloggedCells(prefabShape("Steamboat.prefab.json"), -3).isEmpty(), "the steamboat has no waterlogged block");
        var item = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Item/Items/Containers/Vehicles/Steamboat_Propeller.json")));
        eq("Empty", item.getDocument("BlockType").getString("Material").getValue());
    }

    private static void propellerNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (PaddleWheels.Spin sp : new PaddleWheels.Spin[]{PaddleWheels.Spin.FWD_FAST, PaddleWheels.Spin.FWD_SLOW,
                PaddleWheels.Spin.REV_FAST, PaddleWheels.Spin.REV_SLOW}) {
            names.add(PaddleWheels.propellerAnimationId("Propeller", sp));
        }
        check(names.size() == 4, "4 noms distincts");
        check(PaddleWheels.Spin.FWD_FAST.periodMs() == 200 && PaddleWheels.Spin.REV_SLOW.periodMs() == 400, "périodes");
    }

    private static void wheelNames() {
        check("Wheel_Left_FwdFast".equals(PaddleWheels.animationId(false, PaddleWheels.Spin.FWD_FAST)), "nom gauche");
        check("Wheel_Right_RevVerySlow".equals(PaddleWheels.animationId(true, PaddleWheels.Spin.REV_VERY_SLOW)), "nom droite");
        check(PaddleWheels.animationId(false, PaddleWheels.Spin.STOP) == null, "arrêt sans nom");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (PaddleWheels.Spin sp : PaddleWheels.Spin.values()) {
            if (sp == PaddleWheels.Spin.STOP) {
                continue;
            }
            names.add(PaddleWheels.animationId(false, sp));
            names.add(PaddleWheels.animationId(true, sp));
        }
        check(names.size() == 12, "12 noms distincts");
        check(PaddleWheels.Spin.FWD_SLOW.periodMs() == 400, "période lente");
        check(PaddleWheels.Spin.REV_VERY_SLOW.periodMs() == 800, "période très lente");
    }

    private static void wheelHoldAndCycle() {
        PaddleWheels.Spin f = PaddleWheels.Spin.FWD_FAST;
        PaddleWheels.Spin sl = PaddleWheels.Spin.FWD_SLOW;
        check(PaddleWheels.nextSpin(f, 0, sl, 1000, 1100) == f, "pas stable depuis 300 ms");
        check(PaddleWheels.nextSpin(f, 0, sl, 1000, 1310) == f, "cycle pas fini");
        check(PaddleWheels.nextSpin(f, 0, sl, 1000, 1400) == sl, "fin de cycle");
        check(PaddleWheels.nextSpin(f, 0, sl, 1000, 1390) == sl, "dans la tolérance");
        check(PaddleWheels.nextSpin(PaddleWheels.Spin.STOP, 0, f, 0, 299) == PaddleWheels.Spin.STOP, "attente 299");
        check(PaddleWheels.nextSpin(PaddleWheels.Spin.STOP, 0, f, 0, 300) == f, "attente 300");
        check(PaddleWheels.atCycleEnd(0, 0, 123, 17), "période nulle");
    }

    private static void steamboatKind() {
        check(Deployables.SHIPS.equals(List.of(Deployables.AIRSHIP, Deployables.STEAMBOAT)), "SHIPS");
        check(Deployables.forPrefab("Steamboat.prefab.json") == Deployables.STEAMBOAT, "forPrefab");
        Deployables.AirshipSpec b = Deployables.STEAMBOAT.airship;
        check(b.hasCrew() && b.voyage && !b.recall && !b.ladders && !b.onePerPlayer, "boat has a pilot and voyages but no recall, ladders, limit");
        check(!AirshipLimit.countsForLimit(Deployables.STEAMBOAT), "no boat limit");
        check(b.pivot.equals(new Vector3f(0, 4, 5)) && "Steamboat_Crate".equals(b.crateItemId), "boat spec");
        check("Airship_Cloudwork_TurnLeft".equals(Deployables.AIRSHIP.airship.turnLeftModelId), "airship turn model kept");
        check("Airship_Cloudwork_TurnRight".equals(Deployables.AIRSHIP.airship.turnRightModelId), "airship turn right kept");
        check(Deployables.AIRSHIP.airship.turnLeftSystems.length == 2 && Deployables.AIRSHIP.airship.turnRightSystems.length == 2, "airship turn systems kept");
        check("Airship_Burner_Flame".equals(Deployables.AIRSHIP.airship.flameSystemId), "airship flame kept");
        check("Steamboat".equals(b.turnLeftModelId) && "Steamboat".equals(b.turnRightModelId), "boat turn models");
        check("Airship_Exhaust_Smoke".equals(b.smokeSystemId) && "Steamboat_Chimney_Smoke_Thick".equals(b.flameSystemId), "boat smoke and flame");
    }

    /** Cells of a shape that exist with the same block name at the same place in the other shape, over the cell count. */
    static double commonRatio(StructureShape a, StructureShape b) {
        java.util.Set<String> other = new java.util.HashSet<>();
        for (StructureShape.Cell c : b.cells()) other.add(c.x() + "," + c.y() + "," + c.z() + "," + c.name());
        int n = 0;
        for (StructureShape.Cell c : a.cells()) if (other.contains(c.x() + "," + c.y() + "," + c.z() + "," + c.name())) n++;
        return a.cells().isEmpty() ? 0 : (double) n / a.cells().size();
    }

    private static void shipKindsOrder() throws Exception {
        StructureShape airship = prefabShape("The_Cloudwork.prefab.json");
        StructureShape boat = prefabShape("Steamboat.prefab.json");
        double r1 = commonRatio(airship, boat);
        double r2 = commonRatio(boat, airship);
        System.out.println("shipKindsOrder: airship in boat " + r1 + ", boat in airship " + r2);
        check(r1 < 0.5 && r2 < 0.5, "ships do not confuse: " + r1 + " " + r2);
    }

    private static void smokeExhaustCells() throws Exception {
        eq(Set.of(new Vector3i(-1, 5, 12), new Vector3i(1, 5, 12)), Set.copyOf(ShipSmoke.exhaustCells(prefabShape("The_Cloudwork.prefab.json"))));
        eq(Set.of(new Vector3i(-2, 8, 11), new Vector3i(2, 8, 11)), Set.copyOf(ShipSmoke.exhaustCells(prefabShape("Steamboat.prefab.json"))));
    }

    private static void smokeLegacyAlias() {
        check(BalloonManager.legacyAlias("Airship_Exhaust_Pipe", "Metal_Iron_Pipe_Short"), "airship pipe alias");
        check(BalloonManager.legacyAlias("Steamboat_Chimney_Top", "Metal_Bronze_Pipe_Large_Mouthpiece"), "chimney alias");
        check(!BalloonManager.legacyAlias("Metal_Iron_Pipe_Short", "Airship_Exhaust_Pipe"), "no inverse airship");
        check(!BalloonManager.legacyAlias("Metal_Bronze_Pipe_Large_Mouthpiece", "Steamboat_Chimney_Top"), "no inverse chimney");
    }

    private static void steamboatThickSmoke() throws Exception {
        String moving = Files.readString(Path.of("Server/Models/Vehicles/Steamboat.json"));
        String idle = Files.readString(Path.of("Server/Models/Vehicles/Steamboat_Idle.json"));
        check(moving.contains("Steamboat_Chimney_Smoke_Thick"), "moving model uses thick smoke");
        check(!idle.contains("Steamboat_Chimney_Smoke_Thick"), "idle model keeps the light smoke");
        check(Files.exists(Path.of("Server/Particles/Vehicles/Steamboat/Spawners/Steamboat_Chimney_Smoke_Thick_Main.particlespawner")), "thick spawner exists");
    }

    private static void deskDecision() {
        AirshipCrew.CrewState h = AirshipCrew.CrewState.AT_HELM;
        eq(AirshipDesk.Action.NO_PILOT, AirshipDesk.decide(true, false, AirshipCrew.CrewState.ABSENT));
        eq(AirshipDesk.Action.NO_PILOT, AirshipDesk.decide(false, false, h));
        eq(AirshipDesk.Action.NOT_CAPTAIN, AirshipDesk.decide(false, true, h));
        eq(AirshipDesk.Action.START_NOW, AirshipDesk.decide(true, true, h));
        eq(AirshipDesk.Action.WALK_FIRST, AirshipDesk.decide(true, true, AirshipCrew.CrewState.SEATED));
        eq(AirshipDesk.Action.BUSY, AirshipDesk.decide(true, true, AirshipCrew.CrewState.WALKING));
        eq(AirshipDesk.Action.ABSENT, AirshipDesk.decide(true, true, AirshipCrew.CrewState.ABSENT));
    }

    private static void helmRefusal() {
        CrewRecord helm = new CrewRecord(UUID.randomUUID(), 1L, CrewRecord.Post.HELM);
        CrewRecord stool = helm.withPost(CrewRecord.Post.STOOL);
        eq(null, AirshipCrew.helmRefusalKey(null, AirshipCrew.CrewState.ABSENT));
        eq("crew.helm.held", AirshipCrew.helmRefusalKey(helm, AirshipCrew.CrewState.AT_HELM));
        eq("crew.helm.notSeated", AirshipCrew.helmRefusalKey(stool, AirshipCrew.CrewState.WALKING));
        eq(null, AirshipCrew.helmRefusalKey(stool, AirshipCrew.CrewState.SEATED));
    }

    private static void seatsExcludeStool() {
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(-2, 8, 4, "Airship_Stool"), new StructureShape.Cell(2, 8, 9, "Airship_Stool"),
                new StructureShape.Cell(1, 4, 5, "Airship_Stool"), new StructureShape.Cell(0, 0, 0, "Wood_Softwood_Planks")));
        Vector3i stool = new Vector3i(Deployables.crewOf(Deployables.AIRSHIP_KIND).rest()[0], Deployables.crewOf(Deployables.AIRSHIP_KIND).rest()[1], Deployables.crewOf(Deployables.AIRSHIP_KIND).rest()[2]);
        int all = AirshipPassengers.seats(s).size();
        eq(all - 1, AirshipPassengers.seats(s, Set.of(stool)).size());
    }

    private static void voyageList() {
        java.util.List<VoyagePlanner.Destination> all = java.util.List.of(
                new VoyagePlanner.Destination("far station", VoyagePlanner.Kind.STATION, 50, 0, 64),
                new VoyagePlanner.Destination("near", VoyagePlanner.Kind.MARKER, 20, 0, Integer.MIN_VALUE),
                new VoyagePlanner.Destination("m2", VoyagePlanner.Kind.MARKER, 300, 0, Integer.MIN_VALUE),
                new VoyagePlanner.Destination("m1", VoyagePlanner.Kind.MARKER, 100, 0, Integer.MIN_VALUE),
                new VoyagePlanner.Destination("s0", VoyagePlanner.Kind.STATION, 40, 0, 64));
        java.util.List<VoyagePlanner.Destination> out = VoyagePlanner.list(all, 0, 0);
        eq(4, out.size());
        eq("m1", out.get(0).name());
        eq("m2", out.get(1).name());
        eq("s0", out.get(2).name());
        eq("far station", out.get(3).name());
    }

    private static void arrivalNearStation() {
        double[] p = VoyagePlanner.stationArrival(0, 0, 500, 0, java.util.List.of());
        check(Math.abs(p[0] - 70) < 0.01 && Math.abs(p[1]) < 0.01, "arrival (70, 0)");
    }

    private static void arrivalRotatesOutOfZones() {
        double[] other = {90, 0};
        double[] p = VoyagePlanner.stationArrival(0, 0, 500, 0, java.util.List.<double[]>of(other));
        check(Math.abs(Math.hypot(p[0], p[1]) - 70) < 0.01, "70 from first pad");
        check(Math.hypot(p[0] - other[0], p[1] - other[1]) >= 70, "free of the other pad");
    }

    private static void cruiseHeight() {
        eq(190, VoyagePlanner.cruiseY(100, 150, 319));
        eq(140, VoyagePlanner.cruiseY(100, Integer.MIN_VALUE, 319));
        eq(309, VoyagePlanner.cruiseY(300, Integer.MIN_VALUE, 319));
    }

    private static void voyageTrip() {
        check(Math.abs(VoyagePlanner.tripSeconds(100, 64, 104, 5, 4) - 35) < 1e-9, "trip 35 s");
    }

    static void recallSpawnPoint() {
        double[] a = RecallPlanner.spawnPoint(0, 0, 500, 0);
        near(120, a[0]);
        near(0, a[1]);
        double[] b = RecallPlanner.spawnPoint(0, 0, 0, 0);
        near(120, b[0]);
        near(0, b[1]);
        double[] c = RecallPlanner.spawnPoint(10, 10, 10, -300);
        near(10, c[0]);
        near(-110, c[1]);
    }

    static void hoverPivotClearsStation() {
        double[] p = RecallPlanner.hoverPivot(0, 0, 74, 0);
        near(84, p[1]);
        for (int k = 0; k < 4; k++) {
            double yaw = k * Math.PI / 2;
            double[] h = RecallPlanner.hoverPivot(5, -7, 74, yaw);
            near(yaw, h[3]);
            near(5, h[0] - 10 * -Math.sin(yaw));
            near(-7, h[2] - 10 * -Math.cos(yaw));
        }
    }

    static void recallWaitPhase() {
        eq(-1, RecallPlanner.waitMessageAt(0));
        eq(-1, RecallPlanner.waitMessageAt(29_999));
        eq(30, RecallPlanner.waitMessageAt(30_000));
        eq(30, RecallPlanner.waitMessageAt(30_999));
        eq(-1, RecallPlanner.waitMessageAt(31_000));
        eq(-1, RecallPlanner.waitMessageAt(49_000));
        eq(10, RecallPlanner.waitMessageAt(50_500));
        eq(-1, RecallPlanner.waitMessageAt(59_000));
        eq(-1, RecallPlanner.waitMessageAt(-5));
        eq(60_000L, RecallPlanner.WAIT_MS);
    }

    static void recallWaitCrossed() {
        eq(-1, RecallPlanner.waitThresholdReached(0, Integer.MAX_VALUE));
        eq(30, RecallPlanner.waitThresholdReached(30_000, Integer.MAX_VALUE));
        eq(-1, RecallPlanner.waitThresholdReached(35_000, 30));
        eq(10, RecallPlanner.waitThresholdReached(52_500, 30));
        eq(-1, RecallPlanner.waitThresholdReached(52_500, 10));
        eq(10, RecallPlanner.waitThresholdReached(55_000, Integer.MAX_VALUE));
    }

    static List<double[]> stationBoardingPoints() {
        List<double[]> pts = new java.util.ArrayList<>();
        for (float[] p : Deployables.STATION_BOARDING_POINTS) {
            pts.add(new double[] {p[0] + 0.5, p[1], p[2] + 0.5});
        }
        return pts;
    }

    static void recallLadderSide() {
        // Station rotation None: pad centre at (19.5, 0.5), boarding points at x 12..14, so the ladder at x = 14.5 (pad - 5) is the nearest.
        List<double[]> pts = stationBoardingPoints();
        int[] side = RecallPlanner.ladderSide(19.5, 0.5, pts);
        eq(0, side[0]);
        eq(-1, side[1]);
        eq(-1, RecallPlanner.ladderSx(19.5, 0.5, pts, 0));
        eq(1, RecallPlanner.ladderSx(19.5, 0.5, pts, 2)); // the other ladder of a half-turned ship
        // Points on the other side of the pad: a half turn is not needed, the other ladder is nearer.
        List<double[]> east = new java.util.ArrayList<>();
        east.add(new double[] {25.5, 8, 0.5});
        int[] e = RecallPlanner.ladderSide(19.5, 0.5, east);
        eq(0, e[0]);
        eq(1, e[1]);
        // A station to the north: ladder along z, a quarter turn is chosen.
        List<double[]> north = new java.util.ArrayList<>();
        north.add(new double[] {19.5, 8, -5.5});
        int[] n = RecallPlanner.ladderSide(19.5, 0.5, north);
        eq(true, n[0] == 1 || n[0] == 3);
    }

    static void recallLadderCells() {
        // The ladder bottom at y 19: rungs 18 down to 8, the stairs at y 7 are solid and stay.
        List<Integer> col = RecallPlanner.ladderCells(19, y -> y == 7, 48);
        eq(11, col.size());
        eq(18, col.get(0));
        eq(8, col.get(col.size() - 1));
        eq(java.util.List.of(), RecallPlanner.ladderCells(19, y -> y == 18, 48));
        eq(3, RecallPlanner.ladderCells(19, y -> false, 3).size());
        eq(19, RecallPlanner.ladderCells(19, y -> false, 100).size()); // never under y = 0
    }

    static void recallMarkRoundTrip() {
        UUID quay = UUID.randomUUID();
        BalloonRegistry.Recall mark = new BalloonRegistry.Recall(quay, 10, 64, -20, Rotation.Ninety, 120.5, -3.0, new int[] {14, 18, 0, 14, 17, 0});
        BalloonRegistry.Entry e = new BalloonRegistry.Entry("w", 1, 2, 3, Rotation.None, "airship", null, 0, false, java.util.List.of(),
                null, null, false, null, mark);
        BalloonRegistry.Entry back = BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(e));
        eq(quay, back.recall().quayId());
        eq(Rotation.Ninety, back.recall().stationRotation());
        eq(6, back.recall().ladders().length);
        eq(17, back.recall().ladders()[4]);
        eq(null, BalloonRegistry.entryFromBson(BalloonRegistry.entryToBson(new BalloonRegistry.Entry("w", 1, 2, 3, Rotation.None, "airship", null))).recall());
    }

    static void recallClimbing() {
        List<int[]> rungs = new java.util.ArrayList<>();
        for (int y = 18; y >= 8; y--) {
            rungs.add(new int[] {14, y, 0});
        }
        double top = 8.0; // top of the station: the lowest rung cell stands on it
        eq(true, RecallPlanner.isClimbing(14.5, 12.0, 0.5, rungs, top)); // mid-ladder
        eq(true, RecallPlanner.isClimbing(15.3, 12.0, 0.2, rungs, top)); // within a block of the column
        eq(true, RecallPlanner.isClimbing(14.5, 19.4, 0.5, rungs, top)); // at the top, under the deck
        eq(false, RecallPlanner.isClimbing(14.5, 8.0, 0.5, rungs, top)); // on the boarding step
        eq(false, RecallPlanner.isClimbing(14.5, 8.2, 0.9, rungs, top)); // on the step, next to the column
        eq(false, RecallPlanner.isClimbing(14.5, 20.5, 0.5, rungs, top)); // on the deck
        eq(false, RecallPlanner.isClimbing(14.5, 6.0, 0.5, rungs, top)); // below the station
        eq(false, RecallPlanner.isClimbing(17.0, 12.0, 0.5, rungs, top)); // elsewhere
        eq(false, RecallPlanner.isClimbing(14.5, 12.0, 0.5, java.util.List.of(), top));
    }

    static void recallRefusal() {
        eq("recall.noAirship", RecallPlanner.recallRefusalKey(false, true, true, false));
        eq("recall.inFlight", RecallPlanner.recallRefusalKey(true, true, true, false));
        eq("recall.already", RecallPlanner.recallRefusalKey(true, false, true, false));
        eq("recall.quayBusy", RecallPlanner.recallRefusalKey(true, false, false, false));
        eq(null, RecallPlanner.recallRefusalKey(true, false, false, true));
    }

    static void recallRefusalOccupied() {
        // Order: no airship, in flight, already recalling (a ship parked above a station counts), occupied, quay busy.
        eq("recall.occupied", RecallPlanner.recallRefusalKey(true, false, false, true, true, false));
        eq("recall.already", RecallPlanner.recallRefusalKey(true, false, true, true, true, true));
        eq("recall.inFlight", RecallPlanner.recallRefusalKey(true, true, false, true, true, true));
        eq("recall.noAirship", RecallPlanner.recallRefusalKey(false, false, false, true, true, true));
        eq(null, RecallPlanner.recallRefusalKey(true, false, false, true, false, true));
        eq("recall.quayBusy", RecallPlanner.recallRefusalKey(true, false, false, true, false, false));
        eq("recall.unknownStation", RecallPlanner.recallRefusalKey(true, false, false, false, false, true));
        eq("recall.unknownStation", RecallPlanner.recallRefusalKey(true, false, false, false, true, false));
        eq("recall.already", RecallPlanner.recallRefusalKey(true, false, true, false, false, true));
    }

    static void recallStrandOffline() {
        eq(true, RecallPlanner.strandOffline(false, "w", "w", true));
        eq(false, RecallPlanner.strandOffline(true, "w", "w", true)); // online: the take-off handles him
        eq(false, RecallPlanner.strandOffline(false, "other", "w", true)); // saved in another world
        eq(false, RecallPlanner.strandOffline(false, null, "w", true)); // unknown world
        eq(false, RecallPlanner.strandOffline(false, "w", "w", false)); // not on the ship
    }

    static void recallDepartRetryBound() {
        eq(true, RecallPlanner.departRetryAllowed(1));
        eq(true, RecallPlanner.departRetryAllowed(RecallPlanner.MAX_DEPART_TRIES - 1));
        eq(false, RecallPlanner.departRetryAllowed(RecallPlanner.MAX_DEPART_TRIES));
    }

    static void recallApproachNear() {
        eq(true, RecallPlanner.approachNear(100, 100, 100, 100));
        eq(true, RecallPlanner.approachNear(100, 100, 110, 110)); // about 14 blocks
        eq(false, RecallPlanner.approachNear(100, 100, 120, 100));
    }

    static void recallTakeoffRungsRoundTrip() {
        int[] flat = new int[] {1, 70, -3, 1, 69, -3};
        eq(6, flat.length);
        org.bson.BsonDocument d = new org.bson.BsonDocument();
        d.put("takeoffRungs", AirshipResume.rungsToBson(flat));
        int[] back = AirshipResume.rungsFromBson(org.bson.BsonDocument.parse(d.toJson()));
        eq(6, back.length);
        eq(69, back[4]);
        eq(-3, back[5]);
        eq(null, AirshipResume.rungsFromBson(new org.bson.BsonDocument())); // older files
    }

    static void quayRecallBlocks() {
        TransitNetwork.Station s = new TransitNetwork.Station("Port Recall", "w", new org.joml.Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        UUID f = UUID.randomUUID();
        eq(true, StationQueue.beginRecall(s, f));
        eq(StationQueue.QuayState.RECALL, StationQueue.state(s));
        eq(f, StationQueue.recallOf(s));
        eq(false, StationQueue.beginRecall(s, UUID.randomUUID()));
        eq(false, StationQueue.tryBeginArrival(s, UUID.randomUUID()));
        StationQueue.endRecall(s, UUID.randomUUID()); // not the holder: no effect
        eq(StationQueue.QuayState.RECALL, StationQueue.state(s));
        StationQueue.endRecall(s, f);
        eq(StationQueue.QuayState.FREE, StationQueue.state(s));
        eq(null, StationQueue.recallOf(s));
        eq(false, StationQueue.beginRecall(s, f)); // departure gap
    }

    static void recallVoyageState() {
        VoyageState plain = new VoyageState("A", VoyagePlanner.Kind.STATION, 1, 2, 70, null);
        eq(false, plain.fuelFree);
        eq(VoyageState.ArrivalMode.LAND, plain.arrivalMode);
        VoyageState recall = new VoyageState("A", VoyagePlanner.Kind.STATION, 1, 2, 70, null, true, VoyageState.ArrivalMode.HOVER);
        eq(true, recall.fuelFree);
        eq(VoyageState.ArrivalMode.HOVER, recall.arrivalMode);
    }

    static void recallSavedRoundTrip() {
        UUID owner = UUID.randomUUID();
        RecallState.Saved saved = new RecallState.Saved("Station 1", owner, 120.5, -33.25, 70.0, -12.5);
        RecallState.Saved back = RecallState.Saved.fromBson(saved.toBson());
        eq(saved, back);
        eq(null, RecallState.Saved.fromBson(new RecallState.Saved("S", null, 1, 2, 3, 4).toBson()).owner());
    }

    static void benchContentsKeys() {
        // Older resume files have no key: nothing to put back. An empty set writes an empty array under the key.
        eq(0, AirshipBenchContents.fromBson(new org.bson.BsonDocument()).entryCount());
        eq(0, AirshipBenchContents.fromBson(null).entryCount());
        org.bson.BsonDocument d = AirshipBenchContents.fromBson(null).toBson();
        eq(true, d.containsKey("benchContents"));
        eq(0, AirshipBenchContents.fromBson(d).entryCount());
        // The fuel tank is the engines' business, every other multi-cell origin cell is a candidate.
        eq(false, AirshipBenchContents.isCandidate(new StructureShape.Cell(0, 5, 8, "Airship_Fuel_Tank", 0)));
        eq(true, AirshipBenchContents.isCandidate(new StructureShape.Cell(3, 8, 5, "Bench_Furnace", 0)));
        eq(false, AirshipBenchContents.isCandidate(new StructureShape.Cell(3, 8, 6, "Bench_Furnace", 1024)));
    }

    static void legacyLadderAccepted() {
        eq(true, BalloonManager.legacyAlias("Airship_Rope_Ladder", "Furniture_Crude_Ladder"));
        eq(true, BalloonManager.legacyAlias("Airship_Captain_Map_Desk", "Deco_Map"));
        eq(false, BalloonManager.legacyAlias("Airship_Rope_Ladder", "Furniture_Kweebec_Ladder"));
        eq(false, BalloonManager.legacyAlias("Airship_Ladder_Lever", "Deco_Lever"));
    }

    static void ashoreSide() {
        // theta 0: prefab frame equals world frame, +x flank is +1.
        eq(1, AirshipAutopilot.ashoreSide(3, 0, 0));
        eq(-1, AirshipAutopilot.ashoreSide(-3, 0, 0));
        eq(-1, AirshipAutopilot.ashoreSide(0, 7, 0)); // tie: -1
        double th = Math.PI / 2;
        // prefab point (4, 0, 0) rotated to the world by AirshipMath.rotateY, must come back as the +x flank.
        org.joml.Vector3d w = AirshipMath.rotateY(4, 0, 0, th, new org.joml.Vector3d());
        eq(1, AirshipAutopilot.ashoreSide(w.x, w.z, th));
        org.joml.Vector3d w2 = AirshipMath.rotateY(-4, 0, 1, th, new org.joml.Vector3d());
        eq(-1, AirshipAutopilot.ashoreSide(w2.x, w2.z, th));
    }

    static void ashoreDescent() {
        // ladder bottom cell y 100, ground block y 40 (surface 41): 59 above the ground, 29 to go down to be 30 above.
        eq(29, (int) Math.round(AirshipAutopilot.ashoreDescent(100, 40, 30)));
        eq(0, (int) Math.round(AirshipAutopilot.ashoreDescent(70, 40, 30))); // exactly 29 above... already low enough
        eq(0, (int) Math.round(AirshipAutopilot.ashoreDescent(50, 40, 30)));
        eq(0.0, AirshipAutopilot.ashoreDescent(71, 40, 30)); // bottom exactly 30 above the surface (41)
        eq(true, Double.isNaN(AirshipAutopilot.ashoreDescent(100, Double.NaN, 30)));
        eq(30, AirshipAutopilot.ASHORE_MAX_ABOVE);
    }

    static void gateRule() {
        eq(ShipLadder.GateAction.OPEN, ShipLadder.gateAction(ShipLadder.Phase.DOWN, false, false));
        eq(ShipLadder.GateAction.NONE, ShipLadder.gateAction(ShipLadder.Phase.DOWN, true, false));
        eq(ShipLadder.GateAction.NONE, ShipLadder.gateAction(ShipLadder.Phase.LOWERING, false, false));
        eq(ShipLadder.GateAction.CLOSE, ShipLadder.gateAction(ShipLadder.Phase.UP, true, true));
        eq(ShipLadder.GateAction.NONE, ShipLadder.gateAction(ShipLadder.Phase.UP, true, false)); // opened by a player
        eq(ShipLadder.GateAction.NONE, ShipLadder.gateAction(ShipLadder.Phase.RAISING, true, true));
        eq("OpenDoorOut", ShipLadder.gateOpenState(1));
        eq("CloseDoorOut", ShipLadder.gateCloseState(1));
        eq("OpenDoorIn", ShipLadder.gateOpenState(-1));
        eq("CloseDoorIn", ShipLadder.gateCloseState(-1));
    }

    static void campWakeRule() {
        check(CampPacker.shouldAutoPack(true, true, "bedroll"), "night, owner, bedroll");
        check(!CampPacker.shouldAutoPack(false, true, "bedroll"), "got up early");
        check(!CampPacker.shouldAutoPack(true, false, "bedroll"), "not the owner");
        check(!CampPacker.shouldAutoPack(true, true, "tent"), "tents never pack alone");
        check(!CampPacker.shouldAutoPack(true, true, "tent_big"), "big tents never pack alone");
    }

    static void campPackerGiveFallback() {
        check(CampPacker.fits(1), "a free slot fits");
        check(!CampPacker.fits(0), "no free slot drops at the feet");
        // ItemStack needs the game's item assets (not loaded in tests): the metadata document packStack stores is checked instead.
        TentContents contents = new TentContents(AirshipBenchContents.fromBson(null), new BalloonCargo(new java.util.ArrayList<>()));
        org.bson.BsonDocument doc = contents.toBson(CampTiers.Tier.TENT.kindId());
        eq("tent", doc.getString("kind").getValue());
        check(TentContents.fromBson(doc, "tent") != null, "round trip keeps the kind");
        check(TentContents.fromBson(doc, "bedroll") == null, "another kind is ignored");
        eq("Camping_Pack_Tent", CampTiers.Tier.TENT.packItemId());
    }

    static void removalKeepsForeignBagCell() {
        check(!StructureRemoval.removeCell(true, false), "bag cell holding a foreign block is kept");
        check(StructureRemoval.removeCell(true, true), "the placed bag itself is cleared");
        check(StructureRemoval.removeCell(false, true), "ordinary prefab block");
        check(StructureRemoval.removeCell(false, false), "ordinary cell follows the usual rule");
    }

    static void slumberTargetReached() {
        java.time.Instant target = java.time.Instant.parse("2026-01-02T06:00:00Z");
        check(TentBedSystem.reachedTarget(target, target), "exactly at target");
        check(TentBedSystem.reachedTarget(target, target.plusSeconds(500)), "past target");
        check(!TentBedSystem.reachedTarget(target, target.minusSeconds(600)), "cut short");
        check(!TentBedSystem.reachedTarget(null, target), "never slept");
    }

    static void campChestForbidden() {
        for (String id : new String[]{"Camping_Pack_Bedroll", "Camping_Pack_Tent", "Camping_Pack_Tent_Big", "Camping_Tent_Crate", "Camping_Tent_Big_Crate"}) {
            check(CampChestFilter.forbidden(id), id + " is refused");
        }
        check(!CampChestFilter.forbidden("Utility_Leather_Backpack"), "backpack accepted");
        check(!CampChestFilter.forbidden("Ingredient_Stick"), "stick accepted");
        check(!CampChestFilter.forbidden(null), "null accepted");
    }

    static void campChestSplit() {
        boolean[] mask = CampChestFilter.ejectMask(new String[]{"Ingredient_Stick", null, "Camping_Pack_Tent", "Camping_Pack_Bedroll", "Camping_Tent_Crate"});
        check(!mask[0] && !mask[1] && mask[2] && mask[3] && mask[4], "only the packs are ejected, slots keep their index");
        CampChestFilter.Plan mixed = CampChestFilter.plan(new String[]{"Ingredient_Stick", "Camping_Pack_Tent", null});
        check(mixed.anyEjected() && mixed.anyLeft() && mixed.eject()[1] && !mixed.eject()[0], "mixed chest keeps the stick, ejects the pack");
        CampChestFilter.Plan only = CampChestFilter.plan(new String[]{null, "Camping_Pack_Bedroll"});
        check(only.anyEjected() && !only.anyLeft(), "a chest holding only a pack is emptied entirely");
        CampChestFilter.Plan clean = CampChestFilter.plan(new String[]{"Ingredient_Stick", null});
        check(!clean.anyEjected() && clean.anyLeft(), "a clean chest is untouched");
        // ItemStack needs the game's item assets: the split is checked on an empty cargo.
        CampChestFilter.Split sp = CampChestFilter.split(new BalloonCargo(new java.util.ArrayList<>()));
        check(sp.kept().isEmpty() && sp.ejected().isEmpty() && sp.dropPos() == null, "empty cargo splits to nothing");
    }

    static void usageCountersSnapshot() {
        UsageCounters c = new UsageCounters();
        c.increment("deploy.tent"); c.increment("deploy.tent"); c.increment("glider.jump");
        c.increment("not.a.counter");
        Map<String, Long> s = c.snapshotAndReset();
        check(s.size() == 2, "two non-zero counters");
        check(s.get("deploy.tent") == 2L && s.get("glider.jump") == 1L, "counts");
        check(new ArrayList<>(s.keySet()).equals(List.of("deploy.tent", "glider.jump")), "NAMES order");
        check(c.snapshotAndReset().isEmpty(), "reset after snapshot");
        check(UsageCounters.NAMES.size() == 31, "31 counters");
        for (String n : List.of("first", "fallback", "repaired")) {
            check(UsageCounters.NAMES.contains("worldgen.boat.built." + n), "boat built counter " + n);
        }
        check(UsageCounters.NAMES.contains("worldgen.boat.exhausted"), "boat exhausted counter");
        check(UsageCounters.NAMES.contains("transit.boatTrip"), "boat trip counter");
        check(UsageCounters.NAMES.contains("deploy.steamboat") && UsageCounters.NAMES.contains("takeoff.steamboat"), "steamboat counters");
        check(UsageCounters.NAMES.indexOf("deploy.steamboat") == UsageCounters.NAMES.indexOf("deploy.airship") + 1, "deploy order");
        check(UsageCounters.NAMES.indexOf("takeoff.steamboat") == UsageCounters.NAMES.indexOf("takeoff.airship") + 1, "takeoff order");
    }

    static void tickTimingsAggregate() {
        TickTimings t = new TickTimings();
        t.record("tick.balloon", 2_000_000L);
        t.record("tick.balloon", 6_000_000L);
        t.record("tick.balloon", 1_000_000L);
        t.record("tick.airship", 50_000_000L);
        t.record("not.a.system", 9L);
        t.record("tick.airship", -5L);
        Map<String, TickTimings.Snapshot> s = t.snapshotAndReset();
        check(s.size() == 2, "two systems ticked");
        check(new ArrayList<>(s.keySet()).equals(List.of("tick.balloon", "tick.airship")), "NAMES order");
        TickTimings.Snapshot b = s.get("tick.balloon");
        check(b.ticks() == 3 && b.totalNanos() == 9_000_000L && b.maxNanos() == 6_000_000L, "balloon count, total and max");
        check(b.maxMs() == 6, "max in ms");
        check(Math.abs(b.meanMs() - 3.0) < 1e-9, "mean in ms");
        check(s.get("tick.airship").ticks() == 1, "negative duration ignored");
        check(t.snapshotAndReset().isEmpty(), "reset after snapshot");
        t.record("tick.airship", 1_000_000L);
        check(t.snapshotAndReset().get("tick.airship").maxNanos() == 1_000_000L, "max restarts after reset");
    }

    static void tickTimingsRounding() {
        check(TickTimings.ceilMs(0) == 0 && TickTimings.ceilMs(-1) == 0, "no duration");
        check(TickTimings.ceilMs(1) == 1, "one ns rounds up to 1 ms");
        check(TickTimings.ceilMs(1_000_000L) == 1, "exact ms kept");
        check(TickTimings.ceilMs(1_000_001L) == 2, "just over rounds up");
        check(TickTimings.ceilMs(49_000_001L) == 50, "49 ms and a bit reaches the 50 ms threshold");
        check(TickTimings.ceilMs(Long.MAX_VALUE) == Integer.MAX_VALUE, "capped");
        check(new TickTimings.Snapshot(0, 0, 0).meanMs() == 0, "mean of nothing");
    }

    static void performanceDescriptor() throws Exception {
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("Server/Beacon/project.json"));
        int at = json.indexOf("\"performance\"");
        check(at > 0, "performance block");
        String perf = json.substring(at);
        check(perf.contains("\"supported\": true") && perf.contains("\"thresholdMs\": 50"), "performance on, threshold 50 ms");
        for (String n : TickTimings.NAMES) check(perf.contains("\"" + n + "\""), "descriptor declares " + n);
        for (String n : List.of("takeoff", "land", "worldgen.station")) check(perf.contains("\"" + n + "\""), "descriptor declares " + n);
    }

    static void shutdownFlushSettled() {
        check(!Telemetry.flushSettled("a", "b", true, 0, 5000), "in progress never settled");
        check(Telemetry.flushSettled("a", "a", false, 0, 0), "nothing pending");
        check(Telemetry.flushSettled("a", "b", false, 3, 0), "new pass completed");
        check(!Telemetry.flushSettled("a", "a", false, 3, 200), "still waiting");
        check(Telemetry.flushSettled("a", "a", false, 3, 1000), "grace elapsed");
        check(Telemetry.flushSettled(null, null, false, 3, 1500), "null results, grace elapsed");
    }

    static void telemetryWithoutBeacon() {
        Telemetry.count("deploy.tent");
        Telemetry.count("unknown.counter");
        Telemetry.error("balloon.takeoff", new RuntimeException("x"), null, null);
        check(Telemetry.start() == 0L, "no measure without Beacon");
        Telemetry.timing("takeoff", Telemetry.start(), "balloon");
        Telemetry.tick("tick.balloon", Telemetry.start());
        Telemetry.stop();
        check(true, "no exception without start");
    }

    static void wheelAnimationsDeclared() throws Exception {
        for (String suffix : new String[]{"", "_Idle", "_Off"}) {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of("Server/Models/Vehicles/Steamboat" + suffix + ".json"));
            for (boolean right : new boolean[]{false, true}) {
                for (PaddleWheels.Spin spin : PaddleWheels.Spin.values()) {
                    if (spin == PaddleWheels.Spin.STOP) continue;
                    String id = PaddleWheels.animationId(right, spin);
                    check(json.contains("\"" + id + "\""), "Steamboat" + suffix + " declares " + id);
                    String file = "Common/Items/Vehicles/Steamboat_" + id + ".blockyanim";
                    check(json.contains("Items/Vehicles/Steamboat_" + id + ".blockyanim"), id + " path declared");
                    String anim = java.nio.file.Files.readString(java.nio.file.Path.of(file));
                    check(anim.contains("\"duration\": " + spin.frames()), id + " duration");
                    check(!java.util.regex.Pattern.compile("\"time\": \\d+\\.").matcher(anim).find(), id + " integer times");
                    check(anim.contains("\"" + (right ? "Wheel_Right" : "Wheel_Left") + "\""), id + " animates its wheel node");
                }
            }
            check(json.contains("\"BlendingDuration\": 0") && json.contains("\"Looping\": true"), "blending 0 and looping");
        }
        for (String part : new String[]{"_Part1", "_Part2"}) {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of("Server/Models/Vehicles/Steamboat" + part + ".json"));
            check(!json.contains("AnimationSets"), part + " has no AnimationSets");
        }
    }

    /** The shuttle models declare the four propeller animations, and the files match the Java frame counts and the node name. */
    static void propellerAnimationsDeclared() throws Exception {
        for (String suffix : new String[]{"", "_Idle", "_Off"}) {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of("Server/Models/Vehicles/Steamboat_Public" + suffix + ".json"));
            for (PaddleWheels.Spin spin : new PaddleWheels.Spin[]{PaddleWheels.Spin.FWD_FAST, PaddleWheels.Spin.FWD_SLOW,
                    PaddleWheels.Spin.REV_FAST, PaddleWheels.Spin.REV_SLOW}) {
                String id = PaddleWheels.propellerAnimationId(Deployables.STEAMBOAT_PUBLIC.airship.propellerAnimPrefix, spin);
                String path = "Items/Vehicles/Steamboat_Public_" + id + ".blockyanim";
                check(json.contains("\"" + id + "\"") && json.contains(path), "Steamboat_Public" + suffix + " declares " + id);
                String anim = java.nio.file.Files.readString(java.nio.file.Path.of("Common/" + path));
                check(anim.contains("\"duration\": " + spin.frames()), id + " duration");
                check(!java.util.regex.Pattern.compile("\"time\": \\d+\\.").matcher(anim).find(), id + " integer times");
                check(anim.contains("\"Propeller\""), id + " animates the Propeller node");
            }
            check(!json.contains("Wheel_"), "Steamboat_Public" + suffix + " has no wheel animation");
        }
        String model = java.nio.file.Files.readString(java.nio.file.Path.of("Common/Items/Vehicles/Steamboat_Public.blockymodel"));
        check(model.contains("\"name\":\"Propeller\""), "the flying model has a node named Propeller");
    }

    /** Animated helm: target step, one step at a time toward it, names, and the flag of every ship. */
    static void helmRules() {
        check(ShipHelm.targetFor(false, 0) == 0, "tout droit : centre");
        check(ShipHelm.targetFor(false, -1) == 4, "virage à droite : demi-tour à droite");
        check(ShipHelm.targetFor(false, 1) == -4, "virage à gauche : demi-tour à gauche");
        check(ShipHelm.targetFor(true, -1) == 0 && ShipHelm.targetFor(true, 1) == 0, "navire arrêté : centre");
        check(ShipHelm.heldTurn(-1, 1, 0, 5000) == -1, "virage en cours : son sens, même inverse du précédent");
        check(ShipHelm.heldTurn(0, -1, 1000, 1000 + ShipHelm.RELEASE_MS - 1) == -1, "pause entre deux crans : virage gardé");
        check(ShipHelm.heldTurn(0, -1, 1000, 1000 + ShipHelm.RELEASE_MS) == 0, "virage fini : retour au centre");
        check(ShipHelm.heldTurn(0, 0, 0, 10) == 0, "jamais tourné : centre");
        check(ShipHelm.nextToward(0, 4) == 1 && ShipHelm.nextToward(3, 4) == 4 && ShipHelm.nextToward(4, 4) == 4, "montée vers la droite");
        check(ShipHelm.nextToward(0, -4) == -1 && ShipHelm.nextToward(-3, 0) == -2 && ShipHelm.nextToward(4, 0) == 3, "descente et retour");
        int step = 0, moves = 0;
        while (step != 4) {
            step = ShipHelm.nextToward(step, 4);
            moves++;
        }
        check(moves == 4 && ShipHelm.degrees(step) == 180, "4 pas de 45 degrés jusqu'au demi-tour");
        step = 4;
        moves = 0;
        while (step != -4) {
            step = ShipHelm.nextToward(step, -4);
            moves++;
        }
        check(moves == 8, "de butée à butée : 8 pas");
        check("0".equals(ShipHelm.stepName(0)) && "P3".equals(ShipHelm.stepName(3)) && "M4".equals(ShipHelm.stepName(-4)), "noms de pas");
        check("Helm_Pose_P2".equals(ShipHelm.poseId(2)) && "Helm_Pose_M1".equals(ShipHelm.poseId(-1)), "noms de pose");
        check("Helm_Step_P1_P2".equals(ShipHelm.stepId(1, 2)) && "Helm_Step_M1_0".equals(ShipHelm.stepId(-1, 0)), "noms de transition");
        check(ShipHelm.ROTATE_MS == 250, "un pas dure 250 ms");
        check(!ShipHelm.transitionDone(1000, 1200) && ShipHelm.transitionDone(1000, 1233) && ShipHelm.transitionDone(1000, 1300),
                "fin d'un pas : 250 ms moins la tolérance");
        ShipHelm.State st = new ShipHelm.State();
        check(st.displayId() == null, "rien de joué au départ (pose de repos)");
        st.played = true;
        st.step = 2;
        check("Helm_Pose_P2".equals(st.displayId()), "pose courante");
        st.running = true;
        st.from = 2;
        st.to = 3;
        check("Helm_Pose_P3".equals(st.displayId()), "pose d'arrivée d'une transition en cours");
        for (Deployables.AirshipSpec sp : new Deployables.AirshipSpec[]{Deployables.AIRSHIP.airship, Deployables.STEAMBOAT.airship,
                Deployables.STEAMBOAT_PUBLIC.airship}) {
            check(sp.helmAnimated, "la barre est animée sur " + sp.modelId);
        }
    }

    /** The 25 shared animation files exist, with the frame counts and the rotation sign the plugin assumes. */
    static void helmAnimationFiles() throws Exception {
        int files = 0;
        for (int a = -ShipHelm.MAX_STEP; a <= ShipHelm.MAX_STEP; a++) {
            String pose = java.nio.file.Files.readString(java.nio.file.Path.of("Common/Items/Vehicles/" + ShipHelm.poseId(a) + ".blockyanim"));
            files++;
            check(pose.contains("\"Helm\"") && pose.contains("\"duration\": 12"), ShipHelm.poseId(a) + " anime le nœud Helm sur 12 images");
            check(!java.util.regex.Pattern.compile("\"time\": \\d+\\.").matcher(pose).find(), ShipHelm.poseId(a) + " temps entiers");
            for (int b : new int[]{a - 1, a + 1}) {
                if (Math.abs(b) > ShipHelm.MAX_STEP) {
                    continue;
                }
                String id = ShipHelm.stepId(a, b);
                String anim = java.nio.file.Files.readString(java.nio.file.Path.of("Common/Items/Vehicles/" + id + ".blockyanim"));
                files++;
                int total = ShipHelm.ROTATE_FRAMES + ShipHelm.HOLD_FRAMES;
                check(anim.contains("\"duration\": " + total), id + " dure " + total + " images");
                check(anim.contains("\"time\": " + ShipHelm.ROTATE_FRAMES + ",") && anim.contains("\"time\": " + total + ","),
                        id + " : clés à la fin de la rotation et de l'arrêt");
                check(!java.util.regex.Pattern.compile("\"time\": \\d+\\.").matcher(anim).find(), id + " temps entiers");
            }
        }
        check(files == 25, "9 poses et 16 transitions, " + files + " trouvés");
        // Sign: the pose of step +4 (a right turn) is a half turn, its quaternion z is +-1 and w ~ 0, step +1 has the sign of RIGHT_SIGN.
        String p1 = java.nio.file.Files.readString(java.nio.file.Path.of("Common/Items/Vehicles/Helm_Pose_P1.blockyanim"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"z\": (-?[0-9.E-]+)").matcher(p1);
        check(m.find() && Math.signum(Double.parseDouble(m.group(1))) == ShipHelm.RIGHT_SIGN, "sens de la barre à droite = RIGHT_SIGN");
        double half = Math.sin(Math.toRadians(ShipHelm.degrees(1)) / 2);
        check(Math.abs(Math.abs(Double.parseDouble(m.group(1))) - half) < 1e-6, "pose P1 : 45 degrés");
        String p4 = java.nio.file.Files.readString(java.nio.file.Path.of("Common/Items/Vehicles/Helm_Pose_P4.blockyanim"));
        java.util.regex.Matcher m4 = java.util.regex.Pattern.compile("\"z\": (-?[0-9.E-]+)").matcher(p4);
        check(m4.find() && Math.abs(Math.abs(Double.parseDouble(m4.group(1))) - 1) < 1e-6, "pose P4 : demi-tour");
    }

    /** The node Helm is in part 0 of the three flight models (and in no other part), every model JSON declares all 25 animations. */
    static void helmDeclared() throws Exception {
        for (String model : new String[]{"Airship_Cloudwork", "Steamboat", "Steamboat_Public"}) {
            String part0 = java.nio.file.Files.readString(java.nio.file.Path.of("Common/Items/Vehicles/" + model + ".blockymodel"));
            check(part0.contains("\"name\":\"Helm\""), model + " : nœud Helm dans la partie 0");
            check(part0.contains("Airship_Helm_"), model + " : bloc de la barre dans la partie 0");
            for (int k = 1; k < 4; k++) {
                java.nio.file.Path other = java.nio.file.Path.of("Common/Items/Vehicles/" + model + "_Part" + k + ".blockymodel");
                if (java.nio.file.Files.exists(other)) {
                    check(!java.nio.file.Files.readString(other).contains("\"name\":\"Helm\""), model + " partie " + k + " sans nœud Helm");
                }
            }
            for (String suffix : new String[]{"", "_Idle", "_Off", "_TurnLeft", "_TurnRight"}) {
                java.nio.file.Path path = java.nio.file.Path.of("Server/Models/Vehicles/" + model + suffix + ".json");
                if (!java.nio.file.Files.exists(path)) {
                    check(suffix.startsWith("_Turn") && !model.equals("Airship_Cloudwork"), model + suffix + " existe");
                    continue;
                }
                String json = java.nio.file.Files.readString(path);
                for (int a = -ShipHelm.MAX_STEP; a <= ShipHelm.MAX_STEP; a++) {
                    check(json.contains("\"" + ShipHelm.poseId(a) + "\"") && json.contains("Items/Vehicles/" + ShipHelm.poseId(a) + ".blockyanim"),
                            model + suffix + " déclare " + ShipHelm.poseId(a));
                    for (int b : new int[]{a - 1, a + 1}) {
                        if (Math.abs(b) <= ShipHelm.MAX_STEP) {
                            check(json.contains("\"" + ShipHelm.stepId(a, b) + "\""), model + suffix + " déclare " + ShipHelm.stepId(a, b));
                        }
                    }
                }
            }
        }
        // The roues and the propeller sets are merged with the helm's, not replaced.
        check(java.nio.file.Files.readString(java.nio.file.Path.of("Server/Models/Vehicles/Steamboat.json")).contains("\"Wheel_Left_FwdFast\""),
                "Steamboat garde ses animations de roues");
        check(java.nio.file.Files.readString(java.nio.file.Path.of("Server/Models/Vehicles/Steamboat_Public_Idle.json")).contains("\"Propeller_FwdFast\""),
                "la navette garde ses animations d'hélice");
    }

    static void usageNamesMatchDescriptor() throws Exception {
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("Server/Beacon/project.json"));
        // allowedEvents must list exactly UsageCounters.NAMES
        for (String n : UsageCounters.NAMES) check(json.contains("\"" + n + "\""), "descriptor allows " + n);
        check(json.contains("\"projectId\": \"orbis-horizon\"") || json.contains("\"projectId\":\"orbis-horizon\""), "project id pinned");
        check(json.contains("\"proj_Imk2qiS_8E0rfsYNdHHUg0Zs\""), "project key");
        check(json.contains("com.keepkoolz.orbishorizon"), "package prefix");
    }

    static void errorThrottleWindow() {
        ErrorThrottle t = new ErrorThrottle(1000, 3);
        check(t.admit("a", 0) == 0, "first send");
        check(t.admit("a", 10) == -1 && t.admit("a", 20) == -1, "suppressed in window");
        check(t.admit("a", 1000) == 2, "repeats reported after window");
        t.admit("b", 1001); t.admit("c", 1002); t.admit("d", 1003);
        check(t.admit("a", 1004) == 0, "oldest forgotten beyond max entries");
    }

    static void redactedErrorHasNoMessage() {
        Exception e = new IllegalStateException("Steve at -755, 118, -106");
        RuntimeException r = ErrorThrottle.redact(e);
        check(!String.valueOf(r.getMessage()).contains("Steve"), "message removed");
        check(String.valueOf(r.getMessage()).equals("java.lang.IllegalStateException"), "class name kept");
        check(Arrays.equals(r.getStackTrace(), e.getStackTrace()), "stack kept");
        check(r.getCause() == null, "no cause chain (causes may carry messages)");
        String fp = ErrorThrottle.fingerprint("balloon.takeoff", e);
        check(fp.startsWith("balloon.takeoff|java.lang.IllegalStateException|"), "fingerprint shape");
        check(!fp.contains("Steve"), "fingerprint without message");
    }

    static void travelTargetReverse() {
        double cone = Math.toRadians(100);
        check(!AirshipManager.travelTarget(0.3, 0.0, cone, true).reversing(), "forward");
        check(Math.abs(AirshipManager.travelTarget(0.3, 0.0, cone, true).heading() - 0.3) < 1e-9, "bow follows");
        AirshipManager.TravelTarget back = AirshipManager.travelTarget(Math.PI + 0.2, 0.0, cone, true);
        check(back.reversing() && Math.abs(AirshipMath.wrapPi(back.heading() - 0.2)) < 1e-9, "stern follows: backing right turns right");
        check(Double.isNaN(AirshipManager.travelTarget(Math.PI + 0.2, 0.0, cone, false).heading()), "airship keeps its heading");
        check(AirshipManager.travelTarget(Math.PI + 0.2, 0.0, cone, false).reversing(), "airship still reversing");
    }

    static void engineSoundRule() {
        check(AirshipManager.engineSoundWanted(true, false), "engines and fuel: runs");
        check(!AirshipManager.engineSoundWanted(true, true), "dry: silent");
        check(!AirshipManager.engineSoundWanted(false, false), "no engines: silent");
    }

    static void pilotSpeedReverse() {
        check(Math.abs(AirshipManager.pilotHorizontalSpeed(5.16f, false, true, 0.5f) - 2.58f) < 1e-6, "half speed reversing");
        check(AirshipManager.pilotHorizontalSpeed(5.16f, false, false, 0.5f) == 5.16f, "full speed forward");
        check(AirshipManager.pilotHorizontalSpeed(5.16f, true, true, 0.5f) == 0f, "dry");
        check(AirshipManager.pilotHorizontalSpeed(5.16f, false, true, 1f) == 5.16f, "airship factor 1");
    }

    private static void shipTextKeys() throws Exception {
        check(Texts.SHIP_KEYS.size() == 27, "27 restes");
        for (String lang : new String[] {"en-US", "fr-FR"}) {
            List<String> lines = Files.readAllLines(Path.of("Server/Languages/" + lang + "/server.lang"));
            for (String l : lines) {
                check(!l.contains("\u2014"), lang + " : tiret cadratin : " + l);
            }
            for (String rest : Texts.SHIP_KEYS) {
                boolean air = false;
                String boat = null;
                for (String l : lines) {
                    if (l.startsWith("orbis_horizon.chat.airship." + rest + " = ")) {
                        air = true;
                    }
                    if (l.startsWith("orbis_horizon.chat.steamboat." + rest + " = ")) {
                        boat = l;
                    }
                }
                check(air, lang + " airship." + rest);
                check(boat != null, lang + " steamboat." + rest);
                String val = boat.substring(boat.indexOf(" = ") + 3);
                check(!val.contains("irship") && !val.contains("irigeable"), lang + " steamboat." + rest + " nomme le dirigeable");
            }
            for (String id : new String[] {"airship_helm", "airship_engine", "airship_fuel_tank", "airship_stool"}) {
                for (String l : lines) {
                    if (l.startsWith("orbis_horizon.items." + id + ".name = ")) {
                        check(!l.substring(l.indexOf(" = ") + 3).contains("irship") && !l.substring(l.indexOf(" = ") + 3).contains("irigeable"), lang + " nom générique " + id);
                    }
                }
            }
        }
    }

    // ---- WaterRoute ----

    private static char[][] waterMap(char fill) {
        char[][] m = new char[120][120];
        for (char[] row : m) {
            java.util.Arrays.fill(row, fill);
        }
        return m;
    }

    private static void waterRect(char[][] m, int x0, int x1, int z0, int z1, char c) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                m[x][z] = c;
            }
        }
    }

    private static WaterRoute.ColumnProbe waterProbe(char[][] m) {
        return (x, z) -> {
            if (x < 0 || z < 0 || x >= 120 || z >= 120) {
                return WaterRoute.Cell.BLOCKED;
            }
            char c = m[x][z];
            return c == '.' ? WaterRoute.Cell.WATER : c == '?' ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.BLOCKED;
        };
    }

    private static WaterRoute.Search waterSearch(char[][] m, int fx, int fz, int tx, int tz) {
        return new WaterRoute.Search(waterProbe(m), fx, fz, tx, tz, 0, 0, 119, 119);
    }

    private static double landDistance(char[][] m, double px, double pz) {
        double best = 1e9;
        for (int x = 0; x < 120; x++) {
            for (int z = 0; z < 120; z++) {
                if (m[x][z] == '#') {
                    best = Math.min(best, Math.hypot(x - px, z - pz));
                }
            }
        }
        return best;
    }

    private static void waterRouteOpenLake() {
        char[][] m = waterMap('.');
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        check(s.step(100000) == WaterRoute.Status.FOUND, "lac ouvert trouvé");
        check(s.waypoints().size() == 1, "un seul point : " + s.waypoints().size());
        int[] w = s.waypoints().get(0);
        check(w[0] == 100 && w[1] == 60, "arrivée exacte");
    }

    private static void waterRouteIsland() {
        char[][] m = waterMap('.');
        waterRect(m, 45, 75, 45, 75, '#');
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        check(s.step(100000) == WaterRoute.Status.FOUND, "île contournée");
        check(s.waypoints().size() > 1, "plusieurs points");
        int px = 20;
        int pz = 60;
        for (int[] w : s.waypoints()) {
            check(landDistance(m, w[0], w[1]) >= 8, "point à moins de 8 blocs de la terre");
            for (int i = 0; i <= 40; i++) {
                double x = px + (w[0] - px) * i / 40.0;
                double z = pz + (w[1] - pz) * i / 40.0;
                check(landDistance(m, x, z) >= 7, "segment à moins de 7 blocs de la terre");
            }
            px = w[0];
            pz = w[1];
        }
    }

    private static void waterRouteSCurve() {
        char[][] m = waterMap('#');
        waterRect(m, 5, 100, 10, 29, '.');
        waterRect(m, 81, 100, 10, 69, '.');
        waterRect(m, 5, 100, 50, 69, '.');
        waterRect(m, 5, 24, 50, 109, '.');
        waterRect(m, 5, 100, 90, 109, '.');
        WaterRoute.Search s = waterSearch(m, 15, 19, 80, 99);
        check(s.step(100000) == WaterRoute.Status.FOUND, "rivière en S");
        check(s.waypoints().size() >= 3, "plusieurs points : " + s.waypoints().size());
        int[] last = s.waypoints().get(s.waypoints().size() - 1);
        check(last[0] == 80 && last[1] == 99, "arrivée exacte");
    }

    private static void waterRouteNarrow() {
        char[][] m = waterMap('#');
        waterRect(m, 5, 40, 20, 99, '.');
        waterRect(m, 80, 115, 20, 99, '.');
        waterRect(m, 41, 79, 54, 65, '.');
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.NO_ROUTE, "chenal de 12 : NO_ROUTE");
        waterRect(m, 41, 79, 50, 69, '.');
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.FOUND, "chenal de 20 : FOUND");
    }

    private static void waterRouteSeparateLakes() {
        char[][] m = waterMap('#');
        waterRect(m, 5, 40, 20, 99, '.');
        waterRect(m, 80, 115, 20, 99, '.');
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.NO_ROUTE, "lacs séparés");
    }

    private static void waterRouteNeedsData() {
        char[][] m = waterMap('.');
        waterRect(m, 60, 60, 0, 119, '?');
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        check(s.step(100000) == WaterRoute.Status.NEED_DATA, "colonne inconnue");
        int[] miss = s.missingColumn();
        check(miss != null && miss[0] == 60, "colonne manquante signalée");
        waterRect(m, 60, 60, 0, 119, '.');
        check(s.step(100000) == WaterRoute.Status.FOUND, "reprise après données");
    }

    private static void boatRoutesFileRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("boatroutes");
        Path f = dir.resolve("boat-routes.json");
        BoatRoutes.Book b = new BoatRoutes.Book();
        b.load(f);
        b.addStation("w", "A", List.of("A", "B", "C"));
        b.resolve("w", "A", "B", BoatRoutes.RouteState.FOUND, List.of(new int[] {10, -20}, new int[] {30, 40}, new int[] {-5, 7}));
        b.resolve("w", "A", "C", BoatRoutes.RouteState.NONE, List.of());
        BoatRoutes.Book c = new BoatRoutes.Book();
        c.load(f);
        BoatRoutes.Route ab = c.get("w", "A", "B");
        check(ab != null && ab.state() == BoatRoutes.RouteState.FOUND && ab.points().size() == 3, "route trouvée relue");
        check(ab.points().get(2)[0] == -5 && ab.points().get(2)[1] == 7, "points relus");
        BoatRoutes.Route ba = c.get("w", "B", "A");
        check(ba != null && ba.state() == BoatRoutes.RouteState.FOUND && ba.points().get(0)[0] == -5, "route retour inversée");
        BoatRoutes.Route ac = c.get("w", "A", "C");
        check(ac != null && ac.state() == BoatRoutes.RouteState.NONE && ac.points().isEmpty(), "route absente relue");
        Files.writeString(f, "not json");
        BoatRoutes.Book d = new BoatRoutes.Book();
        d.load(f);
        check(d.get("w", "A", "B") == null && Files.exists(dir.resolve("boat-routes.json.bad")), "fichier illisible renommé");
    }

    private static void crossMaxLeavesRoomForRoute() {
        check(WorldStationPlanner.CROSS_MAX < WaterRoute.MAX_LENGTH, "crossing limit under the route limit");
        eq(3800, WorldStationPlanner.CROSS_MAX);
    }

    private static void boatRoutesInvalidate() {
        BoatRoutes.Book b = new BoatRoutes.Book();
        b.addStation("w", "A", List.of("A"));
        check(b.nextComputing("w") == null, "une seule station : rien à calculer");
        b.addStation("w", "B", List.of("A", "B"));
        check(b.get("w", "A", "B").state() == BoatRoutes.RouteState.COMPUTING, "A vers B en calcul");
        check(b.get("w", "B", "A").state() == BoatRoutes.RouteState.COMPUTING, "B vers A en calcul");
        b.resolve("w", "A", "B", BoatRoutes.RouteState.FOUND, List.of(new int[] {1, 1}));
        b.addStation("w", "C", List.of("A", "B", "C"));
        check(b.get("w", "A", "B").state() == BoatRoutes.RouteState.FOUND, "paire sans C gardée");
        check(b.get("w", "A", "C").state() == BoatRoutes.RouteState.COMPUTING, "paires de C en calcul");
        b.removeStation("w", "C");
        check(b.get("w", "A", "C") == null && b.get("w", "C", "B") == null, "paires de C oubliées");
        check(b.get("w", "A", "B") != null && b.nextComputing("w") == null, "A et B gardées");
        check(b.nextComputing("other") == null, "autre monde");
    }

    private static void mooredPilot() {
        for (Rotation r : new Rotation[] {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), r, null, null,
                    new ArrayList<>(), Deployables.BOAT_STATION_KIND);
            // Helm: the pivot cell of the prefab, turned by the berth rotation around the berth origin (cell centre in x and z).
            Vector3i o = BoatRoutes.berthOrigin(s);
            org.joml.Vector3f pv = AirshipManager.pivot(Deployables.STEAMBOAT_PUBLIC);
            Vector3d rot = AirshipMath.rotateY(pv.x, pv.y, pv.z, BoatRoutes.berthRotation(s).getRadians(), new Vector3d());
            Vector3d p = MooredPilot.helmPoint(s, false);
            check(Math.abs(p.x - (o.x + Math.round(rot.x) + 0.5)) < 1e-6 && Math.abs(p.z - (o.z + Math.round(rot.z) + 0.5)) < 1e-6
                    && Math.abs(p.y - (o.y + Math.round(rot.y))) < 1e-6, "pivot du prefab tourné, rotation " + r + " : " + p);
            // Facing: forward (-sin yaw, -cos yaw) is the bow of the prefab (0, 0, -1) turned by the berth rotation, whatever the station's.
            double yaw = MooredPilot.facingYaw(s, false);
            Vector3d bow = AirshipMath.rotateY(0, 0, -1, BoatRoutes.berthRotation(s).getRadians(), new Vector3d());
            near(bow.x, -Math.sin(yaw));
            near(bow.z, -Math.cos(yaw));
            java.util.UUID id = MooredPilot.uuidFor("w", o);
            check(id.equals(MooredPilot.uuidFor("w", new Vector3i(o.x, o.y, o.z))), "identifiant stable : un reste est retrouvé");
            check(!id.equals(MooredPilot.uuidFor("w", new Vector3i(o.x + 1, o.y, o.z))), "un identifiant par ponton");
            check(!id.equals(MooredPilot.uuidFor("autre", o)), "un identifiant par monde");
        }
    }

    private static TransitNetwork.Station stationAt(Rotation r) {
        return new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), r, null, null, new ArrayList<>(), Deployables.BOAT_STATION_KIND);
    }

    private static final Rotation[] ALL_ROTATIONS = {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy};

    /** The pilot of a shuttle moored the other way round: pivot turned a half turn about the berth origin, looking the opposite way. */
    private static void mooredPilotFlipped() {
        for (Rotation r : ALL_ROTATIONS) {
            TransitNetwork.Station s = stationAt(r);
            Vector3i o = BoatRoutes.berthOrigin(s);
            Vector3d pn = MooredPilot.helmPoint(s, false);
            Vector3d pf = MooredPilot.helmPoint(s, true);
            // Half turn about the prefab origin: the two pivots are symmetric about the origin cell centre.
            near(2 * (o.x + 0.5), pn.x + pf.x);
            near(2 * (o.z + 0.5), pn.z + pf.z);
            near(pn.y, pf.y);
            // The pivot cell of the prefab turned by the flipped rotation.
            org.joml.Vector3f pv = AirshipManager.pivot(Deployables.STEAMBOAT_PUBLIC);
            Vector3d rot = AirshipMath.rotateY(pv.x, pv.y, pv.z, BoatRoutes.berthRotation(s, true).getRadians(), new Vector3d());
            near(o.x + Math.round(rot.x) + 0.5, pf.x);
            near(o.z + Math.round(rot.z) + 0.5, pf.z);
            // Facing: forward (-sin yaw, -cos yaw) is the prefab bow turned by the flipped berth rotation, opposite to the usual one.
            double yaw = MooredPilot.facingYaw(s, true);
            Vector3d bow = AirshipMath.rotateY(0, 0, -1, BoatRoutes.berthRotation(s, true).getRadians(), new Vector3d());
            near(bow.x, -Math.sin(yaw));
            near(bow.z, -Math.cos(yaw));
            near(Math.PI, Math.abs(AirshipMath.wrapPi(yaw - MooredPilot.facingYaw(s, false))));
            near(BoatRoutes.berthTheta(s, true), yaw);
            // The rotation centre does not depend on the heading.
            Vector3d c = BoatRoutes.berthCentre(s);
            Vector3d cf = AirshipManager.get().centerWorld(Deployables.STEAMBOAT_PUBLIC, pf, BoatRoutes.berthTheta(s, true));
            near(c.x, cf.x);
            near(c.z, cf.z);
            check(BoatRoutes.berthRotation(s, true) == BoatRoutes.berthRotation(s).add(Rotation.OneEighty), "flipped rotation " + r);
            check(BoatRoutes.berthRotation(s, false) == BoatRoutes.berthRotation(s), "usual rotation " + r);
        }
    }

    /**
     * A boarding step touches the last pier plank for both berth headings and the 4 station rotations (the steps are symmetric about the
     * prefab origin), and neither hull overlaps a block of the station.
     */
    private static void berthStepBothHeadings() throws Exception {
        StructureShape station = prefabShape("Boat_Station.prefab.json");
        StructureShape boat = prefabShape("Steamboat_Public.prefab.json");
        Vector3i pierEnd = new Vector3i(1, 0, 12); // station frame: against the last deck plank (1, 0, 11)
        for (Rotation r : ALL_ROTATIONS) {
            TransitNetwork.Station s = stationAt(r);
            Vector3i expected = r.rotateYaw(new Vector3i(pierEnd), new Vector3i()).add(s.origin);
            Set<String> taken = new java.util.HashSet<>();
            for (StructureShape.Cell c : station.cells()) {
                Vector3i p = c.rotated(r).add(s.origin);
                taken.add(p.x + "," + p.y + "," + p.z);
            }
            for (boolean flipped : new boolean[] {false, true}) {
                Rotation br = BoatRoutes.berthRotation(s, flipped);
                boolean touches = false;
                for (StructureShape.Cell c : boat.cells()) {
                    Vector3i w = c.rotated(br).add(BoatRoutes.berthOrigin(s));
                    check(!taken.contains(w.x + "," + w.y + "," + w.z), "hull cell on a station block " + w + ", flipped " + flipped + ", " + r);
                    if (c.y() == 1 && Math.abs(c.x()) == 2 && c.z() == 0 && w.equals(expected)) {
                        touches = true;
                    }
                }
                check(touches, "a boarding step at " + expected + ", flipped " + flipped + ", station " + r);
            }
        }
        // Along the pier (station x) the flipped hull is the usual one mirrored about the berth origin column: the columns of its
        // cells go from origin - maxZ to origin - minZ, and the place box of the prefab (z -5..6) gains the column at -5 and loses 7.
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (StructureShape.Cell c : boat.cells()) {
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
        }
        int ox = Deployables.BOAT_STATION_BERTH_ORIGIN[0];
        int usualMin = Integer.MAX_VALUE, usualMax = Integer.MIN_VALUE, turnedMin = Integer.MAX_VALUE, turnedMax = Integer.MIN_VALUE;
        for (StructureShape.Cell c : boat.cells()) {
            int a = c.rotated(BoatRoutes.berthRotation(Rotation.None, false)).x + ox;
            int b = c.rotated(BoatRoutes.berthRotation(Rotation.None, true)).x + ox;
            usualMin = Math.min(usualMin, a);
            usualMax = Math.max(usualMax, a);
            turnedMin = Math.min(turnedMin, b);
            turnedMax = Math.max(turnedMax, b);
        }
        eq(ox + minZ, usualMin);
        eq(ox + maxZ, usualMax);
        eq(ox - maxZ, turnedMin);
        eq(ox - minZ, turnedMax);
        int[] box = {Deployables.STEAMBOAT_PUBLIC_PLACE_MIN[2], Deployables.STEAMBOAT_PUBLIC_PLACE_MAX[2]};
        eq(ox + box[0], -4);
        eq(ox + box[1], 7);
        eq(ox - box[1], -5);
        eq(ox - box[0], 6);
    }

    /** The berth heading is the one closest to the heading of the shuttle, with the wrap-around at +-pi. */
    private static void berthHeadingChoice() {
        for (Rotation r : ALL_ROTATIONS) {
            TransitNetwork.Station s = stationAt(r);
            double usual = BoatRoutes.berthTheta(s);
            check(!BoatRoutes.preferFlipped(usual, usual), "same heading keeps the usual one, " + r);
            check(BoatRoutes.preferFlipped(usual + Math.PI, usual), "opposite heading flips, " + r);
            check(BoatRoutes.preferFlipped(usual - Math.PI, usual), "opposite heading (other side of the wrap) flips, " + r);
            check(!BoatRoutes.preferFlipped(usual + Math.toRadians(80), usual), "80 degrees off keeps the usual one, " + r);
            check(!BoatRoutes.preferFlipped(usual - Math.toRadians(80), usual), "-80 degrees off keeps the usual one, " + r);
            check(BoatRoutes.preferFlipped(usual + Math.toRadians(100), usual), "100 degrees off flips, " + r);
            check(BoatRoutes.preferFlipped(usual - Math.toRadians(100), usual), "-100 degrees off flips, " + r);
            check(!BoatRoutes.preferFlipped(usual + Math.PI / 2, usual) && !BoatRoutes.preferFlipped(usual - Math.PI / 2, usual),
                    "a quarter turn keeps the usual one, " + r);
            // A heading near the flipped berth heading flips.
            check(BoatRoutes.preferFlipped(BoatRoutes.berthTheta(s, true) + 0.3, usual), "heading near the flipped one, " + r);
        }
        // Wrap-around at +-pi for the usual heading pi (station Ninety): -0.9 pi is 0.1 pi away, 0.1 pi is 0.9 pi away.
        check(!BoatRoutes.preferFlipped(-Math.PI * 0.9, Math.PI), "-0.9 pi is next to pi");
        check(BoatRoutes.preferFlipped(Math.PI * 0.1, Math.PI), "0.1 pi is next to the opposite of pi");
        check(!BoatRoutes.preferFlipped(Math.PI * 0.95, Math.PI), "0.95 pi is next to pi");
        check(BoatRoutes.preferFlipped(-Math.PI * 0.05, Math.PI), "-0.05 pi is next to the opposite of pi");
    }

    private static void berthPivotRotation() {
        TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), Rotation.Ninety, null, null,
                new ArrayList<>(), Deployables.BOAT_STATION_KIND);
        // Berth origin {1, -1, 14} turned by Ninety (x, z) -> (z, -x) = (14, -1), plus the origin: (114, 63, 199).
        eq(new Vector3i(114, 63, 199), BoatRoutes.berthOrigin(s));
        eq(Rotation.OneEighty, BoatRoutes.berthRotation(s));
        // Pivot {0, 1, -1} turned by OneEighty = (0, 1, 1): cell (114, 64, 200), centre (114.5, 64, 200.5).
        Vector3d p = BoatRoutes.berthPivot(s);
        check(p.x == 114.5 && p.y == 64 && p.z == 200.5, "pivot " + p);
        check(Math.abs(BoatRoutes.berthTheta(s) - Math.PI) < 1e-6, "cap");
        eq(63, BoatRoutes.surfaceY(s));
        check(!BoatRoutes.tooFar(new Vector3i(0, 0, 0), new Vector3i(1000, 0, 1000)), "1414 blocs : cherché");
        check(BoatRoutes.tooFar(new Vector3i(0, 0, 0), new Vector3i(3000, 0, 3000)), "4243 blocs : coupé");
    }

    private static void waterRouteLongCrossing() {
        WaterRoute.ColumnProbe sea = (x, z) -> z >= 0 && z < 60 ? WaterRoute.Cell.WATER : WaterRoute.Cell.BLOCKED;
        WaterRoute.Search s = new WaterRoute.Search(sea, 20, 30, 3520, 30, 0, 0, 3540, 59);
        check(s.step(1_000_000) == WaterRoute.Status.FOUND, "traversée de 3500 blocs");
        check(!BoatRoutes.tooFar(new Vector3i(0, 0, 0), new Vector3i(3500, 0, 0)), "3500 blocs : cherché");
        check(BoatRoutes.tooFar(new Vector3i(0, 0, 0), new Vector3i(4500, 0, 0)), "4500 blocs : coupé");
    }

    private static void waterRouteTooLong() {
        char[][] m = waterMap('.');
        check(waterSearch(m, 20, 60, 100, 60).withMaxLength(50).step(100000) == WaterRoute.Status.NO_ROUTE, "trop long");
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.FOUND, "longueur par défaut");
    }

    private static void waterRouteBerthNearShore() {
        char[][] m = waterMap('.');
        waterRect(m, 0, 119, 0, 57, '#'); // long shoreline, water from z = 58
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        check(s.step(100000) == WaterRoute.Status.FOUND, "appontements près de la rive");
        for (int[] w : s.waypoints()) {
            boolean far = Math.hypot(w[0] - 20, w[1] - 60) > 16 && Math.hypot(w[0] - 100, w[1] - 60) > 16;
            if (far) {
                check(landDistance(m, w[0], w[1]) >= 8, "marge pleine loin des extrémités");
            }
        }
    }

    private static void waterRouteNegativeOffset() {
        char[][] m = waterMap('.');
        waterRect(m, 45, 75, 45, 75, '#');
        // Same island map, shifted by (-300, -500), even to keep the node grid aligned: box and coordinates are negative.
        WaterRoute.ColumnProbe base = waterProbe(m);
        WaterRoute.ColumnProbe shifted = (x, z) -> base.at(x + 300, z + 500);
        WaterRoute.Search s = new WaterRoute.Search(shifted, -280, -440, -200, -440, -300, -500, -181, -381);
        check(s.step(100000) == WaterRoute.Status.FOUND, "coordonnées négatives");
        WaterRoute.Search plain = waterSearch(m, 20, 60, 100, 60);
        plain.step(100000);
        check(plain.waypoints().size() == s.waypoints().size(), "même nombre de points");
        for (int i = 0; i < s.waypoints().size(); i++) {
            check(s.waypoints().get(i)[0] + 300 == plain.waypoints().get(i)[0]
                && s.waypoints().get(i)[1] + 500 == plain.waypoints().get(i)[1], "point décalé " + i);
        }
    }

    private static void waterRouteResumedSame() {
        char[][] m = waterMap('.');
        waterRect(m, 45, 75, 45, 75, '#');
        WaterRoute.Search whole = waterSearch(m, 20, 60, 100, 60);
        check(whole.step(100000) == WaterRoute.Status.FOUND, "recherche continue");
        waterRect(m, 30, 30, 0, 119, '?');
        WaterRoute.Search cut = waterSearch(m, 20, 60, 100, 60);
        check(cut.step(100000) == WaterRoute.Status.NEED_DATA, "interrompue");
        waterRect(m, 30, 30, 0, 119, '.');
        check(cut.step(100000) == WaterRoute.Status.FOUND, "reprise");
        check(cut.missingColumn() == null, "colonne manquante remise à zéro");
        check(cut.waypoints().size() == whole.waypoints().size(), "même nombre de points");
        for (int i = 0; i < cut.waypoints().size(); i++) {
            check(java.util.Arrays.equals(cut.waypoints().get(i), whole.waypoints().get(i)), "même point " + i);
        }
    }

    /** The ship turns on the spot at each waypoint: far from the ends, the corners keep the whole turning circle clear. */
    private static void waterRouteTurnClearance() {
        char[][] m = waterMap('.');
        waterRect(m, 45, 75, 45, 75, '#');
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        check(s.step(100000) == WaterRoute.Status.FOUND, "île contournée");
        List<int[]> w = s.waypoints();
        for (int i = 0; i < w.size() - 1; i++) {
            boolean far = Math.hypot(w.get(i)[0] - 20, w.get(i)[1] - 60) > 40 && Math.hypot(w.get(i)[0] - 100, w.get(i)[1] - 60) > 40;
            if (far) {
                check(landDistance(m, w.get(i)[0], w.get(i)[1]) >= WaterRoute.TURN_CLEARANCE - 1, "coin à moins du cercle de rotation de la terre");
            }
        }
    }

    private static void waterRouteAvoid() {
        char[][] m = waterMap('.');
        check(waterSearch(m, 20, 60, 100, 60).avoiding(java.util.Set.of()).step(100000) == WaterRoute.Status.FOUND, "sans colonne à éviter");
        WaterRoute.Search plain = waterSearch(m, 20, 60, 100, 60);
        plain.step(100000);
        check(plain.waypoints().size() == 1, "ligne droite sans obstacle");
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60).avoiding(java.util.Set.of(WaterRoute.columnKey(60, 60)));
        check(s.step(100000) == WaterRoute.Status.FOUND, "route avec colonne à éviter");
        check(s.waypoints().size() > 1, "la route contourne la colonne : " + s.waypoints().size());
    }

    /** An unloaded column in a turn circle is asked for a few times, then counts as not clear: the search ends (no endless NEED_DATA). */
    private static void waterRouteTurnData() {
        char[][] m = waterMap('.');
        waterRect(m, 45, 75, 45, 75, '#');
        m[50][8] = '?';
        m[50][112] = '?';
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        WaterRoute.Status st = WaterRoute.Status.RUNNING;
        int calls = 0;
        while ((st == WaterRoute.Status.NEED_DATA || st == WaterRoute.Status.RUNNING) && calls++ < 200) {
            st = s.step(100000);
        }
        check(st == WaterRoute.Status.FOUND, "route trouvée malgré une colonne jamais chargée : " + st);
        check(calls < 100, "pas d'attente sans fin : " + calls);
    }

    private static void waterRouteFreeStart() {
        char[][] m = waterMap('.');
        waterRect(m, 22, 24, 55, 65, '#');
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.FOUND, "départ normal : le taper laisse passer");
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60).withFreeStart();
        check(s.step(100000) == WaterRoute.Status.FOUND, "départ libre : un bateau bloqué par le côté peut repartir");
        WaterRoute.Search a = waterSearch(m, 20, 60, 100, 60).withFreeStart()
                .avoiding(java.util.Set.of(WaterRoute.columnKey(21, 60)));
        check(a.step(100000) == WaterRoute.Status.FOUND, "départ libre avec colonne interdite : encore une route");
    }

    // ---- WaterRoute.exitsToOpenWater ----

    /** Open-water check on a 400 x 400 map: land (#) everywhere, then the given water; start at (200, 200). */
    private static boolean exitsAt(java.util.function.Consumer<char[][]> fill, char base) {
        char[][] m = new char[400][400];
        for (char[] row : m) {
            java.util.Arrays.fill(row, base);
        }
        fill.accept(m);
        WaterRoute.ColumnProbe p = (x, z) -> {
            if (x < 0 || z < 0 || x >= 400 || z >= 400) {
                return WaterRoute.Cell.BLOCKED;
            }
            char c = m[x][z];
            return c == '.' ? WaterRoute.Cell.WATER : c == '?' ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.BLOCKED;
        };
        return WaterRoute.exitsToOpenWater(p, 200, 200);
    }

    private static void waterExitOpen() {
        check(exitsAt(m -> { }, '.'), "pleine mer : sortie");
    }

    private static void waterExitPond() {
        check(!exitsAt(m -> waterRect(m, 165, 235, 165, 235, '.'), '#'), "étang de 71 blocs : pas de sortie");
    }

    private static void waterExitShore() {
        // Small cove around the start, a 5-block wall of land, then open sea.
        check(!exitsAt(m -> {
            waterRect(m, 185, 210, 185, 215, '.');
            waterRect(m, 216, 399, 0, 399, '.');
        }, '#'), "mur de terre face au poste : pas de sortie");
    }

    private static void waterExitNarrowChannel() {
        // Basin around the start, then a channel of 2 * CLEARANCE (18) wide, one block too narrow, to open sea.
        check(!exitsAt(m -> {
            waterRect(m, 180, 215, 180, 220, '.');
            waterRect(m, 216, 235, 191, 208, '.');
            waterRect(m, 236, 399, 0, 399, '.');
        }, '#'), "chenal de 18 : pas de sortie");
    }

    private static void waterExitWideChannel() {
        check(exitsAt(m -> {
            waterRect(m, 180, 215, 180, 220, '.');
            waterRect(m, 216, 235, 190, 210, '.');
            waterRect(m, 236, 399, 0, 399, '.');
        }, '#'), "chenal de 21 : sortie");
    }

    private static void waterExitUnknown() {
        check(!exitsAt(m -> { }, '?'), "colonnes inconnues partout : pas de sortie");
    }

    private static void boatLookAhead() {
        // Heading north (-z): an obstacle disc around (50, 70).
        BoatAutopilot.PoseTest t = (x, z, th) -> Math.hypot(x - 50, z - 70) < 10;
        double[] hit = BoatAutopilot.blockedAhead(t, 50.5, 100.5, new int[] {50, 40}, new int[] {50, 0});
        check(hit != null && hit[0] == 24, "obstacle vu à 24 blocs");
        check(BoatAutopilot.blockedAhead(t, 50.5, 100.5, new int[] {50, 90}, new int[] {50, 0}) == null, "waypoint avant l'obstacle, rien ne gêne");
        // The final leg is scanned too (next null): the obstacle at 24 blocs is seen while the berth guard only cuts the last 12.
        check(BoatAutopilot.blockedAhead(t, 50.5, 100.5, new int[] {50, 20}, null) != null, "dernier tronçon scruté");
        BoatAutopilot.PoseTest free = (x, z, th) -> false;
        check(BoatAutopilot.blockedAhead(free, 50.5, 100.5, new int[] {50, 40}, null) == null, "voie libre");
        // Last waypoint (berth point): poses closer than END_GUARD to it are not judged.
        BoatAutopilot.PoseTest nearEnd = (x, z, th) -> z < 52;
        check(BoatAutopilot.blockedAhead(nearEnd, 50.5, 70.5, new int[] {50, 50}, null) == null, "zone du dernier point ignorée");
        // Corner: the turn at the waypoint is judged with the next heading.
        BoatAutopilot.PoseTest corner = (x, z, th) -> Math.abs(x - 50.5) < 0.1 && Math.abs(z - 80.5) < 0.1 && Math.abs(th) > 0.5;
        check(BoatAutopilot.blockedAhead(corner, 50.5, 100.5, new int[] {50, 80}, new int[] {10, 80}) != null, "virage au point jugé");
        // A turn blocked only partway (the logged failure): ends free, an intermediate heading blocked.
        BoatAutopilot.PoseTest mid = (x, z, th) -> Math.abs(th - 0.5) < 0.1;
        check(BoatAutopilot.turnBlocked(mid, 0, 0, 0, 1.0), "virage bloqué à mi-course");
        check(!BoatAutopilot.turnBlocked(mid, 0, 0, 0, 0.3), "virage libre");
    }

    private static void boatDetour() {
        BoatAutopilot.PoseTest t = (x, z, th) -> Math.hypot(x - 50, z - 70) < 10;
        int[] wp = {50, 40};
        check(BoatAutopilot.detour(t, 50.5, 100.5, AirshipAutopilot.headingTo(0, -1), wp, 24, 0) == null, "3 blocs de côté : encore dans l'obstacle");
        double[] d = null;
        int found = -1;
        for (int n = 0; n < BoatAutopilot.MAX_SHIFT_TRIES && d == null; n++) {
            d = BoatAutopilot.detour(t, 50.5, 100.5, AirshipAutopilot.headingTo(0, -1), wp, 24, n);
            found = n;
        }
        check(d != null, "un déport libre existe");
        check(Math.abs(d[0] - 50.5) >= 6, "déport latéral d'au moins 6 blocs : " + d[0]);
        check(Math.abs(d[0] - Math.floor(d[0]) - 0.5) < 1e-9 && Math.abs(d[1] - Math.floor(d[1]) - 0.5) < 1e-9, "centre de colonne");
        check(d[1] < 100.5 - 30, "le point est au-delà de l'obstacle : " + d[1]);
        check(found >= 2, "un déport de plus de 3 blocs : " + found);
        check(BoatAutopilot.detour(t, 50.5, 100.5, AirshipAutopilot.headingTo(0, -1), wp, 24, BoatAutopilot.MAX_SHIFT_TRIES) == null, "hors liste");
        BoatAutopilot.PoseTest wall = (x, z, th) -> z < 90 && z > 50;
        for (int n = 0; n < BoatAutopilot.MAX_SHIFT_TRIES; n++) {
            check(BoatAutopilot.detour(wall, 50.5, 100.5, AirshipAutopilot.headingTo(0, -1), wp, 12, n) == null, "mur sans passage : aucun déport");
        }
    }

    private static void paddleSlideWheels() {
        PaddleWheels.Spin[] w = PaddleWheels.slideWheels();
        check(w[0] == PaddleWheels.Spin.FWD_SLOW && w[1] == PaddleWheels.Spin.FWD_SLOW, "glissade : deux roues lentes en avant");
        check(!java.util.Arrays.equals(w, PaddleWheels.wheelsFor(false, false, 0)), "différent de l'arrêt");
    }

    private static void boatAutopilotHeading() {
        // Waypoints are pivot columns: the target is the column centre (x + 0.5, z + 0.5). Bow = (-sin theta, -cos theta).
        near(0.0, BoatAutopilot.targetHeading(10.5, 20.5, new int[] {10, 0})); // straight to -z
        near(Math.PI / 2, BoatAutopilot.targetHeading(10.5, 20.5, new int[] {0, 20})); // to -x
        near(-Math.PI / 2, BoatAutopilot.targetHeading(10.5, 20.5, new int[] {30, 20})); // to +x
        near(Math.PI, Math.abs(BoatAutopilot.targetHeading(10.5, 20.5, new int[] {10, 40}))); // to +z
        double h = BoatAutopilot.targetHeading(0.5, 0.5, new int[] {7, -9});
        near(AirshipAutopilot.headingTo(7, -9), h);
    }

    private static void boatAutopilotReached() {
        int[] wp = {10, 10};
        check(BoatAutopilot.reached(10.5, 10.5, wp, BoatAutopilot.WAYPOINT_RADIUS), "on the point");
        check(BoatAutopilot.reached(13.4, 10.5, wp, BoatAutopilot.WAYPOINT_RADIUS), "2.9 blocks away");
        check(!BoatAutopilot.reached(13.6, 10.5, wp, BoatAutopilot.WAYPOINT_RADIUS), "3.1 blocks away");
        near(3.0, BoatAutopilot.WAYPOINT_RADIUS);
    }

    /** Pre-berth point, route trimming and turning-circle clearance of the sideways berthing manoeuvre. */
    private static void boatBerthManoeuvre() {
        int s = BoatRoutes.BERTH_STANDOFF;
        org.joml.Vector3i berth = new org.joml.Vector3i(100, 60, 200);
        // the pre-berth point lies along the station's +z axis, rotated with the station
        org.joml.Vector3i p0 = BoatRoutes.preBerthCell(com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, berth, s);
        check(p0.x == 100 && p0.z == 200 + s && p0.y == 60, "rotation None: +z");
        org.joml.Vector3i p90 = BoatRoutes.preBerthCell(com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.Ninety, berth, s);
        check(Math.abs(p90.x - 100) == s && p90.z == 200 && Integer.signum(p90.x - 100) == Integer.signum(
                com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.Ninety.rotateYaw(new org.joml.Vector3i(0, 0, 1),
                        new org.joml.Vector3i()).x), "rotation Ninety: along x, signed");
        // turning circle of the hull (corner of the prefab box around the rotation centre) stays clear of the pier edge
        int[] mn = Deployables.STEAMBOAT_PUBLIC_PLACE_MIN;
        int[] mx = Deployables.STEAMBOAT_PUBLIC_PLACE_MAX;
        double rx = Math.max(Math.abs(mn[0]), Math.abs(mx[0])) + 0.5;
        double rz = Math.max(Math.abs(mn[2]), Math.abs(mx[2])) + 0.5;
        double radius = Math.hypot(rx, rz);
        double berthZ = Deployables.BOAT_STATION_BERTH_ORIGIN[2];
        double pierEdge = Deployables.BOAT_STATION_PIER_END[2] + 1;
        check(berthZ + s - radius >= pierEdge + 1, "turning circle clear of the pier by a block (radius " + radius + ")");
        // arrival route: waypoints nearer than the standoff are dropped, the pre-berth point ends the route
        org.joml.Vector3i pre = new org.joml.Vector3i(100, 60, 200 + s);
        java.util.List<int[]> route = java.util.List.of(new int[] {100, 300}, new int[] {100, 230}, new int[] {100, 200 + s - 2},
                new int[] {100, 200 + s - 4}, new int[] {100, 200});
        java.util.List<int[]> in = BoatRoutes.arriveRoute(route, berth, pre, s);
        check(in.size() == 3, "arrive: 2 far waypoints + pre-berth, got " + in.size());
        check(in.get(2)[0] == 100 && in.get(2)[1] == 200 + s, "arrive: ends at the pre-berth point");
        java.util.List<int[]> in2 = BoatRoutes.arriveRoute(java.util.List.of(new int[] {100, 300}, new int[] {100, 200 + s}, new int[] {100, 200}),
                berth, pre, s);
        check(in2.size() == 2, "arrive: no duplicate pre-berth point");
        // departure route: starts at the pre-berth point, nearer waypoints dropped
        java.util.List<int[]> out = BoatRoutes.departRoute(java.util.List.of(new int[] {100, 200}, new int[] {100, 200 + s - 2}, new int[] {100, 230},
                new int[] {100, 300}), berth, pre, s);
        check(out.size() == 3 && out.get(0)[1] == 200 + s && out.get(1)[1] == 230, "depart: pre-berth first, then the far waypoints");
        java.util.List<int[]> out2 = BoatRoutes.departRoute(java.util.List.of(new int[] {100, 200 + s}, new int[] {100, 300}), berth, pre, s);
        check(out2.size() == 2, "depart: no duplicate pre-berth point");
        // abandoning the sideways arrival resumes at the first dropped waypoint
        check(BoatRoutes.firstDropped(route, in) == 2, "resume at the first dropped waypoint (got " + BoatRoutes.firstDropped(route, in) + ")");
        check(BoatRoutes.firstDropped(route, java.util.List.of(new int[] {1, 1})) == 0, "resume index never negative");
        // a voyage list keeps both ends: departure slide-out then arrival at the destination pre-berth point
        org.joml.Vector3i dest = new org.joml.Vector3i(100, 60, 900);
        org.joml.Vector3i destPre = new org.joml.Vector3i(100, 60, 900 + s);
        java.util.List<int[]> voyage = java.util.List.of(new int[] {100, 200}, new int[] {100, 300}, new int[] {100, 880}, new int[] {100, 900});
        java.util.List<int[]> both = BoatRoutes.arriveRoute(BoatRoutes.departRoute(voyage, berth, pre, s), dest, destPre, s);
        check(both.get(0)[1] == 200 + s, "voyage: starts at the departure pre-berth point");
        check(both.get(both.size() - 1)[1] == 900 + s, "voyage: ends at the arrival pre-berth point");
        check(both.size() == 4, "voyage: pre, 300, 880, arrival pre (" + both.size() + ")");
        // the seaward sign is pinned for every rotation: pre-berth direction = from the pier end toward the berth, rotated
        int lateral = Deployables.BOAT_STATION_BERTH_ORIGIN[2] - Deployables.BOAT_STATION_PIER_END[2];
        for (com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation r : com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.values()) {
            org.joml.Vector3i want = r.rotateYaw(new org.joml.Vector3i(0, 0, lateral), new org.joml.Vector3i());
            org.joml.Vector3i got = BoatRoutes.preBerthCell(r, new org.joml.Vector3i(), s);
            check(Integer.signum(got.x) == Integer.signum(want.x) && Integer.signum(got.z) == Integer.signum(want.z), "seaward sign " + r);
        }
        // slide: half the sailing speed, never under the minimum
        near(2.58, BoatAutopilot.slideSpeed(50, 5.16));
        near(BoatAutopilot.MIN_SPEED, BoatAutopilot.slideSpeed(0, 5.16));
    }

    private static void boatAutopilotSpeed() {
        near(5.16, BoatAutopilot.sailSpeed(100, 5.16));
        near(5.16, BoatAutopilot.sailSpeed(12, 5.16));
        check(BoatAutopilot.sailSpeed(6, 5.16) < 5.16, "slower in the last 12 blocks");
        check(BoatAutopilot.sailSpeed(3, 5.16) < BoatAutopilot.sailSpeed(6, 5.16), "slower still nearer");
        near(0.5, BoatAutopilot.sailSpeed(0, 5.16));
        near(0.5, BoatAutopilot.sailSpeed(0.2, 5.16));
        near(0.3, BoatAutopilot.sailSpeed(50, 0.3)); // never above the full speed
    }

    private static void shuttleFuellessRules() {
        // Engine sound: the fuelless shuttle always runs, the players' ships keep their rule.
        check(AirshipManager.engineSoundWanted(false, false, true), "shuttle without engines: runs");
        check(AirshipManager.engineSoundWanted(false, true, true), "shuttle never dry");
        check(AirshipManager.engineSoundWanted(true, false, false), "steamboat fuelled: runs");
        check(!AirshipManager.engineSoundWanted(true, true, false), "steamboat dry: silent");
        check(!AirshipManager.engineSoundWanted(false, false, false), "no engines: silent");
        // Wheels: pilotless without engines still turn for the shuttle, never for the players' ships.
        check(!PaddleWheels.still(false, false, false, false, true, true, true), "shuttle sailing: wheels turn");
        check(PaddleWheels.still(false, true, false, false, true, true, true), "shuttle off: still");
        check(PaddleWheels.still(false, false, true, false, true, true, true), "shuttle landing: still");
        check(PaddleWheels.still(false, false, false, true, true, false, false), "steamboat pilotless: still");
        check(PaddleWheels.still(false, false, false, false, false, false, false), "steamboat without engines: still");
        check(PaddleWheels.still(true, false, false, true, false, false, false), "steamboat dry: still");
        check(!PaddleWheels.still(false, false, false, true, false, false, false), "steamboat piloted and fuelled: turns");
        check(!PaddleWheels.still(false, false, false, true, true, false, true), "steamboat voyage (NPC pilot): wheels turn");
        check(PaddleWheels.still(true, false, false, true, true, false, true), "steamboat voyage dry: still");
        check(PaddleWheels.still(false, false, true, true, true, false, true), "steamboat voyage landing: still");
    }

    private static void berthCentreRotation() {
        TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), Rotation.Ninety, null, null,
                new ArrayList<>(), Deployables.BOAT_STATION_KIND);
        // Station Ninety at (100, 64, 200): berth origin {1, -1, 14} -> (114, 63, 199), boat rotation OneEighty, theta = pi.
        // Pivot (0, 1, -1) turned -> (0, 1, 1): cell (114, 64, 200), pivot (114.5, 64, 200.5). Centre = prefab (0, z 0) at the pivot
        // height: cell (114, 64, 199), centre (114.5, 64, 199.5).
        org.joml.Vector3d p = BoatRoutes.berthPivot(s);
        check(Math.abs(p.x - 114.5) < 1e-6 && Math.abs(p.z - 200.5) < 1e-6, "pivot " + p);
        org.joml.Vector3d c = BoatRoutes.berthCentre(s);
        check(Math.abs(c.x - 114.5) < 1e-6 && Math.abs(c.y - 64) < 1e-6 && Math.abs(c.z - 199.5) < 1e-6, "centre " + c);
        eq(new Vector3i(114, 64, 199), BoatRoutes.berthCentreCell(s));
        // Turning keeps the centre fixed: the pivot is found back from the centre at the berth heading.
        org.joml.Vector3d back = AirshipManager.get().pivotFromCenter(Deployables.STEAMBOAT_PUBLIC, c, BoatRoutes.berthTheta(s));
        check(back.distance(p) < 1e-6, "pivot from centre " + back);
        // Station None: berth origin {1, -1, 14} -> (101, 63, 214), boat rotation Ninety ((x, z) -> (z, -x)), theta = pi / 2.
        // Pivot (0, 1, -1) -> (-1, 1, 0): cell (100, 64, 214), pivot (100.5, 64, 214.5). Centre = origin column: (101.5, 64, 214.5).
        TransitNetwork.Station n = new TransitNetwork.Station("N", "w", new Vector3i(100, 64, 200), Rotation.None, null, null,
                new ArrayList<>(), Deployables.BOAT_STATION_KIND);
        eq(Rotation.Ninety, BoatRoutes.berthRotation(n));
        org.joml.Vector3d pn = BoatRoutes.berthPivot(n);
        check(Math.abs(pn.x - 100.5) < 1e-6 && Math.abs(pn.z - 214.5) < 1e-6, "Ninety berth pivot " + pn);
        org.joml.Vector3d cn = BoatRoutes.berthCentre(n);
        check(Math.abs(cn.x - 101.5) < 1e-6 && Math.abs(cn.y - 64) < 1e-6 && Math.abs(cn.z - 214.5) < 1e-6, "Ninety berth centre " + cn);
        check(AirshipManager.get().pivotFromCenter(Deployables.STEAMBOAT_PUBLIC, cn, BoatRoutes.berthTheta(n)).distance(pn) < 1e-6,
                "Ninety pivot from centre");
    }

    private static void boatDropPoints() throws Exception {
        StructureShape station = prefabShape("Boat_Station.prefab.json");
        float[][] pts = Deployables.BOAT_STATION_DROP_POINTS;
        check(pts.length == 12, "12 drop points, got " + pts.length);
        Set<String> seen = new java.util.HashSet<>();
        for (float[] p : pts) {
            int x = Math.round(p[0]);
            int z = Math.round(p[2]);
            check(Math.abs(p[0] - x) < 1e-6 && Math.abs(p[2] - z) < 1e-6, "whole cell x, z " + java.util.Arrays.toString(p));
            check(seen.add(x + "," + z), "distinct point " + x + "," + z);
            StructureShape.Cell deck = station.cellAt(x, 0, z);
            check(deck != null && "Wood_Hardwood_Planks_Half".equals(deck.baseName()), "deck half plank under " + x + "," + z);
            check(Math.abs(p[1] - 0.6f) < 1e-6, "feet on the deck top plus 0.1, got " + p[1]);
            check(station.cellAt(x, 1, z) == null && station.cellAt(x, 2, z) == null, "two free cells above " + x + "," + z);
        }
        // World conversion: whole cell rotated with the station, then the cell centre.
        List<org.joml.Vector3d> w = BoatQuay.dropPoints(new Vector3i(100, 64, 200), Rotation.None);
        check(w.size() == 12, "12 world points");
        near(100 + pts[0][0] + 0.5, w.get(0).x);
        near(64 + 0.6, w.get(0).y);
        near(200 + pts[0][2] + 0.5, w.get(0).z);
    }

    private static void waterLogLineFill() {
        WaterLog w = new WaterLog();
        w.record(1, 1);          // cell (0, 0)
        w.record(11, 5);         // cell (5, 2): skipped cells added along the line
        eq(6, w.size());
        check(w.contains(0, 0) && w.contains(5, 2) && w.contains(3, 1), "8-connected line");
        w.forgetLastPosition();
        w.record(41, 41);        // new line: only this cell
        eq(7, w.size());
    }

    private static void waterLogUPath() {
        WaterLog w = new WaterLog();
        w.record(1, 1); w.record(1, 41); w.record(41, 41); w.record(41, 1); // a U: down, across, up
        List<int[]> p = w.path(1, 1, 41, 1, java.util.Set.of());
        check(!p.isEmpty(), "path found");
        for (int[] pt : p) check(w.contains(WaterLog.gridOf(pt[0]), WaterLog.gridOf(pt[1])), "path stays on the log");
        check(p.stream().anyMatch(pt -> pt[1] >= 39), "the path goes round the bottom of the U");
        check(w.path(1, 1, 41, 1, java.util.Set.of(WaterLog.cell(10, 20))).isEmpty(), "U cut at its bottom: no path");
        List<int[]> c = WaterLog.collapse(p);
        check(c.size() >= 4 && c.size() <= 6, "start, the corners (a corner may be cut diagonally), goal");
        check(java.util.Arrays.equals(p.get(0), c.get(0)) && java.util.Arrays.equals(p.get(p.size() - 1), c.get(c.size() - 1)), "ends kept");
    }

    private static void waterLogStops() {
        WaterLog w = new WaterLog();
        eq("1", w.nextStopNumber());
        eq(null, w.addStop(0, 62, 0, Rotation.None, 0, 0));
        eq("crew.stop.tooClose", w.addStop(20, 62, 0, Rotation.None, 20, 0));
        eq(null, w.addStop(40, 62, 0, Rotation.None, 40, 0));
        check(w.contains(WaterLog.gridOf(40), WaterLog.gridOf(0)), "a stop's cell is logged");
        check(w.forgetStop("1"), "forget");
        eq("1", w.nextStopNumber()); // smallest free number
    }

    private static void waterLogRoundTrip() {
        WaterLog w = new WaterLog();
        w.record(1, 1); w.record(9, 1);
        w.addStop(40, 62, 0, Rotation.Ninety, 41, 3);
        w.addReached(100, 61, 7);
        WaterLog back = WaterLog.fromBson(w.toBson());
        eq(w.size(), back.size());
        eq(Rotation.Ninety, back.stops().get(0).rotation());
        eq(41, back.stops().get(0).cx());
        eq(new WaterLog.Reached(100, 61, 7), back.reached().get(0));
        eq(0, WaterLog.fromBson(new org.bson.BsonDocument()).size());
    }

    private static void knownStationsAddOnce() {
        KnownStations k = new KnownStations();
        java.util.UUID a = java.util.UUID.randomUUID();
        SkyLog.Reached st = new SkyLog.Reached(1, 64, 2);
        check(!k.known("w", a, st), "unknown at first");
        check(k.add("w", a, st), "first add is new");
        check(!k.add("w", a, st), "second add is not new");
        check(k.known("w", a, st), "known after add");
    }

    private static void knownStationsSeparation() {
        KnownStations k = new KnownStations();
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID b = java.util.UUID.randomUUID();
        SkyLog.Reached st = new SkyLog.Reached(1, 64, 2);
        k.add("w", a, st);
        check(!k.known("w", b, st), "another player does not know it");
        check(!k.known("other", a, st), "another world does not know it");
        check(k.add("w", b, st), "the other player discovers it on his own");
        check(k.add("other", a, st), "the other world discovers it on its own");
    }

    private static void knownStationsShared() {
        KnownStations k = new KnownStations();
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID late = java.util.UUID.randomUUID();
        SkyLog.Reached st = new SkyLog.Reached(1, 64, 2);
        check(!k.isShared("w", st), "not shared at first");
        check(k.share("w", st), "first share is new");
        check(!k.share("w", st), "second share is not new");
        check(k.known("w", a, st), "a shared station is known to everyone");
        check(k.known("w", late, st), "also to a player who comes later");
        check(!k.known("other", a, st), "but only in its world");
        check(!k.knownPersonally("w", a, st), "shared is not personal");
        check(k.add("w", a, st), "personal discovery still counts once");
    }

    private static void knownStationsRoundTrip() {
        KnownStations k = new KnownStations();
        java.util.UUID a = java.util.UUID.randomUUID();
        k.add("w", a, new SkyLog.Reached(1, 64, 2));
        k.add("w", a, new SkyLog.Reached(-5, 70, 9));
        k.share("w2", new SkyLog.Reached(3, 4, 5));
        KnownStations back = new KnownStations();
        back.fromBson(org.bson.BsonDocument.parse(k.toBson().toJson()));
        check(back.known("w", a, new SkyLog.Reached(1, 64, 2)), "personal station kept");
        check(back.known("w", a, new SkyLog.Reached(-5, 70, 9)), "negative coordinates kept");
        check(back.isShared("w2", new SkyLog.Reached(3, 4, 5)), "shared station kept");
        check(!back.known("w", java.util.UUID.randomUUID(), new SkyLog.Reached(1, 64, 2)), "still separated by player");
    }

    private static void knownStationsMalformed() {
        KnownStations k = new KnownStations();
        java.util.UUID a = java.util.UUID.randomUUID();
        k.fromBson(org.bson.BsonDocument.parse("{\"players\": [1, {\"world\": \"w\", \"player\": \"nope\", \"stations\": []},"
                + " {\"world\": \"w\", \"player\": \"" + a + "\", \"stations\": [[1, 2], \"x\", [4, 5, 6]]}], \"shared\": 7}"));
        check(k.known("w", a, new SkyLog.Reached(4, 5, 6)), "valid entry kept among bad ones");
        k.fromBson(new org.bson.BsonDocument());
        check(!k.known("w", a, new SkyLog.Reached(4, 5, 6)), "empty document gives an empty list");
        k.fromBson(null);
    }

    private static void skyLogCells() {
        SkyLog k = new SkyLog();
        check(k.isEmpty(), "new log is empty");
        check(k.record(0, 0), "first cell is new");
        check(!k.record(15.9, 15.9), "same 16-block cell is not new");
        check(k.record(16, 0), "next cell is new");
        check(k.record(-0.1, 0), "negative side is the cell before 0");
        check(k.contains(-1, 0) && k.contains(0, 0) && k.contains(1, 0), "cells by floorDiv 16");
        k.forgetLastPosition();
        k.record(0, 0);
        k.record(64, 0); // cells skipped on the way are added along the line
        check(k.contains(2, 0) && k.contains(3, 0) && k.contains(4, 0), "skipped cells added");
        check(!k.copy().isEmpty() && k.copy().size() == k.size(), "copy keeps the cells");
    }

    private static void destinationKnownOthers() {
        java.util.function.BiFunction<String, Integer, TransitNetwork.Station> mk = (n, x) -> new TransitNetwork.Station(n, "w",
                new Vector3i(x, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station self = mk.apply("Gare", 0), b = mk.apply("B", 100), c = mk.apply("C", 200);
        java.util.List<TransitNetwork.Station> all = java.util.List.of(self, b, c);
        java.util.List<TransitNetwork.Station> out = DestinationPage.knownOthers(all, self, s -> s != c);
        eq(1, out.size());
        check(out.get(0) == b, "only the known station other than self is listed");
        check(DestinationPage.knownOthers(all, self, s -> false).isEmpty(), "nothing known gives an empty list");
        eq(2, DestinationPage.knownOthers(all, self, s -> true).size());
    }

    private static void skyLogStationDiscovery() {
        SkyLog k = new SkyLog();
        check(k.addReached(10, 64, 20), "first discovery is new");
        check(!k.addReached(10, 64, 20), "second discovery is not new");
        check(k.addReached(10, 65, 20), "another station is new");
        eq(2, k.reached().size());
        check(!k.isEmpty(), "a log with a station is not empty");
        check(SkyLog.stationInReach(0, 0, 40, 0), "exactly 40 blocks is in reach");
        check(!SkyLog.stationInReach(0, 0, 41, 0), "41 blocks is out of reach");
        check(!SkyLog.stationInReach(0, 0, 30, 30), "diagonal 42.4 blocks is out of reach");
        check(k.copy().hasReached(new SkyLog.Reached(10, 64, 20)), "copy keeps the stations");
    }

    private static void skyLogMarkerDistance() {
        SkyLog k = new SkyLog();
        check(!k.markerVisited(0, 0), "empty log: nothing visited");
        k.record(8, 8); // cell (0, 0): square 0..16
        check(k.markerVisited(8, 8), "marker inside the cell");
        check(k.markerVisited(16 + 32, 8), "32 blocks from the cell edge");
        check(!k.markerVisited(16 + 33, 8), "33 blocks from the cell edge");
        check(k.markerVisited(-32, 8), "32 blocks on the other side");
        check(!k.markerVisited(-33, 8), "33 blocks on the other side");
        check(!k.markerVisited(16 + 25, 16 + 25), "corner distance 35.4 is too far");
        check(k.markerVisited(16 + 22, 16 + 22), "corner distance 31.1 is near enough");
    }

    private static void skyLogRoundTrip() {
        SkyLog k = new SkyLog();
        k.record(1, 1);
        k.record(100, -40);
        k.addReached(5, 70, -9);
        SkyLog back = SkyLog.fromBson(k.toBson());
        eq(k.size(), back.size());
        eq(new SkyLog.Reached(5, 70, -9), back.reached().get(0));
        check(back.markerVisited(100, -40), "visited cells survive the round trip");
        check(SkyLog.fromBson(new org.bson.BsonDocument()).isEmpty(), "empty document gives an empty log");
        check(SkyLog.fromBson(null).isEmpty(), "null gives an empty log");
    }

    private static void skyLogDestinationFilter() {
        SkyLog.Reached known = new SkyLog.Reached(1, 2, 3);
        List<VoyagePlanner.Destination> all = List.of(
                new VoyagePlanner.Destination("Known", VoyagePlanner.Kind.STATION, 500, 0, 60, known),
                new VoyagePlanner.Destination("Unknown", VoyagePlanner.Kind.STATION, 900, 0, 60, new SkyLog.Reached(9, 9, 9)),
                new VoyagePlanner.Destination("Near", VoyagePlanner.Kind.MARKER, 20, 20, Integer.MIN_VALUE),
                new VoyagePlanner.Destination("Far", VoyagePlanner.Kind.MARKER, 800, 800, Integer.MIN_VALUE));
        eq(0, VoyagePlanner.known(all, null).size());
        eq(0, VoyagePlanner.known(all, new SkyLog()).size());
        SkyLog k = new SkyLog();
        k.record(0, 0);
        k.addReached(1, 2, 3);
        List<VoyagePlanner.Destination> kept = VoyagePlanner.known(all, k);
        eq(2, kept.size());
        eq("Known", kept.get(0).name());
        eq("Near", kept.get(1).name());
    }

    private static void waterLogEmptyStart() {
        WaterLog w = new WaterLog();
        eq(List.of(), w.path(1, 1, 41, 1, java.util.Set.of()));
        eq(null, w.stationTarget(0, 0, 0, 0));
    }

    private static void reachedStationsDropRemoved() {
        WaterLog w = new WaterLog();
        w.addReached(1, 2, 3);
        w.addReached(4, 5, 6);
        w.dropReachedNotIn(java.util.Set.of(new WaterLog.Reached(4, 5, 6)));
        eq(List.of(new WaterLog.Reached(4, 5, 6)), w.reached());
    }

    private static void waterLogStationTarget() {
        WaterLog w = new WaterLog();
        w.record(1, 1); w.record(121, 1); // a straight run from the station to 120 blocks east
        int[] t = w.stationTarget(10, 0, 0, 0);
        check(t != null && Math.hypot(t[0], t[1]) >= WaterLog.STATION_STANDOFF, "outside the zone and the hull reach");
        check(Math.hypot(t[0], t[1]) < WaterLog.STATION_STANDOFF + 3, "nearest such cell to the pier");
        eq(TransitConstants.STATION_ZONE_RADIUS + 20, WaterLog.STATION_STANDOFF); // the hull (about 17.2 blocks) never reaches the zone
        check(Math.hypot(7.5, 15.5) + 2 <= WaterLog.HULL_REACH, "hull corner plus margin within the reach");
        check(Math.hypot(t[0], t[1]) - Math.hypot(7.5, 15.5) >= TransitConstants.STATION_ZONE_RADIUS, "any heading keeps the hull out");
    }

    private static void boatBackoffAction() {
        eq(ShipVoyage.BackoffAction.SAIL_BACK, ShipVoyage.backoffAction(true, false));
        eq(ShipVoyage.BackoffAction.RELEASE_RIDERS, ShipVoyage.backoffAction(false, false)); // queue exhausted: riders set down once
        eq(ShipVoyage.BackoffAction.KEEP_TRYING, ShipVoyage.backoffAction(false, true));     // then the boat keeps trying alone
        eq(ShipVoyage.BackoffAction.SAIL_BACK, ShipVoyage.backoffAction(true, true));
    }

    private static void voyageEndAction() {
        eq(BoatAutopilot.EndAction.END_SHUTTLE, BoatAutopilot.endAction(true));
        eq(BoatAutopilot.EndAction.LAND_IN_PLACE, BoatAutopilot.endAction(false));
        ShuttleState st = new ShuttleState(null, null, List.of(new int[]{0, 0}), ShuttleState.Phase.SAIL, 0);
        check(Double.isNaN(st.goalTheta) && st.router == null, "a shuttle has no goal heading and no router");
    }

    private static void boatQuayRules() {
        BoatQuay.Quay q = new BoatQuay.Quay();
        long t = 1_000_000L;
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        eq(BoatQuay.QuayState.FREE, q.state);
        check(!q.canApproach(t, false), "nothing queued: no approach");
        check(q.canApproach(t, true), "free and queued: approach");
        q.approach();
        eq(BoatQuay.QuayState.APPROACH, q.state);
        check(!q.canApproach(t, true), "approach under way");
        check(!q.tryArrive(a, t), "an arrival waits while the quay is taken");
        q.docked(t);
        eq(BoatQuay.QuayState.DOCKED, q.state);
        check(!q.departureDue(t + TransitConstants.COUNTDOWN_SECONDS * 1000L - 1), "countdown running");
        check(q.departureDue(t + TransitConstants.COUNTDOWN_SECONDS * 1000L), "countdown over");
        q.departing();
        eq(BoatQuay.QuayState.DEPARTING, q.state);
        long cleared = t + 60_000L;
        q.cleared(cleared);
        eq(BoatQuay.QuayState.FREE, q.state);
        long gap = TransitConstants.QUAY_GAP_SECONDS * 1000L;
        check(!q.canApproach(cleared + gap - 1, true), "10 s gap after a departure");
        check(!q.tryArrive(a, cleared + gap - 1), "no arrival in the gap either");
        // The arrival registered in the gap waits; a second one waits behind it.
        eq(0, q.register(a));
        eq(1, q.register(b));
        eq(0, q.register(a));
        check(!q.canApproach(cleared + gap, true), "an arrival has priority over a new approach");
        check(!q.tryArrive(b, cleared + gap), "the second arrival waits for the first");
        check(q.tryArrive(a, cleared + gap), "first arrival takes the quay");
        eq(BoatQuay.QuayState.ARRIVING, q.state);
        check(q.tryArrive(a, cleared + gap), "idempotent for the holder");
        eq(0, q.order(b));
        eq(1, q.register(c));
        check(!q.tryArrive(b, cleared + gap), "quay held by the first arrival");
        long left = cleared + 120_000L;
        q.endArrival(a, left);
        eq(BoatQuay.QuayState.FREE, q.state);
        check(!q.tryArrive(b, left + gap - 1), "gap after the arrival left");
        check(!q.tryArrive(c, left + gap), "third waits for the second");
        check(q.tryArrive(b, left + gap), "second arrival takes the quay after the gap");
        q.endArrival(b, left + gap);
        q.endArrival(c, left + gap); // forgotten in the waiting line
        check(!q.arrivalPending(), "nothing pending");
        check(q.canApproach(left + 2 * gap, true), "approach again");
        q.approach();
        q.approachFailed(left + 2 * gap, 30_000L);
        eq(BoatQuay.QuayState.FREE, q.state);
        check(!q.canApproach(left + 2 * gap + 29_999L, true), "failed approach retried after 30 s");
        check(q.canApproach(left + 2 * gap + 30_000L, true), "failed approach retried");
    }

    private static void boatRouteLeg() {
        List<int[]> route = List.of(new int[]{0, 0}, new int[]{10, 0}, new int[]{10, 50});
        List<int[]> leg = BoatQuay.leg(route, 40);
        check(leg.size() == 3, "leg points " + leg.size());
        eq(0, leg.get(0)[0]);
        eq(10, leg.get(2)[0]);
        eq(30, leg.get(2)[1]);
        near(60.0, BoatQuay.pathLength(route));
        // Shorter route than the leg: the whole route.
        List<int[]> all = BoatQuay.leg(route, 500);
        check(all.size() == 3 && all.get(2)[1] == 50, "whole route");
        // Remaining path from a point toward a waypoint index.
        near(5.0 + 50.0, BoatQuay.remaining(route, 1, 5.5, 0.5));
        List<int[]> rev = BoatQuay.reversed(route);
        eq(10, rev.get(0)[0]);
        eq(50, rev.get(0)[1]);
        eq(0, rev.get(2)[0]);
    }

    private static void boatRoutesOldVersion() throws Exception {
        Path dir = Files.createTempDirectory("boatroutesv1");
        Path f = dir.resolve("boat-routes.json");
        Files.writeString(f, "{\"version\": 1, \"routes\": [{\"world\": \"w\", \"from\": \"A\", \"to\": \"B\", "
                + "\"state\": \"FOUND\", \"points\": [[1, 2], [3, 4]]}]}");
        BoatRoutes.Book b = new BoatRoutes.Book();
        b.load(f);
        check(b.get("w", "A", "B") == null, "version 1 discarded");
        b.ensure("w", "A", "B");
        check(b.get("w", "A", "B").state() == BoatRoutes.RouteState.COMPUTING, "pair computed again");
        Files.writeString(f, "{\"routes\": []}");
        BoatRoutes.Book c = new BoatRoutes.Book();
        c.load(f);
        check(c.get("w", "A", "B") == null && !Files.exists(dir.resolve("boat-routes.json.bad")), "missing version discarded, not .bad");
        c.resolve("w", "A", "B", BoatRoutes.RouteState.NONE, List.of());
        check(Files.readString(f).contains("\"version\": " + BoatRoutes.VERSION), "written as the current version");
        Files.writeString(f, "{\"version\": 2, \"routes\": [{\"world\": \"w\", \"from\": \"A\", \"to\": \"B\", "
                + "\"state\": \"FOUND\", \"points\": [[1, 2], [3, 4]]}]}");
        BoatRoutes.Book d = new BoatRoutes.Book();
        d.load(f);
        check(d.get("w", "A", "B") == null, "routes of version 2 (old clearance) are recomputed");
    }

    private static void boatAutopilotHeadingChange() {
        near(Math.toRadians(4), BoatAutopilot.headingChange(Math.toRadians(178), Math.toRadians(-178))); // across +-pi
        near(Math.toRadians(4), BoatAutopilot.headingChange(Math.toRadians(-178), Math.toRadians(178)));
        near(0.5, BoatAutopilot.headingChange(1.0, 1.5));
        near(0.0, BoatAutopilot.headingChange(0.3, 0.3 + 2 * Math.PI)); // a full turn is no change
    }

    private static void shortcutOpenWater() {
        List<int[]> path = new java.util.ArrayList<>();
        for (int x = 1; x <= 81; x += 2) path.add(new int[]{x, 1 + (x % 4 == 1 ? 0 : 2)}); // zigzag on open water
        ShipVoyage.Shortcut s = new ShipVoyage.Shortcut(path, box -> (x, z) -> WaterRoute.Cell.WATER);
        while (!s.step(10)) { }
        eq(2, s.result().size());
    }

    private static void shortcutKeepsUnknown() {
        List<int[]> path = List.of(new int[]{1, 1}, new int[]{41, 1}, new int[]{41, 41});
        ShipVoyage.Shortcut s = new ShipVoyage.Shortcut(path, box -> (x, z) -> x > 20 && z > 20 ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.WATER);
        while (!s.step(10)) { }
        eq(3, s.result().size()); // no straight line through unknown columns: the corner stays
    }

    private static void shortcutAroundLand() {
        List<int[]> path = List.of(new int[]{1, 1}, new int[]{1, 61}, new int[]{61, 61}, new int[]{61, 1}); // a U round an island
        ShipVoyage.Shortcut s = new ShipVoyage.Shortcut(path,
                box -> (x, z) -> x > 15 && x < 45 && z < 45 ? WaterRoute.Cell.BLOCKED : WaterRoute.Cell.WATER);
        while (!s.step(10)) { }
        eq(4, s.result().size()); // the island is never crossed
    }

    private static void shortcutDogLeg() {
        // A dog-leg round an island: the turning point must survive the final collapse (deltas share signs, not a straight line).
        List<int[]> path = List.of(new int[]{1, 1}, new int[]{11, 61}, new int[]{71, 71});
        ShipVoyage.Shortcut s = new ShipVoyage.Shortcut(path,
                box -> (x, z) -> x > 20 && x < 60 && z > 10 && z < 55 ? WaterRoute.Cell.BLOCKED : WaterRoute.Cell.WATER);
        while (!s.step(10)) { }
        eq(3, s.result().size());
        check(java.util.Arrays.equals(new int[]{11, 61}, s.result().get(1)), "the turning point is kept");
        eq(3, WaterLog.collapse(path).size());
        List<int[]> run = List.of(new int[]{1, 1}, new int[]{3, 3}, new int[]{5, 5}, new int[]{7, 5});
        eq(3, WaterLog.collapse(run).size()); // a straight run is merged, the turn kept
        eq(3, WaterLog.collapse(List.of(new int[]{1, 1}, new int[]{5, 1}, new int[]{3, 1})).size()); // going back is a turn
    }

    private static void boatDestinations() {
        WaterLog w = new WaterLog();
        w.record(1, 1); w.record(201, 1);
        w.addStop(10, 62, 0, Rotation.None, 11, 1);     // too close to the boat at (0, 0)
        w.addStop(100, 62, 0, Rotation.None, 101, 1);
        TransitNetwork.Station port = new TransitNetwork.Station("Port", "w", new Vector3i(200, 62, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        TransitNetwork.Station gone = new TransitNetwork.Station("Gone", "w", new Vector3i(150, 62, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND);
        w.addReached(200, 62, 0);
        w.addReached(150, 62, 0);
        List<VoyagePlanner.Destination> d = ShipVoyage.destinations(w, List.of(port), 0, 0, s -> new double[]{s.origin.x, s.origin.z, s.origin.x, s.origin.z});
        eq(2, d.size());
        eq(VoyagePlanner.Kind.STOP, d.get(0).kind());
        eq(VoyagePlanner.Kind.STATION, d.get(1).kind());
        eq("Port", d.get(1).name());
        eq(1, w.reached().size()); // the removed station is dropped
    }

    private static void boatGoalOfStop() {
        WaterLog w = new WaterLog();
        w.addStop(100, 62, 0, Rotation.Ninety, 101, 5);
        VoyagePlanner.Destination d = new VoyagePlanner.Destination("1", VoyagePlanner.Kind.STOP, 101, 5, 62);
        double[] g = ShipVoyage.goalOf(w, d, null, null);
        eq(101.0, g[0]);
        eq(5.0, g[1]);
        eq(Rotation.Ninety.getRadians(), g[2]);
    }

    private static void shipLandingRetryNeverClimbs() {
        // Pure rule used by onLandingRefused: a waterborne ship keeps its height.
        check(!AirshipAutopilot.climbsOnRetry(true), "a boat never climbs to retry");
        check(AirshipAutopilot.climbsOnRetry(false), "an airship climbs to retry");
    }

    private static void boatBackoffPoints() {
        List<int[]> wps = List.of(new int[]{0, 0}, new int[]{10, 0}, new int[]{20, 0}, new int[]{30, 0});
        // Sailing to index 2 (passed 0 and 1): nearest previous first, then the departure point.
        List<int[]> b = ShipVoyage.backoffPoints(wps, 2, new int[]{-1, 0});
        eq(3, b.size());
        eq(10, b.get(0)[0]);
        eq(0, b.get(1)[0]);
        eq(-1, b.get(2)[0]);
        // The departure point equal to the first waypoint is not repeated.
        eq(2, ShipVoyage.backoffPoints(wps, 2, new int[]{0, 0}).size());
        // Nothing sailed yet: only the departure point.
        eq(1, ShipVoyage.backoffPoints(wps, 0, new int[]{-1, 0}).size());
        eq(0, ShipVoyage.backoffPoints(wps, 0, null).size());
    }

    private static void airshipCaptainChest() throws Exception {
        // The gems handed back are exactly the gems of the crate recipe, one of each.
        var recipe = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Item/Items/Containers/Vehicles/Airship_Cloudwork_Crate.json")));
        List<String> gems = new java.util.ArrayList<>();
        collectGems(recipe, gems);
        eq(4, AirshipCaptainChest.GEM_IDS.size());
        check(gems.containsAll(AirshipCaptainChest.GEM_IDS) && gems.size() == AirshipCaptainChest.GEM_IDS.size(), "gems = recipe gems");
        // The chest is where the prefab holds the captain's chest, and it is empty there (the prefab never carries the gems).
        var prefab = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs/The_Cloudwork.prefab.json")));
        boolean found = false;
        for (org.bson.BsonValue v : prefab.getArray("blocks")) {
            org.bson.BsonDocument b = v.asDocument();
            if (b.getNumber("x").intValue() == AirshipCaptainChest.CHEST_LOCAL.x && b.getNumber("y").intValue() == AirshipCaptainChest.CHEST_LOCAL.y
                    && b.getNumber("z").intValue() == AirshipCaptainChest.CHEST_LOCAL.z) {
                found = true;
                check(b.getString("name").getValue().contains(AirshipCaptainChest.CHEST_BLOCK), "chest block name");
                eq(0, b.getDocument("components").getDocument("Components").getDocument("ItemContainerBlock")
                        .getDocument("ItemContainer").getDocument("Items").size());
                eq((int) AirshipCaptainChest.CHEST_CAPACITY, b.getDocument("components").getDocument("Components")
                        .getDocument("ItemContainerBlock").getDocument("ItemContainer").getNumber("Capacity").intValue());
            }
        }
        check(found, "captain chest in the prefab");
        // Same transform as the paste.
        Vector3i origin = new Vector3i(100, 64, 200);
        eq(new Vector3i(98, 73, 203), AirshipCaptainChest.worldPosition(origin, Rotation.None));
        eq(Rotation.Ninety.rotateYaw(new Vector3i(-2, 9, 3), new Vector3i()).add(origin), AirshipCaptainChest.worldPosition(origin, Rotation.Ninety));
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
        g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 1, 2, Rotation.None, false));
        g.add("other", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 3, 4, Rotation.None, false));
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

    private static void collectGems(org.bson.BsonValue v, List<String> out) {
        if (v.isDocument()) {
            org.bson.BsonDocument d = v.asDocument();
            if (d.containsKey("ItemId") && d.get("ItemId").isString() && d.getString("ItemId").getValue().startsWith("Rock_Gem_")) {
                out.add(d.getString("ItemId").getValue());
            }
            for (org.bson.BsonValue c : d.values()) {
                collectGems(c, out);
            }
        } else if (v.isArray()) {
            for (org.bson.BsonValue c : v.asArray()) {
                collectGems(c, out);
            }
        }
    }

    static void generatedRegistryRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("generated-stations");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        GeneratedStations.Entry a = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 100, 200, Rotation.None, false));
        GeneratedStations.Entry b = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, -50, 70, Rotation.Ninety, true));
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
        eq(Deployables.STATION_KIND, es.get(0).kind);
        eq(100, es.get(0).x);
        eq(200, es.get(0).z);
        eq(Rotation.None, es.get(0).rotation);
        check(!es.get(0).partner, "first not partner");
        eq(GeneratedStations.State.BUILT, es.get(0).state);
        eq(Deployables.BOAT_STATION_KIND, es.get(1).kind);
        eq(Rotation.Ninety, es.get(1).rotation);
        check(es.get(1).partner, "second partner");
        eq(GeneratedStations.State.PLANNED, es.get(1).state);
        check(!g.markPortal("w", 12, 22), "portal remembered");
        check(!g.markCell("w", 3, -4), "cell remembered");
        eq(1, g.positions("w", Deployables.STATION_KIND).size());
        g.setState(es.get(0), GeneratedStations.State.REMOVED);
        eq(0, g.positions("w", Deployables.STATION_KIND).size());
        eq(0, g.entries("other").size());
    }

    static void generatedRegistrySites() throws Exception {
        Path dir = Files.createTempDirectory("generated-sites");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        WorldStationPlanner.Site s1 = new WorldStationPlanner.Site(150, 210, Rotation.Ninety);
        WorldStationPlanner.Site s2 = new WorldStationPlanner.Site(-30, 400, Rotation.OneEighty);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 100, 200,
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
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 0, 0,
                Rotation.None, false, List.of(s1, s2)));
        check(GenerationListener.moveToNextSite(g, e, List.of(new int[] {6000, 0})), "site 2 is far enough");
        eq(2, e.active);
        eq(-5000, e.x);
        GeneratedStations.Entry f = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 0, 0,
                Rotation.None, false, List.of(s1, s2)));
        check(!GenerationListener.moveToNextSite(g, f, List.of(new int[] {6000, 0}, new int[] {-4000, 0})), "both fallbacks too close");
        GeneratedStations.Entry p = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 0, 0,
                Rotation.None, true, List.of(s1, s2)));
        check(GenerationListener.moveToNextSite(g, p, List.of(new int[] {6000, 0})), "a partner only keeps its reuse distance");
        eq(1, p.active);
    }

    static void generatedBuiltSite() throws Exception {
        Path dir = Files.createTempDirectory("generated-built-site");
        GeneratedStations g = GeneratedStations.get();
        g.load(dir);
        WorldStationPlanner.Site fallback = new WorldStationPlanner.Site(5000, 0, Rotation.None);
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 0, 0,
                Rotation.None, false, List.of(fallback)));
        g.setBuiltSite(e, 40, 8, Rotation.Ninety);
        g.setState(e, GeneratedStations.State.BUILT);
        eq(e, g.at("w", 41, 7));
        check(g.at("w", 0, 0) == null, "planned site no longer matches");
        eq(1, g.positions("w", Deployables.BOAT_STATION_KIND).size());
        eq(40, g.positions("w", Deployables.BOAT_STATION_KIND).get(0)[0]);
        eq(8, g.positions("w", Deployables.BOAT_STATION_KIND).get(0)[1]);
        eq(new WorldStationPlanner.Site(40, 8, Rotation.Ninety), g.activeSite(e));
        eq(chunkSet(GenerationListener.footprintChunks(Deployables.BOAT_STATION_KIND, 40, 8, Rotation.Ninety)),
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
                + "{\"world\": \"w\", \"kind\": \"" + Deployables.BOAT_STATION_KIND + "\", \"x\": 1, \"z\": 2, \"rotation\": \"None\", "
                + sites + ", \"active\": 7, \"partner\": false, \"state\": \"PLANNED\"},"
                + "{\"world\": \"w\", \"kind\": \"" + Deployables.BOAT_STATION_KIND + "\", \"x\": 1, \"z\": 2, \"rotation\": \"None\", "
                + sites + ", \"active\": -3, \"partner\": false, \"state\": \"PLANNED\"},"
                + "{\"world\": \"w\", \"kind\": \"" + Deployables.BOAT_STATION_KIND + "\", \"x\": 1, \"z\": 2, \"rotation\": \"None\", "
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
        GeneratedStations.Entry e = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 0, 0,
                Rotation.None, false, List.of(fallback)));
        eq(chunkSet(GenerationListener.footprintChunks(Deployables.BOAT_STATION_KIND, 0, 0, Rotation.None)),
                chunkSet(GenerationListener.footprintChunks(e)));
        check(g.advance(e), "advanced");
        Set<String> moved = chunkSet(GenerationListener.footprintChunks(e));
        eq(chunkSet(GenerationListener.footprintChunks(Deployables.BOAT_STATION_KIND, 5000, -3000, Rotation.OneEighty)), moved);
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
        GeneratedStations.Entry boat = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 0, 0,
                Rotation.None, false, List.of(s1, s2)));
        // another main pier near the first fallback
        g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 5500, 0, Rotation.None, false));
        check(GenerationListener.moveOffOldTerrain(g, boat, "w"), "boat moves to a site far from the other pier");
        eq(2, boat.active);
        eq(-5000, boat.x);
        eq(GeneratedStations.State.PLANNED, boat.state);
        check(!GenerationListener.moveOffOldTerrain(g, boat, "w"), "sites used up");
        eq(2, boat.active);
        GeneratedStations.Entry air = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 0, 0, Rotation.None, false,
                List.of(s1)));
        check(!GenerationListener.moveOffOldTerrain(g, air, "w"), "a balloon station never moves");
        eq(0, air.active);
    }

    static void generatedRegistryOldFormat() throws Exception {
        Path dir = Files.createTempDirectory("generated-old-format");
        String json = "{\"version\": 1, \"entries\": [{\"world\": \"w\", \"kind\": \"" + Deployables.STATION_KIND
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
        GeneratedStations.Entry a = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 0, 0, Rotation.None, false));
        GeneratedStations.Entry far = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 5000, 5000, Rotation.None, false));
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
        g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 0, 0, Rotation.None, false));
        // reloaded from disk: no longer an entry of this session
        g.load(dir);
        GeneratedStations.Entry old = g.at("w", 0, 0);
        check(old != null && !old.thisSession, "entry loaded from disk");
        GeneratedStations.Entry fresh = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 20, 20, Rotation.None, false));
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

    static void generatedCellOnce() throws Exception {
        GeneratedStations g = GeneratedStations.get();
        g.load(Files.createTempDirectory("generated-cell"));
        check(g.markCell("w", 1, 2), "first cell");
        check(!g.markCell("w", 1, 2), "same cell");
        check(g.markCell("w", 2, 1), "other cell");
        check(g.markCell("other", 1, 2), "other world");
    }

    static void cellsAroundPlayer() {
        List<int[]> cells = GenerationListener.cellsAround(-1, 4095);
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
            TransitSettings.load(dir);
            TransitSettings.setWorldgenEnabled(false);
            GenerationListener.planAhead();
        } finally {
            TransitSettings.setWorldgenEnabled(true);
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
        g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 1, 2, Rotation.None, false));
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
        GeneratedStations.Entry built = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 10, 0, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, false));
        GeneratedStations.Entry far = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.BOAT_STATION_KIND, 500, 0, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, false));
        GeneratedStations.Entry abandoned = g.add("w", new WorldStationPlanner.PlannedSite(Deployables.STATION_KIND, 100, 0, com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, false));
        g.setState(built, GeneratedStations.State.BUILT);
        g.setState(abandoned, GeneratedStations.State.ABANDONED);
        eq(abandoned, StationCommand.nearestUnbuilt(g.entries("w"), 0, 0));
        eq(far, StationCommand.nearestUnbuilt(g.entries("w"), 450, 0));
        eq(null, StationCommand.nearestUnbuilt(g.entries("other"), 0, 0));
    }

    private static void generatorAvailability() {
        eq(GeneratorProbe.Availability.WAIT, GeneratorProbe.availability(false, null));
        eq(GeneratorProbe.Availability.OFF, GeneratorProbe.availability(true, null));
        eq(GeneratorProbe.Availability.OFF, GeneratorProbe.availability(false, new Object()));
    }

    static void footprintChunksCover() {
        GeneratedStations.Entry rotated = new GeneratedStations.Entry("w", Deployables.STATION_KIND, 0, 0, Rotation.Ninety, false,
                GeneratedStations.State.PLANNED);
        eq(java.util.Set.of("-1,-1", "0,-1"), chunkSet(GenerationListener.footprintChunks(rotated)));
        GeneratedStations.Entry plain = new GeneratedStations.Entry("w", Deployables.STATION_KIND, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        eq(java.util.Set.of("0,-1", "0,0"), chunkSet(GenerationListener.footprintChunks(plain)));
        GeneratedStations.Entry boat = new GeneratedStations.Entry("w", Deployables.BOAT_STATION_KIND, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        eq(java.util.Set.of("-1,-1", "-1,0", "0,-1", "0,0"), chunkSet(GenerationListener.footprintChunks(boat)));
        GeneratedStations.Entry far = new GeneratedStations.Entry("w", Deployables.STATION_KIND, -4000, 3000, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        check(chunkSet(GenerationListener.footprintChunks(far)).contains("-125,93"), "negative x chunk of the origin");
    }

    static void decideRules() {
        List<long[]> chunks = List.of(new long[]{0, 0}, new long[]{1, 0});
        java.util.function.Predicate<long[]> all = c -> true;
        java.util.function.Predicate<long[]> none = c -> false;
        java.util.function.Predicate<long[]> firstOnly = c -> c[0] == 0;
        eq(GenerationListener.Decision.BUILD, GenerationListener.decide(chunks, all, none));
        eq(GenerationListener.Decision.ABANDON, GenerationListener.decide(chunks, firstOnly, c -> c[0] == 1));
        eq(GenerationListener.Decision.WAIT, GenerationListener.decide(chunks, firstOnly, none));
        eq(GenerationListener.Decision.ABANDON, GenerationListener.decide(chunks, all, c -> c[0] == 0));
    }

    static void newChunksOldLoadAbandons() {
        GeneratedStations.Entry e = new GeneratedStations.Entry("w", Deployables.STATION_KIND, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        NewChunks news = new NewChunks();
        news.mark(0, -1);
        List<long[]> chunks = GenerationListener.footprintChunks(e);
        // chunk (0, 0) is loaded and was never marked new: old terrain.
        java.util.function.Predicate<long[]> loaded = c -> c[0] == 0 && c[1] == 0;
        GenerationListener.Decision d = GenerationListener.decide(chunks, c -> news.isNew((int) c[0], (int) c[1]),
                c -> loaded.test(c) && !news.isNew((int) c[0], (int) c[1]));
        eq(GenerationListener.Decision.ABANDON, d);
    }

    static void modRecipesMatchAssets() throws Exception {
        java.util.TreeSet<String> expected = new java.util.TreeSet<>();
        try (var files = Files.walk(Path.of("Server/Item/Items"))) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".json"))::iterator) {
                if (org.bson.BsonDocument.parse(Files.readString(f)).containsKey("Recipe")) {
                    String name = f.getFileName().toString();
                    expected.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        }
        try (var files = Files.list(Path.of("Server/Item/Recipes/OrbisHorizon"))) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".json"))::iterator) {
                var out = org.bson.BsonDocument.parse(Files.readString(f)).getArray("Output").get(0).asDocument();
                expected.add(out.getString("ItemId").getValue());
            }
        }
        eq(expected, new java.util.TreeSet<>(ModRecipes.ITEMS));
        eq(ModRecipes.ITEMS.size(), new java.util.HashSet<>(ModRecipes.ITEMS).size());
        eq(true, ModRecipes.isModItem("Hotair_Balloon_Crate"));
        eq(false, ModRecipes.isModItem("Wood_Hardwood_Planks"));
    }

    static void worldgenSettingDefault() throws Exception {
        Path dir = Files.createTempDirectory("worldgen-settings");
        try {
            TransitSettings.load(dir);
            eq(true, TransitSettings.worldgenEnabled());
            TransitSettings.setWorldgenEnabled(false);
            TransitSettings.load(dir);
            eq(false, TransitSettings.worldgenEnabled());
            TransitSettings.setWorldgenEnabled(true);
            TransitSettings.load(dir);
            eq(true, TransitSettings.worldgenEnabled());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": true}");
            TransitSettings.load(dir);
            eq(true, TransitSettings.worldgenEnabled());
        } finally {
            deleteAll(dir);
        }
    }

    static void footprintContainsShapes() throws Exception {
        StructureShape station = prefabShape("Balloon_Landing_zone.prefab.json");
        StructureShape balloon = prefabShape("Balloon_Public_Transport.prefab.json");
        StructureShape boatStation = prefabShape("Boat_Station.prefab.json");
        StructureShape shuttle = prefabShape("Steamboat_Public.prefab.json");
        // origins near a chunk corner, on both sides of zero
        int[][] origins = {{31, 31}, {0, 0}, {-1, -1}, {-33, 62}};
        for (Rotation r : new Rotation[] {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            for (int[] o : origins) {
                Vector3i origin = new Vector3i(o[0], 70, o[1]);
                GeneratedStations.Entry air = new GeneratedStations.Entry("w", Deployables.STATION_KIND, o[0], o[1], r, false,
                        GeneratedStations.State.PLANNED);
                Set<String> airChunks = chunkSet(GenerationListener.footprintChunks(air));
                for (StructureShape.Cell c : station.cells()) {
                    check(airChunks.contains(chunkOf(c.rotated(r).add(origin))), "station cell in footprint " + r + " " + c);
                }
                Vector3i dock = DeployStationInteraction.dockOrigin(origin, r);
                for (StructureShape.Cell c : balloon.cells()) {
                    check(airChunks.contains(chunkOf(c.rotated(r).add(dock))), "docked balloon cell in footprint " + r + " " + c);
                }
                GeneratedStations.Entry boat = new GeneratedStations.Entry("w", Deployables.BOAT_STATION_KIND, o[0], o[1], r, false,
                        GeneratedStations.State.PLANNED);
                Set<String> boatChunks = chunkSet(GenerationListener.footprintChunks(boat));
                for (StructureShape.Cell c : boatStation.cells()) {
                    check(boatChunks.contains(chunkOf(c.rotated(r).add(origin))), "boat station cell in footprint " + r + " " + c);
                }
                int[] b = Deployables.BOAT_STATION_BERTH_ORIGIN;
                Vector3i berth = r.rotateYaw(new Vector3i(b[0], b[1], b[2]), new Vector3i()).add(origin);
                Rotation boatRot = r.add(Deployables.BOAT_STATION_BERTH_ROTATION);
                for (StructureShape.Cell c : shuttle.cells()) {
                    check(boatChunks.contains(chunkOf(c.rotated(boatRot).add(berth))), "shuttle cell in footprint " + r + " " + c);
                }
            }
        }
    }

    private static String chunkOf(Vector3i p) {
        return Math.floorDiv(p.x, 32) + "," + Math.floorDiv(p.z, 32);
    }

    static void reloadedNewChunkRetriggers() {
        GeneratedStations.Entry a = new GeneratedStations.Entry("w", Deployables.STATION_KIND, 0, 0, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        GeneratedStations.Entry far = new GeneratedStations.Entry("w", Deployables.STATION_KIND, 5000, 5000, Rotation.None, false,
                GeneratedStations.State.PLANNED);
        List<GeneratedStations.Entry> pending = List.of(a, far);
        eq(List.of(a), GenerationListener.touching(pending, 0, 0));
        eq(List.of(far), GenerationListener.touching(pending, 5000 / 32, 5000 / 32));
        eq(0, GenerationListener.touching(pending, 40, 40).size());
    }
}
