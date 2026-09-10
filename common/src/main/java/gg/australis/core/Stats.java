package gg.australis.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory counters for the {@code /australis stats} command and
 * (later) Prometheus export. All fields are atomic so they can be touched from
 * Netty/event threads without locking.
 */
public final class Stats {

    public final AtomicLong connectionsSeen = new AtomicLong();
    public final AtomicLong connectionsBlocked = new AtomicLong();
    public final AtomicLong pingsSeen = new AtomicLong();
    public final AtomicLong pingsBlocked = new AtomicLong();
    public final AtomicLong loginsThrottled = new AtomicLong();
    public final AtomicLong handshakesRejected = new AtomicLong();

    public final AtomicLong verificationChallenges = new AtomicLong();
    public final AtomicLong verificationsPassed = new AtomicLong();
    public final AtomicLong verificationsFailed = new AtomicLong();

    public final AtomicLong edgeBlocklistPushes = new AtomicLong();
    public final AtomicLong attacksDetected = new AtomicLong();

    /** Snapshot for display. */
    public String summary(boolean underAttack, int verifiedIps) {
        return String.format(
                "under-attack=%s | conns seen=%d blocked=%d | pings seen=%d blocked=%d | "
                        + "logins throttled=%d | handshakes rejected=%d | "
                        + "verify challenged=%d passed=%d failed=%d | verified-ips=%d | "
                        + "edge-pushes=%d | attacks=%d",
                underAttack,
                connectionsSeen.get(), connectionsBlocked.get(),
                pingsSeen.get(), pingsBlocked.get(),
                loginsThrottled.get(),
                handshakesRejected.get(),
                verificationChallenges.get(), verificationsPassed.get(), verificationsFailed.get(),
                verifiedIps,
                edgeBlocklistPushes.get(),
                attacksDetected.get());
    }
}
