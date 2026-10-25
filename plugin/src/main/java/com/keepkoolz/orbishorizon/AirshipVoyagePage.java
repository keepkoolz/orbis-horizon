package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;

/**
 * Orders window of an airship pilot (talk with F). Offers "take us to..." (the list of destinations, T139) and "take the helm"
 * while the pilot is at the helm, and "take the helm back" while he sits on the stool. Owner and crew state are checked again on
 * every click.
 */
final class AirshipVoyagePage extends InteractiveCustomUIPage<AirshipVoyagePage.Data> {

    enum CrewOrder { VOYAGE, NOTE_STOP, TAKE_HELM, RETURN_HELM }

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>("Dest", Codec.STRING), (d, v) -> d.dest = v, d -> d.dest).add()
                .append(new KeyedCodec<>("Forget", Codec.STRING), (d, v) -> d.forget = v, d -> d.forget).add()
                .build();
        String action;
        String dest;
        String forget;
    }

    /** Orders offered by the dialogue. Pure: only the captain gives orders, and only while the pilot is at rest at a post. */
    static List<CrewOrder> dialogueOptions(boolean owner, AirshipCrew.CrewState state) {
        if (!owner) {
            return List.of();
        }
        return crewOptions(state, true);
    }

    /** Orders by pilot state, without the voyage order when the ship type has no voyage. Pure. */
    static List<CrewOrder> crewOptions(AirshipCrew.CrewState state, boolean voyage) {
        return crewOptions(state, voyage, false);
    }

    /** Orders by pilot state: at the helm, the voyage order (if any), "note this place" (if the type keeps a water log), then the helm. Pure. */
    static List<CrewOrder> crewOptions(AirshipCrew.CrewState state, boolean voyage, boolean notes) {
        return switch (state) {
            case AT_HELM -> {
                List<CrewOrder> out = new java.util.ArrayList<>();
                if (voyage) {
                    out.add(CrewOrder.VOYAGE);
                }
                if (notes) {
                    out.add(CrewOrder.NOTE_STOP);
                }
                out.add(CrewOrder.TAKE_HELM);
                yield out;
            }
            case SEATED -> List.of(CrewOrder.RETURN_HELM);
            default -> List.of();
        };
    }

    private final UUID npc;
    /** The recalled ship waiting above a station (T145): closing the window without a choice sends it away. Null for the pilot dialogue. */
    private final RecallState recall;
    /** Opened from the chart on the captain's desk (T148): the list is shown at once and the order goes through AirshipDesk. */
    private final boolean desk;
    /** The destination list is shown instead of the orders (after "take us to..."). */
    private boolean listMode;
    /** Destinations shown by the last build of the list, in order: a click gives the index. */
    private List<VoyagePlanner.Destination> shown = List.of();

    AirshipVoyagePage(PlayerRef player, UUID npc) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.npc = npc;
        this.recall = null;
        this.desk = false;
    }

    /** Destination list of the chart on the captain's desk (parked airship, pilot at the helm or on the stool). */
    AirshipVoyagePage(PlayerRef player, UUID npc, boolean desk) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.npc = npc;
        this.recall = null;
        this.desk = desk;
        this.listMode = desk;
    }

    /** Destination window of the captain at the end of the minute above a station: the destination list of the pilot's orders. */
    AirshipVoyagePage(PlayerRef player, UUID npc, RecallState recall) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.npc = npc;
        this.recall = recall;
        this.desk = false;
        this.listMode = true;
    }

    /** Closes the window from the server side (the choice timed out). Never throws. */
    void closeQuietly() {
        try {
            close();
        } catch (RuntimeException e) {
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e)
                    .log("Rappel : fenêtre non fermée");
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (recall != null) {
            AirshipRecall.onChoiceClosed(recall, this);
        }
    }

    private List<CrewOrder> ownerOptions(BalloonRegistry.Entry e, World world) {
        return isOwner(e) ? crewOptions(AirshipCrew.state(world, e), Deployables.voyageOf(e.kind()), keepsLog(e.kind())) : List.of();
    }

    /** Whether the ship type floats on water and keeps a log of stops. */
    static boolean keepsLog(String kindId) {
        Deployables.Kind k = Deployables.get(kindId);
        return k != null && k.airship != null && k.airship.waterborne();
    }

    private static Message m(String key) {
        return Message.translation("server.orbis_horizon.ui." + key);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Airship_Voyage.ui");
        cmd.set("#TitleText.Text", m("crew.title"));
        cmd.set("#CloseButton.Text", m("close"));
        cmd.set("#VoyageButton.Text", m("voyage.go"));
        cmd.set("#NoteStopButton.Text", m("crew.noteStop"));
        cmd.set("#TakeHelmButton.Text", m("crew.takeHelm"));
        cmd.set("#ReturnHelmButton.Text", m("crew.returnHelm"));
        World world = store.getExternalData().getWorld();
        BalloonRegistry.Entry e = AirshipCrew.entryOfPilot(world.getName(), npc);
        List<CrewOrder> options = e == null ? List.of() : ownerOptions(e, world);
        boolean list = listMode && options.contains(CrewOrder.VOYAGE);
        if (desk) {
            AirshipDesk.Action a = e == null ? AirshipDesk.Action.NO_PILOT
                    : AirshipDesk.decide(isOwner(e), true, AirshipCrew.state(world, e));
            list = a == AirshipDesk.Action.START_NOW || a == AirshipDesk.Action.WALK_FIRST;
        }
        cmd.set("#VoyageButton.Visible", !list && options.contains(CrewOrder.VOYAGE));
        cmd.set("#NoteStopButton.Visible", !list && options.contains(CrewOrder.NOTE_STOP));
        cmd.set("#TakeHelmButton.Visible", !list && options.contains(CrewOrder.TAKE_HELM));
        cmd.set("#ReturnHelmButton.Visible", !list && options.contains(CrewOrder.RETURN_HELM));
        cmd.set("#List.Visible", list);
        cmd.set("#Empty.Visible", false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#VoyageButton", EventData.of("Action", "Voyage"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NoteStopButton", EventData.of("Action", "NoteStop"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TakeHelmButton", EventData.of("Action", "TakeHelm"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReturnHelmButton", EventData.of("Action", "ReturnHelm"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "Close"));
        if (list) {
            buildList(ref, cmd, events, store, world, e);
        }
    }

    /** Fills the destination list: markers then stations, one line each (name, direction, distance and flight time). */
    private void buildList(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store, World world,
                           BalloonRegistry.Entry entry) {
        shown = List.of();
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        if (t == null || player == null) {
            cmd.set("#Empty.Visible", true);
            cmd.set("#Empty.Text", m("voyage.empty"));
            return;
        }
        double fromX = t.getPosition().x;
        double fromZ = t.getPosition().z;
        int fromY = (int) Math.round(t.getPosition().y);
        boolean boat = entry != null && keepsLog(entry.kind());
        List<VoyagePlanner.Destination> all;
        List<VoyagePlanner.Destination> dests;
        if (boat) {
            // A boat goes to its own stops and to the stations it has reached (the log decides which are far enough).
            WaterLog log = entry.water() != null ? entry.water().copy() : new WaterLog();
            dests = ShipVoyage.destinations(log, TransitNetwork.get().stations(world.getName(), Deployables.BOAT_STATION_KIND),
                    fromX, fromZ, ShipVoyage::pierAndZone);
            all = dests;
        } else {
            all = VoyagePlanner.collectAll(player, world, fromX, fromZ);
            dests = VoyagePlanner.list(all, fromX, fromZ);
        }
        if (dests.isEmpty()) {
            cmd.set("#Empty.Visible", true);
            cmd.set("#Empty.Text", all.isEmpty() ? m("voyage.empty")
                    : Texts.pilot("crew.voyage.tooClose", AirshipCrew.crewOfPilot(world.getName(), npc)));
            return;
        }
        shown = dests;
        double hSpeed = BalloonManager.defaultHorizontalSpeed(world, store);
        double vSpeed = BalloonManager.defaultDescentSpeed(world, store);
        cmd.clear("#List");
        int row = 0;
        VoyagePlanner.Kind last = null;
        for (int i = 0; i < dests.size(); i++) {
            VoyagePlanner.Destination d = dests.get(i);
            if (d.kind() != last) {
                last = d.kind();
                // Section title styled like the headers of the station master's window, with a gap above all but the first.
                cmd.append("#List", "Pages/Airship_Voyage_Header.ui");
                cmd.set("#List[" + row + "] #Text.Text", m(d.kind() == VoyagePlanner.Kind.MARKER ? "voyage.markers"
                        : d.kind() == VoyagePlanner.Kind.STOP ? "voyage.stops" : "voyage.stations"));
                cmd.set("#List[" + row + "] #Gap.Visible", row > 0);
                row++;
            }
            double dx = d.x() - fromX;
            double dz = d.z() - fromZ;
            double horizontal = Math.hypot(dx, dz);
            int cruise = VoyagePlanner.cruiseY(fromY, d.groundY(), BalloonManager.WORLD_MAX_Y);
            double secs = VoyagePlanner.tripSeconds(horizontal, fromY, cruise, hSpeed, vSpeed);
            boolean stop = d.kind() == VoyagePlanner.Kind.STOP;
            cmd.append("#List", stop ? "Pages/Airship_Voyage_StopEntry.ui" : "Pages/Airship_Voyage_Entry.ui");
            String sel = "#List[" + row + "]";
            String btn = stop ? sel + " #Button" : sel;
            if (stop) {
                cmd.set(btn + " #Name.Text", Texts.t("crew.stop.name").param("n", d.name()));
            } else {
                cmd.set(btn + " #Name.Text", d.name());
            }
            cmd.set(btn + " #Dir.Text", Message.translation("server.orbis_horizon.ui.dir." + DestinationPage.compass(dx, dz)));
            cmd.set(btn + " #Dist.Text", Message.translation("server.orbis_horizon.ui.dest.info")
                    .param("n", (int) Math.round(horizontal)).param("time", BalloonManager.durationText(secs)));
            events.addEventBinding(CustomUIEventBindingType.Activating, btn, EventData.of("Dest", Integer.toString(i)));
            if (stop) {
                cmd.set(sel + " #Forget #ForgetText.Text", m("voyage.forget"));
                events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Forget", EventData.of("Forget", Integer.toString(i)));
            }
            row++;
        }
    }

    /** Notes the place where the boat is moored as a stop of its log, and tells the captain. */
    private void noteStop(World world, BalloonRegistry.Entry e) {
        Deployables.Kind kind = Deployables.get(e.kind());
        AirshipManager am = AirshipManager.get();
        Vector3i origin = new Vector3i(e.x(), e.y(), e.z());
        Vector3d pivot = am.parkedPivot(kind, origin, e.rotation());
        Vector3d c = am.centerWorld(kind, pivot, e.rotation().getRadians());
        WaterLog log = e.water() != null ? e.water().copy() : new WaterLog();
        String refusal = log.addStop(e.x(), e.y(), e.z(), e.rotation(), (int) Math.floor(c.x), (int) Math.floor(c.z));
        if (refusal != null) {
            playerRef.sendMessage(Texts.pilot(refusal, e.crew()));
            return;
        }
        WaterLog.Stop added = log.stops().get(log.stops().size() - 1);
        BalloonRegistry.setWater(world, origin, e.rotation(), e.kind(), log);
        playerRef.sendMessage(Texts.pilot("crew.stop.noted", e.crew()).param("stop", Texts.t("crew.stop.name").param("n", added.name())));
    }

    /** Forgets a stop of the boat's log by its number. */
    private static void forgetStop(World world, BalloonRegistry.Entry e, String number) {
        WaterLog log = e.water() != null ? e.water().copy() : new WaterLog();
        if (log.forgetStop(number)) {
            BalloonRegistry.setWater(world, new Vector3i(e.x(), e.y(), e.z()), e.rotation(), e.kind(), log);
        }
    }

    private boolean isOwner(BalloonRegistry.Entry e) {
        return e.owner() != null && e.owner().equals(playerRef.getUuid());
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        boolean keepOpen = false;
        try {
            World world = store.getExternalData().getWorld();
            if (recall != null && "Close".equals(data.action)) {
                AirshipRecall.onChoiceClosed(recall, this);
                return;
            }
            BalloonRegistry.Entry e = AirshipCrew.entryOfPilot(world.getName(), npc);
            // Re-check on click: the state may have changed since the page was built.
            List<CrewOrder> options = e == null ? List.of() : ownerOptions(e, world);
            if (data.forget != null && !data.forget.isEmpty()) {
                int i = Integer.parseInt(data.forget);
                if (e != null && options.contains(CrewOrder.VOYAGE) && i >= 0 && i < shown.size()
                        && shown.get(i).kind() == VoyagePlanner.Kind.STOP) {
                    forgetStop(world, e, shown.get(i).name());
                }
                keepOpen = true;
                rebuild();
            } else if ("NoteStop".equals(data.action)) {
                if (e != null && options.contains(CrewOrder.NOTE_STOP)) {
                    noteStop(world, e);
                }
            } else if ("Voyage".equals(data.action)) {
                if (options.contains(CrewOrder.VOYAGE)) {
                    listMode = true;
                    keepOpen = true;
                    rebuild();
                }
            } else if (desk && data.dest != null && !data.dest.isEmpty()) {
                int i = Integer.parseInt(data.dest);
                AirshipDesk.Action a = e == null ? AirshipDesk.Action.NO_PILOT
                        : AirshipDesk.decide(isOwner(e), true, AirshipCrew.state(world, e));
                if (i >= 0 && i < shown.size()) {
                    VoyagePlanner.Destination d = shown.get(i);
                    Message error = null;
                    if (a == AirshipDesk.Action.START_NOW) {
                        error = AirshipManager.get().startVoyage(store, world, playerRef, e, d,
                                () -> AirshipDesk.sayOrder(playerRef, e.crew(), d));
                        if (error == AirshipManager.DEFERRED) {
                            error = null;
                        }
                    } else if (a == AirshipDesk.Action.WALK_FIRST) {
                        error = AirshipDesk.walkFirst(store, world, playerRef, e, d);
                    } else if (a == AirshipDesk.Action.BUSY) {
                        error = Texts.pilot("crew.talk.busy", e.crew());
                    } else if (a == AirshipDesk.Action.ABSENT) {
                        error = Texts.t("crew.desk.absent");
                    }
                    if (error != null) {
                        playerRef.sendMessage(error);
                    }
                }
            } else if (data.dest != null && !data.dest.isEmpty()) {
                int i = Integer.parseInt(data.dest);
                if (options.contains(CrewOrder.VOYAGE) && i >= 0 && i < shown.size()) {
                    Deployables.Kind pilotedKind = e != null ? Deployables.get(e.kind()) : null;
                    Message error = pilotedKind != null && pilotedKind.balloon != null
                            ? BalloonManager.get().startVoyage(store, world, playerRef, e, shown.get(i))
                            : AirshipManager.get().startVoyage(store, world, playerRef, e, shown.get(i));
                    if (error != null && error != AirshipManager.DEFERRED) {
                        playerRef.sendMessage(error);
                        // Recall window: a refused destination (hotbar full, too many players) keeps the window open, so the
                        // captain can fix it and choose again instead of the ship leaving on the close.
                        keepOpen = recall != null;
                    }
                }
            } else {
                CrewOrder order = null;
                if ("TakeHelm".equals(data.action)) {
                    order = CrewOrder.TAKE_HELM;
                } else if ("ReturnHelm".equals(data.action)) {
                    order = CrewOrder.RETURN_HELM;
                }
                if (order != null && options.contains(order)) {
                    AirshipDesk.cancel(world, e);
                    AirshipCrew.orderWalk(store, world, e,
                            order == CrewOrder.TAKE_HELM ? CrewRecord.Post.STOOL : CrewRecord.Post.HELM);
                }
            }
        } catch (RuntimeException ex) {
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(ex)
                    .log("Pilote : ordre non transmis");
        } finally {
            if (!keepOpen) {
                close();
            }
        }
    }
}
