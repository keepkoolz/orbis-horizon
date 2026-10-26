package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Level;

/** Rope ladder and ladder lever of the airship: block names and pure state shared by the ladder logic. */
final class ShipLadder {
    static final String ROPE_BLOCK = "Airship_Rope_Ladder";
    static final String LEVER_BLOCK = "Airship_Ladder_Lever";

    /** One rung placed or removed per step. */
    static final long STEP_MS = 250;
    static final int MAX_RUNGS = 48;
    /** Lever cell in the prefab frame, x is signed by the side. */
    static final int[] LEVER_CELL = {4, 7, -1};
    static final int TOP_FIXED_Y = 6;
    static final int BOTTOM_FIXED_Y = 4;
    static final int SIDE_X = 5;

    enum Phase { UP, LOWERING, DOWN, RAISING }

    /** The state of one ladder kept in the registry: side (+1 or -1), phase and the placed rungs (flat x, y, z, top to bottom). */
    record Saved(int side, Phase phase, int[] rungs) {
        BsonDocument toBson() {
            BsonDocument d = new BsonDocument();
            d.put("side", new BsonInt32(side));
            d.put("phase", new BsonString(phase.name()));
            BsonArray a = new BsonArray();
            for (int v : rungs) {
                a.add(new BsonInt32(v));
            }
            d.put("rungs", a);
            return d;
        }

        static Saved fromBson(BsonDocument d) {
            BsonArray a = d.getArray("rungs");
            int[] r = new int[a.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = a.get(i).asNumber().intValue();
            }
            return new Saved(d.getNumber("side").intValue(), Phase.valueOf(d.getString("phase").getValue()), r);
        }
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int SET_NO_PARTICLES = 4;

    /** Runtime state of one ladder (one side of one placed airship). Only touched on the world thread. */
    private static final class Run {
        final String world;
        final Vector3i origin;
        final Rotation rotation;
        final int side;
        Phase phase = Phase.UP;
        final List<int[]> rungs = new ArrayList<>(); // top to bottom
        List<int[]> startRungs = List.of();          // rungs when the current raise began (to catch climbers at the end)
        long nextMs;
        boolean flipped;                              // lever shown pulled
        Phase gateDone;                               // phase for which the gate rule was already applied (reset while moving)
        boolean gateByMod;                            // the deck gate of this side was opened by the mod

        Run(String world, Vector3i origin, Rotation rotation, int side) {
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.side = side;
        }

        boolean same(String w, Vector3i o, Rotation r) {
            return world.equals(w) && origin.equals(o) && rotation == r;
        }
    }

    private static final List<Run> RUNS = new CopyOnWriteArrayList<>();

    private ShipLadder() {
    }

    /** True if the cell is loaded, empty and not a liquid. */
    static boolean isAir(SectionReader reader, int x, int y, int z) {
        return reader.hasStorageInMemory(x, y, z) && reader.getBlock(x, y, z) == 0 && BalloonManager.liquidAt(reader, x, y, z) == null;
    }

