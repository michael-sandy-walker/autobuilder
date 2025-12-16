package com.walpro.autobuilder.agent.arbitration;

import com.walpro.autobuilder.agent.plan.Action;
import com.walpro.autobuilder.agent.plan.ActionScope;
import com.walpro.autobuilder.agent.plan.Plan;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultPlanArbiter implements PlanArbiter {

    private final double maxRisk;

    public DefaultPlanArbiter(double maxRisk) {
        this.maxRisk = maxRisk;
    }

    @Override
    public ArbitrationDecision decide(Plan plan, CycleContextData context) {
        if (plan == null) {
            return new ArbitrationDecision(DecisionType.REJECT, "Plan is null.");
        }
        if (plan.actions() == null || plan.actions().isEmpty()) {
            return new ArbitrationDecision(DecisionType.ACCEPT, "No actions to arbitrate.");
        }

        // 1) Reject if any action exceeds risk threshold
        Action riskiest = plan.actions().stream()
                .max((a, b) -> Double.compare(a.risk(), b.risk()))
                .orElse(null);

        if (riskiest != null && riskiest.risk() > maxRisk) {
            return new ArbitrationDecision(
                    DecisionType.REJECT,
                    "Risk too high: " + riskiest.risk() + " > " + maxRisk + " for scope " + riskiest.scope()
            );
        }

        // 2) Reject if plan has duplicate scopes internally
        Set<ActionScope> seen = new HashSet<>();
        Set<ActionScope> duplicates = plan.actions().stream()
                .map(Action::scope)
                .filter(s -> !seen.add(s))
                .collect(Collectors.toSet());

        if (!duplicates.isEmpty()) {
            return new ArbitrationDecision(
                    DecisionType.REJECT,
                    "Plan contains duplicate scopes: " + duplicates
            );
        }

        // 3) Reject if conflicts with already reserved scopes in this cycle
        Set<ActionScope> reserved = context.reservedScopes();
        Set<ActionScope> conflicts = plan.actions().stream()
                .map(Action::scope)
                .filter(reserved::contains)
                .collect(Collectors.toSet());

        if (!conflicts.isEmpty()) {
            return new ArbitrationDecision(
                    DecisionType.DEFER,
                    "Scope conflict with already reserved scopes this cycle: " + conflicts
            );
        }

        return new ArbitrationDecision(DecisionType.ACCEPT, "Accepted by minimal arbiter.");
    }
}
