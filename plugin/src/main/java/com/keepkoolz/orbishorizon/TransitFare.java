package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** Ticket price of the public transit and the choice of what a player pays with. Pure rules, no world access. */
final class TransitFare {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final String ESSENCE = "Ingredient_Life_Essence";
    static final String CONCENTRATED = "Ingredient_Life_Essence_Concentrated";
    static final int ESSENCE_PRICE = 100;
    static final int CONCENTRATED_PRICE = 1;

    private TransitFare() {}

    /** What was taken from a player for one queued destination (payer is never null). */
    record Payment(UUID payer, String itemId, int quantity) {}

    /** Essences first (100), concentrated essence (1) when there are not enough. Null when the player cannot pay. */
    static Payment choose(UUID payer, int essences, int concentrated) {
        if (essences >= ESSENCE_PRICE) return new Payment(payer, ESSENCE, ESSENCE_PRICE);
        if (concentrated >= CONCENTRATED_PRICE) return new Payment(payer, CONCENTRATED, CONCENTRATED_PRICE);
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // charging

    /**
     * Takes the ticket price from the player's inventory (creative mode included). Returns the payment, or null after
     * sending the player the refusal (transit.fare.missing) or the failure (transit.fare.failed). World thread.
     */
    static Payment charge(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player) {
        try {
            UUID id = player.getUuid();
            ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
            int essences = 0;
            int concentrated = 0;
            for (short i = 0; i < inv.getCapacity(); i++) {
                ItemStack s = inv.getItemStack(i);
                if (s == null || s.isEmpty()) continue;
                if (ESSENCE.equals(s.getItemId())) essences += s.getQuantity();
                else if (CONCENTRATED.equals(s.getItemId())) concentrated += s.getQuantity();
            }
            Payment pay = choose(id, essences, concentrated);
            if (pay == null) {
                player.sendMessage(Texts.t("transit.fare.missing")
                        .param("essence", Math.max(0, ESSENCE_PRICE - essences))
                        .param("concentrated", Math.max(0, CONCENTRATED_PRICE - concentrated)));
                return null;
            }
            List<ItemStack> cost = List.of(new ItemStack(pay.itemId(), pay.quantity()));
            if (!inv.canRemoveItemStacks(cost)) {
                LOGGER.at(Level.WARNING).log("Billet : retrait du prix impossible malgré les comptes");
                player.sendMessage(Texts.t("transit.fare.failed"));
                return null;
            }
            inv.removeItemStacks(cost);
            return pay;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Billet : prélèvement en échec");
            Telemetry.error("transit", e, null, null);
            try {
                player.sendMessage(Texts.t("transit.fare.failed"));
            } catch (RuntimeException ignored) {
                // nothing more to do
            }
            return null;
        }
    }

    /**
     * Charges the chooser for a destination about to be queued on the station and adds it (same results as
     * StationQueue.addTo). Nothing is taken unless the destination would really be ADDED. World thread.
     */
    static StationQueue.EnqueueResult addPaid(TransitNetwork.Station s, String targetName, PlayerRef chooser,
                                              Store<EntityStore> store, Ref<EntityStore> ref) {
        TransitNetwork net = TransitNetwork.get();
        StationQueue.EnqueueResult sim;
        synchronized (net) {
            sim = StationQueue.addTo(new ArrayList<>(s.queue), s.name, targetName);
        }
        if (sim != StationQueue.EnqueueResult.ADDED) return sim;
        if (chooser == null || store == null || ref == null) return StationQueue.EnqueueResult.UNKNOWN;
        Payment pay = charge(store, ref, chooser);
        if (pay == null) return StationQueue.EnqueueResult.UNKNOWN;
        StationQueue.EnqueueResult r = net.queueEnqueue(s, targetName, pay);
        if (r != StationQueue.EnqueueResult.ADDED) {
            refund(pay, targetName); // the queue changed in between
        } else {
            try {
                chooser.sendMessage(Texts.t("transit.fare.paid")
                        .param("price", Message.raw(pay.quantity() + " ").insert(itemName(pay.itemId()))));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Billet : message de paiement non envoyé");
            }
        }
        return r;
    }

    /** Refunds every ticket recorded in the queue of a station that is being removed (destination names taken from its queue). */
    static void refundRemovedStation(TransitNetwork.Station removed) {
        try {
            TransitNetwork net = TransitNetwork.get();
            java.util.Map<String, String> names = new java.util.HashMap<>();
            java.util.Map<String, Payment> taken;
            synchronized (net) {
                for (String n : removed.queue) names.put(TransitNetwork.normalize(n).toLowerCase(java.util.Locale.ROOT), n);
                taken = net.takeAllPayments(removed);
            }
            for (java.util.Map.Entry<String, Payment> e : taken.entrySet()) {
                refund(e.getValue(), names.getOrDefault(e.getKey(), e.getKey()));
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Billet : remboursements de la station retirée en échec");
        }
    }

    static Message itemName(String itemId) {
        return Message.translation("server.items." + itemId + ".name");
    }

    // ------------------------------------------------------------------------------------------------------------
    // refunds

    private static Path file;
    private static final List<Payment> PENDING = new ArrayList<>();

    /** Gives back what was paid for a destination that will not be served. Any thread, never throws. */
    static void refund(Payment p, String destination) {
        if (p == null) return;
        try {
            World found = null;
            PlayerRef who = null;
            try {
                for (World w : Universe.get().getWorlds().values()) {
                    for (PlayerRef pr : w.getPlayerRefs()) {
                        if (p.payer().equals(pr.getUuid())) {
                            found = w;
                            who = pr;
                            break;
                        }
                    }
                    if (found != null) break;
                }
            } catch (RuntimeException | LinkageError e) {
                found = null;
            }
            if (found == null) {
                refundOffline(p);
                return;
            }
            final PlayerRef target = who;
            final Message name = Message.raw(destination == null ? "" : destination);
            found.execute(() -> {
                try {
                    giveBack(target, p, "transit.fare.refunded", "transit.fare.refundedDropped", name);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Billet : remboursement en échec, noté pour plus tard");
                    refundOffline(p);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Billet : remboursement en échec");
            refundOffline(p);
        }
    }

    /** Notes the refund in refunds.json for the next time the payer is ready. */
    static void refundOffline(Payment p) {
        synchronized (PENDING) {
            PENDING.add(p);
            save();
        }
    }

    /** Gives the item, drops what does not fit, tells the player. World thread of the player. */
    private static void giveBack(PlayerRef player, Payment p, String keyOk, String keyDropped, Message destination) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) throw new IllegalStateException("joueur invalide");
        Store<EntityStore> store = ref.getStore();
        ItemStack stack = new ItemStack(p.itemId(), p.quantity());
        ItemStack rest = stack;
        boolean gave = false;
        try {
            ItemStackTransaction t = Player.giveItem(stack, ref, store);
            rest = t != null ? t.getRemainder() : stack;
            gave = true;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Billet : objet non rendu, lâché au sol");
        }
        Message msg;
        if (rest != null && !rest.isEmpty()) {
            try {
                Vector3d at = store.getComponent(ref, TransformComponent.getComponentType()).getPosition();
                BalloonManager.dropItems(store, List.of(rest),
                        new Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)));
            } catch (RuntimeException e) {
                if (!gave) throw e; // nothing was given: the caller notes the refund in the file
                // Part of the stack is already given: never fall back to the file (it would refund twice).
                LOGGER.at(Level.WARNING).withCause(e).log("Billet : reste du remboursement non lâché au sol");
                Telemetry.error("transit", e, null, null);
            }
            msg = Texts.t(keyDropped);
        } else {
            msg = Texts.t(keyOk);
        }
        // The item is already given: a failure here must not send the refund to the file a second time.
        try {
            player.sendMessage(destination != null ? msg.param("name", destination) : msg);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Billet : message de remboursement non envoyé");
        }
    }

    static void loadRefunds(Path dataDir) {
        synchronized (PENDING) {
            file = dataDir.resolve("refunds.json");
            PENDING.clear();
            if (!Files.isRegularFile(file)) return;
            try {
                BsonDocument doc = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
                for (var v : doc.getArray("refunds")) {
                    try {
                        BsonDocument d = v.asDocument();
                        PENDING.add(new Payment(UUID.fromString(d.getString("player").getValue()),
                                d.getString("item").getValue(), d.getInt32("qty").getValue()));
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Remboursement illisible dans refunds.json, ignoré");
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("refunds.json illisible, renommé en .bad");
                PENDING.clear();
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    LOGGER.at(Level.WARNING).withCause(e2).log("Impossible de renommer refunds.json en .bad");
                }
            }
        }
    }

    /** Removes and saves the pending refunds of a player, in the order they were noted. */
    static List<Payment> takeRefunds(UUID player) {
        synchronized (PENDING) {
            List<Payment> out = new ArrayList<>();
            PENDING.removeIf(p -> {
                if (p.payer().equals(player)) {
                    out.add(p);
                    return true;
                }
                return false;
            });
            if (!out.isEmpty()) save();
            return out;
        }
    }

    /** PlayerReadyEvent: gives every refund noted while the player was away, on the player's world thread. */
    static void onReady(Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) return;
            UUIDComponent c = ref.getStore().getComponent(ref, UUIDComponent.getComponentType());
            if (c == null) return;
            UUID id = c.getUuid();
            World world = null;
            try {
                for (World w : Universe.get().getWorlds().values()) {
                    for (PlayerRef pr : w.getPlayerRefs()) {
                        if (id.equals(pr.getUuid())) {
                            world = w;
                            break;
                        }
                    }
                    if (world != null) break;
                }
            } catch (RuntimeException | LinkageError e) {
                world = null;
            }
            if (world == null) return; // refunds stay in the file for the next ready event
            world.execute(() -> {
                List<Payment> list = takeRefunds(id);
                if (list.isEmpty()) return;
                PlayerRef player = null;
                try {
                    Ref<EntityStore> r = ref.isValid() ? ref : null;
                    if (r != null) player = r.getStore().getComponent(r, PlayerRef.getComponentType());
                } catch (RuntimeException e) {
                    player = null;
                }
                for (Payment p : list) {
                    if (player == null) {
                        refundOffline(p);
                        continue;
                    }
                    try {
                        giveBack(player, p, "transit.fare.refundedLater", "transit.fare.refundedDropped", null);
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Billet : remboursement différé en échec, noté pour plus tard");
                        refundOffline(p);
                    }
                }
            });
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Billet : remboursements à l'arrivée en échec");
        }
    }

    /** Caller holds the PENDING lock. */
    private static void save() {
        Path f = file;
        if (f == null) return;
        try {
            BsonArray arr = new BsonArray();
            for (Payment p : PENDING) {
                BsonDocument d = new BsonDocument();
                d.put("player", new BsonString(p.payer().toString()));
                d.put("item", new BsonString(p.itemId()));
                d.put("qty", new BsonInt32(p.quantity()));
                arr.add(d);
            }
            BsonDocument doc = new BsonDocument();
            doc.put("version", new BsonInt32(1));
            doc.put("refunds", arr);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, doc.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Écriture de refunds.json impossible");
        }
    }
}
