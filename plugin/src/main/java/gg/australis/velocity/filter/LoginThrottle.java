package gg.australis.velocity.filter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects login abuse and connect/disconnect churn per IP.
 *
 * <p>Bot swarms frequently connect, get part-way through login, then disconnect
 * and immediately retry — thrashing the pipeline without ever playing. This
 * tracks how quickly an IP churns and lets the caller reject sources that
 * exceed a healthy rate.
 */
public final class LoginThrottle {

    private static final class State {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicLong windowStart = new AtomicLong();
        final AtomicInteger churn = new AtomicInteger();
        volatile long lastSeen;
    }

    private final Map<String, State> table = new ConcurrentHashMap<>();

    private volatile int maxAttemptsPerWindow;
    private volatile long windowMillis;
    private volatile int maxChurn;

    public LoginThrottle(int maxAttemptsPerWindow, long windowMillis, int maxChurn) {
        this.maxAttemptsPerWindow = maxAttemptsPerWindow;
        this.windowMillis = windowMillis;
        this.maxChurn = maxChurn;
    }

    public void reconfigure(int maxAttemptsPerWindow, long windowMillis, int maxChurn) {
        this.maxAttemptsPerWindow = maxAttemptsPerWindow;
        this.windowMillis = windowMillis;
        this.maxChurn = maxChurn;
    }

    /** @return true if this login attempt should be rejected. */
    public boolean onLoginAttempt(String ip) {
        long now = System.currentTimeMillis();
        State s = table.computeIfAbsent(ip, k -> {
            State ns = new State();
            ns.windowStart.set(now);
            return ns;
        });
        s.lastSeen = now;

        long start = s.windowStart.get();
        if (now - start >= windowMillis) {
            if (s.windowStart.compareAndSet(start, now)) {
                s.attempts.set(0);
                s.churn.set(0);
            }
        }
        int a = s.attempts.incrementAndGet();
        return a > maxAttemptsPerWindow || s.churn.get() > maxChurn;
    }

    /** Record an early disconnect (part-way through the flow) as churn. */
    public void onEarlyDisconnect(String ip) {
        State s = table.get(ip);
        if (s != null) {
            s.churn.incrementAndGet();
        }
    }

    /** Drop entries not seen within {@code ttlMillis}. */
    public int prune(long ttlMillis) {
        long now = System.currentTimeMillis();
        int[] removed = {0};
        table.entrySet().removeIf(e -> {
            if (now - e.getValue().lastSeen > ttlMillis) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }
}
