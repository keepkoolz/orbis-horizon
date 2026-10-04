package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Lights of the balloon in flight (T34), reusable (T35: burner flame).
 *
 * A model has only one light (ModelAsset.Light, a ColorLight sent with the model, placed at the model's
 * origin): one light per crystal is impossible. The only way to light several precise points is
 * the DynamicLight component (DynamicLightUpdate, sent to clients by EntitySystems$DynamicLightTracker for
 * each visible entity that carries it, the light follows the entity). Each light is therefore a small
 * auxiliary entity with no model or collision box:
 *
 * - TransformComponent and NetworkId (NetworkSendableSpatialSystem query, otherwise the entity is never sent),
 *   Intangible, NonSerialized (never saved with the world, like the flying entity), DynamicLight.
 * - MountedComponent on the balloon entity (Minecart controller, like the T21 passengers) with the
 *   offset in the entity's frame (BalloonManager.attachOffset, the same calculation as the seats), so that the
 *   client sticks it to the balloon without jerks.
 * - its server-side position is reset on every tick (follow) to the world point, so that client
 *   tracking (view area) and the fallback without chained mounting stay correct. No Teleport (it would remove the
 *   mount, MountSystems$TeleportMountedEntity).
 * - a deterministic UUID (balloonUuid + key) to find and remove lights left in a world
 *   (T19 recovery, removeOrphans) without changing the recovery file format.
 *
 * Colour: the block's ColorLight (BlockType.getLight, "#eb7" for Rock_Crystal_Yellow_Block gives radius 0 and
 * red, green, blue 14, 11, 7: ColorParseUtil.hexStringToColorLightDirect reads one hexadecimal digit per channel,
 * value 0 to 15, the six-digit form is divided by 17). This is also what ItemComponent.computeDynamicLight does for a block dropped on the ground, and the
 * game's models (Fireball: "Light": {"Color": "#fb8"}). The unit of the radius is not documented: 0 for the game's
 * blocks (the client deduces the range from the channels), 2 to 18 for others. Option /orbishorizon balloon light radius:N to experiment.
 *
 * T35: call spawn(store, flight, KEY_BURNER, prefab-frame anchor point, colour) at take-off, then
 * set(store, flight, KEY_BURNER, colour or null) to put it out (running dry) or boost it (climbing). removeAll and
 * removeOrphans already handle the KEY_BURNER key.
 */
final class BalloonLights {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Prefix of the key of a prefab block light: "block:x,y,z" (prefab cell). */
    static final String KEY_BLOCK_PREFIX = "block:";
    /** Key reserved for the burner flame light (T35). */
    static final String KEY_BURNER = "burner";

    /**
     * Colour of the lit burner flame (T35): "#dba" from the On state of Hotair_Balloon_Burner.json, like the
     * game's brazier. One hexadecimal digit per channel (red 13, green 11, blue 10), radius 0 like the game's blocks.
     */
    static final ColorLight BURNER_LIT = new ColorLight((byte) 0, (byte) 13, (byte) 11, (byte) 10);
    /**
     * Boosted flame when climbing (T17): "#fc8", same hue, higher channels (red 15, green 12, blue 8). The
     * radius has no documented unit, so the stronger light goes through the channels, not the radius.
     */
    static final ColorLight BURNER_BOOST = new ColorLight((byte) 0, (byte) 15, (byte) 12, (byte) 8);

    private BalloonLights() {
    }

    /** Wanted colour of the burner light: null when dry (off), stronger when climbing, otherwise normal. */
    static ColorLight burnerColor(boolean dry, boolean boost) {
        if (dry) {
            return null;
        }
        return boost ? BURNER_BOOST : BURNER_LIT;
    }

    /** Emission point of the burner flame: the point where the model attaches its particles (Burner_Fire node). */
    static Vector3f burnerPoint(BalloonShape s) {
        BalloonShape.Cell a = s.anchor();
        return new Vector3f(a.x(), a.y() + 1, a.z());
    }

    /** Creates the burner flame light at take-off (T35), if lights are enabled and the burner is lit. */
    static void spawnBurner(Store<EntityStore> store, BalloonFlight flight, BalloonShape s) {
        if (!enabled || flight.dry || flight.lights.stream().anyMatch(l -> l.key.equals(KEY_BURNER))) {
            return;
        }
        spawn(store, flight, KEY_BURNER, burnerPoint(s), burnerColor(false, flight.boost));
    }

