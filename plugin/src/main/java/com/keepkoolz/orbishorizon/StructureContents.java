package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Contents of the containers of a placed structure, to drop them on the ground when it is packed (T46). Reuses the logic
 * of BurnerFuel.takeFrom (processing benches) and BalloonCargo.takeFrom (containers), without the burner case:
 * for each cell of the rotated shape, every ProcessingBenchBlock (the tent's campfire) and every
 * ItemContainerBlock is copied then emptied. The containers are emptied before the blocks are removed, so the game drops nothing
 * extra (finding of T40): the plugin is the only way of dropping. Call on the world thread.
 */
final class StructureContents {

    /** A stack taken from a container, with the world cell where it was. */
    record Found(ItemStack stack, Vector3i pos) {
    }

    private StructureContents() {
    }

    static List<Found> takeAll(World world, BalloonShape shape, Vector3i origin, Rotation rotation) {
        List<Found> list = new ArrayList<>();
        for (BalloonShape.Cell c : shape.cells()) {
            Vector3i p = c.rotated(rotation).add(origin);
            // Processing bench: inputs, fuel (taken by BurnerFuel.takeFrom), outputs and extra outputs.
            // BurnerFuel.takeFrom also resets the burn time in progress (reflection).
            ProcessingBenchBlock bench = BurnerFuel.live(world, p);
            if (bench != null) {
                for (ItemStack st : BurnerFuel.takeFrom(bench).takeAllItems()) {
                    list.add(new Found(st, p));
                }
            }
            // Container (like BalloonCargo.takeFrom): live component, not a copy.
            ItemContainerBlock block = BlockModule.getComponent(ItemContainerBlock.getComponentType(), world, p.x, p.y, p.z);
            ItemContainer container = block != null ? block.getItemContainer() : null;
            if (container == null || container.isEmpty()) {
                continue;
            }
            for (short s = 0; s < container.getCapacity(); s++) {
                ItemStack st = container.getItemStack(s);
                if (st != null && !st.isEmpty()) {
                    list.add(new Found(st, p));
                }
            }
            container.clear();
        }
        return list;
    }
}
