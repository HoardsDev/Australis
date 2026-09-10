package gg.australis.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import gg.australis.core.AttackDetector;
import gg.australis.core.ConnectionRateLimiter;
import gg.australis.core.EdgeClient;
import gg.australis.core.LimboRouter;
import gg.australis.core.LoginThrottle;
import gg.australis.core.Stats;
import gg.australis.core.VerificationManager;
import gg.australis.velocity.command.AustralisCommand;
import gg.australis.velocity.config.AustralisConfig;
import gg.australis.velocity.filter.PingCache;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Australis — free, self-hosted L7 protection for Minecraft (Velocity proxy).
 *
 * <p>Wires the whole Layer-2 pipeline: a global attack detector, per-IP
 * connection/ping/login rate limiting, bot verification (reconnect challenge),
 * a status-ping cache, and the feedback loop that pushes confirmed abusers to
 * the edge for kernel-level dropping. See {@code docs/ARCHITECTURE.md}.
 */
@Plugin(
        id = "australis",
        name = "Australis",
        version = "0.2.0",
        description = "Free self-hosted L7 DDoS/bot protection for Minecraft.",
        authors = {"Negativevibez"}
)
public final class AustralisPlugin {

    private static final String VERSION = "0.2.0";
    private static final MinecraftChannelIdentifier VERIFY_CHANNEL =
            MinecraftChannelIdentifier.from("australis:verify");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private volatile AustralisConfig config;
    private final Stats stats = new Stats();

    private AttackDetector attackDetector;
    private ConnectionRateLimiter rateLimiter;
    private LoginThrottle loginThrottle;
    private VerificationManager verification;
    private LimboRouter limboRouter;
    private PingCache pingCache;
    private EdgeClient edgeClient;

    private ScheduledTask pruneTask;
    private final AtomicBoolean attackActive = new AtomicBoolean(false);

    @Inject
    public AustralisPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.config = AustralisConfig.loadOrCreate(dataDir, logger);

        this.attackDetector = new AttackDetector(
                config.attackPerSecondThreshold(), config.attackCooldownMillis());
        this.rateLimiter = new ConnectionRateLimiter(
                config.maxConnectionsPerWindow(), config.connectionWindowMillis(), config.connectionBlockMillis(),
                config.maxPingsPerWindow(), config.pingWindowMillis(), config.pingBlockMillis());
        this.loginThrottle = new LoginThrottle(
                config.maxLoginsPerWindow(), config.loginWindowMillis(), config.maxChurn());
        this.verification = new VerificationManager(
                config.verifyEnabled(), config.verifyOnlyDuringAttack(),
                config.verifyReconnectWindowMillis(), config.verifyVerifiedTtlMillis(),
                config.verifyPendingTtlMillis(), config.verifyAllowlist());
        this.limboRouter = new LimboRouter(
                config.limboEnabled(), config.limboOnlyDuringAttack(), config.limboServer());
        this.pingCache = new PingCache(config.pingCacheMillis());
        this.edgeClient = new EdgeClient(logger, stats);
        this.edgeClient.reconfigure(config.edgeEnabled(), config.edgeUrl(),
                config.edgeToken(), config.edgeBanSeconds());

        CommandManager cm = proxy.getCommandManager();
        CommandMeta meta = cm.metaBuilder("australis").plugin(this).build();
        cm.register(meta, new AustralisCommand(this));

        // Channel the limbo backend uses to signal that a player passed verification.
        proxy.getChannelRegistrar().register(VERIFY_CHANNEL);

        pruneTask = proxy.getScheduler().buildTask(this, this::prune)
                .repeat(Duration.ofMillis(config.pruneIntervalMillis()))
                .schedule();

