package gg.australis.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionRateLimiterTest {

    private ConnectionRateLimiter limiter() {
        // 3 connections / 10s window, 10s ban; pings 5 / 10s, 10s ban.
        return new ConnectionRateLimiter(3, 10_000, 10_000, 5, 10_000, 10_000);
    }

    @Test
    void allowsUnderLimit() {
        ConnectionRateLimiter l = limiter();
        for (int i = 0; i < 3; i++) {
            assertFalse(l.checkConnection("1.1.1.1").blocked(), "connection " + i + " should be allowed");
        }
    }

    @Test
    void blocksOverLimit() {
        ConnectionRateLimiter l = limiter();
        for (int i = 0; i < 3; i++) {
            l.checkConnection("2.2.2.2");
        }
        assertTrue(l.checkConnection("2.2.2.2").blocked(), "4th connection should be blocked");
    }

    @Test
    void tempBanPersistsAfterTrip() {
        ConnectionRateLimiter l = limiter();
        for (int i = 0; i < 5; i++) {
            l.checkConnection("3.3.3.3");
        }
        // Subsequent checks remain blocked (banned) for the ban duration.
        assertTrue(l.checkConnection("3.3.3.3").blocked());
        assertTrue(l.checkConnection("3.3.3.3").reason().contains("banned"));
    }

    @Test
    void perIpIsolation() {
        ConnectionRateLimiter l = limiter();
        for (int i = 0; i < 4; i++) {
            l.checkConnection("4.4.4.4");
        }
        assertFalse(l.checkConnection("5.5.5.5").blocked(), "a different IP has its own allowance");
    }

    @Test
    void connectionsAndPingsAreIndependent() {
        ConnectionRateLimiter l = limiter();
        for (int i = 0; i < 4; i++) {
            l.checkConnection("6.6.6.6"); // trip connection limit
        }
        // Ping limit (5) for the same IP is untouched.
        for (int i = 0; i < 5; i++) {
            assertFalse(l.checkPing("6.6.6.6").blocked(), "ping " + i + " should be allowed");
        }
        assertTrue(l.checkPing("6.6.6.6").blocked(), "6th ping should be blocked");
    }

    @Test
    void isConnectionBlockedPeeksWithoutCounting() {
        ConnectionRateLimiter l = limiter();
        assertFalse(l.isConnectionBlocked("9.9.9.9"), "unseen IP is not blocked");
        // Peeking must not create or advance a window: still allowed up to limit.
        for (int i = 0; i < 3; i++) {
            assertFalse(l.checkConnection("9.9.9.9").blocked());
            assertFalse(l.isConnectionBlocked("9.9.9.9"), "peek must not count toward the limit");
        }
        assertTrue(l.checkConnection("9.9.9.9").blocked(), "4th trips the ban");
        assertTrue(l.isConnectionBlocked("9.9.9.9"), "peek now reports the ban");
    }

    @Test
    void windowRollsAfterExpiry() throws InterruptedException {
        // Tiny window so it rolls quickly.
        ConnectionRateLimiter l = new ConnectionRateLimiter(2, 80, 1, 2, 80, 1);
        assertFalse(l.checkConnection("7.7.7.7").blocked());
        assertFalse(l.checkConnection("7.7.7.7").blocked());
        assertTrue(l.checkConnection("7.7.7.7").blocked()); // over limit, 1ms ban
        Thread.sleep(120); // window + ban expire
        assertFalse(l.checkConnection("7.7.7.7").blocked(), "window should have rolled");
    }

    @Test
    void pruneRemovesStaleEntries() throws InterruptedException {
        ConnectionRateLimiter l = limiter();
        l.checkConnection("8.8.8.8");
        assertEquals(1, l.trackedConnections());
        Thread.sleep(30);
        assertEquals(1, l.prune(10), "entry older than ttl should be pruned");
        assertEquals(0, l.trackedConnections());
    }
}
