package com.walpro.autobuilder.analysis.problem;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.List;

public interface ProblemDetector {
    ProblemContext detectAndUpdate(CycleContextData ctx, EvaluationOutcome outcome, List<HeuristicFinding> findings);
}
