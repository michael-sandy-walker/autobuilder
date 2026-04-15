package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreRegressionHeuristicTest {

    private final ScoreRegressionHeuristic heuristic = new ScoreRegressionHeuristic();

    @Test
    void testRejectsMajorRegression() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withLastScore(0.8);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("src/Foo.java", "changed"), Set.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.5,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Refactored as requested",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, outcome);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("drop");
    }

    // ── Graduated thresholds (new behaviour) ─────────────────────────────────

    @Test
    void severeRegression_belowAbsoluteFloor_isCritical() {
        // Δ=-0.17, current=0.58 (<0.65) → confidence 0.95 (CRITICAL)
        CycleContextData ctx = CycleContextData.builder().lastScore(0.75).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.58, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null);
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        assertThat(opinion.confidence()).isEqualTo(0.95);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void severeRegression_aboveAbsoluteFloor_isHighNotCritical() {
        // Δ=-0.16, current=0.70 (≥0.65) → confidence 0.75 (HIGH), not CRITICAL
        CycleContextData ctx = CycleContextData.builder().lastScore(0.86).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.70, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null);
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        assertThat(opinion.confidence()).isEqualTo(0.75);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void notableRegression_aboveFloor_isMediumAndDoesNotOverride() {
        // Δ=-0.11, current=0.72 (≥0.65) → confidence 0.50 (MEDIUM) — no single-heuristic override
        CycleContextData ctx = CycleContextData.builder().lastScore(0.83).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.72, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null);
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        assertThat(opinion.confidence()).isEqualTo(0.50);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED); // no override
    }

    @Test
    void sessionPatternRegression_0_80_to_0_71_doesNotOverride() {
        // Previously this caused 78.3% rejection (Δ=-0.09 triggered 0.75 confidence).
        // New: Δ=-0.09 → confidence 0.20 (very LOW) — no override.
        CycleContextData ctx = CycleContextData.builder().lastScore(0.80).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.71, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null);
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        assertThat(opinion.confidence()).isLessThan(0.75);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    // ── T2-AJ: complexity-adaptive tolerance (file-count) ────────────────────

    @Test
    void singleFileMutation_noToleranceChange_severeRejectionStillTriggers() {
        // 1 file → extraFiles=0 → toleranceShift=0, scale=1.0 — no change from baseline
        EvolutionOutcome eo = new EvolutionOutcome(
                List.of(new FileMutation("src/A.java", "content")), List.of(), "summary", "raw");
        CycleContextData ctx = CycleContextData.builder().lastScore(0.80)
                .evolutionOutcome(eo).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.62, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null);
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome); // Δ=-0.18, current=0.62
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void multiFileMutation_widenedTolerance_doesNotRejectBorderlineDrop() {
        // 6 files → extraFiles=5 → toleranceShift=0.05, severeBound=-0.20
        // Δ=-0.17 would normally trigger "severe" (≤-0.15) → rejection.
        // With shift, severe threshold is -0.20, so Δ=-0.17 falls into "notable" (0.50 confidence).
        List<FileMutation> files = List.of(
                new FileMutation("A.java", ""), new FileMutation("B.java", ""),
                new FileMutation("C.java", ""), new FileMutation("D.java", ""),
                new FileMutation("E.java", ""), new FileMutation("F.java", "")
        );
        EvolutionOutcome eo = new EvolutionOutcome(files, List.of(), "summary", "raw");
        CycleContextData ctx = CycleContextData.builder().lastScore(0.87)
                .evolutionOutcome(eo).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.70, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null); // Δ=-0.17
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        // notableBound = -(0.10+0.05) = -0.15; Δ=-0.17 ≤ -0.15 is notable → raw confidence 0.50
        // scale = max(0.65, 1.0 - 5*0.05) = max(0.65, 0.75) = 0.75 → final = 0.375 → no override
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.confidence()).isLessThan(0.75);
    }

    @Test
    void lastAcceptedFileCount_widensTolerance_whenCurrentMutationIsSingleFile() {
        // lastAcceptedMutationFileCount=5 → effectiveFileCount=max(1,5)=5, extraFiles=4
        // toleranceShift=0.04, severeBound=-0.19
        // Δ=-0.16 would be "severe" at 0.75 normally; with shift, severe is ≤-0.19 → drops to notable
        EvolutionOutcome eo = new EvolutionOutcome(
                List.of(new FileMutation("Only.java", "")), List.of(), "summary", "raw");
        CycleContextData ctx = CycleContextData.builder().lastScore(0.86)
                .evolutionOutcome(eo)
                .lastAcceptedMutationFileCount(5)
                .build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.70, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null); // Δ=-0.16
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        // severeBound=-(0.15+0.04)=-0.19; Δ=-0.16 > -0.19 → notable raw=0.50
        // scale=max(0.65, 1.0-4*0.05)=max(0.65,0.80)=0.80 → final=0.40 → no override
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.confidence()).isLessThan(0.75);
    }

    @Test
    void catastrophicDrop_bypassesWideningUnconditionally() {
        // Δ=-0.35 ≤ CATASTROPHIC_DROP_FLOOR (-0.30) → no shift, full confidence
        List<FileMutation> files = List.of(
                new FileMutation("A.java", ""), new FileMutation("B.java", ""),
                new FileMutation("C.java", ""), new FileMutation("D.java", ""),
                new FileMutation("E.java", ""), new FileMutation("F.java", ""),
                new FileMutation("G.java", ""), new FileMutation("H.java", ""),
                new FileMutation("I.java", ""), new FileMutation("J.java", "")
        ); // 10 files, would shift by 0.09 and scale by 0.65 without catastrophic bypass
        EvolutionOutcome eo = new EvolutionOutcome(files, List.of(), "summary", "raw");
        CycleContextData ctx = CycleContextData.builder().lastScore(0.90)
                .evolutionOutcome(eo).build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.55, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "", null); // Δ=-0.35
        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);
        // catastrophic → scale=1.0, shift=0.0; confidence=0.95 (score<0.65) → override
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.confidence()).isEqualTo(0.95);
    }

    @Test
    void testStableScoreYieldAccepted() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withLastScore(0.8);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("src/Foo.java", "changed"), Set.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.81,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Minor improvements",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, outcome);

        assertThat(opinion.confidence()).isEqualTo(0.0);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
    }

    // ── T2-CC: verify-in-place bypass ────────────────────────────────────────

    @Test
    void verifyInPlace_suppressesRegressionHeuristic_evenOnCatastrophicDrop() {
        // When evolutionVerifyInPlace=true the LLM scores 0.0 because the diff is empty.
        // The heuristic must not fire — the zero score is an artefact of the mode, not a regression.
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.80)
                .evolutionVerifyInPlace(true)
                .build();
        EvaluationOutcome outcome = new EvaluationOutcome(0.0, MutationAcceptanceState.NOT_EVALUATED,
                GoalProgressState.NOT_EVALUATED, "No changes detected", null);

        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);

        assertThat(opinion.confidence()).isEqualTo(0.0);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
    }
}
