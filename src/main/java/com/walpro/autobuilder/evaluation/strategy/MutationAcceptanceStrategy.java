package com.walpro.autobuilder.evaluation.strategy;

import java.util.List;
import java.util.Set;

import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import com.walpro.autobuilder.heuristic.HeuristicFinding;

public interface MutationAcceptanceStrategy {
    MutationAcceptanceState evaluate(
        double score,
        Double lastScore,
        Set<String> updatedFiles,
        Set<String> deletedFiles,
        List<HeuristicFinding> findings
    );
}