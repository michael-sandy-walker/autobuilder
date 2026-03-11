package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class ScoreRegressionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "ScoreRegressionHeuristic";

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
        double confidence;
        String rationale;

        // Graduate confidence by both the regression magnitude AND the absolute current score.
        // A drop that lands below 0.60 is genuinely bad; a drop that stays above 0.65 is borderline
        // and should influence but not dominate the negotiator vote.
        if (delta <= -0.15) {
            confidence = score < 0.65 ? 0.95 : 0.75; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Severe score drop (Δ=%.2f, current=%.2f)".formatted(delta, score);
        } else if (delta <= -0.10) {
            confidence = score < 0.65 ? 0.75 : 0.50; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Notable score drop (Δ=%.2f, current=%.2f)".formatted(delta, score);
        } else if (delta <= -0.05) {
            confidence = 0.35; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Moderate score drop (Δ=%.2f)".formatted(delta);
        } else if (delta < 0.0) {
            confidence = 0.20; // TODO: adjust values if necessary (maybe make this more dynamic)
            rationale = "Minor regression (Δ=%.2f)".formatted(delta);
        } else {
            confidence = 0.0;
            rationale = "No regression detected (Δ=%.2f)".formatted(delta);
        }

        boolean override = confidence >= 0.75;
        double suggestedScore = score; // keep current score; the REJECTED state is the penalty TODO: check for correctness

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }
}
