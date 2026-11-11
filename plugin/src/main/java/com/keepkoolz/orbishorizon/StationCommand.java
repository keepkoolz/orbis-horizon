package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/** /orbishorizon station ...: transit network administration (ops). */
public final class StationCommand extends AbstractCommandCollection {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public StationCommand() {
        super("station", Texts.cmd("station.desc"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        addSubCommand(new ListStations());
        addSubCommand(new Debug());
        addSubCommand(new Despawn());
        addSubCommand(new Contract());
        addSubCommand(new Tp());
        addSubCommand(new Unlock());
        addSubCommand(new Lock());
        addSubCommand(new Nearest());
        for (AbstractCommand extra : Extensions.stationSubCommands()) {
            try {
                addSubCommand(extra);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Extension station command not added");
            }
        }
    }

    private static org.joml.Vector3d posOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        return t == null ? null : new org.joml.Vector3d(t.getPosition());
    }

    /** Diagnostic line of one station (English, not translated). */
    private static String describe(TransitNetwork.Station s, boolean detail) {
        StringBuilder b = new StringBuilder();
        b.append(s.name).append(" at (").append(s.origin.x).append(", ").append(s.origin.y).append(", ").append(s.origin.z)
                .append(") ").append(s.rotation).append(", kind ").append(s.kind).append(", quay ").append(StationQueue.state(s))
                .append(", queue ").append(s.queue).append(", master ").append(s.masterUuid != null ? "yes" : "none");
        if (detail) {
            b.append("\n  owner ").append(s.owner).append("\n  master ").append(s.masterUuid)
                    .append("\n  countdown ").append(StationQueue.secondsLeft(s)).append(" s")
                    .append("\n  geometry: full prefab ").append(s.fullPrefab).append(", editable ").append(s.editable)
                    .append("\n  master cell ").append(s.master).append(", yaw ").append(s.masterYaw)
                    .append("\n  quay ").append(s.quay).append(" ").append(s.quayRotation);
            if (s.quay != null) {
                b.append("\n  dock origin ").append(StationQueue.dockOrigin(s)).append(", pad y ").append(StationQueue.padY(s))
                        .append("\n  marker point ").append(StationGeometry.markerPoint(s));
            }
            if (s.master != null) {
                b.append("\n  master point ").append(StationMaster.masterPoint(s));
            }
        }
        return b.toString();
    }

