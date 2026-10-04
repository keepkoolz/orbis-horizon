package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Contents of the basket containers (chests, T18) during a flight.
 *
 * At take-off, each prefab block that carries an ItemContainerBlock component (the two
 * Furniture_Kweebec_Chest_Small, but no name is tested: any prefab container is taken,
 * except the burner, which keeps its special handling in BurnerFuel) is copied then emptied, because
 * the game drops the contents of a container whose block is removed. On landing, each content
 * goes back into the container at the same prefab position, slot by slot.
 *
 * Structure designed for the T19 resume file: a list of entries, each with the
 * container position in the prefab frame (before rotation, so valid whatever the
 * orientation at placement), the block name, the capacity and the contents of each non-empty
 * slot (slot index and stack). toBson and fromBson give its JSON form.
 * Only non-empty containers are kept.
 */
final class BalloonCargo {

    /** A container: position in the prefab frame, block, capacity, stacks by slot index. */
    static final class Entry {
        final Vector3i local;
        final String blockName;
        final short capacity;
        /** Capacity size, null for an empty slot. */
        final ItemStack[] slots;

        Entry(Vector3i local, String blockName, short capacity, ItemStack[] slots) {
            this.local = local;
            this.blockName = blockName;
            this.capacity = capacity;
            this.slots = slots;
        }

        /** The non-empty stacks of this container (T40, to drop them into the world). */
        List<ItemStack> items() {
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack st : slots) {
                if (st != null && !st.isEmpty()) {
                    list.add(st);
                }
            }
            return list;
        }

        int itemCount() {
            int n = 0;
            for (ItemStack s : slots) {
                if (s != null && !s.isEmpty()) {
                    n += s.getQuantity();
                }
            }
            return n;
        }

        BsonDocument toBson() {
            BsonDocument d = new BsonDocument();
            d.put("x", new BsonInt32(local.x));
            d.put("y", new BsonInt32(local.y));
            d.put("z", new BsonInt32(local.z));
            d.put("block", new BsonString(blockName));
            d.put("capacity", new BsonInt32(capacity));
            BsonArray arr = new BsonArray();
            for (int i = 0; i < slots.length; i++) {
                ItemStack st = slots[i];
                if (st == null || st.isEmpty()) {
                    continue;
                }
                BsonDocument slot = new BsonDocument();
                slot.put("slot", new BsonInt32(i));
                slot.put("stack", ItemStack.CODEC.encode(st, new ExtraInfo()));
                arr.add(slot);
            }
            d.put("slots", arr);
            return d;
        }

