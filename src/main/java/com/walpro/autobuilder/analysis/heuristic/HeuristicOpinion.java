package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

public record HeuristicOpinion(
        String name,
        double confidence,
        ConfidenceLevel level,
        MutationAcceptanceState mutationState,
        GoalProgressState goalProgress,
        String rationale,
        Double suggestedScore,
        boolean defectFinding
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
                null,
                false
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
                suggestedScore,
                false
        );
    }

    /**
     * An opinion grounded in a deterministic structural defect of the mutation itself (parallel
     * store, manufactured dependency, ...) — as opposed to score statistics or procedural
     * deferrals. Defect findings take precedence in the negotiator's CRITICAL tiebreak: run 14
     * (2026-07-04) showed the deterministic score echo crossing the CRITICAL threshold on
     * confidence alone (1.0) and outranking a correct defect rejection (0.9) twice, merging
     * runtime-fatal mutations.
     */
    public static HeuristicOpinion ofDefectWithScore(
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
                suggestedScore,
                true
        );
    }

    public static HeuristicOpinion none(String name) {
        return new HeuristicOpinion(
                name,
                0.0,
                ConfidenceLevel.NONE,
                MutationAcceptanceState.NOT_EVALUATED,
                GoalProgressState.NOT_EVALUATED,
                "No concerns identified.",
                null,
                false
        );
    }
}
