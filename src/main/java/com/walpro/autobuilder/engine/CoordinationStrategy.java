package com.walpro.autobuilder.engine;

import java.util.List;

import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface CoordinationStrategy {
    List<ExecutionAgent> determineExecutionOrder(List<ExecutionAgent> agents, CycleContextData context);
}
