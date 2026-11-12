package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.List;

/**
 * The messages an examination sends, as an ordered list of chat keys. The rules are pure: the interaction does the world and
 * album work, then asks which lines to send.
 */
final class DiscoveryRules {
    /** A chat key (relative to the chat prefix) and its parameter, null when the message has none. */
    record Line(String key, String param) {
    }

    private DiscoveryRules() {
    }

    /**
     * @param itemId  item of the examined block, null when there is none
     * @param enabled the furnitureDiscovery setting
     * @param result  outcome of the album add, null when nothing was added
     * @param learned whether the recipe was learned by this examination
     */
    static List<Line> messages(String itemId, boolean enabled, DiscoveryStore.AddResult result, boolean learned) {
        FurnitureDiscovery.Family family = FurnitureDiscovery.familyOf(itemId);
        if (family == null) {
            return List.of(new Line("discovery.nothing", null));
        }
        if (!enabled) {
            return List.of(new Line("discovery.disabled", null));
        }
        List<Line> lines = new ArrayList<>();
        boolean added = result != null && result.added();
        if (result != null && result.newFamily()) {
            lines.add(new Line("discovery.newPage", FurnitureDiscovery.familyNameKey(family.id())));
        }
        if (learned) {
            lines.add(new Line("discovery.learned", itemId));
        } else if (added) {
            lines.add(new Line("discovery.added", itemId));
        } else {
            lines.add(new Line("discovery.known", itemId));
        }
        if (result != null && result.titleFamily() != null) {
            lines.add(new Line("discovery.titleEarned", FurnitureDiscovery.familyTitleKey(result.titleFamily())));
        }
        return lines;
    }
}
