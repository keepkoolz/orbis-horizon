package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name window shown right after a station received its quay (PlaceQuayInteraction). The station already is in the network under
 * its automatic name: a valid name (Confirm) renames it, and a page closed without validating (Cancel, Escape, disconnection,
 * timeout) keeps the automatic name. The station is named exactly once.
 */
final class StationNamePage extends InteractiveCustomUIPage<StationNamePage.Data> {

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("@Name", Codec.STRING), (d, v) -> d.name = v, d -> d.name).add()
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .build();
        String name;
        String action;
    }

    /** Open name pages by player, so a disconnection can register the station with its automatic name. */
    private static final Map<UUID, StationNamePage> OPEN = new ConcurrentHashMap<>();

    private final World world;
    private final TransitNetwork.Station station;
    private boolean done;
    private final long openedMs = System.currentTimeMillis();

    StationNamePage(PlayerRef player, World world, TransitNetwork.Station station) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.world = world;
        this.station = station;
        OPEN.put(player.getUuid(), this);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Transit_StationName.ui");
        TransitUiTexts.stationName(cmd);
        cmd.set("#NameInput.Value", station.name);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetButton", EventData.of("@Name", "#NameInput.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton", EventData.of("Action", "Cancel"));
    }

    private ArrayList<String> takenNames() {
        ArrayList<String> taken = new ArrayList<>();
        for (TransitNetwork.Station s : TransitNetwork.get().stations(world.getName())) {
            if (s != station) {
                taken.add(s.name);
            }
        }
        return taken;
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (done) {
            // Named meanwhile (timeout): the name stays. The player is told and the page closes.
            playerRef.sendMessage(Texts.t("transit.name.late"));
            close();
            return;
        }
        if ("Cancel".equals(data.action) || data.name == null) {
            finish(null);
            close();
            return;
        }
        TransitNetwork.NameCheck check = TransitNetwork.checkName(data.name, takenNames());
        if (check != TransitNetwork.NameCheck.OK) {
            String key = switch (check) {
                case TOO_SHORT -> "transit.name.tooShort";
                case TOO_LONG -> "transit.name.tooLong";
                default -> "transit.name.taken";
            };
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#Error.Text", Texts.t(key));
            cmd.set("#Error.Visible", true);
            sendUpdate(cmd, false);
            return;
        }
        finish(TransitNetwork.normalize(data.name));
        close();
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        finish(null);
    }

    /** Names the station once: with the given name, or keeping the automatic one when null. */
    private void finish(String name) {
        if (done) {
            return;
        }
        done = true;
        OPEN.remove(playerRef.getUuid(), this);
        StationPlacement.completeNaming(world, station, name, playerRef);
    }

    /**
     * Safety net for a page that was replaced while the player stays online (no dismiss callback is guaranteed): after
     * NAME_PAGE_TIMEOUT_SECONDS the station keeps its automatic name. A later Validate is refused (transit.name.late).
     */
    static void sweep(String worldName) {
        long now = System.currentTimeMillis();
        for (StationNamePage page : OPEN.values()) {
            if (page.world.getName().equals(worldName) && now - page.openedMs >= TransitConstants.NAME_PAGE_TIMEOUT_SECONDS * 1000L) {
                page.finish(null);
            }
        }
    }

    /** Called when a player disconnects: a pending name page leaves its station with the automatic name. */
    static void onPlayerGone(UUID player) {
        StationNamePage page = OPEN.get(player);
        if (page != null) {
            page.finish(null);
        }
    }
}
