package gg.australis.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrometheusExporterTest {

    @Test
    void rendersCountersGaugesAndValues() {
        Stats s = new Stats();
        s.connectionsSeen.set(120);
        s.connectionsBlocked.set(30);
        s.verificationsPassed.set(7);
        s.edgeBlocklistPushes.set(4);

        String out = PrometheusExporter.render(s, true, 5);

        assertTrue(out.contains("australis_under_attack 1"), "under-attack gauge");
        assertTrue(out.contains("australis_verified_ips 5"), "verified-ips gauge");
        assertTrue(out.contains("australis_connections_seen_total 120"));
        assertTrue(out.contains("australis_connections_blocked_total 30"));
        assertTrue(out.contains("australis_verifications_passed_total 7"));
        assertTrue(out.contains("australis_edge_blocklist_pushes_total 4"));
        assertTrue(out.contains("# TYPE australis_connections_seen_total counter"));
        assertTrue(out.contains("# TYPE australis_under_attack gauge"));
        // every metric line must be preceded by HELP/TYPE (valid exposition)
        assertTrue(out.startsWith("# HELP australis_under_attack"));
    }

    @Test
    void underAttackZeroWhenCalm() {
        assertTrue(PrometheusExporter.render(new Stats(), false, 0).contains("australis_under_attack 0"));
    }
}
