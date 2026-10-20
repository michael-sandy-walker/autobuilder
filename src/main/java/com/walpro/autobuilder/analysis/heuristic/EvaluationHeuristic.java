package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface EvaluationHeuristic {
    /**
     * Returns a structured opinion about the mutation.
     */
    HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original);

    /**
     * T6-T: whether this heuristic's veto is a MEASUREMENT of the candidate -- the outcome of running it, such as tests
     * that pass without it and fail with it -- rather than an inference about it. {@link VetoGrind} never silences a
     * measured veto: one that keeps deciding means the candidates keep doing what it measured, and letting one through
     * merges exactly that (stuck-questions run 2026-10-20_10-44-59: 29 candidates vetoed for breaking a passing spec row,
     * the veto silenced, the next such candidate merged, 27 -> 26 of 29).
     */
    default boolean measured() {
        return false;
    }
}
