package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.keepkoolz.orbishorizon.api.ConfigRow;
import com.keepkoolz.orbishorizon.api.Extension;
import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Calls from Orbis Horizon to the registered extension. Every call is inside a try/catch (a failure is logged and counted in the
 * telemetry), and without an extension every call does nothing. No call uses a world to log, so a null world is tolerated.
 */
final class Extensions {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile boolean started;
    /** Names of the worlds the extension was told about and that have not stopped since. */
    private static final Set<String> DELIVERED = ConcurrentHashMap.newKeySet();
    /** Keys of the rejected rows already logged. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Extensions() {
    }

    /** Clean state: the plugin has not finished starting. */
    static void reset() {
        started = false;
        DELIVERED.clear();
        REPORTED.clear();
    }

    /** True the first time a world name is claimed since its last release: the world start is delivered once. */
    static boolean claimDelivery(String worldName) {
        return DELIVERED.add(worldName);
    }

    static void releaseDelivery(String worldName) {
        DELIVERED.remove(worldName);
    }

    /** The name of a world, null for a null world, a null name or a failing read. */
    private static String nameOf(World world) {
        try {
            return world == null ? null : world.getName();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A world without a readable name (a null world in tests included) is never remembered, so it is always delivered. */
    private static boolean claim(World world) {
        String name = nameOf(world);
        return name == null || claimDelivery(name);
    }

    private static Extension extension() {
        try {
            return OrbisHorizonApi.get().extension();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    static boolean isRegistered() {
        return extension() != null;
    }

    /** The plugin finished starting: worlds already started are delivered to an extension that registers from now on. */
    static void markStarted() {
        started = true;
    }

    /** An extension registered. After the plugin has started, it receives onWorldStart for every world already started. */
    static void onRegistered(Extension e, Supplier<Collection<World>> startedWorlds) {
        if (!started || e == null) {
            return;
        }
        Collection<World> worlds;
        try {
            worlds = startedWorlds.get();
        } catch (RuntimeException ex) {
            fail(ex, "startedWorlds");
            return;
        }
        if (worlds == null) {
            return;
        }
        for (World w : new ArrayList<>(worlds)) {
            if (claim(w)) {
                call(e, "worldStart", () -> e.onWorldStart(w));
            }
        }
    }

    static void worldStart(World world) {
        Extension e = extension();
        if (e != null && claim(world)) {
            call(e, "worldStart", () -> e.onWorldStart(world));
        }
    }

    static void worldStop(World world) {
        Extension e = extension();
        String name = nameOf(world);
        if (name != null) {
            releaseDelivery(name);
        }
        if (e != null) {
            call(e, "worldStop", () -> e.onWorldStop(world));
        }
    }

    static void stationRemoved(World world, TransitNetwork.Station s) {
        Extension e = extension();
        if (e != null) {
            call(e, "stationRemoved", () -> e.onStationRemovedByOperator(world, ApiStations.info(s)));
        }
    }

    /** The extension's rows without the null and invalid ones, the reserved keys and the duplicates. Never null. */
    static List<ConfigRow> configRows() {
        Extension e = extension();
        List<ConfigRow> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        List<ConfigRow> rows;
        try {
            rows = e.configRows();
        } catch (RuntimeException | LinkageError ex) {
            fail(ex, "configRows");
            return out;
        }
        if (rows == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (ConfigRow r : rows) {
            if (!valid(r) || reserved(r.key()) || !seen.add(r.key())) {
                if (r != null && REPORTED.add(String.valueOf(r.key()))) {
                    LOGGER.at(Level.WARNING).log("Extension config row ignored: %s", r.key());
                }
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private static boolean valid(ConfigRow r) {
        return r != null && notEmpty(r.key()) && notEmpty(r.sectionKey()) && notEmpty(r.labelKey()) && r.read() != null
                && r.write() != null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean reserved(String key) {
        return ConfigPage.BUILT_IN_KEYS.contains(key) || ModRecipes.ITEMS.contains(key);
    }

    static ConfigRow configRow(String key) {
        for (ConfigRow r : configRows()) {
            if (r.key().equals(key)) {
                return r;
            }
        }
        return null;
    }

    static boolean readRow(ConfigRow r) {
        try {
            return r.read().getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            fail(e, "readRow");
            return false;
        }
    }

    static boolean writeRow(ConfigRow r, boolean on) {
        try {
            r.write().accept(on);
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail(e, "writeRow");
            return false;
        }
    }

    /** The extension's station sub-commands without the null ones. Never null. */
    static List<AbstractCommand> stationSubCommands() {
        Extension e = extension();
        List<AbstractCommand> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        try {
            List<AbstractCommand> commands = e.stationSubCommands();
            if (commands != null) {
                for (AbstractCommand c : commands) {
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            fail(ex, "stationSubCommands");
            out.clear();
        }
        return out;
    }

    /** The worlds the server has started. Empty without a Universe. */
    static Collection<World> startedWorlds() {
        Universe universe = Universe.get();
        return universe == null ? List.of() : new ArrayList<>(universe.getWorlds().values());
    }

    private static void call(Extension e, String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError ex) {
            fail(ex, what);
        }
    }

    private static void fail(Throwable t, String what) {
        LOGGER.at(Level.WARNING).withCause(t).log("Extension: %s failed", what);
        try {
            Telemetry.error("extension", t, null, what);
        } catch (RuntimeException | LinkageError ignored) {
            // telemetry is not available (tests): the log line is enough
        }
    }
}
