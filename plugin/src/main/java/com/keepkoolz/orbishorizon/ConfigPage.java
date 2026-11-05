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

import com.keepkoolz.orbishorizon.api.ConfigRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Configuration window of the mod (/orbishorizon config, ops only): a search box and a scrolling list with one section per
 * topic (recipes, world, map, transit, camping, passengers). Every change is applied and saved at once, then the list is redrawn. The search
 * box is not rebuilt, so typing is not interrupted. Each event checks that the player is still an op.
 */
final class ConfigPage extends InteractiveCustomUIPage<ConfigPage.Data> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String UI = "server.orbis_horizon.ui.config.";

    static final String MARKER_SHARING = "markerSharing";
    static final String PACK_BACK = "packBack";
    static final String PROTECT = "protect";
    static final String FARE = "fare";
    static final String GENERATED_EDITABLE = "generatedStationsEditable";
    static final String CONSOLE_LOG_LEVEL = "consoleLogLevel";
    static final String CONTRACT_ESSENCES = "contractEssences";
    static final String CONTRACT_CONCENTRATED = "contractConcentrated";

    /** Keys of the built-in rows, which an extension row cannot take. */
    static final Set<String> BUILT_IN_KEYS = Set.of(MARKER_SHARING, PACK_BACK, PROTECT, FARE, GENERATED_EDITABLE, CONSOLE_LOG_LEVEL,
            CONTRACT_ESSENCES, CONTRACT_CONCENTRATED);

    static final class Data {
        static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>("Key", Codec.STRING), (d, v) -> d.key = v, d -> d.key).add()
                .append(new KeyedCodec<>("@Checked", Codec.BOOLEAN), (d, v) -> d.checked = v, d -> d.checked).add()
                .append(new KeyedCodec<>("@Choice", Codec.STRING), (d, v) -> d.choice = v, d -> d.choice).add()
                .append(new KeyedCodec<>("@SearchQuery", Codec.STRING), (d, v) -> d.searchQuery = v, d -> d.searchQuery).add()
                .append(new KeyedCodec<>("@Number", Codec.DOUBLE), (d, v) -> d.number = v, d -> d.number).add()
                .build();
        String action;
        String key;
        Boolean checked;
        String choice;
        String searchQuery;
        Double number;
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
        fillExtensionRows(cmd, events, index, lang);
        if (settingVisible(MARKER_SHARING, lang)) {
            section(cmd, index, "section.map");
            toggleRow(cmd, events, index, MARKER_SHARING, Message.translation(UI + MARKER_SHARING), null, TransitSettings.markerSharing());
        }
        boolean fareShown = settingVisible(FARE, lang);
        boolean editableShown = settingVisible(GENERATED_EDITABLE, lang);
        boolean essencesShown = settingVisible(CONTRACT_ESSENCES, lang);
        boolean concentratedShown = settingVisible(CONTRACT_CONCENTRATED, lang);
        if (fareShown || editableShown || essencesShown || concentratedShown) {
            section(cmd, index, "section.transit");
        }
        if (fareShown) {
            toggleRow(cmd, events, index, FARE, Message.translation(UI + FARE), null, TransitSettings.fareEnabled());
        }
        if (editableShown) {
            toggleRow(cmd, events, index, GENERATED_EDITABLE, Message.translation(UI + GENERATED_EDITABLE), null,
                    TransitSettings.generatedStationsEditable());
        }
        if (essencesShown) {
            numberRow(cmd, events, index, CONTRACT_ESSENCES, TransitSettings.contractEssences());
        }
        if (concentratedShown) {
            numberRow(cmd, events, index, CONTRACT_CONCENTRATED, TransitSettings.contractConcentrated());
        }
        if (settingVisible(PACK_BACK, lang)) {
            section(cmd, index, "section.camping");
            toggleRow(cmd, events, index, PACK_BACK, Message.translation(UI + PACK_BACK), null, TransitSettings.packBackView());
        }
        if (settingVisible(PROTECT, lang)) {
            section(cmd, index, "section.passengers");
            choiceRow(cmd, events, index, PROTECT, PassengerProtection.Mode.values(), PassengerProtection.mode());
        }
        if (settingVisible(CONSOLE_LOG_LEVEL, lang)) {
            section(cmd, index, "section.server");
            choiceRow(cmd, events, index, CONSOLE_LOG_LEVEL, ConsoleLogs.Threshold.values(), TransitSettings.consoleLogLevel());
        }
    }

    /** The rows of the extension, grouped by section in order of arrival. A section with no visible row is left out. */
    private void fillExtensionRows(UICommandBuilder cmd, UIEventBuilder events, int[] index, String lang) {
        List<ConfigRow> rows = Extensions.configRows();
        Map<String, List<ConfigRow>> bySection = new LinkedHashMap<>();
        for (ConfigRow r : rows) {
            if (ConfigSearch.matches(translate(lang, r.labelKey()), query) || ConfigSearch.matches(r.key(), query)) {
                bySection.computeIfAbsent(r.sectionKey(), k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<String, List<ConfigRow>> e : bySection.entrySet()) {
            cmd.append("#List", "Pages/Config_Section.ui");
            cmd.set("#List[" + index[0]++ + "] #Label.Text", Message.translation(e.getKey()));
            for (ConfigRow r : e.getValue()) {
                toggleRow(cmd, events, index, r.key(), Message.translation(r.labelKey()), null, Extensions.readRow(r));
            }
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

    /** A whole number row. The label is the key {@code ui.config.<key>}. */
    private void numberRow(UICommandBuilder cmd, UIEventBuilder events, int[] index, String key, int value) {
        cmd.append("#List", "Pages/Config_NumberRow.ui");
        String sel = "#List[" + index[0]++ + "]";
        cmd.set(sel + " #Label.Text", Message.translation(UI + key));
        cmd.set(sel + " #Input.Value", value);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                new EventData().append("Action", "Number").append("Key", key).append("@Number", sel + " #Input.Value"), false);
    }

    /** A dropdown row. Entry labels are the keys {@code <key>.<constant name in lower case>}, values the constant names. */
    private void choiceRow(UICommandBuilder cmd, UIEventBuilder events, int[] index, String key, Enum<?>[] choices, Enum<?> current) {
        cmd.append("#List", "Pages/Config_ChoiceRow.ui");
        String sel = "#List[" + index[0]++ + "]";
        cmd.set(sel + " #Label.Text", Message.translation(UI + key));
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (Enum<?> c : choices) {
            entries.add(new DropdownEntryInfo(
                    LocalizableString.fromMessageId(UI + key + "." + c.name().toLowerCase(java.util.Locale.ROOT)), c.name()));
        }
        cmd.set(sel + " #Input.Entries", entries);
        cmd.set(sel + " #Input.Value", current.name());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                new EventData().append("Action", "Choice").append("Key", key).append("@Choice", sel + " #Input.Value"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        try {
            if (!StructureRemoval.isOp(playerRef.getUuid())) {
                LOGGER.at(Level.INFO).log("Configuration ignored: %s is no longer an operator", playerRef.getUsername());
                close();
                return;
            }
            if (data.searchQuery != null) {
                query = data.searchQuery;
            } else if ("Toggle".equals(data.action) && data.key != null && data.checked != null) {
                applyToggle(data.key, data.checked);
            } else if ("Number".equals(data.action) && data.key != null && data.number != null) {
                applyNumber(data.key, data.number);
                return; // the list is not redrawn: the field being typed in keeps its text
            } else if ("Choice".equals(data.action) && data.key != null && data.choice != null) {
                applyChoice(data.key, data.choice);
            } else {
                return;
            }
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            fillList(cmd, events);
            sendUpdate(cmd, events, false);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Configuration page: event not handled");
            Telemetry.error("config", e, null, null);
        }
    }

    private static void applyToggle(String key, boolean on) {
        ConfigRow row = Extensions.configRow(key);
        if (row != null) {
            Extensions.writeRow(row, on);
            return;
        }
        switch (key) {
            case MARKER_SHARING -> TransitSettings.setMarkerSharing(on);
            case FARE -> TransitSettings.setFareEnabled(on);
            case GENERATED_EDITABLE -> TransitSettings.setGeneratedStationsEditable(on);
            case PACK_BACK -> PackBackView.set(on);
            default -> RecipeToggles.set(key, on); // ignores an id that is not a mod item
        }
    }

    private static void applyNumber(String key, double value) {
        if (Double.isNaN(value) || value < 1 || value > 1_000_000) return;
        int n = (int) Math.round(value);
        switch (key) {
            case CONTRACT_ESSENCES -> TransitSettings.setContractEssences(n);
            case CONTRACT_CONCENTRATED -> TransitSettings.setContractConcentrated(n);
            default -> LOGGER.at(Level.INFO).log("Number ignored: unknown setting '%s'", key);
        }
    }

    private static void applyChoice(String key, String name) {
        try {
            switch (key) {
                case PROTECT -> PassengerProtection.setMode(PassengerProtection.Mode.valueOf(name));
                case CONSOLE_LOG_LEVEL -> TransitSettings.setConsoleLogLevel(ConsoleLogs.Threshold.valueOf(name));
                default -> LOGGER.at(Level.INFO).log("Choice ignored: unknown setting '%s'", key);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.at(Level.INFO).log("Choice ignored: unknown value '%s' for %s", name, key);
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
            LOGGER.at(Level.WARNING).withCause(e).log("Item name %s not read", itemId);
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
