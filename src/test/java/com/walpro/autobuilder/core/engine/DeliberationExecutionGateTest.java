package com.walpro.autobuilder.core.engine;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.deliberation.DeliberationMode;

import static org.assertj.core.api.Assertions.assertThat;

class DeliberationExecutionGateTest {

    private final DeliberationExecutionGate gate = new DeliberationExecutionGate();

    @Test
    void replan_allowsEvolution() {
        assertThat(gate.allows(DeliberationMode.REPLAN, "evolution")).isTrue();
    }

    @Test
    void replan_allowsPlannerAndAnalysis() {
        assertThat(gate.allows(DeliberationMode.REPLAN, "planner")).isTrue();
        assertThat(gate.allows(DeliberationMode.REPLAN, "analysis")).isTrue();
    }

    @Test
    void replan_doesNotAllowEvaluationOrMerge() {
        assertThat(gate.allows(DeliberationMode.REPLAN, "evaluation")).isFalse();
        assertThat(gate.allows(DeliberationMode.REPLAN, "merge")).isFalse();
    }

    @Test
    void pause_allowsNothing() {
        assertThat(gate.allows(DeliberationMode.PAUSE, "planner")).isFalse();
        assertThat(gate.allows(DeliberationMode.PAUSE, "evolution")).isFalse();
    }

    @Test
    void noop_allowsAllRoles() {
        for (String role : new String[]{"planner", "evolution", "evaluation", "merge", "analysis"}) {
            assertThat(gate.allows(DeliberationMode.UNDIRECTED, role)).isTrue();
        }
    }

    @Test
    void null_mode_allowsNothing() {
        assertThat(gate.allows(null, "evolution")).isFalse();
    }

    @Test
    void null_role_allowsNothing() {
        assertThat(gate.allows(DeliberationMode.UNDIRECTED, null)).isFalse();
    }
}
