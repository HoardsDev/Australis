package gg.australis.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LimboCheckTest {

    @Test
    void failsBeforeMinHold() {
        assertFalse(LimboCheck.passes(true, 500, true, 1500));
        assertFalse(LimboCheck.passes(true, 1499, true, 1500));
    }

    @Test
    void passesAfterHoldWhenMovedAndMovementRequired() {
        assertTrue(LimboCheck.passes(true, 1500, true, 1500));
        assertTrue(LimboCheck.passes(true, 5000, true, 1500));
    }

    @Test
    void failsAfterHoldWhenNotMovedAndMovementRequired() {
        assertFalse(LimboCheck.passes(false, 5000, true, 1500));
    }

    @Test
    void passesOnHoldAloneWhenMovementNotRequired() {
        assertTrue(LimboCheck.passes(false, 1500, false, 1500));
        assertFalse(LimboCheck.passes(false, 1000, false, 1500));
    }
}
