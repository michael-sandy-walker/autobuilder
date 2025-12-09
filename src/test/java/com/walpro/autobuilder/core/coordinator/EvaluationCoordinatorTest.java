package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
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
class EvaluationCoordinatorTest {

    @Mock
    private EvaluationAgent evaluationAgent;

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ScoringService scoringService;

    @Mock
    private EvaluationInterpreter evaluationInterpreter;

    @Mock
    private SignalBus signalBus;

    @Mock
    private MessageBus messageBus;

    // --- helpers -------------------------------------------------------------

    private EvaluationCoordinator newCoordinator() {
        return new EvaluationCoordinator(evaluationAgent, scoringService, evaluationInterpreter, signalBus, messageBus);
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
        EvaluationCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .build();

        // Evaluation outcome + interpretation so evaluate(...) can complete
        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(new EvaluationOutcome(
                        0.9,
                        MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.PROGRESSING,
                        "ok",
                        null
                ));
        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        coordinator.evaluate(ctx);

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
        EvaluationCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId(null)
                .build();

        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(new EvaluationOutcome(
                        0.8,
                        MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.PROGRESSING,
                        "ok",
                        null
                ));
        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        coordinator.evaluate(ctx);

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
        EvaluationCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId(null)
                .currentSubGoalId(null)
                .build();

        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(new EvaluationOutcome(
                        0.5,
                        MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.NOT_EVALUATED,
                        "ok",
                        null
                ));
        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(false);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        coordinator.evaluate(ctx);

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
    void evaluate_propagatesOutcomeAndRecordsScoreHistory() {
        EvaluationCoordinator coordinator = newCoordinator();

        String goalId = "goal-123";

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId(goalId)
                .build();

        // ScoreHistoryService comes from deep stub
        ScoreHistoryService scoreHistoryService = scoringService.getScoreHistoryService();

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );
        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(outcome);

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(EvaluationOutcome.class), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        CycleContextData result = coordinator.evaluate(ctx);

        // outcome is attached to context
        assertThat(result.evaluationOutcome())
                .as("Evaluation outcome should be attached to the context")
                .isEqualTo(outcome);

        // interpretation propagated
        assertThat(result.evaluationInterpretation())
                .as("Interpretation result should be stored on the context")
                .isEqualTo(interpretation);

        assertThat(result.mutationState())
                .isEqualTo(interpretation.mutationState());
        assertThat(result.goalProgress())
                .isEqualTo(interpretation.goalProgress());

        // score history recorded
        verify(scoreHistoryService).recordGoalEvaluation(goalId, outcome.score());
        verify(scoreHistoryService).recordGoalAcceptance(goalId, outcome.score());
    }

    @Test
    void evaluate_publishesStagnationSignalWhenGoalProgressStagnating() {
        EvaluationCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext().toBuilder()
                .cycleName("cycle-1")
                .currentGoalId("goal-1")
                .build();

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.4,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.STAGNATING,
                "stuck",
                null
        );
        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(outcome);

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(false);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        coordinator.evaluate(ctx);

        ArgumentCaptor<Signal> signalCaptor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus, times(1)).publish(signalCaptor.capture());

        Signal sent = signalCaptor.getValue();
        assertThat(sent.type()).isEqualTo(BasicSignals.STAGNATION);
        assertThat(sent.meta()).containsEntry("cycleName", "cycle-1");
        assertThat(sent.meta()).containsKey("score");
        assertThat(sent.meta()).containsEntry("goalId", "goal-1");
    }

    @Test
    void evaluate_publishesInsightMessageToPlanner() {
        EvaluationCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext().toBuilder()
                .cycleName("cycle-1")
                .currentGoalId("goal-1")
                .build();

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );
        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(outcome);

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        coordinator.evaluate(ctx);

        ArgumentCaptor<AgentMessage> messageCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageBus, times(1)).publish(messageCaptor.capture());

        AgentMessage sent = messageCaptor.getValue();
        assertThat(sent.fromAgent()).isEqualTo(AgentRoles.EVALUATION);
        assertThat(sent.toAgent()).isEqualTo(AgentRoles.PLANNER);
        assertThat(sent.type()).isEqualTo(AgentMessageType.INSIGHT);

        assertThat(sent.payload())
                .containsEntry("cycleName", "cycle-1")
                .containsEntry("evaluationScore", outcome.score())
                .containsEntry("goalProgress", outcome.goalProgress());
    }
}
