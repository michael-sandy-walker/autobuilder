package com.walpro.autobuilder.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@Component
@RequiredArgsConstructor
public class ExecutionEligibilityExplainer {

    private final FileVariantCollector fileVariantCollector;

    public List<String> explainBlockers(String role, CycleContextData ctx) {
        List<String> blockers = new ArrayList<>();
        if (ctx == null) {
            blockers.add("context is null");
            return blockers;
        }

        // Role-signal expectations
        switch (role == null ? "" : role.toLowerCase()) {
            case "planner" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_PLANNER, 0.10)) {
                    blockers.add("missing strong ROLE_PLANNER (>=0.10)");
                }
                boolean hasGoal = ctx.currentGoalId() != null && !ctx.currentGoalId().isBlank();
                boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();
                boolean noGoalState = !hasGoal && !hasSubGoal;
                boolean stagnating = ctx.hasSignal(BasicSignals.STAGNATION);

                if (!noGoalState && !stagnating) {
                    blockers.add("neither noGoalState nor STAGNATION present");
                }
            }
            case "evolution" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVOLUTION, 0.10)) {
                    blockers.add("missing strong ROLE_EVOLUTION (>=0.10)");
                }
            }
            case "evaluation" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVALUATION, 0.10)) {
                    blockers.add("missing strong ROLE_EVALUATION (>=0.10)");
                } else if (ctx.projectDiff() == null) {
                    blockers.add("missing project diff");
                } else if (Objects.equals(ctx.lastEvolutionFingerprint(), ctx.lastEvaluatedEvolutionFingerprint())) {
                    blockers.add("already evaluated");
                }
            }
            case "merge" -> {
                if (!ctx.hasSignal(BasicSignals.ROLE_MERGE)) {
                    blockers.add("missing ROLE_MERGE");
                } else {
                    boolean hasAnyVariant = false;
                    try {
                        hasAnyVariant = fileVariantCollector.hasAnyVariant(ctx);
                    } catch (Exception ignored) {}
                    if (!hasAnyVariant) {
                        blockers.add("missing variant");
                    }
                }
            }
            default -> blockers.add("unknown role gating");
        }

        return blockers;
    }
}
