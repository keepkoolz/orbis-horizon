package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;

import java.util.List;
import java.util.logging.Level;

/**
 * Interaction "OrbisHorizon_ExamineFurniture": examines the targeted block, or opens the album when there is none or it is not furniture of a family. A piece of furniture of a known family goes into the
 * player's album, teaches its recipe when it has one to learn, and may complete the family's title. The album and recipe work runs
 * on the world thread, the messages are decided by DiscoveryRules.
 */
public class ExamineFurnitureInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "OrbisHorizon_ExamineFurniture";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String SOUND_LEARNED = "SFX_Discovery_Z1_Short";
    private static final String SOUND_TITLE = "SFX_Discovery_Z1_Medium";

    public static final BuilderCodec<ExamineFurnitureInteraction> CODEC = BuilderCodec
            .builder(ExamineFurnitureInteraction.class, ExamineFurnitureInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Examines the targeted block: adds a piece of furniture to the player's journal and teaches its recipe.")
            .build();

    public ExamineFurnitureInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            Ref<EntityStore> player = context.getEntity();
            PlayerRef playerRef = commandBuffer.getComponent(player, PlayerRef.getComponentType());
            BlockPosition target = context.getTargetBlock();
            if (playerRef == null) {
                context.getState().state = InteractionState.Failed;
                return;
            }
            World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
            if (target == null) {
                world.execute(() -> OpenJournalInteraction.open(world, player));
                return;
            }
            int x = target.x, y = target.y, z = target.z;
            world.execute(() -> examine(world, player, playerRef, x, y, z));
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Examine furniture failed");
            Telemetry.error("discovery", t, "examine", "run");
        }
    }

    /** Runs on the world thread. Anything that is not furniture of a family opens the album. Never throws: an error answers "nothing to note". */
    private static void examine(World world, Ref<EntityStore> player, PlayerRef playerRef, int x, int y, int z) {
        try {
            if (!player.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            String id = itemIdAt(world, x, y, z);
            if (id == null || FurnitureDiscovery.familyOf(id) == null) {
                OpenJournalInteraction.open(world, player);
                return;
            }
            FurnitureDiscovery.Item furniture = FurnitureDiscovery.item(id);
            boolean enabled = TransitSettings.furnitureDiscovery();
            DiscoveryStore.AddResult result = null;
            boolean learned = false;
            if (furniture != null && enabled) {
                String worldName = world.getName() == null ? "" : world.getName();
                result = DiscoveryStore.record(playerRef.getUuid(), new DiscoveryStore.Entry(id, x, y, z, worldName));
                learned = CraftingPlugin.learnRecipe(player, id, store);
            }
            List<DiscoveryRules.Line> lines = DiscoveryRules.messages(id, enabled, result, learned);
            for (DiscoveryRules.Line line : lines) {
                playerRef.sendMessage(message(line));
                if (line.key().equals("discovery.learned")) {
                    play(store, player, SOUND_LEARNED, x, y, z);
                } else if (line.key().equals("discovery.titleEarned")) {
                    EventTitleUtil.showEventTitleToPlayer(playerRef, message(line),
                            Texts.t(FurnitureDiscovery.familyNameKey(result.titleFamily())), true);
                    play(store, player, SOUND_TITLE, x, y, z);
                }
            }
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Examine furniture failed");
            Telemetry.error("discovery", t, "examine", "run");
            try {
                playerRef.sendMessage(Texts.t("discovery.nothing"));
            } catch (Throwable ignored) {
                // the player left
            }
        }
    }

    /** Item id of the block, null when the section is not loaded or the block has no item. */
    private static String itemIdAt(World world, int x, int y, int z) {
        BlockType block = SectionBlocks.typeAt(new SectionReader(world.getChunkStore()), x, y, z);
        if (block == null || block.getItem() == null) {
            return null;
        }
        return block.getItem().getId();
    }

    /** The chat message of a line: the parameter is an item id, a family name key or a title key depending on the line. */
    private static Message message(DiscoveryRules.Line line) {
        Message m = Texts.t(line.key());
        String param = line.param();
        if (param == null) {
            return m;
        }
        return switch (line.key()) {
            case "discovery.newPage" -> m.param("family", Texts.t(param));
            case "discovery.titleEarned" -> m.param("title", Texts.t(param));
            default -> m.param("name", Message.translation(nameKey(param)));
        };
    }

    /** Translation key of the name an item declares in its JSON, or the key of its id when the item has none. */
    static String nameKey(String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null && item.getTranslationProperties() != null && item.getTranslationProperties().getName() != null) {
                return item.getTranslationProperties().getName();
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Item name %s not read", itemId);
        }
        return "server.items." + itemId + ".name";
    }

    private static void play(Store<EntityStore> store, Ref<EntityStore> player, String soundId, int x, int y, int z) {
        int idx = SoundEvent.getAssetMap().getIndex(soundId);
        if (idx > 0) {
            SoundUtil.playSoundEvent3dToPlayer(player, idx, SoundCategory.UI, x + 0.5, y + 0.5, z + 0.5, store);
        }
    }

    @Override
    public String toString() {
        return "ExamineFurnitureInteraction{}";
    }
}
