package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface EvaluationAgent {

    EvaluationOutcome evaluate(CycleContextData input);

    void evolveGraph(CycleContextData input, EvaluationOutcome outcome);
}
