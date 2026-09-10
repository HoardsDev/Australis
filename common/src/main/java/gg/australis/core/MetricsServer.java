package gg.australis.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Tiny optional Prometheus endpoint (JDK {@link HttpServer}, no deps) shared by
 * every platform plugin. Serves {@code /metrics} (Prometheus text) and
 * {@code /health}. Bind it to loopback or a private link — the counters are not
 * sensitive but there is no auth. Off by default.
 */
public final class MetricsServer {

    private final Stats stats;
    private final BooleanSupplier underAttack;
    private final IntSupplier verifiedIps;
    private HttpServer server;

    public MetricsServer(Stats stats, BooleanSupplier underAttack, IntSupplier verifiedIps) {
        this.stats = stats;
        this.underAttack = underAttack;
        this.verifiedIps = verifiedIps;
    }

    /** Start (or restart) the server on {@code bind} (host:port). */
    public synchronized void start(String bind) throws IOException {
        stop();
        int colon = bind.lastIndexOf(':');
        String host = colon > 0 ? bind.substring(0, colon) : "127.0.0.1";
        int port = Integer.parseInt(bind.substring(colon + 1).trim());
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        s.createContext("/metrics", ex -> {
            byte[] body = PrometheusExporter.render(
                    stats, underAttack.getAsBoolean(), verifiedIps.getAsInt())
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        s.createContext("/health", ex -> {
            byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        s.setExecutor(null); // small default executor is fine for scrapes
        s.start();
        this.server = s;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** The actually-bound port (useful when starting on port 0), or -1 if stopped. */
    public synchronized int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }
}
