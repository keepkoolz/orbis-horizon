package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.Message;

import java.util.List;

/**
 * Chat messages and command descriptions translated by the client, in the language chosen in its settings
 * (Server/Languages/en-US and fr-FR/server.lang). Message.translation sends the key, not the text: the client
 * looks it up in the translations of its language, with English (en-US) as the fallback (I18nModule.getMessages).
 * The {name} parameters of the .lang file are filled in by Message.param, like the game's own commands (GiveCommand).
 */
final class Texts {
    /** Prefix of chat messages. The "server." prefix comes from the name of the server.lang file. */
    static final String CHAT = "server.orbis_horizon.chat.";
    /** Prefix of command descriptions: AbstractCommand passes the description through Message.translation. */
    static final String COMMANDS = "server.orbis_horizon.commands.";

    private Texts() {
    }

    /** Prefix of the texts of pages and windows (.ui files cannot use keys with an underscore, so Java sets them). */
    static final String UI = "server.orbis_horizon.ui.";

    /** Translated text of a page or window, key relative to UI. */
    static Message ui(String key) {
        return Message.translation(UI + key);
    }

    /** Translated chat message, key relative to CHAT. */
    static Message t(String key) {
        return Message.translation(CHAT + key);
    }

    /** Keys shared by the airship and the steamboat: "airship.<rest>" or "steamboat.<rest>" depending on the ship. */
    static final List<String> SHIP_KEYS = List.of("takeoff", "fuel.none", "landing.start", "landing.already", "landing.stuck", "landing.noRoom", "landed",
            "error.alreadyPiloting", "error.notPiloting", "error.noAirship", "error.prefabUnreadable", "error.full",
            "error.modelMissing", "passenger.seated", "passenger.landed", "passenger.pilotDied", "passenger.pilotLeft",
            "despawn.none", "despawn.done", "despawn.doneInventoryFull", "despawn.removedByAdmin",
            "despawn.alreadyLanding", "approach");

    /** True for the steamboat and the public shuttle boat (they share the boat texts). */
    static boolean isBoat(Deployables.Kind kind) {
        return kind == Deployables.STEAMBOAT || kind == Deployables.STEAMBOAT_PUBLIC;
    }

    /** Message of a ship: the steamboat text for the steamboat, the airship text otherwise (unknown kind included). */
    static Message ship(Deployables.Kind kind, String rest) {
        return t((isBoat(kind) ? "steamboat." : "airship.") + rest);
    }

    /** Key of a ship message given as "airship.<rest>": the steamboat key for the steamboat, the same key otherwise. */
    static String shipKey(Deployables.Kind kind, String key) {
        if (isBoat(kind) && key.startsWith("airship.") && SHIP_KEYS.contains(key.substring("airship.".length()))) {
            return "steamboat." + key.substring("airship.".length());
        }
        return key;
    }

    /** Chat line spoken by the pilot of an airship: the {pilot} parameter is his first name. */
    static Message pilot(String key, CrewRecord crew) {
        return t(key).param("pilot", PilotLook.nameOf(crew));
    }

    /** Full key of a command or argument description, relative to COMMANDS. */
    static String cmd(String key) {
        return COMMANDS + key;
    }
}
