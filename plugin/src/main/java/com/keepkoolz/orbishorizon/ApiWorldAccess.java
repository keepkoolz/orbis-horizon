package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.keepkoolz.orbishorizon.api.PlacedStructure;
import com.keepkoolz.orbishorizon.api.WorldAccess;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Block, fluid and section access for the public API, backed by SectionBlocks and the balloon registry. */
final class ApiWorldAccess implements WorldAccess {
    @Override
    public boolean outOfHeight(int y) {
        return BalloonManager.outOfHeight(y);
    }

    @Override
    public BlockType typeAt(SectionReader reader, int x, int y, int z) {
        return SectionBlocks.typeAt(reader, x, y, z);
    }

    @Override
    public String liquidAt(SectionReader reader, int x, int y, int z) {
        return BalloonManager.liquidAt(reader, x, y, z);
    }

    @Override
    public boolean isWaterCell(SectionReader reader, int x, int y, int z) {
        return SteamboatWater.isWaterCell(reader, x, y, z);
    }

    @Override
    public boolean setBlock(ChunkStore store, int x, int y, int z, String blockKey, int settings) {
        return SectionBlocks.setAt(store, x, y, z, blockKey, settings);
    }

    @Override
    public boolean setFluid(ChunkStore store, int x, int y, int z, int fluidId, byte level) {
        return SectionBlocks.setFluidAt(store, x, y, z, fluidId, level);
    }

    @Override
    public boolean sectionsLoaded(ChunkStore store, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return SectionBlocks.sectionsLoaded(store, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public CompletableFuture<Void> loadSections(ChunkStore store, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return SectionBlocks.loadSections(store, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public List<PlacedStructure> structuresIn(World world) {
        List<PlacedStructure> out = new ArrayList<>();
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesIn(world)) {
            out.add(new PlacedStructure(e.kind(), e.x(), e.y(), e.z(), structureBox(e)));
        }
        return out;
    }

    /** World box of a deployed structure (its shape bounds rotated and placed at its origin), null when its shape is unknown. */
    static int[] structureBox(BalloonRegistry.Entry r) {
        StructureShape shape = BalloonRegistry.shapeOf(r);
        if (shape == null) {
            return null;
        }
        int[] b = shape.bounds();
        if (b[0] > b[3]) {
            return null;
        }
        Vector3i origin = new Vector3i(r.x(), r.y(), r.z());
        Rotation rotation = r.rotation();
        int[] out = null;
        for (int x : new int[] {b[0], b[3]}) {
            for (int y : new int[] {b[1], b[4]}) {
                for (int z : new int[] {b[2], b[5]}) {
                    Vector3i p = rotation.rotateYaw(new Vector3i(x, y, z), new Vector3i()).add(origin);
                    if (out == null) {
                        out = new int[] {p.x, p.y, p.z, p.x, p.y, p.z};
                    } else {
                        out[0] = Math.min(out[0], p.x);
                        out[1] = Math.min(out[1], p.y);
                        out[2] = Math.min(out[2], p.z);
                        out[3] = Math.max(out[3], p.x);
                        out[4] = Math.max(out[4], p.y);
                        out[5] = Math.max(out[5], p.z);
                    }
                }
            }
        }
        return out;
    }
}
