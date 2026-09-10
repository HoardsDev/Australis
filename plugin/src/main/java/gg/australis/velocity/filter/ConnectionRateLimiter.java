package gg.australis.velocity.filter;

import gg.australis.velocity.config.AustralisConfig;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP sliding-window rate limiter for connections and status pings.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Lock-free hot path: one {@link ConcurrentHashMap} lookup + a couple of
 *       atomic ops per event, so it stays cheap under a flood.</li>
 *   <li>Two independent windows: new connections and status pings, because a
 *       ping flood and a join flood have very different healthy rates.</li>
 *   <li>Temp-ban: once an IP trips the limit it stays blocked for a cool-down,
 *       so repeat offenders don't get a fresh allowance every window.</li>
 *   <li>Self-pruning: stale entries are dropped opportunistically to bound
 *       memory (an attacker using millions of spoofed IPs can't grow this
 *       unbounded — and spoofed L4 is the XDP layer's job anyway).</li>
 * </ul>
 *
 * <p>This is intentionally a plain in-JVM limiter. The kernel/XDP layer handles
 * true packet floods; this layer handles application-level connection abuse that
 * has already passed L4.
 */
public final class ConnectionRateLimiter {

    /** A per-IP counter with a window start and an optional block-until stamp. */
    private static final class Window {
        final AtomicInteger count = new AtomicInteger();
        final AtomicLong windowStart = new AtomicLong();
        final AtomicLong blockedUntil = new AtomicLong();
        volatile long lastSeen;
    }

    public record Decision(boolean blocked, String reason) {
        static final Decision ALLOW = new Decision(false, "allow");
        static Decision block(String reason) { return new Decision(true, reason); }
    }

    private final AustralisConfig config;
    private final Logger logger;

    private final Map<String, Window> connections = new ConcurrentHashMap<>();
    private final Map<String, Window> pings = new ConcurrentHashMap<>();

    private final AtomicLong lastPrune = new AtomicLong(System.currentTimeMillis());

    public ConnectionRateLimiter(AustralisConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public Decision checkConnection(String ip) {
        return check(connections, ip,
                config.maxConnectionsPerWindow(),
                config.connectionWindowMillis(),
                config.connectionBlockMillis(),
                "connection-flood");
    }

    public Decision checkPing(String ip) {
        return check(pings, ip,
                config.maxPingsPerWindow(),
                config.pingWindowMillis(),
                config.pingBlockMillis(),
                "ping-flood");
    }

    private Decision check(Map<String, Window> table, String ip,
                           int maxPerWindow, long windowMillis, long blockMillis,
                           String reason) {
        long now = System.currentTimeMillis();
        maybePrune(now);

        Window w = table.computeIfAbsent(ip, k -> {
            Window nw = new Window();
            nw.windowStart.set(now);
            return nw;
        });
        w.lastSeen = now;

        // Still inside a temp-ban?
        long until = w.blockedUntil.get();
        if (until > now) {
            return Decision.block(reason + "/banned");
        }

        // Roll the window if it has expired.
        long start = w.windowStart.get();
        if (now - start >= windowMillis) {
            // Reset window; small race here is harmless (worst case one extra allow).
            if (w.windowStart.compareAndSet(start, now)) {
                w.count.set(0);
            }
        }

        int c = w.count.incrementAndGet();
        if (c > maxPerWindow) {
            w.blockedUntil.set(now + blockMillis);
            return Decision.block(reason);
        }
        return Decision.ALLOW;
    }

    /** Opportunistic cleanup so the maps don't grow without bound. */
    private void maybePrune(long now) {
        long last = lastPrune.get();
        if (now - last < config.pruneIntervalMillis()) {
            return;
        }
        if (!lastPrune.compareAndSet(last, now)) {
            return; // another thread is pruning
        }
        long ttl = config.entryTtlMillis();
        int removed = 0;
        removed += prune(connections, now, ttl);
        removed += prune(pings, now, ttl);
        if (removed > 0 && config.logBlocks()) {
            logger.debug("Australis pruned {} stale rate-limit entries", removed);
        }
    }

    private int prune(Map<String, Window> table, long now, long ttl) {
        int[] removed = {0};
        table.entrySet().removeIf(e -> {
            Window w = e.getValue();
            boolean expiredBan = w.blockedUntil.get() <= now;
            boolean stale = now - w.lastSeen > ttl;
            if (stale && expiredBan) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }
}
