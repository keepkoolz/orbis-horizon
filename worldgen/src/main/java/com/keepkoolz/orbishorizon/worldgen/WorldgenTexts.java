package com.keepkoolz.orbishorizon.worldgen;

import com.hypixel.hytale.server.core.Message;

/** Chat messages and command descriptions of the station generation, translated by the client from server.lang. */
final class WorldgenTexts {
    private static final String CHAT = "server.orbis_horizon.chat.";
    private static final String COMMANDS = "server.orbis_horizon.commands.";

    private WorldgenTexts() {
    }

    /** Translated chat message, key relative to the chat prefix. */
    static Message t(String key) {
        return Message.translation(CHAT + key);
    }

    /** Translation key of a command description or argument, relative to the commands prefix. */
    static String cmd(String key) {
        return COMMANDS + key;
    }
}
