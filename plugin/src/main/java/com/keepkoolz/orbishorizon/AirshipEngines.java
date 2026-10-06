package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.world.CancelParticleSystems;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Contents of the airship's engines during a flight (T57). An engine is the mod's Airship_Fuel_Tank block, a processing bench
 * (named Airship_Engine before T61; since then Airship_Engine is the firebox block under it, and this code keeps the words
 * "engine" for the tank and "firebox" for that block)
 * set up like the balloon burner (inputs that only take fuel, outputs for charcoal, no recipe). Each engine's contents are
 * kept in a BurnerFuel (same copy, empty, burn and put-back logic as the burner, which is left unchanged). The engines are
 * pooled: the fuel burns from the first engine that has some, then the next.
 *
 * Only the origin cell of a multi-cell engine counts (BalloonShape.Cell.filler is 0): the other cells have no component.
 * Position of an engine in the file and in this list: prefab frame, before rotation.
 */
final class AirshipEngines {

    static final String TANK_BLOCK = "Airship_Fuel_Tank";
    /** Block directly under the engine (T58), with an "On" state that glows. */
    static final String ENGINE_BLOCK = "Airship_Engine";
    static final String FIREBOX_ON_STATE = "On";
    /** Ember particle system of the firebox (block state and flying model). */
    static final String FIREBOX_EMBERS = "Airship_Firebox_Embers";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** One engine: the prefab-frame position of its origin cell and its kept contents. */
    static final class Entry {
        final Vector3i local;
        final BurnerFuel fuel;

        Entry(Vector3i local, BurnerFuel fuel) {
            this.local = local;
            this.fuel = fuel;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private AirshipEngines() {
    }

    /**
     * T58, on the ground: lights or puts out the firebox directly under the engine at enginePos (the engine's own cell y - 1),
     * like BalloonManager.setBurnerLit for the burner (World.setBlockInteractionState keeps the rotation). Nothing happens if
     * the block under the engine is not the firebox or already in the wanted state. To be called on the world thread.
     */
    static void setFireboxLit(World world, Vector3i enginePos, boolean lit) {
        int x = enginePos.x, y = enginePos.y - 1, z = enginePos.z;
        BlockType bt = world.getBlockType(x, y, z);
        if (bt == null || bt.getId() == null) {
            return;
        }
        String id = bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId();
        if (!id.startsWith(ENGINE_BLOCK)) {
            return;
        }
        boolean on = FIREBOX_ON_STATE.equals(bt.getCurrentInteractionState()) || id.endsWith("_State_Definitions_" + FIREBOX_ON_STATE);
        if (on == lit) {
            return;
        }
        world.setBlockInteractionState(new Vector3i(x, y, z), bt, lit ? FIREBOX_ON_STATE : "default");
        if (!lit) {
            // Same precaution as for the burner flame: the client could keep the ember particles displayed.
            cancelEmbers(world.getPlayerRefs(), x + 0.5, y + 0.5, z + 0.5);
        }
    }

    /** Asks these players' clients to stop the firebox ember particles around a point (block centre or model node). */
    static void cancelEmbers(java.util.Collection<PlayerRef> players, double cx, double cy, double cz) {
        CancelParticleSystems packet = new CancelParticleSystems(new Position(cx - 2, cy - 2, cz - 2), new Position(cx + 2, cy + 3, cz + 2),
                new String[]{FIREBOX_EMBERS}, true);
        for (PlayerRef player : players) {
            player.getPacketHandler().writeNoCache(packet);
        }
    }

    /** True for the cell that holds an engine's bench component (the origin cell of an Airship_Fuel_Tank block). */
    static boolean isEngineOrigin(BalloonShape.Cell c) {
        return TANK_BLOCK.equals(c.baseName()) && c.filler() == 0;
    }

    /** True for any cell of an engine block (origin or filler). */
    static boolean isEngineCell(BalloonShape.Cell c) {
        return TANK_BLOCK.equals(c.baseName());
    }

    /**
     * Copies then empties the contents of every engine of the prefab placed at this origin and rotation (the blocks are about
     * to be removed, the game would drop the contents). Engines whose bench component cannot be read are skipped with a warning.
     */
    static AirshipEngines takeFrom(World world, BalloonShape shape, Vector3i origin, Rotation rotation) {
        AirshipEngines engines = new AirshipEngines();
        for (BalloonShape.Cell c : shape.cells()) {
            if (!isEngineOrigin(c)) {
                continue;
            }
            Vector3i p = c.rotated(rotation).add(origin);
            ProcessingBenchBlock bench = BurnerFuel.live(world, p);
            if (bench == null) {
                LOGGER.at(Level.WARNING).log("Moteur du dirigeable introuvable en %s : contenu non gardé", p);
                continue;
            }
            engines.entries.add(new Entry(new Vector3i(c.x(), c.y(), c.z()), BurnerFuel.takeFrom(bench)));
        }
        return engines;
    }

    /** JSON form for the resume file: {"engines": [{"x", "y", "z", "bench": BurnerFuel.toBson}]}. */
    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        BsonArray arr = new BsonArray();
        for (Entry e : entries) {
            BsonDocument ed = new BsonDocument();
            ed.put("x", new BsonInt32(e.local.x));
            ed.put("y", new BsonInt32(e.local.y));
            ed.put("z", new BsonInt32(e.local.z));
            ed.put("bench", e.fuel.toBson());
            arr.add(ed);
        }
        d.put("engines", arr);
        return d;
    }

