package com.keepkoolz.orbishorizon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory chart of the sea and fresh-water oceans, labelled on a grid of CHART_STEP blocks from the terrain probe.
 * Cells are stored by blocks of BLOCK x BLOCK cells. A fill works on copies of the blocks it touches and publishes
 * them all at the end, so a reader never sees a half-written block. Fills are serialised and never block readers.
 */
final class SeaChart {
    enum Water { LAND, SEA, FRESH }

    static final int CHART_STEP = 16;
    static final int CHART_RADIUS = 4096;
    static final int BLOCK = 64;
    static final int MIN_DEPTH = 2;

    private static final int RADIUS_CELLS = CHART_RADIUS / CHART_STEP;
    private static final int MAX_DEPTH = 127;
    private static final int MAX_LABEL = 0xFFFF;
    private static final byte UNKNOWN = 0;
    private static final byte KIND_LAND = 1;
    private static final byte KIND_SEA = 2;
    private static final byte KIND_FRESH = 3;
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class Block {
        final short[] labels;
        final byte[] depths;
        final byte[] kinds;

        Block() {
            labels = new short[BLOCK * BLOCK];
            depths = new byte[BLOCK * BLOCK];
            kinds = new byte[BLOCK * BLOCK];
        }

        Block(Block o) {
            labels = o.labels.clone();
            depths = o.depths.clone();
            kinds = o.kinds.clone();
        }
    }

    private final long seed;
    private final ConcurrentHashMap<Long, Block> blocks = new ConcurrentHashMap<>();
    /** Union-find parents indexed by label. Index 0 is unused. The smaller label is always the root. */
    private int[] parent = new int[64];
    private int labelCount;
    /** Serialises fills. Readers never take it. Union-find state is guarded by this object's own monitor. */
    private final Object fillLock = new Object();
    private volatile int fillReads;

    SeaChart(long seed) {
        this.seed = seed;
    }

    long seed() {
        return seed;
    }

    static int cellOf(int block) {
        return Math.floorDiv(block, CHART_STEP);
    }

    /** Kind of water at the centre of a cell. */
    static Water classify(TerrainProbe p, int cx, int cz) {
        int x = cx * CHART_STEP + CHART_STEP / 2;
        int z = cz * CHART_STEP + CHART_STEP / 2;
        int depth = SeaRules.SEA_LEVEL - p.height(x, z);
        if (depth < MIN_DEPTH) {
            return Water.LAND;
        }
        return SeaRules.isSea(p, x, z) ? Water.SEA : Water.FRESH;
    }

    private static long key(int bx, int bz) {
        return ((long) bx << 32) | (bz & 0xFFFFFFFFL);
    }

    private static int index(int cx, int cz) {
        return Math.floorMod(cz, BLOCK) * BLOCK + Math.floorMod(cx, BLOCK);
    }

    int fillReads() {
        return fillReads;
    }

    /** Canonical ocean number of a cell, 0 for land or an unknown cell. */
    int ocean(int cx, int cz) {
        Block b = blocks.get(key(Math.floorDiv(cx, BLOCK), Math.floorDiv(cz, BLOCK)));
        if (b == null) {
            return 0;
        }
        int label = b.labels[index(cx, cz)] & 0xFFFF;
        return label == 0 ? 0 : find(label);
    }

    int depth(int cx, int cz) {
        Block b = blocks.get(key(Math.floorDiv(cx, BLOCK), Math.floorDiv(cz, BLOCK)));
        return b == null ? 0 : b.depths[index(cx, cz)];
    }

    Water water(int cx, int cz) {
        Block b = blocks.get(key(Math.floorDiv(cx, BLOCK), Math.floorDiv(cz, BLOCK)));
        if (b == null) {
            return Water.LAND;
        }
        return switch (b.kinds[index(cx, cz)]) {
            case KIND_SEA -> Water.SEA;
            case KIND_FRESH -> Water.FRESH;
            default -> Water.LAND;
        };
    }

