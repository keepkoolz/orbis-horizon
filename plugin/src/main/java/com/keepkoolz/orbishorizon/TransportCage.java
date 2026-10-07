package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Cage of the animal transport balloon (T73): descent and ascent block by block, opening and closing of the side.
 *
 * State (BalloonRegistry.Entry): descent in blocks (0 to Deployables.TRANSPORT_MAX_DESCENT) and open side. The movement in
 * progress is not saved: after a restart the cage stays where it was.
 *
 * The cage is made of blocks. A step removes the cage blocks (and the chain under the basket), without particles, and puts them
 * back one cell lower or higher, then lengthens or shortens the chain column: the cage blocks are read as they are in the world
 * (identifier, rotation, filler), so the structure's rotation is respected. The open side is the 9 bars of the gate (TRANSPORT_GATE),
 * removed to open and put back to close. cells() is the only function that tells which cells of a state are expected: the
 * recognition, the lock, the removal and the placed-structure tests all go through it (BalloonRegistry.shapeOf).
 *
 * Hooks of the animal capture (T74, CageAnimals): Listener, called at each step, at closing and at opening.
 */
final class TransportCage {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Ratio given to a recognised structure that is in the registry in its registered state: it wins against any other type
     * measured at the same burner (the small balloon's shape matches about 90 % in a transport balloon).
     */
    static final double REGISTERED_RATIO = 2.0;

    /** Time between two steps: about 4 blocks per second. */
    private static final long STEP_MS = 250;
    private static final int MAX = Deployables.TRANSPORT_MAX_DESCENT;
    private static final int SET_BLOCK_NO_PARTICLES = 4;
    private static final String CHAIN_BLOCK = "Deco_Iron_Chain_Small";
    private static final String BARS_BLOCK = "Deco_Iron_Bars";
    /** Back-wall bar used as the template of the gate bars: same prefab rotation (none) as the gate bars. */
    private static final int[] GATE_TEMPLATE = {1, 2, -4};

    /**
     * Hook for the capture of animals (T74, implemented by CageAnimals). All methods are called on the world thread,
     * after the change of blocks.
     */
    interface Listener {
        /** The cage has just moved from descent from to descent to (blocks), side as in the entry. */
        default void onStep(World world, BalloonRegistry.Entry entry, int from, int to) {
        }

        /** The side has just been closed: the cage captures the animals it contains. Returns the result (null if nothing is handled). */
        default CageAnimals.CaptureResult onClosed(World world, BalloonRegistry.Entry entry) {
            return null;
        }

        /** The side has just been opened: the cage releases its animals. */
        default void onOpened(World world, BalloonRegistry.Entry entry) {
        }
    }

    /** The listener (T74: CageAnimals). */
    static volatile Listener listener = CageAnimals.LISTENER;

    /** A movement in progress: dir 1 = down, -1 = up. */
    private static final class Move {
        final World world;
        final Vector3i origin;
        final Rotation rotation;
        final int dir;
        final PlayerRef who;
        long nextMs;

        Move(World world, Vector3i origin, Rotation rotation, int dir, PlayerRef who) {
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.dir = dir;
            this.who = who;
        }
    }

    private static final Map<String, Move> MOVES = new ConcurrentHashMap<>();
    private static final Map<Integer, StructureShape> SHAPES = new ConcurrentHashMap<>();

    private TransportCage() {
    }

    static boolean isTransport(Deployables.Kind kind) {
        return kind == Deployables.BALLOON_TRANSPORT;
    }

    private static String key(World world, Vector3i origin, Rotation rotation) {
        return world.getName() + ":" + origin.x + "," + origin.y + "," + origin.z + ":" + rotation;
    }

    // ------------------------------------------------------------------ expected cells

    private static boolean inCage(int x, int y, int z) {
        int[] a = Deployables.TRANSPORT_CAGE_MIN;
        int[] b = Deployables.TRANSPORT_CAGE_MAX;
        return x >= a[0] && x <= b[0] && y >= a[1] && y <= b[1] && z >= a[2] && z <= b[2];
    }

    /** A cell of the gate (side that opens) in the raised state, prefab frame. */
    private static boolean isGate(int x, int y, int z) {
        int[] a = Deployables.TRANSPORT_GATE_MIN;
        int[] b = Deployables.TRANSPORT_GATE_MAX;
        return x >= a[0] && x <= b[0] && y >= a[1] && y <= b[1] && z >= a[2] && z <= b[2];
    }

    /** A cell of the chain column of the prefab (raised state): the three links between the cage and the basket floor. */
    private static boolean isChain(StructureShape.Cell c) {
        int[] col = Deployables.TRANSPORT_CHAIN_COLUMN;
        return CHAIN_BLOCK.equals(c.baseName()) && c.x() == col[0] && c.z() == col[2]
                && c.y() >= col[1] && c.y() <= Deployables.TRANSPORT_CHAIN_TOP_Y;
    }

    /**
     * Cells expected for the transport balloon with its cage lowered by d blocks and its side open or closed, prefab frame,
     * before rotation: the cage cells (TRANSPORT_CAGE box) shifted by -d in y, without the 9 gate bars if open, the chain column
     * from y = 5 - d up to the basket floor, the rest of the prefab unchanged. d = 0 and closed gives the prefab's own cells.
     */
    static List<StructureShape.Cell> cells(StructureShape base, int d, boolean open) {
        List<StructureShape.Cell> out = new ArrayList<>(base.cells().size() + d);
        StructureShape.Cell chain = null;
        for (StructureShape.Cell c : base.cells()) {
            if (isChain(c)) {
                chain = chain == null ? c : chain;
                continue;
            }
            if (inCage(c.x(), c.y(), c.z())) {
                if (open && isGate(c.x(), c.y(), c.z())) {
                    continue;
                }
                out.add(d == 0 ? c : new StructureShape.Cell(c.x(), c.y() - d, c.z(), c.name(), c.filler()));
            } else {
                out.add(c);
            }
        }
        if (chain != null) {
            int[] col = Deployables.TRANSPORT_CHAIN_COLUMN;
            for (int y = col[1] - d; y <= Deployables.TRANSPORT_CHAIN_TOP_Y; y++) {
                out.add(new StructureShape.Cell(col[0], y, col[2], chain.name(), chain.filler()));
            }
        }
        return out;
    }

    /** The shape of the transport balloon in this state (cached per state). */
    static StructureShape shapeFor(StructureShape base, int d, boolean open) {
        int state = d * 2 + (open ? 1 : 0);
        if (state == 0) {
            return base;
        }
        if (base != Deployables.BALLOON_TRANSPORT.shapeOrNull()) {
            return base.withCells(cells(base, d, open));
        }
        return SHAPES.computeIfAbsent(state, k -> base.withCells(cells(base, d, open)));
    }

    /** True if a transport balloon is registered with this origin and rotation (its entry decides the type and the state). */
    static boolean registered(World world, Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        return isTransport(kind) && BalloonRegistry.get(world, origin, rotation, kind.id) != null;
    }

    /** The shape to compare with the world for a structure recognised at this origin: the registered state, otherwise the prefab's. */
    static StructureShape shapeAt(World world, Deployables.Kind kind, StructureShape base, Vector3i origin, Rotation rotation) {
        if (!isTransport(kind)) {
            return base;
        }
        BalloonRegistry.Entry e = BalloonRegistry.get(world, origin, rotation, kind.id);
        return e == null ? base : shapeFor(base, e.descent(), e.open());
    }

    /**
     * Placement lock (T27) beyond the type's volume: the whole path of the cage (x -2..2, z -8..-4, from its lowest cell
     * up to the burner row) and the opening of the gate, so that nothing can be built where the cage goes or in its open side.
     */
    static boolean placeForbidden(Deployables.Kind kind, BalloonRegistry.Entry e, int x, int y, int z) {
        if (!isTransport(kind)) {
            return false;
        }
        int[] a = Deployables.TRANSPORT_CAGE_MIN;
        int[] b = Deployables.TRANSPORT_CAGE_MAX;
        return x >= a[0] && x <= b[0] && z >= a[2] && z <= b[2] && y >= a[1] - e.descent() && y <= a[1] + 12;
    }

    // ------------------------------------------------------------------ take-off and dry descent

    /** Error message if take-off must be refused (cage lowered, opened or moving), null if all is well. Nothing is changed. */
    static Message takeOffRefusal(World world, Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        if (!isTransport(kind)) {
            return null;
        }
        BalloonRegistry.Entry e = BalloonRegistry.get(world, origin, rotation, kind.id);
        if (e != null && (e.descent() > 0 || e.open() || MOVES.containsKey(key(world, origin, rotation)))) {
            return Texts.t("error.cageNotReady");
        }
        return null;
    }

    /**
     * Before the automatic descent of a balloon that ran out of fuel (T20): the cage is raised in one go and its side closed, so
     * that the placed prefab can be taken off as blocks and landed as usual. If the cage cannot be raised (path blocked),
     * returns false and nothing else is done: the descent starts again at the next try. If the side cannot be put back
     * (a block in the opening), it stays open and the descent starts all the same: the flight model always has a closed cage, the
     * landing pastes the prefab with its side closed. An animal in the opening does not prevent the closing.
     */
    static boolean raiseForDescent(World world, Deployables.Kind kind, Vector3i origin, Rotation rotation) {
        if (!isTransport(kind)) {
            return true;
        }
        BalloonRegistry.Entry e = BalloonRegistry.get(world, origin, rotation, kind.id);
        if (e == null || (e.descent() == 0 && !e.open())) {
            return true;
        }
        MOVES.remove(key(world, origin, rotation));
        if (e.descent() > 0) {
            Outcome o = apply(world, e, e.descent(), e.open(), 0, e.open(), null);
            if (o != Outcome.OK) {
                LOGGER.at(Level.WARNING).log("Cage de la montgolfière de transport en %s : remontée avant la descente à sec impossible (%s)", origin, o);
                return false;
            }
            BalloonRegistry.Entry up = BalloonRegistry.setCage(world, origin, rotation, kind.id, 0, e.open(), true);
            if (up != null) {
                listener.onStep(world, up, e.descent(), 0);
                e = up;
            }
        }
        if (e.open()) {
            Outcome o = apply(world, e, 0, true, 0, false, null);
            if (o == Outcome.OK) {
                BalloonRegistry.Entry closed = BalloonRegistry.setCage(world, origin, rotation, kind.id, 0, false, true);
                if (closed != null) {
                    listener.onClosed(world, closed);
                }
            } else {
                LOGGER.at(Level.WARNING).log("Cage de la montgolfière de transport en %s : pan resté ouvert avant la descente à sec (%s)", origin, o);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ levers

    /**
     * Cage lever. Cage immobile and up: lowers it (refused if the side is open or if the balloon is not in the air). Cage
     * immobile and down: raises it. Cage moving: stops it where it is. Returns the message for the pilot.
     */
    static Message pullCageLever(World world, BalloonRegistry.Entry e, PlayerRef who) {
        Deployables.Kind kind = Deployables.get(e.kind());
        StructureShape base = kind == null ? null : kind.shapeOrNull();
        if (kind == null || !isTransport(kind) || base == null) {
            return Texts.t("cage.unknown");
        }
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        String k = key(world, origin, e.rotation());
        if (MOVES.remove(k) != null) {
            BalloonRegistry.flushCage();
            return Texts.t("cage.stopped");
        }
        int dir;
        if (e.descent() == 0) {
            if (e.open()) {
                return Texts.t("cage.closeFirst");
            }
            if (!BalloonManager.isInAir(world, base, origin, e.rotation())) {
                return Texts.t("cage.needAir");
            }
            dir = 1;
        } else {
            dir = -1;
        }
        Move m = new Move(world, origin, e.rotation(), dir, who);
        m.nextMs = System.currentTimeMillis();
        MOVES.put(k, m);
        return Texts.t(dir > 0 ? "cage.lowering" : "cage.raising");
    }

    /**
     * Gate lever: opens or closes the side, cage immobile only. Opening only when the cage floor rests on the ground (T74). Closing is
     * refused if a player or a non-player character is in the opening, or if a solid block (or a liquid) occupies one of its cells.
     */
    static Message pullGateLever(World world, BalloonRegistry.Entry e) {
        Deployables.Kind kind = Deployables.get(e.kind());
        if (kind == null || !isTransport(kind) || kind.shapeOrNull() == null) {
            return Texts.t("cage.unknown");
        }
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        if (MOVES.containsKey(key(world, origin, e.rotation()))) {
            return Texts.t("cage.gateMoving");
        }
        if (!e.open()) {
            // T74: the side opens when the cage floor rests on the ground, raised (landed balloon) or lowered. A cage hanging in the
            // air (raised in flight, or stopped at the bottom without touching the ground) would drop the animal: refused.
            if (!groundUnder(world, e)) {
                return Texts.t("cage.gateNoGround");
            }
            Outcome o = apply(world, e, e.descent(), false, e.descent(), true, null);
            if (o != Outcome.OK) {
                return outcomeMessage(o);
            }
            BalloonRegistry.Entry opened = BalloonRegistry.setCage(world, origin, e.rotation(), kind.id, e.descent(), true, true);
            if (opened != null) {
                listener.onOpened(world, opened);
            }
            return Texts.t("cage.gateOpened");
        }
        if (occupied(world, e)) {
            return Texts.t("cage.gateBlocked");
        }
        Outcome o = apply(world, e, e.descent(), true, e.descent(), false, null);
        if (o == Outcome.BLOCKED) {
            return Texts.t("cage.gateBlocked");
        }
        if (o != Outcome.OK) {
            return outcomeMessage(o);
        }
        BalloonRegistry.Entry closed = BalloonRegistry.setCage(world, origin, e.rotation(), kind.id, e.descent(), false, true);
        CageAnimals.CaptureResult r = closed == null ? null : listener.onClosed(world, closed);
        if (r == null) {
            return Texts.t("cage.gateClosed");
        }
        if (r.captured() == 0) {
            return Texts.t("cage.gateClosedNone");
        }
        return Texts.t(r.skipped() > 0 ? "cage.gateClosedLimit" : "cage.gateClosedCaptured").param("count", r.captured());
    }

    private static Message outcomeMessage(Outcome o) {
        return switch (o) {
            case UNLOADED -> Texts.t("cage.unloaded");
            case DAMAGED -> Texts.t("cage.damaged");
            case BLOCKED -> Texts.t("cage.blocked");
            case OK -> Texts.t("cage.stopped");
        };
    }

    /**
     * True if the cage floor rests on the ground (T74): at least one of the cells right under the floor of the cage footprint
     * (x -2..2, z -8..-4) is a solid block or a liquid (same rule as T51). The floor is at y = cage minimum - descent.
     */
    static boolean groundUnder(World world, BalloonRegistry.Entry e) {
        int[] a = Deployables.TRANSPORT_CAGE_MIN;
        int[] b = Deployables.TRANSPORT_CAGE_MAX;
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        for (int x = a[0]; x <= b[0]; x++) {
            for (int z = a[2]; z <= b[2]; z++) {
                Vector3i p = wp(e.rotation(), origin, x, a[1] - e.descent() - 1, z);
                if (chunkAt(world, p.x, p.z) != null && BalloonManager.blocksBalloon(world, p.x, p.y, p.z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if a player or a non-player character is in the box of the gate cells (the opening) of the entry. */
    private static boolean occupied(World world, BalloonRegistry.Entry e) {
        int[] a = Deployables.TRANSPORT_GATE_MIN;
        int[] b = Deployables.TRANSPORT_GATE_MAX;
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        Vector3i p = e.rotation().rotateYaw(new Vector3i(a[0], a[1] - e.descent(), a[2]), new Vector3i()).add(origin);
        Vector3i q = e.rotation().rotateYaw(new Vector3i(b[0], b[1] - e.descent(), b[2]), new Vector3i()).add(origin);
        Vector3d min = new Vector3d(Math.min(p.x, q.x), Math.min(p.y, q.y), Math.min(p.z, q.z));
        Vector3d max = new Vector3d(Math.max(p.x, q.x) + 1, Math.max(p.y, q.y) + 1, Math.max(p.z, q.z) + 1);
        Store<EntityStore> store = world.getEntityStore().getStore();
        List<Ref<EntityStore>> inBox = TargetUtil.getAllEntitiesInBox(min, max, store);
        for (Ref<EntityStore> ref : new ArrayList<>(inBox)) {
            if (ref != null && ref.isValid()
                    && (store.getComponent(ref, Player.getComponentType()) != null
                    || store.getComponent(ref, NPCEntity.getComponentType()) != null)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ movement

    /** Called on every tick of the world (BalloonFlightSystem): the steps due, then the registry write if it is due. */
    static void tick(World world) {
        if (!MOVES.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Move> me : MOVES.entrySet()) {
                Move m = me.getValue();
                if (m.world != world || now < m.nextMs) {
                    continue;
                }
                m.nextMs = Math.max(m.nextMs + STEP_MS, now);
                if (!step(me.getKey(), m)) {
                    MOVES.remove(me.getKey());
                    BalloonRegistry.flushCage();
                }
            }
        }
        BalloonRegistry.flushIfDue();
    }

    /** One step of a movement. Returns false when the movement ends (end of travel, obstacle, entry gone). */
    private static boolean step(String key, Move m) {
        BalloonRegistry.Entry e = BalloonRegistry.get(m.world, m.origin, m.rotation, Deployables.TRANSPORT_KIND);
        if (e == null) {
            return false;
        }
        int from = e.descent();
        int to = from + m.dir;
        if (to < 0 || to > MAX) {
            return false;
        }
        Vector3i blockedAt = new Vector3i();
        Outcome o = apply(m.world, e, from, e.open(), to, e.open(), blockedAt);
        if (o != Outcome.OK) {
            if (o == Outcome.DAMAGED) {
                LOGGER.at(Level.WARNING).log("Cage de la montgolfière de transport en %s : bloc absent, mouvement arrêté", m.origin);
            } else if (o == Outcome.BLOCKED) {
                LOGGER.at(Level.INFO).log("Cage de la montgolfière de transport en %s : arrêt à %d bloc(s), case (%d, %d, %d) prise",
                        m.origin, from, blockedAt.x, blockedAt.y, blockedAt.z);
            }
            tell(m, outcomeMessage(o));
            return false;
        }
        BalloonRegistry.Entry next = BalloonRegistry.setCage(m.world, m.origin, m.rotation, Deployables.TRANSPORT_KIND, to, e.open(), false);
        if (next != null) {
            listener.onStep(m.world, next, from, to);
        }
        if (to == MAX || to == 0) {
            tell(m, Texts.t(to == 0 ? "cage.top" : "cage.bottom"));
            return false;
        }
        return true;
    }

    private static void tell(Move m, Message message) {
        if (m.who != null) {
            m.who.sendMessage(message);
        }
    }

    private enum Outcome { OK, BLOCKED, UNLOADED, DAMAGED }

    /** Block read from the world, put back as it is. */
    private record Blk(int id, BlockType type, int rotation, int filler) {
    }

    private static Vector3i wp(Rotation rotation, Vector3i origin, int x, int y, int z) {
        return rotation.rotateYaw(new Vector3i(x, y, z), new Vector3i()).add(origin);
    }

    private static WorldChunk chunkAt(World world, int x, int z) {
        return world.getChunkIfLoaded(ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(x), ChunkUtil.chunkCoordinate(z)));
    }

    /** The block of the world at this position if it is the expected one, null otherwise (chunk not loaded or other block). */
    private static Blk capture(World world, Vector3i p, String blockName) {
        WorldChunk chunk = chunkAt(world, p.x, p.z);
        BlockType bt = world.getBlockType(p.x, p.y, p.z);
        if (chunk == null || bt == null || bt.getId() == null) {
            return null;
        }
        String id = bt.getId().startsWith("*") ? bt.getId().substring(1) : bt.getId();
        if (!id.startsWith(blockName)) {
            return null;
        }
        return new Blk(chunk.getBlock(p.x, p.y, p.z), bt, chunk.getRotationIndex(p.x, p.y, p.z), chunk.getFiller(p.x, p.y, p.z));
    }

    /**
     * Changes the blocks of the cage from the state (dOld, openOld) to the state (dNew, openNew), all in the same tick: reads
     * the blocks of the old state, checks the new cells (chunks loaded, nothing solid or liquid that is not the cage's own), removes the old
     * cells that are not in the new state, then puts the new ones. Nothing is changed unless OK is returned. blockedAt (nullable)
     * receives the first occupied cell.
     */
    private static Outcome apply(World world, BalloonRegistry.Entry e, int dOld, boolean openOld, int dNew, boolean openNew,
                                 Vector3i blockedAt) {
        Deployables.Kind kind = Deployables.get(e.kind());
        StructureShape base = kind == null ? null : kind.shapeOrNull();
        if (base == null) {
            return Outcome.DAMAGED;
        }
        Rotation rot = e.rotation();
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());

        // Chunks of the cage and its chain (the cage always stays in the same columns).
        int[] a = Deployables.TRANSPORT_CAGE_MIN;
        int[] b = Deployables.TRANSPORT_CAGE_MAX;
        for (int x : new int[]{a[0], b[0]}) {
            for (int z : new int[]{a[2], b[2]}) {
                Vector3i p = wp(rot, origin, x, 0, z);
                if (chunkAt(world, p.x, p.z) == null) {
                    return Outcome.UNLOADED;
                }
            }
        }

        Set<Vector3i> oldSet = new HashSet<>();
        Set<Vector3i> keep = new HashSet<>();
        Map<Vector3i, Blk> newMap = new LinkedHashMap<>();
        Blk gateTemplate = null;
        for (StructureShape.Cell c : base.cells()) {
            if (isChain(c) || !inCage(c.x(), c.y(), c.z())) {
                continue;
            }
            boolean gate = isGate(c.x(), c.y(), c.z());
            Vector3i po = wp(rot, origin, c.x(), c.y() - dOld, c.z());
            Vector3i pn = wp(rot, origin, c.x(), c.y() - dNew, c.z());
            Blk data = null;
            if (!(gate && openOld)) {
                data = capture(world, po, c.baseName());
                if (data == null) {
                    return Outcome.DAMAGED;
                }
                oldSet.add(po);
            }
            if (gate && openNew) {
                continue;
            }
            if (data == null) {
                if (gateTemplate == null) {
                    gateTemplate = capture(world, wp(rot, origin, GATE_TEMPLATE[0], GATE_TEMPLATE[1] - dOld, GATE_TEMPLATE[2]), BARS_BLOCK);
                    if (gateTemplate == null) {
                        return Outcome.DAMAGED;
                    }
                }
                data = gateTemplate;
            }
            if (po.equals(pn) && !(gate && openOld)) {
                keep.add(po);
            } else {
                newMap.put(pn, data);
            }
        }
        // Chain column: from y = 5 - d to the basket floor. The cells already there stay.
        int[] col = Deployables.TRANSPORT_CHAIN_COLUMN;
        Blk chain = capture(world, wp(rot, origin, col[0], Deployables.TRANSPORT_CHAIN_TOP_Y, col[2]), CHAIN_BLOCK);
        if (chain == null) {
            return Outcome.DAMAGED;
        }
        for (int y = col[1] - dOld; y <= Deployables.TRANSPORT_CHAIN_TOP_Y; y++) {
            oldSet.add(wp(rot, origin, col[0], y, col[2]));
        }
        for (int y = col[1] - dNew; y <= Deployables.TRANSPORT_CHAIN_TOP_Y; y++) {
            Vector3i p = wp(rot, origin, col[0], y, col[2]);
            if (oldSet.contains(p) && y >= col[1] - dOld && !newMap.containsKey(p)) {
                keep.add(p);
            } else {
                newMap.put(p, chain);
            }
        }

        // Obstacles: a new cell that is not an old cell of the structure must not be solid or liquid.
        for (Vector3i p : newMap.keySet()) {
            if (oldSet.contains(p) || keep.contains(p)) {
                continue;
            }
            if (chunkAt(world, p.x, p.z) == null) {
                return Outcome.UNLOADED;
            }
            if (BalloonManager.blocksBalloon(world, p.x, p.y, p.z)) {
                if (blockedAt != null) {
                    blockedAt.set(p);
                }
                return Outcome.BLOCKED;
            }
        }

        for (Vector3i p : oldSet) {
            if (!newMap.containsKey(p) && !keep.contains(p)) {
                world.setBlock(p.x, p.y, p.z, BlockType.EMPTY_KEY, SET_BLOCK_NO_PARTICLES);
            }
        }
        for (Map.Entry<Vector3i, Blk> me : newMap.entrySet()) {
            Vector3i p = me.getKey();
            Blk d = me.getValue();
            WorldChunk chunk = chunkAt(world, p.x, p.z);
            chunk.setBlock(p.x, p.y, p.z, d.id(), d.type(), d.rotation(), d.filler(), SET_BLOCK_NO_PARTICLES);
        }
        return Outcome.OK;
    }

    // ------------------------------------------------------------------ diagnostic

    /** State of the transport balloon nearest to this point (48 blocks), in English: diagnostic of /orbishorizon balloon cage. */
    static String describe(World world, Vector3d at) {
        BalloonRegistry.Entry best = null;
        double bestDist = 48;
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKind(world, Deployables.TRANSPORT_KIND)) {
            double d = new Vector3d(e.x() + 0.5, e.y() + 0.5, e.z() + 0.5).distance(at);
            if (d <= bestDist) {
                best = e;
                bestDist = d;
            }
        }
        if (best == null) {
            return "No placed transport balloon within 48 blocks.";
        }
        Move m = MOVES.get(key(world, new Vector3i(best.x(), best.y(), best.z()), best.rotation()));
        return "Transport balloon at " + best.x() + ", " + best.y() + ", " + best.z() + " (" + best.rotation() + "): cage descent "
                + best.descent() + "/" + MAX + ", side " + (best.open() ? "open" : "closed") + ", movement "
                + (m == null ? "none" : m.dir > 0 ? "down" : "up")
                + ", floor on ground " + groundUnder(world, best)
                + ", captured animals " + CageAnimals.describe(world, best.animals());
    }
}