    /** A light attached to the flying entity. */
    static final class Light {
        final String key;
        /** Emission point, prefab frame (blocks, before rotation), height included. */
        final Vector3f local;
        Ref<EntityStore> ref;
        UUID uuid;
        /** Colour currently given to the component (null: off). */
        volatile ColorLight color;

        Light(String key, Vector3f local) {
            this.key = key;
            this.local = local;
        }
    }

    /** Light of a prefab block: key, emission point (centre of the cell) and the block's original colour. */
    record Spec(String key, Vector3f local, ColorLight light) {
    }

    private static volatile boolean enabled = true;
    /** Radius forced on all lights (diagnostics), null: the block's own. */
    private static volatile Integer radiusOverride;

    private static volatile BalloonShape cachedShape;
    private static volatile List<Spec> cachedSpecs = List.of();

    static boolean enabled() {
        return enabled;
    }

    static void setEnabled(boolean value) {
        enabled = value;
    }

    static Integer radiusOverride() {
        return radiusOverride;
    }

    static void setRadiusOverride(Integer value) {
        radiusOverride = value;
    }

    /** Copy of the colour with the forced radius if there is one. */
    static ColorLight effective(ColorLight base) {
        if (base == null) {
            return null;
        }
        ColorLight c = new ColorLight(base);
        Integer r = radiusOverride;
        if (r != null) {
            c.radius = (byte) Math.max(0, Math.min(127, r));
        }
        return c;
    }

    /**
     * The prefab cells whose block (base type, no state) emits light. Currently the 4
     * Rock_Crystal_Yellow_Block. The burner is not one of them: its light only exists in the On state, and in flight it has its own key (T35, spawnBurner).
     * Computed once per shape.
     */
    static List<Spec> specsFor(BalloonShape s) {
        if (cachedShape == s) {
            return cachedSpecs;
        }
        List<Spec> specs = new ArrayList<>();
        for (BalloonShape.Cell c : s.cells()) {
            BlockType bt = BlockType.getAssetMap().getAsset(c.baseName());
            ColorLight light = bt != null ? bt.getLight() : null;
            if (light == null || (light.red == 0 && light.green == 0 && light.blue == 0)) {
                continue;
            }
            specs.add(new Spec(KEY_BLOCK_PREFIX + c.x() + "," + c.y() + "," + c.z(),
                    new Vector3f(c.x(), c.y() + 0.5f, c.z()), new ColorLight(light)));
        }
        cachedSpecs = List.copyOf(specs);
        cachedShape = s;
        return cachedSpecs;
    }

    /** Creates the prefab blocks' lights (if lights are enabled). Call on the world thread, once the flying entity is created. */
    static void spawnAll(Store<EntityStore> store, BalloonFlight flight, BalloonShape s) {
        if (!enabled) {
            return;
        }
        for (Spec spec : specsFor(s)) {
            if (flight.lights.stream().noneMatch(l -> l.key.equals(spec.key()))) {
                spawn(store, flight, spec.key(), spec.local(), spec.light());
            }
        }
    }

