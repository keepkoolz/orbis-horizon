package com.keepkoolz.orbishorizon.api;

import org.joml.Vector3i;

/**
 * A room test refusal: the chat key, the offending block and cell when the message names them, and a second key sent after the
 * first (boat berth), and for a berth refusal whether the faulty cell is a hull cell (null for the other refusals). A null key is a
 * refusal with no message to the player.
 */
public record Refusal(String key, String block, Vector3i at, String detailKey, Boolean hull) {
    public Refusal(String key, String block, Vector3i at, String detailKey) {
        this(key, block, at, detailKey, null);
    }

    public static Refusal of(String key) {
        return new Refusal(key, null, null, null);
    }

    public static Refusal at(String key, String block, int x, int y, int z) {
        return new Refusal(key, block, new Vector3i(x, y, z), null);
    }
}
