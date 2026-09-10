package gg.australis.core;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
    /**
     * Cap on queued edge pushes. A spoofed/unique-IP flood produces one send task
     * per fresh IP; without a bound this single-threaded queue of blocking HTTP
     * calls would grow without limit and exhaust the heap. When the queue is full
     * we drop new pushes (the in-JVM limiter still protects the proxy, and the
     * edge de-dups), never blocking or throwing on the calling event thread.
     */
    private static final int MAX_QUEUED_PUSHES = 10_000;

    private final ThreadPoolExecutor exec = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED_PUSHES),
            r -> {
                Thread t = new Thread(r, "australis-edge");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

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
        if (enabled && baseUrl != null && baseUrl.startsWith("http://") && !isPrivateHost(baseUrl)) {
            logger.warn("Australis edge URL is plaintext HTTP to a non-private host ({}). "
                    + "The token and blocked IPs travel in cleartext — use HTTPS or reach the "
                    + "edge over a private link (WireGuard/Tailscale).", baseUrl);
        }
    }

    /** True if the URL's host is loopback or an RFC1918/CGNAT/ULA private address. */
    private static boolean isPrivateHost(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) {
                return false;
            }
            if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1")
                    || h.startsWith("10.") || h.startsWith("192.168.")
                    || h.startsWith("fd") || h.startsWith("fc")) {
                return true;
            }
            String[] p = h.split("\\.");
            if (p.length > 1) {
                try {
                    int o = Integer.parseInt(p[1]);
                    if (h.startsWith("172.") && o >= 16 && o <= 31) return true;   // RFC1918
                    if (h.startsWith("100.") && o >= 64 && o <= 127) return true;  // CGNAT (Tailscale)
                } catch (NumberFormatException ignored) {
                    // hostname, not a dotted IP — treat as non-private
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
                    jsonEscape(ip), banSeconds, jsonEscape(reason));
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

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
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
