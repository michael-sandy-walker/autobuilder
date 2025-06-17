package com.walpro.autobuilder.evaluation.dynamic;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.model.EvaluationInput;
import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;

@Component
public class DynamicEvaluationEngine {

    private final DynamicEvaluatorRegistry registry;

    public DynamicEvaluationEngine(DynamicEvaluatorRegistry registry) {
        this.registry = registry;
    }

    public List<EvaluationOutcome> evaluateAll(EvaluationInput input) {
        List<EvaluationOutcome> outcomes = new ArrayList<>();
        for (DynamicEvaluator evaluator : registry.getActiveEvaluators()) {
            EvaluationOutcome outcome = evaluator.evaluate(input);
            outcomes.add(outcome);
        }
        return outcomes;
    }
}
