package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationAgentTest {

    @Mock private EvaluationLLMAdapter evaluationAdapter;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) private ScoringService scoringService;
    @Mock private EvaluationInterpreter evaluationInterpreter;
    @Mock private ProblemDetector problemDetector;

    @Mock private HeuristicNegotiator heuristicNegotiator;
    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private EvaluationActionRegistrar evaluationActionRegistrar;

    // --- helpers -------------------------------------------------------------

    private EvaluationAgent newAgent(List<EvaluationHeuristic> heuristics) {
        return new EvaluationAgent(
                evaluationAdapter,
                scoringService,
                evaluationInterpreter,
                problemDetector,
                heuristicNegotiator,
                heuristics,
                graphFacade,
                evaluationActionRegistrar
        );
    }

    private CycleContextData baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .projectDiff(new ProjectDiffResult(
                        null,
                        Map.of("src/Main.java", "changed"),
                        Set.of()
                ))
                .previousOutput(Map.of())
                .build();
    }

    @Test
    void execute_prefersCurrentSubGoalIdWhenPresent() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "ok",
                null
        );

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(raw);
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());

        // negotiation returns final outcome (often equals raw)
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(raw, List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue()).isEqualTo("sub-1");
    }

    @Test
    void execute_fallsBackToCurrentGoalIdWhenNoSubGoal() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId(null)
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "ok",
                null
        );

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(raw);
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(raw, List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue()).isEqualTo("goal-1");
    }

    @Test
    void execute_propagatesFinalOutcome_storesInterpretation_andCallsGraphAndRegistrar() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-123")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );

        EvaluationOutcome negotiated = new EvaluationOutcome(
                0.88,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Final",
                null
        );

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(raw);
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(negotiated, List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(eq(negotiated), anyDouble(), anyBoolean())).thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evaluationOutcome()).isEqualTo(negotiated);
        assertThat(result.evaluationInterpretation()).isEqualTo(interpretation);

        verify(graphFacade).recordEvaluation(eq(result), eq(negotiated));
        verify(evaluationActionRegistrar).registerEvaluation(anyString(), eq(result), eq(negotiated));
    }
}