package gg.australis.paper;

import gg.australis.core.AttackDetector;
import gg.australis.core.ConfigSanitizer;
import gg.australis.core.ConnectionRateLimiter;
import gg.australis.core.EdgeClient;
import gg.australis.core.LoginThrottle;
import gg.australis.core.MetricsServer;
import gg.australis.core.Stats;
import gg.australis.core.VerificationManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Australis — Paper-native L7 DDoS/bot protection.
 *
 * <p>Runs directly on a Paper/Spigot server (no proxy required), reusing the
 * same shared protection logic ({@code gg.australis.core}) as the Velocity
 * plugin. It rate-limits connections/logins/pings per IP, gates a bot
 * verification reconnect-challenge to real attacks, and can push convicted IPs
 * to an Australis edge agent for kernel-level dropping.
 *
 * <p>Note: as a backend plugin it can't hide the server IP or absorb volumetric
 * floods — that's the edge's job (see docs/DEPLOYMENT.md). It fully owns the L7
 * layer that reaches the server.
 */
public final class AustralisPaper extends JavaPlugin implements Listener, CommandExecutor {

    private final Stats stats = new Stats();
    private AttackDetector attackDetector;
    private ConnectionRateLimiter rateLimiter;
    private LoginThrottle loginThrottle;
    private VerificationManager verification;
    private EdgeClient edgeClient;
    private MetricsServer metricsServer;

    private volatile String kickMsg = "Australis > You are connecting too quickly.";
    private volatile String verifyKickMsg = "Australis > Verifying... please reconnect.";
    private volatile boolean logBlocks = true;
    private volatile long verifiedTtlMillis = 3_600_000L;
    private volatile long entryTtlMillis = 300_000L;
    private final AtomicBoolean attackActive = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();

        attackDetector = new AttackDetector(60, 30_000);
        rateLimiter = new ConnectionRateLimiter(8, 3_000, 30_000, 20, 3_000, 10_000);
        loginThrottle = new LoginThrottle(6, 5_000, 4);
        verification = new VerificationManager(true, true, 15_000, 3_600_000, 60_000, new HashSet<>());
        edgeClient = new EdgeClient(getSLF4JLogger(), stats);
        reconfigureAll();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("australis");
        if (cmd != null) {
            cmd.setExecutor(this);
        }

