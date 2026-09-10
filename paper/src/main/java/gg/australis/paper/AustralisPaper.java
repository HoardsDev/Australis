package gg.australis.paper;

import gg.australis.core.AttackDetector;
import gg.australis.core.ConnectionRateLimiter;
import gg.australis.core.EdgeClient;
import gg.australis.core.LoginThrottle;
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

import java.net.InetSocketAddress;
import java.util.HashSet;

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

    private volatile String kickMsg = "Australis > You are connecting too quickly.";
    private volatile String verifyKickMsg = "Australis > Verifying... please reconnect.";
    private volatile boolean logBlocks = true;
    private volatile long verifiedTtlMillis = 3_600_000L;
    private volatile long entryTtlMillis = 300_000L;

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

        getSLF4JLogger().info("Australis (Paper) enabled — L7 protection active.");
    }

    @Override
    public void onDisable() {
        if (edgeClient != null) {
            edgeClient.shutdown();
        }
    }

    private long entryTtlWindowTicks() {
        long pruneMillis = getConfig().getLong("housekeeping.prune-interval-millis", 60_000);
        return Math.max(20L, pruneMillis / 50L); // 50ms per tick
    }

    private void reconfigureAll() {
        FileConfiguration c = getConfig();

        attackDetector.reconfigure(
                c.getInt("attack-detector.connections-per-second", 60),
                c.getLong("attack-detector.cooldown-millis", 30_000));

        rateLimiter.reconfigure(
                c.getInt("connections.max-per-window", 8),
                c.getLong("connections.window-millis", 3_000),
                c.getLong("connections.block-millis", 30_000),
                c.getInt("pings.max-per-window", 20),
                c.getLong("pings.window-millis", 3_000),
                c.getLong("pings.block-millis", 10_000));

        loginThrottle.reconfigure(
                c.getInt("login.max-per-window", 6),
                c.getLong("login.window-millis", 5_000),
                c.getInt("login.max-churn", 4));

        verifiedTtlMillis = c.getLong("verification.verified-ttl-millis", 3_600_000);
        verification.reconfigure(
                c.getBoolean("verification.enabled", true),
                c.getBoolean("verification.only-during-attack", true),
                c.getLong("verification.reconnect-window-millis", 15_000),
                verifiedTtlMillis,
                c.getLong("verification.pending-ttl-millis", 60_000),
                new HashSet<>(c.getStringList("verification.allowlist")));

        edgeClient.reconfigure(
                c.getBoolean("edge.enabled", false),
                c.getString("edge.url", ""),
                c.getString("edge.token", ""),
                c.getLong("edge.ban-seconds", 600));

        kickMsg = c.getString("kick-message", kickMsg);
        verifyKickMsg = c.getString("verification.kick-message", verifyKickMsg);
        logBlocks = c.getBoolean("log-blocks", true);
        entryTtlMillis = c.getLong("housekeeping.entry-ttl-millis", 300_000);
    }

    private void prune() {
        rateLimiter.prune(entryTtlMillis);
        loginThrottle.prune(entryTtlMillis);
        verification.prune();
        edgeClient.housekeeping();
    }

    // ---- Event pipeline (AsyncPlayerPreLogin runs off the main thread) ----

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        stats.connectionsSeen.incrementAndGet();

        boolean underAttack = attackDetector.record();
        if (underAttack) {
            stats.attacksDetected.incrementAndGet();
        }

        ConnectionRateLimiter.Decision conn = rateLimiter.checkConnection(ip);
        if (conn.blocked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            stats.connectionsBlocked.incrementAndGet();
            edgeClient.blocklist(ip, conn.reason());
            if (logBlocks) {
                getSLF4JLogger().warn("Australis blocked {} ({})", ip, conn.reason());
            }
            return;
        }

        if (loginThrottle.onLoginAttempt(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            stats.loginsThrottled.incrementAndGet();
            edgeClient.blocklist(ip, "login-abuse");
            return;
        }

        if (verification.check(ip, underAttack) == VerificationManager.Result.DENY_RECONNECT) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, verifyKickMsg);
            stats.verificationChallenges.incrementAndGet();
        }
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
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
