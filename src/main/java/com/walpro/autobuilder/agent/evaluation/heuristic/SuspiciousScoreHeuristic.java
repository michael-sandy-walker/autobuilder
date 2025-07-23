package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SuspiciousScoreHeuristic implements EvaluationHeuristic {

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        double score = outcome.score();
        double last = input.lastScore() != null ? input.lastScore() : -1.0;
        double delta = score - last;

        boolean noEffectiveChange = input.projectDiff().changedFiles().isEmpty() && input.projectDiff().deletedPaths().isEmpty();

        double confidence;
        String rationale;

        if (score >= 0.99 && noEffectiveChange) {
            confidence = 0.95;
            rationale = "Score of %.2f without file changes — likely hallucination.".formatted(score);
        } else if (score >= 0.97 && delta < 0.005 && noEffectiveChange) {
            confidence = 0.85;
            rationale = "High score (%.2f) with minimal change (Δ=%.3f)".formatted(score, delta);
        } else if (score >= 0.95 && delta < 0.01) {
            confidence = 0.6;
            rationale = "Suspicious stagnation: score jumped to %.2f with small delta (%.3f)".formatted(score, delta);
        } else if (score >= 0.90 && delta < 0.02) {
            confidence = 0.3;
            rationale = "Minor suspicion due to high score with little change.";
        } else {
            confidence = 0.0;
            rationale = "Score looks appropriate for the mutation.";
        }

        boolean override = confidence >= 0.75;
        double suggestedScore = override ? 0.4 : score;

        return HeuristicOpinion.ofWithScore(
                "SuspiciousScoreHeuristic",
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }
}
