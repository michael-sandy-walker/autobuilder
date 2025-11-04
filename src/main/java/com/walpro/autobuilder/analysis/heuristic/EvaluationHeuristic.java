package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface EvaluationHeuristic {
    /**
     * Returns a structured opinion about the mutation.
     */
    HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original);
}
