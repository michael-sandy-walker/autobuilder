package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdaptiveExecutionEngineTest {

    private final DeliberationService deliberationService = mock(DeliberationService.class);
    private final ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
    private final DeliberationExecutionGate deliberationExecutionGate = new DeliberationExecutionGate();

    private AdaptiveExecutionEngine newEngine(List<ExecutionAgent> agents) {
        return new AdaptiveExecutionEngine(
                agents,
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer
        );
    }

    @Test
    void runCycle_executesOnlyAgentsThatCanExecute() {
        // Context: enough state so your engine/fitness logic won’t NPE.
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.5, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.5, 0.05, "test"));

        // deliberation is deterministic for test
        DeliberationResult deliberationResult = DeliberationResult.noop("test");
        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), deliberationResult));

        // Two agents: one runs, one does not
        PlannerAgent runnable = mock(PlannerAgent.class);
        when(runnable.name()).thenReturn("planner");
        when(runnable.role()).thenReturn("planner");
        when(executionEligibilityExplainer.canExecute(eq("planner"), any())).thenReturn(true);
        when(runnable.execute(any())).thenAnswer(inv -> inv.<CycleContextData>getArgument(0)); // no-op

        EvolutionAgent blocked = mock(EvolutionAgent.class);
        when(blocked.name()).thenReturn("evolution");
        when(blocked.role()).thenReturn("evolution");
        when(executionEligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(false);

        AdaptiveExecutionEngine engine = newEngine(List.of(runnable, blocked));

        CycleContextData out = engine.runCycle(ctx);

        assertThat(out).isNotNull();
        assertThat(out.deliberationResult()).isEqualTo(deliberationResult);

        verify(runnable, atLeastOnce()).execute(any());
        verify(blocked, never()).execute(any());
    }
}
