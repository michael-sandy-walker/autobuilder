package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
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
    public boolean canExecute(CycleContextData ctx) {
        if (ctx == null) return false;

        if (!ctx.hasStrongSignal(BasicSignals.ROLE_PLANNER, 0.10)) {
            return false;
        }

        boolean hasGoal = ctx.currentGoalId() != null && !ctx.currentGoalId().isBlank();
        boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();

        // Planner is allowed to bootstrap/repair goals.
        // If we later add an explicit PLAN_REQUEST signal, include it here too.
        boolean noGoalState = /*!hasGoal &&*/ !hasSubGoal;

        // No bus: stagnation/refinement is derived from context directly
        boolean stagnating = ctx.hasSignal(BasicSignals.STAGNATION)
                || (ctx.goalProgress() == GoalProgressState.STAGNATING);

        boolean bootstrap = ctx.deliberationResult() != null
                && ctx.deliberationResult().mode() == DeliberationMode.BOOTSTRAP;

        boolean needsInitialDecomposition = needsInitialRootDecomposition(ctx);

        return noGoalState
                || stagnating
                || bootstrap
                || needsInitialDecomposition
                || ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.1);
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

        PlannerOutcome outcome = plannerAdapter.plan(context);

        // inject goals into planner service + similarity registry
        injectOutcomeGoals(outcome, null); // TODO: is parentGoalId always null?

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

        String goalDescription = goalPlannerService.getRootGoal().getDescription();
        String parentDescription = parent.getDescription();

        CycleContextData planningContext = ctx != null ? ctx : CycleContextData.builder()
                .currentGoalDescription(goalDescription)
                .currentSubGoalDescription(parentDescription)
                .currentGoalId(goalPlannerService.getRootGoal().getId())
                .currentSubGoalId(parent.getId())
                .remainingGoals(goalPlannerService.getRemainingGoals())
                .completedGoals(goalPlannerService.getCompletedGoals())
                .build();

        PlannerOutcome outcome = plannerAdapter.plan(planningContext);

        if (outcome.updated()) {
            boolean isFirst = true;
            for (String goal : outcome.newGoals()) {
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

    private void injectOutcomeGoals(PlannerOutcome outcome, String parentGoalId) {
        if (outcome == null || !outcome.updated()) return;

        boolean isFirst = true;
        for (String goal : outcome.newGoals()) {
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

            similarityService.register(goal);
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

    private boolean needsInitialRootDecomposition(CycleContextData ctx) {
        if (ctx.currentGoalId() == null || ctx.currentSubGoalId() == null) return false;
        if (!ctx.currentGoalId().equals(ctx.currentSubGoalId())) return false;

        GoalEntity root = goalPlannerService.getRootGoal();
        if (root == null) return false;

        Object plannedOnce = root.getMetadata() != null ? root.getMetadata().get("plannedOnce") : null;
        if (Boolean.TRUE.equals(plannedOnce)) return false;

        // If we’re sitting on the root and it hasn’t been planned yet → do it once.
        return true;
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
