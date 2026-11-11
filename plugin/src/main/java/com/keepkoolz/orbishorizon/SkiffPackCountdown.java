package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Packing countdown of a skiff: one press on its placed crate starts COUNTDOWN_S seconds, shown as a progress bar (custom HUD, the
 * same kind as the mod's oxygen bar), then the skiff is packed (SkiffPacker.pack, every check made again). A second press, moving
 * more than MAX_DISTANCE blocks away from the crate, dying, leaving or the skiff leaving the registry cancels it. Ticked on each world
 * thread, never throws. Nothing is done while no countdown runs, and a running one is checked and its bar updated every CHECK_MS
 * only (a distance, two map lookups and one HUD update, no ray cast).
 */
final class SkiffPackCountdown {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final int COUNTDOWN_S = 10;
    static final double MAX_DISTANCE = 6.0;
    static final long CHECK_MS = 250;
    static final String HUD_KEY = "OrbisHorizon_SkiffPack";
    static final String UI_FILE = "Hud/OrbisHorizon_SkiffPack.ui";
    static final String BAR = "#PackBar.Value";
    static final String LABEL = "#PackLabel.Text";

    private static final class Pending {
        final Ref<EntityStore> ref;
        final World world;
        final BalloonRegistry.Entry entry;
        final Vector3i crate;
        final long startMs;
        long checkedMs;
        volatile boolean cancelRequested;
        PackBarHud hud;

        Pending(Ref<EntityStore> ref, World world, BalloonRegistry.Entry entry, Vector3i crate, long startMs) {
            this.ref = ref;
            this.world = world;
            this.entry = entry;
            this.crate = crate;
            this.startMs = startMs;
        }
    }

    /** The progress bar: appends the .ui file, the value is then set by updates. */
    private static final class PackBarHud extends CustomUIHud {
        private final float initial;

        PackBarHud(PlayerRef pr, float initial) {
            super(pr, HUD_KEY);
            this.initial = initial;
        }

        @Override
        protected void build(UICommandBuilder cmd) {
            cmd.append(UI_FILE);
            cmd.set(LABEL, Texts.ui("skiff.packing"));
            cmd.set(BAR, initial);
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private SkiffPackCountdown() {
    }

    /** Share of the countdown already elapsed, from 0 at the start to 1 when it is over. */
    static float progress(long startMs, long now) {
        float f = (now - startMs) / (COUNTDOWN_S * 1000f);
        return f < 0f ? 0f : Math.min(1f, f);
    }

    /** True when the player's feet are close enough to the crate cell for the countdown to go on. */
    static boolean nearCrate(Vector3d feet, Vector3i crate) {
        double dx = feet.x - (crate.x + 0.5), dy = feet.y - (crate.y + 0.5), dz = feet.z - (crate.z + 0.5);
        return dx * dx + dy * dy + dz * dz <= MAX_DISTANCE * MAX_DISTANCE;
    }

    /** A press on the crate: starts the countdown, or asks the running one of this player to stop (done at its next check). */
    static void press(PlayerRef pr, Ref<EntityStore> ref, World world, BalloonRegistry.Entry entry, Vector3i crate) {
        Pending old = PENDING.get(pr.getUuid());
        if (old != null) {
            old.cancelRequested = true;
            old.checkedMs = 0;
            return;
        }
        PENDING.put(pr.getUuid(), new Pending(ref, world, entry, crate, System.currentTimeMillis()));
    }

    static void tick(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
            Pending p = e.getValue();
            if (p.ref == null || !p.ref.isValid()) { // gone: the game drops its interface
                PENDING.remove(e.getKey(), p);
                continue;
            }
            if (p.world != world || now - p.checkedMs < CHECK_MS) {
                continue;
            }
            p.checkedMs = now;
            try {
                PlayerRef pr = store.getComponent(p.ref, PlayerRef.getComponentType());
                Player player = store.getComponent(p.ref, Player.getComponentType());
                boolean dead = store.getComponent(p.ref, DeathComponent.getComponentType()) != null;
                boolean present = BalloonRegistry.get(world, new Vector3i(p.entry.x(), p.entry.y(), p.entry.z()), p.entry.rotation(),
                        p.entry.kind()) != null;
                TransformComponent t = store.getComponent(p.ref, TransformComponent.getComponentType());
                if (p.cancelRequested || pr == null || player == null || t == null || dead || !present
                        || !nearCrate(t.getPosition(), p.crate)) {
                    PENDING.remove(e.getKey(), p);
                    if (pr != null && player != null) {
                        removeBar(player, pr, p);
                        pr.sendMessage(Texts.t("skiff.packCancelled"));
                    }
                    continue;
                }
                float f = progress(p.startMs, now);
                if (f >= 1f) {
                    PENDING.remove(e.getKey(), p);
                    removeBar(player, pr, p);
                    SkiffPacker.pack(store, p.ref, world, p.entry, true);
                } else if (p.hud == null || player.getHudManager().getCustomHud(HUD_KEY) == null) {
                    p.hud = new PackBarHud(pr, f);
                    player.getHudManager().addCustomHud(pr, p.hud);
                } else {
                    UICommandBuilder cmd = new UICommandBuilder();
                    cmd.set(BAR, f);
                    p.hud.update(false, cmd);
                }
            } catch (RuntimeException ex) {
                PENDING.remove(e.getKey(), p);
                LOGGER.at(Level.WARNING).withCause(ex).log("Skiff: packing countdown failed");
                Telemetry.error("boat.skiff.pack", ex, Deployables.STEAMBOAT_SKIFF_KIND, "countdown");
            }
        }
    }

    private static void removeBar(Player player, PlayerRef pr, Pending p) {
        if (p.hud != null || player.getHudManager().getCustomHud(HUD_KEY) != null) {
            player.getHudManager().removeCustomHud(pr, HUD_KEY);
            p.hud = null;
        }
    }
}
