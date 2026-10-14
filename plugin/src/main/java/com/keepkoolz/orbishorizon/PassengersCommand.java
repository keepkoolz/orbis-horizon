package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** /orbishorizon passengers protect full|damage|off: passenger protection setting (next boarding), ops only. */
public final class PassengersCommand extends AbstractCommandCollection {

    public PassengersCommand() {
        super("passengers", Texts.cmd("passengers.desc"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        addSubCommand(new Protect());
    }

    static final class Protect extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("passengers.protect.mode"), ArgTypes.STRING);

        Protect() {
            super("protect", Texts.cmd("passengers.protect.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx);
            PassengerProtection.Mode parsed = null;
            for (PassengerProtection.Mode v : PassengerProtection.Mode.values()) {
                if (v.name().equalsIgnoreCase(m)) {
                    parsed = v;
                }
            }
            if (parsed == null) {
                ctx.sendMessage(Texts.t("cmd.passengers.protectUsage"));
                return;
            }
            PassengerProtection.setMode(parsed);
            ctx.sendMessage(Texts.t("cmd.passengers.protect").param("mode", parsed.name().toLowerCase()));
        }
    }
}
