package gg.australis.core;

/**
 * Bounds-checking + clamping for configuration values, shared by the Velocity and
 * Paper config loaders (one source of truth, no drift).
 *
 * <p>Bad config values (negative windows, zero limits, absurd magnitudes) are a
 * real production hazard: a zero/negative {@code window-millis} makes a rate
 * limiter roll its window on every call (so it never limits, or blocks everyone),
 * a zero {@code attack-detector.connections-per-second} pins the plugin into
 * permanent "attack mode", and a zero {@code prune-interval-millis} crashes the
 * Velocity scheduler ({@code Duration.repeat} rejects non-positive periods). This
 * clamps each value into a safe range and reports what it changed, so a fat-
 * fingered config degrades to safe defaults instead of a crash or silent misfire.
 *
 * <p>Deliberately free of any logging framework so it stays trivially unit-
 * testable and reusable: callers supply a {@link Warner} (typically their SLF4J
 * logger) to receive human-readable messages.
 */
public final class ConfigSanitizer {

    /** Sink for human-readable clamp warnings. */
    @FunctionalInterface
    public interface Warner {
        void warn(String message);
    }

    /** A {@link Warner} that discards messages (useful in tests). */
    public static final Warner NOOP = m -> { };

    private ConfigSanitizer() { }

    /**
     * Clamp {@code value} into {@code [min, max]}. If it was out of range, emit a
     * warning and return the nearest bound; otherwise return it unchanged.
     */
    public static int clampInt(Warner warn, String key, int value, int min, int max) {
        if (value < min) {
            warn(warn, key, value, min);
            return min;
        }
        if (value > max) {
            warn(warn, key, value, max);
            return max;
        }
        return value;
    }

    /** Long variant of {@link #clampInt}. */
    public static long clampLong(Warner warn, String key, long value, long min, long max) {
        if (value < min) {
            warn(warn, key, value, min);
            return min;
        }
        if (value > max) {
            warn(warn, key, value, max);
            return max;
        }
        return value;
    }

    private static void warn(Warner warn, String key, long bad, long used) {
        if (warn != null) {
            warn.warn("Australis config: '" + key + "' = " + bad
                    + " is out of range; clamped to " + used + ".");
        }
    }
}
