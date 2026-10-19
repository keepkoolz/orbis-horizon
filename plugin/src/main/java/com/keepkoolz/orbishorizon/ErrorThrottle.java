package com.keepkoolz.orbishorizon;

import java.util.LinkedHashMap;
import java.util.Map;

/** Limits error reports: one per fingerprint per window, bounded memory. */
final class ErrorThrottle {
    static final long WINDOW_MS = 3_600_000L;
    static final int MAX_ENTRIES = 256;
    private static final String MOD_PREFIX = "com.keepkoolz.orbishorizon.";

    /** Exception carrying only the original class name, never its message or causes. */
    public static final class RedactedException extends RuntimeException {
        RedactedException(String className) {
            super(className, null, false, true);
        }
    }

    private static final class Seen {
        long lastSent;
        long suppressed;
    }

    private final long windowMs;
    private final LinkedHashMap<String, Seen> entries;

    ErrorThrottle(long windowMs, int maxEntries) {
        this.windowMs = windowMs;
        this.entries = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Seen> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** Returns -1 if the send is suppressed, else the repeats suppressed since the last send. */
    synchronized long admit(String fingerprint, long nowMs) {
        Seen e = entries.get(fingerprint);
        if (e == null) {
            e = new Seen();
            e.lastSent = nowMs;
            entries.put(fingerprint, e);
            return 0;
        }
        if (nowMs - e.lastSent < windowMs) {
            e.suppressed++;
            return -1;
        }
        long repeats = e.suppressed;
        e.suppressed = 0;
        e.lastSent = nowMs;
        return repeats;
    }

    static String fingerprint(String zone, Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        String frame = "";
        for (StackTraceElement f : st) {
            if (f.getClassName().startsWith(MOD_PREFIX)) {
                frame = f.toString();
                break;
            }
        }
        if (frame.isEmpty() && st.length > 0) frame = st[0].toString();
        return zone + "|" + t.getClass().getName() + "|" + frame;
    }

    static RuntimeException redact(Throwable t) {
        RedactedException r = new RedactedException(t.getClass().getName());
        r.setStackTrace(t.getStackTrace());
        return r;
    }
}
