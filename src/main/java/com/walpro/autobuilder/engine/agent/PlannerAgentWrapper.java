package com.walpro.autobuilder.engine.agent;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.engine.ExecutionAgent;
import com.walpro.autobuilder.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PlannerAgentWrapper implements ExecutionAgent {

    private final PlannerAgent delegate;
    

    @Override
    public boolean canExecute(CycleContextData context) {
        return !context.subgoalPromoted(); // skip if branch already merged
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        PlannerOutcome outcome = delegate.plan(context);
        return context.withPlannerOutcome(outcome);
    }

    @Override
    public String name() {
        return "PlannerAgent";
    }

}
