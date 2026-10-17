package com.keepkoolz.orbishorizon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One airship per player: owners are read from the registry (placed airships) and from flights in the air. */
final class AirshipLimit {
    private AirshipLimit() {
    }

    /** True when the player is among the owners (null owners are ignored). */
    static boolean ownsAny(UUID player, Iterable<UUID> owners) {
        if (player == null) {
            return false;
        }
        for (UUID o : owners) {
            if (player.equals(o)) {
                return true;
            }
        }
        return false;
    }

    /** True when the player already owns an airship, placed in any world or flying. */
    static boolean ownsAirship(UUID player) {
        List<UUID> owners = new ArrayList<>();
        for (BalloonRegistry.Entry e : BalloonRegistry.entriesOfKindAllWorlds(Deployables.AIRSHIP_KIND)) {
            // An entry whose helm is provably gone (airship removed outside the mod) must not lock the player out.
            if (!BalloonRegistry.removeIfGone(e)) {
                owners.add(e.owner());
            }
        }
        for (AirshipFlight f : AirshipManager.get().flights()) {
            owners.add(f.ownership.owner());
        }
        return ownsAny(player, owners);
    }
}
