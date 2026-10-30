package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.interface_.BlockChange;
import com.hypixel.hytale.protocol.packets.interface_.FluidChange;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.protocol.packets.player.HideTriggerVolumePastePrefabPreview;
import com.hypixel.hytale.protocol.packets.player.ShowTriggerVolumePastePrefabPreview;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Matrix4d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Deployment preview: /orbishorizon preview prefab|box|clear <type>. Shows, to the calling player only,
 * the structure of a type (Deployables id: balloon, balloon_basic, balloon_transport, tent, tent_big, airship, station) at the
 * place the crate of this type would put it (DeployPlacement; the held crate shows it automatically, HeldCratePreview): either the builder tools' paste preview (ShowTriggerVolumePastePrefabPreview, block by
 * block, built from the prefab's BlockSelection like the game) or a translucent box of its bounds (DisplayDebug, Cube). Op only.
 */
final class PreviewCommand extends AbstractCommandCollection {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    PreviewCommand() {
        super("preview", Texts.cmd("preview.desc"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        addSubCommand(new Show("prefab", true));
        addSubCommand(new Show("box", false));
        addSubCommand(new Clear());
        addSubCommand(new PlaceWheel());
    }

    private static final class Show extends AbstractPlayerCommand {
        private final boolean prefab;
        private final RequiredArg<String> type = withRequiredArg("type", Texts.cmd("preview.type"), ArgTypes.STRING);

        Show(String name, boolean prefab) {
            super(name, Texts.cmd("preview." + name));
            this.prefab = prefab;
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            try {
                Deployables.Kind kind = Deployables.get(type.get(ctx));
                StructureShape shape = kind == null ? null : kind.shapeOrNull();
                if (shape == null) {
                    ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Unknown type " + type.get(ctx)));
                    return;
                }
                Vector3i target = TargetUtil.getTargetBlock(ref, 64, store);
                TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                if (target == null || t == null) {
                    ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Look at a block within 64 blocks"));
                    return;
                }
                // the same placement as the crate of this type (shared helper), debugging tool kept.
                DeployPlacement.Crate crate = DeployPlacement.CRATES.values().stream()
                        .filter(c -> c.prefabPath().equals(kind.prefabPath)).findFirst().orElse(null);
                DeployPlacement.Placement placement = crate != null
                        ? DeployPlacement.compute(target, t.getPosition(), t.getRotation().y, crate.offset(), crate.rotationOffset())
                        : DeployPlacement.compute(target, t.getPosition(), t.getRotation().y, new Vector3i(0, 1, 0), Rotation.None);
                Rotation rotation = placement.rotation();
                Vector3i origin = placement.origin();
                if (prefab) {
                    // The game's own encoding: the prefab read as a BlockSelection (as the prefab list and the trigger volumes do),
                    // turned around Y like the paste, turned into its network form (EditorBlocksChange) with block rotations and fillers.
                    java.nio.file.Path path = com.hypixel.hytale.server.core.prefab.PrefabStore.get().findAssetPrefabPath(kind.prefabPath);
                    com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection sel = path == null ? null
                            : com.hypixel.hytale.server.core.prefab.PrefabStore.get().getPrefab(path);
                    if (sel == null) {
                        ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Prefab not found: " + kind.prefabPath));
                        return;
                    }
                    sel = sel.cloneSelection();
                    if (rotation.getDegrees() != 0) {
                        sel = sel.rotate(com.hypixel.hytale.math.Axis.Y, rotation.getDegrees());
                    }
                    com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange pkt = sel.toPacket();
                    BlockChange[] blocks = pkt.blocksChange != null ? pkt.blocksChange : new BlockChange[0];
                    FluidChange[] fluids = pkt.fluidsChange != null ? pkt.fluidsChange : new FluidChange[0];
                    player.getPacketHandler().writeNoCache(new ShowTriggerVolumePastePrefabPreview(
                            new Vector3f(origin.x, origin.y, origin.z), blocks, fluids, new ClipboardEntityChange[0], 0, 0));
                    ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Prefab preview sent: " + blocks.length + " blocks, origin "
                            + origin.x + " " + origin.y + " " + origin.z + ", " + rotation + " (" + rotation.getDegrees() + " deg)"));
                } else {
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (StructureShape.Cell c : shape.cells()) {
                        Vector3i p = c.rotated(rotation).add(origin);
                        minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
                        maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
                    }
                    double sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
                    Matrix4d m = new Matrix4d().translate(minX + sx / 2, minY + sy / 2, minZ + sz / 2).scale(sx, sy, sz);
                    float[] data = com.hypixel.hytale.math.matrix.Matrix4dUtil.asFloatData(m);
                    player.getPacketHandler().writeNoCache(new DisplayDebug(DebugShape.Cube, data, new Vector3f(0.2f, 0.9f, 0.3f),
                            30f, (byte) 0, null, 0.35f));
                    ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Box preview sent: " + (int) sx + " x " + (int) sy
                            + " x " + (int) sz + " for 30 s"));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Preview not sent");
                ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Preview failed: " + e));
            }
        }
    }

    /**
     * Paddle wheel test: /orbishorizon preview wheel. A player cannot place Steamboat_Paddle_Wheel by hand
     * (its 2 x 5 x 7 hitbox must be free, it goes 1 block below the target cell). This places it 6 blocks in front of the player,
     * 4 blocks up, turned like the player, through BlockOperations.setBlock, which writes the filler cells itself, as a prefab will.
     */
    private static final class PlaceWheel extends AbstractPlayerCommand {
        PlaceWheel() {
            super("wheel", Texts.cmd("preview.desc"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null) {
                ctx.sendMessage(Texts.t("error.noPosition"));
                return;
            }
            String key = "Steamboat_Paddle_Wheel";
            int id = BlockType.getAssetMap().getIndex(key);
            BlockType type = id < 0 ? null : BlockType.getAssetMap().getAsset(id);
            if (type == null) {
                ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Unknown block " + key));
                return;
            }
            double yaw = t.getRotation().y;
            Rotation facing = Rotation.closestOfDegrees((float) Math.toDegrees(yaw));
            int x = (int) Math.floor(t.getPosition().x - Math.sin(yaw) * 6);
            int y = (int) Math.floor(t.getPosition().y) + 4;
            int z = (int) Math.floor(t.getPosition().z - Math.cos(yaw) * 6);
            boolean ok = SectionBlocks.setAt(world.getChunkStore(), x, y, z, id, type, facing.ordinal(), 0, 0);
            SectionReader reader = new SectionReader(world.getChunkStore());
            int cells = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockType b = SectionBlocks.typeAt(reader, x + dx, y + dy, z + dz);
                        if (b != null && key.equals(b.getId())) {
                            cells++;
                        }
                    }
                }
            }
            String msg = "Wheel at " + x + ", " + y + ", " + z + " rotation " + facing + ": set " + ok + ", " + cells
                    + " wheel cell(s) found (70 expected)";
            LOGGER.at(Level.INFO).log("Test wheel: %s", msg);
            ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw(msg));
        }
    }

    private static final class Clear extends AbstractPlayerCommand {
        Clear() {
            super("clear", Texts.cmd("preview.clear"));
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            player.getPacketHandler().writeNoCache(new HideTriggerVolumePastePrefabPreview());
            HeldCratePreview.forget(player.getUuid());
            player.getPacketHandler().writeNoCache(new com.hypixel.hytale.protocol.packets.player.ClearDebugShapes());
            ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw("Previews cleared"));
        }
    }
}
