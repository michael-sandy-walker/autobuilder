package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class NoMeaningfulChangeHeuristic implements EvaluationHeuristic {

    private static final String NAME = "NoMeaningfulChangeHeuristic";

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);
        if (input.projectDiff() == null) return HeuristicOpinion.none(NAME);

        boolean noChange = input.projectDiff().changedFiles().isEmpty() && input.projectDiff().deletedPaths().isEmpty();

        Double scoreObj = outcome.score();
        if (scoreObj == null) {
            // No numeric score => cannot judge "hallucinated improvement".
            return HeuristicOpinion.none(NAME);
        }
        double score = scoreObj;

        double confidence;
        String rationale;

        if (noChange && score >= 0.99) {
            confidence = 0.95;
            rationale = "Score of %.2f without any changes — likely hallucinated improvement".formatted(score);
        } else if (noChange && score >= 0.95) {
            confidence = 0.75;
            rationale = "High score (%.2f) with no actual file changes".formatted(score);
        } else if (noChange && score >= 0.9) {
            confidence = 0.45;
            rationale = "Moderate suspicion due to high score with no content change";
        } else if (noChange) {
            confidence = 0.2;
            rationale = "Some doubt due to empty file delta";
        } else {
            confidence = 0.0;
            rationale = "Files changed — no issue with score.";
        }

        boolean override = confidence >= 0.75;
        double suggestedScore = override ? 0.4 : score;

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
