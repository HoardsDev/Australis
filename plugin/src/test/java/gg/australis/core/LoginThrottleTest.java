package gg.australis.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {

    @Test
    void allowsUnderLimit() {
        LoginThrottle t = new LoginThrottle(3, 10_000, 5);
        for (int i = 0; i < 3; i++) {
            assertFalse(t.onLoginAttempt("1.1.1.1"));
        }
    }

    @Test
    void blocksOverAttemptLimit() {
        LoginThrottle t = new LoginThrottle(3, 10_000, 5);
        for (int i = 0; i < 3; i++) {
            t.onLoginAttempt("2.2.2.2");
        }
        assertTrue(t.onLoginAttempt("2.2.2.2"), "4th attempt should be blocked");
    }

    @Test
    void churnTriggersBlock() {
        LoginThrottle t = new LoginThrottle(100, 10_000, 2); // high attempt limit, low churn
        t.onLoginAttempt("3.3.3.3");
        t.onEarlyDisconnect("3.3.3.3");
        t.onEarlyDisconnect("3.3.3.3");
        t.onEarlyDisconnect("3.3.3.3"); // churn now 3 > maxChurn 2
        assertTrue(t.onLoginAttempt("3.3.3.3"), "excessive churn should block");
    }

    @Test
    void windowResets() throws InterruptedException {
        LoginThrottle t = new LoginThrottle(2, 60, 5);
        t.onLoginAttempt("4.4.4.4");
        t.onLoginAttempt("4.4.4.4");
        assertTrue(t.onLoginAttempt("4.4.4.4"));
        Thread.sleep(90);
        assertFalse(t.onLoginAttempt("4.4.4.4"), "window should reset attempts");
    }

    @Test
    void pruneRemovesStale() throws InterruptedException {
        LoginThrottle t = new LoginThrottle(3, 10_000, 5);
        t.onLoginAttempt("5.5.5.5");
        Thread.sleep(30);
        assertEquals(1, t.prune(10));
    }
}
