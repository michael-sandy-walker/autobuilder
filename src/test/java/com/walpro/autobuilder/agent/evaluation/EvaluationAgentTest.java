package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.core.build.BlockerTypes;
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
                mutationSnapshotService
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
    void execute_whenAcceptedAndEvolutionRanThisCycle_persistsMutationStateToMetaJson() throws Exception {
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

        CycleContextData existingMeta = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalId("sub-1")
                .build();
        when(mutationSnapshotService.readMutationMeta("mutations/cycle-1/meta.json")).thenReturn(existingMeta);
        when(mutationSnapshotService.writeMutationMeta(any())).thenAnswer(inv -> inv.getArgument(0));

        agent.execute(ctx);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(mutationSnapshotService).writeMutationMeta(captor.capture());
        assertThat(captor.getValue().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void execute_whenRejectedAndEvolutionRanThisCycle_persistsRejectedStateToMetaJson() throws Exception {
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

        CycleContextData existingMeta = CycleContextData.builder().cycleName("cycle-1").build();
        when(mutationSnapshotService.readMutationMeta("mutations/cycle-1/meta.json")).thenReturn(existingMeta);
        when(mutationSnapshotService.writeMutationMeta(any())).thenAnswer(inv -> inv.getArgument(0));

        agent.execute(ctx);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(mutationSnapshotService).writeMutationMeta(captor.capture());
        assertThat(captor.getValue().mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void execute_whenNoEvolutionThisCycleAndNoMutationPath_doesNotPersistToMeta() throws Exception {
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

        verify(mutationSnapshotService, never()).writeMutationMeta(any());
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
}