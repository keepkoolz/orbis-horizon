package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.ProcessingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonValue;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Contents of the burner (Hotair_Balloon_Burner block) during a flight, and combustion.
 *
 * On the ground, the burner is a game processing bench (ProcessingBenchBlock component) modelled on
 * Bench_Tannery (T41): input and output slots only, no Fuel field in its JSON. The 3 input slots only
 * accept items of the Fuel resource (ResourceTypeId, ResourceFilter set by ProcessingBenchBlock.setupSlots): this is the
 * fuel. The game never burns this content: with no recipe (recipe null),
 * ProcessingBenchBlock.advanceProcessing returns immediately and BenchSystems$ProcessingBenchTick
 * does not read the input, and consumeOneFuel (which reads the fuel container) returns 0 without a Fuel field.
 * In flight the block no longer exists: the plugin copies its containers at take-off, empties them (otherwise the game
 * would drop their contents into the world when the block is removed, onEntityRemove of
 * BenchSystems$ProcessingBenchLifecycle), burns the fuel itself, then puts everything back into the
 * block on landing.
 *
 * Compatibility (T41): a burner placed before T41 has its fuel in the fuel container
 * (3 slots). On load, setupSlots shrinks it to capacity 0 and the game drops whatever does not
 * fit into the world. BurnerMigrationSystem therefore runs before the game and moves it into the
 * inputs. takeFrom and fromBson (old recovery files, "fuel" key) also move any old fuel
 * content into the inputs.
 *
 * Combustion follows ProcessingBenchBlock (consumeOneFuel and consumeFuelForDuration,
 * read from the server bytecode), applied to the inputs:
 * - a fuel item is removed when the remaining time (fuelTime) drops to zero, and adds
 *   Item.getFuelQuality() seconds (FuelQuality JSON field, 1 by default in the code, for example
 *   6 for Ingredient_Charcoal and Wood_Softwood_Planks, 9 for Wood_Oak_Trunk).
 * - the bench counts this time in real seconds (the game tick divides the elapsed game time by
 *   WorldTimeResource.getSecondsPerTick).
 * - ExtraOutput: every PerFuelItemsConsumed items burned (except IgnoredFuelSources), the bench
 *   adds its Outputs (Ingredient_Charcoal) to the output. The internal counter (nextExtra) starts at
 *   0, so the first item burned already yields charcoal, as in the game.
 */
final class BurnerFuel {

    /** Duration per item if the game gives none (FuelQuality null or negative). */
    static final double DEFAULT_FUEL_SECONDS = 30;
    /**
     * Multiplier for the game's durations. 1 = furnace and campfire durations (6 s per plank). The
     * durations are short for a flight: raise it here if the flight ends too quickly.
     */
    static final double FUEL_DURATION_FACTOR = 1.0;
    /** Number of input (fuel) slots in the burner JSON (T41). */
    static final int INPUT_SLOTS = 3;

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Private fields of ProcessingBenchBlock (no write accessor), read by reflection. */
    private static final Field FUEL_TIME = field("fuelTime");
    private static final Field NEXT_EXTRA = field("nextExtra");

    /** Detached copies of the bench containers (same slots). The inputs hold the fuel (T41). */
    private ItemContainer input;
    private final ItemContainer output;
    private final ProcessingBench.ExtraOutput extra;
    /** Seconds remaining for the item currently burning. */
    private double fuelTime;
    private int nextExtra;
    /** Outputs that do not fit in the output slots: dropped on landing. */
    private final List<ItemStack> overflow = new ArrayList<>();

    private BurnerFuel(ItemContainer input, ItemContainer output, ProcessingBench.ExtraOutput extra,
                       double fuelTime, int nextExtra) {
        this.input = input;
        this.output = output;
        this.extra = extra;
        this.fuelTime = fuelTime;
        this.nextExtra = nextExtra;
    }

    private static Field field(String name) {
        try {
            Field f = ProcessingBenchBlock.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Champ %s de ProcessingBenchBlock inaccessible", name);
            return null;
        }
    }