        logger.info("Australis {} enabled — L7 protection active.", VERSION);
        logger.info("Verification: {} (only-during-attack={}). Edge feedback: {}.",
                config.verifyEnabled() ? "on" : "off",
                config.verifyOnlyDuringAttack(),
                config.edgeEnabled() ? "on -> " + config.edgeUrl() : "off");
        logger.info("See docs/DEPLOYMENT.md to add the XDP edge (L3/L4 + IP hiding).");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (pruneTask != null) {
            pruneTask.cancel();
        }
        if (edgeClient != null) {
            edgeClient.shutdown();
        }
    }

    /** Reload config and push new settings into every component. */
    public void reload() {
        AustralisConfig fresh = AustralisConfig.loadOrCreate(dataDir, logger);
        this.config = fresh;
        attackDetector.reconfigure(fresh.attackPerSecondThreshold(), fresh.attackCooldownMillis());
        rateLimiter.reconfigure(
                fresh.maxConnectionsPerWindow(), fresh.connectionWindowMillis(), fresh.connectionBlockMillis(),
                fresh.maxPingsPerWindow(), fresh.pingWindowMillis(), fresh.pingBlockMillis());
        loginThrottle.reconfigure(fresh.maxLoginsPerWindow(), fresh.loginWindowMillis(), fresh.maxChurn());
        verification.reconfigure(fresh.verifyEnabled(), fresh.verifyOnlyDuringAttack(),
                fresh.verifyReconnectWindowMillis(), fresh.verifyVerifiedTtlMillis(),
                fresh.verifyPendingTtlMillis(), fresh.verifyAllowlist());
        limboRouter.reconfigure(fresh.limboEnabled(), fresh.limboOnlyDuringAttack(), fresh.limboServer());
        pingCache.reconfigure(fresh.pingCacheMillis());
        edgeClient.reconfigure(fresh.edgeEnabled(), fresh.edgeUrl(), fresh.edgeToken(), fresh.edgeBanSeconds());
        logger.info("Australis configuration reloaded.");
    }

    private void prune() {
        long ttl = config.entryTtlMillis();
        rateLimiter.prune(ttl);
        loginThrottle.prune(ttl);
        verification.prune();
        edgeClient.housekeeping();
        // Catch the attack->normal transition once the cooldown expires and no
        // new handshakes are arriving to drive onHandshake.
        setAttackState(attackDetector.isUnderAttack());
    }

    /**
     * Log the attack-mode edge transitions once, not on every connection. Called
     * concurrently from every event thread and the prune task, so the transition
     * is claimed with a CAS to guarantee exactly-one log per edge.
     */
    private void setAttackState(boolean nowUnderAttack) {
        if (!attackActive.compareAndSet(!nowUnderAttack, nowUnderAttack)) {
            return;
        }
        if (nowUnderAttack) {
            logger.warn("Australis: ATTACK DETECTED — aggressive defences armed (verification {}).",
                    config.verifyEnabled() ? "on" : "off");
        } else {
            logger.info("Australis: attack subsided — back to normal operation.");
        }
    }

    // ---- Event pipeline (order matters: cheapest/earliest checks first) ----

    /**
     * Earliest hook Velocity exposes: fires on the client handshake, BEFORE the
     * proxy's own {@code login-ratelimit} and before {@link PreLoginEvent}. This
     * is the only place we see the <em>true</em> connection rate — everything
     * downstream sees only what Velocity's throttle lets through. So attack
     * detection and the per-IP connection limiter live here.
     *
     * <p>The event is not cancellable (Velocity gives no deniable pre-login
     * hook), so enforcement happens where it can: the login kick in
     * {@link #onPreLogin} for anything that survives the throttle, and — when an
     * edge is configured — a kernel-level drop at the NIC, which is the only way
     * to actually shed a flood at the proxy layer.
     */
    @Subscribe
    public void onHandshake(ConnectionHandshakeEvent event) {
        if (event.getIntent() == HandshakeIntent.STATUS) {
            return; // status/ping volume is handled on the ping path
        }
        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return;
        }
        String ip = remote.getAddress().getHostAddress();
        stats.connectionsSeen.incrementAndGet();

        boolean underAttack = attackDetector.record();
        if (underAttack) {
            stats.attacksDetected.incrementAndGet();
        }
        setAttackState(underAttack);

        ConnectionRateLimiter.Decision conn = rateLimiter.checkConnection(ip);
        if (conn.blocked()) {
            stats.connectionsBlocked.incrementAndGet();
            edgeClient.blocklist(ip, conn.reason());
            // Log only the moment an IP trips, not every subsequent banned
            // packet, so a flood does not drown the log.
            if (config.logBlocks() && !conn.reason().endsWith("/banned")) {
                logger.warn("Australis flood-limited {} ({}) — pushed to edge; kicked at login while banned",
                        ip, conn.reason());
            }
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return;
        }
        String ip = remote.getAddress().getHostAddress();
        boolean underAttack = attackDetector.isUnderAttack();

        // 1) Per-IP connection flood — counted at handshake; enforce the kick
        //    here (the earliest deniable hook). Peek only: no double-count.
        if (rateLimiter.isConnectionBlocked(ip)) {
            deny(event, config.kickMessage());
            return;
        }

        // 2) Login/auth abuse + churn.
        if (loginThrottle.onLoginAttempt(ip)) {
            deny(event, config.kickMessage());
            stats.loginsThrottled.incrementAndGet();
            edgeClient.blocklist(ip, "login-abuse");
            if (config.logBlocks()) {
                logger.warn("Australis throttled login from {}", ip);
            }
            return;
        }

        // 3) Bot verification (reconnect challenge, attack-gated).
        VerificationManager.Result vr = verification.check(ip, underAttack);
        if (vr == VerificationManager.Result.DENY_RECONNECT) {
            deny(event, config.verifyKickMessage());
            stats.verificationChallenges.incrementAndGet();
            if (config.logBlocks()) {
                logger.info("Australis issued reconnect challenge to {}", ip);
            }
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        InetSocketAddress remote = event.getPlayer().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            verification.markVerified(remote.getAddress().getHostAddress());
            stats.verificationsPassed.incrementAndGet();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        InetSocketAddress remote = event.getPlayer().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            loginThrottle.onEarlyDisconnect(remote.getAddress().getHostAddress());
        }
    }

    /**
     * Deep verification: route not-yet-verified players to the limbo backend
     * first (when configured/under attack). The limbo backend does the
     * behavioural checks and signals a pass over the {@code australis:verify}
     * channel (see {@link #onPluginMessage}).
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!limboRouter.enabled()) {
            return;
        }
        InetSocketAddress remote = event.getPlayer().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return;
        }
        String ip = remote.getAddress().getHostAddress();
        Optional<String> limbo = limboRouter.initialServer(
                verification.isVerified(ip), attackDetector.isUnderAttack());
        limbo.flatMap(proxy::getServer).ifPresent(event::setInitialServer);
    }

    /**
     * The limbo backend sends a message on {@code australis:verify} once a player
     * has passed its behavioural checks. We trust it only from a backend
     * connection (never a client), mark the IP verified, and move the player to a
     * real server.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!VERIFY_CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // Consume: never forward a verify message on to the client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection sc)) {
            return; // ignore anything not from a backend server
        }
        Player player = sc.getPlayer();
        InetSocketAddress remote = player.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return;
        }
        verification.markVerified(remote.getAddress().getHostAddress());
        stats.verificationsPassed.incrementAndGet();
        RegisteredServer target = pickFallbackServer();
        if (target != null) {
            player.createConnectionRequest(target).fireAndForget();
        }
    }

    private RegisteredServer pickFallbackServer() {
        String configured = config.limboFallback();
        if (configured != null && !configured.isBlank()) {
            return proxy.getServer(configured).orElse(null);
        }
        String limbo = config.limboServer();
        for (RegisteredServer s : proxy.getAllServers()) {
            if (!s.getServerInfo().getName().equalsIgnoreCase(limbo)) {
                return s;
            }
        }
        return null;
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return;
        }
        String ip = remote.getAddress().getHostAddress();
        stats.pingsSeen.incrementAndGet();

        // Note: ProxyPingEvent cannot cancel the response (setPing is @NotNull),
        // so at this layer we only *cheapen* it: serve a cached ping so a flood
        // can't force repeated MOTD computation. Actually dropping ping-flood
        // packets is the edge/XDP layer's job (see docs/THREAT-MODEL.md #2).
        if (rateLimiter.checkPing(ip).blocked()) {
            stats.pingsBlocked.incrementAndGet();
        }

        ServerPing cached = pingCache.get();
        if (cached != null) {
            event.setPing(cached);
        } else {
            pingCache.put(event.getPing());
        }
    }

    private static void deny(PreLoginEvent event, net.kyori.adventure.text.Component msg) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(msg));
    }

    // ---- Accessors for the command ----
    public AustralisConfig config() { return config; }
    public Stats stats() { return stats; }
    public AttackDetector attackDetector() { return attackDetector; }
    public VerificationManager verification() { return verification; }
}
