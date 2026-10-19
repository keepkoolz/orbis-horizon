package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * T156: upgrading a camp pack keeps the contents of the lower pack. The recipe of a level 2 or 3 pack needs the lower pack as an
 * ingredient, which comes back as a fresh stack without metadata. CraftRecipeEvent.Pre (before ingredients are taken, can be
 * cancelled) notes the lower pack's contents, converted to the target tent. After the craft (the output reaches the inventory one
 * tick after Post) the tick puts them on the new pack. Facts from the in-game spike of 18 October 2026: ingredients are not taken
 * in creative mode, the workbench may take ingredients from nearby chests, which is why the lower pack must be on the player.
 */
final class CampUpgrade {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Ticks to wait for the crafted pack to reach the inventory, after Post. */
    private static final int MAX_TICKS = 5;

    enum Refusal { NONE, NO_BAG, SEVERAL_FULL }

    /** bagsOfPreviousTier: lower packs on the player, fullBags: those that carry contents. */
    static Refusal check(int bagsOfPreviousTier, int fullBagsOfPreviousTier) {
        if (bagsOfPreviousTier <= 0) {
            return Refusal.NO_BAG;
        }
        return fullBagsOfPreviousTier > 1 ? Refusal.SEVERAL_FULL : Refusal.NONE;
    }

    /**
     * Contents taken from the full lower pack at Pre (ruling 11: they live in exactly one place, this note). contents is the
     * conversion for the target tent, original the lower pack's own document (to give back on a cancelled craft). Both are null
     * for an empty pack or in creative mode (nothing taken, nothing carried). Section and slot say where the lower pack was.
     */
    record Note(CampTiers.Tier target, BsonDocument contents, BsonDocument original, int section, short slot) {
    }

