package com.keepkoolz.orbishorizon;

import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;

import java.util.UUID;

/** Ownership data of a deployed structure: stable id, owner, owner name and sharing on the world map. */
record Ownership(UUID id, UUID owner, String ownerName, boolean shared) {
    static final Ownership NONE = new Ownership(null, null, null, false);

    /** A new ownership with a random id, not shared. */
    static Ownership of(UUID owner, String ownerName) {
        return new Ownership(UUID.randomUUID(), owner, ownerName, false);
    }

    Ownership withShared(boolean shared) {
        return new Ownership(id, owner, ownerName, shared);
    }

    Ownership withOwner(UUID owner, String ownerName) {
        return new Ownership(id, owner, ownerName, shared);
    }

    /** Itself if it has an id, otherwise a copy with a random id. */
    Ownership withId() {
        return id != null ? this : new Ownership(UUID.randomUUID(), owner, ownerName, shared);
    }

    /**
     * Ownership to register when a vehicle is put back down: the flight's ownership if it has an owner,
     * otherwise the pilot becomes the owner (a balloon placed before this feature has none). Never without an id.
     */
    static Ownership landingOwner(Ownership flight, UUID pilot, String pilotName) {
        return landingOwner(flight, pilot, pilotName, true);
    }

    /** Same, but the pilot does not become the owner when pilotMayOwn is false (airship limit). */
    static Ownership landingOwner(Ownership flight, UUID pilot, String pilotName, boolean pilotMayOwn) {
        if (flight.owner() != null) {
            return flight.withId();
        }
        if (pilot != null && pilotMayOwn) {
            return flight.withOwner(pilot, pilotName).withId();
        }
        return flight.withId();
    }

    void writeTo(BsonDocument d) {
        if (id != null) {
            d.put("structureId", new BsonString(id.toString()));
        }
        if (owner != null) {
            d.put("owner", new BsonString(owner.toString()));
        }
        if (ownerName != null) {
            d.put("ownerName", new BsonString(ownerName));
        }
        if (shared) {
            d.put("shared", new BsonBoolean(true));
        }
    }

    static Ownership readFrom(BsonDocument d) {
        UUID id = d.containsKey("structureId") ? UUID.fromString(d.getString("structureId").getValue()) : null;
        UUID owner = d.containsKey("owner") ? UUID.fromString(d.getString("owner").getValue()) : null;
        String name = d.containsKey("ownerName") ? d.getString("ownerName").getValue() : null;
        boolean shared = d.containsKey("shared") && d.getBoolean("shared").getValue();
        if (id == null && owner == null && name == null && !shared) {
            return NONE;
        }
        return new Ownership(id, owner, name, shared);
    }
}
