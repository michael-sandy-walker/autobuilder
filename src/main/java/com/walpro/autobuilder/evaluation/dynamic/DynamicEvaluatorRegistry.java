package com.walpro.autobuilder.evaluation.dynamic;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class DynamicEvaluatorRegistry {

    private final Map<String, DynamicEvaluator> registry = new ConcurrentHashMap<>();

    public void register(DynamicEvaluator evaluator) {
        registry.put(evaluator.getId(), evaluator);
    }

    public void unregister(String evaluatorId) {
        registry.remove(evaluatorId);
    }

    public Collection<DynamicEvaluator> getActiveEvaluators() {
        return registry.values().stream()
                .filter(DynamicEvaluator::isActive)
                .toList();
    }

    public void activate(String id) {
        DynamicEvaluator e = registry.get(id);
        if (e != null) e.activate();
    }

    public void deactivate(String id) {
        DynamicEvaluator e = registry.get(id);
        if (e != null) e.deactivate();
    }
}
