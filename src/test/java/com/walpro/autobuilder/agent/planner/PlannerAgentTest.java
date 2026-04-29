package com.walpro.autobuilder.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

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
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.model.MutationAcceptanceState;

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
        when(outcome.goalCriteria()).thenReturn(Map.of("First goal", List.of("Returns HTTP 201")));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("First goal", "Duplicate goal"));

        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(eq("First goal"), anyList())).thenReturn(false);
        when(similarityService.isDuplicate(eq("Duplicate goal"), anyList())).thenReturn(true);

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

        verify(similarityService).isDuplicate(eq("First goal"), anyList());
        verify(similarityService).isDuplicate(eq("Duplicate goal"), anyList());

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
        when(outcome.goalCriteria()).thenReturn(Map.of(
                "Nested 1", List.of("criterion 1"),
                "Nested 2", List.of("criterion 2")));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("Nested 1", "Nested 2"));

        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(anyString(), anyList())).thenReturn(false);

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
    void execute_whenThrottledButStrongReplan_injectsGoalsConsumesReplanKeepsThrottle() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .build()
                .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.0, "test"))
                .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.1, "recovery"));

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("New goal"));
        when(outcome.message()).thenReturn("replan");
        when(outcome.goalCriteria()).thenReturn(Map.of("New goal", List.of("criterion")));

        when(plannerAdapter.plan(any())).thenReturn(outcome);
        when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of("New goal"));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(eq("New goal"), anyList())).thenReturn(false);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData updated = agent.execute(ctx);

        // Goal was injected despite throttle
        verify(goalPlannerService).addSubGoal(anyString(), eq("New goal"), eq(true));
        // REPLAN_REQUESTED was consumed; PLANNING_THROTTLED remains (not cleared by override)
        assertThat(updated.hasSignal(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(updated.hasSignal(BasicSignals.PLANNING_THROTTLED)).isTrue();
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
    void planInto_whenThrottled_injectsGoalsIgnoringThrottle() {
        // planInto() is a lifecycle callback, not demand-driven planning.
        // PLANNING_THROTTLED must not block it — the throttle signal is stale at the point
        // planInto() is called (set at cycle start, before completions are applied).
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
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Integration tests"));
        when(outcome.goalCriteria()).thenReturn(Map.of("Integration tests", List.of("tests pass")));
        when(plannerAdapter.plan(any())).thenReturn(outcome);
        when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of("Integration tests"));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(eq("Integration tests"), anyList())).thenReturn(false);

        CycleContextData throttledCtx = CycleContextData.builder()
                .build()
                .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.0, "test"));

        agent.planInto(throttledCtx, parentId);

        verify(goalPlannerService).addSubGoal(anyString(), eq("Integration tests"), eq(true), eq(parentId));
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
        when(outcome.goalCriteria()).thenReturn(Map.of(
                "San A", List.of("criterion A"),
                "San B", List.of("criterion B")));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        // sanitize filters/normalizes
        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("San A", "San dup", "San B"));

        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(eq("San A"), anyList())).thenReturn(false);
        when(similarityService.isDuplicate(eq("San dup"), anyList())).thenReturn(true);
        when(similarityService.isDuplicate(eq("San B"), anyList())).thenReturn(false);

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
    void execute_whenScopeOutOfScope_andBestMatchPresent_activatesSubGoalAndClearsSignal() {
        String currentSubId = "sub-current";
        String bestMatchId = "sub-best";

        GoalEntity bestMatch = mock(GoalEntity.class);
        when(bestMatch.getId()).thenReturn(bestMatchId);
        when(bestMatch.getDescription()).thenReturn("Best match description");

        when(goalPlannerService.activateSubGoal(bestMatchId, "planner: scope repair")).thenReturn(true);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(bestMatch);
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId(currentSubId)
                .build()
                .withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "guard"))
                .withBestMatchSubGoalId(bestMatchId);

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService).activateSubGoal(bestMatchId, "planner: scope repair");
        assertThat(updated.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).isFalse();
        assertThat(updated.bestMatchSubGoalId()).isNull();
        assertThat(updated.currentSubGoalId()).isEqualTo(bestMatchId);
        assertThat(updated.currentSubGoalDescription()).isEqualTo("Best match description");
    }

    @Test
    void execute_whenScopeOutOfScope_andActivationFails_clearsSignalWithoutSwitchingId() {
        String currentSubId = "sub-current";
        String bestMatchId = "sub-best";

        when(goalPlannerService.activateSubGoal(bestMatchId, "planner: scope repair")).thenReturn(false);
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")  // needed to bypass ensureFallbackGoalState
                .currentSubGoalId(currentSubId)
                .build()
                .withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "guard"))
                .withBestMatchSubGoalId(bestMatchId);

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService).activateSubGoal(bestMatchId, "planner: scope repair");
        assertThat(updated.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).isFalse();
        assertThat(updated.bestMatchSubGoalId()).isNull();
        // id not changed because activation failed
        assertThat(updated.currentSubGoalId()).isEqualTo(currentSubId);
    }

    @Test
    void execute_whenScopeOutOfScope_andNoBestMatch_clearsSignalWithoutActivating() {
        String currentSubId = "sub-current";

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(false);
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId(currentSubId)
                .build()
                .withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "guard"));
        // bestMatchSubGoalId is null (not set)

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService, never()).activateSubGoal(anyString(), anyString());
        assertThat(updated.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)).isFalse();
        assertThat(updated.bestMatchSubGoalId()).isNull();
    }

    @Test
    void execute_whenOutcomeHasGoalsToRemove_marksMatchingGoalsGivenUp() {
        GoalEntity toRemove = mock(GoalEntity.class);
        when(toRemove.getId()).thenReturn("sub-obsolete");
        when(toRemove.getDescription()).thenReturn("Obsolete goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Obsolete goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // First call: before removal; second call: after marking given up the current is no longer given up
        GoalEntity currentAfterRemoval = mock(GoalEntity.class);
        when(currentAfterRemoval.getStatus()).thenReturn(com.walpro.autobuilder.goal.GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentAfterRemoval);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(toRemove));
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-obsolete")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-obsolete"), anyString());
    }

    @Test
    void execute_whenGoalToRemoveIsId_marksMatchingGoalGivenUpById() {
        GoalEntity toRemove = mock(GoalEntity.class);
        when(toRemove.getId()).thenReturn("goal-abc-123");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("goal-abc-123")); // ID, not description
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity currentAfterRemoval = mock(GoalEntity.class);
        when(currentAfterRemoval.getStatus()).thenReturn(com.walpro.autobuilder.goal.GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(currentAfterRemoval);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(toRemove));
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("goal-abc-123"), anyString());
    }

    @Test
    void execute_whenGoalToRemoveNotFound_logsWarnAndSkips() {
        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Nonexistent goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity unrelated = mock(GoalEntity.class);
        when(unrelated.getDescription()).thenReturn("Something else");

        GoalEntity current = mock(GoalEntity.class);
        when(current.getStatus()).thenReturn(com.walpro.autobuilder.goal.GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(unrelated));
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService, never()).markGoalGivenUp(anyString(), any());
    }

    @Test
    void execute_doesNotReInjectGivenUpGoalThatHadMerges() {
        // T1-BT: a GIVEN_UP goal with mergeCount > 0 was pruned after successful work.
        // It is excluded from snapshotKnownDescriptions() (which skips GIVEN_UP), so the
        // standard isDuplicate check misses it. buildKnownDescriptions() adds it back so the
        // re-injection is blocked by the isDuplicate guard.
        GoalEntity prunedWithMerge = new GoalEntity("pruned-1", "Implement POST /books", "role", GoalType.SUBGOAL, "root-1");
        prunedWithMerge.getMetadata().put("mergeCount", 1);
        prunedWithMerge.setStatus(GoalStatus.GIVEN_UP);

        GoalEntity root = new GoalEntity("root-1", "root goal", "role", GoalType.GOAL, null);

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Implement POST /books"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAllGoals()).thenReturn(List.of(root, prunedWithMerge));
        when(goalPlannerService.sanitizeProposedSubGoals(any())).thenAnswer(inv -> inv.getArgument(0));
        // snapshotKnownDescriptions returns nothing (GIVEN_UP excluded from graph query)
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        // isDuplicate returns true when the pruned description is in the known list
        when(similarityService.isDuplicate(eq("Implement POST /books"), argThat(list -> list.contains("Implement POST /books"))))
                .thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService, never()).addSubGoal(anyString(), eq("Implement POST /books"), anyBoolean());
    }

    @Test
    void execute_whenRemovedGoalWasCurrent_advancesToNextActiveSubgoal() {
        GoalEntity givenUpGoal = mock(GoalEntity.class);
        when(givenUpGoal.getId()).thenReturn("sub-current");
        when(givenUpGoal.getDescription()).thenReturn("Current goal");
        when(givenUpGoal.getStatus()).thenReturn(com.walpro.autobuilder.goal.GoalStatus.GIVEN_UP);

        GoalEntity nextGoal = mock(GoalEntity.class);
        when(nextGoal.getId()).thenReturn("sub-next");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Current goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(givenUpGoal))  // before removal (for matching)
                .thenReturn(List.of(nextGoal));      // after removal (for advancement)
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUpGoal);
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-current")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-current"), anyString());
        verify(goalPlannerService).setCurrentSubGoal("sub-next");
    }

    @Test
    void execute_whenRemovalAdvancesSubgoal_clearsStaleSubgoalExhausted() {
        // T1-BV: SUBGOAL_EXHAUSTED was raised for "sub-old". The planner removes it,
        // advancing to "sub-next". The stale signal must be cleared so the new subgoal
        // is not immediately given up by deriveCycleResult.
        GoalEntity givenUpGoal = mock(GoalEntity.class);
        when(givenUpGoal.getId()).thenReturn("sub-old");
        when(givenUpGoal.getDescription()).thenReturn("Old goal");
        when(givenUpGoal.getStatus()).thenReturn(GoalStatus.GIVEN_UP);

        GoalEntity nextGoal = mock(GoalEntity.class);
        when(nextGoal.getId()).thenReturn("sub-next");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Old goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // First call: inside processRemovals (for matching) → givenUpGoal still listed
        // Second call: inside processRemovals (advancement stream) → nextGoal
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(givenUpGoal))
                .thenReturn(List.of(nextGoal));

        // First call: processRemovals GIVEN_UP check → givenUpGoal
        // Subsequent calls (our new fix + refreshGoalState) → nextGoal
        when(goalPlannerService.getCurrentSubGoal())
                .thenReturn(givenUpGoal)
                .thenReturn(nextGoal);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-old")
                .build()
                .withSignal(Signal.of(BasicSignals.SUBGOAL_EXHAUSTED, 0.8, 0.05, "exhaustion-detector"));

        CycleContextData updated = agent.execute(ctx);

        assertThat(updated.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void execute_whenRemovalAdvancesSubgoal_clearsStaleStagnation() {
        // T1-BX: STAGNATION was raised for "sub-old". The planner removes it,
        // advancing to "sub-next". The stale signal must be cleared so Rule C in
        // GoalExhaustionDetector does not fire immediately on the new subgoal.
        GoalEntity givenUpGoal = mock(GoalEntity.class);
        when(givenUpGoal.getId()).thenReturn("sub-old");
        when(givenUpGoal.getDescription()).thenReturn("Old goal");
        when(givenUpGoal.getStatus()).thenReturn(GoalStatus.GIVEN_UP);

        GoalEntity nextGoal = mock(GoalEntity.class);
        when(nextGoal.getId()).thenReturn("sub-next");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Old goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // First call: inside processRemovals (for matching) → givenUpGoal still listed
        // Second call: inside processRemovals (advancement stream) → nextGoal
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(givenUpGoal))
                .thenReturn(List.of(nextGoal));

        // First call: processRemovals GIVEN_UP check → givenUpGoal
        // Subsequent calls (our new fix + refreshGoalState) → nextGoal
        when(goalPlannerService.getCurrentSubGoal())
                .thenReturn(givenUpGoal)
                .thenReturn(nextGoal);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-old")
                .build()
                .withSignal(Signal.of(BasicSignals.STAGNATION, 0.8, 0.05, "exhaustion-detector"));

        CycleContextData updated = agent.execute(ctx);

        assertThat(updated.hasSignal(BasicSignals.STAGNATION)).isFalse();
    }

    // ── T2-BU: subgoals without criteria are skipped ──────────────────────────

    @Test
    void execute_skipsSubgoalWithNoCriteria_doesNotAddToGoalStore() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .build();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Subgoal with criteria", "Subgoal no criteria"));
        when(outcome.message()).thenReturn("msg");
        // only first goal has criteria
        when(outcome.goalCriteria()).thenReturn(Map.of("Subgoal with criteria", List.of("Returns HTTP 200")));

        when(plannerAdapter.plan(any())).thenReturn(outcome);
        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("Subgoal with criteria", "Subgoal no criteria"));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(anyString(), anyList())).thenReturn(false);

        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        agent.execute(ctx);

        verify(goalPlannerService).addSubGoal(anyString(), eq("Subgoal with criteria"), eq(true));
        verify(goalPlannerService, never()).addSubGoal(anyString(), eq("Subgoal no criteria"), anyBoolean());
    }

    @Test
    void execute_whenAllGoalsGivenUpAfterRemoval_injectsUnderDeadParentNotAtRoot() {
        // T1-CP: planner removes the only remaining subgoal → no next sibling → the
        // currentSubGoalId pointer stays on the just-pruned GIVEN_UP goal.  New goals
        // proposed in the same response must be nested under the dead parent (preserving
        // root completion ratio), NOT injected at root level.

        GoalEntity givenUpGoal = mock(GoalEntity.class);
        when(givenUpGoal.getId()).thenReturn("sub-pruned");
        when(givenUpGoal.getDescription()).thenReturn("Stagnant subgoal");
        when(givenUpGoal.getStatus()).thenReturn(GoalStatus.GIVEN_UP);

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Stagnant subgoal"));
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Fresh replacement"));
        when(outcome.goalCriteria()).thenReturn(Map.of("Fresh replacement", List.of("GET /items returns 200")));
        when(outcome.message()).thenReturn("Replaced stagnant goal");
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        // first call: processRemovals match loop; second: advancement stream (empty → all given up);
        // third: remainingCount check after removals
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(givenUpGoal))
                .thenReturn(List.of())
                .thenReturn(List.of());

        // getCurrentSubGoal: pointer is NOT advanced because there is no next sibling,
        // so it still returns the GIVEN_UP goal after removal
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUpGoal);

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of("Fresh replacement"));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(anyString(), anyList())).thenReturn(false);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-pruned")
                .build();

        agent.execute(ctx);

        // T1-CP: must inject under the GIVEN_UP parent (4-arg), never at root level (3-arg)
        verify(goalPlannerService).addSubGoal(anyString(), eq("Fresh replacement"), eq(true), eq("sub-pruned"));
        verify(goalPlannerService, never()).addSubGoal(anyString(), eq("Fresh replacement"), anyBoolean());
    }

    @Test
    void planInto_skipsSubgoalWithNoCriteria_doesNotAddToGoalStore() {
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
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Has criteria", "No criteria"));
        when(outcome.goalCriteria()).thenReturn(Map.of("Has criteria", List.of("* GET /items returns 200")));

        when(plannerAdapter.plan(any())).thenReturn(outcome);
        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of("Has criteria", "No criteria"));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(anyString(), anyList())).thenReturn(false);

        agent.planInto(CycleContextData.builder().build(), parentId);

        verify(goalPlannerService).addSubGoal(anyString(), eq("Has criteria"), eq(true), eq(parentId));
        verify(goalPlannerService, never()).addSubGoal(anyString(), eq("No criteria"), anyBoolean(), anyString());
    }

    // --- T1-CH: evolution fingerprints cleared on T1-CA root injection ---

    @Test
    void execute_T1CA_clearsEvolutionFingerprintsWhenInjectingAtRoot() {
        // T1-CH: when T1-CA fires (backlog empty + current subgoal GIVEN_UP → inject at root),
        // the returned context must have null lastEvolutionFingerprint and
        // lastEvaluatedEvolutionFingerprint so the scope guard does not compare new subgoal
        // descriptions against the dead subgoal's stale diff.

        GoalEntity givenUpGoal = mock(GoalEntity.class);
        when(givenUpGoal.getId()).thenReturn("sub-pruned");
        when(givenUpGoal.getDescription()).thenReturn("Stagnant subgoal");
        when(givenUpGoal.getStatus()).thenReturn(GoalStatus.GIVEN_UP);

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Stagnant subgoal"));
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of("Fresh replacement"));
        when(outcome.goalCriteria()).thenReturn(Map.of("Fresh replacement", List.of("GET /items returns 200")));
        when(outcome.message()).thenReturn("Replaced stagnant goal");
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(givenUpGoal))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUpGoal);

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of("Fresh replacement"));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        when(similarityService.isDuplicate(anyString(), anyList())).thenReturn(false);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-pruned")
                .lastEvolutionFingerprint("stale-fp")
                .lastEvaluatedEvolutionFingerprint("stale-eval-fp")
                .build();

        CycleContextData updated = agent.execute(ctx);

        assertThat(updated.lastEvolutionFingerprint()).isNull();
        assertThat(updated.lastEvaluatedEvolutionFingerprint()).isNull();
    }

    @Test
    void execute_whenBacklogNotEmpty_doesNotClearEvolutionFingerprints() {
        // T1-CH guard: fingerprints must be preserved when T1-CA does NOT fire
        // (backlog is non-empty → normal injection path, not root injection).

        GoalEntity activeGoal = mock(GoalEntity.class);
        when(activeGoal.getId()).thenReturn("sub-active");
        when(activeGoal.getDescription()).thenReturn("Active subgoal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of());
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(activeGoal));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeGoal);

        GoalEntity root = mock(GoalEntity.class);
        when(root.getId()).thenReturn("root-1");
        when(root.getDescription()).thenReturn("Root");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-active")
                .lastEvolutionFingerprint("live-fp")
                .lastEvaluatedEvolutionFingerprint("live-eval-fp")
                .build();

        CycleContextData updated = agent.execute(ctx);

        assertThat(updated.lastEvolutionFingerprint()).isEqualTo("live-fp");
        assertThat(updated.lastEvaluatedEvolutionFingerprint()).isEqualTo("live-eval-fp");
    }

    // T1-DC: processRemovals guard — refuse prune when an evolution this cycle has not yet
    // been evaluated (residual case; T1-DA's accepted-but-unmerged guard removed in T1-DC).

    @Test
    void processRemovals_refusesPruneWhenEvolutionUnevaluated() {
        GoalEntity target = mock(GoalEntity.class);
        when(target.getId()).thenReturn("sub-target");
        when(target.getDescription()).thenReturn("Target goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Target goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity current = mock(GoalEntity.class);
        when(current.getStatus()).thenReturn(GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(target));
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        // evolution produced fp-new this cycle; evaluation has not yet caught up to it.
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-target")
                .lastEvolutionFingerprint("fp-new")
                .lastEvaluatedEvolutionFingerprint("fp-old")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService, never()).markGoalGivenUp(eq("sub-target"), anyString());
    }

    @Test
    void processRemovals_allowsPruneWhenFingerprintsMatch() {
        // When fingerprints match, evaluation has caught up — pruning is safe even with
        // ACCEPTED + !merged because EvaluationAgent's ROLE_MERGE signal will run merge
        // before the planner is consulted (and merge gates on its own state).
        GoalEntity target = mock(GoalEntity.class);
        when(target.getId()).thenReturn("sub-target");
        when(target.getDescription()).thenReturn("Target goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Target goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity givenUp = mock(GoalEntity.class);
        when(givenUp.getStatus()).thenReturn(GoalStatus.GIVEN_UP);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUp);
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(target))
                .thenReturn(List.of());
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-target")
                .lastEvolutionFingerprint("fp-same")
                .lastEvaluatedEvolutionFingerprint("fp-same")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .merged(false)
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-target"), anyString());
    }

    @Test
    void processRemovals_allowsPruneWhenNoEvolutionFingerprint() {
        // No evolution this cycle (planner-only or first-ever cycle): nothing to lose, allow prune.
        GoalEntity target = mock(GoalEntity.class);
        when(target.getId()).thenReturn("sub-target");
        when(target.getDescription()).thenReturn("Target goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Target goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity givenUp = mock(GoalEntity.class);
        when(givenUp.getStatus()).thenReturn(GoalStatus.GIVEN_UP);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUp);
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(target))
                .thenReturn(List.of());
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-target")
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-target"), anyString());
    }

    @Test
    void processRemovals_allowsPruneWhenMutationStateRejected() {
        GoalEntity target = mock(GoalEntity.class);
        when(target.getId()).thenReturn("sub-target");
        when(target.getDescription()).thenReturn("Target goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Target goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity givenUp = mock(GoalEntity.class);
        when(givenUp.getStatus()).thenReturn(GoalStatus.GIVEN_UP);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUp);
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(target))
                .thenReturn(List.of());
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-target")
                .mutationState(MutationAcceptanceState.REJECTED)
                .merged(false)
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-target"), anyString());
    }

    @Test
    void processRemovals_allowsPruneWhenAlreadyMerged() {
        GoalEntity target = mock(GoalEntity.class);
        when(target.getId()).thenReturn("sub-target");
        when(target.getDescription()).thenReturn("Target goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Target goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity givenUp = mock(GoalEntity.class);
        when(givenUp.getStatus()).thenReturn(GoalStatus.GIVEN_UP);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUp);
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(target))
                .thenReturn(List.of());
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-target")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .merged(true)
                .build();

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-target"), anyString());
    }

    @Test
    void processRemovals_allowsPruneWhenEvolutionNoOp() {
        GoalEntity target = mock(GoalEntity.class);
        when(target.getId()).thenReturn("sub-target");
        when(target.getDescription()).thenReturn("Target goal");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.goalsToRemove()).thenReturn(List.of("Target goal"));
        when(plannerAdapter.plan(any())).thenReturn(outcome);

        GoalEntity givenUp = mock(GoalEntity.class);
        when(givenUp.getStatus()).thenReturn(GoalStatus.GIVEN_UP);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(givenUp);
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(target))
                .thenReturn(List.of());
        when(goalPlannerService.getRootGoal()).thenReturn(null);
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-target")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .merged(false)
                .build()
                .withSignal(Signal.of(BasicSignals.EVOLUTION_NOOP, 1.0, 0.1, "test"));

        agent.execute(ctx);

        verify(goalPlannerService).markGoalGivenUp(eq("sub-target"), anyString());
    }

}