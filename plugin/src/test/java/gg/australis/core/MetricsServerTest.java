package gg.australis.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MetricsServerTest {

    @Test
    void servesMetricsAndHealthOverHttp() throws Exception {
        Stats s = new Stats();
        s.connectionsBlocked.set(3);
        s.verificationsPassed.set(9);
        MetricsServer m = new MetricsServer(s, () -> true, () -> 2);
        try {
            m.start("127.0.0.1:0"); // ephemeral port
            int port = m.boundPort();
            assertTrue(port > 0, "server should report a bound port");

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();

            HttpResponse<String> metrics = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("australis_connections_blocked_total 3"));
            assertTrue(metrics.body().contains("australis_verifications_passed_total 9"));
            assertTrue(metrics.body().contains("australis_under_attack 1"));
            assertTrue(metrics.body().contains("australis_verified_ips 2"));

            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("ok", health.body().trim());
        } finally {
            m.stop();
            assertEquals(-1, m.boundPort());
        }
    }
}
