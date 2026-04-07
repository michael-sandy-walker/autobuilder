package com.walpro.autobuilder.analysis.heuristic;

import java.util.Map;

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

        // Override when at least half the output files are redundant.
        // This scales naturally: 1/1 (100%) overrides, 1/10 (10%) does not.
        Map<String, String> prev = input.previousOutput();
        int totalFiles = (prev != null) ? prev.size() : 0;
        boolean override = totalFiles > 0 && (double) count / totalFiles >= 0.5;

        double confidence = override ? 0.75 : 0.5;
        String rationale = "Detected %d/%d redundant output file(s) from last cycle"
                .formatted(count, totalFiles);

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
