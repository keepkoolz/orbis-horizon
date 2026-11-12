package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.logging.Level;

/**
 * The album of the explorer's journal, drawn as an open book. The index (left page) lists the families the player has started, a family page shows one slot per
 * piece of furniture and the detail of the chosen slot. Every text sent comes from JournalView, so nothing of a family the
 * player has not started reaches the client, and every event is checked against the album again.
 */
final class JournalPage extends InteractiveCustomUIPage<JournalPage.Data> {

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>("Key", Codec.STRING), (d, v) -> d.key = v, d -> d.key).add()
                .build();
        String action;
        String key;
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** The family page shown, null for the index. */
    private String familyId;
    /** The item chosen on the family page, null for none. */
    private String selected;

    JournalPage(PlayerRef player) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        try {
            DiscoveryStore.Album album = DiscoveryStore.get(playerRef.getUuid());
            cmd.append("Pages/Journal.ui");
            cmd.set("#JournalTitle.Text", Texts.ui("journal.title"));
            cmd.set("#Lore.Visible", false);
            cmd.set("#Counter.Visible", false);
            cmd.set("#Titles.Visible", false);
            cmd.set("#Empty.Visible", false);
            cmd.set("#Intro.Visible", false);
            cmd.set("#Detail.Visible", false);
            cmd.set("#Grid.Visible", false);
            cmd.set("#BookmarkRow.Visible", false);
            cmd.set("#PrevButton.Visible", false);
            cmd.set("#NextButton.Visible", false);
            if (familyId != null && JournalView.slots(album, familyId).isEmpty()) {
                familyId = null;
                selected = null;
            }
            cmd.clear("#List");
            cmd.clear("#Slots");
            if (familyId == null) {
                buildIndex(cmd, events, album);
            } else {
                buildFamily(cmd, events, album);
            }
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Journal page not built");
            Telemetry.error("discovery", t, "journal", "build");
            closeQuietly();
        }
    }

    private void buildIndex(UICommandBuilder cmd, UIEventBuilder events, DiscoveryStore.Album album) {
        List<JournalView.IndexRow> rows = JournalView.index(album);
        cmd.set("#Intro.Visible", true);
        cmd.set("#Intro.Text", Texts.ui("journal.intro"));
        if (rows.isEmpty()) {
            cmd.set("#Empty.Visible", true);
            cmd.set("#Empty.Text", Texts.ui("journal.empty"));
            return;
        }
        List<String> titles = JournalView.earnedTitles(album);
        if (!titles.isEmpty()) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < titles.size(); i++) {
                line.append(i == 0 ? "" : ", ");
                line.append(translate(playerRef.getLanguage(), Texts.CHAT + FurnitureDiscovery.familyTitleKey(titles.get(i))));
            }
            cmd.set("#Titles.Visible", true);
            cmd.set("#Titles.Text", line.toString());
        }
        for (int i = 0; i < rows.size(); i++) {
            JournalView.IndexRow row = rows.get(i);
            cmd.append("#List", "Pages/Journal_IndexEntry.ui");
            String sel = "#List[" + i + "]";
            cmd.set(sel + " #Name.Text", Texts.t(FurnitureDiscovery.familyNameKey(row.familyId())));
            cmd.set(sel + " #Count.Text", row.found() + " / " + row.total());
            if (row.titled()) {
                cmd.set(sel + " #Title.Text", Texts.t(FurnitureDiscovery.familyTitleKey(row.familyId())));
            } else {
                cmd.set(sel + " #Title.Text", "");
            }
            events.addEventBinding(CustomUIEventBindingType.Activating, sel,
                    new EventData().append("Action", "Family").append("Key", row.familyId()));
        }
    }

    private void buildFamily(UICommandBuilder cmd, UIEventBuilder events, DiscoveryStore.Album album) {
        List<JournalView.Slot> slots = JournalView.slots(album, familyId);
        FurnitureDiscovery.Family family = FurnitureDiscovery.family(familyId);
        int found = album.found(familyId);
        cmd.set("#BookmarkRow.Visible", true);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Bookmark", EventData.of("Action", "Back"));
        if (JournalView.previousFamily(album, familyId) != null) {
            cmd.set("#PrevButton.Visible", true);
            cmd.set("#PrevLabel.Text", "\u2039");
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevButton", EventData.of("Action", "Prev"));
        }
        if (JournalView.nextFamily(album, familyId) != null) {
            cmd.set("#NextButton.Visible", true);
            cmd.set("#NextLabel.Text", "\u203A");
            events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton", EventData.of("Action", "Next"));
        }
        cmd.set("#Grid.Visible", true);
        cmd.set("#JournalTitle.Text", Texts.t(FurnitureDiscovery.familyNameKey(familyId)));
        cmd.set("#Lore.Visible", true);
        cmd.set("#Lore.Text", Texts.t(FurnitureDiscovery.familyLoreKey(familyId)));
        cmd.set("#Counter.Visible", true);
        cmd.set("#Counter.Text", found + " / " + family.items().size());
        if (album.titles().contains(familyId)) {
            cmd.set("#Titles.Visible", true);
            cmd.set("#Titles.Text", Texts.t(FurnitureDiscovery.familyTitleKey(familyId)));
        }
        for (int i = 0; i < slots.size(); i++) {
            JournalView.Slot slot = slots.get(i);
            String sel = "#Slots[" + i + "]";
            if (slot.itemId() == null) {
                cmd.append("#Slots", "Pages/Journal_SlotEmpty.ui");
                cmd.set(sel + " #Name.Text", "?");
                continue;
            }
            cmd.append("#Slots", "Pages/Journal_Slot.ui");
            cmd.set(sel + " #Icon.ItemId", slot.itemId());
            cmd.set(sel + " #Name.Text", Texts.t(FurnitureDiscovery.itemNameKey(slot.itemId())));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel,
                    new EventData().append("Action", "Slot").append("Key", slot.itemId()));
        }
        fillDetail(cmd, slots);
    }

    /** Shows the detail panel of the chosen slot, nothing when no found slot is chosen. */
    private void fillDetail(UICommandBuilder cmd, List<JournalView.Slot> slots) {
        JournalView.Slot chosen = null;
        for (JournalView.Slot s : slots) {
            if (s.itemId() != null && s.itemId().equals(selected)) {
                chosen = s;
            }
        }
        if (chosen != null) {
            cmd.set("#Detail.Visible", true);
            cmd.set("#DetailName.Text", Texts.t(FurnitureDiscovery.itemNameKey(chosen.itemId())));
            cmd.set("#DetailFamily.Text", Texts.t(FurnitureDiscovery.familyNameKey(familyId)));
            DiscoveryStore.Entry e = chosen.entry();
            cmd.set("#DetailFound.Text", Texts.ui("journal.foundAt").param("x", e.x()).param("y", e.y()).param("z", e.z()));
            cmd.set("#DetailLore.Text", Texts.t(FurnitureDiscovery.itemLoreKey(chosen.itemId())));
            cmd.set("#DetailCraft.Text", Texts.ui("journal.craftable"));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        boolean keepOpen = false;
        boolean rebuild = true;
        try {
            DiscoveryStore.Album album = DiscoveryStore.get(playerRef.getUuid());
            String action = data.action == null ? "" : data.action;
            switch (action) {
                case "Family" -> {
                    // A forged family id, or one the player has not started, is ignored.
                    if (data.key != null && !JournalView.slots(album, data.key).isEmpty()) {
                        familyId = data.key;
                        selected = null;
                    }
                    keepOpen = true;
                }
                case "Slot" -> {
                    if (familyId != null && data.key != null && JournalView.slots(album, familyId).stream()
                            .anyMatch(s -> data.key.equals(s.itemId()))) {
                        selected = data.key;
                        // Only the detail panel changes: a partial update keeps the scroll position of the grid.
                        UICommandBuilder detail = new UICommandBuilder();
                        fillDetail(detail, JournalView.slots(album, familyId));
                        sendUpdate(detail, false);
                    }
                    rebuild = false;
                    keepOpen = true;
                }
                case "Prev", "Next" -> {
                    String target = familyId == null ? null : "Prev".equals(action)
                            ? JournalView.previousFamily(album, familyId) : JournalView.nextFamily(album, familyId);
                    if (target != null) {
                        familyId = target;
                        selected = null;
                    }
                    keepOpen = true;
                }
                case "Back" -> {
                    familyId = null;
                    selected = null;
                    keepOpen = true;
                }
                default -> {
                }
            }
            if (keepOpen && rebuild) {
                rebuild();
            }
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Journal event failed");
            Telemetry.error("discovery", t, "journal", "event");
            keepOpen = false;
        } finally {
            if (!keepOpen) {
                closeQuietly();
            }
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Journal page not closed");
        }
    }

    /** Text of a key in a language (English as fallback, handled by I18nModule), the key itself when it cannot be resolved. */
    private static String translate(String lang, String key) {
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n != null) {
                String text = i18n.getMessage(lang, key);
                if (text == null && key.startsWith("server.")) {
                    text = i18n.getMessage(lang, key.substring("server.".length()));
                }
                if (text != null) {
                    return text;
                }
            }
        } catch (RuntimeException e) {
            // falls back to the key
        }
        return key;
    }
}
