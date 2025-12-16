package com.walpro.autobuilder.core.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;

@ExtendWith(MockitoExtension.class)
class PlannerCoordinatorTest {

    @Mock private PlannerAgent plannerAgent;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalSimilarityService similarityService;
    @Mock private MessageBus messageBus;

    private PlannerCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new PlannerCoordinator(plannerAgent, goalPlannerService, similarityService, messageBus);
    }

    @Test
    void plan_injectsNonDuplicateGoalsAndRegistersSimilarity() {
        CycleContextData ctx = CycleContextData.builder().build();

        PlannerOutcome outcome = org.mockito.Mockito.mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("First goal", "Duplicate goal"));
        when(outcome.message()).thenReturn("message");

        when(plannerAgent.plan(any(CycleContextData.class))).thenReturn(outcome);

        // First goal is new, second is considered duplicate
        when(similarityService.isDuplicate("First goal")).thenReturn(false);
        when(similarityService.isDuplicate("Duplicate goal")).thenReturn(true);

        CycleContextData updated = coordinator.plan(ctx);

        // PlannerOutcome must be attached to context
        assertThat(updated.plannerOutcome()).isSameAs(outcome);
        assertThat(updated.lastPlannerOutcome()).isSameAs(outcome);

        // First goal should be injected and registered
        verify(goalPlannerService).addSubGoal(anyString(), eq("First goal"), eq(true));
        verify(similarityService).register("First goal");

        // Duplicate goal is skipped completely
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), eq("Duplicate goal"), anyBoolean());
        verify(similarityService, never()).register("Duplicate goal");

        // Planning graph is updated
        verify(plannerAgent).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void plan_skipsInjectionWhenOutcomeNotUpdated() {
        CycleContextData ctx = CycleContextData.builder().build();

        PlannerOutcome outcome = org.mockito.Mockito.mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAgent.plan(any(CycleContextData.class))).thenReturn(outcome);

        coordinator.plan(ctx);

        // No goals should be touched
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean());
        verify(similarityService, never()).isDuplicate(anyString());
        verify(similarityService, never()).register(anyString());
    }

    @Test
    void planInto_injectsNestedSubGoalsUnderParent() {
        String parentId = "goal-parent";

        GoalEntity parent = org.mockito.Mockito.mock(GoalEntity.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getDescription()).thenReturn("Parent description");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        GoalEntity root = org.mockito.Mockito.mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-goal");
        when(root.getDescription()).thenReturn("Root goal");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = org.mockito.Mockito.mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Nested goal"));

        when(plannerAgent.plan(any(CycleContextData.class))).thenReturn(outcome);
        when(similarityService.isDuplicate("Nested goal")).thenReturn(false);

        coordinator.planInto(parentId);

        // Nested subgoal should be injected with parentId
        verify(goalPlannerService)
                .addSubGoal(anyString(), eq("Nested goal"), eq(true), eq(parentId));
        verify(similarityService).register("Nested goal");

        // Planning graph is updated for the planning context
        verify(plannerAgent).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void planInto_withUnknownParentDoesNothing() {
        String parentId = "unknown";
        when(goalPlannerService.getGoalById(parentId)).thenReturn(null);

        coordinator.planInto(parentId);

        verify(plannerAgent, never()).plan(any());
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
        verify(similarityService, never()).register(anyString());
    }

    @Test
    void canExecute_dependsOnPlannerSignal() {
        CycleContextData withoutSignal = CycleContextData.builder().build();

        CycleContextData withSignal = withoutSignal.withSignal(
                Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test")
        );

        assertThat(coordinator.canExecute(withoutSignal)).isFalse();
        assertThat(coordinator.canExecute(withSignal)).isTrue();
    }

    @Test
    void onAgentMessage_addsRefinementSubgoalWhenStagnating() {
        // Arrange
        String parentId = "goal-1";

        GoalEntity parent = org.mockito.Mockito.mock(GoalEntity.class);
        when(parent.getDescription()).thenReturn("Improve evaluation-planner integration");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        AgentMessage message = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                "plan",
                null,
                Map.of(
                        "goalProgress", GoalProgressState.STAGNATING,
                        "currentGoalId", parentId
                )
        );

        // Act
        coordinator.onAgentMessage(message);

        // Assert
        // We don't care about the exact newId, only that we add a subgoal under parentId with a refinement description.
        verify(goalPlannerService).addSubGoal(
                anyString(),
                org.mockito.ArgumentMatchers.contains("Refine goal: Improve evaluation-planner integration"),
                eq(true),
                eq(parentId)
        );
    }

    @Test
    void onAgentMessage_ignoresNonInsightOrNonEvaluationMessages() {
        // Non-INSIGHT type
        AgentMessage nonInsight = new AgentMessage(
                AgentRoles.EVOLUTION,
                AgentRoles.PLANNER,
                AgentMessageType.REQUEST,
                "plan",
                null,
                Map.of(
                        "goalProgress", GoalProgressState.STAGNATING,
                        "currentGoalId", "goal-1"
                )
        );

        coordinator.onAgentMessage(nonInsight);

        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean());
    }

    @Test
    void onAgentMessage_withNonStagnatingProgress_doesNothing() {
        AgentMessage insight = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                "plan",
                null,
                Map.of(
                        "goalProgress", GoalProgressState.PROGRESSING,
                        "currentGoalId", "goal-1"
                )
        );

        coordinator.onAgentMessage(insight);

        // No refinement subgoal should be created
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void onAgentMessage_withStagnatingProgressAndSubGoal_createsRefinementSubgoalUnderSubGoal() {
        String parentId = "sub-1";

        GoalEntity parent = new GoalEntity();
        parent.setId(parentId);
        parent.setDescription("Make evaluation more precise");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        AgentMessage insight = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                "plan",
                null,
                Map.of(
                        "goalProgress", GoalProgressState.STAGNATING,
                        "currentSubGoalId", parentId,
                        "currentGoalId", "root-1"
                )
        );

        coordinator.onAgentMessage(insight);

        String expectedDescription =
                "Refine goal: " + parent.getDescription() + " (auto-refinement from evaluation)";

        // We don’t care about the generated ID, only that:
        //  - description matches the refinement text
        //  - it is nested under the correct parent
        verify(goalPlannerService).addSubGoal(
                anyString(),
                eq(expectedDescription),
                eq(true),
                eq(parentId)
        );
    }

    @Test
    void onAgentMessage_withStagnatingProgressButNoGoalId_doesNothing() {
        AgentMessage insight = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                "plan",
                null,
                Map.of(
                        "goalProgress", GoalProgressState.STAGNATING
                        // no currentGoalId / currentSubGoalId
                )
        );

        coordinator.onAgentMessage(insight);

        // Without any goalId, no refinement can be created
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
    }

}
