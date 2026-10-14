package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/** /orbishorizon map share|unshare <id>: sharing of a structure on the world map, open to every player (owner or op). */
public final class MapCommand extends AbstractCommandCollection {

    public MapCommand() {
        super("map", Texts.cmd("map.desc"));
        requireNoPermission();
        addSubCommand(new Share(true));
        addSubCommand(new Share(false));
    }

    /** The id as a UUID, null if it is not one. */
    static UUID parseId(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True if the player may change the sharing: an op, or the owner. */
    static boolean canToggle(Ownership own, UUID player, boolean op) {
        return op || (own.owner() != null && own.owner().equals(player));
    }

    static final class Share extends AbstractPlayerCommand {
        private final boolean share;
        private final RequiredArg<String> id;

        Share(boolean share) {
            super(share ? "share" : "unshare", Texts.cmd(share ? "map.share.desc" : "map.unshare.desc"));
            this.share = share;
            requireNoPermission();
            this.id = withRequiredArg("id", Texts.cmd("map.id.desc"), ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            UUID sid = parseId(id.get(ctx));
            Ownership own = sid == null ? null : find(sid);
            if (own == null) {
                ctx.sendMessage(Texts.t("map.notFound"));
                return;
            }
            if (own.owner() == null) {
                ctx.sendMessage(Texts.t("map.noOwner"));
                return;
            }
            if (!canToggle(own, player.getUuid(), StructureRemoval.isOp(player.getUuid()))) {
                ctx.sendMessage(Texts.t("map.notOwner"));
                return;
            }
            BalloonRegistry.setShared(sid, share);
            for (BalloonFlight f : BalloonManager.get().flights()) {
                if (sid.equals(f.ownership.id())) {
                    f.ownership = f.ownership.withShared(share);
                }
            }
            for (AirshipFlight f : AirshipManager.get().flights()) {
                if (sid.equals(f.ownership.id())) {
                    f.ownership = f.ownership.withShared(share);
                }
            }
            ctx.sendMessage(Texts.t(share ? "map.shared" : "map.unshared"));
        }

        /** Ownership of the structure with this id: registry first, then the flights. Null if unknown. */
        private static Ownership find(UUID sid) {
            BalloonRegistry.Entry e = BalloonRegistry.byId(sid);
            if (e != null) {
                return e.ownership();
            }
            for (BalloonFlight f : BalloonManager.get().flights()) {
                if (sid.equals(f.ownership.id())) {
                    return f.ownership;
                }
            }
            for (AirshipFlight f : AirshipManager.get().flights()) {
                if (sid.equals(f.ownership.id())) {
                    return f.ownership;
                }
            }
            return null;
        }
    }
}
