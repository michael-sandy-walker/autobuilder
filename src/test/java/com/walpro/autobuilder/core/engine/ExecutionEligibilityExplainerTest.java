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
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot.ActionEvidence;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
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

        // Make safeFetchSnapshot run
        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchLatestSnapshot("g1", "sg1", 10)).thenReturn(snap);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1"); // required by evolution gate

        ActionEvidence ev = mock(ActionEvidence.class);
        when(ev.status()).thenReturn("ACCEPTED");
        when(snap.lastEvaluation()).thenReturn(ev);

        when(snap.lastMerge()).thenReturn(null);
        when(snap.mergedViaGraph()).thenReturn(Boolean.FALSE);

        assertThat(explainer.canExecute("evolution", ctx)).isFalse();
        assertThat(explainer.explainBlockers("evolution", ctx))
                .contains("current attempt already ACCEPTED; merge pending");
    }

    @Test
    void evolution_ok_true() {
        CycleContextData ctx = mock(CycleContextData.class);

        when(ctx.currentGoalId()).thenReturn("g1");
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        OrchestrationSnapshot snap = mock(OrchestrationSnapshot.class);
        when(orchestrationSnapshotService.fetchLatestSnapshot("g1", "sg1", 10)).thenReturn(snap);

        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_EVOLUTION), anyDouble())).thenReturn(true);

        ActionEvidence ev = mock(ActionEvidence.class);
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
        when(ctx.lastEvolutionFingerprint()).thenReturn("fp1");
        when(ctx.lastEvaluatedEvolutionFingerprint()).thenReturn(null);

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
    void merge_missingRole_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(false);

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .contains("missing ROLE_MERGE");

        verifyNoInteractions(fileVariantCollector);
    }

    @Test
    void merge_missingVariant_blocks() throws Exception {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(fileVariantCollector.hasAnyVariant(ctx)).thenReturn(false);

        assertThat(explainer.canExecute("merge", ctx)).isFalse();
        assertThat(explainer.explainBlockers("merge", ctx))
                .contains("missing variant");
    }

    @Test
    void merge_ok_true() throws Exception {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasSignal(BasicSignals.ROLE_MERGE)).thenReturn(true);
        when(ctx.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
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
    void analysis_nonCognitive_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("do stuff");
        when(kindClassifier.classify("do stuff")).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);

        assertThat(explainer.canExecute("analysis", ctx)).isFalse();
        assertThat(explainer.explainBlockers("analysis", ctx))
                .contains("subgoal is not COGNITIVE/HYBRID");
    }

    @Test
    void analysis_cognitiveButNoTrigger_blocks() {
        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.hasStrongSignal(eq(BasicSignals.ROLE_ANALYSIS), anyDouble())).thenReturn(true);
        when(ctx.currentSubGoalId()).thenReturn("sg1");

        GoalEntity sg = mock(GoalEntity.class);
        when(goalPlannerService.getGoalById("sg1")).thenReturn(sg);
        when(sg.getDescription()).thenReturn("think");
        when(kindClassifier.classify("think")).thenReturn(GoalKindClassifier.GoalKind.COGNITIVE);

        when(ctx.hasSignal(BasicSignals.LOW_CONFIDENCE)).thenReturn(false);
        when(ctx.hasSignal(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.hasStrongSignal(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

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
        when(kindClassifier.classify("think")).thenReturn(GoalKindClassifier.GoalKind.HYBRID);

        when(ctx.hasSignal(BasicSignals.LOW_CONFIDENCE)).thenReturn(true);

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

        when(ctx.hasSignal(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.hasSignal(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(ctx.deliberationResult()).thenReturn(null);
        when(ctx.hasStrongSignal(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

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

        when(ctx.hasSignal(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.hasSignal(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(ctx.deliberationResult()).thenReturn(null);
        when(ctx.hasStrongSignal(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);

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
        when(ctx.hasSignal(BasicSignals.STAGNATION)).thenReturn(false);
        when(ctx.hasSignal(BasicSignals.PLANNING_THROTTLED)).thenReturn(false);
        when(ctx.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        DeliberationResult dr = mock(DeliberationResult.class);
        when(dr.mode()).thenReturn(DeliberationMode.BOOTSTRAP);
        when(ctx.deliberationResult()).thenReturn(dr);

        when(ctx.hasStrongSignal(eq(BasicSignals.REPLAN_REQUESTED), anyDouble())).thenReturn(false);
        when(ctx.currentGoalId()).thenReturn("g1");

        assertThat(explainer.explainBlockers("planner", ctx)).isEmpty();
        assertThat(explainer.canExecute("planner", ctx)).isTrue();
    }
}