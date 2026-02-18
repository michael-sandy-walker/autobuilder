package com.walpro.autobuilder.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionEligibilityExplainer {

    private final FileVariantCollector fileVariantCollector;
    private final OrchestrationSnapshotService orchestrationSnapshotService;

    private final GoalPlannerService goalPlannerService;
    private final GoalKindClassifier kindClassifier;

    public List<String> explainBlockers(String role, CycleContextData ctx) {
        List<String> blockers = new ArrayList<>();
        if (ctx == null) {
            blockers.add("context is null");
            return blockers;
        }

        OrchestrationSnapshot snapshot = safeFetchSnapshot(ctx);

        // Role-signal expectations
        switch (role == null ? "" : role.toLowerCase()) {

            case "evolution" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVOLUTION, 0.10)) {
                    blockers.add("missing strong ROLE_EVOLUTION (>=0.10)");
                    return blockers;
                }

                boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();
                if (!hasSubGoal) {
                    blockers.add("missing subgoal");
                    return blockers;
                }

                if (isAcceptedNotMergedAttempt(snapshot)) {
                    blockers.add("current attempt already ACCEPTED; merge pending");
                }
                return blockers;
            }

            case "evaluation" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVALUATION, 0.10)) {
                    blockers.add("missing strong ROLE_EVALUATION (>=0.10)");
                }

                if (ctx.projectDiff() == null) {
                    blockers.add("missing project diff");
                }

                String lastEvalFp = ctx.lastEvaluatedEvolutionFingerprint();
                String nowFp = ctx.lastEvolutionFingerprint();
                boolean alreadyEvaluatedThisFingerprint = lastEvalFp != null && Objects.equals(nowFp, lastEvalFp);
                if (alreadyEvaluatedThisFingerprint) {
                    blockers.add("already evaluated current evolution fingerprint");
                }

                return blockers;
            }


            case "merge" -> {
                if (!ctx.hasSignal(BasicSignals.ROLE_MERGE)) {
                    blockers.add("missing ROLE_MERGE");
                    return blockers;
                }

                boolean hasAnyVariant = false;
                try {
                    hasAnyVariant = fileVariantCollector.hasAnyVariant(ctx);
                } catch (Exception ignored) {
                    log.warn("Ignore message: " + ignored.getMessage(), ignored);
                }

                if (!hasAnyVariant) {
                    blockers.add("missing variant");
                }

                if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
                    blockers.add("mutation currently not accepted");
                }

                return blockers;
            }


            case "analysis" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_ANALYSIS, 0.10)) {
                    blockers.add("missing strong ROLE_ANALYSIS (>=0.10)");
                    return blockers;
                }

                GoalEntity sg = (ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank())
                        ? goalPlannerService.getGoalById(ctx.currentSubGoalId())
                        : null;

                String desc = sg != null ? sg.getDescription() : ctx.currentSubGoalDescription();
                GoalKindClassifier.GoalKind kind = kindClassifier.classify(desc);

                boolean cognitive = kind == GoalKindClassifier.GoalKind.COGNITIVE
                        || kind == GoalKindClassifier.GoalKind.HYBRID;

                if (!cognitive) {
                    blockers.add("subgoal is not COGNITIVE/HYBRID");
                    return blockers;
                }

                boolean triggered = ctx.hasSignal(BasicSignals.LOW_CONFIDENCE)
                        || ctx.hasSignal(BasicSignals.STAGNATION)
                        || ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10);

                if (!triggered) {
                    blockers.add("no trigger present (LOW_CONFIDENCE/STAGNATION/REPLAN_REQUESTED)");
                }

                return blockers;
            }

            case "planner" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_PLANNER, 0.10)) {
                    blockers.add("missing strong ROLE_PLANNER (>=0.10)");
                    return blockers;
                }

                if (ctx.hasSignal(BasicSignals.PLANNING_THROTTLED)) { // TODO: (not here) - also prune (sub)goals if necessary
                    blockers.add("planning throttled (disproportional inflow vs completion outflow)");
                    return blockers;
                }

                GoalEntity sg = goalPlannerService.getCurrentSubGoal();
                if (sg != null && sg.getKind() == GoalKindClassifier.GoalKind.COGNITIVE) {
                    boolean sameScope = Objects.equals(ctx.currentSubGoalId(), ctx.analysisScopeSubGoalId());
                    boolean hasProposed = ctx.analysisOutcome() != null
                            && ctx.analysisOutcome().proposedExecutableSubGoals() != null
                            && !ctx.analysisOutcome().proposedExecutableSubGoals().isEmpty();

                    if (sameScope && hasProposed) {
                        blockers.add("analysis already proposed executable subgoals for this cognitive subgoal scope");
                        return blockers;
                    }
                }

                boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();

                // Planner can bootstrap/repair goals
                boolean noGoalState = !hasSubGoal;

                boolean stagnating = ctx.hasSignal(BasicSignals.STAGNATION)
                        || (ctx.goalProgress() == com.walpro.autobuilder.model.GoalProgressState.STAGNATING);

                boolean bootstrap = ctx.deliberationResult() != null
                        && ctx.deliberationResult().mode() == DeliberationMode.BOOTSTRAP;

                boolean needsInitialDecomposition = needsInitialRootDecomposition(ctx);

                boolean replanRequested = ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10);

                if (!noGoalState && !stagnating && !bootstrap && !needsInitialDecomposition && !replanRequested) {
                    blockers.add("neither noGoalState nor STAGNATION/BOOTSTRAP/needsInitialDecomposition/REPLAN_REQUESTED present");
                }

                return blockers;
            }

            default -> {
                blockers.add("unknown role gating");
                return blockers;
            }
        }
    }

    public boolean canExecute(String role, CycleContextData ctx) {
        return explainBlockers(role, ctx).isEmpty();
    }

    public boolean isAcceptedNotMergedAttempt(OrchestrationSnapshot snapshot) {
        if (snapshot == null) return false;

        try {
            boolean accepted = snapshot.lastEvaluation() != null
                    && "ACCEPTED".equalsIgnoreCase(snapshot.lastEvaluation().status());

            boolean merged = (snapshot.lastMerge() != null)
                    || Boolean.TRUE.equals(snapshot.mergedViaGraph());

            return accepted && !merged;
        } catch (Exception ignored) {
            // fail open: do not block if graph lookup fails
            log.debug("isAcceptedNotMergedAttempt lookup failed: {}", ignored.getMessage());
            return false;
        }
    }

    private OrchestrationSnapshot safeFetchSnapshot(CycleContextData ctx) {
        if (ctx == null) return null;

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();

        if ((goalId == null || goalId.isBlank()) && (subGoalId == null || subGoalId.isBlank())) {
            return null;
        }

        try {
            return orchestrationSnapshotService.fetchLatestSnapshot(goalId, subGoalId, 10);
        } catch (Exception ignored) {
            return null; // fail open
        }
    }

    public boolean needsInitialRootDecomposition(CycleContextData ctx) {
        if (ctx == null) return false;

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();

        if (goalId == null || goalId.isBlank()) return false;
        if (subGoalId == null || subGoalId.isBlank()) return false;

        // only when "we are sitting on the root"
        if (!goalId.equals(subGoalId)) return false;

        GoalEntity root = goalPlannerService.getRootGoal();
        if (root == null) return false;

        Object plannedOnce = root.getMetadata() != null ? root.getMetadata().get("plannedOnce") : null;
        if (Boolean.TRUE.equals(plannedOnce)) return false;

        return true;
    }
}
