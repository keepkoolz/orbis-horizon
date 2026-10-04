package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shape of the balloon, read once from the prefab: list of blocks with
 * their position relative to the prefab origin.
 */
public final class BalloonShape {

    /**
     * Block used as a marker to find a placed balloon: the mod's burner, since
     * the removal of the brazier (T16). The prefab brazier no longer exists.
     */
    public static final String ANCHOR_BLOCK = "Hotair_Balloon_Burner";
    /** Burner (fuel tank, flame in the "On" state), same as the marker (T12, T16). */
    public static final String BURNER_BLOCK = "Hotair_Balloon_Burner";
    /**
     * Basket seat (T21): the 4 stools of the prefab, at the corners of the basket. On the ground they work
     * as in the game (Block_Seat, nothing was changed), in flight they receive the passengers.
     */
    public static final String SEAT_BLOCK = "Furniture_Crude_Stool";
    /**
     * Basket ladder (T23, T24). In flight it is folded: only the top cell of each column
     * of ladders (attached to the basket) exists. The other cells are "unfolded": placed with the prefab,
     * but not in the flight model, nor in the collisions, nor in the room tests.
     */
    public static final String LADDER_BLOCK = "Furniture_Kweebec_Ladder";

    public record Cell(int x, int y, int z, String name) {
        /** Name without "*" prefix or state suffix (e.g. "_State_Definitions_OpenDoorOut"). */
        public String baseName() {
            String n = name.startsWith("*") ? name.substring(1) : name;
            int i = n.indexOf("_State_Definitions");
            return i >= 0 ? n.substring(0, i) : n;
        }

        public Vector3i rotated(Rotation rotation) {
            return rotation.rotateYaw(new Vector3i(x, y, z), new Vector3i());
        }
    }

    private final String prefabPath;
    private final List<Cell> cells;
    private final Cell anchor;
    private final Cell burner;
    private final List<Cell> seats;
    private final List<Cell> flightCells;
    private final List<Cell> unfoldedLadder;
    private final int flightBottomGap;
    /** T27: the prefab cells by position (prefab frame), and the box that contains them. */
    private final java.util.Map<Long, Cell> byPosition = new java.util.HashMap<>();
    private final int[] bounds = new int[6];

    private BalloonShape(String prefabPath, List<Cell> cells, Cell anchor, Cell burner, List<Cell> seats) {
        this.prefabPath = prefabPath;
        this.cells = cells;
        // T24: a ladder cell is unfolded if another ladder is just above it.
        java.util.Set<java.util.List<Integer>> ladders = new java.util.HashSet<>();
        for (Cell c : cells) {
            if (LADDER_BLOCK.equals(c.baseName())) {
                ladders.add(java.util.List.of(c.x(), c.y(), c.z()));
            }
        }
        List<Cell> flight = new ArrayList<>(cells.size());
        List<Cell> unfolded = new ArrayList<>();
        int minAll = Integer.MAX_VALUE;
        int minFlight = Integer.MAX_VALUE;
        for (Cell c : cells) {
            minAll = Math.min(minAll, c.y());
            if (LADDER_BLOCK.equals(c.baseName()) && ladders.contains(java.util.List.of(c.x(), c.y() + 1, c.z()))) {
                unfolded.add(c);
            } else {
                flight.add(c);
                minFlight = Math.min(minFlight, c.y());
            }
        }
        this.flightCells = Collections.unmodifiableList(flight);
        this.unfoldedLadder = Collections.unmodifiableList(unfolded);
        this.flightBottomGap = cells.isEmpty() ? 0 : minFlight - minAll;
        this.anchor = anchor;
        this.burner = burner;
        this.seats = seats;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            byPosition.put(pack(c.x(), c.y(), c.z()), c);
            b[0] = Math.min(b[0], c.x());
            b[1] = Math.min(b[1], c.y());
            b[2] = Math.min(b[2], c.z());
            b[3] = Math.max(b[3], c.x());
            b[4] = Math.max(b[4], c.y());
            b[5] = Math.max(b[5], c.z());
        }
        System.arraycopy(b, 0, bounds, 0, 6);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (long) (z + 512);
    }

    public static BalloonShape load(String prefabPath) throws IOException {
        return load(prefabPath, ANCHOR_BLOCK, true);
    }

    /**
     * Reads a prefab (T43). anchorBlock is the marker block (unique). Without requireBurner (the tent), the prefab
     * does not need a burner and burner() returns null.
     */
    public static BalloonShape load(String prefabPath, String anchorBlock, boolean requireBurner) throws IOException {
        Path path = PrefabStore.get().findAssetPrefabPath(prefabPath);
        if (path == null) {
            throw new IOException("Prefab not found: " + prefabPath);
        }
        BsonDocument doc = BsonDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
        BsonArray blocks = doc.getArray("blocks");
        List<Cell> cells = new ArrayList<>(blocks.size());
        Cell anchor = null;
        Cell burner = null;
        List<Cell> seats = new ArrayList<>();
        for (BsonValue v : blocks) {
            BsonDocument b = v.asDocument();
            Cell c = new Cell(b.getNumber("x").intValue(), b.getNumber("y").intValue(),
                    b.getNumber("z").intValue(), b.getString("name").getValue());
            cells.add(c);
            if (anchorBlock.equals(c.baseName())) {
                anchor = c;
            }
            if (BURNER_BLOCK.equals(c.baseName())) {
                burner = c;
            }
            if (SEAT_BLOCK.equals(c.baseName())) {
                seats.add(c);
            }
        }
        // Stable order of the seats (T21): by z then by x, in the prefab frame.
        seats.sort(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x));
        if (anchor == null) {
            throw new IOException("The prefab has no anchor block " + anchorBlock);
        }
        if (requireBurner && burner == null) {
            throw new IOException("The prefab has no burner " + BURNER_BLOCK);
        }
        return new BalloonShape(prefabPath, Collections.unmodifiableList(cells), anchor, burner,
                Collections.unmodifiableList(seats));
    }

    public String prefabPath() {
        return prefabPath;
    }

    public List<Cell> cells() {
        return cells;
    }

    /**
     * Cells of the prefab in flight (T24): all except the unfolded ladder cells. This is the list for collisions,
     * room tests (landing, double jump, resume), recognition at take-off and the
     * "in the air" test of T20. The full prefab (`cells()`) is used for placement, block removal, chests
     * and the chunk computation.
     */
    public List<Cell> flightCells() {
        return flightCells;
    }

    /** Unfolded ladder cells (absent in flight, placed with the prefab unless the terrain occupies them). */
    public List<Cell> unfoldedLadder() {
        return unfoldedLadder;
    }

    /** Height, in cells, between the bottom of the full prefab and the bottom of the flight cells (the length of the unfolded ladder). */
    public int flightBottomGap() {
        return flightBottomGap;
    }

    public Cell anchor() {
        return anchor;
    }

    /** The burner, null for a prefab loaded without requireBurner (T43). */
    public Cell burner() {
        return burner;
    }

    /** The seats (stools) of the basket, in the prefab frame (T21). The passenger limit is their number. */
    public List<Cell> seats() {
        return seats;
    }

    /** The prefab cell at this position (prefab frame, before rotation), null if the prefab has no block there (T27). */
    public Cell cellAt(int x, int y, int z) {
        return byPosition.get(pack(x, y, z));
    }

    /** True if the position (prefab frame) is in the box that contains all the prefab blocks (T27). */
    public boolean inBounds(int x, int y, int z) {
        return x >= bounds[0] && y >= bounds[1] && z >= bounds[2] && x <= bounds[3] && y <= bounds[4] && z <= bounds[5];
    }
}
