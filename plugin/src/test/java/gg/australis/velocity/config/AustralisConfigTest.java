package gg.australis.velocity.config;

import gg.australis.core.ConfigSanitizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link AustralisConfig#apply} + {@link AustralisConfig#validate} via
 * the {@code fromMap} test seam — no filesystem, YAML, or logger required, so it
 * is fully deterministic.
 */
class AustralisConfigTest {

    private static final class Recorder implements ConfigSanitizer.Warner {
        final List<String> messages = new ArrayList<>();
        @Override public void warn(String message) { messages.add(message); }
    }

    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Map<String, Object> s = new HashMap<>();
        root.put(key, s);
        return s;
    }

    @Test
    void defaultsAreValidAndSilent() {
        Recorder r = new Recorder();
        AustralisConfig cfg = AustralisConfig.fromMap(null, r);
        assertEquals(8, cfg.maxConnectionsPerWindow());
        assertEquals(3_000L, cfg.connectionWindowMillis());
        assertEquals(60, cfg.attackPerSecondThreshold());
        assertEquals(60_000L, cfg.pruneIntervalMillis());
        assertTrue(r.messages.isEmpty(), "default config must not produce clamp warnings");
    }

    @Test
    void validOverridesArePreserved() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> conn = section(root, "connections");
        conn.put("max-per-window", 20);
        conn.put("window-millis", 5_000);
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(20, cfg.maxConnectionsPerWindow());
        assertEquals(5_000L, cfg.connectionWindowMillis());
        assertTrue(r.messages.isEmpty());
    }

    @Test
    void zeroConnectionLimitClampedToOne() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        section(root, "connections").put("max-per-window", 0);
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(1, cfg.maxConnectionsPerWindow(),
                "a zero limit would block every player; must clamp to 1");
        assertFalse(r.messages.isEmpty());
    }

    @Test
    void negativeWindowClampedToOne() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        section(root, "connections").put("window-millis", -100);
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(1L, cfg.connectionWindowMillis());
    }

    @Test
    void zeroAttackThresholdClampedToOne() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        section(root, "attack-detector").put("connections-per-second", 0);
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(1, cfg.attackPerSecondThreshold(),
                "a zero threshold would pin permanent-attack mode; must clamp to >= 1");
    }

    @Test
    void nonPositivePruneIntervalClampedToFloor() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        section(root, "housekeeping").put("prune-interval-millis", 0);
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(1_000L, cfg.pruneIntervalMillis(),
                "0 would crash Velocity's Duration.repeat; must clamp to a positive floor");
        assertFalse(r.messages.isEmpty());
    }

    @Test
    void absurdValueClampedToMax() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        section(root, "connections").put("max-per-window", 5_000_000);
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(1_000_000, cfg.maxConnectionsPerWindow());
        assertFalse(r.messages.isEmpty());
    }

    @Test
    void malformedSectionTypeFallsBackToDefault() {
        Recorder r = new Recorder();
        Map<String, Object> root = new HashMap<>();
        root.put("connections", "not-a-map"); // wrong type -> section() yields empty
        AustralisConfig cfg = AustralisConfig.fromMap(root, r);
        assertEquals(8, cfg.maxConnectionsPerWindow(), "wrong-typed section falls back to defaults");
        assertTrue(r.messages.isEmpty());
    }
}
