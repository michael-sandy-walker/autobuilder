package com.walpro.autobuilder.evaluation.dynamic;

import java.util.List;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.EvaluationInput;

public interface DynamicEvaluationCoordinator {
    EvaluationOutcome coordinate(List<EvaluationOutcome> outcomes, EvaluationInput input);
}