package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.build.PackageGate;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4-CC: decisive gate acceptance on gate-born blocker subgoals. Fixture shape replays the
 * 2026-07-16 fatal evaluation: a packaging-born {@code [BUILD_FIX]} subgoal, a staged
 * compile-clean candidate that creates the missing main class, and an LLM REJECTED assessment
 * with a hallucinated rationale — the originating gate's result must take precedence over the assessment.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAgentGateCheckTest {

    @Mock private EvaluationLLMAdapter evaluationAdapter;
    @Mock private ScoringService scoringService;
    @Mock private EvaluationInterpreter evaluationInterpreter;
    @Mock private ProblemDetector problemDetector;
    @Mock private HeuristicNegotiator heuristicNegotiator;
    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private EvaluationActionRegistrar evaluationActionRegistrar;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private MutationSnapshotService mutationSnapshotService;
    @Mock private CompileProbeService compileProbeService;
    @Mock private MergeRollbackService mergeRollbackService;
    @Mock private FileService fileService;
    @Mock private PackageGate packageGate;
    @Mock private BootGate bootGate;
    @Mock private TestRunGate testRunGate;

    private EvaluationAgent agent;

    @BeforeEach
    void setUp() {
        agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.of(), graphFacade, evaluationActionRegistrar,
                goalPlannerService, mutationSnapshotService, compileProbeService,
                mergeRollbackService, fileService);
        agent.setPackageGate(packageGate);
        agent.setBootGate(bootGate);
        agent.setTestRunGate(testRunGate);
    }

    private static CycleContextData contextOnBlocker(String subGoalId, BuildResult probeResult) {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-t4cc")
                .build()
                .withCurrentSubGoalId(subGoalId);
        return probeResult == null ? ctx
                : ctx.withLastProbeOutcome(new BuildOutcome(probeResult, "", 0L, "", "cycle-t4cc"));
    }

    /** The 2026-07-16 15:19:05 assessment, verbatim shape. */
    private static EvaluationOutcome hallucinatedRejection() {
        return new EvaluationOutcome(0.35, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING,
                "The change does not address the core build issue preventing the application from packaging; "
                        + "the main class is present but the build system cannot locate it.", null);
    }

    private static NegotiationResult noOverrides(EvaluationOutcome outcome) {
        return new NegotiationResult(outcome, List.of(), List.of());
    }

    @Test
    void packagingBornBlocker_gatePasses_flipsRejectToDecisiveAccept_keepingTheAssessmentAsBackground() {
        when(goalPlannerService.getGoalMetadataString("goal-blocker", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(BlockerTypes.GATE_PACKAGE);
        when(packageGate.verifyPackageable("cycle-t4cc")).thenReturn(Optional.empty());
        CycleContextData ctx = contextOnBlocker("goal-blocker", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(result.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(result.feedbackMessage())
                .contains("package-gate passed")
                .contains("recorded, not applied");
        verify(packageGate).verifyPackageable("cycle-t4cc");
    }

    @Test
    void gateStillFailing_keepsTheReject_butCarriesTheGateOutputAsTheReason() {
        when(goalPlannerService.getGoalMetadataString("goal-blocker", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(BlockerTypes.GATE_PACKAGE);
        when(packageGate.verifyPackageable("cycle-t4cc"))
                .thenReturn(Optional.of("[ERROR] Unable to find main class"));
        CycleContextData ctx = contextOnBlocker("goal-blocker", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.feedbackMessage())
                .contains("package-gate still failing")
                .contains("Unable to find main class");
    }

    @Test
    void criticalHeuristicFinding_keepsItsDecision_gateNeverConsulted() {
        // A deterministic defect finding (e.g. StoreCoherenceHeuristic) reports a different
        // defect class than the gate measures — the gate must not supersede it.
        CycleContextData ctx = contextOnBlocker("goal-blocker", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();
        NegotiationResult withCritical = new NegotiationResult(rejected,
                List.of(HeuristicOpinion.of("StoreCoherenceHeuristic", 0.9,
                        MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "parallel store introduced")),
                List.of());

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, withCritical, rejected);

        assertThat(result).isSameAs(rejected);
        verifyNoInteractions(packageGate, bootGate, testRunGate);
    }

    @Test
    void nonGateBornSubgoal_isUntouched() {
        when(goalPlannerService.getGoalMetadataString("goal-plain", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(null);
        CycleContextData ctx = contextOnBlocker("goal-plain", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result).isSameAs(rejected);
        verifyNoInteractions(packageGate, bootGate, testRunGate);
    }

    @Test
    void probeNotGreen_gateNeverConsulted() {
        lenient().when(goalPlannerService.getGoalMetadataString("goal-blocker", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(BlockerTypes.GATE_PACKAGE);
        CycleContextData ctx = contextOnBlocker("goal-blocker", BuildResult.COMPILE_ERROR);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result).isSameAs(rejected);
        verifyNoInteractions(packageGate, bootGate, testRunGate);
    }

    @Test
    void acceptedAssessment_neverFlippedOnGateFailure_incrementalFixesStillLand() {
        // The T4-BP-4 validation run succeeded through 8 consecutive gate refusals precisely
        // because partial fixes kept merging — a gate failure must not undo an LLM accept.
        CycleContextData ctx = contextOnBlocker("goal-blocker", BuildResult.COMPILE_OK);
        EvaluationOutcome accepted = new EvaluationOutcome(0.8, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "ok", null);

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(accepted), accepted);

        assertThat(result).isSameAs(accepted);
        verifyNoInteractions(packageGate, bootGate, testRunGate);
    }

    @Test
    void bootGate_usesRootSpecText_andFlipsOnPass() {
        when(goalPlannerService.getGoalMetadataString("goal-boot", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(BlockerTypes.GATE_BOOT);
        var root = new com.walpro.autobuilder.goal.GoalEntity();
        root.setDescription("Evolve the app; expose GET /books");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(bootGate.verifyBootsAndServes(eq("cycle-t4cc"), any())).thenReturn(Optional.empty());
        CycleContextData ctx = contextOnBlocker("goal-boot", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        verify(bootGate).verifyBootsAndServes("cycle-t4cc", "Evolve the app; expose GET /books");
    }

    @Test
    void gateBeanAbsent_fallsBackToTheNegotiatedOutcome() {
        agent.setPackageGate(null);
        when(goalPlannerService.getGoalMetadataString("goal-blocker", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(BlockerTypes.GATE_PACKAGE);
        CycleContextData ctx = contextOnBlocker("goal-blocker", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result).isSameAs(rejected);
    }

    @Test
    void gateThrowing_fallsBackToTheNegotiatedOutcome() {
        when(goalPlannerService.getGoalMetadataString("goal-test", BlockerTypes.ORIGINATING_GATE_KEY))
                .thenReturn(BlockerTypes.GATE_TEST);
        var root = new com.walpro.autobuilder.goal.GoalEntity();
        root.setDescription("plain spec");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(testRunGate.verifyTestsPass(eq("cycle-t4cc"), anyBoolean()))
                .thenThrow(new IllegalStateException("gate exploded"));
        CycleContextData ctx = contextOnBlocker("goal-test", BuildResult.COMPILE_OK);
        EvaluationOutcome rejected = hallucinatedRejection();

        EvaluationOutcome result = agent.applyOriginatingGateCheck(ctx, noOverrides(rejected), rejected);

        assertThat(result).isSameAs(rejected);
    }
}