    private synchronized int find(int label) {
        int root = label;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[label] != root) {
            int next = parent[label];
            parent[label] = root;
            label = next;
        }
        return root;
    }

    private synchronized int newLabel() {
        if (labelCount >= MAX_LABEL) {
            return 0;
        }
        labelCount++;
        if (labelCount >= parent.length) {
            parent = Arrays.copyOf(parent, parent.length * 2);
        }
        parent[labelCount] = labelCount;
        return labelCount;
    }

    private synchronized void union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return;
        }
        if (ra < rb) {
            parent[rb] = ra;
        } else {
            parent[ra] = rb;
        }
    }

    /**
     * Labels the water connected to the cell holding block (x, z), up to CHART_RADIUS blocks away from it.
     * Returns the canonical ocean number, or 0 when the cell is not water.
     */
    int fill(TerrainProbe p, int x, int z) {
        return fill(p, x, z, RADIUS_CELLS);
    }

    /** Same as fill with the fill radius given in cells. */
    int fill(TerrainProbe p, int x, int z, int radiusCells) {
        synchronized (fillLock) {
            return fillLocked(p, x, z, radiusCells);
        }
    }

    private int fillLocked(TerrainProbe p, int x, int z, int radiusCells) {
        int sx = cellOf(x);
        int sz = cellOf(z);
        Map<Long, Block> work = new HashMap<>();
        int existing = labelAt(work, sx, sz);
        if (existing != 0) {
            return find(existing);
        }
        byte startKind = kindAt(work, sx, sz);
        if (startKind == UNKNOWN) {
            startKind = kindByte(readCell(p, work, sx, sz));
        }
        if (startKind == KIND_LAND) {
            publish(work);
            return 0;
        }
        int label = newLabel();
        if (label == 0) {
            return 0;
        }
        setLabel(work, sx, sz, label);
        long[] queue = new long[1024];
        int head = 0;
        int tail = 0;
        queue[tail++] = pack(sx, sz);
        while (head < tail) {
            if (Thread.currentThread().isInterrupted()) {
                return 0; // interrupted: nothing is published, the caller discards the result
            }
            long cell = queue[head++];
            int cx = (int) (cell >> 32);
            int cz = (int) cell;
            for (int[] d : NEIGHBOURS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (Math.abs(nx - sx) > radiusCells || Math.abs(nz - sz) > radiusCells) {
                    continue;
                }
                byte known = kindAt(work, nx, nz);
                if (known == UNKNOWN) {
                    known = kindByte(readCell(p, work, nx, nz));
                }
                if (known != startKind) {
                    continue;
                }
                int other = labelAt(work, nx, nz);
                if (other != 0) {
                    union(label, other);
                    continue;
                }
                setLabel(work, nx, nz, label);
                if (tail == queue.length) {
                    queue = Arrays.copyOf(queue, queue.length * 2);
                }
                queue[tail++] = pack(nx, nz);
            }
        }
        publish(work);
        return find(label);
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static byte kindByte(Water w) {
        return switch (w) {
            case SEA -> KIND_SEA;
            case FRESH -> KIND_FRESH;
            case LAND -> KIND_LAND;
        };
    }

    /** Reads a cell from the probe and records its kind and depth in the working copy. */
    private Water readCell(TerrainProbe p, Map<Long, Block> work, int cx, int cz) {
        fillReads++;
        Water w = classify(p, cx, cz);
        Block b = writable(work, cx, cz);
        int i = index(cx, cz);
        b.kinds[i] = kindByte(w);
        if (w != Water.LAND) {
            int h = p.height(cx * CHART_STEP + CHART_STEP / 2, cz * CHART_STEP + CHART_STEP / 2);
            b.depths[i] = (byte) Math.max(0, Math.min(MAX_DEPTH, SeaRules.SEA_LEVEL - h));
        }
        return w;
    }

    private void setLabel(Map<Long, Block> work, int cx, int cz, int label) {
        writable(work, cx, cz).labels[index(cx, cz)] = (short) label;
    }

    /** Working copy of the block holding the cell, made from the published block on first touch. */
    private Block writable(Map<Long, Block> work, int cx, int cz) {
        long k = key(Math.floorDiv(cx, BLOCK), Math.floorDiv(cz, BLOCK));
        Block b = work.get(k);
        if (b == null) {
            Block published = blocks.get(k);
            b = published == null ? new Block() : new Block(published);
            work.put(k, b);
        }
        return b;
    }

    private Block readable(Map<Long, Block> work, int cx, int cz) {
        long k = key(Math.floorDiv(cx, BLOCK), Math.floorDiv(cz, BLOCK));
        Block b = work.get(k);
        return b != null ? b : blocks.get(k);
    }

    private byte kindAt(Map<Long, Block> work, int cx, int cz) {
        Block b = readable(work, cx, cz);
        return b == null ? UNKNOWN : b.kinds[index(cx, cz)];
    }

    private int labelAt(Map<Long, Block> work, int cx, int cz) {
        Block b = readable(work, cx, cz);
        return b == null ? 0 : b.labels[index(cx, cz)] & 0xFFFF;
    }

    private void publish(Map<Long, Block> work) {
        blocks.putAll(work);
    }

    private static final int MAX_BLOCKS = 1 << 20;

    /**
     * Writes the union-find state and every block. Published blocks are never modified, so a snapshot of the
     * references taken while no fill runs is consistent and the bytes are written outside the lock.
     */
    void writeTo(DataOutputStream out) throws IOException {
        Map<Long, Block> snapshot;
        int[] roots;
        int count;
        synchronized (fillLock) {
            snapshot = new HashMap<>(blocks);
            synchronized (this) {
                count = labelCount;
                roots = Arrays.copyOf(parent, count + 1);
            }
        }
        out.writeInt(count);
        for (int i = 1; i <= count; i++) {
            out.writeInt(roots[i]);
        }
        out.writeInt(snapshot.size());
        for (Map.Entry<Long, Block> e : snapshot.entrySet()) {
            Block b = e.getValue();
            out.writeLong(e.getKey());
            for (short l : b.labels) {
                out.writeShort(l);
            }
            out.write(b.depths);
            out.write(b.kinds);
        }
    }

    /** Reads what writeTo wrote. Throws IOException on any inconsistency, never allocates from an unchecked length. */
    static SeaChart readFrom(DataInputStream in, long seed) throws IOException {
        SeaChart chart = new SeaChart(seed);
        int count = in.readInt();
        if (count < 0 || count > MAX_LABEL) {
            throw new IOException("label count " + count);
        }
        int[] roots = new int[Math.max(64, count + 1)];
        for (int i = 1; i <= count; i++) {
            int r = in.readInt();
            if (r < 1 || r > i) {
                throw new IOException("parent of " + i + " is " + r);
            }
            roots[i] = r;
        }
        int n = in.readInt();
        if (n < 0 || n > MAX_BLOCKS) {
            throw new IOException("block count " + n);
        }
        for (int k = 0; k < n; k++) {
            long key = in.readLong();
            Block b = new Block();
            for (int i = 0; i < b.labels.length; i++) {
                short l = in.readShort();
                if ((l & 0xFFFF) > count) {
                    throw new IOException("label " + (l & 0xFFFF));
                }
                b.labels[i] = l;
            }
            in.readFully(b.depths);
            in.readFully(b.kinds);
            for (byte kind : b.kinds) {
                if (kind < UNKNOWN || kind > KIND_FRESH) {
                    throw new IOException("kind " + kind);
                }
            }
            if (chart.blocks.put(key, b) != null) {
                throw new IOException("duplicate block");
            }
        }
        chart.parent = roots;
        chart.labelCount = count;
        return chart;
    }
}
