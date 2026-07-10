package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveExecutionEngineOrderingTest {

    @Test
    void runCycle_ordersAgentsBySignalFitness_evolutionPlanner() {
        List<String> executionOrder = new ArrayList<>();
        AdaptiveExecutionEngine engine = newEngine(executionOrder);

        CycleContextData ctx = CycleContextData.builder().build()
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

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("planner", "evolution");
    }

    private static AdaptiveExecutionEngine newEngine(List<String> executionOrder) {
        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

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
            Set<SignalType> supportedSignals,
            List<String> executionOrder
    ) {
        return new ExecutionAgent() {
            @Override public String name() { return name; }
            @Override public String role() { return name; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add(name);
                return ctx;
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return supportedSignals;
            }
        };
    }

    @Test
    void runCycle_whenAgentMerges_cycleEndsAndLaterRolesDoNotExecute() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent evolutionAgent = new ExecutionAgent() {
            @Override public String name() { return "evolution"; }
            @Override public String role() { return "evolution"; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add("evolution");
                return ctx.withMerged(true); // shouldEndCycle => true
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVOLUTION);
            }
        };

        ExecutionAgent plannerAgent = new ExecutionAgent() {
            @Override public String name() { return "planner"; }
            @Override public String role() { return "planner"; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add("planner");
                return ctx;
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_PLANNER);
            }
        };

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer
        );

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                // Ensure evolution runs first
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.9, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("evolution");
    }

    /** T4-AI-3: a halt signal raised DURING the cycle (PlannerAgent's empty-backlog-injection-
     *  limit raises GOAL_EXHAUSTED+SYSTEM_PAUSED mid-cycle) ends the cycle immediately — the
     *  remaining agents previously kept executing against a tree the producer had declared
     *  halted, honoring the halt one cycle late. */
    @Test
    void runCycle_haltSignalRaisedMidCycle_endsTheCycleBeforeRemainingAgents() {
        List<String> executionOrder = new ArrayList<>();

        // planner raises GOAL_EXHAUSTED during its own execution
        ExecutionAgent haltingPlanner = new ExecutionAgent() {
            @Override public String name() { return "halting-planner"; }
            @Override public String role() { return "planner"; }
            @Override public Set<com.walpro.autobuilder.core.lifecycle.SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_PLANNER);
            }
            @Override public double priority(CycleContextData ctx) { return 0.0; }
            @Override public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add("planner");
                return ctx.withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "empty-backlog-injection-limit"));
            }
        };
        ExecutionAgent evolutionAgent = newExecutionAgent("evolution", Set.of(BasicSignals.ROLE_EVOLUTION), executionOrder);

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);
        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0),
                        com.walpro.autobuilder.core.deliberation.DeliberationResult.noop("test")));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(haltingPlanner, evolutionAgent),
                deliberationService,
                new DeliberationExecutionGate(),
                executionEligibilityExplainer
        );

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.5, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder)
                .as("evolution must not run after the planner declared the halt")
                .containsExactly("planner");
    }
}
