package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.execution.CoordinatorExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdaptiveExecutionEngineTest {

    private AdaptiveExecutionEngine newEngine() {
        CoordinationStrategy strategy = mock(CoordinationStrategy.class);
        // Keep it neutral: proposed order = given order
        when(strategy.determineExecutionOrder(anyList(), any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // We don't execute coordinators in these tests
        return new AdaptiveExecutionEngine(List.of(), strategy);
    }

    private boolean invokeHasProgressed(AdaptiveExecutionEngine engine,
                                        CycleContextData before,
                                        CycleContextData after) throws Exception {
        Method m = AdaptiveExecutionEngine.class
                .getDeclaredMethod("hasProgressed", CycleContextData.class, CycleContextData.class);
        m.setAccessible(true);
        return (boolean) m.invoke(engine, before, after);
    }

    @Test
    void hasProgressedFalseWhenSignalsIdentical() throws Exception {
        AdaptiveExecutionEngine engine = newEngine();

        CycleContextData before = mock(CycleContextData.class);
        CycleContextData after = mock(CycleContextData.class);

        SignalType type = SignalType.of("EVALUATION");
        Signal signal = new Signal(type, 0.5, 0.1, 3, "test", Collections.emptyMap());
        Map<SignalType, Signal> map = Collections.singletonMap(type, signal);

        when(before.signals()).thenReturn(map);
        when(after.signals()).thenReturn(map);

        boolean progressed = invokeHasProgressed(engine, before, after);

        assertFalse(progressed, "Identical signal map should not be considered progress");
    }

    @Test
    void hasProgressedTrueWhenSignalStrengthChanges() throws Exception {
        AdaptiveExecutionEngine engine = newEngine();

        CycleContextData before = mock(CycleContextData.class);
        CycleContextData after = mock(CycleContextData.class);

        SignalType type = SignalType.of("EVALUATION");
        Signal beforeSignal = new Signal(type, 0.5, 0.1, 0, "test", Collections.emptyMap());
        Signal afterSignal  = new Signal(type, 0.7, 0.1, 0, "test", Collections.emptyMap());

        when(before.signals()).thenReturn(Collections.singletonMap(type, beforeSignal));
        when(after.signals()).thenReturn(Collections.singletonMap(type, afterSignal));

        boolean progressed = invokeHasProgressed(engine, before, after);

        assertTrue(progressed, "Changed signal strength should be considered progress");
    }

    @Test
    void hasProgressedTrueWhenSignalSetDiffers() throws Exception {
        AdaptiveExecutionEngine engine = newEngine();

        CycleContextData before = mock(CycleContextData.class);
        CycleContextData after = mock(CycleContextData.class);

        SignalType evalType = SignalType.of("EVALUATION");
        SignalType evoType  = SignalType.of("EVOLUTION");

        Map<SignalType, Signal> beforeMap = Collections.singletonMap(
                evalType,
                new Signal(evalType, 0.5, 0.1, 0, "test", Collections.emptyMap())
        );
        Map<SignalType, Signal> afterMap = Collections.singletonMap(
                evoType,
                new Signal(evoType, 0.5, 0.1, 0, "test", Collections.emptyMap())
        );

        when(before.signals()).thenReturn(beforeMap);
        when(after.signals()).thenReturn(afterMap);

        boolean progressed = invokeHasProgressed(engine, before, after);

        assertTrue(progressed, "Different signal key set should be considered progress");
    }

    @Test
    void runCycle_executesOnlyCoordinatorsThatCanExecute() {
        // Start with a context that already has role signals so fitness() won't NPE
        CycleContextData ctx = CycleContextData.builder().build();
        ctx = ctx
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.5, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.5, 0.05, "test"));

        // Two coordinators: one executable, one not
        AgentCoordinator planner = mock(AgentCoordinator.class);
        AgentCoordinator evolution = mock(AgentCoordinator.class);

        when(planner.role()).thenReturn("planner");
        when(planner.canExecute(any())).thenReturn(true);
        // No progress: execute returns the context unchanged
        when(planner.execute(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(evolution.role()).thenReturn("evolution");
        when(evolution.canExecute(any())).thenReturn(false);

        // CoordinationStrategy: keep given order, no global adaptation
        CoordinationStrategy strategy = mock(CoordinationStrategy.class);
        when(strategy.determineExecutionOrder(anyList(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0)); // identity order
        when(strategy.adaptAfterCycle(any()))
                .thenAnswer(invocation -> invocation.getArgument(0)); // no change

        CoordinatorExecutionAgent evolutionAgent = new CoordinatorExecutionAgent("evolution", evolution, BasicSignals.ALL);
        CoordinatorExecutionAgent plannerAgent = new CoordinatorExecutionAgent("planner", planner, BasicSignals.ALL);

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(plannerAgent, evolutionAgent),
                strategy
        );

        CycleContextData result = engine.runCycle(ctx);

        assertThat(result).isNotNull();

        // 1) Strategy was asked for an execution order
        verify(strategy).determineExecutionOrder(eq(List.of(plannerAgent, evolutionAgent)), any());

        // 2) Only the coordinator with canExecute == true was actually executed
        verify(planner, atLeastOnce()).execute(any());
        verify(evolution, never()).execute(any());
    }
}
