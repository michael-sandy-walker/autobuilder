package com.walpro.autobuilder.evaluation.dynamic;

import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.EvaluationInput;

public class EvaluatingDynamicHost implements DynamicEvaluator {

    private final DynamicEvaluatorDefinition definition;
    private final DynamicLogicInterpreter interpreter;
    private boolean active;

    public EvaluatingDynamicHost(DynamicEvaluatorDefinition definition, DynamicLogicInterpreter interpreter) {
        this.definition = definition;
        this.interpreter = interpreter;
        this.active = definition.active();
    }

    @Override
    public String getId() {
        return definition.id();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        this.active = true;
    }

    @Override
    public void deactivate() {
        this.active = false;
    }

    @Override
    public EvaluationOutcome evaluate(EvaluationInput input) {
        return interpreter.evaluate(definition, input);
    }

    public String getRole() {
        return definition.role();
    }

    public String getBehaviorText() {
        return definition.behavior();
    }
}
