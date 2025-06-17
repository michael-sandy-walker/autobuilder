package com.walpro.autobuilder.evaluation.dynamic;

import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.EvaluationInput;

public interface DynamicLogicInterpreter {
    EvaluationOutcome evaluate(DynamicEvaluatorDefinition def, EvaluationInput input);
}