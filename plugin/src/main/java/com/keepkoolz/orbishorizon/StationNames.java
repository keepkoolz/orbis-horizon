package com.keepkoolz.orbishorizon;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/**
 * Invented proper names for generated stations. The names are identical in every language, so the
 * syllables live here and not in the language files.
 */
final class StationNames {
    private static final String MARITIME_PREFIX = "Port ";
    private static final int TRIES = 20;
    private static final int HYPHEN_ONE_IN = 6;
    private static final int MAX_ROMAN = 20;
    private static final Pattern ROMAN_SUFFIX = Pattern.compile(" [IVXL]+$");

    /** Start, middle and end syllables of one climate. */
    private record Climate(String[] starts, String[] mids, String[] ends) { }

    private static final Climate TEMPERATE = new Climate(
        words("val mel lor elen nor alm cal mir sèl ber lin aur oli fen mar pel tam ren isa lum dan sal dèv bel tir hèl ond ful gal jor lèn mon ned pin quel rovi sep tul vil wen yan zef"),
        words("la ne mi lè li ma re no lu mè ra le ro vè ta di so ba fi to gu ve"),
        words("lin mère nor lia mont vale ris dène mia ron lan nèl via mar lis dor tis mel rèn tan fèl bris gal nia vèn sor mir den lèt sil gor wen tur"));
    private static final Climate ARID = new Climate(
        words("saz ha zah asr sah hez zar amz sha azh taz huz sar haz naz ysa zeh ash sem bez dah emr far jaf kez mur nur rab sim tar uzh wad yaz zim lah dar"),
        words("sa ha za ah sha zi saa ta ra zu hu si ba ka ru di mu ne fa ja ze do ki ya"),
        words("sah zar hara az ash zah ra hem saz tah ras zan shah dis sur ham zir ara bar dar fal jad kem mur nab rim tam yas zul lam sid hir"));
    private static final Climate COLD = new Climate(
        words("kar vor rek brin tor skal dro gri kre hjo vrak thur bra kjo rav zhe dun fro gor ulv sku vel tyr krim orm ang bjo eir fjo gun hrol isk jar lod mjo nid ost pjo rag sig til urd ves yng"),
        words("ra ko vi rek du ga ri vo ka ru ske ve dor ki ta gra bo fe gu ha je le mu ne sa to ya"),
        words("k rik vik gar rak dun vard kor rund heim ska ven tuk rod nak gurd var kal ruk vorn dal bjorn feld gund hald jarn lund mund nes sten tind vang vald fjord"));
    private static final Climate VOLCANIC = new Climate(
        words("gor rox xar gro ogr rog vog kro xo gur orx rax ghor mog dro zog ox grom tox ur krag bor hox bax dor fum jax kor lax mur nok pyr sax tur vul wyr yor zax cin dak hur"),
        words("go ro xo gro or ox ru ga ko rog xa do mo bo ba di fu ju ka lu ne pu sa ta vu ya"),
        words("gor rox rax gar rog xor ogh rok gron ox grom orn dro goth mox tor kro bax dur fum jor kax lur nok pyr sax tur vul zur dak"));
    private static final Climate COASTAL = new Climate(
        words("mer cal lar ôl mar sol mel lom thal mol ar ola lur cor pol sel mir nam ele ôm tal pel ber del fal gal hal jan kel lis nor pan rya sam tel vel wal ysa eda ula"),
        words("ar la lô mer ma lo ôl mar lu mo li lar ô be ga ha je ke ne pa ra sa ta"),
        words("mar lô mer lia ôn lar ola mère ôt ar lume lom lis mon ôme lor mol bel fal gan hal kel ral sal tal vel wen"));

    private StationNames() { }

    private static String[] words(String spaced) { return spaced.split(" "); }

