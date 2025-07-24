package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface PlannerAgent {
    PlannerOutcome plan(CycleContextData input);
    void evolveGraph(CycleContextData input, PlannerOutcome outcome);
}
