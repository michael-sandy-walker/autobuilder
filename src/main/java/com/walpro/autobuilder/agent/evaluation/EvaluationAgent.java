package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface EvaluationAgent {

    public EvaluationOutcome evaluate(CycleContextData input);

    void evolveGraph(CycleContextData input, EvaluationOutcome outcome);
}
