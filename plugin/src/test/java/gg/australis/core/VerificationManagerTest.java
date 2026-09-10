package gg.australis.core;

import gg.australis.core.VerificationManager.Result;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VerificationManagerTest {

    private VerificationManager mgr(boolean enabled, boolean onlyDuringAttack, Set<String> allow) {
        return new VerificationManager(enabled, onlyDuringAttack,
                5_000, 3_600_000, 60_000, allow);
    }

    @Test
    void disabledAlwaysAllows() {
        VerificationManager m = mgr(false, true, Set.of());
        assertEquals(Result.ALLOW, m.check("1.1.1.1", true));
    }

    @Test
    void allowsWhenNotUnderAttack() {
        VerificationManager m = mgr(true, true, Set.of());
        assertEquals(Result.ALLOW, m.check("1.1.1.1", false),
                "only-during-attack: no challenge outside an attack");
    }

    @Test
    void challengesUnknownDuringAttackThenAllowsOnReconnect() {
        VerificationManager m = mgr(true, true, Set.of());
        assertEquals(Result.DENY_RECONNECT, m.check("2.2.2.2", true), "first sight challenged");
        assertEquals(Result.ALLOW, m.check("2.2.2.2", true), "reconnect within window allowed");
    }

    @Test
    void allowlistBypassesChallenge() {
        VerificationManager m = mgr(true, true, Set.of("3.3.3.3"));
        assertEquals(Result.ALLOW, m.check("3.3.3.3", true));
    }

    @Test
    void markVerifiedBypassesChallenge() {
        VerificationManager m = mgr(true, true, Set.of());
        m.markVerified("4.4.4.4");
        assertTrue(m.isVerified("4.4.4.4"));
        assertEquals(Result.ALLOW, m.check("4.4.4.4", true));
    }

    @Test
    void forceVerifyAndUnverify() {
        VerificationManager m = mgr(true, true, Set.of());
        m.forceVerify("5.5.5.5", 60_000);
        assertTrue(m.isVerified("5.5.5.5"));
        m.unverify("5.5.5.5");
        assertFalse(m.isVerified("5.5.5.5"));
    }

    @Test
    void expiredChallengeReissued() throws InterruptedException {
        VerificationManager m = new VerificationManager(true, true,
                40, 3_600_000, 60_000, Set.of()); // 40ms reconnect window
        assertEquals(Result.DENY_RECONNECT, m.check("6.6.6.6", true));
        Thread.sleep(70); // window elapses
        assertEquals(Result.DENY_RECONNECT, m.check("6.6.6.6", true),
                "reconnect after the window should be challenged again");
    }

    @Test
    void verifiedCountTracks() {
        VerificationManager m = mgr(true, true, Set.of());
        m.markVerified("7.7.7.7");
        m.markVerified("8.8.8.8");
        assertEquals(2, m.verifiedCount());
    }
}
