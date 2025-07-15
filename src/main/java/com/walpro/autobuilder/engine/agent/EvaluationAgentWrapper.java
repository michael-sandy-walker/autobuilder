package com.walpro.autobuilder.engine.agent;

import com.walpro.autobuilder.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.engine.ExecutionAgent;
import com.walpro.autobuilder.model.CycleContextData;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class EvaluationAgentWrapper implements ExecutionAgent {

    private final EvaluationCoordinator delegate;

    @Override
    public boolean canExecute(CycleContextData context) {
        return context.evolutionOutcome() != null;
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        return delegate.evaluate(context);
    }

    @Override
    public String name() {
        return "EvaluationAgent";
    }
}
