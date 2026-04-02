package com.walpro.autobuilder.agent.interpretation;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultInterpretationAgentTest {

    @Mock
    private AgentGraphService agentGraphService;

    @Mock
    private InterpretationLLMAdapter llmAdapter;

    @InjectMocks
    private DefaultInterpretationAgent agent;

    @Test
    void interpret_nullContext_returnsNoop() {
        InterpretationOutcome result = agent.interpret(null);

        assertThat(result.status()).isEqualTo("NOOP");
        verify(agentGraphService, never()).findRecentArtifactsForGoal(any(), any(), eq(12));
        verify(llmAdapter, never()).interpret(any(), any());
    }

    @Test
    void interpret_missingGoalId_returnsNoop() {
        CycleContextData nullGoal = CycleContextData.builder()
                .currentGoalId(null).signals(new java.util.LinkedHashMap<>()).build();
        CycleContextData blankGoal = CycleContextData.builder()
                .currentGoalId("   ").signals(new java.util.LinkedHashMap<>()).build();

        assertThat(agent.interpret(nullGoal).status()).isEqualTo("NOOP");
        assertThat(agent.interpret(blankGoal).status()).isEqualTo("NOOP");
        verify(llmAdapter, never()).interpret(any(), any());
    }

    @Test
    void interpret_idleContext_noEvolutionNoSignals_returnsNoop() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        InterpretationOutcome result = agent.interpret(ctx);

        assertThat(result.status()).isEqualTo("NOOP");
        verify(agentGraphService, never()).findRecentArtifactsForGoal(any(), any(), eq(12));
        verify(llmAdapter, never()).interpret(any(), any());
    }

    @Test
    void interpret_validContext_fetchesArtifactsWithExpectedTypesAndDelegates() {
        EvolutionOutcome evo = new EvolutionOutcome(List.of(), List.of(), "summary", "raw", false);
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .evolutionOutcome(evo)
                .signals(new java.util.LinkedHashMap<>())
                .build();

        List<ArtifactEntity> artifacts = List.of(
                new ArtifactEntity("a1", "planner_outcome", null, "planner content", null)
        );
        InterpretationOutcome expected = new InterpretationOutcome("APPLIED", "Pattern found.", "", List.of());

        when(agentGraphService.findRecentArtifactsForGoal(eq("goal-1"), any(), eq(12)))
                .thenReturn(artifacts);
        when(llmAdapter.interpret(any(), any())).thenReturn(expected);

        InterpretationOutcome result = agent.interpret(ctx);

        assertThat(result).isSameAs(expected);

        var typeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(agentGraphService).findRecentArtifactsForGoal(eq("goal-1"), typeCaptor.capture(), eq(12));
        assertThat(typeCaptor.getValue()).contains(
                "planner_outcome", "evaluation_payload", "merge_payload",
                "deliberation_summary", "interpretation_summary"
        );
        verify(llmAdapter).interpret(any(), any());
    }

    @Test
    void interpret_sparseHistory_stripsPatternBasedSignals() {
        EvolutionOutcome evo = new EvolutionOutcome(List.of(), List.of(), "summary", "raw", false);
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .evolutionOutcome(evo)
                .signals(new java.util.LinkedHashMap<>())
                .build();

        // Only 2 interpretation_summary artifacts — below the threshold of 3
        List<ArtifactEntity> artifacts = List.of(
                new ArtifactEntity("a1", "interpretation_summary", null, "summary1", null),
                new ArtifactEntity("a2", "interpretation_summary", null, "summary2", null),
                new ArtifactEntity("a3", "evaluation_payload", null, "eval", null)
        );

        Signal replanSignal = Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.3, "interpretation");
        Signal lowConfSignal = Signal.of(BasicSignals.LOW_CONFIDENCE, 0.5, 0.2, "interpretation");
        Signal stagnationSignal = Signal.of(BasicSignals.STAGNATION, 0.4, 0.1, "interpretation");
        InterpretationOutcome llmOutcome = new InterpretationOutcome(
                "APPLIED", "Pattern detected.", "", List.of(replanSignal, lowConfSignal, stagnationSignal));

        when(agentGraphService.findRecentArtifactsForGoal(eq("goal-1"), any(), eq(12))).thenReturn(artifacts);
        when(llmAdapter.interpret(any(), any())).thenReturn(llmOutcome);

        InterpretationOutcome result = agent.interpret(ctx);

        assertThat(result.proposedSignals())
                .doesNotContain(replanSignal, lowConfSignal)
                .contains(stagnationSignal);
    }

    @Test
    void interpret_sufficientHistory_preservesAllSignals() {
        EvolutionOutcome evo = new EvolutionOutcome(List.of(), List.of(), "summary", "raw", false);
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .evolutionOutcome(evo)
                .signals(new java.util.LinkedHashMap<>())
                .build();

        // Exactly 3 interpretation_summary artifacts — at the threshold
        List<ArtifactEntity> artifacts = List.of(
                new ArtifactEntity("a1", "interpretation_summary", null, "s1", null),
                new ArtifactEntity("a2", "interpretation_summary", null, "s2", null),
                new ArtifactEntity("a3", "interpretation_summary", null, "s3", null)
        );

        Signal replanSignal = Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.3, "interpretation");
        InterpretationOutcome llmOutcome = new InterpretationOutcome(
                "APPLIED", "Pattern detected.", "", List.of(replanSignal));

        when(agentGraphService.findRecentArtifactsForGoal(eq("goal-1"), any(), eq(12))).thenReturn(artifacts);
        when(llmAdapter.interpret(any(), any())).thenReturn(llmOutcome);

        InterpretationOutcome result = agent.interpret(ctx);

        assertThat(result).isSameAs(llmOutcome);
        assertThat(result.proposedSignals()).contains(replanSignal);
    }
}
