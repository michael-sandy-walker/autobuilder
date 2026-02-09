package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveExecutionEngineOrderingTest {

    /**
     * Higher ROLE_EVOLUTION signal strength should cause the evolution agent
     * to execute before the planner agent.
     */
    @Test
    void runCycle_ordersAgentsBySignalFitness_evolutionPlanner() {
        List<String> executionOrder = new ArrayList<>();
        AdaptiveExecutionEngine engine = newEngine(executionOrder);

        CycleContextData ctx = CycleContextData.builder().build();
        ctx = ctx
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("evolution", "planner");
    }

    @Test
    void runCycle_ordersAgentsBySignalFitness_plannerEvolution() {
        List<String> executionOrder = new ArrayList<>();
        AdaptiveExecutionEngine engine = newEngine(executionOrder);

        CycleContextData ctx = CycleContextData.builder().build();
        ctx = ctx
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"));

        engine.explorationJitter = 0.0;
        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("planner", "evolution");
    }

    private static AdaptiveExecutionEngine newEngine(List<String> executionOrder) {
        // Evolution agent: declares it supports ROLE_EVOLUTION and appends to order when executed
        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

        // Planner agent: declares it supports ROLE_PLANNER and appends to order when executed
        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(
                        inv.getArgument(0),
                        DeliberationResult.noop("test")
                ));

        return new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer
        );
    }

    private static ExecutionAgent newExecutionAgent(
            String name,
            Set<com.walpro.autobuilder.core.lifecycle.SignalType> supportedSignals,
            List<String> executionOrder
    ) {
        return new ExecutionAgent() {
            @Override public String name() { return name; }
            @Override public String role() { return name; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add(name);
                // no changes -> deterministic single pass ordering
                return ctx;
            }

            @Override
            public Set<com.walpro.autobuilder.core.lifecycle.SignalType> getSupportedSignals() {
                return supportedSignals;
            }
        };
    }
}
