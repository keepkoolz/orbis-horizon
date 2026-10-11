package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Every fixed label and both window titles of the transit windows. The .ui files hold empty placeholders (the .ui parser rejects
 * "_" in a %key, so mod keys cannot be used there) and the server sets the translated texts here.
 */
final class TransitUiTexts {
    

    private TransitUiTexts() {
    }

    private static Message m(String key) {
        return Message.translation("server.orbis_horizon.ui." + key);
    }

    static void destinations(UICommandBuilder cmd) {
        cmd.set("#TitleText.Text", m("dest.title"));
        cmd.set("#CloseButton.Text", m("close"));
    }

    static void stationName(UICommandBuilder cmd) {
        cmd.set("#TitleText.Text", m("name.title"));
        cmd.set("#NameLabel.Text", m("name.label"));
        cmd.set("#SetButton.Text", m("name.confirm"));
        cmd.set("#CancelButton.Text", m("name.cancel"));
    }
}
