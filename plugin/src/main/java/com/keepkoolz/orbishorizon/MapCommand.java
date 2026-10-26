package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3i;

import java.util.UUID;
import java.util.logging.Level;

/**
 * /orbishorizon map share|unshare <id>: sharing of a structure on the world map, open to every player (owner or op).
 * /orbishorizon map shareStation <x> <y> <z>: sharing of a known station with every player of the world.
 */
public final class MapCommand extends AbstractCommandCollection {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public MapCommand() {
        super("map", Texts.cmd("map.desc"));
        requireNoPermission();
        addSubCommand(new Share(true));
        addSubCommand(new Share(false));
        addSubCommand(new ShareStation());
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

    /** Shares a station the player knows with every player of the world, from the menu of its map marker. */
    static final class ShareStation extends AbstractPlayerCommand {
        private final RequiredArg<Integer> x;
        private final RequiredArg<Integer> y;
        private final RequiredArg<Integer> z;

        ShareStation() {
            super("shareStation", Texts.cmd("map.shareStation.desc"));
            requireNoPermission();
            this.x = withRequiredArg("x", Texts.cmd("map.shareStation.x"), ArgTypes.INTEGER);
            this.y = withRequiredArg("y", Texts.cmd("map.shareStation.y"), ArgTypes.INTEGER);
            this.z = withRequiredArg("z", Texts.cmd("map.shareStation.z"), ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            try {
                TransitNetwork.Station s = TransitNetwork.get().at(world.getName(), new Vector3i(x.get(ctx), y.get(ctx), z.get(ctx)));
                if (s == null) {
                    ctx.sendMessage(Texts.t("map.station.notFound"));
                    return;
                }
                SkyLog.Reached key = new SkyLog.Reached(s.origin.x, s.origin.y, s.origin.z);
                KnownStations known = KnownStations.get();
                if (!known.known(world.getName(), player.getUuid(), key)) {
                    ctx.sendMessage(Texts.t("map.station.unknown"));
                    return;
                }
                if (!known.share(world.getName(), key)) {
                    ctx.sendMessage(Texts.t("map.station.alreadyShared").param("station", s.name));
                    return;
                }
                known.save();
                ctx.sendMessage(Texts.t("map.station.shared").param("station", s.name));
                for (PlayerRef other : world.getPlayerRefs()) {
                    if (other.isValid() && !other.getUuid().equals(player.getUuid())
                            && !known.knownPersonally(world.getName(), other.getUuid(), key)) {
                        StationDiscovery.showShared(other, s.name, player.getUsername());
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Partage de station impossible");
                Telemetry.error("map.shareStation", e, null, null);
                ctx.sendMessage(Texts.t("map.station.error"));
            }
        }
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
