package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.keepkoolz.orbishorizon.api.ConfigRow;
import com.keepkoolz.orbishorizon.api.Extension;
import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;
import com.keepkoolz.orbishorizon.api.StationInfo;
import com.keepkoolz.orbishorizon.api.Refusal;
import com.keepkoolz.orbishorizon.api.StationKinds;
import com.keepkoolz.orbishorizon.api.Terrain;
import com.keepkoolz.orbishorizon.api.TerrainProbe;
import com.keepkoolz.orbishorizon.api.WorldAccess;
import com.keepkoolz.orbishorizon.TestSupport.ChartGrid;
import com.keepkoolz.orbishorizon.TestSupport.GridProbe;
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
import java.util.UUID;

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

    private record RouteView(Set<Long> closedCells, List<SeaChartBook.Segment> learned) implements ChartRoute.View {
        @Override
        public boolean closed(int cx, int cz) {
            return closedCells.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL));
        }

        @Override
        public List<SeaChartBook.Segment> segments() {
            return learned;
        }
    }

    private static RouteView routeView(List<SeaChartBook.Segment> learned, int[]... closed) {
        Set<Long> set = new java.util.HashSet<>();
        for (int[] c : closed) {
            set.add(((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL));
        }
        return new RouteView(set, learned);
    }

    private static SeaChart routeChart(ChartGrid g, int cols, int rows) {
        SeaChart c = new SeaChart(1L);
        for (int cz = 0; cz < rows; cz++) {
            for (int cx = 0; cx < cols; cx++) {
                c.fill(g, cx * 16 + 8, cz * 16 + 8, 40);
            }
        }
        return c;
    }

    private static ChartRoute.Status runRoute(ChartRoute r) {
        ChartRoute.Status s = ChartRoute.Status.RUNNING;
        for (int i = 0; i < 1000 && s == ChartRoute.Status.RUNNING; i++) {
            s = r.step(100);
        }
        return s;
    }

    /** Route between two cells, centres in blocks. */
    private static ChartRoute route(SeaChart c, RouteView v, int fx, int fz, int tx, int tz) {
        return new ChartRoute(c, v, fx * 16 + 8, fz * 16 + 8, tx * 16 + 8, tz * 16 + 8);
    }

    static void corridorAroundIsland() {
        String[] rows = {"#########", "#~~~~~~~#", "#~~###~~#", "#~~###~~#", "#~~~~~~~#", "#########"};
        SeaChart c = routeChart(new ChartGrid(rows), 9, 6);
        ChartRoute r = route(c, routeView(List.of()), 1, 2, 7, 2);
        eq(ChartRoute.Status.FOUND, runRoute(r));
        for (int[] p : r.points()) {
            check(c.ocean(SeaChart.cellOf(p[0]), SeaChart.cellOf(p[1])) != 0, "point sur la terre " + p[0] + "," + p[1]);
        }
    }

    static void corridorPrefersDeep() {
        // the shallow top passage is shorter, both passages touch land all along: only the depth factor decides
        String[] rows = {"#########", "#~33333~#", "#~#####~#", "#~#####~#", "#~~~~~~~#", "#########"};
        SeaChart c = routeChart(new ChartGrid(rows), 9, 6);
        ChartRoute r = route(c, routeView(List.of()), 1, 2, 7, 2);
        eq(ChartRoute.Status.FOUND, runRoute(r));
        boolean deepRow = false;
        for (int[] p : r.points()) {
            int cz = SeaChart.cellOf(p[1]);
            check(cz != 1, "le passage peu profond est pris");
            deepRow |= cz == 4;
        }
        check(deepRow, "le passage profond est pris");
    }

    static void corridorAvoidsShore() {
        String[] rows = {"###########", "#~~~~~~~~~#", "#~#######~#", "#~~~~~~~~~#", "#~~~~~~~~~#", "#~~~~~~~~~#", "###########"};
        SeaChart c = routeChart(new ChartGrid(rows), 11, 7);
        ChartRoute r = route(c, routeView(List.of()), 1, 2, 9, 2);
        eq(ChartRoute.Status.FOUND, runRoute(r));
        boolean offshore = false;
        for (int[] p : r.points()) {
            int cz = SeaChart.cellOf(p[1]);
            check(cz != 1, "le passage collé à la terre est pris");
            offshore |= cz == 4;
        }
        check(offshore, "le passage au large est pris");
    }

    private static final String[] TWO_SEAS = {"#######", "#~~#~~#", "#######"};

    private static List<SeaChartBook.Segment> acrossLand() {
        return List.of(new SeaChartBook.Segment(2 * 16 + 8, 24, 4 * 16 + 8, 24));
    }

    static void corridorLearnedThroughLand() {
        SeaChart c = routeChart(new ChartGrid(TWO_SEAS), 7, 3);
        eq(ChartRoute.Status.NONE, runRoute(route(c, routeView(List.of()), 1, 1, 5, 1)));
        ChartRoute r = route(c, routeView(acrossLand()), 1, 1, 5, 1);
        eq(ChartRoute.Status.FOUND, runRoute(r));
        List<int[]> pts = r.points();
        eq(24, pts.get(0)[0]);
        eq(5 * 16 + 8, pts.get(pts.size() - 1)[0]);
    }

    static void corridorLearnedSegmentPointsSpaced() {
        String[] rows = {"#".repeat(15), "#~~" + "#".repeat(10) + "~~#", "#".repeat(15)};
        SeaChart c = routeChart(new ChartGrid(rows), 15, 3);
        List<SeaChartBook.Segment> seg = List.of(new SeaChartBook.Segment(2 * 16 + 8, 24, 13 * 16 + 8, 24));
        ChartRoute r = route(c, routeView(seg), 1, 1, 14, 1);
        eq(ChartRoute.Status.FOUND, runRoute(r));
        List<int[]> pts = r.points();
        eq(24, pts.get(0)[0]);
        eq(14 * 16 + 8, pts.get(pts.size() - 1)[0]);
        for (int i = 1; i < pts.size(); i++) {
            double d = Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
            check(d <= 48.0 + 1e-9, "gap " + d + " at " + i);
        }
    }

    static void corridorClosedCell() {
        String[] rows = {"#######", "#~~~~~#", "#######"};
        SeaChart c = routeChart(new ChartGrid(rows), 7, 3);
        eq(ChartRoute.Status.FOUND, runRoute(route(c, routeView(List.of()), 1, 1, 5, 1)));
        eq(ChartRoute.Status.NONE, runRoute(route(c, routeView(List.of(), new int[] {3, 1}), 1, 1, 5, 1)));
    }

    static void corridorClosedKillsSegment() {
        SeaChart c = routeChart(new ChartGrid(TWO_SEAS), 7, 3);
        eq(ChartRoute.Status.NONE, runRoute(route(c, routeView(acrossLand(), new int[] {3, 1}), 1, 1, 5, 1)));
    }

    static void corridorNoDiagonalCut() {
        String[] rows = {"####", "#~##", "##~#", "####"};
        SeaChart c = routeChart(new ChartGrid(rows), 4, 4);
        eq(ChartRoute.Status.NONE, runRoute(route(c, routeView(List.of()), 1, 1, 2, 2)));
    }

    private static String straight(int cells) {
        return "#" + "~".repeat(cells) + "#";
    }

    static void corridorPoints48() {
        String[] rows = {straight(20).replace('~', '#'), straight(20), straight(20).replace('~', '#')};
        SeaChart c = routeChart(new ChartGrid(rows), 22, 3);
        ChartRoute r = route(c, routeView(List.of()), 1, 1, 20, 1);
        eq(ChartRoute.Status.FOUND, runRoute(r));
        List<int[]> pts = r.points();
        eq(1 * 16 + 8, pts.get(0)[0]);
        eq(20 * 16 + 8, pts.get(pts.size() - 1)[0]);
        for (int i = 1; i < pts.size() - 1; i++) {
            eq(48, pts.get(i)[0] - pts.get(i - 1)[0]);
        }
        eq(8, pts.size()); // 20 cells: points at 0, 3 .. 18 and the last cell
    }

    static void corridorBudget() {
        String[] rows = {straight(20).replace('~', '#'), straight(20), straight(20).replace('~', '#')};
        SeaChart c = routeChart(new ChartGrid(rows), 22, 3);
        ChartRoute r = route(c, routeView(List.of()), 1, 1, 20, 1);
        eq(ChartRoute.Status.RUNNING, r.step(10));
        ChartRoute.Status s = ChartRoute.Status.RUNNING;
        int calls = 0;
        while (s == ChartRoute.Status.RUNNING && calls < 20) {
            s = r.step(10);
            calls++;
        }
        eq(ChartRoute.Status.FOUND, s);
        check(calls >= 1, "plusieurs appels");
    }

    static void chartTwoSeas() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~#~~", "~~#~~");
        int a = c.fill(g, 8, 8), b = c.fill(g, 4 * 16 + 8, 8);
        check(a != 0 && b != 0 && a != b, "deux mers séparées");
    }

    static void chartBerthOnRock() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~~~~", "~~#~~", "~~~~~");
        int sea = c.fill(g, 8, 8);
        check(sea != 0, "mer");
        eq(sea, c.fill(g, 2 * 16 + 8, 16 + 8));
        eq(sea, c.oceanNear(2, 1));
        SeaChart fresh = new SeaChart(1L);
        int fromRock = fresh.fill(g, 2 * 16 + 8, 16 + 8);
        check(fromRock != 0, "rocher entouré de mer");
        eq(fromRock, fresh.oceanNear(2, 1));
        eq(fromRock, fresh.ocean(0, 0));
    }

    static void chartBerthFarFromWater() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~###");
        eq(0, c.fill(g, 3 * 16 + 8, 8));
        eq(0, c.oceanNear(3, 0));
    }

    static void chartStrait() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~#~~", "~~~~~", "~~#~~");
        check(c.fill(g, 8, 8) == c.fill(g, 4 * 16 + 8, 8), "détroit d'une case");
    }

    static void chartLakeNotSea() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~ll");
        int sea = c.fill(g, 8, 8), lake = c.fill(g, 3 * 16 + 8, 8);
        check(sea != 0 && lake != 0 && sea != lake, "lac jamais fusionné avec la mer");
        eq(SeaChart.Water.FRESH, c.water(3, 0));
    }

    static void chartRiverFresh() {
        ChartGrid g = new ChartGrid("lrrl");
        SeaChart c = new SeaChart(1L);
        check(c.fill(g, 8, 8) == c.fill(g, 3 * 16 + 8, 8), "lacs reliés par une rivière");
    }

    static void chartRockAndShallow() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~#~", "~,~");
        c.fill(g, 8, 8);
        eq(0, c.ocean(1, 0));
        eq(0, c.ocean(1, 1)); // depth 1 is under MIN_DEPTH
        check(c.ocean(0, 0) != c.ocean(2, 0), "ni le rocher ni le haut-fond ne relient");
    }

    static void chartMergeOrder() {
        ChartGrid g = new ChartGrid("~#~#~", "~~~~~");
        for (int[] order : new int[][] {{0, 2, 4}, {4, 2, 0}, {2, 0, 4}}) {
            SeaChart c = new SeaChart(1L);
            int last = 0;
            for (int i : order) last = c.fill(g, i * 16 + 8, 8);
            eq(c.ocean(0, 0), c.ocean(4, 0));
            eq(c.ocean(0, 0), last);
        }
    }

    static void chartFillSkipsLabelled() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~~~~~~~");
        c.fill(g, 8, 8);
        int reads = c.fillReads();
        c.fill(g, 5 * 16 + 8, 8);
        eq(reads, c.fillReads());
    }

    static void chartLakeAfterSea() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~lll", "~~lll");
        c.fill(g, 8, 8);
        int lake = c.fill(g, 4 * 16 + 8, 8);
        check(lake != 0, "lac rempli après la mer");
        for (int cz = 0; cz < 2; cz++) {
            for (int cx = 2; cx < 5; cx++) {
                eq(lake, c.ocean(cx, cz));
            }
        }
    }

    static void chartMergeShortRadius() {
        SeaChart c = new SeaChart(1L);
        ChartGrid g = new ChartGrid("~~~~~~~");
        int a = c.fill(g, 8, 8, 3);
        eq(0, c.ocean(6, 0));
        int b = c.fill(g, 6 * 16 + 8, 8, 3);
        check(a != 0, "premier remplissage");
        eq(a, b);
        eq(a, c.ocean(0, 0));
        eq(a, c.ocean(6, 0));
    }

    static void chartFileRoundTrip() throws Exception {
        ChartGrid g = new ChartGrid("~~#~~lll#~~", "~~#~~lll#~~", "~~#~~~~~#~~");
        SeaChart c = new SeaChart(7L);
        c.fill(g, 8, 8);
        c.fill(g, 4 * 16 + 8, 8);
        c.fill(g, 5 * 16 + 8, 8);
        Path f = Files.createTempFile("chart", ".bin");
        try {
            SeaChartFile.write(f, c);
            SeaChart r = SeaChartFile.read(f, 7L);
            check(r != null, "carte relue");
            for (int cz = -2; cz < 6; cz++) {
                for (int cx = -2; cx < 12; cx++) {
                    eq(c.ocean(cx, cz), r.ocean(cx, cz));
                    eq(c.depth(cx, cz), r.depth(cx, cz));
                    eq(c.water(cx, cz), r.water(cx, cz));
                }
            }
            int before = 0;
            for (int cz = -2; cz < 6; cz++) {
                for (int cx = -2; cx < 12; cx++) {
                    before = Math.max(before, c.ocean(cx, cz));
                }
            }
            int fresh = r.fill(g, 9 * 16 + 8, 8);
            check(fresh > before, "étiquette neuve après rechargement : " + fresh + " contre " + before);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static void chartFileOtherSeed() throws Exception {
        SeaChart c = new SeaChart(7L);
        c.fill(new ChartGrid("~~~"), 8, 8);
        Path f = Files.createTempFile("chart", ".bin");
        try {
            SeaChartFile.write(f, c);
            check(SeaChartFile.read(f, 8L) == null, "autre graine");
            check(SeaChartFile.read(f.resolveSibling("absent.bin"), 7L) == null, "fichier absent");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static void chartFileCorrupt() throws Exception {
        SeaChart c = new SeaChart(7L);
        c.fill(new ChartGrid("~~~"), 8, 8);
        Path f = Files.createTempFile("chart", ".bin");
        try {
            byte[] junk = new byte[10];
            new java.util.Random(3).nextBytes(junk);
            Files.write(f, junk);
            check(SeaChartFile.read(f, 7L) == null, "octets au hasard");
            SeaChartFile.write(f, c);
            byte[] good = Files.readAllBytes(f);
            Files.write(f, Arrays.copyOf(good, good.length / 2));
            check(SeaChartFile.read(f, 7L) == null, "fichier tronqué");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static void bookSegmentsEvery50() {
        SeaChartBook b = new SeaChartBook();
        SeaChartBook.Trip t = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 160; x += 4) t.note(x, 0);
        eq(3, t.pending());
        eq(0, b.segments("w").size());
        b.commit(t);
        eq(3, b.segments("w").size());
    }

    static void bookMergeNearKnownPoint() {
        SeaChartBook b = new SeaChartBook();
        SeaChartBook.Trip t = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 100; x += 2) t.note(x, 0);
        b.commit(t);
        SeaChartBook.Trip u = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 100; x += 2) u.note(x, 10);
        b.commit(u);
        eq(2, b.segments("w").size());
    }

    static void bookForgetAndCap() {
        SeaChartBook b = new SeaChartBook();
        SeaChartBook.Trip t = b.startTrip("w", "A", "B", 2);
        for (int x = 0; x <= 500; x += 5) t.note(x, 0);
        eq(2, t.pending());
        b.forget(t);
        eq(0, t.pending());
        b.commit(t);
        eq(0, b.segments("w").size());
        eq(30, SeaChartBook.maxSegments(250));
    }

    static void bookClosure() {
        SeaChartBook b = new SeaChartBook();
        SeaChartBook.Trip t = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 100; x += 2) t.note(x, 0);
        b.commit(t);
        b.close("w", 1, 0, 1000L); // cell 1 holds x 16..31, only the segment 0 to 50 crosses it
        check(b.closed("w", 1, 0, 1000L), "fermée");
        check(!b.closed("w", 1, 0, 1000L + SeaChartBook.CLOSE_MS), "expirée");
        eq(1, b.segments("w").size());
        b.close("w", 9, 9, 0L);
        b.crossed("w", 9, 9);
        check(!b.closed("w", 9, 9, 0L), "effacée au passage");
    }

    static void bookIncomplete() {
        SeaChartBook b = new SeaChartBook();
        b.markIncomplete("w", "A", "B");
        check(b.incomplete("w", "A", "B") && !b.incomplete("w", "B", "A"), "paire ordonnée");
        b.clearIncomplete("w", "A", "B");
        check(!b.incomplete("w", "A", "B"), "effacée");
    }

    static void geometryConvertBalloon() {
        for (Rotation r : Rotation.values()) {
            Vector3i o = new Vector3i(100, 64, -40);
            StationGeometry.Converted c = StationGeometry.convert(Deployables.STATION_KIND, o, r);
            check(StationGeometry.dockOrigin(c.quay()).equals(StationPlacement.dockOrigin(o, r)), "dock " + r);
            check(c.master().equals(r.rotateYaw(new Vector3i(4, 0, 7), new Vector3i()).add(o)), "master " + r);
            check(c.quayRotation() == r, "rotation " + r);
            check(o.equals(new Vector3i(100, 64, -40)), "input untouched " + r);
        }
    }

    static void geometryConvertBoat() {
        for (Rotation r : Rotation.values()) {
            Vector3i o = new Vector3i(-7, 62, 300);
            StationGeometry.Converted c = StationGeometry.convert(Deployables.BOAT_STATION_KIND, o, r);
            Vector3i oldBerth = r.rotateYaw(new Vector3i(1, -1, 14), new Vector3i()).add(o);
            check(StationGeometry.berthOrigin(c.quay(), c.quayRotation()).equals(oldBerth), "berth " + r);
            check(c.quay().equals(r.rotateYaw(new Vector3i(1, 0, 11), new Vector3i()).add(o)), "pier end " + r);
        }
    }

    static void dropPointsNearestFirst() {
        Vector3i m = new Vector3i(0, 10, 0);
        List<org.joml.Vector3d> p = StationGeometry.dropPoints(m, 3, (x, y, z) -> y == 10 && !(x == 0 && z == 0));
        check(p.size() == 3, "count");
        check(Math.abs(p.get(0).x - 0.5) + Math.abs(p.get(0).z - 0.5) == 1.0, "first is a neighbour");
        check(p.get(0).y == 10.1, "feet");
    }

    static void dropPointsFallbackMaster() {
        List<org.joml.Vector3d> p = StationGeometry.dropPoints(new Vector3i(5, 20, 5), 2, (x, y, z) -> false);
        check(p.size() == 2 && p.get(0).x == 5.5 && p.get(0).z == 5.5, "fallback");
    }

    static void dropPointsFromRecordedMaster() {
        Vector3i m = new Vector3i(40, 10, 40);
        List<org.joml.Vector3d> p = StationGeometry.dropPoints(m, 1, (x, y, z) -> y == 10);
        check(Math.hypot(p.get(0).x - 40.5, p.get(0).z - 40.5) <= 1.5, "near recorded master");
    }

    static void arrivalRefusalRule() {
        Vector3i q = new Vector3i(0, 10, 0);
        check("transit.arrival.notOwner".equals(StationGeometry.arrivalRefusal(new Vector3i(1, 11, 0), q, false, true)), "not owner");
        check("transit.arrival.noQuay".equals(StationGeometry.arrivalRefusal(new Vector3i(1, 11, 0), null, true, true)), "no quay");
        check("transit.arrival.badSpot".equals(StationGeometry.arrivalRefusal(new Vector3i(1, 11, 0), q, true, false)), "bad spot");
        check(StationGeometry.arrivalRefusal(new Vector3i(16, 10, 0), q, true, true) == null, "16 in x");
        check("transit.arrival.tooFar".equals(StationGeometry.arrivalRefusal(new Vector3i(17, 10, 0), q, true, true)), "17 in x");
        check("transit.arrival.tooFar".equals(StationGeometry.arrivalRefusal(new Vector3i(12, 10, 12), q, true, true)), "12,12");
        check(StationGeometry.arrivalRefusal(new Vector3i(0, 18, 0), q, true, true) == null, "8 high");
        check("transit.arrival.tooFar".equals(StationGeometry.arrivalRefusal(new Vector3i(0, 19, 0), q, true, true)), "9 high");
    }

    static void dropPointsLoopAndRange() {
        Vector3i m = new Vector3i(0, 10, 0);
        // Only two cells qualify: the list loops over them.
        List<org.joml.Vector3d> p = StationGeometry.dropPoints(m, 5,
                (x, y, z) -> y == 10 && ((x == 1 && z == 0) || (x == 0 && z == 1)));
        check(p.size() == 5 && p.get(0).equals(p.get(2)) && p.get(1).equals(p.get(3)), "loops");
        // Order: horizontal distance, then |dy|, then x, then z.
        List<org.joml.Vector3d> q = StationGeometry.dropPoints(m, 2, (x, y, z) -> (x == 1 && z == 0 && (y == 10 || y == 11)));
        check(q.get(0).y == 10.1 && q.get(1).y == 11.1, "flat cell first: " + q);
        List<org.joml.Vector3d> r = StationGeometry.dropPoints(m, 2, (x, y, z) -> y == 11 && x == 1 && z == 0 || y == 10 && x == 0 && z == 2);
        check(r.get(0).x == 1.5 && r.get(0).y == 11.1, "nearer first even when higher: " + r);
        // Out of range cells (7 blocks away, 3 above) are ignored.
        List<org.joml.Vector3d> o = StationGeometry.dropPoints(m, 1, (x, y, z) -> x == 7 || y == 13);
        check(o.get(0).x == 0.5 && o.get(0).z == 0.5 && o.get(0).y == 10.1, "fallback to master cell centre: " + o);
    }

    private static TransitNetwork.Station dropStation(String kind, Vector3i quay, Vector3i arrival) {
        return dropStation(kind, quay, arrival, null);
    }

    private static TransitNetwork.Station dropStation(String kind, Vector3i quay, Vector3i arrival, java.util.UUID owner) {
        TransitNetwork.Station s = new TransitNetwork.Station("D", "w", new Vector3i(0, 0, 0), Rotation.None, null, owner,
                new java.util.ArrayList<>(), kind, false, true, new Vector3i(1, 2, 3), 0f, quay, Rotation.None);
        s.arrival = arrival;
        return s;
    }

    static void dropCentreArrival() {
        TransitNetwork.Station s = dropStation(Deployables.BOAT_STATION_KIND, new Vector3i(10, 20, 30), new Vector3i(5, 6, 7));
        eq(new Vector3i(5, 6, 7), StationGeometry.dropCentre(s, (x, y, z) -> x == 5 && y == 6 && z == 7));
    }

    static void dropCentreFallbacks() {
        TransitNetwork.Station s = dropStation(Deployables.BOAT_STATION_KIND, new Vector3i(10, 20, 30), new Vector3i(5, 6, 7));
        eq(new Vector3i(10, 21, 30), StationGeometry.dropCentre(s, (x, y, z) -> false));
        TransitNetwork.Station n = dropStation(Deployables.BOAT_STATION_KIND, new Vector3i(10, 20, 30), null);
        eq(new Vector3i(10, 21, 30), StationGeometry.dropCentre(n, (x, y, z) -> true));
        TransitNetwork.Station nq = dropStation(Deployables.BOAT_STATION_KIND, null, null);
        eq(nq.master, StationGeometry.dropCentre(nq, (x, y, z) -> true));
        TransitNetwork.Station b = dropStation(Deployables.STATION_KIND, new Vector3i(10, 20, 30), new Vector3i(5, 6, 7));
        eq(b.master, StationGeometry.dropCentre(b, (x, y, z) -> true));
    }

    static void dropPointsIncludeCentre() {
        Vector3i c = new Vector3i(4, 10, 4);
        org.joml.Vector3d with = StationGeometry.dropPoints(c, 3, (x, y, z) -> y == 10, true).get(0);
        check(with.x == 4.5 && with.y == 10.1 && with.z == 4.5, "centre first: " + with);
        org.joml.Vector3d without = StationGeometry.dropPoints(c, 3, (x, y, z) -> y == 10, false).get(0);
        check(!(without.x == 4.5 && without.z == 4.5), "centre excluded: " + without);
    }

    static void clearanceBalloonBounds() {
        Vector3i q = new Vector3i(0, 50, 0);
        check(StationGeometry.inBalloonClearance(q, 3, 51, 0), "column edge");
        check(!StationGeometry.inBalloonClearance(q, 4, 51, 0), "outside column at +1");
        check(!StationGeometry.inBalloonClearance(q, 10, 52, 10), "socle + 2 allowed");
        check(StationGeometry.inBalloonClearance(q, 10, 53, 10), "socle + 3 refused");
        check(!StationGeometry.inBalloonClearance(q, 13, 200, 0), "outside 25 x 25");
        check(StationGeometry.inBalloonClearance(q, 0, 300, 0), "column to the top");
        check(!StationGeometry.inBalloonClearance(q, 0, 50, 0), "the quay layer itself is not clearance");
    }

    static void clearanceBoatCorridor() {
        for (Rotation r : Rotation.values()) {
            Vector3i e = new Vector3i(10, 63, 10);
            Vector3i in = r.rotateYaw(new Vector3i(8, 0, 20), new Vector3i()).add(e);
            Vector3i out = r.rotateYaw(new Vector3i(9, 0, 5), new Vector3i()).add(e);
            Vector3i behind = r.rotateYaw(new Vector3i(0, 0, 0), new Vector3i()).add(e);
            check(StationGeometry.inBoatCorridor(e, r, 62, in.x, 62, in.z), "corner in " + r);
            check(!StationGeometry.inBoatCorridor(e, r, 62, out.x, 62, out.z), "side out " + r);
            check(!StationGeometry.inBoatCorridor(e, r, 62, in.x, 71, in.z), "above + 8 " + r);
            check(StationGeometry.inBoatCorridor(e, r, 62, in.x, 70, in.z), "surface + 8 in " + r);
            check(!StationGeometry.inBoatCorridor(e, r, 62, in.x, 60, in.z), "below surface - 1 " + r);
            check(!StationGeometry.inBoatCorridor(e, r, 62, behind.x, 62, behind.z), "pier end itself out " + r);
        }
    }

    /** Cells of a station prefab file read directly (Kind.shape() needs the game assets). */
    private static java.util.List<StructureShape.Cell> prefabCells(String file) throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs/" + file)));
        java.util.List<StructureShape.Cell> cells = new java.util.ArrayList<>();
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            cells.add(new StructureShape.Cell(b.getInt32("x").getValue(), b.getInt32("y").getValue(), b.getInt32("z").getValue(),
                    b.getString("name").getValue(), b.containsKey("filler") ? b.getInt32("filler").getValue() : 0));
        }
        return cells;
    }

    static void padShapeMatchesGeometry() throws Exception {
        check(Deployables.PAD_KIND.equals("station_pad"), "pad kind id");
        check(Deployables.all().contains(Deployables.STATION_PAD), "pad in ALL");
        var cells = prefabCells(Deployables.STATION_PAD.prefabPath);
        check(cells.size() == 49, "49 cells, got " + cells.size());
        TransitNetwork.Station s = new TransitNetwork.Station("Q", "w", new Vector3i(0, 0, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(1, 2, 3), 0f, new Vector3i(0, 0, 0),
                Rotation.None);
        int markers = 0;
        for (var c : cells) {
            check(StationGeometry.inQuayCore(s, c.x(), c.y(), c.z()), "pad cell in core " + c);
            check(c.y() == 0, "pad layer");
            if (StructureShape.isMarker(c, Deployables.STATION_PAD.anchorBlock)) {
                markers++;
                check(c.x() == 0 && c.z() == 0, "marker at centre");
            }
        }
        check(markers == 1, "one marker");
        var box = Deployables.STATION_PAD;
        check(box.inPlaceVolume(-3, 0, 3) && !box.inPlaceVolume(4, 0, 0), "place volume is the prefab box");
        // Stone rename applies to the pad bricks and not to the marker.
        StructureShape base = StructureShape.ofCells(cells);
        StructureShape sand = BalloonRegistry.remapStationShape(base, null, new BiomeStone.Stone("Sandstone"), x -> true);
        check(sand.cells().stream().anyMatch(c -> !c.name().equals("Rock_Basalt_Brick") && !c.name().equals(Deployables.STATION_PAD.anchorBlock)),
                "pad bricks renamed");
    }

    static void pierShapeInsideCore() throws Exception {
        check(Deployables.PIER_KIND.equals("boat_station_pier"), "pier kind id");
        check(Deployables.all().contains(Deployables.BOAT_STATION_PIER), "pier in ALL");
        var cells = prefabCells(Deployables.BOAT_STATION_PIER.prefabPath);
        int markers = 0;
        for (Rotation r : Rotation.values()) {
            Vector3i q = new Vector3i(100, 63, 100);
            TransitNetwork.Station s = new TransitNetwork.Station("P", "w", new Vector3i(0, 0, 0), Rotation.None, null, null,
                    new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND, false, true, new Vector3i(1, 2, 3), 0f, q, r);
            for (var c : cells) {
                Vector3i w = r.rotateYaw(new Vector3i(c.x(), 0, c.z()), new Vector3i()).add(q);
                check(StationGeometry.inQuayCore(s, w.x, q.y + c.y(), w.z), "pier cell in core " + c + " " + r);
            }
        }
        for (var c : cells) {
            if (StructureShape.isMarker(c, Deployables.BOAT_STATION_PIER.anchorBlock) && c.x() == 0 && c.y() == 0 && c.z() == 0) {
                markers++;
            }
        }
        check(markers == 1, "one anchor cell at (0, 0, 0)");
        check(java.util.Arrays.equals(Deployables.BOAT_STATION_PIER.anchorAt, new int[]{0, 0, 0}), "anchor position");
        check(Deployables.BOAT_STATION_PIER.inPlaceVolume(-2, -10, -3) && Deployables.BOAT_STATION_PIER.inPlaceVolume(3, 2, 0)
                && !Deployables.BOAT_STATION_PIER.inPlaceVolume(4, 0, 0) && !Deployables.BOAT_STATION_PIER.inPlaceVolume(0, 0, 1),
                "place volume is the prefab box");
    }

    static void pierDeckPostsAndLegacy() throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs/" + Deployables.BOAT_STATION_PIER.prefabPath)));
        int deck = 0, trunks = 0, lanterns = 0;
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            String n = b.getString("name").getValue();
            check(!n.equals("Furniture_Desert_Lantern_Tall") && !n.endsWith("_Beam"), "no lantern, no beam: " + n);
            if (n.equals("Wood_Hardwood_Planks_Half")) {
                deck++;
                check(b.getInt32("y").getValue() == 0 && !b.containsKey("rotation"), "deck plank at y 0, unturned");
            } else if (n.equals("Deco_Lantern")) {
                lanterns++;
                check(b.getInt32("x").getValue() == -2 && b.getInt32("y").getValue() == 2 && b.getInt32("z").getValue() == -1,
                        "lantern on top of the right side log");
            } else if (n.equals("Wood_Ash_Trunk")) {
                trunks++;
                int x = b.getInt32("x").getValue(), y = b.getInt32("y").getValue(), z = b.getInt32("z").getValue();
                boolean side = (x == -2 || x == 3) && z == -1;
                check(b.getInt32("support").getValue() == 15 && y >= -10 && (side ? y <= 1 : y < 0),
                        "trunk: corner post below the deck or side post up to one block above it");
            }
        }
        check(deck == 16 && trunks == 64 && lanterns == 1 && root.getArray("blocks").size() == 81,
                "16 deck planks, 64 trunks, one lantern, nothing else");
        check(Deployables.BOAT_STATION_PIER.anchorBlock.equals("Wood_Hardwood_Planks_Half"), "anchor is a deck plank");
        // Wood of the biome: deck and posts follow it, the default wood keeps the prefab.
        var w = new BiomeWood.Wood("Birch", "Lightwood");
        eq("Wood_Lightwood_Planks_Half", BiomeWood.remap("Wood_Hardwood_Planks_Half", w, x -> true));
        eq("Wood_Birch_Trunk", BiomeWood.remap("Wood_Ash_Trunk", w, x -> true));
        // Both boat plans keep a half turn: the water side of the pier end points straight ahead of the player.
        eq(Rotation.OneEighty, DeployPlacement.CRATES.get("Hotair_Balloon_Boat_Station_Plan_Pier").rotationOffset());
        eq(Rotation.OneEighty, DeployPlacement.CRATES.get("Hotair_Balloon_Boat_Station_Plan_Full").rotationOffset());
        // Piers placed with the first prefab: beams and the lantern still match their cells.
        var deckCell = new StructureShape.Cell(0, 0, 0, "Wood_Hardwood_Planks_Half");
        var trunkCell = new StructureShape.Cell(2, -3, 0, "Wood_Birch_Trunk");
        check(BalloonManager.legacyAlias(trunkCell.baseName(), "Wood_Hardwood_Beam"), "old beam matches a trunk");
        check(!BalloonManager.legacyAlias("Wood_Birch_Trunk", "Wood_Hardwood_Planks"), "plain planks do not match a trunk");
        var legacy = Deployables.BOAT_STATION_PIER.legacy;
        check(legacy.size() == 1 && legacy.get(0).x() == -1 && legacy.get(0).y() == 1 && legacy.get(0).z() == -3
                && legacy.get(0).baseName().equals("Furniture_Desert_Lantern_Tall"), "old lantern is the legacy cell");
        // The anchor is re-read after a rename and the legacy cell stays inside the bounds (the y 1 headroom stays locked).
        StructureShape base = StructureShape.ofCells(java.util.List.of(deckCell, trunkCell), deckCell, legacy);
        StructureShape renamed = base.withCells(java.util.List.of(new StructureShape.Cell(0, 0, 0, "Wood_Redwood_Planks_Half"), trunkCell));
        eq("Wood_Redwood_Planks_Half", renamed.anchor().name());
        check(renamed.inBounds(-1, 1, -3) || renamed.inBounds(0, 1, 0), "legacy cell counted in the bounds");
        check(renamed.legacyAt(-1, 1, -3) != null && renamed.legacyAt(0, 0, 0) == null, "legacy lookup");
    }

    /** The held preview adds the shuttle at its berth: the pier end plus (0, -1, 3), turned by the quay rotation. */
    static void previewShuttleMerge() {
        var base = new com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange();
        base.blocksChange = new com.hypixel.hytale.protocol.packets.interface_.BlockChange[]{
                new com.hypixel.hytale.protocol.packets.interface_.BlockChange(0, 0, 0, 1, (byte) 0)};
        var boat = new com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange();
        boat.blocksChange = new com.hypixel.hytale.protocol.packets.interface_.BlockChange[]{
                new com.hypixel.hytale.protocol.packets.interface_.BlockChange(1, 2, 3, 7, (byte) 5)};
        for (Rotation r : Rotation.values()) {
            Vector3i at = StationGeometry.berthOrigin(new Vector3i(0, 0, 0), r);
            var merged = HeldCratePreview.merge(base, boat, at.x, at.y, at.z);
            eq(2, merged.blocksChange.length);
            var c = merged.blocksChange[1];
            check(c.x == 1 + at.x && c.y == 2 + at.y && c.z == 3 + at.z && c.block == 7 && c.rotation == 5, "shuttle block shifted " + r);
            check(boat.blocksChange[0].x == 1 && base.blocksChange.length == 1, "sources untouched");
        }
        Vector3i north = StationGeometry.berthOrigin(new Vector3i(0, 0, 0), Rotation.None);
        eq(new Vector3i(0, -1, 3), north);
    }

    static void quayCoreBalloon() {
        TransitNetwork.Station s = new TransitNetwork.Station("Q", "w", new Vector3i(0, 0, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(1, 2, 3), 0f, new Vector3i(0, 50, 0),
                Rotation.None);
        check(StationGeometry.inQuayCore(s, 3, 50, -3), "corner in core");
        check(!StationGeometry.inQuayCore(s, 4, 50, 0), "outside pad");
        check(!StationGeometry.inQuayCore(s, 0, 51, 0), "above the pad");
        check(!StationGeometry.inQuayCore(s, 0, 49, 0), "below the pad");
    }

    static void quayCoreBoat() {
        for (Rotation r : Rotation.values()) {
            Vector3i q = new Vector3i(100, 63, 100);
            TransitNetwork.Station s = new TransitNetwork.Station("P", "w", new Vector3i(0, 0, 0), Rotation.None, null, null,
                    new java.util.ArrayList<>(), Deployables.BOAT_STATION_KIND, false, true, new Vector3i(1, 2, 3), 0f, q, r);
            java.util.function.BiPredicate<Vector3i, Integer> core = (l, y) -> {
                Vector3i w = r.rotateYaw(l, new Vector3i()).add(q);
                return StationGeometry.inQuayCore(s, w.x, q.y + y, w.z);
            };
            check(core.test(new Vector3i(-2, 0, -4), 2), "corner in " + r);
            check(core.test(new Vector3i(3, 0, 0), -10), "far corner in " + r);
            check(!core.test(new Vector3i(4, 0, 0), 0), "lx + 1 out " + r);
            check(!core.test(new Vector3i(0, 0, 1), 0), "lz + 1 out " + r);
            check(!core.test(new Vector3i(-3, 0, -2), 0), "lx - 1 out " + r);
            check(!core.test(new Vector3i(0, 0, -5), 0), "lz - 1 out " + r);
            check(!core.test(new Vector3i(0, 0, 0), 3), "above out " + r);
            check(!core.test(new Vector3i(0, 0, 0), -11), "below out " + r);
        }
    }

    static void clearanceEditableRule() throws Exception {
        Path dir = Files.createTempDirectory("clearance-editable");
        try {
            TransitSettings.load(dir);
            eq(false, TransitSettings.generatedStationsEditable());
            TransitNetwork.Station gen = new TransitNetwork.Station("G", "w", new Vector3i(0, 0, 0), Rotation.None, null, null,
                    new java.util.ArrayList<>(), Deployables.STATION_KIND);
            TransitNetwork.Station mine = new TransitNetwork.Station("M", "w", new Vector3i(0, 0, 0), Rotation.None, null,
                    java.util.UUID.randomUUID(), new java.util.ArrayList<>(), Deployables.STATION_KIND, true, false,
                    StationGeometry.convert(Deployables.STATION_KIND, new Vector3i(0, 0, 0), Rotation.None));
            check(!StationClearance.editable(gen), "generated station locked by default");
            check(!StationClearance.editable(mine), "player station not editable by default");
            mine.editable = true;
            check(StationClearance.editable(mine), "editable flag");
            TransitSettings.setGeneratedStationsEditable(true);
            check(StationClearance.editable(gen), "setting unlocks generated stations");
            TransitSettings.load(dir);
            eq(true, TransitSettings.generatedStationsEditable());
            check(Files.readString(dir.resolve("transit-settings.json")).contains("generatedStationsEditable"), "setting saved");
        } finally {
            Path reset = Files.createTempDirectory("clearance-editable-reset");
            TransitSettings.load(reset);
            deleteAll(reset);
            deleteAll(dir);
        }
    }

    static void markerPointAndPadCell() {
        TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(0, 0, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(1, 2, 3), 0f, new Vector3i(10, 20, 30),
                Rotation.None);
        eq(new org.joml.Vector3d(10.5, 21, 30.5), StationGeometry.markerPoint(s));
        eq(new Vector3i(7, 20, 27), StationGeometry.padCell(s, 0));
        eq(new Vector3i(13, 20, 27), StationGeometry.padCell(s, 6));
        eq(new Vector3i(7, 20, 28), StationGeometry.padCell(s, 7));
        eq(new Vector3i(13, 20, 33), StationGeometry.padCell(s, 48));
        eq(StationGeometry.padCell(s, 0), StationGeometry.padCell(s, 49));
    }

    static void despawnTargetsNearestMaster() {
        // Master cells at 10 and 30 blocks: the first. The pending station (no quay) counts. Nothing within 48: null.
        TransitNetwork.Station near = new TransitNetwork.Station("A", "w", new Vector3i(500, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(10, 64, 0), 0f, null, null);
        TransitNetwork.Station far = new TransitNetwork.Station("B", "w", new Vector3i(900, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(30, 64, 0), 0f,
                new Vector3i(900, 64, 0), Rotation.None);
        List<TransitNetwork.Station> all = List.of(far, near);
        eq(new Vector3i(10, 64, 0), StationCommand.nearestMaster(all, new Vector3d(0.5, 64, 0.5), 48));
        eq(new Vector3i(30, 64, 0), StationCommand.nearestMaster(all, new Vector3d(40.5, 64, 0.5), 48));
        eq(null, StationCommand.nearestMaster(all, new Vector3d(200, 64, 0), 48));
        eq(null, StationCommand.nearestMaster(List.of(), new Vector3d(0, 64, 0), 48));
        // A generated station has no master cell: it is skipped.
        TransitNetwork.Station noMaster = new TransitNetwork.Station("C", "w", new Vector3i(1, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, null, 0f, null, null);
        eq(null, StationCommand.nearestMaster(List.of(noMaster), new Vector3d(0, 64, 0), 48));
    }

    static void modBlocksDropGameItems() throws Exception {
        Path dir = Path.of("Server/Item/Items/Containers/Vehicles");
        Map<String, String> drops = Map.of("Balloon_Station_Marker", "Furniture_Village_Brazier", "Boat_Station_Map", "Deco_Map",
                "Boat_Station_Inkwell", "Deco_Inkwell", "Boat_Station_Candle", "Furniture_Kweebec_Candle",
                "Boat_Station_Shell", "Deco_Coral_Shell_Swirly", "Boat_Station_Treasure", "Deco_Treasure",
                "Boat_Station_Gate", "Wood_Hardwood_Fence_Gate");
        for (Map.Entry<String, String> e : drops.entrySet()) {
            String json = Files.readString(dir.resolve(e.getKey() + ".json"));
            check(json.contains("\"ItemId\": \"" + e.getValue() + "\""), e.getKey() + " drops " + e.getValue());
        }
    }

    static void pendingStationsHaveNoQuay() {
        TransitNetwork.Station ok = new TransitNetwork.Station("Ok", "pw", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND);
        TransitNetwork.Station no = new TransitNetwork.Station("No", "pw", new Vector3i(200, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(200, 64, 0), 0f, null, null);
        TransitNetwork net = TransitNetwork.get();
        net.add(ok);
        net.add(no);
        try {
            check(net.stations("pw", Deployables.STATION_KIND).equals(List.of(ok)), "only with quay");
            check(net.pending("pw").equals(List.of(no)), "pending");
            check(net.stations("pw").size() == 2, "all stations");
        } finally {
            net.remove(ok);
            net.remove(no);
        }
    }

    /** a station registered through the API carries the geometry of its whole prefab; the API reads masters, hides pending stations. */
    static void apiRegisterUsesConvertedGeometry() {
        Vector3i origin = new Vector3i(1000, 70, 2000);
        Rotation rot = Rotation.Ninety;
        TransitNetwork net = TransitNetwork.get();
        String name = StationPlacement.registerStation("apiw", origin, rot, null, "Apiville", Deployables.STATION_KIND);
        TransitNetwork.Station s = net.at("apiw", origin);
        TransitNetwork.Station pending = null;
        try {
            eq("Apiville", name);
            StationGeometry.Converted g = StationGeometry.convert(Deployables.STATION_KIND, origin, rot);
            eq(g.master(), s.master);
            eq(g.masterYaw(), s.masterYaw);
            eq(g.quay(), s.quay);
            eq(g.quayRotation(), s.quayRotation);
            eq(false, s.editable);
            eq(true, s.fullPrefab);
            // nearStation converts the candidate prefab origin to its master cell
            Vector3i far = new Vector3i(origin).add(70, 0, 0);
            eq(null, ApiStations.nearIn(net.stations("apiw"), Deployables.STATION_KIND, far));
            Vector3i candidate = new Vector3i(origin).add(40, 0, 0);
            eq("Apiville", ApiStations.nearIn(net.stations("apiw"), Deployables.STATION_KIND, candidate).name());
            eq(null, ApiStations.nearIn(net.stations("apiw"), Deployables.BOAT_STATION_KIND, candidate));
            // a pending station counts for the distance, but is neither listed nor returned by column
            pending = net.addPending("apiw", new Vector3i(1500, 70, 2000), 0f, UUID.randomUUID(), Deployables.STATION_KIND);
            eq(true, ApiStations.nearIn(net.stations("apiw"), Deployables.STATION_KIND, new Vector3i(1500, 70, 2000)) != null);
            eq(null, ApiStations.visible(pending));
            eq("Apiville", ApiStations.visible(s).name());
            eq(null, ApiStations.stationAtIn(net, "apiw", 1500, 2000));
            eq("Apiville", ApiStations.stationAtIn(net, "apiw", 1000, 2000).name());
            // the boarding point is the one of the full prefab
            int[] bp = new ApiStationKinds().airBoardingPoint();
            eq(Math.round(Deployables.STATION_BOARDING_POINTS[0][1]), bp[1]);
        } finally {
            net.remove(s);
            if (pending != null) net.remove(pending);
        }
    }

    static void masterTooCloseSameKindOnly() {
        TransitNetwork.Station near = new TransitNetwork.Station("A", "w", new Vector3i(500, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(63, 64, 0), 0f,
                new Vector3i(500, 64, 0), Rotation.None);
        TransitNetwork.Station far = new TransitNetwork.Station("B", "w", new Vector3i(1, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(64, 64, 0), 0f,
                new Vector3i(1, 64, 0), Rotation.None);
        TransitNetwork.Station pendingNear = new TransitNetwork.Station("C", "w", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(0, 64, 0), 0f, null, null);
        Vector3i at = new Vector3i(0, 64, 0);
        // every station counts from its master cell, whatever its origin (identity) and quay
        eq("transit.deploy.tooClose", PlaceMasterInteraction.refusal(List.of(near), at));
        eq(null, PlaceMasterInteraction.refusal(List.of(far), at));
        eq(null, PlaceMasterInteraction.refusal(List.of(), at));
        eq(pendingNear, PlaceMasterInteraction.closest(List.of(far, pendingNear), at));
        eq("transit.deploy.tooClose", PlaceMasterInteraction.refusal(List.of(pendingNear), new Vector3i(63, 64, 0)));
        eq(null, PlaceMasterInteraction.refusal(List.of(pendingNear), new Vector3i(64, 64, 0)));
        // the same rule for the generated stations and the API
        eq(near, StationPlacement.tooClose(List.of(near, far), Deployables.STATION_KIND, at, 64));
        eq(null, StationPlacement.tooClose(List.of(far), Deployables.STATION_KIND, at, 64));
        eq(null, StationPlacement.tooClose(List.of(near), Deployables.BOAT_STATION_KIND, at, 64));
        // a whole station moves its master: the other stations only are measured, the pending one excluded
        eq(null, PlaceQuayInteraction.tooCloseToMaster(List.of(pendingNear), pendingNear, new Vector3i(10, 64, 0)));
        eq(near, PlaceQuayInteraction.tooCloseToMaster(List.of(pendingNear, near, far), pendingNear, new Vector3i(10, 64, 0)));
        eq(null, PlaceQuayInteraction.tooCloseToMaster(List.of(pendingNear, far), pendingNear, new Vector3i(0, 64, 0)));
        eq(far, PlaceQuayInteraction.tooCloseToMaster(List.of(pendingNear, far), pendingNear, new Vector3i(1, 64, 0)));
    }

    /** the distance between two stations (destination list) reads the marker point, not the identity origin. */
    static void stationDistanceUsesGeometry() {
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new java.util.ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(0, 64, 0), 0f, new Vector3i(100, 70, 0), Rotation.None);
        org.joml.Vector3d m = StationGeometry.markerPoint(a);
        eq(100.5, m.x);
        eq(71.0, m.y);
    }

    private static TransitNetwork.Station pendingAt(UUID owner, int x, int y, int z, String kind) {
        return new TransitNetwork.Station("P", "w", new Vector3i(x, y, z), Rotation.None, null, owner, new java.util.ArrayList<>(), kind,
                false, true, new Vector3i(x, y, z), 0f, null, null);
    }

    /** the refusals of a quay: owner, quay already placed, reach of a pad. */
    static void quayRefusals() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TransitNetwork.Station s = pendingAt(owner, 100, 64, 100, Deployables.STATION_KIND);
        String bal = Deployables.STATION_KIND;
        eq(null, PlaceQuayInteraction.refusal(s, owner, false, new Vector3i(116, 64, 100), bal, false));
        eq(null, PlaceQuayInteraction.refusal(s, owner, false, new Vector3i(100, 70, 84), bal, false));
        eq("transit.quay.tooFar", PlaceQuayInteraction.refusal(s, owner, false, new Vector3i(116, 64, 101), bal, false));
        eq("transit.quay.tooFar", PlaceQuayInteraction.refusal(s, owner, false, new Vector3i(111, 64, 112), bal, false));
        eq(null, PlaceQuayInteraction.refusal(s, owner, false, new Vector3i(111, 64, 111), bal, false));
        eq("transit.quay.notOwner", PlaceQuayInteraction.refusal(s, other, false, new Vector3i(100, 64, 100), bal, false));
        eq(null, PlaceQuayInteraction.refusal(s, other, true, new Vector3i(100, 64, 100), bal, false));
        // a whole station moves the master into its hut: no reach rule
        eq(null, PlaceQuayInteraction.refusal(s, owner, false, new Vector3i(160, 64, 100), bal, true));
        // a station that already has its quay
        TransitNetwork.Station done = pendingAt(owner, 100, 64, 100, bal);
        done.quay = new Vector3i(100, 64, 100);
        eq("transit.quay.already", PlaceQuayInteraction.refusal(done, owner, false, new Vector3i(100, 64, 100), bal, false));
        eq("transit.quay.already", PlaceQuayInteraction.refusal(done, owner, true, new Vector3i(100, 64, 100), bal, true));
        eq("transit.quay.notOwner", PlaceQuayInteraction.refusal(done, other, false, new Vector3i(100, 64, 100), bal, false));
        // a boat station has no reach rule for its pier end
        TransitNetwork.Station boat = pendingAt(owner, 0, 63, 0, Deployables.BOAT_STATION_KIND);
        eq(null, PlaceQuayInteraction.refusal(boat, owner, false, new Vector3i(40, 62, 0), Deployables.BOAT_STATION_KIND, false));
        eq("transit.quay.notOwner", PlaceQuayInteraction.refusal(boat, other, false, new Vector3i(40, 62, 0), Deployables.BOAT_STATION_KIND, false));
        // a station without an owner (generated) belongs to nobody but an operator
        eq("transit.quay.notOwner", PlaceQuayInteraction.refusal(pendingAt(null, 0, 64, 0, bal), owner, false, new Vector3i(), bal, false));
    }

    /** the station that receives a plan: the nearest pending one of the player within 48 blocks, any owner for an operator. */
    static void quayNearestPending() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TransitNetwork.Station near = pendingAt(owner, 10, 64, 0, Deployables.STATION_KIND);
        TransitNetwork.Station nearer = pendingAt(owner, 5, 64, 0, Deployables.STATION_KIND);
        TransitNetwork.Station foreign = pendingAt(other, 1, 64, 0, Deployables.STATION_KIND);
        TransitNetwork.Station far = pendingAt(owner, 60, 64, 0, Deployables.STATION_KIND);
        TransitNetwork.Station done = pendingAt(owner, 2, 64, 0, Deployables.STATION_KIND);
        done.quay = new Vector3i(2, 64, 0);
        org.joml.Vector3d at = new org.joml.Vector3d(0.5, 64, 0.5);
        List<TransitNetwork.Station> all = List.of(near, nearer, foreign, far, done);
        eq(nearer, PlaceQuayInteraction.nearestPending(all, owner, false, at, 48));
        eq(foreign, PlaceQuayInteraction.nearestPending(all, owner, true, at, 48));
        eq(null, PlaceQuayInteraction.nearestPending(List.of(far, foreign, done), owner, false, at, 48));
        eq(far, PlaceQuayInteraction.nearestPending(List.of(far), owner, false, at, 60));
        eq(null, PlaceQuayInteraction.nearestPending(List.of(), owner, false, at, 48));
    }

    /** the structure a plan places, by station kind and model. */
    static void quayStructureKind() {
        eq(Deployables.STATION_PAD, PlaceQuayInteraction.structureKind(Deployables.STATION_KIND, "Pad"));
        eq(Deployables.STATION, PlaceQuayInteraction.structureKind(Deployables.STATION_KIND, "Full"));
        eq(Deployables.BOAT_STATION_PIER, PlaceQuayInteraction.structureKind(Deployables.BOAT_STATION_KIND, "Pad"));
        eq(Deployables.BOAT_STATION, PlaceQuayInteraction.structureKind(Deployables.BOAT_STATION_KIND, "Full"));
        eq(null, PlaceQuayInteraction.structureKind("tent", "Pad"));
        eq(null, PlaceQuayInteraction.structureKind(Deployables.STATION_KIND, "Half"));
        eq(null, PlaceQuayInteraction.structureKind(Deployables.STATION_KIND, null));
    }

    /** the room test of a pad: the pad layer, the free column, the 25 x 25 square up to the pad plus two, vegetation, unloaded sections. */
    static void quayPadVolume() {
        Vector3i q = new Vector3i(100, 64, 100);
        PlaceQuayInteraction.Found stone = new PlaceQuayInteraction.Found(PlaceQuayInteraction.Content.BLOCKED, "Rock_Stone");
        PlaceQuayInteraction.Found grass = new PlaceQuayInteraction.Found(PlaceQuayInteraction.Content.VEGETATION, "Plant_Grass_Tall");
        PlaceQuayInteraction.Found unloaded = new PlaceQuayInteraction.Found(PlaceQuayInteraction.Content.UNLOADED, null);
        check(PlaceQuayInteraction.padVolume(q, (x, y, z) -> PlaceQuayInteraction.Found.EMPTY).fits(), "empty volume fits");
        // ground below the pad is not tested
        check(PlaceQuayInteraction.padVolume(q, (x, y, z) -> y < 64 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(), "ground under the pad");
        // a block in the pad layer
        var v = PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 102 && y == 64 && z == 98 ? stone : PlaceQuayInteraction.Found.EMPTY);
        check(!v.fits() && v.fault().equals(new Vector3i(102, 64, 98)) && v.found() == stone, "pad layer " + v.fault());
        // outside the 7 x 7 pad layer, same height: free (the square starts at the pad plus three)
        check(PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 104 && y == 64 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(), "beside the pad");
        // the column and the square are tested up to the height limit of the world
        check(!PlaceQuayInteraction.padVolume(q, (x, y, z) -> y == 104 && x == 100 && z == 100 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(),
                "column above pad plus 40");
        check(!PlaceQuayInteraction.padVolume(q, (x, y, z) -> y == BalloonManager.WORLD_MAX_Y && x == 100 && z == 100 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(),
                "column top");
        check(!PlaceQuayInteraction.padVolume(q, (x, y, z) -> y == BalloonManager.WORLD_MAX_Y && x == 110 && z == 100 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(),
                "square top");
        // square: free up to pad + 2, blocked from pad + 3, only within 12 blocks
        check(PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 110 && z == 100 && y <= 66 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(),
                "square up to pad plus two");
        check(!PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 110 && z == 100 && y == 67 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(),
                "square at pad plus three");
        check(PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 113 && y == 80 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(), "outside the square");
        check(!PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 112 && z == 88 && y == 67 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(),
                "square corner");
        // vegetation counts as empty and is listed once per cell
        v = PlaceQuayInteraction.padVolume(q, (x, y, z) -> x == 100 && z == 100 && y >= 64 && y <= 65 ? grass : PlaceQuayInteraction.Found.EMPTY);
        check(v.fits() && v.vegetation().equals(List.of(new Vector3i(100, 64, 100), new Vector3i(100, 65, 100))), "vegetation " + v.vegetation());
        // unloaded: empty above the pad, a refusal on the pad layer
        check(PlaceQuayInteraction.padVolume(q, (x, y, z) -> y > 64 ? unloaded : PlaceQuayInteraction.Found.EMPTY).fits(), "unloaded above");
        v = PlaceQuayInteraction.padVolume(q, (x, y, z) -> y == 64 ? unloaded : PlaceQuayInteraction.Found.EMPTY);
        check(!v.fits() && v.found() == unloaded, "unloaded pad layer");
        // the top of the world cuts the column short
        Vector3i high = new Vector3i(0, 310, 0);
        check(PlaceQuayInteraction.padVolume(high, (x, y, z) -> y > 319 ? stone : PlaceQuayInteraction.Found.EMPTY).fits(), "world top");
        check(!PlaceQuayInteraction.padVolume(new Vector3i(0, 330, 0), (x, y, z) -> PlaceQuayInteraction.Found.EMPTY).fits(), "pad above the world");
    }

    /** the basin dug in front of a pier end: profile, cells to dig, refusals, rotation, and the tests reading the dug cells as water. */
    static void pierDredge() {
        // profile: 4 at the centre line, 2 at the edges and in the rows next to the pier, symmetric
        for (int z = 3; z <= 20; z++) {
            eq(4, PierDredge.depth(0, z));
            eq(2, PierDredge.depth(8, z));
            eq(2, PierDredge.depth(-8, z));
            eq(PierDredge.depth(5, z), PierDredge.depth(-5, z));
            for (int x = -8; x < 0; x++) {
                check(PierDredge.depth(x, z) <= PierDredge.depth(x + 1, z), "the depth never grows toward the edge at x " + x);
            }
        }
        for (int x = -8; x <= 8; x++) {
            eq(2, PierDredge.depth(x, 1));
            eq(2, PierDredge.depth(x, 2));
        }
        eq(4, PierDredge.depth(4, 10));
        eq(3, PierDredge.depth(6, 10));
        // diggable blocks: natural yes, built, mod and empty no
        for (String id : new String[] {"Soil_Sand_White", "Soil_Gravel_Sand_White", "Rock_Sandstone", "Rock_Quartzite", "Plant_Seaweed_Grass", "*Rock_Stone"}) {
            check(PierDredge.diggable(id), "diggable " + id);
        }
        for (String id : new String[] {"Wood_Oak_Planks", "Furniture_Crude_Chest", "Hotair_Balloon_Basket", "Deco_Lantern", null, ""}) {
            check(!PierDredge.diggable(id), "not diggable " + id);
        }

        Vector3i end = new Vector3i(50, 65, 50);
        int surface = 64;
        java.util.function.Predicate<String> natural = PierDredge::diggable;
        java.util.function.Predicate<Vector3i> none = p -> false;
        // deep water everywhere: nothing to dig
        PierDredge.Cells deep = (x, y, z) -> y <= surface ? PierDredge.Cell.WATER : PierDredge.Cell.AIR;
        PierDredge.Plan p = PierDredge.plan(end, Rotation.None, surface, deep, natural, none);
        check(p.ok() && p.dig().isEmpty(), "nothing dug when already deep");

        // a bank of sand 1 under the surface from z 70 on (local z 20 only) and a bar at the centre line, 3 under the surface
        PierDredge.Cell sand = new PierDredge.Cell(PierDredge.Kind.BLOCK, "Soil_Sand_White");
        PierDredge.Cells bar = (x, y, z) -> {
            if (z == 70 && y == surface - 2) {
                return sand;
            }
            return y <= surface ? PierDredge.Cell.WATER : PierDredge.Cell.AIR;
        };
        p = PierDredge.plan(end, Rotation.None, surface, bar, natural, none);
        check(p.ok(), "sand is natural");
        // depth of the row z 70 (local z 20): y = surface - 2 is the third water cell, dug where the depth reaches 3 or more
        int expected = 0;
        for (int lx = -8; lx <= 8; lx++) {
            expected += PierDredge.depth(lx, 20) >= 3 ? 1 : 0;
        }
        eq(expected, p.dig().size());
        check(p.dig().contains(new Vector3i(50, surface - 2, 70)), "centre of the bar is dug");
        check(!p.dig().contains(new Vector3i(58, surface - 2, 70)), "the edge keeps depth 2");

        // a beach: sand up to the surface level on the side x >= 56, air above it; the dug cells come out of the profile only
        PierDredge.Cells beach = (x, y, z) -> {
            if (x >= 56 && y <= surface) {
                return sand;
            }
            return y <= surface ? PierDredge.Cell.WATER : PierDredge.Cell.AIR;
        };
        p = PierDredge.plan(end, Rotation.None, surface, beach, natural, none);
        check(p.ok(), "a beach of sand is dug");
        for (Vector3i c : p.dig()) {
            int lx = c.x - 50;
            int lz = c.z - 50;
            check(c.y > surface - PierDredge.depth(lx, lz) && c.y <= surface, "a dug cell lies in the profile: " + c);
            check(lz >= 1 && lz <= 20 && Math.abs(lx) <= 8, "a dug cell lies in the zone: " + c);
        }
        check(p.dig().contains(new Vector3i(56, surface, 60)) && p.dig().contains(new Vector3i(56, surface - 1, 60)), "the beach column is dug at the surface");
        check(!p.dig().contains(new Vector3i(56, surface - 4, 60)), "nothing under the profile");

        // a block that is not natural, or a structure cell, refuses and names the block
        PierDredge.Cell planks = new PierDredge.Cell(PierDredge.Kind.BLOCK, "Wood_Oak_Planks");
        p = PierDredge.plan(end, Rotation.None, surface, (x, y, z) -> x == 50 && z == 55 && y == surface - 1 ? planks : deep.at(x, y, z), natural, none);
        check(!p.ok() && "Wood_Oak_Planks".equals(p.faultCell().id()), "a built block refuses");
        eq(new Vector3i(50, surface - 1, 55), p.fault());
        p = PierDredge.plan(end, Rotation.None, surface, (x, y, z) -> x == 50 && z == 55 && y == surface - 1 ? sand : deep.at(x, y, z), natural,
                c -> c.x == 50 && c.z == 55);
        check(!p.ok(), "a cell of another structure refuses");
        // a block below the profile does not matter
        p = PierDredge.plan(end, Rotation.None, surface, (x, y, z) -> y == surface - 4 ? planks : deep.at(x, y, z), natural, none);
        check(p.ok(), "built block under the basin");
        // unloaded and foreign liquid refuse
        p = PierDredge.plan(end, Rotation.None, surface, (x, y, z) -> x == 50 && z == 60 && y == surface ? new PierDredge.Cell(PierDredge.Kind.UNLOADED, null) : deep.at(x, y, z), natural, none);
        check(!p.ok() && p.faultCell().kind() == PierDredge.Kind.UNLOADED, "unloaded refuses");
        p = PierDredge.plan(end, Rotation.None, surface, (x, y, z) -> x == 50 && z == 60 && y == surface - 1 ? new PierDredge.Cell(PierDredge.Kind.LIQUID, "Fluid_Lava") : deep.at(x, y, z), natural, none);
        check(!p.ok() && "Fluid_Lava".equals(p.faultCell().id()), "lava refuses");

        // rotation: Ninety sends local +z to world +x
        PierDredge.Cells wallEast = (x, y, z) -> x == 70 && z == 50 && y == surface - 2 ? sand : deep.at(x, y, z);
        p = PierDredge.plan(end, Rotation.Ninety, surface, wallEast, natural, none);
        check(p.ok() && p.dig().contains(new Vector3i(70, surface - 2, 50)), "rotated zone reaches +x");
        p = PierDredge.plan(end, Rotation.None, surface, wallEast, natural, none);
        check(p.ok() && p.dig().isEmpty(), "unrotated zone does not reach it");

        // the corridor test, the berth style test and the water probe read the dug cells as water
        PierDredge.Cells sandy = (x, y, z) -> y == surface - 1 && z > 50 ? sand : (y <= surface - 1 ? sand : y == surface ? PierDredge.Cell.WATER : PierDredge.Cell.AIR);
        DeployPlacement.CellState raw = PierDredge.state(sandy);
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, raw) != null, "sand at surface - 1 refuses as before");
        p = PierDredge.plan(end, Rotation.None, surface, sandy, natural, none);
        check(p.ok() && !p.dig().isEmpty(), "the plan digs the sand");
        DeployPlacement.CellState dug = DeployPlacement.withWater(raw, p.dig());
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, dug) == null, "the corridor passes once the cells are water");
        check(dug.at(50, surface - 3, 60) == 1 && dug.at(50, surface - 4, 60) == 2, "dug cells read as water, the rest as before");

        // water choice: source first, then the highest level, else the foot of the pier end
        Vector3i cell = new Vector3i(10, 60, 10);
        java.util.Set<Vector3i> one = java.util.Set.of(cell);
        SteamboatWater.Neighbour flow = new SteamboatWater.Neighbour(1, (byte) 3, false);
        SteamboatWater.Neighbour source = new SteamboatWater.Neighbour(2, (byte) 1, true);
        eq(source, PierDredge.chooseWater(one, null, n -> n.x == 11 ? flow : n.z == 11 ? source : null));
        eq(flow, PierDredge.chooseWater(one, null, n -> n.x == 11 ? flow : null));
        eq(flow, PierDredge.chooseWater(one, new Vector3i(0, 64, 0), n -> n.x == 0 ? flow : null));
        check(PierDredge.chooseWater(one, new Vector3i(0, 64, 0), n -> null) == null, "no water at all");
        check(PierDredge.chooseWater(java.util.Set.of(cell, new Vector3i(11, 60, 10)), null, n -> n.x == 11 && n.z == 10 ? source : null) == null,
                "a dug neighbour is not a water source");
    }

    /** the corridor of the shuttle in the frame of the pier: local x -8..8, z 1..20, surface - 1 to surface + 8. */
    static void quayCorridor() {
        Vector3i end = new Vector3i(50, 65, 50);
        int surface = 64;
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> y <= surface ? 1 : 0) == null, "water and air");
        // local (0, surface + 8, 20) rotated None is (50, 72, 70)
        eq(new Vector3i(50, 72, 70), PlaceQuayInteraction.corridorFault(end, Rotation.None, surface,
                (x, y, z) -> x == 50 && y == 72 && z == 70 ? 2 : 0));
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> x == 50 && y == 73 && z == 70 ? 2 : 0) == null,
                "above the corridor");
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> x == 50 && y == 62 && z == 70 ? 2 : 0) == null,
                "under the corridor");
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> z == 50 ? 2 : 0) == null, "the pier row itself");
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> z == 71 ? 2 : 0) == null, "beyond 20");
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> x == 59 ? 2 : 0) == null, "beyond 8");
        check(PlaceQuayInteraction.corridorFault(end, Rotation.None, surface, (x, y, z) -> x == 58 && z == 51 && y == 63 ? 2 : 0) != null, "edge");
        // turned a quarter: local +z leads to +x in the world (Ninety sends (x, z) to (z, -x))
        eq(new Vector3i(70, 65, 50), PlaceQuayInteraction.corridorFault(end, Rotation.Ninety, surface,
                (x, y, z) -> x == 70 && y == 65 && z == 50 ? 2 : 0));
        check(PlaceQuayInteraction.corridorFault(end, Rotation.Ninety, surface, (x, y, z) -> x == 30 && y == 65 && z == 50 ? 2 : 0) == null,
                "behind the pier");
        // every cell of the corridor is read once, 17 x 20 x 10
        int[] count = {0};
        PlaceQuayInteraction.corridorFault(end, Rotation.OneEighty, surface, (x, y, z) -> { count[0]++; return 0; });
        eq(17 * 20 * 10, count[0]);
        // the cells tested are those of StationGeometry.inBoatCorridor
        java.util.Set<Vector3i> seen = new java.util.HashSet<>();
        PlaceQuayInteraction.corridorFault(end, Rotation.Ninety, surface, (x, y, z) -> { seen.add(new Vector3i(x, y, z)); return 0; });
        for (Vector3i c : seen) {
            check(StationGeometry.inBoatCorridor(end, Rotation.Ninety, surface, c.x, c.y, c.z), "in corridor " + c);
        }
    }

    /** the vegetation that a pad clears: same rule as the generated stations. */
    static void quayVegetation() {
        for (String id : new String[]{"Plant_Grass_Tall", "*Plant_Grass_Tall", "Plant_Leaves_Oak", "Rubble_Stone", "Wood_Sticks",
                "Wood_Oak_Trunk", "Wood_Oak_Branch_Long", "Wood_Birch_Roots", "Deco_Nest_Small", "Deco_SpiderWeb_Corner"}) {
            check(StationPlacement.isVegetation(id), id + " is vegetation");
        }
        for (String id : new String[]{"Rock_Stone", "Soil_Grass", "Wood_Softwood_Planks", "Furniture_Desert_Lantern_Tall", null, ""}) {
            check(!StationPlacement.isVegetation(id), id + " is not vegetation");
        }
    }

    /** pier end plan aim: the aimed cell sits at local (0, y, -4), behind the land row of the deck (z -3) and outside it. */
    static void pierPlanOriginAim() {
        Vector3i aimed = new Vector3i(50, 64, 80);
        for (Rotation rotation : new Rotation[]{Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            Vector3i origin = DeployPlacement.pierPlanOrigin(aimed, rotation);
            Vector3i back = rotation.rotateYaw(new Vector3i(0, 0, -4), new Vector3i());
            eq(new Vector3i(origin).add(back), aimed);
            Vector3i land = rotation.rotateYaw(new Vector3i(0, 0, -3), new Vector3i());
            Vector3i landCell = new Vector3i(origin).add(land);
            check(Math.abs(landCell.x - aimed.x) + Math.abs(landCell.z - aimed.z) == 1 && landCell.y == aimed.y, "land row adjacent to the aimed cell " + rotation);
            for (int x = -1; x <= 2; x++) {
                for (int z = -3; z <= 0; z++) {
                    Vector3i cell = new Vector3i(origin).add(rotation.rotateYaw(new Vector3i(x, 0, z), new Vector3i()));
                    check(!cell.equals(aimed), "deck cell does not cover the aimed cell " + rotation);
                }
            }
        }
    }

    private static Vector3i seaward(Rotation r) {
        return r.rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
    }

    /** water at y <= 0 everywhere, solid blocks at y 1 on the given (x, z) cells. */
    private static DeployPlacement.CellState pierWorld(int[][] blocks) {
        return (x, y, z) -> {
            if (y <= 0) {
                return 1;
            }
            for (int[] b : blocks) {
                if (y == 1 && b[0] == x && b[1] == z) {
                    return 2;
                }
            }
            return 0;
        };
    }

    private static Rotation pierRotation(DeployPlacement.CellState state, Vector3i aimed, Rotation look) {
        return DeployPlacement.pierPlanRotation(state, new Vector3i(aimed).add(0, 1, 0), aimed, look);
    }

    /** the rotated deck of the pier end plan covers none of the given blocks. */
    private static void deckAvoids(DeployPlacement.CellState state, Vector3i aimed, Rotation r, String what) {
        Vector3i origin = DeployPlacement.pierPlanSeaOrigin(state, new Vector3i(aimed).add(0, 1, 0), r);
        check(origin != null, what + ": origin found");
        for (int x = -1; x <= 2; x++) {
            for (int z = -3; z <= 0; z++) {
                Vector3i c = new Vector3i(origin).add(r.rotateYaw(new Vector3i(x, 0, z), new Vector3i()));
                check(state.at(c.x, aimed.y, c.z) != 2, what + ": deck cell free");
            }
        }
    }

    /** pier end plan: the deck slabs take the rotation of an aimed half slab, else the unturned one. */
    static void pierDeckSlabRotationRule() {
        check(DeployPlacement.pierDeckSlabRotation(true, 0) == 0, "aimed slab unturned: 0");
        for (int i : new int[]{1, 2, 3}) {
            check(DeployPlacement.pierDeckSlabRotation(true, i) == i, "aimed flat slab " + i + ": same index");
        }
        for (int i : new int[]{4, 5, 6, 7, 8}) {
            check(DeployPlacement.pierDeckSlabRotation(true, i) == 0, "aimed slab on its side or upside down " + i + ": unturned");
        }
        for (int i : new int[]{0, 1, 2, 3, 6}) {
            check(DeployPlacement.pierDeckSlabRotation(false, i) == 0, "aimed other block " + i + ": unturned");
        }
        check(DeployPlacement.pierDeckSlabRotation(true, -1) == 0, "unreadable rotation: unturned");
        check(DeployPlacement.isPierDeckSlab("Wood_Hardwood_Planks_Half"), "hardwood slab is a deck slab");
        check(DeployPlacement.isPierDeckSlab("Wood_Oak_Planks_Half"), "renamed slab is a deck slab");
        check(!DeployPlacement.isPierDeckSlab("Wood_Hardwood_Planks"), "full planks are not");
        check(!DeployPlacement.isPierDeckSlab("Wood_Ash_Trunk"), "trunk is not");
        check(!DeployPlacement.isPierDeckSlab(null), "null is not");
    }

    /** pier end plan rotation: the player's pier decides, whatever the look. */
    static void pierPlanRotationFollowsPier() {
        Vector3i aimed = new Vector3i(0, 1, 0);
        // straight pier along +x ending at the aimed block
        DeployPlacement.CellState alongX = pierWorld(new int[][]{{-5, 0}, {-4, 0}, {-3, 0}, {-2, 0}, {-1, 0}, {0, 0}});
        // straight pier along -z (seaward is -z)
        DeployPlacement.CellState alongZ = pierWorld(new int[][]{{0, 5}, {0, 4}, {0, 3}, {0, 2}, {0, 1}, {0, 0}});
        for (Rotation look : ALL_ROTATIONS) {
            Rotation rx = pierRotation(alongX, aimed, look);
            eq(new Vector3i(1, 0, 0), seaward(rx));
            deckAvoids(alongX, aimed, rx, "along +x, look " + look);
            Rotation rz = pierRotation(alongZ, aimed, look);
            eq(new Vector3i(0, 0, -1), seaward(rz));
            deckAvoids(alongZ, aimed, rz, "along -z, look " + look);
        }
        // corner: blocks behind the aimed block toward -x and -z, so +x and +z both continue the pier, the look decides
        DeployPlacement.CellState corner = pierWorld(new int[][]{{-1, 0}, {0, -1}, {0, 0}});
        for (Rotation look : ALL_ROTATIONS) {
            Vector3i d = seaward(look);
            Rotation r = pierRotation(corner, aimed, look);
            Vector3i rd = seaward(r);
            check(rd.equals(new Vector3i(1, 0, 0)) || rd.equals(new Vector3i(0, 0, 1)), "corner: seaward is open water, look " + look);
            if (d.equals(new Vector3i(1, 0, 0)) || d.equals(new Vector3i(0, 0, 1))) {
                eq(look, r);
            }
        }
    }

    /** pier end plan rotation: no construction gives the closest candidate to the look, no water gives the look. */
    static void pierPlanRotationFallbacks() {
        Vector3i aimed = new Vector3i(0, 1, 0);
        DeployPlacement.CellState open = pierWorld(new int[0][]);
        for (Rotation look : ALL_ROTATIONS) {
            eq(look, pierRotation(open, aimed, look));
        }
        // a block in the line of the look: next candidate is a quarter turn forward, then back, then the opposite
        Rotation look = Rotation.None;
        Vector3i d = seaward(look);
        DeployPlacement.CellState blocked = pierWorld(new int[][]{{d.x * 2, d.z * 2}});
        eq(look.add(Rotation.Ninety), pierRotation(blocked, aimed, look));
        Vector3i d2 = seaward(look.add(Rotation.Ninety));
        DeployPlacement.CellState blocked2 = pierWorld(new int[][]{{d.x * 2, d.z * 2}, {d2.x * 2, d2.z * 2}});
        eq(look.add(Rotation.TwoSeventy), pierRotation(blocked2, aimed, look));
        // a block behind the aimed block makes that direction preferred over the closer candidates (the look included)
        Vector3i d3 = seaward(look.add(Rotation.TwoSeventy));
        DeployPlacement.CellState behind = pierWorld(new int[][]{{-d3.x, -d3.z}});
        eq(look.add(Rotation.TwoSeventy), pierRotation(behind, aimed, look));
        // no water: the look rotation
        for (Rotation r : ALL_ROTATIONS) {
            eq(r, pierRotation((x, y, z) -> y <= 0 ? 2 : 0, aimed, r));
        }
    }

    /** the pier end origin: the anchor is the pier end, y follows the water surface under it. */
    static void quayPierOrigin() {
        DeployPlacement.CellState water = (x, y, z) -> x == 10 && z == 10 ? (y <= 61 ? 1 : 0) : 0;
        eq(new Vector3i(10, 62, 10), DeployPlacement.boatStationOrigin(water, new Vector3i(10, 64, 10), Rotation.OneEighty, new int[]{0, 0, 0}));
        eq(null, DeployPlacement.boatStationOrigin((x, y, z) -> 0, new Vector3i(10, 64, 10), Rotation.None, new int[]{0, 0, 0}));
        eq(null, DeployPlacement.boatStationOrigin((x, y, z) -> y <= 64 ? 2 : 0, new Vector3i(10, 64, 10), Rotation.None, new int[]{0, 0, 0}));
        // the whole station reads the water at its own pier end, turned with the prefab
        int[] end = Deployables.BOAT_STATION_PIER_END;
        Vector3i rotated = Rotation.Ninety.rotateYaw(new Vector3i(end[0], end[1], end[2]), new Vector3i());
        DeployPlacement.CellState at = (x, y, z) -> x == 20 + rotated.x && z == 20 + rotated.z && y <= 55 ? 1 : 0;
        eq(new Vector3i(20, 56, 20), DeployPlacement.boatStationOrigin(at, new Vector3i(20, 58, 20), Rotation.Ninety));
    }

    /** a station that receives its quay joins the network and is saved with it. */
    static void quayJoinsNetwork() throws Exception {
        Path dir = Files.createTempDirectory("stations");
        try {
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            UUID owner = UUID.randomUUID();
            TransitNetwork.Station s = n.addPending("w", new Vector3i(5, 70, 9), 90f, owner, Deployables.STATION_KIND);
            check(n.stations("w", Deployables.STATION_KIND).isEmpty() && n.pending("w").contains(s), "pending only");
            check(n.setQuay(s, new Vector3i(12, 70, 9), Rotation.None), "quay set");
            check(n.stations("w", Deployables.STATION_KIND).contains(s) && n.pending("w").isEmpty(), "in the network");
            check(n.byNameWithQuay("w", s.name) == s, "destination lookup");
            check(s.origin.equals(new Vector3i(5, 70, 9)) && s.master.equals(new Vector3i(5, 70, 9)), "identity and master kept");
            check(!n.setQuay(s, new Vector3i(1, 1, 1), Rotation.Ninety), "second quay refused");
            check(s.quay.equals(new Vector3i(12, 70, 9)) && s.quayRotation == Rotation.None, "first quay kept");
            // a whole station moves the master
            TransitNetwork.Station f = n.addPending("w", new Vector3i(500, 70, 9), 0f, owner, Deployables.BOAT_STATION_KIND);
            check(n.setQuay(f, new Vector3i(530, 69, 20), Rotation.Ninety, new Vector3i(520, 70, 15), 45f), "whole station quay");
            check(f.master.equals(new Vector3i(520, 70, 15)) && f.masterYaw == 45f && f.origin.equals(new Vector3i(500, 70, 9)), "master moved");
            TransitNetwork r = new TransitNetwork();
            r.load(dir);
            TransitNetwork.Station s2 = r.byName("w", s.name);
            TransitNetwork.Station f2 = r.byName("w", f.name);
            check(s2 != null && s2.quay.equals(new Vector3i(12, 70, 9)) && s2.master.equals(new Vector3i(5, 70, 9)), "quay reloaded");
            check(f2 != null && f2.quay.equals(new Vector3i(530, 69, 20)) && f2.quayRotation == Rotation.Ninety
                    && f2.master.equals(new Vector3i(520, 70, 15)) && f2.masterYaw == 45f, "moved master reloaded");
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    /** naming a station that has its quay: its own automatic name stays valid, a taken or invalid name is refused. */
    static void quayRename() throws Exception {
        Path dir = Files.createTempDirectory("stations");
        try {
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            UUID owner = UUID.randomUUID();
            TransitNetwork.Station a = n.addPending("w", new Vector3i(0, 70, 0), 0f, owner, Deployables.STATION_KIND);
            TransitNetwork.Station b = n.addPending("w", new Vector3i(500, 70, 0), 0f, owner, Deployables.STATION_KIND);
            eq("Station 1", a.name);
            eq("Station 2", b.name);
            check(n.rename(a, "Station 1"), "own name");
            check(!n.rename(a, "station 2"), "taken name");
            check(!n.rename(a, "x"), "too short");
            check(n.rename(a, "  Harbour  "), "valid name");
            eq("Harbour", a.name);
            check(n.byName("w", "Harbour") == a && n.byName("w", "Station 1") == null, "lookup follows");
            TransitNetwork r = new TransitNetwork();
            r.load(dir);
            check(r.byName("w", "Harbour") != null, "name saved");
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    /** The arrival point plan: item JSON, texts, station choice and aimed cell. */
    static void arrivalPlanItem() throws Exception {
        String id = "Hotair_Balloon_Boat_Station_Plan_Arrival";
        String j = Files.readString(Path.of("Server/Item/Items/Containers/Vehicles", id + ".json"));
        var root = org.bson.BsonDocument.parse(j);
        check(!j.contains("\"Recipe\""), "no recipe");
        eq(1, root.getNumber("MaxStack").intValue());
        var step = root.getDocument("Interactions").getDocument("Secondary").getArray("Interactions").get(0).asDocument();
        eq(PlaceArrivalInteraction.TYPE_ID, step.getString("Type").getValue());
        eq("Transit_PlaceArrival", PlaceArrivalInteraction.TYPE_ID);
        check(!step.containsKey("PrefabPath"), "no prefab");
        eq(-1, step.getDocument("Next").getNumber("AdjustHeldItemQuantity").intValue());
        eq(QuayPlanPage.BOAT_ARRIVAL, id);
        String key = "orbis_horizon.items." + id.toLowerCase(java.util.Locale.ROOT);
        check(j.contains("server." + key + ".name") && j.contains("server." + key + ".description"), "text keys");
        for (String lang : new String[]{"en-US", "fr-FR"}) {
            String text = Files.readString(Path.of("Server/Languages/" + lang + "/server.lang"));
            check(text.contains(key + ".name = ") && text.contains(key + ".description = "), lang + " item texts");
            for (String k : new String[]{"notOwner", "noQuay", "badSpot", "tooFar", "placed", "planGiven", "planGivenDropped", "planFailed", "noStation", "noTarget"}) {
                check(text.contains("orbis_horizon.chat.transit.arrival." + k + " = "), lang + " " + k);
            }
        }
        // Aimed block plus one: the cell above it.
        eq(new Vector3i(4, 11, 6), PlaceArrivalInteraction.cellAbove(new Vector3i(4, 10, 6)));
        // Nearest maritime station within reach, the other kinds are ignored.
        java.util.UUID me = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        TransitNetwork.Station far = dropStation(Deployables.BOAT_STATION_KIND, new Vector3i(40, 10, 0), null, me);
        TransitNetwork.Station near = dropStation(Deployables.BOAT_STATION_KIND, new Vector3i(10, 10, 0), null, other);
        TransitNetwork.Station balloon = dropStation(Deployables.STATION_KIND, new Vector3i(2, 10, 0), null);
        List<TransitNetwork.Station> all = List.of(far, balloon, near);
        org.joml.Vector3d at = new org.joml.Vector3d(0, 10, 0);
        check(PlaceArrivalInteraction.nearestBoatStation(all, at, 48.0, null, false) == near, "nearest boat station");
        check(PlaceArrivalInteraction.nearestBoatStation(all, at, 5.0, null, false) == null, "none in reach");
        check(PlaceArrivalInteraction.nearestBoatStation(List.of(balloon), at, 48.0, null, false) == null, "balloon station ignored");
        // The player's own station wins over a nearer neighbour's, an operator takes the nearest, no own station falls back to the nearest.
        check(PlaceArrivalInteraction.nearestBoatStation(all, at, 48.0, me, false) == far, "own station preferred");
        check(PlaceArrivalInteraction.nearestBoatStation(all, at, 48.0, me, true) == near, "operator takes the nearest");
        check(PlaceArrivalInteraction.nearestBoatStation(all, at, 48.0, java.util.UUID.randomUUID(), false) == near, "no own station, nearest other");
    }

    /** the four plan items: no recipe, the interaction with its fields, texts and the held-plan preview table. */
    static void quayPlanItems() throws Exception {
        String[][] plans = {
                {"Hotair_Balloon_Station_Plan_Pad", "station", "Pad"},
                {"Hotair_Balloon_Station_Plan_Full", "station", "Full"},
                {"Hotair_Balloon_Boat_Station_Plan_Pier", "boat_station", "Pad"},
                {"Hotair_Balloon_Boat_Station_Plan_Full", "boat_station", "Full"}};
        String en = Files.readString(Path.of("Server/Languages/en-US/server.lang"));
        String fr = Files.readString(Path.of("Server/Languages/fr-FR/server.lang"));
        for (String[] plan : plans) {
            String id = plan[0];
            String j = Files.readString(Path.of("Server/Item/Items/Containers/Vehicles", id + ".json"));
            check(!j.contains("\"Recipe\""), id + " has no recipe");
            check(j.contains("\"Transit_PlaceQuay\""), id + " interaction");
            check(j.contains("\"StationKind\": \"" + plan[1] + "\""), id + " kind");
            check(j.contains("\"Model\": \"" + plan[2] + "\""), id + " model");
            check(j.contains("\"AdjustHeldItemQuantity\": -1"), id + " consumed");
            String key = "orbis_horizon.items." + id.toLowerCase(java.util.Locale.ROOT);
            check(j.contains("server." + key + ".name") && j.contains("server." + key + ".description"), id + " text keys");
            for (String lang : new String[]{en, fr}) {
                check(lang.contains(key + ".name = ") && lang.contains(key + ".description = "), id + " lang");
            }
            DeployPlacement.Crate c = DeployPlacement.CRATES.get(id);
            check(c != null, id + " in the preview table");
            Deployables.Kind kind = PlaceQuayInteraction.structureKind(plan[1], plan[2]);
            eq(kind.prefabPath, c.prefabPath());
        }
        eq(QuayPlanPage.BALLOON_PAD, plans[0][0]);
        eq(QuayPlanPage.BALLOON_FULL, plans[1][0]);
        eq(QuayPlanPage.BOAT_PIER, plans[2][0]);
        eq(QuayPlanPage.BOAT_FULL, plans[3][0]);
        for (String k : new String[]{"noStation", "noTarget", "notOwner", "already", "tooFar", "blocked", "notLoaded", "corridor"}) {
            check(en.contains("orbis_horizon.chat.transit.quay." + k + " = ") && fr.contains("orbis_horizon.chat.transit.quay." + k + " = "),
                    "lang transit.quay." + k);
        }
        String beacon = Files.readString(Path.of("Server/Beacon/project.json"));
        check(beacon.split("station.quayPlaced", -1).length == 3, "counter declared in the list and in the schema");
    }

    static void pendingStationPersists() throws Exception {
        Path dir = Files.createTempDirectory("stations");
        try {
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            UUID owner = UUID.randomUUID();
            TransitNetwork.Station s = n.addPending("w", new Vector3i(5, 70, 9), 90f, owner, Deployables.BOAT_STATION_KIND);
            eq("Station 1", s.name);
            check(s.quay == null && s.editable && !s.fullPrefab && s.origin.equals(s.master), "pending shape");
            check(n.byNameWithQuay("w", "Station 1") == null && n.byName("w", "Station 1") == s, "no quay lookup");
            check(n.addPending("w", new Vector3i(500, 70, 9), 0f, owner, Deployables.STATION_KIND).name.equals("Station 2"), "next auto name");
            TransitNetwork r = new TransitNetwork();
            r.load(dir);
            TransitNetwork.Station s2 = r.byName("w", "Station 1");
            check(s2 != null && s2.quay == null && s2.master.equals(new Vector3i(5, 70, 9)) && s2.masterYaw == 90f
                    && owner.equals(s2.owner) && s2.kind.equals(Deployables.BOAT_STATION_KIND), "pending reloaded");
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    static void geometryHelpers() {
        Vector3i q = new Vector3i(10, 70, 10);
        check(StationGeometry.zoneBottomY(Deployables.STATION_KIND, q) == 63, "zone balloon");
        check(StationGeometry.zoneBottomY(Deployables.BOAT_STATION_KIND, q) == 60, "zone boat");
        check(StationGeometry.berthRotation(Rotation.None, false) == Deployables.BOAT_STATION_BERTH_ROTATION, "berth rotation");
        check(StationGeometry.berthRotation(Rotation.None, true) == Deployables.BOAT_STATION_BERTH_ROTATION.add(Rotation.OneEighty), "berth flipped");
    }

    // ---------------------------------------------------------------- final review fixes

    /** Geometry of a station reads quayRotation, never the identity rotation (a player station has the identity rotation None). */
    static void stationGeometryUsesQuayRotation() {
        for (Rotation q : new Rotation[] {Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), Rotation.None, null, UUID.randomUUID(),
                    new ArrayList<>(), Deployables.BOAT_STATION_KIND, false, true, new Vector3i(98, 64, 205), 0f, new Vector3i(100, 64, 200), q);
            Vector3i berth = BoatBerth.berthCentreCell(s);
            eq(BoatBerth.preBerthCell(q, berth, BoatBerth.BERTH_STANDOFF), BoatBerth.preBerthCell(s));
            int[] want = BoatQuay.approachSpawn(q, berth);
            int[] got = BoatQuay.approachSpawn(s);
            eq(want[0], got[0]);
            eq(want[1], got[1]);
            check(!BoatBerth.preBerthCell(s).equals(BoatBerth.preBerthCell(Rotation.None, berth, BoatBerth.BERTH_STANDOFF)),
                    "the pre-berth point follows the quay rotation " + q);
        }
    }

    /** A station whose name window is open is outside the network until the flag is cleared. */
    static void namingStationOutOfNetwork() {
        TransitNetwork n = new TransitNetwork();
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(1, 64, 1), 0f, new Vector3i(0, 64, 0), Rotation.None);
        TransitNetwork.Station b = new TransitNetwork.Station("B", "w", new Vector3i(200, 64, 0), Rotation.None, null, null,
                new ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(201, 64, 1), 0f, new Vector3i(200, 64, 0), Rotation.None);
        n.add(a);
        n.add(b);
        eq(2, n.stations("w", Deployables.STATION_KIND).size());
        b.naming = true;
        eq(List.of(a), n.stations("w", Deployables.STATION_KIND));
        check(n.byNameWithQuay("w", "B") == null, "a naming station is not a destination");
        check(n.byName("w", "B") == b, "but it exists");
        check(!b.inNetwork() && a.inNetwork(), "inNetwork");
        b.naming = false;
        eq(2, n.stations("w", Deployables.STATION_KIND).size());
        check(n.byNameWithQuay("w", "B") == b, "named: a destination again");
        TransitNetwork.Station pending = n.addPending("w", new Vector3i(50, 64, 50), 0f, UUID.randomUUID(), Deployables.STATION_KIND);
        check(!pending.inNetwork(), "a pending station is not in the network");
    }

    static void firstContractDefaultWorldOnly() {
        check(StationContracts.isDefaultWorld("main", "main"), "default world");
        check(!StationContracts.isDefaultWorld("main", "other"), "another world");
        check(StationContracts.isDefaultWorld(null, "other"), "no default world known");
    }

    static void givenResultFromCrate() {
        eq(StationContracts.Given.GIVEN, StationContracts.givenFrom(true));
        eq(StationContracts.Given.DROPPED, StationContracts.givenFrom(false));
        check(StationContracts.Given.DROPPED.reached() && StationContracts.Given.GIVEN.reached() && !StationContracts.Given.FAILED.reached(),
                "reached");
    }

    static void lockRefusedOnPlayerStation() {
        TransitNetwork.Station player = new TransitNetwork.Station("P", "w", new Vector3i(0, 64, 0), Rotation.None, null, UUID.randomUUID(),
                new ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(1, 64, 1), 0f, new Vector3i(0, 64, 0), Rotation.None);
        TransitNetwork.Station generated = new TransitNetwork.Station("G", "w", new Vector3i(0, 64, 0), Rotation.None, null, null,
                new ArrayList<>(), Deployables.STATION_KIND, true, true, new Vector3i(1, 64, 1), 0f, new Vector3i(0, 64, 0), Rotation.None);
        eq("transit.cmd.lockPlayerStation", StationCommand.lockRefusal(player, false));
        check(StationCommand.lockRefusal(player, true) == null, "unlock a player station");
        check(StationCommand.lockRefusal(generated, false) == null, "lock a generated station");
        check(StationCommand.lockRefusal(generated, true) == null, "unlock a generated station");
    }

    /** What despawn takes down from an editable whole-prefab station: the pad layer of 7 x 7 only. */
    static void despawnCoreShapeBalloon() {
        TransitNetwork.Station s = new TransitNetwork.Station("G", "w", new Vector3i(0, 64, 0), Rotation.Ninety, null, null,
                new ArrayList<>(), Deployables.STATION_KIND, true, true, new Vector3i(1, 64, 1), 0f, new Vector3i(10, 66, 10), Rotation.Ninety);
        Vector3i origin = new Vector3i(10, 60, 10);
        List<StructureShape.Cell> cells = new ArrayList<>();
        // local cells that land on the quay layer (y 66) around (10, 10) once turned by Ninety, and cells above and beside
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                Vector3i l = Rotation.Ninety.toInverse().rotateYaw(new Vector3i(dx, 0, dz), new Vector3i());
                cells.add(new StructureShape.Cell(l.x, 6, l.z, "Rock_Basalt_Brick"));
            }
        }
        cells.add(new StructureShape.Cell(0, 7, 0, "Furniture_Village_Brazier"));
        cells.add(new StructureShape.Cell(0, 2, 0, "Soil_Grass"));
        StructureShape shape = StructureShape.ofCells(cells);
        StructureShape core = StationCommand.quayCoreShape(s, shape, Rotation.Ninety, origin);
        eq(49, core.cells().size());
        for (StructureShape.Cell c : core.cells()) {
            Vector3i p = c.rotated(Rotation.Ninety).add(origin);
            check(p.y == 66 && Math.abs(p.x - 10) <= 3 && Math.abs(p.z - 10) <= 3, "core cell " + p);
        }
    }

    static void stationArrivalRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("stations-arrival");
        try {
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            TransitNetwork.Station s = n.addPending("w", new Vector3i(1, 70, 2), 0f, UUID.randomUUID(), Deployables.BOAT_STATION_KIND);
            check(s.arrival == null, "no arrival by default");
            Vector3i cell = new Vector3i(10, 65, -4);
            n.setArrival(s, cell);
            cell.x = 99;
            eq(new Vector3i(10, 65, -4), s.arrival);
            TransitNetwork reloaded = new TransitNetwork();
            reloaded.load(dir);
            eq(1, reloaded.allStations().size());
            eq(new Vector3i(10, 65, -4), reloaded.allStations().get(0).arrival);
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    static void stationArrivalMissing() throws Exception {
        Path dir = Files.createTempDirectory("stations-arrival-missing");
        try {
            String v2 = "{\"version\":2,\"stations\":["
                    + "{\"name\":\"B\",\"world\":\"w\",\"x\":-5,\"y\":62,\"z\":7,\"rotation\":\"None\",\"kind\":\"boat_station\","
                    + "\"master\":{\"x\":1,\"y\":2,\"z\":3},\"quay\":{\"x\":4,\"y\":5,\"z\":6},\"quayRotation\":\"None\","
                    + "\"editable\":true,\"fullPrefab\":false,\"queue\":[]}]}";
            Files.writeString(dir.resolve("stations.json"), v2);
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            check(!n.loadFailed, "the file is not rejected");
            check(n.allStations().get(0).arrival == null, "arrival absent reads as null");
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    static void stationsFileV2WithoutMaster() throws Exception {
        Path dir = Files.createTempDirectory("stations-v2");
        try {
            Path f = dir.resolve("stations.json");
            String v2 = "{\"version\":2,\"stations\":["
                    + "{\"name\":\"A\",\"world\":\"w\",\"x\":10,\"y\":64,\"z\":20,\"rotation\":\"Ninety\",\"kind\":\"station\",\"queue\":[]},"
                    + "{\"name\":\"B\",\"world\":\"w\",\"x\":-5,\"y\":62,\"z\":7,\"rotation\":\"None\",\"kind\":\"boat_station\","
                    + "\"master\":{\"x\":1,\"y\":2,\"z\":3},\"quay\":{\"x\":4,\"y\":5,\"z\":6},\"quayRotation\":\"None\","
                    + "\"editable\":true,\"fullPrefab\":false,\"queue\":[]}]}";
            Files.writeString(f, v2);
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            check(!n.loadFailed, "the file is not rejected");
            eq(2, n.allStations().size());
            TransitNetwork.Station a = n.allStations().get(0);
            TransitNetwork.Station b = n.allStations().get(1);
            StationGeometry.Converted ca = StationGeometry.convert(Deployables.STATION_KIND, new Vector3i(10, 64, 20), Rotation.Ninety);
            check(a.master.equals(ca.master()) && a.quay.equals(ca.quay()) && a.quayRotation == Rotation.Ninety && a.fullPrefab,
                    "A converted from its prefab");
            check(b.master.equals(new Vector3i(1, 2, 3)) && b.quay.equals(new Vector3i(4, 5, 6)) && !b.fullPrefab && b.editable,
                    "B read as written");
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    static void stationsFileV1Converted() throws Exception {
        Path dir = Files.createTempDirectory("stations");
        try {
            UUID owner = UUID.randomUUID();
            Path f = dir.resolve("stations.json");
            String v1 = "{\"version\":1,\"stations\":["
                    + "{\"name\":\"A\",\"world\":\"w\",\"x\":10,\"y\":64,\"z\":20,\"rotation\":\"Ninety\",\"owner\":\"" + owner + "\",\"kind\":\"station\",\"queue\":[]},"
                    + "{\"name\":\"B\",\"world\":\"w\",\"x\":-5,\"y\":62,\"z\":7,\"rotation\":\"Ninety\",\"kind\":\"boat_station\",\"queue\":[]}]}";
            Files.writeString(f, v1);
            TransitNetwork n = new TransitNetwork();
            n.load(dir);
            TransitNetwork.Station a = n.allStations().get(0);
            TransitNetwork.Station b = n.allStations().get(1);
            StationGeometry.Converted ca = StationGeometry.convert(Deployables.STATION_KIND, new Vector3i(10, 64, 20), Rotation.Ninety);
            StationGeometry.Converted cb = StationGeometry.convert(Deployables.BOAT_STATION_KIND, new Vector3i(-5, 62, 7), Rotation.Ninety);
            check(a.master.equals(ca.master()) && a.quay.equals(ca.quay()) && a.quayRotation == Rotation.Ninety, "A converted");
            check(b.master.equals(cb.master()) && b.quay.equals(cb.quay()) && b.quayRotation == Rotation.Ninety, "B converted");
            check(a.masterYaw == ca.masterYaw(), "A yaw");
            check(a.editable && !b.editable, "editable");
            check(a.fullPrefab && b.fullPrefab, "fullPrefab");
            n.save();
            check(Files.isRegularFile(dir.resolve("stations.json.v1.bak")), "backup");
            check(Files.readString(dir.resolve("stations.json.v1.bak")).equals(v1), "backup is the v1 file");
            check(org.bson.BsonDocument.parse(Files.readString(f)).getInt32("version").getValue() == 2, "version 2");
            TransitNetwork r = new TransitNetwork();
            r.load(dir);
            TransitNetwork.Station a2 = r.allStations().get(0);
            TransitNetwork.Station b2 = r.allStations().get(1);
            check(a2.master.equals(a.master) && a2.quay.equals(a.quay) && a2.quayRotation == a.quayRotation
                    && a2.masterYaw == a.masterYaw && a2.editable && a2.fullPrefab, "A reloaded");
            check(b2.master.equals(b.master) && b2.quay.equals(b.quay) && !b2.editable && b2.fullPrefab, "B reloaded");
        } finally {
            try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
            Files.deleteIfExists(dir);
        }
    }

    static void bookFileRoundTripAndBad() throws Exception {
        Path dir = Files.createTempDirectory("book");
        Path f = dir.resolve("sea-chart.json");
        try {
            SeaChartBook a = new SeaChartBook();
            a.save(); // no file yet: nothing happens
            check(!Files.exists(f), "save sans load");
            a.load(f);
            SeaChartBook.Trip t = a.startTrip("w", "A", "B", 100);
            for (int x = 0; x <= 100; x += 2) t.note(x, 0);
            a.commit(t);
            a.close("w", 40, 40, 5000L);
            a.markIncomplete("w", "A", "B");
            check(!Files.exists(f), "aucune écriture avant save");
            a.save();
            check(Files.isRegularFile(f), "fichier écrit");
            SeaChartBook r = new SeaChartBook();
            r.load(f);
            eq(a.segments("w"), r.segments("w"));
            eq(2, r.segments("w").size());
            check(r.closed("w", 40, 40, 5000L), "fermeture relue");
            check(r.incomplete("w", "A", "B") && !r.incomplete("w", "B", "A"), "paire relue");
            Files.writeString(f, "pas du json");
            SeaChartBook bad = new SeaChartBook();
            bad.load(f);
            eq(0, bad.segments("w").size());
            check(!Files.exists(f), "fichier retiré");
            check(Files.isRegularFile(dir.resolve("sea-chart.json.bad")), "renommé en .bad");
        } finally {
            try (var s = Files.list(dir)) {
                for (Path p : s.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    static void bookChangeListenerAndCap() {
        SeaChartBook b = new SeaChartBook();
        int[] calls = {0};
        b.setChangeListener(() -> calls[0]++);
        SeaChartBook.Trip t = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 100; x += 2) t.note(x, 0);
        b.commit(t);
        eq(1, calls[0]);
        b.commit(b.startTrip("w", "A", "B", 100));
        eq(1, calls[0]); // nothing new
        b.close("w", 40, 40, 0L);
        eq(2, calls[0]);
        b.crossed("w", 7, 7);
        eq(2, calls[0]); // the cell was not closed
        b.crossed("w", 40, 40);
        eq(3, calls[0]);
        b.markIncomplete("w", "A", "B");
        b.markIncomplete("w", "A", "B");
        eq(4, calls[0]);
        b.clearIncomplete("w", "A", "B");
        eq(5, calls[0]);
        SeaChartBook.Trip u = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 500; x += 5) u.note(x, 0);
        u.setMaxSegments(3);
        eq(3, u.pending());
    }

    static void seaChartsBookSavedByItsThread() throws Exception {
        Path dir = Files.createTempDirectory("seabook");
        Path f = dir.resolve("sea-chart.json");
        try {
            SeaCharts.start(dir);
            SeaChartBook b = SeaCharts.book();
            for (int i = 0; i < 5; i++) {
                b.markIncomplete("seaw", "A", "B" + i);
            }
            long end = System.currentTimeMillis() + 5000;
            while (!Files.isRegularFile(f) && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
            check(Files.isRegularFile(f), "écrit par le fil de la carte");
            b.markIncomplete("seaw", "A", "late");
            SeaCharts.stop();
            SeaChartBook r = new SeaChartBook();
            r.load(f);
            check(r.incomplete("seaw", "A", "B4") && r.incomplete("seaw", "A", "late"), "écrit à l'arrêt");
            SeaCharts.start(dir);
            check(SeaCharts.book().incomplete("seaw", "A", "B0"), "relu au démarrage");
            SeaCharts.stop();
        } finally {
            SeaCharts.book().reset();
            try (var s = Files.list(dir)) {
                for (Path p : s.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    static void localNavFrozenNearBerth() {
        check(BoatAutopilot.localNavFrozen(79), "79 figé");
        check(BoatAutopilot.localNavFrozen(BoatAutopilot.ARRIVAL_DECIDE_RANGE), "80 figé");
        check(!BoatAutopilot.localNavFrozen(81), "81 libre");
    }

    static void shuttleTripRecorded() {
        SeaChartBook b = new SeaChartBook();
        b.markIncomplete("w", "A", "B");
        SeaChartBook.Trip t = b.startTrip("w", "A", "B", 100);
        for (int x = 0; x <= 100; x += 2) t.note(x, 0);
        eq(0, b.segments("w").size());
        ShuttleNav.tripArrived(b, t);
        eq(2, b.segments("w").size());
        check(!b.incomplete("w", "A", "B"), "arrivée : paire complète");

        SeaChartBook.Trip u = b.startTrip("w", "A", "C", 100);
        for (int x = 0; x <= 100; x += 2) u.note(x, 300);
        ShuttleNav.tripFailed(b, u);
        eq(2, b.segments("w").size());
        eq(0, u.pending());
        check(b.incomplete("w", "A", "C"), "secours : paire incomplète");

        SeaChartBook.Trip v = b.startTrip("w", "A", "D", 100);
        for (int x = 0; x <= 100; x += 2) v.note(x, 600);
        ShuttleNav.tripRemoved(b, v);
        eq(2, b.segments("w").size());
        eq(0, v.pending());
        check(!b.incomplete("w", "A", "D"), "retrait : rien noté");

        // The same decisions taken from the state of a shuttle, on the shared book.
        SeaChartBook shared = SeaCharts.book();
        ShuttleState st = new ShuttleState(null, null, List.of(), ShuttleState.Phase.SAIL, 0);
        st.trip = shared.startTrip("tripw", "A", "B", 100);
        for (int x = 0; x <= 100; x += 2) st.trip.note(x, 0);
        ShuttleNav.arrived(st);
        eq(2, shared.segments("tripw").size());
        check(st.trip == null && st.nav == null, "navigation terminée");
        st.trip = shared.startTrip("tripw", "A", "C", 100);
        ShuttleNav.failed(st);
        check(shared.incomplete("tripw", "A", "C") && st.trip == null, "échec noté");
        st.trip = shared.startTrip("tripw", "A", "E", 100);
        ShuttleNav.removed(st);
        check(!shared.incomplete("tripw", "A", "E") && st.trip == null, "retrait sans note");
        shared.clearIncomplete("tripw", "A", "C");
    }

    static void shuttleNavWaitIsBounded() {
        // A load that never completes: the wait gives up at the stall time.
        long t0 = 1000L;
        check(ShuttleNav.waitDecision(false, false, 0, t0, t0) == ShuttleNav.Wait.LOAD, "premier chargement");
        check(ShuttleNav.waitDecision(true, false, 1, t0, t0 + 1000) == ShuttleNav.Wait.WAIT, "chargement en cours");
        check(ShuttleNav.waitDecision(true, false, 1, t0, t0 + BoatBerth.STALL_MS + 1) == ShuttleNav.Wait.GIVE_UP, "jamais terminé");
        // Loads that finish but leave the columns unknown: gives up after MAX_LOAD_ATTEMPTS loads, whatever the time.
        int attempts = 0;
        int ticks = 0;
        boolean gaveUp = false;
        boolean pending = false;
        while (ticks++ < 100 && !gaveUp) {
            switch (ShuttleNav.waitDecision(pending, pending, attempts, t0, t0 + ticks)) {
                case LOAD -> {
                    attempts++;
                    pending = true;
                }
                case DATA_LOADED -> pending = false;
                case GIVE_UP -> gaveUp = true;
                default -> {
                }
            }
        }
        check(gaveUp, "abandon");
        eq(BoatBerth.MAX_LOAD_ATTEMPTS, attempts);
        check(ShuttleNav.beforeDeparture(ShuttleState.Phase.SAIL, 5), "au poste en SAIL");
        check(!ShuttleNav.beforeDeparture(ShuttleState.Phase.SAIL, 30), "parti");
        check(!ShuttleNav.beforeDeparture(ShuttleState.Phase.HOLD, 5), "pas en SAIL");
    }

    static void shuttleNavHelpers() {
        List<int[]> l = List.of(new int[] {0, 0}, new int[] {40, 0}, new int[] {80, 0});
        List<int[]> c = ShuttleNav.completed(l, 1, new int[] {100, 0});
        eq(3, c.size());
        eq(40, c.get(0)[0]);
        eq(100, c.get(2)[0]);
        eq(2, ShuttleNav.completed(l, 1, new int[] {80, 0}).size()); // already ends at the berth
        eq(1, ShuttleNav.completed(List.of(), 0, new int[] {5, 5}).size());
        eq(3, l.size());
        eq(80, ShuttleNav.corridorBlocks(l));
        check(!ShuttleNav.loadWaitExpired(2, 1000L, 30_000L), "attente en cours");
        check(ShuttleNav.loadWaitExpired(BoatBerth.MAX_LOAD_ATTEMPTS, 1000L, 2000L), "assez d'essais");
        check(ShuttleNav.loadWaitExpired(1, 1000L, 1000L + BoatBerth.STALL_MS + 1), "trop long");
        check(ShuttleNav.beforeDeparture(ShuttleNav.DEPARTURE_RANGE), "au poste");
        check(!ShuttleNav.beforeDeparture(ShuttleNav.DEPARTURE_RANGE + 1), "parti");
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
        // pier end column (111, 199): sea surface at 114 gives origin 115, a lower pool (air at 114, water at 113) gives 114
        org.joml.Vector3i computed = new org.joml.Vector3i(100, SeaRules.SEA_LEVEL, 200);
        DeployPlacement.CellState open = (cx, cy, cz) -> cy <= 114 && cy >= 100 ? 1 : (cy < 100 ? 2 : 0);
        DeployPlacement.CellState pool = (cx, cy, cz) -> cy <= 113 && cy >= 100 ? 1 : (cy < 100 ? 2 : 0);
        check(SeaRules.atSeaLevel(DeployPlacement.boatStationOrigin(open, computed, Rotation.Ninety)), "sea surface: accepted");
        check(!SeaRules.atSeaLevel(DeployPlacement.boatStationOrigin(pool, computed, Rotation.Ninety)), "lower pool: refused");
    }

    /** One row of the given runs: a character repeated for a number of 16 block cells. */
    private static GridProbe strip(Object... runs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length; i += 2) {
            sb.append(String.valueOf(runs[i]).repeat((Integer) runs[i + 1]));
        }
        return new GridProbe(sb.toString());
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
        TestSupport.installApi();
        run("apiRegisterUsesConvertedGeometry", TransitTests::apiRegisterUsesConvertedGeometry);
        run("padShapeMatchesGeometry", TransitTests::padShapeMatchesGeometry);
        run("pierShapeInsideCore", TransitTests::pierShapeInsideCore);
        run("pierDeckPostsAndLegacy", TransitTests::pierDeckPostsAndLegacy);
        run("previewShuttleMerge", TransitTests::previewShuttleMerge);
        run("airshipCaptainChest", TransitTests::airshipCaptainChest);
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
        run("boatStationHutFoundation", TransitTests::boatStationHutFoundation);
        run("refusalCarriesHullFlag", TransitTests::refusalCarriesHullFlag);
        run("emptiedCellsReadAsEmpty", TransitTests::emptiedCellsReadAsEmpty);
        run("dredgedCellsReadAsWater", TransitTests::dredgedCellsReadAsWater);
        run("roomRefusalKeysUnchanged", TransitTests::roomRefusalKeysUnchanged);
        run("apiConstantsMatchInternals", TransitTests::apiConstantsMatchInternals);
        run("apiKindsGeometry", TransitTests::apiKindsGeometry);
        run("apiKindsCellsFromShape", TransitTests::apiKindsCellsFromShape);
        run("apiTerrainRules", TransitTests::apiTerrainRules);
        run("apiRefusalRecord", TransitTests::apiRefusalRecord);
        run("apiInstallReplacesState", TransitTests::apiInstallReplacesState);
        runQuiet("extensionsHooksNeverThrow", TransitTests::extensionsHooksNeverThrow);
        runQuiet("extensionRegisterTwice", TransitTests::extensionRegisterTwice);
        runQuiet("extensionRowsSanitized", TransitTests::extensionRowsSanitized);
        runQuiet("extensionDeliversStartedWorldsOnce", TransitTests::extensionDeliversStartedWorldsOnce);
        runQuiet("extensionDeliveryClaimedOnce", TransitTests::extensionDeliveryClaimedOnce);
        runQuiet("extensionSubCommandsNullsDropped", TransitTests::extensionSubCommandsNullsDropped);
        run("usageCountersRegister", TransitTests::usageCountersRegister);
        run("blocksRoomPredicateOnlyWhenSolid", TransitTests::blocksRoomPredicateOnlyWhenSolid);
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
        run("contractPriceEssencesFirst", TransitTests::contractPriceEssencesFirst);
        run("firstContractOnlyOnce", TransitTests::firstContractOnlyOnce);
        run("firstContractOwnerGate", TransitTests::firstContractOwnerGate);
        run("contractItems", TransitTests::contractItems);
        runQuiet("contractPriceSettings", TransitTests::contractPriceSettings);
        runQuiet("stationCratesKeyIgnored", TransitTests::stationCratesKeyIgnored);
        runQuiet("firstContractPersisted", TransitTests::firstContractPersisted);
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
        run("fareSetting", TransitTests::fareSetting);
        run("ownedMarkerSharingSwitch", TransitTests::ownedMarkerSharingSwitch);
        run("knownStationsSharingSwitch", TransitTests::knownStationsSharingSwitch);
        run("settingsDisabledRecipes", TransitTests::settingsDisabledRecipes);
        run("recipeTogglesWithoutUniverse", TransitTests::recipeTogglesWithoutUniverse);
        runQuiet("stationRemovalStepsAreIndependent", TransitTests::stationRemovalStepsAreIndependent);
        runQuiet("networkWorksWithoutExtension", TransitTests::networkWorksWithoutExtension);
        run("configSearch", TransitTests::configSearch);
        run("consoleLogsNames", TransitTests::consoleLogsNames);
        run("consoleLogLevelSetting", TransitTests::consoleLogLevelSetting);
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
        run("arrivalButtonVisibility", TransitTests::arrivalButtonVisibility);
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
        run("campPackCrouchRule", TransitTests::campPackCrouchRule);
        run("packChestsEmpty", TransitTests::packChestsEmpty);
        run("packChestsBigOrder", TransitTests::packChestsBigOrder);
        run("packChestsRoundTrip", TransitTests::packChestsRoundTrip);
        run("campRecipesJson", TransitTests::campRecipesJson);
        run("explorerBenchJson", TransitTests::explorerBenchJson);
        run("recipesOnExplorerBench", TransitTests::recipesOnExplorerBench);
        run("creativeVisibility", TransitTests::creativeVisibility);
        run("oxygenHud", TransitTests::oxygenHud);
        run("fuelHud", TransitTests::fuelHud);
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
        run("masterTooCloseSameKindOnly", TransitTests::masterTooCloseSameKindOnly);
        run("pendingStationPersists", TransitTests::pendingStationPersists);
        run("stationDistanceUsesGeometry", TransitTests::stationDistanceUsesGeometry);
        run("quayRefusals", TransitTests::quayRefusals);
        run("quayNearestPending", TransitTests::quayNearestPending);
        run("quayStructureKind", TransitTests::quayStructureKind);
        run("quayPadVolume", TransitTests::quayPadVolume);
        run("quayCorridor", TransitTests::quayCorridor);
        run("pierDredge", TransitTests::pierDredge);
        run("quayVegetation", TransitTests::quayVegetation);
        run("quayPierOrigin", TransitTests::quayPierOrigin);
        run("pierPlanOriginAim", TransitTests::pierPlanOriginAim);
        run("pierPlanRotationFollowsPier", TransitTests::pierPlanRotationFollowsPier);
        run("pierDeckSlabRotationRule", TransitTests::pierDeckSlabRotationRule);
        run("pierPlanRotationFallbacks", TransitTests::pierPlanRotationFallbacks);
        run("quayJoinsNetwork", TransitTests::quayJoinsNetwork);
        run("quayRename", TransitTests::quayRename);
        run("quayPlanItems", TransitTests::quayPlanItems);
        run("arrivalPlanItem", TransitTests::arrivalPlanItem);
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
        run("hoverAboveHighestBlock", TransitTests::hoverAboveHighestBlock);
        run("highestSolidColumns", TransitTests::highestSolidColumns);
        run("stationPositionFallback", TransitTests::stationPositionFallback);
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
        run("iceHullFlags", TransitTests::iceHullFlags);
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
        run("waterLogCollapse", TransitTests::waterLogCollapse);
        run("voyageCorridorFromLog", TransitTests::voyageCorridorFromLog);
        run("voyageCorridorEndsAtGoal", TransitTests::voyageCorridorEndsAtGoal);
        run("voyageCorridorReadsLogThenChart", TransitTests::voyageCorridorReadsLogThenChart);
        run("voyageChartCorridor", TransitTests::voyageChartCorridor);
        run("voyageTrackBackoff", TransitTests::voyageTrackBackoff);
        run("voyageFinalInsideWindow", TransitTests::voyageFinalInsideWindow);
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
        run("localNavFollowsCorridor", TransitTests::localNavFollowsCorridor);
        run("localNavGoalOnShore", TransitTests::localNavGoalOnShore);
        run("localNavWidens", TransitTests::localNavWidens);
        run("localNavClosesAndRetraces", TransitTests::localNavClosesAndRetraces);
        run("localNavFailsWithoutProgress", TransitTests::localNavFailsWithoutProgress);
        run("localNavFailsWithoutCorridor", TransitTests::localNavFailsWithoutCorridor);
        run("localNavUnknownWaits", TransitTests::localNavUnknownWaits);
        run("localNavNeverClosesOwnCell", TransitTests::localNavNeverClosesOwnCell);
        run("localNavGaveUpClosesNothing", TransitTests::localNavGaveUpClosesNothing);
        run("localNavNeedsSearchAfterNoPath", TransitTests::localNavNeedsSearchAfterNoPath);
        run("shuttleWatchdogSleepsDuringWait", TransitTests::shuttleWatchdogSleepsDuringWait);
        run("localNavCancelKeepsGiveUp", TransitTests::localNavCancelKeepsGiveUp);
        run("clearanceShuttleChannel", TransitTests::clearanceShuttleChannel);
        run("searchNoGoalTaper", TransitTests::searchNoGoalTaper);
        run("searchMaxExpanded", TransitTests::searchMaxExpanded);
        run("searchNearestClear", TransitTests::searchNearestClear);
        run("waterExitOpen", TransitTests::waterExitOpen);
        run("waterExitPond", TransitTests::waterExitPond);
        run("waterExitShore", TransitTests::waterExitShore);
        run("waterExitNarrowChannel", TransitTests::waterExitNarrowChannel);
        run("waterExitWideChannel", TransitTests::waterExitWideChannel);
        run("waterExitUnknown", TransitTests::waterExitUnknown);
        run("boatLookAhead", TransitTests::boatLookAhead);
        run("boatDetour", TransitTests::boatDetour);
        run("boatCaptureTurn", TransitTests::boatCaptureTurn);
        run("boatBackOff", TransitTests::boatBackOff);
        run("boatSideArrivalClear", TransitTests::boatSideArrivalClear);
        run("paddleSlideWheels", TransitTests::paddleSlideWheels);
        run("berthPivotRotation", TransitTests::berthPivotRotation);
        run("mooredPilot", TransitTests::mooredPilot);
        run("mooredPilotFlipped", TransitTests::mooredPilotFlipped);
        run("berthStepBothHeadings", TransitTests::berthStepBothHeadings);
        run("berthHeadingChoice", TransitTests::berthHeadingChoice);
        run("berthCentreRotation", TransitTests::berthCentreRotation);
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
        run("skyLogDiscoveryRadius", TransitTests::skyLogDiscoveryRadius);
        run("stationMarkerDisplay", TransitTests::stationMarkerDisplay);
        run("iceBreakable", TransitTests::iceBreakable);
        run("iceColumn", TransitTests::iceColumn);
        run("iceMarginRing", TransitTests::iceMarginRing);
        run("iceMarginStable", TransitTests::iceMarginStable);
        run("iceMarginCell", TransitTests::iceMarginCell);
        run("iceHullCell", TransitTests::iceHullCell);
        run("iceProbeMatchesHull", TransitTests::iceProbeMatchesHull);
        run("icePlacementState", TransitTests::icePlacementState);
        run("floeMaxHeight", TransitTests::floeMaxHeight);
        run("destinationKnownOthers", TransitTests::destinationKnownOthers);
        run("skyLogMarkerDistance", TransitTests::skyLogMarkerDistance);
        run("skyLogRoundTrip", TransitTests::skyLogRoundTrip);
        run("skyLogDestinationFilter", TransitTests::skyLogDestinationFilter);
        run("waterLogEmptyStart", TransitTests::waterLogEmptyStart);
        run("reachedStationsDropRemoved", TransitTests::reachedStationsDropRemoved);
        run("waterLogStationTarget", TransitTests::waterLogStationTarget);
        run("boatBackoffAction", TransitTests::boatBackoffAction);
        run("boatRouteLeg", TransitTests::boatRouteLeg);
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
        run("generatorAvailability", TransitTests::generatorAvailability);
        run("chartAvailabilityIgnoresDeleteOnRemove", TransitTests::chartAvailabilityIgnoresDeleteOnRemove);
        run("generationCyclesGone", TransitTests::generationCyclesGone);
        run("modRecipesMatchAssets", TransitTests::modRecipesMatchAssets);
        run("benchTabsFilter", TransitTests::benchTabsFilter);
        run("chartTwoSeas", TransitTests::chartTwoSeas);
        run("chartStrait", TransitTests::chartStrait);
        run("chartBerthOnRock", TransitTests::chartBerthOnRock);
        run("chartBerthFarFromWater", TransitTests::chartBerthFarFromWater);
        run("chartLakeNotSea", TransitTests::chartLakeNotSea);
        run("chartRiverFresh", TransitTests::chartRiverFresh);
        run("chartRockAndShallow", TransitTests::chartRockAndShallow);
        run("chartMergeOrder", TransitTests::chartMergeOrder);
        run("chartFillSkipsLabelled", TransitTests::chartFillSkipsLabelled);
        run("chartLakeAfterSea", TransitTests::chartLakeAfterSea);
        run("chartMergeShortRadius", TransitTests::chartMergeShortRadius);
        run("chartFileRoundTrip", TransitTests::chartFileRoundTrip);
        run("chartFileOtherSeed", TransitTests::chartFileOtherSeed);
        run("chartFileCorrupt", TransitTests::chartFileCorrupt);
        run("bookSegmentsEvery50", TransitTests::bookSegmentsEvery50);
        run("bookMergeNearKnownPoint", TransitTests::bookMergeNearKnownPoint);
        run("bookForgetAndCap", TransitTests::bookForgetAndCap);
        run("bookClosure", TransitTests::bookClosure);
        run("bookIncomplete", TransitTests::bookIncomplete);
        run("bookFileRoundTripAndBad", TransitTests::bookFileRoundTripAndBad);
        run("bookChangeListenerAndCap", TransitTests::bookChangeListenerAndCap);
        run("seaChartsBookSavedByItsThread", TransitTests::seaChartsBookSavedByItsThread);
        run("localNavFrozenNearBerth", TransitTests::localNavFrozenNearBerth);
        run("destinationsSameOceanSurface", TransitTests::destinationsSameOceanSurface);
        run("queueHeadAction", TransitTests::queueHeadAction);
        run("chartingWaitPerHeadEntry", TransitTests::chartingWaitPerHeadEntry);
        run("approachSpawnOffshore", TransitTests::approachSpawnOffshore);
        run("offshoreSpawnNeedsFullClearance", TransitTests::offshoreSpawnNeedsFullClearance);
        run("shuttleTripRecorded", TransitTests::shuttleTripRecorded);
        run("shuttleNavHelpers", TransitTests::shuttleNavHelpers);
        run("shuttleNavWaitIsBounded", TransitTests::shuttleNavWaitIsBounded);
        run("corridorAroundIsland", TransitTests::corridorAroundIsland);
        run("corridorPrefersDeep", TransitTests::corridorPrefersDeep);
        run("corridorAvoidsShore", TransitTests::corridorAvoidsShore);
        run("corridorLearnedThroughLand", TransitTests::corridorLearnedThroughLand);
        run("corridorLearnedSegmentPointsSpaced", TransitTests::corridorLearnedSegmentPointsSpaced);
        run("corridorClosedCell", TransitTests::corridorClosedCell);
        run("corridorClosedKillsSegment", TransitTests::corridorClosedKillsSegment);
        run("corridorNoDiagonalCut", TransitTests::corridorNoDiagonalCut);
        run("corridorPoints48", TransitTests::corridorPoints48);
        run("corridorBudget", TransitTests::corridorBudget);
        run("removalSequenceSingleSource", TransitTests::removalSequenceSingleSource);
        run("geometryConvertBalloon", TransitTests::geometryConvertBalloon);
        run("geometryConvertBoat", TransitTests::geometryConvertBoat);
        run("geometryHelpers", TransitTests::geometryHelpers);
        run("dropPointsNearestFirst", TransitTests::dropPointsNearestFirst);
        run("dropPointsFallbackMaster", TransitTests::dropPointsFallbackMaster);
        run("dropPointsFromRecordedMaster", TransitTests::dropPointsFromRecordedMaster);
        run("dropPointsLoopAndRange", TransitTests::dropPointsLoopAndRange);
        run("arrivalRefusalRule", TransitTests::arrivalRefusalRule);
        run("dropCentreArrival", TransitTests::dropCentreArrival);
        run("dropCentreFallbacks", TransitTests::dropCentreFallbacks);
        run("dropPointsIncludeCentre", TransitTests::dropPointsIncludeCentre);
        run("clearanceBalloonBounds", TransitTests::clearanceBalloonBounds);
        run("clearanceBoatCorridor", TransitTests::clearanceBoatCorridor);
        run("quayCoreBalloon", TransitTests::quayCoreBalloon);
        run("quayCoreBoat", TransitTests::quayCoreBoat);
        run("clearanceEditableRule", TransitTests::clearanceEditableRule);
        run("markerPointAndPadCell", TransitTests::markerPointAndPadCell);
        run("pendingStationsHaveNoQuay", TransitTests::pendingStationsHaveNoQuay);
        run("despawnTargetsNearestMaster", TransitTests::despawnTargetsNearestMaster);
        run("modBlocksDropGameItems", TransitTests::modBlocksDropGameItems);
        run("stationsFileV1Converted", TransitTests::stationsFileV1Converted);
        run("stationGeometryUsesQuayRotation", TransitTests::stationGeometryUsesQuayRotation);
        run("namingStationOutOfNetwork", TransitTests::namingStationOutOfNetwork);
        run("firstContractDefaultWorldOnly", TransitTests::firstContractDefaultWorldOnly);
        run("givenResultFromCrate", TransitTests::givenResultFromCrate);
        run("lockRefusedOnPlayerStation", TransitTests::lockRefusedOnPlayerStation);
        run("despawnCoreShapeBalloon", TransitTests::despawnCoreShapeBalloon);
        run("stationsFileV2WithoutMaster", TransitTests::stationsFileV2WithoutMaster);
        run("stationArrivalRoundTrip", TransitTests::stationArrivalRoundTrip);
        run("stationArrivalMissing", TransitTests::stationArrivalMissing);
        runQuiet("saveKeepsLegacyWorldgenKeys", TransitTests::saveKeepsLegacyWorldgenKeys);
        runQuiet("stationsApiAtAndTrust", TransitTests::stationsApiAtAndTrust);
        System.out.println("TransitTests: " + passed + " OK");
    }

    /** The removal sequence (refund, master NPC, network entry, queues) lives in StationRemoval only. */
    static void removalSequenceSingleSource() throws Exception {
        Path dir = Path.of("plugin/src/main/java/com/keepkoolz/orbishorizon");
        Path removal = dir.resolve("StationRemoval.java");
        check(Files.exists(removal), "StationRemoval.java exists");
        try (var w = Files.list(dir)) {
            for (Path p : (Iterable<Path>) w.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String name = p.getFileName().toString();
                String src = Files.readString(p);
                if (!name.equals("StationRemoval.java")) {
                    check(!src.contains("TransitFare.refundRemovedStation("), name + " must not refund a removed station itself");
                    if (!name.equals("StationMaster.java")) {
                        check(!src.contains("StationMaster.remove("), name + " must not remove the master NPC itself");
                    }
                }
            }
        }
    }

    static void saveKeepsLegacyWorldgenKeys() throws Exception {
        Path dir = Files.createTempDirectory("keep-legacy");
        try {
            Files.writeString(dir.resolve("transit-settings.json"), "{\"worldgenEnabled\": false, \"worldgenOldTerrain\": true}");
            TransitSettings.load(dir);
            TransitSettings.setMarkerSharing(false);
            var doc = org.bson.BsonDocument.parse(Files.readString(dir.resolve("transit-settings.json")));
            eq(false, doc.getBoolean("worldgenEnabled").getValue());
            eq(true, doc.getBoolean("worldgenOldTerrain").getValue());
            eq(false, doc.getBoolean("markerSharing").getValue());
            TransitSettings.setFareEnabled(false);
            doc = org.bson.BsonDocument.parse(Files.readString(dir.resolve("transit-settings.json")));
            eq(false, doc.getBoolean("worldgenEnabled").getValue());
            eq(true, doc.getBoolean("worldgenOldTerrain").getValue());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"packBackView\": true}");
            TransitSettings.load(dir);
            TransitSettings.setMarkerSharing(false);
            doc = org.bson.BsonDocument.parse(Files.readString(dir.resolve("transit-settings.json")));
            check(!doc.containsKey("worldgenEnabled"), "no worldgenEnabled added");
            check(!doc.containsKey("worldgenOldTerrain"), "no worldgenOldTerrain added");
        } finally {
            Path reset = Files.createTempDirectory("keep-legacy-reset");
            TransitSettings.load(reset);
            deleteAll(reset);
            deleteAll(dir);
        }
    }

    static void stationsApiAtAndTrust() throws Exception {
        Path net = Files.createTempDirectory("api-at");
        try {
            TestSupport.loadNetwork(net);
            TestSupport.addStation("Alpha", "w", 10, 70, 20, Deployables.STATION_KIND);
            ApiStations api = new ApiStations();
            StationInfo found = api.stationAt("w", 10, 20);
            check(found != null, "station found by column");
            eq("Alpha", found.name());
            check(api.stationAt("w", 11, 20) == null, "other column");
            check(api.stationAt("other", 10, 20) == null, "other world");
            check(api.networkTrusted(), "network trusted after a clean load");
        } finally {
            Path reset = Files.createTempDirectory("api-at-reset");
            TestSupport.loadNetwork(reset);
            deleteAll(reset);
            deleteAll(net);
        }
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

    /** Runs a case that provokes logged failures with the loggers of Extensions and OrbisHorizonApi silenced, then restores them. */
    private static void runQuiet(String name, Test t) {
        String[] names = {"Extensions", "OrbisHorizonApi", "WorldgenSettings", "WorldgenMigration", "WorldgenReconcile", "TransitNetwork",
                "TransitSettings"};
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

    static void arrivalButtonVisibility() {
        UUID me = UUID.randomUUID();
        String sea = Deployables.BOAT_STATION_KIND;
        eq(true, DestinationPage.canOfferArrival(sea, true, me, me));
        eq(false, DestinationPage.canOfferArrival(Deployables.STATION_KIND, true, me, me));
        eq(false, DestinationPage.canOfferArrival(sea, false, me, me));
        eq(false, DestinationPage.canOfferArrival(sea, true, null, me));
        eq(false, DestinationPage.canOfferArrival(sea, true, UUID.randomUUID(), me));
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

    static void fareSetting() throws Exception {
        Path dir = Files.createTempDirectory("transit-settings-fare");
        try {
            TransitSettings.load(dir);
            eq(true, TransitSettings.fareEnabled());
            TransitSettings.setFareEnabled(false);
            TransitSettings.load(dir);
            eq(false, TransitSettings.fareEnabled());
            eq(true, TransitSettings.markerSharing());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"packBackView\": false}");
            TransitSettings.load(dir);
            eq(true, TransitSettings.fareEnabled());
            Files.writeString(dir.resolve("transit-settings.json"), "{garbage");
            TransitSettings.load(dir);
            eq(true, TransitSettings.fareEnabled());
        } finally {
            TransitSettings.load(Files.createTempDirectory("transit-settings-fare-reset"));
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
            eq(Set.of(), TransitSettings.disabledRecipes());
            eq("{garbage", Files.readString(dir.resolve("transit-settings.json.bad")));
        } finally {
            deleteAll(dir);
        }
    }

    static void recipeTogglesWithoutUniverse() throws Exception {
        TestSupport.installApi();
        Path dir = Files.createTempDirectory("recipe-toggles");
        try {
            TransitSettings.load(dir);
            eq(true, RecipeToggles.enabled("Steamboat_Crate"));
            RecipeToggles.set("Steamboat_Crate", false);
            eq(false, RecipeToggles.enabled("Steamboat_Crate"));
            RecipeToggles.set("Not_A_Mod_Item", false);
            eq(false, TransitSettings.disabledRecipes().contains("Not_A_Mod_Item"));
            TransitSettings.load(dir);
            eq(false, RecipeToggles.enabled("Steamboat_Crate"));
        } finally {
            deleteAll(dir);
        }
    }

    private static void writeSettings(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("transit-settings.json"), json);
        TransitSettings.load(dir);
    }






    static void stationRemovalStepsAreIndependent() throws Exception {
        TestSupport.installApi();
        Path net = Files.createTempDirectory("removal-steps");
        try {
            TestSupport.loadNetwork(net);
            TestSupport.addStation("Gone", "w", 0, 64, 0, Deployables.STATION_KIND);
            TransitNetwork.Station gone = TransitNetwork.get().byName("w", "Gone");
            check(gone != null, "station added");
            // A null world makes the station master step fail: the network entry must go anyway.
            StationRemoval.fromNetwork(null, gone);
            check(TransitNetwork.get().byName("w", "Gone") == null, "network entry removed although a step failed");
        } finally {
            Path reset = Files.createTempDirectory("removal-steps-reset");
            TestSupport.loadNetwork(reset);
            deleteAll(reset);
            deleteAll(net);
        }
    }

    static void networkWorksWithoutExtension() throws Exception {
        TestSupport.installApi();
        Extensions.reset();
        Path net = Files.createTempDirectory("no-extension");
        try {
            check(!Extensions.isRegistered(), "no extension registered");
            TestSupport.loadNetwork(net);
            TestSupport.addStation("Alpha", "w", 0, 64, 0, Deployables.STATION_KIND);
            TestSupport.addStation("Beta", "w", 400, 64, 0, Deployables.STATION_KIND);
            TestSupport.addStation("Port A", "w", 0, 62, 300, Deployables.BOAT_STATION_KIND);
            TestSupport.addStation("Port B", "w", 500, 62, 300, Deployables.BOAT_STATION_KIND);
            TransitNetwork network = TransitNetwork.get();
            TransitNetwork.Station alpha = network.byName("w", "Alpha");
            check(alpha != null && network.byName("w", "Beta") != null, "stations found by name");
            // Queue and ticket: a destination is queued with its payment, then taken back with it.
            eq(StationQueue.EnqueueResult.ADDED, network.queueEnqueue(alpha, "Beta",
                    new TransitFare.Payment(UUID.randomUUID(), TransitFare.CONCENTRATED, 1)));
            eq(1, StationQueue.positionOf(alpha, "beta"));
            eq("Beta", network.queueHead(alpha));
            check(network.queueRemoveFirst(alpha, "Beta") != null, "ticket payment given back");
            eq(null, network.queueHead(alpha));
            eq(StationQueue.EnqueueResult.SELF, StationQueue.addTo(new ArrayList<>(), "Alpha", "alpha"));
            // Shuttle between two ports: the departure port is still the target, the other kind never counts.
            TransitNetwork.Station portA = network.byName("w", "Port A");
            TransitNetwork.Station portB = network.byName("w", "Port B");
            List<TransitNetwork.Station> ports = network.stations("w", Deployables.BOAT_STATION_KIND);
            eq(2, ports.size());
            org.joml.Vector3d here = new org.joml.Vector3d(480, 61, 300);
            eq(List.of(portA), BoatQuay.redirectTargets(ports, "Port A", "Port B", here));
            eq(portA, BoatQuay.returnStation(ports, "Port A", "Port B", here));
            check(!Extensions.isRegistered(), "still no extension");
        } finally {
            Path reset = Files.createTempDirectory("no-extension-reset");
            TestSupport.loadNetwork(reset);
            deleteAll(reset);
            deleteAll(net);
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

    static void consoleLogsNames() {
        var names = ConsoleLogs.namesFromEntries(java.util.List.of(
                "com/keepkoolz/orbishorizon/Foo.class",
                "com/keepkoolz/orbishorizon/Foo$Bar.class",
                "com/keepkoolz/orbishorizon/Foo$$Lambda.class",
                "com/keepkoolz/orbishorizon/sub/Baz.class",
                "com/keepkoolz/orbishorizon/package-info.class",
                "com/keepkoolz/orbishorizon/data.json",
                "com/other/Qux.class",
                "plugin.json"));
        eq(java.util.Set.of("Foo", "Foo$Bar", "Baz"), names);
    }

    static void consoleLogLevelSetting() throws Exception {
        eq(java.util.logging.Level.ALL, ConsoleLogs.Threshold.ALL.level);
        eq(java.util.logging.Level.INFO, ConsoleLogs.Threshold.INFO.level);
        eq(java.util.logging.Level.WARNING, ConsoleLogs.Threshold.WARNING.level);
        eq(java.util.logging.Level.SEVERE, ConsoleLogs.Threshold.SEVERE.level);
        eq(java.util.logging.Level.OFF, ConsoleLogs.Threshold.NONE.level);
        eq(ConsoleLogs.Threshold.INFO, ConsoleLogs.Threshold.parse(null));
        eq(ConsoleLogs.Threshold.INFO, ConsoleLogs.Threshold.parse("LOUD"));
        eq(ConsoleLogs.Threshold.NONE, ConsoleLogs.Threshold.parse("NONE"));
        Path dir = Files.createTempDirectory("transit-settings-console");
        try {
            TransitSettings.load(dir);
            eq(ConsoleLogs.Threshold.INFO, TransitSettings.consoleLogLevel());
            TransitSettings.setConsoleLogLevel(ConsoleLogs.Threshold.SEVERE);
            TransitSettings.load(dir);
            eq(ConsoleLogs.Threshold.SEVERE, TransitSettings.consoleLogLevel());
            check(Files.readString(dir.resolve("transit-settings.json")).contains("\"consoleLogLevel\": \"SEVERE\""), "level written by name");
            Files.writeString(dir.resolve("transit-settings.json"), "{\"consoleLogLevel\": \"LOUD\"}");
            TransitSettings.load(dir);
            eq(ConsoleLogs.Threshold.INFO, TransitSettings.consoleLogLevel());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"packBackView\": false}");
            TransitSettings.load(dir);
            eq(ConsoleLogs.Threshold.INFO, TransitSettings.consoleLogLevel());
        } finally {
            ConsoleLogs.apply(ConsoleLogs.Threshold.INFO);
            TransitSettings.load(Files.createTempDirectory("transit-settings-console-reset"));
            deleteAll(dir);
        }
    }

    static void settingsDisabledRecipes() throws Exception {
        Path dir = Files.createTempDirectory("transit-settings-recipes");
        try {
            TransitSettings.load(dir);                                    // no file
            eq(Set.of(), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": true}");
            TransitSettings.load(dir);
            eq(Set.of(), TransitSettings.disabledRecipes());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": false}");
            TransitSettings.load(dir);
            eq(Set.of(), TransitSettings.disabledRecipes());
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
            eq(Set.of(), TransitSettings.disabledRecipes());
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

    static void contractPriceEssencesFirst() {
        var kindOf = (java.util.function.Function<TransitFare.Payment, String>) pay -> pay == null ? null : pay.itemId();
        eq(TransitFare.ESSENCE, kindOf.apply(TransitFare.choose(null, 1000, 10, 1000, 10)));
        eq(1000, TransitFare.choose(null, 1000, 10, 1000, 10).quantity());
        eq(TransitFare.CONCENTRATED, kindOf.apply(TransitFare.choose(null, 999, 10, 1000, 10)));
        eq(10, TransitFare.choose(null, 999, 10, 1000, 10).quantity());
        eq(null, TransitFare.choose(null, 999, 9, 1000, 10));
        // the ticket overload keeps its prices
        eq(TransitFare.ESSENCE, kindOf.apply(TransitFare.choose(null, 100, 0)));
    }

    static void firstContractOnlyOnce() {
        Set<String> given = new java.util.HashSet<>();
        check(StationContracts.shouldGiveFirst(given, "w", false), "empty world, not given");
        check(!StationContracts.shouldGiveFirst(given, "w", true), "world with a station");
        given.add("w");
        check(!StationContracts.shouldGiveFirst(given, "w", false), "already given");
        check(StationContracts.shouldGiveFirst(given, "other", false), "another world");
    }

    static void firstContractOwnerGate() {
        UUID host = UUID.randomUUID();
        check(StationContracts.mayReceiveFirst(host, host), "the host");
        check(!StationContracts.mayReceiveFirst(host, UUID.randomUUID()), "a guest");
        check(StationContracts.mayReceiveFirst(null, UUID.randomUUID()), "no owner: first player");
    }

    static void contractItems() throws Exception {
        eq("Hotair_Balloon_Station_Contract", StationContracts.itemFor(Deployables.STATION_KIND));
        eq("Hotair_Balloon_Boat_Station_Contract", StationContracts.itemFor(Deployables.BOAT_STATION_KIND));
        eq(Deployables.STATION_KIND, StationContracts.kindOfItem("Hotair_Balloon_Station_Contract"));
        eq(Deployables.BOAT_STATION_KIND, StationContracts.kindOfItem("Hotair_Balloon_Boat_Station_Contract"));
        eq(null, StationContracts.kindOfItem("Balloon_Station_Crate"));
        eq(Deployables.STATION_KIND, StationContracts.kindOfCrate("Balloon_Station_Crate"));
        eq(Deployables.BOAT_STATION_KIND, StationContracts.kindOfCrate("Boat_Station_Crate"));
        eq(null, StationContracts.kindOfCrate("Hotair_Balloon_Crate"));
        for (String id : new String[]{"Hotair_Balloon_Station_Contract", "Hotair_Balloon_Boat_Station_Contract"}) {
            String j = Files.readString(Path.of("Server/Item/Items/Containers/Vehicles", id + ".json"));
            check(!j.contains("\"Recipe\""), id + " has no recipe");
            check(j.contains("\"Transit_PlaceMaster\""), id + " interaction");
            check(j.contains("\"StationKind\""), id + " kind");
            check(j.contains("server.orbis_horizon.items." + id.toLowerCase(java.util.Locale.ROOT) + ".name"), id + " name key");
        }
        for (String id : new String[]{"Balloon_Station_Crate", "Boat_Station_Crate"}) {
            String j = Files.readString(Path.of("Server/Item/Items/Containers/Vehicles", id + ".json"));
            check(!j.contains("\"Recipe\""), id + " has no recipe");
            check(j.contains("\"Transit_ExchangeCrate\""), id + " exchange interaction");
            check(!j.contains("Transit_DeployStation"), id + " no deploy interaction");
        }
        for (String lang : new String[]{"en-US", "fr-FR"}) {
            String lines = Files.readString(Path.of("Server/Languages", lang, "server.lang"));
            for (String key : new String[]{"orbis_horizon.items.hotair_balloon_station_contract.name",
                    "orbis_horizon.items.hotair_balloon_boat_station_contract.name", "orbis_horizon.chat.transit.crate.exchanged",
                    "orbis_horizon.ui.contract.buy"}) {
                check(lines.contains(key + " ="), lang + " " + key);
            }
        }
    }

    static void contractPriceSettings() throws Exception {
        Path dir = Files.createTempDirectory("contract-price");
        try {
            TransitSettings.load(dir);
            eq(1000, TransitSettings.contractEssences());
            eq(10, TransitSettings.contractConcentrated());
            TransitSettings.setContractEssences(500);
            TransitSettings.setContractConcentrated(5);
            TransitSettings.setContractEssences(-3); // refused
            TransitSettings.load(dir);
            eq(500, TransitSettings.contractEssences());
            eq(5, TransitSettings.contractConcentrated());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"contractEssences\": 0, \"contractConcentrated\": \"x\"}");
            TransitSettings.load(dir);
            eq(1000, TransitSettings.contractEssences());
            eq(10, TransitSettings.contractConcentrated());
        } finally {
            deleteAll(dir);
        }
    }

    static void stationCratesKeyIgnored() throws Exception {
        TestSupport.installApi();
        Path dir = Files.createTempDirectory("crates-ignored");
        try {
            writeSettings(dir, "{\"stationCrates\": \"off\", \"disabledRecipes\": [\"Balloon_Station_Crate\", \"Orbis_Explorer_Bench\"]}");
            eq(Set.of("Orbis_Explorer_Bench"), TransitSettings.disabledRecipes());
            TransitSettings.setMarkerSharing(false);
            var doc = org.bson.BsonDocument.parse(Files.readString(dir.resolve("transit-settings.json")));
            check(!doc.containsKey("stationCrates"), "stationCrates no longer written");
            check(!ModRecipes.ITEMS.contains("Balloon_Station_Crate") && !ModRecipes.ITEMS.contains("Boat_Station_Crate"), "no crate recipe");
        } finally {
            deleteAll(dir);
        }
    }

    static void firstContractPersisted() throws Exception {
        Path net = Files.createTempDirectory("first-contract");
        try {
            TestSupport.loadNetwork(net);
            check(TransitNetwork.get().setFirstContract("w", true), "newly noted");
            check(!TransitNetwork.get().setFirstContract("w", true), "already noted");
            TestSupport.loadNetwork(net);
            check(TransitNetwork.get().firstContractWorlds().contains("w"), "kept after a reload");
            check(TransitNetwork.get().setFirstContract("w", false), "removed");
            TestSupport.loadNetwork(net);
            check(TransitNetwork.get().firstContractWorlds().isEmpty(), "removal kept");
        } finally {
            deleteAll(net);
        }
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
        eq(null, StationPlacement.tooClose(List.of(balloon), Deployables.BOAT_STATION_KIND, new Vector3i(0, 64, 10), 64));
        eq(boat, StationPlacement.tooClose(List.of(balloon, boat), Deployables.BOAT_STATION_KIND, origin, 64));
        eq(balloon, StationPlacement.tooClose(List.of(balloon, boat), Deployables.STATION_KIND, new Vector3i(0, 64, 10), 64));
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

    private static void emptiedCellsReadAsEmpty() {
        DeployPlacement.CellState base = (x, y, z) -> 2;
        DeployPlacement.CellState sim = DeployPlacement.withEmptied(base, Set.of(new Vector3i(1, 2, 3)));
        eq(0, sim.at(1, 2, 3));
        eq(2, sim.at(1, 2, 4));
    }

    private static void dredgedCellsReadAsWater() {
        DeployPlacement.CellState base = (x, y, z) -> 2;
        Set<Vector3i> dug = Set.of(new Vector3i(1, 2, 3));
        eq(1, DeployPlacement.withWater(base, dug).at(1, 2, 3));
        eq(2, DeployPlacement.withWater(base, dug).at(1, 2, 4));
        // Water wins over a cell read as emptied.
        eq(1, DeployPlacement.withWater(DeployPlacement.withEmptied(base, dug), dug).at(1, 2, 3));
    }

    private static void refusalCarriesHullFlag() {
        eq(null, Refusal.of("k").hull());
        eq(null, Refusal.at("k", "b", 1, 2, 3).hull());
        eq(null, new Refusal(null, null, null, null).hull());
        eq(Boolean.TRUE, new Refusal("k", "b", new Vector3i(), "d", true).hull());
    }

    /**
     * The refusals carry the keys and parameters the crate path sends. The local predicate `id -> id.startsWith("Plant_")`
     * stands for the generator's test that a block is foliage, which counts as empty only for a generated station.
     */
    private static void roomRefusalKeysUnchanged() {
        Refusal at = Refusal.at("transit.deploy.noSpaceAt", "Rock_Stone", 1, 2, 3);
        eq("transit.deploy.noSpaceAt", at.key());
        eq("Rock_Stone", at.block());
        eq(new org.joml.Vector3i(1, 2, 3), at.at());
        eq(null, at.detailKey());
        eq("deploy.underwater", Refusal.of("deploy.underwater").key());
        check(StationPlacement.blocksRoom("Plant_Leaves_Oak", true, id -> false), "crate: foliage blocks");
        check(!StationPlacement.blocksRoom("Plant_Leaves_Oak", true, id -> id.startsWith("Plant_")), "generated: foliage counts as empty");
        check(StationPlacement.blocksRoom("Rock_Stone", true, id -> id.startsWith("Plant_")), "generated: rock blocks");
        check(!StationPlacement.blocksRoom("Rock_Stone", false, id -> false), "non solid never blocks");
        check(StationPlacement.boatCellAllowed(-1, true, true), "boat rule kept");
        check(!StationPlacement.boatCellAllowed(0, true, false), "boat deck rule kept");
    }

    private static void apiConstantsMatchInternals() {
        eq(Deployables.STATION_KIND, StationKinds.AIR);
        eq(Deployables.BOAT_STATION_KIND, StationKinds.BOAT);
        eq(Deployables.STEAMBOAT_PUBLIC_KIND, StationKinds.SHUTTLE);
        eq(Deployables.PUBLIC_KIND, StationKinds.PUBLIC_BALLOON);
        eq(Deployables.STATION_GRASS_Y, StationKinds.GRASS_Y);
        eq(Deployables.BOAT_STATION_BERTH_ROTATION, StationKinds.BOAT_BERTH_ROTATION);
        eq(SeaRules.SEA_LEVEL, Terrain.SEA_LEVEL);
        eq(BalloonManager.WORLD_MAX_Y, WorldAccess.MAX_Y);
    }

    private static void apiKindsGeometry() {
        StationKinds k = OrbisHorizonApi.get().kinds();
        Object[][] kinds = {{StationKinds.AIR, Deployables.STATION}, {StationKinds.BOAT, Deployables.BOAT_STATION},
                {StationKinds.SHUTTLE, Deployables.STEAMBOAT_PUBLIC}, {StationKinds.PUBLIC_BALLOON, Deployables.BALLOON_PUBLIC}};
        for (Object[] row : kinds) {
            String id = (String) row[0];
            Deployables.Kind kind = (Deployables.Kind) row[1];
            check(Arrays.equals(kind.placeMin, k.placeMin(id)), "placeMin " + id);
            check(Arrays.equals(kind.placeMax, k.placeMax(id)), "placeMax " + id);
            int[] copy = k.placeMin(id);
            int before = copy[0];
            copy[0] = before + 1000;
            eq(before, k.placeMin(id)[0]);
            int[] copyMax = k.placeMax(id);
            copyMax[1] = copyMax[1] + 1000;
            eq(kind.placeMax[1], k.placeMax(id)[1]);
        }
        check(Arrays.equals(Deployables.BOAT_STATION_BERTH_ORIGIN, k.boatBerthOrigin()), "berth origin");
        check(Arrays.equals(Deployables.BOAT_STATION_PIER_END, k.boatPierEnd()), "pier end");
        check(Arrays.equals(Deployables.BOAT_STATION_MASTER_POS, k.boatMasterPos()), "master pos");
        float[] p = Deployables.STATION_BOARDING_POINTS[0];
        check(Arrays.equals(new int[] {Math.round(p[0]), Math.round(p[1]), Math.round(p[2])}, k.airBoardingPoint()), "boarding point");
        for (Rotation r : Rotation.values()) {
            eq(StationPlacement.dockOrigin(new Vector3i(10, 70, 10), r), k.dockOrigin(new Vector3i(10, 70, 10), r));
        }
        boolean refused = false;
        try {
            k.placeMin("tent");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "unknown kind refused");
    }

    private static void apiKindsCellsFromShape() throws Exception {
        StructureShape shape = prefabShape("Steamboat_Public.prefab.json");
        List<Vector3i> out = ApiStationKinds.positions(shape);
        eq(shape.cells().size(), out.size());
        for (int i = 0; i < out.size(); i++) {
            StructureShape.Cell c = shape.cells().get(i);
            eq(new Vector3i(c.x(), c.y(), c.z()), out.get(i));
        }
    }

    private static void apiTerrainRules() {
        Terrain t = OrbisHorizonApi.get().terrain();
        GridProbe g = new GridProbe("~#");
        check(t.isSea(g, 0, 0), "sea");
        check(t.isLand(g, 16, 0), "land");
        check(!t.isWater(g, 16, 0), "land is not water");
        check(t.isSeaZone("Zone1_Shore"), "shore zone");
        check(!t.isSeaZone("Zone1_Tier1"), "inland zone");
        check(t.atSeaLevel(new Vector3i(0, 115, 0)), "sea level");
        check(!t.atSeaLevel(new Vector3i(0, 114, 0)), "under sea level");
        check(!t.atSeaLevel(null), "null origin");
    }

    private static void apiRefusalRecord() {
        eq(null, Refusal.of("k").hull());
        eq(null, Refusal.at("k", "b", 1, 2, 3).hull());
        eq(null, new Refusal(null, null, null, null).hull());
        eq(Boolean.TRUE, new Refusal("k", "b", new Vector3i(), "d", true).hull());
        Refusal at = Refusal.at("transit.deploy.noSpaceAt", "Rock_Stone", 1, 2, 3);
        eq("transit.deploy.noSpaceAt", at.key());
        eq("Rock_Stone", at.block());
        eq(new Vector3i(1, 2, 3), at.at());
        eq(null, at.detailKey());
        eq("deploy.underwater", Refusal.of("deploy.underwater").key());
    }

    /** An extension of the tests: counts the world starts, and every method throws when asked to. */
    private static final class TestExtension implements Extension {
        final boolean throwing;
        int starts;
        int stops;
        int removed;
        List<ConfigRow> rows = List.of();
        List<AbstractCommand> commands = List.of();

        TestExtension(boolean throwing) {
            this.throwing = throwing;
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void onWorldStart(World world) {
            starts++;
            if (throwing) throw new IllegalStateException("start");
        }

        @Override
        public void onWorldStop(World world) {
            stops++;
            if (throwing) throw new IllegalStateException("stop");
        }

        @Override
        public void onStationRemovedByOperator(World world, StationInfo station) {
            removed++;
            if (throwing) throw new IllegalStateException("removed");
        }

        @Override
        public List<ConfigRow> configRows() {
            if (throwing) throw new IllegalStateException("rows");
            return rows;
        }

        @Override
        public List<AbstractCommand> stationSubCommands() {
            if (throwing) throw new IllegalStateException("commands");
            return commands;
        }
    }

    private static TransitNetwork.Station extensionStation() {
        return new TransitNetwork.Station("A", "w", new Vector3i(1, 2, 3), Rotation.None, null, null, new ArrayList<>(), "station");
    }

    private static void extensionsHooksNeverThrow() throws Exception {
        TestSupport.installApi();
        Extensions.reset();
        TransitNetwork.Station s = extensionStation();
        check(!Extensions.isRegistered(), "no extension yet");
        Extensions.worldStart(null);
        Extensions.worldStop(null);
        Extensions.stationRemoved(null, s);
        eq(List.of(), Extensions.configRows());
        eq(List.of(), Extensions.stationSubCommands());
        TestExtension bad = new TestExtension(true);
        check(OrbisHorizonApi.get().register(bad), "registered");
        check(Extensions.isRegistered(), "registered seen");
        Extensions.worldStart(null);
        Extensions.worldStop(null);
        Extensions.stationRemoved(null, s);
        eq(List.of(), Extensions.configRows());
        eq(List.of(), Extensions.stationSubCommands());
        eq(1, bad.starts);
        eq(1, bad.stops);
        eq(1, bad.removed);
    }

    private static void extensionRegisterTwice() throws Exception {
        int[] calls = {0};
        OrbisHorizonApi.install(new OrbisHorizonApi.Services(new ApiStations(), new ApiStationKinds(), new ApiWorldAccess(),
                new ApiTerrain(), new ApiTelemetry(), Files.createTempDirectory("oh-api"), e -> calls[0]++));
        TestExtension a = new TestExtension(false);
        TestExtension b = new TestExtension(false);
        check(OrbisHorizonApi.get().register(a), "first");
        check(OrbisHorizonApi.get().register(a), "same again");
        eq(1, calls[0]);
        check(!OrbisHorizonApi.get().register(b), "second refused");
        check(OrbisHorizonApi.get().extension() == a, "first kept");
        check(!OrbisHorizonApi.get().register(null), "null refused");
    }

    private static void extensionRowsSanitized() throws Exception {
        TestSupport.installApi();
        Extensions.reset();
        TestExtension e = new TestExtension(false);
        boolean[] written = {false};
        ConfigRow good = new ConfigRow("x", "s", "l", () -> true, v -> written[0] = v);
        e.rows = Arrays.asList(good,
                new ConfigRow("markerSharing", "s", "l", () -> true, v -> { }),
                new ConfigRow("Hotair_Balloon_Crate", "s", "l", () -> true, v -> { }),
                new ConfigRow("n", "s", "l", null, v -> { }),
                new ConfigRow("x", "s", "l2", () -> false, v -> { }),
                null);
        OrbisHorizonApi.get().register(e);
        eq(List.of(good), Extensions.configRows());
        check(Extensions.configRow("x") == good, "row by key");
        eq(null, Extensions.configRow("markerSharing"));
        check(Extensions.readRow(good), "read");
        check(!Extensions.readRow(new ConfigRow("y", "s", "l", () -> { throw new IllegalStateException(); }, v -> { })),
                "read throws");
        check(!Extensions.writeRow(new ConfigRow("y", "s", "l", () -> true, v -> { throw new IllegalStateException(); }), true),
                "write throws");
        check(Extensions.writeRow(good, true), "write ok");
        check(written[0], "written value");
    }

    private static void extensionDeliversStartedWorldsOnce() throws Exception {
        TestSupport.installApi();
        Extensions.reset();
        TestExtension e = new TestExtension(false);
        TestExtension e2 = new TestExtension(false);
        Extensions.onRegistered(e, () -> Arrays.asList((World) null));
        eq(0, e.starts);
        Extensions.markStarted();
        Extensions.onRegistered(e2, () -> Arrays.asList((World) null, (World) null));
        eq(2, e2.starts);
        OrbisHorizonApi.get().register(e2);
        Extensions.worldStart(null);
        eq(3, e2.starts);
    }

    private static void extensionDeliveryClaimedOnce() {
        Extensions.reset();
        check(Extensions.claimDelivery("w"), "first claim");
        check(!Extensions.claimDelivery("w"), "second claim refused");
        check(Extensions.claimDelivery("v"), "other world");
        Extensions.releaseDelivery("w");
        check(Extensions.claimDelivery("w"), "claim after stop");
        Extensions.reset();
        check(Extensions.claimDelivery("v"), "reset clears");
    }

    private static void extensionSubCommandsNullsDropped() throws Exception {
        TestSupport.installApi();
        Extensions.reset();
        TestExtension e = new TestExtension(false);
        e.commands = Arrays.asList(null, null);
        OrbisHorizonApi.get().register(e);
        eq(List.of(), Extensions.stationSubCommands());
    }

    private static void apiInstallReplacesState() throws Exception {
        TestSupport.installApi();
        TestSupport.installApi();
        eq(null, OrbisHorizonApi.get().extension());
    }

    private static void usageCountersRegister() {
        UsageCounters c = new UsageCounters();
        c.register(List.of("x.a", "x.b", "x.a", "deploy.tent"));
        c.increment("x.b");
        c.increment("x.b");
        c.increment("x.a");
        c.increment("deploy.tent");
        c.increment("glider.jump");
        Map<String, Long> s = c.snapshotAndReset();
        eq(List.of("deploy.tent", "glider.jump", "x.a", "x.b"), new ArrayList<>(s.keySet()));
        eq(2L, s.get("x.b"));
        eq(1L, s.get("x.a"));
        c.increment("x.unknown");
        c.increment(null);
        check(c.snapshotAndReset().isEmpty(), "unknown name ignored");
    }

    private static void blocksRoomPredicateOnlyWhenSolid() {
        int[] calls = {0};
        java.util.function.Predicate<String> counting = id -> {
            calls[0]++;
            return false;
        };
        check(!StationPlacement.blocksRoom("Rock_Stone", false, counting), "non solid never blocks");
        eq(0, calls[0]);
        check(StationPlacement.blocksRoom("Rock_Stone", true, counting), "solid asks the predicate");
        eq(1, calls[0]);
    }

    /** Boat station room rule: the posts (prefab y below 0) may stand in the lake bed or the water, the deck and above must be free. */
    private static void boatStationRoomRule() {
        check(StationPlacement.boatCellAllowed(-3, true, true), "post in the lake bed");
        check(StationPlacement.boatCellAllowed(-1, false, true), "post in the water");
        check(!StationPlacement.boatCellAllowed(0, false, true), "deck in the water");
        check(!StationPlacement.boatCellAllowed(0, true, false), "deck in a block");
        check(StationPlacement.boatCellAllowed(1, false, false), "free cell");
        check(!DeployPlacement.CRATES.containsKey("Boat_Station_Crate"), "the boat crate is exchanged, not placed");
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
    /** Only the three camping packs are pitched crouching, every crate keeps its usual placement. */
    private static void campPackCrouchRule() {
        check(CampTiers.pitchedOnlyCrouching("Camping_Pack_Bedroll"), "bedroll pack");
        check(CampTiers.pitchedOnlyCrouching("Camping_Pack_Tent"), "tent pack");
        check(CampTiers.pitchedOnlyCrouching("Camping_Pack_Tent_Big"), "large tent pack");
        check(!CampTiers.pitchedOnlyCrouching("Camping_Tent_Crate"), "old tent crate");
        check(!CampTiers.pitchedOnlyCrouching("Camping_Tent_Big_Crate"), "old large tent crate");
        check(!CampTiers.pitchedOnlyCrouching(null), "no item");
        for (String id : DeployPlacement.CRATES.keySet()) {
            if (!id.startsWith("Camping_Pack_")) {
                check(!CampTiers.pitchedOnlyCrouching(id), "crate " + id);
            }
        }
    }

    /** An empty pack gives one empty chest per prefab chest cell, 18 slots each. */
    private static void packChestsEmpty() throws Exception {
        List<Vector3i> small = PackChests.chestCells(prefabShape("Camping_Tent.prefab.json"));
        eq(1, small.size());
        PackChests.Layout layout = PackChests.toSlots(null, small, new short[]{18});
        eq(1, layout.slots().length);
        eq(18, layout.slots()[0].length);
        check(layout.leftovers().isEmpty(), "no leftover");
        List<Vector3i> big = PackChests.chestCells(prefabShape("Camping_Tent_Big.prefab.json"));
        PackChests.Layout bigLayout = PackChests.toSlots(null, big, new short[]{18, 18});
        eq(2, bigLayout.slots().length);
        eq(18, bigLayout.slots()[1].length);
    }

    /** The large tent lists its chests in ascending (y, x, z) order, and the stored chests follow their position. */
    private static void packChestsBigOrder() throws Exception {
        List<Vector3i> big = PackChests.chestCells(prefabShape("Camping_Tent_Big.prefab.json"));
        eq(new Vector3i(-2, 0, 3), big.get(0));
        eq(new Vector3i(2, 0, 3), big.get(1));
        BalloonCargo cargo = new BalloonCargo(List.of(new BalloonCargo.Entry(new Vector3i(2, 0, 3),
                "Furniture_Kweebec_Chest_Small", (short) 12, new com.hypixel.hytale.server.core.inventory.ItemStack[12])));
        PackChests.Layout layout = PackChests.toSlots(new TentContents(AirshipBenchContents.fromBson(null), cargo), big, new short[]{18, 18});
        eq(18, layout.slots()[0].length);
        eq(12, layout.slots()[1].length);
    }

    /** Contents to slots to contents keeps the campfire, the position of stored chests and the entries without a cell. */
    private static void packChestsRoundTrip() throws Exception {
        List<Vector3i> big = PackChests.chestCells(prefabShape("Camping_Tent_Big.prefab.json"));
        org.bson.BsonDocument benchDoc = new org.bson.BsonDocument();
        org.bson.BsonDocument e = new org.bson.BsonDocument();
        e.put("x", new org.bson.BsonInt32(-3));
        e.put("y", new org.bson.BsonInt32(0));
        e.put("z", new org.bson.BsonInt32(1));
        e.put("bench", new org.bson.BsonDocument());
        org.bson.BsonArray arr = new org.bson.BsonArray();
        arr.add(e);
        benchDoc.put("benchContents", arr);
        BalloonCargo cargo = new BalloonCargo(List.of(
                new BalloonCargo.Entry(new Vector3i(-2, 0, 3), "Furniture_Kweebec_Chest_Small", (short) 18,
                        new com.hypixel.hytale.server.core.inventory.ItemStack[18]),
                new BalloonCargo.Entry(new Vector3i(9, 9, 9), "Furniture_Kweebec_Chest_Small", (short) 6,
                        new com.hypixel.hytale.server.core.inventory.ItemStack[6])));
        TentContents before = new TentContents(AirshipBenchContents.fromBson(benchDoc), cargo);
        PackChests.Layout layout = PackChests.toSlots(before, big, new short[]{18, 18});
        eq(1, layout.leftovers().size());
        TentContents after = PackChests.fromSlots(before, big, layout.slots(), layout.leftovers());
        org.bson.BsonDocument d = after.toBson("tent_big");
        eq(before.toBson("tent_big").getDocument("benches"), d.getDocument("benches"));
        // the chests are all empty (no stack can be built here): only the foreign entry stays, at its position
        eq(1, d.getDocument("cargo").getArray("containers").size());
        eq(9, d.getDocument("cargo").getArray("containers").get(0).asDocument().getInt32("x").getValue());
        check(PackChests.sameMetadata(null, null), "no metadata matches no metadata");
        check(!PackChests.sameMetadata(null, d), "metadata appeared");
        check(PackChests.sameMetadata(d, d.clone()), "same metadata");
    }

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

    private static void fuelHud() throws Exception {
        check(FuelHud.level(120, false) == FuelHud.FuelLevel.NORMAL, "normal above 30 s");
        check(FuelHud.level(30.5, false) == FuelHud.FuelLevel.NORMAL, "normal just above 30 s");
        check(FuelHud.level(30, false) == FuelHud.FuelLevel.LOW, "low at 30 s");
        check(FuelHud.level(10.5, false) == FuelHud.FuelLevel.LOW, "low just above 10 s");
        check(FuelHud.level(10, false) == FuelHud.FuelLevel.CRITICAL, "critical at 10 s");
        check(FuelHud.level(0.2, false) == FuelHud.FuelLevel.CRITICAL, "critical near 0");
        check(FuelHud.level(500, true) == FuelHud.FuelLevel.DRY, "dry whatever the seconds");
        check(FuelHud.wholeSeconds(14.2) == 15 && FuelHud.wholeSeconds(15) == 15 && FuelHud.wholeSeconds(-1) == 0, "whole seconds rounded up");
        FuelHud.Content a = FuelHud.content(FuelHud.Vehicle.BALLOON, 14.2, 4, false);
        check(FuelHud.changed(null, a), "first content is sent");
        check(!FuelHud.changed(a, FuelHud.content(FuelHud.Vehicle.BALLOON, 14.9, 4, false)), "same whole second, no packet");
        check(FuelHud.changed(a, FuelHud.content(FuelHud.Vehicle.BALLOON, 13.9, 4, false)), "next second changes");
        check(FuelHud.changed(a, FuelHud.content(FuelHud.Vehicle.BALLOON, 14.2, 3, false)), "item count changes");
        check(FuelHud.changed(a, FuelHud.content(FuelHud.Vehicle.AIRSHIP, 14.2, 4, false)), "vehicle changes");
        FuelHud.Content b = FuelHud.content(FuelHud.Vehicle.BALLOON, 31, 4, false);
        check(FuelHud.changed(b, FuelHud.content(FuelHud.Vehicle.BALLOON, 30.5, 4, false)) == false, "same second, same level");
        check(FuelHud.changed(b, FuelHud.content(FuelHud.Vehicle.BALLOON, 30, 4, false)), "level change at 30 s");
        FuelHud.Content dry = FuelHud.content(FuelHud.Vehicle.BALLOON, 12, 4, true);
        check(!FuelHud.changed(dry, FuelHud.content(FuelHud.Vehicle.BALLOON, 0, 0, true)), "dry content does not depend on the rest");
        check(FuelHud.changed(a, dry), "dry replaces the countdown");
        check(!FuelHud.stale(1000, 3500) && FuelHud.stale(1000, 4500), "stale after 3 s");
        check(FuelHud.vehicleOf(Deployables.STEAMBOAT) == FuelHud.Vehicle.STEAMBOAT
                && FuelHud.vehicleOf(Deployables.STEAMBOAT_PUBLIC) == FuelHud.Vehicle.STEAMBOAT
                && FuelHud.vehicleOf(Deployables.AIRSHIP) == FuelHud.Vehicle.AIRSHIP, "ship texts by kind");
        String ui = Files.readString(Path.of("Common/UI/Custom/" + FuelHud.UI_FILE));
        for (String id : new String[] {"#FuelTitle", FuelHud.TIME_NORMAL, FuelHud.TIME_LOW, FuelHud.TIME_CRITICAL, "#FuelItems"}) {
            check(ui.contains("Label " + id + " "), "element in the .ui file: " + id);
        }
        check(!ui.contains("%server"), "texts are set from the plugin, no % key in the .ui file");
        check(ui.contains("@PanelBottom = 160;") && ui.contains("@PanelWidth"), "position constants");
        for (String lang : new String[] {"en-US", "fr-FR"}) {
            String text = Files.readString(Path.of("Server/Languages/" + lang + "/server.lang"));
            for (String k : new String[] {"title.balloon", "title.airship", "title.steamboat", "time.balloon", "time.airship",
                    "time.steamboat", "items", "dry"}) {
                check(text.contains("orbis_horizon.ui.fuel." + k + " = "), lang + " key ui.fuel." + k);
            }
            check(!text.contains("chat.fuel.level") && !text.contains("chat.fuel.status") && !text.contains("fuel.low =")
                    && !text.contains("fuel.critical =") && !text.contains("hover.fuel"), lang + " no fuel countdown in the chat");
            check(text.contains("orbis_horizon.chat.fuel.dryLanded") && text.contains("orbis_horizon.chat.airship.fuel.none"),
                    lang + " fuel events kept");
        }
    }

    private static void creativeVisibility() throws Exception {
        Set<String> visible = Set.of("Orbis_Explorer_Bench", "Camping_Pack_Bedroll", "Camping_Pack_Tent",
                "Camping_Pack_Tent_Big", "Hotair_Balloon_Crate", "Hotair_Balloon_Basic_Crate",
                "Hotair_Balloon_Transport_Crate", "Airship_Cloudwork_Crate", "Steamboat_Crate",
                "Balloon_Station_Crate", "Boat_Station_Crate", "Airship_Pilot_Contract",
                "Hotair_Balloon_Station_Contract", "Hotair_Balloon_Boat_Station_Contract",
                "Hotair_Balloon_Station_Plan_Pad", "Hotair_Balloon_Station_Plan_Full",
                "Hotair_Balloon_Boat_Station_Plan_Pier", "Hotair_Balloon_Boat_Station_Plan_Full",
                "Hotair_Balloon_Boat_Station_Plan_Arrival", "Ship_Wheel");
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
        // marker = pier end (1, 0, 11) -> (11, 0, -1), one block up
        eq(new org.joml.Vector3d(111.5, 65, 199.5), StationMarkerProvider.markerPoint(boat));
        check(StationSpec.of("unknown") == StationSpec.BALLOON, "unknown kind uses the balloon spec");
        check(StationSpec.of(Deployables.BOAT_STATION_KIND).soilLayerY() == null, "boat station has no soil layer");
        eq(64 - 10, StationZone.zoneOf(boat).bottomY());
        eq(64 + Deployables.STATION_GRASS_Y, StationZone.zoneOf(balloon).bottomY());
    }

    static void boatStationOrigin() {
        TransitNetwork.Station boat = testStation(Deployables.BOAT_STATION_KIND);
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

    private static void iceHullFlags() {
        StructureShape s = StructureShape.ofCells(List.of(
                new StructureShape.Cell(0, -4, 0, "Wood_Hardwood_Planks", 0),
                new StructureShape.Cell(0, -3, 0, "Wood_Hardwood_Planks", 0),
                new StructureShape.Cell(0, -2, 0, "Wood_Hardwood_Planks", 0)));
        AirshipManager.Hull boat = AirshipManager.hullFor(s, new Vector3f(0, 1, 0), -3);
        check(boat.icebreaker, "a waterborne hull is an ice-breaker");
        check(boat.surfaceDy == -4.0, "surfaceDy is waterline minus pivot, got " + boat.surfaceDy);
        check(boat.topDy == -3.0, "topDy is top cell minus pivot, got " + boat.topDy);
        AirshipManager.Hull air = AirshipManager.hullFor(s, new Vector3f(0, 1, 0), null);
        check(!air.icebreaker && air.surfaceDy == 0 && air.topDy == 0, "an airship hull is not an ice-breaker");
        check(Deployables.STEAMBOAT_PUBLIC.airship.waterborne() && Deployables.STEAMBOAT.airship.waterborne(), "both steamboats break ice");
        check(!Deployables.AIRSHIP.airship.waterborne(), "an airship does not");
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

    static void hoverAboveHighestBlock() {
        check(StationGeometry.hoverBottomY(60, 55) == 67, "pad only");
        check(StationGeometry.hoverBottomY(60, 80) == 84, "tower nearby");
        check(StationGeometry.hoverBottomY(60, Integer.MIN_VALUE) == 67, "nothing under the ship");
        // A bare converted station hovers as it always did: statues 9 above the origin, lowest block 4 above them.
        Vector3i o = new Vector3i(100, 70, 200);
        StationGeometry.Converted c = StationGeometry.convert(Deployables.STATION_KIND, o, Rotation.None);
        eq(o.y + 13, StationGeometry.hoverBottomY(c.quay().y, o.y + 9));
        eq(o.y + 13, StationGeometry.hoverBottomY(c.quay().y, Integer.MIN_VALUE));
    }

    static void highestSolidColumns() {
        // A tower at (105, 100) of height 80 and a block at (130, 100) out of the 18-block box around (100, 100).
        StationGeometry.Solid solid = (x, y, z) -> (x == 105 && z == 100 && y <= 80) || (x == 130 && z == 100 && y <= 200) || y <= 50;
        eq(80, StationGeometry.highestSolid(solid, 100, 100, 18, 60, 319));
        // The ignored box of the parked ship does not count.
        eq(Integer.MIN_VALUE, StationGeometry.highestSolid((x, y, z) -> !StationGeometry.inBox(new int[] {95, 60, 95, 115, 90, 115}, x, y, z)
                && x == 105 && z == 100 && y <= 80, 100, 100, 18, 60, 319));
        eq(Integer.MIN_VALUE, StationGeometry.highestSolid((x, y, z) -> false, 100, 100, 18, 60, 319));
        // Ground below the quay is ignored: the search starts at the quay height.
        eq(Integer.MIN_VALUE, StationGeometry.highestSolid((x, y, z) -> y <= 50, 100, 100, 18, 60, 319));
    }

    static void stationPositionFallback() {
        TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(0, 10, 0), Rotation.None, null, null,
                new ArrayList<>(), Deployables.STATION_KIND, false, true, new Vector3i(5, 20, 6), 0f, null, null);
        near(5.5, StationGeometry.position(s).x);
        near(6.5, StationGeometry.position(s).z);
        s.quay = new Vector3i(40, 30, 50);
        near(40.5, StationGeometry.position(s).x);
        near(31, StationGeometry.position(s).y);
    }

    static void hoverPivotClearsStation() {
        double[] p = RecallPlanner.hoverPivot(0, 0, 78, 0);
        near(84, p[1]);
        for (int k = 0; k < 4; k++) {
            double yaw = k * Math.PI / 2;
            double[] h = RecallPlanner.hoverPivot(5, -7, 78, yaw);
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
        c.register(null); // ignored without an exception
        Telemetry.registerCounters(null);
        check(c.snapshotAndReset().isEmpty(), "null registration changes nothing");
        check(UsageCounters.NAMES.size() == 25, "25 counters");
        check(UsageCounters.NAMES.contains("station.quayPlaced"), "quay counter");
        for (String n : UsageCounters.NAMES) {
            check(!n.startsWith("worldgen."), "no worldgen counter in NAMES: " + n);
        }
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
        check(Texts.SHIP_KEYS.size() == 23, "23 restes");
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

    // ---- LocalNav ----

    private static final class FakeCorridor implements LocalNav.Corridor {
        final List<int[]> closed = new ArrayList<>();
        final List<int[]> retraced = new ArrayList<>();
        List<int[]> next;

        FakeCorridor(List<int[]> next) {
            this.next = next;
        }

        @Override
        public List<int[]> retrace(int fromX, int fromZ) {
            retraced.add(new int[] {fromX, fromZ});
            return next;
        }

        @Override
        public void close(int x, int z) {
            closed.add(new int[] {x, z});
        }
    }

    private static char[][] navMap(char fill) {
        char[][] m = new char[400][400];
        for (char[] row : m) {
            java.util.Arrays.fill(row, fill);
        }
        return m;
    }

    private static WaterRoute.ColumnProbe navProbe(char[][] m) {
        return (x, z) -> {
            if (x < 0 || z < 0 || x >= m.length || z >= m[0].length) {
                return WaterRoute.Cell.BLOCKED;
            }
            char c = m[x][z];
            return c == '.' ? WaterRoute.Cell.WATER : c == '?' ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.BLOCKED;
        };
    }

    private static LocalNav navFor(FakeCorridor c, List<int[]> points, int gx, int gz) {
        LocalNav nav = new LocalNav(c, HullClearance.SHUTTLE, gx, gz);
        nav.setCorridor(points);
        return nav;
    }

    /** Ticks until the result is not RUNNING (or the tick limit is hit, which fails the check). */
    private static LocalNav.Result navRun(LocalNav nav, double cx, double cz, WaterRoute.ColumnProbe probe, int limit) {
        LocalNav.Result r = null;
        for (int i = 0; i < limit; i++) {
            r = nav.tick(cx, cz, probe, 500, null);
            if (r.kind() != LocalNav.Kind.RUNNING) {
                return r;
            }
        }
        return r;
    }

    private static void localNavFollowsCorridor() {
        char[][] m = navMap('.');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200}, new int[] {326, 200});
        LocalNav nav = navFor(new FakeCorridor(null), pts, 326, 200);
        LocalNav.Result r = navRun(nav, 200, 200, navProbe(m), 200);
        eq(LocalNav.Kind.PATH, r.kind());
        int[] last = r.path().get(r.path().size() - 1);
        eq(230, last[0]);
        eq(200, last[1]);
        check(!nav.needsSearch(210, 200), "pas de nouvelle recherche près du départ");
        check(nav.needsSearch(250, 200), "nouvelle recherche après une demi-fenêtre");
    }

    private static void localNavGoalOnShore() {
        char[][] m = navMap('.');
        waterRect(m, 228, 399, 0, 399, '#');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        LocalNav nav = navFor(new FakeCorridor(null), pts, 278, 200);
        WaterRoute.ColumnProbe probe = navProbe(m);
        int[] expected = new WaterRoute.Search(probe, 200, 200, 230, 200, 152, 152, 248, 248)
                .clearance(HullClearance.SHUTTLE).taperGoal(false).nearestClear(230, 200, 16);
        check(expected != null, "un nœud dégagé près de la rive");
        LocalNav.Result r = navRun(nav, 200, 200, probe, 200);
        eq(LocalNav.Kind.PATH, r.kind());
        int[] last = r.path().get(r.path().size() - 1);
        eq(expected[0], last[0]);
        eq(expected[1], last[1]);
    }

    private static void localNavWidens() {
        char[][] m = navMap('.');
        waterRect(m, 215, 217, 140, 399, '#');
        FakeCorridor c = new FakeCorridor(null);
        LocalNav nav = navFor(c, List.of(new int[] {230, 200}, new int[] {278, 200}), 278, 200);
        LocalNav.Result r = navRun(nav, 200, 200, navProbe(m), 400);
        eq(LocalNav.Kind.PATH, r.kind());
        eq(0, c.closed.size());
        eq(0, c.retraced.size());
    }

    private static void localNavClosesAndRetraces() {
        char[][] m = navMap('.');
        waterRect(m, 215, 217, 0, 399, '#');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        FakeCorridor c = new FakeCorridor(pts);
        LocalNav nav = navFor(c, pts, 278, 200);
        WaterRoute.ColumnProbe probe = navProbe(m);
        for (int i = 0; i < 400 && c.retraced.isEmpty(); i++) {
            nav.tick(200, 200, probe, 500, null);
        }
        eq(1, c.closed.size());
        check(c.closed.get(0)[0] >= 208 && c.closed.get(0)[0] <= 223, "case du mur : " + c.closed.get(0)[0]);
        eq(1, c.retraced.size());
        eq(200, c.retraced.get(0)[0]);
    }

    private static void localNavFailsWithoutProgress() {
        char[][] m = navMap('.');
        waterRect(m, 215, 217, 0, 399, '#');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        FakeCorridor c = new FakeCorridor(pts);
        LocalNav nav = navFor(c, pts, 278, 200);
        WaterRoute.ColumnProbe probe = navProbe(m);
        LocalNav.Result r = null;
        for (int i = 0; i < 4000 && (r == null || r.kind() != LocalNav.Kind.FAILED); i++) {
            r = nav.tick(200, 200, probe, 500, null);
        }
        eq(LocalNav.Kind.FAILED, r.kind());
        eq(LocalNav.MAX_RETRACES, c.retraced.size());
        eq(LocalNav.Kind.FAILED, nav.tick(200, 200, probe, 500, null).kind());
    }

    private static void localNavFailsWithoutCorridor() {
        char[][] m = navMap('.');
        waterRect(m, 215, 217, 0, 399, '#');
        FakeCorridor c = new FakeCorridor(null);
        LocalNav nav = navFor(c, List.of(new int[] {230, 200}, new int[] {278, 200}), 278, 200);
        LocalNav.Result r = navRun(nav, 200, 200, navProbe(m), 400);
        eq(LocalNav.Kind.FAILED, r.kind());
        eq(1, c.retraced.size());
    }

    private static void localNavNeverClosesOwnCell() {
        char[][] m = navMap('.');
        waterRect(m, 215, 217, 0, 399, '#');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        FakeCorridor c = new FakeCorridor(pts);
        LocalNav nav = navFor(c, pts, 278, 200);
        WaterRoute.ColumnProbe probe = navProbe(m);
        for (int i = 0; i < 400 && c.retraced.isEmpty(); i++) {
            nav.tick(210, 200, probe, 500, null);
        }
        eq(1, c.closed.size());
        check(Math.floorDiv(c.closed.get(0)[0], 16) != Math.floorDiv(210, 16), "pas la case du bateau");
    }

    private static void localNavGaveUpClosesNothing() {
        char[][] m = navMap('.');
        waterRect(m, 215, 260, 0, 399, '?');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        FakeCorridor c = new FakeCorridor(pts);
        LocalNav nav = navFor(c, pts, 278, 200);
        WaterRoute.ColumnProbe probe = navProbe(m);
        eq(LocalNav.Kind.WAIT_DATA, navRun(nav, 200, 200, probe, 200).kind());
        nav.dataGaveUp();
        for (int i = 0; i < 400 && c.retraced.isEmpty(); i++) {
            nav.tick(200, 200, probe, 500, null);
        }
        eq(1, c.retraced.size());
        eq(0, c.closed.size());
    }

    private static void shuttleWatchdogSleepsDuringWait() {
        // wait, loaded box (held), search running while sailing, frozen: only the held ticks suspend the watchdog
        LocalNav.Kind[] ticks = {LocalNav.Kind.WAIT_DATA, LocalNav.Kind.WAIT_DATA, LocalNav.Kind.RUNNING, LocalNav.Kind.RUNNING,
                LocalNav.Kind.PATH};
        boolean[] suspended = {true, true, false, false, false};
        for (int i = 0; i < ticks.length; i++) {
            eq(suspended[i], BoatAutopilot.watchdogSuspended(ShuttleNav.heldForData(ticks[i])));
        }
        check(!BoatAutopilot.watchdogSuspended(false), "chien de garde actif en route");
    }

    private static void localNavCancelKeepsGiveUp() {
        char[][] m = navMap('.');
        waterRect(m, 215, 260, 0, 399, '?');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        FakeCorridor c = new FakeCorridor(pts);
        LocalNav nav = navFor(c, pts, 278, 200);
        WaterRoute.ColumnProbe probe = navProbe(m);
        eq(LocalNav.Kind.WAIT_DATA, navRun(nav, 200, 200, probe, 200).kind());
        nav.dataGaveUp();
        nav.cancel(); // a new search (blocked shuttle) must not wait again on the same box
        for (int i = 0; i < 400 && c.retraced.isEmpty(); i++) {
            check(nav.tick(200, 200, probe, 500, null).kind() != LocalNav.Kind.WAIT_DATA, "pas de nouvelle attente");
        }
        eq(1, c.retraced.size()); // the give-up lasted until the attempt ended
    }

    private static void localNavNeedsSearchAfterNoPath() {
        char[][] m = navMap('.');
        List<int[]> pts = List.of(new int[] {230, 200}, new int[] {278, 200});
        FakeCorridor c = new FakeCorridor(pts);
        LocalNav nav = navFor(c, pts, 278, 200);
        eq(LocalNav.Kind.PATH, navRun(nav, 200, 200, navProbe(m), 200).kind());
        check(!nav.needsSearch(200, 200), "chemin courant");
        waterRect(m, 215, 217, 0, 399, '#');
        WaterRoute.ColumnProbe probe = navProbe(m);
        for (int i = 0; i < 400 && c.retraced.isEmpty(); i++) {
            nav.tick(200, 200, probe, 500, null);
        }
        check(nav.needsSearch(200, 200), "plus de chemin après un échec");
    }

    private static void localNavUnknownWaits() {
        char[][] m = navMap('.');
        waterRect(m, 220, 245, 150, 250, '?');
        FakeCorridor c = new FakeCorridor(null);
        LocalNav nav = navFor(c, List.of(new int[] {230, 200}, new int[] {278, 200}), 278, 200);
        LocalNav.Result r = navRun(nav, 200, 200, navProbe(m), 200);
        eq(LocalNav.Kind.WAIT_DATA, r.kind());
        check(r.box() != null && r.box().length == 4, "zone à charger");
        check(r.box()[0] <= 152 && r.box()[2] >= 248, "la zone couvre la fenêtre");
        eq(0, c.closed.size());
        waterRect(m, 220, 245, 150, 250, '.');
        nav.dataLoaded();
        r = navRun(nav, 200, 200, navProbe(m), 200);
        eq(LocalNav.Kind.PATH, r.kind());
        eq(0, c.closed.size());
        // An unknown strip across the way, with a clear goal: the search itself asks for data.
        char[][] m2 = navMap('.');
        waterRect(m2, 210, 212, 150, 250, '?');
        LocalNav nav2 = navFor(c, List.of(new int[] {230, 200}, new int[] {278, 200}), 278, 200);
        r = navRun(nav2, 200, 200, navProbe(m2), 200);
        eq(LocalNav.Kind.WAIT_DATA, r.kind());
        eq(0, c.closed.size());
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

    private static void clearanceShuttleChannel() {
        char[][] m = waterMap('#');
        waterRect(m, 5, 40, 20, 99, '.');
        waterRect(m, 80, 115, 20, 99, '.');
        waterRect(m, 41, 79, 55, 64, '.');
        check(waterSearch(m, 20, 60, 100, 60).clearance(HullClearance.SHUTTLE).step(100000) == WaterRoute.Status.FOUND,
                "chenal de 10 : FOUND avec la navette");
        check(waterSearch(m, 20, 60, 100, 60).clearance(HullClearance.SHIP).step(100000) == WaterRoute.Status.NO_ROUTE,
                "chenal de 10 : NO_ROUTE avec le bateau");
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.NO_ROUTE, "défaut : bateau");
    }

    private static void searchNoGoalTaper() {
        char[][] m = waterMap('#');
        waterRect(m, 10, 110, 10, 110, '.');
        // Goal 3 blocks from the east shore (x = 111 is land).
        WaterRoute.Search tapered = waterSearch(m, 20, 60, 108, 60);
        check(tapered.step(100000) == WaterRoute.Status.FOUND, "but près de la rive : trouvé avec le taper");
        WaterRoute.Search s = waterSearch(m, 20, 60, 108, 60).taperGoal(false);
        WaterRoute.Status st = s.step(100000);
        if (st == WaterRoute.Status.FOUND) {
            int[] last = s.waypoints().get(s.waypoints().size() - 1);
            check(landDistance(m, last[0], last[1]) >= WaterRoute.CLEARANCE, "marge au but");
        } else {
            eq(WaterRoute.Status.NO_ROUTE, st);
        }
    }

    private static void searchMaxExpanded() {
        char[][] m = waterMap('.');
        waterRect(m, 45, 75, 30, 90, '#');
        eq(WaterRoute.Status.NO_ROUTE, waterSearch(m, 20, 60, 100, 60).maxExpanded(50).step(100000));
        eq(WaterRoute.Status.FOUND, waterSearch(m, 20, 60, 100, 60).step(100000));
    }

    private static void searchNearestClear() {
        char[][] m = waterMap('#');
        waterRect(m, 10, 110, 10, 110, '.');
        WaterRoute.Search s = waterSearch(m, 20, 60, 100, 60);
        int[] c = s.nearestClear(11, 60, 30);
        check(c != null, "colonne dégagée trouvée");
        check(Math.hypot(c[0] - 11, c[1] - 60) <= 30, "dans le rayon");
        check(landDistance(m, c[0], c[1]) >= WaterRoute.CLEARANCE || c[0] - 10 >= WaterRoute.CLEARANCE, "marge complète");
        check(s.nearestClear(5, 5, 3) == null, "rien dans la terre");
        char[][] u = waterMap('?');
        check(waterSearch(u, 20, 60, 100, 60).nearestClear(60, 60, 20) == null, "inconnu non dégagé");
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

    private static void mooredPilot() {
        for (Rotation r : new Rotation[] {Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
            TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), r, null, null,
                    new ArrayList<>(), Deployables.BOAT_STATION_KIND);
            // Helm: the pivot cell of the prefab, turned by the berth rotation around the berth origin (cell centre in x and z).
            Vector3i o = BoatBerth.berthOrigin(s);
            org.joml.Vector3f pv = AirshipManager.pivot(Deployables.STEAMBOAT_PUBLIC);
            Vector3d rot = AirshipMath.rotateY(pv.x, pv.y, pv.z, BoatBerth.berthRotation(s).getRadians(), new Vector3d());
            Vector3d p = MooredPilot.helmPoint(s, false);
            check(Math.abs(p.x - (o.x + Math.round(rot.x) + 0.5)) < 1e-6 && Math.abs(p.z - (o.z + Math.round(rot.z) + 0.5)) < 1e-6
                    && Math.abs(p.y - (o.y + Math.round(rot.y))) < 1e-6, "pivot du prefab tourné, rotation " + r + " : " + p);
            // Facing: forward (-sin yaw, -cos yaw) is the bow of the prefab (0, 0, -1) turned by the berth rotation, whatever the station's.
            double yaw = MooredPilot.facingYaw(s, false);
            Vector3d bow = AirshipMath.rotateY(0, 0, -1, BoatBerth.berthRotation(s).getRadians(), new Vector3d());
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
            Vector3i o = BoatBerth.berthOrigin(s);
            Vector3d pn = MooredPilot.helmPoint(s, false);
            Vector3d pf = MooredPilot.helmPoint(s, true);
            // Half turn about the prefab origin: the two pivots are symmetric about the origin cell centre.
            near(2 * (o.x + 0.5), pn.x + pf.x);
            near(2 * (o.z + 0.5), pn.z + pf.z);
            near(pn.y, pf.y);
            // The pivot cell of the prefab turned by the flipped rotation.
            org.joml.Vector3f pv = AirshipManager.pivot(Deployables.STEAMBOAT_PUBLIC);
            Vector3d rot = AirshipMath.rotateY(pv.x, pv.y, pv.z, BoatBerth.berthRotation(s, true).getRadians(), new Vector3d());
            near(o.x + Math.round(rot.x) + 0.5, pf.x);
            near(o.z + Math.round(rot.z) + 0.5, pf.z);
            // Facing: forward (-sin yaw, -cos yaw) is the prefab bow turned by the flipped berth rotation, opposite to the usual one.
            double yaw = MooredPilot.facingYaw(s, true);
            Vector3d bow = AirshipMath.rotateY(0, 0, -1, BoatBerth.berthRotation(s, true).getRadians(), new Vector3d());
            near(bow.x, -Math.sin(yaw));
            near(bow.z, -Math.cos(yaw));
            near(Math.PI, Math.abs(AirshipMath.wrapPi(yaw - MooredPilot.facingYaw(s, false))));
            near(BoatBerth.berthTheta(s, true), yaw);
            // The rotation centre does not depend on the heading.
            Vector3d c = BoatBerth.berthCentre(s);
            Vector3d cf = AirshipManager.get().centerWorld(Deployables.STEAMBOAT_PUBLIC, pf, BoatBerth.berthTheta(s, true));
            near(c.x, cf.x);
            near(c.z, cf.z);
            check(BoatBerth.berthRotation(s, true) == BoatBerth.berthRotation(s).add(Rotation.OneEighty), "flipped rotation " + r);
            check(BoatBerth.berthRotation(s, false) == BoatBerth.berthRotation(s), "usual rotation " + r);
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
                Rotation br = BoatBerth.berthRotation(s, flipped);
                boolean touches = false;
                for (StructureShape.Cell c : boat.cells()) {
                    Vector3i w = c.rotated(br).add(BoatBerth.berthOrigin(s));
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
            int a = c.rotated(BoatBerth.berthRotation(Rotation.None, false)).x + ox;
            int b = c.rotated(BoatBerth.berthRotation(Rotation.None, true)).x + ox;
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
            double usual = BoatBerth.berthTheta(s);
            check(!BoatBerth.preferFlipped(usual, usual), "same heading keeps the usual one, " + r);
            check(BoatBerth.preferFlipped(usual + Math.PI, usual), "opposite heading flips, " + r);
            check(BoatBerth.preferFlipped(usual - Math.PI, usual), "opposite heading (other side of the wrap) flips, " + r);
            check(!BoatBerth.preferFlipped(usual + Math.toRadians(80), usual), "80 degrees off keeps the usual one, " + r);
            check(!BoatBerth.preferFlipped(usual - Math.toRadians(80), usual), "-80 degrees off keeps the usual one, " + r);
            check(BoatBerth.preferFlipped(usual + Math.toRadians(100), usual), "100 degrees off flips, " + r);
            check(BoatBerth.preferFlipped(usual - Math.toRadians(100), usual), "-100 degrees off flips, " + r);
            check(!BoatBerth.preferFlipped(usual + Math.PI / 2, usual) && !BoatBerth.preferFlipped(usual - Math.PI / 2, usual),
                    "a quarter turn keeps the usual one, " + r);
            // A heading near the flipped berth heading flips.
            check(BoatBerth.preferFlipped(BoatBerth.berthTheta(s, true) + 0.3, usual), "heading near the flipped one, " + r);
        }
        // Wrap-around at +-pi for the usual heading pi (station Ninety): -0.9 pi is 0.1 pi away, 0.1 pi is 0.9 pi away.
        check(!BoatBerth.preferFlipped(-Math.PI * 0.9, Math.PI), "-0.9 pi is next to pi");
        check(BoatBerth.preferFlipped(Math.PI * 0.1, Math.PI), "0.1 pi is next to the opposite of pi");
        check(!BoatBerth.preferFlipped(Math.PI * 0.95, Math.PI), "0.95 pi is next to pi");
        check(BoatBerth.preferFlipped(-Math.PI * 0.05, Math.PI), "-0.05 pi is next to the opposite of pi");
    }

    private static void destinationsSameOceanSurface() {
        eq(BoatQuay.Reach.YES, BoatQuay.reach(3, 3, 63, 63));
        eq(BoatQuay.Reach.NO, BoatQuay.reach(3, 3, 63, 70));
        eq(BoatQuay.Reach.NO, BoatQuay.reach(3, 4, 63, 63));
        eq(BoatQuay.Reach.CHARTING, BoatQuay.reach(0, 3, 63, 63));
        eq(BoatQuay.Reach.CHARTING, BoatQuay.reach(3, 0, 63, 63));
        eq(BoatQuay.Reach.CHARTING, BoatQuay.reach(0, 0, 63, 70));
    }

    private static void offshoreSpawnNeedsFullClearance() {
        int r = HullClearance.SHUTTLE.turn();
        WaterRoute.ColumnProbe channel = (x, z) -> Math.abs(z) <= 1 ? WaterRoute.Cell.WATER : WaterRoute.Cell.BLOCKED;
        WaterRoute.ColumnProbe open = (x, z) -> WaterRoute.Cell.WATER;
        WaterRoute.ColumnProbe unloaded = (x, z) -> x > 5 ? WaterRoute.Cell.UNKNOWN : WaterRoute.Cell.WATER;
        check(!WaterRoute.clearAround(channel, 0, 0, r), "3-block channel refused");
        check(WaterRoute.clearAround(open, 0, 0, r), "open water accepted");
        check(!WaterRoute.clearAround(unloaded, 0, 0, r), "unloaded column not clear");
    }

    private static void queueHeadAction() {
        long w = BoatQuay.CHARTING_WAIT_MS;
        eq(BoatQuay.QueueAction.WAIT, BoatQuay.headAction(BoatQuay.Reach.CHARTING, 1000L, 1000L + w - 1));
        eq(BoatQuay.QueueAction.DROP, BoatQuay.headAction(BoatQuay.Reach.CHARTING, 1000L, 1000L + w + 1));
        eq(BoatQuay.QueueAction.DROP, BoatQuay.headAction(BoatQuay.Reach.NO, 1000L, 1000L));
        eq(BoatQuay.QueueAction.GO, BoatQuay.headAction(BoatQuay.Reach.YES, 1000L, 1000L));
        BoatQuay.Quay q = new BoatQuay.Quay();
        eq(1000L, q.chartingSince(1L, 1000L));
        eq(1000L, q.chartingSince(1L, 9000L));
        eq(9000L, q.chartingSince(2L, 9000L));
        q.chartingDone();
        eq(20000L, q.chartingSince(2L, 20000L));
    }

    /** After a timeout drop, the same destination queued again is a new head entry and waits the full charting delay. */
    private static void chartingWaitPerHeadEntry() {
        TransitNetwork net = TransitNetwork.get();
        TransitNetwork.Station s = new TransitNetwork.Station("Quai", "w", new Vector3i(0, 62, 0), Rotation.None, null, null,
                new ArrayList<>(), Deployables.BOAT_STATION_KIND);
        long w = BoatQuay.CHARTING_WAIT_MS;
        net.queueAdd(s, "Large");
        net.queueAdd(s, "Ile");
        long first = net.queueHeadId(s);
        net.queueRemoveAll(s, "Ile");
        eq(first, net.queueHeadId(s));
        BoatQuay.Quay q = new BoatQuay.Quay();
        long now = 1000L;
        eq(BoatQuay.QueueAction.WAIT, BoatQuay.headAction(BoatQuay.Reach.CHARTING, q.chartingSince(net.queueHeadId(s), now), now));
        now += w + 1;
        eq(BoatQuay.QueueAction.DROP, BoatQuay.headAction(BoatQuay.Reach.CHARTING, q.chartingSince(net.queueHeadId(s), now), now));
        net.queueRemoveFirst(s, "Large");
        net.queueAdd(s, "Large");
        long second = net.queueHeadId(s);
        check(second != first, "new head entry for the same destination");
        eq(BoatQuay.QueueAction.WAIT, BoatQuay.headAction(BoatQuay.Reach.CHARTING, q.chartingSince(second, now), now));
        eq(BoatQuay.QueueAction.WAIT, BoatQuay.headAction(BoatQuay.Reach.CHARTING, q.chartingSince(second, now + w - 1), now + w - 1));
        net.queuePoll(s);
        check(net.queueHeadId(s) != second, "poll gives a new head identity");
    }

    private static void approachSpawnOffshore() {
        for (Rotation r : new Rotation[] {Rotation.None, Rotation.Ninety}) {
            TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), r, null, null,
                    new ArrayList<>(), Deployables.BOAT_STATION_KIND);
            Vector3i pre = BoatBerth.preBerthCell(s);
            Vector3i berth = BoatBerth.berthCentreCell(s);
            int[] p = BoatQuay.approachSpawn(s);
            // 40 blocks from the pre-berth point, on the same axis, on the side away from the berth (the pier).
            check(Math.abs(Math.hypot(p[0] - pre.x, p[1] - pre.z) - 40.0) < 1e-9, "40 blocks from the pre-berth point, " + r);
            Vector3i axis = r.rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
            eq(pre.x + 40 * axis.x, p[0]);
            eq(pre.z + 40 * axis.z, p[1]);
            check(Math.hypot(p[0] - berth.x, p[1] - berth.z) > Math.hypot(pre.x - berth.x, pre.z - berth.z), "seaward of the pre-berth point, " + r);
        }
        int[] north = BoatQuay.approachSpawn(Rotation.None, new Vector3i(100, 60, 200));
        eq(100, north[0]);
        eq(200 + BoatBerth.BERTH_STANDOFF + 40, north[1]);
    }

    private static void berthPivotRotation() {
        TransitNetwork.Station s = new TransitNetwork.Station("S", "w", new Vector3i(100, 64, 200), Rotation.Ninety, null, null,
                new ArrayList<>(), Deployables.BOAT_STATION_KIND);
        // Berth origin {1, -1, 14} turned by Ninety (x, z) -> (z, -x) = (14, -1), plus the origin: (114, 63, 199).
        eq(new Vector3i(114, 63, 199), BoatBerth.berthOrigin(s));
        eq(Rotation.OneEighty, BoatBerth.berthRotation(s));
        // Pivot {0, 1, -1} turned by OneEighty = (0, 1, 1): cell (114, 64, 200), centre (114.5, 64, 200.5).
        Vector3d p = BoatBerth.berthPivot(s);
        check(p.x == 114.5 && p.y == 64 && p.z == 200.5, "pivot " + p);
        check(Math.abs(BoatBerth.berthTheta(s) - Math.PI) < 1e-6, "cap");
        eq(63, BoatBerth.surfaceY(s));
    }

    private static void waterRouteLongCrossing() {
        WaterRoute.ColumnProbe sea = (x, z) -> z >= 0 && z < 60 ? WaterRoute.Cell.WATER : WaterRoute.Cell.BLOCKED;
        WaterRoute.Search s = new WaterRoute.Search(sea, 20, 30, 3520, 30, 0, 0, 3540, 59);
        check(s.step(1_000_000) == WaterRoute.Status.FOUND, "traversée de 3500 blocs");
    }

    private static void waterRouteTooLong() {
        char[][] m = waterMap('.');
        check(waterSearch(m, 20, 60, 100, 60).step(100000) == WaterRoute.Status.FOUND, "chemin trouvé");
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

    /** Pose test of the real public shuttle hull (rotation centre at the pose) against one blocked world column. */
    private static BoatAutopilot.PoseTest shuttleHullTest(int colX, int colZ) throws Exception {
        AirshipManager.Hull hull = AirshipManager.hullFor(prefabShape("Steamboat_Public.prefab.json"), new Vector3f(0, 1, -1), null);
        AirshipManager.CellTest cell = (x, y, z, wet) -> x == colX && z == colZ;
        // the pivot sits one block ahead of the rotation centre, toward the bow: centre + R(theta)(0, -1) = (cx - sin, cz - cos)
        return (cx, cz, th) -> AirshipManager.blocked(cell, hull, cx - Math.sin(th), 0, cz - Math.cos(th), th, 0) > 0;
    }

    private static void boatCaptureTurn() throws Exception {
        // Open sea: ice column (3044, -3049), node (3047, -3045), the next waypoint (3064, -3080).
        BoatAutopilot.PoseTest t = shuttleHullTest(3044, -3049);
        int[] node = {3047, -3045};
        int[] next = {3064, -3080};
        check(!BoatAutopilot.turnBlocked(t, 3047.5, -3044.5, Math.toRadians(-90), BoatAutopilot.targetHeading(3047.5, -3044.5, next)),
                "the turn at the node is clear");
        check(BoatAutopilot.turnBlocked(t, 3044.6, -3044.3, Math.toRadians(-83), BoatAutopilot.targetHeading(3044.6, -3044.3, next)),
                "the same turn 2.9 blocks short of the node hits the column");
        check(!BoatAutopilot.captureHere(t, 3044.6, -3044.3, Math.toRadians(-83), node, next), "no early capture where the turn is blocked");
        check(BoatAutopilot.captureHere(t, 3047.5, -3044.5, Math.toRadians(-90), node, next), "captured at the node, the turn there is clear");
        check(!BoatAutopilot.captureHere(t, 3043.0, -3044.5, Math.toRadians(-90), node, next), "out of the radius: not captured");
        // Arrival: ice column (143, -2118), node (149, -2119), the next waypoint (136, -2090).
        BoatAutopilot.PoseTest d = shuttleHullTest(143, -2118);
        int[] dnode = {149, -2119};
        int[] dnext = {136, -2090};
        check(!BoatAutopilot.captureHere(d, 149.0, -2115.7, Math.toRadians(-12), dnode, dnext), "arrival: no early capture, the turn is blocked");
        check(BoatAutopilot.captureHere(d, 149.5, -2118.5, 0, dnode, dnext), "arrival: captured at the node");
        // A free sea captures within the radius, as before; a last waypoint has no turn to judge.
        BoatAutopilot.PoseTest free = (x, z, th) -> false;
        check(BoatAutopilot.captureHere(free, 13.4, 10.5, 0, new int[] {10, 10}, new int[] {10, 40}), "free sea: captured within the radius");
        check(!BoatAutopilot.captureHere(free, 13.7, 10.5, 0, new int[] {10, 10}, new int[] {10, 40}), "free sea: not beyond the radius");
        check(BoatAutopilot.captureHere(t, 3044.6, -3044.3, 0, node, null), "no next waypoint: captured within the radius");
    }

    private static void boatBackOff() throws Exception {
        // Open sea wedge: centre (3044.6, -3044.3), heading -40.4 deg, the target heads toward (3064, -3080).
        BoatAutopilot.PoseTest t = shuttleHullTest(3044, -3049);
        double target = BoatAutopilot.targetHeading(3044.6, -3044.3, new int[] {3064, -3080});
        eq(2.0, BoatAutopilot.backOff(t, 3044.6, -3044.3, Math.toRadians(-40.4), target));
        // Arrival wedge: centre (149, -2115.7), heading 61.6 deg, the target heads toward the berth (136, -2090).
        BoatAutopilot.PoseTest d = shuttleHullTest(143, -2118);
        eq(1.0, BoatAutopilot.backOff(d, 149.0, -2115.7, Math.toRadians(61.6), BoatAutopilot.targetHeading(149.0, -2115.7, new int[] {136, -2090})));
        check(BoatAutopilot.wedged(t, 3044.6, -3044.3, Math.toRadians(-40.4), target), "open sea wedge: turn and way ahead blocked");
        check(BoatAutopilot.wedged(d, 149.0, -2115.7, Math.toRadians(61.6),
                BoatAutopilot.targetHeading(149.0, -2115.7, new int[] {136, -2090})), "arrival wedge: turn and way ahead blocked");
        check(!BoatAutopilot.wedged(t, 3047.5, -3044.5, Math.toRadians(-90), BoatAutopilot.targetHeading(3047.5, -3044.5, new int[] {3064, -3080})),
                "at the node the turn is clear: not wedged");
        // Bow free, turn blocked by a column abeam: still wedged (the controller never moves ahead while turning), and backing off helps.
        BoatAutopilot.PoseTest abeam = (x, z, th) -> th > 0.3 && z < 2.5;
        check(!abeam.blocked(0.5, -0.5, 0), "the bow is free");
        check(BoatAutopilot.wedged(abeam, 0.5, 0.5, 0, 1.0), "bow free, turn blocked: wedged");
        eq(2.0, BoatAutopilot.backOff(abeam, 0.5, 0.5, 0, 1.0));
        // Persistence: a refused yaw step may still turn the hull around the unmoved pivot, so one refusal is not a wedge.
        check(!BoatAutopilot.wedgedFor(1, 0), "one refused step: not wedged");
        check(!BoatAutopilot.wedgedFor(3, 0), "three refused steps: not wedged yet");
        check(BoatAutopilot.wedgedFor(4, 0), "four refused steps, heading unchanged: wedged");
        check(BoatAutopilot.wedgedFor(4, Math.toRadians(0.2)), "four refused steps, heading moved 0.2 deg: wedged");
        check(!BoatAutopilot.wedgedFor(4, Math.toRadians(3)), "four refused steps, heading moved 3 deg: not wedged");
        // Per-trip cap and position guard of the back-off.
        check(BoatAutopilot.backOffAllowed(0, 10, 10, false, 0, 0, 0, 50), "the first back-off of a trip is allowed");
        check(!BoatAutopilot.backOffAllowed(BoatAutopilot.BACK_TRIP_MAX, 10, 10, false, 0, 0, 0, 50), "trip cap reached");
        check(BoatAutopilot.backOffAllowed(BoatAutopilot.BACK_TRIP_MAX - 1, 10, 10, false, 0, 0, 0, 50), "one under the cap");
        check(!BoatAutopilot.backOffAllowed(1, 14, 10, true, 10, 10, 60, 60), "near the last back-off point, no progress: refused");
        check(!BoatAutopilot.backOffAllowed(1, 14, 10, true, 10, 10, 60, 58), "near the last back-off point, 2 blocks of progress: refused");
        check(BoatAutopilot.backOffAllowed(1, 14, 10, true, 10, 10, 60, 50), "near the last back-off point, real progress: allowed");
        check(BoatAutopilot.backOffAllowed(1, 30, 10, true, 10, 10, 60, 60), "far from the last back-off point: allowed");
        // A step the move would refuse (a pocket of the hull sampling) is jumped over, never beyond what is left.
        BoatAutopilot.PoseTest pocket = (x, z, th) -> z > 0.2 && z < 0.4;
        near(0.6, BoatAutopilot.hop(pocket, 0, 0, 0, 1, 0, 0.3, 2.0));
        near(0.3, BoatAutopilot.hop((x, z, th) -> false, 0, 0, 0, 1, 0, 0.3, 2.0));
        near(0.1, BoatAutopilot.hop((x, z, th) -> false, 0, 0, 0, 1, 0, 0.3, 0.1));
        near(0.3, BoatAutopilot.hop((x, z, th) -> true, 0, 0, 0, 1, 0, 0.3, 2.0));
        // Nothing around is free: no back-off. A free sea does not need one: the smallest distance already clears the turn.
        BoatAutopilot.PoseTest wall = (x, z, th) -> true;
        eq(-1.0, BoatAutopilot.backOff(wall, 0.5, 0.5, 0, 1.0));
        BoatAutopilot.PoseTest free = (x, z, th) -> false;
        eq(1.0, BoatAutopilot.backOff(free, 0.5, 0.5, 0, 1.0));
        // Heading 0: astern is +z. Only the third block astern clears the turn, so the answer is 3.
        BoatAutopilot.PoseTest far = (x, z, th) -> th > 0.2 && z < 3.0;
        eq(3.0, BoatAutopilot.backOff(far, 0.5, 0.5, 0, 1.0));
        // The turn is blocked until 3.5 blocks astern, but the hull cannot go back that far (a wall at the stern): no back-off.
        BoatAutopilot.PoseTest stern = (x, z, th) -> (th > 0.2 && z < 3.5) || (z > 2.2 && th == 0);
        eq(-1.0, BoatAutopilot.backOff(stern, 0.5, 0.5, 0, 1.0));
    }

    private static void boatSideArrivalClear() {
        // A sweep blocked only at mid-course: the end pose alone is free.
        BoatAutopilot.PoseTest mid = (x, z, th) -> Math.abs(th - 0.5) < 0.1;
        check(!mid.blocked(0, 0, 1.0), "the berth pose alone is free");
        check(!BoatAutopilot.sideArrivalClear(mid, 0, 0, 0, 1.0), "sideways arrival refused: the turn sweep hits");
        check(BoatAutopilot.sideArrivalClear(mid, 0, 0, 0, 0.3), "sideways arrival kept: the sweep is clear");
        check(BoatAutopilot.sideArrivalClear((x, z, th) -> false, 0, 0, 0, 3.0), "free sea");
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
        int s = BoatBerth.BERTH_STANDOFF;
        org.joml.Vector3i berth = new org.joml.Vector3i(100, 60, 200);
        // the pre-berth point lies along the station's +z axis, rotated with the station
        org.joml.Vector3i p0 = BoatBerth.preBerthCell(com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, berth, s);
        check(p0.x == 100 && p0.z == 200 + s && p0.y == 60, "rotation None: +z");
        org.joml.Vector3i p90 = BoatBerth.preBerthCell(com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.Ninety, berth, s);
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
        java.util.List<int[]> in = BoatBerth.arriveRoute(route, berth, pre, s);
        check(in.size() == 3, "arrive: 2 far waypoints + pre-berth, got " + in.size());
        check(in.get(2)[0] == 100 && in.get(2)[1] == 200 + s, "arrive: ends at the pre-berth point");
        java.util.List<int[]> in2 = BoatBerth.arriveRoute(java.util.List.of(new int[] {100, 300}, new int[] {100, 200 + s}, new int[] {100, 200}),
                berth, pre, s);
        check(in2.size() == 2, "arrive: no duplicate pre-berth point");
        // departure route: starts at the pre-berth point, nearer waypoints dropped
        java.util.List<int[]> out = BoatBerth.departRoute(java.util.List.of(new int[] {100, 200}, new int[] {100, 200 + s - 2}, new int[] {100, 230},
                new int[] {100, 300}), berth, pre, s);
        check(out.size() == 3 && out.get(0)[1] == 200 + s && out.get(1)[1] == 230, "depart: pre-berth first, then the far waypoints");
        java.util.List<int[]> out2 = BoatBerth.departRoute(java.util.List.of(new int[] {100, 200 + s}, new int[] {100, 300}), berth, pre, s);
        check(out2.size() == 2, "depart: no duplicate pre-berth point");
        // abandoning the sideways arrival resumes at the first dropped waypoint
        check(BoatBerth.firstDropped(route, in, 0) == 2, "resume at the first dropped waypoint (got " + BoatBerth.firstDropped(route, in, 0) + ")");
        check(BoatBerth.firstDropped(route, java.util.List.of(new int[] {1, 1}), 0) == 0, "resume index never negative");
        // détours inserted after the trim are counted once: full of 5, trimmed of 6 with 2 détours resumes at 3
        java.util.List<int[]> trimmed6 = java.util.List.of(new int[] {1, 1}, new int[] {2, 2}, new int[] {3, 3}, new int[] {4, 4}, new int[] {5, 5},
                new int[] {6, 6});
        java.util.List<int[]> full5 = java.util.List.of(new int[] {1, 1}, new int[] {2, 2}, new int[] {3, 3}, new int[] {4, 4}, new int[] {7, 7});
        eq(3, BoatBerth.firstDropped(full5, trimmed6, 2));
        eq(3, BoatBerth.firstDropped(full5, java.util.List.of(new int[] {1, 1}, new int[] {2, 2}, new int[] {3, 3}, new int[] {9, 9}), 0));
        eq(0, BoatBerth.firstDropped(full5, java.util.List.of(new int[] {1, 1}), 3));
        // a voyage list keeps both ends: departure slide-out then arrival at the destination pre-berth point
        org.joml.Vector3i dest = new org.joml.Vector3i(100, 60, 900);
        org.joml.Vector3i destPre = new org.joml.Vector3i(100, 60, 900 + s);
        java.util.List<int[]> voyage = java.util.List.of(new int[] {100, 200}, new int[] {100, 300}, new int[] {100, 880}, new int[] {100, 900});
        java.util.List<int[]> both = BoatBerth.arriveRoute(BoatBerth.departRoute(voyage, berth, pre, s), dest, destPre, s);
        check(both.get(0)[1] == 200 + s, "voyage: starts at the departure pre-berth point");
        check(both.get(both.size() - 1)[1] == 900 + s, "voyage: ends at the arrival pre-berth point");
        check(both.size() == 4, "voyage: pre, 300, 880, arrival pre (" + both.size() + ")");
        // the seaward sign is pinned for every rotation: pre-berth direction = from the pier end toward the berth, rotated
        int lateral = Deployables.BOAT_STATION_BERTH_ORIGIN[2] - Deployables.BOAT_STATION_PIER_END[2];
        for (com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation r : com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.values()) {
            org.joml.Vector3i want = r.rotateYaw(new org.joml.Vector3i(0, 0, lateral), new org.joml.Vector3i());
            org.joml.Vector3i got = BoatBerth.preBerthCell(r, new org.joml.Vector3i(), s);
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
        org.joml.Vector3d p = BoatBerth.berthPivot(s);
        check(Math.abs(p.x - 114.5) < 1e-6 && Math.abs(p.z - 200.5) < 1e-6, "pivot " + p);
        org.joml.Vector3d c = BoatBerth.berthCentre(s);
        check(Math.abs(c.x - 114.5) < 1e-6 && Math.abs(c.y - 64) < 1e-6 && Math.abs(c.z - 199.5) < 1e-6, "centre " + c);
        eq(new Vector3i(114, 64, 199), BoatBerth.berthCentreCell(s));
        // Turning keeps the centre fixed: the pivot is found back from the centre at the berth heading.
        org.joml.Vector3d back = AirshipManager.get().pivotFromCenter(Deployables.STEAMBOAT_PUBLIC, c, BoatBerth.berthTheta(s));
        check(back.distance(p) < 1e-6, "pivot from centre " + back);
        // Station None: berth origin {1, -1, 14} -> (101, 63, 214), boat rotation Ninety ((x, z) -> (z, -x)), theta = pi / 2.
        // Pivot (0, 1, -1) -> (-1, 1, 0): cell (100, 64, 214), pivot (100.5, 64, 214.5). Centre = origin column: (101.5, 64, 214.5).
        TransitNetwork.Station n = new TransitNetwork.Station("N", "w", new Vector3i(100, 64, 200), Rotation.None, null, null,
                new ArrayList<>(), Deployables.BOAT_STATION_KIND);
        eq(Rotation.Ninety, BoatBerth.berthRotation(n));
        org.joml.Vector3d pn = BoatBerth.berthPivot(n);
        check(Math.abs(pn.x - 100.5) < 1e-6 && Math.abs(pn.z - 214.5) < 1e-6, "Ninety berth pivot " + pn);
        org.joml.Vector3d cn = BoatBerth.berthCentre(n);
        check(Math.abs(cn.x - 101.5) < 1e-6 && Math.abs(cn.y - 64) < 1e-6 && Math.abs(cn.z - 214.5) < 1e-6, "Ninety berth centre " + cn);
        check(AirshipManager.get().pivotFromCenter(Deployables.STEAMBOAT_PUBLIC, cn, BoatBerth.berthTheta(n)).distance(pn) < 1e-6,
                "Ninety pivot from centre");
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

    private static void skyLogDiscoveryRadius() {
        check(SkyLog.stationInReach(0, 0, 39, 0, StationDiscovery.DISCOVERY_RADIUS), "39 blocks is in the discovery reach");
        check(SkyLog.stationInReach(0, 0, 40, 0, StationDiscovery.DISCOVERY_RADIUS), "exactly 40 blocks is in the discovery reach");
        check(!SkyLog.stationInReach(0, 0, 41, 0, StationDiscovery.DISCOVERY_RADIUS), "41 blocks is out of the discovery reach");
        check(!SkyLog.stationInReach(0, 0, 41, 0), "41 blocks stays out of the vehicle reach");
        check(SkyLog.stationInReach(0, 0, 40, 0, SkyLog.STATION_REACH), "vehicle reach keeps 40 blocks");
        eq(40.0, StationDiscovery.DISCOVERY_RADIUS);
    }

    private static void iceBreakable() {
        check(IceBreaker.isBreakableIce("Rock_Ice"), "sea ice breaks");
        check(IceBreaker.isBreakableIce("Rock_Ice_Permafrost"), "permafrost ice breaks");
        check(!IceBreaker.isBreakableIce("Rock_Ice_Icicles"), "icicles do not");
        check(!IceBreaker.isBreakableIce("Soil_Snow"), "snow does not");
        check(!IceBreaker.isBreakableIce("Rock_Stone"), "stone does not");
        check(!IceBreaker.isBreakableIce(null), "no block does not");
    }

    /** Column over y 113..121 (surfaceY 114, topY 120), kinds given from y 113; the default is the open sea. */
    private static WaterRoute.Cell iceCol(java.util.Map<Integer, IceBreaker.Kind> over) {
        return IceBreaker.column(y -> {
            IceBreaker.Kind k = over.get(y);
            if (k != null) {
                return k;
            }
            return y <= 114 ? IceBreaker.Kind.WATER : IceBreaker.Kind.AIR;
        }, 113, 114, 120);
    }

    private static void iceColumn() {
        IceBreaker.Kind ice = IceBreaker.Kind.ICE;
        eq(WaterRoute.Cell.WATER, iceCol(java.util.Map.of()));
        eq(WaterRoute.Cell.WATER, iceCol(java.util.Map.of(114, ice, 115, ice, 116, ice)));
        java.util.Map<Integer, IceBreaker.Kind> berg = new java.util.HashMap<>();
        for (int y = 114; y <= 117; y++) {
            berg.put(y, ice);
        }
        eq(WaterRoute.Cell.BLOCKED, iceCol(berg));
        eq(WaterRoute.Cell.BLOCKED, iceCol(java.util.Map.of(115, IceBreaker.Kind.SOLID)));
        eq(WaterRoute.Cell.BLOCKED, iceCol(java.util.Map.of(116, IceBreaker.Kind.WATER)));
        eq(WaterRoute.Cell.BLOCKED, iceCol(java.util.Map.of(114, IceBreaker.Kind.AIR)));
        eq(WaterRoute.Cell.UNKNOWN, iceCol(java.util.Map.of(117, IceBreaker.Kind.UNLOADED)));
        eq(WaterRoute.Cell.WATER, iceCol(java.util.Map.of(121, IceBreaker.Kind.UNLOADED, 114, ice)));
        // a floe rises at most MAX_FLOE_HEIGHT above the surface, even when the hull is taller
        eq(WaterRoute.Cell.BLOCKED, iceCol(java.util.Map.of(117, IceBreaker.Kind.UNLOADED, 118, IceBreaker.Kind.SOLID)));
        // ice only above the hull top makes no ice inside the slice: the hull passes under it
        eq(WaterRoute.Cell.WATER, iceCol(java.util.Map.of(121, ice)));
        eq(WaterRoute.Cell.WATER, iceCol(java.util.Map.of(121, ice, 117, IceBreaker.Kind.AIR)));
    }

    /**
     * The column probe and the hull test read a column the same way: whenever the hull test blocks a cell of the hull range of some
     * column, the probe calls the column blocked. Random columns over surfaceY - 2 .. topY + 3, for the hulls of both steamboats
     * (bottom, surface and top relative to the water level: shuttle 0 / 0 / 5, player's boat -2 / 0 / 11), wet cells being those at or
     * under the surface.
     */
    private static void iceProbeMatchesHull() {
        IceBreaker.Kind[] pool = {IceBreaker.Kind.WATER, IceBreaker.Kind.ICE, IceBreaker.Kind.AIR, IceBreaker.Kind.SOLID};
        int[][] hulls = {{0, 0, 5}, {-2, 0, 11}};
        java.util.Random rnd = new java.util.Random(20261029L);
        int blockedColumns = 0;
        for (int[] hull : hulls) {
            for (int n = 0; n < 20000; n++) {
                int surface = 60 + rnd.nextInt(60);
                int bottom = surface + hull[0];
                int top = surface + hull[2];
                int lo = surface - 2;
                IceBreaker.Kind[] col = new IceBreaker.Kind[top + 3 - lo + 1];
                for (int i = 0; i < col.length; i++) {
                    int y = lo + i;
                    // mostly open sea and air, with ice and a few other cells, so that every mix comes up
                    int roll = rnd.nextInt(10);
                    col[i] = roll < 6 ? (y <= surface ? IceBreaker.Kind.WATER : IceBreaker.Kind.AIR)
                            : roll < 9 ? IceBreaker.Kind.ICE : pool[rnd.nextInt(pool.length)];
                }
                // beyond the generated range: open sea under it, air above it (the hull test reads a few cells under a wet empty cell)
                IceBreaker.CellReader reader = (x, y, z) -> y < lo ? IceBreaker.Kind.WATER : y - lo < col.length ? col[y - lo] : IceBreaker.Kind.AIR;
                for (int y = bottom; y <= top; y++) {
                    if (IceBreaker.hullCellBlocks(reader, 0, y, 0, y <= surface, surface)) {
                        blockedColumns++;
                        WaterRoute.Cell probe = IceBreaker.column(y2 -> col[y2 - lo], bottom, surface, top);
                        check(probe == WaterRoute.Cell.BLOCKED, "the hull blocks at y " + (y - surface) + " over the water but the probe reads "
                                + probe + ": " + java.util.Arrays.toString(col));
                        break;
                    }
                }
            }
        }
        check(blockedColumns > 1000, "the generated columns block the hull often enough, got " + blockedColumns);
    }

    /** Fake world column at x = 0, z = 0: kinds by height, the default is open sea under y 114 and air above. */
    private static IceBreaker.CellReader iceWorld(java.util.Map<Integer, IceBreaker.Kind> over) {
        return (x, y, z) -> {
            IceBreaker.Kind k = over.get(y);
            if (k != null) {
                return k;
            }
            return y <= 114 ? IceBreaker.Kind.WATER : IceBreaker.Kind.AIR;
        };
    }

    private static boolean iceBlocks(java.util.Map<Integer, IceBreaker.Kind> over, int y, boolean wet) {
        return IceBreaker.hullCellBlocks(iceWorld(over), 0, y, 0, wet, 114);
    }

    private static void iceHullCell() {
        IceBreaker.Kind ice = IceBreaker.Kind.ICE;
        IceBreaker.Kind air = IceBreaker.Kind.AIR;
        IceBreaker.Kind stone = IceBreaker.Kind.SOLID;
        java.util.Map<Integer, IceBreaker.Kind> none = java.util.Map.of();
        check(!iceBlocks(java.util.Map.of(113, ice), 113, true), "floe ice under the waterline gives way");
        check(!iceBlocks(java.util.Map.of(116, ice), 116, false), "floe ice above the waterline gives way");
        check(iceBlocks(java.util.Map.of(116, ice, 117, ice), 116, false), "iceberg ice blocks");
        check(iceBlocks(java.util.Map.of(113, ice, 117, ice), 113, true), "iceberg ice under the waterline blocks");
        check(!iceBlocks(java.util.Map.of(114, air), 114, true), "broken cell with water under it floats");
        check(!iceBlocks(java.util.Map.of(114, air, 113, ice), 114, true), "broken cell over ice floats");
        check(iceBlocks(java.util.Map.of(114, air, 113, stone), 114, true), "wet empty cell over stone blocks");
        check(iceBlocks(java.util.Map.of(114, air, 113, air, 112, air, 111, air, 110, air), 114, true),
                "wet empty cell with no water within the scan blocks");
        check(!iceBlocks(java.util.Map.of(114, air, 113, air, 112, air), 114, true), "two stacked empty cells over water float");
        check(!iceBlocks(java.util.Map.of(114, air, 113, air, 112, air, 111, air), 114, true), "three stacked empty cells over water float");
        check(iceBlocks(java.util.Map.of(116, stone), 116, false), "dry stone blocks");
        check(!iceBlocks(java.util.Map.of(), 116, false), "dry air is free");
        check(iceBlocks(none, 114, false), "water above the waterline blocks");
        check(!iceBlocks(none, 114, true), "water under the waterline is free");
        check(!iceBlocks(java.util.Map.of(114, IceBreaker.Kind.UNLOADED), 114, true), "unloaded never blocks");
    }

    /** Footprint: a band of 5 columns (z 0..4) along x, 200 long. */
    private static final IceBreaker.ColumnTest ICE_BAND = (x, z) -> x >= 0 && x < 200 && z >= 0 && z <= 4;

    private static void iceMarginRing() {
        int in2 = 0;
        int out2 = 0;
        for (int x = 20; x < 180; x++) {
            check(IceBreaker.inIceMargin(ICE_BAND, x, -1), "distance 1 below is in the margin");
            check(IceBreaker.inIceMargin(ICE_BAND, x, 5), "distance 1 above is in the margin");
            check(!IceBreaker.inIceMargin(ICE_BAND, x, -3), "distance 3 is out");
            check(!IceBreaker.inIceMargin(ICE_BAND, x, 7), "distance 3 above is out");
            check(!IceBreaker.inIceMargin(ICE_BAND, x, -9), "far is out");
            check(!IceBreaker.inIceMargin(ICE_BAND, x, 2), "inside the footprint is not margin");
            if (IceBreaker.inIceMargin(ICE_BAND, x, -2)) {
                in2++;
            } else {
                out2++;
            }
        }
        check(in2 > 20 && out2 > 20, "distance 2 is irregular: in " + in2 + ", out " + out2);
    }

    private static void iceMarginStable() {
        for (int x = -30; x < 30; x++) {
            for (int z = -5; z < 10; z++) {
                boolean first = IceBreaker.inIceMargin(ICE_BAND, x, z);
                for (int i = 0; i < 3; i++) {
                    eq(first, IceBreaker.inIceMargin(ICE_BAND, x, z));
                }
            }
        }
    }

    private static void iceMarginCell() {
        IceBreaker.Kind ice = IceBreaker.Kind.ICE;
        IceBreaker.Kind stone = IceBreaker.Kind.SOLID;
        check(IceBreaker.marginCellBreaks(iceWorld(java.util.Map.of(113, ice)), 0, 113, 0, 114), "floe ice over water breaks");
        check(!IceBreaker.marginCellBreaks(
                (x, y, z) -> y == 113 ? ice : y > 113 ? IceBreaker.Kind.AIR : stone, 0, 113, 0, 114), "ice on stone stays");
        check(!IceBreaker.marginCellBreaks(iceWorld(java.util.Map.of(113, ice, 117, ice)), 0, 113, 0, 114), "iceberg column stays");
        check(!IceBreaker.marginCellBreaks(iceWorld(java.util.Map.of(113, ice, 117, ice)), 0, 117, 0, 114), "ice at surface + 3 never breaks");
        check(IceBreaker.marginCellBreaks(iceWorld(java.util.Map.of(116, ice, 115, ice)), 0, 116, 0, 114), "ice at surface + 2 breaks");
        check(!IceBreaker.marginCellBreaks(iceWorld(java.util.Map.of()), 0, 113, 0, 114), "water is not ice");
        check(IceBreaker.marginCellBreaks(iceWorld(java.util.Map.of(113, ice, 112, ice)), 0, 113, 0, 114), "stacked ice over water breaks");
    }

    private static void floeMaxHeight() {
        IceBreaker.Kind ice = IceBreaker.Kind.ICE;
        eq(2, IceBreaker.MAX_FLOE_HEIGHT);
        java.util.Map<Integer, IceBreaker.Kind> floe = new java.util.HashMap<>();
        java.util.Map<Integer, IceBreaker.Kind> berg = new java.util.HashMap<>();
        for (int y = 112; y <= 116; y++) {
            floe.put(y, ice);
            berg.put(y, ice);
        }
        berg.put(117, ice);
        // ice up to surfaceY + 2: the hull cell does not block, the column reads as water then empty
        check(!iceBlocks(floe, 116, false), "floe top cell does not block");
        check(!iceBlocks(floe, 114, true), "floe cell at the surface does not block");
        eq(WaterRoute.Cell.WATER, iceCol(floe));
        eq(0, IceBreaker.placementState(iceWorld(floe), 0, 116, 0, false, 114));
        eq(1, IceBreaker.placementState(iceWorld(floe), 0, 114, 0, true, 114));
        // ice reaching surfaceY + 3: an iceberg
        check(iceBlocks(berg, 116, false), "iceberg cell blocks");
        check(iceBlocks(berg, 113, true), "iceberg cell under the surface blocks");
        eq(WaterRoute.Cell.BLOCKED, iceCol(berg));
        eq(2, IceBreaker.placementState(iceWorld(berg), 0, 116, 0, false, 114));
        check(!IceBreaker.marginCellBreaks(iceWorld(berg), 0, 113, 0, 114), "iceberg margin cell stays");
        check(!IceBreaker.marginCellBreaks(iceWorld(berg), 0, 117, 0, 114), "ice at surface + 3 never breaks");
        // loose ice above the cap is not a floe cell
        check(iceBlocks(java.util.Map.of(118, ice), 118, false), "ice above the floe cap blocks the hull");
    }

    private static void icePlacementState() {
        IceBreaker.Kind ice = IceBreaker.Kind.ICE;
        IceBreaker.Kind air = IceBreaker.Kind.AIR;
        eq(1, IceBreaker.placementState(iceWorld(java.util.Map.of(113, ice)), 0, 113, 0, true, 114));
        eq(0, IceBreaker.placementState(iceWorld(java.util.Map.of(116, ice)), 0, 116, 0, false, 114));
        eq(2, IceBreaker.placementState(iceWorld(java.util.Map.of(116, ice, 117, ice)), 0, 116, 0, false, 114));
        eq(1, IceBreaker.placementState(iceWorld(java.util.Map.of(114, air)), 0, 114, 0, true, 114));
        eq(2, IceBreaker.placementState(iceWorld(java.util.Map.of(114, IceBreaker.Kind.SOLID)), 0, 114, 0, true, 114));
        eq(0, IceBreaker.placementState(iceWorld(java.util.Map.of(114, air, 113, IceBreaker.Kind.SOLID)), 0, 114, 0, true, 114));
        eq(1, IceBreaker.placementState(iceWorld(java.util.Map.of()), 0, 114, 0, true, 114));
    }

    private static void stationMarkerDisplay() {
        eq(StationMarkerProvider.Display.NEARBY, StationMarkerProvider.display(false, false, true));
        eq(StationMarkerProvider.Display.NEARBY, StationMarkerProvider.display(false, false, false));
        eq(StationMarkerProvider.Display.KNOWN_SHAREABLE, StationMarkerProvider.display(true, false, true));
        eq(StationMarkerProvider.Display.KNOWN, StationMarkerProvider.display(true, true, true));
        eq(StationMarkerProvider.Display.KNOWN, StationMarkerProvider.display(true, false, false));
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

    private static void boatAutopilotHeadingChange() {
        near(Math.toRadians(4), BoatAutopilot.headingChange(Math.toRadians(178), Math.toRadians(-178))); // across +-pi
        near(Math.toRadians(4), BoatAutopilot.headingChange(Math.toRadians(-178), Math.toRadians(178)));
        near(0.5, BoatAutopilot.headingChange(1.0, 1.5));
        near(0.0, BoatAutopilot.headingChange(0.3, 0.3 + 2 * Math.PI)); // a full turn is no change
    }

    private static void waterLogCollapse() {
        List<int[]> path = List.of(new int[]{1, 1}, new int[]{11, 61}, new int[]{71, 71});
        eq(3, WaterLog.collapse(path).size());
        List<int[]> run = List.of(new int[]{1, 1}, new int[]{3, 3}, new int[]{5, 5}, new int[]{7, 5});
        eq(3, WaterLog.collapse(run).size()); // a straight run is merged, the turn kept
        eq(3, WaterLog.collapse(List.of(new int[]{1, 1}, new int[]{5, 1}, new int[]{3, 1})).size()); // going back is a turn
    }

    private static void voyageCorridorFromLog() {
        List<int[]> path = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) path.add(new int[]{2 * i, 0}); // 100 log cells of 2 blocks
        List<int[]> c = ShipVoyage.corridorFromLog(path);
        eq(6, c.size()); // cells 0, 24, 48, 72, 96 and the last one (99)
        for (int i = 1; i < 5; i++) eq(48, c.get(i)[0] - c.get(i - 1)[0]); // a point every 48 blocks
        check(java.util.Arrays.equals(new int[]{0, 0}, c.get(0)), "first point kept");
        check(java.util.Arrays.equals(new int[]{198, 0}, c.get(c.size() - 1)), "last point kept");
        eq(0, ShipVoyage.corridorFromLog(List.of()).size());
        eq(1, ShipVoyage.corridorFromLog(List.of(new int[]{5, 5})).size());
        List<int[]> exact = new java.util.ArrayList<>();
        for (int i = 0; i <= 24; i++) exact.add(new int[]{2 * i, 0});
        eq(2, ShipVoyage.corridorFromLog(exact).size()); // the last cell is already a multiple: not doubled
    }

    private static void voyageCorridorEndsAtGoal() {
        List<int[]> chart = List.of(new int[]{8, 8}, new int[]{56, 8});
        List<int[]> a = ShipVoyage.endingAt(chart, 60, 11);
        eq(3, a.size());
        check(java.util.Arrays.equals(new int[]{60, 11}, a.get(2)), "the goal column ends the corridor");
        eq(2, ShipVoyage.endingAt(chart, 56, 8).size()); // already there
        eq(chart.size(), 2); // the input is not changed
    }

    private static void voyageCorridorReadsLogThenChart() {
        WaterLog w = new WaterLog();
        w.record(1, 1);
        w.record(201, 1); // the cells between are added along the line
        List<int[]> c = ShipVoyage.corridorNow("no-such-world", w, java.util.Set.of(), 1, 1, 201, 1);
        check(c != null && c.size() >= 2, "a log path gives the corridor");
        check(java.util.Arrays.equals(new int[]{201, 1}, c.get(c.size() - 1)), "it ends on the goal column");
        check(ShipVoyage.corridorNow("no-such-world", new WaterLog(), java.util.Set.of(), 1, 1, 201, 1) == null,
                "no log path and no chart: no corridor");
        check(ShipVoyage.corridorNow("no-such-world", null, java.util.Set.of(), 1, 1, 201, 1) == null, "no log at all: no corridor");
    }

    private static void voyageChartCorridor() {
        ChartGrid g = new ChartGrid("~~~~~~~~~", "~~~~~~~~~", "~~~~~~~~~");
        SeaChart chart = routeChart(g, 9, 3);
        RouteView view = routeView(List.of());
        int gx = 8 * 16 + 8, gz = 24;
        List<int[]> c = ShipVoyage.corridorNow(chart, view, new WaterLog(), java.util.Set.of(), 8, 24, gx, gz);
        check(c != null && c.size() >= 2, "no log path: a chart corridor");
        check(java.util.Arrays.equals(new int[]{gx, gz}, c.get(c.size() - 1)), "it ends on the goal cell centre");
        check(ShipVoyage.corridorNow((SeaChart) null, view, null, java.util.Set.of(), 8, 24, gx, gz) == null, "no chart: none");
    }

    private static void voyageTrackBackoff() {
        List<int[]> track = new java.util.ArrayList<>();
        int[] home = {0, 0};
        for (int i = 0; i < 10; i++) {
            check(ShipVoyage.noteTrack(track, i * 48.0, 0), "a point every 48 blocks");
            check(!ShipVoyage.noteTrack(track, i * 48.0 + 47, 0), "not before 48 blocks"); // not added
        }
        eq(10, track.size());
        List<int[]> back = ShipVoyage.backoffPoints(track, track.size(), home);
        eq(10, back.size()); // the departure point is the first track point: not repeated
        for (int i = 0; i < 10; i++) eq((9 - i) * 48, back.get(i)[0]); // reverse order
        check(java.util.Arrays.equals(home, back.get(back.size() - 1)), "ends at the departure column");
    }

    private static void voyageFinalInsideWindow() {
        int reach = ShuttleNav.VOYAGE_FINAL_REACH;
        eq(LocalNav.WINDOW / 2 - HullClearance.SHIP.leg() - WaterRoute.GRID, reach);
        check(ShuttleNav.voyageFinal(reach, -reach), "the window corner is final");
        check(!ShuttleNav.voyageFinal(reach + 1, 0), "one block farther on x is not");
        check(!ShuttleNav.voyageFinal(0, -reach - 1), "one block farther on z is not");
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

    private static void generatorAvailability() {
        eq(GeneratorProbe.Availability.WAIT, GeneratorProbe.availability(false, null));
        eq(GeneratorProbe.Availability.OFF, GeneratorProbe.availability(true, null));
        eq(GeneratorProbe.Availability.OFF, GeneratorProbe.availability(false, new Object()));
    }

    private static void chartAvailabilityIgnoresDeleteOnRemove() {
        eq(GeneratorProbe.Availability.WAIT, GeneratorProbe.chartAvailability(null));
        eq(GeneratorProbe.Availability.OFF, GeneratorProbe.chartAvailability("x"));
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

    static void generationCyclesGone() throws Exception {
        Path src = Path.of("plugin/src/main/java/com/keepkoolz/orbishorizon");
        check(!Files.readString(src.resolve("TransitSettings.java")).contains("GenerationListener"), "TransitSettings free of GenerationListener");
        String cmd = Files.readString(src.resolve("StationCommand.java"));
        String despawn = cmd.substring(cmd.indexOf("class Despawn"), cmd.indexOf("class Tp"));
        check(!despawn.contains("GeneratedStations"), "Despawn free of GeneratedStations");
    }
}
