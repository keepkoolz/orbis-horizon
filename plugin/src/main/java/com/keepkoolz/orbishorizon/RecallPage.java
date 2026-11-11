package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.logger.HytaleLogger;
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

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Window listing the player's recallable vehicles, opened from the destination window of a station master. It takes over the
 * reservation of the master and releases it when it ends, whatever the cause (a choice, close, Escape).
 */
final class RecallPage extends InteractiveCustomUIPage<RecallPage.Data> {
    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Vehicle", Codec.STRING), (d, v) -> d.vehicle = v, d -> d.vehicle).add()
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .build();
        String vehicle;
        String action;
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final TransitNetwork.Station station;
    private boolean finished;

    RecallPage(PlayerRef player, TransitNetwork.Station station) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.station = station;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        try {
            cmd.append("Pages/Transit_Destinations.ui");
            TransitUiTexts.destinations(cmd, StationSpec.of(station.kind).titleKey());
            cmd.set("#TitleText.Text", Message.translation("server.orbis_horizon.ui.recall.title"));
            for (String id : new String[] {"#ContractButton", "#HireButton", "#ArrivalButton", "#RecallButton", "#Queue", "#FarePrice",
                    "#ContractPrice", "#HirePrice", "#DepHeader"}) {
                cmd.set(id + ".Visible", false);
            }
            World world = store.getExternalData().getWorld();
            List<VehicleRecall.Candidate> list = VehicleRecall.candidates(world, playerRef.getUuid(), station, playerRef);
            cmd.set("#Header.Text", list.isEmpty() ? Message.translation("server.orbis_horizon.ui.recall.none") : Message.translation("server.orbis_horizon.ui.recall.header"));
            cmd.clear("#List");
            for (int i = 0; i < list.size(); i++) {
                VehicleRecall.Candidate c = list.get(i);
                cmd.append("#List", "Pages/Transit_DestinationEntry.ui");
                String sel = "#List[" + i + "]";
                // A page field takes a plain string or a single translation, never a composed message (the client disconnects)
                cmd.set(sel + " #Name.Text", c.name() + " (" + VehicleNames.typeLabel(playerRef, c.kind()) + ")");
                cmd.set(sel + " #Dir.Text", Message.translation("server.orbis_horizon.ui.dir." + c.direction()));
                cmd.set(sel + " #Dist.Text", Message.translation("server.orbis_horizon.ui.recall.distance").param("n", (int) Math.round(c.distance())));
                events.addEventBinding(CustomUIEventBindingType.Activating, sel, EventData.of("Vehicle", c.id().toString()));
            }
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "Close"));
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Recall window not built");
            Telemetry.error("vehicle.recall", e, null, "page");
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        try {
            if ("Close".equals(data.action) || data.vehicle == null || data.vehicle.isEmpty()) {
                finish();
                close();
                return;
            }
            if (!StationMaster.holds(station, playerRef.getUuid())) {
                playerRef.sendMessage(Texts.t("transit.master.expired"));
                finished = true;
                close();
                return;
            }
            UUID id;
            try {
                id = UUID.fromString(data.vehicle);
            } catch (IllegalArgumentException e) {
                finish();
                close();
                return;
            }
            Message refusal = VehicleRecall.start(store, store.getExternalData().getWorld(), playerRef, station, id);
            if (refusal != null) {
                // The page stays open with the master's answer.
                UICommandBuilder cmd = new UICommandBuilder();
                cmd.set("#Status.Text", refusal);
                sendUpdate(cmd, false);
                return;
            }
            finish();
            close();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Recall window event not handled");
            Telemetry.error("vehicle.recall", e, null, "page");
            finish();
            close();
        }
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
