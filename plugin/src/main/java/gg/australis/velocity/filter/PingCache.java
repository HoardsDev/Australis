package gg.australis.velocity.filter;

import com.velocitypowered.api.proxy.server.ServerPing;

/**
 * Caches the server-list ping response for a short window so a status/ping flood
 * can't force the proxy (and any MOTD-building plugins) to recompute a response
 * for every request. Under a flood the same cached {@link ServerPing} is served
 * to everyone until the window expires.
 */
public final class PingCache {

    private volatile ServerPing cached;
    private volatile long cachedAt;
    private volatile long cacheMillis;

    public PingCache(long cacheMillis) {
        this.cacheMillis = cacheMillis;
    }

    public void reconfigure(long cacheMillis) {
        this.cacheMillis = cacheMillis;
    }

    /** Returns a fresh-enough cached ping, or null if the cache is stale. */
    public ServerPing get() {
        if (cacheMillis <= 0) {
            return null;
        }
        ServerPing p = cached;
        if (p != null && System.currentTimeMillis() - cachedAt <= cacheMillis) {
            return p;
        }
        return null;
    }

    public void put(ServerPing ping) {
        if (cacheMillis <= 0 || ping == null) {
            return;
        }
        this.cached = ping;
        this.cachedAt = System.currentTimeMillis();
    }
}
