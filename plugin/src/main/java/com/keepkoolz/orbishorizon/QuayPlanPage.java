package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * Quay plan window of a station master whose station has no quay yet. Opened by StationMaster.onTalk for the owner, which has
 * already reserved the master. A click on a plan hands the plan item of the station kind to the player and closes the page. The
 * reservation is released when the page ends, whatever the cause.
 */
final class QuayPlanPage extends InteractiveCustomUIPage<QuayPlanPage.Data> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String BALLOON_PAD = "Hotair_Balloon_Station_Plan_Pad";
    static final String BALLOON_FULL = "Hotair_Balloon_Station_Plan_Full";
    static final String BOAT_PIER = "Hotair_Balloon_Boat_Station_Plan_Pier";
    static final String BOAT_FULL = "Hotair_Balloon_Boat_Station_Plan_Full";
    static final String BOAT_ARRIVAL = "Hotair_Balloon_Boat_Station_Plan_Arrival";

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .build();
        String action;
    }

    private final TransitNetwork.Station station;
    private boolean finished;

    QuayPlanPage(PlayerRef player, TransitNetwork.Station station) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.station = station;
    }

    /** Plan item of a station kind: the quay alone (pad or pier) or the whole station. Pure. */
    static String planItem(String kind, boolean full) {
        if (Deployables.BOAT_STATION_KIND.equals(kind)) return full ? BOAT_FULL : BOAT_PIER;
        return full ? BALLOON_FULL : BALLOON_PAD;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Transit_QuayPlan.ui");
        TransitUiTexts.quayPlan(cmd, Deployables.BOAT_STATION_KIND.equals(station.kind));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PadButton", EventData.of("Action", "Pad"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FullButton", EventData.of("Action", "Full"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "Close"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        try {
            boolean pad = "Pad".equals(data.action);
            boolean full = "Full".equals(data.action);
            if (pad || full) {
                if (!StationMaster.holds(station, playerRef.getUuid())) {
                    playerRef.sendMessage(Texts.t("transit.master.expired"));
                } else if (station.quay != null) {
                    playerRef.sendMessage(Texts.t("transit.master.hasQuay"));
                } else {
                    StationContracts.Given given = StationContracts.give(store, ref, planItem(station.kind, full));
                    playerRef.sendMessage(Texts.t(switch (given) {
                        case FAILED -> "transit.plan.failed";
                        case DROPPED -> "transit.plan.givenDropped";
                        case GIVEN -> "transit.plan.given";
                    }));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Quay plan not given for '%s'", station.name);
            Telemetry.error("transit", e, null, null);
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