        long ticks = Math.max(20L, entryTtlWindowTicks());
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::prune, ticks, ticks);

        if (getConfig().getBoolean("metrics.enabled", false)) {
            String bind = getConfig().getString("metrics.bind", "127.0.0.1:9110");
            metricsServer = new MetricsServer(stats, attackDetector::isUnderAttack, verification::verifiedCount);
            try {
                metricsServer.start(bind);
                getSLF4JLogger().info("Australis Prometheus metrics: http://{}/metrics", bind);
            } catch (IOException | RuntimeException e) {
                getSLF4JLogger().warn("Australis metrics failed to start on {}: {}", bind, e.getMessage());
                metricsServer = null;
            }
        }

        getSLF4JLogger().info("Australis (Paper) enabled — L7 protection active.");
    }

    @Override
    public void onDisable() {
        if (edgeClient != null) {
            edgeClient.shutdown();
        }
        if (metricsServer != null) {
            metricsServer.stop();
        }
    }

    private long entryTtlWindowTicks() {
        long pruneMillis = getConfig().getLong("housekeeping.prune-interval-millis", 60_000);
        return Math.max(20L, pruneMillis / 50L); // 50ms per tick
    }

    private void reconfigureAll() {
        FileConfiguration c = getConfig();
        // Clamp every numeric setting to a safe range (mirrors the Velocity
        // AustralisConfig.validate) so a bad config degrades to safe defaults
        // instead of pinning permanent-attack mode or disabling a limiter.
        ConfigSanitizer.Warner warn = m -> getSLF4JLogger().warn(m);

        attackDetector.reconfigure(
                ConfigSanitizer.clampInt(warn, "attack-detector.connections-per-second",
                        c.getInt("attack-detector.connections-per-second", 60), 1, 10_000_000),
                ConfigSanitizer.clampLong(warn, "attack-detector.cooldown-millis",
                        c.getLong("attack-detector.cooldown-millis", 30_000), 0, 86_400_000));

        rateLimiter.reconfigure(
                ConfigSanitizer.clampInt(warn, "connections.max-per-window",
                        c.getInt("connections.max-per-window", 8), 1, 1_000_000),
                ConfigSanitizer.clampLong(warn, "connections.window-millis",
                        c.getLong("connections.window-millis", 3_000), 1, 3_600_000),
                ConfigSanitizer.clampLong(warn, "connections.block-millis",
                        c.getLong("connections.block-millis", 30_000), 0, 86_400_000),
                ConfigSanitizer.clampInt(warn, "pings.max-per-window",
                        c.getInt("pings.max-per-window", 20), 1, 1_000_000),
                ConfigSanitizer.clampLong(warn, "pings.window-millis",
                        c.getLong("pings.window-millis", 3_000), 1, 3_600_000),
                ConfigSanitizer.clampLong(warn, "pings.block-millis",
                        c.getLong("pings.block-millis", 10_000), 0, 86_400_000));

        loginThrottle.reconfigure(
                ConfigSanitizer.clampInt(warn, "login.max-per-window",
                        c.getInt("login.max-per-window", 6), 1, 1_000_000),
                ConfigSanitizer.clampLong(warn, "login.window-millis",
                        c.getLong("login.window-millis", 5_000), 1, 3_600_000),
                ConfigSanitizer.clampInt(warn, "login.max-churn",
                        c.getInt("login.max-churn", 4), 0, 1_000_000));

        verifiedTtlMillis = ConfigSanitizer.clampLong(warn, "verification.verified-ttl-millis",
                c.getLong("verification.verified-ttl-millis", 3_600_000), 1, 604_800_000L);
        verification.reconfigure(
                c.getBoolean("verification.enabled", true),
                c.getBoolean("verification.only-during-attack", true),
                ConfigSanitizer.clampLong(warn, "verification.reconnect-window-millis",
                        c.getLong("verification.reconnect-window-millis", 15_000), 1, 3_600_000),
                verifiedTtlMillis,
                ConfigSanitizer.clampLong(warn, "verification.pending-ttl-millis",
                        c.getLong("verification.pending-ttl-millis", 60_000), 1, 3_600_000),
                new HashSet<>(c.getStringList("verification.allowlist")));

        edgeClient.reconfigure(
                c.getBoolean("edge.enabled", false),
                c.getString("edge.url", ""),
                c.getString("edge.token", ""),
                ConfigSanitizer.clampLong(warn, "edge.ban-seconds",
                        c.getLong("edge.ban-seconds", 600), 0, 31_536_000L));

        kickMsg = c.getString("kick-message", kickMsg);
        verifyKickMsg = c.getString("verification.kick-message", verifyKickMsg);
        logBlocks = c.getBoolean("log-blocks", true);
        entryTtlMillis = ConfigSanitizer.clampLong(warn, "housekeeping.entry-ttl-millis",
                c.getLong("housekeeping.entry-ttl-millis", 300_000), 0, 86_400_000);
    }

    private void prune() {
        rateLimiter.prune(entryTtlMillis);
        loginThrottle.prune(entryTtlMillis);
        verification.prune();
        edgeClient.housekeeping();
        // Catch the attack->normal transition once the cooldown expires and no
        // new logins are arriving to drive onPreLogin.
        setAttackState(attackDetector.isUnderAttack());
    }

    /**
     * Log attack-mode edge transitions once (parity with the Velocity plugin).
     * Called from the async pre-login event and the async prune task, so the
     * transition is claimed with a CAS for exactly-one log per edge.
     */
    private void setAttackState(boolean nowUnderAttack) {
        if (!attackActive.compareAndSet(!nowUnderAttack, nowUnderAttack)) {
            return;
        }
        if (nowUnderAttack) {
            getSLF4JLogger().warn("Australis (Paper): ATTACK DETECTED — aggressive defences armed.");
        } else {
            getSLF4JLogger().info("Australis (Paper): attack subsided — back to normal operation.");
        }
    }

    // ---- Event pipeline (AsyncPlayerPreLogin runs off the main thread) ----

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getAddress() == null) {
            return; // no source address to key on; nothing we can safely do
        }
        String ip = event.getAddress().getHostAddress();
        stats.connectionsSeen.incrementAndGet();

        boolean underAttack = attackDetector.record();
        if (underAttack) {
            stats.attacksDetected.incrementAndGet();
        }
        setAttackState(underAttack);

        ConnectionRateLimiter.Decision conn = rateLimiter.checkConnection(ip);
        if (conn.blocked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            stats.connectionsBlocked.incrementAndGet();
            edgeClient.blocklist(ip, conn.reason());
            // Log only the moment an IP trips, not every banned packet, so a
            // flood does not drown the log (parity with the Velocity plugin).
            if (logBlocks && !conn.reason().endsWith("/banned")) {
                getSLF4JLogger().warn("Australis blocked {} ({})", ip, conn.reason());
            }
            return;
        }

        if (loginThrottle.onLoginAttempt(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            stats.loginsThrottled.incrementAndGet();
            edgeClient.blocklist(ip, "login-abuse");
            if (logBlocks) {
                getSLF4JLogger().warn("Australis throttled login from {}", ip);
            }
            return;
        }

        if (verification.check(ip, underAttack) == VerificationManager.Result.DENY_RECONNECT) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, verifyKickMsg);
            stats.verificationChallenges.incrementAndGet();
            if (logBlocks) {
                getSLF4JLogger().info("Australis issued reconnect challenge to {}", ip);
            }
        }
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (event.getAddress() == null) {
            return;
        }
        stats.pingsSeen.incrementAndGet();
        String ip = event.getAddress().getHostAddress();
        if (rateLimiter.checkPing(ip).blocked()) {
            stats.pingsBlocked.incrementAndGet();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        InetSocketAddress a = event.getPlayer().getAddress();
        if (a != null && a.getAddress() != null) {
            verification.markVerified(a.getAddress().getHostAddress());
            stats.verificationsPassed.incrementAndGet();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        InetSocketAddress a = event.getPlayer().getAddress();
        if (a != null && a.getAddress() != null) {
            loginThrottle.onEarlyDisconnect(a.getAddress().getHostAddress());
        }
    }

    // ---- /australis command ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("australis.admin")) {
            sender.sendMessage("Australis > no permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Australis > /australis <stats|reload|verify|unverify> [ip]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "stats" -> sender.sendMessage("Australis > " +
                    stats.summary(attackDetector.isUnderAttack(), verification.verifiedCount()));
            case "reload" -> {
                reloadConfig();
                reconfigureAll();
                sender.sendMessage("Australis > configuration reloaded.");
            }
            case "verify" -> {
                if (args.length < 2) {
                    sender.sendMessage("Australis > usage: /australis verify <ip>");
                } else {
                    verification.forceVerify(args[1], verifiedTtlMillis);
                    sender.sendMessage("Australis > verified " + args[1]);
                }
            }
            case "unverify" -> {
                if (args.length < 2) {
                    sender.sendMessage("Australis > usage: /australis unverify <ip>");
                } else {
                    verification.unverify(args[1]);
                    sender.sendMessage("Australis > unverified " + args[1]);
                }
            }
            default -> sender.sendMessage("Australis > unknown subcommand: " + args[0]);
        }
        return true;
    }
}
