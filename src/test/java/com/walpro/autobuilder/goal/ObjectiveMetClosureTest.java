package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.stuck.Recurrence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T6-T, rule-extraction run 2026-10-18_19-33-28: the suite passed from minute 41; the criteria-gate work item was
 * refused with the same eight criteria seventeen times from minute 65 to the deadline.
 */
class ObjectiveMetClosureTest {

    private static final long MIN = 60_000L;
    private static final List<String> EIGHT = List.of(
            "EndpointSurfaceQuery no longer defines its own Endpoint record",
            "AppConfig registers SpecEndpointBehaviourQuery as a bean",
            "SpecEndpointBehaviourQuery has no Spring imports or annotations");

    private static TestRunGate gate(TestRunGate.TreeTests ran, Set<String> failing) {
        TestRunGate g = mock(TestRunGate.class);
        when(g.treeTests()).thenReturn(ran);
        when(g.treeFailing()).thenReturn(Optional.ofNullable(failing));
        return g;
    }

    @Test
    void theObjectiveIsMetOnlyWhenTheLoopsOwnTestRunRanTheSuiteOnTheTreeAndNothingFailed() {
        ObjectiveMetClosure c = new ObjectiveMetClosure(new Recurrence(0));
        assertThat(c.objectiveMet()).as("no test gate").isFalse();
        c.setTestRunGate(gate(TestRunGate.TreeTests.RAN, Set.of()));
        assertThat(c.objectiveMet()).isTrue();
        c.setTestRunGate(gate(TestRunGate.TreeTests.RAN, Set.of("QuillTest.dries")));
        assertThat(c.objectiveMet()).isFalse();
        c.setTestRunGate(gate(TestRunGate.TreeTests.NOT_COMPILING, Set.of()));
        assertThat(c.objectiveMet()).isFalse();
        c.setTestRunGate(gate(TestRunGate.TreeTests.UNKNOWN, null));
        assertThat(c.objectiveMet()).isFalse();
    }

    @Test
    void theSameCriteriaRefusedAgainAndAgain_becomeStalled_byTheRunsOwnRatios() {
        ObjectiveMetClosure c = new ObjectiveMetClosure(new Recurrence(0));
        boolean at70 = false;
        int from = -1;
        for (int minute = 65; minute <= 120; minute += 3) {
            boolean persistent = c.refused(EIGHT, minute * MIN);
            if (minute == 71) at70 = persistent;
            if (from < 0 && persistent) from = minute;
        }

        assertThat(at70).isFalse();
        assertThat(from).as("a quarter of the run since the first refusal: minute 87").isBetween(86, 90);
        assertThat(c.allStalled(EIGHT)).isTrue();
        assertThat(c.allStalled(List.of(EIGHT.get(0)))).as("a subset of what stalled").isTrue();
        assertThat(c.allStalled(List.of("SeedStatusSupport exists"))).isFalse();
    }

    @Test
    void theClosingReasonNamesTheOpenCriteria() {
        assertThat(ObjectiveMetClosure.reason(EIGHT)).startsWith("objective met by the project's own tests")
                .contains("AppConfig registers SpecEndpointBehaviourQuery as a bean");
    }
}
