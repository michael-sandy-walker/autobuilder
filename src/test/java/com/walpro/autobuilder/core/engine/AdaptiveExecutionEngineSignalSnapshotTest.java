package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-BO: the engine must emit a raw signal-presence snapshot ({@code signalSnapshot}) at the
 * SAME point it already emits {@code agentFitnessRanked} — before any agent in the fitness pass
 * executes. This is the causality-preserving point the offline correlation analyser depends on
 * (see project_t4bo_design_note.md): a signal that changes as a side effect of an agent running
 * must never be misread as having "caused" that agent's selection.
 */
class AdaptiveExecutionEngineSignalSnapshotTest {

    @Test
    void runCycle_emitsSignalSnapshot_beforeAnyAgentExecutes_withThePreExecutionSignalMap() {
        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);
        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));

        ExecutionAgent evolutionAgent = new ExecutionAgent() {
            @Override public String name() { return "evolution-agent"; }
            @Override public String role() { return "evolution"; }
            @Override public java.util.Set<com.walpro.autobuilder.core.lifecycle.SignalType> getSupportedSignals() {
                return java.util.Set.of(BasicSignals.ROLE_EVOLUTION);
            }
            @Override public double priority(CycleContextData ctx) { return 0.0; }
            @Override public CycleContextData execute(CycleContextData ctx) {
                // If this ran BEFORE the snapshot was taken, the snapshot would see this
                // extra signal too — assert it does not.
                return ctx.grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("merge", 1.0, 0.1, "side-effect"));
            }
        };

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionAgent),
                deliberationService,
                new DeliberationExecutionGate(),
                executionEligibilityExplainer
        );
        engine.setBehaviourLogger(behaviourLogger);

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("evolution", 1.0, 0.1, "test"));

        engine.runCycle(ctx);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(behaviourLogger).signalSnapshot(any(), captor.capture());

        Map<String, Double> snapshot = captor.getValue();
        assertThat(snapshot).containsKey("ROLE_EVOLUTION");
        assertThat(snapshot)
                .as("the snapshot must be taken BEFORE the agent runs — its side-effect signal must not appear")
                .doesNotContainKey("ROLE_MERGE");
    }
}
