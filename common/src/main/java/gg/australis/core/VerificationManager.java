package gg.australis.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2 — bot verification via a reconnect challenge + verified-IP allowlist.
 *
 * <h2>Why this design</h2>
 * True packet-level verification (holding a client in a limbo world and checking
 * movement/gravity physics, the way <em>Sonar</em> does) requires injecting into
 * the Netty pipeline below Velocity's public API, and is version-specific. That
 * is the eventual "deep" path (see {@code docs/ROADMAP.md} and the note at the
 * bottom of this file). This class implements the strongest verification that
 * the <em>public, stable</em> Velocity API supports, which already stops the
 * overwhelming majority of join-flood bots:
 *
 * <ol>
 *   <li><b>Verified allowlist</b> — any IP that has recently completed a full,
 *       successful login+connect is trusted for a TTL. Returning real players
 *       are never challenged.</li>
 *   <li><b>Reconnect challenge</b> — during an attack, an unknown IP is denied
 *       once with a "please reconnect" message. Real players reconnect; dumb
 *       flood bots fire a single connection and never retry, so they never get
 *       in. Clients that reconnect within the human window are let through and
 *       promoted to verified on successful login.</li>
 *   <li><b>Attack-gated</b> — challenges only apply while {@link
 *       gg.australis.core.AttackDetector} reports an attack, so
 *       normal operation is completely transparent.</li>
 * </ol>
 *
 * All state is time-bounded and pruned, so a spoofed-IP flood can't grow it
 * without bound (and spoofed L4 is the edge/XDP layer's job regardless).
 */
public final class VerificationManager {

    public enum Result {
        /** Let the connection proceed. */
        ALLOW,
        /** Deny now and ask the client to reconnect (challenge issued). */
        DENY_RECONNECT
    }

    /** IPs trusted for a while after a successful session. */
    private final Map<String, Long> verified = new ConcurrentHashMap<>();
    /** IPs that were issued a challenge, mapped to when it was issued. */
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    /** Operator-configured permanent allowlist. */
    private volatile Set<String> allowlist = Set.of();

    private volatile boolean enabled;
    private volatile boolean onlyDuringAttack;
    private volatile long reconnectWindowMillis;
    private volatile long verifiedTtlMillis;
    private volatile long pendingTtlMillis;

    public VerificationManager(boolean enabled, boolean onlyDuringAttack,
                               long reconnectWindowMillis, long verifiedTtlMillis,
                               long pendingTtlMillis, Set<String> allowlist) {
        reconfigure(enabled, onlyDuringAttack, reconnectWindowMillis,
                verifiedTtlMillis, pendingTtlMillis, allowlist);
    }

    public void reconfigure(boolean enabled, boolean onlyDuringAttack,
                            long reconnectWindowMillis, long verifiedTtlMillis,
                            long pendingTtlMillis, Set<String> allowlist) {
        this.enabled = enabled;
        this.onlyDuringAttack = onlyDuringAttack;
        this.reconnectWindowMillis = reconnectWindowMillis;
        this.verifiedTtlMillis = verifiedTtlMillis;
        this.pendingTtlMillis = pendingTtlMillis;
        this.allowlist = Set.copyOf(allowlist);
    }

    /**
     * Decide whether an incoming connection should be allowed or challenged.
     *
     * @param ip           the remote IP
     * @param underAttack  current attack state from {@link gg.australis.core.AttackDetector}
     */
    public Result check(String ip, boolean underAttack) {
        if (!enabled) {
            return Result.ALLOW;
        }
        if (allowlist.contains(ip) || isVerified(ip)) {
            return Result.ALLOW;
        }
        if (onlyDuringAttack && !underAttack) {
            // Normal operation: don't challenge, but let PostLogin verify them
            // so they're trusted if an attack starts mid-session.
            return Result.ALLOW;
        }

        long now = System.currentTimeMillis();
        Long issued = pending.get(ip);
        if (issued != null && (now - issued) <= reconnectWindowMillis) {
            // They came back within the human window -> passed the challenge.
            pending.remove(ip);
            // Provisionally trust until PostLogin confirms with a real TTL.
            verified.put(ip, now + Math.min(verifiedTtlMillis, 60_000L));
            return Result.ALLOW;
        }
        // No pending challenge, or it expired -> (re)issue one.
        pending.put(ip, now);
        return Result.DENY_RECONNECT;
    }

    /** Call on a fully successful login+connect: trust this IP for the full TTL. */
    public void markVerified(String ip) {
        verified.put(ip, System.currentTimeMillis() + verifiedTtlMillis);
        pending.remove(ip);
    }

    public boolean isVerified(String ip) {
        Long exp = verified.get(ip);
        if (exp == null) {
            return false;
        }
        if (exp < System.currentTimeMillis()) {
            verified.remove(ip);
            return false;
        }
        return true;
    }

    /** Manual admin override. */
    public void forceVerify(String ip, long ttlMillis) {
        verified.put(ip, System.currentTimeMillis() + ttlMillis);
    }

    public void unverify(String ip) {
        verified.remove(ip);
        pending.remove(ip);
    }

    public int verifiedCount() {
        return verified.size();
    }

    /** Time-bounded cleanup. */
    public void prune() {
        long now = System.currentTimeMillis();
        verified.entrySet().removeIf(e -> e.getValue() < now);
        pending.entrySet().removeIf(e -> now - e.getValue() > pendingTtlMillis);
    }

    /*
     * DEEP VERIFICATION (future Netty path, Sonar-style)
     * --------------------------------------------------
     * For attacks by bots that DO reconnect, the next tier holds the client in a
     * fake "limbo" login/play state entirely on the proxy and checks that it
     * behaves like a real client: responds to KeepAlive, obeys gravity, sends
     * plausible position/rotation packets, answers a transaction/pong. Bots that
     * don't implement the full client fail instantly and never reach a backend.
     *
     * Implementing that requires registering a channel initializer on Velocity's
     * inbound pipeline and speaking raw Minecraft protocol per version. The
     * open-source `Sonar` plugin already does this well; recommended integration
     * options: (a) ship Australis alongside Sonar and let this class own the
     * allowlist/edge-feedback while Sonar owns limbo, or (b) route unverified
     * players to a lightweight limbo backend (e.g. NanoLimbo) via
     * PlayerChooseInitialServerEvent and promote them once they pass. Tracked in
     * ROADMAP.md Phase 2 (deep).
     */
}
