package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Contents of the processing benches aboard the airship (furnaces, campfire, tannery, salvage bench) during a flight
 *. Same mechanism as the chests and the fuel tank (AirshipEngines): each bench's inputs and outputs are copied then
 * emptied at take-off, kept in a BurnerFuel, written in the resume file (optional key "benchContents") and put back at landing with the
 * usual 10 s retry. Processing in progress is frozen during the flight. The fuel tank is not handled here (AirshipEngines does it) and
 * the bench levels and upgrade items stay with AirshipBenches. Positions are in the prefab frame, before rotation.
 */
final class AirshipBenchContents {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** One bench: the prefab-frame position of its origin cell and its kept contents. */
    private static final class Entry {
        final Vector3i local;
        final BurnerFuel contents;
        /** The bench's own fuel container (furnace, campfire), kept apart so the fuel goes back to the fuel slot. Null if none. */
        final ItemContainer fuel;

        Entry(Vector3i local, BurnerFuel contents, ItemContainer fuel) {
            this.local = local;
            this.contents = contents;
            this.fuel = fuel;
        }

        List<ItemStack> fuelStacks() {
            List<ItemStack> out = new ArrayList<>();
            if (fuel != null) {
                for (short s = 0; s < fuel.getCapacity(); s++) {
                    ItemStack st = fuel.getItemStack(s);
                    if (st != null && !st.isEmpty()) {
                        out.add(st);
                    }
                }
            }
            return out;
        }
    }

    /** A restore waiting for the bench component (and its level): retried each tick, then everything is dropped. */
    private static final class Pending {
        final World world;
        final Vector3i pos;
        final Entry entry;
        final long deadlineMs;
        volatile boolean scheduled;

        Pending(World world, Vector3i pos, Entry entry, long deadlineMs) {
            this.world = world;
            this.pos = pos;
            this.entry = entry;
            this.deadlineMs = deadlineMs;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private AirshipBenchContents() {
    }

    /** True for a cell that can hold a bench component other than the fuel tank (origin cell of a multi-cell block). */
    static boolean isCandidate(StructureShape.Cell c) {
        return c.filler() == 0 && !AirshipEngines.isEngineCell(c);
    }

    /** True if a bench holds nothing worth keeping (no items in its inputs, fuel or outputs, nothing burning). */
    static boolean benchEmpty(ProcessingBenchBlock b) {
        ItemContainer in = b.getInputContainer();
        ItemContainer fuel = b.getFuelContainer();
        ItemContainer out = b.getOutputContainer();
        return (in == null || in.isEmpty()) && (fuel == null || fuel.getCapacity() == 0 || fuel.isEmpty())
                && (out == null || out.isEmpty()) && b.getFuelTime() <= 0;
    }

    /** Copies then empties every non-empty processing bench (the fuel tank excepted) of the prefab placed at this origin and rotation. */
    static AirshipBenchContents takeFrom(World world, StructureShape shape, Vector3i origin, Rotation rotation) {
        AirshipBenchContents kept = new AirshipBenchContents();
        for (StructureShape.Cell c : shape.cells()) {
            if (!isCandidate(c)) {
                continue;
            }
            Vector3i at = c.rotated(rotation).add(origin);
            // A restore still waiting for this bench (quick take-off after a landing) goes back into the flight, not dropped in the air.
            for (Pending p : PENDING) {
                if (p.world == world && p.pos.equals(at) && PENDING.remove(p)) {
                    kept.entries.add(new Entry(new Vector3i(c.x(), c.y(), c.z()), p.entry.contents, p.entry.fuel));
                }
            }
            try {
                ProcessingBenchBlock bench = BurnerFuel.live(world, at);
                if (bench != null && !benchEmpty(bench)) {
                    // The fuel container is cloned then cleared first: BurnerFuel.takeFrom would merge it into the inputs (rule).
                    ItemContainer fuelBox = bench.getFuelContainer();
                    ItemContainer fuel = null;
                    if (fuelBox != null && fuelBox.getCapacity() > 0) {
                        fuel = fuelBox.clone();
                        fuelBox.clear();
                    }
                    kept.entries.add(new Entry(new Vector3i(c.x(), c.y(), c.z()), BurnerFuel.takeFrom(bench), fuel));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Établi du dirigeable en (%d, %d, %d) : contenu non gardé", c.x(), c.y(), c.z());
            }
        }
        return kept;
    }

    /** JSON form for the resume file: {"benchContents": [{"x", "y", "z", "bench": BurnerFuel.toBson}]}. */
    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        BsonArray arr = new BsonArray();
        for (Entry e : entries) {
            BsonDocument ed = new BsonDocument();
            ed.put("x", new BsonInt32(e.local.x));
            ed.put("y", new BsonInt32(e.local.y));
            ed.put("z", new BsonInt32(e.local.z));
            ed.put("bench", e.contents.toBson());
            ed.put("fuelBox", BurnerFuel.containerToBson(e.fuel)); // own key: "fuel" would be merged into the inputs on reading
            arr.add(ed);
        }
        d.put("benchContents", arr);
        return d;
    }

    /** Reads the document written by toBson. Null or a missing key (older files) gives an empty set. */
    static AirshipBenchContents fromBson(BsonDocument d) {
        AirshipBenchContents kept = new AirshipBenchContents();
        if (d != null && d.containsKey("benchContents")) {
            for (org.bson.BsonValue v : d.getArray("benchContents")) {
                BsonDocument ed = v.asDocument();
                kept.entries.add(new Entry(new Vector3i(ed.getNumber("x").intValue(), ed.getNumber("y").intValue(),
                        ed.getNumber("z").intValue()), BurnerFuel.fromBson(ed.getDocument("bench")),
                        ed.containsKey("fuelBox") ? BurnerFuel.containerFromBson(ed.get("fuelBox")) : null));
            }
        }
        return kept;
    }

    private static final long RESTORE_TIMEOUT_MS = 10_000;
    private static final java.util.concurrent.ConcurrentLinkedQueue<Pending> PENDING = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Puts each bench's contents back into the bench at this origin and rotation. Call after AirshipBenches.restore: a bench whose
     * level is still pending (or whose component does not exist yet) is retried every tick for 10 s, then its items are dropped.
     */
    void restore(World world, Store<EntityStore> store, Vector3i origin, Rotation rotation) {
        for (Entry e : entries) {
            Vector3i p = rotation.rotateYaw(new Vector3i(e.local), new Vector3i()).add(origin);
            if (!tryRestore(world, store, p, e)) {
                PENDING.add(new Pending(world, p, e, System.currentTimeMillis() + RESTORE_TIMEOUT_MS));
            }
        }
        entries.clear();
    }

    private static boolean tryRestore(World world, Store<EntityStore> store, Vector3i p, Entry e) {
        if (AirshipBenches.pendingAt(world, p)) {
            return false; // the level (and its extra slots) comes first
        }
        ProcessingBenchBlock bench = BurnerFuel.live(world, p);
        if (bench == null) {
            return false;
        }
        List<ItemStack> left = new ArrayList<>(e.contents.putInto(bench));
        ItemContainer target = bench.getFuelContainer();
        for (ItemStack st : e.fuelStacks()) {
            ItemStack rest = st;
            if (target != null && target.getCapacity() > 0) {
                rest = target.addItemStack(st, false, false, false).getRemainder();
            }
            if (rest != null && !rest.isEmpty()) {
                left.add(rest);
            }
        }
        BalloonManager.dropItems(store, left, p);
        return true;
    }

    /** Called every tick by AirshipManager.tick for the retries. */
    static void retry(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Pending p : PENDING) {
            if (p.world != world || p.scheduled) {
                continue;
            }
            p.scheduled = true;
            world.execute(() -> {
                p.scheduled = false;
                if (!PENDING.contains(p)) {
                    return; // taken back by a take-off or flushed at shutdown
                }
                if (tryRestore(world, store, p.pos, p.entry)) {
                    PENDING.remove(p);
                } else if (System.currentTimeMillis() > p.deadlineMs) {
                    PENDING.remove(p);
                    LOGGER.at(Level.WARNING).log("Établi du dirigeable introuvable en %s : contenu lâché au sol", p.pos);
                    List<ItemStack> all = new ArrayList<>(p.entry.contents.allItems());
                    all.addAll(p.entry.fuelStacks());
                    BalloonManager.dropItems(store, all, p.pos);
                }
            });
        }
    }

