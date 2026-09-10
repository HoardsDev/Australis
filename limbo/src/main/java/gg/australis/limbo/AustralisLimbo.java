package gg.australis.limbo;

import gg.australis.core.LimboCheck;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Australis limbo verifier (Phase 2, deep tier).
 *
 * <p>Runs on the lightweight Paper server the Velocity proxy routes
 * not-yet-verified players to. It holds each arriving client briefly and watches
 * for real-client behaviour (a movement/look packet within a window). Once a
 * client passes {@link LimboCheck}, it sends a message on the
 * {@code australis:verify} plugin channel; the Australis proxy plugin receives it
 * from this backend connection, marks the IP verified, and moves the player to a
 * real server. Dumb flood bots that never behave like a client are kicked after a
 * timeout and never reach the backend.
 *
 * <p>All Bukkit events and the scheduler run on the main thread, so the pending
 * map needs no synchronization.
 */
public final class AustralisLimbo extends JavaPlugin implements Listener {

    /** Must match the Velocity plugin's channel: namespace "australis", key "verify". */
    private static final String CHANNEL = "australis:verify";

    private static final class Held {
        final long joinAt;
        boolean moved;
        Held(long joinAt) { this.joinAt = joinAt; }
    }

    private final Map<UUID, Held> pending = new HashMap<>();

    private long minHoldMillis;
    private boolean requireMovement;
    private long failAfterMillis;
    private String kickMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);

        // Poll pending clients ~every 250ms (5 ticks): promote passers, kick timeouts.
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 5L);

        getLogger().info("AustralisLimbo enabled — hold=" + minHoldMillis
                + "ms require-movement=" + requireMovement + " fail-after=" + failAfterMillis + "ms");
    }

    private void reloadSettings() {
        reloadConfig();
        minHoldMillis = Math.max(0, getConfig().getLong("min-hold-millis", 1500));
        requireMovement = getConfig().getBoolean("require-movement", true);
        failAfterMillis = Math.max(minHoldMillis + 1000, getConfig().getLong("fail-after-millis", 15000));
        kickMessage = getConfig().getString("kick-message",
                "Australis > Verification failed — reconnect to try again.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Keep the limbo world harmless: no building, no interaction.
        p.setGameMode(GameMode.ADVENTURE);
        pending.put(p.getUniqueId(), new Held(System.currentTimeMillis()));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Held h = pending.get(event.getPlayer().getUniqueId());
        if (h == null || h.moved) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()
                || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            h.moved = true;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Held>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Held> e = it.next();
            Player p = getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            Held h = e.getValue();
            long held = now - h.joinAt;
            if (LimboCheck.passes(h.moved, held, requireMovement, minHoldMillis)) {
                // Signal the proxy; it promotes the IP and moves the player.
                p.sendPluginMessage(this, CHANNEL,
                        p.getUniqueId().toString().getBytes(StandardCharsets.UTF_8));
                it.remove();
            } else if (held > failAfterMillis) {
                p.kickPlayer(kickMessage);
                it.remove();
            }
        }
    }
}
