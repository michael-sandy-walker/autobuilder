package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CycleExecutor unit tests
 *
 * Notes:
 * - These tests are intentionally "interaction-heavy" because CycleExecutor is an orchestration unit
 *   (it delegates to many collaborators and mostly moves the context through a pipeline).
 * - They verify:
 *   1) pause short-circuit
 *   2) merge is executed immediately (option A) when ACCEPTED + EVOLUTION_OCCURRED
 *   3) completion uses SubGoalFulfillmentHeuristic and applies FULFILLED via GoalPlannerService
 *
 * If your CycleContextData is a record/immutable type (as it looks), mocking is usually fine here
 * because we are validating orchestration + delegation, not internal context logic.
 */
@ExtendWith(MockitoExtension.class)
class CycleExecutorTest {

    @Mock private AdaptiveExecutionEngine adaptiveExecutionEngine;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;
    @Mock private InterpretationService interpretationService;
    @Mock private CycleScoreRecorder cycleScoreRecorder;
    @Mock private GoalCompletionDecider goalCompletionDecider;
    @Mock private PatternSignalAnalyzer patternSignalAnalyzer;
    @Mock private PlanningThrashSignalAnalyzer planningThrashSignalAnalyzer;

    @InjectMocks
    private CycleExecutor sut;

    @BeforeEach
    void defaultGoalSyncStubs() {
        // CycleExecutor.syncContextFromGoals(...) uses these; provide safe defaults so tests only override what they need.
        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("goal-root");
        when(root.getDescription()).thenReturn("Root goal");

        GoalEntity sub = mock(GoalEntity.class);
//        when(sub.getId()).thenReturn("sub-1");
//        when(sub.getDescription()).thenReturn("Sub goal 1");

        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sub);

        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent-0"));
    }

    @Test
    void runCycle_whenSystemPaused_shortCircuitsAndOnlyTicksSignals() {
        CycleContextData input = mock(CycleContextData.class);
        CycleContextData afterSync = mock(CycleContextData.class);
        CycleContextData ticked = mock(CycleContextData.class);

        // syncContextFromGoals(...)
        // (We can’t easily intercept the private method; instead stub the chaining calls it makes.)
        when(input.withCurrentGoalId(anyString())).thenReturn(afterSync);
        when(afterSync.withCurrentGoalDescription(anyString())).thenReturn(afterSync);
        when(afterSync.withCurrentSubGoalId(any())).thenReturn(afterSync);
        when(afterSync.withCurrentSubGoalDescription(any())).thenReturn(afterSync);
        when(afterSync.withLastAttemptId(anyString())).thenReturn(afterSync);
        when(afterSync.withParentAttemptId(anyString())).thenReturn(afterSync);

        when(afterSync.cycleName()).thenReturn("cycle-42");
        when(afterSync.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(true);
        when(afterSync.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(ticked);

        CycleContextData out = sut.runCycle(input);

        assertThat(out).isSameAs(ticked);

        verify(goalPlannerService).setCurrentCycleContext(input);
        verify(afterSync).hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50);
        verify(afterSync).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);

        verifyNoInteractions(adaptiveExecutionEngine);
        verifyNoInteractions(patternSignalAnalyzer);
        verifyNoInteractions(planningThrashSignalAnalyzer);
        verifyNoInteractions(subGoalFulfillmentHeuristic);
        verifyNoInteractions(interpretationService);
        verifyNoInteractions(cycleScoreRecorder);
    }

    @Test
    void runCycle_whenAcceptedAndEvolutionOccurred_executesMergeImmediatelyInSameCycle() {
        CycleContextData input = mock(CycleContextData.class);
        CycleContextData afterSync = mock(CycleContextData.class);

        CycleContextData afterEngine = mock(CycleContextData.class);
        CycleContextData afterPattern = mock(CycleContextData.class);

        CycleContextData withMergeSignal = mock(CycleContextData.class);
        CycleContextData afterMerge = mock(CycleContextData.class);

        CycleContextData afterInterpret = mock(CycleContextData.class);
        CycleContextData afterTick = mock(CycleContextData.class);

        // --- syncContextFromGoals pipeline stubs ---
        when(input.withCurrentGoalId(anyString())).thenReturn(afterSync);
        when(afterSync.withCurrentGoalDescription(anyString())).thenReturn(afterSync);
        when(afterSync.withCurrentSubGoalId(any())).thenReturn(afterSync);
        when(afterSync.withCurrentSubGoalDescription(any())).thenReturn(afterSync);
        when(afterSync.withLastAttemptId(anyString())).thenReturn(afterSync);
        when(afterSync.withParentAttemptId(anyString())).thenReturn(afterSync);

        when(afterSync.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);

        // --- engine ---
        when(adaptiveExecutionEngine.runCycle(afterSync)).thenReturn(afterEngine);

        // ensure evaluationInterpretation is absent => applyEvaluationInterpretationIfPresent no-op
        when(afterEngine.evaluationInterpretation()).thenReturn(null);

        // --- pattern analyzer ---
        when(patternSignalAnalyzer.analyze(afterEngine)).thenReturn(afterPattern);
        when(planningThrashSignalAnalyzer.analyze(afterPattern)).thenReturn(afterPattern);

        // --- merge gating (this is the meat) ---
        when(afterPattern.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterPattern.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(true);
        when(afterPattern.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(false);

        // adding the merge signal
        when(afterPattern.withSignal(any(Signal.class))).thenReturn(withMergeSignal);

        // execution happens immediately
        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), same(withMergeSignal))).thenReturn(afterMerge);

        // After merge, keep the rest of the pipeline minimal:
        // no evaluation progress application
        when(afterMerge.goalProgress()).thenReturn(null);
        when(afterMerge.currentSubGoalId()).thenReturn("sub-1");

        // completion: heuristic says not fulfilled => no goal completion
        when(subGoalFulfillmentHeuristic.decide(afterMerge))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("not fulfilled"));
        when(goalCompletionDecider.decide(eq(afterMerge), eq(false)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // deriveCycleResult needs some basics
        when(afterMerge.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterMerge.lastGoalProgressApplication()).thenReturn(null);
        when(afterMerge.subgoalPromoted()).thenReturn(false);
        GoalEntity g1 = mock(GoalEntity.class);
//        when(afterMerge.remainingGoals()).thenReturn(List.of(g1));

        // CycleExecutor sets cycle result state
        when(afterMerge.withCycleResultState(any())).thenReturn(afterMerge);

        // interpretation + ticking
        when(interpretationService.interpret(afterMerge)).thenReturn(afterInterpret);
        when(afterInterpret.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);
//        when(afterInterpret.cycleName()).thenReturn("cycle-1");
//        when(afterInterpret.signals()).thenReturn(java.util.Map.of());

        CycleContextData out = sut.runCycle(input);
        assertThat(out).isSameAs(afterTick);

        // Verify ordering: engine -> pattern -> merge request -> merge execute -> interpret -> tick
        InOrder inOrder = inOrder(adaptiveExecutionEngine, patternSignalAnalyzer, cycleScoreRecorder, interpretationService);
        inOrder.verify(adaptiveExecutionEngine).runCycle(afterSync);
        inOrder.verify(patternSignalAnalyzer).analyze(afterEngine);
        inOrder.verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), same(withMergeSignal));
        inOrder.verify(cycleScoreRecorder).recordGoalEventsIfPresent(afterMerge);
        inOrder.verify(interpretationService).interpret(afterMerge);

        // Verify we actually injected ROLE_MERGE signal before merge execution
        ArgumentCaptor<Signal> signalCaptor = ArgumentCaptor.forClass(Signal.class);
        verify(afterPattern).withSignal(signalCaptor.capture());
        Signal injected = signalCaptor.getValue();
        assertThat(injected.type()).isEqualTo(BasicSignals.ROLE_MERGE);
        assertThat(injected.strength()).isEqualTo(1.0);
        assertThat(injected.decayPerCycle()).isEqualTo(0.0);

        // Ensure we did not accidentally call engine merge via another path
        verifyNoMoreInteractions(adaptiveExecutionEngine);
    }

    @Test
    void runCycle_whenSubgoalFulfilled_appliesFulfilledProgressAndUpdatesContext_withoutTriggeringMerge() {
        CycleContextData input = mock(CycleContextData.class);
        CycleContextData afterSync = mock(CycleContextData.class);

        CycleContextData afterEngine = mock(CycleContextData.class);
        CycleContextData afterPattern = mock(CycleContextData.class);

        CycleContextData afterMergeGate = mock(CycleContextData.class);

        CycleContextData afterCompletion = mock(CycleContextData.class);
        CycleContextData afterInterpret = mock(CycleContextData.class);
        CycleContextData afterTick = mock(CycleContextData.class);

        // --- syncContextFromGoals stubs ---
        when(input.withCurrentGoalId(anyString())).thenReturn(afterSync);
        when(afterSync.withCurrentGoalDescription(anyString())).thenReturn(afterSync);
        when(afterSync.withCurrentSubGoalId(any())).thenReturn(afterSync);
        when(afterSync.withCurrentSubGoalDescription(any())).thenReturn(afterSync);
        when(afterSync.withLastAttemptId(anyString())).thenReturn(afterSync);
        when(afterSync.withParentAttemptId(anyString())).thenReturn(afterSync);

        when(afterSync.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);

        // --- engine ---
        when(adaptiveExecutionEngine.runCycle(afterSync)).thenReturn(afterEngine);
        when(afterEngine.evaluationInterpretation()).thenReturn(null);

        // --- pattern analyzer ---
        when(patternSignalAnalyzer.analyze(afterEngine)).thenReturn(afterPattern);
        when(planningThrashSignalAnalyzer.analyze(afterPattern)).thenReturn(afterPattern);

        // --- merge gating: no evolution occurred => merge should NOT be executed here ---
        when(afterPattern.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterPattern.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(false);
        when(afterPattern.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(false);

        // applyMergeOnAcceptedMutation returns ctx unchanged in this case
        // (CycleExecutor will keep working with that same instance)
        afterMergeGate = afterPattern; // alias for readability

        // --- evaluation progress application: keep no-op ---
        when(afterMergeGate.goalProgress()).thenReturn(null);

        // --- completion ---
        when(afterMergeGate.currentSubGoalId()).thenReturn("sub-1");

        when(subGoalFulfillmentHeuristic.decide(afterMergeGate))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "fulfilled"));

        when(goalCompletionDecider.decide(eq(afterMergeGate), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        GoalProgressApplicationResult applied = mock(GoalProgressApplicationResult.class);
        when(applied.promoted()).thenReturn(true);
        when(applied.newSubGoalId()).thenReturn("sub-2");

        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.FULFILLED)).thenReturn(applied);
        GoalEntity g1 = mock(GoalEntity.class);
        GoalEntity g2 = mock(GoalEntity.class);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(g2));
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of(g1));

        GoalEntity next = mock(GoalEntity.class);
        when(next.getDescription()).thenReturn("Sub goal 2");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(next);

        // context updates done in applyCompletion(...)
        when(afterMergeGate.withSubgoalPromoted(true)).thenReturn(afterCompletion);
        when(afterCompletion.withCurrentSubGoalId("sub-2")).thenReturn(afterCompletion);
        when(afterCompletion.withRemainingGoals(List.of(g2))).thenReturn(afterCompletion);
        when(afterCompletion.withCompletedGoals(List.of(g1))).thenReturn(afterCompletion);
        when(afterCompletion.withLastGoalProgressApplication(applied)).thenReturn(afterCompletion);
        when(afterCompletion.withCurrentSubGoalDescription("Sub goal 2")).thenReturn(afterCompletion);

        // deriveCycleResult basics
        when(afterCompletion.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterCompletion.lastGoalProgressApplication()).thenReturn(applied);
        when(afterCompletion.goalProgress()).thenReturn(null);
