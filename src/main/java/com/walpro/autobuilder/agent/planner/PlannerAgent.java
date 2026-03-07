package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent implements ExecutionAgent {

    private final PlannerLLMAdapter plannerAdapter;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;

    @PostConstruct
    public void registerPlanningCallback() {
        goalPlannerService.setPlanningCallback(this::planInto);
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        // If evaluation suggests stagnation, perform an automatic refinement
        // (replaces message-based INSIGHT refinement)
        if (shouldAutoRefine(context)) {
            String target = chooseRefinementTarget(context);
            if (target != null) {
                createRefinementSubgoal(target);
                // consume stagnation so it doesn't endlessly refine
                if (context.hasSignal(BasicSignals.STAGNATION)) {
                    context = context.withoutSignal(BasicSignals.STAGNATION);
                }
            }
        }

        context = ensureFallbackGoalState(context);

        if (context.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            String best = context.bestMatchSubGoalId();
            if (best != null && !best.isBlank()) {
                boolean switched = false;
                try {
                    switched = goalPlannerService.activateSubGoal(best, "planner: scope repair");
                } catch (Exception e) {
                    log.warn("Planner scope repair activation failed (bestMatch={}): {}", best, e.getMessage());
                }

                if (switched) {
                    GoalEntity g = goalPlannerService.getCurrentSubGoal();
                    context = context
                            .withCurrentSubGoalId(g != null ? g.getId() : best)
                            .withCurrentSubGoalDescription(g != null ? g.getDescription() : context.currentSubGoalDescription());
                } else {
                    log.warn("Planner scope repair could not activate bestMatchSubGoalId={}", best);
                }
            }

            // Consume verdict regardless (avoid stale carryover); guard will re-emit next cycle if still needed.
            context = context
                    .withoutSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)
                    .withBestMatchSubGoalId(null);
        }

        PlannerOutcome outcome = plannerAdapter.plan(context);

        boolean replanOverride = context.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 1.0);
        if (context.hasSignal(BasicSignals.PLANNING_THROTTLED) && !replanOverride) {
            CycleContextData refreshed = refreshGoalState(context)
                    .withPlannerOutcome(outcome)
                    .withLastPlannerOutcome(outcome);

            // record planner attempt/outcome, but do not inject goals TODO: still to test if this holds
            plannerAdapter.evolveGraph(refreshed, outcome);
            return refreshed;
        }
