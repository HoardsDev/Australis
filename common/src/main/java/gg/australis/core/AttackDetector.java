package gg.australis.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global attack detector. Tracks the *aggregate* rate of new connections across
 * all IPs and flips the plugin into "attack mode" when that rate spikes past a
 * threshold.
 *
 * <p>Why this matters: the aggressive defences (reconnect challenges, strict
 * verification) should only kick in during an actual attack, so legitimate
 * players are never inconvenienced in normal operation. This is the switch that
 * makes that possible.
 *
 * <p>Uses a simple fixed-window counter that rolls every second; attack state is
 * "sticky" for a cooldown so a bursty attack doesn't flap in and out.
 */
public final class AttackDetector {

    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong windowCount = new AtomicLong();
    private final AtomicLong attackUntil = new AtomicLong();

    private volatile int perSecondThreshold;
    private volatile long cooldownMillis;

    public AttackDetector(int perSecondThreshold, long cooldownMillis) {
        this.perSecondThreshold = perSecondThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    public void reconfigure(int perSecondThreshold, long cooldownMillis) {
        this.perSecondThreshold = perSecondThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Record one connection attempt. Returns true if this pushed us into (or
     * kept us in) attack mode.
     */
    public boolean record() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= 1000L) {
            if (windowStart.compareAndSet(start, now)) {
                windowCount.set(0);
            }
        }
        long c = windowCount.incrementAndGet();
        if (c >= perSecondThreshold) {
            attackUntil.set(now + cooldownMillis);
            return true;
        }
        return isUnderAttack(now);
    }

    public boolean isUnderAttack() {
        return isUnderAttack(System.currentTimeMillis());
    }

    private boolean isUnderAttack(long now) {
        return attackUntil.get() > now;
    }
}
