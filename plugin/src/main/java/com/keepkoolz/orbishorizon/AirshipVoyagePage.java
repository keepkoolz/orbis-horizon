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

import java.util.List;
import java.util.UUID;

/**
 * Orders window of an airship pilot (talk with F). Offers "take us to..." (the list of destinations, T139) and "take the helm"
 * while the pilot is at the helm, and "take the helm back" while he sits on the stool. Owner and crew state are checked again on
 * every click.
 */
final class AirshipVoyagePage extends InteractiveCustomUIPage<AirshipVoyagePage.Data> {

    enum CrewOrder { VOYAGE, TAKE_HELM, RETURN_HELM }

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>("Dest", Codec.STRING), (d, v) -> d.dest = v, d -> d.dest).add()
                .build();
        String action;
        String dest;
    }

    /** Orders offered by the dialogue. Pure: only the captain gives orders, and only while the pilot is at rest at a post. */
    static List<CrewOrder> dialogueOptions(boolean owner, AirshipCrew.CrewState state) {
        if (!owner) {
            return List.of();
        }
        return switch (state) {
            case AT_HELM -> List.of(CrewOrder.VOYAGE, CrewOrder.TAKE_HELM);
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

    private static Message m(String key) {
        return Message.translation("server.orbis_horizon.ui." + key);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Airship_Voyage.ui");
        cmd.set("#TitleText.Text", m("crew.title"));
        cmd.set("#CloseButton.Text", m("close"));
        cmd.set("#VoyageButton.Text", m("voyage.go"));
        cmd.set("#TakeHelmButton.Text", m("crew.takeHelm"));
        cmd.set("#ReturnHelmButton.Text", m("crew.returnHelm"));
        World world = store.getExternalData().getWorld();
        BalloonRegistry.Entry e = AirshipCrew.entryOfPilot(world.getName(), npc);
        List<CrewOrder> options = e == null ? List.of() : dialogueOptions(isOwner(e), AirshipCrew.state(world, e));
        boolean list = listMode && options.contains(CrewOrder.VOYAGE);
        if (desk) {
            AirshipDesk.Action a = e == null ? AirshipDesk.Action.NO_PILOT
                    : AirshipDesk.decide(isOwner(e), true, AirshipCrew.state(world, e));
            list = a == AirshipDesk.Action.START_NOW || a == AirshipDesk.Action.WALK_FIRST;
        }
        cmd.set("#VoyageButton.Visible", !list && options.contains(CrewOrder.VOYAGE));
        cmd.set("#TakeHelmButton.Visible", !list && options.contains(CrewOrder.TAKE_HELM));
        cmd.set("#ReturnHelmButton.Visible", !list && options.contains(CrewOrder.RETURN_HELM));
        cmd.set("#List.Visible", list);
        cmd.set("#Empty.Visible", false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#VoyageButton", EventData.of("Action", "Voyage"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TakeHelmButton", EventData.of("Action", "TakeHelm"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReturnHelmButton", EventData.of("Action", "ReturnHelm"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "Close"));
        if (list) {
            buildList(ref, cmd, events, store, world);
        }
    }

    /** Fills the destination list: markers then stations, one line each (name, direction, distance and flight time). */
    private void buildList(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store, World world) {
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
        List<VoyagePlanner.Destination> all = VoyagePlanner.collectAll(player, world, fromX, fromZ);
        List<VoyagePlanner.Destination> dests = VoyagePlanner.list(all, fromX, fromZ);
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
                cmd.append("#List", "Pages/Airship_Voyage_Entry.ui");
                cmd.set("#List[" + row + "] #Name.Text", m(d.kind() == VoyagePlanner.Kind.MARKER ? "voyage.markers" : "voyage.stations"));
                cmd.set("#List[" + row + "] #Dir.Text", "");
                cmd.set("#List[" + row + "] #Dist.Text", "");
                row++;
            }
            double dx = d.x() - fromX;
            double dz = d.z() - fromZ;
            double horizontal = Math.hypot(dx, dz);
            int cruise = VoyagePlanner.cruiseY(fromY, d.groundY(), BalloonManager.WORLD_MAX_Y);
            double secs = VoyagePlanner.tripSeconds(horizontal, fromY, cruise, hSpeed, vSpeed);
            cmd.append("#List", "Pages/Airship_Voyage_Entry.ui");
            String sel = "#List[" + row + "]";
            cmd.set(sel + " #Name.Text", d.name());
            cmd.set(sel + " #Dir.Text", Message.translation("server.orbis_horizon.ui.dir." + DestinationPage.compass(dx, dz)));
            cmd.set(sel + " #Dist.Text", Message.translation("server.orbis_horizon.ui.dest.info")
                    .param("n", (int) Math.round(horizontal)).param("time", BalloonManager.durationText(secs)));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel, EventData.of("Dest", Integer.toString(i)));
            row++;
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
            List<CrewOrder> options = e == null ? List.of() : dialogueOptions(isOwner(e), AirshipCrew.state(world, e));
            if ("Voyage".equals(data.action)) {
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
                                () -> AirshipDesk.sayOrder(playerRef, e.crew(), d.name()));
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
                    Message error = AirshipManager.get().startVoyage(store, world, playerRef, e, shown.get(i));
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
