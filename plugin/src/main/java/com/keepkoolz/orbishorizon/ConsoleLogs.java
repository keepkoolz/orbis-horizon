package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Sets the minimal level of the mod's logs shown in the console, on each of its loggers.
 * HytaleLogger.forEnclosingClass() names a logger after the simple name of the class, so the loggers are found
 * from the class names of the plugin JAR. The backend treats a logger at OFF as not loggable at any level.
 */
final class ConsoleLogs {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PACKAGE_PATH = "com/keepkoolz/orbishorizon/";

    /** Choices of the configuration page, in display order. INFO is the level the mod logs at by default. */
    enum Threshold {
        ALL(Level.ALL),
        INFO(Level.INFO),
        WARNING(Level.WARNING),
        SEVERE(Level.SEVERE),
        NONE(Level.OFF);

        final Level level;

        Threshold(Level level) {
            this.level = level;
        }

        /** The choice named {@code name}, or INFO when the name is null or unknown. */
        static Threshold parse(String name) {
            if (name != null) {
                for (Threshold t : values()) {
                    if (t.name().equals(name)) return t;
                }
            }
            return INFO;
        }
    }

    private static volatile HytaleLogger pluginLogger;

    private ConsoleLogs() {
    }

    /** Registers the logger of the plugin itself, so that it follows the same level as the others. */
    static void init(HytaleLogger plugin) {
        pluginLogger = plugin;
    }

    /** Sets the level of every logger of the mod. Never throws. */
    static synchronized void apply(Threshold threshold) {
        try {
            for (String name : loggerNames()) {
                try {
                    HytaleLogger.get(name).setLevel(threshold.level);
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("Logger %s: level not changed", name);
                }
            }
            HytaleLogger own = pluginLogger;
            if (own != null) {
                own.setLevel(threshold.level);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Console logs: setting not applied");
            Telemetry.error("consoleLogs", e, null, null);
        }
    }

    /** Simple names of the classes of the mod package (inner classes keep their $), read from the plugin JAR or class folder. */
    private static Set<String> loggerNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            Path src = Path.of(OrbisHorizonPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> entries = new ArrayList<>();
            if (Files.isRegularFile(src)) {
                try (JarFile jar = new JarFile(src.toFile())) {
                    jar.stream().forEach(e -> entries.add(e.getName()));
                }
            } else if (Files.isDirectory(src)) {
                Path root = src.resolve(PACKAGE_PATH);
                if (Files.isDirectory(root)) {
                    try (Stream<Path> walk = Files.walk(root)) {
                        walk.filter(Files::isRegularFile)
                                .forEach(p -> entries.add(src.relativize(p).toString().replace('\\', '/')));
                    }
                }
            }
            names.addAll(namesFromEntries(entries));
        } catch (IOException | URISyntaxException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Mod classes not listed, only the plugin logger is set");
        }
        return names;
    }

    /** Logger names for the .class entries of the mod package: simple name, $ kept, lambda classes skipped. */
    static Set<String> namesFromEntries(Collection<String> entries) {
        Set<String> names = new LinkedHashSet<>();
        for (String entry : entries) {
            if (!entry.startsWith(PACKAGE_PATH) || !entry.endsWith(".class")) continue;
            String simple = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - ".class".length());
            if (simple.isEmpty() || simple.contains("$$") || simple.equals("package-info") || simple.equals("module-info")) continue;
            names.add(simple);
        }
        return names;
    }
}
