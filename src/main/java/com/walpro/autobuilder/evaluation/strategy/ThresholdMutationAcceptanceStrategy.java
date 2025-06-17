package com.walpro.autobuilder.evaluation.strategy;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.config.EvaluationConfig;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import com.walpro.autobuilder.heuristic.HeuristicFinding;

@Component
public class ThresholdMutationAcceptanceStrategy implements MutationAcceptanceStrategy {

	private final EvaluationConfig config;

    public ThresholdMutationAcceptanceStrategy(EvaluationConfig config) {
    	this.config = config;
    }

    @Override
    public MutationAcceptanceState evaluate(
        double score,
        Double lastScore,
        Set<String> updatedFiles,
        Set<String> deletedFiles,
        List<HeuristicFinding> findings
    ) {
        if (score < config.getHeuristicThreshold()) {
            return MutationAcceptanceState.REJECTED;
        }

        if (lastScore != null && score < lastScore - config.getMaxScoreDrop()) {
            return MutationAcceptanceState.REJECTED;
        }

        return MutationAcceptanceState.ACCEPTED;
    }
}