    /**
     * Shutdown: the restores still waiting for their bench are done now, or their items dropped where the bench should be, so that
     * nothing is lost with the deleted resume file. Waits at most timeoutMs per world. Never throws.
     */
    static void flushAll(long timeoutMs) {
        java.util.Set<World> worlds = new java.util.HashSet<>();
        for (Pending p : PENDING) {
            worlds.add(p.world);
        }
        for (World w : worlds) {
            try {
                BalloonManager.runOnWorld(w, () -> {
                    Store<EntityStore> store = w.getEntityStore().getStore();
                    for (Pending p : PENDING) {
                        if (p.world != w || !PENDING.remove(p)) {
                            continue;
                        }
                        if (!tryRestore(w, store, p.pos, p.entry)) {
                            List<ItemStack> all = new ArrayList<>(p.entry.contents.allItems());
                            all.addAll(p.entry.fuelStacks());
                            BalloonManager.dropItems(store, all, p.pos);
                            LOGGER.at(Level.WARNING).log("Arrêt : contenu de l'établi du dirigeable en %s lâché au sol", p.pos);
                        }
                    }
                }, timeoutMs);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Arrêt : contenus d'établis en attente non traités");
            }
        }
    }

    /** A copy whose benches all sit at this prefab-frame position (the campfire of another tent). */
    AirshipBenchContents relocated(Vector3i to) {
        AirshipBenchContents out = new AirshipBenchContents();
        for (Entry e : entries) {
            out.entries.add(new Entry(new Vector3i(to), e.contents, e.fuel));
        }
        return out;
    }

    int entryCount() {
        return entries.size();
    }

    /** All kept items; the benches are emptied so that nothing can be dropped twice. */
    List<ItemStack> takeAllItems() {
        List<ItemStack> all = new ArrayList<>();
        for (Entry e : entries) {
            all.addAll(e.contents.takeAllItems());
            all.addAll(e.fuelStacks());
        }
        entries.clear();
        return all;
    }

    /** Number of kept items, for the log. */
    int itemCount() {
        int n = 0;
        for (Entry e : entries) {
            for (ItemStack st : e.contents.allItems()) {
                n += st.getQuantity();
            }
            for (ItemStack st : e.fuelStacks()) {
                n += st.getQuantity();
            }
        }
        return n;
    }
}
