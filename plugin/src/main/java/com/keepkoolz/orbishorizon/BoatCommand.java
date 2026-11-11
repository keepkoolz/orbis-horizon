package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

/**
 * /orbishorizon boat: commands of the player's own boats. The group is open to everyone (requireNoPermission, so that the open
 * sub-commands are reachable), each sub-command sets its own rights.
 */
public final class BoatCommand extends AbstractCommandCollection {

    public BoatCommand() {
        super("boat", Texts.cmd("boat.desc"));
        requireNoPermission();
        addSubCommand(new Pack());
    }

    /** /orbishorizon boat pack: packs the nearest skiff within 8 blocks. Owner or op (checked in Java). */
    static final class Pack extends AbstractPlayerCommand {
        Pack() {
            super("pack", Texts.cmd("boat.pack.desc"));
            requireNoPermission();
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            try {
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                BalloonRegistry.Entry e = t != null ? nearestSkiff(world, t.getPosition(), 8) : null;
                if (e == null) {
                    ctx.sendMessage(Texts.t("skiff.notASkiff"));
                    return;
                }
                SkiffPacker.pack(store, ref, world, e, true);
            } catch (RuntimeException e) {
                Telemetry.error("boat.skiff.pack", e, Deployables.STEAMBOAT_SKIFF_KIND, null);
                ctx.sendMessage(Texts.t("skiff.notASkiff"));
            }
        }
    }

    /** The registered skiff whose origin is the closest to this position within the radius, null if none. */
    static BalloonRegistry.Entry nearestSkiff(World world, Vector3d at, double radius) {
        BalloonRegistry.Entry best = null;
        double bestD = radius * radius;
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.STEAMBOAT_SKIFF_KIND)) {
            double dx = e.x() + 0.5 - at.x, dy = e.y() - at.y, dz = e.z() + 0.5 - at.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }
}
