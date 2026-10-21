package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** /orbishorizon steamboat despawn: removes the nearest steamboat only, ops only. */
public final class SteamboatCommand extends AbstractCommandCollection {

    public SteamboatCommand() {
        super("steamboat", Texts.cmd("steamboat.desc"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        addSubCommand(new Despawn());
    }

    static final class Despawn extends AbstractPlayerCommand {
        Despawn() {
            super("despawn", Texts.cmd("steamboat.despawn.desc"));
            addAliases("remove");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(AirshipManager.get().despawn(store, ref, player, world, Deployables.STEAMBOAT_KIND).message());
        }
    }
}
