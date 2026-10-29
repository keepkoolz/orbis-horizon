package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** One gzip file per world holding a SeaChart: magic, version, seed, then the chart itself. */
final class SeaChartFile {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int MAGIC = 0x4F485343; // "OHSC"
    private static final int VERSION = 1;

    private SeaChartFile() {
    }

    /** Writes to a temporary file, then moves it over the target. */
    static void write(Path file, SeaChart chart) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(chart.seed());
            chart.writeTo(out);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The saved chart, or null when the file is absent, unreadable or made for another seed. Never throws. */
    static SeaChart read(Path file, long seed) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                    throw new IOException("bad header");
                }
                if (in.readLong() != seed) {
                    return null;
                }
                return SeaChart.readFrom(in, seed);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Carte de l'océan illisible (" + file.getFileName() + "), elle sera recalculée");
            return null;
        }
    }
}
