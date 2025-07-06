package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

public interface EvaluationAgent {

    EvaluationOutcome evaluate(EvaluationInput input);

    void evolveGraph(EvaluationInput input, EvaluationOutcome outcome);
}
