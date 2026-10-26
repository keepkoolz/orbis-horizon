package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import org.joml.Vector3i;
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
 * the pack command are reachable), each sub-command sets its own rights. {@code back on|off} is for ops.
 */
public final class CampingCommand extends AbstractCommandCollection {

    public CampingCommand() {
        super("camping", Texts.cmd("camping.desc"));
        requireNoPermission();
        addSubCommand(new Back());
        addSubCommand(new Pack());
        addSubCommand(new Forget());
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
            requireNoPermission();
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

    /** /orbishorizon camping forget x y z: forgets the camp respawn point on this block (menu of the world map). Open to everyone, own points only. */
    static final class Forget extends AbstractPlayerCommand {
        private final RequiredArg<Integer> x = withRequiredArg("x", Texts.cmd("camping.forget.x"), ArgTypes.INTEGER);
        private final RequiredArg<Integer> y = withRequiredArg("y", Texts.cmd("camping.forget.y"), ArgTypes.INTEGER);
        private final RequiredArg<Integer> z = withRequiredArg("z", Texts.cmd("camping.forget.z"), ArgTypes.INTEGER);

        Forget() {
            super("forget", Texts.cmd("camping.forget.desc"));
            requireNoPermission();
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Vector3i block = new Vector3i(x.get(ctx), y.get(ctx), z.get(ctx));
            Player pl = store.getComponent(ref, Player.getComponentType());
            PlayerWorldData data = pl == null ? null : pl.getPlayerConfigData().getPerWorldData(world.getName());
            if (data == null || !CampRespawn.forget(player.getUuid(), world.getName(), block)) {
                ctx.sendMessage(Texts.t("camp.respawnNotFound"));
                return;
            }
            data.setRespawnPoints(CampRespawn.withoutBlock(data.getRespawnPoints(), block));
            ctx.sendMessage(Texts.t("camp.respawnForgotten"));
        }
    }
}