//        if (replanOverride && context.hasSignal(BasicSignals.PLANNING_THROTTLED)) { // TODO: this is commented out, because PLANNING_THROTTLED should still be a signal in non-REPLAN_REQUESTED runs
//            log.info("Planner: PLANNING_THROTTLED cleared — REPLAN_REQUESTED override");
//            context = context.withoutSignal(BasicSignals.PLANNING_THROTTLED);
//        }
        if (replanOverride) {
            context = context.withoutSignal(BasicSignals.REPLAN_REQUESTED);
        }

        // remove goals the planner wants to prune before injecting new ones
        processRemovals(outcome);

        // inject goals into planner service + similarity registry
        injectOutcomeGoals(outcome, context.currentSubGoalId());

        plannerAdapter.evolveGraph(context, outcome);

        context = refreshGoalState(context);

        return context.withPlannerOutcome(outcome).withLastPlannerOutcome(outcome);
    }

    @Override
    public String name() {
        return "planner";
    }

    @Override
    public String role() {
        return "planner";
    }

    @Override
    public java.util.Set<SignalType> getSupportedSignals() {
        return BasicSignals.PLANNER_SIGNALS;
    }

    private CycleContextData ensureFallbackGoalState(CycleContextData context) {
        boolean noCurrent = context.currentGoalId() == null;
        boolean noRemaining = context.remainingGoals() == null || context.remainingGoals().isEmpty();

        if (noCurrent && noRemaining) {
            log.warn("No current or remaining goals found — injecting fallback root goal");

            return context.withCurrentGoalId("bootstrap-project-structure")
                    .withCurrentSubGoalId("create-basic-structure")
                    .withPlannerOutcome(null)
                    .withEvaluationFeedback(context.evaluationFeedback())
                    .withRecommendation(context.recommendation());
        }

        return context;
    }

    /**
     * Callback used by GoalPlannerService to plan under a given parent goal id.
     */
    public void planInto(CycleContextData ctx, String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("Cannot plan into null parent: {}", parentGoalId);
            return;
        }

        AttemptIds ids = goalPlannerService.ensureAttemptIdsFor(parent.getId());

        GoalEntity root = goalPlannerService.getRootGoal();
        String goalId = root != null ? root.getId() : null;
        String goalDescription = root != null ? root.getDescription() : null;

        String parentDescription = parent.getDescription();

        CycleContextData base = (ctx != null) ? ctx : CycleContextData.builder().build();

        // Always start from ctx, but overwrite the planning scope fields deterministically
        CycleContextData planningContext = base
                .withCurrentGoalId(goalId)
                .withCurrentGoalDescription(goalDescription)
                .withCurrentSubGoalId(parent.getId())
                .withCurrentSubGoalDescription(parentDescription)
                .withLastAttemptId(ids.attemptId())
                .withParentAttemptId(ids.parentAttemptId())
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals());

        PlannerOutcome outcome = plannerAdapter.plan(planningContext);

        if (planningContext.hasSignal(BasicSignals.PLANNING_THROTTLED)
                && !planningContext.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 1.0)) {
            CycleContextData refreshed = refreshGoalState(planningContext)
                    .withPlannerOutcome(outcome)
                    .withLastPlannerOutcome(outcome);

            // record planner attempt/outcome, but do not inject goals
            plannerAdapter.evolveGraph(refreshed, outcome);
            return;
        }

        if (outcome.updated()) {
            var sanitized = goalPlannerService.sanitizeProposedSubGoals(outcome.newGoals());
            boolean isFirst = true;

            for (String goal : sanitized) {
                if (similarityService.isDuplicate(goal)) {
                    log.warn("Skipped similar subgoal: '{}'", goal);
                    continue;
                }
                String id = "goal-" + UUID.randomUUID();
                goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
                log.info("Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);

                isFirst = false;
            }
        } else {
            log.warn("Planner did not produce new subgoals for {}", parentGoalId);
        }

        plannerAdapter.evolveGraph(planningContext, outcome);
    }

    private void processRemovals(PlannerOutcome outcome) {
        if (outcome == null || outcome.goalsToRemove() == null || outcome.goalsToRemove().isEmpty()) return;

        for (String desc : outcome.goalsToRemove()) {
            goalPlannerService.getRemainingGoals().stream()
                    .filter(g -> g.getDescription().equalsIgnoreCase(desc))
                    .findFirst()
                    .ifPresentOrElse(
                            g -> {
                                goalPlannerService.markGoalGivenUp(g.getId());
                                log.info("Planner pruned subgoal [{}]: {}", g.getId(), desc);
                            },
                            () -> log.warn("Planner #SubGoalRemove: no active subgoal found for description='{}'", desc)
                    );
        }

        // If currentSubGoalId is now GIVEN_UP, advance to the next active subgoal.
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current != null && current.getStatus() == GoalStatus.GIVEN_UP) {
            goalPlannerService.getRemainingGoals().stream().findFirst().ifPresentOrElse(
                    next -> {
                        try {
                            goalPlannerService.setCurrentSubGoal(next.getId());
                            log.info("Advanced current subgoal to [{}] after removal.", next.getId());
                        } catch (Exception e) {
                            log.warn("Could not advance current subgoal after removal: {}", e.getMessage());
                        }
                    },
                    () -> log.warn("All subgoals given up after removal — no active subgoal remains.")
            );
        }
    }

    private void injectOutcomeGoals(PlannerOutcome outcome, String parentGoalId) {
        if (outcome == null || !outcome.updated()) return;

        var sanitized = goalPlannerService.sanitizeProposedSubGoals(outcome.newGoals());
        if (sanitized.isEmpty()) return;

        boolean isFirst = true;
        for (String goal : sanitized) {
            if (similarityService.isDuplicate(goal)) {
                log.warn("Skipped similar subgoal: '{}'", goal);
                continue;
            }

            String id = "goal-" + UUID.randomUUID();
            if (parentGoalId != null) {
                goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
                log.info("Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);
            } else {
                goalPlannerService.addSubGoal(id, goal, isFirst);
                log.info("Planner injected new subgoal [{}]: {}", id, goal);
            }

            isFirst = false;
        }

        log.info("Planner outcome message: {}", outcome.message());
    }

    private boolean shouldAutoRefine(CycleContextData ctx) {
        if (ctx == null) return false;
        if (ctx.goalProgress() == GoalProgressState.STAGNATING) return true;
        return ctx.hasSignal(BasicSignals.STAGNATION);
    }

    private String chooseRefinementTarget(CycleContextData ctx) {
        if (ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank()) {
            return ctx.currentSubGoalId();
        }
        if (ctx.currentGoalId() != null && !ctx.currentGoalId().isBlank()) {
            return ctx.currentGoalId();
        }
        return null;
    }

    private void createRefinementSubgoal(String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("PlannerExecutionAgent: Cannot refine unknown goalId={}", parentGoalId);
            return;
        }

        String parentDescription = parent.getDescription();
        String refinementDescription = "Refine goal: " + parentDescription + " (auto-refinement from evaluation)";

        String newId = "goal-" + UUID.randomUUID();

        goalPlannerService.addSubGoal(newId, refinementDescription, true, parentGoalId);

        log.info("PlannerExecutionAgent: added refinement subgoal [{}] under [{}]: {}",
                newId, parentGoalId, refinementDescription);
    }

    private CycleContextData refreshGoalState(CycleContextData ctx) {
        if (ctx == null) return null;

        GoalEntity root = goalPlannerService.getRootGoal();
        GoalEntity current = goalPlannerService.getCurrentSubGoal();

        String rootId = root != null ? root.getId() : ctx.currentGoalId();
        String rootDesc = root != null ? root.getDescription() : ctx.currentGoalDescription();

        String subId = current != null ? current.getId() : ctx.currentSubGoalId();
        String subDesc = current != null ? current.getDescription() : ctx.currentSubGoalDescription();

        return ctx
                .withCurrentGoalId(rootId)
                .withCurrentGoalDescription(rootDesc)
                .withCurrentSubGoalId(subId)
                .withCurrentSubGoalDescription(subDesc)
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals());
    }

}
