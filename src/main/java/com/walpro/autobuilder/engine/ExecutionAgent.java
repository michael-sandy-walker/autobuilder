package com.walpro.autobuilder.engine;

import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface ExecutionAgent {
    boolean canExecute(CycleContextData context);
    CycleContextData execute(CycleContextData context);
    String name();
}