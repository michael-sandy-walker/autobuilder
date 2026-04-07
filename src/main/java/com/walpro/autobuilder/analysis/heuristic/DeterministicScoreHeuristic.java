package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Component
public class DeterministicScoreHeuristic implements EvaluationHeuristic {

    private static final String NAME = "DeterministicScoreHeuristic";
    private static final double ACCEPTANCE_THRESHOLD = 0.75;
    private static final double K = 0.05;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null) return HeuristicOpinion.none(NAME);

        ScoringResult scoring = input.scoringResult();
        if (scoring == null || scoring.globalScore() == null) return HeuristicOpinion.none(NAME);

        double s = scoring.globalScore();
        MutationAcceptanceState state = s >= ACCEPTANCE_THRESHOLD
                ? MutationAcceptanceState.ACCEPTED
                : MutationAcceptanceState.REJECTED;
        double confidence = computeConfidence(scoring);

        String rationale = "Deterministic score from ScoringService.globalScore=%.3f confidence=%.2f".formatted(s, confidence);

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                state,
                original.goalProgress(),
                rationale,
                s
        );
    }

    private double computeConfidence(ScoringResult scoring) {
        Double lastGlobal = scoring.lastGlobalScore();
        Map<String, Double> perFileScores = scoring.perFileScores();

        double temporalFactor;
        if (lastGlobal == null) {
            temporalFactor = 0.5; // neutral: no prior cycle to compare
        } else {
            double delta = Math.abs(scoring.globalScore() - lastGlobal);
            temporalFactor = 1.0 / (1.0 + delta / K);
        }

        if (lastGlobal == null) {
            return temporalFactor * 0.7;
        }

        double spatialFactor = 1.0; // neutral when < 2 files
        if (perFileScores != null && perFileScores.size() >= 2) {
            double cv = coefficientOfVariation(perFileScores.values());
            spatialFactor = 1.0 / (1.0 + cv);
        }

        return Math.sqrt(temporalFactor * spatialFactor);
    }

    private double coefficientOfVariation(Collection<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean == 0.0) return 0.0;
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
        return Math.sqrt(variance) / mean;
    }
}
