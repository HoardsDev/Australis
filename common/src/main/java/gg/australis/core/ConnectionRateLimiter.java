package gg.australis.core;

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
 *   <li>Pure JDK (no plugin/config/log deps) so it is trivially unit-testable
 *       and reusable. Pruning is driven externally via {@link #prune(long)} on
 *       the plugin's scheduler.</li>
 * </ul>
 *
 * <p>The kernel/XDP layer handles true packet floods; this layer handles
 * application-level connection abuse that has already passed L4.
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

    private final Map<String, Window> connections = new ConcurrentHashMap<>();
    private final Map<String, Window> pings = new ConcurrentHashMap<>();

    private volatile int maxConnPerWindow;
    private volatile long connWindowMillis;
    private volatile long connBlockMillis;
    private volatile int maxPingPerWindow;
    private volatile long pingWindowMillis;
    private volatile long pingBlockMillis;

    public ConnectionRateLimiter(int maxConnPerWindow, long connWindowMillis, long connBlockMillis,
                                 int maxPingPerWindow, long pingWindowMillis, long pingBlockMillis) {
        reconfigure(maxConnPerWindow, connWindowMillis, connBlockMillis,
                maxPingPerWindow, pingWindowMillis, pingBlockMillis);
    }

    public void reconfigure(int maxConnPerWindow, long connWindowMillis, long connBlockMillis,
                            int maxPingPerWindow, long pingWindowMillis, long pingBlockMillis) {
        this.maxConnPerWindow = maxConnPerWindow;
        this.connWindowMillis = connWindowMillis;
        this.connBlockMillis = connBlockMillis;
        this.maxPingPerWindow = maxPingPerWindow;
        this.pingWindowMillis = pingWindowMillis;
        this.pingBlockMillis = pingBlockMillis;
    }

    public Decision checkConnection(String ip) {
        return check(connections, ip, maxConnPerWindow, connWindowMillis, connBlockMillis, "connection-flood");
    }

    public Decision checkPing(String ip) {
        return check(pings, ip, maxPingPerWindow, pingWindowMillis, pingBlockMillis, "ping-flood");
    }

    /**
     * Peek whether this IP is currently connection-banned, WITHOUT counting a
     * hit or rolling its window. Used at {@code PreLoginEvent} to enforce a ban
     * that was already decided at the (earlier, non-deniable) handshake stage,
     * so the connection is counted exactly once.
     */
    public boolean isConnectionBlocked(String ip) {
        Window w = connections.get(ip);
        return w != null && w.blockedUntil.get() > System.currentTimeMillis();
    }

    private Decision check(Map<String, Window> table, String ip,
                           int maxPerWindow, long windowMillis, long blockMillis, String reason) {
        long now = System.currentTimeMillis();

        Window w = table.computeIfAbsent(ip, k -> {
            Window nw = new Window();
            nw.windowStart.set(now);
            return nw;
        });
        w.lastSeen = now;

        // Still inside a temp-ban?
        if (w.blockedUntil.get() > now) {
            return Decision.block(reason + "/banned");
        }

        // Roll the window if it has expired.
        long start = w.windowStart.get();
        if (now - start >= windowMillis) {
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

    /** Drop entries not seen within {@code ttlMillis} whose ban has expired. */
    public int prune(long ttlMillis) {
        long now = System.currentTimeMillis();
        return prune(connections, now, ttlMillis) + prune(pings, now, ttlMillis);
    }

    private int prune(Map<String, Window> table, long now, long ttl) {
        int[] removed = {0};
        table.entrySet().removeIf(e -> {
            Window w = e.getValue();
            if (now - w.lastSeen > ttl && w.blockedUntil.get() <= now) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    // Exposed for metrics/tests.
    public int trackedConnections() { return connections.size(); }
    public int trackedPings() { return pings.size(); }
}
