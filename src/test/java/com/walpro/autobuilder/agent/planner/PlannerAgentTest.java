package com.walpro.autobuilder.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.walpro.autobuilder.goal.AttemptIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.model.GoalProgressState;

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
    void registerPlanningCallback_registersPlanIntoCallback() {
        agent.registerPlanningCallback();
        verify(goalPlannerService).setPlanningCallback(any());
    }

    @Test
    void execute_injectsNonDuplicateGoalsUnderRootWhenNoCurrentSubGoal() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentGoalDescription("root desc")
                .build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("First goal", "Duplicate goal"));
        when(outcome.message()).thenReturn("message");

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("First goal", "Duplicate goal"));

        when(similarityService.isDuplicate("First goal")).thenReturn(false);
        when(similarityService.isDuplicate("Duplicate goal")).thenReturn(true);

        // refreshGoalState: keep it simple
        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("root desc");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData updated = agent.execute(ctx);

        assertThat(updated.plannerOutcome()).isSameAs(outcome);
        assertThat(updated.lastPlannerOutcome()).isSameAs(outcome);

        // Root injection (no parent)
        verify(goalPlannerService).addSubGoal(anyString(), eq("First goal"), eq(true));
        verify(goalPlannerService, never()).addSubGoal(anyString(), eq("Duplicate goal"), anyBoolean());

        verify(similarityService).isDuplicate("First goal");
        verify(similarityService).isDuplicate("Duplicate goal");

        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void execute_injectsNonDuplicateGoalsUnderCurrentSubGoalWhenPresent() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-1")
                .build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Nested 1", "Nested 2"));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("Nested 1", "Nested 2"));

        when(similarityService.isDuplicate(anyString())).thenReturn(false);

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(goalPlannerService.getRootGoal()).thenReturn(root);

        GoalEntity current = mock(GoalEntity.class);
        when(current.getId()).thenReturn("sub-1");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(anyString(), eq("Nested 1"), eq(true), eq("sub-1"));
        verify(goalPlannerService).addSubGoal(anyString(), eq("Nested 2"), eq(false), eq("sub-1"));
    }

    @Test
    void execute_skipsInjectionWhenOutcomeNotUpdated_butStillEvolvesGraph() {
        CycleContextData ctx = CycleContextData.builder().build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        // refreshGoalState stubs
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        agent.execute(ctx);

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
        verify(similarityService, never()).isDuplicate(anyString());

        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void execute_whenPlanningThrottled_doesNotInjectGoals_butStillEvolvesGraphAndAttachesOutcome() {
        PlannerOutcome outcome = mock(PlannerOutcome.class);
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .build()
                .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.1, "test"));

        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // refreshGoalState stubs
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
        verify(similarityService, never()).isDuplicate(anyString());

        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
        verify(goalPlannerService, never()).sanitizeProposedSubGoals(anyList());
        assertThat(updated.plannerOutcome()).isSameAs(outcome);
        assertThat(updated.lastPlannerOutcome()).isSameAs(outcome);
    }

    @Test
    void execute_whenStagnating_createsRefinementSubgoalAndConsumesStagnationSignal() {
        String parentId = "goal-1";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getDescription()).thenReturn("Improve evaluation-planner integration");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(parentId)
                .build()
                .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "test"));

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(
                anyString(),
                contains("Refine goal: Improve evaluation-planner integration (auto-refinement from evaluation)"),
                eq(true),
                eq(parentId)
        );

        assertThat(updated.hasSignal(BasicSignals.STAGNATION)).isFalse();
    }

    @Test
    void execute_whenGoalProgressStagnating_autoRefinesEvenWithoutStagnationSignal() {
        String parentId = "goal-1";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getDescription()).thenReturn("Tighten planner throttling");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(parentId)
                .goalProgress(GoalProgressState.STAGNATING)
                .build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(
                anyString(),
                eq("Refine goal: Tighten planner throttling (auto-refinement from evaluation)"),
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
                .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "test"));

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(
                anyString(),
                eq("Refine goal: Make evaluation more precise (auto-refinement from evaluation)"),
                eq(true),
                eq(subId)
        );
    }

    @Test
    void execute_whenNoCurrentAndNoRemaining_injectsFallbackGoalState() {
        CycleContextData ctx = CycleContextData.builder()
                .evaluationFeedback("keep this")
                .recommendation("and this")
                .build();

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of()); // triggers fallback
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        CycleContextData updated = agent.execute(ctx);

        assertThat(updated.currentGoalId()).isEqualTo("bootstrap-project-structure");
        assertThat(updated.currentSubGoalId()).isEqualTo("create-basic-structure");
        assertThat(updated.evaluationFeedback()).isEqualTo("keep this");
        assertThat(updated.recommendation()).isEqualTo("and this");
    }

    @Test
    void planInto_withUnknownParentDoesNothing() {
        String parentId = "unknown";
        when(goalPlannerService.getGoalById(parentId)).thenReturn(null);

        agent.planInto(CycleContextData.builder().build(), parentId);

        verify(plannerAdapter, never()).plan(any());
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void planInto_whenThrottled_doesNotInjectButEvolvesGraph() {
        String parentId = "goal-parent";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getDescription()).thenReturn("Parent");

        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);
        when(goalPlannerService.ensureAttemptIdsFor(parentId))
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent"));

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
//        when(outcome.updated()).thenReturn(true);
//        when(outcome.newGoals()).thenReturn(List.of("X"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        CycleContextData throttledCtx = CycleContextData.builder()
                .build()
                .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.1, "test"));

        agent.planInto(throttledCtx, parentId);

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean(), anyString());
        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void planInto_withNullCtx_buildsPlanningContextWithAttemptIdsAndDescriptions() {
        String parentId = "goal-parent";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getDescription()).thenReturn("Parent desc");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        when(goalPlannerService.ensureAttemptIdsFor(parentId))
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent"));

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root desc");
        when(goalPlannerService.getRootGoal()).thenReturn(root);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        agent.planInto(null, parentId);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(plannerAdapter).plan(captor.capture());

        CycleContextData planningCtx = captor.getValue();
        assertThat(planningCtx.currentGoalId()).isEqualTo("root-1");
        assertThat(planningCtx.currentSubGoalId()).isEqualTo(parentId);
        assertThat(planningCtx.currentGoalDescription()).isEqualTo("Root desc");
        assertThat(planningCtx.currentSubGoalDescription()).isEqualTo("Parent desc");
        assertThat(planningCtx.lastAttemptId()).isEqualTo("attempt-1");
        assertThat(planningCtx.parentAttemptId()).isEqualTo("attempt-parent");
    }

    @Test
    void planInto_injectsNestedSubGoalsUnderParent_skipsDuplicates_andUsesSanitizedList() {
        String parentId = "goal-parent";

        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getDescription()).thenReturn("Parent description");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        when(goalPlannerService.ensureAttemptIdsFor(parentId))
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent"));

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-goal");
        when(root.getDescription()).thenReturn("Root goal");
        when(goalPlannerService.getRootGoal()).thenReturn(root);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Raw A", "Raw B", "Raw dup"));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        // sanitize filters/normalizes
        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("San A", "San dup", "San B"));

        when(similarityService.isDuplicate("San A")).thenReturn(false);
        when(similarityService.isDuplicate("San dup")).thenReturn(true);
        when(similarityService.isDuplicate("San B")).thenReturn(false);

        agent.planInto(CycleContextData.builder().build(), parentId);

        verify(goalPlannerService).addSubGoal(anyString(), eq("San A"), eq(true), eq(parentId));
        verify(goalPlannerService).addSubGoal(anyString(), eq("San B"), eq(false), eq(parentId));

        verify(goalPlannerService, never()).addSubGoal(anyString(), eq("San dup"), anyBoolean(), anyString());

        verify(plannerAdapter).evolveGraph(any(CycleContextData.class), eq(outcome));
    }

    @Test
    void planInto_withNonNullCtx_overwritesPlanningScopeFields() {
        String parentId = "goal-parent";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getDescription()).thenReturn("Parent desc");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        when(goalPlannerService.ensureAttemptIdsFor(parentId))
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent"));

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root desc");
        when(goalPlannerService.getRootGoal()).thenReturn(root);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // ctx contains "wrong" scope fields
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("WRONG")
                .currentGoalDescription("WRONG")
                .currentSubGoalId("WRONG")
                .currentSubGoalDescription("WRONG")
                .build();

        agent.planInto(ctx, parentId);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(plannerAdapter).plan(captor.capture());

        CycleContextData planningCtx = captor.getValue();
        assertThat(planningCtx.currentGoalId()).isEqualTo("root-1");
        assertThat(planningCtx.currentGoalDescription()).isEqualTo("Root desc");
        assertThat(planningCtx.currentSubGoalId()).isEqualTo(parentId);
        assertThat(planningCtx.currentSubGoalDescription()).isEqualTo("Parent desc");
        assertThat(planningCtx.lastAttemptId()).isEqualTo("attempt-1");
        assertThat(planningCtx.parentAttemptId()).isEqualTo("attempt-parent");
    }

    @Test
    void execute_whenPlanningThrottled_evolvesGraphWithRefreshedState() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentGoalDescription("root-desc-original")
                .build()
                .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.1, "test"));

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // refreshGoalState would change descriptions if root/current exist
        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("root-desc-refreshed");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData updated = agent.execute(ctx);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(plannerAdapter).evolveGraph(captor.capture(), eq(outcome));

        // should be the refreshed planning context (not original)
        assertThat(captor.getValue()).isSameAs(updated);
        assertThat(captor.getValue().currentGoalDescription()).isEqualTo("root-desc-refreshed");
    }

    @Test
    void planInto_preservesSignalsFromCtxWhileOverwritingScopeFields() {
        String parentId = "goal-parent";
        GoalEntity parent = mock(GoalEntity.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getDescription()).thenReturn("Parent desc");
        when(goalPlannerService.getGoalById(parentId)).thenReturn(parent);

        when(goalPlannerService.ensureAttemptIdsFor(parentId))
                .thenReturn(new AttemptIds("attempt-1", "attempt-parent"));

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root desc");
        when(goalPlannerService.getRootGoal()).thenReturn(root);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
//        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("WRONG")
                .build()
                .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 0.5, 0.1, "kept"));

        agent.planInto(ctx, parentId);

        ArgumentCaptor<CycleContextData> captor = ArgumentCaptor.forClass(CycleContextData.class);
        verify(plannerAdapter).plan(captor.capture());

        CycleContextData planningCtx = captor.getValue();
        assertThat(planningCtx.currentGoalId()).isEqualTo("root-1"); // overwritten
        assertThat(planningCtx.hasSignal(BasicSignals.PLANNING_THROTTLED)).isTrue(); // preserved
    }

    @Test
    void execute_whenNotStagnating_doesNotCreateRefinementSubgoal() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .goalProgress(GoalProgressState.PROGRESSING)
                .build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        agent.execute(ctx);

        verify(goalPlannerService, never()).addSubGoal(anyString(), contains("Refine goal:"), anyBoolean(), anyString());
    }
}