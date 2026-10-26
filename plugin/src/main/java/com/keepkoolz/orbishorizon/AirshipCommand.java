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
        // The group is open so that "pilot reset" can be used by captains; every other sub-command keeps its own op-only group.
        requireNoPermission();
        addSubCommand(new Pilot());
        addSubCommand(new TakeOff());
        addSubCommand(new Land());
        addSubCommand(new Steer());
        addSubCommand(new YawSign());
        addSubCommand(new Input());
        addSubCommand(new Tune());
        addSubCommand(new Debug());
        addSubCommand(new Despawn());
        addSubCommand(new Render());
    }

    /** /orbishorizon airship pilot reset: open to every player, checked in Java (owner of the airship or op). */
    static final class Pilot extends AbstractCommandCollection {
        Pilot() {
            super("pilot", Texts.cmd("airship.pilot.desc"));
            requireNoPermission();
            addSubCommand(new PilotReset());
        }
    }

    static final class PilotReset extends AbstractPlayerCommand {
        PilotReset() {
            super("reset", Texts.cmd("airship.pilot.reset.desc"));
            requireNoPermission();
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            com.hypixel.hytale.server.core.modules.entity.component.TransformComponent tr =
                    store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (tr == null) {
                return;
            }
            AirshipCrew.ResetResult r = AirshipCrew.resetPilot(store, world, new org.joml.Vector3d(tr.getPosition()),
                    player.getUuid(), StructureRemoval.isOp(player.getUuid()));
            ctx.sendMessage(Texts.t(switch (r) {
                case DONE -> "crew.reset.done";
                case NONE -> "crew.reset.none";
                case NOT_OWNER -> "crew.reset.notOwner";
            }));
        }
    }

    /** /orbishorizon airship render dual|single, same as the balloon's (next take-off). */
    static final class Render extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("render.mode"), ArgTypes.STRING);

        Render() {
            super("render", Texts.cmd("airship.render.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx);
            if (m.equalsIgnoreCase("dual")) {
                AirshipManager.get().renderDual = true;
            } else if (m.equalsIgnoreCase("single")) {
                AirshipManager.get().renderDual = false;
            } else if (m.toLowerCase().startsWith("parts")) {
                // Model parts: parts (show), parts:on, parts:off, parts:noyaw (next take-off).
                if (m.length() > 5) {
                    VehicleParts.Mode pm = m.charAt(5) == ':' ? VehicleParts.Mode.parse(m.substring(6)) : null;
                    if (pm == null) {
                        ctx.sendMessage(Texts.t("cmd.render.usage").param("command", "/orbishorizon airship render"));
                        return;
                    }
                    AirshipManager.get().partsMode = pm;
                }
                ctx.sendMessage(Texts.t("cmd.render.parts").param("mode", AirshipManager.get().partsMode.name().toLowerCase()));
                return;
            } else if (m.equalsIgnoreCase("pilot:on") || m.equalsIgnoreCase("pilot:off")) {
                // standing mannequin of the pilot for the others (next take-off), shared by the balloons and the airship.
                PilotMannequin.enabled = m.equalsIgnoreCase("pilot:on");
                ctx.sendMessage(Texts.t("cmd.render.pilot").param("mode", PilotMannequin.enabled ? "on" : "off"));
                return;
            } else if (!m.equalsIgnoreCase("show")) {
                ctx.sendMessage(Texts.t("cmd.render.usage").param("command", "/orbishorizon airship render"));
                return;
            }
            ctx.sendMessage(Texts.t("cmd.render.state").param("mode", AirshipManager.get().renderDual ? "dual" : "single")
                    .param("filter", ViewFilter.available() ? "ok" : "unavailable"));
        }
    }

    static final class TakeOff extends AbstractPlayerCommand {
        TakeOff() {
            super("takeoff", Texts.cmd("airship.takeoff.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = AirshipManager.get().takeOff(store, ref, player, world);
            if (error != null && error != AirshipManager.DEFERRED) {
                ctx.sendMessage(error);
            }
        }
    }

    static final class Land extends AbstractPlayerCommand {
        Land() {
            super("land", Texts.cmd("airship.land.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
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
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            AirshipManager m = AirshipManager.get();
            AirshipManager.Steer s = m.parseSteer(mode.get(ctx));
            if (s == null) {
                ctx.sendMessage(Message.raw("Steering modes: travel (the ship turns "
                        + "toward the direction you fly, default), look (toward your head yaw), off (fixed heading). Current: " + m.steer));
                return;
            }
            m.steer = s;
            ctx.sendMessage(Message.raw("Steering mode: " + s));
        }
    }

    static final class YawSign extends AbstractPlayerCommand {
        YawSign() {
            super("yawsign", Texts.cmd("airship.yawsign.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
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
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
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
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(Message.raw(AirshipManager.get().tune(param.get(ctx), value.get(ctx))));
        }
    }

    static final class Debug extends AbstractPlayerCommand {
        Debug() {
            super("debug", Texts.cmd("airship.debug.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
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
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(AirshipManager.get().despawn(store, ref, player, world).message());
        }
    }
}
