package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SeedCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutobuilderServiceTest {

    @Mock
    private SeedCoordinator seedCoordinator;

    @Mock
    private FileService fileService;

    @Mock
    private AutobuilderConfigProperties autobuilderConfig;

    @Mock
    private SystemResetConfig systemResetConfig;

    @Mock
    private CycleContextCoordinator contextCoordinator;

    @Mock
    private CycleExecutor cycleExecutor;

    @Mock
    private CyclePersistenceService cyclePersistenceService;

    @Mock
    private CycleLoggingService cycleLoggingService;

    @Mock
    private SmsCycleIngestor smsCycleIngestor;

    @Mock
    private GoalPlannerService goalPlannerService;

    @Mock
    private LlmCircuitBreaker llmCircuitBreaker;

    @InjectMocks
    private AutobuilderService autobuilderService;

    @Test
    void runAutobuilderCycle_abortsWhenRuntimeContextNotAvailable() {
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.empty());

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        verify(cyclePersistenceService, never()).persistSnapshot(any());
    }

    @Test
    void runAutobuilderCycle_runsCycleAndPersistsSnapshot() {
        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        CycleContextData afterPersist = emptyContext();

        when(contextCoordinator.prepareRuntimeContext(any()))
                .thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterPersist);

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor).runCycle(prepared);
        verify(cyclePersistenceService).persistSnapshot(afterRun);

        // Optional: assert that AutobuilderService.context was updated
        // (uses reflection to access the private field)
        try {
            var field = AutobuilderService.class.getDeclaredField("context");
            field.setAccessible(true);
            CycleContextData stored = (CycleContextData) field.get(autobuilderService);
            assertThat(stored).isSameAs(afterPersist);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void runAutobuilderCycle_whenStagnating_injectsRecoverySignalsAndContinues() {
        CycleContextData stagnatingCtx = contextWithResult(CycleResultState.STAGNATING);
        setStoredContext(stagnatingCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        // cycle must still run
        verify(cycleExecutor).runCycle(prepared);

        // the context passed to prepareRuntimeContext must carry recovery signals
        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        CycleContextData sentToPrepare = ctxCaptor.getValue();
        assertThat(sentToPrepare.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10)).isTrue();
        assertThat(sentToPrepare.hasStrongSignal(BasicSignals.DIVERGENCE_REQUEST, 0.10)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_marksGivenUpAndInjectsReplan() {
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.GIVEN_UP);
        verify(cycleExecutor).runCycle(prepared);

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        CycleContextData sentToPrepare = ctxCaptor.getValue();
        assertThat(sentToPrepare.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        assertThat(sentToPrepare.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenRootExhausted_pausesSystemAndSkipsCycle() {
        // subGoalId is null → root-level exhaustion
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.GOAL_EXHAUSTED, "root-1", null);
        setStoredContext(exhaustedCtx);

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        verify(goalPlannerService, never()).applyGoalProgress(any(), any());

        // SYSTEM_PAUSED must be set on the stored context
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenRootExhausted_subGoalIdEqualsRootGoalId_pausesSystem() {
        // subGoalId == rootGoalId → also treated as root-level exhaustion
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.GOAL_EXHAUSTED, "root-1", "root-1");
        setStoredContext(exhaustedCtx);

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).isTrue();
    }

    // --- helpers ---

    private void setStoredContext(CycleContextData ctx) {
        try {
            var field = AutobuilderService.class.getDeclaredField("context");
            field.setAccessible(true);
            field.set(autobuilderService, ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CycleContextData getStoredContext() {
        try {
            var field = AutobuilderService.class.getDeclaredField("context");
            field.setAccessible(true);
            return (CycleContextData) field.get(autobuilderService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CycleContextData contextWithResult(CycleResultState state) {
        return CycleContextData.builder()
                .cycleResultState(state)
                .signals(new java.util.LinkedHashMap<>())
                .build();
    }

    private static CycleContextData contextWithResultAndGoals(
            CycleResultState state, String goalId, String subGoalId) {
        return CycleContextData.builder()
                .cycleResultState(state)
                .currentGoalId(goalId)
                .currentSubGoalId(subGoalId)
                .signals(new java.util.LinkedHashMap<>())
                .build();
    }

    private static CycleContextData emptyContext() {
        try {
            Constructor<?> ctor = CycleContextData.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> type = paramTypes[i];
                if (!type.isPrimitive()) {
                    args[i] = null;
                } else if (type == boolean.class) {
                    args[i] = false;
                } else if (type == int.class || type == long.class
                        || type == short.class || type == byte.class) {
                    args[i] = 0;
                } else if (type == double.class || type == float.class) {
                    args[i] = 0.0;
                } else if (type == char.class) {
                    args[i] = '\0';
                } else {
                    args[i] = null;
                }
            }

            return (CycleContextData) ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct empty CycleContextData for tests", e);
        }
    }
}
