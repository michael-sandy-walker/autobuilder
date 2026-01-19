package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationAgentTest {

    @Mock
    private EvaluationLLMAdapter evaluationAdapter;

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ScoringService scoringService;

    @Mock
    private EvaluationInterpreter evaluationInterpreter;


    // --- helpers -------------------------------------------------------------

    private EvaluationAgent newAgent() {
        return new EvaluationAgent(evaluationAdapter, scoringService, evaluationInterpreter);
    }

    private CycleContextData baseContext() {
        // minimal context with non-null diff & previousOutput to avoid NPEs
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
    void evaluate_prefersCurrentSubGoalIdWhenPresent() {
        EvaluationAgent agent = newAgent();

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .build();

        // Evaluation outcome + interpretation so evaluate(...) can complete
        when(evaluationAdapter.evaluate(any(CycleContextData.class)))
                .thenReturn(new EvaluationOutcome(
                        0.9,
                        MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.PROGRESSING,
                        "ok",
                        null
                ));
        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
//        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue())
                .as("currentSubGoalId should be preferred when present")
                .isEqualTo("sub-1");
    }

    @Test
    void evaluate_fallsBackToCurrentGoalIdWhenNoSubGoal() {
        EvaluationAgent agent = newAgent();

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId(null)
                .build();

        when(evaluationAdapter.evaluate(any(CycleContextData.class)))
                .thenReturn(new EvaluationOutcome(
                        0.8,
                        MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.PROGRESSING,
                        "ok",
                        null
                ));
        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
//        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue())
                .as("currentGoalId should be used when currentSubGoalId is null")
                .isEqualTo("goal-1");
    }

    @Test
    void evaluate_allowsNullGoalIdWhenBothNull() {
        EvaluationAgent agent = newAgent();

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId(null)
                .currentSubGoalId(null)
                .build();

        when(evaluationAdapter.evaluate(any(CycleContextData.class)))
                .thenReturn(new EvaluationOutcome(
                        0.5,
                        MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.NOT_EVALUATED,
                        "ok",
                        null
                ));
        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(false);
//        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//        when(interpretation.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue())
                .as("goalId may be null if neither currentGoalId nor currentSubGoalId is set")
                .isNull();
    }

    @Test
    void evaluate_propagatesOutcomeAndStoresInterpretation_only() {
        EvaluationAgent agent = newAgent();

        String goalId = "goal-123";

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalId(goalId)
                .projectDiff(new ProjectDiffResult(
                        null,
                        Map.of("src/Main.java", "changed"),
                        Set.of()
                ))
                .previousOutput(Map.of())
                .build();

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );
        when(evaluationAdapter.evaluate(any(CycleContextData.class)))
                .thenReturn(outcome);

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
//        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(evaluationInterpreter.interpret(any(EvaluationOutcome.class), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evaluationOutcome())
                .as("Evaluation outcome should be attached to the context")
                .isEqualTo(outcome);

        assertThat(result.evaluationInterpretation())
                .as("Interpretation result should be stored on the context")
                .isEqualTo(interpretation);

        // No score history recording here anymore (moved to CycleExecutor/CycleScoreRecorder).
        // No mutationState/goalProgress finalization here anymore (CycleExecutor/meta owns that).
    }
}
