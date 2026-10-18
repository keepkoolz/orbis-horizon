package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
 * /orbishorizon camping: camp commands. The group is open to everyone (requireNoPermission, so that open sub-commands such as
 * the pack command of T158 are reachable), each sub-command sets its own rights. {@code back on|off} is for ops.
 */
public final class CampingCommand extends AbstractCommandCollection {

    public CampingCommand() {
        super("camping", Texts.cmd("camping.desc"));
        requireNoPermission();
        addSubCommand(new Back());
        addSubCommand(new Pack());
    }

    /** /orbishorizon camping back on|off: shows or hides the camp pack on players' backs (server setting). */
    static final class Back extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("camping.back.mode"), ArgTypes.STRING);

        Back() {
            super("back", Texts.cmd("camping.back.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx);
            boolean on;
            if ("on".equalsIgnoreCase(m)) {
                on = true;
            } else if ("off".equalsIgnoreCase(m)) {
                on = false;
            } else {
                ctx.sendMessage(Texts.t("cmd.camping.backUsage"));
                return;
            }
            TransitSettings.setPackBackView(on);
            PackBackView.applySetting();
            ctx.sendMessage(Texts.t(on ? "cmd.camping.backOn" : "cmd.camping.backOff"));
        }
    }

    /** /orbishorizon camping pack: fallback to pack the nearest camp within 8 blocks when its bag cannot be used. Owner or op (checked in Java). */
    static final class Pack extends AbstractPlayerCommand {
        Pack() {
            super("pack", Texts.cmd("camping.pack.desc"));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            BalloonRegistry.Entry e = t != null ? LegacyCamps.nearest(world, t.getPosition(), 8) : null;
            if (e == null) {
                ctx.sendMessage(Texts.t("camp.noneNearby"));
                return;
            }
            CampPacker.pack(store, ref, world, e, true);
        }
    }
}
