package com.keepkoolz.orbishorizon.api;

/** A deployed structure of the registry. box is {minX, minY, minZ, maxX, maxY, maxZ} in the world, null when the shape is unknown. */
public record PlacedStructure(String kind, int x, int y, int z, int[] box) {
}