    /** Pure FIFO rule: removes and returns the first note whose target is the output (Pre and Post fire in the same order), or null. */
    static Note take(List<Note> queue, CampTiers.Tier output) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).target() == output) {
                return queue.remove(i);
            }
        }
        return null;
    }

    /** Idle ticks (no queued or running job) before leftover notes are given back: one tick lets the last Post and its output come first. */
    static final int IDLE_TICKS = 2;

    /**
     * Pure reconcile rule: notes left while the bench queue stays empty belong to jobs that ended without Post (queue full, missing
     * ingredient, craft cancelled by another system, disconnect). Returns the notes whose contents must be given back and removes
     * every note from the list; keeps all of them while a job remains or the queue was idle for too short a time.
     */
    static List<Note> reconcile(List<Note> notes, int queueSize, int idleTicks) {
        List<Note> back = new ArrayList<>();
        if (queueSize > 0 || idleTicks < IDLE_TICKS || notes.isEmpty()) {
            return back;
        }
        for (Note n : notes) {
            if (n != null && n.contents() != null) {
                back.add(n);
            }
        }
        notes.clear();
        return back;
    }

    /** What to do with a note at Post: put its contents on the new pack, give them back (cancelled craft), or nothing. */
    enum Outcome { NOTHING, APPLY, GIVE_BACK }

    static Outcome outcome(Note note, boolean cancelled) {
        if (note == null || note.contents() == null) {
            return Outcome.NOTHING;
        }
        return cancelled ? Outcome.GIVE_BACK : Outcome.APPLY;
    }

    /** A crafted output waiting to receive its contents: the player's reference, the ticks already waited and the note to apply. */
    private record Wait(World world, Store<EntityStore> store, Ref<EntityStore> ref, int ticks, Note note) {
    }

    private static final Map<UUID, List<Note>> NOTES = new ConcurrentHashMap<>();
    /** Where to reach a player who has pending notes (set at Pre), and for how many ticks their bench queue has been empty. */
    private record Ctx(Store<EntityStore> store, Ref<EntityStore> ref) {
    }

    private static final Map<UUID, Ctx> CTX = new ConcurrentHashMap<>();
    /** Notes that could not be placed at arrival (inventory full): adopted by tick once the player is in the world. */
    private static final Map<UUID, List<Note>> CARRIED = new ConcurrentHashMap<>();
    /** Notes of waits that failed at arrival: tick turns them into waits again once the player is in the world. */
    private static final Map<UUID, List<Note>> WAIT_CARRIED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> IDLE = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Wait>> WAITS = new ConcurrentHashMap<>();

    private static List<Note> queueOf(UUID player) {
        return NOTES.computeIfAbsent(player, k -> new ArrayList<>());
    }

    private CampUpgrade() {
    }

    /** The tier a recipe produces when it is a pack of level 2 or 3, else null. */
    private static CampTiers.Tier targetOf(CraftingRecipe recipe) {
        try {
            if (recipe != null && recipe.getPrimaryOutput() != null) {
                CampTiers.Tier t = CampTiers.ofPack(recipe.getPrimaryOutput().getItemId());
                return t != null && t.previous() != null ? t : null;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Amélioration de sac : recette illisible");
        }
        return null;
    }

    private static List<ItemContainer> containers(InventoryComponent hotbar, InventoryComponent storage, InventoryComponent backpack) {
        List<ItemContainer> out = new ArrayList<>();
        for (InventoryComponent c : new InventoryComponent[] {hotbar, storage, backpack}) {
            if (c != null && c.getInventory() != null) {
                out.add(c.getInventory());
            }
        }
        return out;
    }

    private static List<ItemContainer> containers(Store<EntityStore> store, Ref<EntityStore> ref) {
        return containers(store.getComponent(ref, InventoryComponent.Hotbar.getComponentType()),
                store.getComponent(ref, InventoryComponent.Storage.getComponentType()),
                store.getComponent(ref, InventoryComponent.Backpack.getComponentType()));
    }

    static List<ItemContainer> containers(Holder<EntityStore> holder) {
        return containers(holder.getComponent(InventoryComponent.Hotbar.getComponentType()),
                holder.getComponent(InventoryComponent.Storage.getComponentType()),
                holder.getComponent(InventoryComponent.Backpack.getComponentType()));
    }

    /** Puts the contents on the first pack of this tier that has none. True when done. */
    private static boolean apply(List<ItemContainer> containers, CampTiers.Tier target, BsonDocument contents) {
        for (ItemContainer c : containers) {
            for (short i = 0; i < c.getCapacity(); i++) {
                ItemStack st = c.getItemStack(i);
                if (st == null || ItemStack.isEmpty(st) || !target.packItemId().equals(st.getItemId())) {
                    continue;
                }
                BsonDocument meta = st.getMetadata();
                if (meta != null && meta.containsKey(TentContents.METADATA_KEY)) {
                    continue;
                }
                c.setItemStackForSlot(i, st.withMetadata(TentContents.METADATA_KEY, contents));
                return true;
            }
        }
        return false;
    }

    /** Contents of the single full lower pack converted to the target tent, null if nothing to carry. Never throws. */
    private static BsonDocument converted(CampTiers.Tier previous, CampTiers.Tier target, BsonDocument stored) {
        try {
            TentContents from = TentContents.fromBson(stored, previous.kindId());
            if (from == null) {
                return null;
            }
            TentContents to = from.convert(Deployables.get(target.kindId()).shape());
            return to.toBson(target.kindId());
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Amélioration de sac : contenu non converti, le sac sera vide");
            return null;
        }
    }

    /** CraftRecipeEvent.Pre: refuses an upgrade without the lower pack on the player, otherwise notes its contents. */
    static final class Pre extends EntityEventSystem<EntityStore, CraftRecipeEvent.Pre> {
        Pre() {
            super(CraftRecipeEvent.Pre.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, CraftRecipeEvent.Pre event) {
            try {
                if (event.isCancelled()) {
                    return; // already cancelled by another handler: strip nothing
                }
                CampTiers.Tier target = targetOf(event.getCraftedRecipe());
                if (target == null) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                Player player = store.getComponent(ref, Player.getComponentType());
                if (pr == null) {
                    return;
                }
                boolean creative = player != null && player.getGameMode() == GameMode.Creative;
                CampTiers.Tier previous = target.previous();
                int bags = 0;
                int full = 0;
                BsonDocument stored = null;
                int fullSection = -1;
                short fullSlot = -1;
                List<ItemContainer> all = containers(store, ref);
                for (int sec = 0; sec < all.size(); sec++) {
                    ItemContainer c = all.get(sec);
                    for (short i = 0; i < c.getCapacity(); i++) {
                        ItemStack st = c.getItemStack(i);
                        if (st == null || ItemStack.isEmpty(st) || !previous.packItemId().equals(st.getItemId())) {
                            continue;
                        }
                        bags++;
                        BsonDocument meta = st.getMetadata();
                        if (meta != null && meta.containsKey(TentContents.METADATA_KEY) && meta.get(TentContents.METADATA_KEY).isDocument()) {
                            full++;
                            stored = meta.getDocument(TentContents.METADATA_KEY);
                            fullSection = sec;
                            fullSlot = i;
                        }
                    }
                }
                // Creative mode takes no ingredient, so the lower pack is not required on the player and nothing is carried.
                Refusal refusal = creative ? Refusal.NONE : check(bags, full);
                if (refusal != Refusal.NONE) {
                    event.setCancelled(true);
                    pr.sendMessage(Texts.t(refusal == Refusal.NO_BAG ? "camp.upgrade.keepBag" : "camp.upgrade.oneBag"));
                    return;
                }
                BsonDocument contents = null;
                BsonDocument original = null;
                if (!creative && stored != null) {
                    contents = converted(previous, target, stored);
                    if (contents == null) {
                        // Unreadable contents: refuse rather than consume the pack and lose them.
                        event.setCancelled(true);
                        pr.sendMessage(Texts.t("camp.upgrade.unreadable"));
                        return;
                    }
                }
                if (contents != null) {
                    // Ruling 11: take the contents off the lower pack right now, they exist only in the note until Post.
                    ItemContainer c = all.get(fullSection);
                    c.setItemStackForSlot(fullSlot, blank(c.getItemStack(fullSlot)));
                    original = stored;
                }
                List<Note> queue = queueOf(pr.getUuid());
                synchronized (queue) {
                    queue.add(new Note(target, contents, original, fullSection, fullSlot));
                }
                CTX.put(pr.getUuid(), new Ctx(store, ref));
                IDLE.remove(pr.getUuid());
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Amélioration de sac : Pre en échec");
            }
        }
    }

    /** The stack without the contents document (a blank pack). */
    private static ItemStack blank(ItemStack st) {
        BsonDocument meta = st.getMetadata();
        if (meta == null || !meta.containsKey(TentContents.METADATA_KEY)) {
            return st;
        }
        BsonDocument rest = meta.clone();
        rest.remove(TentContents.METADATA_KEY);
        return rest.isEmpty() ? new ItemStack(st.getItemId(), st.getQuantity()) : st.withMetadata(rest);
    }

    private static boolean isBlankPack(ItemStack st, String packId) {
        if (st == null || ItemStack.isEmpty(st) || !packId.equals(st.getItemId())) {
            return false;
        }
        BsonDocument meta = st.getMetadata();
        return meta == null || !meta.containsKey(TentContents.METADATA_KEY);
    }

    /** Puts the original contents back on a blank lower pack (its own slot first). True when done. */
    private static boolean giveBack(List<ItemContainer> all, Note note) {
        String id = note.target().previous().packItemId();
        if (note.section() >= 0 && note.section() < all.size()) {
            ItemContainer c = all.get(note.section());
            ItemStack st = note.slot() >= 0 && note.slot() < c.getCapacity() ? c.getItemStack(note.slot()) : null;
            if (isBlankPack(st, id)) {
                c.setItemStackForSlot(note.slot(), st.withMetadata(TentContents.METADATA_KEY, note.original()));
                return true;
            }
        }
        for (ItemContainer c : all) {
            for (short i = 0; i < c.getCapacity(); i++) {
                ItemStack st = c.getItemStack(i);
                if (isBlankPack(st, id)) {
                    c.setItemStackForSlot(i, st.withMetadata(TentContents.METADATA_KEY, note.original()));
                    return true;
                }
            }
        }
        return false;
    }

    /** Arrival, no blank lower pack: a new full lower pack goes in the inventory (the player is not in the world yet, nothing can be dropped); false when the inventory is full. */
    private static boolean giveNewPackOffline(List<ItemContainer> all, Note note, UUID player) {
        ItemStack pack = new ItemStack(note.target().previous().packItemId(), 1).withMetadata(TentContents.METADATA_KEY, note.original());
        for (ItemContainer c : all) {
            ItemStackTransaction t = c.addItemStack(pack);
            if (t == null || t.getRemainder() == null || t.getRemainder().isEmpty()) {
                LOGGER.at(Level.INFO).log("Amélioration de sac : contenu rendu dans un sac neuf à l'arrivée du joueur %s", player);
                return true;
            }
            pack = t.getRemainder();
        }
        LOGGER.at(Level.INFO).log("Amélioration de sac : inventaire plein à l'arrivée du joueur %s, contenu gardé pour la suite", player);
        return false;
    }

    /** Gives the contents back to an online player: blank lower pack, else a new full lower pack in the inventory or at their feet. */
    private static void giveBackOnline(Store<EntityStore> store, Ref<EntityStore> ref, Note note) {
        if (giveBack(containers(store, ref), note)) {
            return;
        }
        ItemStack pack = new ItemStack(note.target().previous().packItemId(), 1).withMetadata(TentContents.METADATA_KEY, note.original());
        ItemStack rest = pack;
        try {
            ItemStackTransaction t = Player.giveItem(pack, ref, store);
            rest = t != null ? t.getRemainder() : pack;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Amélioration de sac : sac rendu non donné, lâché au sol");
        }
        if (rest != null && !rest.isEmpty()) {
            TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
            if (tc != null) {
                Vector3d at = tc.getPosition();
                BalloonManager.dropItems(store, List.of(rest), new Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)));
            } else {
                LOGGER.at(Level.WARNING).log("Amélioration de sac : contenu perdu, position du joueur inconnue");
            }
        }
        LOGGER.at(Level.INFO).log("Amélioration de sac : craft annulé, contenu rendu au joueur");
    }

    /** CraftRecipeEvent.Post: a cancelled craft forgets the note, otherwise the contents are put on the new pack now or in the next ticks. */
    static final class Post extends EntityEventSystem<EntityStore, CraftRecipeEvent.Post> {
        Post() {
            super(CraftRecipeEvent.Post.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, CraftRecipeEvent.Post event) {
            try {
                CampTiers.Tier output = targetOf(event.getCraftedRecipe());
                if (output == null) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr == null) {
                    return;
                }
                List<Note> queue = NOTES.get(pr.getUuid());
                if (queue == null) {
                    return;
                }
                Note note;
                synchronized (queue) {
                    note = take(queue, output);
                }
                Outcome out = outcome(note, event.isCancelled());
                if (out == Outcome.NOTHING) {
                    return;
                }
                if (out == Outcome.GIVE_BACK) {
                    giveBackOnline(store, ref, note);
                    return;
                }
                Telemetry.count("camp.upgrade");
                World world = store.getExternalData().getWorld();
                if (!apply(containers(store, ref), note.target(), note.contents())) {
                    WAITS.computeIfAbsent(pr.getUuid(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                            .add(new Wait(world, store, ref, 0, note));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Amélioration de sac : Post en échec");
            }
        }
    }

    /** Called every tick of each world: waits for the crafted pack (5 ticks at most), then puts the contents on it. Never throws. */
    static void tick(Store<EntityStore> store, World world) {
        reconcileNotes(store);
        adoptWaits(store, world);
        if (WAITS.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<UUID, List<Wait>> e : WAITS.entrySet()) {
                for (Wait w : e.getValue()) {
                    if (w.world() != world || w.store() != store) {
                        continue;
                    }
                    if (!w.ref().isValid()) {
                        // The player left: the wait is kept without bound (until onArrival or plugin shutdown), the simplest safe option.
                        continue;
                    } else if (apply(containers(store, w.ref()), w.note().target(), w.note().contents())) {
                        e.getValue().remove(w);
                    } else if (w.ticks() >= MAX_TICKS) {
                        // Already warned: keep trying quietly, the note also stays for onArrival.
                        continue;
                    } else {
                        if (w.ticks() + 1 >= MAX_TICKS) {
                            LOGGER.at(Level.WARNING).log("Amélioration de sac : sac %s introuvable après %d ticks, contenu gardé pour l'arrivée du joueur",
                                    w.note().target().packItemId(), MAX_TICKS);
                        }
                        int at = e.getValue().indexOf(w);
                        if (at >= 0) {
                            e.getValue().set(at, new Wait(w.world(), w.store(), w.ref(), Math.min(MAX_TICKS, w.ticks() + 1), w.note()));
                        }
                    }
                }
                if (e.getValue().isEmpty()) {
                    WAITS.remove(e.getKey(), e.getValue());
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Amélioration de sac : tick en échec");
        }
    }

    /** Re-creates, in the world the player now is in, the waits that failed at arrival. Never throws. */
    private static void adoptWaits(Store<EntityStore> store, World world) {
        if (WAIT_CARRIED.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<UUID, List<Note>> e : WAIT_CARRIED.entrySet()) {
                PlayerRef pr = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(e.getKey());
                Ref<EntityStore> ref = pr == null ? null : pr.getReference();
                if (ref == null || !ref.isValid() || ref.getStore() != store) {
                    continue;
                }
                if (WAIT_CARRIED.remove(e.getKey(), e.getValue())) {
                    for (Note n : e.getValue()) {
                        WAITS.computeIfAbsent(e.getKey(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add(new Wait(world, store, ref, 0, n));
                    }
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Amélioration de sac : attentes reprises à l'arrivée en échec");
        }
    }

    /** Gives back the contents of notes whose job ended without Post, once the player's bench queue has stayed empty. Never throws. */
    private static void reconcileNotes(Store<EntityStore> store) {
        if (NOTES.isEmpty() && CARRIED.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<UUID, List<Note>> e : CARRIED.entrySet()) {
                PlayerRef pr = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(e.getKey());
                Ref<EntityStore> ref = pr == null ? null : pr.getReference();
                if (ref == null || !ref.isValid() || ref.getStore() != store) {
                    continue;
                }
                if (CARRIED.remove(e.getKey(), e.getValue())) {
                    queueOf(e.getKey()).addAll(e.getValue());
                    CTX.put(e.getKey(), new Ctx(store, ref));
                    IDLE.remove(e.getKey());
                }
            }
            for (Map.Entry<UUID, List<Note>> e : NOTES.entrySet()) {
                Ctx ctx = CTX.get(e.getKey());
                if (ctx == null || ctx.store() != store || !ctx.ref().isValid()) {
                    continue;
                }
                CraftingManager cm = store.getComponent(ctx.ref(), CraftingManager.getComponentType());
                int size = cm == null ? 0 : cm.getRemainingQueueSize();
                int idle = size > 0 ? 0 : IDLE.merge(e.getKey(), 1, Integer::sum);
                if (size > 0) {
                    IDLE.remove(e.getKey());
                }
                List<Note> back;
                synchronized (e.getValue()) {
                    back = reconcile(e.getValue(), size, idle);
                }
                for (Note n : back) {
                    giveBackOnline(store, ctx.ref(), n);
                }
                if (e.getValue().isEmpty()) {
                    NOTES.remove(e.getKey(), e.getValue());
                    CTX.remove(e.getKey());
                    IDLE.remove(e.getKey());
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Amélioration de sac : rapprochement des notes en échec");
        }
    }

    /**
     * A player arrives in a world: a craft they finished before leaving gets its contents now, if the new pack is still without
     * contents. Notes whose craft never ended give their contents back to a blank lower pack. Never throws.
     */
    static void onArrival(Holder<EntityStore> holder, UUID player) {
        try {
            CTX.remove(player);
            IDLE.remove(player);
            List<Note> pending = NOTES.remove(player);
            if (pending != null) {
                // A craft that never ended: the contents taken at Pre go back on a blank lower pack.
                for (Note n : pending) {
                    if (n.contents() != null && !giveBack(containers(holder), n)) {
                        if (!giveNewPackOffline(containers(holder), n, player)) {
                            CARRIED.computeIfAbsent(player, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(n);
                        }
                    }
                }
            }
            List<Wait> waits = WAITS.remove(player);
            if (waits != null) {
                for (Wait w : waits) {
                    if (apply(containers(holder), w.note().target(), w.note().contents())) {
                        LOGGER.at(Level.INFO).log("Amélioration de sac : contenu posé à l'arrivée du joueur %s", player);
                    } else {
                        // The new pack is not in the inventory yet: tick re-creates the wait once the player is in the world.
                        WAIT_CARRIED.computeIfAbsent(player, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(w.note());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Amélioration de sac : reprise à l'arrivée en échec");
        }
    }
}
