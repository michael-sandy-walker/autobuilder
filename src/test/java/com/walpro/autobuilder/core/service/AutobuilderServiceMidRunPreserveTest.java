package com.walpro.autobuilder.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T3-BY: the mid-run preserve interval. The behaviour that matters is that a run killed from
 * OUTSIDE still leaves its best state on disk — measured over 64 runs, 4 ended after their peak
 * and every one scored 0, and all four were external kills where the terminal-only hook never
 * runs. These cover the arithmetic that decides when to pay for a preserve, because the cost is
 * a real build probe per candidate and getting the gate wrong is either a build on every cycle
 * or no preserve at all.
 */
class AutobuilderServiceMidRunPreserveTest {

    private AutobuilderService serviceWithInterval(int interval) {
        // 16 collaborators, none of which this arithmetic touches.
        AutobuilderService s = new AutobuilderService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(s, "preserveIntervalCycles", interval);
        return s;
    }

    @Test
    void firesOnlyOnTheNthMergedCycle() {
        AutobuilderService s = serviceWithInterval(3);
        assertThat(s.dueForMidRunPreserve(true)).isFalse();
        assertThat(s.dueForMidRunPreserve(true)).isFalse();
        assertThat(s.dueForMidRunPreserve(true)).isTrue();
    }

    @Test
    void theIntervalIsBetweenPreservesNotSinceTheRunStarted() {
        AutobuilderService s = serviceWithInterval(2);
        assertThat(s.dueForMidRunPreserve(true)).isFalse();
        assertThat(s.dueForMidRunPreserve(true)).isTrue();
        // Counter reset: the next fire is two merges later, not immediately.
        assertThat(s.dueForMidRunPreserve(true)).isFalse();
        assertThat(s.dueForMidRunPreserve(true)).isTrue();
    }

    @Test
    void unmergedCyclesNeitherFireNorAdvanceTheCounter() {
        // An unmerged cycle produced no new state; spending a build probe on it buys nothing,
        // and letting it advance the counter would preserve a state already preserved.
        AutobuilderService s = serviceWithInterval(2);
        assertThat(s.dueForMidRunPreserve(false)).isFalse();
        assertThat(s.dueForMidRunPreserve(false)).isFalse();
        assertThat(s.dueForMidRunPreserve(false)).isFalse();
        assertThat(s.dueForMidRunPreserve(true)).isFalse();
        assertThat(s.dueForMidRunPreserve(true)).isTrue();
    }

    @Test
    void zeroRestoresTheTerminalOnlyBehaviourThatShippedBeforeThis() {
        AutobuilderService s = serviceWithInterval(0);
        for (int i = 0; i < 50; i++) {
            assertThat(s.dueForMidRunPreserve(true)).isFalse();
        }
    }

    @Test
    void aNegativeIntervalIsTreatedAsDisabledRatherThanFiringEveryCycle() {
        AutobuilderService s = serviceWithInterval(-1);
        assertThat(s.dueForMidRunPreserve(true)).isFalse();
    }

    @Test
    void anIntervalOfOnePreservesOnEveryMergedCycle() {
        AutobuilderService s = serviceWithInterval(1);
        assertThat(s.dueForMidRunPreserve(true)).isTrue();
        assertThat(s.dueForMidRunPreserve(true)).isTrue();
    }
}
