package gg.australis.bungee;

import gg.australis.bungee.command.AustralisCommand;
import gg.australis.bungee.config.AustralisConfig;
import gg.australis.bungee.filter.PingCache;
import gg.australis.core.AttackDetector;
import gg.australis.core.ConnectionRateLimiter;
import gg.australis.core.EdgeClient;
import gg.australis.core.LimboRouter;
import gg.australis.core.LoginThrottle;
import gg.australis.core.MetricsServer;
import gg.australis.core.Stats;
import gg.australis.core.VerificationManager;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PlayerHandshakeEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Australis — free, self-hosted L7 protection for Minecraft (BungeeCord/Waterfall
 * proxy). Feature-parity port of the Velocity plugin.
 *
 * <p>Wires the whole Layer-2 pipeline: a global attack detector, per-IP
 * connection/ping/login rate limiting, bot verification (reconnect challenge +
 * optional limbo routing), a status-ping cache, and the feedback loop that pushes
 * confirmed abusers to the edge for kernel-level dropping.
 *
 * <p>Two Bungee capabilities the Velocity API lacks are exploited here:
 * <ul>
 *   <li>{@link PreLoginEvent} is <b>cancellable</b>, so the connection block,
 *       login throttle, and verification challenge are enforced directly.</li>
 *   <li>{@link ProxyPingEvent} lets us {@code setResponse(...)}, so the ping
 *       cache truly short-circuits MOTD computation under a flood.</li>
 * </ul>
 */
public final class AustralisBungee extends Plugin implements Listener {

    private static final String VERSION = "0.2.0";
    private static final String VERIFY_CHANNEL = "australis:verify";
    /** Minecraft handshake next-state for a status/ping request. */
    private static final int HANDSHAKE_STATUS = 1;

    private volatile AustralisConfig config;
    private final Stats stats = new Stats();

    private AttackDetector attackDetector;
    private ConnectionRateLimiter rateLimiter;
    private LoginThrottle loginThrottle;
    private VerificationManager verification;
    private LimboRouter limboRouter;
    private PingCache pingCache;
    private EdgeClient edgeClient;
    private MetricsServer metricsServer;