    /**
     * Rungs to remove, bottom first, skipping the ones whose cell no longer holds our block. Flat x, y, z lists, the input top to bottom.
     */
    static int[] foreignSafeRaise(int[] rungs, Predicate<int[]> ours) {
        List<Integer> out = new ArrayList<>();
        for (int i = rungs.length - 3; i >= 0; i -= 3) {
            int[] c = {rungs[i], rungs[i + 1], rungs[i + 2]};
            if (ours.test(c)) {
                out.add(c[0]);
                out.add(c[1]);
                out.add(c[2]);
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    /** A departure waiting for the ladders to come up. */
    private static final class Pending implements Runnable {
        final String world;
        final Vector3i origin;
        final Rotation rotation;
        final java.util.function.BooleanSupplier stillValid;
        final Runnable retry;

        Pending(String world, Vector3i origin, Rotation rotation, java.util.function.BooleanSupplier stillValid, Runnable retry) {
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.stillValid = stillValid;
            this.retry = retry;
        }

        @Override
        public void run() {
            retry.run();
        }
    }

    private static final java.util.Map<Object, Runnable> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    private static String pendingKey(String world, Vector3i origin, Rotation rotation) {
        return world + "|" + origin.x + "," + origin.y + "," + origin.z + "|" + rotation;
    }

    /** Pure: false if a departure is already registered under the key (nothing is added then). */
    static boolean registerPending(java.util.Map<Object, Runnable> pending, Object key, Runnable r) {
        return pending.putIfAbsent(key, r) == null;
    }

    /** True while a departure of the ship waits for its ladders to come up. */
    static boolean departing(World world, Vector3i origin, Rotation rotation) {
        return PENDING.containsKey(pendingKey(world.getName(), origin, rotation));
    }

    enum Defer { PROCEED, DEFERRED, ALREADY_PENDING }

    /** Pure: the outcome of a departure request from the ladders state and the registration result. */
    static Defer deferResult(boolean allUp, boolean registered) {
        return allUp ? Defer.PROCEED : registered ? Defer.DEFERRED : Defer.ALREADY_PENDING;
    }

    /**
     * PROCEED if both ladders are up (the departure goes on at once). Otherwise raises them, registers the departure (one per ship, a
     * second call while waiting registers nothing) and returns DEFERRED: the retry runs on the world thread once every ladder is up.
     */
    static Defer deferDeparture(World world, Vector3i origin, Rotation rotation, java.util.function.BooleanSupplier stillValid,
                                  Runnable retry) {
        if (allUp(world, origin, rotation)) {
            return Defer.PROCEED;
        }
        raiseAll(world, origin, rotation);
        String name = world.getName();
        boolean registered = registerPending(PENDING, pendingKey(name, origin, rotation),
                new Pending(name, new Vector3i(origin), rotation, stillValid, retry));
        return deferResult(false, registered);
    }

    private static void tickPending(World world) {
        String name = world.getName();
        for (java.util.Map.Entry<Object, Runnable> me : PENDING.entrySet()) {
            Pending p = (Pending) me.getValue();
            if (!p.world.equals(name)) {
                continue;
            }
            try {
                if (BalloonRegistry.get(world, p.origin, p.rotation, Deployables.AIRSHIP_KIND) == null || !p.stillValid.getAsBoolean()) {
                    PENDING.remove(me.getKey()); // the ship is gone or the requester left: the raising goes on alone
                } else if (allUp(world, p.origin, p.rotation)) {
                    PENDING.remove(me.getKey());
                    p.retry.run();
                }
            } catch (RuntimeException e) {
                PENDING.remove(me.getKey());
                LOGGER.at(Level.WARNING).withCause(e).log("Échelle du dirigeable : départ en attente abandonné");
            }
        }
    }

    private static int[] flat(List<int[]> rungs) {
        int[] f = new int[rungs.size() * 3];
        for (int i = 0; i < rungs.size(); i++) {
            System.arraycopy(rungs.get(i), 0, f, i * 3, 3);
        }
        return f;
    }

    private static Run find(String world, Vector3i origin, Rotation rotation, int side) {
        for (Run r : RUNS) {
            if (r.side == side && r.same(world, origin, rotation)) {
                return r;
            }
        }
        return null;
    }

    private static Vector3i fixedBottom(Run r) {
        return r.rotation.rotateYaw(new Vector3i(r.side * SIDE_X, BOTTOM_FIXED_Y, 0), new Vector3i()).add(r.origin);
    }

    private static Vector3i leverCell(Run r) {
        return r.rotation.rotateYaw(new Vector3i(r.side * LEVER_CELL[0], LEVER_CELL[1], LEVER_CELL[2]), new Vector3i()).add(r.origin);
    }

    /** The runtime of a ladder, built from the registry entry if it is not in memory yet. Rungs whose loaded cell lost our block are forgotten. */
    private static Run getOrCreate(World world, BalloonRegistry.Entry entry, int side) {
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        Run run = find(world.getName(), origin, entry.rotation(), side);
        if (run != null) {
            return run;
        }
        run = new Run(world.getName(), origin, entry.rotation(), side);
        for (Saved sv : entry.ladders()) {
            if (sv.side() != side) {
                continue;
            }
            run.phase = sv.phase();
            run.flipped = sv.phase() == Phase.LOWERING || sv.phase() == Phase.DOWN;
            SectionReader reader = new SectionReader(world.getChunkStore());
            int[] f = sv.rungs();
            for (int i = 0; i + 2 < f.length; i += 3) {
                if (reader.hasStorageInMemory(f[i], f[i + 1], f[i + 2])) {
                    BlockType t = SectionBlocks.typeAt(reader, f[i], f[i + 1], f[i + 2]);
                    if (t == null || !ROPE_BLOCK.equals(t.getId())) {
                        continue;
                    }
                }
                run.rungs.add(new int[] {f[i], f[i + 1], f[i + 2]});
            }
            if (run.phase == Phase.RAISING) {
                run.startRungs = new ArrayList<>(run.rungs);
            }
        }
        extendDown(world, run);
        RUNS.add(run);
        return run;
    }

    /**
     * A crash in the middle of a move can leave rungs out of the registry: follows the contiguous rope cells under the last listed
     * rung (or under the bottom fixed rung when the list is empty), sections in memory only. The phase is kept (see adoptPhase).
     */
    private static void extendDown(World world, Run run) {
        try {
            SectionReader reader = new SectionReader(world.getChunkStore());
            Vector3i b = fixedBottom(run);
            int y = run.rungs.isEmpty() ? b.y : run.rungs.get(run.rungs.size() - 1)[1];
            if (run.rungs.isEmpty()) {
                BlockType bt = SectionBlocks.typeAt(reader, b.x, b.y, b.z);
                if (bt == null || !ROPE_BLOCK.equals(bt.getId())) {
                    return;
                }
            }
            int added = 0;
            while (run.rungs.size() < MAX_RUNGS && y - 1 >= 0 && reader.hasStorageInMemory(b.x, y - 1, b.z)) {
                BlockType t = SectionBlocks.typeAt(reader, b.x, y - 1, b.z);
                if (t == null || !ROPE_BLOCK.equals(t.getId())) {
                    break;
                }
                y--;
                run.rungs.add(new int[] {b.x, y, b.z});
                added++;
            }
            if (added > 0) {
                run.phase = adoptPhase(run.phase, added);
                run.flipped = run.phase == Phase.LOWERING || run.phase == Phase.DOWN;
                if (run.phase == Phase.RAISING) {
                    run.startRungs = new ArrayList<>(run.rungs);
                }
                LOGGER.at(Level.WARNING).log("Échelle du dirigeable : %d échelon(s) hors registre retrouvé(s), phase gardée : %s%s", added,
                        run.phase, run.phase == Phase.RAISING ? " (remontée lancée)" : "");
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Échelle du dirigeable : recherche d'échelons hors registre en échec");
        }
    }

    /** Phase kept when rope rungs below the registry list are adopted: a ladder still UP that has rope cells was lowering. */
    static Phase adoptPhase(Phase found, int adopted) {
        if (adopted == 0) {
            return found;
        }
        return found == Phase.UP ? Phase.LOWERING : found;
    }

    /** Writes the ladder states of this ship in the registry (a retracted ladder is left out). */
    private static void persist(World world, Vector3i origin, Rotation rotation, boolean force) {
        List<Saved> list = new ArrayList<>();
        for (Run r : RUNS) {
            if (r.same(world.getName(), origin, rotation) && !(r.phase == Phase.UP && r.rungs.isEmpty())) {
                list.add(new Saved(r.side, r.phase, flat(r.rungs)));
            }
        }
        BalloonRegistry.setLadders(world, origin, rotation, list, force);
    }

    /** The lever is shown turned half a turn while the ladder is down or going down. */
    private static void updateLever(World world, Run run) {
        boolean desired = run.phase == Phase.LOWERING || run.phase == Phase.DOWN;
        if (desired == run.flipped) {
            return;
        }
        Vector3i c = leverCell(run);
        SectionReader reader = new SectionReader(world.getChunkStore());
        if (!reader.hasStorageInMemory(c.x, c.y, c.z)) {
            return;
        }
        BlockType t = SectionBlocks.typeAt(reader, c.x, c.y, c.z);
        if (t != null && LEVER_BLOCK.equals(t.getId())) {
            int rot = reader.getRotationIndex(c.x, c.y, c.z);
            int flipped = (rot & ~3) | ((rot + 2) & 3);
            SectionBlocks.setAt(world.getChunkStore(), c.x, c.y, c.z, reader.getBlock(c.x, c.y, c.z), t, flipped, 0, SET_NO_PARTICLES);
        }
        run.flipped = desired;
    }

    /** Puts on the ground the players in the rung column whose feet are in the rung cell. */
    private static void catchPlayers(Store<EntityStore> store, World world, int[] c) {
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> ref = pr.getReference();
            TransformComponent t = ref != null && ref.isValid() ? store.getComponent(ref, TransformComponent.getComponentType()) : null;
            if (t == null) {
                continue;
            }
            Vector3d p = t.getPosition();
            if ((int) Math.floor(p.x) == c[0] && (int) Math.floor(p.z) == c[2] && caught(p.y, c[1])) {
                AirshipPassengers.teleport(store, ref, BalloonManager.groundBelow(world, new Vector3d(p)));
            }
        }
    }

    /**
     * Pure: true if the player is horizontally in the rung column cell and the feet are between the lowest rung of the raise and the
     * top of the bottom fixed rung cell (ground to deck).
     */
    static boolean inColumn(double px, double py, double pz, int colX, int colZ, int lowestY, int bottomFixedY) {
        return (int) Math.floor(px) == colX && (int) Math.floor(pz) == colZ && py >= lowestY && py < bottomFixedY + 1;
    }

    /** Puts on the ground the players still in the ladder column at the end of the raise (they would fall from the deck). */
    private static void dropClimbers(Store<EntityStore> store, World world, Run run, Vector3i bottom) {
        if (run.startRungs.isEmpty()) {
            return;
        }
        int lowest = Integer.MAX_VALUE;
        for (int[] c : run.startRungs) {
            lowest = Math.min(lowest, c[1]);
        }
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> ref = pr.getReference();
            TransformComponent t = ref != null && ref.isValid() ? store.getComponent(ref, TransformComponent.getComponentType()) : null;
            if (t == null) {
                continue;
            }
            Vector3d p = t.getPosition();
            if (inColumn(p.x, p.y, p.z, bottom.x, bottom.z, lowest, bottom.y)) {
                AirshipPassengers.teleport(store, ref, BalloonManager.groundBelow(world, new Vector3d(p)));
            }
        }
    }

    /** Applies the lever: lowering becomes raising and the other way round. Null if accepted, else the refusal. */
    static Message pullLever(World world, BalloonRegistry.Entry entry, int side) {
        if (entry == null || side == 0 || !Deployables.AIRSHIP_KIND.equals(entry.kind())) {
            return Texts.t("airship.ladder.notParked");
        }
        Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
        if (departing(world, origin, entry.rotation())) {
            return Texts.t("airship.ladder.departing");
        }
        Run run = getOrCreate(world, entry, Integer.signum(side));
        run.phase = onLever(run.phase);
        if (run.phase == Phase.RAISING) {
            run.startRungs = new ArrayList<>(run.rungs);
        }
        run.nextMs = 0;
        updateLever(world, run);
        persist(world, origin, entry.rotation(), true);
        return null;
    }

    /** Starts lowering the ladder of one side. */
    static void lower(World world, Vector3i origin, Rotation rotation, int side) {
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
        if (entry == null) {
            return;
        }
        Run run = getOrCreate(world, entry, side);
        if (run.phase == Phase.UP || run.phase == Phase.RAISING) {
            run.phase = Phase.LOWERING;
            run.nextMs = 0;
            updateLever(world, run);
            persist(world, origin, rotation, true);
        }
    }

    /** Starts raising every ladder of the ship that is out. */
    static void raiseAll(World world, Vector3i origin, Rotation rotation) {
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
        if (entry == null) {
            return;
        }
        boolean changed = false;
        for (int side : new int[] {-1, 1}) {
            if (phase(world, origin, rotation, side) == Phase.UP && !hasRungs(world, entry, side)) {
                continue;
            }
            Run run = getOrCreate(world, entry, side);
            if (run.phase != Phase.RAISING) {
                run.phase = Phase.RAISING;
                run.startRungs = new ArrayList<>(run.rungs);
                run.nextMs = 0;
                updateLever(world, run);
                changed = true;
            }
        }
        if (changed) {
            persist(world, origin, rotation, true);
        }
    }

    private static boolean hasRungs(World world, BalloonRegistry.Entry entry, int side) {
        Run r = find(world.getName(), new Vector3i(entry.x(), entry.y(), entry.z()), entry.rotation(), side);
        if (r != null) {
            return !r.rungs.isEmpty();
        }
        for (Saved sv : entry.ladders()) {
            if (sv.side() == side && sv.rungs().length > 0) {
                return true;
            }
        }
        return false;
    }

    /** True if no ladder of the ship is out or moving. */
    static boolean allUp(World world, Vector3i origin, Rotation rotation) {
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
        for (int side : new int[] {-1, 1}) {
            if (phase(world, origin, rotation, side) != Phase.UP || (entry != null && hasRungs(world, entry, side))) {
                return false;
            }
        }
        return true;
    }

    static Phase phase(World world, Vector3i origin, Rotation rotation, int side) {
        Run r = find(world.getName(), origin, rotation, side);
        if (r != null) {
            return r.phase;
        }
        BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
        if (entry != null) {
            for (Saved sv : entry.ladders()) {
                if (sv.side() == side) {
                    return sv.phase();
                }
            }
        }
        return Phase.UP;
    }

    /**
     * Removes every rung without animation, puts the players of the columns on the ground, clears the state and the registry field.
     * A rung whose section is not in memory is kept (in the Run and in the registry, raising goes on in the tick). Returns true if
     * rungs remain.
     */
    static boolean removeNow(Store<EntityStore> store, World world, Vector3i origin, Rotation rotation) {
        try {
            BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
            if (entry == null) {
                return false;
            }
            ChunkStore cs = world.getChunkStore();
            boolean left = false;
            for (int side : new int[] {-1, 1}) {
                if (find(world.getName(), origin, rotation, side) == null && !hasRungs(world, entry, side)) {
                    continue;
                }
                Run run = getOrCreate(world, entry, side);
                left |= clearRungs(store, world, run);
                if (run.rungs.isEmpty()) {
                    RUNS.remove(run);
                } else {
                    run.phase = Phase.RAISING;
                    run.startRungs = new ArrayList<>(run.rungs);
                    run.nextMs = 0;
                }
            }
            if (left) {
                persist(world, origin, rotation, true);
            } else {
                BalloonRegistry.setLadders(world, origin, rotation, List.of(), true);
            }
            return left;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Échelle du dirigeable : retrait immédiat en échec");
            return true;
        }
    }

    /** Removes the rungs of a run bottom first without particles; rungs in an unloaded section stay in the list. True if any stays. */
    private static boolean clearRungs(Store<EntityStore> store, World world, Run run) {
        ChunkStore cs = world.getChunkStore();
        SectionReader reader = new SectionReader(cs);
        List<int[]> kept = new ArrayList<>();
        for (int i = run.rungs.size() - 1; i >= 0; i--) {
            int[] c = run.rungs.get(i);
            if (!reader.hasStorageInMemory(c[0], c[1], c[2])) {
                kept.add(0, c);
                continue;
            }
            BlockType t = SectionBlocks.typeAt(reader, c[0], c[1], c[2]);
            if (t != null && ROPE_BLOCK.equals(t.getId())) {
                SectionBlocks.setAt(cs, c[0], c[1], c[2], BlockType.EMPTY_KEY, SET_NO_PARTICLES);
                catchPlayers(store, world, c);
            }
        }
        run.rungs.clear();
        run.rungs.addAll(kept);
        return !kept.isEmpty();
    }

    /** Asks for the sections of the rung columns of a ship to load (so a later removal can reach every rung). */
    static void requestRungSections(World world, Vector3i origin, Rotation rotation) {
        try {
            BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, Deployables.AIRSHIP_KIND);
            if (entry == null) {
                return;
            }
            for (Saved sv : entry.ladders()) {
                int[] f = sv.rungs();
                if (f.length < 3) {
                    continue;
                }
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (int i = 0; i + 2 < f.length; i += 3) {
                    minX = Math.min(minX, f[i]); maxX = Math.max(maxX, f[i]);
                    minY = Math.min(minY, f[i + 1]); maxY = Math.max(maxY, f[i + 1]);
                    minZ = Math.min(minZ, f[i + 2]); maxZ = Math.max(maxZ, f[i + 2]);
                }
                SectionBlocks.loadSections(world.getChunkStore(), minX, minY, minZ, maxX, maxY, maxZ);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Échelle du dirigeable : chargement des sections impossible");
        }
    }

    /** One step per ladder at most every STEP_MS. Never throws. */
    static void tick(Store<EntityStore> store, World world, long now) {
        tickRuns(store, world, now);
        if (!PENDING.isEmpty()) {
            tickPending(world);
        }
    }

    private static void tickRuns(Store<EntityStore> store, World world, long now) {
        if (RUNS.isEmpty()) {
            return;
        }
        String name = world.getName();
        for (Run run : RUNS) {
            if (!run.world.equals(name)) {
                continue;
            }
            try {
                if (BalloonRegistry.get(world, run.origin, run.rotation, Deployables.AIRSHIP_KIND) == null) {
                    // The ship is gone (taken off, removed, destroyed): rungs that still hold our block are removed, the others retried.
                    if (!run.rungs.isEmpty()) {
                        clearRungs(store, world, run);
                    }
                    if (run.rungs.isEmpty()) {
                        RUNS.remove(run);
                    }
                    continue;
                }
                updateLever(world, run);
                syncGate(world, run);
                if (run.phase == Phase.UP && run.rungs.isEmpty() && !run.gateByMod) {
                    RUNS.remove(run);
                    continue;
                }
                if (now < run.nextMs) {
                    continue;
                }
                run.nextMs = now + STEP_MS;
                step(store, world, run);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Échelle du dirigeable : pas en échec");
            }
        }
    }

    private static void step(Store<EntityStore> store, World world, Run run) {
        ChunkStore cs = world.getChunkStore();
        SectionReader reader = new SectionReader(cs);
        Vector3i b = fixedBottom(run);
        if (run.phase == Phase.LOWERING) {
            int y = run.rungs.isEmpty() ? b.y - 1 : run.rungs.get(run.rungs.size() - 1)[1] - 1;
            if (!reader.hasStorageInMemory(b.x, b.y, b.z)) {
                return;
            }
            BlockType t = SectionBlocks.typeAt(reader, b.x, b.y, b.z);
            boolean placed = false;
            if (t != null && ROPE_BLOCK.equals(t.getId()) && y >= 0) {
                if (!reader.hasStorageInMemory(b.x, y, b.z)) {
                    return; // wait for the section
                }
                if (isAir(reader, b.x, y, b.z) && run.rungs.size() < MAX_RUNGS) {
                    int id = reader.getBlock(b.x, b.y, b.z);
                    int rot = reader.getRotationIndex(b.x, b.y, b.z);
                    placed = SectionBlocks.setAt(cs, b.x, y, b.z, id, t, rot, 0, SET_NO_PARTICLES);
                    if (placed) {
                        run.rungs.add(new int[] {b.x, y, b.z});
                    }
                }
            }
            Phase next = afterLowerStep(placed, run.rungs.size());
            run.phase = next;
            if (next == Phase.DOWN) {
                updateLever(world, run);
            }
            persist(world, run.origin, run.rotation, next == Phase.DOWN);
        } else if (run.phase == Phase.RAISING) {
            // Foreign blocks at the bottom (a block placed in the column) are forgotten, the others go bottom first.
            while (!run.rungs.isEmpty()) {
                int[] c = run.rungs.get(run.rungs.size() - 1);
                if (!reader.hasStorageInMemory(c[0], c[1], c[2])) {
                    return; // wait for the section
                }
                BlockType t = SectionBlocks.typeAt(reader, c[0], c[1], c[2]);
                if (t != null && ROPE_BLOCK.equals(t.getId())) {
                    break;
                }
                run.rungs.remove(run.rungs.size() - 1);
            }
            if (!run.rungs.isEmpty()) {
                int[] c = run.rungs.get(run.rungs.size() - 1);
                if (!SectionBlocks.setAt(cs, c[0], c[1], c[2], BlockType.EMPTY_KEY, SET_NO_PARTICLES)) {
                    return;
                }
                run.rungs.remove(run.rungs.size() - 1);
                catchPlayers(store, world, c);
            }
            if (run.rungs.isEmpty()) {
                run.phase = Phase.UP;
                dropClimbers(store, world, run, b);
                updateLever(world, run);
                syncGate(world, run);
                if (!run.gateByMod) {
                    RUNS.remove(run); // else the tick closes the gate first, then drops the run
                }
                persist(world, run.origin, run.rotation, true);
            } else {
                persist(world, run.origin, run.rotation, false);
            }
        }
    }

    /** World load: rebuilds the runtime of the ladders kept in the registry (rungs without our block are forgotten). */
    static void recoverWorld(World world) {
        try {
            for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.AIRSHIP_KIND)) {
                if (e.ladders().isEmpty()) {
                    continue;
                }
                for (Saved sv : new ArrayList<>(e.ladders())) {
                    Run run = getOrCreate(world, e, sv.side());
                    if (run.phase == Phase.UP && run.rungs.isEmpty()) {
                        RUNS.remove(run);
                    }
                }
                persist(world, new Vector3i(e.x(), e.y(), e.z()), e.rotation(), true);
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Échelle du dirigeable : reprise du monde en échec");
        }
    }

    /** What to do with the deck gate of a ladder side. */
    enum GateAction { NONE, OPEN, CLOSE }

    /**
     * The deck gate opens when the ladder is fully down and closes when it is fully up, but only if the mod opened it (a gate a
     * player opened is left alone). Pure.
     */
    static GateAction gateAction(Phase phase, boolean gateOpen, boolean openedByMod) {
        if (phase == Phase.DOWN && !gateOpen) {
            return GateAction.OPEN;
        }
        if (phase == Phase.UP && gateOpen && openedByMod) {
            return GateAction.CLOSE;
        }
        return GateAction.NONE;
    }

    /**
     * Both flanks have the same block rotation in the prefab, so the swing direction of a state is the same in the world for both.
     * The +x flank is built in CloseDoorOut, so Out is taken as the swing toward +x (outboard there), and the -x flank uses In
     * (toward -x). Assumption to confirm in game.
     */
    static String gateOpenState(int side) {
        return side > 0 ? "OpenDoorOut" : "OpenDoorIn";
    }

    static String gateCloseState(int side) {
        return side > 0 ? "CloseDoorOut" : "CloseDoorIn";
    }

    private static final String GATE_BLOCK = "Wood_Softwood_Fence_Gate";
    static final int GATE_Y = 7;

    /**
     * Applies the gate rule once per arrival in DOWN (open) or UP (close if the mod opened it), so a gate a player closes by hand
     * while the ladder stays down is not reopened. A section that is not loaded is retried next tick. DoorBlocked is left alone.
     */
    private static void syncGate(World world, Run run) {
        if (run.phase == Phase.LOWERING || run.phase == Phase.RAISING) {
            run.gateDone = null;
            return;
        }
        if (run.gateDone == run.phase) {
            return;
        }
        Vector3i c = run.rotation.rotateYaw(new Vector3i(run.side * SIDE_X, GATE_Y, 0), new Vector3i()).add(run.origin);
        SectionReader reader = new SectionReader(world.getChunkStore());
        if (!reader.hasStorageInMemory(c.x, c.y, c.z)) {
            return;
        }
        BlockType t = SectionBlocks.typeAt(reader, c.x, c.y, c.z);
        if (t == null || t.getId() == null || !t.getId().contains(GATE_BLOCK)) {
            run.gateByMod = false; // no gate here (older ship): nothing to remember
            run.gateDone = run.phase;
            return;
        }
        if (t.getId().contains("DoorBlocked")) {
            run.gateDone = run.phase; // transient state of the game: leave alone
            return;
        }
        boolean open = t.getId().contains("OpenDoor");
        GateAction a = gateAction(run.phase, open, run.gateByMod);
        if (a == GateAction.OPEN) {
            if (!SectionBlocks.setStateAt(world.getChunkStore(), c.x, c.y, c.z, t, gateOpenState(run.side))) {
                return; // retry next tick
            }
            run.gateByMod = true;
        } else if (a == GateAction.CLOSE) {
            if (!SectionBlocks.setStateAt(world.getChunkStore(), c.x, c.y, c.z, t, gateCloseState(run.side))) {
                return;
            }
            run.gateByMod = false;
        } else if (run.phase == Phase.UP) {
            run.gateByMod = false; // a player already closed it
        }
        run.gateDone = run.phase;
    }

    /** The phase after the lever is pulled. */
    static Phase onLever(Phase p) {
        return switch (p) {
            case UP, RAISING -> Phase.LOWERING;
            case LOWERING, DOWN -> Phase.RAISING;
        };
    }

    /** The phase after a lowering step: goes on while a rung was placed and the limit is not reached. */
    static Phase afterLowerStep(boolean placed, int count) {
        return placed && count < MAX_RUNGS ? Phase.LOWERING : Phase.DOWN;
    }

    /** True if a player's feet are in the rung cell, so the rung that goes catches them. */
    static boolean caught(double feetY, int rungY) {
        return feetY >= rungY && feetY < rungY + 1;
    }
}
