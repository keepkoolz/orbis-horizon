package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.protocol.PlayerSkin;

import java.util.List;

/** Gender, first name and gendered look of an airship pilot, all drawn deterministically from the crew seed. Pure. */
final class PilotLook {
    enum Gender { FEMALE, MALE }

    static final List<String> FEMALE_NAMES = List.of("Amélie", "Margot", "Rose", "Ada", "Louise", "Hélène", "Clara", "Iris", "Jeanne", "Ottilie");
    static final List<String> MALE_NAMES = List.of("Gaspard", "Jules", "Émile", "Victor", "Théodore", "Auguste", "Léon", "Armand", "Félix", "Hector");
    static final List<String> FEMALE_FACES = List.of("Face_Eyelashes", "Face_Eyelashes_Top", "Face_MakeUp", "Face_MakeUp_Freckles",
            "Face_Makeup_Blush", "Face_Almond_Eyes");
    static final List<String> MALE_FACES = List.of("Face_Neutral", "Face_Stubble", "Face_Scar", "Face_Aged", "Face_Tired_Eyes", "Face_Sunken");
    static final List<String> FEMALE_HAIR = List.of("BobCut", "PonyTail", "Bun", "Braid", "BraidDouble", "SideBuns", "WavyPonytail", "Curly",
            "Simple", "WidePonytail", "BraidedPonytail", "ThickBraid", "WavyLong", "MessyBobcut", "RoseBun");
    static final List<String> MALE_HAIR = List.of("Morning", "ShortQuiff", "Quiff", "Lazy", "Messy", "Undercut", "Sideslick",
            "MidSinglePart", "Windswept", "Stormy", "VikinManBun");

    private PilotLook() {
    }

    /** SplitMix64 finaliser: spreads the bits of a small seed. */
    private static long mix(long z) {
        z = (z + 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static Gender gender(long seed) {
        return (mix(seed) & 1L) == 0 ? Gender.FEMALE : Gender.MALE;
    }

    private static int pick(long seed, int salt, int size) {
        return (int) Long.remainderUnsigned(mix(seed ^ (0x1234567L * salt)) >>> 8, size);
    }

    /** First name of a crew member for chat lines, or a neutral fallback when the record is gone. */
    static String nameOf(CrewRecord crew) {
        return crew != null ? firstName(crew.seed()) : "Le pilote";
    }

    static String firstName(long seed) {
        List<String> l = gender(seed) == Gender.FEMALE ? FEMALE_NAMES : MALE_NAMES;
        return l.get(pick(seed, 1, l.size()));
    }

    /** Replaces the id of a drawn value, keeping only its colour (second segment): a variant belongs to the old part. */
    static String replaceId(String drawn, String id) {
        if (drawn == null) {
            return id;
        }
        int dot = drawn.indexOf('.');
        if (dot < 0) {
            return id;
        }
        int next = drawn.indexOf('.', dot + 1);
        return id + (next < 0 ? drawn.substring(dot) : drawn.substring(dot, next));
    }

    /** Gives the skin the face, haircut and facial hair of the pilot's gender. */
    static void applyGender(PlayerSkin skin, long seed) {
        boolean female = gender(seed) == Gender.FEMALE;
        List<String> faces = female ? FEMALE_FACES : MALE_FACES;
        List<String> hair = female ? FEMALE_HAIR : MALE_HAIR;
        skin.face = replaceId(skin.face, faces.get(pick(seed, 2, faces.size())));
        skin.haircut = replaceId(skin.haircut, hair.get(pick(seed, 3, hair.size())));
        if (female) {
            skin.facialHair = null;
        }
    }
}
