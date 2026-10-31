package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * The chests kept in a camping pack (small or large tent), opened as a container window while the player crouches with the pack
 * in hand. One container per chest cell of the tent prefab, filled from the pack's TentContents; the large tent shows both chests
 * in one combined window. Every change rewrites the contents in the pack's metadata, in the slot the window was opened from. If
 * that slot no longer holds the same pack (moved, dropped, placed, changed elsewhere), the window closes and nothing is written,
 * so the contents can never be duplicated.
 *
 * The pure part (slots from contents, contents from slots) does not touch the server and is unit-tested.
 */
final class PackChests {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String CHEST_BLOCK = "Furniture_Kweebec_Chest_Small";
    /** Capacity of a tent chest when neither the stored contents nor the block type give one. */
    static final short DEFAULT_CAPACITY = 18;

    private PackChests() {
    }

    // ------------------------------------------------------------------ pure logic

    /** Prefab-frame positions of the chest cells of a tent shape, in ascending (y, x, z) order (the order of the window). */
    static List<Vector3i> chestCells(StructureShape shape) {
        List<Vector3i> cells = new ArrayList<>();
        for (StructureShape.Cell c : shape.cells()) {
            if (c.filler() == 0 && CHEST_BLOCK.equals(c.baseName())) {
                cells.add(new Vector3i(c.x(), c.y(), c.z()));
            }
        }
        cells.sort(TentContents::byYxz);
        return cells;
    }

    /**
     * Slots of every chest cell, and the stored entries that have no cell to show in (position unknown to the shape, or no room left
     * in a cell that several entries were addressed to). Leftovers are kept as they are when the contents are rebuilt.
     */
    record Layout(ItemStack[][] slots, List<BalloonCargo.Entry> leftovers) {
    }

    /**
     * Slots for each cell, from the stored chests (matched by prefab-frame position) or empty with defaultCapacity.
     * A null contents gives empty chests. A stack that does not fit its slot index goes to the first free slot of its chest.
     */
    static Layout toSlots(TentContents contents, List<Vector3i> cells, short[] defaultCapacity) {
        ItemStack[][] slots = new ItemStack[cells.size()][];
        boolean[] filled = new boolean[cells.size()];
        List<BalloonCargo.Entry> leftovers = new ArrayList<>();
        if (contents != null) {
            for (BalloonCargo.Entry e : contents.cargo().entries()) {
                int i = indexOf(cells, e.local);
                if (i < 0) {
                    leftovers.add(e);
                    continue;
                }
                if (!filled[i]) {
                    slots[i] = e.slots.clone();
                    filled[i] = true;
                    continue;
                }
                // a second entry addressed to the same chest (small tent contents converted to a large tent)
                List<ItemStack> rest = new ArrayList<>();
                for (int s = 0; s < e.slots.length; s++) {
                    ItemStack st = e.slots[s];
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    if (s < slots[i].length && slots[i][s] == null) {
                        slots[i][s] = st;
                    } else {
                        rest.add(st);
                    }
                }
                for (ItemStack st : rest) {
                    int free = firstFree(slots[i]);
                    if (free >= 0) {
                        slots[i][free] = st;
                    } else {
                        ItemStack[] one = new ItemStack[1];
                        one[0] = st;
                        leftovers.add(new BalloonCargo.Entry(e.local, e.blockName, (short) 1, one));
                    }
                }
            }
        }
        for (int i = 0; i < slots.length; i++) {
            if (!filled[i]) {
                slots[i] = new ItemStack[defaultCapacity[i]];
            }
        }
        return new Layout(slots, leftovers);
    }

