package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;

public record HeuristicOpinion(
        String name,
        double confidence,
        ConfidenceLevel level,
        MutationAcceptanceState mutationState,
        GoalProgressState goalProgress,
        String rationale,
        Double suggestedScore
) {
    public static HeuristicOpinion of(
            String name,
            double confidence,
            MutationAcceptanceState state,
            GoalProgressState progress,
            String rationale
    ) {
        return new HeuristicOpinion(
                name,
                confidence,
                ConfidenceLevel.fromScore(confidence),
                state,
                progress,
                rationale,
                null
        );
    }

    public static HeuristicOpinion ofWithScore(
            String name,
            double confidence,
            MutationAcceptanceState state,
            GoalProgressState progress,
            String rationale,
            double suggestedScore
    ) {
        return new HeuristicOpinion(
                name,
                confidence,
                ConfidenceLevel.fromScore(confidence),
                state,
                progress,
                rationale,
                suggestedScore
        );
    }
    
    public static HeuristicOpinion none(String name) {
        return new HeuristicOpinion(
                name,
                0.0,
                ConfidenceLevel.NONE,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.NOT_EVALUATED,
                "No concerns identified.",
                null
        );
    }
}
