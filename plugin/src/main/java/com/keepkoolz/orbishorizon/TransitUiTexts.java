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

    static void destinations(UICommandBuilder cmd, String titleKey) {
        cmd.set("#TitleText.Text", m(titleKey));
        cmd.set("#CloseButton.Text", m("close"));
        cmd.set("#FarePrice.Text", m("fare.price"));
        cmd.set("#HireButton.Text", m("crew.hire"));
        cmd.set("#RecallButton.Text", m("crew.recall"));
        cmd.set("#HirePrice.Text", m("crew.hirePrice")
                .param("gold", PilotContract.PRICE.get(PilotContract.GOLD))
                .param("silver", PilotContract.PRICE.get(PilotContract.SILVER))
                .param("essence", PilotContract.PRICE.get(PilotContract.ESSENCE)));
    }

    static void stationName(UICommandBuilder cmd) {
        cmd.set("#TitleText.Text", m("name.title"));
        cmd.set("#NameLabel.Text", m("name.label"));
        cmd.set("#SetButton.Text", m("name.confirm"));
        cmd.set("#CancelButton.Text", m("name.cancel"));
    }
}
