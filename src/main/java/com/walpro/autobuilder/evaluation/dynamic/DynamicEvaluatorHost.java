package com.walpro.autobuilder.evaluation.dynamic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.EvaluationInput;

public class DynamicEvaluatorHost implements DynamicEvaluator {

    private final String id;
    private final String role;
    private boolean active = true;

    // Flexible strategy: could be a lambda or pluggable class
    private final EvaluationFunction evaluationLogic;

    // Memory/state scoped to this evaluator
    private final Map<String, Object> memory = new ConcurrentHashMap<>();

    public interface EvaluationFunction {
        EvaluationOutcome evaluate(EvaluationInput input, Map<String, Object> memory);
    }

    public DynamicEvaluatorHost(String id, String role, EvaluationFunction logic) {
        this.id = id;
        this.role = role;
        this.evaluationLogic = logic;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
    }

    @Override
    public EvaluationOutcome evaluate(EvaluationInput input) {
        return evaluationLogic.evaluate(input, memory);
    }

    public Map<String, Object> memory() {
        return memory;
    }
}
