package gg.australis.velocity.config;

import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Minimal YAML-backed config with sane defaults. Kept dependency-light on
 * purpose; swap for a richer config lib later if desired.
 */
public final class AustralisConfig {

    // Connection limiter
    private int maxConnectionsPerWindow = 8;
    private long connectionWindowMillis = 3_000;
    private long connectionBlockMillis = 30_000;

    // Ping limiter
    private int maxPingsPerWindow = 20;
    private long pingWindowMillis = 3_000;
    private long pingBlockMillis = 10_000;

    // Housekeeping
    private long pruneIntervalMillis = 60_000;
    private long entryTtlMillis = 300_000;

    // Behaviour
    private boolean logBlocks = true;
    private String kickMessage = "Australis: you are connecting too quickly. Try again shortly.";

    public static AustralisConfig loadOrCreate(Path dataDir, Logger logger) {
        AustralisConfig cfg = new AustralisConfig();
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("config.yml");
            if (Files.notExists(file)) {
                try (InputStream in = AustralisConfig.class.getResourceAsStream("/config.yml");
                     OutputStream out = Files.newOutputStream(file)) {
                    if (in != null) {
                        in.transferTo(out);
                    }
                }
                logger.info("Australis wrote default config to {}", file);
            }
            try (InputStream in = Files.newInputStream(file)) {
                Map<String, Object> root = new Yaml().load(in);
                if (root != null) {
                    cfg.apply(root);
                }
            }
        } catch (IOException e) {
            logger.warn("Australis could not load config, using defaults: {}", e.getMessage());
        }
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> root) {
        Map<String, Object> conn = (Map<String, Object>) root.getOrDefault("connections", Map.of());
        maxConnectionsPerWindow = asInt(conn.get("max-per-window"), maxConnectionsPerWindow);
        connectionWindowMillis = asLong(conn.get("window-millis"), connectionWindowMillis);
        connectionBlockMillis = asLong(conn.get("block-millis"), connectionBlockMillis);

        Map<String, Object> ping = (Map<String, Object>) root.getOrDefault("pings", Map.of());
        maxPingsPerWindow = asInt(ping.get("max-per-window"), maxPingsPerWindow);
        pingWindowMillis = asLong(ping.get("window-millis"), pingWindowMillis);
        pingBlockMillis = asLong(ping.get("block-millis"), pingBlockMillis);

        Map<String, Object> hk = (Map<String, Object>) root.getOrDefault("housekeeping", Map.of());
        pruneIntervalMillis = asLong(hk.get("prune-interval-millis"), pruneIntervalMillis);
        entryTtlMillis = asLong(hk.get("entry-ttl-millis"), entryTtlMillis);

        logBlocks = asBool(root.get("log-blocks"), logBlocks);
        Object msg = root.get("kick-message");
        if (msg instanceof String s) {
            kickMessage = s;
        }
    }

    private static int asInt(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static long asLong(Object o, long def) { return o instanceof Number n ? n.longValue() : def; }
    private static boolean asBool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }

    public int maxConnectionsPerWindow() { return maxConnectionsPerWindow; }
    public long connectionWindowMillis() { return connectionWindowMillis; }
    public long connectionBlockMillis() { return connectionBlockMillis; }
    public int maxPingsPerWindow() { return maxPingsPerWindow; }
    public long pingWindowMillis() { return pingWindowMillis; }
    public long pingBlockMillis() { return pingBlockMillis; }
    public long pruneIntervalMillis() { return pruneIntervalMillis; }
    public long entryTtlMillis() { return entryTtlMillis; }
    public boolean logBlocks() { return logBlocks; }
    public Component kickMessage() { return Component.text(kickMessage); }
}
