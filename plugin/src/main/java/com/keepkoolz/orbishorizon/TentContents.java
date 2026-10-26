package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3i;

import java.util.List;
import java.util.logging.Level;

/**
 * Contents of a tent (chests and campfire) kept in the crate item's metadata while the tent is packed.
 * Reuses the airship mechanisms: benches by AirshipBenchContents, containers by BalloonCargo, both with their own retries.
 * Positions are in the prefab frame, before rotation.
 */
final class TentContents {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Key of the crate item's metadata document. */
    static final String METADATA_KEY = "OrbisHorizon_TentContents";
    private static final int VERSION = 1;

    private final AirshipBenchContents benches;
    private final BalloonCargo cargo;

    /** Forbidden stacks (camping packs) found in the chests at capture, to drop on the ground; empty otherwise. */
    private List<com.hypixel.hytale.server.core.inventory.ItemStack> ejected = List.of();
    private Vector3i ejectedPos;

    TentContents(AirshipBenchContents benches, BalloonCargo cargo) {
        this.benches = benches;
        this.cargo = cargo;
    }

    List<com.hypixel.hytale.server.core.inventory.ItemStack> ejected() {
        return ejected;
    }

    /** Prefab-frame position of the first container an ejected stack came from, null if none. */
    Vector3i ejectedPos() {
        return ejectedPos;
    }

    /** Copies then empties the benches and containers of the tent. Null if there is nothing to keep. */
    static TentContents capture(World world, StructureShape shape, Vector3i origin, Rotation rotation) {
        // Only cells whose world block matches the prefab cell are read: an old tent may hold a foreign block
        // (and its container) where the current prefab has a cell, which must not be emptied.
        List<StructureShape.Cell> matching = new java.util.ArrayList<>();
        com.hypixel.hytale.server.core.universe.world.accessor.SectionReader reader =
                new com.hypixel.hytale.server.core.universe.world.accessor.SectionReader(world.getChunkStore());
        for (StructureShape.Cell cell : shape.cells()) {
            Vector3i p = cell.rotated(rotation).add(origin);
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bt = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
            if (bt != null && BalloonManager.sameBlock(bt, cell)) {
                matching.add(cell);
            }
        }
        StructureShape filtered = StructureShape.ofCells(matching);
        AirshipBenchContents b = AirshipBenchContents.takeFrom(world, filtered, origin, rotation);
        BalloonCargo c = BalloonCargo.takeFrom(world, filtered, origin, rotation);
        // Safety net: a camping pack cannot stay in a tent chest (no nesting). It is ejected, nothing is lost.
        CampChestFilter.Split split = CampChestFilter.split(c);
        if (b.entryCount() == 0 && split.kept().isEmpty() && split.ejected().isEmpty()) {
            return null;
        }
        TentContents result = new TentContents(b, split.kept());
        result.ejected = split.ejected();
        result.ejectedPos = split.dropPos();
        return result;
    }

    BsonDocument toBson(String kind) {
        BsonDocument d = new BsonDocument();
        d.put("v", new BsonInt32(VERSION));
        d.put("kind", new BsonString(kind));
        d.put("benches", benches.toBson());
        d.put("cargo", cargo.toBson());
        return d;
    }

    /** Null if d is null, has another version or another kind. An unreadable part becomes empty with a warning. Never throws. */
    static TentContents fromBson(BsonDocument d, String expectedKind) {
        if (d == null) {
            return null;
        }
        try {
            if (!d.containsKey("v") || !d.get("v").isNumber() || d.get("v").asNumber().intValue() != VERSION) {
                LOGGER.at(Level.WARNING).log("Contenu de tente : version inconnue, ignoré");
                return null;
            }
            String kind = d.containsKey("kind") && d.get("kind").isString() ? d.getString("kind").getValue() : null;
            if (!expectedKind.equals(kind)) {
                LOGGER.at(Level.WARNING).log("Contenu de tente : type %s au lieu de %s, ignoré", kind, expectedKind);
                return null;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Contenu de tente illisible, ignoré");
            return null;
        }
        AirshipBenchContents b;
        try {
            b = AirshipBenchContents.fromBson(d.getDocument("benches"));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Contenu de tente : établis illisibles, remplacés par un ensemble vide");
            b = AirshipBenchContents.fromBson(null);
        }
        BalloonCargo c;
        try {
            c = BalloonCargo.fromBson(d.getDocument("cargo"));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Contenu de tente : coffres illisibles, remplacés par un ensemble vide");
            c = new BalloonCargo(new java.util.ArrayList<>());
        }
        return new TentContents(b, c);
    }

    /** Benches then chests; the existing retries (every tick) finish what is not ready yet. */
    void restore(World world, Store<EntityStore> store, Vector3i origin, Rotation rotation) {
        benches.restore(world, store, origin, rotation);
        BalloonManager.get().restoreCargo(world, store, cargo, origin, rotation);
    }

    private static final String CAMPFIRE_BLOCK = "Bench_Campfire";

    /**
     * the contents re-addressed to another tent's prefab (small tent to large tent). The campfire goes to the target's
     * campfire cell. Containers go in ascending (y, x, z) order of their positions, source to target in the same order. A source
     * container with no target of its own is addressed to the last target container: restoring puts its stacks in free slots
     * there and drops what does not fit. Pure: reads only the positions in the shapes and in this object.
     */
    TentContents convert(StructureShape to) {
        Vector3i fire = null;
        List<Vector3i> chests = new java.util.ArrayList<>();
        for (StructureShape.Cell c : to.cells()) {
            if (c.filler() != 0) {
                continue;
            }
            if (CAMPFIRE_BLOCK.equals(c.baseName()) && fire == null) {
                fire = new Vector3i(c.x(), c.y(), c.z());
            } else if ("Furniture_Kweebec_Chest_Small".equals(c.baseName())) {
                chests.add(new Vector3i(c.x(), c.y(), c.z()));
            }
        }
        chests.sort(TentContents::byYxz);
        AirshipBenchContents newBenches = fire != null ? benches.relocated(fire) : benches;
        List<BalloonCargo.Entry> sources = new java.util.ArrayList<>(cargo.entries());
        sources.sort((a, b) -> byYxz(a.local, b.local));
        List<BalloonCargo.Entry> out = new java.util.ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            BalloonCargo.Entry e = sources.get(i);
            if (chests.isEmpty()) {
                out.add(e);
                continue;
            }
            Vector3i target = chests.get(Math.min(i, chests.size() - 1));
            out.add(new BalloonCargo.Entry(new Vector3i(target), e.blockName, e.capacity, e.slots));
        }
        return new TentContents(newBenches, new BalloonCargo(out));
    }

    private static int byYxz(Vector3i a, Vector3i b) {
        if (a.y != b.y) {
            return Integer.compare(a.y, b.y);
        }
        if (a.x != b.x) {
            return Integer.compare(a.x, b.x);
        }
        return Integer.compare(a.z, b.z);
    }

    int itemCount() {
        return benches.itemCount() + cargo.itemCount();
    }
}
