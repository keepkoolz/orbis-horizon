package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Oxygen bar of the mod. The game's client makes its own bar blink under water when the maximum oxygen is above
 * {@link #BASE_MAX} (diving gear). Then the game's bar is hidden and this one is shown instead, only while the oxygen is
 * below its maximum, like the game's. State is per world and per player, in memory only. Never throws.
 */
final class OxygenHud {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Maximum oxygen of a player without gear. */
    static final float BASE_MAX = 100f;
    /** Tolerance above {@link #BASE_MAX} (float noise). */
    static final float TOLERANCE = 0.5f;
    static final String HUD_KEY = "OrbisHorizon_Oxygen";
    static final String UI_FILE = "Hud/OrbisHorizon_Oxygen.ui";
    static final String BAR = "#OxygenBar.Value";
    private static final long TICK_MS = 100;
    private static final long WARN_MS = 60_000;
    private static final float MIN_CHANGE = 0.001f;

    private static final class State {
        boolean hidden; // game's bar hidden
        OxygenBarHud hud; // non-null while our bar is shown
        float last = -1f;
    }

    private static final Map<String, Map<UUID, State>> STATES = new HashMap<>();
    private static final Map<String, Long> LAST_TICK = new HashMap<>();
    private static long lastWarn;
    /** Players whose game bar the mod hid, kept across deaths and world changes (the per-world state is not). */
    private static final Set<UUID> HIDDEN_BY_MOD = new HashSet<>();

    private OxygenHud() {
    }

    /** True when the mod's bar must replace the game's for this maximum oxygen. */
    static boolean wanted(float max) {
        return max > BASE_MAX + TOLERANCE;
    }

    /** Fill fraction, bounded to 0..1 (0 for an invalid maximum). */
    static float fraction(float value, float max) {
        if (!(max > 0f) || Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value / max));
    }

    /** The custom HUD: appends the .ui file, the value is then set by updates. */
    private static final class OxygenBarHud extends CustomUIHud {
        private final float initial;

        OxygenBarHud(PlayerRef pr, float initial) {
            super(pr, HUD_KEY);
            this.initial = initial;
        }

        @Override
        protected void build(UICommandBuilder cmd) {
            cmd.append(UI_FILE);
            cmd.set(BAR, initial);
        }
    }

    /** Per-world-tick work, at most every {@link #TICK_MS}. */
    static void tick(Store<EntityStore> store, World world) {
        try {
            long now = System.currentTimeMillis();
            String wn = world.getName();
            Long last = LAST_TICK.get(wn);
            if (last != null && now - last < TICK_MS) {
                return;
            }
            LAST_TICK.put(wn, now);
            Map<UUID, State> states = STATES.computeIfAbsent(wn, k -> new HashMap<>());
            Set<UUID> seen = new HashSet<>();
            for (PlayerRef pr : world.getPlayerRefs()) {
                try {
                    UUID id = pr.getUuid();
                    seen.add(id);
                    handle(store, pr, states.computeIfAbsent(id, k -> new State()), states, id);
                } catch (RuntimeException e) {
                    warn(e);
                }
            }
            states.keySet().removeIf(id -> !seen.contains(id));
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private static void handle(Store<EntityStore> store, PlayerRef pr, State st, Map<UUID, State> states, UUID id) {
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            states.remove(id);
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            // Dead: forget it, the game resets the interface at respawn and the next tick starts again.
            if (st.hud != null) {
                player.getHudManager().removeCustomHud(pr, HUD_KEY);
            }
            states.remove(id);
            return;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        EntityStatValue ox = stats == null ? null : stats.get(DefaultEntityStatTypes.getOxygen());
        float max = ox == null ? 0f : ox.getMax();
        float value = ox == null ? 0f : ox.get();
        if (!wanted(max)) {
            restore(player, pr, st);
            return;
        }
        // the HudManager is the truth: the game or a world change may have reset the interface since the last tick
        if (player.getHudManager().getVisibleHudComponents().contains(HudComponent.Oxygen)) {
            player.getHudManager().hideHudComponents(pr, HudComponent.Oxygen);
        }
        st.hidden = true;
        HIDDEN_BY_MOD.add(pr.getUuid());
        if (!(value < max)) {
            if (st.hud != null) {
                player.getHudManager().removeCustomHud(pr, HUD_KEY);
                st.hud = null;
                st.last = -1f;
            }
            return;
        }
        float f = fraction(value, max);
        if (st.hud == null || player.getHudManager().getCustomHud(HUD_KEY) == null) {
            st.hud = new OxygenBarHud(pr, f);
            player.getHudManager().addCustomHud(pr, st.hud);
            st.last = f;
        } else if (Math.abs(f - st.last) > MIN_CHANGE) {
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set(BAR, f);
            st.hud.update(false, cmd);
            st.last = f;
        }
    }

    private static void restore(Player player, PlayerRef pr, State st) {
        // checked on the HudManager, not only on our state: the state is lost on death and world change
        if (st.hud != null || player.getHudManager().getCustomHud(HUD_KEY) != null) {
            player.getHudManager().removeCustomHud(pr, HUD_KEY);
            st.hud = null;
            st.last = -1f;
        }
        if (st.hidden || HIDDEN_BY_MOD.remove(pr.getUuid())) {
            // only a bar the mod hid is shown again, never one hidden by the game or another mod
            if (!player.getHudManager().getVisibleHudComponents().contains(HudComponent.Oxygen)) {
                player.getHudManager().showHudComponents(pr, HudComponent.Oxygen);
            }
            HIDDEN_BY_MOD.remove(pr.getUuid());
            st.hidden = false;
        }
    }

    private static void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarn >= WARN_MS) {
            lastWarn = now;
            LOGGER.at(Level.WARNING).withCause(e).log("Mod oxygen bar: tick failed");
        }
    }
}
