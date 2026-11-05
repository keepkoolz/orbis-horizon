package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Moves the files of the generation from the data folder of Orbis Horizon to the one of the extension. Never overwrites a file. */
final class WorldgenMigration {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private WorldgenMigration() {
    }

    /** The outcome of a migration: files finished and files that failed (an error, not a conflict). */
    record Result(int migrated, int failed) {
    }

    /** Same as {@link #migrate} and returns only the number of files finished. */
    static int copyLegacyFiles(Path legacyDir, Path dir) {
        return migrate(legacyDir, dir).migrated();
    }

    /**
     * Copies generated-stations.json and every generated-chunks-*.bin of legacyDir to dir, then renames the original to
     * name.migrated. A file already in dir and identical only has its original renamed (an earlier run stopped before the rename).
     * A different file in dir leaves both untouched and is not a failure. An error on one file is logged, counted in failed and does
     * not stop the others, the next start tries again. Returns 0 and 0 when both folders are the same.
     */
    static Result migrate(Path legacyDir, Path dir) {
        if (legacyDir == null || dir == null) return new Result(0, 0);
        try {
            if (legacyDir.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize())) return new Result(0, 0);
            if (Files.exists(legacyDir) && Files.exists(dir) && legacyDir.toRealPath().equals(dir.toRealPath())) return new Result(0, 0);
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot compare the data folders for the migration");
            WorldgenApi.telemetry().error("worldgen", e, null, "migrate");
            return new Result(0, 1);
        }
        int done = 0;
        int failed = 0;
        List<String> names = new ArrayList<>();
        if (!candidates(legacyDir, names)) failed++;
        for (String name : names) {
            try {
                if (migrateFile(legacyDir.resolve(name), dir.resolve(name), name)) done++;
            } catch (IOException | RuntimeException e) {
                failed++;
                LOGGER.at(Level.WARNING).withCause(e).log("Cannot migrate %s to the extension data folder", name);
                WorldgenApi.telemetry().error("worldgen", e, null, "migrate");
            }
        }
        return new Result(done, failed);
    }

    /** The names of the files to migrate found in the folder: the registry and the chunk files, not their .old, .bad or .tmp copies. */
    private static boolean candidates(Path legacyDir, List<String> names) {
        boolean listed = true;
        if (!Files.exists(legacyDir)) {
            return true; // a new world: Orbis Horizon has not created its data folder yet, nothing to migrate
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacyDir)) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                boolean target = n.equals("generated-stations.json") || (n.startsWith("generated-chunks-") && n.endsWith(".bin"));
                if (target && Files.isRegularFile(p)) names.add(n);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot list the data folder for the generation files");
            WorldgenApi.telemetry().error("worldgen", e, null, "migrate");
            listed = false;
        }
        java.util.Collections.sort(names);
        return listed;
    }

    private static boolean migrateFile(Path from, Path to, String name) throws IOException {
        if (Files.exists(to)) {
            if (Files.isSameFile(from, to)) return false;
            if (!Files.isRegularFile(to) || Files.mismatch(from, to) != -1L) {
                LOGGER.at(Level.WARNING).log("%s exists in both data folders and differs, nothing migrated", name);
                return false;
            }
        } else {
            Files.createDirectories(to.getParent());
            Path tmp = to.resolveSibling(name + ".tmp");
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, to);
            }
        }
        Files.move(from, from.resolveSibling(name + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
