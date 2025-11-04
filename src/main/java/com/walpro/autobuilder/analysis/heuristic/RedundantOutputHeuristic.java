package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class RedundantOutputHeuristic implements EvaluationHeuristic {

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        long count = input.findings().stream()
                .filter(f -> f.type().equals("redundant-output"))
                .count();

        if (count == 0) return HeuristicOpinion.none("RedundantOutputHeuristic");

        double confidence = count >= 2 ? 0.75 : 0.5;
        String rationale = "Detected %d redundant output file(s) from last cycle".formatted(count);
        boolean override = false; // never override now

        return HeuristicOpinion.ofWithScore(
                "RedundantOutputHeuristic",
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                override ? 0.4 : outcome.score()
        );
    }
}