    /** The bench's live component at this position (not a copy), or null. Call on the world thread. */
    static ProcessingBenchBlock live(World world, Vector3i p) {
        // world.getBlockComponentHolder returns a copy (Store.copyEntity): BlockModule.getComponent
        // goes through the block entity's reference and returns the live component.
        return BlockModule.getComponent(ProcessingBenchBlock.getComponentType(), world, p.x, p.y, p.z);
    }

    /** True if the bench has fuel (items or burning in progress). */
    static boolean hasFuel(ProcessingBenchBlock bench) {
        ItemContainer f = bench.getInputContainer();
        return (f != null && !f.isEmpty()) || bench.getFuelTime() > 0;
    }

    /** Seconds of burning left for a bench in place: current burn plus fuel items (T20). */
    static double remainingSeconds(ProcessingBenchBlock bench) {
        double total = Math.max(0, bench.getFuelTime());
        ItemContainer f = bench.getInputContainer();
        if (f != null) {
            for (short s = 0; s < f.getCapacity(); s++) {
                ItemStack st = f.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    total += st.getQuantity() * seconds(st.getItem());
                }
            }
        }
        return total;
    }

    /**
     * Burns the in-place bench's fuel for dt seconds (T20, balloon stopped in mid-air).
     * Same logic as burn: the bench's live containers are modified, the current burn time
     * and the charcoal counter are rewritten by reflection. Returns the outputs that do not
     * fit in the output slots (to be dropped into the world).
     */
    static List<ItemStack> burnLive(ProcessingBenchBlock bench, double dt) {
        ProcessingBench config = bench.getProcessingBench();
        BurnerFuel b = new BurnerFuel(bench.getInputContainer(), bench.getOutputContainer(),
                config != null ? config.getExtraOutput() : null, bench.getFuelTime(), getInt(bench, NEXT_EXTRA));
        b.burn(dt);
        setFloat(bench, FUEL_TIME, (float) b.fuelTime);
        setInt(bench, NEXT_EXTRA, b.nextExtra);
        return new ArrayList<>(b.overflow);
    }

    /** Copies the bench contents then empties it (the block is about to be removed). */
    static BurnerFuel takeFrom(ProcessingBenchBlock bench) {
        ProcessingBench config = bench.getProcessingBench();
        BurnerFuel b = new BurnerFuel(copy(bench.getInputContainer()),
                copy(bench.getOutputContainer()), config != null ? config.getExtraOutput() : null,
                bench.getFuelTime(), getInt(bench, NEXT_EXTRA));
        // T41: an old fuel container that is still filled (pre-T41 burner whose contents
        // were not moved on load) goes into the kept inputs, with no duplicate (it is emptied).
        ItemContainer legacy = bench.getFuelContainer();
        if (legacy != null && legacy.getCapacity() > 0 && !legacy.isEmpty()) {
            b.mergeLegacyFuel(copy(legacy));
            legacy.clear();
        }
        clear(bench.getInputContainer());
        clear(bench.getOutputContainer());
        setFloat(bench, FUEL_TIME, 0f);
        return b;
    }

    /**
     * JSON form for the recovery file (T19): both containers (inputs and outputs, capacity and
     * non-empty stacks by index), the current burn time, the charcoal counter and the surplus
     * outputs. Files from before T41 also have a "fuel" key (fuel container), which fromBson
     * moves into the inputs. T41 no longer writes this key.
     */
    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        d.put("input", containerToBson(input));
        d.put("output", containerToBson(output));
        d.put("fuelTime", new BsonDouble(fuelTime));
        d.put("nextExtra", new BsonInt32(nextExtra));
        BsonArray ov = new BsonArray();
        for (ItemStack st : overflow) {
            ov.add(ItemStack.CODEC.encode(st, new ExtraInfo()));
        }
        d.put("overflow", ov);
        return d;
    }

    /**
     * Reads back contents written by toBson. The bench's ExtraOutput setting is not kept (it comes from the
     * block JSON): these contents are only meant to be put back into the re-placed block, not to burn.
     */
    static BurnerFuel fromBson(BsonDocument d) {
        BurnerFuel b = new BurnerFuel(containerFromBson(d.get("input")),
                containerFromBson(d.get("output")), null,
                d.containsKey("fuelTime") ? d.getNumber("fuelTime").doubleValue() : 0,
                d.containsKey("nextExtra") ? d.getNumber("nextExtra").intValue() : 0);
        // Recovery file from before T41: the fuel was in the "fuel" container, it goes into the inputs.
        b.mergeLegacyFuel(containerFromBson(d.get("fuel")));
        if (d.containsKey("overflow")) {
            for (BsonValue v : d.getArray("overflow")) {
                b.overflow.add(ItemStack.CODEC.decode(v, new ExtraInfo()));
            }
        }
        return b;
    }

    /**
     * T41: adds the contents of an old fuel container to the inputs. Each stack takes its slot index
     * if the slot is free (old slots 0 to 2 are inputs 0 to 2), otherwise it goes into the first free place.
     * What does not fit goes into the surplus outputs (dropped on
     * landing), nothing is lost.
     */
    private void mergeLegacyFuel(ItemContainer legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        short cap = (short) Math.max(INPUT_SLOTS, input != null ? input.getCapacity() : 0);
        ItemContainer merged = new SimpleItemContainer(cap);
        if (input != null) {
            for (short s = 0; s < input.getCapacity(); s++) {
                ItemStack st = input.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    merged.setItemStackForSlot(s, st, false);
                }
            }
        }
        for (short s = 0; s < legacy.getCapacity(); s++) {
            ItemStack st = legacy.getItemStack(s);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (s < cap && merged.getItemStack(s) == null && merged.setItemStackForSlot(s, st, false).succeeded()) {
                continue;
            }
            ItemStack rest = merged.addItemStack(st, false, false, false).getRemainder();
            if (rest != null && !rest.isEmpty()) {
                overflow.add(rest);
            }
        }
        input = merged;
    }

    private static BsonValue containerToBson(ItemContainer c) {
        if (c == null) {
            return BsonNull.VALUE;
        }
        BsonDocument d = new BsonDocument();
        d.put("capacity", new BsonInt32(c.getCapacity()));
        BsonArray arr = new BsonArray();
        for (short s = 0; s < c.getCapacity(); s++) {
            ItemStack st = c.getItemStack(s);
            if (st == null || st.isEmpty()) {
                continue;
            }
            BsonDocument slot = new BsonDocument();
            slot.put("slot", new BsonInt32(s));
            slot.put("stack", ItemStack.CODEC.encode(st, new ExtraInfo()));
            arr.add(slot);
        }
        d.put("slots", arr);
        return d;
    }

    private static ItemContainer containerFromBson(BsonValue v) {
        if (v == null || v.isNull()) {
            return null;
        }
        BsonDocument d = v.asDocument();
        short capacity = (short) d.getNumber("capacity").intValue();
        if (capacity <= 0) {
            // A container of capacity 0 (T36: empty Input, or the fuel container of a bench with no
            // Fuel field since T41): SimpleItemContainer rejects a capacity of 0.
            return null;
        }
        ItemContainer c = new SimpleItemContainer(capacity);
        for (BsonValue sv : d.getArray("slots")) {
            BsonDocument slot = sv.asDocument();
            int i = slot.getNumber("slot").intValue();
            if (i >= 0 && i < capacity) {
                c.setItemStackForSlot((short) i, ItemStack.CODEC.decode(slot.get("stack"), new ExtraInfo()), false);
            }
        }
        return c;
    }

    private static ItemContainer copy(ItemContainer c) {
        return c != null ? c.clone() : null;
    }

    private static void clear(ItemContainer c) {
        if (c != null) {
            c.clear();
        }
    }

    /**
     * Puts the contents back into the bench (new block placed on landing). Returns the items that
     * found no room, to be dropped into the world.
     */
    List<ItemStack> putInto(ProcessingBenchBlock bench) {
        List<ItemStack> left = new ArrayList<>(overflow);
        overflow.clear();
        restore(input, bench.getInputContainer(), left);
        restore(output, bench.getOutputContainer(), left);
        setFloat(bench, FUEL_TIME, (float) fuelTime);
        setInt(bench, NEXT_EXTRA, nextExtra);
        return left;
    }

    private static void restore(ItemContainer saved, ItemContainer target, List<ItemStack> left) {
        if (saved == null) {
            return;
        }
        for (short s = 0; s < saved.getCapacity(); s++) {
            ItemStack st = saved.getItemStack(s);
            if (st == null || st.isEmpty()) {
                continue;
            }
            // No filter: the output slots refuse player additions (assumption),
            // and the inputs' Fuel resource filter does not need to be checked again.
            if (target != null && s < target.getCapacity() && target.getItemStack(s) == null
                    && target.setItemStackForSlot(s, st, false).succeeded()) {
                continue;
            }
            ItemStack rest = st;
            if (target != null) {
                ItemStackTransaction t = target.addItemStack(st, false, false, false);
                rest = t.getRemainder();
            }
            if (rest != null && !rest.isEmpty()) {
                left.add(rest);
            }
        }
    }

    /**
     * T40: all kept items, then the containers are emptied (a pending put-back retry can no longer drop
     * them a second time).
     */
    List<ItemStack> takeAllItems() {
        List<ItemStack> all = allItems();
        overflow.clear();
        clear(input);
        clear(output);
        fuelTime = 0;
        return all;
    }

    /** All kept items (to drop them into the world if the bench does not come back). */
    List<ItemStack> allItems() {
        List<ItemStack> all = new ArrayList<>(overflow);
        for (ItemContainer c : new ItemContainer[]{input, output}) {
            if (c == null) {
                continue;
            }
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack st = c.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    all.add(st);
                }
            }
        }
        return all;
    }

    /** Seconds of flight remaining: current burn plus fuel items. */
    double remainingSeconds() {
        double total = Math.max(0, fuelTime);
        if (input != null) {
            for (short s = 0; s < input.getCapacity(); s++) {
                ItemStack st = input.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    total += st.getQuantity() * seconds(st.getItem());
                }
            }
        }
        return total;
    }

    /** Number of fuel items remaining. */
    int fuelItems() {
        int n = 0;
        if (input != null) {
            for (short s = 0; s < input.getCapacity(); s++) {
                ItemStack st = input.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    n += st.getQuantity();
                }
            }
        }
        return n;
    }

    boolean isEmpty() {
        return remainingSeconds() <= 0;
    }

    /** Burns for dt seconds (same logic as ProcessingBenchBlock.consumeFuelForDuration). */
    void burn(double dt) {
        double left = dt;
        while (left > 0) {
            if (fuelTime > 0) {
                double use = Math.min(fuelTime, left);
                fuelTime -= use;
                left -= use;
            } else if (!consumeOne()) {
                fuelTime = 0;
                return;
            }
        }
    }

    private static double seconds(Item item) {
        double q = item != null ? item.getFuelQuality() : 0;
        return (q > 0 ? q : DEFAULT_FUEL_SECONDS) * FUEL_DURATION_FACTOR;
    }

    /** Removes one fuel item. False if there are none left. */
    private boolean consumeOne() {
        if (input == null) {
            return false;
        }
        for (short s = 0; s < input.getCapacity(); s++) {
            ItemStack st = input.getItemStack(s);
            if (st == null || st.isEmpty()) {
                continue;
            }
            Item item = st.getItem();
            if (!input.removeItemStackFromSlot(s, 1, true, false).succeeded()) {
                continue;
            }
            fuelTime += seconds(item);
            if (extra != null && extra.getOutputs() != null && !extra.isIgnoredFuelSource(item)) {
                nextExtra--;
                if (nextExtra <= 0) {
                    nextExtra = extra.getPerFuelItemsConsumed();
                    for (MaterialQuantity m : extra.getOutputs()) {
                        addOutput(m.toItemStack());
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void addOutput(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return;
        }
        ItemStack rest = st;
        if (output != null) {
            rest = output.addItemStack(st, false, false, false).getRemainder();
        }
        if (rest != null && !rest.isEmpty()) {
            overflow.add(rest);
        }
    }

    private static int getInt(ProcessingBenchBlock bench, Field f) {
        try {
            return f != null ? f.getInt(bench) : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static void setInt(ProcessingBenchBlock bench, Field f, int v) {
        try {
            if (f != null) {
                f.setInt(bench, v);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de %s impossible", f.getName());
        }
    }

    private static void setFloat(ProcessingBenchBlock bench, Field f, float v) {
        try {
            if (f != null) {
                f.setFloat(bench, v);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de %s impossible", f.getName());
        }
    }
}
