package gg.australis.velocity.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttackDetectorTest {

    @Test
    void notUnderAttackInitially() {
        AttackDetector d = new AttackDetector(5, 10_000);
        assertFalse(d.isUnderAttack());
    }

    @Test
    void staysCalmBelowThreshold() {
        AttackDetector d = new AttackDetector(5, 10_000);
        for (int i = 0; i < 4; i++) {
            assertFalse(d.record(), "record " + i + " below threshold");
        }
        assertFalse(d.isUnderAttack());
    }

    @Test
    void tripsAtThreshold() {
        AttackDetector d = new AttackDetector(5, 10_000);
        boolean tripped = false;
        for (int i = 0; i < 5; i++) {
            tripped = d.record();
        }
        assertTrue(tripped, "should trip at threshold");
        assertTrue(d.isUnderAttack());
    }

    @Test
    void cooldownExpires() throws InterruptedException {
        AttackDetector d = new AttackDetector(3, 80); // 80ms cooldown
        for (int i = 0; i < 3; i++) {
            d.record();
        }
        assertTrue(d.isUnderAttack());
        Thread.sleep(120);
        assertFalse(d.isUnderAttack(), "attack state should expire after cooldown");
    }
}
