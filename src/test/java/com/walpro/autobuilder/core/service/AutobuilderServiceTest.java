package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.bootstrap.BuildSystemBootstrap;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SeedCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
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

    @Mock
    private com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService criteriaVerificationService;

    @InjectMocks
    private AutobuilderService autobuilderService;

    @org.junit.jupiter.api.BeforeEach
    void stubVerificationDefaultEmpty() {
        // @InjectMocks uses constructor injection and does not also inject the optional
        // @Setter field — wire it explicitly.
        autobuilderService.setCriteriaVerificationService(criteriaVerificationService);
        org.mockito.Mockito.lenient().when(criteriaVerificationService.verifyAndRecord(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of());
    }

    @org.junit.jupiter.api.BeforeEach
    void stubConvergenceTrackerPassThrough() {
        org.mockito.Mockito.lenient().when(rootConvergenceTracker.track(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void runExclusively_runsTheActionAndReturnsItsValueWhenLockIsFree() {
        assertThat(autobuilderService.runExclusively(() -> "applied")).contains("applied");
    }

    @Test
    void runExclusively_refusesWhileAnotherThreadHoldsTheCycleLock() throws Exception {
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> autobuilderService.runExclusively(() -> {
            acquired.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(autobuilderService.runExclusively(() -> "should not run")).isEmpty();
        } finally {
            release.countDown();
            holder.join(5_000);
        }
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
        assertThat(sentToPrepare.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.10)).isTrue();
        assertThat(sentToPrepare.hasStrongRequest(BasicSignals.DIVERGENCE_REQUEST, 0.10)).isTrue();
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
        assertThat(sentToPrepare.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        assertThat(sentToPrepare.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.10)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_requiredWithRetryBudget_retriesInsteadOfGivingUp() {
        // T2-EI slice 1b: a REQUIRED subgoal must NOT be given up on its first exhaustion when
        // retries are enabled — it gets a fresh attempt (stagnation reset) instead. The
        // 2026-06-27 reliability batch died 3/5 because a single required subgoal exhausted once
        // and cascaded to root GIVEN_UP. Budget is relative: one attempt per unmet criterion.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "subgoalRetryEnabled", true);
        when(goalPlannerService.countUnmetCriteria("sub-1")).thenReturn(1);
        // isExtraOriginSubgoal defaults to false on the mock → "sub-1" is treated as REQUIRED.
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        // First exhaustion within budget → retry: no give-up, stagnation history reset, signal cleared.
        verify(goalPlannerService, never()).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
        verify(goalPlannerService).resetGoalMetadataInt("sub-1", "cumulativeStagnatingCycles");
        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_requiredOverRetryBudget_givesUp() {
        // T2-EI slice 1b: once the per-leaf retry budget (= unmet criteria, here 1) is spent,
        // the subgoal is given up as before.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "subgoalRetryEnabled", true);
        when(goalPlannerService.countUnmetCriteria("sub-1")).thenReturn(1);
        // Pre-seed one used retry so this exhaustion (used=2) exceeds the budget of 1.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> retryMap = (java.util.Map<String, Integer>)
                org.springframework.test.util.ReflectionTestUtils.getField(autobuilderService, "subgoalRetryCount");
        retryMap.put("sub-1", 1);

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
    }

    @Test
    void runAutobuilderCycle_retryBudgetScalesWithUnmetCriteria() {
        // The budget is relative ([[feedback-relative-thresholds]]): a subgoal with 3 unmet
        // criteria gets a 3rd fresh attempt where a 1-criterion subgoal would already give up.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "subgoalRetryEnabled", true);
        when(goalPlannerService.countUnmetCriteria("sub-1")).thenReturn(3);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> retryMap = (java.util.Map<String, Integer>)
                org.springframework.test.util.ReflectionTestUtils.getField(autobuilderService, "subgoalRetryCount");
        retryMap.put("sub-1", 2); // this exhaustion is attempt 3 of budget 3

        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
        verify(goalPlannerService).resetGoalMetadataInt("sub-1", "cumulativeStagnatingCycles");
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_extraOriginIgnoresRetryBudget_givesUp() {
        // T2-EI slice 1b: EXTRA-origin subgoals skip the retry budget — they are absorbed at closure
        // (slice 1a), so spending retries on them is wasted; keep the eager give-up.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "subgoalRetryEnabled", true);
        when(goalPlannerService.isExtraOriginSubgoal("sub-1")).thenReturn(true);

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
        verify(goalPlannerService, never()).resetGoalMetadataInt("sub-1", "cumulativeStagnatingCycles");
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_divideOnExhaustion_dividesInsteadOfGivingUp() {
        // T2-EJ: retries spent + verification graded all criteria unmet (the 0-pass case) →
        // divide into per-criterion subgoals instead of giving up; exhaustion signal cleared.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "divideOnExhaustion", true);
        when(criteriaVerificationService.verifyAndRecord(any(), eq("sub-1")))
                .thenReturn(java.util.Map.of(1, false, 2, false));
        when(goalPlannerService.divideExhaustedSubGoal(eq("sub-1"), anyList()))
                .thenReturn(java.util.List.of("d-1", "d-2"));

        CycleContextData exhaustedCtx = CycleContextData.builder()
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentGoalId("root-1")
                .currentSubGoalId("sub-1")
                .currentSubGoalCriteria(java.util.List.of("404 when book missing", "422 when member over loan limit"))
                
                .build();
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
        @SuppressWarnings("unchecked")
        var unmetCaptor = org.mockito.ArgumentCaptor.forClass((Class<java.util.List<String>>) (Class<?>) java.util.List.class);
        verify(goalPlannerService).divideExhaustedSubGoal(eq("sub-1"), unmetCaptor.capture());
        assertThat(unmetCaptor.getValue())
                .containsExactlyInAnyOrder("404 when book missing", "422 when member over loan limit");
        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(CycleContextData.class);
        verify(contextCoordinator).prepareRuntimeContext(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_divideOnExhaustion_declinedDivision_givesUp() {
        // T2-EJ: the division mechanism declining (e.g. < 2 distinct criteria, already divided)
        // means the normal give-up proceeds.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "divideOnExhaustion", true);
        when(criteriaVerificationService.verifyAndRecord(any(), eq("sub-1")))
                .thenReturn(java.util.Map.of(1, false));
        when(goalPlannerService.divideExhaustedSubGoal(eq("sub-1"), anyList()))
                .thenReturn(java.util.List.of());

        CycleContextData exhaustedCtx = CycleContextData.builder()
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentGoalId("root-1")
                .currentSubGoalId("sub-1")
                .currentSubGoalCriteria(java.util.List.of("only criterion"))
                
                .build();
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_divideOnExhaustion_atRunawayCap_givesUp() {
        // T2-EJ: division counts toward the exhaustion runaway brake like the residue path —
        // at the cap the give-up wins and division is not even attempted.
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService, "divideOnExhaustion", true);
        org.springframework.test.util.ReflectionTestUtils.setField(autobuilderService,
                "consecutiveSubgoalExhaustionsWithoutMerge", 4); // max default 5 → 4+1 not < 5

        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).divideExhaustedSubGoal(anyString(), anyList());
        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString());
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
        assertThat(sentToPrepare.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        assertThat(sentToPrepare.goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_outputVerificationPassesAll_promotesToFulfilled() {
        // T4-BP step 3 (promoting direction): banked criteria say "unmet" because the work merged
        // under sibling subgoals, but verification against the actual output passes everything —
        // promote instead of giving up (2026-06-11 22:11 field case: POST /loans/{id}/return
        // given up with "unmet criteria" while the endpoint sat implemented on disk).
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        when(goalPlannerService.areCriteriaCumulativelySatisfied("sub-1")).thenReturn(false);
        when(criteriaVerificationService.verifyAndRecord(any(), eq("sub-1")))
                .thenReturn(java.util.Map.of(1, true, 2, true, 3, true));

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).saveCriteriaPassStatus("sub-1", java.util.Map.of(1, true, 2, true, 3, true));
        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.FULFILLED,
                "exhausted but output verification confirmed all acceptance criteria");
        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.GIVEN_UP));
        verify(goalPlannerService, never()).exhaustParentIfStuck(any(), anyInt());
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhausted_outputVerificationPartial_banksAndStillGivesUp() {
        CycleContextData exhaustedCtx = contextWithResultAndGoals(
                CycleResultState.SUBGOAL_EXHAUSTED, "root-1", "sub-1");
        setStoredContext(exhaustedCtx);

        when(goalPlannerService.areCriteriaCumulativelySatisfied("sub-1")).thenReturn(false);
        when(criteriaVerificationService.verifyAndRecord(any(), eq("sub-1")))
                .thenReturn(java.util.Map.of(1, true, 2, false));

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        // The proven criterion is banked so later checks see real coverage; the give-up proceeds.
        verify(goalPlannerService).saveCriteriaPassStatus("sub-1", java.util.Map.of(1, true, 2, false));
        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), any());
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
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(stored)).isTrue();
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
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(stored)).isTrue();
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
                    
                    .build();
            return Optional.of(prepared);
        });
        when(cycleExecutor.runCycle(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // Cycle 1 sets the baseline (plateauCycles stays 0); cycles 2-5 increment to 4
        for (int i = 0; i < 5; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(getStoredContext().hasOpenRequest(BasicSignals.SCORE_PLATEAU)).isFalse();
        assertThat(getStoredContext().plateauCycles()).isEqualTo(4);

        // 6th flat cycle — plateau=5 >= THRESHOLD → signal fires
        autobuilderService.runAutobuilderCycle();
        assertThat(getStoredContext().hasOpenRequest(BasicSignals.SCORE_PLATEAU)).isTrue();
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
        assertThat(getStoredContext().hasOpenRequest(BasicSignals.SCORE_PLATEAU)).isFalse();
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
        assertThat(ctxCaptor.getValue().hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
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
    void runAutobuilderCycle_whenPotentiallyComplete_confirmed_promotesSubgoalAndContinues() {
        // T2-D redesign: the completion belief is subgoal-scoped. Verification confirms it →
        // promote THAT subgoal and keep cycling; no system pause (2026-06-11 22:55 field case:
        // a correct leaf-level belief paused a healthy run at 60% spec coverage).
        CycleContextData ctx = CycleContextData.builder()
                .cycleResultState(CycleResultState.CONTINUE)
                .currentSubGoalId("sub-1")
                .currentSubGoalDescription("Create Member entity class")
                
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.POTENTIALLY_COMPLETE, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));
        setStoredContext(ctx);
        when(criteriaVerificationService.verifyAndRecord(any(), eq("sub-1")))
                .thenReturn(java.util.Map.of(1, true));

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.FULFILLED,
                "analysis completion belief confirmed by output verification");
        // Criteria-less goal: the verified description is declared as its criterion + banked.
        verify(goalPlannerService).setAcceptanceCriteria("sub-1",
                java.util.List.of("Create Member entity class"));
        verify(goalPlannerService).saveCriteriaPassStatus("sub-1", java.util.Map.of(1, true));
        // The loop keeps running — no system pause.
        verify(cycleExecutor).runCycle(prepared);
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenPotentiallyComplete_notConfirmed_dropsBeliefAndContinues() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleResultState(CycleResultState.CONTINUE)
                .currentSubGoalId("sub-1")
                .currentSubGoalDescription("Create Member entity class")
                
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.POTENTIALLY_COMPLETE, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));
        setStoredContext(ctx);
        when(criteriaVerificationService.verifyAndRecord(any(), eq("sub-1")))
                .thenReturn(java.util.Map.of()); // no observation

        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(afterRun)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).applyGoalProgress(any(), eq(GoalProgressState.FULFILLED), any());
        verify(cycleExecutor).runCycle(prepared);
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isFalse();
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
        assertThat(sentToPrepare.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(sentToPrepare.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenSubgoalExhaustedWithNullSubGoalId_haltsImmediately() {
        // T1-AR Fix 1: SUBGOAL_EXHAUSTED + null currentSubGoalId → halt with SYSTEM_PAUSED
        CycleContextData exhaustedCtx = CycleContextData.builder()
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentGoalId("root-1")
                .currentSubGoalId(null)
                
                .build();
        setStoredContext(exhaustedCtx);

        autobuilderService.runAutobuilderCycle();

        // Must NOT try to give up a null subgoal
        verify(goalPlannerService, never()).applyGoalProgress(any(), any());
        // Must NOT proceed to run another cycle
        verify(cycleExecutor, never()).runCycle(any());
        // System must be paused
        CycleContextData stored = getStoredContext();
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(stored)).isTrue();
        assertThat(DerivedFacts.goalExhausted(stored)).isTrue();
    }

    @Test
    void runAutobuilderCycle_whenEvolutionNoOp_promotesSubgoalToFulfilled() {
        // T2-CC / T1-DN: #NoDiff with null buildOutcome and null criteriaStatus passes the
        // T1-DN gate (deferred-merge case — no concrete observation of failure) and promotes to FULFILLED.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sub-1")
                
                .buildOutcome(null)
                .cumulativeCriteriaStatus(null)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

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
        assertThat(stored.hasOpenRequest(BasicSignals.EVOLUTION_NOOP)).isFalse();
        assertThat(stored.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isFalse();
    }

    @Test
    void runAutobuilderCycle_whenEvolutionNoOp_withNullSubgoal_doesNothing() {
        // T2-CC safety: null currentSubGoalId → no promotion attempt
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId(null)
                
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

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
                
                .buildOutcome(failedBuild)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).transitionGoal(any(), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(stored.hasOpenRequest(BasicSignals.EVOLUTION_NOOP)).isFalse();
    }

    @Test
    void runCycle_noOpWithUnmetCriteriaPassStatus_T1DN_refusesPromotion() {
        // T1-DN outer gate: cumulativeCriteriaStatus has a FALSE entry → refuse promotion.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("criteria-sub")
                
                .cumulativeCriteriaStatus(Map.of(0, true, 1, false))
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).transitionGoal(any(), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
    }

    @Test
    void runCycle_noOpWithCleanBuildAndAllCriteriaPassing_promotesAsBefore() {
        // T1-DN regression: build ok + all criteria pass → promotion proceeds normally.
        BuildOutcome okBuild = new BuildOutcome(BuildResult.COMPILE_OK, "", 50L, "mvn", null);
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("ok-sub")
                
                .buildOutcome(okBuild)
                .cumulativeCriteriaStatus(Map.of(0, true, 1, true))
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

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
                
                .buildOutcome(null)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "deferred-merge noOp", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).transitionGoal("deferred-sub",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
    }

    @Test
    void runCycle_noOpWithFreshVerificationUnmet_refusesPromotion_andBanksPassMap() {
        // Run 18 (2026-07-06): the T4-BP-1 pass used to run AFTER the promotion decision —
        // recording only — so its ✗ landed on an already-COMPLETED goal, a closed-but-unmet
        // contradiction the closure edge could only give up the root on. A fresh unmet
        // criterion must refuse the promotion, and the pass map must be banked so the retry
        // ladder and the evolution prompt see exactly the unmet work.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("fresh-unmet-sub")
                
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));
        when(criteriaVerificationService.verifyAndRecord(any(), eq("fresh-unmet-sub")))
                .thenReturn(Map.of(1, true, 2, false));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService, never()).transitionGoal(any(), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        verify(goalPlannerService).saveCriteriaPassStatus("fresh-unmet-sub", Map.of(1, true, 2, false));
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(stored.hasOpenRequest(BasicSignals.EVOLUTION_NOOP)).isFalse();
    }

    @Test
    void runCycle_noOpWithFreshVerificationAllMet_promotes_andBanksPassMap() {
        // The positive side of the run-18 gate: verification confirms every criterion against
        // the current output → the promotion proceeds and the passes are banked.
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("fresh-met-sub")
                
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));
        when(criteriaVerificationService.verifyAndRecord(any(), eq("fresh-met-sub")))
                .thenReturn(Map.of(1, true, 2, true));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).saveCriteriaPassStatus("fresh-met-sub", Map.of(1, true, 2, true));
        verify(goalPlannerService).transitionGoal("fresh-met-sub",
                com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
    }

    // --- T1-EE: EVOLUTION_NOOP signal cascade ---

    @Test
    void t1ee_staleNoOpSignal_doesNotPromoteWrongSubgoal() {
        // T1-EE regression: EvolutionAgent emits EVOLUTION_NOOP for sg-A. Evaluation then
        // promotes sg-A to FULFILLED mid-cycle, advancing the pointer to sg-B.
        // AutobuilderService must NOT promote sg-B via the #NoDiff bypass.
        ChannelRequest staleNoOp = ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", null)
                .withMeta("subGoalId", "sg-A");
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sg-B")   // pointer was advanced by evaluation mid-cycle
                .build()
                .issueRequest(staleNoOp);

        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(afterRun);
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        // sg-B must NOT be promoted — the #NoDiff observation was for sg-A
        verify(goalPlannerService, never()).transitionGoal(eq("sg-B"), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), any());
        // EVOLUTION_NOOP signal must still be consumed (removed from context)
        CycleContextData stored = getStoredContext();
        assertThat(stored.hasOpenRequest(BasicSignals.EVOLUTION_NOOP)).isFalse();
    }

    @Test
    void t1ee_matchingNoOpSignal_promotesCorrectSubgoal() {
        // T1-EE: signal subGoalId matches currentSubGoalId → promotion must proceed normally.
        ChannelRequest matchingNoOp = ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "evolution-noop", null)
                .withMeta("subGoalId", "sg-A");
        CycleContextData afterRun = CycleContextData.builder()
                .currentSubGoalId("sg-A")
                .buildOutcome(null)
                .build()
                .issueRequest(matchingNoOp);

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
                
                .buildOutcome(null)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "deferred-merge noOp", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

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
                        
                        .build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // 4 zero-work cycles — no halt yet
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        CycleContextData stored = getStoredContext();
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(stored)).isFalse();

        // 5th zero-work cycle — halt fires
        autobuilderService.runAutobuilderCycle();
        stored = getStoredContext();
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(stored))
                .as("SYSTEM_PAUSED must be set after N zero-work cycles with BUILD_BROKEN")
                .isTrue();
        assertThat(DerivedFacts.goalExhausted(stored)).isTrue();
    }

    @Test
    void t1eb_mergeResetsZeroWorkCounter() {
        // T1-EB: a merge must reset the counter so it doesn't accumulate across non-contiguous
        // zero-work stretches.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // 4 zero-work cycles
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }

        // cycle that merges — resets counter
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().merged(true).build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        autobuilderService.runAutobuilderCycle();

        // 4 more zero-work cycles after merge — counter reset, still no halt
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isFalse();
    }

    @Test
    void t1eb_withoutBuildBroken_doesNotFire() {
        // T1-EB: zero-work cycles without BUILD_BROKEN must not trigger the deadlock halt.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().build());
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < 10; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isFalse();
    }

    @Test
    void t1eb_agentExecutionResetsCounter() {
        // T1-EB: a cycle where any agent ran (even without a merge) resets the zero-work counter.
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        // 4 zero-work cycles
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }

        // cycle where analysis ran — counter resets
        com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome analysisOutcome =
                com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome.empty("ran");
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder()
                        
                        .analysisOutcome(analysisOutcome)
                        .build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        autobuilderService.runAutobuilderCycle();

        // 4 more zero-work cycles — counter reset, still no halt
        when(cycleExecutor.runCycle(any())).thenReturn(
                CycleContextData.builder().build()
                        .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null)));
        for (int i = 0; i < 4; i++) {
            autobuilderService.runAutobuilderCycle();
        }
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isFalse();
    }

    @Test
    void t1fl_breakerPauseResumesWhenTheCooldownHasElapsed() {
        // T1-FL: the breaker's timed open (T1-FD) owns retry timing — once isOpen() flips
        // false, the breaker-caused pause is over and the loop cycles again.
        setStoredContext(contextWithResult(CycleResultState.STAGNATING)
                .withPausedReason("llm-circuit-breaker"));
        when(llmCircuitBreaker.isOpen()).thenReturn(false);
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(emptyContext());
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor).runCycle(any());
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isFalse();
    }

    @Test
    void t1fl_breakerPauseStaysPausedWhileTheBreakerIsStillOpen() {
        setStoredContext(contextWithResult(CycleResultState.STAGNATING)
                .withPausedReason("llm-circuit-breaker"));
        when(llmCircuitBreaker.isOpen()).thenReturn(true);

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isTrue();
    }

    @Test
    void t1fl_nonBreakerPauseNeverAutoResumes() {
        // A user-pause (or any exhaustion halt) must stay paused even when the breaker is closed
        // (the mocked breaker reports closed by default; the reason check short-circuits first).
        setStoredContext(contextWithResult(CycleResultState.STAGNATING)
                .withPausedReason("user-pause"));

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isTrue();
    }

    @Test
    void t4cd_endReportWrittenOncePerPauseEpisode_atTheHaltTerminal() {
        // T4-CD slice 2: the pause episode's skip branch writes the end report exactly once,
        // with the episode's own pause reason, after T4-CA preserve.
        var endReportService = mock(com.walpro.autobuilder.core.persistence.RunEndReportService.class);
        autobuilderService.setRunEndReportService(endReportService);
        setStoredContext(contextWithResult(CycleResultState.STAGNATING)
                .withPausedReason("exhaustion-runaway"));

        autobuilderService.runAutobuilderCycle();
        autobuilderService.runAutobuilderCycle(); // second paused tick — still one end report

        verify(endReportService, times(1)).write(any(), org.mockito.ArgumentMatchers.eq("exhaustion-runaway"));
        verify(cycleExecutor, never()).runCycle(any());
    }

    @Test
    void t4cd_endReportWrittenAtTheFulfilledTerminal() {
        var endReportService = mock(com.walpro.autobuilder.core.persistence.RunEndReportService.class);
        autobuilderService.setRunEndReportService(endReportService);
        setStoredContext(contextWithResult(CycleResultState.GOAL_FULFILLED));

        autobuilderService.runAutobuilderCycle();
        autobuilderService.runAutobuilderCycle();

        verify(endReportService, times(1)).write(any(), org.mockito.ArgumentMatchers.eq("goal-fulfilled"));
    }

    @Test
    void t4ca_preserveCalledAtTheFulfilledTerminal_beforeEndReport() {
        // T4-CA gap fix (2026-07-24): the GOAL_FULFILLED pause path is an in-process halt and must
        // preserve the best snapshot too. It used to skip preserve because a fulfilled run carries
        // no pausedReason and never enters the generic DerivedFacts.systemPaused branch — surfaced
        // by a field run (2026-07-24_10-24-15, fulfilled, no artifact_preserved event). Ordering
        // mirrors that branch: preserve BEFORE the end report so the on-disk provenance is this
        // episode's own. The second paused tick must NOT preserve again (one preserve per episode).
        var preservedService = mock(com.walpro.autobuilder.core.persistence.PreservedArtifactService.class);
        var endReportService = mock(com.walpro.autobuilder.core.persistence.RunEndReportService.class);
        autobuilderService.setPreservedArtifactService(preservedService);
        autobuilderService.setRunEndReportService(endReportService);
        setStoredContext(contextWithResult(CycleResultState.GOAL_FULFILLED));

        autobuilderService.runAutobuilderCycle();
        autobuilderService.runAutobuilderCycle();

        var inOrder = org.mockito.Mockito.inOrder(preservedService, endReportService);
        inOrder.verify(preservedService, times(1)).preserve(any());
        inOrder.verify(endReportService, times(1)).write(any(), org.mockito.ArgumentMatchers.eq("goal-fulfilled"));
    }

    @Test
    void t4cd_noEndReportServiceWired_haltPathUnchanged() {
        setStoredContext(contextWithResult(CycleResultState.STAGNATING)
                .withPausedReason("user-pause"));

        autobuilderService.runAutobuilderCycle(); // must not throw

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(getStoredContext())).isTrue();
    }

    @Test
    void t2eo_stagnationRunaway_verifyBeforePausePromotes_doesNotHalt() {
        // T2-EO: structure-described subgoals whose structure
        // already exists — evolution's cosmetic diffs get rejected as no-meaningful-change,
        // the counter climbs to the runaway threshold, and the run used to halt over work
        // verification would confirm. The rescue promoting ANY subgoal must avert the pause.
        CycleContextData stagnatingCtx = contextWithResultAndGoals(CycleResultState.STAGNATING, "root-1", "sub-1");
        setStoredContext(stagnatingCtx);
        setIntField("consecutiveStagnatingCyclesWithoutMerge", 9);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new GoalPlannerService.VerifyBeforePauseResult(false, 2));
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.of(emptyContext()));
        when(cycleExecutor.runCycle(any())).thenReturn(emptyContext());
        when(cyclePersistenceService.persistSnapshot(any())).thenAnswer(inv -> inv.getArgument(0));

        autobuilderService.runAutobuilderCycle();

        verify(goalPlannerService).verifyAndPromoteBeforePause(any());
        verify(cycleExecutor).runCycle(any()); // the loop kept going instead of pausing
        assertThat(getIntField("consecutiveStagnatingCyclesWithoutMerge")).isZero();
    }

    @Test
    void t2eo_stagnationRunaway_verifyAbstains_pausesAsBefore() {
        // An honest stall (verification abstains or confirms nothing) still halts — the
        // rescue must not weaken the brake, only stop it firing over completed work.
        CycleContextData stagnatingCtx = contextWithResultAndGoals(CycleResultState.STAGNATING, "root-1", "sub-1");
        setStoredContext(stagnatingCtx);
        setIntField("consecutiveStagnatingCyclesWithoutMerge", 9);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new GoalPlannerService.VerifyBeforePauseResult(false, 0));

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        assertThat(getStoredContext().pausedReason()).isEqualTo("stagnation-runaway");
    }

    // --- helpers ---

    private void setIntField(String name, int value) {
        try {
            var field = AutobuilderService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(autobuilderService, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int getIntField(String name) {
        try {
            var field = AutobuilderService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(autobuilderService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
                
                .build();
    }

    private static CycleContextData contextWithResultAndGoals(
            CycleResultState state, String goalId, String subGoalId) {
        return CycleContextData.builder()
                .cycleResultState(state)
                .currentGoalId(goalId)
                .currentSubGoalId(subGoalId)
                
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
