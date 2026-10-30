package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * the respawn point of a player who slept in a camp is kept after the camp is packed. The game never checks that a bed
 * exists for a respawn point (only that the position is free, and it searches around the point itself), but removing the bed block
 * removes the point of its online owner (RespawnBlock$OnRemove). So the camp point is recorded in camp-respawn.json and put back
 * on the next tick after a packing. A point is a "camp" point when its record is in this file for (player, world). Lying down in a
 * camp replaces that player's previous camp point in that world only, never other points.
 */
final class CampRespawn {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** One recorded camp point. */
    record Point(UUID player, String world, Vector3i block, Vector3d pos, String name, float yaw) {
    }

    /** Recorded points, by player then world (one camp point per player and world). */
    private static final Map<String, Point> POINTS = new LinkedHashMap<>();
    private static volatile Path file;

    private CampRespawn() {
    }

    private static String key(UUID player, String world) {
        return player + "|" + world;
    }

    // ---- pure helpers (tested) ----

    /** Nearest cell (distance, then y, x, z ascending) within the cube of this radius where standable is true, null if none. */
    static Vector3i freeSpotNear(Vector3i center, int radius, Predicate<Vector3i> standable) {
        Vector3i best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Vector3i p = new Vector3i(center.x + dx, center.y + dy, center.z + dz);
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (best != null) {
                        if (d > bestD) {
                            continue;
                        }
                        if (d == bestD && !before(p, best)) {
                            continue;
                        }
                    }
                    if (standable.test(p)) {
                        best = p;
                        bestD = d;
                    }
                }
            }
        }
        return best;
    }

    /** Tie-break order: y, then x, then z ascending. */
    private static boolean before(Vector3i a, Vector3i b) {
        if (a.y != b.y) {
            return a.y < b.y;
        }
        if (a.x != b.x) {
            return a.x < b.x;
        }
        return a.z < b.z;
    }

    /** Players whose respawn point sits on this bed block. */
    static Set<UUID> ownersOfBed(Map<UUID, Vector3i> pointBlocks, Vector3i bedPos) {
        Set<UUID> out = new HashSet<>();
        for (Map.Entry<UUID, Vector3i> e : pointBlocks.entrySet()) {
            if (e.getValue() != null && e.getValue().equals(bedPos)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ---- file ----

    static BsonDocument toBson(List<Point> points) {
        BsonArray arr = new BsonArray();
        for (Point p : points) {
            BsonDocument d = new BsonDocument();
            d.put("player", new BsonString(p.player().toString()));
            d.put("world", new BsonString(p.world()));
            d.put("block", ints(p.block().x, p.block().y, p.block().z));
            BsonArray pos = new BsonArray();
            pos.add(new BsonDouble(p.pos().x));
            pos.add(new BsonDouble(p.pos().y));
            pos.add(new BsonDouble(p.pos().z));
            d.put("pos", pos);
            d.put("name", new BsonString(p.name() == null ? "" : p.name()));
            d.put("yaw", new BsonDouble(p.yaw()));
            arr.add(d);
        }
        BsonDocument doc = new BsonDocument();
        doc.put("v", new BsonInt32(1));
        doc.put("points", arr);
        return doc;
    }

    private static BsonArray ints(int x, int y, int z) {
        BsonArray a = new BsonArray();
        a.add(new BsonInt32(x));
        a.add(new BsonInt32(y));
        a.add(new BsonInt32(z));
        return a;
    }

    static List<Point> fromBson(BsonDocument doc) {
        List<Point> out = new ArrayList<>();
        for (BsonValue v : doc.getArray("points")) {
            BsonDocument d = v.asDocument();
            BsonArray b = d.getArray("block");
            BsonArray p = d.getArray("pos");
            out.add(new Point(UUID.fromString(d.getString("player").getValue()), d.getString("world").getValue(),
                    new Vector3i(b.get(0).asInt32().getValue(), b.get(1).asInt32().getValue(), b.get(2).asInt32().getValue()),
                    new Vector3d(p.get(0).asNumber().doubleValue(), p.get(1).asNumber().doubleValue(), p.get(2).asNumber().doubleValue()),
                    d.getString("name").getValue(), (float) d.getNumber("yaw").doubleValue()));
        }
        return out;
    }

    /** Reads camp-respawn.json. Missing: empty. Unreadable: empty, the file is renamed .bad. Never throws. */
    static synchronized void load(Path dataDir) {
        POINTS.clear();
        try {
            file = dataDir.resolve("camp-respawn.json");
            if (!Files.isRegularFile(file)) {
                return;
            }
            for (Point p : fromBson(BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                POINTS.put(key(p.player(), p.world()), p);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("camp-respawn.json unreadable, camp points forgotten");
            POINTS.clear();
            try {
                if (file != null && Files.isRegularFile(file)) {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e2) {
                LOGGER.at(Level.WARNING).withCause(e2).log("Cannot rename camp-respawn.json to .bad");
            }
        }
    }

    /** Atomic write (tmp file then rename). Never throws. */
    private static synchronized void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, toBson(new ArrayList<>(POINTS.values())).toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot write camp-respawn.json");
        }
    }

    // ---- game side ----

    /** Solid ground: a solid block that is not a liquid and inside the height range. */
    private static boolean solidGround(SectionReader r, Vector3i p) {
        if (BalloonManager.outOfHeight(p.y)) {
            return false;
        }
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bt = SectionBlocks.typeAt(r, p.x, p.y, p.z);
        return bt != null && bt.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid
                && BalloonManager.liquidAt(r, p.x, p.y, p.z) == null;
    }

    /** Feet and head cells free of solid and liquid (and in range), solid ground below. */
    private static boolean standable(SectionReader r, Vector3i p) {
        return !BalloonManager.blocksBalloon(r, p.x, p.y, p.z) && !BalloonManager.blocksBalloon(r, p.x, p.y + 1, p.z)
                && solidGround(r, new Vector3i(p.x, p.y - 1, p.z));
    }

    /** Respawn point name in the sleeper's client language, en-US as fallback. */
    private static String nameOf(String kindId, String language) {
        String key = switch (kindId) {
            case Deployables.BEDROLL_KIND -> "bivouac";
            case Deployables.TENT_BIG_KIND -> "tentBig";
            default -> "tent";
        };
        String full = "server.orbis_horizon.respawn." + key;
        try {
            com.hypixel.hytale.server.core.modules.i18n.I18nModule i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
            String text = i18n.getMessage(language, full);
            if (text == null) {
                text = i18n.getMessage("en-US", full);
            }
            if (text != null) {
                return text;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Respawn point name not translated");
        }
        return switch (key) {
            case "bivouac" -> "Bivouac";
            case "tentBig" -> "Large tent";
            default -> "Tent";
        };
    }

    /** The player lies down in a bed of a camp: its camp point of this world becomes this bed. Never throws. */
    static void onLieDown(Store<EntityStore> store, Ref<EntityStore> player, World world, BalloonRegistry.Entry camp, Vector3i bedPos) {
        try {
            if (camp == null || CampTiers.ofKind(camp.kind()) == null || !player.isValid()) {
                return;
            }
            PlayerRef pr = store.getComponent(player, PlayerRef.getComponentType());
            Player pl = store.getComponent(player, Player.getComponentType());
            if (pr == null || pl == null) {
                return;
            }
            Vector3d pos;
            float yaw = 0f;
            Deployables.TentSpec spec = Deployables.get(camp.kind()).tent;
            if (spec != null && spec.exitPoint != null) {
                pos = TentBedSystem.exitPosition(camp, spec);
                yaw = TentBedSystem.exitYaw(camp, spec);
            } else {
                SectionReader r = new SectionReader(world.getChunkStore());
                Vector3i spot = freeSpotNear(bedPos, 2, p -> standable(r, p));
                if (spot == null) {
                    // Safest: record nothing (a point inside the bed would trap the player); the previous points stay as they are.
                    LOGGER.at(Level.WARNING).log("Camp: no free cell near the bed at %d, %d, %d, respawn point not saved",
                            bedPos.x, bedPos.y, bedPos.z);
                    return;
                }
                pos = new Vector3d(spot.x + 0.5, spot.y + 0.1, spot.z + 0.5);
            }
            Point point = new Point(pr.getUuid(), world.getName(), new Vector3i(bedPos), pos, nameOf(camp.kind(), pr.getLanguage()), yaw);
            Point previous;
            synchronized (CampRespawn.class) {
                previous = POINTS.put(key(point.player(), point.world()), point);
            }
            save();
            PlayerWorldData data = pl.getPlayerConfigData().getPerWorldData(world.getName());
            if (data == null) {
                return;
            }
            List<PlayerRespawnPointData> kept = new ArrayList<>();
            PlayerRespawnPointData[] old = data.getRespawnPoints();
            if (old != null) {
                for (PlayerRespawnPointData p : old) {
                    if (p == null || p.getBlockPosition() == null) {
                        continue;
                    }
                    boolean oldCamp = previous != null && p.getBlockPosition().equals(previous.block());
                    if (!oldCamp && !p.getBlockPosition().equals(bedPos)) {
                        kept.add(p);
                    }
                }
            }
            kept.add(new PlayerRespawnPointData(new Vector3i(bedPos), pos, point.name(), yaw));
            data.setRespawnPoints(kept.toArray(new PlayerRespawnPointData[0]));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camp: respawn point not saved");
        }
    }

    /** After a packing: next tick, puts back the camp point the game removed for every online player whose point was in this camp. */
    static void afterRemoval(World world, BalloonRegistry.Entry camp) {
        try {
            Deployables.Kind kind = Deployables.get(camp.kind());
            if (kind == null || CampTiers.ofKind(camp.kind()) == null) {
                return;
            }
            StructureShape shape = kind.shape();
            List<Point> mine = new ArrayList<>();
            synchronized (CampRespawn.class) {
                for (Point p : POINTS.values()) {
                    if (!p.world().equals(world.getName())) {
                        continue;
                    }
                    Vector3i local = camp.rotation().toInverse().rotateYaw(
                            new Vector3i(p.block().x - camp.x(), p.block().y - camp.y(), p.block().z - camp.z()), new Vector3i());
                    if (shape.inBounds(local.x, local.y, local.z) && shape.cellAt(local.x, local.y, local.z) != null) {
                        mine.add(p);
                    }
                }
            }
            if (mine.isEmpty()) {
                return;
            }
            world.execute(() -> {
                for (Point p : mine) {
                    restore(world, p);
                }
            });
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camp: respawn points not restored");
        }
    }

    private static void restore(World world, Point p) {
        try {
            for (PlayerRef pr : world.getPlayerRefs()) {
                if (!p.player().equals(pr.getUuid())) {
                    continue;
                }
                Ref<EntityStore> ref = pr.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Player pl = ref.getStore().getComponent(ref, Player.getComponentType());
                PlayerWorldData data = pl == null ? null : pl.getPlayerConfigData().getPerWorldData(world.getName());
                if (data == null) {
                    return;
                }
                PlayerRespawnPointData[] old = data.getRespawnPoints();
                List<PlayerRespawnPointData> next = new ArrayList<>();
                if (old != null) {
                    for (PlayerRespawnPointData q : old) {
                        if (q != null && q.getBlockPosition() != null && q.getBlockPosition().equals(p.block())) {
                            return; // still there
                        }
                        if (q != null) {
                            next.add(q);
                        }
                    }
                }
                next.add(new PlayerRespawnPointData(new Vector3i(p.block()), new Vector3d(p.pos()), p.name(), p.yaw()));
                data.setRespawnPoints(next.toArray(new PlayerRespawnPointData[0]));
                LOGGER.at(Level.INFO).log("Camp: respawn point restored for %s at %d, %d, %d", pr.getUsername(),
                        p.block().x, p.block().y, p.block().z);
                return;
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camp: respawn point not restored");
        }
    }

    // ---- obstructed respawn fallback ----

    /** The player is more than this far from the camp point after respawning: the game did not use it. */
    static final double RESCUE_FAR = 2.0;
    /** Delay after the respawn event before looking at where the player is, in ms. */
    private static final long RESCUE_DELAY_MS = 1000L;

    private record Watch(World world, long at) {
    }

    private static final Map<Ref<EntityStore>, Watch> WATCH = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Decision: rescue when the player is far from their camp point and the game did not pick another of their points instead
     * (distToNearestOther is Double.POSITIVE_INFINITY when they have none). Pure, tested.
     */
    static boolean shouldRescue(double distToCamp, double distToNearestOther) {
        return distToCamp > RESCUE_FAR && distToNearestOther > RESCUE_FAR;
    }

    /** Listener on RespawnEvent: starts watching the player when they have a camp point in this world. */
    public static final class RespawnWatcher extends com.hypixel.hytale.component.system.EntityEventSystem<EntityStore,
            com.hypixel.hytale.server.core.event.events.ecs.RespawnEvent> {
        public RespawnWatcher() {
            super(com.hypixel.hytale.server.core.event.events.ecs.RespawnEvent.class);
        }

        @Override
        public com.hypixel.hytale.component.query.Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, com.hypixel.hytale.component.ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           com.hypixel.hytale.component.CommandBuffer<EntityStore> commandBuffer,
                           com.hypixel.hytale.server.core.event.events.ecs.RespawnEvent event) {
            try {
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                World world = store.getExternalData().getWorld();
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr != null && campPoint(pr.getUuid(), world.getName()) != null) {
                    WATCH.put(ref, new Watch(world, System.currentTimeMillis()));
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("Camp: respawn not monitored");
            }
        }
    }

    private static synchronized Point campPoint(UUID player, String world) {
        return POINTS.get(key(player, world));
    }

    /** True when this block is the recorded camp point of this player in this world. Thread-safe (map thread). */
    static boolean isCampBlock(UUID player, String world, Vector3i block) {
        Point p = campPoint(player, world);
        return p != null && block != null && p.block().equals(block);
    }

    /** Forgets the camp point of this player and world when it sits on this block: it will never be put back. True if it was. */
    static boolean forget(UUID player, String world, Vector3i block) {
        synchronized (CampRespawn.class) {
            Point p = POINTS.get(key(player, world));
            if (p == null || block == null || !p.block().equals(block)) {
                return false;
            }
            POINTS.remove(key(player, world));
        }
        save();
        return true;
    }

    /** The points without the one on this block (pure, tested). Null stays null. */
    static PlayerRespawnPointData[] withoutBlock(PlayerRespawnPointData[] points, Vector3i block) {
        if (points == null) {
            return null;
        }
        List<PlayerRespawnPointData> out = new ArrayList<>();
        for (PlayerRespawnPointData q : points) {
            if (q != null && q.getBlockPosition() != null && q.getBlockPosition().equals(block)) {
                continue;
            }
            out.add(q);
        }
        return out.toArray(new PlayerRespawnPointData[0]);
    }

    /** Called every tick of each world: after the delay, teleports a player the game did not send to their camp point. Never throws. */
    static void tick(Store<EntityStore> store, World world) {
        guardTick(world);
        if (WATCH.isEmpty()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Ref<EntityStore>, Watch> e : WATCH.entrySet()) {
                Ref<EntityStore> ref = e.getKey();
                Watch w = e.getValue();
                if (w.world() != world || now - w.at() < RESCUE_DELAY_MS) {
                    continue;
                }
                WATCH.remove(ref, w);
                if (ref.isValid()) {
                    world.execute(() -> rescue(store, ref, world));
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Camp: respawn not checked");
        }
    }

    // ---- guard: camp points and game bed points never replace each other ----
    //
    // The game's bed interaction (BedInteraction, also used by our camp beds) opens a claim page when the bed is not owned by the
    // player. Validating it (RespawnPointPage.setRespawnPointForPlayer) removes every saved point within RadiusLimitRespawnPoint
    // blocks (OverrideNearbyRespawnPointPage) or the one the player picks (SelectOverrideRespawnPointPage), clears the owner of
    // the removed beds, then appends its own point. A mod cannot intercept those pages, so once a second we compare each online
    // player's points with the last snapshot and put back what the game removed through a camp.

    private static final long GUARD_PERIOD_MS = 1000L;
    private static final Map<String, Long> GUARD_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, PlayerRespawnPointData[]> SNAPSHOT = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean hasBlock(PlayerRespawnPointData[] points, Vector3i block) {
        if (points != null) {
            for (PlayerRespawnPointData q : points) {
                if (q != null && block.equals(q.getBlockPosition())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Points to put back (pure, tested). prev is the last snapshot (null on first sight), cur the current points, camp the recorded
     * camp point (null if none). Rules: the camp point is put back whenever it is missing (it only disappears through the game's
     * override, since packing and forgetting are handled elsewhere: forgetting removes the record). A game point that vanished
     * is put back when, in the same change, a point appeared on a camp bed (the claim of a camp bed), and its own bed still exists.
     * Points on a camp bed that are not the recorded camp point are left alone (onLieDown replaces them at bedtime).
     */
    static List<PlayerRespawnPointData> pointsToRestore(PlayerRespawnPointData[] prev, PlayerRespawnPointData[] cur, Point camp,
                                                        Predicate<Vector3i> isCampBed, Predicate<Vector3i> bedStillThere) {
        List<PlayerRespawnPointData> out = new ArrayList<>();
        if (camp != null && !hasBlock(cur, camp.block())) {
            out.add(new PlayerRespawnPointData(new Vector3i(camp.block()), new Vector3d(camp.pos()), camp.name(), camp.yaw()));
        }
        if (prev == null || cur == null) {
            return out;
        }
        boolean campClaimed = false;
        for (PlayerRespawnPointData q : cur) {
            if (q != null && q.getBlockPosition() != null && !hasBlock(prev, q.getBlockPosition()) && isCampBed.test(q.getBlockPosition())) {
                campClaimed = true;
                break;
            }
        }
        if (!campClaimed) {
            return out;
        }
        for (PlayerRespawnPointData q : prev) {
            if (q == null || q.getBlockPosition() == null || hasBlock(cur, q.getBlockPosition())) {
                continue;
            }
            if (camp != null && camp.block().equals(q.getBlockPosition())) {
                continue; // already handled above
            }
            if (isCampBed.test(q.getBlockPosition()) || !bedStillThere.test(q.getBlockPosition())) {
                continue; // a camp point (replaced by design) or a broken bed
            }
            out.add(q);
        }
        return out;
    }

    private static void guardTick(World world) {
        try {
            long now = System.currentTimeMillis();
            Long last = GUARD_AT.get(world.getName());
            if (last != null && now - last < GUARD_PERIOD_MS) {
                return;
            }
            GUARD_AT.put(world.getName(), now);
            for (PlayerRef pr : world.getPlayerRefs()) {
                guardPlayer(world, pr);
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camp: respawn point monitoring failed");
        }
    }

    private static void guardPlayer(World world, PlayerRef pr) {
        try {
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Player pl = ref.getStore().getComponent(ref, Player.getComponentType());
            PlayerWorldData data = pl == null ? null : pl.getPlayerConfigData().getPerWorldData(world.getName());
            if (data == null) {
                return;
            }
            String k = key(pr.getUuid(), world.getName());
            PlayerRespawnPointData[] cur = data.getRespawnPoints();
            Point camp = campPoint(pr.getUuid(), world.getName());
            if (camp == null && !SNAPSHOT.containsKey(k)) {
                SNAPSHOT.put(k, copyOf(cur));
                return;
            }
            List<PlayerRespawnPointData> back = pointsToRestore(SNAPSHOT.get(k), cur, camp, b -> isCampBed(world, b),
                    b -> SectionBlocks.blockComponent(world.getChunkStore(), RespawnBlock.getComponentType(), b.x, b.y, b.z) != null);
            if (!back.isEmpty()) {
                List<PlayerRespawnPointData> next = new ArrayList<>();
                if (cur != null) {
                    for (PlayerRespawnPointData q : cur) {
                        if (q != null) {
                            next.add(q);
                        }
                    }
                }
                for (PlayerRespawnPointData q : back) {
                    next.add(q);
                    RespawnBlock rb = SectionBlocks.blockComponent(world.getChunkStore(), RespawnBlock.getComponentType(),
                            q.getBlockPosition().x, q.getBlockPosition().y, q.getBlockPosition().z);
                    if (rb != null && rb.getOwnerUUID() == null && (camp == null || !camp.block().equals(q.getBlockPosition()))) {
                        rb.setOwnerUUID(pr.getUuid()); // the game cleared it when it removed the point
                    }
                    LOGGER.at(Level.INFO).log("Camp: respawn point restored for %s at %d, %d, %d", pr.getUsername(),
                            q.getBlockPosition().x, q.getBlockPosition().y, q.getBlockPosition().z);
                }
                cur = next.toArray(new PlayerRespawnPointData[0]);
                data.setRespawnPoints(cur);
            }
            SNAPSHOT.put(k, copyOf(cur));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Camp: respawn points not checked");
        }
    }

    private static PlayerRespawnPointData[] copyOf(PlayerRespawnPointData[] a) {
        return a == null ? new PlayerRespawnPointData[0] : a.clone();
    }

    /** True when this block is a bed cell of a registered camp (bivouac, tent). */
    private static boolean isCampBed(World world, Vector3i pos) {
        try {
            BalloonRegistry.Entry e = BalloonRegistry.find(world, pos);
            if (e == null || CampTiers.ofKind(e.kind()) == null) {
                return false;
            }
            StructureShape shape = BalloonRegistry.shapeOf(e);
            if (shape == null) {
                return false;
            }
            Vector3i local = e.rotation().toInverse().rotateYaw(new Vector3i(pos.x - e.x(), pos.y - e.y(), pos.z - e.z()), new Vector3i());
            StructureShape.Cell cell = shape.inBounds(local.x, local.y, local.z) ? shape.cellAt(local.x, local.y, local.z) : null;
            return cell != null && cell.baseName().startsWith("Furniture_Crude_Bed");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void rescue(Store<EntityStore> store, Ref<EntityStore> ref, World world) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            Player pl = store.getComponent(ref, Player.getComponentType());
            com.hypixel.hytale.server.core.modules.entity.component.TransformComponent t =
                    store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (pr == null || pl == null || t == null) {
                return;
            }
            Point camp = campPoint(pr.getUuid(), world.getName());
            if (camp == null) {
                return;
            }
            Vector3d here = t.getPosition();
            double toCamp = here.distance(camp.pos());
            double toOther = Double.POSITIVE_INFINITY;
            PlayerWorldData data = pl.getPlayerConfigData().getPerWorldData(world.getName());
            PlayerRespawnPointData[] pts = data == null ? null : data.getRespawnPoints();
            if (pts != null) {
                for (PlayerRespawnPointData q : pts) {
                    if (q != null && q.getRespawnPosition() != null && !camp.block().equals(q.getBlockPosition())) {
                        toOther = Math.min(toOther, here.distance(q.getRespawnPosition()));
                    }
                }
            }
            if (!shouldRescue(toCamp, toOther)) {
                return;
            }
            SectionReader r = new SectionReader(world.getChunkStore());
            Vector3i center = new Vector3i((int) Math.floor(camp.pos().x), (int) Math.floor(camp.pos().y), (int) Math.floor(camp.pos().z));
            Vector3i spot = freeSpotNear(center, 8, p -> standable(r, p));
            if (spot == null) {
                LOGGER.at(Level.INFO).log("Camp: blocked point, no free cell within 8 blocks, game choice kept");
                return;
            }
            com.hypixel.hytale.math.vector.Rotation3f look = new com.hypixel.hytale.math.vector.Rotation3f();
            look.setYaw(camp.yaw());
            store.putComponent(ref, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(),
                    com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.createForPlayer(
                            new Vector3d(spot.x + 0.5, spot.y + 0.1, spot.z + 0.5), look));
            LOGGER.at(Level.INFO).log("Camp: blocked point, player placed at %d, %d, %d", spot.x, spot.y, spot.z);
        } catch (RuntimeException ex) {
            LOGGER.at(Level.WARNING).withCause(ex).log("Camp: blocked point not handled");
        }
    }
}
