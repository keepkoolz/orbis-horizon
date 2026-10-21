package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.world.CancelParticleSystems;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lot 5 of the steamboat: moored ships smoke. The block under the "On" state (Airship_Exhaust_Pipe on the airship, Steamboat_Chimney_Top
 * on the steamboat) puffs smoke while the fuel tank holds fuel, driven by BurnerFlameSystem like the engine glow (T58). No sound.
 */
final class ShipSmoke {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final String PIPE_BLOCK = "Airship_Exhaust_Pipe";
    static final String CHIMNEY_BLOCK = "Steamboat_Chimney_Top";
    private static final String SMOKE_SYSTEM = "Airship_Exhaust_Smoke";
    private static final String ON_STATE = "On";
    private static final long LOG_INTERVAL_MS = 60_000;
    private static final Map<StructureShape, List<Vector3i>> CACHE = new ConcurrentHashMap<>();
    private static volatile long lastLogMs;

    private ShipSmoke() {
    }

    /** True for the block names that smoke when moored. */
    static boolean isSmokeBlock(String baseName) {
        return PIPE_BLOCK.equals(baseName) || CHIMNEY_BLOCK.equals(baseName);
    }

    /** Cells (prefab frame) of the smoking blocks of a ship shape, cached by shape. */
    static List<Vector3i> exhaustCells(StructureShape shape) {
        return CACHE.computeIfAbsent(shape, s -> {
            List<Vector3i> out = new ArrayList<>();
            for (StructureShape.Cell c : s.cells()) {
                if (isSmokeBlock(c.baseName())) {
                    out.add(new Vector3i(c.x(), c.y(), c.z()));
                }
            }
            return List.copyOf(out);
        });
    }

    /** On the world thread: lights or puts out the moored ship's smoking blocks around the fuel tank at tankPos. Never throws. */
    static void setLit(World world, Vector3i tankPos, boolean lit) {
        try {
            BalloonRegistry.Entry entry = BalloonRegistry.find(world, tankPos);
            if (entry == null) {
                return;
            }
            Deployables.Kind kind = Deployables.get(entry.kind());
            if (kind == null || kind.airship == null) {
                return;
            }
            StructureShape shape = BalloonRegistry.shapeOf(entry);
            if (shape == null) {
                return;
            }
            Vector3i origin = new Vector3i(entry.x(), entry.y(), entry.z());
            SectionReader reader = new SectionReader(world.getChunkStore());
            for (Vector3i local : exhaustCells(shape)) {
                Vector3i w = entry.rotation().rotateYaw(new Vector3i(local), new Vector3i()).add(origin);
                BlockType bt = SectionBlocks.typeAt(reader, w.x, w.y, w.z);
                if (bt == null || bt.getId() == null) {
                    continue;
                }
                String id = bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId();
                if (!id.startsWith(PIPE_BLOCK) && !id.startsWith(CHIMNEY_BLOCK)) {
                    continue; // still the game's pipe (ship posed before lot 5)
                }
                boolean on = ON_STATE.equals(bt.getCurrentInteractionState()) || id.endsWith("_State_Definitions_" + ON_STATE);
                if (on == lit) {
                    continue;
                }
                SectionBlocks.setStateAt(world.getChunkStore(), w.x, w.y, w.z, bt, lit ? ON_STATE : "default");
                if (!lit) {
                    cancelSmoke(world.getPlayerRefs(), w.x + 0.5, w.y + 0.5, w.z + 0.5);
                }
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastLogMs > LOG_INTERVAL_MS) {
                lastLogMs = now;
                LOGGER.at(Level.WARNING).log("Fumée du navire à quai : échec (%s)", e);
            }
            Telemetry.error("airship.land", e, null, null);
        }
    }

    /** Same precaution as AirshipEngines.cancelEmbers: the client could keep the smoke displayed. */
    private static void cancelSmoke(java.util.Collection<PlayerRef> players, double cx, double cy, double cz) {
        CancelParticleSystems packet = new CancelParticleSystems(new Position(cx - 2, cy - 2, cz - 2), new Position(cx + 2, cy + 3, cz + 2),
                new String[]{SMOKE_SYSTEM}, true);
        for (PlayerRef player : players) {
            player.getPacketHandler().writeNoCache(packet);
        }
    }
}
