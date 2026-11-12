package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the album page shows for one player's album. Pure: a family the player has not started never appears in any result, so
 * nothing about it can reach the client.
 */
final class JournalView {
    /** One line of the index: a started family, how many items of it are found, and whether its title is earned. */
    record IndexRow(String familyId, int found, int total, boolean titled) {
    }

    /** One slot of a family page: the item and its entry when found, both null for an empty slot. */
    record Slot(String itemId, DiscoveryStore.Entry entry) {
    }

    private JournalView() {
    }

    /** The started families in order of first discovery. Empty for a blank album. */
    static List<IndexRow> index(DiscoveryStore.Album album) {
        List<IndexRow> out = new ArrayList<>();
        Set<String> titles = album.titles();
        for (String id : album.familyOrder()) {
            FurnitureDiscovery.Family family = FurnitureDiscovery.family(id);
            if (family != null) {
                out.add(new IndexRow(id, album.found(id), family.items().size(), titles.contains(id)));
            }
        }
        return out;
    }

    /** The started family before this one in index order, null for the first one or an unknown family. */
    static String previousFamily(DiscoveryStore.Album album, String familyId) {
        List<IndexRow> rows = index(album);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).familyId().equals(familyId)) {
                return i == 0 ? null : rows.get(i - 1).familyId();
            }
        }
        return null;
    }

    /** The started family after this one in index order, null for the last one or an unknown family. */
    static String nextFamily(DiscoveryStore.Album album, String familyId) {
        List<IndexRow> rows = index(album);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).familyId().equals(familyId)) {
                return i == rows.size() - 1 ? null : rows.get(i + 1).familyId();
            }
        }
        return null;
    }

    /** The slots of a started family in table order. Empty when the family is unknown or not started. */
    static List<Slot> slots(DiscoveryStore.Album album, String familyId) {
        FurnitureDiscovery.Family family = FurnitureDiscovery.family(familyId);
        if (family == null || album.found(familyId) == 0) {
            return List.of();
        }
        List<Slot> out = new ArrayList<>();
        for (FurnitureDiscovery.Item item : family.items()) {
            DiscoveryStore.Entry entry = album.entry(item.itemId());
            out.add(entry == null ? new Slot(null, null) : new Slot(item.itemId(), entry));
        }
        return out;
    }

    /** Titles earned for families that are started, in order of first discovery. */
    static List<String> earnedTitles(DiscoveryStore.Album album) {
        List<String> out = new ArrayList<>();
        for (IndexRow row : index(album)) {
            if (row.titled()) {
                out.add(row.familyId());
            }
        }
        return out;
    }

    /** Every text key the page may send for this album: started families (name, lore, title) and the short name and lore of found items. */
    static Set<String> textKeys(DiscoveryStore.Album album) {
        Set<String> out = new LinkedHashSet<>();
        for (IndexRow row : index(album)) {
            out.add(FurnitureDiscovery.familyNameKey(row.familyId()));
            out.add(FurnitureDiscovery.familyLoreKey(row.familyId()));
            out.add(FurnitureDiscovery.familyTitleKey(row.familyId()));
            for (Slot s : slots(album, row.familyId())) {
                if (s.itemId() != null) {
                    out.add(FurnitureDiscovery.itemLoreKey(s.itemId()));
                    out.add(FurnitureDiscovery.itemNameKey(s.itemId()));
                }
            }
        }
        return out;
    }
}
