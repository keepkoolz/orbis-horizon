package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * The NPC pilot of a landed ship that has a crew spec (every ship with a pilot): the NPC standing at its post (the helm, or the rest post), its validated
 * outfit and its persistence. Modelled on StationMaster: the NPC is a world entity saved with its section (NOT NonSerialized), the
 * registry entry keeps its uuid and skin seed under "crew", and after a restart it is found by uuid, else adopted if an orphan
 * stands in the airship, else created again, once the entities of its section are in the world.
 */
final class AirshipCrew {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String ROLE = "Airship_Pilot";
    static final String ROOT_INTERACTION_ID = "Airship_Pilot_Use";
    static final String HINT = "server.orbis_horizon.interactionHints.pilot";

    /**
     * Fixed outfit of every pilot, "slot=Id.Colour" (format of the character creator). It is validated by
     * CosmeticsModule.validateSkin before it is ever put on an NPC: a refused skin crashes the client.
     */
    static final String OUTFIT = "head=ExplorerGoggles.Brown,overtop=Jacket_Voyager.Brown,undertop=LongSleeveShirt.Cream,"
            + "pants=Explorer_Trousers.BrownDark,shoes=HeavyLeather.Black,gloves=LeatherMittens.Brown,ear=EarHoops.Gold_Red.Left";
    /** Head accessory of the outfit: a skin that already carries it is not rebuilt. */
    private static final String OUTFIT_MARK = "ExplorerGoggles.Brown";

    private static final long ENTITY_GRACE_MS = 2_000;
    private static final long REWIRE_INTERVAL_MS = 2_000;

    private AirshipCrew() {
    }

    // ------------------------------------------------------------------ position

