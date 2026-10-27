package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Registry of the structures deployed by the mod, locked against adding and removing blocks (made
 * generic: the structure type is the kind from Deployables).
 *
 * An entry is (world, prefab origin, rotation, kind, nullable owner). It is added by each paste of the mod's prefab
 * (crate, landing (chain or command), running dry, recovery: all go through
 * BalloonManager.pasteKeepingTerrain) and removed at take-off (the blocks no longer exist during the flight). The
 * "deployed.json" file in the plugin's data folder keeps the registry between two starts. Balloons
 * deployed before this version are not in the registry, so they are not locked until their
 * next landing.
 *
 * An entry is checked on each use: if the anchor burner is no longer in place (balloon destroyed
 * by other means, such as a command or a building tool), it is removed and no longer blocks anything.
 */
final class BalloonRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int VERSION = 2;

    /**
     * A deployed structure. The kind is the Deployables type. The owner (nullable) is the player who
     * placed it. Neither the owner nor the cage state (descent in blocks and open gate of the transport
     * balloon, 0 and closed for every other type) nor the captured animals (at most 3, immutable list) count in equality: an entry is found by world, origin, rotation and kind.
     */
    record Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
                 java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew, Recall recall,
                 java.util.List<ShipLadder.Saved> ladders, String wood, WaterLog water, SkyLog sky, String stone) {
        /** An entry without a stone family (the original basalt). */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew, Recall recall,
              java.util.List<ShipLadder.Saved> ladders, String wood, WaterLog water, SkyLog sky) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, id, ownerName, shared, crew, recall, ladders, wood, water, sky, null);
        }

        /** An entry without a sky log (everything but a vehicle whose pilot learns destinations). */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew, Recall recall,
              java.util.List<ShipLadder.Saved> ladders, String wood, WaterLog water) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, id, ownerName, shared, crew, recall, ladders, wood, water, null);
        }

        /** An entry without a water log (everything but a boat with a log). */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew, Recall recall,
              java.util.List<ShipLadder.Saved> ladders, String wood) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, id, ownerName, shared, crew, recall, ladders, wood, null);
        }

        /** An entry without a wood species (the prefab as it is). */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew, Recall recall,
              java.util.List<ShipLadder.Saved> ladders) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, id, ownerName, shared, crew, recall, ladders, null);
        }

        /** An entry without rope ladders (retracted). */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew, Recall recall) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, id, ownerName, shared, crew, recall, java.util.List.of());
        }

        /** An entry without a recall mark. */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals, UUID id, String ownerName, boolean shared, CrewRecord crew) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, id, ownerName, shared, crew, null);
        }

        /** The ownership data of the entry (id, owner, owner name, sharing). */
        Ownership ownership() {
            return new Ownership(id, owner, ownerName, shared);
        }

        /** An entry with cage state and animals but no ownership data beyond the owner. */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open,
              java.util.List<CageAnimals.Animal> animals) {
            this(world, x, y, z, rotation, kind, owner, descent, open, animals, null, null, false, null);
        }

        /** An entry without cage state (every type except the transport balloon). */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner) {
            this(world, x, y, z, rotation, kind, owner, 0, false, java.util.List.of());
        }

        /** An entry with cage state and no captured animal. */
        Entry(String world, int x, int y, int z, Rotation rotation, String kind, UUID owner, int descent, boolean open) {
            this(world, x, y, z, rotation, kind, owner, descent, open, java.util.List.of());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry e && world.equals(e.world) && x == e.x && y == e.y && z == e.z
                    && rotation == e.rotation && kind.equals(e.kind);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(world, x, y, z, rotation, kind);
        }
    }

    /** The deployed structures, all worlds together. */
    private static final Set<Entry> ENTRIES = ConcurrentHashMap.newKeySet();
    private static volatile Path file;

    private BalloonRegistry() {
    }

    static void init(Path dataDirectory) {
        file = dataDirectory.resolve("deployed.json");
        ENTRIES.clear();
        load();
    }

    static int size() {
        return ENTRIES.size();
    }

    /** The deployed balloons registered for this world (administration command). All balloon kinds. */
    static java.util.List<Entry> entriesIn(World world) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        String name = world.getName();
        for (Entry e : ENTRIES) {
            if (e.world.equals(name)) {
                Deployables.Kind k = Deployables.get(e.kind);
                if (k == null || !k.isBalloon()) {
                    continue;
                }
                list.add(e);
            }
        }
        return list;
    }

    /** Every deployed structure registered for this world, whatever its type (world map markers). */
    static java.util.List<Entry> allIn(World world) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        String name = world.getName();
        for (Entry e : ENTRIES) {
            if (e.world.equals(name)) {
                list.add(e);
            }
        }
        return list;
    }

    /** The deployed structures of exactly this type registered for this world (airship despawn). */
    static java.util.List<Entry> entriesOfKind(World world, String kindId) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        String name = world.getName();
        for (Entry e : ENTRIES) {
            if (e.world.equals(name) && e.kind.equals(kindId)) {
                list.add(e);
            }
        }
        return list;
    }

    /** The deployed structures of exactly this type, all worlds together (one airship per player). */
    static java.util.List<Entry> entriesOfKindAllWorlds(String kindId) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        for (Entry e : ENTRIES) {
            if (e.kind.equals(kindId)) {
                list.add(e);
            }
        }
        return list;
    }

    /** The entry of this structure (equality: world, origin, rotation, kind), null if none. */
    static Entry get(World world, Vector3i origin, Rotation rotation, String kind) {
        Entry probe = new Entry(world.getName(), origin.x, origin.y, origin.z, rotation, kind, null);
        for (Entry e : ENTRIES) {
            if (e.equals(probe)) {
                return e;
            }
        }
        return null;
    }

    /**
     * The shape of a placed structure in its registered state: the prefab shape, except for the transport balloon
     * whose cage is lowered by descent blocks and whose side may be open (TransportCage.shapeFor). Null if the type or
     * its prefab is unknown.
     */
    static StructureShape shapeOf(Entry e) {
        Deployables.Kind kind = kindOf(e);
        StructureShape base = kind == null ? null : kind.shapeOrNull();
        if (base != null && e.wood() != null && Deployables.BOAT_STATION_KIND.equals(e.kind())) {
            return BOAT_WOOD_SHAPES.computeIfAbsent(e.wood(), w -> remapShape(base, BiomeWood.woodOf(w)));
        }
        if (base != null && (e.wood() != null || e.stone() != null) && Deployables.STATION_KIND.equals(e.kind())) {
            return STATION_SHAPES.computeIfAbsent(e.wood() + "/" + e.stone(), k -> remapStationShape(base,
                    e.wood() == null ? null : BiomeWood.woodOf(e.wood()),
                    e.stone() == null ? null : new BiomeStone.Stone(e.stone()), BiomeWood::blockExists));
        }
        if (base == null || !TransportCage.isTransport(kind)) {
            return base;
        }
        return TransportCage.shapeFor(base, e.descent(), e.open());
    }

    private static final java.util.Map<String, StructureShape> BOAT_WOOD_SHAPES = new ConcurrentHashMap<>();
    private static final java.util.Map<String, StructureShape> STATION_SHAPES = new ConcurrentHashMap<>();

    /** The shape with every block name passed through BiomeWood.remap (same positions, fillers and rotations of the prefab). */
    static StructureShape remapShape(StructureShape base, BiomeWood.Wood wood) {
        return remapShape(base, wood, BiomeWood::blockExists);
    }

    /** The balloon station shape with its Softwood renamed to the biome wood and its basalt bricks to the zone stone. */
    static StructureShape remapStationShape(StructureShape base, BiomeWood.Wood wood, BiomeStone.Stone stone,
                                            java.util.function.Predicate<String> exists) {
        java.util.List<StructureShape.Cell> cells = new java.util.ArrayList<>(base.cells().size());
        for (StructureShape.Cell c : base.cells()) {
            String name = BiomeStone.remap(BiomeWood.remapStation(c.name(), wood, exists), stone, exists);
            cells.add(new StructureShape.Cell(c.x(), c.y(), c.z(), name, c.filler()));
        }
        return base.withCells(cells);
    }

    static StructureShape remapShape(StructureShape base, BiomeWood.Wood wood, java.util.function.Predicate<String> exists) {
        java.util.List<StructureShape.Cell> cells = new java.util.ArrayList<>(base.cells().size());
        for (StructureShape.Cell c : base.cells()) {
            cells.add(new StructureShape.Cell(c.x(), c.y(), c.z(), BiomeWood.remap(c.name(), wood, exists), c.filler()));
        }
        return base.withCells(cells);
    }

    /**
     * Mark of an airship waiting above a station for a recall: the quay it holds (flight id), the station (origin and rotation),
     * where the ship appeared and the temporary ladder cells (flat triples x, y, z). Removed with the entry when the ship takes off.
     */
    record Recall(UUID quayId, int stationX, int stationY, int stationZ, Rotation stationRotation, double spawnX, double spawnZ,
                  int[] ladders) {
        BsonDocument toBson() {
            BsonDocument d = new BsonDocument();
            d.put("quay", new BsonString(quayId.toString()));
            d.put("sx", new BsonInt32(stationX));
            d.put("sy", new BsonInt32(stationY));
            d.put("sz", new BsonInt32(stationZ));
            d.put("srot", new BsonString(stationRotation.name()));
            d.put("spawnX", new org.bson.BsonDouble(spawnX));
            d.put("spawnZ", new org.bson.BsonDouble(spawnZ));
            org.bson.BsonArray a = new org.bson.BsonArray();
            for (int v : ladders) {
                a.add(new BsonInt32(v));
            }
            d.put("ladders", a);
            return d;
        }

        static Recall fromBson(BsonDocument d) {
            org.bson.BsonArray a = d.getArray("ladders");
            int[] l = new int[a.size()];
            for (int i = 0; i < l.length; i++) {
                l[i] = a.get(i).asNumber().intValue();
            }
            return new Recall(UUID.fromString(d.getString("quay").getValue()), d.getNumber("sx").intValue(), d.getNumber("sy").intValue(),
                    d.getNumber("sz").intValue(), Rotation.valueOf(d.getString("srot").getValue()), d.getNumber("spawnX").doubleValue(),
                    d.getNumber("spawnZ").doubleValue(), l);
        }
    }

    /** Sets or clears the recall mark of a placed airship, written at once. Null if not registered. */
    static synchronized Entry setRecall(World world, Vector3i origin, Rotation rotation, Recall recall) {
        Entry old = get(world, origin, rotation, Deployables.AIRSHIP_KIND);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, old.crew, recall, old.ladders, old.wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    private static volatile boolean cageDirty;
    private static volatile long cageSavedMs;

    /**
     * Sets the cage state of a placed transport balloon. The file is written at once if force is true,
     * otherwise at most once per second (flushIfDue, called by the tick). Returns the new entry, null if not registered.
     */
    static synchronized Entry setCage(World world, Vector3i origin, Rotation rotation, String kind, int descent, boolean open, boolean force) {
        Entry old = get(world, origin, rotation, kind);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, descent, open, old.animals,
                old.id, old.ownerName, old.shared, old.crew, old.recall, old.ladders, old.wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        cageDirty = true;
        if (force || System.currentTimeMillis() - cageSavedMs >= 1000) {
            flushCage();
        }
        return next;
    }

    /**
     * Sets the rope ladder state (retracted when the list is empty) of a placed airship. Written at once if force is true,
     * otherwise at most once per second (same throttling as the cage). Returns the new entry, null if not registered.
     */
    static synchronized Entry setLadders(World world, Vector3i origin, Rotation rotation, java.util.List<ShipLadder.Saved> ladders, boolean force) {
        Entry old = get(world, origin, rotation, Deployables.AIRSHIP_KIND);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, old.crew, old.recall, java.util.List.copyOf(ladders), old.wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        cageDirty = true;
        if (force || System.currentTimeMillis() - cageSavedMs >= 1000) {
            flushCage();
        }
        return next;
    }

    /**
     * Sets the animals captured in the cage of a placed transport balloon, written at once. Returns the new entry, null if
     * not registered.
     */
    static synchronized Entry setAnimals(World world, Vector3i origin, Rotation rotation, String kind, java.util.List<CageAnimals.Animal> animals) {
        Entry old = get(world, origin, rotation, kind);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                java.util.List.copyOf(animals), old.id, old.ownerName, old.shared, old.crew, old.recall, old.ladders, old.wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /**
     * Sets the NPC pilot of a placed ship of the given kind, written at once. A null crew removes the pilot. Returns the new
     * entry, null if not registered.
     */
    static synchronized Entry setCrew(World world, Vector3i origin, Rotation rotation, String kindId, CrewRecord crew) {
        Entry old = get(world, origin, rotation, kindId);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, crew, old.recall, old.ladders, old.wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /** Sets the sky log of a placed vehicle with a pilot, written at once. Null if not registered. The log is stored as given: pass a copy. */
    static synchronized Entry setSky(World world, Vector3i origin, Rotation rotation, String kindId, SkyLog sky) {
        Entry old = get(world, origin, rotation, kindId);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, old.crew, old.recall, old.ladders, old.wood, old.water, sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /** Sets the water log of a placed boat, written at once. Null if not registered. The log is stored as given: pass a copy. */
    static synchronized Entry setWater(World world, Vector3i origin, Rotation rotation, String kindId, WaterLog water) {
        Entry old = get(world, origin, rotation, kindId);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, old.crew, old.recall, old.ladders, old.wood, water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /** Sets the wood species of a placed boat station, written at once. Null if not registered. */
    static synchronized Entry setWood(World world, Vector3i origin, Rotation rotation, String kind, String wood) {
        return setWoodNamed(world.getName(), origin, rotation, kind, wood);
    }

    /** Same as setWood with the world name (also used by tests). */
    static synchronized Entry setWoodNamed(String worldName, Vector3i origin, Rotation rotation, String kind, String wood) {
        Entry old = null;
        for (Entry e : ENTRIES) {
            if (e.world.equals(worldName) && e.x == origin.x && e.y == origin.y && e.z == origin.z && e.rotation == rotation
                    && e.kind.equals(kind)) {
                old = e;
            }
        }
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, old.crew, old.recall, old.ladders, wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /** Sets the stone family of a placed balloon station (null: the original basalt), written at once. Null if not registered. */
    static synchronized Entry setStone(World world, Vector3i origin, Rotation rotation, String kind, String family) {
        return setStoneNamed(world.getName(), origin, rotation, kind, family);
    }

    /** Same as setStone with the world name (also used by tests). */
    static synchronized Entry setStoneNamed(String worldName, Vector3i origin, Rotation rotation, String kind, String family) {
        Entry old = null;
        for (Entry e : ENTRIES) {
            if (e.world.equals(worldName) && e.x == origin.x && e.y == origin.y && e.z == origin.z && e.rotation == rotation
                    && e.kind.equals(kind)) {
                old = e;
            }
        }
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, old.shared, old.crew, old.recall, old.ladders, old.wood, old.water, old.sky, family);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /** Writes the registry if a cage change is waiting for it and the last write is more than one second old. */
    static synchronized void flushIfDue() {
        if (cageDirty && System.currentTimeMillis() - cageSavedMs >= 1000) {
            flushCage();
        }
    }

    /** Writes the registry if a cage change is waiting for it (stop of a movement, server shutdown). */
    static synchronized void flushCage() {
        if (cageDirty) {
            cageDirty = false;
            cageSavedMs = System.currentTimeMillis();
            save();
        }
    }

    /** Registers a deployed structure of the given type, with its owner (nullable). No effect if already there. Never throws. */
    static synchronized void add(World world, Vector3i origin, Rotation rotation, String kind, UUID owner) {
        add(world, origin, rotation, kind, Ownership.of(owner, null));
    }

    /** Registers a deployed structure with its ownership data (the id is created if absent). No effect if already there. Never throws. */
    static synchronized void add(World world, Vector3i origin, Rotation rotation, String kind, Ownership own) {
        try {
            addNamed(world.getName(), origin, rotation, kind, own);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Structure « %s » non enregistrée dans le registre de verrouillage", kind);
        }
    }

    /** Same as add with the world name (also used by tests, a World cannot be built outside the game). */
    static synchronized void addNamed(String worldName, Vector3i origin, Rotation rotation, String kind, Ownership own) {
        Ownership o = own.withId();
        if (ENTRIES.add(new Entry(worldName, origin.x, origin.y, origin.z, rotation, kind, o.owner(), 0, false,
                java.util.List.of(), o.id(), o.ownerName(), o.shared(), null))) {
            save();
        }
    }

    /** Test hook: the entries of a world by name. */
    static java.util.List<Entry> entriesOfNameForTest(String worldName) {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        for (Entry e : ENTRIES) {
            if (e.world.equals(worldName)) {
                list.add(e);
            }
        }
        return list;
    }

    /** The entry with this structure id, in any world, or null. */
    static Entry byId(UUID id) {
        if (id == null) {
            return null;
        }
        for (Entry e : ENTRIES) {
            if (id.equals(e.id)) {
                return e;
            }
        }
        return null;
    }

    /** Sets the sharing of the entry with this id, written at once. Returns the new entry, null if unknown. */
    static synchronized Entry setShared(UUID id, boolean shared) {
        Entry old = byId(id);
        if (old == null) {
            return null;
        }
        Entry next = new Entry(old.world, old.x, old.y, old.z, old.rotation, old.kind, old.owner, old.descent, old.open,
                old.animals, old.id, old.ownerName, shared, old.crew, old.recall, old.ladders, old.wood, old.water, old.sky, old.stone);
        ENTRIES.remove(old);
        ENTRIES.add(next);
        save();
        return next;
    }

    /** Removes a structure from the registry, whatever its owner. Never throws. */
    static synchronized void remove(World world, Vector3i origin, Rotation rotation, String kind) {
        try {
            if (ENTRIES.remove(new Entry(world.getName(), origin.x, origin.y, origin.z, rotation, kind, null))) {
                save();
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Structure « %s » non retirée du registre de verrouillage", kind);
        }
    }

    private static synchronized void remove(Entry entry) {
        if (ENTRIES.remove(entry)) {
            LOGGER.at(Level.INFO).log("Structure « %s » en (%d, %d, %d) du monde %s absente : retirée du registre de verrouillage",
                    entry.kind, entry.x, entry.y, entry.z, entry.world);
            save();
        }
    }

    /**
     * True if the cell is locked.
     *
     * Breaking or damaging a block (broken non-null): locked if it is a block of the structure, that is,
     * if the prefab has a block of that type at this cell. A terrain block left in an unfolded ladder cell
     * is therefore not protected. Placing a block (broken null): locked in any prefab cell and in the
     * type's forbidden placement volume.
     */
    static boolean isLocked(World world, Vector3i pos, BlockType broken, boolean placing) {
        return lockingEntry(world, pos, broken, placing) != null;
    }

    /** The entry that locks this cell (same rules as isLocked), null if none. Used to choose the message. */
    static Entry lockingEntry(World world, Vector3i pos, BlockType broken, boolean placing) {
        if (ENTRIES.isEmpty()) {
            return null;
        }
        String worldName = world.getName();
        for (Entry e : ENTRIES) {
            if (!e.world.equals(worldName)) {
                continue;
            }
            Deployables.Kind kind = kindOf(e);
            StructureShape shape = shapeOf(e);
            if (shape == null) {
                continue;
            }
            if (!e.ladders.isEmpty() && isLadderRung(e, pos)) {
                // A rope ladder rung under an airship: only the rung block can be broken, nothing can be placed.
                if ((placing || broken == null || ShipLadder.ROPE_BLOCK.equals(broken.getId())) && stillThere(world, shape, e)) {
                    return e;
                }
            }
            Vector3i local = e.rotation.toInverse().rotateYaw(new Vector3i(pos.x - e.x, pos.y - e.y, pos.z - e.z), new Vector3i());
            if (!shape.inBounds(local.x, local.y, local.z)) {
                continue;
            }
            StructureShape.Cell cell = shape.cellAt(local.x, local.y, local.z);
            boolean inside;
            if (placing) {
                inside = cell != null || kind.inPlaceVolume(local.x, local.y, local.z)
                        || TransportCage.placeForbidden(kind, e, local.x, local.y, local.z);
            } else {
                inside = cell != null && (broken == null || BalloonManager.sameBlock(broken, cell));
            }
            if (!inside) {
                continue;
            }
            if (!stillThere(world, shape, e)) {
                remove(e);
                continue;
            }
            return e;
        }
        return null;
    }

    private static boolean isLadderRung(Entry e, Vector3i pos) {
        for (ShipLadder.Saved l : e.ladders) {
            int[] f = l.rungs();
            for (int i = 0; i + 2 < f.length; i += 3) {
                if (f[i] == pos.x && f[i + 1] == pos.y && f[i + 2] == pos.z) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The entry whose shape contains a block at this world cell, null if none. Same calculation as isLocked, without the placement volume. */
    static Entry find(World world, Vector3i pos) {
        if (ENTRIES.isEmpty()) {
            return null;
        }
        String worldName = world.getName();
        for (Entry e : ENTRIES) {
            if (!e.world.equals(worldName)) {
                continue;
            }
            StructureShape shape = shapeOf(e);
            if (shape == null) {
                continue;
            }
            Vector3i local = e.rotation.toInverse().rotateYaw(new Vector3i(pos.x - e.x, pos.y - e.y, pos.z - e.z), new Vector3i());
            if (!shape.inBounds(local.x, local.y, local.z) || shape.cellAt(local.x, local.y, local.z) == null) {
                continue;
            }
            if (!stillThere(world, shape, e)) {
                remove(e);
                continue;
            }
            return e;
        }
        return null;
    }

    private static final Set<String> UNKNOWN_WARNED = ConcurrentHashMap.newKeySet();

    /** The entry's type, null if unknown (single warning, the entry is kept in the file). */
    private static Deployables.Kind kindOf(Entry e) {
        Deployables.Kind kind = Deployables.get(e.kind);
        if (kind == null && UNKNOWN_WARNED.add(e.kind)) {
            LOGGER.at(Level.WARNING).log("Registre de verrouillage : type de structure inconnu « %s », entrées ignorées", e.kind);
        }
        return kind;
    }

    /**
     * True only when the entry is provably gone: its world is loaded, its anchor section is in memory and the block there
     * is not the anchor. Unknown (world not loaded, section not in memory, any error) counts as present. A gone entry is
     * removed from the registry, like find and lockingEntry do.
     */
    static boolean removeIfGone(Entry e) {
        try {
            com.hypixel.hytale.server.core.universe.Universe universe = com.hypixel.hytale.server.core.universe.Universe.get();
            if (universe == null) {
                return false;
            }
            World world = null;
            for (World w : universe.getWorlds().values()) {
                if (w.getName().equals(e.world)) {
                    world = w;
                    break;
                }
            }
            StructureShape shape = shapeOf(e);
            if (world == null || shape == null || stillThere(world, shape, e)) {
                return false;
            }
            remove(e);
            return true;
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Vérification de l'entrée du registre en échec : gardée");
            return false;
        }
    }

    /** Is the anchor block in place? An unloaded chunk (unknown block) counts as "yes". */
    private static boolean stillThere(World world, StructureShape shape, Entry e) {
        StructureShape.Cell anchor = shape.anchor();
        Vector3i p = anchor.rotated(e.rotation).add(e.x, e.y, e.z);
        SectionReader reader = new SectionReader(world.getChunkStore());
        // Section not in memory: unknown block counts as "still there", the entry must never be dropped for that.
        if (!reader.hasStorageInMemory(p.x, p.y, p.z)) {
            return true;
        }
        BlockType bt = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
        return bt == null || BalloonManager.sameBlock(bt, anchor);
    }

    // ------------------------------------------------------------------ file

    /** One registry entry as a BSON document (the on-disk format; "crew" only when there is an NPC pilot). */
    static BsonDocument entryToBson(Entry e) {
        BsonDocument d = new BsonDocument();
        d.put("world", new BsonString(e.world));
        d.put("x", new BsonInt32(e.x));
        d.put("y", new BsonInt32(e.y));
        d.put("z", new BsonInt32(e.z));
        d.put("rotation", new BsonString(e.rotation.name()));
        d.put("kind", new BsonString(e.kind));
        e.ownership().writeTo(d);
        if (e.descent > 0) {
            d.put("descent", new BsonInt32(e.descent));
        }
        if (e.open) {
            d.put("open", new org.bson.BsonBoolean(true));
        }
        if (!e.animals.isEmpty()) {
            d.put("animals", CageAnimals.toBson(e.animals));
        }
        if (e.crew != null) {
            d.put("crew", e.crew.toBson());
        }
        if (e.recall != null) {
            d.put("recall", e.recall.toBson());
        }
        if (e.wood != null) {
            d.put("wood", new BsonString(e.wood));
        }
        if (e.stone != null) {
            d.put("stone", new BsonString(e.stone));
        }
        if (e.water != null && !e.water.isEmpty()) {
            d.put("water", e.water.toBson());
        }
        if (e.sky != null && !e.sky.isEmpty()) {
            d.put("sky", e.sky.toBson());
        }
        if (!e.ladders.isEmpty()) {
            org.bson.BsonArray a = new org.bson.BsonArray();
            for (ShipLadder.Saved l : e.ladders) {
                a.add(l.toBson());
            }
            d.put("ladders", a);
        }
        return d;
    }

    private static java.util.List<ShipLadder.Saved> laddersFromBson(org.bson.BsonArray a) {
        java.util.List<ShipLadder.Saved> list = new java.util.ArrayList<>();
        for (org.bson.BsonValue v : a) {
            list.add(ShipLadder.Saved.fromBson(v.asDocument()));
        }
        return java.util.List.copyOf(list);
    }

    /** Reads one registry entry, tolerating files written by older versions. */
    static Entry entryFromBson(BsonDocument d) {
        // Version 1: no kind, it is a balloon. Version 2: kind and owner (absent if null).
        String kind = d.containsKey("kind") ? d.getString("kind").getValue() : Deployables.BALLOON_KIND;
        Ownership own = Ownership.readFrom(d).withId();
        return new Entry(d.getString("world").getValue(), d.getNumber("x").intValue(),
                d.getNumber("y").intValue(), d.getNumber("z").intValue(),
                Rotation.valueOf(d.getString("rotation").getValue()), kind, own.owner(),
                // cage state of the transport balloon, absent in older files (0, closed).
                d.containsKey("descent") ? d.getNumber("descent").intValue() : 0,
                d.containsKey("open") && d.getBoolean("open").getValue(),
                // captured animals, absent in older files.
                d.containsKey("animals") ? CageAnimals.fromBson(d.getArray("animals")) : java.util.List.of(),
                own.id(), own.ownerName(), own.shared(),
                // NPC pilot, absent in older files.
                d.containsKey("crew") ? CrewRecord.fromBson(d.getDocument("crew")) : null,
                // recall mark of an airship waiting above a station, absent otherwise.
                d.containsKey("recall") ? Recall.fromBson(d.getDocument("recall")) : null,
                d.containsKey("ladders") ? laddersFromBson(d.getArray("ladders")) : java.util.List.of(),
                d.containsKey("wood") ? d.getString("wood").getValue() : null,
                d.containsKey("water") ? WaterLog.fromBson(d.getDocument("water")) : null,
                d.containsKey("sky") ? SkyLog.fromBson(d.getDocument("sky")) : null,
                d.containsKey("stone") ? d.getString("stone").getValue() : null);
    }

    private static void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            BsonDocument doc = BsonDocument.parse(Files.readString(f, StandardCharsets.UTF_8));
            boolean idCreated = false;
            for (org.bson.BsonValue v : doc.getArray("balloons")) {
                BsonDocument d = v.asDocument();
                if (Ownership.readFrom(d).id() == null) {
                    idCreated = true;
                }
                ENTRIES.add(entryFromBson(d));
            }
            if (idCreated) {
                // Persist the created ids so they stay stable across restarts.
                save();
            }
            LOGGER.at(Level.INFO).log("Registre de verrouillage : %d structure(s)", ENTRIES.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Registre de verrouillage illisible : %s", f);
            ENTRIES.clear();
            try {
                Files.move(f, f.resolveSibling(f.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The file stays in place.
            }
        }
    }

    /** Writes the whole registry (temporary file then atomic rename, like the recovery file). */
    private static void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            BsonArray list = new BsonArray();
            for (Entry e : ENTRIES) {
                BsonDocument d = entryToBson(e);
                list.add(d);
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(VERSION));
            doc.put("balloons", list);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Registre de verrouillage non écrit");
        }
    }
}
