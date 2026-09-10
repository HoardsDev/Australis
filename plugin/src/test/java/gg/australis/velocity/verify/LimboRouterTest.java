package gg.australis.velocity.verify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LimboRouterTest {

    @Test
    void disabledNeverRoutes() {
        LimboRouter r = new LimboRouter(false, true, "limbo");
        assertTrue(r.initialServer(false, true).isEmpty());
    }

    @Test
    void blankServerNeverRoutes() {
        LimboRouter r = new LimboRouter(true, true, "");
        assertTrue(r.initialServer(false, true).isEmpty());
    }

    @Test
    void verifiedGoesToNormal() {
        LimboRouter r = new LimboRouter(true, true, "limbo");
        assertTrue(r.initialServer(true, true).isEmpty());
    }

    @Test
    void unverifiedDuringAttackGoesToLimbo() {
        LimboRouter r = new LimboRouter(true, true, "limbo");
        assertEquals("limbo", r.initialServer(false, true).orElse(null));
    }

    @Test
    void unverifiedOutsideAttackGoesToNormalWhenGated() {
        LimboRouter r = new LimboRouter(true, true, "limbo");
        assertTrue(r.initialServer(false, false).isEmpty());
    }

    @Test
    void unverifiedAlwaysToLimboWhenNotGated() {
        LimboRouter r = new LimboRouter(true, false, "limbo");
        assertEquals("limbo", r.initialServer(false, false).orElse(null));
    }
}