    static AirshipEngines fromBson(BsonDocument d) {
        AirshipEngines engines = new AirshipEngines();
        if (d != null && d.containsKey("engines")) {
            for (org.bson.BsonValue v : d.getArray("engines")) {
                BsonDocument ed = v.asDocument();
                engines.entries.add(new Entry(new Vector3i(ed.getNumber("x").intValue(), ed.getNumber("y").intValue(),
                        ed.getNumber("z").intValue()), BurnerFuel.fromBson(ed.getDocument("bench"))));
            }
        }
        return engines;
    }

    /**
     * Puts each engine's contents back into the engine placed at this origin and rotation (same retry and drop rules as the
     * burner, BalloonManager.restoreBenchAt). Items that find no engine at all are dropped by the retry once its time is up.
     */
    void restore(World world, Store<EntityStore> store, Vector3i origin, Rotation rotation) {
        for (Entry e : entries) {
            Vector3i p = rotation.rotateYaw(new Vector3i(e.local), new Vector3i()).add(origin);
            BalloonManager.get().restoreBenchAt(world, store, p, e.fuel);
        }
        entries.clear();
    }

    int entryCount() {
        return entries.size();
    }

    /** Seconds of burning left in the pool (current burns plus fuel items, before the fuel factor). */
    double remainingSeconds() {
        double total = 0;
        for (Entry e : entries) {
            total += e.fuel.remainingSeconds();
        }
        return total;
    }

    int fuelItems() {
        int n = 0;
        for (Entry e : entries) {
            n += e.fuel.fuelItems();
        }
        return n;
    }

    boolean isEmpty() {
        return remainingSeconds() <= 0;
    }

    /**
     * Burns dt seconds from the pool, first engine first. Returns the part of dt that could not be burned (0 if all of it was).
     * Charcoal goes to the outputs of the engine that burned.
     */
    double burn(double dt) {
        double left = dt;
        for (Entry e : entries) {
            if (left <= 0) {
                break;
            }
            left = e.fuel.burn(left);
        }
        return left;
    }

    /** All kept items (fuel, charcoal and surplus), and the engines are emptied: nothing can be dropped twice. */
    List<ItemStack> takeAllItems() {
        List<ItemStack> all = new ArrayList<>();
        for (Entry e : entries) {
            all.addAll(e.fuel.takeAllItems());
        }
        return all;
    }

    /** Number of items kept (fuel, charcoal, surplus), for the log. */
    int itemCount() {
        int n = 0;
        for (Entry e : entries) {
            for (ItemStack st : e.fuel.allItems()) {
                n += st.getQuantity();
            }
        }
        return n;
    }

    /** Position of the first engine in the world (to drop leftovers), or the origin. */
    Vector3i firstPosition(Vector3i origin, Rotation rotation) {
        Vector3i local = entries.isEmpty() ? new Vector3i() : entries.get(0).local;
        return rotation.rotateYaw(new Vector3i(local), new Vector3i()).add(origin);
    }
}
