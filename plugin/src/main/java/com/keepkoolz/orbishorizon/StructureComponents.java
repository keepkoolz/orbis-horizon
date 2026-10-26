package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Guard against silent loss: before a structure is removed, every cell whose block carries a container or a bench
 * component must give that component (SectionBlocks.blockComponent, also in a section without players near). Otherwise the contents
 * and the bench level would be dropped with the block, so the take-off or the packing is refused before anything is removed.
 */
final class StructureComponents {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private StructureComponents() {
    }

    /** Pure decision: the expected cells that are not in the found set, in the order given. */
    static List<Vector3i> missing(List<Vector3i> expected, Set<Vector3i> found) {
        List<Vector3i> out = new ArrayList<>();
        for (Vector3i p : expected) {
            if (!found.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** True if the block type declares a container or bench block entity in its template. */
    private static boolean carriesComponent(BlockType type) {
        Holder<ChunkStore> template = type.getBlockEntity();
        if (template == null) {
            return false;
        }
        return template.getComponent(ItemContainerBlock.getComponentType()) != null
                || template.getComponent(BenchBlock.getComponentType()) != null
                || template.getComponent(ProcessingBenchBlock.getComponentType()) != null;
    }

    /**
     * Cells (world positions) of the structure whose placed block matches the prefab, has a container or bench block entity
     * and gives no component. Empty list if all is readable. Call on the world thread before removing anything.
     */
    static List<Vector3i> unreadable(World world, StructureShape shape, Vector3i origin, Rotation rotation) {
        List<Vector3i> expected = new ArrayList<>();
        Set<Vector3i> found = new java.util.HashSet<>();
        ChunkStore cs = world.getChunkStore();
        SectionReader reader = new SectionReader(cs);
        for (StructureShape.Cell c : shape.cells()) {
            if (c.filler() != 0) {
                continue; // only the origin cell of a multi-cell block carries the component
            }
            Vector3i p = c.rotated(rotation).add(origin);
            try {
                BlockType placed = SectionBlocks.typeAt(reader, p.x, p.y, p.z);
                if (placed == null || !BalloonManager.sameBlock(placed, c) || !carriesComponent(placed)) {
                    continue;
                }
                expected.add(p);
                if (SectionBlocks.blockComponent(cs, ItemContainerBlock.getComponentType(), p.x, p.y, p.z) != null
                        || SectionBlocks.blockComponent(cs, BenchBlock.getComponentType(), p.x, p.y, p.z) != null
                        || SectionBlocks.blockComponent(cs, ProcessingBenchBlock.getComponentType(), p.x, p.y, p.z) != null) {
                    found.add(p);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Contrôle des composants en %s impossible", p);
                expected.add(p);
            }
        }
        List<Vector3i> missing = missing(expected, found);
        if (!missing.isEmpty()) {
            LOGGER.at(Level.WARNING).log("Composants de blocs illisibles (%d case(s), premier en %s), retrait refusé", missing.size(),
                    missing.get(0));
        }
        return missing;
    }
}
