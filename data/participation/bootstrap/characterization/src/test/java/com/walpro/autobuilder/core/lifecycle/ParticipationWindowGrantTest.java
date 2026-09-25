package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticipationWindowGrantTest {

    @Test
    void testGrantSameRole() {
        ParticipationWindow receiver = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        ParticipationWindow other = ParticipationWindow.of("roleA", 3.0, 0.5, "src1");

        ParticipationWindow result = receiver.grant(other);

        assertEquals("roleA", result.role());
        assertEquals(3.0, result.boost());
        assertEquals("src1", result.source());
        assertEquals(0, result.age());
        assertEquals(6, result.validForCycles());
        assertEquals(0.5, result.decayEquivalent());

        assertEquals("roleA", receiver.role());
        assertEquals(1.0, receiver.boost());
        assertEquals("src1", receiver.source());
        assertEquals(0, receiver.age());
        assertEquals(2, receiver.validForCycles());
        assertEquals(0.5, receiver.decayEquivalent());

        assertEquals("roleA", other.role());
        assertEquals(3.0, other.boost());
        assertEquals("src1", other.source());
        assertEquals(0, other.age());
        assertEquals(6, other.validForCycles());
        assertEquals(0.5, other.decayEquivalent());
    }

    @Test
    void testGrantDifferentRoles() {
        ParticipationWindow receiver = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        ParticipationWindow other = ParticipationWindow.of("roleE", 2.0, 0.5, "src2");

        ParticipationWindow result = receiver.grant(other);

        assertEquals("roleA", result.role());
        assertEquals(1.0, result.boost());
        assertEquals("src1", result.source());
        assertEquals(0, result.age());
        assertEquals(2, result.validForCycles());
        assertEquals(0.5, result.decayEquivalent());
    }
}