package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticipationWindowsTest {

    @Test
    void testRevokeRemovesWindow() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        ParticipationWindows withWindow = ParticipationWindows.empty().grant(window);
        assertTrue(withWindow.hasActive("roleA"));
        ParticipationWindows afterRevoke = withWindow.revoke("roleA");
        assertFalse(afterRevoke.hasActive("roleA"));
        assertEquals("ParticipationWindows[active={}]", afterRevoke.toString());
    }

    @Test
    void testGrantAddsWindow() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        ParticipationWindows result = ParticipationWindows.empty().grant(window);
        assertTrue(result.hasActive("roleA"));
        assertEquals("roleA", result.get("roleA").role());
        assertEquals(1.0, result.get("roleA").boost());
        assertEquals("src1", result.get("roleA").source());
        assertEquals(0, result.get("roleA").age());
        assertEquals(2, result.get("roleA").validForCycles());
        assertEquals(0.5, result.get("roleA").decayEquivalent());
    }

    @Test
    void testBoostReturnsBoostValue() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 2.5, 0.5, "src1");
        ParticipationWindows withWindow = ParticipationWindows.empty().grant(window);
        assertEquals(2.5, withWindow.boost("roleA"));
    }

    @Test
    void testTickAgesWindow() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 1.0, 0.5, "src1");
        ParticipationWindows withWindow = ParticipationWindows.empty().grant(window);
        ParticipationWindows.TickResult result = withWindow.tick();
        assertEquals("TickResult[windows=ParticipationWindows[active={roleA=ParticipationWindow[role=roleA, boost=1.0, source=src1, age=1, validForCycles=2, decayEquivalent=0.5]}], expired=[]]", result.toString());
    }

    @Test
    void empty_returnsEmptyWindows() {
        ParticipationWindows windows = ParticipationWindows.empty();
        assertNotNull(windows);
        assertTrue(windows.isEmpty());
        assertFalse(windows.hasActive("any-role"));
        assertNull(windows.get("any-role"));
    }

    @Test
    void grant_addsWindowAndHasActiveReturnsTrue() {
        ParticipationWindow window = ParticipationWindow.of("role-a", 3.0, 0.1, "src-a");
        ParticipationWindows windows = ParticipationWindows.empty().grant(window);
        assertTrue(windows.hasActive("role-a"));
        assertEquals(window, windows.get("role-a"));
        assertEquals(3.0, windows.boost("role-a"));
    }

    @Test
    void hasActive_returnsFalseForUnknownRole() {
        ParticipationWindows windows = ParticipationWindows.empty();
        assertFalse(windows.hasActive("unknown"));
    }

    @Test
    void get_returnsNullForUnknownRole() {
        ParticipationWindows windows = ParticipationWindows.empty();
        assertNull(windows.get("unknown"));
    }

    @Test
    void shrink_returnsNewInstanceWithReducedBoost() {
        ParticipationWindow window = ParticipationWindow.of("role-c", 3.0, 0.1, "shrink-src");
        ParticipationWindows windows = ParticipationWindows.empty().grant(window);
        ParticipationWindows shrunk = windows.shrink("role-c");
        assertNotSame(windows, shrunk);
        assertTrue(shrunk.hasActive("role-c"));
        assertNotNull(shrunk.get("role-c"));
        // Observed: shrink reduces boost from 3.0 to 2.7 (3.0 * 0.9)
        assertEquals(2.7, shrunk.get("role-c").boost(), 1e-9);
        assertEquals("shrink-src", shrunk.get("role-c").source());
        assertEquals(0, shrunk.get("role-c").age());
        assertEquals(27, shrunk.get("role-c").validForCycles());
        assertEquals(0.1, shrunk.get("role-c").decayEquivalent(), 1e-9);
    }

    @Test
    void shrink_onUnknownRole_returnsSameInstance() {
        ParticipationWindows windows = ParticipationWindows.empty();
        ParticipationWindows result = windows.shrink("unknown");
        assertSame(windows, result);
    }
}
