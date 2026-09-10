package gg.australis.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import gg.australis.velocity.config.AustralisConfig;
import gg.australis.velocity.filter.ConnectionRateLimiter;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Australis — free, self-hosted L7 protection for Minecraft (Velocity).
 *
 * <p>This is the Phase 1 scaffold. It wires up the plugin lifecycle and a
 * working per-IP connection/ping rate limiter. Phases 2 (bot verification)
 * and 3 (XDP edge feedback) plug into the same structure — see docs/ROADMAP.md.
 *
 * <p>The rate limiter here is deliberately simple and dependency-free so it can
 * be read, understood, and extended. The heavy lifting for volumetric/L4 lives
 * at the edge (XDP); this layer owns application-level abuse.
 */
@Plugin(
        id = "australis",
        name = "Australis",
        version = "0.1.0",
        description = "Free self-hosted L7 DDoS/bot protection for Minecraft.",
        authors = {"Australis"}
)
public final class AustralisPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private AustralisConfig config;
    private ConnectionRateLimiter rateLimiter;

    @Inject
    public AustralisPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.config = AustralisConfig.loadOrCreate(dataDir, logger);
        this.rateLimiter = new ConnectionRateLimiter(config, logger);
        logger.info("Australis enabled — connection limit {} / {}ms per IP, ping limit {} / {}ms.",
                config.maxConnectionsPerWindow(),
                config.connectionWindowMillis(),
                config.maxPingsPerWindow(),
                config.pingWindowMillis());
        logger.info("Australis: L7 protection active. See docs/DEPLOYMENT.md to add the XDP edge (L3/L4 + IP hiding).");
    }

    /**
     * Fires before login/auth work happens. Rejecting here is cheap and stops
     * bot-join and connection floods from reaching backend servers.
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        if (remote == null) {
            return;
        }
        String ip = remote.getAddress().getHostAddress();

        ConnectionRateLimiter.Decision decision = rateLimiter.checkConnection(ip);
        if (decision.blocked()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    config.kickMessage()));
            if (config.logBlocks()) {
                logger.warn("Australis blocked connection from {} ({})", ip, decision.reason());
            }
            // Phase 3 hook: rateLimiter.reportToEdge(ip, decision.reason());
        }
    }

    /**
     * Status/MOTD pings are a common flood vector. Rate-limit them per IP so a
     * ping flood can't spin up work on the proxy.
     */
    @Subscribe
    public void onPing(ProxyPingEvent event) {
        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        if (remote == null) {
            return;
        }
        String ip = remote.getAddress().getHostAddress();
        if (rateLimiter.checkPing(ip).blocked()) {
            // Drop the ping response entirely for over-limit sources.
            event.setPing(null);
        }
    }
}
