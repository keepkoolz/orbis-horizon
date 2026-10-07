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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * /orbishorizon balloon takeoff, /orbishorizon balloon land: piloting (first version).
 * /orbishorizon balloon model <id>: chooses the model used in flight.
 * /orbishorizon balloon spawnmodel <id>: shows a still model in front of the player (diagnostics).
 * /orbishorizon balloon despawn: removes the nearest balloon, administrators only (T40).
 */
public final class BalloonCommand extends AbstractCommandCollection {

    public BalloonCommand() {
        super("balloon", Texts.cmd("balloon.desc"));
        // The /orbishorizon balloon group and each sub-command are restricted to ops (hytale:Admin group).
        setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        addSubCommand(new TakeOff());
        addSubCommand(new Land());
        addSubCommand(new SetModel());
        addSubCommand(new SpawnModel());
        addSubCommand(new Offset());
        addSubCommand(new Debug());
        addSubCommand(new Attach());
        addSubCommand(new Passengers());
        addSubCommand(new SeatHeight());
        addSubCommand(new SeatPose());
        addSubCommand(new Light());
        addSubCommand(new Despawn());
        addSubCommand(new Cage());
        addSubCommand(new Render());
    }

    /**
     * T82: /orbishorizon balloon render dual|single. dual (default): the entity mounted on the pilot is seen by the pilot only and an
     * observer entity moved by the server is seen by the others. single: the previous behaviour (one entity mounted on the pilot,
     * seen by everybody). Applies at the next take-off. Without an argument value other than those two, shows the setting.
     */
    static final class Render extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("render.mode"), ArgTypes.STRING);

        Render() {
            super("render", Texts.cmd("balloon.render.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx);
            if (m.equalsIgnoreCase("dual")) {
                BalloonManager.get().setRenderDual(true);
            } else if (m.equalsIgnoreCase("single")) {
                BalloonManager.get().setRenderDual(false);
            } else if (m.toLowerCase().startsWith("parts")) {
                // Model parts: parts (show), parts:on, parts:off, parts:noyaw (next take-off).
                if (m.length() > 5) {
                    VehicleParts.Mode pm = m.charAt(5) == ':' ? VehicleParts.Mode.parse(m.substring(6)) : null;
                    if (pm == null) {
                        ctx.sendMessage(Texts.t("cmd.render.usage").param("command", "/orbishorizon balloon render"));
                        return;
                    }
                    BalloonManager.get().partsMode = pm;
                }
                ctx.sendMessage(Texts.t("cmd.render.parts").param("mode", BalloonManager.get().partsMode.name().toLowerCase()));
                return;
            } else if (!m.equalsIgnoreCase("show")) {
                ctx.sendMessage(Texts.t("cmd.render.usage").param("command", "/orbishorizon balloon render"));
                return;
            }
            ctx.sendMessage(Texts.t("cmd.render.state").param("mode", BalloonManager.get().renderDual() ? "dual" : "single")
                    .param("filter", ViewFilter.available() ? "ok" : "unavailable"));
        }
    }

    /**
     * T40: removes the nearest balloon (deployed or in flight), administrators (ops) only. The contents of the
     * burner and chests fall to the ground, and the administrator receives the deployment crate. Alias: remove.
     *
     * Permissions (HytaleServer-v65.jar bytecode, AbstractCommand): without any setting, a sub-command gets the node
     * "keepkoolz.orbis_horizon.command.orbishorizon.balloon.<name>" and the player must also hold the one of /orbishorizon balloon: only
     * members of the hytale:Admin group (permission "*", the ops) have them. setPermissionGroups(OP_GROUP) makes this
     * explicit and adds the node to the ops' virtual group, like the game's PlayerResetCommand.
     */
    static final class Despawn extends AbstractPlayerCommand {
        Despawn() {
            super("despawn", Texts.cmd("balloon.despawn.desc"));
            addAliases("remove");
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(BalloonManager.get().despawn(store, ref, player, world).message());
        }
    }

    /**
     * T34: lights of the balloon in flight (yellow crystals). Options separated by +: on, off, radius:N (forced radius,
     * for testing), radius:default (the block's radius), show. Takes effect immediately on the world's flights and at the
     * next take-off. Diagnostic setting, not saved.
     */
    static final class Light extends AbstractPlayerCommand {
        private final RequiredArg<String> options = withRequiredArg("options",
                Texts.cmd("balloon.light.options"), ArgTypes.STRING);

        Light() {
            super("light", Texts.cmd("balloon.light.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            for (String raw : options.get(ctx).split("[+,\\s]+")) {
                String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
                if (t.isEmpty() || t.equals("show")) {
                    continue;
                }
                if (t.equals("on")) {
                    BalloonLights.setEnabled(true);
                } else if (t.equals("off")) {
                    BalloonLights.setEnabled(false);
                } else if (t.equals("radius:default")) {
                    BalloonLights.setRadiusOverride(null);
                } else if (t.startsWith("radius:")) {
                    try {
                        BalloonLights.setRadiusOverride(Integer.parseInt(t.substring(7)));
                    } catch (NumberFormatException e) {
                        ctx.sendMessage(Texts.t("cmd.light.invalidRadius").param("value", t.substring(7)));
                        return;
                    }
                } else {
                    ctx.sendMessage(Texts.t("cmd.light.usage"));
                    return;
                }
            }
            BalloonManager.get().applyLightSetting(store, world);
            ctx.sendMessage(Texts.t("cmd.light.state").param("state", BalloonLights.describe(null)));
        }
    }

    /** T21: passenger seating method (fallback if mounting on the entity does not work in game). */
    static final class Passengers extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("balloon.passengers.mode"), ArgTypes.STRING);

        Passengers() {
            super("passengers", Texts.cmd("balloon.passengers.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx);
            if (m.equalsIgnoreCase("mount")) {
                BalloonManager.get().setPassengerMode(BalloonFlight.PassengerMode.MOUNT);
            } else if (m.equalsIgnoreCase("teleport")) {
                BalloonManager.get().setPassengerMode(BalloonFlight.PassengerMode.TELEPORT);
            } else if (m.equalsIgnoreCase("follow")) {
                BalloonManager.get().setPassengerMode(BalloonFlight.PassengerMode.FOLLOW);
            } else {
                ctx.sendMessage(Texts.t("cmd.passengers.usage"));
                return;
            }
            ctx.sendMessage(Texts.t("cmd.passengers.state").param("mode", String.valueOf(BalloonManager.get().passengerMode())));
        }
    }

    /**
     * T25: passenger seated-pose method, to compare in game (see BalloonFlight.Pose). Options separated
     * by +: none, default, states, nomount, controller, anim, slot:Name, id:Name. show displays the setting.
     */
    static final class SeatPose extends AbstractPlayerCommand {
        private final RequiredArg<String> options = withRequiredArg("options",
                Texts.cmd("balloon.seatpose.options"), ArgTypes.STRING);

        SeatPose() {
            super("seatpose", Texts.cmd("balloon.seatpose.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String o = options.get(ctx);
            if (!o.equalsIgnoreCase("show")) {
                try {
                    BalloonManager.get().setPose(BalloonFlight.Pose.parse(o));
                } catch (IllegalArgumentException e) {
                    ctx.sendMessage(Texts.t("cmd.seatpose.invalid").param("error", String.valueOf(e.getMessage())));
                    return;
                }
            }
            ctx.sendMessage(Texts.t(o.equalsIgnoreCase("show") ? "cmd.seatpose.show" : "cmd.seatpose.state")
                    .param("pose", BalloonManager.get().pose().describe()));
        }
    }

    /** T21: height of the seat point, to calibrate passengers in game. */
    static final class SeatHeight extends AbstractPlayerCommand {
        private final RequiredArg<Float> y = withRequiredArg("y", Texts.cmd("balloon.seatheight.y"), ArgTypes.FLOAT);

        SeatHeight() {
            super("seatheight", Texts.cmd("balloon.seatheight.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            BalloonManager.get().setSeatHeight(y.get(ctx));
            ctx.sendMessage(Texts.t("cmd.seatheight.state").param("height", BalloonManager.get().seatHeight()));
        }
    }

    static final class Offset extends AbstractPlayerCommand {
        private final RequiredArg<Float> x = withRequiredArg("x", Texts.cmd("balloon.offset.x"), ArgTypes.FLOAT);
        private final RequiredArg<Float> y = withRequiredArg("y", Texts.cmd("balloon.offset.y"), ArgTypes.FLOAT);
        private final RequiredArg<Float> z = withRequiredArg("z", Texts.cmd("balloon.offset.z"), ArgTypes.FLOAT);

        Offset() {
            super("offset", Texts.cmd("balloon.offset.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            BalloonManager.get().setModelOffset(x.get(ctx), y.get(ctx), z.get(ctx));
            ctx.sendMessage(Texts.t("cmd.offset.state").param("offset", String.valueOf(BalloonManager.get().modelOffset())));
        }
    }

    /** T73: state of the nearest transport balloon cage (descent, side, movement), diagnostic in English. */
    static final class Cage extends AbstractPlayerCommand {
        Cage() {
            super("cage", Texts.cmd("balloon.cage.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            ctx.sendMessage(Message.raw(t == null ? "Your position is unknown."
                    : TransportCage.describe(world, new org.joml.Vector3d(t.getPosition()))));
        }
    }

    static final class Debug extends AbstractPlayerCommand {
        Debug() {
            super("debug", Texts.cmd("balloon.debug.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            ctx.sendMessage(Message.raw(BalloonManager.get().debug(store, player)));
        }
    }

    static final class Attach extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("balloon.attach.mode"), ArgTypes.STRING);

        Attach() {
            super("attach", Texts.cmd("balloon.attach.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx);
            if (!m.equalsIgnoreCase("on") && !m.equalsIgnoreCase("off")) {
                ctx.sendMessage(Texts.t("cmd.attach.usage"));
                return;
            }
            BalloonManager.get().setAttachToPilot(m.equalsIgnoreCase("on"));
            ctx.sendMessage(Texts.t(BalloonManager.get().attachToPilot() ? "cmd.attach.on" : "cmd.attach.off"));
        }
    }

    static final class SetModel extends AbstractPlayerCommand {
        private final RequiredArg<String> id = withRequiredArg("id", Texts.cmd("balloon.model.id"), ArgTypes.STRING);

        SetModel() {
            super("model", Texts.cmd("balloon.model.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = BalloonManager.get().setModelId(id.get(ctx));
            ctx.sendMessage(error != null ? error : Texts.t("cmd.model.state").param("model", BalloonManager.get().modelId()));
        }
    }

    static final class SpawnModel extends AbstractPlayerCommand {
        private final RequiredArg<String> id = withRequiredArg("id", Texts.cmd("balloon.model.id"), ArgTypes.STRING);

        SpawnModel() {
            super("spawnmodel", Texts.cmd("balloon.spawnmodel.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = BalloonManager.get().spawnTestModel(store, ref, player, id.get(ctx));
            ctx.sendMessage(error != null ? error : Texts.t("cmd.spawnmodel.done").param("model", id.get(ctx)));
        }
    }

    static final class TakeOff extends AbstractPlayerCommand {
        TakeOff() {
            super("takeoff", Texts.cmd("balloon.takeoff.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = BalloonManager.get().takeOff(store, ref, player, world);
            ctx.sendMessage(error != null ? error : Texts.t("takeoff.command"));
        }
    }

    static final class Land extends AbstractPlayerCommand {
        Land() {
            super("land", Texts.cmd("balloon.land.desc"));
            setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Message error = BalloonManager.get().land(store, player);
            ctx.sendMessage(error != null ? error : Texts.t("landed"));
        }
    }
}
