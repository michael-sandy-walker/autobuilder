package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.walpro.autobuilder.agent.meta.MetaAgent;
import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CycleExecutorTest {

    @Mock private MetaAgent metaAgent;
    @Mock private AdaptiveExecutionEngine adaptiveExecutionEngine;
    @Mock private DeliberationService deliberationService;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;

    private CycleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CycleExecutor(metaAgent, adaptiveExecutionEngine, deliberationService, goalPlannerService, subGoalFulfillmentHeuristic );
    }

    @Test
    void runCycle_delegatesToAdaptiveEngine_ticksSignals_and_notifiesMetaAgent() {
        CycleContextData initial = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterDeliberation = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterEngine = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterWithResult = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterTick = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterMeta = org.mockito.Mockito.mock(CycleContextData.class);

        DeliberationResult deliberation = org.mockito.Mockito.mock(DeliberationResult.class);
        DeliberationDecision decision = new DeliberationDecision(initial, deliberation);

        // Deliberation happens first
        when(deliberationService.deliberate(initial)).thenReturn(decision);

        // Assuming CycleContextData has a withDeliberationResult(...) copier (Lombok @With or manual)
        when(initial.withDeliberationResult(deliberation)).thenReturn(afterDeliberation);

        // Engine runs on the deliberated context, not the original
        when(adaptiveExecutionEngine.runCycle(afterDeliberation)).thenReturn(afterEngine);

        // Avoid deriveCycleResult touching remainingGoals() by taking the "STAGNATING" branch
        when(afterEngine.goalProgress()).thenReturn(GoalProgressState.STAGNATING);

        // Critical: mocks return null for unstubbed methods
        when(afterEngine.withCycleResultState(any())).thenReturn(afterWithResult);

        when(afterWithResult.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)).thenReturn(afterTick);

        // runCycle returns metaAgent result
//        when(metaAgent.analyzeAndEvolve(afterTick)).thenReturn(afterMeta); // TODO: Currently disabled

        CycleContextData result = executor.runCycle(initial);

        assertThat(result).isSameAs(afterTick);

        verify(deliberationService).deliberate(initial);
        verify(initial).withDeliberationResult(deliberation);

        verify(adaptiveExecutionEngine).runCycle(afterDeliberation);
        verify(afterEngine).withCycleResultState(any());
        verify(afterWithResult).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
//        verify(metaAgent).analyzeAndEvolve(afterTick); // TODO: Currently disabled
    }
}
