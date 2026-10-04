package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.Message;

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

    /** Translated chat message, key relative to CHAT. */
    static Message t(String key) {
        return Message.translation(CHAT + key);
    }

    /** Full key of a command or argument description, relative to COMMANDS. */
    static String cmd(String key) {
        return COMMANDS + key;
    }
}
