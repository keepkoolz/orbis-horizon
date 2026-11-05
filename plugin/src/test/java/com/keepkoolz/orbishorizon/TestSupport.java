package com.keepkoolz.orbishorizon;

import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;
import com.keepkoolz.orbishorizon.api.TerrainProbe;

import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared set-up of the tests. */
public final class TestSupport {
    private TestSupport() {
    }

    /** Installs the public API with the real services and a throwaway data folder, replacing any earlier state. */
    public static void installApi() throws IOException {
        OrbisHorizonApi.install(new OrbisHorizonApi.Services(new ApiStations(), new ApiStationKinds(), new ApiWorldAccess(),
                new ApiTerrain(), new ApiTelemetry(), Files.createTempDirectory("oh-api"), e -> { }));
    }

    /** Loads the real transit network from the folder, as the plugin does at start. */
    public static void loadNetwork(Path dir) {
        TransitNetwork.get().loadFailed = false;
        TransitNetwork.get().load(dir);
    }

    /** Adds a station without master and without owner to the real transit network. */
    public static void addStation(String name, String world, int x, int y, int z, String kind) {
        TransitNetwork.get().add(new TransitNetwork.Station(name, world, new Vector3i(x, y, z),
                com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation.None, null, null, new ArrayList<>(), kind));
    }

    /** The block positions of a prefab file of Server/Prefabs, in the prefab frame. */
    public static List<Vector3i> prefabCells(String file) throws IOException {
        var root = org.bson.BsonDocument.parse(Files.readString(Path.of("Server/Prefabs", file)));
        List<Vector3i> cells = new ArrayList<>();
        for (var e : root.getArray("blocks")) {
            var b = e.asDocument();
            cells.add(new Vector3i(b.getInt32("x").getValue(), b.getInt32("y").getValue(), b.getInt32("z").getValue()));
        }
        return cells;
    }

    /** Fake terrain from a character map: one cell is 16 blocks, x grows along a row, z grows down the rows. */
    public static class GridProbe implements TerrainProbe {
        private final String[] rows;

        public GridProbe(String... rows) {
            this.rows = rows;
        }

        private char cell(int x, int z) {
            int r = Math.max(0, Math.min(rows.length - 1, Math.floorDiv(z, 16)));
            String row = rows[r];
            int c = Math.max(0, Math.min(row.length() - 1, Math.floorDiv(x, 16)));
            return row.charAt(c);
        }

        @Override
        public int height(int x, int z) {
            return cell(x, z) == '#' ? 120 : 100;
        }

        @Override
        public String zone(int x, int z) {
            return switch (cell(x, z)) {
                case '~' -> "Oceans";
                case 's' -> "Zone1_Shore";
                default -> "Zone1_Tier1";
            };
        }

        @Override
        public String biome(int x, int z) {
            return switch (cell(x, z)) {
                case 'l' -> "Lake";
                case 'r' -> "River";
                case 's' -> "Tile.Beach";
                default -> "";
            };
        }
    }

    /** Fake terrain for the sea chart: one cell is 16 blocks. # land, ~ sea, comma shallow sea (depth 1), 3 shallow sea (depth 3), l lake, r river. */
    public static class ChartGrid implements TerrainProbe {
        private final String[] rows;

        public ChartGrid(String... rows) {
            this.rows = rows;
        }

        private char cell(int x, int z) {
            int r = Math.max(0, Math.min(rows.length - 1, Math.floorDiv(z, 16)));
            String row = rows[r];
            int c = Math.max(0, Math.min(row.length() - 1, Math.floorDiv(x, 16)));
            return row.charAt(c);
        }

        @Override
        public int height(int x, int z) {
            return switch (cell(x, z)) {
                case '#' -> 120;
                case ',' -> 113;
                case '3' -> 111;
                default -> 100;
            };
        }

        @Override
        public String zone(int x, int z) {
            char c = cell(x, z);
            return c == '~' || c == ',' || c == '3' ? "Oceans" : "Zone1_Tier1";
        }

        @Override
        public String biome(int x, int z) {
            return switch (cell(x, z)) {
                case '~', ',', '3' -> "Ocean";
                case 'l' -> "Lake_Temperate";
                case 'r' -> "River_Temperate";
                default -> "";
            };
        }
    }

    /** Fake terrain recording the heights asked for: land everywhere, or sea (zone Oceans) from x seaFromX on. */
    public static class CountingProbe implements TerrainProbe {
        public final int seaFromX;
        public int heightCalls;
        public int minHeightX = Integer.MAX_VALUE;

        public CountingProbe(int seaFromX) {
            this.seaFromX = seaFromX;
        }

        @Override
        public int height(int x, int z) {
            heightCalls++;
            minHeightX = Math.min(minHeightX, x);
            return x >= seaFromX ? 100 : 120;
        }

        @Override
        public String zone(int x, int z) {
            return x >= seaFromX ? "Oceans" : "Zone1_Tier1";
        }

        @Override
        public String biome(int x, int z) {
            return "";
        }
    }
}
