package com.keepkoolz.orbishorizon.api;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A checkbox row an extension adds to the configuration page: its section and label translation keys, a reader and a writer. */
public record ConfigRow(String key, String sectionKey, String labelKey, BooleanSupplier read, Consumer<Boolean> write) {
}
