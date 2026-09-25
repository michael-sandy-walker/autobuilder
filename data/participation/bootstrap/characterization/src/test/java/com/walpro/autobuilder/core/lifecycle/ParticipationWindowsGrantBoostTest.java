package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ParticipationWindowsGrantBoostTest {

    @Test
    void testGrantAddsWindowAndBoostReturnsValue() {
        ParticipationWindow window = ParticipationWindow.of("roleA", 2.5, 0.5, "src1");
        ParticipationWindows windows = ParticipationWindows.empty();
        ParticipationWindows granted = windows.grant(window);
        
        assertNotNull(granted);
        assertEquals(true, granted.hasActive("roleA"));
        assertEquals(2.5, granted.boost("roleA"));
    }

    @Test
    void testBoostReturnsBoostValueForRole() {
        ParticipationWindow window = ParticipationWindow.of("roleB", 3.0, 0.5, "src2");
        ParticipationWindows windows = ParticipationWindows.empty().grant(window);
        
        assertEquals(3.0, windows.boost("roleB"));
    }
}