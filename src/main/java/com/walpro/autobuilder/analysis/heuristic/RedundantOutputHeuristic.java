package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class RedundantOutputHeuristic implements EvaluationHeuristic {

    private static final String NAME = "RedundantOutputHeuristic";

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input.findings() == null) return HeuristicOpinion.none(NAME);

        long count = input.findings().stream()
                .filter(f -> f.type().equals("redundant-output"))
                .count();

        if (count == 0) return HeuristicOpinion.none(NAME);

        // >= 2 redundant files: HIGH confidence, override to reject (persistent stagnation)
        // == 1 redundant file: MEDIUM confidence, advisory only (may be coincidental)
        boolean override = count >= 2;
        double confidence = override ? 0.75 : 0.5;
        String rationale = "Detected %d redundant output file(s) from last cycle".formatted(count);

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                override ? 0.4 : outcome.score()
        );
    }
}
