package com.walpro.autobuilder.core.engine;

import java.util.ArrayList;
import java.util.List;

import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@Component
@RequiredArgsConstructor
public class ExecutionEligibilityExplainer {

    private final FileVariantCollector fileVariantCollector;

    private final OrchestrationSnapshotService orchestrationSnapshotService;

    public List<String> explainBlockers(String role, CycleContextData ctx) {
        List<String> blockers = new ArrayList<>();
        if (ctx == null) {
            blockers.add("context is null");
            return blockers;
        }

        OrchestrationSnapshot snapshot = safeFetchSnapshot(ctx);

        // Role-signal expectations
        switch (role == null ? "" : role.toLowerCase()) {
            case "planner" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_PLANNER, 0.10)) {
                    blockers.add("missing strong ROLE_PLANNER (>=0.10)");
                }
                boolean hasGoal = ctx.currentGoalId() != null && !ctx.currentGoalId().isBlank();
                boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();
                boolean noGoalState = /*!hasGoal &&*/ !hasSubGoal;
                boolean stagnating = ctx.hasSignal(BasicSignals.STAGNATION);

                if (!noGoalState && !stagnating) {
                    blockers.add("neither noGoalState nor STAGNATION present");
                }
            }
            case "evolution" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVOLUTION, 0.10)) {
                    blockers.add("missing strong ROLE_EVOLUTION (>=0.10)");
                    break;
                }

                if (isAcceptedNotMergedAttempt(snapshot)) {
                    blockers.add("current attempt already ACCEPTED; merge pending");
                }
            }
            case "evaluation" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVALUATION, 0.10)) {
                    blockers.add("missing strong ROLE_EVALUATION (>=0.10)");
                }

                if (ctx.projectDiff() == null) {
                    blockers.add("missing project diff");
                }

                if (hasEvaluationForAttempt(snapshot)) {
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

    private boolean isAcceptedNotMergedAttempt(OrchestrationSnapshot snapshot) {
        if (snapshot == null) return false;

        try {
            boolean accepted = snapshot.lastEvaluation() != null
                    && "ACCEPTED".equalsIgnoreCase(snapshot.lastEvaluation().status());

            boolean merged = (snapshot.lastMerge() != null)
                    || Boolean.TRUE.equals(snapshot.mergedViaGraph());

            return accepted && !merged;
        } catch (Exception ignored) {
            // fail open: do not block if graph lookup fails
            return false;
        }
    }

    private OrchestrationSnapshot safeFetchSnapshot(CycleContextData ctx) {
        if (ctx == null) return null;

        String attemptId = ctx.lastAttemptId();
        if (attemptId == null || attemptId.isBlank()) {
            return null;
        }
        try {
            return orchestrationSnapshotService.fetchSnapshot(
                    ctx.currentGoalId(),
                    ctx.currentSubGoalId(),
                    attemptId,
                    10
            );
        } catch (Exception ignored) {
            // fail open: no blockers derived from graph
            return null;
        }
    }

    private boolean hasEvaluationForAttempt(OrchestrationSnapshot snap) {
        if (snap == null) return false;
        return snap.lastEvaluation() != null;
    }
}
