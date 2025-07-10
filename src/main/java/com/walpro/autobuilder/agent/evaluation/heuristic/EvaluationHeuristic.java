package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

public interface EvaluationHeuristic {
//    EvaluationOutcome apply(EvaluationInput input, EvaluationOutcome outcome);
    /**
     * Returns a structured opinion about the mutation.
     */
    HeuristicOpinion evaluate(EvaluationInput input, EvaluationOutcome original);
}
