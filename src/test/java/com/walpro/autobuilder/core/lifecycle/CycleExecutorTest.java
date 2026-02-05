package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
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
    @Mock private PatternSignalAnalyzer patternSignalAnalyzer;

    private CycleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CycleExecutor(
                adaptiveExecutionEngine,
                goalPlannerService,
                subGoalFulfillmentHeuristic,
                interpretationService,
                cycleScoreRecorder,
                goalCompletionDecider,
                patternSignalAnalyzer
        );
    }

    private CycleContextData ctx() {
        return mock(CycleContextData.class);
    }

    private void stubGoalSyncForRootOnly() {
        GoalEntity root = new GoalEntity();
        root.setId("G0");
        root.setDescription("root");

        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("attempt-root", null));
    }

    private void stubSyncPassThrough(CycleContextData c) {
        when(c.withCurrentGoalId(any())).thenReturn(c);
        when(c.withCurrentGoalDescription(any())).thenReturn(c);
        when(c.withCurrentSubGoalId(any())).thenReturn(c);
        when(c.withCurrentSubGoalDescription(any())).thenReturn(c);
        when(c.withLastAttemptId(any())).thenReturn(c);
        when(c.withParentAttemptId(any())).thenReturn(c);
    }

    private void stubAnalyzeAndInterpretPassThrough() {
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubEngineResetPassThrough(CycleContextData afterEngine) {
        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);
    }

    private void stubInterpretation(
            CycleContextData afterEngine,
            MutationAcceptanceState mutation,
            GoalProgressState progress
    ) {
        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(mutation);
        when(ir.goalProgress()).thenReturn(progress);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);
    }

    @Test
    void runCycle_delegatesToAdaptiveEngine_ticksSignals_and_recordsScoreProjection() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();
        stubSyncPassThrough(initial);
        stubAnalyzeAndInterpretPassThrough();

