package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ModelUpdate;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.RespawnEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * T157: shows the highest camp pack of the inventory on the player's back. Every trigger (inventory, armour, skin, settings,
 * model, respawn, arrival) only queues a {@link #refresh}, which runs once per player and per tick on the world thread.
 * Nothing here ever throws into the game.
 */
final class PackBackView {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String TEXTURE_FOLDER = "Items/Camping/";
    private static final long GUARD_MS = 2000L;
    /** Delay before the model is sent again to a player who has just started to see a pack wearer (after the skin packet). */
    private static final long VIEWER_RESEND_MS = 250L;

    /** Players (UUID) whose model currently carries a pack, with their entity reference for the per-tick guard. */
    private static final Map<String, Ref<EntityStore>> SHOWN = new ConcurrentHashMap<>();
    /** Players with a refresh already queued (one refresh per player and per tick). */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    /** True while this class replaces a ModelComponent, so the model watcher ignores our own change. */
    private static final ThreadLocal<Boolean> REBUILDING = ThreadLocal.withInitial(() -> false);
    private static final Map<String, Long> LAST_GUARD_MS = new ConcurrentHashMap<>();
    private static volatile Boolean otherBackpackMod;
    /**
     * Per pack wearer (UUID): viewers already served, and new viewers with the time their model resend is due. A viewer who
     * starts to see the wearer gets the model and the skin in the same update list, and the skin rebuilds the look without
     * the attachments on that client, so the model is sent to him again a moment later. World thread only.
     */
    private static final Map<String, Map<Ref<EntityStore>, Long>> VIEWERS = new HashMap<>();
    /**
     * Players (UUID) who just arrived in a world, with their entity and the time their own model resend is due. On every
     * arrival the game sends the player his own model then his skin in one list (PlayerSystems.sendPlayerSelf), and the skin
     * rebuilds his look without the attachments on his client, so the model is sent to him again a moment later.
     */
    private static final Map<String, SelfResend> SELF = new ConcurrentHashMap<>();

    private record SelfResend(Ref<EntityStore> ref, long dueMs) {
    }

    private PackBackView() {
    }

    /** The highest tier among the camp packs listed, null when there is none. */
    static CampTiers.Tier shownTier(List<String> inventoryItemIds) {
        CampTiers.Tier best = null;
        for (String id : inventoryItemIds) {
            CampTiers.Tier t = CampTiers.ofPack(id);
            if (t != null && (best == null || t.level() > best.level())) {
                best = t;
            }
        }
        return best;
    }

    /** The texture of a tier's pack, relative to Common/ (Camping_Pack_Bedroll, Camping_Pack_Tent, Camping_Pack_Tent_Big). */
    static String textureOf(CampTiers.Tier t) {
        return TEXTURE_FOLDER + t.packItemId() + "_Texture.png";
    }

    /** Whether the True Backpack mod (DefaultQA:TrueBackpack) is loaded: its back attachment would clash, so nothing is drawn. */
    static boolean otherBackpackModPresent() {
        Boolean cached = otherBackpackMod;
        return cached != null ? cached : detectOtherBackpackMod();
    }

    /** Looks for True Backpack among the loaded plugins, once at plugin start, and logs the result. */
    static boolean detectOtherBackpackMod() {
        boolean found = false;
        try {
            for (PluginBase p : PluginManager.get().getPlugins()) {
                if (p.getIdentifier() != null && "TrueBackpack".equals(p.getIdentifier().getName())) {
                    found = true;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Liste des plugins illisible, sac de campement sur le dos laissé actif");
        }
        otherBackpackMod = found;
        LOGGER.at(Level.INFO).log("Sac de campement sur le dos : True Backpack %s", found ? "détecté, rien n'est dessiné" : "absent");
        return found;
    }

    /** Queues one refresh on the world thread; several causes in the same tick give a single refresh. */
    static void refresh(Store<EntityStore> store, Ref<EntityStore> player, String cause) {
        try {
            if (!TransitSettings.packBackView() && !SHOWN.containsValue(player)) {
                return;
            }
            String id = uuid(store, player);
            if (id == null || !PENDING.add(id)) {
                return;
            }
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                PENDING.remove(id);
                try {
                    if (player.isValid()) {
                        refreshNow(store, player, id, cause);
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Sac de campement sur le dos (%s) en échec", cause);
                }
            });
        } catch (RuntimeException e) {
            try {
                String stuck = uuid(store, player);
                if (stuck != null) {
                    PENDING.remove(stuck);
                }
            } catch (RuntimeException ignored) {
                // nothing more to do
            }
            LOGGER.at(Level.WARNING).withCause(e).log("Sac de campement sur le dos (%s) : planification en échec", cause);
        }
    }

    private static void refreshNow(Store<EntityStore> store, Ref<EntityStore> player, String id, String cause) {
        boolean wasShown = SHOWN.containsKey(id);
        CampTiers.Tier tier = TransitSettings.packBackView() && !otherBackpackModPresent() ? shownTier(inventoryItemIds(store, player)) : null;
        REBUILDING.set(true);
        try {
            if (tier != null) {
                PlayerSkinComponent psc = store.getComponent(player, PlayerSkinComponent.getComponentType());
                ModelComponent mc = store.getComponent(player, ModelComponent.getComponentType());
                if (psc == null || mc == null) {
                    return;
                }
                Model rebuilt = SkinAttachments.build(store, player, psc.getPlayerSkin(),
                        new ModelAttachment(SkinAttachments.BACK_MODEL, textureOf(tier), null, null, 1.0));
                if (rebuilt == null) {
                    return; // another asset's model, never touched
                }
                if (!SkinAttachments.sameState(mc.getModel(), rebuilt)) {
                    store.replaceComponent(player, ModelComponent.getComponentType(), new ModelComponent(rebuilt));
                }
                SHOWN.put(id, player);
            } else if (wasShown) {
                SHOWN.remove(id);
                SkinAttachments.restoreNative(store, player);
            }
        } finally {
            REBUILDING.set(false);
        }
    }

    /** Item ids of the hotbar, storage and backpack sections of the player. */
    private static List<String> inventoryItemIds(Store<EntityStore> store, Ref<EntityStore> player) {
        List<String> ids = new ArrayList<>();
        InventoryComponent[] sections = {store.getComponent(player, InventoryComponent.Hotbar.getComponentType()),
                store.getComponent(player, InventoryComponent.Storage.getComponentType()),
                store.getComponent(player, InventoryComponent.Backpack.getComponentType())};
        for (InventoryComponent section : sections) {
            if (section == null) {
                continue;
            }
            ItemContainer c = section.getInventory();
            for (short slot = 0; slot < c.getCapacity(); slot++) {
                ItemStack stack = c.getItemStack(slot);
                if (stack != null && !stack.isEmpty()) {
                    ids.add(stack.getItemId());
                }
            }
        }
        return ids;
    }

    /**
     * Per-world-tick work: the model resend to new viewers of each pack wearer, then, at most every 2 s, the guard that
     * refreshes a player shown with a pack whose model lost it.
     */
    static void tick(Store<EntityStore> store, World world) {
        try {
            resendToSelf(store);
            if (SHOWN.isEmpty()) {
                VIEWERS.clear();
                return;
            }
            resendToNewViewers(store);
            long now = System.currentTimeMillis();
            Long last = LAST_GUARD_MS.get(world.getName());
            if (last != null && now - last < GUARD_MS) {
                return;
            }
            LAST_GUARD_MS.put(world.getName(), now);
            for (Map.Entry<String, Ref<EntityStore>> e : SHOWN.entrySet()) {
                Ref<EntityStore> ref = e.getValue();
                if (!ref.isValid()) {
                    SHOWN.remove(e.getKey(), ref);
                    continue;
                }
                if (ref.getStore() != store) {
                    continue;
                }
                ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
                if (mc != null && !SkinAttachments.hasBag(mc.getModel())) {
                    refresh(store, ref, "garde");
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Garde du sac de campement sur le dos en échec");
        }
    }

    /** Sends the wearer's model again to each viewer who started to see him {@link #VIEWER_RESEND_MS} ago. Never throws. */
    private static void resendToNewViewers(Store<EntityStore> store) {
        try {
            VIEWERS.keySet().removeIf(id -> !SHOWN.containsKey(id));
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Ref<EntityStore>> e : SHOWN.entrySet()) {
                Ref<EntityStore> ref = e.getValue();
                if (!ref.isValid() || ref.getStore() != store) {
                    continue;
                }
                EntityTrackerSystems.Visible visible = store.getComponent(ref, EntityTrackerSystems.Visible.getComponentType());
                ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
                if (visible == null || mc == null || visible.visibleTo == null) {
                    continue;
                }
                Map<Ref<EntityStore>, Long> known = VIEWERS.computeIfAbsent(e.getKey(), k -> new HashMap<>());
                known.keySet().removeIf(v -> !visible.visibleTo.containsKey(v));
                for (Ref<EntityStore> viewer : visible.visibleTo.keySet()) {
                    // a viewer already there when the pack was put on got the model alone: due at once is harmless
                    known.putIfAbsent(viewer, now + VIEWER_RESEND_MS);
                }
                ModelUpdate update = null;
                for (Iterator<Map.Entry<Ref<EntityStore>, Long>> it = known.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Ref<EntityStore>, Long> v = it.next();
                    if (v.getValue() == Long.MAX_VALUE || v.getValue() > now) {
                        continue;
                    }
                    v.setValue(Long.MAX_VALUE); // served, until he stops seeing the wearer
                    EntityTrackerSystems.EntityViewer viewer = visible.visibleTo.get(v.getKey());
                    if (viewer == null || viewer.visible == null || !viewer.visible.contains(ref)) {
                        continue;
                    }
                    if (update == null) {
                        EntityScaleComponent scale = store.getComponent(ref, EntityScaleComponent.getComponentType());
                        update = new ModelUpdate(mc.getModel().toPacket(), scale != null ? scale.getScale() : 0f);
                    }
                    viewer.queueUpdate(ref, update);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Sac de campement sur le dos : renvoi du modèle aux nouveaux spectateurs en échec");
        }
    }

    /** Sends a pack wearer who arrived {@link #VIEWER_RESEND_MS} ago his own model again. World thread, never throws. */
    private static void resendToSelf(Store<EntityStore> store) {
        if (SELF.isEmpty()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<String, SelfResend>> it = SELF.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, SelfResend> e = it.next();
                Ref<EntityStore> ref = e.getValue().ref();
                if (!ref.isValid()) {
                    it.remove();
                    continue;
                }
                if (ref.getStore() != store || e.getValue().dueMs() > now) {
                    continue;
                }
                it.remove();
                if (!SHOWN.containsKey(e.getKey())) {
                    continue; // no pack on his back: the native look the game sent is right
                }
                ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
                EntityTrackerSystems.EntityViewer self = store.getComponent(ref, EntityTrackerSystems.EntityViewer.getComponentType());
                if (mc == null || self == null || !SkinAttachments.hasBag(mc.getModel())) {
                    continue;
                }
                EntityScaleComponent scale = store.getComponent(ref, EntityScaleComponent.getComponentType());
                self.queueUpdate(ref, new ModelUpdate(mc.getModel().toPacket(), scale != null ? scale.getScale() : 0f));
                LOGGER.at(Level.INFO).log("Sac de campement sur le dos : modèle renvoyé au joueur après arrivée");
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Sac de campement sur le dos : renvoi du modèle au joueur en échec");
        }
    }

    /** Called when a player arrives (connection, world change such as a goblin breach, respawn through PlayerReadyEvent). */
    static void onReady(Ref<EntityStore> ref) {
        try {
            if (ref != null && ref.isValid()) {
                refresh(ref.getStore(), ref, "arrivée du joueur");
                String id = uuid(ref.getStore(), ref);
                if (id != null) {
                    SELF.put(id, new SelfResend(ref, System.currentTimeMillis() + VIEWER_RESEND_MS));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Arrivée du joueur : sac de campement sur le dos en échec");
        }
    }

    /** Applies the setting to every online player of every world: on shows the packs, off restores the native look. */
    static void applySetting() {
        try {
            for (World world : Universe.get().getWorlds().values()) {
                world.execute(() -> {
                    try {
                        for (PlayerRef pr : world.getPlayerRefs()) {
                            Ref<EntityStore> ref = pr.getReference();
                            if (ref != null && ref.isValid()) {
                                refresh(ref.getStore(), ref, "réglage");
                            }
                        }
                    } catch (RuntimeException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Réglage du sac de campement sur le dos en échec");
                    }
                });
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Réglage du sac de campement sur le dos en échec");
        }
    }

    private static String uuid(Store<EntityStore> store, Ref<EntityStore> ref) {
        UUIDComponent c = store.getComponent(ref, UUIDComponent.getComponentType());
        return c == null ? null : c.getUuid().toString();
    }

    // ------------------------------------------------------------------------------------------------------------
    // triggers
    // ------------------------------------------------------------------------------------------------------------

    private abstract static class Watch<T extends Component<EntityStore>> extends RefChangeSystem<EntityStore, T> {
        abstract String cause();

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, T c, Store<EntityStore> store, CommandBuffer<EntityStore> cb) {
            changed(ref, store);
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, T oldC, T newC, Store<EntityStore> store, CommandBuffer<EntityStore> cb) {
            changed(ref, store);
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, T c, Store<EntityStore> store, CommandBuffer<EntityStore> cb) {
        }

        private void changed(Ref<EntityStore> ref, Store<EntityStore> store) {
            if (REBUILDING.get()) {
                return;
            }
            refresh(store, ref, cause());
        }
    }

    public static final class SkinWatch extends Watch<PlayerSkinComponent> {
        @Override
        public ComponentType<EntityStore, PlayerSkinComponent> componentType() {
            return PlayerSkinComponent.getComponentType();
        }

        @Override
        String cause() {
            return "changement d'apparence";
        }
    }

    public static final class SettingsWatch extends Watch<PlayerSettings> {
        @Override
        public ComponentType<EntityStore, PlayerSettings> componentType() {
            return PlayerSettings.getComponentType();
        }

        @Override
        String cause() {
            return "changement de réglages (masquage de l'armure)";
        }
    }

    /** Another system replaced the model: refresh (our own replacement is ignored through REBUILDING, so no loop). */
    public static final class ModelWatch extends Watch<ModelComponent> {
        @Override
        public ComponentType<EntityStore, ModelComponent> componentType() {
            return ModelComponent.getComponentType();
        }

        @Override
        String cause() {
            return "modèle remplacé par un autre système";
        }
    }

    /** Any inventory section or the armour changed. */
    public static final class InventoryWatch extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
        public InventoryWatch() {
            super(InventoryChangeEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> cb,
                           InventoryChangeEvent event) {
            try {
                refresh(store, chunk.getReferenceTo(index), "changement d'inventaire");
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Sac de campement sur le dos : écouteur d'inventaire en échec");
            }
        }
    }

    public static final class RespawnWatch extends EntityEventSystem<EntityStore, RespawnEvent> {
        public RespawnWatch() {
            super(RespawnEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> cb,
                           RespawnEvent event) {
            try {
                refresh(store, chunk.getReferenceTo(index), "réapparition");
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Sac de campement sur le dos : écouteur de réapparition en échec");
            }
        }
    }
}
