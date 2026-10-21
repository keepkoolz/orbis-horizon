package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
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

    public static void main(String[] args) throws Exception {
        run("usageCountersSnapshot", TransitTests::usageCountersSnapshot);
        run("errorThrottleWindow", TransitTests::errorThrottleWindow);
        run("telemetryWithoutBeacon", TransitTests::telemetryWithoutBeacon);
        run("shutdownFlushSettled", TransitTests::shutdownFlushSettled);
        run("usageNamesMatchDescriptor", TransitTests::usageNamesMatchDescriptor);
        run("wheelAnimationsDeclared", TransitTests::wheelAnimationsDeclared);
        run("redactedErrorHasNoMessage", TransitTests::redactedErrorHasNoMessage);
        run("travelTargetReverse", TransitTests::travelTargetReverse);
        run("pilotSpeedReverse", TransitTests::pilotSpeedReverse);
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
        run("laddersRoundTrip", TransitTests::laddersRoundTrip);
        run("resumeKindRead", TransitTests::resumeKindRead);
        run("nameRules", TransitTests::nameRules);
        run("componentGuard", TransitTests::componentGuard);
        run("tentContentsRoundTrip", TransitTests::tentContentsRoundTrip);
        run("tentContentsMissing", TransitTests::tentContentsMissing);
        run("tentContentsVersion", TransitTests::tentContentsVersion);
        run("stationFileRoundTrip", TransitTests::stationFileRoundTrip);
        run("corruptFileIsKept", TransitTests::corruptFileIsKept);
        run("grassLayerCells", TransitTests::grassLayerCells);
        run("settingsRoundTrip", TransitTests::settingsRoundTrip);
        run("packBackShownTier", TransitTests::packBackShownTier);
        run("reservationIsExclusive", TransitTests::reservationIsExclusive);
        run("queueRules", TransitTests::queueRules);
        run("cruiseAndHolding", TransitTests::cruiseAndHolding);
        run("holdingPoints", TransitTests::holdingPoints);
        run("rerouteTarget", TransitTests::rerouteTarget);
        run("returnStation", TransitTests::returnStation);
        run("leaveSpot", TransitTests::leaveSpot);
        run("followNoTeleportLoop", TransitTests::followNoTeleportLoop);
        run("randomSeatYawRange", TransitTests::randomSeatYawRange);
        run("viewFilterRules", TransitTests::viewFilterRules);
        run("compassAndTrip", TransitTests::compassAndTrip);
        run("transitViewYaw", TransitTests::transitViewYaw);
        run("groundCageRemaining", TransitTests::groundCageRemaining);
        run("deployPlacement", TransitTests::deployPlacement);
        run("waterSurfaceSearch", TransitTests::waterSurfaceSearch);
        run("steamboatFitCheck", TransitTests::steamboatFitCheck);
        run("gliderAllowedKinds", TransitTests::gliderAllowedKinds);
        run("steamboatAnchor", TransitTests::steamboatAnchor);
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
        run("shipFeatureFlags", TransitTests::shipFeatureFlags);
        run("limitIgnoresOtherShips", TransitTests::limitIgnoresOtherShips);
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
        run("legacyLadderAccepted", TransitTests::legacyLadderAccepted);
        run("ashoreSide", TransitTests::ashoreSide);
        run("ashoreDescent", TransitTests::ashoreDescent);
        run("gateRule", TransitTests::gateRule);
        run("campRespawnShouldRescue", TransitTests::campRespawnShouldRescue);
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
        run("wheelHoldAndCycle", TransitTests::wheelHoldAndCycle);
        run("shipTextKeys", TransitTests::shipTextKeys);
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

    static void leaveSpot() {
        eq(AirshipManager.LeaveSpot.DECK, AirshipManager.leaveSpot(true, false));
        eq(AirshipManager.LeaveSpot.GROUND, AirshipManager.leaveSpot(true, true));
        eq(AirshipManager.LeaveSpot.GROUND, AirshipManager.leaveSpot(false, false));
    }

    static void returnStation() {
        TransitNetwork.Station a = new TransitNetwork.Station("A", "w", new Vector3i(0, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
        TransitNetwork.Station b = new TransitNetwork.Station("B", "w", new Vector3i(500, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
        TransitNetwork.Station c = new TransitNetwork.Station("C", "w", new Vector3i(900, 64, 0), Rotation.None, null, null, new java.util.ArrayList<>());
        org.joml.Vector3d here = new org.joml.Vector3d(480, 110, 0); // right above B, the destination
        eq(a, TransitAutopilot.returnStation(List.of(a, b, c), "A", "B", here)); // departure still there: back to it
        eq(c, TransitAutopilot.returnStation(List.of(b, c), "A", "B", here)); // departure removed: nearest other than the destination
        eq(b, TransitAutopilot.returnStation(List.of(b), "A", "B", here)); // the destination only if it is the last one
        eq(null, TransitAutopilot.returnStation(List.of(), "A", "B", here)); // nothing left: the ground
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

    static void packBackShownTier() {
        eq(null, PackBackView.shownTier(List.of("Tool_Pickaxe_Iron")));
        eq(CampTiers.Tier.TENT, PackBackView.shownTier(List.of("Camping_Pack_Bedroll", "Camping_Pack_Tent")));
        eq(CampTiers.Tier.TENT_BIG, PackBackView.shownTier(List.of("Camping_Pack_Tent_Big", "Camping_Pack_Bedroll")));
        eq(null, PackBackView.shownTier(List.of("Camping_Tent_Crate")));
        eq("Items/Camping/Camping_Pack_Tent_Big_Texture.png", PackBackView.textureOf(CampTiers.Tier.TENT_BIG));
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
            eq(true, TransitSettings.packBackView());
            TransitSettings.setPackBackView(false);
            TransitSettings.load(dir);
            eq(false, TransitSettings.packBackView());
            eq(true, TransitSettings.recipeEnabled());
            Files.writeString(dir.resolve("transit-settings.json"), "{\"recipeEnabled\": true}");
            TransitSettings.load(dir);
            eq(true, TransitSettings.packBackView());
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
                new StructureShape.Cell(0, -3, 0, "A"), new StructureShape.Cell(0, -4, 0, "A"), new StructureShape.Cell(0, 0, 0, "B")));
        Vector3i o = new Vector3i(0, 63, 0); // surface 60: layer -3 at y 60 (water top)
        // water up to y 60, air above
        check(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : 0) == null, "fit");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y == 60 ? 2 : y < 60 ? 1 : 0)), "block in hull");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y < 60 ? 1 : 0)), "air in hull");
        check("deploy.steamboat.blocked".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y <= 60 ? 1 : y == 63 ? 2 : 0)), "block above");
        check("deploy.steamboat.blocked".equals(DeployPlacement.steamboatFit(s, o, Rotation.None, (x, y, z) -> y <= 60 || y == 63 ? 1 : 0)), "water above");
        // Quarter turn: Ninety sends prefab (x, z) to (z, -x), so the hull cell (2, -3, 0) lands at (0, 60, -2) from the origin.
        StructureShape t = StructureShape.ofCells(List.of(
                new StructureShape.Cell(2, -3, 0, "A"), new StructureShape.Cell(0, 0, 0, "B")));
        Vector3i to = new Vector3i(0, 63, 0);
        check(DeployPlacement.steamboatFit(t, to, Rotation.Ninety, (x, y, z) -> y <= 60 && x == 0 && z == -2 ? 1 : 0) == null, "turned fit");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(t, to, Rotation.Ninety,
                (x, y, z) -> y <= 60 && x == 2 && z == 0 ? 1 : 0)), "turned hull is not at the unturned place");
        check("deploy.steamboat.noWater".equals(DeployPlacement.steamboatFit(t, to, Rotation.Ninety,
                (x, y, z) -> x == 0 && z == -2 && y == 60 ? 2 : y <= 60 ? 1 : 0)), "block in turned hull cell");
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

    private static void steamboatSeats() throws Exception {
        int n = AirshipPassengers.seats(prefabShape("Steamboat.prefab.json")).size();
        check(n == 12, "12 seats, got " + n);
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

    /** T153: the tier table, the lookups and the bag cells. */
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
        eq(new Vector3i(-2, 0, 0), CampTiers.bagCell(CampTiers.Tier.TENT_BIG));
        eq(Deployables.BEDROLL, Deployables.forPrefab("Camping_Bedroll.prefab.json"));
    }

    /** T153: the pack and placed-bag JSON files, and the bag cell of each prefab. */
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

    /** T156: refusal rule of a pack upgrade. */
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

    /** T156: small tent contents re-addressed to the large tent (campfire, chest, surplus chest). */
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

    /** The explorer bench item JSON: bench id, categories and texts. */
    private static void explorerBenchJson() throws Exception {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Item/Items/Bench/Orbis_Explorer_Bench.json")));
        var block = root.getDocument("BlockType");
        var bench = block.getDocument("Bench");
        eq("OrbisHorizon_Bench", bench.getString("Id").getValue());
        eq("Crafting", bench.getString("Type").getValue());
        var cats = bench.getArray("Categories");
        eq(2, cats.size());
        eq("OrbisHorizon_Camp", cats.get(0).asDocument().getString("Id").getValue());
        eq("OrbisHorizon_Transport", cats.get(1).asDocument().getString("Id").getValue());
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
        for (int i = 0; i < 2; i++) {
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
            if (name.equals("Balloon_Station_Crate")) {
                check(r.getBoolean("KnowledgeRequired").getValue(), "station knowledge");
            }
            if (name.equals("Hotair_Balloon_Transport_Crate")) {
                java.util.Map<String, Integer> m = new java.util.HashMap<>();
                for (var in : r.getArray("Input")) {
                    m.put(in.asDocument().getString("ItemId").getValue(), in.asDocument().getInt32("Quantity").getValue());
                }
                eq(35, m.get("Ingredient_Bar_Iron"));
                eq(15, m.get("Ingredient_Bar_Thorium"));
                eq(4, m.get("Ingredient_Life_Essence_Concentrated"));
                check(!m.containsKey("Ingredient_Bar_Cobalt") && !m.containsKey("Ingredient_Fire_Essence"), "no cobalt or fire essence");
                eq(250, m.get("Cloth_Block_Wool_White"));
                eq(300, m.get("Cloth_Block_Wool_Green"));
                eq(24, m.get("Wood_Hardwood_Planks"));
            }
        }
        eq(table.size(), seen);
    }

    /** T156: the pack recipes match the spec, the lower pack comes first, ids exist, piles <= 40, legacy crates have no recipe. */
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

    private static void shipFeatureFlags() {
        Deployables.AirshipSpec s = Deployables.AIRSHIP.airship;
        check(s.crew && s.voyage && s.recall && s.ladders && s.onePerPlayer, "airship declares all its features");
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

    static void stationMarkerPoint() {
        org.joml.Vector3d p = StationMarkerProvider.markerPoint(new Vector3i(100, 64, 200), Rotation.None);
        eq(new org.joml.Vector3d(119.5, 71, 200.5), p);
        // Ninety sends the cell (19, 7, 0) to (0, 7, -19), centre (0.5, 7, -18.5)
        org.joml.Vector3d q = StationMarkerProvider.markerPoint(new Vector3i(100, 64, 200), Rotation.Ninety);
        eq(new org.joml.Vector3d(100.5, 71, 181.5), q);
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
        check(!b.crew && !b.voyage && !b.recall && !b.ladders && !b.onePerPlayer, "boat flags all false");
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
        check(UsageCounters.NAMES.size() == 21, "21 counters");
        check(UsageCounters.NAMES.contains("deploy.steamboat") && UsageCounters.NAMES.contains("takeoff.steamboat"), "steamboat counters");
        check(UsageCounters.NAMES.indexOf("deploy.steamboat") == UsageCounters.NAMES.indexOf("deploy.airship") + 1, "deploy order");
        check(UsageCounters.NAMES.indexOf("takeoff.steamboat") == UsageCounters.NAMES.indexOf("takeoff.airship") + 1, "takeoff order");
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
}
