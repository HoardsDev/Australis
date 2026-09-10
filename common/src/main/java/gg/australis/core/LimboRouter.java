package gg.australis.core;

import java.util.Optional;

/**
 * Deep-verification routing decision (Phase 2, deep tier).
 *
 * <p>The reconnect challenge ({@link VerificationManager}) filters bots that
 * never reconnect. Bots that <em>do</em> reconnect need behavioural checks that
 * the public Velocity API can't do directly. The practical way to get those on
 * the stable API is to route not-yet-verified players to a lightweight
 * <b>limbo backend</b> first (e.g. NanoLimbo or a Paper server running Sonar /
 * a movement-check plugin). The limbo backend does the physics/keep-alive
 * verification and, once a player passes, signals the proxy over a plugin
 * message; the proxy then promotes the IP and moves the player to a real server.
 *
 * <p>This class is the pure, testable routing decision. The Velocity glue
 * (PlayerChooseInitialServerEvent + the {@code australis:verify} plugin channel)
 * lives in the plugin and needs a real limbo backend to exercise end-to-end.
 */
public final class LimboRouter {

    private volatile boolean enabled;
    private volatile boolean onlyDuringAttack;
    private volatile String limboServer;

    public LimboRouter(boolean enabled, boolean onlyDuringAttack, String limboServer) {
        reconfigure(enabled, onlyDuringAttack, limboServer);
    }

    public void reconfigure(boolean enabled, boolean onlyDuringAttack, String limboServer) {
        this.enabled = enabled;
        this.onlyDuringAttack = onlyDuringAttack;
        this.limboServer = limboServer;
    }

    public boolean enabled() {
        return enabled;
    }

    public String limboServer() {
        return limboServer;
    }

    /**
     * @return the limbo server to send this player to first, or empty to use the
     *         player's normal initial server.
     */
    public Optional<String> initialServer(boolean verified, boolean underAttack) {
        if (!enabled || limboServer == null || limboServer.isBlank()) {
            return Optional.empty();
        }
        if (verified) {
            return Optional.empty();
        }
        if (onlyDuringAttack && !underAttack) {
            return Optional.empty();
        }
        return Optional.of(limboServer);
    }
}
