package gg.australis.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSanitizerTest {

    /** Collect warnings so we can assert clamping was reported. */
    private static final class Recorder implements ConfigSanitizer.Warner {
        final List<String> messages = new ArrayList<>();
        @Override public void warn(String message) { messages.add(message); }
    }

    @Test
    void inRangeValuesPassThroughSilently() {
        Recorder r = new Recorder();
        assertEquals(8, ConfigSanitizer.clampInt(r, "k", 8, 1, 100));
        assertEquals(3_000L, ConfigSanitizer.clampLong(r, "k", 3_000L, 1, 1_000_000));
        assertEquals(1, ConfigSanitizer.clampInt(r, "k", 1, 1, 100), "min boundary is in range");
        assertEquals(100, ConfigSanitizer.clampInt(r, "k", 100, 1, 100), "max boundary is in range");
        assertTrue(r.messages.isEmpty(), "no warnings for valid values");
    }

    @Test
    void belowMinClampsToMinAndWarns() {
        Recorder r = new Recorder();
        assertEquals(1, ConfigSanitizer.clampInt(r, "connections.max-per-window", 0, 1, 100),
                "zero limit clamps to 1 so we never block every player");
        assertEquals(1, r.messages.size());
        assertTrue(r.messages.get(0).contains("connections.max-per-window"));
    }

    @Test
    void negativeClampsToMinAndWarns() {
        Recorder r = new Recorder();
        assertEquals(1L, ConfigSanitizer.clampLong(r, "connections.window-millis", -5, 1, 3_600_000));
        assertEquals(0L, ConfigSanitizer.clampLong(r, "attack-detector.cooldown-millis", -1, 0, 86_400_000));
        assertEquals(2, r.messages.size(), "each out-of-range value warns once");
    }

    @Test
    void aboveMaxClampsToMaxAndWarns() {
        Recorder r = new Recorder();
        assertEquals(100, ConfigSanitizer.clampInt(r, "k", 999_999, 1, 100));
        assertEquals(10L, ConfigSanitizer.clampLong(r, "k", Long.MAX_VALUE, 0, 10));
        assertEquals(2, r.messages.size());
    }

    @Test
    void nullWarnerIsTolerated() {
        assertEquals(1, ConfigSanitizer.clampInt(null, "k", 0, 1, 100));
        assertEquals(5L, ConfigSanitizer.clampLong(null, "k", 5, 0, 10));
    }

    @Test
    void noopWarnerDiscards() {
        assertEquals(1, ConfigSanitizer.clampInt(ConfigSanitizer.NOOP, "k", -3, 1, 100));
    }
}
