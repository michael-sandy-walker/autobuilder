package com.walpro.autobuilder.core.execution;

import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import lombok.RequiredArgsConstructor;

import java.util.Set;

@RequiredArgsConstructor
public final class CoordinatorExecutionAgent implements ExecutionAgent {

    private final String name;
    private final AgentCoordinator coordinator;
    private final Set<SignalType> supportedSignalTypes;

    @Override
    public String name() {
        return name;
    }

    @Override
    public String role() {
        return coordinator.role();
    }

    @Override
    public AgentCoordinator coordinator() {
        return coordinator;
    }

    @Override
    public boolean canExecute(CycleContextData context) {
        return coordinator.canExecute(context);
    }

    @Override
    public CycleContextData execute(CycleContextData ctx) {
        return coordinator.execute(ctx);
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return supportedSignalTypes;
    }

}
