package com.keepkoolz.orbishorizon;

import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * The NPC pilot of an airship: the NPC entity uuid, the seed of its skin and where it stands.
 * Stored with the registry entry, the flight and the airship resume file under the key "crew".
 */
record CrewRecord(UUID uuid, long seed, Post post) {

    /** Where the pilot stands: at the helm or on the cabin stool. */
    enum Post {
        HELM("helm"), STOOL("stool");

        final String key;

        Post(String key) {
            this.key = key;
        }

        static Post fromKey(String key) {
            for (Post p : values()) {
                if (p.key.equals(key)) {
                    return p;
                }
            }
            return null;
        }
    }

    BsonDocument toBson() {
        BsonDocument d = new BsonDocument();
        d.put("uuid", new BsonString(uuid.toString()));
        d.put("seed", new BsonInt64(seed));
        d.put("post", new BsonString(post.key));
        return d;
    }

    /** Reads a record, null if the uuid is absent or the post unknown. */
    static CrewRecord fromBson(BsonDocument d) {
        if (d == null || !d.containsKey("uuid") || !d.containsKey("post")) {
            return null;
        }
        try {
            Post post = Post.fromKey(d.getString("post").getValue());
            if (post == null) {
                return null;
            }
            long seed = d.containsKey("seed") ? d.getNumber("seed").longValue() : 0L;
            return new CrewRecord(UUID.fromString(d.getString("uuid").getValue()), seed, post);
        } catch (RuntimeException e) {
            return null;
        }
    }

    CrewRecord withUuid(UUID newUuid) {
        return new CrewRecord(newUuid, seed, post);
    }

    CrewRecord withPost(Post newPost) {
        return new CrewRecord(uuid, seed, newPost);
    }
}
