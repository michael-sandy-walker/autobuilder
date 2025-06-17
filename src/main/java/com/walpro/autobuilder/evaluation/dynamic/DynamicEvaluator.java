package com.walpro.autobuilder.evaluation.dynamic;

import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.EvaluationInput;

public interface DynamicEvaluator {

    String getId(); // unique name or ID

    boolean isActive();

    void activate();

    void deactivate();

    EvaluationOutcome evaluate(EvaluationInput input);
}