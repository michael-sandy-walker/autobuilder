package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.springframework.stereotype.Component;

@Component
public class NoMeaningfulChangeHeuristic implements EvaluationHeuristic {

    @Override
    public HeuristicOpinion evaluate(EvaluationInput input, EvaluationOutcome outcome) {
        boolean noChange = input.updatedFiles().isEmpty() && input.deletedFiles().isEmpty();
        double score = outcome.score();

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
                "NoMeaningfulChangeHeuristic",
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }
}
