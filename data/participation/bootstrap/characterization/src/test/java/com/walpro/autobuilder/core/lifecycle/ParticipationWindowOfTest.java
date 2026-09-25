package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParticipationWindowOfTest {

    @Test
    void of_returns_window_with_supplied_role_boost_and_source() {
        ParticipationWindow window = ParticipationWindow.of("moderator", 1.5, 0.1, "policy");

        assertNotNull(window);
        assertEquals("moderator", window.role());
        assertEquals(1.5, window.boost(), 1e-9);
        assertEquals("policy", window.source());
    }

    @Test
    void of_returns_window_with_zero_boost_and_decay() {
        ParticipationWindow window = ParticipationWindow.of("observer", 0.0, 0.0, "default");

        assertNotNull(window);
        assertEquals("observer", window.role());
        assertEquals(0.0, window.boost(), 1e-9);
        assertEquals("default", window.source());
    }

    @Test
    void of_returns_window_with_negative_boost() {
        ParticipationWindow window = ParticipationWindow.of("penalty", -0.5, 0.2, "sanction");

        assertNotNull(window);
        assertEquals("penalty", window.role());
        assertEquals(-0.5, window.boost(), 1e-9);
        assertEquals("sanction", window.source());
    }

    @Test
    void of_returns_window_with_large_decay_equivalent() {
        ParticipationWindow window = ParticipationWindow.of("heavy", 2.0, 100.0, "stress");

        assertNotNull(window);
        assertEquals("heavy", window.role());
        assertEquals(2.0, window.boost(), 1e-9);
        assertEquals("stress", window.source());
    }

    @Test
    void of_returns_distinct_instances_for_same_inputs() {
        ParticipationWindow first = ParticipationWindow.of("role", 1.0, 0.5, "src");
        ParticipationWindow second = ParticipationWindow.of("role", 1.0, 0.5, "src");

        assertNotSame(first, second);
        assertEquals(first, second);
    }

    @Test
    void of_returns_window_that_is_not_expired_initially() {
        ParticipationWindow window = ParticipationWindow.of("fresh", 1.0, 0.1, "init");

        assertFalse(window.expired());
    }
}