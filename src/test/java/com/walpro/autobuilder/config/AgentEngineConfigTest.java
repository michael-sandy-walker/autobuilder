package com.walpro.autobuilder.config;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.SignalType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentEngineConfigTest {

    @Test
    void agents_wiresFourExecutionAgentsWithCorrectSupportedSignals() {
        AgentEngineConfig cfg = new AgentEngineConfig();

        EvaluationAgent eval = mock(EvaluationAgent.class);
        EvolutionAgent evo  = mock(EvolutionAgent.class);
        PlannerAgent plan = mock(PlannerAgent.class);
        MergeAgent merge = mock(MergeAgent.class);

        // Names are how the engine/test identifies agents
        org.mockito.Mockito.when(eval.name()).thenReturn("evaluation");
        org.mockito.Mockito.when(evo.name()).thenReturn("evolution");
        org.mockito.Mockito.when(plan.name()).thenReturn("planner");
        org.mockito.Mockito.when(merge.name()).thenReturn("merge");

        // Supported signals are the contract we care about
        org.mockito.Mockito.when(eval.getSupportedSignals()).thenReturn(cast(BasicSignals.EVALUATION_SIGNALS));
        org.mockito.Mockito.when(evo.getSupportedSignals()).thenReturn(cast(BasicSignals.EVOLUTION_SIGNALS));
        org.mockito.Mockito.when(plan.getSupportedSignals()).thenReturn(cast(BasicSignals.PLANNER_SIGNALS));
        org.mockito.Mockito.when(merge.getSupportedSignals()).thenReturn(cast(BasicSignals.MERGE_SIGNALS));

        // NOTE: adapt the method name to your config after the refactor.
        // The old one was cfg.coordinators(...). A reasonable new one is cfg.agents(...)
        List<ExecutionAgent> agents = cfg.executionAgents(eval, evo, plan, merge);

        assertThat(agents).hasSize(4);

        // No wrappers: config should return exactly those instances (if that's your desired architecture)
        assertThat(agents).containsExactlyInAnyOrder(eval, evo, plan, merge);

        assertSupported(agents, "evaluation", cast(BasicSignals.EVALUATION_SIGNALS));
        assertSupported(agents, "evolution", cast(BasicSignals.EVOLUTION_SIGNALS));
        assertSupported(agents, "planner", cast(BasicSignals.PLANNER_SIGNALS));
        assertSupported(agents, "merge", cast(BasicSignals.MERGE_SIGNALS));
    }

    private static void assertSupported(List<ExecutionAgent> agents, String id, Set<SignalType> expectedSignals) {
        ExecutionAgent agent = agents.stream()
                .filter(a -> id.equals(a.name()))
                .findFirst()
                .orElseThrow();

        assertThat(agent.getSupportedSignals()).isEqualTo(expectedSignals);
    }

    @SuppressWarnings("unchecked")
    private static Set<SignalType> cast(Set<?> set) {
        return (Set<SignalType>) set;
    }
}
