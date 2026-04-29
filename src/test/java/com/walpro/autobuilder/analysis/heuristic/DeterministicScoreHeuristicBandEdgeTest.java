package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemEntry;
import com.walpro.autobuilder.analysis.problem.ProblemSeverity;
import com.walpro.autobuilder.analysis.problem.ProblemStatus;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicScoreHeuristicBandEdgeTest {

    private final DeterministicScoreHeuristic heuristic = new DeterministicScoreHeuristic();

    private static final EvaluationOutcome DUMMY_LLM_ACCEPTED = new EvaluationOutcome(
            0.72, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "llm", null);

    // Stable scoring: delta=0 → confidence=1.0 (CRITICAL) — so we can verify caps
    private ScoringResult stableScoring(double score) {
        return new ScoringResult(score, score, null, null, Map.of(), List.of(), Map.of(), Map.of(), "g");
    }

    private ProjectDiffResult meaningfulDiff() {
        return new ProjectDiffResult(null, Map.of("LoanService.java", "new content"), Set.of());
    }

    private ProjectDiffResult noDiff() {
        return new ProjectDiffResult(null, Map.of(), Set.of());
    }

    private ProblemContext noBlockers() {
        return ProblemContext.empty();
    }

    private ProblemContext withBlocker(String id) {
        ProblemEntry blocker = new ProblemEntry(
                id, ProblemStatus.OPEN, ProblemSeverity.BLOCKER,
                "Compile error", "details", Set.of(),
                null, null, "c0", "c0", null, null, List.of(), List.of());
        return new ProblemContext(List.of(blocker), List.of());
    }

    private CycleContextData ctx(double score, ProjectDiffResult diff,
                                  ProblemContext current, ProblemContext previous,
                                  Double lastAcceptedSubgoalScore) {
        return CycleContextData.builder()
                .scoringResult(stableScoring(score))
                .projectDiff(diff)
                .problemContext(current)
                .previousProblemContext(previous)
                .lastAcceptedSubgoalScore(lastAcceptedSubgoalScore)
                .build();
    }

    // ---- (a) Band-edge acceptance: all conditions met ----

    @Test
    void bandEdge_accepted_whenMeaningfulDiffNoRegression() {
        // score=0.72, meaningful diff, no blocker regression, no lastAccepted
        CycleContextData input = ctx(0.72, meaningfulDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.MEDIUM);
        // confidence capped at 0.65 even though raw would be 1.0 (stable scoring)
        assertThat(opinion.confidence()).isLessThanOrEqualTo(0.65);
    }

    @Test
    void bandEdge_accepted_withLastAcceptedButNoRegression() {
        // lastAccepted=0.71, score=0.72 — no regression (0.72 >= 0.71 - 0.02 = 0.69)
        CycleContextData input = ctx(0.72, meaningfulDiff(), noBlockers(), noBlockers(), 0.71);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    // ---- (b) Band-edge rejection: condition fails ----

    @Test
    void bandEdge_rejected_whenNoMeaningfulDiff() {
        CycleContextData input = ctx(0.72, noDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        // Capped at HIGH (0.75), not CRITICAL (0.90)
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(opinion.confidence()).isLessThanOrEqualTo(0.75);
    }

    @Test
    void bandEdge_rejected_whenBlockerRegressionOccurs() {
        // previous: 0 blockers; current: 1 blocker → regression
        CycleContextData input = ctx(0.72, meaningfulDiff(), withBlocker("err-1"), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(opinion.rationale()).contains("blocker count increased");
    }

    @Test
    void bandEdge_rejected_whenScoreRegressesBeyondMargin() {
        // lastAccepted=0.78 → effectiveLowerBand=max(0.70, 0.73)=0.73.
        // score=0.74 is in band [0.73,0.75). Condition (c): 0.74 >= 0.78-0.02=0.76 → false → REJECTED HIGH.
        CycleContextData input = ctx(0.74, meaningfulDiff(), noBlockers(), noBlockers(), 0.78);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(opinion.rationale()).contains("score regressed");
    }

    // ---- (c) Below 0.70: REJECTED at CRITICAL (unchanged behaviour) ----

    @Test
    void belowSoftBand_rejected_atCritical() {
        CycleContextData input = ctx(0.69, meaningfulDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
    }

    // ---- (d) At or above 0.75: ACCEPTED at CRITICAL (unchanged behaviour) ----

    @Test
    void aboveThreshold_accepted_atCritical() {
        CycleContextData input = ctx(0.78, meaningfulDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.confidence()).isGreaterThan(0.75);
    }

    @Test
    void atThreshold_accepted() {
        CycleContextData input = ctx(0.75, meaningfulDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    // ---- Secondary fix: per-subgoal dynamic lower band ----

    @Test
    void dynamicFloor_raisedWhenLastAcceptedIsHigh() {
        // lastAccepted=0.80, effectiveLowerBand=max(0.70, 0.75)=0.75
        // score=0.72 < effectiveLowerBand=0.75 → below band → REJECTED CRITICAL
        CycleContextData input = ctx(0.72, meaningfulDiff(), noBlockers(), noBlockers(), 0.80);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
    }

    @Test
    void dynamicFloor_notRaisedBelowAbsoluteMinimum() {
        // lastAccepted=0.71, effectiveLowerBand=max(0.70, 0.66)=0.70
        // score=0.71 is in band [0.70, 0.75) → soft veto
        CycleContextData input = ctx(0.71, meaningfulDiff(), noBlockers(), noBlockers(), 0.71);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        // 0.71 >= 0.71 - 0.02 = 0.69 → condition (c) passes
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    // ---- Soft-band caps do not bleed into neighbouring bands ----

    @Test
    void rejectedBand_confidence_notExceedsHighThreshold() {
        // stable scoring → raw=1.0 → must be capped to 0.75
        CycleContextData input = ctx(0.72, noDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.confidence()).isLessThanOrEqualTo(0.75);
        assertThat(opinion.level()).isNotEqualTo(ConfidenceLevel.CRITICAL);
    }

    @Test
    void acceptedBand_confidence_notExceedsMediumCap() {
        // stable scoring → raw=1.0 → must be capped to 0.65
        CycleContextData input = ctx(0.72, meaningfulDiff(), noBlockers(), noBlockers(), null);

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        assertThat(opinion.confidence()).isLessThanOrEqualTo(0.65);
    }

    // ---- Null-safety: missing diff or problem context ----

    @Test
    void nullDiff_treatedAsNoMeaningfulChange_inBand() {
        CycleContextData input = CycleContextData.builder()
                .scoringResult(stableScoring(0.72))
                .projectDiff(null)
                .problemContext(noBlockers())
                .previousProblemContext(noBlockers())
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        // null diff → hasMeaningfulDiff=false → REJECTED HIGH
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void nullProblemContext_assumesNoRegression() {
        CycleContextData input = CycleContextData.builder()
                .scoringResult(stableScoring(0.72))
                .projectDiff(meaningfulDiff())
                .problemContext(null)
                .previousProblemContext(null)
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(input, DUMMY_LLM_ACCEPTED);

        // null problem context → noProblemRegression=true → can still accept
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }
}