    /** Name for a station at (x, z), unique among {@code taken} (full names) and not too close to any of them. Always passes checkName. */
    static String generate(long seed, int x, int z, String kind, String zone, Collection<String> taken) {
        String prefix = Deployables.BOAT_STATION_KIND.equals(kind) ? MARITIME_PREFIX : "";
        List<String> cores = candidates(seed, x, z, zone);
        for (String core : cores) {
            if (TransitNetwork.checkName(prefix + core, taken) == TransitNetwork.NameCheck.OK && !closeToAny(core, taken)) {
                return prefix + core;
            }
        }
        List<String> bases = new ArrayList<>(cores);
        int hyphen = cores.get(0).indexOf('-');
        if (hyphen > 0) bases.add(cores.get(0).substring(0, hyphen));
        for (String base : bases) {
            for (int n = 2; n <= MAX_ROMAN; n++) {
                String full = prefix + base + " " + roman(n);
                if (TransitNetwork.checkName(full, taken) == TransitNetwork.NameCheck.OK) return full;
            }
        }
        return TransitNetwork.autoName(taken);
    }

    /** The cores tried, in order, for a station at (x, z). */
    static List<String> candidates(long seed, int x, int z, String zone) {
        SplittableRandom rng = new SplittableRandom(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
        Climate climate = climateOf(zone);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < TRIES; i++) out.add(invent(rng, climate));
        return out;
    }

    /** Number of syllables in the shortest list of any climate. */
    static int minSyllables() {
        int min = Integer.MAX_VALUE;
        for (Climate c : new Climate[] {TEMPERATE, ARID, COLD, VOLCANIC, COASTAL}) {
            min = Math.min(min, Math.min(c.starts.length, Math.min(c.mids.length, c.ends.length)));
        }
        return min;
    }

    /** The name without the "Port " prefix and without a final roman numeral. */
    static String core(String name) {
        String n = TransitNetwork.normalize(name);
        if (n.regionMatches(true, 0, MARITIME_PREFIX, 0, MARITIME_PREFIX.length())) n = n.substring(MARITIME_PREFIX.length()).trim();
        return ROMAN_SUFFIX.matcher(n).replaceFirst("");
    }

    /** Equal cores (ignoring case), Levenshtein distance of 2 or less, or the same first four letters. */
    static boolean tooClose(String a, String b) {
        String x = fold(a);
        String y = fold(b);
        if (x.equals(y)) return true;
        if (x.length() >= 4 && y.length() >= 4 && x.regionMatches(0, y, 0, 4)) return true;
        return levenshtein(x, y) <= 2;
    }

    private static boolean closeToAny(String core, Collection<String> taken) {
        for (String t : taken) if (tooClose(core, core(t))) return true;
        return false;
    }

    private static Climate climateOf(String zone) {
        if (zone == null || zone.isEmpty() || zone.startsWith("Oceans")) return COASTAL;
        if (zone.startsWith("Zone1")) return TEMPERATE;
        if (zone.startsWith("Zone2")) return ARID;
        if (zone.startsWith("Zone3")) return COLD;
        if (zone.startsWith("Zone4")) return VOLCANIC;
        return COASTAL;
    }

    private static String invent(SplittableRandom rng, Climate c) {
        if (rng.nextInt(HYPHEN_ONE_IN) == 0) {
            return capitalize(shortName(rng, c)) + "-" + capitalize(shortName(rng, c));
        }
        StringBuilder sb = new StringBuilder();
        append(sb, pick(rng, c.starts));
        if (rng.nextInt(3) != 0) append(sb, pick(rng, c.mids));
        append(sb, pick(rng, c.ends));
        return capitalize(sb.toString());
    }

    private static String shortName(SplittableRandom rng, Climate c) {
        StringBuilder sb = new StringBuilder();
        append(sb, pick(rng, c.starts));
        append(sb, pick(rng, c.ends));
        return sb.toString();
    }

    private static String pick(SplittableRandom rng, String[] list) { return list[rng.nextInt(list.length)]; }

    /** Appends a syllable, dropping a letter that would make three identical ones in a row. */
    private static void append(StringBuilder sb, String syllable) {
        for (int i = 0; i < syllable.length(); i++) {
            char ch = syllable.charAt(i);
            int len = sb.length();
            if (len >= 2 && sb.charAt(len - 1) == ch && sb.charAt(len - 2) == ch) continue;
            sb.append(ch);
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String fold(String s) {
        String d = Normalizer.normalize(core(s), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(Locale.ROOT);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static String roman(int n) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) { n -= values[i]; sb.append(symbols[i]); }
        }
        return sb.toString();
    }
}
