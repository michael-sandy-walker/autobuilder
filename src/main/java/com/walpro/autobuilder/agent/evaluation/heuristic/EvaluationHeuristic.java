package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface EvaluationHeuristic {
//    EvaluationOutcome apply(EvaluationInput input, EvaluationOutcome outcome);
    /**
     * Returns a structured opinion about the mutation.
     */
    HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original);
}
