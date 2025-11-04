package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface ExecutionAgent {
    boolean canExecute(CycleContextData context);
    CycleContextData execute(CycleContextData context);
    String name();
}