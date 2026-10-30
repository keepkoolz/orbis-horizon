package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/** /orbishorizon config: opens the configuration window (ops only). */
final class ConfigCommand extends AbstractPlayerCommand {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    ConfigCommand() {
        super("config", Texts.cmd("config.desc"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
        try {
            Player p = store.getComponent(ref, Player.getComponentType());
            if (p == null) {
                return;
            }
            p.getPageManager().openCustomPage(ref, store, new ConfigPage(player));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot open the configuration page for %s", player.getUsername());
            Telemetry.error("config", e, null, null);
        }
    }
}
