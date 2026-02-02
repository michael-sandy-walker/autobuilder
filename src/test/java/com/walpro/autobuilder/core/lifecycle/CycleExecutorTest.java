package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class CycleExecutorTest {

    @Mock private AdaptiveExecutionEngine adaptiveExecutionEngine;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;
    @Mock private InterpretationService interpretationService;
    @Mock private CycleScoreRecorder cycleScoreRecorder;
    @Mock private GoalCompletionDecider goalCompletionDecider;

    private CycleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CycleExecutor(
                adaptiveExecutionEngine,
                goalPlannerService,
                subGoalFulfillmentHeuristic,
                interpretationService,
                cycleScoreRecorder,
                goalCompletionDecider
        );
    }

    private CycleContextData ctx() {
        return mock(CycleContextData.class, RETURNS_SELF);
    }

    private void stubGoalSyncForRootOnly() {
        com.walpro.autobuilder.goal.GoalEntity root = new com.walpro.autobuilder.goal.GoalEntity();
        root.setId("G0");
        root.setDescription("root");

        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new com.walpro.autobuilder.goal.AttemptIds("attempt-root", null));
    }

    @Test
    void runCycle_delegatesToAdaptiveEngine_ticksSignals_and_recordsScoreProjection() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterInterpreted = ctx();
        CycleContextData afterTick = ctx();

        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);

        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        stubGoalSyncForRootOnly();

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        // reset to prev snapshot (these happen immediately after engine.runCycle)
        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);

        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(ir.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);

        // IMPORTANT: interpretation application must produce a context that carries the applied state
        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.STAGNATING)).thenReturn(afterApplied);

        // deriveCycleResult will take STAGNATING branch
        when(afterApplied.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterApplied.lastGoalProgressApplication()).thenReturn(null);
        when(afterApplied.subgoalPromoted()).thenReturn(false);
        when(afterApplied.goalProgress()).thenReturn(GoalProgressState.STAGNATING);

        when(afterApplied.currentSubGoalId()).thenReturn(null);
        when(afterApplied.currentGoalId()).thenReturn(null);

        when(afterApplied.withCycleResultState(any())).thenReturn(afterApplied);

        when(interpretationService.interpret(afterApplied)).thenReturn(afterInterpreted);
        when(afterInterpreted.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData result = executor.runCycle(initial);

        assertThat(result).isSameAs(afterTick);

        verify(adaptiveExecutionEngine).runCycle(initial);

        // Now we assert the correct ordering effect:
        verify(cycleScoreRecorder).recordGoalEventsIfPresent(afterApplied);

        var captor = org.mockito.ArgumentCaptor.forClass(CycleResultState.class);
        verify(afterEngine, times(1)).withCycleResultState(CycleResultState.CONTINUE); // reset
        verify(afterApplied, times(1)).withCycleResultState(captor.capture());         // derived
        assertThat(captor.getValue()).isEqualTo(CycleResultState.STAGNATING);

        verify(interpretationService).interpret(afterApplied);
        verify(afterInterpreted).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }

    @Test
    void runCycle_whenPaused_skipsEngineAndScoreRecording_andOnlyTicksSignals() {
        CycleContextData initial = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();

        when(initial.cycleName()).thenReturn("cycle-paused");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(true);
        when(initial.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verifyNoInteractions(adaptiveExecutionEngine);
        verifyNoInteractions(cycleScoreRecorder);
        verifyNoInteractions(interpretationService);

        verify(initial).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }

    @Test
    void runCycle_addsRoleMergeSignal_whenAccepted_andEvolutionOccurred_andNoExistingRoleMerge() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterWithMerge = ctx();
        CycleContextData afterInterpreted = ctx();
        CycleContextData afterTick = ctx();

        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        stubGoalSyncForRootOnly();

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        // reset to prev snapshot
        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);

        // interpretation -> ACCEPTED
        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(ir.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.PROGRESSING)).thenReturn(afterApplied);

        // merge gates:
        when(afterApplied.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterApplied.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(true);
        when(afterApplied.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(false);

        // adding merge signal returns a new ctx
        when(afterApplied.withSignal(any())).thenReturn(afterWithMerge);

        // avoid planner apply
        when(afterWithMerge.currentSubGoalId()).thenReturn(null);
        when(afterWithMerge.currentGoalId()).thenReturn(null);

        // deriveCycleResult path
        when(afterWithMerge.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterWithMerge.lastGoalProgressApplication()).thenReturn(null);
        when(afterWithMerge.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterWithMerge.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterWithMerge.remainingGoals()).thenReturn(List.of());
        when(afterWithMerge.withCycleResultState(any())).thenReturn(afterWithMerge);

        when(interpretationService.interpret(afterWithMerge)).thenReturn(afterInterpreted);
        when(afterInterpreted.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        ArgumentCaptor<Signal> signalCaptor = ArgumentCaptor.forClass(Signal.class);
        verify(afterApplied).withSignal(signalCaptor.capture());
        Signal s = signalCaptor.getValue();
        assertThat(s.type()).isEqualTo(BasicSignals.ROLE_MERGE);
        assertThat(s.strength()).isEqualTo(1.0);
    }

    @Test
    void runCycle_doesNotAddRoleMergeSignal_whenAccepted_butNoEvolutionOccurred() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterInterpreted = ctx();
        CycleContextData afterTick = ctx();

        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        stubGoalSyncForRootOnly();

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);

        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(ir.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.PROGRESSING)).thenReturn(afterApplied);

        // merge gates: no EVOLUTION_OCCURRED => no merge request
        when(afterApplied.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterApplied.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(false);

        // avoid planner apply
        when(afterApplied.currentSubGoalId()).thenReturn(null);
        when(afterApplied.currentGoalId()).thenReturn(null);

        when(afterApplied.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterApplied.lastGoalProgressApplication()).thenReturn(null);
        when(afterApplied.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterApplied.remainingGoals()).thenReturn(List.of());
        when(afterApplied.withCycleResultState(any())).thenReturn(afterApplied);

        when(interpretationService.interpret(afterApplied)).thenReturn(afterInterpreted);
        when(afterInterpreted.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verify(afterApplied, never()).withSignal(any());
    }

    @Test
    void runCycle_doesNotApplyGoalProgress_whenInterpretationProgressIsFulfilled() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterInterpreted = ctx();
        CycleContextData afterTick = ctx();

        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        stubGoalSyncForRootOnly();

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);

        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(ir.goalProgress()).thenReturn(GoalProgressState.FULFILLED);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.FULFILLED)).thenReturn(afterApplied);

        // deriveCycleResult: CONTINUE
        when(afterApplied.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterApplied.lastGoalProgressApplication()).thenReturn(null);
        when(afterApplied.subgoalPromoted()).thenReturn(false);
        when(afterApplied.goalProgress()).thenReturn(GoalProgressState.FULFILLED);
        when(afterApplied.withCycleResultState(any())).thenReturn(afterApplied);

        when(interpretationService.interpret(afterApplied)).thenReturn(afterInterpreted);
        when(afterInterpreted.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        // applyEvaluationProgressIfPresent skips FULFILLED explicitly
        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.FULFILLED));
    }

    @Test
    void runCycle_whenSubGoalFulfillmentHeuristicSaysFulfilled_appliesFulfilled_andTriggersMerge() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterCompletionApplied = ctx();
        CycleContextData afterWithMerge = ctx();
        CycleContextData afterInterpreted = ctx();
        CycleContextData afterTick = ctx();

        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        stubGoalSyncForRootOnly();

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);

        // Interpretation exists but not fulfilled (the fulfillment heuristic is what triggers FULFILLED)
        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(ir.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.PROGRESSING)).thenReturn(afterApplied);

        when(afterApplied.currentSubGoalId()).thenReturn("S1");

        // Fulfillment heuristic says "fulfilled"
        SubGoalFulfillmentHeuristic.FulfillmentDecision fulfilled = mock(SubGoalFulfillmentHeuristic.FulfillmentDecision.class);
        when(fulfilled.fulfilled()).thenReturn(true);
        when(fulfilled.confidence()).thenReturn(0.9);
        when(fulfilled.rationale()).thenReturn("ok");
        when(subGoalFulfillmentHeuristic.decide(any())).thenReturn(fulfilled);

        // goal progress application result indicates promotion happened
        GoalProgressApplicationResult applied = mock(GoalProgressApplicationResult.class);
        when(applied.promoted()).thenReturn(true);
        when(applied.newSubGoalId()).thenReturn("S2");

        when(goalPlannerService.applyGoalProgress("S1", GoalProgressState.FULFILLED)).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        GoalEntity nextGoal = new GoalEntity();
        nextGoal.setDescription("next");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(nextGoal);

        // After completion application, executor calls withSubgoalPromoted / withCurrentSubGoalId / etc.
        // We model those by returning a new ctx
        when(afterApplied.withSubgoalPromoted(true)).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withCurrentSubGoalId("S2")).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withRemainingGoals(any())).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withCompletedGoals(any())).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withLastGoalProgressApplication(any())).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withCurrentSubGoalDescription(any())).thenReturn(afterCompletionApplied);

        // merge signal injected after promotion
        when(afterCompletionApplied.withSignal(any())).thenReturn(afterWithMerge);

        // deriveCycleResult on the post-merge ctx
        when(afterWithMerge.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterWithMerge.lastGoalProgressApplication()).thenReturn(applied);
        when(afterWithMerge.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterWithMerge.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterWithMerge.remainingGoals()).thenReturn(List.of());
        when(afterWithMerge.withCycleResultState(any())).thenReturn(afterWithMerge);

        when(interpretationService.interpret(afterWithMerge)).thenReturn(afterInterpreted);
        when(afterInterpreted.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verify(goalPlannerService).applyGoalProgress("S1", GoalProgressState.FULFILLED);

        ArgumentCaptor<Signal> signalCaptor = ArgumentCaptor.forClass(Signal.class);
        verify(afterCompletionApplied).withSignal(signalCaptor.capture());
        assertThat(signalCaptor.getValue().type()).isEqualTo(BasicSignals.ROLE_MERGE);
    }

}