        static Entry fromBson(BsonDocument d) {
            short capacity = (short) d.getNumber("capacity").intValue();
            ItemStack[] slots = new ItemStack[capacity];
            for (BsonValue v : d.getArray("slots")) {
                BsonDocument slot = v.asDocument();
                int i = slot.getNumber("slot").intValue();
                if (i >= 0 && i < slots.length) {
                    slots[i] = ItemStack.CODEC.decode(slot.get("stack"), new ExtraInfo());
                }
            }
            return new Entry(new Vector3i(d.getNumber("x").intValue(), d.getNumber("y").intValue(),
                    d.getNumber("z").intValue()), d.getString("block").getValue(), capacity, slots);
        }
    }

    private final List<Entry> entries;

    BalloonCargo(List<Entry> entries) {
        this.entries = entries;
    }

    List<Entry> entries() {
        return entries;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Total number of items carried. */
    int itemCount() {
        int n = 0;
        for (Entry e : entries) {
            n += e.itemCount();
        }
        return n;
    }

    BsonDocument toBson() {
        BsonArray arr = new BsonArray();
        for (Entry e : entries) {
            arr.add(e.toBson());
        }
        return new BsonDocument("containers", arr);
    }

    static BalloonCargo fromBson(BsonDocument d) {
        List<Entry> list = new ArrayList<>();
        for (BsonValue v : d.getArray("containers")) {
            list.add(Entry.fromBson(v.asDocument()));
        }
        return new BalloonCargo(list);
    }

    /** Live container component at this position (not a copy), or null. Call on the world thread. */
    private static ItemContainerBlock live(World world, Vector3i p) {
        return BlockModule.getComponent(ItemContainerBlock.getComponentType(), world, p.x, p.y, p.z);
    }

    /**
     * Copies then empties the contents of the containers of the prefab placed at this origin and with this rotation
     * (the burner is handled by BurnerFuel). Call before removing the blocks, on the world thread.
     */
    static BalloonCargo takeFrom(World world, BalloonShape shape, Vector3i origin, Rotation rotation) {
        List<Entry> list = new ArrayList<>();
        for (BalloonShape.Cell c : shape.cells()) {
            if (BalloonShape.BURNER_BLOCK.equals(c.baseName())) {
                continue;
            }
            Vector3i p = c.rotated(rotation).add(origin);
            ItemContainerBlock block = live(world, p);
            ItemContainer container = block != null ? block.getItemContainer() : null;
            if (container == null || container.isEmpty()) {
                continue;
            }
            short capacity = container.getCapacity();
            ItemStack[] slots = new ItemStack[capacity];
            for (short s = 0; s < capacity; s++) {
                ItemStack st = container.getItemStack(s);
                slots[s] = st != null && !st.isEmpty() ? st : null;
            }
            list.add(new Entry(new Vector3i(c.x(), c.y(), c.z()), c.baseName(), capacity, slots));
            container.clear();
        }
        return new BalloonCargo(list);
    }

    /**
     * Puts the contents back into the containers of the replaced prefab. Returns the items that did not
     * find room (smaller container, slot already taken). If a container does not yet exist
     * (deferred block creation, hypothesis): the entry stays in the list and the caller retries later. Entries
     * already put back are not redone.
     */
    List<ItemStack> putInto(World world, Vector3i origin, Rotation rotation, boolean lastTry) {
        List<ItemStack> left = new ArrayList<>();
        boolean missing = false;
        for (java.util.Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            Vector3i p = rotation.rotateYaw(new Vector3i(e.local), new Vector3i()).add(origin);
            ItemContainerBlock block = live(world, p);
            ItemContainer target = block != null ? block.getItemContainer() : null;
            if (target == null) {
                missing = true;
                if (lastTry) {
                    addAll(left, e);
                    it.remove();
                }
                continue;
            }
            for (short s = 0; s < e.slots.length; s++) {
                ItemStack st = e.slots[s];
                if (st == null || st.isEmpty()) {
                    continue;
                }
                if (s < target.getCapacity() && target.getItemStack(s) == null
                        && target.setItemStackForSlot(s, st, false).succeeded()) {
                    continue;
                }
                ItemStackTransaction t = target.addItemStack(st, false, false, false);
                ItemStack rest = t.getRemainder();
                if (rest != null && !rest.isEmpty()) {
                    left.add(rest);
                }
            }
            it.remove();
        }
        // The remaining entries (container not yet created) stay in the list: the caller retries as long as isEmpty() is false.
        return left;
    }

    /**
     * T40: all the kept stacks, and the list is emptied (the contents can no longer be put back or dropped a second
     * time by a pending retry).
     */
    List<ItemStack> takeAllItems() {
        List<ItemStack> all = new ArrayList<>();
        for (Entry e : entries) {
            addAll(all, e);
        }
        entries.clear();
        return all;
    }

    /** World position of the first remaining container (to drop the items next to it). */
    Vector3i firstPosition(Vector3i origin, Rotation rotation) {
        Vector3i local = entries.isEmpty() ? new Vector3i() : entries.get(0).local;
        return rotation.rotateYaw(new Vector3i(local), new Vector3i()).add(origin);
    }

    private static void addAll(List<ItemStack> left, Entry e) {
        for (ItemStack st : e.slots) {
            if (st != null && !st.isEmpty()) {
                left.add(st);
            }
        }
    }
}
