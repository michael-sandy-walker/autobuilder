package com.walpro.autobuilder.engine;

import com.walpro.autobuilder.model.CycleContextData;
import java.util.List;

public interface CoordinationStrategy {
    List<ExecutionAgent> determineExecutionOrder(List<ExecutionAgent> agents, CycleContextData context);
}
