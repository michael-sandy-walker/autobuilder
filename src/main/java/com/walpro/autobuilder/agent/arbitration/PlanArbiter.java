package com.walpro.autobuilder.agent.arbitration;

import com.walpro.autobuilder.agent.plan.Plan;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface PlanArbiter {

    ArbitrationDecision decide(Plan plan, CycleContextData context);
}
