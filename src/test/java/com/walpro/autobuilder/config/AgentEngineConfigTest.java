package com.walpro.autobuilder.config;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.coordinator.MergeCoordinator;
import com.walpro.autobuilder.core.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.execution.CoordinatorExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentEngineConfigTest {

    @Test
    void coordinators_wiresFourAgentsWithCorrectSupportedSignals() {
        AgentEngineConfig cfg = new AgentEngineConfig();

        EvaluationCoordinator eval = mock(EvaluationCoordinator.class);
        EvolutionCoordinator evo = mock(EvolutionCoordinator.class);
        PlannerCoordinator plan = mock(PlannerCoordinator.class);
        MergeCoordinator merge = mock(MergeCoordinator.class);

        List<ExecutionAgent> agents = cfg.coordinators(eval, evo, plan, merge);

        assertThat(agents).hasSize(4);
        assertThat(agents).allMatch(a -> a instanceof CoordinatorExecutionAgent);

        assertSupported(agents, "evaluation", eval, BasicSignals.EVALUATION_SIGNALS);
        assertSupported(agents, "evolution", evo, BasicSignals.EVOLUTION_SIGNALS);
        assertSupported(agents, "planner", plan, BasicSignals.PLANNER_SIGNALS);
        assertSupported(agents, "merge", merge, BasicSignals.MERGE_SIGNALS);
    }

    private static void assertSupported(
            List<ExecutionAgent> agents,
            String id,
            AgentCoordinator expectedCoordinator,
            java.util.Set<?> expectedSignals
    ) {
        CoordinatorExecutionAgent agent = (CoordinatorExecutionAgent) agents.stream()
                .filter(a -> a.name().equals(id))
                .findFirst()
                .orElseThrow();

        assertThat(agent.coordinator()).isSameAs(expectedCoordinator);
        assertThat(agent.getSupportedSignals()).isEqualTo(expectedSignals);
    }
}
