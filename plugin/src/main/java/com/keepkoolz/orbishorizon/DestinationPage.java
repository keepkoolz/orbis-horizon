package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Destination window of a station master (spec, "Fenêtre de destination"). Opened by StationMaster.onTalk, which has already
 * reserved the master for the player. The reservation is released when the page ends, whatever the cause (button, Escape through
 * onDismiss, a click on a destination); StationMaster.tickReservations is the safety net for the causes that give no callback
 * (page replaced, disconnection, death, distance, 60 s).
 */
final class DestinationPage extends InteractiveCustomUIPage<DestinationPage.Data> {

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Dest", Codec.STRING), (d, v) -> d.dest = v, d -> d.dest).add()
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .build();
        String dest;
        String action;
    }

    private final TransitNetwork.Station station;
    private boolean finished;

    DestinationPage(PlayerRef player, TransitNetwork.Station station) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.station = station;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Transit_Destinations.ui");
        TransitUiTexts.destinations(cmd);
        cmd.set("#Status.Text", statusLine());
        List<String> queue = new ArrayList<>(station.queue);
        cmd.set("#Queue.Text", queue.isEmpty()
                ? Message.translation("server.orbis_horizon.ui.dest.queueEmpty")
                : Message.translation("server.orbis_horizon.ui.dest.queue").param("list", String.join("\n", queue)));

        World world = store.getExternalData().getWorld();
        double hSpeed = BalloonManager.defaultHorizontalSpeed(world, store);
        double vSpeed = BalloonManager.defaultDescentSpeed(world, store);
        cmd.set("#DepHeader.Text", Message.translation("server.orbis_horizon.ui.dest.depHeader"));
        List<TransitNetwork.Station> others = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            if (s != station) {
                others.add(s);
            }
        }
        others.sort(Comparator.comparing((TransitNetwork.Station s) -> s.name, String.CASE_INSENSITIVE_ORDER));
        cmd.set("#Header.Text", others.isEmpty()
                ? Message.translation("server.orbis_horizon.ui.dest.none")
                : Message.translation("server.orbis_horizon.ui.dest.header"));
        cmd.clear("#List");
        for (int i = 0; i < others.size(); i++) {
            TransitNetwork.Station o = others.get(i);
            long dist = Math.round(Math.sqrt(distSq(o)));
            double dx = o.origin.x - station.origin.x, dz = o.origin.z - station.origin.z;
            int dock = Deployables.STATION_DOCK_ORIGIN[1];
            double secs = tripSeconds(station.origin.y + dock, o.origin.y + dock, Math.sqrt(dx * dx + dz * dz), hSpeed, vSpeed);
            cmd.append("#List", "Pages/Transit_DestinationEntry.ui");
            String sel = "#List[" + i + "]";
            cmd.set(sel + " #Name.Text", o.name);
            // Direction in its own label: a translated Message given as a parameter was shown as "{dir}" in a page (11 October 2026).
            cmd.set(sel + " #Dir.Text", Message.translation("server.orbis_horizon.ui.dir." + compass(dx, dz)));
            cmd.set(sel + " #Dist.Text", Message.translation("server.orbis_horizon.ui.dest.info")
                    .param("n", (int) dist)
                    .param("time", BalloonManager.durationText(secs)));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel, EventData.of("Dest", o.name));
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "Close"));
    }

    /**
     * Compass point from one station to another, key suffix n, ne, e, se, s, sw, w or nw. North is -Z (yaw 0 looks towards -Z, the
     * convention of the mod, assumed to be the game's north). Pure, pinned by TransitTests.compassAndTrip.
     */
    static String compass(double dx, double dz) {
        if (dx * dx + dz * dz < 1e-9) {
            return "n";
        }
        double deg = Math.toDegrees(Math.atan2(dx, -dz)); // 0 north, 90 east
        String[] keys = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8);
        return keys[i];
    }

    /**
     * Estimated flight time in seconds, as the autopilot flies it: climb from the departure dock to the cruise height, straight line,
     * descent onto the arrival dock, wait on the pad (DROP_WAIT_SECONDS). The countdown at the quay, obstacles and holding are not
     * counted. Pure, pinned by TransitTests.compassAndTrip.
     */
    static double tripSeconds(int fromDockY, int toDockY, double horizontal, double hSpeed, double vSpeed) {
        double cruise = TransitAutopilot.cruiseY(fromDockY, toDockY);
        return (cruise - fromDockY) / vSpeed + horizontal / hSpeed + (cruise - toDockY) / vSpeed + TransitConstants.DROP_WAIT_SECONDS;
    }

    private double distSq(TransitNetwork.Station o) {
        double dx = o.origin.x - station.origin.x;
        double dy = o.origin.y - station.origin.y;
        double dz = o.origin.z - station.origin.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private Message statusLine() {
        String base = "server.orbis_horizon.ui.status.";
        return switch (StationQueue.state(station)) {
            case FREE -> Message.translation(base + "free");
            case APPROACH -> Message.translation(base + "approach");
            case DOCKED -> Message.translation(base + "docked").param("seconds", StationQueue.secondsLeft(station));
            case DEPARTING -> Message.translation(base + "departing");
            case ARRIVING -> Message.translation(base + "arriving");
        };
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.dest != null && !data.dest.isEmpty()) {
            if (!StationMaster.holds(station, playerRef.getUuid())) {
                // Reservation lost (8 blocks, 60 s, or taken by another player): the click is ignored.
                playerRef.sendMessage(Texts.t("transit.master.expired"));
                finished = true;
                close();
                return;
            }
            World world = store.getExternalData().getWorld();
            StationQueue.enqueue(world, station, data.dest, playerRef);
        }
        finish();
        close();
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        finish();
    }

    /** Releases the master once. */
    private void finish() {
        if (!finished) {
            finished = true;
            StationMaster.release(station, playerRef.getUuid());
        }
    }
}