    /** World position (feet) of a cell of the airship (prefab frame): centre of the cell, rotated with the airship, +0.05 in y. Pure. */
    static Vector3d postPoint(BalloonRegistry.Entry e, int[] cell) {
        Vector3d logical = new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5);
        return BalloonManager.prefabPoint(logical, e.rotation(), new Vector3f(cell[0], cell[1], cell[2])).add(0, 0.05, 0);
    }

    /** Same as postPoint for a point given in prefab coordinates (not necessarily integer): centre rotated with the airship. */
    static Vector3d postPoint(BalloonRegistry.Entry e, double[] p) {
        Vector3d logical = new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5);
        return BalloonManager.prefabPoint(logical, e.rotation(), new Vector3f((float) p[0], (float) p[1], (float) p[2])).add(0, 0.05, 0);
    }

    /** Point of a post: the helm or the rest cell of the ship of the entry (its kind must have a crew spec). */
    static Vector3d postPoint(BalloonRegistry.Entry e, CrewRecord.Post post) {
        Deployables.CrewSpec c = Deployables.crewOf(e.kind());
        return postPoint(e, post == CrewRecord.Post.HELM ? c.helm() : c.rest());
    }

    /** World yaw toward the bow of the airship (prefab -z, rotated with it; forward = (-sin yaw, -cos yaw)). Pure. */
    static float bowYaw(BalloonRegistry.Entry e) {
        Vector3d logical = new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5);
        Vector3d a = BalloonManager.prefabPoint(logical, e.rotation(), new Vector3f(0, 0, 0));
        Vector3d b = BalloonManager.prefabPoint(logical, e.rotation(), new Vector3f(0, 0, -1));
        return (float) Math.atan2(-(b.x - a.x), -(b.z - a.z));
    }

    private static Rotation3f yawRotation(float yaw) {
        Rotation3f r = new Rotation3f();
        r.setYaw(yaw);
        return r;
    }

    // ------------------------------------------------------------------ spawn and wiring

    /**
     * Creates the pilot NPC at the post of the crew record and wires it. Returns its uuid, or null. The caller stores the uuid
     * (BalloonRegistry.setCrew). Not NonSerialized: the pilot is saved with the world. On the world thread.
     */
    static UUID spawn(Store<EntityStore> store, World world, BalloonRegistry.Entry e, CrewRecord crew) {
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null || plugin.getIndex(ROLE) < 0) {
                LOGGER.at(Level.WARNING).log("Pilot: role %s not found", ROLE);
                return null;
            }
            Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> p =
                    plugin.spawnNPC(store, ROLE, null, postPoint(e, crew.post()), yawRotation(bowYaw(e)));
            if (p == null || p.first() == null || !p.first().isValid()) {
                LOGGER.at(Level.WARNING).log("Pilot: creation failed at (%d, %d, %d)", e.x(), e.y(), e.z());
                return null;
            }
            Ref<EntityStore> ref = p.first();
            wire(store, ref, e, crew);
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            if (id == null) {
                return null;
            }
            LOGGER.at(Level.INFO).log("Pilot: created at (%d, %d, %d) (%s, post %s)", e.x(), e.y(), e.z(), id.getUuid(), crew.post().key);
            if (crew.post() == CrewRecord.Post.STOOL) {
                seat(store, ref, e, id.getUuid());
            }
            return id.getUuid();
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: not created at (%d, %d, %d)", e.x(), e.y(), e.z());
            Telemetry.error("airship.crew", ex, null, null);
            return null;
        }
    }

    /** Makes an existing NPC entity the pilot of the airship: outfit, invulnerable, talkable, named. Safe to call again. */
    static void wire(Store<EntityStore> store, Ref<EntityStore> ref, BalloonRegistry.Entry e, CrewRecord crew) {
        ensureSkin(store, ref, crew);
        store.putComponent(ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        store.ensureComponent(ref, Interactable.getComponentType());
        Interactions interactions = store.getComponent(ref, Interactions.getComponentType());
        if (interactions == null) {
            interactions = new Interactions();
            store.putComponent(ref, Interactions.getComponentType(), interactions);
        }
        interactions.setInteractionId(InteractionType.Use, ROOT_INTERACTION_ID);
        interactions.setInteractionHint(HINT);
        store.putComponent(ref, DisplayNameComponent.getComponentType(),
                new DisplayNameComponent(Texts.t("crew.pilot.firstName").param("name", PilotLook.firstName(crew.seed()))));
        String first = PilotLook.firstName(crew.seed());
        Nameplate plate = store.ensureAndGetComponent(ref, Nameplate.getComponentType());
        if (!first.equals(plate.getText())) {
            plate.setText(first); // the game's own nameplate above the head
        }
    }

    /** Parses "slot=Id.Colour,..." onto a skin. Returns the unknown slots (empty if none). */
    static String applyOutfit(PlayerSkin sk, String spec) {
        StringBuilder bad = new StringBuilder();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String v = kv[1].trim();
            switch (kv[0].trim().toLowerCase()) {
                case "head" -> sk.headAccessory = v;
                case "face" -> sk.faceAccessory = v;
                case "overtop" -> sk.overtop = v;
                case "undertop" -> sk.undertop = v;
                case "pants" -> sk.pants = v;
                case "overpants" -> sk.overpants = v;
                case "shoes" -> sk.shoes = v;
                case "gloves" -> sk.gloves = v;
                case "cape" -> sk.cape = v;
                case "ear" -> sk.earAccessory = v;
                default -> bad.append(kv[0]).append(' ');
            }
        }
        return bad.toString();
    }

    /** Pilots (key) whose skin was put: nothing is rebuilt or re-sent until the entity or crew uuid changes. */
    private static final Set<String> SKIN_DONE = ConcurrentHashMap.newKeySet();

    private static String skinKey(CrewRecord crew) {
        return crew.uuid() != null ? crew.uuid().toString() : "seed:" + crew.seed();
    }

    /** Forgets the skin memory of a pilot (crew state cleaned, or entity recreated under a new uuid). */
    static void forgetSkin(UUID npc) {
        if (npc != null) {
            SKIN_DONE.remove(npc.toString());
            SIT_STATE.remove(npc);
        }
    }

    /**
     * Puts the pilot's skin, first validated candidate wins: 1) drawn + gender + outfit, 2) drawn + outfit, 3) drawn + gender,
     * 4) drawn alone. A refused skin is never put (it crashes the client). Once put, later passes do nothing for this pilot
     * (the memory is presence-based, never compared with the component read back). Never throws.
     */
    static void ensureSkin(Store<EntityStore> store, Ref<EntityStore> ref, CrewRecord crew) {
        try {
            String key = skinKey(crew);
            PlayerSkinComponent have = store.getComponent(ref, PlayerSkinComponent.getComponentType());
            boolean present = have != null && have.getPlayerSkin() != null;
            if (present && SKIN_DONE.contains(key)) {
                return;
            }
            CosmeticsModule cosmetics = CosmeticsModule.get();
            PlayerSkin drawn = cosmetics.generateRandomSkin(new Random(crew.seed()));
            if (drawn == null) {
                return;
            }
            PlayerSkin gendered = new PlayerSkin(drawn);
            PilotLook.applyGender(gendered, crew.seed());
            if (present) {
                PlayerSkin h = have.getPlayerSkin();
                boolean looks = Objects.equals(h.haircut, gendered.haircut) && Objects.equals(h.face, gendered.face)
                        && Objects.equals(h.facialHair, gendered.facialHair);
                if (looks && OUTFIT_MARK.equals(h.headAccessory)) {
                    SKIN_DONE.add(key); // set before a restart: already correct
                    return;
                }
            }
            String[] names = {"gender + outfit", "drawn + outfit", "gender only", "drawn only"};
            PlayerSkin[] cands = new PlayerSkin[4];
            cands[0] = new PlayerSkin(gendered);
            String bad = applyOutfit(cands[0], OUTFIT);
            cands[1] = new PlayerSkin(drawn);
            applyOutfit(cands[1], OUTFIT);
            cands[2] = new PlayerSkin(gendered);
            cands[3] = new PlayerSkin(drawn);
            for (int i = 0; i < cands.length; i++) {
                if (!validated(cosmetics, cands[i])) {
                    continue;
                }
                if (i == 0 && !bad.isEmpty()) {
                    LOGGER.at(Level.WARNING).log("Pilot: unknown outfit slots ignored: %s", bad);
                }
                PlayerSkinComponent c = new PlayerSkinComponent(cands[i]);
                c.setNetworkOutdated(); // sent again to the clients that already see the pilot
                store.putComponent(ref, PlayerSkinComponent.getComponentType(), c);
                SKIN_DONE.add(key);
                LOGGER.at(Level.INFO).log("Pilot: appearance applied (%s)", names[i]);
                return;
            }
            SKIN_DONE.add(key); // nothing usable: do not retry every pass
            LOGGER.at(Level.WARNING).log("Pilot: no appearance accepted, nothing applied");
        } catch (Exception ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: appearance not applied");
            Telemetry.error("airship.crew", ex, null, null);
        }
    }

    private static boolean validated(CosmeticsModule cosmetics, PlayerSkin skin) {
        try {
            cosmetics.validateSkin(skin);
            return true;
        } catch (Exception refused) {
            LOGGER.at(Level.WARNING).log("Pilot: appearance refused (%s)", refused.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ helm refusal and flight mannequin

    /**
     * Chat key of the reason the helm refuses a take-off because of the pilot, or null: the pilot holds the helm (post HELM, any
     * state), or it is on the stool but not seated yet (walking or absent). Pure.
     */
    static String helmRefusalKey(CrewRecord crew, CrewState state) {
        if (crew == null) {
            return null;
        }
        if (crew.post() == CrewRecord.Post.HELM) {
            return "crew.helm.held";
        }
        return state != CrewState.SEATED ? "crew.helm.notSeated" : null;
    }

    private static final long MAN_ANIM_FIRST_MS = 400;
    private static final long MAN_ANIM_SECOND_MS = 1500;
    private static final long MAN_ANIM_REPEAT_MS = 4000;

    /** The seated mannequin that stands for the pilot while the airship flies. */
    static final class Mannequin {
        Ref<EntityStore> ref;
        /** Twin on the observer entity (dual rendering only), hidden from the captain. */
        Ref<EntityStore> twin;
        long createdMs;
        long lastAnimMs;
        int anims;
        /** Animation replayed in the Movement slot: Sit on the stool, Idle standing at the helm. */
        String animId = POSE_SIT;
        /** Yaw of the mannequin in the ship frame, added to the ship heading (0 at the helm, the seat's facing on a stool). */
        float localYaw;
    }

    /**
     * Ship-frame yaw of a seat block of the prefab, for the entity convention forward = (-sin yaw, -cos yaw) with the bow at
     * the prefab -z (yaw 0). Block rotation 1 looks toward +x, 3 toward -x, 0 toward +z, 2 toward -z. Pure.
     */
    static float seatYaw(int rotation) {
        switch (((rotation % 4) + 4) % 4) {
            case 1:
                return (float) (-Math.PI / 2);
            case 3:
                return (float) (Math.PI / 2);
            case 0:
                return (float) Math.PI;
            default:
                return 0f;
        }
    }

    static final String POSE_SIT = "Sit";
    static final String POSE_STAND = "Idle";

    static UUID mannequinUuid(UUID shipUuid) {
        return BalloonLights.uuidFor(shipUuid, "crew-mannequin");
    }

    static UUID twinUuid(UUID shipUuid) {
        return BalloonLights.uuidFor(shipUuid, "crew-mannequin+obs");
    }

    /** Model and skin of the pilot NPC, read before it is removed at take-off: {Model, PlayerSkinComponent} or null. */
    static Object[] lookOf(Store<EntityStore> store, Ref<EntityStore> npc) {
        if (npc == null || !npc.isValid()) {
            return null;
        }
        ModelComponent mc = store.getComponent(npc, ModelComponent.getComponentType());
        PlayerSkinComponent skin = store.getComponent(npc, PlayerSkinComponent.getComponentType());
        if (mc == null || mc.getModel() == null || skin == null) {
            return null;
        }
        return new Object[]{mc.getModel(), skin};
    }

    /**
     * Creates the mannequin playing Sit (or the given animation). Dual rendering (observer not null): the original on the ship entity, seen by the captain only,
     * and a twin on the observer entity, hidden from the captain. Single rendering: one mannequin on the ship entity, seen by all.
     * Returns null on failure. Never throws.
     */
    static Mannequin createMannequin(Store<EntityStore> store, UUID shipUuid, Ref<EntityStore> ship, Ref<EntityStore> observer,
                                     UUID captain, Vector3d seatWorld, Vector3f seatOffset, Object[] look, long now) {
        return createMannequin(store, shipUuid, ship, observer, captain, seatWorld, seatOffset, look, now, POSE_SIT);
    }

    /** Same, with the animation to play ({@link #POSE_SIT} for the stool, {@link #POSE_STAND} for the helm). */
    static Mannequin createMannequin(Store<EntityStore> store, UUID shipUuid, Ref<EntityStore> ship, Ref<EntityStore> observer,
                                     UUID captain, Vector3d seatWorld, Vector3f seatOffset, Object[] look, long now, String animId) {
        if (look == null || shipUuid == null || ship == null || !ship.isValid()) {
            return null;
        }
        try {
            Ref<EntityStore> ref = PassengerSeatView.spawn(store, (Model) look[0], mannequinUuid(shipUuid), seatWorld, ship, seatOffset,
                    (PlayerSkinComponent) look[1]);
            if (ref == null) {
                return null;
            }
            Mannequin m = new Mannequin();
            m.ref = ref;
            m.createdMs = now;
            m.lastAnimMs = now;
            m.animId = animId;
            if (observer != null && observer.isValid() && captain != null) {
                ViewFilter.only(ref, captain);
                m.twin = PassengerSeatView.spawn(store, (Model) look[0], twinUuid(shipUuid), seatWorld, observer, seatOffset,
                        (PlayerSkinComponent) look[1]);
                if (m.twin != null) {
                    ViewFilter.hide(m.twin, captain);
                }
            }
            return m;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: flight mannequin failed");
            Telemetry.error("airship.crew", e, null, null);
            return null;
        }
    }

    /**
     * Standalone mannequin (not mounted on anything) standing at a world point, turned to a yaw: the pilot of the moored shuttle. Same look
     * as the flight mannequin, never saved with the world. Returns null on failure. Never throws.
     */
    static Mannequin createStandalone(Store<EntityStore> store, UUID uuid, Vector3d at, float yaw, Object[] look, long now, String animId) {
        if (look == null || uuid == null) {
            return null;
        }
        try {
            Ref<EntityStore> ref = PassengerSeatView.spawnStandalone(store, (Model) look[0], uuid, at, yaw, (PlayerSkinComponent) look[1]);
            if (ref == null) {
                return null;
            }
            if (!ref.isValid()) { // stored away at once (section not ticking): no ghost kept
                LOGGER.at(Level.WARNING).log("Pilot: pier mannequin stored straight away by the world (inactive section)");
                return null;
            }
            Mannequin m = new Mannequin();
            m.ref = ref;
            m.createdMs = now;
            m.lastAnimMs = now;
            m.animId = animId;
            return m;
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: pier mannequin failed");
            Telemetry.error("airship.crew", e, null, null);
            return null;
        }
    }

    /** The dual rendering collapsed (no captain aboard): the twin goes with the observer entity, the original becomes visible to all. */
    static void collapseMannequin(Store<EntityStore> store, Mannequin m) {
        if (m == null) {
            return;
        }
        try {
            if (m.ref != null) {
                ViewFilter.clear(m.ref);
            }
            if (m.twin != null) {
                ViewFilter.clear(m.twin);
                if (m.twin.isValid()) {
                    store.removeEntity(m.twin, RemoveReason.REMOVE);
                }
                m.twin = null;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: flight mannequin not regrouped");
            Telemetry.error("airship.crew", e, null, null);
        }
    }

    /** Replays Sit (viewers learn the entity a tick or two later, and the animation can lapse). Never throws. */
    static void syncMannequin(Store<EntityStore> store, Mannequin m, long now) {
        if (m == null || (!PassengerSeatView.valid(m.ref) && !PassengerSeatView.valid(m.twin))) {
            return;
        }
        long due = m.anims == 0 ? MAN_ANIM_FIRST_MS : m.anims == 1 ? MAN_ANIM_SECOND_MS : MAN_ANIM_REPEAT_MS;
        if (now - (m.anims < 2 ? m.createdMs : m.lastAnimMs) < due) {
            return;
        }
        m.anims++;
        m.lastAnimMs = now;
        try {
            for (Ref<EntityStore> r : java.util.Arrays.asList(m.ref, m.twin)) {
                if (PassengerSeatView.valid(r)) {
                    AnimationUtils.playAnimation(r, BalloonFlight.Pose.DEFAULT.slot(), m.animId, true, store);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: seated pose of the mannequin failed");
            Telemetry.error("airship.crew", e, null, null);
        }
    }

    /**
     * Turns a mounted mannequin to a world yaw (a mannequin keeps the yaw of its own transform whatever the entity it is mounted on turns
     * to, so it is set explicitly, like PilotMannequin). An entity looks along (-sin yaw, -cos yaw): the bow heading of the ship
     * (yawSign * theta) makes the pilot face the helm in front of him. Both renderings. Never throws.
     */
    static void faceYaw(Store<EntityStore> store, Mannequin m, float yaw) {
        if (m == null) {
            return;
        }
        try {
            for (Ref<EntityStore> r : java.util.Arrays.asList(m.ref, m.twin)) {
                if (!PassengerSeatView.valid(r)) {
                    continue;
                }
                TransformComponent t = store.getComponent(r, TransformComponent.getComponentType());
                if (t != null && t.getRotation().y != yaw) {
                    t.setRotation(yawRotation(yaw));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: mannequin yaw not set");
            Telemetry.error("airship.crew", e, null, null);
        }
    }

    /** Removes the mannequin. Safe to call several times. Never throws. */
    static void removeMannequin(Store<EntityStore> store, Mannequin m) {
        if (m == null) {
            return;
        }
        try {
            for (Ref<EntityStore> r : java.util.Arrays.asList(m.ref, m.twin)) {
                if (r != null) {
                    ViewFilter.clear(r);
                    if (r.isValid()) {
                        store.removeEntity(r, RemoveReason.REMOVE);
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: flight mannequin not removed");
            Telemetry.error("airship.crew", e, null, null);
        }
        m.ref = null;
        m.twin = null;
    }

    /** Removes the mannequin an interrupted flight left in a world that stayed loaded (resume). Never throws. */
    static void removeMannequinOrphan(World world, Store<EntityStore> store, UUID shipUuid) {
        if (shipUuid == null) {
            return;
        }
        try {
            for (UUID id : new UUID[]{mannequinUuid(shipUuid), twinUuid(shipUuid)}) {
                Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(id);
                if (ref != null && ref.isValid()) {
                    ViewFilter.clear(ref);
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    LOGGER.at(Level.INFO).log("Pilot: flight mannequin left in the world removed (%s)", shipUuid);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Pilot: flight mannequin left in the world not removed");
            Telemetry.error("airship.crew", e, null, null);
        }
    }

    // ------------------------------------------------------------------ find, remove

    /** The pilot NPC of an airship, found by uuid, or null. */
    static Ref<EntityStore> refOf(World world, BalloonRegistry.Entry e) {
        if (e == null || e.crew() == null) {
            return null;
        }
        Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(e.crew().uuid());
        return ref != null && ref.isValid() ? ref : null;
    }

    /** Crew record of the pilot NPC with this uuid, or null. */
    static CrewRecord crewOfPilot(String world, UUID npc) {
        BalloonRegistry.Entry e = entryOfPilot(world, npc);
        return e != null ? e.crew() : null;
    }

    /** Registry entry of the airship whose pilot has this NPC uuid, or null. */
    static BalloonRegistry.Entry entryOfPilot(String world, UUID npc) {
        for (BalloonRegistry.Entry e : crewedEntriesAllWorlds()) {
            if (e.world().equals(world) && e.crew() != null && npc.equals(e.crew().uuid())) {
                return e;
            }
        }
        return null;
    }

    /** Entries of every ship kind that has a crew spec, in one world. */
    static List<BalloonRegistry.Entry> crewedEntries(World world) {
        List<BalloonRegistry.Entry> out = new ArrayList<>();
        for (Deployables.Kind k : Deployables.all()) {
            if (k.crew() != null) {
                out.addAll(BalloonRegistry.entriesOfKind(world, k.id));
            }
        }
        return out;
    }

    /** Entries of every ship kind that has a crew spec, in every world. */
    static List<BalloonRegistry.Entry> crewedEntriesAllWorlds() {
        List<BalloonRegistry.Entry> out = new ArrayList<>();
        for (Deployables.Kind k : Deployables.all()) {
            if (k.crew() != null) {
                out.addAll(BalloonRegistry.entriesOfKindAllWorlds(k.id));
            }
        }
        return out;
    }

    /** World-axis box around the ship (placeMin/placeMax of the entry's own kind, rotated with it): {min, max}. */
    private static Vector3d[] box(BalloonRegistry.Entry e) {
        Deployables.Kind kind = Deployables.get(e.kind());
        int[] lo = kind.placeMin;
        int[] hi = kind.placeMax;
        Vector3d logical = new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5);
        Vector3d min = new Vector3d(Double.MAX_VALUE), max = new Vector3d(-Double.MAX_VALUE);
        for (int i = 0; i < 8; i++) {
            Vector3d p = BalloonManager.prefabPoint(logical, e.rotation(),
                    new Vector3f((i & 1) == 0 ? lo[0] : hi[0] + 1, (i & 2) == 0 ? lo[1] : hi[1] + 1, (i & 4) == 0 ? lo[2] : hi[2] + 1));
            min.min(p);
            max.max(p);
        }
        return new Vector3d[]{min, max};
    }

    /** True when the pilot uuid is the registered crew of another entry, so this entry must not touch it. Pure. */
    static boolean ownedByOther(java.util.UUID npc, java.util.Collection<java.util.UUID> otherCrews) {
        return npc != null && otherCrews.contains(npc);
    }

    /** Pilot NPCs (role ROLE) inside the ship box, minus those registered to another crewed entry. On the world thread. */
    private static List<Ref<EntityStore>> pilotsIn(Store<EntityStore> store, BalloonRegistry.Entry e) {
        Vector3d[] b = box(e);
        java.util.Set<java.util.UUID> others = new java.util.HashSet<>();
        World w = store.getExternalData().getWorld();
        for (BalloonRegistry.Entry o : crewedEntries(w)) {
            if (o != e && !o.equals(e) && o.crew() != null && o.crew().uuid() != null) {
                others.add(o.crew().uuid());
            }
        }
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (Ref<EntityStore> ref : TargetUtil.getAllEntitiesInBox(b[0], b[1], store)) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null && ROLE.equals(npc.getRoleName())) {
                UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
                if (ownedByOther(id == null ? null : id.getUuid(), others)) {
                    continue;
                }
                out.add(ref);
            }
        }
        return out;
    }

    /** Removes the pilot NPC (by uuid and any other of the role inside the airship) and forgets it in the registry. On the world thread. */
    static void remove(Store<EntityStore> store, World world, BalloonRegistry.Entry e) {
        try {
            Ref<EntityStore> ref = refOf(world, e);
            if (ref != null) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }
            for (Ref<EntityStore> other : pilotsIn(store, e)) {
                if (other.isValid()) {
                    store.removeEntity(other, RemoveReason.REMOVE);
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: not removed at (%d, %d, %d)", e.x(), e.y(), e.z());
            Telemetry.error("airship.crew", ex, null, null);
        }
        PENDING.removeIf(p -> p.sameAirship(e));
        LAST_REWIRE.remove(key(e));
        clearWalkState(world, e);
        if (e.crew() != null) {
            BalloonRegistry.setCrew(world, new Vector3i(e.x(), e.y(), e.z()), e.rotation(), e.kind(), null);
        }
    }

    private static void removeDuplicates(Store<EntityStore> store, BalloonRegistry.Entry e, Ref<EntityStore> keep) {
        UUIDComponent keepId = store.getComponent(keep, UUIDComponent.getComponentType());
        if (keepId == null) {
            return;
        }
        int removed = 0;
        for (Ref<EntityStore> ref : pilotsIn(store, e)) {
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            if (id != null && !keepId.getUuid().equals(id.getUuid())) {
                store.removeEntity(ref, RemoveReason.REMOVE);
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.at(Level.INFO).log("Pilot: %d duplicate(s) removed at (%d, %d, %d)", removed, e.x(), e.y(), e.z());
        }
    }

    // ------------------------------------------------------------------ world load

    private static String key(BalloonRegistry.Entry e) {
        return e.world() + ":" + e.x() + "," + e.y() + "," + e.z() + "," + e.rotation();
    }

    /** Airship waiting for its pilot entity (world load or landing). readyMs: when its entities were first seen loaded (0: not yet). */
    private static boolean hasPending(World world, BalloonRegistry.Entry e) {
        for (Pending p : PENDING) {
            if (p.world == world && p.sameAirship(e)) {
                return true;
            }
        }
        return false;
    }

    private static final long SPAWN_RETRY_MS = 5_000;

    private static final class Pending {
        final World world;
        final BalloonRegistry.Entry entry;
        long readyMs;

        Pending(World world, BalloonRegistry.Entry entry) {
            this.world = world;
            this.entry = entry;
        }

        boolean sameAirship(BalloonRegistry.Entry other) {
            return entry.equals(other);
        }
    }

    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> LAST_REWIRE = new ConcurrentHashMap<>();

    /** World load and plugin start: every landed airship with a pilot gets it back (found by uuid, else adopted, else created). */
    static void recoverWorld(World world) {
        for (BalloonRegistry.Entry e : crewedEntries(world)) {
            if (e.crew() != null) {
                ensure(world, e);
            }
        }
    }

    /**
     * Loads the sections of the pilot's post, then queues the airship for tick, which waits for the entities of that section to be
     * in the world before finding, adopting or creating the pilot (as StationMaster does: deciding earlier doubled the pilot at
     * every restart). Used by recoverWorld and after each landing.
     */
    static void ensure(World world, BalloonRegistry.Entry e) {
        try {
            if (e.crew() == null) {
                return;
            }
            Vector3d p = postPoint(e, e.crew().post());
            int bx = (int) Math.floor(p.x), by = (int) Math.floor(p.y), bz = (int) Math.floor(p.z);
            SectionBlocks.loadSections(world.getChunkStore(), bx, by - 2, bz, bx, by + 2, bz).whenComplete((c, t) -> {
                try {
                    world.execute(() -> {
                        PENDING.removeIf(q -> q.sameAirship(e));
                        PENDING.add(new Pending(world, e));
                    });
                } catch (RuntimeException ex) {
                    LOGGER.at(Level.WARNING).withCause(ex).log("World %s stopped: pilot not found", world.getName());
                }
            });
        } catch (RuntimeException ex) {
            LOGGER.at(Level.INFO).log("World %s not ready yet: pilot resumed at world start (%s)", world.getName(), ex.getClass().getSimpleName());
        }
    }

    /** Each tick of a world: resolves pilots waiting for their entity, then rewires the present ones every 2 s. */
    static void tick(Store<EntityStore> store, World world) {
        resolvePending(store, world);
        rewire(store, world);
        walkTick(store, world);
        AirshipDesk.tick(store, world);
    }

    private static void resolvePending(Store<EntityStore> store, World world) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Pending p : new ArrayList<>(PENDING)) {
            if (p.world != world) {
                continue;
            }
            // The entry may have changed since it was queued (crew uuid): always work on the registry's current one.
            BalloonRegistry.Entry e = BalloonRegistry.get(world, new Vector3i(p.entry.x(), p.entry.y(), p.entry.z()),
                    p.entry.rotation(), p.entry.kind());
            if (e == null || e.crew() == null) {
                PENDING.remove(p);
                continue;
            }
            try {
                Ref<EntityStore> ref = refOf(world, e);
                if (ref != null) {
                    PENDING.remove(p);
                    wire(store, ref, e, e.crew());
                    removeDuplicates(store, e, ref);
                    standUp(store, ref, e.crew().uuid()); // drops a MountedComponent saved with the world
                    if (e.crew().post() == CrewRecord.Post.STOOL) {
                        seat(store, ref, e, e.crew().uuid());
                    }
                    continue;
                }
                Vector3d pp = postPoint(e, e.crew().post());
                if (!SectionBlocks.entitiesLoaded(world.getChunkStore(), (int) Math.floor(pp.x), (int) Math.floor(pp.y), (int) Math.floor(pp.z))) {
                    p.readyMs = 0; // nobody near: the entities of the section are stored away, nothing can be decided
                    continue;
                }
                if (p.readyMs == 0) {
                    p.readyMs = now;
                }
                if (now - p.readyMs < ENTITY_GRACE_MS) {
                    continue;
                }
                PENDING.remove(p);
                Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
                Ref<EntityStore> orphan = pilotsIn(store, e).stream().findFirst().orElse(null);
                if (orphan != null) {
                    UUIDComponent id = store.getComponent(orphan, UUIDComponent.getComponentType());
                    if (id != null) {
                        forgetSkin(e.crew().uuid());
                        BalloonRegistry.Entry updated = BalloonRegistry.setCrew(world, origin, e.rotation(), e.kind(), e.crew().withUuid(id.getUuid()));
                        wire(store, orphan, updated != null ? updated : e, e.crew());
                        standUp(store, orphan, id.getUuid());
                        LOGGER.at(Level.INFO).log("Pilot resumed in place at (%d, %d, %d) (%s)", e.x(), e.y(), e.z(), id.getUuid());
                        removeDuplicates(store, e, orphan);
                        continue;
                    }
                }
                UUID created = spawn(store, world, e, e.crew());
                if (created != null) {
                    forgetSkin(e.crew().uuid());
                    BalloonRegistry.setCrew(world, origin, e.rotation(), e.kind(), e.crew().withUuid(created));
                } else {
                    // Spawn failed: try again later instead of leaving the airship without a pilot until the next world start.
                    Pending retry = new Pending(world, e);
                    retry.readyMs = now - ENTITY_GRACE_MS + SPAWN_RETRY_MS;
                    PENDING.add(retry);
                    LOGGER.at(Level.WARNING).log("Pilot not created at (%d, %d, %d), retrying in %d s", e.x(), e.y(), e.z(), SPAWN_RETRY_MS / 1000);
                }
            } catch (RuntimeException ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("Pilot not restored at (%d, %d, %d)", e.x(), e.y(), e.z());
                Telemetry.error("airship.crew", ex, null, null);
            }
        }
    }

    /**
     * The game rebuilds the role (and resets Use to *UseNPC) when the chunk is reloaded during a session: every 2 s per airship,
     * if the pilot entity is present, its outfit and Use wiring are checked, and stacked duplicates of the role are removed.
     */
    private static void rewire(Store<EntityStore> store, World world) {
        long now = System.currentTimeMillis();
        for (BalloonRegistry.Entry e : crewedEntries(world)) {
            if (e.crew() == null) {
                continue;
            }
            String k = key(e);
            Long last = LAST_REWIRE.get(k);
            if (last != null && now - last < REWIRE_INTERVAL_MS) {
                continue;
            }
            LAST_REWIRE.put(k, now);
            try {
                Ref<EntityStore> ref = refOf(world, e);
                if (ref == null) {
                    // Pilot lost during the session: queue the lookup again (adopt an orphan or create one, after the grace wait).
                    Vector3d pp = postPoint(e, e.crew().post());
                    if (!hasPending(world, e)
                            && SectionBlocks.entitiesLoaded(world.getChunkStore(), (int) Math.floor(pp.x), (int) Math.floor(pp.y), (int) Math.floor(pp.z))) {
                        ensure(world, e);
                    }
                    continue;
                }
                ensureSkin(store, ref, e.crew());
                Interactions i = store.getComponent(ref, Interactions.getComponentType());
                if (i == null || !ROOT_INTERACTION_ID.equals(i.getInteractionId(InteractionType.Use))) {
                    wire(store, ref, e, e.crew());
                }
                removeDuplicates(store, e, ref);
            } catch (RuntimeException ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("Pilot wiring not checked at (%d, %d, %d)", e.x(), e.y(), e.z());
                Telemetry.error("airship.crew", ex, null, null);
            }
        }
    }

    // ------------------------------------------------------------------ walking between the posts

    /** State of the pilot of an airship, for the orders window. */
    enum CrewState { AT_HELM, WALKING, SEATED, ABSENT }

    private static final double STEP_REACHED = 0.9;
    private static final double REST_DRIFT = 1.5;
    private static final double DOOR_NEAR = 3.0;
    private static final double DOOR_FAR = 2.5;
    private static final double DOOR_LEG = 1.5;
    private static final double HELM_APPROACH = 1.5;
    private static final long WALK_TIMEOUT_MS = 45_000;
    /** Logs the walk of the pilot once per second (position, distance, mount and sitting flags, leash). Set to false to silence. */
    private static final boolean WALK_DIAGNOSTICS = true;
    private static final long WALK_STEP_MS = 250; // the walk logic runs about 4 times a second, not every tick
    private static final double HELM_FREE_HORIZONTAL = 0.8;
    private static final double HELM_FREE_VERTICAL = 1.5;

    /** A walk in progress: remaining door legs, final target, and the "make room" wait at the helm. */
    private static final class Walk {
        final CrewRecord.Post target;
        final List<Vector3d> legs = new ArrayList<>();
        Vector3d finalTarget;
        final long startMs = System.currentTimeMillis();
        boolean waiting;
        /** Set by standUp: the leash is applied again on the next step (a component removal may only land after the current tick). */
        boolean leashDirty;
        /** Diagnostics: last log time, position and ms at the start of the walk window. */
        long lastLogMs;
        Vector3d lastLogPos;
        long windowStartMs;
        Vector3d windowStartPos;
        boolean stuckWarned;
        long stuckWarnMs;

        Walk(CrewRecord.Post target) {
            this.target = target;
        }
    }

    private static final Map<String, Walk> WALKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_STEP = new ConcurrentHashMap<>();
    /** Airships whose door was opened by the mod, with the side (sign along the door axis) the pilot opened it from. */
    private static final Map<String, Double> DOORS_OPENED = new ConcurrentHashMap<>();
    /**
     * Pilots (npc uuid) seated on the stool: leash on the stool point and looping Sit animation. A pilot is never mounted on
     * the stool block: the game crashes the world when the section of a block-mounted NPC unloads.
     */
    private static final Set<UUID> SEATED = ConcurrentHashMap.newKeySet();

    /** True if no player stands within 0.8 block (horizontal) and 1.5 block (vertical) of the helm point. Pure. */
    static boolean helmCellFree(List<Vector3d> playerFeet, Vector3d helmPoint) {
        for (Vector3d f : playerFeet) {
            double dx = f.x - helmPoint.x, dz = f.z - helmPoint.z;
            if (Math.sqrt(dx * dx + dz * dz) < HELM_FREE_HORIZONTAL && Math.abs(f.y - helmPoint.y) < HELM_FREE_VERTICAL) {
                return false;
            }
        }
        return true;
    }

    static CrewState state(World world, BalloonRegistry.Entry e) {
        if (e == null || e.crew() == null || refOf(world, e) == null) {
            return CrewState.ABSENT;
        }
        if (WALKS.containsKey(key(e))) {
            return CrewState.WALKING;
        }
        if (e.crew().post() == CrewRecord.Post.STOOL) {
            // Seated only once the seat was applied: a pilot standing next to the stool is not seated.
            return SEATED.contains(e.crew().uuid()) ? CrewState.SEATED : CrewState.WALKING;
        }
        return CrewState.AT_HELM;
    }

    private static void clearWalkState(World world, BalloonRegistry.Entry e) {
        String k = key(e);
        WALKS.remove(k);
        LAST_STEP.remove(k);
        if (DOORS_OPENED.remove(k) != null) {
            setDoor(world, e, false);
        }
        if (e.crew() != null && e.crew().uuid() != null) {
            SEATED.remove(e.crew().uuid());
            forgetSkin(e.crew().uuid());
        }
    }

    /**
     * Makes the pilot really stand up before a walk: removes any MountedComponent (a persisted one survives a restart),
     * stops the Sit animation, clears the sitting/mounting movement flags and drops the seated state.
     * Used by every walk start. Never throws. The leash is applied again on the next step (see Walk.leashDirty).
     */
    static void standUp(Store<EntityStore> store, Ref<EntityStore> ref, UUID npc) {
        try {
            if (npc != null) {
                SEATED.remove(npc);
                SIT_STATE.remove(npc);
            }
            if (ref == null || !ref.isValid()) {
                return;
            }
            store.tryRemoveComponent(ref, Frozen.getComponentType()); // frozen while seated: the role ticks again, and gravity with it
            MountUtil.unseat(store, ref);
            AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, store);
            MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
            if (msc != null && msc.getMovementStates() != null) {
                msc.getMovementStates().sitting = false;
                msc.getMovementStates().mounting = false;
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: survey failed");
            Telemetry.error("airship.crew", ex, null, null);
        }
    }

    /** Diagnostics of the walk (log once per second, warning when stuck). Never throws. */
    private static boolean walkDiagnostics(Store<EntityStore> store, Ref<EntityStore> ref, NPCEntity npc, Walk walk, Vector3d p, long now) {
        try {
            if (walk.windowStartPos == null) {
                walk.windowStartPos = new Vector3d(p);
                walk.windowStartMs = now;
                walk.lastLogPos = new Vector3d(p);
                walk.lastLogMs = now;
                return false;
            }
            if (p.distance(walk.windowStartPos) >= 0.3) { // progress: the stuck window starts again here
                walk.windowStartPos = new Vector3d(p);
                walk.windowStartMs = now;
                walk.stuckWarned = false;
            }
            boolean oncePerSecond = WALK_DIAGNOSTICS && now - walk.lastLogMs >= 1000;
            boolean stuck = !walk.stuckWarned && now - walk.windowStartMs >= 5000 && p.distance(walk.windowStartPos) < 0.3;
            boolean stuckLong = walk.stuckWarned && now - walk.stuckWarnMs >= 10_000;
            if (!oncePerSecond && !stuck) {
                return stuckLong;
            }
            Vector3d target = walk.legs.isEmpty() ? walk.finalTarget : walk.legs.get(0);
            MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
            boolean sitting = msc != null && msc.getMovementStates() != null && msc.getMovementStates().sitting;
            boolean mounting = msc != null && msc.getMovementStates() != null && msc.getMovementStates().mounting;
            boolean mounted = store.getComponent(ref, MountedComponent.getComponentType()) != null;
            Vector3d leash = npc.getLeashPoint();
            double progress = walk.lastLogPos == null ? 0 : p.distance(walk.lastLogPos);
            String line = String.format("walk %s position (%.1f, %.1f, %.1f), %.1f block(s) left toward (%.1f, %.1f, %.1f), mounted %s, sitting %s, mounting %s, leash %s, progress %.2f block(s)",
                    walk.target.key, p.x, p.y, p.z, target == null ? -1.0 : p.distance(target),
                    target == null ? 0.0 : target.x, target == null ? 0.0 : target.y, target == null ? 0.0 : target.z,
                    mounted, sitting, mounting,
                    leash == null ? "null" : String.format("(%.1f, %.1f, %.1f)", leash.x, leash.y, leash.z), progress);
            if (stuck) {
                walk.stuckWarned = true;
                walk.stuckWarnMs = now;
                LOGGER.at(Level.WARNING).log("Pilot: stuck for 5 s, %s", line);
            }
            if (oncePerSecond) {
                LOGGER.at(Level.INFO).log("Pilot: %s", line);
                walk.lastLogMs = now;
                walk.lastLogPos = new Vector3d(p);
            }
            return stuckLong;
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: walk log failed");
            Telemetry.error("airship.crew", ex, null, null);
            return false;
        }
    }

    private static void setLeash(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d p) {
        NPCEntity n = store.getComponent(ref, NPCEntity.getComponentType());
        if (n == null) {
            return;
        }
        n.saveLeashInformation(p, new Rotation3f());
        n.setLeashPoint(new Vector3d(p));
    }

    // ---- sitting pose: "Sit" is played and replayed (cadence of PassengerSeatView)
    private static final long SIT_FIRST_MS = 400;
    private static final long SIT_SECOND_MS = 1500;
    private static final long SIT_REPEAT_MS = 4000;
    /** Per pilot: {start ms, replays done, last replay ms}. */
    private static final Map<UUID, long[]> SIT_STATE = new ConcurrentHashMap<>();

    private static void startSit(Ref<EntityStore> ref, Store<EntityStore> store, UUID npc) {
        long now = System.currentTimeMillis();
        SIT_STATE.put(npc, new long[]{now, 0, now});
        try {
            AnimationUtils.playAnimation(ref, AnimationSlot.Movement, "Sit", true, store);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: seated pose not played");
            Telemetry.error("airship.crew", ex, null, null);
        }
    }

    /** Replays "Sit" while the pilot is on the stool, so players arriving later see it. Never throws. */
    private static void replaySit(Ref<EntityStore> ref, Store<EntityStore> store, UUID npc, long now) {
        try {
            long[] st = SIT_STATE.computeIfAbsent(npc, u -> new long[]{now, 0, now});
            long due = st[1] == 0 ? SIT_FIRST_MS : st[1] == 1 ? SIT_SECOND_MS : SIT_REPEAT_MS;
            if (now - (st[1] < 2 ? st[0] : st[2]) < due) {
                return;
            }
            st[1]++;
            st[2] = now;
            AnimationUtils.playAnimation(ref, AnimationSlot.Movement, "Sit", true, store);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: seated pose not replayed");
            Telemetry.error("airship.crew", ex, null, null);
        }
    }

    /** Stops the sitting pose of a pilot that stands up. Never throws. */
    private static void stopSit(Ref<EntityStore> ref, Store<EntityStore> store, UUID npc) {
        if (SIT_STATE.remove(npc) != null && ref != null) {
            try {
                AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, store);
            } catch (RuntimeException ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: seated pose not stopped");
                Telemetry.error("airship.crew", ex, null, null);
            }
        }
    }

    /** Point of the seated pilot's feet: the stool point raised by the seat height (the stool block has no collision). */
    static Vector3d seatPoint(Vector3d stool, double seatHeight) {
        return new Vector3d(stool.x, stool.y + seatHeight, stool.z);
    }

    /**
     * Seats the pilot on the stool: teleport to the stool point raised by the seat height, leash on that point, movement states
     * at rest, Frozen (no role tick, so no gravity: the pilot stays in the air at seat height), looping Sit animation, seated
     * state. Never throws.
     */
    static void seat(Store<EntityStore> store, Ref<EntityStore> ref, BalloonRegistry.Entry e, UUID npc) {
        try {
            Vector3d stool = seatPoint(postPoint(e, Deployables.crewOf(e.kind()).rest()), BalloonManager.get().seatHeight());
            SEATED.add(npc);
            store.putComponent(ref, Teleport.getComponentType(), new Teleport(stool, yawRotation(bowYaw(e))).withoutVelocityReset());
            setLeash(store, ref, stool);
            MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
            if (msc != null && msc.getMovementStates() != null) {
                MovementStates ms = msc.getMovementStates();
                ms.walking = false;
                ms.running = false;
                ms.sprinting = false;
                ms.jumping = false;
                ms.falling = false;
                ms.fallingFar = false;
                ms.climbing = false;
                ms.flying = false;
                ms.swimming = false;
                ms.swimJumping = false;
                ms.crouching = false;
                ms.idle = true;
                ms.horizontalIdle = true;
            }
            store.putComponent(ref, Frozen.getComponentType(), Frozen.get());
            startSit(ref, store, npc);
            LOGGER.at(Level.INFO).log("Pilot: seated on the stool at (%.1f, %.1f, %.1f)", stool.x, stool.y, stool.z);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: seating on the stool failed");
            Telemetry.error("airship.crew", ex, null, null);
        }
    }

    // ---- door

    private static BlockType doorBlock(World world, BalloonRegistry.Entry e) {
        int[] door = Deployables.crewOf(e.kind()).door();
        if (door == null) {
            return null; // this ship has no cabin door
        }
        Vector3d c = postPoint(e, door);
        return SectionBlocks.typeAt(new SectionReader(world.getChunkStore()), (int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z));
    }

    /** Opens or closes the cabin door (same state change as DoorInteraction: OpenDoorOut / CloseDoorOut). False if nothing changed. */
    private static boolean setDoor(World world, BalloonRegistry.Entry e, boolean open) {
        BlockType bt = doorBlock(world, e);
        if (bt == null || bt.getId() == null || !bt.getId().contains("Furniture_Crude_Door")) {
            LOGGER.at(Level.WARNING).log("Pilot: door not found at the expected cell (block %s)", bt == null ? "null" : bt.getId());
            return false;
        }
        if (bt.getId().contains("OpenDoor") == open) {
            return false;
        }
        int[] door = Deployables.crewOf(e.kind()).door();
        if (door == null) {
            return false;
        }
        Vector3d c = postPoint(e, door);
        boolean ok = SectionBlocks.setStateAt(world.getChunkStore(), (int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z),
                bt, open ? "OpenDoorOut" : "CloseDoorOut");
        LOGGER.at(Level.INFO).log("Pilot: door %s (write %s)", open ? "opened" : "closed", ok);
        return ok;
    }

    /** Unit vector along the door axis (prefab +z, rotated with the airship), world frame. */
    private static Vector3d doorAlong(BalloonRegistry.Entry e) {
        int[] door = Deployables.crewOf(e.kind()).door();
        if (door == null) {
            return new Vector3d(0, 0, 0); // no door: unused (callers skip the door logic)
        }
        Vector3d dc = postPoint(e, door);
        return postPoint(e, new int[]{door[0], door[1], door[2] + 1}).sub(dc);
    }

    /**
     * Aisle of the cabin between the stool and the door, prefab frame (integer = cell centre): the counter stands between the
     * stool and the straight line to the door. z = 4.4 keeps the pilot 0.9 block off the counter edge (z 3.5) and off the carpet
     * (z 4.5 and beyond), and the east point is on x = 0 so the approach to the door is straight north.
     */
    private static final double[] AISLE_WEST = {-1, 8, 4.4};
    private static final double[] AISLE_EAST = {0, 8, 4.4};
    private static final double AISLE_SKIP = 0.9;

    /** Walk plan of a ship without obstacles between its posts (the steamboat): the pilot walks straight to the post. Pure. */
    static List<double[]> straightLegs(double[] from, CrewRecord.Post target) {
        return List.of();
    }

    /**
     * Waypoints (prefab frame, cell centres) of a walk from a point to a post, before the final target. Pure.
     * Door legs: when the door (z = 2) lies between the two, a leg DOOR_LEG either side. Aisle: the stool is walled in by the
     * counter, so every walk leaving it or reaching it goes through the aisle points (-1,8,4.4) then (0,8,4.4) (reverse order toward
     * the stool); aisle points within 0.9 block of the start are skipped. Helm legs of the old rule are kept unchanged.
     */
    static List<double[]> walkLegs(double[] from, CrewRecord.Post target) {
        Deployables.CrewSpec spec = Deployables.crewOf(Deployables.AIRSHIP_KIND);
        final int[] HELM = spec.helm(), STOOL = spec.rest(), DOOR = spec.door();
        List<double[]> legs = new ArrayList<>();
        double[] dest = target == CrewRecord.Post.HELM ? new double[]{HELM[0], HELM[1], HELM[2]} : new double[]{STOOL[0], STOOL[1], STOOL[2]};
        boolean fromStoolSide = from[2] > DOOR[2];
        boolean toStoolSide = dest[2] > DOOR[2];
        List<double[]> aisleOut = List.of(AISLE_WEST, AISLE_EAST); // stool -> door
        if (fromStoolSide && target == CrewRecord.Post.HELM) {
            for (double[] a : aisleOut) {
                // Skip aisle cells already reached or behind the start (west of it).
                if (Math.hypot(a[0] - from[0], a[2] - from[2]) > AISLE_SKIP && a[0] > from[0] - 0.1) {
                    legs.add(a.clone());
                }
            }
        }
        if (fromStoolSide != toStoolSide) {
            double sign = fromStoolSide ? 1 : -1;
            legs.add(new double[]{DOOR[0], DOOR[1], DOOR[2] + sign * DOOR_LEG});
            legs.add(new double[]{DOOR[0], DOOR[1], DOOR[2] - sign * DOOR_LEG});
        }
        if (toStoolSide && target == CrewRecord.Post.STOOL) {
            for (int i = aisleOut.size() - 1; i >= 0; i--) { // door -> stool: east cell first, then west
                double[] a = aisleOut.get(i);
                // Starting already on the stool side: skip cells reached or behind the start (east of it).
                boolean ahead = !fromStoolSide || (Math.hypot(a[0] - from[0], a[2] - from[2]) > AISLE_SKIP && a[0] < from[0] + 0.1);
                if (ahead) {
                    legs.add(a.clone());
                }
            }
        }
        return legs;
    }

    // ---- ordering a walk

    /**
     * Sends the pilot to a post. The target post is written to the registry at once (it is what survives a restart), the pilot
     * is lifted off the stool if seated, and its leash is set on the first step: when the cabin door lies between the pilot
     * and the target, two steps 1.5 block either side of the door, then the target. False if the pilot is absent.
     */
    static boolean orderWalk(Store<EntityStore> store, World world, BalloonRegistry.Entry e, CrewRecord.Post target) {
        Ref<EntityStore> ref = refOf(world, e);
        if (ref == null) {
            return false;
        }
        CrewRecord crew = e.crew();
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        BalloonRegistry.setCrew(world, origin, e.rotation(), e.kind(), crew.withPost(target));
        // Stand up: leave the seated pose (every walk start goes through standUp).
        standUp(store, ref, crew.uuid());
        TransformComponent tr = store.getComponent(ref, TransformComponent.getComponentType());
        if (tr == null) {
            LOGGER.at(Level.WARNING).log("Pilot: position not found, order ignored at (%d, %d, %d)", e.x(), e.y(), e.z());
            return false;
        }
        Vector3d from = new Vector3d(tr.getPosition());
        Vector3d dest = postPoint(e, target);
        Walk w = new Walk(target);
        w.finalTarget = dest;
        Vector3d logical = new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5);
        Vector3f lf = e.rotation().toInverse().rotateYaw(
                new Vector3f((float) (from.x - logical.x), (float) (from.y - logical.y), (float) (from.z - logical.z)), new Vector3f());
        for (double[] leg : Deployables.crewOf(e.kind()).walk().legs(new double[]{lf.x, lf.y, lf.z}, target)) {
            w.legs.add(postPoint(e, leg));
        }
        setLeash(store, ref, w.legs.isEmpty() ? dest : w.legs.get(0));
        w.leashDirty = true; // applied again on the next step, once the removal of a MountedComponent has landed
        WALKS.put(key(e), w);
        LOGGER.at(Level.INFO).log("Pilot: walking to %s at (%d, %d, %d), %d intermediate leg(s)", target.key, e.x(), e.y(), e.z(), w.legs.size());
        return true;
    }

    /**
     * Puts the pilot back at a post at once (intended exception to "the pilot never teleports": stuck walk, walk timeout, reset
     * command): stands up, clears the walk (and closes the door the mod opened), teleports to the post point
     * facing the bow, and seats the pilot for the stool. On the world thread. Never throws.
     */
    static void teleportToPost(Store<EntityStore> store, World world, BalloonRegistry.Entry e, Ref<EntityStore> ref, CrewRecord.Post post, String why) {
        try {
            UUID id = e.crew().uuid();
            standUp(store, ref, id);
            clearWalkState(world, e);
            Vector3d pp = postPoint(e, post);
            Teleport tp = new Teleport(pp, yawRotation(bowYaw(e))).withoutVelocityReset();
            store.putComponent(ref, Teleport.getComponentType(), tp);
            setLeash(store, ref, pp);
            if (post == CrewRecord.Post.STOOL) {
                seat(store, ref, e, id);
            }
            LOGGER.at(Level.WARNING).log("Pilot: %s, teleported to post %s at (%.1f, %.1f, %.1f)", why, post.key, pp.x, pp.y, pp.z);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: teleport to post failed");
            Telemetry.error("airship.crew", ex, null, null);
        }
    }

    /** Result of resetPilot, for the command messages. */
    enum ResetResult { DONE, NONE, NOT_OWNER }

    /** /orbishorizon airship pilot reset: nearest crewed airship within 48 blocks, owner or op only. On the world thread. */
    static ResetResult resetPilot(Store<EntityStore> store, World world, Vector3d at, UUID player, boolean op) {
        BalloonRegistry.Entry best = null;
        double bestD = 48.0;
        for (BalloonRegistry.Entry e : crewedEntries(world)) {
            if (e.crew() == null) {
                continue;
            }
            double d = at.distance(new Vector3d(e.x() + 0.5, e.y(), e.z() + 0.5));
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best == null) {
            return ResetResult.NONE;
        }
        if (!op && !player.equals(best.owner())) {
            return ResetResult.NOT_OWNER;
        }
        Ref<EntityStore> ref = refOf(world, best);
        LOGGER.at(Level.INFO).log("Pilot: return to post requested at (%d, %d, %d), pilot %s", best.x(), best.y(), best.z(), ref == null ? "absent" : "present");
        if (ref == null) {
            ensure(world, best);
            return ResetResult.DONE;
        }
        teleportToPost(store, world, best, ref, best.crew().post(), "returned to post");
        return ResetResult.DONE;
    }

    // ---- tick

    private static void walkTick(Store<EntityStore> store, World world) {
        long now = System.currentTimeMillis();
        for (BalloonRegistry.Entry e : crewedEntries(world)) {
            if (e.crew() == null || e.crew().uuid() == null) {
                continue;
            }
            String k = key(e);
            Long last = LAST_STEP.get(k);
            if (last != null && now - last < WALK_STEP_MS) {
                continue;
            }
            LAST_STEP.put(k, now);
            try {
                pilotStep(store, world, e, now);
            } catch (RuntimeException ex) {
                LOGGER.at(Level.WARNING).withCause(ex).log("Pilot: walk failed at (%d, %d, %d)", e.x(), e.y(), e.z());
                Telemetry.error("airship.crew", ex, null, null);
            }
        }
    }

    private static void pilotStep(Store<EntityStore> store, World world, BalloonRegistry.Entry e, long now) {
        Ref<EntityStore> ref = refOf(world, e);
        if (ref == null) {
            return;
        }
        TransformComponent tr = store.getComponent(ref, TransformComponent.getComponentType());
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (tr == null || npc == null) {
            return;
        }
        String k = key(e);
        UUID id = e.crew().uuid();
        Vector3d p = new Vector3d(tr.getPosition());
        Walk walk = WALKS.get(k);
        CrewRecord.Post post = e.crew().post();
        Vector3d postPoint = postPoint(e, post);
        if (store.getComponent(ref, MountedComponent.getComponentType()) != null) {
            // A pilot is never mounted (a block mount crashes the world when its section unloads): a MountedComponent saved
            // with the world, or one that came back, is removed here, in the world tick.
            standUp(store, ref, id);
            if (walk != null) {
                walk.leashDirty = true;
            }
        }
        boolean seated = SEATED.contains(id);
        if (seated && walk == null && post == CrewRecord.Post.STOOL) {
            replaySit(ref, store, id, now);
        } else if (!seated) {
            stopSit(ref, store, id); // stood up (or never seated): no stale sitting pose
        }
        if (!seated && store.getComponent(ref, Frozen.getComponentType()) != null) {
            // Frozen is saved with the entity: a pilot that is not seated according to the mod is never left frozen.
            store.tryRemoveComponent(ref, Frozen.getComponentType());
            if (walk != null) {
                walk.leashDirty = true;
            }
        }

        // The only teleport: back to the post when the pilot is outside the airship (fell, got stuck).
        Vector3d[] box = box(e);
        if (!seated && (p.x < box[0].x || p.x > box[1].x || p.y < box[0].y || p.y > box[1].y || p.z < box[0].z || p.z > box[1].z)) {
            LOGGER.at(Level.WARNING).log("Pilot: outside the airship at (%.1f, %.1f, %.1f), returned to post %s", p.x, p.y, p.z, post.key);
            WALKS.remove(k);
            Teleport tp = new Teleport(new Vector3d(postPoint), new Rotation3f(tr.getRotation())).withoutVelocityReset();
            store.putComponent(ref, Teleport.getComponentType(), tp);
            setLeash(store, ref, postPoint);
            return;
        }

        // Door: opened when the pilot walks towards it, closed (only if the mod opened it) once he is through or stopped.
        int[] doorCell = Deployables.crewOf(e.kind()).door();
        if (doorCell != null) {
            Vector3d dc = postPoint(e, doorCell);
            Vector3d along = doorAlong(e);
            double doorDist = new Vector3d(p).sub(dc).length();
            double side = along.dot(new Vector3d(p).sub(dc));
            if (walk != null && doorDist < DOOR_NEAR) {
                BlockType bt = doorBlock(world, e);
                if (bt != null && bt.getId() != null && bt.getId().contains("CloseDoor") && setDoor(world, e, true)) {
                    DOORS_OPENED.put(k, Math.signum(side));
                }
            } else if (DOORS_OPENED.containsKey(k) && doorDist > DOOR_FAR && (walk == null || Math.signum(side) != DOORS_OPENED.get(k))) {
                DOORS_OPENED.remove(k);
                setDoor(world, e, false);
            }
        }

        if (walk != null) {
            stepWalk(store, world, e, ref, npc, walk, p, now);
            return;
        }
        // At rest: seated pilots are kept on the stool, the others at their post.
        if (post == CrewRecord.Post.STOOL && !seated && p.distance(postPoint) <= REST_DRIFT) {
            seat(store, ref, e, id);
            return;
        }
        if (!seated && p.distance(postPoint) > REST_DRIFT) {
            LOGGER.at(Level.INFO).log("Pilot: moved %.1f block(s) from the post, returning to post %s", p.distance(postPoint), post.key);
            orderWalk(store, world, e, post);
        }
    }

    private static void stepWalk(Store<EntityStore> store, World world, BalloonRegistry.Entry e, Ref<EntityStore> ref, NPCEntity npc,
                                 Walk walk, Vector3d p, long now) {
        String k = key(e);
        if (walk.leashDirty) {
            walk.leashDirty = false;
            setLeash(store, ref, walk.legs.isEmpty() ? walk.finalTarget : walk.legs.get(0));
        }
        if (walk.waiting) { // waiting for the captain to leave the helm is not being stuck
            walk.windowStartPos = null;
            walk.stuckWarned = false;
        }
        boolean stuckLong = walkDiagnostics(store, ref, npc, walk, p, now);
        if (stuckLong) {
            teleportToPost(store, world, e, ref, walk.target, "stuck");
            return;
        }
        if (now - walk.startMs > WALK_TIMEOUT_MS) {
            LOGGER.at(Level.WARNING).log("Pilot: still not at post %s after 45 s", walk.target.key);
            teleportToPost(store, world, e, ref, walk.target, "timeout");
            return;
        }
        if (walk.waiting) {
            if (!helmCellFree(playerFeet(world), walk.finalTarget)) {
                return;
            }
            walk.waiting = false;
            setLeash(store, ref, walk.finalTarget);
            return;
        }
        // Approaching an occupied helm: wait where we stand, tell the captain once.
        if (walk.target == CrewRecord.Post.HELM && walk.legs.isEmpty() && p.distance(walk.finalTarget) < HELM_APPROACH
                && !helmCellFree(playerFeet(world), walk.finalTarget)) {
            walk.waiting = true;
            setLeash(store, ref, p);
            if (e.owner() != null) {
                for (PlayerRef pr : world.getPlayerRefs()) {
                    if (e.owner().equals(pr.getUuid())) {
                        pr.sendMessage(Texts.pilot("crew.helm.makeRoom", e.crew()));
                    }
                }
            }
            return;
        }
        if (npc.getLeashPoint().distance(p) >= STEP_REACHED) {
            return;
        }
        if (!walk.legs.isEmpty()) {
            walk.legs.remove(0);
            setLeash(store, ref, walk.legs.isEmpty() ? walk.finalTarget : walk.legs.get(0));
            return;
        }
        WALKS.remove(k);
        LOGGER.at(Level.INFO).log("Pilot: arrived at post %s in %.0f s", walk.target.key, (now - walk.startMs) / 1000.0);
        if (walk.target == CrewRecord.Post.STOOL) {
            seat(store, ref, e, e.crew().uuid());
        }
    }

    private static List<Vector3d> playerFeet(World world) {
        List<Vector3d> feet = new ArrayList<>();
        for (PlayerRef pr : world.getPlayerRefs()) {
            Ref<EntityStore> r = pr.getReference();
            if (r == null || !r.isValid()) {
                continue;
            }
            TransformComponent t = r.getStore().getComponent(r, TransformComponent.getComponentType());
            if (t != null) {
                feet.add(new Vector3d(t.getPosition()));
            }
        }
        return feet;
    }
}
