package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Remaining-fuel window of a vehicle, a custom HUD centred above the stamina bar. Callers refresh it every tick while a flight
 * targets a player ({@link #show}, {@link #showDry}); a packet goes out only when the displayed content changes (whole seconds,
 * item count, colour level), so about one per second. A window nobody refreshes any more is removed by {@link #sweep}
 * (safety net for every end of flight), and {@link #hide} removes it at once. State is in memory only. Never throws.
 */
final class FuelHud {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final String HUD_KEY = "OrbisHorizon_Fuel";
    static final String UI_FILE = "Hud/OrbisHorizon_Fuel.ui";
    /** Time line colours: the .ui file holds one label per colour and the plugin shows one of them. */
    static final String TITLE = "#FuelTitle.Text";
    static final String ITEMS = "#FuelItems.Text";
    static final String ITEMS_VISIBLE = "#FuelItems.Visible";
    static final String TIME_NORMAL = "#FuelTime";
    static final String TIME_LOW = "#FuelTimeLow";
    static final String TIME_CRITICAL = "#FuelTimeCritical";
    /** Seconds left at or under which the time line turns gold, then red. */
    static final double LOW_S = 30;
    static final double CRITICAL_S = 10;
    /** A window not refreshed for this long is removed by {@link #sweep} (the slowest caller, the hovering balloon, refreshes once a second). */
    static final long STALE_MS = 3_000;
    private static final long SWEEP_MS = 500;
    private static final long WARN_MS = 60_000;

    /** What the window is about: the texts of the title and of the time line depend on it. */
    enum Vehicle {
        BALLOON("balloon"), AIRSHIP("airship"), STEAMBOAT("steamboat");

        final String id;

        Vehicle(String id) {
            this.id = id;
        }
    }

    /** Colour level of the time line. */
    enum FuelLevel {
        NORMAL, LOW, CRITICAL, DRY
    }

    /** What the window displays: two equal contents need no packet. */
    record Content(Vehicle vehicle, FuelLevel level, long seconds, int items) {
    }

    private static final class State {
        PlayerRef pr;
        HudManager hm;
        FuelWindow hud; // non-null while the window is shown
        Content last;
        String world;
        long seenMs;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_SWEEP = new ConcurrentHashMap<>();
    private static long lastWarn;

    private FuelHud() {
    }

    /** Pure: the vehicle of a ship (the steamboat and the shuttle boat share the steamboat texts). */
    static Vehicle vehicleOf(Deployables.Kind kind) {
        return Texts.isBoat(kind) ? Vehicle.STEAMBOAT : Vehicle.AIRSHIP;
    }

    /** Pure: colour level for the seconds of fuel left (a dry vehicle is {@link FuelLevel#DRY}). */
    static FuelLevel level(double secondsLeft, boolean dry) {
        if (dry) {
            return FuelLevel.DRY;
        }
        if (secondsLeft <= CRITICAL_S) {
            return FuelLevel.CRITICAL;
        }
        return secondsLeft <= LOW_S ? FuelLevel.LOW : FuelLevel.NORMAL;
    }

    /** Pure: whole seconds displayed, rounded up like {@link BalloonManager#durationText}. */
    static long wholeSeconds(double secondsLeft) {
        return Math.max(0, Math.round(Math.ceil(secondsLeft)));
    }

    /** Pure: the content for a fuel level, or for a dry vehicle (no time, no items). */
    static Content content(Vehicle vehicle, double secondsLeft, int items, boolean dry) {
        if (dry) {
            return new Content(vehicle, FuelLevel.DRY, 0, 0);
        }
        return new Content(vehicle, level(secondsLeft, false), wholeSeconds(secondsLeft), Math.max(0, items));
    }

    /** Pure: a packet is needed when nothing is shown yet or the content differs. */
    static boolean changed(Content shown, Content wanted) {
        return shown == null || !shown.equals(wanted);
    }

    /** Pure: is the window stale (not refreshed for {@link #STALE_MS})? */
    static boolean stale(long seenMs, long nowMs) {
        return nowMs - seenMs > STALE_MS;
    }

    /** Sets every text and the visible time line, for the first build and for the updates. */
    private static void apply(UICommandBuilder cmd, Content c) {
        cmd.set(TITLE, Texts.ui("fuel.title." + c.vehicle().id));
        boolean dry = c.level() == FuelLevel.DRY;
        Message time = dry ? Texts.ui("fuel.dry")
                : Texts.ui("fuel.time." + c.vehicle().id).param("time", BalloonManager.durationText(c.seconds()));
        cmd.set(TIME_NORMAL + ".Text", time);
        cmd.set(TIME_LOW + ".Text", time);
        cmd.set(TIME_CRITICAL + ".Text", time);
        cmd.set(TIME_NORMAL + ".Visible", c.level() == FuelLevel.NORMAL);
        cmd.set(TIME_LOW + ".Visible", c.level() == FuelLevel.LOW);
        cmd.set(TIME_CRITICAL + ".Visible", c.level() == FuelLevel.CRITICAL || dry);
        cmd.set(ITEMS_VISIBLE, !dry);
        if (!dry) {
            cmd.set(ITEMS, Texts.ui("fuel.items").param("items", c.items()));
        }
    }

    /** The custom HUD: appends the .ui file and sets the texts, later updates only change the texts. */
    private static final class FuelWindow extends CustomUIHud {
        private final Content initial;

        FuelWindow(PlayerRef pr, Content initial) {
            super(pr, HUD_KEY);
            this.initial = initial;
        }

        @Override
        protected void build(UICommandBuilder cmd) {
            cmd.append(UI_FILE);
            apply(cmd, initial);
        }
    }

    /** Shows or updates the window of a player: seconds of fuel left and fuel items. World thread. Never throws. */
    static void show(Store<EntityStore> store, PlayerRef pr, Vehicle vehicle, double secondsLeft, int items) {
        update(store, pr, content(vehicle, secondsLeft, items, false));
    }

    /** Shows or updates the window of a player with the out-of-fuel text. World thread. Never throws. */
    static void showDry(Store<EntityStore> store, PlayerRef pr, Vehicle vehicle) {
        update(store, pr, content(vehicle, 0, 0, true));
    }

    private static void update(Store<EntityStore> store, PlayerRef pr, Content wanted) {
        try {
            if (pr == null) {
                return;
            }
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            State st = STATES.computeIfAbsent(pr.getUuid(), k -> new State());
            st.pr = pr;
            st.hm = player.getHudManager();
            st.world = store.getExternalData().getWorld().getName();
            st.seenMs = System.currentTimeMillis();
            if (st.hud == null || st.hm.getCustomHud(HUD_KEY) == null) {
                st.hud = new FuelWindow(pr, wanted);
                st.hm.addCustomHud(pr, st.hud);
                st.last = wanted;
            } else if (changed(st.last, wanted)) {
                UICommandBuilder cmd = new UICommandBuilder();
                apply(cmd, wanted);
                st.hud.update(false, cmd);
                st.last = wanted;
            }
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /** Removes the window of a player at once (end of flight, leaving the nacelle). Never throws. */
    static void hide(PlayerRef pr) {
        if (pr != null) {
            hide(pr.getUuid());
        }
    }

    static void hide(UUID id) {
        if (id == null) {
            return;
        }
        try {
            State st = STATES.remove(id);
            if (st != null && st.hud != null && st.hm != null && st.pr != null) {
                st.hm.removeCustomHud(st.pr, HUD_KEY);
            }
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /**
     * Safety net, called every world tick and run at most every {@link #SWEEP_MS}: removes the windows of this world that nobody
     * refreshed for {@link #STALE_MS} (landing, crash, death, disconnect, world change, voyage end), and those of dead players.
     */
    static void sweep(Store<EntityStore> store, World world) {
        try {
            if (STATES.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            String wn = world.getName();
            Long last = LAST_SWEEP.get(wn);
            if (last != null && now - last < SWEEP_MS) {
                return;
            }
            LAST_SWEEP.put(wn, now);
            for (Map.Entry<UUID, State> e : STATES.entrySet()) {
                State st = e.getValue();
                if (!wn.equals(st.world)) {
                    continue;
                }
                try {
                    Ref<EntityStore> ref = st.pr == null ? null : st.pr.getReference();
                    boolean gone = ref == null || !ref.isValid() || ref.getStore() != store;
                    if (gone) {
                        STATES.remove(e.getKey()); // disconnected or in another world: the interface is gone with it
                    } else if (stale(st.seenMs, now) || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                        hide(e.getKey());
                    }
                } catch (RuntimeException ex) {
                    warn(ex);
                }
            }
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private static void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarn >= WARN_MS) {
            lastWarn = now;
            LOGGER.at(Level.WARNING).withCause(e).log("Fuel window: update failed");
            Telemetry.error("fuel.hud", e, null, null);
        }
    }
}
