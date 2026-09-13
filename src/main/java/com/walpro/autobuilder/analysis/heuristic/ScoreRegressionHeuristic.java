package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class ScoreRegressionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "ScoreRegressionHeuristic";

    // T2-AJ constants
    static final double SHIFT_PER_FILE = 0.01;
    static final double MAX_TOLERANCE_SHIFT = 0.10;
    static final double SCALE_REDUCTION_PER_FILE = 0.05;
    static final double MIN_CONFIDENCE_SCALE = 0.65;
    static final double CATASTROPHIC_DROP_FLOOR = -0.30;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        // T1-EU: NOT_EVALUATED means the LLM declined to score (typically a no-diff
        // re-feed of an already-merged mutation, where the parser defaults score to 0.0).
        // Computing Δ vs the prior accepted baseline would treat that 0.0 as a catastrophic
        // regression and reject the mutation. Abstain instead.
        if (outcome.mutationState() == MutationAcceptanceState.NOT_EVALUATED) {
            return HeuristicOpinion.of(
                    NAME,
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "Evaluator returned NOT_EVALUATED — cannot detect regression"
            );
        }

        // T2-CE: use the per-subgoal accepted baseline instead of the global lastScore.
        // lastAcceptedSubgoalScore is null on the first mutation for any subgoal (no prior
        // accepted evaluation in the graph), which correctly triggers abstain below.
        if (input.lastAcceptedSubgoalScore() == null) {
            return HeuristicOpinion.of(
                    NAME,
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "No previous subgoal score — cannot detect regression"
            );
        }

        Double scoreObj = outcome.score();
        if (scoreObj == null) {
            return HeuristicOpinion.of(
                    NAME,
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "No current score — cannot detect regression"
            );
        }

        double score = scoreObj;
        double delta = score - input.lastAcceptedSubgoalScore();

        // T2-AJ: complexity-adaptive tolerance based on file count.
        // effectiveFileCount = max(currentMutationFiles, lastAcceptedMutationFiles), minimum 1.
        int currentFiles = currentMutationFileCount(input);
        int effectiveFileCount = Math.max(Math.max(currentFiles, input.lastAcceptedMutationFileCount()), 1);
        int extraFiles = effectiveFileCount - 1;

        // Catastrophic drops bypass all tolerance widening unconditionally.
        boolean catastrophic = delta <= CATASTROPHIC_DROP_FLOOR;
        double toleranceShift = catastrophic ? 0.0 : Math.min(MAX_TOLERANCE_SHIFT, extraFiles * SHIFT_PER_FILE);
        double confidenceScale = catastrophic ? 1.0 : Math.max(MIN_CONFIDENCE_SCALE, 1.0 - extraFiles * SCALE_REDUCTION_PER_FILE);

        // Shifted regression thresholds.
        double severeBound  = -(0.15 + toleranceShift);
        double notableBound = -(0.10 + toleranceShift);
        double moderateBound = -(0.05 + toleranceShift);

        double confidence;
        String rationale;

        // Graduate confidence by both the regression magnitude AND the absolute current score.
        // A drop that lands below 0.60 is genuinely bad; a drop that stays above 0.65 is borderline
        // and should influence but not dominate the negotiator vote.
        if (delta <= severeBound) {
            confidence = score < 0.65 ? 0.95 : 0.75; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Severe score drop (Δ=%.2f, current=%.2f, shift=%.2f, files=%d)".formatted(delta, score, toleranceShift, effectiveFileCount);
        } else if (delta <= notableBound) {
            confidence = score < 0.65 ? 0.75 : 0.50; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Notable score drop (Δ=%.2f, current=%.2f, shift=%.2f, files=%d)".formatted(delta, score, toleranceShift, effectiveFileCount);
        } else if (delta <= moderateBound) {
            confidence = 0.35; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Moderate score drop (Δ=%.2f, shift=%.2f, files=%d)".formatted(delta, toleranceShift, effectiveFileCount);
        } else if (delta < 0.0) {
            confidence = 0.20; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Minor regression (Δ=%.2f, files=%d)".formatted(delta, effectiveFileCount);
        } else {
            confidence = 0.0;
            rationale = "No regression detected (Δ=%.2f)".formatted(delta);
        }

        // T2-AJ: the scale is the VOTE WEIGHT's complexity adjustment — a multi-file mutation's
        // score drop is less attributable, so its opinion should carry less weight in the
        // negotiator's weighted vote.
        confidence = confidence * confidenceScale;

        // T2-FJ 2026-09-13: note that the ladder above uses HIGH's own value (0.75) as a rung while
        // this line uses it as the override line, so scaling can push a rung that MEANS "override"
        // below the line that performs it: the 0.75 rung survives only at extraFiles == 0, and 0.95
        // survives to extraFiles == 4. Measured before deciding to leave it: over 633 archived
        // firings this cost 2 decisions (0.3%), none of them on the current build, so reading the
        // UNSCALED rung here would trade a documented oddity for a real increase in rejections on
        // no evidence. Named rather than renumbered — re-entry is a measured case where a
        // multi-file mutation's severe regression should have been overridden and was not.
        boolean override = confidence >= ConfidenceLevel.HIGH.threshold();
        double suggestedScore = score; // keep current score; the REJECTED state is the penalty

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }

    private static int currentMutationFileCount(CycleContextData input) {
        EvolutionOutcome eo = input.evolutionOutcome();
        if (eo == null) return 0;
        int count = 0;
        if (eo.addedOrChangedFiles() != null) count += eo.addedOrChangedFiles().size();
        if (eo.deletedFiles() != null) count += eo.deletedFiles().size();
        return count;
    }
}