    /**
     * Creates a light at this point of the prefab frame, mounted on the flying entity. Returns null if the
     * flying entity does not exist or if creation fails (logged, the flight continues without this light).
     */
    static Light spawn(Store<EntityStore> store, BalloonFlight flight, String key, Vector3f local, ColorLight color) {
        if (flight.balloonRef == null || !flight.balloonRef.isValid()) {
            return null;
        }
        try {
            Light light = new Light(key, new Vector3f(local));
            light.uuid = uuidFor(flight.balloonUuid, key);
            ColorLight c = effective(color);
            light.color = c;
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(),
                    new TransformComponent(BalloonManager.prefabPoint(flight.entityPos, flight.rotation, light.local), new Rotation3f()));
            holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(light.uuid));
            holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(Intangible.getComponentType());
            holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
            holder.addComponent(DynamicLight.getComponentType(), new DynamicLight(c));
            light.ref = store.addEntity(holder, AddReason.SPAWN);
            if (light.ref == null) {
                return null;
            }
            store.putComponent(light.ref, MountedComponent.getComponentType(),
                    new MountedComponent(flight.balloonRef, BalloonManager.attachOffset(light.local), MountController.Minecart));
            flight.lights.add(light);
            return light;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Lumière %s de la montgolfière impossible", key);
            return null;
        }
    }

    /** Puts the light entities back on their world point, to be called after each movement of the flying entity. */
    static void follow(Store<EntityStore> store, BalloonFlight flight) {
        if (flight.lights.isEmpty()) {
            return;
        }
        for (Light l : flight.lights) {
            if (l.ref == null || !l.ref.isValid()) {
                continue;
            }
            TransformComponent t = store.getComponent(l.ref, TransformComponent.getComponentType());
            if (t != null) {
                t.setPosition(BalloonManager.prefabPoint(flight.entityPos, flight.rotation, l.local));
            }
        }
    }

    /** Changes a light's colour (null: off, the client removes it). False if the key does not exist. */
    static boolean set(Store<EntityStore> store, BalloonFlight flight, String key, ColorLight color) {
        for (Light l : flight.lights) {
            if (l.key.equals(key) && l.ref != null && l.ref.isValid()) {
                DynamicLight d = store.getComponent(l.ref, DynamicLight.getComponentType());
                if (d == null) {
                    return false;
                }
                ColorLight c = effective(color);
                l.color = c;
                d.setColorLight(c);
                return true;
            }
        }
        return false;
    }

    /** Applies the current setting (forced radius) to lights already created. */
    static void refreshColors(Store<EntityStore> store, BalloonFlight flight, BalloonShape s) {
        for (Spec spec : specsFor(s)) {
            set(store, flight, spec.key(), spec.light());
        }
        if (enabled && flight.lights.stream().noneMatch(l -> l.key.equals(KEY_BURNER))) {
            spawnBurner(store, flight, s);
        }
        set(store, flight, KEY_BURNER, burnerColor(flight.dry, flight.boost));
    }

    /** Removes all the flight's lights. Never throws (called by finishFlight). */
    static void removeAll(Store<EntityStore> store, BalloonFlight flight) {
        for (Light l : flight.lights) {
            try {
                if (l.ref != null && l.ref.isValid()) {
                    store.removeEntity(l.ref, RemoveReason.REMOVE);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Lumière %s non supprimée", l.key);
            }
        }
        flight.lights.clear();
    }

    /**
     * Removes the lights that an interrupted flight may have left in a world that stayed loaded (T19 recovery). They
     * are never saved (NonSerialized), so this only concerns a world that outlived the plugin.
     */
    static void removeOrphans(World world, Store<EntityStore> store, UUID balloonUuid, BalloonShape s) {
        if (balloonUuid == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Spec spec : specsFor(s)) {
            keys.add(spec.key());
        }
        keys.add(KEY_BURNER);
        for (String key : keys) {
            try {
                Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(uuidFor(balloonUuid, key));
                if (ref != null && ref.isValid()) {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    LOGGER.at(Level.INFO).log("Lumière %s restée dans le monde supprimée", key);
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Lumière %s restée dans le monde non supprimée", key);
            }
        }
    }

    /** Deterministic UUID of a light: the same for a given key and flying entity. */
    static UUID uuidFor(UUID balloonUuid, String key) {
        if (balloonUuid == null) {
            return UUID.randomUUID();
        }
        return UUID.nameUUIDFromBytes(("orbis_horizon_light:" + balloonUuid + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Diagnostic text: setting and the flight's lights (in English, technical values). */
    static String describe(BalloonFlight flight) {
        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "on" : "off");
        if (radiusOverride != null) {
            sb.append(", forced radius ").append(radiusOverride);
        }
        if (flight != null) {
            int alive = 0;
            for (Light l : flight.lights) {
                if (l.ref != null && l.ref.isValid()) {
                    alive++;
                }
            }
            sb.append(", ").append(alive).append(" light entit").append(alive == 1 ? "y" : "ies").append(" in flight");
            Light first = flight.lights.isEmpty() ? null : flight.lights.get(0);
            if (first != null && first.color != null) {
                ColorLight c = first.color;
                sb.append(" (radius ").append(c.radius).append(", color ").append(c.red).append(" ").append(c.green)
                        .append(" ").append(c.blue).append(")");
            }
        }
        return sb.toString();
    }
}
