package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.model.GoalInfo;
import com.walpro.autobuilder.agent.planner.model.PlannerInput;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.model.CycleContextData;

import jakarta.annotation.PostConstruct;
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
    
    @PostConstruct
    public void registerPlannerCallback() {
        goalPlannerService.setPlanningCallback(this::planInto);
    }

    public CycleContextData plan(CycleContextData context) {
        PlannerInput input = PlannerInput.from(context);

        if (input.currentGoal() == null && (input.remainingGoals() == null || input.remainingGoals().isEmpty())) {
            log.warn("⚠️ No current or remaining goals found — injecting fallback root goal");

            input = new PlannerInput(
                    "bootstrap-project-structure",
                    "create-basic-structure",
                    List.of(),
                    List.of(),
                    input.evaluationFeedback(),
                    input.recommendation(),
                    input.interpretationResult()
            );
        }

        PlannerOutcome outcome = plannerAgent.plan(input);
        injectOutcomeGoals(outcome, null);
        plannerAgent.evolveGraph(input, outcome);

        return context.withPlannerOutcome(outcome);
    }
    
    public void planInto(String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("⚠️ Cannot plan into null parent: {}", parentGoalId);
            return;
        }

//        PlannerInput input = new PlannerInput(
//                goalPlannerService.getRootGoal().getDescription(),
//                parent.getDescription(),
//                goalPlannerService.getRemainingGoals().stream()
//                        .map(g -> new GoalInfo(g.getId(), g.getDescription()))
//                        .toList(),
//                goalPlannerService.getCompletedGoals().stream()
//                        .map(g -> new GoalInfo(g.getId(), g.getDescription()))
//                        .toList(),
//                "", // no evaluation feedback in this path
//                "",  // no recommendation either
//                null
//        );
        PlannerInput input = PlannerInput.fromSubgoalPlanning(
        	    goalPlannerService.getRootGoal().getDescription(),
        	    parent.getDescription(),
        	    goalPlannerService
        );

        PlannerOutcome outcome = plannerAgent.plan(input);

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

        plannerAgent.evolveGraph(input, outcome);
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
