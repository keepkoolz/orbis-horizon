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
 * Contents of a tent (chests and campfire) kept in the crate item's metadata while the tent is packed (17 October 2026).
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

    TentContents(AirshipBenchContents benches, BalloonCargo cargo) {
        this.benches = benches;
        this.cargo = cargo;
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
        if (b.entryCount() == 0 && c.isEmpty()) {
            return null;
        }
        return new TentContents(b, c);
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

    int itemCount() {
        return benches.itemCount() + cargo.itemCount();
    }
}
