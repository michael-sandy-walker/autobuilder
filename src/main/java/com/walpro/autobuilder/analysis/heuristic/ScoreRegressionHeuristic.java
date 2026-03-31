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

        if (input.lastScore() == null) {
            return HeuristicOpinion.of(
                    NAME,
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "No previous score — cannot detect regression"
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
        double delta = score - input.lastScore();

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

        confidence = confidence * confidenceScale;

        boolean override = confidence >= 0.75;
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
