package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Configuration window of the mod (/orbishorizon config, ops only): a search box and a scrolling list with one section per
 * topic (recipes, world, map, camping, passengers). Every change is applied and saved at once, then the list is redrawn. The search
 * box is not rebuilt, so typing is not interrupted. Each event checks that the player is still an op.
 */
final class ConfigPage extends InteractiveCustomUIPage<ConfigPage.Data> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String UI = "server.orbis_horizon.ui.config.";

    static final String WORLDGEN = "worldgen";
    static final String MARKER_SHARING = "markerSharing";
    static final String PACK_BACK = "packBack";
    static final String PROTECT = "protect";

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>("Key", Codec.STRING), (d, v) -> d.key = v, d -> d.key).add()
                .append(new KeyedCodec<>("@Checked", Codec.BOOLEAN), (d, v) -> d.checked = v, d -> d.checked).add()
                .append(new KeyedCodec<>("@Choice", Codec.STRING), (d, v) -> d.choice = v, d -> d.choice).add()
                .append(new KeyedCodec<>("@SearchQuery", Codec.STRING), (d, v) -> d.searchQuery = v, d -> d.searchQuery).add()
                .build();
        String action;
        String key;
        Boolean checked;
        String choice;
        String searchQuery;
    }

    private String query = "";

    ConfigPage(PlayerRef player) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/Config.ui");
        cmd.set("#TitleText.Text", Message.translation("server.orbis_horizon.ui.config.title"));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                EventData.of("@SearchQuery", "#SearchInput.Value"), false);
        fillList(cmd, events);
    }

    /** Clears and refills the list for the current search query. A section with no visible row is left out. */
    private void fillList(UICommandBuilder cmd, UIEventBuilder events) {
        cmd.clear("#List");
        int[] index = {0};
        String lang = playerRef.getLanguage();

        List<String> recipes = new ArrayList<>();
        for (String id : ModRecipes.ITEMS) {
            if (ConfigSearch.matches(recipeLabel(id, lang), query) || ConfigSearch.matches(id, query)) {
                recipes.add(id);
            }
        }
        if (!recipes.isEmpty()) {
            section(cmd, index, "section.recipes");
            for (String id : recipes) {
                toggleRow(cmd, events, index, id, Message.translation(recipeKey(id)), id, RecipeToggles.enabled(id));
            }
        }
        if (settingVisible(WORLDGEN, lang)) {
            section(cmd, index, "section.world");
            toggleRow(cmd, events, index, WORLDGEN, Message.translation(UI + WORLDGEN), null, TransitSettings.worldgenEnabled());
        }
        if (settingVisible(MARKER_SHARING, lang)) {
            section(cmd, index, "section.map");
            toggleRow(cmd, events, index, MARKER_SHARING, Message.translation(UI + MARKER_SHARING), null, TransitSettings.markerSharing());
        }
        if (settingVisible(PACK_BACK, lang)) {
            section(cmd, index, "section.camping");
            toggleRow(cmd, events, index, PACK_BACK, Message.translation(UI + PACK_BACK), null, TransitSettings.packBackView());
        }
        if (settingVisible(PROTECT, lang)) {
            section(cmd, index, "section.passengers");
            choiceRow(cmd, events, index);
        }
    }

    private boolean settingVisible(String key, String lang) {
        return ConfigSearch.matches(translate(lang, UI + key), query) || ConfigSearch.matches(key, query);
    }

    private static void section(UICommandBuilder cmd, int[] index, String key) {
        cmd.append("#List", "Pages/Config_Section.ui");
        cmd.set("#List[" + index[0]++ + "] #Label.Text", Message.translation(UI + key));
    }

    private void toggleRow(UICommandBuilder cmd, UIEventBuilder events, int[] index, String key, Message label, String itemId, boolean on) {
        cmd.append("#List", "Pages/Config_ToggleRow.ui");
        String sel = "#List[" + index[0]++ + "]";
        cmd.set(sel + " #Label.Text", label);
        cmd.set(sel + " #Input.Value", on);
        if (itemId != null) {
            cmd.set(sel + " #Icon.ItemId", itemId);
        } else {
            cmd.set(sel + " #Icon.Visible", false);
        }
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                new EventData().append("Action", "Toggle").append("Key", key).append("@Checked", sel + " #Input.Value"), false);
    }

    private void choiceRow(UICommandBuilder cmd, UIEventBuilder events, int[] index) {
        cmd.append("#List", "Pages/Config_ChoiceRow.ui");
        String sel = "#List[" + index[0]++ + "]";
        cmd.set(sel + " #Label.Text", Message.translation(UI + PROTECT));
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (PassengerProtection.Mode m : PassengerProtection.Mode.values()) {
            entries.add(new DropdownEntryInfo(
                    LocalizableString.fromMessageId(UI + PROTECT + "." + m.name().toLowerCase(java.util.Locale.ROOT)), m.name()));
        }
        cmd.set(sel + " #Input.Entries", entries);
        cmd.set(sel + " #Input.Value", PassengerProtection.mode().name());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                new EventData().append("Action", "Choice").append("Key", PROTECT).append("@Choice", sel + " #Input.Value"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        try {
            if (!StructureRemoval.isOp(playerRef.getUuid())) {
                LOGGER.at(Level.INFO).log("Configuration ignorée : %s n'est plus opérateur", playerRef.getUsername());
                close();
                return;
            }
            if (data.searchQuery != null) {
                query = data.searchQuery;
            } else if ("Toggle".equals(data.action) && data.key != null && data.checked != null) {
                applyToggle(data.key, data.checked);
            } else if ("Choice".equals(data.action) && PROTECT.equals(data.key) && data.choice != null) {
                applyProtection(data.choice);
            } else {
                return;
            }
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            fillList(cmd, events);
            sendUpdate(cmd, events, false);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Page de configuration : événement non traité");
            Telemetry.error("config", e, null, null);
        }
    }

    private static void applyToggle(String key, boolean on) {
        switch (key) {
            case WORLDGEN -> TransitSettings.setWorldgenEnabled(on);
            case MARKER_SHARING -> TransitSettings.setMarkerSharing(on);
            case PACK_BACK -> PackBackView.set(on);
            default -> RecipeToggles.set(key, on); // ignores an id that is not a mod item
        }
    }

    private static void applyProtection(String name) {
        try {
            PassengerProtection.setMode(PassengerProtection.Mode.valueOf(name));
        } catch (IllegalArgumentException e) {
            LOGGER.at(Level.INFO).log("Protection ignorée : valeur inconnue « %s »", name);
        }
    }

    /** Translation key of the name an item declares in its JSON, or the key of its id when the item has none. */
    private static String recipeKey(String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null && item.getTranslationProperties() != null && item.getTranslationProperties().getName() != null) {
                return item.getTranslationProperties().getName();
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Nom de l'objet %s non lu", itemId);
        }
        return "server.items." + itemId + ".name";
    }

    /** Name of a recipe in the player's language, the item id when no translation is found. */
    private static String recipeLabel(String itemId, String lang) {
        String text = translate(lang, recipeKey(itemId));
        return text != null ? text : itemId;
    }

    /** Text of a key in a language (English as fallback, handled by I18nModule), null when unknown. Keys with and without the "server." prefix are tried. */
    private static String translate(String lang, String key) {
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n == null) {
                return null;
            }
            String text = i18n.getMessage(lang, key);
            if (text == null && key.startsWith("server.")) {
                text = i18n.getMessage(lang, key.substring("server.".length()));
            }
            return text;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
