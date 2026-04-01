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
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
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

    @Test
    void scorePlateau_emitsSignalAfterFiveFlatAcceptedCycles() {
        // Each cycle: runCycle returns the prepared context unchanged (plateauCycles carries through),
        // persistSnapshot passes the context through. After 5 flat accepted cycles SCORE_PLATEAU fires.
        double flatScore = 0.80;
        EvaluationOutcome evalOutcome = new EvaluationOutcome(flatScore, MutationAcceptanceState.ACCEPTED, null, null, null);

        when(contextCoordinator.prepareRuntimeContext(any())).thenAnswer(inv -> {
            CycleContextData stored = inv.getArgument(0);
            int prevPlateau = stored != null ? stored.plateauCycles() : 0;
            CycleContextData prepared = CycleContextData.builder()
                    .evaluationOutcome(evalOutcome)
                    .mutationState(MutationAcceptanceState.ACCEPTED)
                    .score(flatScore)
                    .plateauCycles(prevPlateau)
                    .signals(new java.util.LinkedHashMap<>())
                    .build();
            return Optional.of(prepared);
        });
        when(cycleExecutor.runCycle(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // Cycle 1 sets the baseline (plateauCycles stays 0); cycles 2-5 increment to 4
        for (int i = 0; i < 5; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(getStoredContext().hasSignal(BasicSignals.SCORE_PLATEAU)).isFalse();
        assertThat(getStoredContext().plateauCycles()).isEqualTo(4);

        // 6th flat cycle — plateau=5 >= THRESHOLD → signal fires
        autobuilderService.runAutobuilderCycle();
        assertThat(getStoredContext().hasSignal(BasicSignals.SCORE_PLATEAU)).isTrue();
        assertThat(getStoredContext().plateauCycles()).isEqualTo(5);
    }

    @Test
    void scorePlateau_resetsOnScoreImprovement() {
        EvaluationOutcome flatOutcome = new EvaluationOutcome(0.80, MutationAcceptanceState.ACCEPTED, null, null, null);
        EvaluationOutcome improvedOutcome = new EvaluationOutcome(0.85, MutationAcceptanceState.ACCEPTED, null, null, null);

        // 4 flat cycles, then one improvement
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        when(contextCoordinator.prepareRuntimeContext(any())).thenAnswer(inv -> {
            CycleContextData stored = inv.getArgument(0);
            int prevPlateau = stored != null ? stored.plateauCycles() : 0;
            int call = callCount.incrementAndGet();
            EvaluationOutcome outcome = call <= 4 ? flatOutcome : improvedOutcome;
            double score = call <= 4 ? 0.80 : 0.85;
            CycleContextData prepared = CycleContextData.builder()
                    .evaluationOutcome(outcome)
                    .mutationState(MutationAcceptanceState.ACCEPTED)
                    .score(score)
                    .plateauCycles(prevPlateau)
                    .signals(new java.util.LinkedHashMap<>())
                    .build();
            return Optional.of(prepared);
        });
        when(cycleExecutor.runCycle(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(getStoredContext().plateauCycles()).isEqualTo(3);

        // improvement cycle — plateau resets to 0
        autobuilderService.runAutobuilderCycle();
        assertThat(getStoredContext().hasSignal(BasicSignals.SCORE_PLATEAU)).isFalse();
        assertThat(getStoredContext().plateauCycles()).isEqualTo(0);
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