    private ScheduledTask pruneTask;
    private final AtomicBoolean attackActive = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        this.config = AustralisConfig.loadOrCreate(getDataFolder().toPath(), getLogger());

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
        this.edgeClient = new EdgeClient(new JulSlf4jLogger(getLogger()), stats);
        this.edgeClient.reconfigure(config.edgeEnabled(), config.edgeUrl(),
                config.edgeToken(), config.edgeBanSeconds());

        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new AustralisCommand(this));

        // Channel the limbo backend uses to signal that a player passed verification.
        getProxy().registerChannel(VERIFY_CHANNEL);

        long interval = config.pruneIntervalMillis();
        pruneTask = getProxy().getScheduler().schedule(
                this, this::prune, interval, interval, TimeUnit.MILLISECONDS);

        getLogger().info("Australis " + VERSION + " enabled — L7 protection active.");
        getLogger().info(String.format("Verification: %s (only-during-attack=%s). Edge feedback: %s.",
                config.verifyEnabled() ? "on" : "off",
                config.verifyOnlyDuringAttack(),
                config.edgeEnabled() ? "on -> " + config.edgeUrl() : "off"));
        getLogger().info("See docs/DEPLOYMENT.md for the edge (origin hiding + kernel blocklist feedback).");

        if (config.metricsEnabled()) {
            metricsServer = new MetricsServer(stats, attackDetector::isUnderAttack, verification::verifiedCount);
            try {
                metricsServer.start(config.metricsBind());
                getLogger().info("Australis Prometheus metrics: http://" + config.metricsBind() + "/metrics");
            } catch (IOException | RuntimeException e) {
                getLogger().warning("Australis metrics failed to start on " + config.metricsBind() + ": " + e.getMessage());
                metricsServer = null;
            }
        }
    }

    @Override
    public void onDisable() {
        if (pruneTask != null) {
            pruneTask.cancel();
        }
        if (edgeClient != null) {
            edgeClient.shutdown();
        }
        if (metricsServer != null) {
            metricsServer.stop();
        }
    }

    /** Reload config and push new settings into every component. */
    public void reload() {
        AustralisConfig fresh = AustralisConfig.loadOrCreate(getDataFolder().toPath(), getLogger());
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
        getLogger().info("Australis configuration reloaded.");
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
            getLogger().warning(String.format(
                    "Australis: ATTACK DETECTED — aggressive defences armed (verification %s).",
                    config.verifyEnabled() ? "on" : "off"));
        } else {
            getLogger().info("Australis: attack subsided — back to normal operation.");
        }
    }

    // ---- Event pipeline (order matters: cheapest/earliest checks first) ----

    /**
     * Earliest hook Bungee exposes: fires on the client handshake, before
     * {@link PreLoginEvent}. This is where we see the <em>true</em> connection
     * rate, so attack detection and the per-IP connection limiter live here.
     * Enforcement (the kick) happens in {@link #onPreLogin}, the earliest
     * cancellable hook.
     */
    @EventHandler
    public void onHandshake(PlayerHandshakeEvent event) {
        if (event.getHandshake().getRequestedProtocol() == HANDSHAKE_STATUS) {
            return; // status/ping volume is handled on the ping path
        }
        String ip = ipOf(event.getConnection());
        if (ip == null) {
            return;
        }
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
            // Log only the moment an IP trips, not every subsequent banned packet.
            if (config.logBlocks() && !conn.reason().endsWith("/banned")) {
                getLogger().warning(String.format(
                        "Australis flood-limited %s (%s) — pushed to edge; kicked at login while banned",
                        ip, conn.reason()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(PreLoginEvent event) {
        String ip = ipOf(event.getConnection());
        if (ip == null) {
            return;
        }
        boolean underAttack = attackDetector.isUnderAttack();

        // 1) Per-IP connection flood — counted at handshake; enforce the kick here
        //    (the earliest deniable hook). Peek only: no double-count.
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
                getLogger().warning("Australis throttled login from " + ip);
            }
            return;
        }

        // 3) Bot verification (reconnect challenge, attack-gated).
        VerificationManager.Result vr = verification.check(ip, underAttack);
        if (vr == VerificationManager.Result.DENY_RECONNECT) {
            deny(event, config.verifyKickMessage());
            stats.verificationChallenges.incrementAndGet();
            if (config.logBlocks()) {
                getLogger().info("Australis issued reconnect challenge to " + ip);
            }
        }
    }

    /**
     * Deep verification: route not-yet-verified players to the limbo backend first
     * (when configured/under attack). Gated to the initial proxy join so it mirrors
     * Velocity's {@code PlayerChooseInitialServerEvent} and never re-routes a
     * player who is switching servers or being moved after passing limbo.
     */
    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (!limboRouter.enabled()) {
            return;
        }
        if (event.getReason() != ServerConnectEvent.Reason.JOIN_PROXY) {
            return;
        }
        String ip = ipOf(event.getPlayer());
        if (ip == null) {
            return;
        }
        Optional<String> limbo = limboRouter.initialServer(
                verification.isVerified(ip), attackDetector.isUnderAttack());
        if (limbo.isPresent()) {
            ServerInfo info = getProxy().getServerInfo(limbo.get());
            if (info != null) {
                event.setTarget(info);
            }
        }
    }

    /**
     * Auto-verify an IP only once it has actually reached a <em>real</em> backend
     * (not the limbo). Doing this here — rather than at player-join / post-login —
     * is essential: verifying earlier would run before {@link #onServerConnect} and
     * disable limbo routing entirely.
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        String connected = event.getServer().getInfo().getName();
        if (connected.equalsIgnoreCase(config.limboServer())) {
            return; // reaching limbo is not proof of anything
        }
        String ip = ipOf(event.getPlayer());
        if (ip == null) {
            return;
        }
        // Count a pass only the first time an IP becomes verified (not on every
        // server switch, and not double-counting a limbo pass already counted in
        // onPluginMessage); always refresh the TTL.
        boolean wasVerified = verification.isVerified(ip);
        verification.markVerified(ip);
        if (!wasVerified) {
            stats.verificationsPassed.incrementAndGet();
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        String ip = ipOf(event.getPlayer());
        if (ip != null) {
            loginThrottle.onEarlyDisconnect(ip);
        }
    }

    /**
     * The limbo backend sends a message on {@code australis:verify} once a player
     * has passed its behavioural checks. We trust it only from a backend
     * connection (never a client), mark the IP verified, and move the player to a
     * real server.
     */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!VERIFY_CHANNEL.equals(event.getTag())) {
            return;
        }
        // Consume: never forward a verify message on to the client.
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server)) {
            return; // ignore anything not from a backend server
        }
        if (!(event.getReceiver() instanceof ProxiedPlayer player)) {
            return;
        }
        String ip = ipOf(player);
        if (ip == null) {
            return;
        }
        verification.markVerified(ip);
        stats.verificationsPassed.incrementAndGet();
        ServerInfo target = pickFallbackServer();
        if (target != null) {
            player.connect(target);
        }
    }

    private ServerInfo pickFallbackServer() {
        String configured = config.limboFallback();
        if (configured != null && !configured.isBlank()) {
            return getProxy().getServerInfo(configured);
        }
        String limbo = config.limboServer();
        for (ServerInfo s : getProxy().getServers().values()) {
            if (!s.getName().equalsIgnoreCase(limbo)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Unlike Velocity, Bungee's {@link ProxyPingEvent} lets us replace the
     * response — so under a flood we serve a cached ping and skip MOTD computation
     * entirely. Actually dropping ping-flood packets remains the edge/XDP layer's
     * job (see docs/THREAT-MODEL.md #2).
     */
    @EventHandler
    public void onPing(ProxyPingEvent event) {
        String ip = ipOf(event.getConnection());
        if (ip == null) {
            return;
        }
        stats.pingsSeen.incrementAndGet();
        if (rateLimiter.checkPing(ip).blocked()) {
            stats.pingsBlocked.incrementAndGet();
        }

        ServerPing cached = pingCache.get();
        if (cached != null) {
            event.setResponse(cached);
        } else {
            pingCache.put(event.getResponse());
        }
    }

    private static void deny(PreLoginEvent event, String legacyMessage) {
        event.setCancelled(true);
        event.setReason(TextComponent.fromLegacy(legacyMessage));
    }

    /** Extract the dotted IP of any Bungee connection, or null if unavailable. */
    private static String ipOf(Connection connection) {
        if (connection == null) {
            return null;
        }
        SocketAddress sa = connection.getSocketAddress();
        if (sa instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().getHostAddress();
        }
        return null;
    }

    // ---- Accessors for the command ----
    public AustralisConfig config() { return config; }
    public Stats stats() { return stats; }
    public AttackDetector attackDetector() { return attackDetector; }
    public VerificationManager verification() { return verification; }
}
