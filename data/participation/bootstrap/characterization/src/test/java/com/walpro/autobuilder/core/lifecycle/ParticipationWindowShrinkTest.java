package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParticipationWindowShrinkTest {

    @Test
    void testShrinkReturnsNewInstanceWithReducedBoost() {
        ParticipationWindow original = ParticipationWindow.of("roleA", 2.0, 0.5, "src1");
        ParticipationWindow shrunk = original.shrink();

        assertEquals("roleA", shrunk.role());
        assertEquals(1.8, shrunk.boost());
        assertEquals("src1", shrunk.source());
        assertEquals(0, shrunk.age());
        assertEquals(4, shrunk.validForCycles());
        assertEquals(0.5, shrunk.decayEquivalent());

        assertEquals("roleA", original.role());
        assertEquals(2.0, original.boost());
        assertEquals("src1", original.source());
        assertEquals(0, original.age());
        assertEquals(4, original.validForCycles());
        assertEquals(0.5, original.decayEquivalent());
    }

    @Test
    void testShrinkImmutability() {
        ParticipationWindow original = ParticipationWindow.of("roleB", 3.0, 0.5, "src2");
        ParticipationWindow shrunk = original.shrink();

        assertNotSame(original, shrunk);
        assertEquals(3.0, original.boost());
    }
}