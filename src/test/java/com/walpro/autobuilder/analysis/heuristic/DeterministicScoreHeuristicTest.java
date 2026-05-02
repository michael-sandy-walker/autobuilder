package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class DeterministicScoreHeuristicTest {

    private final DeterministicScoreHeuristic heuristic = new DeterministicScoreHeuristic();

    private static final EvaluationOutcome DUMMY_OUTCOME = new EvaluationOutcome(
            0.7, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "dummy", null
    );

    private CycleContextData contextWith(ScoringResult scoring) {
        return CycleContextData.builder().scoringResult(scoring).build();
    }

    private ScoringResult scoring(Double global, Double lastGlobal, Map<String, Double> perFile) {
        return new ScoringResult(global, lastGlobal, null, null, perFile, List.of(), Map.of(), Map.of(), "goal-1");
    }

    // --- none() cases ---

    @Test
    void returnsNone_whenInputIsNull() {
        HeuristicOpinion opinion = heuristic.evaluate(null, DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isEqualTo(0.0);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void returnsNone_whenScoringResultIsNull() {
        HeuristicOpinion opinion = heuristic.evaluate(CycleContextData.builder().build(), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isEqualTo(0.0);
    }

    @Test
    void returnsNone_whenGlobalScoreIsNull() {
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(null, null, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isEqualTo(0.0);
    }

    // --- mutationState derivation ---

    @Test
    void accepted_whenGlobalScoreAtThreshold() {
        // 0.75 is the exact acceptance boundary
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.75, 0.75, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void accepted_whenGlobalScoreAboveThreshold() {
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.90, 0.90, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void rejected_whenGlobalScoreBelowThreshold() {
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.74, 0.74, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void mutationState_isIndependentOfLlmResult() {
        // LLM said ACCEPTED but score is below 0.75 — heuristic overrides
        EvaluationOutcome llmAccepted = new EvaluationOutcome(
                0.6, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "llm", null);
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.60, 0.60, Map.of())), llmAccepted);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    // --- Abstain when LLM REJECTED below soft-band floor ---

    @Test
    void abstains_whenLlmRejectedWithScoreBelowSoftBandFloor() {
        // LLM scored 0.52 REJECTED (clear evidence of failure) — heuristic must not override with globalScore
        EvaluationOutcome llmRejected = new EvaluationOutcome(
                0.52, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "criterion unmet", null);
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.78, 0.78, Map.of())), llmRejected);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void doesNotAbstain_whenLlmRejectedButScoreAboveSoftBandFloor() {
        // LLM scored 0.72 REJECTED — in the ambiguous band, heuristic still votes
        EvaluationOutcome llmRejected = new EvaluationOutcome(
                0.72, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "borderline", null);
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.78, 0.78, Map.of())), llmRejected);
        assertThat(opinion.level()).isNotEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void doesNotAbstain_whenLlmAcceptedWithLowScore() {
        // LLM said ACCEPTED even at 0.52 — heuristic still checks globalScore normally
        EvaluationOutcome llmAccepted = new EvaluationOutcome(
                0.52, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "optimistic llm", null);
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.78, 0.78, Map.of())), llmAccepted);
        assertThat(opinion.level()).isNotEqualTo(ConfidenceLevel.NONE);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    // --- Confidence: no prior score ---

    @Test
    void lowConfidence_whenNoPriorScore() {
        // temporal = 0.5 (neutral), confidence = 0.5 * 0.7 = 0.35
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.7, null, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(0.35, offset(1e-9));
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void noPriorScore_spatialIgnored() {
        // Even with identical per-file scores, spatial is not used when lastGlobal is null
        HeuristicOpinion opinion = heuristic.evaluate(
                contextWith(scoring(0.7, null, Map.of("A.java", 0.70, "B.java", 0.70))), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(0.35, offset(1e-9));
    }

    // --- Confidence: temporal consistency ---

    @Test
    void criticalConfidence_whenScoreFullyStable() {
        // delta = 0 → temporal = 1/(1+0) = 1.0, spatial = 1.0 (< 2 files), geom = 1.0
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.8, 0.8, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(1.0, offset(1e-9));
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
    }

    @Test
    void mediumConfidence_whenDeltaEqualsK() {
        // delta = 0.05 = K → temporal = 1/(1+1) = 0.5, spatial = 1.0, geom = sqrt(0.5) ≈ 0.707
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.7, 0.65, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(Math.sqrt(0.5), offset(1e-9));
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void lowerConfidence_whenDeltaIsLarge() {
        // delta = 0.3, temporal = 1/(1+6) = 1/7 ≈ 0.1429, spatial = 1.0, geom ≈ 0.378
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.7, 0.4, Map.of())), DUMMY_OUTCOME);
        double expected = Math.sqrt(1.0 / 7.0);
        assertThat(opinion.confidence()).isCloseTo(expected, offset(1e-9));
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.LOW);
    }

    // --- Confidence: spatial consistency ---

    @Test
    void reducedConfidence_whenPerFileScoresDiverge() {
        // globalScore=0.7, lastGlobal=0.7 (temporal=1.0), perFile: mean=0.6, stddev=0.3, cv=0.5
        // spatial = 1/(1+0.5) = 0.667, geom (raw) = sqrt(1.0 * 0.667) ≈ 0.816.
        // Score 0.70 is in the soft-veto band [0.70,0.75); no projectDiff → no meaningful change
        // → REJECTED in band, confidence capped at 0.75 (HIGH).
        HeuristicOpinion opinion = heuristic.evaluate(
                contextWith(scoring(0.7, 0.7, Map.of("A.java", 0.3, "B.java", 0.9))), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(0.75, offset(1e-9));
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void criticalConfidence_whenAllFilesConverge() {
        // delta=0 → temporal=1.0; all files same → cv=0 → spatial=1.0; geom=1.0 (raw).
        // Score 0.70 is in the soft-veto band [0.70,0.75); no projectDiff → no meaningful change
        // → REJECTED in band, confidence capped at 0.75 (HIGH), not CRITICAL.
        HeuristicOpinion opinion = heuristic.evaluate(
                contextWith(scoring(0.7, 0.7, Map.of("A.java", 0.70, "B.java", 0.70, "C.java", 0.70))),
                DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(0.75, offset(1e-9));
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void spatialNeutral_whenFewerThanTwoFiles() {
        // 1 file → spatial stays 1.0, geom = sqrt(temporal)
        // delta=0.05 → temporal=0.5, spatial=1.0, geom=sqrt(0.5)
        HeuristicOpinion withOneFile = heuristic.evaluate(
                contextWith(scoring(0.7, 0.65, Map.of("A.java", 0.7))), DUMMY_OUTCOME);
        HeuristicOpinion withNoFiles = heuristic.evaluate(
                contextWith(scoring(0.7, 0.65, Map.of())), DUMMY_OUTCOME);
        assertThat(withOneFile.confidence()).isCloseTo(withNoFiles.confidence(), offset(1e-9));
    }

    // --- Combined axes ---

    @Test
    void confidence_combinesTemporalAndSpatialAsGeometricMean() {
        // delta = 0.05 → temporal = 0.5; cv = 0.5 → spatial = 0.667
        // geom = sqrt(0.5 * 0.667) ≈ 0.577
        HeuristicOpinion opinion = heuristic.evaluate(
                contextWith(scoring(0.7, 0.65, Map.of("A.java", 0.3, "B.java", 0.9))), DUMMY_OUTCOME);
        double temporal = 0.5;
        double spatial = 1.0 / (1.0 + 0.3 / 0.6);
        double expected = Math.sqrt(temporal * spatial);
        assertThat(opinion.confidence()).isCloseTo(expected, offset(1e-9));
    }

    // --- No artificial cap ---

    @Test
    void confidenceCanReachOne_whenFullyConsistent() {
        // Perfect stability + perfect file agreement → confidence = 1.0
        HeuristicOpinion opinion = heuristic.evaluate(
                contextWith(scoring(0.8, 0.8, Map.of("A.java", 0.8, "B.java", 0.8))), DUMMY_OUTCOME);
        assertThat(opinion.confidence()).isCloseTo(1.0, offset(1e-9));
    }

    // --- Suggested score passthrough ---

    @Test
    void suggestedScoreMatchesGlobalScore() {
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.82, null, Map.of())), DUMMY_OUTCOME);
        assertThat(opinion.suggestedScore()).isEqualTo(0.82);
    }

    // --- Goal progress passthrough ---

    @Test
    void goalProgressEchoedFromOriginal() {
        EvaluationOutcome outcome = new EvaluationOutcome(
                0.7, MutationAcceptanceState.ACCEPTED, GoalProgressState.FULFILLED, "test", null);
        HeuristicOpinion opinion = heuristic.evaluate(contextWith(scoring(0.8, 0.8, Map.of())), outcome);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.FULFILLED);
    }
}
