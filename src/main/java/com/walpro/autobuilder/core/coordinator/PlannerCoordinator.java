package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.model.GoalProgressState;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerCoordinator implements AgentCoordinator {

    private final PlannerAgent plannerAgent;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;
    private final MessageBus messageBus;

    @PostConstruct
    public void registerPlannerCallback() {
        goalPlannerService.setPlanningCallback(this::planInto);
    }

    @PostConstruct
    void initMessageSubscription() {
        messageBus.subscribe(AgentRoles.PLANNER, this::onAgentMessage);
        log.debug("PlannerCoordinator subscribed to MessageBus as '{}'", AgentRoles.PLANNER);
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

    @Override
    public String role() {
        return "planner";
    }

    @Override
    public boolean canExecute(CycleContextData ctx) {
        return ctx.hasSignal(BasicSignals.ROLE_PLANNER);
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing coordinator (role={}):", role());

        context = plan(context);
        return context;
    }

    void onAgentMessage(AgentMessage message) {
        if (message.type() == AgentMessageType.INSIGHT
                && AgentRoles.EVALUATION.equals(message.fromAgent())) {

            log.debug("PlannerCoordinator received Evaluation INSIGHT: {}", message.payload());

            handleEvaluationInsight(message);
        }
    }

    private void handleEvaluationInsight(AgentMessage message) {
        Object progressObj = message.payload().get("goalProgress");
        if (!(progressObj instanceof GoalProgressState progress)) {
            log.debug("PlannerCoordinator: INSIGHT without GoalProgressState, ignoring.");
            return;
        }

        if (progress != GoalProgressState.STAGNATING) {
            log.debug("PlannerCoordinator: GoalProgress is {}, no refinement needed.", progress);
            return;
        }

        // Prefer refining the current subgoal; fall back to the current root goal
        Object subGoalIdObj = message.payload().get("currentSubGoalId");
        Object goalIdObj = message.payload().get("currentGoalId");

        String targetGoalId = subGoalIdObj instanceof String s && !s.isBlank()
                ? s
                : goalIdObj instanceof String g && !g.isBlank() ? g : null;

        if (targetGoalId == null) {
            log.warn("PlannerCoordinator: STAGNATING but no goalId available in INSIGHT payload, cannot refine.");
            return;
        }

        createRefinementSubgoal(targetGoalId);
    }

    private void createRefinementSubgoal(String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("PlannerCoordinator: Cannot refine unknown goalId={}", parentGoalId);
            return;
        }

        String parentDescription = parent.getDescription();
        String refinementDescription = "Refine goal: " + parentDescription + " (auto-refinement from evaluation)";

        String newId = "goal-" + System.currentTimeMillis();

        // Nested refinement under the parent
        goalPlannerService.addSubGoal(newId, refinementDescription, true, parentGoalId);

        log.info("🧠 PlannerCoordinator: added refinement subgoal [{}] under [{}]: {}",
                newId, parentGoalId, refinementDescription);
    }
}