//        when(initial.cycleName()).thenReturn("cycle-1");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);
        stubEngineResetPassThrough(afterEngine);

        stubInterpretation(afterEngine, MutationAcceptanceState.ACCEPTED, GoalProgressState.STAGNATING);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.STAGNATING)).thenReturn(afterApplied);

        // merge should not happen (no EVOLUTION_OCCURRED)
        when(afterApplied.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterApplied.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(false);

        // avoid evaluation progress (no target goal id)
        when(afterApplied.currentSubGoalId()).thenReturn(null);
        when(afterApplied.currentGoalId()).thenReturn(null);

        // deriveCycleResult -> STAGNATING
        when(afterApplied.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterApplied.lastGoalProgressApplication()).thenReturn(null);
        when(afterApplied.subgoalPromoted()).thenReturn(false);
        when(afterApplied.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(afterApplied.withCycleResultState(any())).thenReturn(afterApplied);

        when(afterApplied.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verify(adaptiveExecutionEngine).runCycle(initial);
        verify(cycleScoreRecorder).recordGoalEventsIfPresent(afterApplied);

        ArgumentCaptor<CycleResultState> cr = ArgumentCaptor.forClass(CycleResultState.class);
        verify(afterEngine).withCycleResultState(CycleResultState.CONTINUE);
        verify(afterApplied).withCycleResultState(cr.capture());
        assertThat(cr.getValue()).isEqualTo(CycleResultState.STAGNATING);

        verify(interpretationService).interpret(afterApplied);
        verify(afterApplied).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }

    @Test
    void runCycle_whenPaused_skipsEngineAndScoreRecording_andOnlyTicksSignals() {
        CycleContextData initial = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();
        stubSyncPassThrough(initial);

        when(initial.cycleName()).thenReturn("cycle-paused");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(true);
        when(initial.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verifyNoInteractions(adaptiveExecutionEngine);
        verifyNoInteractions(cycleScoreRecorder);
        verifyNoInteractions(patternSignalAnalyzer);
        verifyNoInteractions(interpretationService);
    }

    @Test
    void runCycle_addsRoleMergeSignal_whenAccepted_andEvolutionOccurred_andNoExistingRoleMerge() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterWithMerge = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();
        stubSyncPassThrough(initial);
        stubAnalyzeAndInterpretPassThrough();

//        when(initial.cycleName()).thenReturn("cycle-merge");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);
        stubEngineResetPassThrough(afterEngine);

        stubInterpretation(afterEngine, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.PROGRESSING)).thenReturn(afterApplied);

        // merge gates
        when(afterApplied.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterApplied.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(true);
        when(afterApplied.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(false);

        when(afterApplied.withSignal(any())).thenReturn(afterWithMerge);

        // avoid evaluation progress + completion
        when(afterWithMerge.currentSubGoalId()).thenReturn(null);
        when(afterWithMerge.currentGoalId()).thenReturn(null);

        // deriveCycleResult -> CONTINUE
        when(afterWithMerge.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterWithMerge.lastGoalProgressApplication()).thenReturn(null);
//        when(afterWithMerge.subgoalPromoted()).thenReturn(false);
        when(afterWithMerge.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterWithMerge.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterWithMerge.remainingGoals()).thenReturn(List.of());
        when(afterWithMerge.withCycleResultState(any())).thenReturn(afterWithMerge);

        when(afterWithMerge.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        ArgumentCaptor<Signal> signalCaptor = ArgumentCaptor.forClass(Signal.class);
        verify(afterApplied).withSignal(signalCaptor.capture());

        Signal s = signalCaptor.getValue();
        assertThat(s.type()).isEqualTo(BasicSignals.ROLE_MERGE);
        assertThat(s.strength()).isEqualTo(1.0);

        verify(interpretationService).interpret(afterWithMerge);
        verify(afterWithMerge).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }

    @Test
    void runCycle_doesNotAddRoleMergeSignal_whenAccepted_butNoEvolutionOccurred() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();
        stubSyncPassThrough(initial);
        stubAnalyzeAndInterpretPassThrough();

//        when(initial.cycleName()).thenReturn("cycle-no-merge");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);
        stubEngineResetPassThrough(afterEngine);

        stubInterpretation(afterEngine, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.PROGRESSING)).thenReturn(afterApplied);

        // merge gate fails
        when(afterApplied.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(afterApplied.hasSignal(BasicSignals.EVOLUTION_OCCURRED)).thenReturn(false);

        // avoid evaluation progress + completion
        when(afterApplied.currentSubGoalId()).thenReturn(null);
        when(afterApplied.currentGoalId()).thenReturn(null);

        // deriveCycleResult -> CONTINUE
        when(afterApplied.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterApplied.lastGoalProgressApplication()).thenReturn(null);
//        when(afterApplied.subgoalPromoted()).thenReturn(false);
        when(afterApplied.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(afterApplied.remainingGoals()).thenReturn(List.of());
        when(afterApplied.withCycleResultState(any())).thenReturn(afterApplied);

        when(afterApplied.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verify(afterApplied, never()).withSignal(any());
        verify(interpretationService).interpret(afterApplied);
        verify(afterApplied).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }

    @Test
    void runCycle_doesNotApplyGoalProgress_whenInterpretationProgressIsFulfilled() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();
        stubSyncPassThrough(initial);
        stubAnalyzeAndInterpretPassThrough();

//        when(initial.cycleName()).thenReturn("cycle-fulfilled");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);
        stubEngineResetPassThrough(afterEngine);

        stubInterpretation(afterEngine, MutationAcceptanceState.ACCEPTED, GoalProgressState.FULFILLED);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.FULFILLED)).thenReturn(afterApplied);

        // completion not applicable
        when(afterApplied.currentSubGoalId()).thenReturn(null);
//        when(afterApplied.currentGoalId()).thenReturn(null);

        // deriveCycleResult -> CONTINUE
        when(afterApplied.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterApplied.lastGoalProgressApplication()).thenReturn(null);
        when(afterApplied.subgoalPromoted()).thenReturn(false);
        when(afterApplied.goalProgress()).thenReturn(GoalProgressState.FULFILLED);
        when(afterApplied.withCycleResultState(any())).thenReturn(afterApplied);

        when(afterApplied.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.FULFILLED));
        verify(interpretationService).interpret(afterApplied);
    }

    @Test
    void runCycle_whenSubGoalFulfillmentHeuristicSaysFulfilled_appliesFulfilled_andTriggersMerge() {
        CycleContextData initial = ctx();
        CycleContextData afterEngine = ctx();
        CycleContextData afterApplied = ctx();
        CycleContextData afterCompletionApplied = ctx();
        CycleContextData afterWithMerge = ctx();
        CycleContextData afterTick = ctx();

        stubGoalSyncForRootOnly();
        stubSyncPassThrough(initial);
        stubAnalyzeAndInterpretPassThrough();

//        when(initial.cycleName()).thenReturn("cycle-subgoal-fulfilled");
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);
        stubEngineResetPassThrough(afterEngine);

        // ensure applyEvaluationProgressIfPresent becomes a no-op
        stubInterpretation(afterEngine, MutationAcceptanceState.ACCEPTED, GoalProgressState.NOT_EVALUATED);

        when(afterEngine.withMutationState(MutationAcceptanceState.ACCEPTED)).thenReturn(afterApplied);
        when(afterApplied.withGoalProgress(GoalProgressState.NOT_EVALUATED)).thenReturn(afterApplied);

        when(afterApplied.currentSubGoalId()).thenReturn("S1");
//        when(afterApplied.currentGoalId()).thenReturn("G0");

        SubGoalFulfillmentHeuristic.FulfillmentDecision fd = mock(SubGoalFulfillmentHeuristic.FulfillmentDecision.class);
        when(fd.fulfilled()).thenReturn(true);
        when(fd.confidence()).thenReturn(0.9);
        when(fd.rationale()).thenReturn("ok");
        when(subGoalFulfillmentHeuristic.decide(any())).thenReturn(fd);

        GoalCompletionDecider.CompletionDecision cd = mock(GoalCompletionDecider.CompletionDecision.class);
        when(goalCompletionDecider.decide(eq(afterApplied), eq(true))).thenReturn(cd);

        GoalProgressApplicationResult applied = mock(GoalProgressApplicationResult.class);
        when(applied.promoted()).thenReturn(true);
        when(applied.newSubGoalId()).thenReturn("S2");
        when(goalPlannerService.applyGoalProgress("S1", GoalProgressState.FULFILLED)).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        GoalEntity next = new GoalEntity();
        next.setId("S2");
        next.setDescription("next");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(next);

        when(afterApplied.withSubgoalPromoted(true)).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withCurrentSubGoalId("S2")).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withRemainingGoals(any())).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withCompletedGoals(any())).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withLastGoalProgressApplication(any())).thenReturn(afterCompletionApplied);
        when(afterCompletionApplied.withCurrentSubGoalDescription(any())).thenReturn(afterCompletionApplied);

        when(afterCompletionApplied.withSignal(any())).thenReturn(afterWithMerge);

        when(afterWithMerge.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterWithMerge.lastGoalProgressApplication()).thenReturn(applied);
        when(afterWithMerge.subgoalPromoted()).thenReturn(true);
        when(afterWithMerge.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
//        when(afterWithMerge.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//        when(afterWithMerge.remainingGoals()).thenReturn(List.of());
        when(afterWithMerge.withCycleResultState(any())).thenReturn(afterWithMerge);

        when(afterWithMerge.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData out = executor.runCycle(initial);

        assertThat(out).isSameAs(afterTick);

        verify(goalPlannerService).applyGoalProgress("S1", GoalProgressState.FULFILLED);

        ArgumentCaptor<Signal> signalCaptor = ArgumentCaptor.forClass(Signal.class);
        verify(afterCompletionApplied).withSignal(signalCaptor.capture());
        assertThat(signalCaptor.getValue().type()).isEqualTo(BasicSignals.ROLE_MERGE);

        verify(interpretationService).interpret(afterWithMerge);
        verify(afterWithMerge).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }
}