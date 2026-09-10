package gg.australis.velocity.edge;

import gg.australis.velocity.metrics.Stats;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Phase 3 (plugin side) — the feedback loop.
 *
 * <p>When the plugin decides an IP is malicious (repeated floods, failed
 * verification churn), it tells the <b>edge</b> to drop that IP at the kernel
 * (nftables set now, XDP blocklist map later). From then on the attacker is
 * dropped in a few CPU cycles at the NIC — expensive L7 detection becomes cheap
 * L3/L4 enforcement.
 *
 * <p>Fire-and-forget over HTTP with a bearer token. De-duplicated so we don't
 * spam the edge with the same IP. If the edge is unreachable, we degrade
 * gracefully — the in-JVM limiter still protects the proxy.
 */
public final class EdgeClient {

    private final Logger logger;
    private final Stats stats;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "australis-edge");
                t.setDaemon(true);
                return t;
            });

    /** ip -> lastPushedMillis, to de-dup. */
    private final ConcurrentHashMap<String, Long> recentlyPushed = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile String baseUrl;
    private volatile String token;
    private volatile long banSeconds;
    private volatile long dedupMillis = 30_000;

    public EdgeClient(Logger logger, Stats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    public void reconfigure(boolean enabled, String baseUrl, String token, long banSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.token = token;
        this.banSeconds = banSeconds;
    }

    /** Ask the edge to drop this IP at the kernel. Non-blocking, de-duplicated. */
    public void blocklist(String ip, String reason) {
        if (!enabled || baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = recentlyPushed.put(ip, now);
        if (last != null && now - last < dedupMillis) {
            return;
        }
        exec.execute(() -> send(ip, reason));
    }

    private void send(String ip, String reason) {
        try {
            String body = String.format(
                    "{\"ip\":\"%s\",\"ban_seconds\":%d,\"reason\":\"%s\"}",
                    ip, banSeconds, reason.replace("\"", ""));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/block"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (token == null ? "" : token))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                stats.edgeBlocklistPushes.incrementAndGet();
            } else {
                logger.warn("Australis edge rejected block for {} ({}): {}", ip, reason, resp.statusCode());
            }
        } catch (Exception e) {
            logger.debug("Australis edge unreachable for {}: {}", ip, e.getMessage());
        }
    }

    public void housekeeping() {
        long now = System.currentTimeMillis();
        recentlyPushed.entrySet().removeIf(e -> now - e.getValue() > dedupMillis * 4);
    }

    public void shutdown() {
        exec.shutdown();
        try {
            exec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
