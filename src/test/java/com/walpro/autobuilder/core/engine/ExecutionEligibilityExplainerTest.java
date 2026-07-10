package com.walpro.autobuilder.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot.ActionRecord;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionEligibilityExplainerTest {

    @Mock FileVariantCollector fileVariantCollector;
    @Mock OrchestrationSnapshotService orchestrationSnapshotService;
    @Mock GoalPlannerService goalPlannerService;
    @Mock GoalKindClassifier kindClassifier;

    ExecutionEligibilityExplainer explainer;

    @BeforeEach
    void setup() {
        explainer = new ExecutionEligibilityExplainer(
                fileVariantCollector,
                orchestrationSnapshotService,
                goalPlannerService,
                kindClassifier
        );
    }

    @Test
    void canExecute_nullContext_false() {
        assertThat(explainer.canExecute("planner", null)).isFalse();
        assertThat(explainer.explainBlockers("planner", null)).contains("context is null");
    }

    @Test
    void canExecute_unknownRole_false() {
        CycleContextData ctx = mock(CycleContextData.class);
        assertThat(explainer.canExecute("wat", ctx)).isFalse();
        assertThat(explainer.explainBlockers("wat", ctx)).contains("unknown role gating");
    }

    @Test
    void evolution_alreadyMerged_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.merged()).thenReturn(true);

        assertThat(explainer.canExecute("evolution", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evolution", ctx))
                .containsOnly("merge already occurred in this cycle — post-merge evolution blocked");
    }

    @Test
    void evolution_missingRoleSignal_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("evolution", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evolution", ctx))
                .contains("missing strong ROLE_EVOLUTION (>=0.10)");
    }

    @Test
    void evolution_missingSubGoal_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("  ");

        assertThat(explainer.canExecute("evolution", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evolution", ctx))
                .contains("missing subgoal");
    }

    @Test
    void evolution_acceptedNotMerged_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        String subGoalId = "sg1";
        GoalEntity currentSubGoalEntity = mock(GoalEntity.class);
        when(currentSubGoalEntity.getId()).thenReturn(subGoalId);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentSubGoalEntity);

        // Make safeFetchSnapshot run
        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn(subGoalId);
        when(ctx.lastAttemptId()).thenReturn("attempt-1");

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchSnapshot("g1", subGoalId, "attempt-1", 10)).thenReturn(snap);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1"); // required by evolution gate

        ActionRecord ev = mock(ActionRecord.class);
        when(ev.status()).thenReturn("ACCEPTED");
        when(snap.lastEvaluation()).thenReturn(ev);

        // Evolution DID happen under this attempt — this is a genuine accepted-not-merged state.
        ActionRecord evo = mock(ActionRecord.class);
        when(snap.lastEvolution()).thenReturn(evo);

        when(snap.lastMerge()).thenReturn(null);
        when(snap.mergedViaGraph()).thenReturn(Boolean.FALSE);

        // Confirm there are ACCEPTED variants on disk — makes the blocker genuine (not phantom).
        try {
            when(fileVariantCollector.hasAnyVariant(ctx)).thenReturn(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(explainer.canExecute("evolution", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evolution", ctx))
                .contains("latest evaluation already ACCEPTED, but merge still pending");
    }

    @Test
    void evolution_acceptedWithoutEvolution_doesNotBlock() {
        // A meta-step evaluation can record ACCEPTED under an attempt that has no corresponding
        // evolution. This phantom state must not block the next evolution attempt.
        CycleContextData ctx = mock(CycleContextData.class);

        String subGoalId = "sg1";
        GoalEntity currentSubGoalEntity = mock(GoalEntity.class);
        when(currentSubGoalEntity.getId()).thenReturn(subGoalId);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentSubGoalEntity);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn(subGoalId);
        when(ctx.lastAttemptId()).thenReturn("attempt-1");

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchSnapshot("g1", subGoalId, "attempt-1", 10)).thenReturn(snap);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        ActionRecord ev = mock(ActionRecord.class);
        when(ev.status()).thenReturn("ACCEPTED");
        when(snap.lastEvaluation()).thenReturn(ev);

        // No evolution under this attempt — phantom accepted state.
        when(snap.lastEvolution()).thenReturn(null);
        when(snap.lastMerge()).thenReturn(null);
        when(snap.mergedViaGraph()).thenReturn(Boolean.FALSE);

        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
    }

    @Test
    void evolution_ok_true() {
        CycleContextData ctx = mock(CycleContextData.class);

        String subGoalId = "sg1";
        GoalEntity currentSubGoalEntity = mock(GoalEntity.class);
        when(currentSubGoalEntity.getId()).thenReturn(subGoalId);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentSubGoalEntity);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn(subGoalId);
        when(ctx.lastAttemptId()).thenReturn("attempt-1");

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchSnapshot("g1", subGoalId, "attempt-1", 10)).thenReturn(snap);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        ActionRecord ev = mock(ActionRecord.class);
        when(ev.status()).thenReturn("REJECTED");
        when(snap.lastEvaluation()).thenReturn(ev);

        when(snap.lastMerge()).thenReturn(null);
        when(snap.mergedViaGraph()).thenReturn(Boolean.FALSE);

        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
    }

    @Test
    void evaluation_missingRoleAndDiff_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVALUATION), anyDouble())).thenReturn(false);
        when(ctx.projectDiff()).thenReturn(null);

        assertThat(explainer.canExecute("evaluation", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evaluation", ctx))
                .contains("missing strong ROLE_EVALUATION (>=0.10)")
                .contains("missing project diff");
    }

    @Test
    void evaluation_alreadyEvaluatedFingerprint_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVALUATION), anyDouble())).thenReturn(true);
        when(ctx.projectDiff()).thenReturn(mock(ProjectDiffResult.class));
        when(ctx.lastEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.lastEvaluatedEvolutionFingerprint()).thenReturn("fp1");

        assertThat(explainer.canExecute("evaluation", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evaluation", ctx))
                .contains("already evaluated current evolution fingerprint");
    }

    @Test
    void evaluation_T3BR_fingerprintRepeats_allCriteriaBanked_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVALUATION), anyDouble())).thenReturn(true);
        when(ctx.projectDiff()).thenReturn(mock(ProjectDiffResult.class));
        when(ctx.lastEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.lastEvaluatedEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(goalPlannerService.hasAcceptanceCriteria("sg1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sg1")).thenReturn(true);
        when(ctx.evolutionRanThisCycle()).thenReturn(true);

        assertThat(explainer.explainBlockers("evaluation", ctx))
                .contains("already evaluated current evolution fingerprint and all criteria banked");
    }

    @Test
    void evaluation_T3BR_fingerprintRepeats_criteriaUnbanked_retriesUnderCap_allowed() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVALUATION), anyDouble())).thenReturn(true);
        when(ctx.projectDiff()).thenReturn(mock(ProjectDiffResult.class));
        when(ctx.lastEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.lastEvaluatedEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(goalPlannerService.hasAcceptanceCriteria("sg1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sg1")).thenReturn(false);
        when(ctx.evaluatorRepeatRetries()).thenReturn(1);
        when(ctx.evolutionRanThisCycle()).thenReturn(true);

        // No fingerprint-related blocker should be present — eval is allowed to re-run.
        assertThat(explainer.explainBlockers("evaluation", ctx))
                .doesNotContain("already evaluated current evolution fingerprint")
                .doesNotContain("already evaluated current evolution fingerprint and all criteria banked")
                .doesNotContain("evaluator retry cap reached on repeated fingerprint with unbanked criteria");
    }

    @Test
    void evaluation_T3BR_fingerprintRepeats_criteriaUnbanked_retriesAtCap_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVALUATION), anyDouble())).thenReturn(true);
        when(ctx.projectDiff()).thenReturn(mock(ProjectDiffResult.class));
        when(ctx.lastEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.lastEvaluatedEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(goalPlannerService.hasAcceptanceCriteria("sg1")).thenReturn(true);
        when(goalPlannerService.allCriteriaDefinedAndBanked("sg1")).thenReturn(false);
        when(ctx.evaluatorRepeatRetries())
                .thenReturn(ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP);
        when(ctx.evolutionRanThisCycle()).thenReturn(true);

        assertThat(explainer.explainBlockers("evaluation", ctx))
                .contains("evaluator retry cap reached on repeated fingerprint with unbanked criteria");
    }

    @Test
    void merge_missingRole_blocks_not() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(false);

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .contains("missing ROLE_MERGE");
    }

    @Test
    void merge_noSubGoal_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn(null);

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .containsOnly("no active subgoal — merge blocked");
    }

    @Test
    void merge_blankSubGoal_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("  ");

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .containsOnly("no active subgoal — merge blocked");
    }

    @Test
    void merge_missingVariant_blocks() throws Exception {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(fileVariantCollector.hasAnyVariant(ctx)).thenReturn(false);

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .containsOnly("missing variant");
    }

    @Test
    void merge_ok_true() throws Exception {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(fileVariantCollector.hasAnyVariant(ctx)).thenReturn(true);

        assertThat(explainer.explainBlockers("merge", ctx)).isEmpty();
        assertThat(explainer.canExecute("merge", ctx)).isTrue();
    }

    @Test
    void analysis_missingRole_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx))
                .contains("missing strong ROLE_ANALYSIS (>=0.10)");
    }

    @Test
    void analysis_EXECUTABLE_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("do stuff");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx))
                .contains("subgoal is not COGNITIVE/HYBRID and REPLAN_REQUESTED is absent");
    }

    @Test
    void analysis_executableWithReplanRequested_allows() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("fix the auth service");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(true);
        when(ctx.hasOpenRequest(BasicSignals.LOW_CONFIDENCE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);

        assertThat(explainer.canExecute("analysis", ctx)).isTrue();
        assertThat(explainer.explainBlockers("analysis", ctx)).isEmpty();
    }

    @Test
    void analysis_cognitiveButNoTrigger_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("think");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.COGNITIVE);

        when(ctx.hasOpenRequest(BasicSignals.LOW_CONFIDENCE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx))
                .contains("no trigger present (LOW_CONFIDENCE/STAGNATION/REPLAN_REQUESTED)");
    }

    @Test
    void analysis_cognitiveAndTriggered_ok_true() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("think");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.HYBRID);

        when(ctx.hasOpenRequest(BasicSignals.LOW_CONFIDENCE)).thenReturn(true);

        assertThat(explainer.explainBlockers("analysis", ctx)).isEmpty();
        assertThat(explainer.canExecute("analysis", ctx)).isTrue();
    }

    @Test
    void planner_missingRole_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("planner", ctx)).isFalse();
        assertThat(explainer.explainBlockers("planner", ctx))
                .contains("missing strong ROLE_PLANNER (>=0.10)");
    }

    @Test
    void planner_cognitiveAlreadyProposed_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(ctx.analysisScopeSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sg);
        when(sg.getKind()).thenReturn(GoalKindClassifier.GoalKind.COGNITIVE);

        AnalysisOutcome ao = mock(AnalysisOutcome.class);
        when(ao.proposedExecutableSubGoals()).thenReturn(List.of("x"));
        when(ctx.analysisOutcome()).thenReturn(ao);

        assertThat(explainer.canExecute("planner", ctx)).isFalse();
        assertThat(explainer.explainBlockers("planner", ctx))
                .contains("analysis already proposed executable subgoals for this cognitive subgoal scope");
    }

    @Test
    void planner_noTrigger_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("sg1"); // hasSubGoal => noGoalState false

        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);

        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(ctx.deliberationResult()).thenReturn(null);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

        // goalId != subGoalId => needsInitialRootDecomposition false
        assertThat(explainer.canExecute("planner", ctx)).isFalse();
        assertThat(explainer.explainBlockers("planner", ctx))
                .contains("neither noGoalState nor STAGNATION/BOOTSTRAP/needsInitialDecomposition/REPLAN_REQUESTED present");
    }

    @Test
    void planner_needsInitialDecomposition_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("g1"); // sitting on root

        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(ctx.deliberationResult()).thenReturn(null);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

        GoalEntity root = mock(GoalEntity.class);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(root.getMetadata()).thenReturn(Map.of()); // plannedOnce absent

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void planner_bootstrap_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);

        when(ctx.currentSubGoalId()).thenReturn("sg1"); // noGoalState false
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        DeliberationResult dr = mock(DeliberationResult.class);
        when(dr.mode()).thenReturn(DeliberationMode.BOOTSTRAP);
        when(ctx.deliberationResult()).thenReturn(dr);

        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);
        when(ctx.currentGoalId()).thenReturn("g1");

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void evolution_acceptedAndMergedViaGraph_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        String subgoalId = "sg1";
        GoalEntity currentSubGoalEntity = mock(GoalEntity.class);
        when(currentSubGoalEntity.getId()).thenReturn(subgoalId);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentSubGoalEntity);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn(subgoalId);
        when(ctx.lastAttemptId()).thenReturn("attempt-1");

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchSnapshot("g1", subgoalId, "attempt-1", 10)).thenReturn(snap);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        ActionRecord ev = mock(ActionRecord.class);
        when(ev.status()).thenReturn("ACCEPTED");
        when(snap.lastEvaluation()).thenReturn(ev);

        when(snap.lastMerge()).thenReturn(null);
        when(snap.mergedViaGraph()).thenReturn(Boolean.TRUE);

        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
    }

    @Test
    void evolution_snapshotFetchThrows_failsOpen_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        String subgoalId = "sg1";
        GoalEntity currentSubGoalEntity = mock(GoalEntity.class);
        when(currentSubGoalEntity.getId()).thenReturn(subgoalId);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentSubGoalEntity);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn(subgoalId);
        when(ctx.lastAttemptId()).thenReturn("attempt-1");

        when(orchestrationSnapshotService.fetchSnapshot("g1", subgoalId, "attempt-1", 10))
                .thenThrow(new RuntimeException("db down"));

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
    }

    @Test
    void merge_variantCollectorThrows_doesNotThrow_butBlocksMissingVariant() throws Exception {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        when(fileVariantCollector.hasAnyVariant(ctx)).thenThrow(new RuntimeException("fs issue"));

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .contains("missing variant");
    }

    @Test
    void analysis_missingDescription_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        // sg exists but missing description
        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("   ");

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx))
                .contains("missing subgoal description");

        verifyNoInteractions(kindClassifier);
    }

    @Test
    void analysis_goalMissing_usesContextDescription() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        when(goalPlannerService.getGoalById("sg1")).thenReturn(null);
        when(ctx.currentSubGoalDescription()).thenReturn("think");

        when(kindClassifier.classify("think")).thenReturn(GoalKindClassifier.GoalKind.COGNITIVE);
        when(ctx.hasOpenRequest(BasicSignals.LOW_CONFIDENCE)).thenReturn(true);

        assertThat(explainer.explainBlockers("analysis", ctx)).isEmpty();
        assertThat(explainer.canExecute("analysis", ctx)).isTrue();
    }

    @Test
    void planner_throttled_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(true);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("planner", ctx)).isFalse();
        assertThat(explainer.explainBlockers("planner", ctx))
                .contains("planning throttled (disproportional inflow vs completion outflow)");

        verifyNoInteractions(goalPlannerService);
    }

    @Test
    void planner_throttled_butStrongReplan_bypassesThrottle() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(true);
        // REPLAN_REQUESTED >= 10.0 — bypass fires
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(true);

        when(ctx.currentSubGoalId()).thenReturn("sg1"); // noGoalState false
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.deliberationResult()).thenReturn(null);
        when(ctx.currentGoalId()).thenReturn("g1");

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void planner_stagnationSignal_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);

        when(ctx.currentSubGoalId()).thenReturn("sg1"); // hasSubGoal => noGoalState false
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(true);

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void planner_stagnationViaGoalProgress_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);

        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.STAGNATING);

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void planner_replanRequested_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);

        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(ctx.deliberationResult()).thenReturn(null);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(true);

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void planner_noGoalState_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_PLANNER), anyDouble())).thenReturn(true);
        when(ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);

        when(ctx.currentSubGoalId()).thenReturn("   "); // noGoalState true

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }

    @Test
    void needsInitialRootDecomposition_plannedOnceTrue_false() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("g1");

        GoalEntity root = mock(GoalEntity.class);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(root.getMetadata()).thenReturn(Map.of("plannedOnce", Boolean.TRUE));

        assertThat(explainer.needsInitialRootDecomposition(ctx)).isFalse();
    }

    @Test
    void needsInitialRootDecomposition_plannedOnceAbsent_true() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("g1");

        GoalEntity root = mock(GoalEntity.class);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(root.getMetadata()).thenReturn(Map.of());

        assertThat(explainer.needsInitialRootDecomposition(ctx)).isTrue();
    }

    @Test
    void evolution_snapshotNull_failsOpen_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        GoalEntity sg = mock(GoalEntity.class);
        when(sg.getId()).thenReturn("sg1");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sg);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(ctx.lastAttemptId()).thenReturn("attempt-1");
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        when(orchestrationSnapshotService.fetchSnapshot("g1", "sg1", "attempt-1", 10)).thenReturn(null);

        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
    }

    @Test
    void evolution_lastEvaluationNull_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        GoalEntity sg = mock(GoalEntity.class);
        when(sg.getId()).thenReturn("sg1");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sg);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(ctx.lastAttemptId()).thenReturn("attempt-1");
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchSnapshot("g1", "sg1", "attempt-1", 10)).thenReturn(snap);

        when(snap.lastEvaluation()).thenReturn(null);
        when(snap.lastMerge()).thenReturn(null);
        when(snap.mergedViaGraph()).thenReturn(Boolean.FALSE);

        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
    }

    @Test
    void evolution_acceptedAndLastMergePresent_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        GoalEntity sg = mock(GoalEntity.class);
        when(sg.getId()).thenReturn("sg1");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sg);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(ctx.lastAttemptId()).thenReturn("attempt-1");
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchSnapshot("g1", "sg1", "attempt-1", 10)).thenReturn(snap);

        ActionRecord ev = mock(ActionRecord.class);
        when(ev.status()).thenReturn("ACCEPTED");
        when(snap.lastEvaluation()).thenReturn(ev);

        // merged evidence via lastMerge object
        ActionRecord mergeEv = mock(ActionRecord.class);
        when(snap.lastMerge()).thenReturn(mergeEv);

        assertThat(explainer.explainBlockers("evolution", ctx)).isEmpty();
        assertThat(explainer.canExecute("evolution", ctx)).isTrue();
    }

    @Test
    void merge_variantPresent_mutationStateIsNotABlocker() throws Exception {
        // T1-CD: mutationState is no longer checked in eligibility — hasAnyVariant() already
        // guarantees ACCEPTED variants only. A REJECTED current-cycle mutationState does not
        // prevent merge when ROLE_MERGE is present and accepted variants exist on disk.
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");
        when(fileVariantCollector.hasAnyVariant(ctx)).thenReturn(true);

        assertThat(explainer.canExecute("merge", ctx)).isTrue();
        assertThat(explainer.explainBlockers("merge", ctx)).isEmpty();
    }

    @Test
    void analysis_triggeredByStagnation_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("think");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.COGNITIVE);

        when(ctx.hasOpenRequest(BasicSignals.LOW_CONFIDENCE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(true);

        assertThat(explainer.explainBlockers("analysis", ctx)).isEmpty();
        assertThat(explainer.canExecute("analysis", ctx)).isTrue();
    }

    @Test
    void analysis_triggeredByReplanRequestedStrong_allows() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("think");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.HYBRID);

        when(ctx.hasOpenRequest(BasicSignals.LOW_CONFIDENCE)).thenReturn(false);
        when(ctx.hasOpenRequest(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(true);

        assertThat(explainer.explainBlockers("analysis", ctx)).isEmpty();
        assertThat(explainer.canExecute("analysis", ctx)).isTrue();
    }

    @Test
    void needsInitialRootDecomposition_rootNull_false() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("g1");

        when(goalPlannerService.getRootGoal()).thenReturn(null);

        assertThat(explainer.needsInitialRootDecomposition(ctx)).isFalse();
    }

    @Test
    void needsInitialRootDecomposition_metadataNull_true() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("g1");

        GoalEntity root = mock(GoalEntity.class);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(root.getMetadata()).thenReturn(null);

        assertThat(explainer.needsInitialRootDecomposition(ctx)).isTrue();
    }

    // ── T1-DT: build-block gate integration ──

    // ── T2-DE: analysis is eligible when build is broken ──

    @Test
    void analysis_buildBroken_executableSubgoal_noTriggers_stillAllowed() {
        // Root cause of the 20:02 deadlock: BUILD_FIX subgoal is EXECUTABLE, no REPLAN/STAGNATION
        // present — the old code blocked analysis at the COGNITIVE/trigger check despite the build
        // block gate explicitly allowing it. With T2-DE the bypass fires before those checks.
        com.walpro.autobuilder.core.build.BuildBlockGate gate = mock(com.walpro.autobuilder.core.build.BuildBlockGate.class);
        when(gate.isActive(any())).thenReturn(true);
        when(gate.isEligible(eq("analysis"), any())).thenReturn(true);
        explainer.setBuildBlockGate(gate);

        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("[BUILD_FIX] Fix compile error in Foo.java");

        // Deliberately do NOT set up kindClassifier or trigger signals — the bypass must fire first.
        assertThat(explainer.explainBlockers("analysis", ctx)).isEmpty();
        assertThat(explainer.canExecute("analysis", ctx)).isTrue();
        verifyNoInteractions(kindClassifier);
    }

    @Test
    void analysis_buildBroken_missingDescription_stillBlocks() {
        // Description check must still apply even under BUILD_BROKEN — analysis needs context to run.
        com.walpro.autobuilder.core.build.BuildBlockGate gate = mock(com.walpro.autobuilder.core.build.BuildBlockGate.class);
        when(gate.isActive(any())).thenReturn(true);
        when(gate.isEligible(eq("analysis"), any())).thenReturn(true);
        explainer.setBuildBlockGate(gate);

        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("   "); // blank

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx)).contains("missing subgoal description");
    }

    @Test
    void analysis_buildNotBroken_executableSubgoal_stillBlockedByKindCheck() {
        // When build is fine the original COGNITIVE/trigger gating must remain intact.
        com.walpro.autobuilder.core.build.BuildBlockGate gate = mock(com.walpro.autobuilder.core.build.BuildBlockGate.class);
        when(gate.isActive(any())).thenReturn(false);
        explainer.setBuildBlockGate(gate);

        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("implement feature X");
        when(kindClassifier.classify(sg)).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
        when(ctx.hasStrongRequest(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx))
                .contains("subgoal is not COGNITIVE/HYBRID and REPLAN_REQUESTED is absent");
    }

    @Test
    void buildBlocked_evolutionOnNonBuildFixSubgoal_blocksWithBuildBlockedReason() {
        com.walpro.autobuilder.core.build.BuildBlockGate gate = mock(com.walpro.autobuilder.core.build.BuildBlockGate.class);
        when(gate.isActive(any())).thenReturn(true);
        when(gate.isEligible(eq("evolution"), any())).thenReturn(false);
        explainer.setBuildBlockGate(gate);

        CycleContextData ctx = mock(CycleContextData.class);

        assertThat(explainer.canExecute("evolution", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evolution", ctx)).containsExactly("build_blocked");
    }

    @Test
    void buildBlocked_analysisAlwaysAllowed() {
        com.walpro.autobuilder.core.build.BuildBlockGate gate = mock(com.walpro.autobuilder.core.build.BuildBlockGate.class);
        when(gate.isActive(any())).thenReturn(true);
        when(gate.isEligible(eq("analysis"), any())).thenReturn(true);
        explainer.setBuildBlockGate(gate);

        CycleContextData ctx = mock(CycleContextData.class);
        // Analysis still has its own role-specific gating, but the build-block gate must not contribute a blocker.
        assertThat(explainer.explainBlockers("analysis", ctx)).doesNotContain("build_blocked");
    }

    @Test
    void buildBlocked_inactive_doesNotInterfereWithRoleGating() {
        com.walpro.autobuilder.core.build.BuildBlockGate gate = mock(com.walpro.autobuilder.core.build.BuildBlockGate.class);
        when(gate.isActive(any())).thenReturn(false);
        explainer.setBuildBlockGate(gate);

        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.merged()).thenReturn(true);

        assertThat(explainer.explainBlockers("evolution", ctx))
                .containsExactly("merge already occurred in this cycle — post-merge evolution blocked");
    }
}