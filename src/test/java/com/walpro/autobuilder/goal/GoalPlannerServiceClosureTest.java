package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    void autoCompleteRoot_depthFirst_absorbsExtraOriginGivenUpLeaf_butBlocksWhenRequired() throws IOException {
        // T2-EI: the 2026-06-24 depth-first run died when a lone GIVEN_UP *extra* leaf (a nested,
        // unrequested unit-test subgoal) became the sole root blocker. Under depth-first, an
        // EXTRA-origin closure failure must be absorbed (not doom the root); a REQUIRED-origin
        // failure must still block. See [[project-t2ei-design-note]] (P3).
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        when(goalPlannerConfig.isDepthFirstDecomposition()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        // A required, completed-criteria-less subgoal (passes closure).
        GoalEntity done = new GoalEntity("D", "completed required work", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);
        done.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
        // An EXTRA-origin GIVEN_UP leaf (would block, criteria-less) — must be absorbed under depth-first.
        GoalEntity extraLeaf = new GoalEntity("E", "unrequested unit test", "planner", GoalType.SUBGOAL, "D");
        extraLeaf.setStatus(GoalStatus.GIVEN_UP);
        extraLeaf.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_EXTRA);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        goalMap.put(extraLeaf.getId(), extraLeaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();
        // EXTRA failure absorbed → root completes on the surviving required work.
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);

        // Now make the same leaf REQUIRED: it must block the root even under depth-first.
        root.setStatus(GoalStatus.ACTIVE);
        extraLeaf.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void autoCompleteRoot_breadthFirst_extraOriginGivenUpLeaf_stillBlocks() throws IOException {
        // Control: with depth-first OFF (default), origin classification is ignored and ANY
        // GIVEN_UP blocker dooms the root — the pre-T2-EI behaviour is unchanged.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "completed required work", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);
        GoalEntity extraLeaf = new GoalEntity("E", "unrequested unit test", "planner", GoalType.SUBGOAL, "D");
        extraLeaf.setStatus(GoalStatus.GIVEN_UP);
        extraLeaf.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_EXTRA);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        goalMap.put(extraLeaf.getId(), extraLeaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void addSubGoal_stampsOrigin_topLevelPlannerRequired_nestedAndAnalysisExtra() throws IOException {
        // T2-EI D1: origin-based classification at creation. Top-level planner decomposition =
        // REQUIRED; nested (parent is a subgoal) or analysis/graduation/residue = EXTRA.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        // Top-level planner subgoal → REQUIRED
        service.addSubGoal("top", "Implement the Book resource", false, "G", "planner", null);
        assertThat(GoalPlannerService.isExtraOrigin(service.getGoalById("top"))).isFalse();

        // Nested under a subgoal → EXTRA
        service.addSubGoal("nested", "Add a unit test for the controller", false, "top", "planner", null);
        assertThat(GoalPlannerService.isExtraOrigin(service.getGoalById("nested"))).isTrue();

        // Analysis-spawned at root level → EXTRA
        service.addSubGoal("ana", "Investigate stagnation", false, "G", "analysis", null);
        assertThat(GoalPlannerService.isExtraOrigin(service.getGoalById("ana"))).isTrue();
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

    @Test
    void autoCompleteRoot_testGateFailure_blocksCompletion_andInjectsWorkItem() throws IOException {
        // T4-AN-2: the 18:00 fulfilled artifact packaged clean but failed 7+ of its own tests.
        // The test gate failing at the completion edge must refuse completion like the package gate.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.core.build.TestRunGate testRunGate =
                mock(com.walpro.autobuilder.core.build.TestRunGate.class);
        when(testRunGate.verifyTestsPass(any(), anyBoolean()))
                .thenReturn(java.util.Optional.of("test suite failing (Tests run: 7, Failures: 7): MemberServiceTest.testGetMemberById"));
        service.setTestRunGate(testRunGate);

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
                        && g.getDescription().contains("test suite pass"));
    }

    @Test
    void autoCompleteRoot_unconfirmedCriteria_blocksCompletion_andInjectsWorkItem() throws IOException {
        // T4-BP step 3: the 2026-06-16 order-checkout artifact compiled and had no tests, and its
        // subgoals' criteria were banked as passed optimistically — but the code was hollow (stub
        // checkout, uncomputed price). The completion edge must re-verify the passing criteria
        // against the current code and refuse completion when one is not positively confirmed.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService verifier =
                mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        when(verifier.verifyCompletion(any(), any(), any()))
                .thenReturn(java.util.Optional.of("1 acceptance criterion(s) not confirmed in the current code: POST /carts/{id}/checkout returns an OrderResponse with a computed PriceBreakdown"));
        service.setCriteriaVerificationService(verifier);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        // A subsumed subgoal counts as passing (covered) and carries criteria to re-verify.
        GoalEntity done = new GoalEntity("D", "Implement checkout", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.SUBSUMED);
        done.getMetadata().put("acceptanceCriteria",
                java.util.List.of("POST /carts/{id}/checkout returns an OrderResponse with a computed PriceBreakdown"));

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
                        && g.getDescription().contains("not confirmed in the current code"));

        // Verification confirming everything → completion proceeds.
        when(verifier.verifyCompletion(any(), any(), any())).thenReturn(java.util.Optional.empty());
        service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .forEach(g -> g.setStatus(GoalStatus.SUBSUMED));
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

}