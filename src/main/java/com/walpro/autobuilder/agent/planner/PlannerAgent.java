package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerInput;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;

public interface PlannerAgent {
    PlannerOutcome plan(PlannerInput input);
    void evolveGraph(PlannerInput input, PlannerOutcome outcome);
}
