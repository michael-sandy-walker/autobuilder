package com.walpro.autobuilder.analysis.scope;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface SubgoalScopeGuard {
    CycleContextData guard(CycleContextData ctx);
}