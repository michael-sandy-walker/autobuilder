package com.walpro.autobuilder.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;

@ExtendWith(MockitoExtension.class)
class PlannerAgentTest {

    @Mock private PlannerLLMAdapter plannerAdapter;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalSimilarityService similarityService;

    private PlannerAgent agent;

    @BeforeEach
    void setUp() {
        agent = new PlannerAgent(plannerAdapter, goalPlannerService, similarityService);
    }

    @Test
    void plan_injectsNonDuplicateGoalsAndRegistersSimilarity() {
        CycleContextData ctx = CycleContextData.builder().build().withCurrentGoalId("123").withCurrentGoalDescription("test");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("First goal", "Duplicate goal"));
        when(outcome.message()).thenReturn("message");

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        // First goal is new, second is considered duplicate
        when(similarityService.isDuplicate("First goal")).thenReturn(false);
        when(similarityService.isDuplicate("Duplicate goal")).thenReturn(true);

        CycleContextData updated = agent.execute(ctx);

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
        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void plan_skipsInjectionWhenOutcomeNotUpdated() {
        CycleContextData ctx = CycleContextData.builder().build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        agent.execute(ctx);

        // No goals should be touched
        verify(goalPlannerService, never())
                .addSubGoal(anyString(), anyString(), anyBoolean());
        verify(similarityService, never()).isDuplicate(anyString());
        verify(similarityService, never()).register(anyString());
    }

    @Test
    void planInto_injectsNestedSubGoalsUnderParent() {
        String parentId = "goal-parent";

        GoalEntity parent = mock(GoalEntity.class);
//        when(parent.getId()).thenReturn(parentId);
//        when(parent.getDescription()).thenReturn("Parent description");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        GoalEntity root = mock(GoalEntity.class);
//        when(root.getId()).thenReturn("root-goal");
//        when(root.getDescription()).thenReturn("Root goal");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
//        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
//        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Nested goal"));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);
        when(similarityService.isDuplicate("Nested goal")).thenReturn(false);

        agent.planInto(CycleContextData.builder().build(), parentId);

        // Nested subgoal should be injected with parentId
        verify(goalPlannerService)
                .addSubGoal(anyString(), eq("Nested goal"), eq(true), eq(parentId));

        // Planning graph is updated for the planning context
        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void planInto_withUnknownParentDoesNothing() {
        String parentId = "unknown";
        when(goalPlannerService.getGoalById(parentId)).thenReturn(null);

        agent.planInto(CycleContextData.builder().build(), parentId);

        verify(plannerAdapter, never()).plan(any());
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

        assertThat(agent.canExecute(withoutSignal)).isFalse();
        assertThat(agent.canExecute(withSignal)).isTrue();
    }

    @Test
    void execute_whenStagnating_createsRefinementSubgoalUnderCurrentGoal() {
        String parentId = "goal-1";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getDescription()).thenReturn("Improve evaluation-planner integration");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(parentId)
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "test"));

        // plannerAgent.plan can return "not updated" so injection doesn't distract from refinement assertion
        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(
                anyString(),
                contains("Refine goal: Improve evaluation-planner integration"),
                eq(true),
                eq(parentId)
        );
    }


    @Test
    void execute_whenStagnating_prefersCurrentSubGoalForRefinement() {
        String subId = "sub-1";
        GoalEntity sub = mock(GoalEntity.class);
        when(sub.getDescription()).thenReturn("Make evaluation more precise");
        when(goalPlannerService.getGoalById(subId)).thenReturn(sub);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId(subId)
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "test"));

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(
                anyString(),
                eq("Refine goal: Make evaluation more precise (auto-refinement from evaluation)"),
                eq(true),
                eq(subId)
        );
    }
}
