package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/** /orbishorizon station ...: transit network administration (ops). Task 4b: only "recipe on|off|show". */
public final class StationCommand extends AbstractCommandCollection {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public StationCommand() {
        super("station", Texts.cmd("station.desc"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        addSubCommand(new Recipe());
        addSubCommand(new ListStations());
        addSubCommand(new Debug());
        addSubCommand(new Despawn());
        addSubCommand(new Tp());
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
                    .append("\n  dock origin ").append(StationQueue.dockOrigin(s)).append(", pad y ").append(StationQueue.padY(s))
                    .append("\n  master point ").append(StationMaster.masterPoint(s));
            if (Deployables.BOAT_STATION_KIND.equals(s.kind)) {
                b.append(BoatRoutes.describe(s));
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
        }
    }

    /** /orbishorizon station despawn: removes the nearest station (48 blocks) and gives its crate back to the operator. */
    static final class Despawn extends AbstractPlayerCommand {
        Despawn() {
            super("despawn", Texts.cmd("station.despawn.desc"));
            addAliases("remove");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            org.joml.Vector3d here = posOf(store, ref);
            TransitNetwork.Station s = here == null ? null
                    : TransitNetwork.get().nearest(world.getName(), here, TransitConstants.REMOVE_RADIUS);
            if (s == null) {
                ctx.sendMessage(Texts.t("transit.cmd.despawnNone").param("radius", (int) TransitConstants.REMOVE_RADIUS));
                return;
            }
            StationSpec spec = StationSpec.of(s.kind);
            // The registry shape follows the wood of a boat station (renamed blocks), the prefab shape does not.
            StructureShape shape;
            try {
                BalloonRegistry.Entry entry = BalloonRegistry.get(world, s.origin, s.rotation, s.kind);
                StructureShape reg = entry == null ? null : BalloonRegistry.shapeOf(entry);
                shape = reg != null ? reg : spec.structure().shape();
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Forme de la station %s illisible", s.name);
                ctx.sendMessage(Texts.t("transit.cmd.despawnFailed"));
                return;
            }
            // 0. Players on the pad, in the hut or in the docked balloon lose their floor: unseated now, put on the ground after the removal.
            java.util.Map<Ref<EntityStore>, org.joml.Vector3d> stranded = new java.util.LinkedHashMap<>();
            try {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (StructureShape.Cell c : shape.cells()) {
                    org.joml.Vector3i p = c.rotated(s.rotation).add(s.origin);
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
                org.joml.Vector3i dock = StationQueue.dockOrigin(s);
                if (Deployables.STATION_KIND.equals(s.kind) && BalloonRegistry.get(world, dock, s.rotation, Deployables.BALLOON_PUBLIC.id) != null) {
                    for (BalloonManager.Occupant o : BalloonManager.get().nacelleOccupants(world, store, Deployables.BALLOON_PUBLIC, dock, s.rotation)) {
                        org.joml.Vector3d pp = posOf(store, o.ref());
                        if (pp != null) stranded.putIfAbsent(o.ref(), pp);
                    }
                }
                for (Ref<EntityStore> sr : stranded.keySet()) MountUtil.unseat(store, sr);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Joueurs de la station %s non repérés", s.name);
            }
            // 1. Station blocks first: it refuses (chunks not loaded) before anything else is touched. Contents dropped, lock entry removed.
            StructureRemoval.Result r;
            try {
                r = StructureRemoval.remove(store, world, spec.structure(), shape, s.origin, s.rotation);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Station %s non retirée", s.name);
                ctx.sendMessage(Texts.t("transit.cmd.despawnFailed"));
                return;
            }
            if (r.error() != null) {
                ctx.sendMessage(r.error());
                return;
            }
            int filled;
            try {
                filled = spec.soilLayerY() == null ? 0
                        : fillGroundHoles(world, shape, s.origin, s.rotation, spec.soilLayerY());
            } catch (RuntimeException e) {
                filled = 0;
            }
            // 2. Master NPC (also clears its reservation, rewire stamp and pending recovery), 3. network entry.
            // Refund first: the payments must be written to refunds.json before the station leaves stations.json.
            TransitFare.refundRemovedStation(s);
            StationMaster.remove(store, s);
            TransitNetwork.get().remove(s);
            TransitNetwork.get().save();
            BoatRoutes.stationRemoved(world, s);
            StationNameType.stationsChanged();
            // 4. Quay (QUAYS entry), approaching or departing flight, docked balloon, flights heading here, other queues. World thread.
            StationQueue.onStationRemoved(world, s);
            BoatQuay.onStationRemoved(world, s); // boat stations only: shuttles, moored shuttle, other queues
            for (java.util.Map.Entry<Ref<EntityStore>, org.joml.Vector3d> e : stranded.entrySet()) {
                if (e.getKey().isValid()) BalloonManager.putOnGround(store, world, e.getKey(), e.getValue());
            }
            boolean given = BalloonManager.get().giveCrate(store, ref, here, spec.crateId());
            LOGGER.at(Level.INFO).log("Station « %s » retirée par %s : %d bloc(s), %d objet(s) lâchés, %d trou(s) du sol rebouché(s)",
                    s.name, player.getUsername(), r.removed(), r.items(), filled);
            ctx.sendMessage(Texts.t(given ? "transit.cmd.despawnDone" : "transit.cmd.despawnDoneFull").param("name", s.name));
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
            org.joml.Vector3d target = StationSpec.teleportPoint(s);
            int bx = (int) Math.floor(target.x), by = (int) Math.floor(target.y), bz = (int) Math.floor(target.z);
            SectionBlocks.loadSections(world.getChunkStore(), bx, by - 2, bz, bx, by + 2, bz).whenComplete((c, t) -> {
                try {
                    world.execute(() -> {
                        if (!ref.isValid()) return;
                        Store<EntityStore> st = ref.getStore();
                        TransformComponent tr = st.getComponent(ref, TransformComponent.getComponentType());
                        Rotation3f look = tr != null ? new Rotation3f(tr.getRotation()) : new Rotation3f();
                        MountUtil.unseat(st, ref);
                        st.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
                        ctx.sendMessage(Texts.t("transit.cmd.tpDone").param("name", s.name));
                    });
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Téléportation vers « %s » impossible", s.name);
                }
            });
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
            LOGGER.at(Level.WARNING).withCause(e).log("Trous du sol de la station non rebouchés");
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
                LOGGER.at(Level.WARNING).withCause(e).log("Liste des stations non renvoyée aux joueurs");
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

    /** Learns or forgets the crate recipe for a player who is arriving in a world (holder not in the store yet). */
    static void syncOnArrival(Holder<EntityStore> holder) {
        Player p = holder.getComponent(Player.getComponentType());
        if (p == null) return;
        Set<String> known = new HashSet<>(p.getPlayerConfigData().getKnownRecipes());
        boolean changed = TransitSettings.recipeEnabled() ? known.addAll(StationSpec.CRATE_IDS) : known.removeAll(StationSpec.CRATE_IDS);
        if (changed) p.getPlayerConfigData().setKnownRecipes(known);
    }

    static final class Recipe extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", Texts.cmd("station.recipe.mode"), ArgTypes.STRING);

        Recipe() {
            super("recipe", Texts.cmd("station.recipe.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx).trim().toLowerCase(java.util.Locale.ROOT);
            switch (m) {
                case "on", "off" -> {
                    boolean on = m.equals("on");
                    TransitSettings.setRecipeEnabled(on);
                    for (World w : Universe.get().getWorlds().values()) {
                        w.execute(() -> {
                            for (PlayerRef pr : w.getPlayerRefs()) {
                                Ref<EntityStore> r = pr.getReference();
                                if (r == null || !r.isValid()) continue;
                                try {
                                    for (String id : StationSpec.CRATE_IDS) {
                                        if (on) CraftingPlugin.learnRecipe(r, id, r.getStore());
                                        else CraftingPlugin.forgetRecipe(r, id, r.getStore());
                                    }
                                } catch (RuntimeException e) {
                                    LOGGER.at(Level.WARNING).withCause(e).log("Recette de la station non synchronisée pour %s", pr.getUsername());
                                }
                            }
                        });
                    }
                    ctx.sendMessage(Texts.t(on ? "transit.recipe.on" : "transit.recipe.off"));
                }
                case "show" -> ctx.sendMessage(Texts.t("transit.recipe.show")
                        .param("state", TransitSettings.recipeEnabled() ? "on" : "off"));
                default -> ctx.sendMessage(Texts.t("transit.recipe.usage"));
            }
        }
    }
}
