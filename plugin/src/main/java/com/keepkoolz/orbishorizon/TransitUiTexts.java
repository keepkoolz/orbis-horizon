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
        cmd.set("#FarePrice.Text", m(TransitSettings.fareEnabled() ? "fare.price" : "fare.free"));
        cmd.set("#HireButton.Text", m("crew.hire"));
        cmd.set("#RecallButton.Text", m("crew.recall"));
        cmd.set("#ContractButton.Text", m("contract.buy"));
        cmd.set("#ArrivalButton.Text", m("dest.arrivalButton"));
        cmd.set("#ContractPrice.Text", m("contract.price")
                .param("essence", TransitSettings.contractEssences())
                .param("concentrated", TransitSettings.contractConcentrated()));
        cmd.set("#HirePrice.Text", m("crew.hirePrice")
                .param("gold", PilotContract.PRICE.get(PilotContract.GOLD))
                .param("silver", PilotContract.PRICE.get(PilotContract.SILVER))
                .param("essence", PilotContract.PRICE.get(PilotContract.ESSENCE)));
    }

    static void quayPlan(UICommandBuilder cmd, boolean boat) {
        cmd.set("#TitleText.Text", m("plan.title"));
        cmd.set("#Intro.Text", m("plan.intro"));
        cmd.set("#PadButton.Text", m(boat ? "plan.pier" : "plan.pad"));
        cmd.set("#PadText.Text", m(boat ? "plan.pierText" : "plan.padText"));
        cmd.set("#FullButton.Text", m("plan.full"));
        cmd.set("#FullText.Text", m(boat ? "plan.fullBoatText" : "plan.fullText"));
        cmd.set("#CloseButton.Text", m("close"));
    }

    static void stationName(UICommandBuilder cmd) {
        cmd.set("#TitleText.Text", m("name.title"));
        cmd.set("#NameLabel.Text", m("name.label"));
        cmd.set("#SetButton.Text", m("name.confirm"));
        cmd.set("#CancelButton.Text", m("name.cancel"));
    }
}
