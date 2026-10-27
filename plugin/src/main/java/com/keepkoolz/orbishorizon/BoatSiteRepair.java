package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Pure search for a pier site near a planned one, used when the real blocks refuse the planned site. */
final class BoatSiteRepair {
    static final int REPAIR_TRIES = 24;
    static final int LATERAL_STEP = 8;
    static final int LATERAL_MAX = 48;
    static final int DEPTH_STEP = 4;
    static final int DEPTH_LATERAL_MAX = 16;

    /** The outcome of a search: the site that fits (null when none does) and the number of sites checked against the blocks. */
    record Result(WorldStationPlanner.Site site, int tries) {
    }

    private BoatSiteRepair() {
    }

    /**
     * The sites to try around a planned site, in order, all with the planned rotation. First the lateral shifts
     * (perpendicular to the pier direction) +8, -8, +16, -16 up to 48 in both directions, then the depth shifts
     * along the pier axis. The depth shifts apply to the lateral offsets 0, +8, -8, +16, -16, in that order, and for
     * each one the seaward shift (along the pier direction) comes before the landward shift.
     * At most {@link #REPAIR_TRIES} sites.
     */
    static List<WorldStationPlanner.Site> candidates(WorldStationPlanner.Site planned) {
        Rotation rotation = planned.rotation();
        Vector3i pier = rotation.rotateYaw(new Vector3i(0, 0, 1), new Vector3i());
        int seaX = Integer.signum(pier.x);
        int seaZ = Integer.signum(pier.z);
        // lateral axis: pier direction turned a quarter turn, so Rotation.None gives +x
        int latX = seaZ;
        int latZ = -seaX;
        List<WorldStationPlanner.Site> out = new ArrayList<>();
        for (int d = LATERAL_STEP; d <= LATERAL_MAX; d += LATERAL_STEP) {
            out.add(shifted(planned, latX, latZ, d, seaX, seaZ, 0));
            out.add(shifted(planned, latX, latZ, -d, seaX, seaZ, 0));
        }
        for (int lateral = 0; lateral <= DEPTH_LATERAL_MAX; lateral += LATERAL_STEP) {
            int[] laterals = lateral == 0 ? new int[] {0} : new int[] {lateral, -lateral};
            for (int l : laterals) {
                out.add(shifted(planned, latX, latZ, l, seaX, seaZ, DEPTH_STEP));
                out.add(shifted(planned, latX, latZ, l, seaX, seaZ, -DEPTH_STEP));
            }
        }
        return out.size() > REPAIR_TRIES ? new ArrayList<>(out.subList(0, REPAIR_TRIES)) : out;
    }

    private static WorldStationPlanner.Site shifted(WorldStationPlanner.Site s, int latX, int latZ, int lateral,
                                                    int seaX, int seaZ, int depth) {
        return new WorldStationPlanner.Site(s.x() + latX * lateral + seaX * depth,
                s.z() + latZ * lateral + seaZ * depth, s.rotation());
    }

    /**
     * Tries the candidates in order and returns the first one that fits.
     * @param ready whether the blocks around a site are loaded; sites that are not ready are skipped without counting a try
     * @param fits whether the pier can be built on a site; only called on ready sites
     */
    static Result search(WorldStationPlanner.Site planned, Predicate<WorldStationPlanner.Site> ready,
                         Predicate<WorldStationPlanner.Site> fits) {
        int tries = 0;
        for (WorldStationPlanner.Site site : candidates(planned)) {
            if (!ready.test(site)) {
                continue;
            }
            tries++;
            if (fits.test(site)) {
                return new Result(site, tries);
            }
        }
        return new Result(null, tries);
    }
}