    /**
     * New contents from the slots of each cell: the benches of base are kept as they are, the chests are replaced by the
     * non-empty cells (as at capture), the leftovers of the layout stay. Null base means no campfire kept.
     */
    static TentContents fromSlots(TentContents base, List<Vector3i> cells, ItemStack[][] slots, List<BalloonCargo.Entry> leftovers) {
        List<BalloonCargo.Entry> entries = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            boolean any = false;
            ItemStack[] copy = new ItemStack[slots[i].length];
            for (int s = 0; s < copy.length; s++) {
                ItemStack st = slots[i][s];
                if (st != null && !st.isEmpty()) {
                    copy[s] = st;
                    any = true;
                }
            }
            if (any) {
                entries.add(new BalloonCargo.Entry(new Vector3i(cells.get(i)), CHEST_BLOCK, (short) copy.length, copy));
            }
        }
        entries.addAll(leftovers);
        AirshipBenchContents benches = base != null ? base.benches() : AirshipBenchContents.fromBson(null);
        return new TentContents(benches, new BalloonCargo(entries));
    }

    /** True when the stored metadata document is exactly the one we last wrote (or the one the window opened with). */
    static boolean sameMetadata(BsonDocument expected, BsonDocument actual) {
        return Objects.equals(expected, actual);
    }

    private static int indexOf(List<Vector3i> cells, Vector3i p) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).equals(p)) {
                return i;
            }
        }
        return -1;
    }

    private static int firstFree(ItemStack[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ opening

    /**
     * Opens the chests of the pack held in the interaction's slot. Returns true when the pack was handled (window opened, or a
     * message sent), false when the pack has no chest to show and must be deployed as usual. Never throws.
     */
    static boolean open(InteractionContext context, Deployables.Kind kind, StructureShape shape) {
        try {
            List<Vector3i> cells = chestCells(shape);
            if (cells.isEmpty()) {
                return false;
            }
            Ref<EntityStore> ref = context.getEntity();
            Player player = context.getCommandBuffer().getComponent(ref, Player.getComponentType());
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef =
                    context.getCommandBuffer().getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            ItemStack held = context.getHeldItem();
            ItemContainer heldContainer = context.getHeldItemContainer();
            if (player == null || playerRef == null || held == null || held.isEmpty() || heldContainer == null || held.getQuantity() != 1) {
                return false;
            }
            if (player.getPageManager().getCustomPage() != null) {
                return true; // another page is open: nothing to do, and the pack is not deployed
            }
            BsonDocument meta = held.getMetadata();
            TentContents contents = null;
            if (meta != null && meta.containsKey(TentContents.METADATA_KEY)) {
                if (meta.get(TentContents.METADATA_KEY).isDocument()) {
                    contents = TentContents.fromBson(meta.getDocument(TentContents.METADATA_KEY), kind.id);
                }
                if (contents == null) {
                    // unreadable: opening would overwrite what the pack keeps
                    playerRef.sendMessage(Texts.t("tent.packUnreadable"));
                    return true;
                }
            }
            short[] caps = new short[cells.size()];
            short blockCapacity = blockCapacity();
            java.util.Arrays.fill(caps, blockCapacity);
            Layout layout = toSlots(contents, cells, caps);

            SimpleItemContainer[] boxes = new SimpleItemContainer[cells.size()];
            for (int i = 0; i < boxes.length; i++) {
                ItemStack[] chest = layout.slots()[i];
                boxes[i] = new SimpleItemContainer((short) chest.length);
                for (short s = 0; s < chest.length; s++) {
                    if (chest[s] != null) {
                        boxes[i].setItemStackForSlot(s, chest[s], false);
                    }
                }
                CampChestFilter.install(boxes[i]);
            }
            ItemContainer shown = boxes.length == 1 ? boxes[0] : new CombinedItemContainer(boxes);

            PackWindow window = new PackWindow(shown, boxes, new Tracker(heldContainer, (short) context.getHeldItemSlot(),
                    held.getItemId(), meta, contents, cells, layout.leftovers(), kind.id));
            Store<EntityStore> store = ref.getStore();
            boolean ok = player.getPageManager().setPageWithWindows(ref, store, Page.Bench, true, new Window[]{window});
            LOGGER.at(Level.INFO).log("Pack chests of \"%s\" opened by %s (%d chest(s), shown: %s)", kind.id, playerRef.getUsername(), boxes.length, ok);
            return true;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pack chests: cannot open");
            Telemetry.error("packchests", e, Telemetry.kindId(kind), null);
            return true;
        }
    }

    /** Capacity of a tent chest from the block type's container component, DEFAULT_CAPACITY if unavailable. */
    private static short blockCapacity() {
        try {
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bt =
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(CHEST_BLOCK);
            if (bt != null && bt.getBlockEntity() != null) {
                ItemContainerBlock c = bt.getBlockEntity().getComponent(ItemContainerBlock.getComponentType());
                if (c != null && c.getCapacity() > 0) {
                    return c.getCapacity();
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pack chests: chest capacity unreadable");
        }
        LOGGER.at(Level.INFO).log("Pack chests: chest capacity not found in the block type, using %d", DEFAULT_CAPACITY);
        return DEFAULT_CAPACITY;
    }

    // ------------------------------------------------------------------ pack tracking

    /** Where the pack is and what its metadata must be, to write only into the pack the window was opened from. */
    private static final class Tracker {
        private final ItemContainer holder;
        private final short slot;
        private final String itemId;
        private BsonDocument expected;
        private final TentContents base;
        private final List<Vector3i> cells;
        private final List<BalloonCargo.Entry> leftovers;
        private final String kindId;

        Tracker(ItemContainer holder, short slot, String itemId, BsonDocument expected, TentContents base,
                List<Vector3i> cells, List<BalloonCargo.Entry> leftovers, String kindId) {
            this.holder = holder;
            this.slot = slot;
            this.itemId = itemId;
            this.expected = expected;
            this.base = base;
            this.cells = cells;
            this.leftovers = leftovers;
            this.kindId = kindId;
        }

        /** True while our own write runs: the holder change it fires is not a departure of the pack. */
        private boolean writing;

        ItemContainer holder() {
            return holder;
        }

        /** True when the tracked slot still holds the pack the window was opened from, with the metadata we expect. */
        boolean packInPlace() {
            if (writing) {
                return true;
            }
            ItemStack now = holder.getItemStack(slot);
            return now != null && !now.isEmpty() && itemId.equals(now.getItemId()) && now.getQuantity() == 1
                    && sameMetadata(expected, now.getMetadata());
        }

        /** Writes the boxes into the pack. False when the pack is no longer there (nothing written). */
        boolean write(SimpleItemContainer[] boxes) {
            if (!packInPlace()) {
                return false;
            }
            ItemStack now = holder.getItemStack(slot);
            ItemStack[][] slots = new ItemStack[boxes.length][];
            for (int i = 0; i < boxes.length; i++) {
                slots[i] = new ItemStack[boxes[i].getCapacity()];
                for (short s = 0; s < slots[i].length; s++) {
                    ItemStack st = boxes[i].getItemStack(s);
                    slots[i][s] = st != null && !st.isEmpty() ? st : null;
                }
            }
            TentContents updated = fromSlots(base, cells, slots, leftovers);
            ItemStack next = now.withMetadata(TentContents.METADATA_KEY, updated.toBson(kindId));
            writing = true;
            try {
                if (!holder.setItemStackForSlot(slot, next, false).succeeded()) {
                    return false;
                }
                ItemStack stored = holder.getItemStack(slot);
                expected = stored != null ? stored.getMetadata() : next.getMetadata();
                return true;
            } finally {
                writing = false;
            }
        }
    }

    /** Container window that saves into the pack on every change and closes when the pack leaves its slot. */
    private static final class PackWindow extends ContainerWindow {
        private final SimpleItemContainer[] boxes;
        private final Tracker tracker;
        private final List<EventRegistration<?, ?>> registrations = new ArrayList<>();
        private boolean closing;

        private final ItemContainer shown;

        PackWindow(ItemContainer shown, SimpleItemContainer[] boxes, Tracker tracker) {
            super(shown);
            this.shown = shown;
            this.boxes = boxes;
            this.tracker = tracker;
        }

        @Override
        public boolean onOpen0(Ref<EntityStore> ref, Store<EntityStore> store) {
            for (SimpleItemContainer box : boxes) {
                registrations.add(box.registerChangeEvent(event -> onChange(ref, store)));
            }
            if (boxes.length > 1) {
                // moves made through the combined container: also listened to there (a double write is harmless)
                registrations.add(shown.registerChangeEvent(event -> onChange(ref, store)));
            }
            // the pack leaving its slot closes the window at once, before anything can be taken out of it
            registrations.add(tracker.holder().registerChangeEvent(event -> onHolderChange(ref, store)));
            return true;
        }

        @Override
        public void onClose0(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
            unregister();
        }

        private void unregister() {
            for (EventRegistration<?, ?> r : registrations) {
                try {
                    r.unregister();
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Pack chests: cannot unregister a listener");
                }
            }
            registrations.clear();
        }

        private void onHolderChange(Ref<EntityStore> ref, Store<EntityStore> store) {
            if (closing) {
                return;
            }
            try {
                if (!tracker.packInPlace()) {
                    LOGGER.at(Level.INFO).log("Pack chests: the pack left its slot, window closed");
                    shut(ref, store);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Pack chests: cannot check the pack");
                Telemetry.error("packchests", e, null, null);
                shut(ref, store);
            }
        }

        private void shut(Ref<EntityStore> ref, Store<EntityStore> store) {
            closing = true;
            unregister();
            try {
                close(ref, store);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Pack chests: cannot close the window");
            }
        }

        private void onChange(Ref<EntityStore> ref, Store<EntityStore> store) {
            if (closing) {
                return;
            }
            try {
                if (!tracker.write(boxes)) {
                    LOGGER.at(Level.INFO).log("Pack chests: the pack left its slot, window closed and nothing written");
                    closing = true;
                    unregister();
                    close(ref, store);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Pack chests: cannot save into the pack");
                Telemetry.error("packchests", e, null, null);
                closing = true;
                unregister();
                try {
                    close(ref, store);
                } catch (RuntimeException e2) {
                    LOGGER.at(Level.WARNING).withCause(e2).log("Pack chests: cannot close the window");
                }
            }
        }
    }
}
