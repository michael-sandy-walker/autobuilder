package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class GoalPlannerServiceClosureTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    @Test
    void promoteCurrentSubGoal_doesNotAutoCloseAncestors_advancesToParentThenNextLeaf() throws IOException {
        doNothing().when(fileService).writeFile(anyString(), anyString());
        doNothing().when(goalGraphService).setState(anyString(), any());
        doNothing().when(goalGraphService).markCompleted(anyString());

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        // Build goal tree:
        // root(G) -> parent(P) -> leaves(A,B)
        // root(G) -> X (active)
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);

        GoalEntity parent = new GoalEntity("P", "parent", "planner", GoalType.SUBGOAL, "G");
        parent.setStatus(GoalStatus.ACTIVE);

        GoalEntity leafA = new GoalEntity("A", "leafA", "planner", GoalType.SUBGOAL, "P");
        leafA.setStatus(GoalStatus.ACTIVE);

        GoalEntity leafB = new GoalEntity("B", "leafB", "planner", GoalType.SUBGOAL, "P");
        leafB.setStatus(GoalStatus.COMPLETED);

        GoalEntity leafX = new GoalEntity("X", "leafX", "planner", GoalType.SUBGOAL, "G");
        leafX.setStatus(GoalStatus.ACTIVE);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(parent.getId(), parent);
        goalMap.put(leafA.getId(), leafA);
        goalMap.put(leafB.getId(), leafB);
        goalMap.put(leafX.getId(), leafX);

        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        ReflectionTestUtils.setField(service, "currentSubGoalId", "A");

        boolean adopted = service.promoteCurrentSubGoal();

        assertThat(adopted).isTrue();

        // Leaf A completed
        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);

        // Parent is NOT auto-closed; it becomes the next current subgoal (closure is explicit)
        assertThat(service.getGoalById("P").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("P");

        // Root remains open because X is still active
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);

        // Graph completion invoked only for A (P is not completed yet)
        verify(goalGraphService, times(1)).markCompleted("A");
        verify(goalGraphService, never()).markCompleted("P");

        // If we promote once more, P completes and we advance to X
        service.promoteCurrentSubGoal();

        assertThat(service.getGoalById("P").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("X");

        verify(goalGraphService, times(1)).markCompleted("P");
    }

    @Test
    void autoCompleteRoot_completedWithoutCriteria_passes_givenUpWithoutCriteria_blocks() throws IOException {
        // 2026-06-11 23:57 terminal: 2 of the 4 final closure blockers were COMPLETED goals
        // whose only fault was declaring no acceptance criteria — the evaluation that promoted
        // them was the evidence. GIVEN_UP without criteria stays a blocker (abandoned work,
        // no evidence of coverage).
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "completed, criteria-less", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);

        // Reset with an additional criteria-less GIVEN_UP leaf: root must NOT complete.
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity abandoned = new GoalEntity("U", "given up, criteria-less", "planner", GoalType.SUBGOAL, "G");
        abandoned.setStatus(GoalStatus.GIVEN_UP);
        goalMap.put(abandoned.getId(), abandoned);

        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void autoCompleteRoot_packageGateFailure_blocksCompletion_andInjectsWorkItem() throws IOException {
        // T4-AN-8: run-2 terminal artifact (2026-06-12) had no application entry point — every
        // closure check passed but the artifact could not start. The package gate failing at
        // the completion edge must refuse completion and inject the failure as a work item.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.core.build.PackageGate packageGate =
                mock(com.walpro.autobuilder.core.build.PackageGate.class);
        when(packageGate.verifyPackageable(any()))
                .thenReturn(java.util.Optional.of("Unable to find main class"));
        service.setPackageGate(packageGate);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "completed, criteria-less", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getAllGoals())
                .anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE
                        && g.getDescription() != null
                        && g.getDescription().contains("Unable to find main class"));

        // Gate passing → completion proceeds.
        when(packageGate.verifyPackageable(any())).thenReturn(java.util.Optional.empty());
        service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .forEach(g -> g.setStatus(GoalStatus.SUBSUMED));
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }
}