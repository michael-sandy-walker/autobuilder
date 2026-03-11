package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

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
}
