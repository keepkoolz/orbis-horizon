package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name window of a vehicle (airship, steamboat, large balloon), on the page of the station naming. The vehicle already has a
 * name when the window opens (the automatic one when it had none), so a window closed without validating changes nothing.
 * The follow-up given at creation runs once, when the window closes whatever the way.
 */
final class VehicleNamePage extends InteractiveCustomUIPage<VehicleNamePage.Data> {

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("@Name", Codec.STRING), (d, v) -> d.name = v, d -> d.name).add()
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .build();
        String name;
        String action;
    }

    /** Open name pages by player, so a disconnection forgets the page. */
    private static final Map<UUID, VehicleNamePage> OPEN = new ConcurrentHashMap<>();

    private final UUID structureId;
    private final Runnable after;
    private boolean done;

    VehicleNamePage(PlayerRef player, UUID structureId, String kind, Runnable after) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.structureId = structureId;
        this.after = after;
        OPEN.put(player.getUuid(), this);
    }

    /** Gives the vehicle its automatic name if it has none, then opens the window. */
    static void open(Store<EntityStore> store, Ref<EntityStore> player, PlayerRef pr, UUID structureId, String kind, Runnable after) {
        try {
            Player p = store.getComponent(player, Player.getComponentType());
            if (p == null || structureId == null) {
                runAfter(after);
                return;
            }
            if (VehicleNames.get(structureId) == null) {
                VehicleNames.set(structureId, VehicleNames.autoName(pr, kind));
            }
            p.getPageManager().openCustomPage(player, store, new VehicleNamePage(pr, structureId, kind, after));
        } catch (RuntimeException e) {
            OPEN.remove(pr.getUuid());
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e)
                    .log("Vehicle name window not opened");
            Telemetry.error("vehicle-name", e, kind, "open");
            runAfter(after);
        }
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Transit_StationName.ui");
        cmd.set("#TitleText.Text", Texts.ui("vehicle.name.title"));
        cmd.set("#NameLabel.Text", Texts.ui("vehicle.name.label"));
        cmd.set("#SetButton.Text", Texts.ui("vehicle.name.set"));
        cmd.set("#CancelButton.Text", Texts.ui("vehicle.name.cancel"));
        String current = VehicleNames.get(structureId);
        cmd.set("#NameInput.Value", current == null ? "" : current);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetButton", EventData.of("@Name", "#NameInput.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton", EventData.of("Action", "Cancel"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (done) {
            close();
            return;
        }
        if ("Cancel".equals(data.action) || data.name == null) {
            close();
            finish();
            return;
        }
        String name = VehicleNames.clean(data.name);
        String error = null;
        if (name == null) {
            error = "vehicle.name.length";
        } else {
            Map<UUID, String> others = VehicleNames.namesOf(VehicleNames.ownedVehicles(playerRef.getUuid()));
            if (VehicleNames.taken(name, structureId, others)) {
                error = "vehicle.name.taken";
            }
        }
        if (error != null) {
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#Error.Text", Texts.t(error).param("name", data.name.trim()));
            cmd.set("#Error.Visible", true);
            sendUpdate(cmd, false);
            return;
        }
        VehicleNames.set(structureId, name);
        playerRef.sendMessage(Texts.t("vehicle.name.done").param("name", name));
        close();
        finish();
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        finish();
    }

    /** Runs the follow-up once. */
    private void finish() {
        if (done) {
            return;
        }
        done = true;
        OPEN.remove(playerRef.getUuid(), this);
        runAfter(after);
    }

    private static void runAfter(Runnable after) {
        if (after != null) {
            try {
                after.run();
            } catch (RuntimeException e) {
                com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING).withCause(e)
                        .log("Vehicle name window: follow-up failed");
                Telemetry.error("vehicle-name", e, null, null);
            }
        }
    }

    /** Called when a player disconnects: the window is forgotten, the vehicle keeps its name. */
    static void onPlayerGone(UUID player) {
        VehicleNamePage page = OPEN.remove(player);
        if (page != null) {
            page.done = true;
        }
    }
}
