package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.model.GoalInfo;
import com.walpro.autobuilder.agent.planner.model.PlannerInput;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.model.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerCoordinator {

    private final PlannerAgent plannerAgent;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;

    public CycleContextData plan(CycleContextData context) {
        PlannerInput input = new PlannerInput(
                context.currentGoalDescription(),
                context.currentSubGoalDescription(),
                goalPlannerService.getRemainingGoals().stream()
                        .map(g -> new GoalInfo(g.getId(), g.getDescription()))
                        .toList(),
                goalPlannerService.getCompletedGoals().stream()
                        .map(g -> new GoalInfo(g.getId(), g.getDescription()))
                        .toList(),
                context.evaluationFeedback(),
                context.recommendation()
        );

        if (input.currentGoal() == null && (input.remainingGoals() == null || input.remainingGoals().isEmpty())) {
            log.warn("⚠️ No current or remaining goals found — injecting fallback root goal");

            input = new PlannerInput(
                    "bootstrap-project-structure",                // currentGoal
                    "create-basic-structure",                     // currentSubGoal
                    List.of(),                                    // remainingGoals
                    List.of(),                                    // completedGoals
                    input.evaluationFeedback(),
                    input.recommendation()
            );
        }

        PlannerOutcome outcome = plannerAgent.plan(input);

        if (outcome.updated()) {
        	boolean isFirstNewGoal = true;
            for (String newGoal : outcome.newGoals()) {
                if (similarityService.isDuplicate(newGoal)) {
                    log.warn("⚠️ Skipped similar subgoal: '{}'", newGoal);
                    continue;
                }
                String id = "goal-" + System.currentTimeMillis();
                goalPlannerService.addSubGoal(id, newGoal, isFirstNewGoal);
                similarityService.register(newGoal); // register only if accepted
                isFirstNewGoal = false;
                log.info("🧠 Planner injected sub-goal [{}]: {}", id, newGoal);
            }
            log.info("Planner update message: {}", outcome.message());
        }

        return context.withPlannerOutcome(outcome);
    }
}