//        when(afterCompletion.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//        when(afterCompletion.remainingGoals()).thenReturn(List.of(g2));
        when(afterCompletion.subgoalPromoted()).thenReturn(true);
        when(afterCompletion.withCycleResultState(any())).thenReturn(afterCompletion);

        // interpretation + tick
        when(interpretationService.interpret(afterCompletion)).thenReturn(afterInterpret);
        when(afterInterpret.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);
//        when(afterInterpret.cycleName()).thenReturn("cycle-1");
//        when(afterInterpret.signals()).thenReturn(java.util.Map.of());

        CycleContextData out = sut.runCycle(input);
        assertThat(out).isSameAs(afterTick);

        // verify goal progress application happened
        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.FULFILLED);

        // verify merge was NOT executed in this scenario (no EVOLUTION_OCCURRED)
        verify(adaptiveExecutionEngine, never()).tryExecuteRole(eq("merge"), any());

        // verify completion updates happened
        verify(afterMergeGate).withSubgoalPromoted(true);
        verify(afterCompletion).withCurrentSubGoalId("sub-2");
        verify(afterCompletion).withRemainingGoals(List.of(g2));
        verify(afterCompletion).withCompletedGoals(List.of(g1));
        verify(afterCompletion).withLastGoalProgressApplication(applied);
        verify(afterCompletion).withCurrentSubGoalDescription("Sub goal 2");
    }
}