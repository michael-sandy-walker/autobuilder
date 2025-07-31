package com.walpro.autobuilder.coordinator;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.lifecycle.CycleContextData;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerCoordinator {

    private final PlannerAgent plannerAgent;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;

    @PostConstruct
    public void registerPlannerCallback() {
        goalPlannerService.setPlanningCallback(this::planInto);
    }

    public CycleContextData plan(CycleContextData context) {
        boolean noCurrent = context.currentGoalId() == null;
        boolean noRemaining = context.remainingGoals() == null || context.remainingGoals().isEmpty();

        if (noCurrent && noRemaining) {
            log.warn("⚠️ No current or remaining goals found — injecting fallback root goal");

            context = context.withCurrentGoalId("bootstrap-project-structure")
                    .withCurrentSubGoalId("create-basic-structure")
                    .withPlannerOutcome(null)
                    .withEvaluationFeedback(context.evaluationFeedback())
                    .withRecommendation(context.recommendation());
        }

        PlannerOutcome outcome = plannerAgent.plan(context);
        injectOutcomeGoals(outcome, null);
        plannerAgent.evolveGraph(context, outcome);

        return context.withPlannerOutcome(outcome).withLastPlannerOutcome(outcome);
    }

    public void planInto(String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("⚠️ Cannot plan into null parent: {}", parentGoalId);
            return;
        }

        String goalDescription = goalPlannerService.getRootGoal().getDescription();
        String parentDescription = parent.getDescription();

        // Provide full context for planner agent
        CycleContextData planningContext = CycleContextData.builder()
                .currentGoalDescription(goalDescription)
                .currentSubGoalDescription(parentDescription)
                .currentGoalId(goalPlannerService.getRootGoal().getId())
                .currentSubGoalId(parent.getId())
                .remainingGoals(goalPlannerService.getRemainingGoals())
                .completedGoals(goalPlannerService.getCompletedGoals())
                .build();

        PlannerOutcome outcome = plannerAgent.plan(planningContext);

        if (outcome.updated()) {
            boolean isFirst = true;
            for (String goal : outcome.newGoals()) {
                if (similarityService.isDuplicate(goal)) {
                    log.warn("⚠️ Skipped similar subgoal: '{}'", goal);
                    continue;
                }
                String id = "goal-" + System.currentTimeMillis();
                goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
                similarityService.register(goal);
                log.info("🧠 Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);
                isFirst = false;
            }
        } else {
            log.warn("⚠️ Planner did not produce new subgoals for {}", parentGoalId);
        }

        plannerAgent.evolveGraph(planningContext, outcome);
    }


    private void injectOutcomeGoals(PlannerOutcome outcome, String parentGoalId) {
        if (!outcome.updated()) return;

        boolean isFirst = true;
        for (String goal : outcome.newGoals()) {
            if (similarityService.isDuplicate(goal)) {
                log.warn("⚠️ Skipped similar subgoal: '{}'", goal);
                continue;
            }

            String id = "goal-" + System.currentTimeMillis();
            if (parentGoalId != null) {
                goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
                log.info("🧠 Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);
            } else {
                goalPlannerService.addSubGoal(id, goal, isFirst);
                log.info("🧠 Planner injected new subgoal [{}]: {}", id, goal);
            }

            similarityService.register(goal);
            isFirst = false;
        }

        log.info("Planner outcome message: {}", outcome.message());
    }
}
