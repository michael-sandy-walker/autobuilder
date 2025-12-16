package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;

import java.util.Set;

public interface ExecutionAgent {
    boolean canExecute(CycleContextData context);
    CycleContextData execute(CycleContextData context);
    String name();
    String role();
    public AgentCoordinator coordinator();
    Set<SignalType> getSupportedSignals();
}