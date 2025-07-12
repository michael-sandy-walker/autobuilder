package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

@Component
public class RedundantOutputHeuristic implements EvaluationHeuristic {

    @Override
    public HeuristicOpinion evaluate(EvaluationInput input, EvaluationOutcome outcome) {
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
