package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Plain test harness: one static method per test, no library. */
public class TransitTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        run("nameRules", TransitTests::nameRules);
        run("componentGuard", TransitTests::componentGuard);
        run("tentContentsRoundTrip", TransitTests::tentContentsRoundTrip);
        run("tentContentsMissing", TransitTests::tentContentsMissing);
        run("tentContentsVersion", TransitTests::tentContentsVersion);
        run("stationFileRoundTrip", TransitTests::stationFileRoundTrip);
        run("corruptFileIsKept", TransitTests::corruptFileIsKept);
        run("grassLayerCells", TransitTests::grassLayerCells);
        run("settingsRoundTrip", TransitTests::settingsRoundTrip);
        run("reservationIsExclusive", TransitTests::reservationIsExclusive);
        run("queueRules", TransitTests::queueRules);
        run("cruiseAndHolding", TransitTests::cruiseAndHolding);
        run("holdingPoints", TransitTests::holdingPoints);
        run("rerouteTarget", TransitTests::rerouteTarget);
        run("followNoTeleportLoop", TransitTests::followNoTeleportLoop);
        run("randomSeatYawRange", TransitTests::randomSeatYawRange);
        run("viewFilterRules", TransitTests::viewFilterRules);
        run("compassAndTrip", TransitTests::compassAndTrip);
        run("transitViewYaw", TransitTests::transitViewYaw);
        run("groundCageRemaining", TransitTests::groundCageRemaining);
        run("deployPlacement", TransitTests::deployPlacement);
        run("smallTentChest", TransitTests::smallTentChest);
        run("externalView", TransitTests::externalView);
        run("behindHeading", TransitTests::behindHeading);
        run("viewResend", TransitTests::viewResend);
        run("protectionRules", TransitTests::protectionRules);
        run("protectedFileRoundTrip", TransitTests::protectedFileRoundTrip);
        run("registryOwnershipRoundTrip", TransitTests::registryOwnershipRoundTrip);
        run("stationMarkerPoint", TransitTests::stationMarkerPoint);
        run("stationZoneCell", TransitTests::stationZoneCell);
        run("stationZoneCorner", TransitTests::stationZoneCorner);
        run("stationZoneOtherWorld", TransitTests::stationZoneOtherWorld);
        run("ownershipRules", TransitTests::ownershipRules);
        run("ownedMarkerRules", TransitTests::ownedMarkerRules);
        run("mapShareRules", TransitTests::mapShareRules);
        run("gliderJumpPhase", TransitTests::gliderJumpPhase);
        run("gliderExitLocal", TransitTests::gliderExitLocal);
        run("airshipLimitCounts", TransitTests::airshipLimitCounts);
        run("walkLegsAisle", TransitTests::walkLegsAisle);
        run("staleEntryUnknownIsPresent", TransitTests::staleEntryUnknownIsPresent);
        run("landingOwnerLimit", TransitTests::landingOwnerLimit);
        run("contractMissing", TransitTests::contractMissing);
        run("crewBsonRoundTrip", TransitTests::crewBsonRoundTrip);
        run("crewPostAfterReload", TransitTests::crewPostAfterReload);
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
        run("mapHolderRule", TransitTests::mapHolderRule);
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
        System.out.println("TransitTests: " + passed + " OK");
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

    static void contractMissing() {
        Map<String, Integer> have = new HashMap<>(Map.of("Ingredient_Bar_Gold", 31, "Ingredient_Bar_Silver", 5));
        Map<String, Integer> m = PilotContract.missing(have);
        eq(0, m.get("Ingredient_Bar_Gold"));
        eq(15, m.get("Ingredient_Bar_Silver"));
        eq(10, m.get("Ingredient_Life_Essence_Concentrated"));
        eq(3, m.size());
        check(PilotContract.missing(Map.of("Ingredient_Bar_Gold", 30, "Ingredient_Bar_Silver", 20,
                "Ingredient_Life_Essence_Concentrated", 10)).values().stream().allMatch(v -> v == 0), "exact price is enough");
    }

    private static void crewBsonRoundTrip() {
        CrewRecord c = new CrewRecord(java.util.UUID.fromString("00000000-0000-0000-0000-000000000042"), 77L, CrewRecord.Post.STOOL);
        eq(c, CrewRecord.fromBson(c.toBson()));
        eq("stool", c.toBson().getString("post").getValue());
        eq(null, CrewRecord.fromBson(new org.bson.BsonDocument("post", new org.bson.BsonString("deck"))));
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

    private static void captainMapPhase() {
        eq(-1, CaptainMap.phase(0, 499));
        eq(3, CaptainMap.phase(0, 500));
        eq(3, CaptainMap.phase(0, 1499));
        eq(2, CaptainMap.phase(0, 1500));
        eq(1, CaptainMap.phase(0, 2500));
        eq(1, CaptainMap.phase(0, 3499));
        eq(0, CaptainMap.phase(0, 3500));
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
        org.joml.Vector3d p = AirshipCrew.postPoint(none, AirshipCrew.HELM);
        check(Math.abs(p.x - 100.5) < 1e-6 && Math.abs(p.y - 72.05) < 1e-6 && Math.abs(p.z - 190.5) < 1e-6, "helm None " + p);
        BalloonRegistry.Entry flip = new BalloonRegistry.Entry("w", 100, 64, 200, Rotation.OneEighty, "airship", null);
        org.joml.Vector3d q = AirshipCrew.postPoint(flip, AirshipCrew.HELM);
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

    /** T118: ONLY, HIDE and HIDE_SET visibility rules. */
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

    /** T120: cage left on the ground, balloon home at y 100 with the cage lowered by 12 (target 88). */
    /** T124: external view framing, camera opposite to the given direction, above the aim point, looking down at it. */
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
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
        TransitNetwork.Station b = new TransitNetwork.Station("B", "w", new Vector3i(500, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
        TransitNetwork.Station c = new TransitNetwork.Station("C", "w", new Vector3i(100, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
        org.joml.Vector3d here = new org.joml.Vector3d(450, 110, 0);
        eq(a, TransitAutopilot.rerouteTarget(List.of(a, b), "A", here)); // departure still there: back to it
        eq(b, TransitAutopilot.rerouteTarget(List.of(b, c), "A", here)); // departure removed: nearest
        eq(c, TransitAutopilot.rerouteTarget(List.of(c), "A", here));
        eq(null, TransitAutopilot.rerouteTarget(List.of(), "A", here)); // nothing left: the ground
    }

    static void holdingPoints() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        eq(0, TransitAutopilot.holdIndexFor(List.of(a, b), a));
        eq(1, TransitAutopilot.holdIndexFor(List.of(a, b), b));
        eq(2, TransitAutopilot.holdIndexFor(List.of(a, b), c)); // not registered yet: behind the others
        TransitNetwork.Station s = new TransitNetwork.Station("Port Sud", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
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
        TransitNetwork.Station s = new TransitNetwork.Station("Port Nord", "w", new org.joml.Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
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

    static void settingsRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("transit-settings");
        try {
            TransitSettings.load(dir);
            eq(false, TransitSettings.recipeEnabled());
            TransitSettings.setRecipeEnabled(true);
            eq(true, TransitSettings.recipeEnabled());
            TransitSettings.load(dir);
            eq(true, TransitSettings.recipeEnabled());
            Files.writeString(dir.resolve("transit-settings.json"), "{garbage");
            TransitSettings.load(dir);
            eq(false, TransitSettings.recipeEnabled());
            eq("{garbage", Files.readString(dir.resolve("transit-settings.json.bad")));
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
                Rotation.Ninety, master, owner, new java.util.ArrayList<>(List.of("Gare Sud", "Plaine")));
        net.add(s);
        net.save();
        net.remove(s);
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
    }

    /** T121: the placement helper and the crate table (compared with the crate JSON files). */
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

    private static void landingOwnerLimit() {
        UUID p = UUID.randomUUID();
        check(Ownership.landingOwner(Ownership.NONE, p, "Ann", false).owner() == null, "already owns one: stays unowned");
        check(p.equals(Ownership.landingOwner(Ownership.NONE, p, "Ann", true).owner()), "free: becomes owner");
        check(Ownership.landingOwner(Ownership.NONE, p, "Ann", false).id() != null, "always an id");
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
                {"balloon_transport", "Owned_Balloon_Transport.png"}, {"airship", "Owned_Airship.png"},
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

    static void stationMarkerPoint() {
        org.joml.Vector3d p = StationMarkerProvider.markerPoint(new Vector3i(100, 64, 200), Rotation.None);
        eq(new org.joml.Vector3d(119.5, 71, 200.5), p);
        // Ninety sends the cell (19, 7, 0) to (0, 7, -19), centre (0.5, 7, -18.5)
        org.joml.Vector3d q = StationMarkerProvider.markerPoint(new Vector3i(100, 64, 200), Rotation.Ninety);
        eq(new org.joml.Vector3d(100.5, 71, 181.5), q);
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
        Vector3i stool = new Vector3i(AirshipCrew.STOOL[0], AirshipCrew.STOOL[1], AirshipCrew.STOOL[2]);
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
        eq("recall.occupied", RecallPlanner.recallRefusalKey(true, false, false, true, false));
        eq("recall.already", RecallPlanner.recallRefusalKey(true, false, true, true, true));
        eq("recall.inFlight", RecallPlanner.recallRefusalKey(true, true, false, true, true));
        eq("recall.noAirship", RecallPlanner.recallRefusalKey(false, false, false, true, true));
        eq(null, RecallPlanner.recallRefusalKey(true, false, false, false, true));
        eq("recall.quayBusy", RecallPlanner.recallRefusalKey(true, false, false, false, false));
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
        int[] flat = AirshipRecall.flatten(List.of(new int[] {1, 70, -3}, new int[] {1, 69, -3}));
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
        TransitNetwork.Station s = new TransitNetwork.Station("Port Recall", "w", new org.joml.Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
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
}
