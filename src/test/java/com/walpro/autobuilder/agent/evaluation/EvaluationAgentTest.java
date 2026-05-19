package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationAgentTest {

    @Mock private EvaluationLLMAdapter evaluationAdapter;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) private ScoringService scoringService;
    @Mock private EvaluationInterpreter evaluationInterpreter;
    @Mock private ProblemDetector problemDetector;

    @Mock private HeuristicNegotiator heuristicNegotiator;
    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private EvaluationActionRegistrar evaluationActionRegistrar;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private MutationSnapshotService mutationSnapshotService;
    @Mock private CompileProbeService compileProbeService;
    @Mock private com.walpro.autobuilder.core.merge.MergeRollbackService mergeRollbackService;
    @Mock private com.walpro.autobuilder.core.service.FileService fileService;

    @BeforeEach
    void stubProbe() {
        // T1-EC: default stub — probe skipped so existing tests exercise the LLM path unchanged.
        lenient().when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled in test"), null));
    }

    // --- helpers -------------------------------------------------------------

    private EvaluationAgent newAgent(List<EvaluationHeuristic> heuristics) {
        return new EvaluationAgent(
                evaluationAdapter,
                scoringService,
                evaluationInterpreter,
                problemDetector,
                heuristicNegotiator,
                heuristics,
                graphFacade,
                evaluationActionRegistrar,
                goalPlannerService,
                mutationSnapshotService,
                compileProbeService,
                mergeRollbackService,
                fileService
        );
    }

    private CycleContextData baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .projectDiff(new ProjectDiffResult(
                        null,
                        Map.of("src/Main.java", "changed"),
                        Set.of()
                ))
                .previousOutput(Map.of())
                .build();
    }

    @Test
    void execute_prefersCurrentSubGoalIdWhenPresent() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "ok",
                null
        );

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());

        // negotiation returns final outcome (often equals raw)
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue()).isEqualTo("sub-1");
    }

    @Test
    void execute_fallsBackToCurrentGoalIdWhenNoSubGoal() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId(null)
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "ok",
                null
        );

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interpretation);

        agent.execute(ctx);

        ArgumentCaptor<String> goalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringService).computeScores(
                eq("cycle-1"),
                goalIdCaptor.capture(),
                anyMap(),
                anyMap(),
                anySet()
        );

        assertThat(goalIdCaptor.getValue()).isEqualTo("goal-1");
    }

    @Test
    void execute_criteriaAssessments_propagatedToContext() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.7,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "partial",
                null
        );

        List<String> assessments = List.of("[1] \u2713 criterion one", "[2] \u2717 criterion two \u2014 not done");
        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(raw, assessments));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.criteriaAssessments()).isEqualTo(assessments);
    }

    @Test
    void execute_criteriaAssessments_clearedWhenLLMOmitsCriteriaCheck() {
        // Stale ✓ assessments from prior cycle must be cleared when LLM returns no CriteriaCheck lines.
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .criteriaAssessments(List.of("[1] \u2713 stale criterion from prior cycle"))
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.4, MutationAcceptanceState.REJECTED, GoalProgressState.PROGRESSING, "rejected", null);

        when(evaluationAdapter.evaluate(any(CycleContextData.class)))
                .thenReturn(new ParsedEvaluation(raw, List.of())); // LLM omits CriteriaCheck
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(false);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.criteriaAssessments()).isEmpty();
    }

    // T1-BG: cumulative criteria status tests

    @Test
    void execute_cumulativeCriteriaStatus_passedCriterionNeverReverts() {
        // Criterion 1 passed last cycle (cumulative=true). This cycle LLM says [1] ✗.
        // The cumulative map must retain true for index 1.
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .cumulativeCriteriaStatus(new java.util.HashMap<>(Map.of(1, true)))
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(0.7, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "partial", null);

        List<String> assessments = List.of("[1] \u2717 criterion one \u2014 regressed", "[2] \u2713 criterion two");
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, assessments));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.cumulativeCriteriaStatus()).containsEntry(1, true);   // not overwritten by ✗
        assertThat(result.cumulativeCriteriaStatus()).containsEntry(2, true);   // new pass recorded
    }

    @Test
    void execute_cumulativeCriteriaStatus_newPassIsRecorded() {
        // No prior cumulative map. LLM says [1] ✓ and [2] ✗. Map is created fresh.
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(0.7, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "ok", null);

        List<String> assessments = List.of("[1] \u2713 criterion one", "[2] \u2717 criterion two \u2014 missing");
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, assessments));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.cumulativeCriteriaStatus()).containsEntry(1, true);
        assertThat(result.cumulativeCriteriaStatus()).containsEntry(2, false);
    }

    @Test
    void execute_partialCriteria_banksPassAndAllowsAccepted() {
        // T1-BN: with T2-BM removed, a partial-pass mutation (criterion [1] ✓, [2] ✗) is no longer vetoed.
        // The LLM outcome (ACCEPTED) passes through; criteria are still banked in cumulativeCriteriaStatus.
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .currentSubGoalCriteria(List.of("criterion one", "criterion two"))
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(0.7, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "partial", null);

        List<String> assessments = List.of("[1] \u2713 criterion one", "[2] \u2717 criterion two \u2014 missing");
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, assessments));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(eq(raw), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evaluationOutcome().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(result.cumulativeCriteriaStatus()).containsEntry(1, true);
        assertThat(result.cumulativeCriteriaStatus()).containsEntry(2, false);
    }

    @Test
    void execute_propagatesFinalOutcome_storesInterpretation_andCallsGraphAndRegistrar() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-123")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );

        EvaluationOutcome negotiated = new EvaluationOutcome(
                0.88,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Final",
                null
        );

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList())).thenReturn(new NegotiationResult(negotiated, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(evaluationInterpreter.interpret(eq(negotiated), anyDouble(), anyBoolean())).thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evaluationOutcome()).isEqualTo(negotiated);
        assertThat(result.evaluationInterpretation()).isEqualTo(interpretation);

        verify(graphFacade).recordEvaluation(eq(result), eq(negotiated));
        verify(evaluationActionRegistrar).registerEvaluation(anyString(), eq(result), any(NegotiationResult.class));
    }

    // T1-DC: EvaluationAgent now writes mutationState/goalProgress to the context directly
    // and emits ROLE_MERGE on ACCEPTED, so same-cycle agents (planner, merge) see fresh state.

    @Test
    void execute_writesMutationStateAndGoalProgress_andEmitsRoleMergeOnAccepted() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-123")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.9, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);
        EvaluationOutcome negotiated = new EvaluationOutcome(
                0.88, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "final", null);

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(negotiated, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(eq(negotiated), anyDouble(), anyBoolean())).thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(result.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(result.hasSignal(com.walpro.autobuilder.core.lifecycle.BasicSignals.ROLE_MERGE)).isTrue();
    }

    @Test
    void execute_writesRejectedMutationState_andDoesNotEmitRoleMerge() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-123")
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.4, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "no", null);
        EvaluationOutcome negotiated = new EvaluationOutcome(
                0.4, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "final", null);

        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(negotiated, List.of(), List.of()));

        InterpretationResult interpretation = mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(false);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(evaluationInterpreter.interpret(eq(negotiated), anyDouble(), anyBoolean())).thenReturn(interpretation);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(result.hasSignal(com.walpro.autobuilder.core.lifecycle.BasicSignals.ROLE_MERGE)).isFalse();
    }

    // T1-DI: EvaluationAgent must persist mutationState to meta.json immediately so
    // FileVariantCollector can find ACCEPTED variants within the same cycle (meta-step path).

    @Test
    void execute_whenAcceptedAndEvolutionRanThisCycle_delegatesPersistWithAcceptedState() {
        // persistMutationStateToMeta delegates to MutationSnapshotService.persistMutationState;
        // the real persistence logic is unit-tested in MutationSnapshotServiceTest.
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .evolutionRanThisCycle(true)
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.85, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);

        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        agent.execute(ctx);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(mutationSnapshotService).persistMutationState(captor.capture(), any());
        assertThat(captor.getValue().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void execute_whenRejectedAndEvolutionRanThisCycle_delegatesPersistWithRejectedState() {
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .evolutionRanThisCycle(true)
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.3, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "bad", null);

        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(false);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        agent.execute(ctx);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(mutationSnapshotService).persistMutationState(captor.capture(), any());
        assertThat(captor.getValue().mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void execute_alwaysDelegatesPersistRegardlessOfEvolution() {
        // persistMutationStateToMeta is always called; MutationSnapshotService decides internally
        // whether to do anything based on evolutionRanThisCycle / mutationPath.
        EvaluationAgent agent = newAgent(List.of());

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .evolutionRanThisCycle(false)
                .build();

        EvaluationOutcome raw = new EvaluationOutcome(
                0.85, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);

        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        agent.execute(ctx);

        verify(mutationSnapshotService).persistMutationState(any(), any());
    }

    // T3-BR: criteria-aware fingerprint-repeat handling --------------------------

    @Test
    void execute_T3BR_fingerprintRepeats_criteriaUnbanked_forcesFreshLLMCall() {
        EvaluationAgent agent = newAgent(List.of());

        EvaluationOutcome priorOutcome = new EvaluationOutcome(
                0.7, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "stale", null);

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastEvolutionFingerprint("fp-same")
                .lastEvaluatedEvolutionFingerprint("fp-same")
                .lastEvaluationOutcome(priorOutcome)
                .evaluatorRepeatRetries(0)
                .build();

        when(goalPlannerService.hasAcceptanceCriteria("sub-1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(false);

        EvaluationOutcome fresh = new EvaluationOutcome(
                0.82, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "fresh", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(fresh, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(fresh), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(fresh), anyList()))
                .thenReturn(new NegotiationResult(fresh, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        // Fresh LLM call happened (not the reuse path).
        verify(evaluationAdapter, times(1)).evaluate(any());
        // Retry counter incremented from 0 to 1 — still under cap, no REPLAN.
        assertThat(result.evaluatorRepeatRetries()).isEqualTo(1);
        assertThat(result.hasSignal(com.walpro.autobuilder.core.lifecycle.BasicSignals.REPLAN_REQUESTED)).isFalse();
    }

    @Test
    void execute_T3BR_fingerprintRepeats_allCriteriaBanked_reusesPriorOutcome() {
        EvaluationAgent agent = newAgent(List.of());

        EvaluationOutcome priorOutcome = new EvaluationOutcome(
                0.85, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "prior", null);

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastEvolutionFingerprint("fp-same")
                .lastEvaluatedEvolutionFingerprint("fp-same")
                .lastEvaluationOutcome(priorOutcome)
                .evaluatorRepeatRetries(0)
                .build();

        when(goalPlannerService.hasAcceptanceCriteria("sub-1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(true);

        when(problemDetector.detectAndUpdate(any(), eq(priorOutcome), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(priorOutcome), anyList()))
                .thenReturn(new NegotiationResult(priorOutcome, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        // No fresh LLM call — prior outcome was reused.
        verify(evaluationAdapter, never()).evaluate(any());
        // Retry counter not incremented when criteria are already all banked.
        assertThat(result.evaluatorRepeatRetries()).isEqualTo(0);
    }

    @Test
    void execute_T3BR_fingerprintRepeats_criteriaUnbanked_atCap_emitsReplanRequested() {
        EvaluationAgent agent = newAgent(List.of());

        EvaluationOutcome priorOutcome = new EvaluationOutcome(
                0.7, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "stale", null);

        int oneBelowCap = com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP - 1;

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastEvolutionFingerprint("fp-same")
                .lastEvaluatedEvolutionFingerprint("fp-same")
                .lastEvaluationOutcome(priorOutcome)
                .evaluatorRepeatRetries(oneBelowCap)
                .build();

        when(goalPlannerService.hasAcceptanceCriteria("sub-1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(false);

        EvaluationOutcome fresh = new EvaluationOutcome(
                0.7, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "still partial", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(fresh, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(fresh), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(fresh), anyList()))
                .thenReturn(new NegotiationResult(fresh, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(false);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evaluatorRepeatRetries())
                .isEqualTo(com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP);
        assertThat(result.hasSignal(com.walpro.autobuilder.core.lifecycle.BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void execute_T2CRGate_capFires_emitsEvaluatorRepeatCapFiredBehaviourEvent() {
        // T2-CR observability gate: when T3-BR's evaluator-repeat-cap fires the REPLAN path,
        // a behaviour event must be emitted so live-session analysis can confirm whether the
        // cap path actually fires in production. T2-CR's demote-and-narrow refinement remains
        // parked until this event appears in real runs.
        com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger =
                mock(com.walpro.autobuilder.observability.behaviour.BehaviourLogger.class);
        EvaluationAgent agent = newAgent(List.of());
        agent.setBehaviourLogger(behaviourLogger);

        EvaluationOutcome priorOutcome = new EvaluationOutcome(
                0.7, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "stale", null);
        int oneBelowCap = com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP - 1;

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .cycleName("cycle-cap")
                .lastEvolutionFingerprint("fp-X")
                .lastEvaluatedEvolutionFingerprint("fp-X")
                .lastEvaluationOutcome(priorOutcome)
                .evaluatorRepeatRetries(oneBelowCap)
                .build();

        when(goalPlannerService.hasAcceptanceCriteria("sub-1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sub-1")).thenReturn(false);
        // Non-BUILD_FIX subgoal — cap-fire path is reachable.
        when(goalPlannerService.getGoalById("sub-1")).thenReturn(null);

        EvaluationOutcome fresh = new EvaluationOutcome(
                0.7, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "partial", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(fresh, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(fresh), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(fresh), anyList()))
                .thenReturn(new NegotiationResult(fresh, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(false);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        agent.execute(ctx);

        verify(behaviourLogger).evaluatorRepeatCapFired(
                eq("cycle-cap"),
                eq("sub-1"),
                eq(com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP),
                eq(com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP),
                eq("fp-X"),
                anyInt(),
                anyInt()
        );
    }

    @Test
    void evaluate_T3BR_buildFixSubgoal_atRetryCap_doesNotEmitReplanRequested() {
        // T1-DN carve-out: at the retry cap, a BUILD_FIX subgoal must NOT emit REPLAN_REQUESTED —
        // replanning would inject new subgoals that also can't pass evaluation while the build is red.
        EvaluationAgent agent = newAgent(List.of());

        EvaluationOutcome priorOutcome = new EvaluationOutcome(
                0.25, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "build broken", null);

        int oneBelowCap = com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP - 1;

        CycleContextData ctx = baseContext().toBuilder()
                .currentGoalId("goal-bf")
                .currentSubGoalId("bf-sub")
                .lastEvolutionFingerprint("fp-same")
                .lastEvaluatedEvolutionFingerprint("fp-same")
                .lastEvaluationOutcome(priorOutcome)
                .evaluatorRepeatRetries(oneBelowCap)
                .build();

        when(goalPlannerService.hasAcceptanceCriteria("bf-sub")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("bf-sub")).thenReturn(false);

        GoalEntity buildFixGoal = new GoalEntity();
        buildFixGoal.setStatus(GoalStatus.ACTIVE);
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        buildFixGoal.setMetadata(meta);
        when(goalPlannerService.getGoalById("bf-sub")).thenReturn(buildFixGoal);

        EvaluationOutcome fresh = new EvaluationOutcome(
                0.25, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "still broken", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(fresh, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(fresh), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(fresh), anyList()))
                .thenReturn(new NegotiationResult(fresh, List.of(), List.of()));

        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(false);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.STAGNATING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evaluatorRepeatRetries())
                .isEqualTo(com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP);
        assertThat(result.hasSignal(com.walpro.autobuilder.core.lifecycle.BasicSignals.REPLAN_REQUESTED))
                .as("T1-DN: BUILD_FIX subgoal at retry cap must NOT emit REPLAN_REQUESTED")
                .isFalse();
    }

    // --- T1-EC ---------------------------------------------------------------

    @Test
    void execute_T1EC_compileProbeFails_returnsRejectedWithoutCallingLLM() {
        when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(
                        new BuildOutcome(BuildResult.COMPILE_ERROR, "error: incompatible types", 1500L, "mvn compile", "cycle-1-probe"),
                        null));

        // Probe-fail rawOutcome flows through heuristics/interpreter — stub the pipeline.
        EvaluationOutcome probeRejected = new EvaluationOutcome(
                0.0, MutationAcceptanceState.REJECTED, GoalProgressState.NOT_EVALUATED, "Compile probe failed", null);
        when(problemDetector.detectAndUpdate(any(), any(), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), any(), anyList()))
                .thenReturn(new NegotiationResult(probeRejected, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(false);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(evaluationInterpreter.interpret(any(), any(), anyBoolean())).thenReturn(interp);

        EvaluationAgent agent = newAgent(List.of());
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "bad"), Set.of()))
                .previousOutput(Map.of())
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation("src/Main.java", "class Main {}")),
                        List.of(), "summary", "raw"))
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = agent.execute(ctx);

        verify(evaluationAdapter, never()).evaluate(any());
        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.criteriaAssessments()).isNotNull();
    }

    @Test
    void execute_T1EC_compileProbeSkipped_proceedsToLLM() {
        // SKIPPED (build disabled) must not block the LLM call
        when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled"), null));

        EvaluationOutcome llmOutcome = new EvaluationOutcome(
                0.80, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "looks good", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(llmOutcome, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(llmOutcome), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(llmOutcome), anyList()))
                .thenReturn(new NegotiationResult(llmOutcome, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), any(), anyBoolean())).thenReturn(interp);

        EvaluationAgent agent = newAgent(List.of());
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "ok"), Set.of()))
                .previousOutput(Map.of())
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation("src/Main.java", "class Main {}")),
                        List.of(), "summary", "raw"))
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = agent.execute(ctx);

        verify(evaluationAdapter).evaluate(any());
        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void persistMutationStateToMeta_delegatesToService_cycle2Scenario() {
        // T1-ED: EvaluationAgent delegates to MutationSnapshotService.persistMutationState.
        // The fallback-create behavior (meta.json missing) is tested in MutationSnapshotServiceTest.
        EvaluationAgent agent = newAgent(List.of());

        EvaluationOutcome raw = new EvaluationOutcome(
                0.86, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(raw, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(raw), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(raw), anyList()))
                .thenReturn(new NegotiationResult(raw, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-2")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/BookController.java", "class BookController {}"), Set.of()))
                .previousOutput(Map.of())
                .currentSubGoalId("goal-ce1310f7")
                .evolutionRanThisCycle(true)
                .build();

        CycleContextData result = agent.execute(ctx);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(mutationSnapshotService).persistMutationState(captor.capture(), any());
        assertThat(captor.getValue().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    // --- Stale rollback-point safety -----------------------------------------

    @Test
    void execute_compileError_doesNotFireSecondRollbackEvenWithStaleLastRollbackPoint() {
        // Scenario: a prior ACCEPTED cycle left lastRollbackPoint set on context (leak).
        // This cycle's probe returns COMPILE_ERROR (so it already rolled back its own snapshot
        // and returned a null rollbackPoint). The post-eval rollback path must NOT fire
        // against the stale snapshot — that would undo files from the prior accepted merge.
        when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(
                        new BuildOutcome(BuildResult.COMPILE_ERROR, "error: cannot find symbol", 1500L, "mvn compile", "cycle-1-probe"),
                        null));

        EvaluationOutcome probeRejected = new EvaluationOutcome(
                0.0, MutationAcceptanceState.REJECTED, GoalProgressState.NOT_EVALUATED, "Compile probe failed", null);
        when(problemDetector.detectAndUpdate(any(), any(), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), any(), anyList()))
                .thenReturn(new NegotiationResult(probeRejected, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(false);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.NOT_EVALUATED);
        when(evaluationInterpreter.interpret(any(), any(), anyBoolean())).thenReturn(interp);

        // Stale rollback point left over from a prior ACCEPTED cycle.
        com.walpro.autobuilder.core.merge.MergeRollbackPoint stalePoint =
                new com.walpro.autobuilder.core.merge.MergeRollbackPoint(
                        "prior-cycle-probe", Map.of("src/PriorlyMerged.java", ""), Set.of("src/PriorlyMerged.java"));

        EvaluationAgent agent = newAgent(List.of());
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "bad"), Set.of()))
                .previousOutput(Map.of())
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation("src/Main.java", "class Main {}")),
                        List.of(), "summary", "raw"))
                .currentSubGoalId("sg-1")
                .lastRollbackPoint(stalePoint)
                .build();

        agent.execute(ctx);

        // EvaluationAgent must NOT invoke MergeRollbackService.rollback — the probe already
        // handled its own rollback internally, and the stale snapshot would have undone the
        // previous cycle's accepted merge.
        verify(mergeRollbackService, never()).rollback(any(), anyString(), any());
    }

    @Test
    void execute_acceptedOutcome_clearsLastRollbackPointToPreventStaleLeak() {
        // After ACCEPTED, the probe rollback point is no longer the right "undo" target —
        // MergeAgent will commit next. Leaving it set leaks the snapshot into the next cycle,
        // where it could fire against unrelated files (the bug we just fixed).
        com.walpro.autobuilder.core.merge.MergeRollbackPoint probePoint =
                new com.walpro.autobuilder.core.merge.MergeRollbackPoint(
                        "cycle-2-probe", Map.of("src/Main.java", "original"), Set.of());
        when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(
                        new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "mvn compile", "cycle-2-probe"),
                        probePoint));

        EvaluationOutcome accepted = new EvaluationOutcome(
                0.85, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(accepted, List.of()));
        when(problemDetector.detectAndUpdate(any(), eq(accepted), any())).thenReturn(ProblemContext.empty());
        when(heuristicNegotiator.negotiate(any(), eq(accepted), anyList()))
                .thenReturn(new NegotiationResult(accepted, List.of(), List.of()));
        InterpretationResult interp = mock(InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean())).thenReturn(interp);

        EvaluationAgent agent = newAgent(List.of());
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-2")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "class Main {}"), Set.of()))
                .previousOutput(Map.of())
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation("src/Main.java", "class Main {}")),
                        List.of(), "s", "r"))
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = agent.execute(ctx);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(result.lastRollbackPoint())
                .as("ACCEPTED must clear lastRollbackPoint so it cannot leak into the next REJECTED cycle")
                .isNull();
    }
}