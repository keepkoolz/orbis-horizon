package com.keepkoolz.orbishorizon;

import java.text.Normalizer;
import java.util.Locale;

/** Accent and case insensitive text filter used by the configuration page search box. */
final class ConfigSearch {
    private ConfigSearch() {
    }

    /** Lower-cased, accent-free, trimmed form of {@code s} (null reads as empty). */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").trim();
    }

    /** True when the normalised query is empty or contained in the normalised label. */
    static boolean matches(String label, String query) {
        String q = normalize(query);
        return q.isEmpty() || normalize(label).contains(q);
    }
}
