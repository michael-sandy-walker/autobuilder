package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.bootstrap.BuildSystemBootstrap;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
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
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import java.util.Map;
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
    private BuildSystemBootstrap buildSystemBootstrap;

    @Mock
    private FileService fileService;

    @Mock
    private OrchestrationSnapshotService orchestrationSnapshotService;

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

    @Mock
    private com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker rootConvergenceTracker;

    @Mock
    private com.walpro.autobuilder.goal.GoalConsolidationService goalConsolidationService;

    @InjectMocks
    private AutobuilderService autobuilderService;

    @org.junit.jupiter.api.BeforeEach
    void stubConvergenceTrackerPassThrough() {
        org.mockito.Mockito.lenient().when(rootConvergenceTracker.track(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

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
        // T1-BU: subgoal must be non-null; null-subgoal stagnation is a structural wait state
        // and skips the circuit-breaker count + recovery signal injection.
        CycleContextData stagnatingCtx = contextWithResultAndGoals(CycleResultState.STAGNATING, "root-1", "sub-1");
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

        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
        verify(cycleExecutor).runCycle(prepared);

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        CycleContextData sentToPrepare = ctxCaptor.getValue();
        assertThat(sentToPrepare.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        assertThat(sentToPrepare.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_withAllCriteriaMet_promotesToFulfilled() {
        // T1-CF: SUBGOAL_EXHAUSTED + all criteria cumulatively satisfied → FULFILLED, not GIVEN_UP
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        when(goalPlannerService.areCriteriaCumulativelySatisfied("sub-1")).thenReturn(true);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.FULFILLED,
                "exhausted but all criteria cumulatively satisfied");
        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.GIVEN_UP));
        verify(goalPlannerService, never()).exhaustParentIfStuck(any(), anyInt());
        verify(cycleExecutor).runCycle(prepared);

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        CycleContextData sentToPrepare = ctxCaptor.getValue();
        assertThat(sentToPrepare.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        assertThat(sentToPrepare.goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_resetsGoalProgressToNotEvaluated() {
        // Bug 2 regression: stale STAGNATING goalProgress on context caused GoalExhaustionDetector
        // Rule A to fire immediately on the next subgoal's first cycle, cascading all subgoals.
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1")
                .withGoalProgress(GoalProgressState.STAGNATING);
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
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

    @Test
    void runAutobuilderCycle_whenSubgoalExhaustedButMerged_suppressesEviction() {
        // T1-AR Fix 2 / T1-BY: SUBGOAL_EXHAUSTED + merged=true + suppressedCount below cap → do NOT give up
        CycleContextData exhaustedCtx = CycleContextData.builder()
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentGoalId("root-1")
                .currentSubGoalId("sub-1")
                .merged(true)
                .signals(new java.util.LinkedHashMap<>())
                .build();
        setStoredContext(exhaustedCtx);

        when(goalPlannerService.getGoalMetadataInt("sub-1", "consecutiveSuppressedEvictions")).thenReturn(0);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        // Must NOT call applyGoalProgress with GIVEN_UP
        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.GIVEN_UP));
        // Must increment the suppression counter
        verify(goalPlannerService).incrementGoalMetadataInt("sub-1", "consecutiveSuppressedEvictions");
        // Cycle must still run (not halted)
        verify(cycleExecutor).runCycle(prepared);
        // SUBGOAL_EXHAUSTED signal should be cleared from the context passed to prepare
        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhaustedButMerged_allowsEvictionAfterSuppressionCap() {
        // T1-BY: SUBGOAL_EXHAUSTED + merged=true but consecutiveSuppressedEvictions >= MAX (3)
        // → cap exceeded, eviction must proceed despite the merge.
        CycleContextData exhaustedCtx = CycleContextData.builder()
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentGoalId("root-1")
                .currentSubGoalId("sub-1")
                .merged(true)
                .signals(new java.util.LinkedHashMap<>())
                .build();
        setStoredContext(exhaustedCtx);

        when(goalPlannerService.getGoalMetadataInt("sub-1", "consecutiveSuppressedEvictions")).thenReturn(3);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        // Suppression cap exceeded — subgoal must be given up
        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
        // Counter must NOT be incremented further
        verify(goalPlannerService, never()).incrementGoalMetadataInt("sub-1", "consecutiveSuppressedEvictions");
        // Cycle still runs
        verify(cycleExecutor).runCycle(prepared);
    }

    @Test
    void runAutobuilderCycle_whenPotentiallyComplete_pausesForHumanReview() {
        // T2-D: POTENTIALLY_COMPLETE signal on stored context → pause loop, do not run another cycle
        CycleContextData ctx = CycleContextData.builder()
                .cycleResultState(CycleResultState.CONTINUE)
                .currentSubGoalId("sub-1")
                .signals(new java.util.LinkedHashMap<>())
                .build()
                .withSignal(Signal.of(BasicSignals.POTENTIALLY_COMPLETE, 1.0, 0.0, "test"));
        setStoredContext(ctx);

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenStagnatingWithNullSubgoal_skipsCircuitBreakerAndContinues() {
        // T1-BU: STAGNATING + null currentSubGoalId is a structural wait state (T1-AD recovery
        // gap — no agent can produce a merge). It must NOT count toward consecutiveStagnating
        // or re-activate the circuit breaker. The cycle still runs normally.
        CycleContextData stagnatingNullSubgoal = CycleContextData.builder()
                .cycleResultState(CycleResultState.STAGNATING)
                .currentGoalId("root-1")
                .currentSubGoalId(null)
                .signals(new java.util.LinkedHashMap<>())
                .build();
        setStoredContext(stagnatingNullSubgoal);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        // cycle must still run
        verify(cycleExecutor).runCycle(prepared);

        // no stagnation metadata increment for null subgoal
        verify(goalPlannerService, never()).incrementGoalMetadataInt(any(), any());

        // no REPLAN_REQUESTED or DIVERGENCE_REQUEST injected (null-subgoal break fires first)
        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        CycleContextData sentToPrepare = ctxCaptor.getValue();
        assertThat(sentToPrepare.hasSignal(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(sentToPrepare.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhaustedWithNullSubGoalId_haltsImmediately() {
        // T1-AR Fix 1: SUBGOAL_EXHAUSTED + null currentSubGoalId → halt with SYSTEM_PAUSED
        CycleContextData exhaustedCtx = CycleContextData.builder()
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentGoalId("root-1")
                .currentSubGoalId(null)
                .signals(new java.util.LinkedHashMap<>())
                .build();
        setStoredContext(exhaustedCtx);

        autobuilderService.runAutobuilderCycle();

        // Must NOT try to give up a null subgoal
        verify(goalPlannerService, never()).applyGoalProgress(any(), any());
        // Must NOT proceed to run another cycle
        verify(cycleExecutor, never()).runCycle(any());
        // System must be paused
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)).isTrue();
        assertThat(stored.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.50)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenEvolutionNoOp_promotesSubgoalToFulfilled() {
        // T2-CC / T1-DN: #NoDiff with null buildOutcome and null criteriaStatus passes the
        // T1-DN gate (deferred-merge case — no concrete evidence of failure) and promotes to FULFILLED.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sub-1")
                .signals(new java.util.LinkedHashMap<>())
                .buildOutcome(null)
                .cumulativeCriteriaStatus(null)
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        // T4-BC: #NoDiff close now routes through transitionGoal(FULFILLED) so criteria bank.
        verify(goalPlannerService).transitionGoal("sub-1",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.GIVEN_UP));
        // EVOLUTION_NOOP signal must be consumed and REPETITION_DETECTED must not be set
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasSignal(BasicSignals.EVOLUTION_NOOP)).isFalse();
        assertThat(stored.hasSignal(BasicSignals.REPETITION_DETECTED)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenEvolutionNoOp_withNullSubgoal_doesNothing() {
        // T2-CC safety: null currentSubGoalId → no promotion attempt
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId(null)
                .signals(new java.util.LinkedHashMap<>())
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).transitionGoal(any(), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
    }

    @Test
    void runCycle_noOpWithFailingBuild_T1DN_refusesPromotion_forcesDivergence() {
        // T1-DN outer gate: buildOutcome.failed() == true → refuse promotion and emit DIVERGENCE_REQUEST.
        BuildOutcome failedBuild = new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR] no POM", 100L, "mvn", null);
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("bf-sub")
                .signals(new java.util.LinkedHashMap<>())
                .buildOutcome(failedBuild)
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).transitionGoal(any(), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(stored.hasSignal(BasicSignals.EVOLUTION_NOOP)).isFalse();
    }

    @Test
    void runCycle_noOpWithUnmetCriteriaPassStatus_T1DN_refusesPromotion() {
        // T1-DN outer gate: cumulativeCriteriaStatus has a FALSE entry → refuse promotion.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("criteria-sub")
                .signals(new java.util.LinkedHashMap<>())
                .cumulativeCriteriaStatus(Map.of(0, true, 1, false))
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).transitionGoal(any(), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
    }

    @Test
    void runCycle_noOpWithCleanBuildAndAllCriteriaPassing_promotesAsBefore() {
        // T1-DN regression: build ok + all criteria pass → promotion proceeds normally.
        BuildOutcome okBuild = new BuildOutcome(BuildResult.COMPILE_OK, "", 50L, "mvn", null);
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("ok-sub")
                .signals(new java.util.LinkedHashMap<>())
                .buildOutcome(okBuild)
                .cumulativeCriteriaStatus(Map.of(0, true, 1, true))
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).transitionGoal("ok-sub",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
    }

    @Test
    void runCycle_noOpDeferredMergeCase_nullCriteriaPassStatus_promotesAsBefore() {
        // T1-DN regression: null buildOutcome + null criteriaStatus = original deferred-merge case;
        // gate passes through and promotion proceeds.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("deferred-sub")
                .signals(new java.util.LinkedHashMap<>())
                .buildOutcome(null)
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "deferred-merge noOp"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).transitionGoal("deferred-sub",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
    }

    // --- T1-EE: EVOLUTION_NOOP signal cascade ---

    @Test
    void t1ee_staleNoOpSignal_doesNotPromoteWrongSubgoal() {
        // T1-EE regression: EvolutionAgent emits EVOLUTION_NOOP for sg-A. Evaluation then
        // promotes sg-A to FULFILLED mid-cycle, advancing the pointer to sg-B.
        // AutobuilderService must NOT promote sg-B via the #NoDiff bypass.
        Signal staleNoOp = Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop")
                .withMeta("subGoalId", "sg-A");
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sg-B")   // pointer was advanced by evaluation mid-cycle
                .signals(new java.util.LinkedHashMap<>())
                .build()
                .withSignal(staleNoOp);

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        // sg-B must NOT be promoted — the #NoDiff evidence was for sg-A
        verify(goalPlannerService, never()).transitionGoal(eq("sg-B"), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        // EVOLUTION_NOOP signal must still be consumed (removed from context)
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasSignal(BasicSignals.EVOLUTION_NOOP)).isFalse();
    }

    @Test
    void t1ee_matchingNoOpSignal_promotesCorrectSubgoal() {
        // T1-EE: signal subGoalId matches currentSubGoalId → promotion must proceed normally.
        Signal matchingNoOp = Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "evolution-noop")
                .withMeta("subGoalId", "sg-A");
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sg-A")
                .signals(new java.util.LinkedHashMap<>())
                .buildOutcome(null)
                .build()
                .withSignal(matchingNoOp);

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).transitionGoal("sg-A",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
    }

    @Test
    void t1ee_noOpSignalWithoutMeta_promotesCurrentSubgoal() {
        // T1-EE back-compat: signals without subGoalId meta (e.g. from older code paths or
        // the deferred-merge case) still trigger promotion for the current subgoal.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sg-legacy")
                .signals(new java.util.LinkedHashMap<>())
                .buildOutcome(null)
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.0, "deferred-merge noOp"));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).transitionGoal("sg-legacy",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
    }

    // --- T1-EB: build-blocked deadlock breaker ---

    @Test
    void t1eb_consecutiveZeroWorkCycles_withBuildBroken_pausesAfterLimit() {
        // T1-EB: after zeroWorkDeadlockLimit (default 5) consecutive zero-work cycles while
        // BUILD_BROKEN is active, emit GOAL_EXHAUSTED + SYSTEM_PAUSED.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        // Each cycle returns: BUILD_BROKEN active, no merge, no mutation, no agent outcomes
        when(cycleExecutor.runCycle(any())).thenAnswer(inv ->
                CycleContextData.builder()
                        .signals(new java.util.LinkedHashMap<>())
                        .build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // 4 zero-work cycles — no halt yet
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.5)).isFalse();

        // 5th zero-work cycle — halt fires
        autobuilderService.runAutobuilderCycle();
        stored = getStoredContext();
        assertThat(stored.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.5))
                .as("SYSTEM_PAUSED must be set after N zero-work cycles with BUILD_BROKEN")
                .isTrue();
        assertThat(stored.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.5)).isTrue();
    }

    @Test
    void t1eb_mergeResetsZeroWorkCounter() {
        // T1-EB: a merge must reset the counter so it doesn't accumulate across non-contiguous
        // zero-work stretches.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // 4 zero-work cycles
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().signals(new java.util.LinkedHashMap<>()).build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }

        // cycle that merges — resets counter
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().signals(new java.util.LinkedHashMap<>()).merged(true).build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        autobuilderService.runAutobuilderCycle();

        // 4 more zero-work cycles after merge — counter reset, still no halt
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().signals(new java.util.LinkedHashMap<>()).build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(getStoredContext().hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.5)).isFalse();
    }

    @Test
    void t1eb_withoutBuildBroken_doesNotFire() {
        // T1-EB: zero-work cycles without BUILD_BROKEN must not trigger the deadlock halt.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().signals(new java.util.LinkedHashMap<>()).build());
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < 10; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(getStoredContext().hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.5)).isFalse();
    }

    @Test
    void t1eb_agentExecutionResetsCounter() {
        // T1-EB: a cycle where any agent ran (even without a merge) resets the zero-work counter.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // 4 zero-work cycles
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().signals(new java.util.LinkedHashMap<>()).build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }

        // cycle where analysis ran — counter resets
        com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome analysisOutcome =
                com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome.empty("ran");
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder()
                        .signals(new java.util.LinkedHashMap<>())
                        .analysisOutcome(analysisOutcome)
                        .build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        autobuilderService.runAutobuilderCycle();

        // 4 more zero-work cycles — counter reset, still no halt
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().signals(new java.util.LinkedHashMap<>()).build()
                        .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test")));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(getStoredContext().hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.5)).isFalse();
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
