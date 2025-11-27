package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.walpro.autobuilder.agent.meta.MetaAgent;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.coordinator.MergeCoordinator;
import com.walpro.autobuilder.core.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CycleExecutorTest {

    @Mock private EvolutionCoordinator evolutionCoordinator;
    @Mock private EvaluationCoordinator evaluationCoordinator;
    @Mock private MergeCoordinator mergeCoordinator;
    @Mock private PlannerCoordinator plannerCoordinator;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private MutationApplier mutationApplier;
    @Mock private CycleOrchestrator cycleOrchestrator;
    @Mock private MetaAgent metaAgent;
    @Mock private AdaptiveExecutionEngine adaptiveExecutionEngine;

    private CycleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CycleExecutor(
                evolutionCoordinator,
                evaluationCoordinator,
                mergeCoordinator,
                plannerCoordinator,
                goalPlannerService,
                mutationApplier,
                cycleOrchestrator,
                metaAgent,
                adaptiveExecutionEngine
        );
    }

    @Test
    void runCycle_delegatesToAdaptiveEngine_ticksSignals_and_notifiesMetaAgent() {
        CycleContextData initial = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterEngine = org.mockito.Mockito.mock(CycleContextData.class);
        CycleContextData afterTick = org.mockito.Mockito.mock(CycleContextData.class);

        // 1) Adaptive engine returns a new context
        when(adaptiveExecutionEngine.runCycle(initial)).thenReturn(afterEngine);

        // 2) Signals are ticked on that context with the configured threshold
        when(afterEngine.tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD))
                .thenReturn(afterTick);

        // (cycleName/signals used only for logging – no need to assert them)
        when(afterTick.cycleName()).thenReturn("cycle-1");

        CycleContextData result = executor.runCycle(initial);

        // Final context must be the ticked one
        assertThat(result).isSameAs(afterTick);

        // Verify sequence of calls
        verify(adaptiveExecutionEngine).runCycle(initial);
        verify(afterEngine).tickSignals(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD);
        verify(metaAgent).analyzeAndEvolve(afterTick);
    }
}
