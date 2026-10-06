package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * /orbishorizon airship ...: the airship prototype (AirshipManager). Everything is restricted to ops, like /orbishorizon balloon.
 * takeoff, land, steer (travel, look, off), yawsign, input, tune, debug, despawn. Diagnostic outputs (debug, input, tune, steer, yawsign) are in
 * English in both languages, like the balloon's debug.
 */
public final class AirshipCommand extends AbstractCommandCollection {

    public AirshipCommand() {
        super("airship", Texts.cmd("airship.desc"));
        setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        addSubCommand(new TakeOff());
        addSubCommand(new Land());
        addSubCommand(new Steer());
        addSubCommand(new YawSign());
        addSubCommand(new Input());
        addSubCommand(new Tune());
        addSubCommand(new Debug());
        addSubCommand(new Despawn());
    }

    static final class TakeOff extends AbstractPlayerCommand {
        TakeOff() {
            super("takeoff", Texts.cmd("airship.takeoff.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = AirshipManager.get().takeOff(store, ref, player, world);
            if (error != null) {
                ctx.sendMessage(error);
            }
        }
    }

    static final class Land extends AbstractPlayerCommand {
        Land() {
            super("land", Texts.cmd("airship.land.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = AirshipManager.get().requestLanding(player.getUuid());
            if (error != null) {
                ctx.sendMessage(error);
            }
        }
    }

    static final class Steer extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("airship.steer.mode"), ArgTypes.STRING);

        Steer() {
            super("steer", Texts.cmd("airship.steer.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            AirshipManager m = AirshipManager.get();
            AirshipManager.Steer s = m.parseSteer(mode.get(ctx));
            if (s == null) {
                ctx.sendMessage(Message.raw("Steering modes: travel (the ship turns toward the direction you fly, default), look (toward "
                        + "your head yaw), off (fixed heading). Current: " + m.steer));
                return;
            }
            m.steer = s;
            ctx.sendMessage(Message.raw("Steering mode: " + s));
        }
    }

    static final class YawSign extends AbstractPlayerCommand {
        YawSign() {
            super("yawsign", Texts.cmd("airship.yawsign.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            double v = AirshipManager.get().flipYawSign();
            ctx.sendMessage(Message.raw("Visual yaw sign is now " + (v > 0 ? "+1 (normal)" : "-1 (flipped, quarter turns are mirrored)")));
        }
    }

    static final class Input extends AbstractPlayerCommand {
        Input() {
            super("input", Texts.cmd("airship.input.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            AirshipManager.get().startWatch(player);
            ctx.sendMessage(Message.raw("Logging your raw input for 10 s (chat and server log): wish movement, rider/movement states, head yaw, "
                    + "position updates. Press keys and look around now."));
        }
    }

    static final class Tune extends AbstractPlayerCommand {
        private final RequiredArg<String> param = withRequiredArg("param", Texts.cmd("airship.tune.param"), ArgTypes.STRING);
        private final RequiredArg<String> value = withRequiredArg("value", Texts.cmd("airship.tune.value"), ArgTypes.STRING);

        Tune() {
            super("tune", Texts.cmd("airship.tune.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(Message.raw(AirshipManager.get().tune(param.get(ctx), value.get(ctx))));
        }
    }

    static final class Debug extends AbstractPlayerCommand {
        Debug() {
            super("debug", Texts.cmd("airship.debug.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(Message.raw(AirshipManager.get().debug(store, player)));
        }
    }

    static final class Despawn extends AbstractPlayerCommand {
        Despawn() {
            super("despawn", Texts.cmd("airship.despawn.desc"));
            addAliases("remove");
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(AirshipManager.get().despawn(store, ref, player, world).message());
        }
    }
}
