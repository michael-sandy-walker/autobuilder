package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.ReplanPatternDetector;
import com.walpro.autobuilder.analysis.pattern.ScopeThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeGuard;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CycleExecutorTest {

    @Mock private AdaptiveExecutionEngine adaptiveExecutionEngine;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;
    @Mock private InterpretationService interpretationService;
    @Mock private CycleScoreRecorder cycleScoreRecorder;
    @Mock private GoalCompletionDecider goalCompletionDecider;
    @Mock private PatternSignalAnalyzer patternSignalAnalyzer;
    @Mock private PlanningThrashSignalAnalyzer planningThrashSignalAnalyzer;
    @Mock private ReplanPatternDetector replanPatternDetector;
    @Mock private ScopeThrashSignalAnalyzer scopeThrashSignalAnalyzer;
    @Mock private SubgoalScopeGuard subgoalScopeGuard;
    @Mock private MutationSnapshotService mutationSnapshotService;
    @org.mockito.Spy private com.walpro.autobuilder.core.build.BuildFixEscalator buildFixEscalator
            = new com.walpro.autobuilder.core.build.BuildFixEscalator();

    @InjectMocks
    private CycleExecutor cycleExecutor;

    @BeforeEach
    void setUpMutationSnapshotService() throws Exception {
        // T1-CT: persistEvaluationResultToMeta reads the existing meta.json to preserve
        // currentSubGoalId and merged=false. Return null to safely skip the write in unit
        // tests that do not exercise the filesystem path.
        org.mockito.Mockito.lenient()
                .when(mutationSnapshotService.readMutationMeta(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
    }

    private CycleContextData baseContext(String cycleName) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build();
    }

    private void stubGoalSync(String rootId, String rootDesc, String subId, String subDesc) {
        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn(rootId);
        when(root.getDescription()).thenReturn(rootDesc);

        GoalEntity sub = mock(GoalEntity.class);
        when(sub.getId()).thenReturn(subId);
        when(sub.getDescription()).thenReturn(subDesc);

        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sub);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent-0"));
    }

    private void stubIdentityPipeline() {
        when(adaptiveExecutionEngine.runCycle(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        // default completion behavior: not fulfilled
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("n/a"));
        when(goalCompletionDecider.decide(any(), anyBoolean()))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // Pass-through so it never nulls ctx in unrelated tests
        when(subgoalScopeGuard.guard(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
    }

    @Test
    void runCycle_alwaysSyncsGoalScopeAndAttemptIdsIntoContext() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.currentGoalId()).isEqualTo("goal-root");
        assertThat(out.currentGoalDescription()).isEqualTo("Root goal");
        assertThat(out.currentSubGoalId()).isEqualTo("sub-1");
        assertThat(out.currentSubGoalDescription()).isEqualTo("Sub goal 1");
        assertThat(out.lastAttemptId()).isEqualTo("attempt-1");
        assertThat(out.parentAttemptId()).isEqualTo("attempt-parent-0");
    }

    @Test
    void runCycle_whenSystemPaused_shortCircuits_andOnlyTicksSignals() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        // NOTE: no identity pipeline stubs here — they must remain unused.

        CycleContextData input = baseContext("cycle-42")
                .withPausedReason("test");

        CycleContextData out = cycleExecutor.runCycle(input);

        // the pause toggle is sticky — the paused cycle must not clear it
        assertThat(DerivedFacts.systemPaused(out)).isTrue();

        verify(goalPlannerService).setCurrentCycleContext(input);
        verifyNoInteractions(adaptiveExecutionEngine, patternSignalAnalyzer, planningThrashSignalAnalyzer,
                replanPatternDetector, scopeThrashSignalAnalyzer, subGoalFulfillmentHeuristic, goalCompletionDecider, cycleScoreRecorder, interpretationService);
    }

    @Test
    void runCycle_whenEvaluationInterpretationPresent_writesMutationStateAndGoalProgress() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        // T1-DC: EvaluationAgent now writes mutationState/goalProgress to the context
        // directly inside the signal-driven runCycle. Simulate that here.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING);
        });

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("n/a"));
        when(goalCompletionDecider.decide(any(), anyBoolean()))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(out.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
    }

    @Test
    void runCycle_whenNotAccepted_doesNotExecuteMerge() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");

        stubIdentityPipeline();

        // analyzer sets REJECTED
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.REJECTED);
        });

        // No ROLE_MERGE signal and no matching fingerprints: mutationState=REJECTED guard fires.
        // (With ROLE_MERGE present, merge would be attempted — T1-CD ensures no rejected content
        // is merged regardless, so the eligibility gate alone is the guard in that scenario.)
        CycleContextData context = baseContext("cycle-1");

        cycleExecutor.runCycle(context);

        verify(adaptiveExecutionEngine, never()).tryExecuteRole(eq("merge"), any());
    }

    @Test
    void runCycle_whenAcceptedButNoEvolutionAndNoMergeRequest_doesNotExecuteMerge() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");

        stubIdentityPipeline();

        // analyzer sets ACCEPTED but does not add EVOLUTION_OCCURRED
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED);
        });

        cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(adaptiveExecutionEngine, never()).tryExecuteRole(eq("merge"), any());
    }

    @Test
    void runCycle_whenAcceptedAndFingerprintsMatch_executesMerge_evenWithoutEvolutionOccurredSignal() {
        // Regression: accepted-not-merged deadlock. EVOLUTION_OCCURRED decays away after the cycle
        // where evaluation ran, but the fingerprint equality tells us the accepted evaluation is
        // still for the latest (unmerged) evolution. Auto-merge must fire to clear the gate.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED);
            // no EVOLUTION_OCCURRED — simulates a cycle after the signal decayed
        });

        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), any()))
                .thenAnswer(inv -> inv.getArgument(1, CycleContextData.class));

        // fingerprints match: latest evolution was accepted but not yet merged
        CycleContextData ctx = baseContext("cycle-1")
                .withLastEvolutionFingerprint("fp-abc")
                .withLastEvaluatedEvolutionFingerprint("fp-abc");

        cycleExecutor.runCycle(ctx);

        verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), any());
    }

    @Test
    void runCycle_whenAcceptedAndFingerprintsMismatch_doesNotExecuteMerge() {
        // Fingerprints differ = evaluation is for an older evolution; do not auto-merge stale state.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED);
        });

        CycleContextData ctx = baseContext("cycle-1")
                .withLastEvolutionFingerprint("fp-new")
                .withLastEvaluatedEvolutionFingerprint("fp-old");

        cycleExecutor.runCycle(ctx);

        verify(adaptiveExecutionEngine, never()).tryExecuteRole(eq("merge"), any());
    }

    @Test
    void runCycle_whenAcceptedAndEvolutionOccurred_executesMerge_andInjectsRoleMergeIfMissing() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withEvolutionRanThisCycle(true)
                    .withUnmergedEvolutionWork(true);
        });

        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), any()))
                .thenAnswer(inv -> inv.getArgument(1, CycleContextData.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        ArgumentCaptor<CycleContextData> ctxCaptor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), ctxCaptor.capture());

        CycleContextData mergeArg = ctxCaptor.getValue();
        ParticipationWindow mergeWindow = mergeArg.windows().get("merge");

        assertThat(mergeWindow).isNotNull();
        assertThat(mergeWindow.boost()).isEqualTo(1.0);
        // same-cycle window: must not bleed into the next cycle (old full-decay 1:1)
        assertThat(mergeWindow.validForCycles()).isEqualTo(1);
    }

    @Test
    void runCycle_whenAcceptedAndMergeAlreadyRequested_executesMerge_evenWithoutEvolutionOccurred() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("merge", 1.0, 0.0, "pre-request"));
        });

        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), any()))
                .thenAnswer(inv -> inv.getArgument(1, CycleContextData.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), any());
    }

    @Test
    void runCycle_callsAnalyzersInOrder_patternThenPlanningThrashThenScopeThrash() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        cycleExecutor.runCycle(baseContext("cycle-1"));

        InOrder inOrder = inOrder(patternSignalAnalyzer, planningThrashSignalAnalyzer, replanPatternDetector, scopeThrashSignalAnalyzer);
        inOrder.verify(patternSignalAnalyzer).analyze(any());
        inOrder.verify(planningThrashSignalAnalyzer).analyze(any());
        inOrder.verify(replanPatternDetector).detect(any());
        inOrder.verify(scopeThrashSignalAnalyzer).analyze(any());
    }

    @Test
    void runCycle_whenGoalProgressIsProgressing_appliesGoalProgressToCurrentSubGoal() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        // T1-DC: EvaluationAgent now writes mutationState/goalProgress directly.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.REJECTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING);
        });

        var applied = mock(com.walpro.autobuilder.goal.GoalProgressApplicationResult.class);
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.PROGRESSING)).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.PROGRESSING);
        assertThat(out.lastGoalProgressApplication()).isSameAs(applied);
    }

    @Test
    void runCycle_whenGoalProgressIsStagnating_appliesGoalProgressToCurrentSubGoal() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        // T1-DC: EvaluationAgent now writes mutationState/goalProgress directly.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.REJECTED)
                    .withGoalProgress(GoalProgressState.STAGNATING);
        });

        var applied = mock(com.walpro.autobuilder.goal.GoalProgressApplicationResult.class);
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.STAGNATING)).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService).applyGoalProgress("sub-1", GoalProgressState.STAGNATING);
        assertThat(out.lastGoalProgressApplication()).isSameAs(applied);
    }

    @Test
    void runCycle_whenSubGoalNotFulfilled_doesNotApplyFulfilledProgress() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("nope"));
        when(goalCompletionDecider.decide(any(), eq(false)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService, never()).applyGoalProgress(anyString(), eq(GoalProgressState.FULFILLED), any());
    }

    @Test
    void runCycle_whenGoalExhaustedSignal_present_setsCycleResultGoalExhausted() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withGoalExhaustedReason("test");
        });

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.GOAL_EXHAUSTED);
    }

    @Test
    void runCycle_whenGoalProgressStagnating_setsCycleResultStagnating() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.REJECTED)
                    .withGoalProgress(GoalProgressState.STAGNATING);
        });

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.STAGNATING);
    }

    @Test
    void runCycle_whenSubgoalPromoted_setsCycleResultContinue() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        // Force a fulfilled completion promotion
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "ok"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        GoalProgressApplicationResult applied = mock(GoalProgressApplicationResult.class);
        when(applied.promoted()).thenReturn(true);
        when(applied.newSubGoalId()).thenReturn("sub-2");
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any())).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(mock(GoalEntity.class)));
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of(mock(GoalEntity.class)));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.subgoalPromoted()).isTrue();
        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.CONTINUE);
    }

    @Test
    void runCycle_T1BH_regression_progressingAcceptedWithAllSubgoalsGivenUp_doesNotProduceFalseGoalFulfilled() {
        // Regression for T1-BH: planner gave up the only remaining subgoal (remainingGoals becomes
        // empty), evolution still ran and produced ACCEPTED + PROGRESSING. The old condition:
        //   (leafCompletedNow || PROGRESSING) && ACCEPTED && remainingGoals.isEmpty()
        // fired GOAL_FULFILLED because PROGRESSING && ACCEPTED && [] = true, even though the root
        // goal was still ACTIVE and no subgoal was actually completed.
        GoalEntity sub = mock(GoalEntity.class);
        when(sub.getId()).thenReturn("sub-1");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sub);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("a1", "p1"));

        GoalEntity activeRoot = mock(GoalEntity.class);
        when(activeRoot.getId()).thenReturn("goal-root");
        when(activeRoot.getDescription()).thenReturn("Root goal");
        // getStatus() not stubbed — returns null (not COMPLETED), which is sufficient to verify
        // the false-GOAL_FULFILLED path is blocked regardless of the exact status value.
        when(goalPlannerService.getRootGoal()).thenReturn(activeRoot);

        // T1-DC: Engine delivers ACCEPTED + PROGRESSING via direct context writes.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING);
        });
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(subgoalScopeGuard.guard(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("n/a"));
        when(goalCompletionDecider.decide(any(), anyBoolean()))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // Planner gave up all subgoals — no active ones remain
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isNotEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    @Test
    void runCycle_T1BH_leafCompletedNow_withRootCompleted_setsCycleResultGoalFulfilled() {
        // When the last subgoal is fulfilled and promoted (leafCompletedNow=true), remainingGoals
        // is empty, AND the root goal is actually COMPLETED — GOAL_FULFILLED must still fire.
        // Verifies the T1-BH fix does not suppress legitimate completions.
        GoalEntity sub = mock(GoalEntity.class);
        when(sub.getId()).thenReturn("sub-1");
        // First call: syncContextFromGoals; second: nextGoalEntity in applyCompletion (null = no successor)
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sub, (GoalEntity) null);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("a1", "p1"));

        GoalEntity completedRoot = mock(GoalEntity.class);
        when(completedRoot.getId()).thenReturn("goal-root");
        when(completedRoot.getDescription()).thenReturn("Root goal");
        when(completedRoot.getStatus()).thenReturn(GoalStatus.COMPLETED);
        when(goalPlannerService.getRootGoal()).thenReturn(completedRoot);

        stubIdentityPipeline();

        // Fulfillment heuristic says yes → applyCompletion fires
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "done"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // Completion promoted the subgoal; no successor (last leaf); root did not auto-complete
        GoalProgressApplicationResult fulfilled = mock(GoalProgressApplicationResult.class);
        when(fulfilled.promoted()).thenReturn(true);
        when(fulfilled.previousSubGoalId()).thenReturn("sub-1");
        when(fulfilled.newSubGoalId()).thenReturn(null);
        when(fulfilled.rootAutoCompleted()).thenReturn(false);
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any()))
                .thenReturn(fulfilled);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of(mock(GoalEntity.class)));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    @Test
    void runCycle_whenRootAutoCompleted_setsCycleResultGoalFulfilled() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        GoalProgressApplicationResult applied = mock(GoalProgressApplicationResult.class);
        when(applied.rootAutoCompleted()).thenReturn(true);

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "ok"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        when(applied.promoted()).thenReturn(true);
        when(applied.newSubGoalId()).thenReturn("sub-2");
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any())).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of(mock(GoalEntity.class)));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    @Test
    void runCycle_whenScopeOutOfScope_doesNotApplyGoalProgress_andRequestsReplan() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");

        // Engine produces evaluation interpretation that would normally set PROGRESSING.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);

            var ir = mock(com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult.class);

            return ctx.withEvaluationInterpretation(ir);
        });

        // Scope guard injects out-of-scope + replan.
        when(subgoalScopeGuard.guard(any(CycleContextData.class))).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx
                    .withScopeGuardResult(ScopeGuardResult.OUT_OF_SCOPE)
                    .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.05)));
        });

        // These are still called.
        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        // Contract: out-of-scope blocks progress commit.
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), any());

        // Also: out-of-scope blocks planning-thrash analyzer + replan detector + completion + merge.
        verify(planningThrashSignalAnalyzer, never()).analyze(any());
        verify(replanPatternDetector, never()).detect(any());
        verify(subGoalFulfillmentHeuristic, never()).decide(any());
        verify(goalCompletionDecider, never()).decide(any(), anyBoolean());
        verify(adaptiveExecutionEngine, never()).tryExecuteRole(eq("merge"), any());

        verify(subgoalScopeGuard).guard(any(CycleContextData.class));

        InOrder inOrder = inOrder(adaptiveExecutionEngine, subgoalScopeGuard);
        inOrder.verify(adaptiveExecutionEngine).runCycle(any());
        inOrder.verify(subgoalScopeGuard).guard(any());

        assertThat(DerivedFacts.scopeOutOfScope(out)).isTrue();
        assertThat(out.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void runCycle_whenScopeOutOfScope_blocksCommitFields_evenIfInterpretationSuggestsProgress() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");

        // Engine returns interpretation that would normally commit ACCEPTED + PROGRESSING.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);

            var ir = mock(com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult.class);
//            when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
//            when(ir.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

            return ctx.withEvaluationInterpretation(ir);
        });

        // Guard injects OUT_OF_SCOPE.
        when(subgoalScopeGuard.guard(any(CycleContextData.class))).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withScopeGuardResult(ScopeGuardResult.OUT_OF_SCOPE);
        });

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        // Invariant: block progress application
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), any());

        // Invariant: block downstream stage execution
        verify(planningThrashSignalAnalyzer, never()).analyze(any());
        verify(replanPatternDetector, never()).detect(any());
        verify(subGoalFulfillmentHeuristic, never()).decide(any());
        verify(goalCompletionDecider, never()).decide(any(), anyBoolean());
        verify(adaptiveExecutionEngine, never()).tryExecuteRole(eq("merge"), any());

        // Stronger semantic invariant: do NOT commit these fields based on out-of-scope interpretation.
        // Adjust expected values depending on your intended semantics:
        assertThat(out.goalProgress()).isNotEqualTo(GoalProgressState.PROGRESSING);
        assertThat(out.mutationState()).isNotEqualTo(MutationAcceptanceState.ACCEPTED);

        // But we still want audit visibility (optional contract):
        assertThat(out.evaluationInterpretation()).isNotNull();
        assertThat(DerivedFacts.scopeOutOfScope(out)).isTrue();
    }

    @Test
    void runCycle_whenAcceptedAndMergeAlreadyRequested_preservesExistingMergeSignalOnDelegation() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        ParticipationWindow existingMerge = ParticipationWindow.of("merge", 0.42, 0.17, "pre-request");

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .grantWindow(existingMerge);
        });

        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), any()))
                .thenAnswer(inv -> inv.getArgument(1, CycleContextData.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), captor.capture());

        ParticipationWindow delegatedMerge = captor.getValue().windows().get("merge");
        assertThat(delegatedMerge).isNotNull();
        assertThat(delegatedMerge.boost()).isEqualTo(0.42);
        assertThat(delegatedMerge.decayEquivalent()).isEqualTo(0.17);
        assertThat(delegatedMerge.source()).isEqualTo("pre-request");
    }

    @Test
    void runCycle_whenScopeOutOfScope_blocksFulfilledCompletionEvenIfHeuristicWouldFulfill() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");

        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            var ir = mock(com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult.class);
            return ctx.withEvaluationInterpretation(ir);
        });

        when(subgoalScopeGuard.guard(any(CycleContextData.class))).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withScopeGuardResult(ScopeGuardResult.OUT_OF_SCOPE);
        });

        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(subGoalFulfillmentHeuristic, never()).decide(any());
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), eq(GoalProgressState.FULFILLED), any());
        assertThat(out.subgoalPromoted()).isFalse();
        assertThat(out.cycleResultState()).isNotEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    // ── T3-BL: criteria-banked early exit ────────────────────────────────────────────────

    @Test
    void runCycle_t3bl_allCriteriaBankedAndMergeOccurred_appliesFulfilledAndSkipsAgents() {
        // syncContextFromGoals picks up sub-1 on the first call; the early-exit then promotes
        // to sub-2 and calls getCurrentSubGoal() a second time to get the next entity.
        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("goal-root");
        when(root.getDescription()).thenReturn("Root goal");
        GoalEntity sub1 = mock(GoalEntity.class);
        when(sub1.getId()).thenReturn("sub-1");
        when(sub1.getDescription()).thenReturn("Sub goal 1");
        GoalEntity sub2 = mock(GoalEntity.class);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sub1, sub2);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent-0"));

        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(true);
        when(goalPlannerService.getGoalMetadataInt("sub-1", "mergeCount")).thenReturn(1);
        // T1-ET Part B: T3-BL early-exit guard now also requires no ACTIVE descendants.
        when(goalPlannerService.hasNoActiveDescendants("sub-1")).thenReturn(true);

        GoalProgressApplicationResult result = mock(GoalProgressApplicationResult.class);
        when(result.promoted()).thenReturn(true);
        when(result.newSubGoalId()).thenReturn("sub-2");
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any())).thenReturn(result);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any());
        verifyNoInteractions(adaptiveExecutionEngine);
        assertThat(out.subgoalPromoted()).isTrue();
        assertThat(out.currentSubGoalId()).isEqualTo("sub-2");
    }

    @Test
    void runCycle_t3bl_allCriteriaBankedButNoMerge_doesNotEarlyExit() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(true);
        when(goalPlannerService.getGoalMetadataInt("sub-1", "mergeCount")).thenReturn(0);

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(adaptiveExecutionEngine).runCycle(any());
    }

    /**
     * T1-ET Part B: T3-BL guard symmetry. Parent goal with banked criteria + non-zero
     * mergeCount but still has ACTIVE descendants must NOT enter the early-exit fast path —
     * otherwise the cycle ends without running agents, T1-DD refuses the downstream
     * COMPLETED flip, and the zero-work loop fires.
     */
    @Test
    void runCycle_t3bl_T1ET_hasActiveDescendants_doesNotEarlyExit() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(true);
        when(goalPlannerService.getGoalMetadataInt("sub-1", "mergeCount")).thenReturn(1);
        // T1-ET guard: tree still has active descendants under sub-1 — refuse the fast path.
        when(goalPlannerService.hasNoActiveDescendants("sub-1")).thenReturn(false);

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(adaptiveExecutionEngine).runCycle(any());
        verify(goalPlannerService, never())
                .applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any());
    }

    // ── T1-ET Part B livelock detector ───────────────────────────────────────────────────

    /**
     * T1-ET Part B tertiary: three consecutive cycles that end with
     * {@code mut=null && promoted=true && merged=false} on the same {@code currentSubGoalId}
     * fire the livelock invariant — structured WARN + one-shot REPLAN_REQUESTED signal.
     */
    @Test
    void runCycle_T1ET_livelockDetector_firesAfterThirdConsecutiveZeroWorkCycle() {
        stubGoalSync("goal-root", "Root goal", "sub-stuck", "Stuck sub");
        stubIdentityPipeline();

        // Engine pretends each cycle "promoted" the subgoal without producing any mutation
        // or merging — exactly the shape the livelock detector watches for.
        when(adaptiveExecutionEngine.runCycle(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class)
                        .withSubgoalPromoted(true));

        CycleContextData c1 = cycleExecutor.runCycle(baseContext("cycle-1"));
        CycleContextData c2 = cycleExecutor.runCycle(baseContext("cycle-2"));
        CycleContextData c3 = cycleExecutor.runCycle(baseContext("cycle-3"));

        // First two cycles count but stay quiet (no signal emit, no WARN).
        assertThat(c1.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(c2.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
        // Third cycle: livelock detector fires the one-shot planner-priority request.
        assertThat(c3.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    /**
     * Livelock counter resets whenever the cycle does real work — a merged cycle in the
     * middle of a streak should prevent the WARN from firing on the next zero-work cycle.
     */
    @Test
    void runCycle_T1ET_livelockDetector_resetsOnMergedCycle() {
        stubGoalSync("goal-root", "Root goal", "sub-stuck", "Stuck sub");
        stubIdentityPipeline();

        // Sequence: zero-work, zero-work, MERGED (resets counter), zero-work.
        // After the reset the trailing zero-work is only the 1st in a new streak — no fire.
        when(adaptiveExecutionEngine.runCycle(any()))
                .thenAnswer(new org.mockito.stubbing.Answer<CycleContextData>() {
                    private int n = 0;
                    @Override public CycleContextData answer(org.mockito.invocation.InvocationOnMock inv) {
                        n++;
                        CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
                        if (n == 3) return ctx.withSubgoalPromoted(true).withMerged(true);
                        return ctx.withSubgoalPromoted(true);
                    }
                });

        cycleExecutor.runCycle(baseContext("cycle-1"));
        cycleExecutor.runCycle(baseContext("cycle-2"));
        cycleExecutor.runCycle(baseContext("cycle-3")); // merged — resets counter
        CycleContextData c4 = cycleExecutor.runCycle(baseContext("cycle-4"));

        assertThat(c4.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
    }

    // ── T1-DJ: deriveCycleResult root-status check ────────────────────────────────────────

    /**
     * Stubs a null-subgoal scenario: root goal has the given status, no active sub-goal.
     * getRootGoal() is called both by syncContextFromGoals and deriveCycleResult.
     */
    private void stubGoalSyncNullSubgoal(String rootId, String rootDesc, GoalStatus rootStatus) {
        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn(rootId);
        when(root.getDescription()).thenReturn(rootDesc);
        when(root.getStatus()).thenReturn(rootStatus);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.ensureAttemptIdsForCurrentScope())
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent-0"));
    }

    /** Minimal pipeline stubs for a null-subgoal cycle (completion heuristic never fires). */
    private void stubMinimalPipelineNullSubgoal() {
        when(adaptiveExecutionEngine.runCycle(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(subgoalScopeGuard.guard(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
    }

    @Test
    void deriveCycleResult_completedRootWithNullSubgoal_returnsGoalFulfilled() {
        // T1-DJ: GOAL_EXHAUSTED signal present, but root is COMPLETED — must return GOAL_FULFILLED.
        stubGoalSyncNullSubgoal("goal-root", "Root goal", GoalStatus.COMPLETED);
        stubMinimalPipelineNullSubgoal();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withGoalExhaustedReason("test");
        });

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    @Test
    void deriveCycleResult_givenUpRootWithNullSubgoal_returnsGoalExhausted() {
        // T1-DJ: GOAL_EXHAUSTED signal present, root is GIVEN_UP — must return GOAL_EXHAUSTED.
        stubGoalSyncNullSubgoal("goal-root", "Root goal", GoalStatus.GIVEN_UP);
        stubMinimalPipelineNullSubgoal();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withGoalExhaustedReason("test");
        });

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.GOAL_EXHAUSTED);
    }

    @Test
    void deriveCycleResult_activeRootWithNullSubgoal_returnsGoalExhausted() {
        // T1-DJ: GOAL_EXHAUSTED signal present, root is still ACTIVE — must return GOAL_EXHAUSTED.
        stubGoalSyncNullSubgoal("goal-root", "Root goal", GoalStatus.ACTIVE);
        stubMinimalPipelineNullSubgoal();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withGoalExhaustedReason("test");
        });

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.GOAL_EXHAUSTED);
    }

    @Test
    void runCycle_t3bl_criteriaNotAllBanked_doesNotEarlyExit() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(false);

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(adaptiveExecutionEngine).runCycle(any());
    }

    // ── T1-CU: merge-before-completion gate ──────────────────────────────────────────────

    /**
     * Stubs up an evaluation that produces ACCEPTED + PROGRESSING via evaluationInterpretation.
     * Also stubs applyGoalProgress for PROGRESSING (called by applyEvaluationProgressIfPresent)
     * to avoid Mockito strict-stubbing false positives.
     */
    private void stubAcceptedProgressingEngine() {
        // T1-DC: EvaluationAgent now writes mutationState/goalProgress directly to the context.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING);
        });
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.PROGRESSING))
                .thenReturn(mock(GoalProgressApplicationResult.class));
    }

    private void stubAcceptedProgressingEngineMerged() {
        // T1-DC: EvaluationAgent now writes mutationState/goalProgress directly to the context.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING)
                    .withMerged(true);
        });
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.PROGRESSING))
                .thenReturn(mock(GoalProgressApplicationResult.class));
    }

    private void stubAcceptedProgressingEngineWithSignal(SignalType signalType) {
        // T1-DC: EvaluationAgent now writes mutationState/goalProgress directly to the context.
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING)
                    .issueRequest(ChannelRequest.of(signalType, 1.0, "test",
                            ChannelRequest.horizonFromDecay(1.0, 0.5)));
        });
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.PROGRESSING))
                .thenReturn(mock(GoalProgressApplicationResult.class));
    }

    @Test
    void applyCompletion_t1cu_acceptedNotMergedMergeCountZero_suppressesFulfilled() {
        // T1-CU: score-based FULFILLED must be suppressed when the accepted mutation has not
        // been merged yet AND mergeCount==0 for the subgoal. This prevents score-based closure
        // from orphaning accepted mutations before MergeAgent has run.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();
        stubAcceptedProgressingEngine();

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "score-based"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // merged=false this cycle, mergeCount=0 — accepted mutation not yet merged
        when(goalPlannerService.getGoalMetadataInt("sub-1", "mergeCount")).thenReturn(0);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        // FULFILLED must be suppressed — applyGoalProgress should not be called with FULFILLED
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), eq(GoalProgressState.FULFILLED), any());
        assertThat(out.subgoalPromoted()).isFalse();
        assertThat(out.cycleResultState()).isNotEqualTo(CycleResultState.GOAL_FULFILLED);
    }

    @Test
    void applyCompletion_t1cu_acceptedAndMergedThisCycle_allowsFulfilled() {
        // T1-CU bypass: when merged=true in the current cycle, FULFILLED is allowed.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();
        stubAcceptedProgressingEngineMerged();

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "score-based"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // merged=true this cycle — the guard short-circuits at !ctx.merged() so mergeCount is never read
        GoalProgressApplicationResult fulfilled = mock(GoalProgressApplicationResult.class);
        when(fulfilled.promoted()).thenReturn(true);
        when(fulfilled.newSubGoalId()).thenReturn("sub-2");
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any())).thenReturn(fulfilled);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(mock(GoalEntity.class)));
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any());
        assertThat(out.subgoalPromoted()).isTrue();
    }

    @Test
    void applyCompletion_t1cu_acceptedNotMergedButMergeCountPositive_allowsFulfilled() {
        // T1-CU bypass: when mergeCount > 0, the subgoal has been merged before (prior cycle).
        // FULFILLED is allowed since there is at least one real merge for this subgoal.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();
        stubAcceptedProgressingEngine();

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "score-based"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // merged=false this cycle but mergeCount=1 from a prior cycle — allowed
        when(goalPlannerService.getGoalMetadataInt("sub-1", "mergeCount")).thenReturn(1);

        GoalProgressApplicationResult fulfilled = mock(GoalProgressApplicationResult.class);
        when(fulfilled.promoted()).thenReturn(true);
        when(fulfilled.newSubGoalId()).thenReturn("sub-2");
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any())).thenReturn(fulfilled);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(mock(GoalEntity.class)));
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any());
        assertThat(out.subgoalPromoted()).isTrue();
    }

    @Test
    void applyCompletion_t1cu_acceptedNotMergedWithEvolutionNoopSignal_allowsFulfilled() {
        // T1-CU bypass: EVOLUTION_NOOP means no merge step is needed (code already in output).
        // FULFILLED must be allowed even when merged=false and mergeCount=0.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();
        stubAcceptedProgressingEngineWithSignal(BasicSignals.EVOLUTION_NOOP);

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.9, "score-based"));
        when(goalCompletionDecider.decide(any(), eq(true)))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        // EVOLUTION_NOOP present — the guard short-circuits at !ctx.hasSignal(EVOLUTION_NOOP) so mergeCount is never read
        GoalProgressApplicationResult fulfilled = mock(GoalProgressApplicationResult.class);
        when(fulfilled.promoted()).thenReturn(true);
        when(fulfilled.newSubGoalId()).thenReturn("sub-2");
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any())).thenReturn(fulfilled);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(mock(GoalEntity.class)));
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(goalPlannerService).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.FULFILLED), any());
        assertThat(out.subgoalPromoted()).isTrue();
    }

    // ── T1-DX: rolled-back cycle must not promote subgoal ──

    // ── T1-DY: criteria persistence deferred until merge gate confirms ──

    @Test
    void flushPendingCriteria_persistsWhenNotRolledBack() {
        stubGoalSync("goal-root", "Root", "sub-1", "Sub 1");

        java.util.Map<Integer, Boolean> pass = new java.util.HashMap<>();
        pass.put(0, true);
        java.util.Map<Integer, Integer> fail = new java.util.HashMap<>();
        fail.put(0, 0);
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withCumulativeCriteriaStatus(pass)
                    .withCriteriaConsecutiveFailCount(fail);
        });
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(subgoalScopeGuard.guard(any(CycleContextData.class))).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        // applyCompletion runs because we're NOT rolled back — needs a default decision.
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("n/a"));
        when(goalCompletionDecider.decide(any(), anyBoolean()))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        // T1-DY: not rolled back → criteria are persisted to GoalEntity metadata.
        verify(goalPlannerService).saveCriteriaPassStatus("sub-1", pass);
        verify(goalPlannerService).saveCriteriaFailStreak("sub-1", fail);
    }

    @Test
    void flushPendingCriteria_skipsPersistenceWhenRolledBack() {
        stubGoalSync("goal-root", "Root", "sub-1", "Sub 1");

        java.util.Map<Integer, Boolean> pass = new java.util.HashMap<>();
        pass.put(0, true);
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withCumulativeCriteriaStatus(pass)
                    .withRolledBackThisCycle(true);
        });
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(subgoalScopeGuard.guard(any(CycleContextData.class))).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        cycleExecutor.runCycle(baseContext("cycle-rb"));

        // T1-DY: rolled back → no criteria persistence (would otherwise pollute T3-BL early-exit).
        verify(goalPlannerService, never()).saveCriteriaPassStatus(anyString(), any());
        verify(goalPlannerService, never()).saveCriteriaFailStreak(anyString(), any());
    }

    @Test
    void applyCompletion_rolledBackThisCycle_skipsClosure_andDoesNotPromote() {
        // Adaptive engine returns a context that LOOKS promotable (ACCEPTED + score≥0.75) but
        // is also marked rolledBackThisCycle=true — same shape MergeAgent produces after T1-DW
        // restored the filesystem. CycleExecutor must NOT call applyGoalProgress.
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");

        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withGoalProgress(GoalProgressState.PROGRESSING)
                    .withRolledBackThisCycle(true);
        });
        // Pipeline pass-throughs so the cycle reaches deriveCycleResult.
        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(subgoalScopeGuard.guard(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-rb"));

        // T1-DX: Closure must be skipped on the rolled-back path.
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), eq(GoalProgressState.FULFILLED), any());
        verify(goalPlannerService, never()).applyGoalProgress(eq("sub-1"), eq(GoalProgressState.PROGRESSING));
        assertThat(out.subgoalPromoted()).isFalse();
        // deriveCycleResult must surface ROLLED_BACK rather than CONTINUE/GOAL_FULFILLED.
        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.ROLLED_BACK);
    }
}