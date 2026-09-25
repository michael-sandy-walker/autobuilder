package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ParticipationWindowExpiredTest {

    @Test
    public void testExpiredWhenAgeExceedsValidForCycles() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        // validForCycles is 2 (1.0 / 0.5)
        // tick once: age becomes 1
        ParticipationWindow ticked = window.tick();
        // tick again: age becomes 2
        ParticipationWindow tickedAgain = ticked.tick();
        // tick a third time: age becomes 3, which is > validForCycles (2)
        ParticipationWindow expired = tickedAgain.tick();
        assertTrue(expired.expired());
    }

    @Test
    public void testNotExpiredWhenAgeIsWithinValidForCycles() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        // validForCycles is 2
        // age 0 is within range
        assertFalse(window.expired());
        // tick once: age becomes 1, still within range
        ParticipationWindow ticked = window.tick();
        assertFalse(ticked.expired());
    }

    @Test
    public void testExpiredWhenBoostIsZero() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 0.0, 0.5, "src1");
        assertTrue(window.expired());
    }
}