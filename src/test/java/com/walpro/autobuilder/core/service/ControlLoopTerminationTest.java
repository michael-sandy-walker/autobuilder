package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.bootstrap.BuildSystemBootstrap;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.SeedCoordinator;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ControlLoopTerminationTest {

    @Mock private SeedCoordinator seedCoordinator;
    @Mock private BuildSystemBootstrap buildSystemBootstrap;
    @Mock private FileService fileService;
    @Mock private AutobuilderConfigProperties autobuilderConfig;
    @Mock private SystemResetConfig systemResetConfig;
    @Mock private CycleContextCoordinator contextCoordinator;
    @Mock private CycleExecutor cycleExecutor;
    @Mock private CyclePersistenceService cyclePersistenceService;
    @Mock private CycleLoggingService cycleLoggingService;
    @Mock private SmsCycleIngestor smsCycleIngestor;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private LlmCircuitBreaker llmCircuitBreaker;
    @Mock private com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker rootConvergenceTracker;
    @Mock private com.walpro.autobuilder.goal.GoalConsolidationService goalConsolidationService;
    @Mock private OrchestrationSnapshotService orchestrationSnapshotService;

    private AutobuilderService newService() {
        AutobuilderService service = new AutobuilderService(
                seedCoordinator,
                buildSystemBootstrap,
                fileService,
                autobuilderConfig,
                systemResetConfig,
                contextCoordinator,
                cycleExecutor,
                cyclePersistenceService,
                cycleLoggingService,
                smsCycleIngestor,
                goalPlannerService,
                llmCircuitBreaker,
                rootConvergenceTracker,
                goalConsolidationService,
                orchestrationSnapshotService
        );
        // The convergence brake is a pass-through unless a test exercises it explicitly.
        lenient().when(rootConvergenceTracker.track(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        return service;
    }

    @Test
    void runAutobuilderCycle_whenLastCycleGoalFulfilled_shortCircuitsAndDoesNotPrepareOrExecute() throws Exception {
        AutobuilderService service = newService();

        CycleContextData fulfilled = CycleContextData.builder()
                .cycleName("cycle-fulfilled")
                .cycleResultState(CycleResultState.GOAL_FULFILLED)
                .build();

        setField(service, "context", fulfilled);
        setField(service, "schedulerEnabled", true);

        service.runAutobuilderCycle();

        // Hard invariant: no more work after goal fulfilled
        verifyNoInteractions(contextCoordinator, cycleExecutor, cyclePersistenceService, cycleLoggingService, smsCycleIngestor);

        // Context remains unchanged
        assertThat(service.getContext()).isPresent();
        assertThat(service.getContext().get().cycleName()).isEqualTo("cycle-fulfilled");
        assertThat(service.getContext().get().cycleResultState()).isEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    @Test
    void runAutobuilderCycle_whenSchedulerDisabled_abortsBeforePrepareRuntimeContext() throws Exception {
        AutobuilderService service = newService();

        setField(service, "schedulerEnabled", false);

        service.runAutobuilderCycle();

        verifyNoInteractions(contextCoordinator, cycleExecutor, cyclePersistenceService, cycleLoggingService, smsCycleIngestor);
    }

    @Test
    void runAutobuilderCycle_whenPrepareRuntimeContextEmpty_abortsAndDoesNotExecute() throws Exception {
        AutobuilderService service = newService();

        setField(service, "schedulerEnabled", true);

        // prepare fails
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.empty());

        service.runAutobuilderCycle();

        verify(contextCoordinator).prepareRuntimeContext(any());
        verifyNoInteractions(cycleExecutor, cyclePersistenceService, cycleLoggingService, smsCycleIngestor);
    }

    @Test
    void runAutobuilderCycle_happyPath_callsPrepareThenExecuteThenPersistThenLogThenIngest_inThatOrder() throws Exception {
        AutobuilderService service = newService();

        setField(service, "schedulerEnabled", true);

        CycleContextData prepared = CycleContextData.builder().cycleName("cycle-1").build();
        CycleContextData executed = CycleContextData.builder().cycleName("cycle-1").cycleResultState(CycleResultState.CONTINUE).build();
        CycleContextData persisted = CycleContextData.builder().cycleName("cycle-1").cycleResultState(CycleResultState.CONTINUE).build();

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(executed);
        when(cyclePersistenceService.persistSnapshot(executed)).thenReturn(persisted);

        service.runAutobuilderCycle();

        InOrder inOrder = inOrder(contextCoordinator, cycleExecutor, cyclePersistenceService, cycleLoggingService, smsCycleIngestor);
        inOrder.verify(contextCoordinator).prepareRuntimeContext(any());
        inOrder.verify(cycleExecutor).runCycle(prepared);
        inOrder.verify(cyclePersistenceService).persistSnapshot(executed);
        inOrder.verify(cycleLoggingService).logCycleSummary(persisted);
        inOrder.verify(smsCycleIngestor).ingestCycle(persisted);

        assertThat(service.getContext()).isPresent();
        assertThat(service.getContext().get()).isSameAs(persisted);
    }

    @Test
    void runAutobuilderCycle_whenLlmCircuitOpen_pausesAndSkipsCycle() throws Exception {
        AutobuilderService service = newService();
        setField(service, "schedulerEnabled", true);

        when(llmCircuitBreaker.isOpen()).thenReturn(true);
        when(llmCircuitBreaker.pauseReason()).thenReturn("insufficient_quota");

        service.runAutobuilderCycle();

        verifyNoInteractions(contextCoordinator, cycleExecutor, cyclePersistenceService, cycleLoggingService, smsCycleIngestor);

        assertThat(service.getContext()).isPresent();
        assertThat(service.getContext().get().hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).isTrue();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}