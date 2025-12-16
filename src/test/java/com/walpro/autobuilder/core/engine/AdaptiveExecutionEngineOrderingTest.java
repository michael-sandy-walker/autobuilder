package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.execution.CoordinatorExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveExecutionEngineOrderingTest {

    /**
     * Higher ROLE_EVOLUTION signal strength should cause the evolution coordinator
     * to execute before the planner coordinator, even though the strategy proposes
     * them in the opposite order.
     */
    @Test
    void runCycle_ordersCoordinatorsBySignalFitness_notStrategyOrder_evolutionPlanner() {
        // side-effect log to capture execution order
        List<String> executionOrder = new ArrayList<>();

        AdaptiveExecutionEngine engine = getAdaptiveExecutionEngine(executionOrder);

        // Context with stronger evolution signal than planner signal
        CycleContextData ctx = CycleContextData.builder().build();
        ctx = ctx
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"));

        // Act
        engine.runCycle(ctx);

        // Assert: despite strategy proposing [planner, evolution],
        // the actual execution order must be [evolution, planner].
        assertThat(executionOrder)
                .containsExactly("evolution", "planner");
    }

    @Test
    void runCycle_ordersCoordinatorsBySignalFitness_notStrategyOrder_plannerEvolution() {
        List<String> executionOrder = new ArrayList<>();

        AdaptiveExecutionEngine engine = getAdaptiveExecutionEngine(executionOrder);

        // Context with stronger planner signal than evolution signal
        CycleContextData ctx = CycleContextData.builder().build();
        ctx = ctx
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"));

        // Act
        engine.runCycle(ctx);

        // the actual execution order must be [planner, evolution].
        assertThat(executionOrder)
                .containsExactly("planner", "evolution");
    }

    private static AdaptiveExecutionEngine getAdaptiveExecutionEngine(List<String> executionOrder) {
        AgentCoordinator evolution = new AgentCoordinator() {
            @Override
            public String role() {
                return "evolution";
            }

            @Override
            public boolean canExecute(CycleContextData context) {
                return true;
            }

            @Override
            public double priority(CycleContextData ctx) {
                return 0.0; // keep neutral so signal dominates
            }

            @Override
            public CycleContextData execute(CycleContextData context) {
                executionOrder.add("evolution");
                // no changes -> no progress, so we get exactly one pass
                return context;
            }
        };

        AgentCoordinator planner = new AgentCoordinator() {
            @Override
            public String role() {
                return "planner";
            }

            @Override
            public boolean canExecute(CycleContextData context) {
                return true;
            }

            @Override
            public double priority(CycleContextData ctx) {
                return 0.0; // keep neutral so signal dominates
            }

            @Override
            public CycleContextData execute(CycleContextData context) {
                executionOrder.add("planner");
                return context;
            }
        };

        CoordinatorExecutionAgent evolutionAgent = new CoordinatorExecutionAgent("evolution", evolution, Set.of(BasicSignals.ROLE_EVOLUTION));
        CoordinatorExecutionAgent plannerAgent = new CoordinatorExecutionAgent("planner", planner, Set.of(BasicSignals.ROLE_PLANNER));

        CoordinationStrategy strategy = (coordinators, context) -> {
            return List.of(evolutionAgent, plannerAgent);
        };

        return new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                strategy
        );
    }
}
