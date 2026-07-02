package com.walpro.autobuilder.controller.goal;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.AutobuilderService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalExplorerServiceTest {

    private GoalPlannerService planner;
    private GoalSimilarityService similarity;
    private OrchestrationSnapshotService snapshot;
    private GoalExplorerService service;

    @BeforeEach
    void setUp() {
        planner = mock(GoalPlannerService.class);
        similarity = mock(GoalSimilarityService.class);
        snapshot = mock(OrchestrationSnapshotService.class);
        service = new GoalExplorerService(planner, similarity);
        service.setSnapshotService(snapshot);
    }

    @Test
    void getGoalTree_returnsNullWhenNoRoot() {
        when(planner.getRootGoal()).thenReturn(null);
        assertThat(service.getGoalTree()).isNull();
    }

    @Test
    void getGoalTree_buildsRecursiveNodesWithMetadata() {
        GoalEntity root = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        GoalEntity a = goal("a", "Step A", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        GoalEntity b = goal("b", "Step B", GoalStatus.COMPLETED, GoalType.SUBGOAL, "root");

        a.getMetadata().put("acceptanceCriteria", List.of("crit-1", "crit-2"));
        a.getMetadata().put("mergeCount", 3);
        Map<String, Boolean> aPass = new LinkedHashMap<>();
        aPass.put("1", true);
        aPass.put("2", false);
        a.getMetadata().put("criteriaPassStatus", aPass);

        when(planner.getRootGoal()).thenReturn(root);
        when(planner.peekCurrentSubGoal()).thenReturn(a);
        when(planner.getSubGoals("root")).thenReturn(List.of(a, b));
        when(planner.getSubGoals("a")).thenReturn(List.of());
        when(planner.getSubGoals("b")).thenReturn(List.of());
        when(planner.getCriteriaPassStatus(any(GoalEntity.class))).thenAnswer(inv -> {
            GoalEntity g = inv.getArgument(0);
            Object raw = g.getMetadata().get("criteriaPassStatus");
            Map<Integer, Boolean> out = new HashMap<>();
            if (raw instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(Integer.parseInt(String.valueOf(e.getKey())), (Boolean) e.getValue());
                }
            }
            return out;
        });
        when(planner.getGoalMetadataInt(any(GoalEntity.class), any(String.class))).thenAnswer(inv -> {
            GoalEntity g = inv.getArgument(0);
            String k = inv.getArgument(1);
            Object val = g.getMetadata().get(k);
            return val instanceof Number n ? n.intValue() : 0;
        });

        GoalTreeNodeDto tree = service.getGoalTree();

        assertThat(tree.id()).isEqualTo("root");
        assertThat(tree.subGoals()).hasSize(2);
        GoalTreeNodeDto aDto = tree.subGoals().get(0);
        assertThat(aDto.id()).isEqualTo("a");
        assertThat(aDto.isCurrent()).isTrue();
        assertThat(aDto.criteriaCount()).isEqualTo(2);
        assertThat(aDto.criteriaMetCount()).isEqualTo(1);
        assertThat(aDto.mergeCount()).isEqualTo(3);
        GoalTreeNodeDto bDto = tree.subGoals().get(1);
        assertThat(bDto.isCurrent()).isFalse();
        assertThat(bDto.status()).isEqualTo("COMPLETED");
    }

    @Test
    void getGoalDetail_returnsNullForMissingGoal() {
        when(planner.getGoalById("missing")).thenReturn(null);
        assertThat(service.getGoalDetail("missing")).isNull();
    }

    @Test
    void getGoalDetail_mergesGraphProgressWithMetadata() {
        GoalEntity g = goal("sub-1", "Add endpoint", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        g.getMetadata().put("acceptanceCriteria", List.of("Endpoint returns 200", "Endpoint documented"));
        g.getMetadata().put("mergedFiles", List.of("FooController.java"));
        Map<String, Boolean> pass = new LinkedHashMap<>();
        pass.put("1", true);
        pass.put("2", false);
        g.getMetadata().put("criteriaPassStatus", pass);
        Map<String, Integer> streak = new LinkedHashMap<>();
        streak.put("2", 3);
        g.getMetadata().put("criteriaFailStreak", streak);
        g.getMetadata().put("planRationale", "break down endpoint work");

        GoalEntity parent = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        when(planner.getGoalById("sub-1")).thenReturn(g);
        when(planner.getGoalById("root")).thenReturn(parent);
        when(planner.peekCurrentSubGoal()).thenReturn(g);
        when(planner.getCriteriaPassStatus(g)).thenReturn(Map.of(1, true, 2, false));
        when(planner.getCriteriaFailStreak(g)).thenReturn(Map.of(2, 3));
        when(planner.getGoalMetadataInt(any(GoalEntity.class), any(String.class))).thenReturn(0);
        when(planner.getPlanRationale(g)).thenReturn("break down endpoint work");

        when(snapshot.fetchCriteriaProgress("sub-1")).thenReturn(List.of(
                new OrchestrationSnapshot.CriterionProgress(1, "Endpoint returns 200", false, true, true, 0),
                new OrchestrationSnapshot.CriterionProgress(2, "Endpoint documented", false, false, false, 3)
        ));

        GoalNodeDetailDto detail = service.getGoalDetail("sub-1");

        assertThat(detail.id()).isEqualTo("sub-1");
        assertThat(detail.isCurrent()).isTrue();
        assertThat(detail.mergedFiles()).containsExactly("FooController.java");
        assertThat(detail.ancestry()).containsExactly("root");
        assertThat(detail.planRationale()).isEqualTo("break down endpoint work");
        assertThat(detail.criteria()).hasSize(2);

        CriterionStatusDto c1 = detail.criteria().get(0);
        assertThat(c1.index()).isEqualTo(1);
        assertThat(c1.cumulativePassed()).isTrue();
        assertThat(c1.lastPassed()).isTrue();
        assertThat(c1.everPassed()).isTrue();

        CriterionStatusDto c2 = detail.criteria().get(1);
        assertThat(c2.cumulativePassed()).isFalse();
        assertThat(c2.failStreak()).isEqualTo(3);
        assertThat(c2.consecutiveFailCount()).isEqualTo(3);
        assertThat(c2.everPassed()).isFalse();
    }

    @Test
    void getGoalDetail_falsBackGracefullyWhenSnapshotUnavailable() {
        service.setSnapshotService(null);
        GoalEntity g = goal("sub-1", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, null);
        g.getMetadata().put("acceptanceCriteria", List.of("c1"));
        when(planner.getGoalById("sub-1")).thenReturn(g);
        when(planner.peekCurrentSubGoal()).thenReturn(null);
        when(planner.getCriteriaPassStatus(g)).thenReturn(Map.of(1, false));
        when(planner.getCriteriaFailStreak(g)).thenReturn(Map.of());
        when(planner.getGoalMetadataInt(any(GoalEntity.class), any(String.class))).thenReturn(0);

        GoalNodeDetailDto detail = service.getGoalDetail("sub-1");

        assertThat(detail.criteria()).hasSize(1);
        CriterionStatusDto c = detail.criteria().get(0);
        assertThat(c.neverAssessed()).isTrue();
        assertThat(c.lastPassed()).isNull();
    }

    @Test
    void findSynergies_returnsCrossGoalCriteriaAboveThreshold() {
        GoalEntity a = goal("a", "Build auth", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        GoalEntity b = goal("b", "Harden API", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        GoalEntity c = goal("c", "Unrelated work", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");

        a.getMetadata().put("acceptanceCriteria", List.of("JWT token validated on each request"));
        b.getMetadata().put("acceptanceCriteria", List.of("Each API call validates a JWT token",
                                                          "Rate-limit exceeded returns 429"));
        c.getMetadata().put("acceptanceCriteria", List.of("Analytics dashboard renders"));

        when(planner.getGoalById("a")).thenReturn(a);
        when(planner.getAllGoals()).thenReturn(List.of(a, b, c));
        // Mock similarity — high for the JWT pair, low for others.
        when(similarity.similarity("JWT token validated on each request",
                                   "Each API call validates a JWT token")).thenReturn(0.82f);
        when(similarity.similarity("JWT token validated on each request",
                                   "Rate-limit exceeded returns 429")).thenReturn(0.15f);
        when(similarity.similarity("JWT token validated on each request",
                                   "Analytics dashboard renders")).thenReturn(0.05f);

        List<SynergyCandidateDto> synergies = service.findSynergies("a");

        assertThat(synergies).hasSize(1);
        SynergyCandidateDto s = synergies.get(0);
        assertThat(s.sourceGoalId()).isEqualTo("a");
        assertThat(s.sourceCriterionIndex()).isEqualTo(1);
        assertThat(s.targetGoalId()).isEqualTo("b");
        assertThat(s.targetCriterionIndex()).isEqualTo(1);
        assertThat(s.similarity()).isCloseTo(0.82d, org.assertj.core.data.Offset.offset(0.001d));
    }

    @Test
    void findSynergies_returnsEmptyForGoalWithNoCriteria() {
        GoalEntity a = goal("a", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("a")).thenReturn(a);
        assertThat(service.findSynergies("a")).isEmpty();
    }

    @Test
    void getFileOverlay_returnsOnlyFilesTouchedByMultipleGoals() {
        GoalEntity a = goal("a", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        GoalEntity b = goal("b", "y", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        GoalEntity c = goal("c", "z", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        a.getMetadata().put("mergedFiles", List.of("Foo.java", "Bar.java"));
        b.getMetadata().put("mergedFiles", List.of("Foo.java"));
        c.getMetadata().put("mergedFiles", List.of("Baz.java"));

        when(planner.getAllGoals()).thenReturn(List.of(a, b, c));

        List<FileOverlayDto> overlay = service.getFileOverlay();

        assertThat(overlay).hasSize(1);
        assertThat(overlay.get(0).fileName()).isEqualTo("Foo.java");
        assertThat(overlay.get(0).goalIds()).containsExactlyInAnyOrder("a", "b");
    }

    // =======================================================================================
    // T4-AM: write actions
    // =======================================================================================

    @Test
    void markCriterionMet_banksStatusAndClearsStreak() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        g.getMetadata().put("acceptanceCriteria", List.of("c1", "c2"));
        Map<Integer, Boolean> existingPass = new LinkedHashMap<>();
        existingPass.put(1, false);
        existingPass.put(2, false);
        Map<Integer, Integer> existingStreak = new LinkedHashMap<>();
        existingStreak.put(1, 3);
        existingStreak.put(2, 2);

        when(planner.getGoalById("sub")).thenReturn(g);
        when(planner.getCriteriaPassStatus(g)).thenReturn(existingPass);
        when(planner.getCriteriaFailStreak(g)).thenReturn(existingStreak);

        ActionResultDto r = service.markCriterionMet("sub", 1, "manual");

        assertThat(r.ok()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, Boolean>> passCap = ArgumentCaptor.forClass(Map.class);
        verify(planner).saveCriteriaPassStatus(eq("sub"), passCap.capture());
        assertThat(passCap.getValue()).containsEntry(1, true).containsEntry(2, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, Integer>> streakCap = ArgumentCaptor.forClass(Map.class);
        verify(planner).saveCriteriaFailStreak(eq("sub"), streakCap.capture());
        assertThat(streakCap.getValue()).doesNotContainKey(1).containsEntry(2, 2);
    }

    @Test
    void markCriterionMet_rejectsOutOfRangeIndex() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        g.getMetadata().put("acceptanceCriteria", List.of("only-crit"));
        when(planner.getGoalById("sub")).thenReturn(g);
        when(planner.getCriteriaPassStatus(g)).thenReturn(Map.of());
        when(planner.getCriteriaFailStreak(g)).thenReturn(Map.of());

        ActionResultDto r = service.markCriterionMet("sub", 5, null);

        assertThat(r.ok()).isFalse();
        verify(planner, never()).saveCriteriaPassStatus(anyString(), any());
    }

    @Test
    void markCriterionMet_returnsFailWhenGoalMissing() {
        when(planner.getGoalById("missing")).thenReturn(null);
        ActionResultDto r = service.markCriterionMet("missing", 1, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("not found");
    }

    @Test
    void pinAsPriority_activatesAndEmitsReplanSignal() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("sub")).thenReturn(g);
        when(planner.activateSubGoal("sub", "manual")).thenReturn(true);

        AutobuilderService autobuilder = mock(AutobuilderService.class);
        service.setAutobuilderService(autobuilder);

        ActionResultDto r = service.pinAsPriority("sub", "manual");

        assertThat(r.ok()).isTrue();
        assertThat(r.goalId()).isEqualTo("sub");
        verify(planner).activateSubGoal("sub", "manual");
        ArgumentCaptor<Signal> sigCap = ArgumentCaptor.forClass(Signal.class);
        verify(autobuilder).injectSignal(sigCap.capture());
        assertThat(sigCap.getValue().type()).isEqualTo(BasicSignals.REPLAN_REQUESTED);
        assertThat(sigCap.getValue().strength()).isEqualTo(1.0);
    }

    @Test
    void pinAsPriority_rejectsNonSubgoalNode() {
        GoalEntity root = goal("root", "root", GoalStatus.ACTIVE, GoalType.GOAL, null);
        when(planner.getGoalById("root")).thenReturn(root);
        ActionResultDto r = service.pinAsPriority("root", null);
        assertThat(r.ok()).isFalse();
        verify(planner, never()).activateSubGoal(anyString(), anyString());
    }

    @Test
    void pinAsPriority_failsWhenActivateReturnsFalse() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("sub")).thenReturn(g);
        when(planner.activateSubGoal(eq("sub"), anyString())).thenReturn(false);
        ActionResultDto r = service.pinAsPriority("sub", null);
        assertThat(r.ok()).isFalse();
    }

    @Test
    void injectSubgoal_createsChildAndOptionallyPinsCurrent() {
        GoalEntity parent = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        when(planner.getGoalById("root")).thenReturn(parent);
        AutobuilderService autobuilder = mock(AutobuilderService.class);
        service.setAutobuilderService(autobuilder);

        GoalInjectSubgoalRequest req = new GoalInjectSubgoalRequest(
                "Add /health endpoint", true, List.of("returns 200", "JSON body"), null
        );
        ActionResultDto r = service.injectSubgoal("root", req);

        assertThat(r.ok()).isTrue();
        assertThat(r.goalId()).startsWith("user-");

        ArgumentCaptor<String> idCap = ArgumentCaptor.forClass(String.class);
        verify(planner).addSubGoal(idCap.capture(), eq("Add /health endpoint"), eq(true),
                eq("root"), eq("user"), any());
        String createdId = idCap.getValue();
        verify(planner).setAcceptanceCriteria(eq(createdId),
                eq(List.of("returns 200", "JSON body")));
        // setAsCurrent=true → REPLAN_REQUESTED injected
        ArgumentCaptor<Signal> sigCap = ArgumentCaptor.forClass(Signal.class);
        verify(autobuilder).injectSignal(sigCap.capture());
        assertThat(sigCap.getValue().type()).isEqualTo(BasicSignals.REPLAN_REQUESTED);
    }

    @Test
    void injectSubgoal_noSignalWhenNotSetAsCurrent() {
        GoalEntity parent = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        when(planner.getGoalById("root")).thenReturn(parent);
        AutobuilderService autobuilder = mock(AutobuilderService.class);
        service.setAutobuilderService(autobuilder);

        ActionResultDto r = service.injectSubgoal("root", new GoalInjectSubgoalRequest(
                "work item", false, List.of(), "custom-role"
        ));

        assertThat(r.ok()).isTrue();
        verify(planner).addSubGoal(anyString(), eq("work item"), eq(false),
                eq("root"), eq("custom-role"), any());
        verify(autobuilder, never()).injectSignal(any());
    }

    @Test
    void injectSubgoal_rejectsBlankDescription() {
        ActionResultDto r = service.injectSubgoal("root", new GoalInjectSubgoalRequest("  ", false, null, null));
        assertThat(r.ok()).isFalse();
        verify(planner, never()).addSubGoal(anyString(), anyString(), anyBoolean(),
                anyString(), anyString(), any());
    }

    @Test
    void injectSubgoal_failsWhenParentMissing() {
        when(planner.getGoalById("nope")).thenReturn(null);
        ActionResultDto r = service.injectSubgoal("nope",
                new GoalInjectSubgoalRequest("work", false, null, null));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("Parent");
    }

    @Test
    void forceGivenUp_appliesGivenUpProgress() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("sub")).thenReturn(g);

        ActionResultDto r = service.forceGivenUp("sub", "no longer needed");

        assertThat(r.ok()).isTrue();
        verify(planner).applyGoalProgress("sub", GoalProgressState.GIVEN_UP, "no longer needed");
    }

    @Test
    void forceGivenUp_fallsBackToDefaultResolution() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("sub")).thenReturn(g);

        service.forceGivenUp("sub", null);

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(planner).applyGoalProgress(eq("sub"), eq(GoalProgressState.GIVEN_UP), reasonCap.capture());
        assertThat(reasonCap.getValue()).contains("user-initiated");
    }

    @Test
    void forceGivenUp_rejectsAlreadyGivenUp() {
        GoalEntity g = goal("sub", "x", GoalStatus.GIVEN_UP, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("sub")).thenReturn(g);
        ActionResultDto r = service.forceGivenUp("sub", null);
        assertThat(r.ok()).isFalse();
        verify(planner, never()).applyGoalProgress(anyString(), any(), anyString());
    }

    @Test
    void updateDescription_persistsTrimmedValue() {
        GoalEntity g = goal("sub", "old", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        when(planner.getGoalById("sub")).thenReturn(g);

        ActionResultDto r = service.updateDescription("sub", "  shiny new description  ");

        assertThat(r.ok()).isTrue();
        assertThat(g.getDescription()).isEqualTo("shiny new description");
        verify(planner).saveGoalMetadata(g);
    }

    @Test
    void updateDescription_rejectsBlank() {
        ActionResultDto r = service.updateDescription("sub", "   ");
        assertThat(r.ok()).isFalse();
        verify(planner, never()).saveGoalMetadata(any());
    }

    @Test
    void writeActions_broadcastTreeChangedWhenPublisherWired() {
        GoalEntity g = goal("sub", "x", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        g.getMetadata().put("acceptanceCriteria", List.of("c1"));
        when(planner.getGoalById("sub")).thenReturn(g);
        when(planner.getCriteriaPassStatus(g)).thenReturn(new LinkedHashMap<>());
        when(planner.getCriteriaFailStreak(g)).thenReturn(new LinkedHashMap<>());

        GoalStateEventPublisher publisher = mock(GoalStateEventPublisher.class);
        service.setGoalStateEventPublisher(publisher);

        service.markCriterionMet("sub", 1, null);
        verify(publisher).publishTreeChanged(any());
    }

    // -----------------------------------------------------------------------------------

    private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }

    private static GoalEntity goal(String id, String description, GoalStatus status,
                                   GoalType type, String parentId) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setDescription(description);
        g.setStatus(status);
        g.setType(type);
        g.setParentId(parentId);
        g.setCreated(Instant.now());
        g.setRole("planner");
        g.setMetadata(new HashMap<>());
        g.setSubGoals(new ArrayList<>());
        return g;
    }
}