    /** /orbishorizon station list: every station of the current world. */
    static final class ListStations extends AbstractPlayerCommand {
        ListStations() {
            super("list", Texts.cmd("station.list.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            java.util.List<TransitNetwork.Station> all = TransitNetwork.get().stations(world.getName());
            StringBuilder b = new StringBuilder("Stations in " + world.getName() + ": " + all.size());
            for (TransitNetwork.Station s : all) b.append("\n- ").append(describe(s, false));
            ctx.sendMessage(Message.raw(b.toString()));
        }
    }

    /** /orbishorizon station debug: detail of the nearest station within 48 blocks. */
    static final class Debug extends AbstractPlayerCommand {
        Debug() {
            super("debug", Texts.cmd("station.debug.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            org.joml.Vector3d pos = posOf(store, ref);
            TransitNetwork.Station s = pos == null ? null
                    : TransitNetwork.get().nearest(world.getName(), pos, TransitConstants.REMOVE_RADIUS);
            ctx.sendMessage(Message.raw(s == null ? "No station within " + (int) TransitConstants.REMOVE_RADIUS + " blocks."
                    : describe(s, true)));
            if (s != null && Deployables.BOAT_STATION_KIND.equals(s.kind)) {
                for (Message m : BoatBerth.describe(s)) {
                    ctx.sendMessage(m);
                }
            }
        }
    }

    /**
     * Master cell of the station whose master is nearest to pos and within radius of it, null when none is. Stations without a master
     * cell are skipped, stations without a quay (pending) count. Pure.
     */
    static org.joml.Vector3i nearestMaster(List<TransitNetwork.Station> all, org.joml.Vector3d pos, double radius) {
        org.joml.Vector3i best = null;
        double bestD = radius * radius;
        for (TransitNetwork.Station s : all) {
            org.joml.Vector3i m = s.master;
            if (m == null) continue;
            double d = pos.distanceSquared(m.x + 0.5, m.y + 0.5, m.z + 0.5);
            if (d <= bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /** The station designated by a name (underscores put back as spaces), null when unknown. */
    private static TransitNetwork.Station byTypedName(World world, String wanted) {
        TransitNetwork.Station exact = TransitNetwork.get().byName(world.getName(), wanted);
        return exact != null || wanted.indexOf('_') < 0 ? exact : TransitNetwork.get().byName(world.getName(), wanted.replace('_', ' '));
    }

    /** Registry kind of the quay of a station that is not a full prefab. */
    static Deployables.Kind quayKind(TransitNetwork.Station s) {
        return Deployables.BOAT_STATION_KIND.equals(s.kind) ? Deployables.BOAT_STATION_PIER : Deployables.STATION_PAD;
    }

    /**
     * The cells of a shape that belong to the locked quay core of a station (StationGeometry.inQuayCore), the shape of what is taken
     * down from a whole-prefab station whose structure may be edited: the rest of the structure stays in the world. Pure.
     */
    static StructureShape quayCoreShape(TransitNetwork.Station s, StructureShape shape, Rotation rotation, org.joml.Vector3i origin) {
        List<StructureShape.Cell> core = new java.util.ArrayList<>();
        for (StructureShape.Cell c : shape.cells()) {
            org.joml.Vector3i p = c.rotated(rotation).add(origin);
            if (StationGeometry.inQuayCore(s, p.x, p.y, p.z)) {
                core.add(c);
            }
        }
        return shape.withCells(core);
    }

    /**
     * /orbishorizon station despawn [name]: removes the station whose master is nearest (48 blocks, with or without a quay) or the
     * named one, and gives its contract back to the operator.
     */
    static final class Despawn extends AbstractPlayerCommand {
        private final OptionalArg<String> name = withOptionalArg("name", Texts.cmd("station.despawn.name"), StationNameType.INSTANCE);

        Despawn() {
            super("despawn", Texts.cmd("station.despawn.desc"));
            addAliases("remove");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            try {
                despawn(ctx, store, ref, player, world);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station despawn failed");
                Telemetry.error("transit", e, null, "despawn");
                ctx.sendMessage(Texts.t("transit.cmd.despawnFailed"));
            }
        }

        private void despawn(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            org.joml.Vector3d here = posOf(store, ref);
            TransitNetwork.Station s = null;
            if (name.provided(ctx)) {
                String wanted = name.get(ctx);
                s = byTypedName(world, wanted);
                if (s == null) {
                    ctx.sendMessage(Texts.t("transit.cmd.tpUnknown").param("name", TransitNetwork.normalize(wanted)));
                    return;
                }
            } else if (here != null) {
                List<TransitNetwork.Station> all = TransitNetwork.get().stations(world.getName());
                org.joml.Vector3i master = nearestMaster(all, here, TransitConstants.REMOVE_RADIUS);
                for (TransitNetwork.Station c : all) {
                    if (master != null && master.equals(c.master)) {
                        s = c;
                        break;
                    }
                }
            }
            if (s == null) {
                ctx.sendMessage(Texts.t("transit.cmd.despawnNone").param("radius", (int) TransitConstants.REMOVE_RADIUS));
                return;
            }
            // What is taken down: the whole prefab of a generated or converted station, the pad or pier of a player station
            // (nothing for a station still without a quay).
            Deployables.Kind kind;
            org.joml.Vector3i origin;
            com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation;
            // A whole-prefab station whose structure may be edited loses only its quay core, the rest is ordinary blocks.
            boolean coreOnly = s.fullPrefab && s.quay != null && StationClearance.editable(s);
            if (s.fullPrefab) {
                kind = StationSpec.of(s.kind).structure();
                origin = s.origin;
                rotation = s.rotation;
            } else if (s.quay != null && s.quayRotation != null) {
                kind = quayKind(s);
                origin = s.quay;
                rotation = s.quayRotation;
            } else {
                kind = null;
                origin = null;
                rotation = null;
            }
            // The registry shape follows the wood of a boat station (renamed blocks), the prefab shape does not.
            StructureShape shape = null;
            if (kind != null) {
                try {
                    BalloonRegistry.Entry entry = BalloonRegistry.get(world, origin, rotation, kind.id);
                    StructureShape reg = entry == null ? null : BalloonRegistry.shapeOf(entry);
                    shape = reg != null ? reg : kind.shape();
                    if (coreOnly) {
                        shape = quayCoreShape(s, shape, rotation, origin);
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Shape of station %s unreadable", s.name);
                    ctx.sendMessage(Texts.t("transit.cmd.despawnFailed"));
                    return;
                }
            }
            // 0. Players on the pad, in the hut or in the docked balloon lose their floor: unseated now, put on the ground after the removal.
            java.util.Map<Ref<EntityStore>, org.joml.Vector3d> stranded = new java.util.LinkedHashMap<>();
            if (shape != null) {
                try {
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (StructureShape.Cell c : shape.cells()) {
                        org.joml.Vector3i p = c.rotated(rotation).add(origin);
                        minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
                        maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
                    }
                    for (PlayerRef pr : world.getPlayerRefs()) {
                        Ref<EntityStore> pRef = pr.getReference();
                        if (pRef == null || !pRef.isValid()) continue;
                        org.joml.Vector3d pp = posOf(store, pRef);
                        if (pp != null && pp.x >= minX - 1 && pp.x <= maxX + 2 && pp.y >= minY - 1 && pp.y <= maxY + 4
                                && pp.z >= minZ - 1 && pp.z <= maxZ + 2) {
                            stranded.put(pRef, pp);
                        }
                    }
                    if (Deployables.STATION_KIND.equals(s.kind) && s.quay != null) {
                        org.joml.Vector3i dock = StationQueue.dockOrigin(s);
                        if (BalloonRegistry.get(world, dock, s.quayRotation, Deployables.BALLOON_PUBLIC.id) != null) {
                            for (BalloonManager.Occupant o : BalloonManager.get().nacelleOccupants(world, store, Deployables.BALLOON_PUBLIC, dock, s.quayRotation)) {
                                org.joml.Vector3d pp = posOf(store, o.ref());
                                if (pp != null) stranded.putIfAbsent(o.ref(), pp);
                            }
                        }
                    }
                    for (Ref<EntityStore> sr : stranded.keySet()) MountUtil.unseat(store, sr);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Players of station %s not located", s.name);
                }
            }
            // 1. Station blocks first: it refuses (chunks not loaded) before anything else is touched. Contents dropped, lock entry removed.
            if (shape != null) {
                StructureRemoval.Result r;
                try {
                    r = StructureRemoval.remove(store, world, kind, shape, origin, rotation);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Station %s not removed", s.name);
                    ctx.sendMessage(Texts.t("transit.cmd.despawnFailed"));
                    return;
                }
                if (r.error() != null) {
                    ctx.sendMessage(r.error());
                    return;
                }
                Integer soil = StationSpec.of(s.kind).soilLayerY();
                try {
                    if (s.fullPrefab && !coreOnly && soil != null) {
                        fillGroundHoles(world, shape, s.origin, s.rotation, soil);
                    }
                } catch (RuntimeException e) {
                    // the ground holes stay as they are
                }
            }
            // 2. Master NPC, network entry, quay, flights and shuttles heading here, other queues.
            StationRemoval.fromNetwork(world, s);
            for (java.util.Map.Entry<Ref<EntityStore>, org.joml.Vector3d> e : stranded.entrySet()) {
                if (e.getKey().isValid()) BalloonManager.putOnGround(store, world, e.getKey(), e.getValue());
            }
            Extensions.stationRemoved(world, s);
            StationContracts.Given given = StationContracts.give(player, s.kind);
            ctx.sendMessage(Texts.t(given == StationContracts.Given.GIVEN ? "transit.cmd.despawnDone" : "transit.cmd.despawnDoneFull")
                    .param("name", s.name));
            if (given == StationContracts.Given.FAILED) {
                ctx.sendMessage(Texts.t("transit.contract.failed"));
            }
        }
    }

    /**
     * /orbishorizon station tp (name): teleports the operator to the first drop point of the station. The name is a greedy string
     * (ArgTypes.GREEDY_STRING takes the rest of the line), so names with spaces need no quotes.
     */
    static final class Tp extends AbstractPlayerCommand {
        private final RequiredArg<String> name = withRequiredArg("name", Texts.cmd("station.tp.name"), StationNameType.INSTANCE);

        Tp() {
            super("tp", Texts.cmd("station.tp.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String wanted = name.get(ctx);
            TransitNetwork.Station exact = TransitNetwork.get().byName(world.getName(), wanted);
            // Suggested form of a name with spaces: underscores put back as spaces.
            TransitNetwork.Station s = exact != null || wanted.indexOf('_') < 0 ? exact
                    : TransitNetwork.get().byName(world.getName(), wanted.replace('_', ' '));
            if (s == null) {
                ctx.sendMessage(Texts.t("transit.cmd.tpUnknown").param("name", TransitNetwork.normalize(wanted)));
                return;
            }
            ApiStations.teleportTo(ctx, ref, world, StationSpec.teleportPoint(world, s), Texts.t("transit.cmd.tpDone").param("name", s.name), s.name);
        }
    }

    /** /orbishorizon station contract (balloon|boat) [--player name]: gives a station contract to the operator or to the named player. */
    static final class Contract extends AbstractPlayerCommand {
        private final RequiredArg<String> kind = withRequiredArg("kind", Texts.cmd("station.contract.kind"), ArgTypes.STRING);
        private final OptionalArg<PlayerRef> target = withOptionalArg("player", Texts.cmd("station.contract.player"), ArgTypes.PLAYER_REF);

        Contract() {
            super("contract", Texts.cmd("station.contract.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            try {
                String k = kind.get(ctx).trim().toLowerCase(java.util.Locale.ROOT);
                String stationKind = switch (k) {
                    case "balloon" -> Deployables.STATION_KIND;
                    case "boat" -> Deployables.BOAT_STATION_KIND;
                    default -> null;
                };
                if (stationKind == null) {
                    ctx.sendMessage(Texts.t("transit.cmd.contractUsage"));
                    return;
                }
                PlayerRef who = target.provided(ctx) ? target.get(ctx) : player;
                Ref<EntityStore> whoRef = who == null ? null : who.getReference();
                if (whoRef == null || !whoRef.isValid()) {
                    ctx.sendMessage(Texts.t("transit.cmd.contractNoPlayer"));
                    return;
                }
                World where = whoRef.getStore().getExternalData().getWorld();
                where.execute(() -> {
                    if (StationContracts.give(who, stationKind) == StationContracts.Given.FAILED) {
                        who.sendMessage(Texts.t("transit.contract.failed"));
                    }
                });
                ctx.sendMessage(Texts.t("transit.cmd.contractGiven").param("player", who.getUsername()));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station contract command failed");
                Telemetry.error("station.contract", e, null, null);
                ctx.sendMessage(Texts.t("transit.contract.failed"));
            }
        }
    }

    /** Finds a station by the name typed (spaces may be given as underscores), null if none. */
    private static TransitNetwork.Station stationNamed(World world, String wanted) {
        TransitNetwork.Station exact = TransitNetwork.get().byName(world.getName(), wanted);
        return exact != null || wanted.indexOf('_') < 0 ? exact : TransitNetwork.get().byName(world.getName(), wanted.replace('_', ' '));
    }

    /** Message key of the refusal to lock a station, null when it may be locked: the structure of a player station is always editable. Pure. */
    static String lockRefusal(TransitNetwork.Station s, boolean editable) {
        return !editable && s.owner != null ? "transit.cmd.lockPlayerStation" : null;
    }

    private static void setEditable(CommandContext ctx, World world, String wanted, boolean editable) {
        TransitNetwork.Station s = stationNamed(world, wanted);
        if (s == null) {
            ctx.sendMessage(Texts.t("transit.cmd.unknownStation").param("name", TransitNetwork.normalize(wanted)));
            return;
        }
        if (lockRefusal(s, editable) != null) {
            ctx.sendMessage(Texts.t(lockRefusal(s, editable)).param("name", s.name));
            return;
        }
        s.editable = editable;
        TransitNetwork.get().save();
        if (!editable && StationClearance.editable(s)) {
            ctx.sendMessage(Texts.t("transit.cmd.lockedSetting").param("name", s.name));
            return;
        }
        ctx.sendMessage(Texts.t(editable ? "transit.cmd.unlocked" : "transit.cmd.locked").param("name", s.name));
    }

    /** /orbishorizon station unlock (name): lets the structure of the station be edited, only its quay core stays locked. */
    static final class Unlock extends AbstractPlayerCommand {
        private final RequiredArg<String> name = withRequiredArg("name", Texts.cmd("station.unlock.name"), StationNameType.INSTANCE);

        Unlock() {
            super("unlock", Texts.cmd("station.unlock.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            setEditable(ctx, world, name.get(ctx), true);
        }
    }

    /** /orbishorizon station lock (name): locks the whole structure of the station again. */
    static final class Lock extends AbstractPlayerCommand {
        private final RequiredArg<String> name = withRequiredArg("name", Texts.cmd("station.lock.name"), StationNameType.INSTANCE);

        Lock() {
            super("lock", Texts.cmd("station.lock.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            setEditable(ctx, world, name.get(ctx), false);
        }
    }

    /** /orbishorizon station nearest: teleports the operator to the nearest station of the current world, of any kind. */
    static final class Nearest extends AbstractPlayerCommand {
        Nearest() {
            super("nearest", Texts.cmd("station.nearest.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            org.joml.Vector3d pos = posOf(store, ref);
            TransitNetwork.Station s = pos == null ? null : TransitNetwork.get().nearest(world.getName(), pos, Double.MAX_VALUE);
            if (s == null) {
                ctx.sendMessage(Texts.t("transit.cmd.nearestNone"));
                return;
            }
            org.joml.Vector3d at = StationGeometry.position(s);
            long dist = Math.round(Math.hypot(at.x - pos.x, at.z - pos.z));
            ApiStations.teleportTo(ctx, ref, world, StationSpec.teleportPoint(world, s),
                    Texts.t("transit.cmd.nearestDone").param("name", s.name).param("distance", dist), s.name);
        }
    }

    /**
     * After a station is removed, fills the holes of its ground layer (prefab y = STATION_GRASS_Y, the level of the stone path that
     * replaced the terrain, and of the soil cells removed with it) with the solid block most found around each hole: its 4
     * horizontal neighbours, the block below as a fallback. Repeated so that holes surrounded by holes are filled from the edge.
     * Returns the number of cells filled. Never throws.
     */
    static int fillGroundHoles(World world, StructureShape shape, org.joml.Vector3i origin,
                               com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation rotation, int soilLayerY) {
        int filled = 0;
        try {
            List<org.joml.Vector3i> holes = new ArrayList<>();
            for (StructureShape.Cell c : shape.cells()) {
                if (c.y() == soilLayerY) {
                    holes.add(c.rotated(rotation).add(origin));
                }
            }
            int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int pass = 0; pass < 32 && !holes.isEmpty(); pass++) {
                List<org.joml.Vector3i> left = new ArrayList<>();
                java.util.Map<org.joml.Vector3i, String> fills = new java.util.HashMap<>();
                for (org.joml.Vector3i p : holes) {
                    if (solidId(world, p.x, p.y, p.z) != null) {
                        continue; // terrain kept by the soil rule, or already filled
                    }
                    java.util.Map<String, Integer> count = new java.util.HashMap<>();
                    for (int[] d : around) {
                        String id = solidId(world, p.x + d[0], p.y, p.z + d[2]);
                        if (id != null) count.merge(id, 1, Integer::sum);
                    }
                    String best = null;
                    for (java.util.Map.Entry<String, Integer> e : count.entrySet()) {
                        if (best == null || e.getValue() > count.get(best)) best = e.getKey();
                    }
                    if (best == null && pass >= 4) {
                        best = solidId(world, p.x, p.y - 1, p.z); // enclosed by holes for a while: the block below
                    }
                    if (best != null) fills.put(p, best);
                    else left.add(p);
                }
                if (fills.isEmpty() && left.size() == holes.size() && pass >= 4) {
                    break; // nothing around and nothing below: left as it is
                }
                for (java.util.Map.Entry<org.joml.Vector3i, String> e : fills.entrySet()) {
                    org.joml.Vector3i p = e.getKey();
                    SectionBlocks.setAt(world.getChunkStore(), p.x, p.y, p.z, e.getValue(), 4); // 4: without particles
                    filled++;
                }
                holes = left;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Station ground holes not filled");
        }
        return filled;
    }

    /** Id of the solid block at a cell (state prefix "*" removed), null if the cell is not solid or its chunk is not loaded. */
    private static String solidId(World world, int x, int y, int z) {
        com.hypixel.hytale.server.core.universe.world.accessor.SectionReader reader =
                new com.hypixel.hytale.server.core.universe.world.accessor.SectionReader(world.getChunkStore());
        if (!reader.hasStorageInMemory(x, y, z)) {
            return null;
        }
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bt = SectionBlocks.typeAt(reader, x, y, z);
        if (bt == null || bt.getMaterial() != com.hypixel.hytale.protocol.BlockMaterial.Solid || bt.getId() == null) {
            return null;
        }
        String id = bt.getId();
        return id.startsWith("*") ? null : id; // a state variant is not put back as such: skipped
    }

    /**
     * One-word string whose suggestions are the station names (spaces written as underscores). It was a greedy string
     * first: the client showed the names in the command list instead of the value list it shows for /give (one-word item argument),
     * cause supposed to be the greedy string. The client asks the server for the values
     * of an argument type (ArgValuesRequest, handled by GamePacketHandler with ArgumentType.suggest of the type registered under
     * its own suggestion id). Names of every world: the command sender's world is not known here. Same name and usage keys as the
     * game's greedy string.
     */
    static final class StationNameType extends SingleArgumentType<String> {
        static final StationNameType INSTANCE = new StationNameType();

        /** Tells the clients that the station names changed (they keep the values of an argument type in a cache). Never throws. */
        static void stationsChanged() {
            try {
                com.hypixel.hytale.server.core.command.system.CommandManager.get().broadcastArgCacheInvalidation(INSTANCE.getSuggestionTypeId());
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station list not sent back to players");
            }
        }

        /**
         * Number of values the client can ask for. The default (-1) means none: the client then never asked for the station names
         * and showed them elsewhere, where the item argument of /give (an asset type, which returns the size of its asset map) gets
         * a value list. At least 1, read when the command tree is built.
         */
        @Override
        public int getSuggestionValueCount() {
            return Math.max(1, TransitNetwork.get().allStations().size());
        }

        private StationNameType() {
            super("server.commands.parsing.argtype.string.name", "server.commands.parsing.argtype.string.usage", "Station_1");
        }

        @Override
        public String parse(String input, ParseResult result) {
            return input;
        }


        @Override
        public void suggest(CommandSender sender, String partial, int count, SuggestionResult out) {
            String p = partial == null ? "" : partial.trim().toLowerCase(java.util.Locale.ROOT);
            Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (TransitNetwork.Station st : TransitNetwork.get().allStations()) {
                names.add(st.name);
            }
            for (String n : names) {
                if (out.isFull()) {
                    out.markTruncated();
                    return;
                }
                String token = n.replace(' ', '_'); // one word: spaces as underscores, put back by Tp
                if (token.toLowerCase(java.util.Locale.ROOT).startsWith(p)) {
                    out.suggest(token);
                }
            }
        }
    }
}
