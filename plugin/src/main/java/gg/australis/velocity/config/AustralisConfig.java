package gg.australis.velocity.config;

import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YAML-backed configuration for all Australis subsystems. Immutable snapshot:
 * {@link #loadOrCreate} builds a fresh instance and the plugin swaps its
 * reference on reload, calling {@code reconfigure(...)} on each component.
 */
public final class AustralisConfig {

    // Connection limiter (per IP)
    private int maxConnectionsPerWindow = 8;
    private long connectionWindowMillis = 3_000;
    private long connectionBlockMillis = 30_000;

    // Ping limiter (per IP)
    private int maxPingsPerWindow = 20;
    private long pingWindowMillis = 3_000;
    private long pingBlockMillis = 10_000;
    private long pingCacheMillis = 1_000;

    // Login throttle (per IP)
    private int maxLoginsPerWindow = 6;
    private long loginWindowMillis = 5_000;
    private int maxChurn = 4;

    // Attack detector (global)
    private int attackPerSecondThreshold = 60;
    private long attackCooldownMillis = 30_000;

    // Verification (Phase 2)
    private boolean verifyEnabled = true;
    private boolean verifyOnlyDuringAttack = true;
    private long verifyReconnectWindowMillis = 15_000;
    private long verifyVerifiedTtlMillis = 3_600_000;
    private long verifyPendingTtlMillis = 60_000;
    private Set<String> verifyAllowlist = Set.of();
    private String verifyKickMessage = "§bAustralis §7» §fVerifying connection… please reconnect.";

    // Edge feedback (Phase 3)
    private boolean edgeEnabled = false;
    private String edgeUrl = "";
    private String edgeToken = "";
    private long edgeBanSeconds = 600;

    // Housekeeping
    private long pruneIntervalMillis = 60_000;
    private long entryTtlMillis = 300_000;

    // Behaviour
    private boolean logBlocks = true;
    private String kickMessage = "§bAustralis §7» §fYou are connecting too quickly. Try again shortly.";

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
        Map<String, Object> conn = section(root, "connections");
        maxConnectionsPerWindow = asInt(conn.get("max-per-window"), maxConnectionsPerWindow);
        connectionWindowMillis = asLong(conn.get("window-millis"), connectionWindowMillis);
        connectionBlockMillis = asLong(conn.get("block-millis"), connectionBlockMillis);

        Map<String, Object> ping = section(root, "pings");
        maxPingsPerWindow = asInt(ping.get("max-per-window"), maxPingsPerWindow);
        pingWindowMillis = asLong(ping.get("window-millis"), pingWindowMillis);
        pingBlockMillis = asLong(ping.get("block-millis"), pingBlockMillis);
        pingCacheMillis = asLong(ping.get("cache-millis"), pingCacheMillis);

        Map<String, Object> login = section(root, "login");
        maxLoginsPerWindow = asInt(login.get("max-per-window"), maxLoginsPerWindow);
        loginWindowMillis = asLong(login.get("window-millis"), loginWindowMillis);
        maxChurn = asInt(login.get("max-churn"), maxChurn);

        Map<String, Object> atk = section(root, "attack-detector");
        attackPerSecondThreshold = asInt(atk.get("connections-per-second"), attackPerSecondThreshold);
        attackCooldownMillis = asLong(atk.get("cooldown-millis"), attackCooldownMillis);

        Map<String, Object> v = section(root, "verification");
        verifyEnabled = asBool(v.get("enabled"), verifyEnabled);
        verifyOnlyDuringAttack = asBool(v.get("only-during-attack"), verifyOnlyDuringAttack);
        verifyReconnectWindowMillis = asLong(v.get("reconnect-window-millis"), verifyReconnectWindowMillis);
        verifyVerifiedTtlMillis = asLong(v.get("verified-ttl-millis"), verifyVerifiedTtlMillis);
        verifyPendingTtlMillis = asLong(v.get("pending-ttl-millis"), verifyPendingTtlMillis);
        Object al = v.get("allowlist");
        if (al instanceof List<?> list) {
            verifyAllowlist = Set.copyOf(list.stream().map(String::valueOf).toList());
        }
        Object vmsg = v.get("kick-message");
        if (vmsg instanceof String s) {
            verifyKickMessage = s;
        }

        Map<String, Object> edge = section(root, "edge");
        edgeEnabled = asBool(edge.get("enabled"), edgeEnabled);
        edgeUrl = asString(edge.get("url"), edgeUrl);
        edgeToken = asString(edge.get("token"), edgeToken);
        edgeBanSeconds = asLong(edge.get("ban-seconds"), edgeBanSeconds);

        Map<String, Object> hk = section(root, "housekeeping");
        pruneIntervalMillis = asLong(hk.get("prune-interval-millis"), pruneIntervalMillis);
        entryTtlMillis = asLong(hk.get("entry-ttl-millis"), entryTtlMillis);

        logBlocks = asBool(root.get("log-blocks"), logBlocks);
        kickMessage = asString(root.get("kick-message"), kickMessage);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object o = root.get(key);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static int asInt(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static long asLong(Object o, long def) { return o instanceof Number n ? n.longValue() : def; }
    private static boolean asBool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }
    private static String asString(Object o, String def) { return o instanceof String s ? s : def; }

    // Connections
    public int maxConnectionsPerWindow() { return maxConnectionsPerWindow; }
    public long connectionWindowMillis() { return connectionWindowMillis; }
    public long connectionBlockMillis() { return connectionBlockMillis; }
    // Pings
    public int maxPingsPerWindow() { return maxPingsPerWindow; }
    public long pingWindowMillis() { return pingWindowMillis; }
    public long pingBlockMillis() { return pingBlockMillis; }
    public long pingCacheMillis() { return pingCacheMillis; }
    // Login
    public int maxLoginsPerWindow() { return maxLoginsPerWindow; }
    public long loginWindowMillis() { return loginWindowMillis; }
    public int maxChurn() { return maxChurn; }
    // Attack detector
    public int attackPerSecondThreshold() { return attackPerSecondThreshold; }
    public long attackCooldownMillis() { return attackCooldownMillis; }
    // Verification
    public boolean verifyEnabled() { return verifyEnabled; }
    public boolean verifyOnlyDuringAttack() { return verifyOnlyDuringAttack; }
    public long verifyReconnectWindowMillis() { return verifyReconnectWindowMillis; }
    public long verifyVerifiedTtlMillis() { return verifyVerifiedTtlMillis; }
    public long verifyPendingTtlMillis() { return verifyPendingTtlMillis; }
    public Set<String> verifyAllowlist() { return verifyAllowlist; }
    public Component verifyKickMessage() { return Component.text(verifyKickMessage); }
    // Edge
    public boolean edgeEnabled() { return edgeEnabled; }
    public String edgeUrl() { return edgeUrl; }
    public String edgeToken() { return edgeToken; }
    public long edgeBanSeconds() { return edgeBanSeconds; }
    // Housekeeping
    public long pruneIntervalMillis() { return pruneIntervalMillis; }
    public long entryTtlMillis() { return entryTtlMillis; }
    // Behaviour
    public boolean logBlocks() { return logBlocks; }
    public Component kickMessage() { return Component.text(kickMessage); }
}
