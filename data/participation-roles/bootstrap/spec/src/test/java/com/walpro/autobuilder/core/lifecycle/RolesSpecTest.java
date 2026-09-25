package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The operator's specification of two new capabilities. These tests fail until the code provides them. */
class RolesSpecTest {

    private static ParticipationWindow w(String role, double boost) {
        return ParticipationWindow.of(role, boost, 0.1, "spec");
    }

    @Test
    void rolesAreTheActiveRolesInTheOrderTheyWereFirstGranted() {
        ParticipationWindows ws = ParticipationWindows.empty().grant(w("planner", 1.0)).grant(w("merge", 0.5))
                .grant(w("planner", 0.5));
        assertEquals(List.of("planner", "merge"), ws.roles());
    }

    @Test
    void rolesOfNoWindowsIsEmpty_andARevokedRoleIsGone() {
        assertEquals(List.of(), ParticipationWindows.empty().roles());
        ParticipationWindows ws = ParticipationWindows.empty().grant(w("a", 1.0)).grant(w("b", 1.0)).revoke("a");
        assertEquals(List.of("b"), ws.roles());
    }

    @Test
    void rolesCannotBeModifiedThroughTheReturnedList() {
        List<String> roles = ParticipationWindows.empty().grant(w("a", 1.0)).roles();
        assertThrows(UnsupportedOperationException.class, () -> roles.add("b"));
    }

    @Test
    void strongestIsTheWindowWithTheHighestBoost() {
        ParticipationWindows ws = ParticipationWindows.empty().grant(w("a", 0.5)).grant(w("b", 2.0)).grant(w("c", 1.0));
        assertEquals("b", ws.strongest().map(ParticipationWindow::role).orElse(null));
    }

    @Test
    void strongestBreaksATieByTheEarliestGrantedRole() {
        ParticipationWindows ws = ParticipationWindows.empty().grant(w("first", 1.0)).grant(w("second", 1.0));
        assertEquals("first", ws.strongest().map(ParticipationWindow::role).orElse(null));
    }

    @Test
    void strongestOfNoWindowsIsEmpty() {
        assertEquals(Optional.empty(), ParticipationWindows.empty().strongest());
    }
}
