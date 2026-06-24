package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T2-EH / T4-BP (promoting direction): the convergence-stall verify-before-pause re-verifies
 * active leaf subgoals against the current code and promotes those that pass, then attempts root
 * completion — so a near-complete artifact is verified rather than abandoned by the T4-I brake.
 */
@ExtendWith(MockitoExtension.class)
class GoalPlannerServiceVerifyBeforePauseTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;
    @Mock private CriteriaVerificationService criteriaVerificationService;

    private GoalPlannerService service;

    @BeforeEach
    void setUp() {
        service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        ReflectionTestUtils.setField(service, "criteriaVerificationService", criteriaVerificationService);
    }

    private GoalEntity subgoalWithCriteria(String id, String parentId, String criterion) {
        GoalEntity sg = new GoalEntity(id, "leaf-" + id, "planner", GoalType.SUBGOAL, parentId);
        sg.setStatus(GoalStatus.ACTIVE);
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("acceptanceCriteria", List.of(criterion));
        md.put("mergeCount", 1);
        sg.setMetadata(md);
        return sg;
    }

    private void installTree(GoalEntity root, GoalEntity... subgoals) {
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        for (GoalEntity sg : subgoals) goalMap.put(sg.getId(), sg);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", root.getId());
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousOutput(Map.of("Foo.java", "class Foo {}"))
                .build();
        ReflectionTestUtils.setField(service, "currentCycleContext", ctx);
    }

    private CycleContextData ctx() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .previousOutput(Map.of("Foo.java", "class Foo {}"))
                .build();
    }

    @Test
    void allCriteriaVerified_promotesLeaf_andCompletesRoot() throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity leaf = subgoalWithCriteria("A", "G", "Books endpoint returns 200");
        installTree(root, leaf);

        // Verification confirms the single criterion against the current code.
        when(criteriaVerificationService.verifyAndRecord(any(), eq("A"), any(), any()))
                .thenReturn(Map.of(1, true));
        // T4-BP-3 completion gate corroborates (no unconfirmed criteria).
        when(criteriaVerificationService.verifyCompletion(any(), any(), any()))
                .thenReturn(java.util.Optional.empty());

        var result = service.verifyAndPromoteBeforePause(ctx());

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(result.promoted()).isEqualTo(1);
        assertThat(result.rootCompleted()).isTrue();
        assertThat(result.madeProgress()).isTrue();
    }

    @Test
    void criteriaNotVerified_noPromotion_rootStaysActive() {
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity leaf = subgoalWithCriteria("A", "G", "ISBN 13-digit validation present");
        installTree(root, leaf);

        // The criterion is not satisfied by the current code.
        when(criteriaVerificationService.verifyAndRecord(any(), eq("A"), any(), any()))
                .thenReturn(Map.of(1, false));

        var result = service.verifyAndPromoteBeforePause(ctx());

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(result.promoted()).isZero();
        assertThat(result.rootCompleted()).isFalse();
        assertThat(result.madeProgress()).isFalse();
    }

    @Test
    void emptyVerification_abstains_noPromotion() {
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity leaf = subgoalWithCriteria("A", "G", "anything");
        installTree(root, leaf);

        // LLM failure / unparseable → empty map → never treated as a pass.
        when(criteriaVerificationService.verifyAndRecord(any(), eq("A"), any(), any()))
                .thenReturn(Map.of());

        var result = service.verifyAndPromoteBeforePause(ctx());

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(result.madeProgress()).isFalse();
    }

    @Test
    void criteriaAlreadyBanked_promotedWithoutVerificationCall() throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity leaf = subgoalWithCriteria("A", "G", "Members endpoint exists");
        // Already banked: the brake's "merges land, never promoted" case.
        leaf.getMetadata().put("criteriaPassStatus", Map.of("1", true));
        installTree(root, leaf);

        when(criteriaVerificationService.verifyCompletion(any(), any(), any()))
                .thenReturn(java.util.Optional.empty());

        var result = service.verifyAndPromoteBeforePause(ctx());

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(result.promoted()).isEqualTo(1);
        // No LLM verification needed for an already-banked criterion.
        verify(criteriaVerificationService, never()).verifyAndRecord(any(), eq("A"), any(), any());
    }

    @Test
    void completedRoot_returnsCompletedWithoutWork() {
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.COMPLETED);
        ReflectionTestUtils.setField(service, "goalMap",
                new LinkedHashMap<>(Map.of("G", root)));
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        var result = service.verifyAndPromoteBeforePause(ctx());

        assertThat(result.rootCompleted()).isTrue();
        verifyNoInteractions(criteriaVerificationService);
    }
}
