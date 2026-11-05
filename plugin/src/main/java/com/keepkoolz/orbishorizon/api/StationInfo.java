package com.keepkoolz.orbishorizon.api;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3i;

/** A station of the transit network as the extension sees it: a copy, never the live record. kind is one of the {@link StationKinds} ids. */
public record StationInfo(String name, String world, Vector3i origin, Rotation rotation, String kind) {
}
