package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParticipationWindowTest {

    @Test
    void tickReturnsNewInstanceWithAgedState() {
        ParticipationWindow original = ParticipationWindow.of("role-a", 0.8, 0.1, "source-a");
        ParticipationWindow ticked = original.tick();

        assertNotSame(original, ticked);
        assertEquals(original.expired(), ticked.expired());
    }

    @Test
    void tickOnExpiredWindowReturnsNewInstanceStillExpired() {
        ParticipationWindow original = ParticipationWindow.of("role-b", 0.05, 0.1, "source-b");
        ParticipationWindow ticked = original.tick();

        assertNotSame(original, ticked);
        assertTrue(ticked.expired());
    }
}