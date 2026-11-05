package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.keepkoolz.orbishorizon.api.StationKinds;
import com.keepkoolz.orbishorizon.api.Terrain;
import com.keepkoolz.orbishorizon.api.TerrainProbe;

import java.util.List;

/** The station sub-commands of the generation: /orbishorizon station worldgen and planned (ops). */
final class WorldgenCommands {
    private WorldgenCommands() {
    }

    private static org.joml.Vector3d posOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        return t == null ? null : new org.joml.Vector3d(t.getPosition());
    }

    /**
     * /orbishorizon station planned: teleports the operator above the nearest generated station that is not built (planned or
     * abandoned) and gives its kind, state and distance.
     */
    static final class Planned extends AbstractPlayerCommand {
        Planned() {
            super("planned", WorldgenTexts.cmd("station.planned.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            if (!WorldgenSettings.enabled()) {
                ctx.sendMessage(WorldgenTexts.t("transit.cmd.plannedWorldgenOff"));
                return;
            }
            org.joml.Vector3d pos = posOf(store, ref);
            GeneratedStations.Entry e = pos == null ? null
                    : nearestUnbuilt(GeneratedStations.get().entries(world.getName()), pos.x, pos.z);
            if (e == null) {
                ctx.sendMessage(WorldgenTexts.t("transit.cmd.plannedNone"));
                return;
            }
            TerrainProbe probe = WorldgenApi.terrain().probeOf(world);
            double y = probe != null ? Math.max(probe.height(e.x, e.z), Terrain.SEA_LEVEL) + 3 : pos.y;
            long dist = Math.round(Math.hypot(e.x - pos.x, e.z - pos.z));
            String kind = StationKinds.BOAT.equals(e.kind) ? "boat" : "balloon";
            WorldgenApi.stations().teleport(ctx, ref, world, new org.joml.Vector3d(e.x + 0.5, y, e.z + 0.5),
                    WorldgenTexts.t("transit.cmd.plannedDone." + kind).param("state", WorldgenTexts.t("transit.cmd.state." + e.state.name().toLowerCase(java.util.Locale.ROOT))).param("distance", dist)
                            .param("x", e.x).param("z", e.z), "(" + e.x + ", " + e.z + ")");
        }
    }

    /** Nearest generated entry still planned or abandoned, horizontally, or null. */
    static GeneratedStations.Entry nearestUnbuilt(List<GeneratedStations.Entry> entries, double x, double z) {
        GeneratedStations.Entry best = null;
        double bestD = Double.MAX_VALUE;
        for (GeneratedStations.Entry e : entries) {
            if (e.state != GeneratedStations.State.PLANNED && e.state != GeneratedStations.State.ABANDONED) {
                continue;
            }
            double d = (e.x - x) * (e.x - x) + (e.z - z) * (e.z - z);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** /orbishorizon station worldgen on|off|show: turns the generated stations on or off for the whole server. */
    static final class Worldgen extends AbstractPlayerCommand {
        private final RequiredArg<String> mode = withRequiredArg("mode", WorldgenTexts.cmd("station.worldgen.mode"), ArgTypes.STRING);

        Worldgen() {
            super("worldgen", WorldgenTexts.cmd("station.worldgen.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
            addUsageVariant(new OldTerrain());
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String m = mode.get(ctx).trim().toLowerCase(java.util.Locale.ROOT);
            switch (m) {
                case "on", "off" -> {
                    boolean on = m.equals("on");
                    WorldgenSettings.setEnabled(on);
                    ctx.sendMessage(WorldgenTexts.t(on ? "transit.worldgen.on" : "transit.worldgen.off"));
                }
                case "show" -> ctx.sendMessage(WorldgenTexts.t("transit.worldgen.show")
                        .param("state", WorldgenSettings.enabled() ? "on" : "off"));
                default -> ctx.sendMessage(WorldgenTexts.t("transit.worldgen.usage"));
            }
        }
    }

    /** /orbishorizon station worldgen oldterrain on|off|show: lets the generated stations also be built on terrain explored before. */
    static final class OldTerrain extends AbstractPlayerCommand {
        private final RequiredArg<String> topic = withRequiredArg("topic", WorldgenTexts.cmd("station.worldgen.oldterrain.topic"), ArgTypes.STRING);
        private final RequiredArg<String> mode = withRequiredArg("mode", WorldgenTexts.cmd("station.worldgen.mode"), ArgTypes.STRING);

        OldTerrain() {
            super(WorldgenTexts.cmd("station.worldgen.oldterrain.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            if (!topic.get(ctx).trim().equalsIgnoreCase("oldterrain")) {
                ctx.sendMessage(WorldgenTexts.t("transit.worldgen.oldTerrain.usage"));
                return;
            }
            String m = mode.get(ctx).trim().toLowerCase(java.util.Locale.ROOT);
            switch (m) {
                case "on", "off" -> {
                    boolean on = m.equals("on");
                    WorldgenSettings.setOldTerrain(on);
                    ctx.sendMessage(WorldgenTexts.t(on ? "transit.worldgen.oldTerrain.on" : "transit.worldgen.oldTerrain.off"));
                }
                case "show" -> ctx.sendMessage(WorldgenTexts.t("transit.worldgen.oldTerrain.show")
                        .param("state", WorldgenSettings.oldTerrain() ? "on" : "off"));
                default -> ctx.sendMessage(WorldgenTexts.t("transit.worldgen.oldTerrain.usage"));
            }
        }
    }
}
