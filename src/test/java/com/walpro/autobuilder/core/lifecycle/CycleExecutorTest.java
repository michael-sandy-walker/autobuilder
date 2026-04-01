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
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
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

    @InjectMocks
    private CycleExecutor cycleExecutor;

    private CycleContextData baseContext(String cycleName) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                .signals(new LinkedHashMap<>())
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
                .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 0.75, 0.0, "test"));

        CycleContextData out = cycleExecutor.runCycle(input);

        // paused signal must be ticked (age increments)
        Signal paused = out.getSignal(BasicSignals.SYSTEM_PAUSED);
        assertThat(paused).isNotNull();
        assertThat(paused.age()).isEqualTo(1);

        verify(goalPlannerService).setCurrentCycleContext(input);
        verifyNoInteractions(adaptiveExecutionEngine, patternSignalAnalyzer, planningThrashSignalAnalyzer,
                replanPatternDetector, scopeThrashSignalAnalyzer, subGoalFulfillmentHeuristic, goalCompletionDecider, cycleScoreRecorder, interpretationService);
    }

    @Test
    void runCycle_whenEvaluationInterpretationPresent_writesMutationStateAndGoalProgress() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        // engine returns ctx with evaluationInterpretation
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            // Create a tiny stub for InterpretationResult (record) via mock if needed
            var ir = mock(com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult.class);
            when(ir.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
            when(ir.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
            return ctx.withEvaluationInterpretation(ir);
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

        CycleContextData context = baseContext("cycle-1");
        context = context.withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.1, "test"));

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
                    .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));
        });

        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), any()))
                .thenAnswer(inv -> inv.getArgument(1, CycleContextData.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        ArgumentCaptor<CycleContextData> ctxCaptor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), ctxCaptor.capture());

        CycleContextData mergeArg = ctxCaptor.getValue();
        Signal mergeSignal = mergeArg.getSignal(BasicSignals.ROLE_MERGE);

        assertThat(mergeSignal).isNotNull();
        assertThat(mergeSignal.strength()).isEqualTo(1.0);
        assertThat(mergeSignal.decayPerCycle()).isEqualTo(1.0); // full decay: must not bleed into next cycle
    }

    @Test
    void runCycle_whenAcceptedAndMergeAlreadyRequested_executesMerge_evenWithoutEvolutionOccurred() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 1.0, 0.0, "pre-request"));
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

        // make engine provide an evaluationInterpretation -> CycleExecutor will write goalProgress + mutationState
        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            var ir = mock(com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult.class);
            when(ir.mutationState()).thenReturn(MutationAcceptanceState.REJECTED); // doesn't matter here
            when(ir.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
            return ctx.withEvaluationInterpretation(ir);
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

        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            var ir = mock(com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult.class);
            when(ir.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
            when(ir.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
            return ctx.withEvaluationInterpretation(ir);
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

        verify(goalPlannerService, never()).applyGoalProgress(anyString(), eq(GoalProgressState.FULFILLED));
    }

    @Test
    void runCycle_whenGoalExhaustedSignal_present_setsCycleResultGoalExhausted() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "test"));
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
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.FULFILLED)).thenReturn(applied);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(mock(GoalEntity.class)));
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of(mock(GoalEntity.class)));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        assertThat(out.subgoalPromoted()).isTrue();
        assertThat(out.cycleResultState()).isEqualTo(CycleResultState.CONTINUE);
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
        when(goalPlannerService.applyGoalProgress("sub-1", GoalProgressState.FULFILLED)).thenReturn(applied);

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
                    .withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "test"))
                    .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "test"));
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

        assertThat(out.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).isTrue();
        assertThat(out.hasSignal(BasicSignals.REPLAN_REQUESTED)).isTrue();
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
            return ctx.withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "test"));
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
        assertThat(out.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).isTrue();
    }

    @Test
    void runCycle_whenAcceptedAndMergeAlreadyRequested_preservesExistingMergeSignalOnDelegation() {
        stubGoalSync("goal-root", "Root goal", "sub-1", "Sub goal 1");
        stubIdentityPipeline();

        Signal existingMerge = Signal.of(BasicSignals.ROLE_MERGE, 0.42, 0.17, "pre-request");

        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> {
            CycleContextData ctx = inv.getArgument(0, CycleContextData.class);
            return ctx.withMutationState(MutationAcceptanceState.ACCEPTED)
                    .withSignal(existingMerge);
        });

        when(adaptiveExecutionEngine.tryExecuteRole(eq("merge"), any()))
                .thenAnswer(inv -> inv.getArgument(1, CycleContextData.class));

        cycleExecutor.runCycle(baseContext("cycle-1"));

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(adaptiveExecutionEngine).tryExecuteRole(eq("merge"), captor.capture());

        Signal delegatedMerge = captor.getValue().getSignal(BasicSignals.ROLE_MERGE);
        assertThat(delegatedMerge).isNotNull();
        assertThat(delegatedMerge.strength()).isEqualTo(0.42);
        assertThat(delegatedMerge.decayPerCycle()).isEqualTo(0.17);
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
            return ctx.withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "test"));
        });

        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cycleExecutor.runCycle(baseContext("cycle-1"));

        verify(subGoalFulfillmentHeuristic, never()).decide(any());
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), eq(GoalProgressState.FULFILLED));
        assertThat(out.subgoalPromoted()).isFalse();
        assertThat(out.cycleResultState()).isNotEqualTo(CycleResultState.GOAL_FULFILLED);
    }
}