package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CycleExecutorTest {

    @Mock private AdaptiveExecutionEngine adaptiveExecutionEngine;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;
    @Mock private InterpretationService interpretationService;
    @Mock private CycleScoreRecorder cycleScoreRecorder;

    private CycleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CycleExecutor(
                adaptiveExecutionEngine,
                goalPlannerService,
                subGoalFulfillmentHeuristic,
                interpretationService,
                cycleScoreRecorder
        );
    }

    @Test
    void runCycle_delegatesToAdaptiveEngine_ticksSignals_and_recordsScoreProjection() {
        CycleContextData initial = mock(CycleContextData.class);
        CycleContextData afterEngine = mock(CycleContextData.class);
        CycleContextData afterInterpreted = mock(CycleContextData.class);
        CycleContextData afterTick = mock(CycleContextData.class);
//        CycleContextData afterMeta = org.mockito.Mockito.mock(CycleContextData.class);

        // not paused
        when(initial.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).thenReturn(false);

        // snapshot values
        when(initial.mutationState()).thenReturn(MutationAcceptanceState.NOT_EVALUATED);
        when(initial.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(initial.cycleResultState()).thenReturn(CycleResultState.CONTINUE);

        // Engine runs on the deliberated context, not the original
        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        // fluent chaining must not return null
        when(afterEngine.withMutationState(any())).thenReturn(afterEngine);
        when(afterEngine.withGoalProgress(any())).thenReturn(afterEngine);
        when(afterEngine.withCycleResultState(any())).thenReturn(afterEngine);

        // interpretation present so CycleExecutor can finalize mutationState/goalProgress
        EvaluationInterpreter.InterpretationResult ir = mock(EvaluationInterpreter.InterpretationResult.class);
        when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(ir.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(afterEngine.evaluationInterpretation()).thenReturn(ir);

        // deriveCycleResult will take STAGNATING branch
        when(afterEngine.hasSignal(BasicSignals.GOAL_EXHAUSTED)).thenReturn(false);
        when(afterEngine.lastGoalProgressApplication()).thenReturn(null);
        when(afterEngine.subgoalPromoted()).thenReturn(false);
        when(afterEngine.goalProgress()).thenReturn(GoalProgressState.STAGNATING);

        // applyEvaluationProgressIfPresent will call planner service because progress != null and != FULFILLED
        when(afterEngine.currentSubGoalId()).thenReturn(null);
        when(afterEngine.currentGoalId()).thenReturn(null); // makes applyEvaluationProgressIfPresent no-op

        // interpretationService + tickSignals
        when(interpretationService.interpret(afterEngine)).thenReturn(afterInterpreted);
        when(afterInterpreted.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        CycleContextData result = executor.runCycle(initial);

        assertThat(result).isSameAs(afterTick);

        verify(adaptiveExecutionEngine).runCycle(initial);
        verify(cycleScoreRecorder).recordGoalEventsIfPresent(afterEngine);

        var captor = org.mockito.ArgumentCaptor.forClass(CycleResultState.class);
        verify(afterEngine, times(2)).withCycleResultState(captor.capture());
        var states = captor.getAllValues();
        assertThat(states.get(0)).isEqualTo(CycleResultState.CONTINUE);   // reset to prevCycleResult
        assertThat(states.get(1)).isEqualTo(CycleResultState.STAGNATING); // derived from goalProgress

        verify(interpretationService).interpret(afterEngine);
        verify(afterInterpreted).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
    }
}
