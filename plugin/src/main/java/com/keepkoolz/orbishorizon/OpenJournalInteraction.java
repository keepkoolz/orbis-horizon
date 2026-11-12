package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/** Interaction "OrbisHorizon_OpenJournal": opens the album of the explorer's journal. */
public class OpenJournalInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "OrbisHorizon_OpenJournal";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<OpenJournalInteraction> CODEC = BuilderCodec
            .builder(OpenJournalInteraction.class, OpenJournalInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Opens the album of the explorer's journal.")
            .build();

    public OpenJournalInteraction() {
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            Ref<EntityStore> ref = context.getEntity();
            World world = ((EntityStore) context.getCommandBuffer().getExternalData()).getWorld();
            world.execute(() -> open(world, ref));
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Journal page not opened");
            Telemetry.error("discovery", t, "journal", "open");
        }
    }

    /** Opens the album page for the player. Runs on the world thread and never throws. */
    static void open(World world, Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            Player player = store.getComponent(ref, Player.getComponentType());
            if (playerRef == null || player == null) {
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new JournalPage(playerRef));
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Journal page not opened");
            Telemetry.error("discovery", t, "journal", "open");
        }
    }

    @Override
    public String toString() {
        return "OpenJournalInteraction{}";
    }
}
