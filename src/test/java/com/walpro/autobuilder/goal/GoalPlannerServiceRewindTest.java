package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * T4-DQ blocker 1, the payoff: goal status was mutated in place, so a run could not return to
 * what a goal WAS. These are the two halves a rewind has to move — the status, and whether the
 * goal had been thought of at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalPlannerServiceRewindTest {

    @TempDir
    Path tmp;

    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    private GoalPlannerService service;
    private GoalTransitionLog log;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileService fileService = new FileService(tmp.toString(), mapper);
        log = new GoalTransitionLog(fileService, mapper);
        service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setGoalTransitionLog(log);
        service.setCurrentCycleContext(CycleContextData.builder().cycleName("cycle-9").build());
    }

    @Test
    @DisplayName("a completed goal goes back to what it was before it was completed")
    void completedGoalIsRestoredToItsEarlierStatus() {
        installTree(goal("root", null, GoalStatus.ACTIVE), goal("s1", "root", GoalStatus.COMPLETED));
        long cut = log.append("root", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        cut = log.append("s1", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        log.append("s1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "cycle-2", "done");

        GoalPlannerService.RewindResult result = service.restoreGoalStateAsOf(
                log.statusesAsOf(cut), log.goalsExistingAsOf(cut), "s1");

        assertThat(service.getGoalById("s1").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getGoalById("s1").getCompleted()).isNull();
        assertThat(result.statusesRestored()).isEqualTo(1);
        assertThat(result.currentSubGoalId()).isEqualTo("s1");
    }

    @Test
    @DisplayName("a goal planned after the cut point is rewound out of existence, not left behind")
    void goalsPlannedAfterTheCutAreRewoundOut() {
        installTree(goal("root", null, GoalStatus.ACTIVE),
                goal("s1", "root", GoalStatus.ACTIVE),
                goal("s2", "root", GoalStatus.ACTIVE));
        log.append("root", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        long cut = log.append("s1", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        log.append("s2", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-2", null);

        GoalPlannerService.RewindResult result = service.restoreGoalStateAsOf(
                log.statusesAsOf(cut), log.goalsExistingAsOf(cut), "s1");

        assertThat(service.getGoalById("s2")).isNull();
        assertThat(result.goalsRewoundOut()).isEqualTo(1);
        verify(goalGraphService).markRewoundOut(anyList(), anyString());
    }

    @Test
    @DisplayName("a goal pruned since the cut point is brought back, not silently left out")
    void prunedGoalsAreRestoredFromTheGraph() {
        // The fold knows a goal existed at the cut but not what it said, so the text comes from the
        // graph. Without this the fold can only ever shrink the tree: it removes what was planned
        // after the checkpoint and is powerless over what was deleted after it — which a cross-run
        // rewind hits hardest, having given the planner longer to prune.
        installTree(goal("root", null, GoalStatus.ACTIVE), goal("s1", "root", GoalStatus.ACTIVE));
        log.append("root", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        log.append("s1", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        long cut = log.append("pruned", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);

        GoalEntity fromGraph = goal("pruned", "root", GoalStatus.ACTIVE);
        org.mockito.Mockito.when(goalGraphService.findById("pruned"))
                .thenReturn(java.util.Optional.of(fromGraph));

        service.restoreGoalStateAsOf(log.statusesAsOf(cut), log.goalsExistingAsOf(cut), "s1");

        assertThat(service.getGoalById("pruned"))
                .as("it existed at the cut point, so the restored tree must contain it")
                .isNotNull();
        assertThat(service.getGoalById("pruned").getDescription()).isEqualTo("desc-pruned");
    }

    @Test
    @DisplayName("the rewind's own writes are appended as history, never rewritten over it")
    void theRewindIsItselfRecorded() {
        installTree(goal("root", null, GoalStatus.ACTIVE), goal("s1", "root", GoalStatus.COMPLETED));
        log.append("root", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        long cut = log.append("s1", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        log.append("s1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "cycle-2", "done");
        int before = log.readAll().size();

        service.restoreGoalStateAsOf(log.statusesAsOf(cut), log.goalsExistingAsOf(cut), "s1");

        assertThat(log.readAll()).hasSizeGreaterThan(before);
        assertThat(log.readAll()).anyMatch(t -> t.kind() == GoalTransition.Kind.REWOUND
                && "s1".equals(t.goalId()) && "ACTIVE".equals(t.to()));
        // the earlier lines are untouched — the log only ever grows
        assertThat(log.readAll().subList(0, before))
                .noneMatch(t -> t.kind() == GoalTransition.Kind.REWOUND);
    }

    @Test
    @DisplayName("the pointer falls back to a live leaf when the checkpoint's subgoal is gone")
    void pointerFallsBackWhenTheRestoredSubgoalIsGone() {
        installTree(goal("root", null, GoalStatus.ACTIVE), goal("s1", "root", GoalStatus.ACTIVE));
        long cut = log.append("root", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        cut = log.append("s1", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);

        GoalPlannerService.RewindResult result = service.restoreGoalStateAsOf(
                log.statusesAsOf(cut), log.goalsExistingAsOf(cut), "gone");

        assertThat(result.currentSubGoalId()).isEqualTo("s1");
    }

    private static GoalEntity goal(String id, String parentId, GoalStatus status) {
        GoalEntity g = new GoalEntity(id, "desc-" + id, "planner",
                parentId == null ? GoalType.GOAL : GoalType.SUBGOAL, parentId);
        g.setStatus(status);
        if (status == GoalStatus.COMPLETED) g.setCompleted(java.time.Instant.now());
        return g;
    }

    private void installTree(GoalEntity root, GoalEntity... subgoals) {
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        for (GoalEntity sg : subgoals) goalMap.put(sg.getId(), sg);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", root.getId());
    }
}
