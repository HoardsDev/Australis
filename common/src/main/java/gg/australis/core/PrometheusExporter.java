package gg.australis.core;

/**
 * Renders {@link Stats} as Prometheus text-exposition format (v0.0.4). Pure and
 * dependency-free so it is trivially unit-testable and shared by every platform.
 */
public final class PrometheusExporter {

    private PrometheusExporter() { }

    public static String render(Stats s, boolean underAttack, int verifiedIps) {
        StringBuilder b = new StringBuilder(1024);
        gauge(b, "australis_under_attack", "1 if the attack detector is currently tripped", underAttack ? 1 : 0);
        gauge(b, "australis_verified_ips", "IPs currently trusted (verified, within TTL)", verifiedIps);
        counter(b, "australis_connections_seen_total", "connection/login attempts seen", s.connectionsSeen.get());
        counter(b, "australis_connections_blocked_total", "connections blocked by the per-IP limiter", s.connectionsBlocked.get());
        counter(b, "australis_pings_seen_total", "status pings seen", s.pingsSeen.get());
        counter(b, "australis_pings_blocked_total", "status pings blocked by the per-IP limiter", s.pingsBlocked.get());
        counter(b, "australis_logins_throttled_total", "logins throttled (rate/churn)", s.loginsThrottled.get());
        counter(b, "australis_handshakes_rejected_total", "handshakes rejected", s.handshakesRejected.get());
        counter(b, "australis_verification_challenges_total", "reconnect challenges issued", s.verificationChallenges.get());
        counter(b, "australis_verifications_passed_total", "IPs newly verified", s.verificationsPassed.get());
        counter(b, "australis_verifications_failed_total", "verification failures", s.verificationsFailed.get());
        counter(b, "australis_edge_blocklist_pushes_total", "IPs pushed to the edge kernel blocklist", s.edgeBlocklistPushes.get());
        counter(b, "australis_attacks_detected_total", "times the attack detector tripped", s.attacksDetected.get());
        return b.toString();
    }

    private static void counter(StringBuilder b, String name, String help, long v) {
        b.append("# HELP ").append(name).append(' ').append(help).append('\n');
        b.append("# TYPE ").append(name).append(" counter\n");
        b.append(name).append(' ').append(v).append('\n');
    }

    private static void gauge(StringBuilder b, String name, String help, long v) {
        b.append("# HELP ").append(name).append(' ').append(help).append('\n');
        b.append("# TYPE ").append(name).append(" gauge\n");
        b.append(name).append(' ').append(v).append('\n');
    }
}
