package com.keepkoolz.orbishorizon;

/**
 * Clearances a hull needs in a water search: leg is the half width kept clear along a leg, turn the radius kept clear around a
 * waypoint where the hull turns on the spot.
 */
record HullClearance(int leg, int turn) {
    /** The steam ship (the clearances the water routes were tuned for). */
    static final HullClearance SHIP = new HullClearance(WaterRoute.CLEARANCE, WaterRoute.TURN_CLEARANCE);
    /** The maritime shuttle: x -2..2, z -5..6, a corner radius of about 7. */
    static final HullClearance SHUTTLE = new HullClearance(4, 9);
}
