package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalPlannerServiceTest {

    private GoalPlannerService service;
    private GoalGraphService goalGraphService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties config = mock(GoalPlannerConfigProperties.class);

        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);

        doNothing().when(goalGraphService).setState(anyString(), any());
        doNothing().when(goalGraphService).setParent(anyString(), anyString());
        doNothing().when(goalGraphService).markCompleted(anyString());
        doNothing().when(goalGraphService).save(any(GoalEntity.class));

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);

        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.setCurrentCycleContext(CycleContextData.builder().build());
        service.init(); // Manually initialize
    }

    @Test
    void testDeepGoalTreeAndSubgoalPromotion() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("a", "Step A", true, "root");
        service.addSubGoal("b", "Step B", true, "a");
        service.addSubGoal("c", "Step C", true, "b");

        assertEquals("c", service.getCurrentSubGoal().getId());

        // Promote repeatedly
        service.promoteCurrentSubGoal(); // c -> completed
        assertEquals("b", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // b -> completed
        assertEquals("a", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // a -> completed
        assertNull(service.getCurrentSubGoal());

        assertEquals(GoalStatus.COMPLETED, service.getRootGoal().getStatus());
    }

    @Test
    void testPlannerIsInvokedWhenBranchExhausted() {
        // Create a mock callback
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback); // Inject the mock

        service.resetInitialGoal("root", "Milestone");

        service.addSubGoal("g1", "Single Goal", true, "root");
        assertEquals("g1", service.getCurrentSubGoal().getId());

        boolean success = service.promoteCurrentSubGoal(); // g1 -> completed, no more subgoals
        assertTrue(success);
        verify(mockCallback, times(1)).planInto(service.getCurrentCycleContext(), "root");

        // Trigger planner logic (will hit mock)
        success = service.promoteCurrentSubGoal();
        assertFalse(success);

        // Verify the callback was invoked
        verify(mockCallback, atLeastOnce()).planInto(service.getCurrentCycleContext(), "root");
    }

    @Test
    void testStructureWithMultipleBranches() {
        service.resetInitialGoal("root", "Root Goal");

        service.addSubGoal("a", "Branch A", false, "root");
        service.addSubGoal("b", "Branch B", true, "root"); // Set as current
        service.addSubGoal("b1", "Sub B.1", true, "b");
        service.addSubGoal("b2", "Sub B.2", true, "b1");

        // Should promote back up through B.2 → B.1 → B → then continue to A
        assertEquals("b2", service.getCurrentSubGoal().getId());
        service.promoteCurrentSubGoal();
        assertEquals("b1", service.getCurrentSubGoal().getId());
        service.promoteCurrentSubGoal();
        assertEquals("b", service.getCurrentSubGoal().getId());
        service.promoteCurrentSubGoal();
        assertEquals("a", service.getCurrentSubGoal().getId());
    }

    @Test
    void testMigrateToGraphWritesGoals() {
        service.resetInitialGoal("root", "Main Goal"); // 2 saves
        service.addSubGoal("a", "A", true); // 2 saves
        service.addSubGoal("b", "B under A", true, "a"); // 2 saves
        service.addSubGoal("c", "C under B", true, "b"); // 2 saves

        service.migrateToGraph(); // 4 saves

        verify(goalGraphService, atLeast(4)).save(any(GoalEntity.class));
        verify(goalGraphService, atLeast(1)).setParent(eq("a"), eq("root"));
        verify(goalGraphService, atLeast(1)).setParent(eq("b"), eq("a"));
        verify(goalGraphService, atLeast(1)).setParent(eq("c"), eq("b"));
        verify(goalGraphService, atLeastOnce()).setState(eq("root"), any());
    }

    @Test
    void testPruneShouldRemoveCompletedOrGivenUpBranches() {
        service.resetInitialGoal("root", "Goal");

        service.addSubGoal("g1", "G1", true, "root");
        service.addSubGoal("g2", "G2", true, "g1");
        service.addSubGoal("g3", "G3", true, "g2");

        // Mark all as completed
        service.markGoalCompleted("g3");
        service.markGoalCompleted("g2");
        service.markGoalCompleted("g1");

        // Trigger pruning
        service.pruneCompletedBranches();

        GoalEntity root = service.getRootGoal();
        List<GoalEntity> remaining = root.getSubGoals();
        assertTrue(remaining.isEmpty(), "Expected all pruned goals to be removed from tree.");
        assertNull(service.getGoalById("g1"));
        assertNull(service.getGoalById("g2"));
        assertNull(service.getGoalById("g3"));
    }

    @Test
    void promoteCurrentSubGoal_whenRootBranchExhausted_callsPlannerOnlyOnce_dueToPlannedOnce() {
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        service.resetInitialGoal("root", "Root");

        // Only one subgoal under root
        service.addSubGoal("g1", "Only leaf", true, "root");
        assertEquals("g1", service.getCurrentSubGoal().getId());

        // Complete g1 -> pointer becomes null (no next)
        service.promoteCurrentSubGoal();
        assertNull(service.getCurrentSubGoal());

        // First extra promote triggers planner for root
        service.promoteCurrentSubGoal();
        verify(mockCallback, times(1)).planInto(service.getCurrentCycleContext(), "root");

        // Second extra promote should NOT re-trigger planner (plannedOnce)
        service.promoteCurrentSubGoal();
        verify(mockCallback, times(1)).planInto(service.getCurrentCycleContext(), "root");
    }

    @Test
    void promoteCurrentSubGoal_plannerCallbackReceivesCurrentCycleContext() {
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("fb").build();
        service.setCurrentCycleContext(ctx);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");
        service.promoteCurrentSubGoal();
        assertNull(service.getCurrentSubGoal());

        service.promoteCurrentSubGoal();

        verify(mockCallback).planInto(eq(ctx), eq("root"));
    }

    @Test
    void promoteCurrentSubGoal_whenPlannerAddsNewLeaf_advancesToThatLeaf() {
        PlanningCallback callback = (ctx, parentId) -> {
            // emulate planner injecting 2 subgoals under parent
            service.addSubGoal("n1", "New 1", true, parentId);
            service.addSubGoal("n2", "New 2", false, parentId);
        };
        service.setPlanningCallback(callback);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");

        // Completing g1 triggers planning within the same promote call
        service.promoteCurrentSubGoal();

        GoalEntity current = service.getCurrentSubGoal();
        assertNotNull(current);
        assertEquals("n1", current.getId());
        assertEquals("New 1", current.getDescription());
        assertEquals(GoalStatus.ACTIVE, current.getStatus());
    }

    @Test
    void promoteCurrentSubGoal_whenPlannerAddsNoNewLeaf_resultsInExhaustedPointer() {
        PlanningCallback callback = (ctx, parentId) -> {
            // intentionally inject nothing
        };
        service.setPlanningCallback(callback);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");

        service.promoteCurrentSubGoal();

        assertNull(service.getCurrentSubGoal());
    }

    @Test
    void sanitizeProposedSubGoals_dedupesWithinBatch_afterNormalization() {
        List<String> out = service.sanitizeProposedSubGoals(List.of(
                "  Add unit tests for /hello endpoint   ",
                "Add   unit   tests for /hello endpoint",
                "ADD UNIT TESTS FOR /HELLO ENDPOINT", // NOTE: your normalizeGoalText does not lowercase result,
                // so this will remain distinct unless you later add case-folding.
                "as an AI I will do X",               // should be dropped
                "fix",                                // dropped
                "improve",                            // dropped
                "short"                               // dropped (min chars)
        ));

        // With current implementation: exact de-dup only, not case-insensitive.
        // So we expect 2 entries (first two collapse, ALLCAPS stays).
        assertThat(out).containsExactly(
                "Add unit tests for /hello endpoint",
                "ADD UNIT TESTS FOR /HELLO ENDPOINT"
        );
    }

    @Test
    void sanitizeProposedSubGoals_enforcesMaxNewSubGoals_afterFiltering() {
        List<String> raw = List.of(
                "Short",                      // filtered (min chars)
                "as an ai blah blah",         // filtered
                "Refactor",                   // filtered (refactor)
                "Subgoal 01 valid text",
                "Subgoal 02 valid text",
                "Subgoal 03 valid text",
                "Subgoal 04 valid text",
                "Subgoal 05 valid text",
                "Subgoal 06 valid text",
                "Subgoal 07 valid text",
                "Subgoal 08 valid text",
                "Subgoal 09 valid text",
                "Subgoal 10 valid text"
        );

        List<String> out = service.sanitizeProposedSubGoals(raw);

        // DEFAULT_MAX_NEW_SUBGOALS is 8
        assertThat(out).hasSize(8);
        assertThat(out.get(0)).isEqualTo("Subgoal 01 valid text");
        assertThat(out.get(7)).isEqualTo("Subgoal 08 valid text");
    }

    @Test
    void addSubGoalsBatch_addsOnlySanitized_andSetsOnlyFirstAsCurrent() {
        service.resetInitialGoal("root", "Main Goal");

        // Create a container subgoal (parent) under root so we can add children to it.
        service.addSubGoal("goal-parent", "Container subgoal for planning", true, "root");

        List<String> created = service.addSubGoalsBatch(
                List.of(
                        "  First valid planned subgoal  ",
                        "Second valid planned subgoal",
                        "fix",   // filtered
                        "short"  // filtered
                ),
                "goal-parent",
                true,           // setFirstAsCurrent
                "planner",
                null
        );

        assertThat(created).hasSize(2);

        // The first created one should be current (setFirstAsCurrent)
        GoalEntity current = service.getCurrentSubGoal();
        assertThat(current).isNotNull();
        assertThat(created.get(0)).isEqualTo(current.getId());

        // Both children should exist and be ACTIVE SUBGOALs under goal-parent
        List<GoalEntity> children = service.getSubGoals("goal-parent");
        assertThat(children).hasSize(2);
        assertThat(children.get(0).getDescription()).isEqualTo("First valid planned subgoal");
        assertThat(children.get(1).getDescription()).isEqualTo("Second valid planned subgoal");
    }

    @Test
    void promoteCurrentSubGoal_marksPlannedOnceAndDoesNotReplanAgain_forSameParent() {
        service.resetInitialGoal("root", "Main Goal");

        // Build: root -> parent -> leaf
        service.addSubGoal("goal-parent", "Parent container for branch", false, "root");
        service.addSubGoal("goal-leaf", "Leaf actionable subgoal", true, "goal-parent");

        // Simulate: leaf is completed, no active siblings remain -> shouldTriggerPlanner would consider replanning
        // We want to ensure: if plannedOnce is set, it should NOT call planningCallback.
        PlanningCallback cb = mock(PlanningCallback.class);
        service.setPlanningCallback(cb);

        // Mark parent as already planned once
        service.markPlannedOnce("goal-parent");

        // Promote leaf
        boolean promoted = service.promoteCurrentSubGoal();

        assertThat(promoted).isTrue();

        // plannedOnce should prevent replanning callback
        verify(cb, never()).planInto(any(), eq("goal-parent"));

        // leaf becomes COMPLETED, and currentSubGoalId should advance (may become parent or null depending on tree)
        assertThat(service.getGoalById("goal-leaf").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void applyGoalProgress_whenFulfilledForCurrent_promotes_andReturnsActuallyPromotedTrue() {
        service.resetInitialGoal("root", "Main Goal");

        // Arrange: root -> leaf1 (current) and leaf2 (sibling)
        service.addSubGoal("goal-leaf-1", "First leaf subgoal to complete", true, "root");
        service.addSubGoal("goal-leaf-2", "Second leaf subgoal to become current", false, "root");

        String before = service.getCurrentSubGoal().getId();
        assertThat(before).isEqualTo("goal-leaf-1");

        // Act
        GoalProgressApplicationResult r = service.applyGoalProgress("goal-leaf-1", GoalProgressState.FULFILLED);

        // Assert: leaf1 completed
        assertThat(service.getGoalById("goal-leaf-1").getStatus()).isEqualTo(GoalStatus.COMPLETED);

        // Assert: current moved to leaf2 (or at least changed away from leaf1)
        String after = service.getCurrentSubGoal() != null ? service.getCurrentSubGoal().getId() : null;
        assertThat(after).isNotEqualTo(before);
        assertThat(after).isEqualTo("goal-leaf-2");

        // Assert result contract
        assertThat(r.goalId()).isEqualTo("goal-leaf-1");
        assertThat(r.previousSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r.newSubGoalId()).isEqualTo("goal-leaf-2");

        // “adopted/promoted means subgoal changed”
        assertThat(r.promoted()).isTrue();

        // root should not auto-complete here (since leaf2 still active)
        assertThat(r.rootAutoCompleted()).isFalse();
    }

    @Test
    void applyGoalProgress_whenFulfilledForNonCurrent_marksCompleted_butDoesNotPromote() {
        service.resetInitialGoal("root", "Main Goal");

        // Arrange: root -> leaf1 (current) and leaf2 (non-current sibling)
        service.addSubGoal("goal-leaf-1", "First leaf subgoal to remain current", true, "root");
        service.addSubGoal("goal-leaf-2", "Second leaf subgoal to be fulfilled non-current", false, "root");

        String beforeCurrent = service.getCurrentSubGoal().getId();
        assertThat(beforeCurrent).isEqualTo("goal-leaf-1");

        // Act: fulfill the non-current goal
        GoalProgressApplicationResult r = service.applyGoalProgress("goal-leaf-2", GoalProgressState.FULFILLED);

        // Assert: non-current goal is completed
        assertThat(service.getGoalById("goal-leaf-2").getStatus()).isEqualTo(GoalStatus.COMPLETED);

        // Assert: current pointer did NOT change
        String afterCurrent = service.getCurrentSubGoal() != null ? service.getCurrentSubGoal().getId() : null;
        assertThat(afterCurrent).isEqualTo("goal-leaf-1");

        // Result contract: not promoted, pointer stable
        assertThat(r.goalId()).isEqualTo("goal-leaf-2");
        assertThat(r.promoted()).isFalse();
        assertThat(r.previousSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r.newSubGoalId()).isEqualTo("goal-leaf-1");

        // Root should not auto-complete here because leaf1 is still active
        assertThat(r.rootAutoCompleted()).isFalse();
    }

    @Test
    void applyGoalProgress_whenCalledWithNullArgs_returnsNotPromoted_andKeepsPointer() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("goal-leaf-1", "First leaf subgoal to remain current", true, "root");
        String beforeCurrent = service.getCurrentSubGoal().getId();
        assertThat(beforeCurrent).isEqualTo("goal-leaf-1");

        // Act: null progress
        GoalProgressApplicationResult r1 = service.applyGoalProgress("goal-leaf-1", null);

        // Assert: pointer unchanged, no promotion
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("goal-leaf-1");
        assertThat(r1.promoted()).isFalse();
        assertThat(r1.previousSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r1.newSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r1.rootAutoCompleted()).isFalse();

        // Act: null goalId
        GoalProgressApplicationResult r2 = service.applyGoalProgress(null, GoalProgressState.FULFILLED);

        // Assert: pointer unchanged, no promotion
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("goal-leaf-1");
        assertThat(r2.promoted()).isFalse();
        assertThat(r2.previousSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r2.newSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r2.rootAutoCompleted()).isFalse();
    }

    @Test
    void applyGoalProgress_whenGivenUpForCurrent_advancesCurrentSubGoalToNextSibling() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("goal-leaf-1", "First leaf — will be given up", true, "root");
        service.addSubGoal("goal-leaf-2", "Second leaf — should become current", false, "root");

        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("goal-leaf-1");

        GoalProgressApplicationResult r = service.applyGoalProgress("goal-leaf-1", GoalProgressState.GIVEN_UP);

        // given-up goal is marked GIVEN_UP
        assertThat(service.getGoalById("goal-leaf-1").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);

        // current pointer advances to the sibling — not stuck on the given-up goal
        GoalEntity current = service.getCurrentSubGoal();
        assertThat(current).isNotNull();
        assertThat(current.getId()).isEqualTo("goal-leaf-2");
        assertThat(current.getStatus()).isEqualTo(GoalStatus.ACTIVE);

        assertThat(r.previousSubGoalId()).isEqualTo("goal-leaf-1");
        assertThat(r.newSubGoalId()).isEqualTo("goal-leaf-2");
    }

    @Test
    void applyGoalProgress_whenGivenUpForNonCurrent_doesNotMoveCurrentSubGoal() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("goal-leaf-1", "First leaf — current", true, "root");
        service.addSubGoal("goal-leaf-2", "Second leaf — will be given up", false, "root");

        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("goal-leaf-1");

        service.applyGoalProgress("goal-leaf-2", GoalProgressState.GIVEN_UP);

        assertThat(service.getGoalById("goal-leaf-2").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        // current pointer must not change
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("goal-leaf-1");
    }

    @Test
    void completedGoal_cannotBeReactivatedBySetCurrentSubGoal() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("goal-leaf-1", "Leaf 1", true, "root");

        service.markGoalCompleted("goal-leaf-1");

        assertThat(service.getGoalById("goal-leaf-1").getStatus()).isEqualTo(GoalStatus.COMPLETED);

        Throwable thrown = catchThrowable(() -> service.setCurrentSubGoal("goal-leaf-1"));

        assertThat(thrown).isNotNull();
        assertThat(service.getGoalById("goal-leaf-1").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    // --- T2-AL: per-parent exhaustion ---

    @Test
    void exhaustParentIfStuck_belowFloorAndBelowMajority_doesNothing() {
        // 2 children, 1 GIVEN_UP: floor=3 not reached, majority (ceil(2*0.51)=2) not reached either
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child 1", true, "parent");
        service.addSubGoal("c2", "Child 2", false, "parent");

        service.applyGoalProgress("c1", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("c1", 3);

        assertThat(result).isFalse();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void exhaustParentIfStuck_strictMajorityGivenUpWithNoCompletions_marksParentGivenUp() {
        // 3 children, 3 GIVEN_UP: floor=3 reached, majority (ceil(3*0.51)=2) exceeded
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child 1", true, "parent");
        service.addSubGoal("c2", "Child 2", false, "parent");
        service.addSubGoal("c3", "Child 3", false, "parent");

        service.applyGoalProgress("c1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c2", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c3", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("c3", 3);

        assertThat(result).isTrue();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void exhaustParentIfStuck_majorityRuleOverridesFloor_largeFamilyFiresLater() {
        // 10 children: floor=3 would fire at 3, but majority rule requires ceil(10*0.51)=6
        // 5 GIVEN_UP → below majority → should NOT fire
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        for (int i = 1; i <= 10; i++) {
            service.addSubGoal("c" + i, "Child " + i, i == 1, "parent");
        }
        for (int i = 1; i <= 5; i++) {
            service.applyGoalProgress("c" + i, GoalProgressState.GIVEN_UP);
        }

        boolean result = service.exhaustParentIfStuck("c5", 3);

        assertThat(result).isFalse();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void exhaustParentIfStuck_majorityRuleOverridesFloor_largeFamilyFiresAtMajority() {
        // 10 children: majority threshold = ceil(10*0.51) = 6
        // 6 GIVEN_UP → fires
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        for (int i = 1; i <= 10; i++) {
            service.addSubGoal("c" + i, "Child " + i, i == 1, "parent");
        }
        for (int i = 1; i <= 6; i++) {
            service.applyGoalProgress("c" + i, GoalProgressState.GIVEN_UP);
        }

        boolean result = service.exhaustParentIfStuck("c6", 3);

        assertThat(result).isTrue();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void exhaustParentIfStuck_withCompletedChild_doesNotExhaustParent() {
        // c1 completes (merge happened), c2 and c3 give up — completions block exhaustion
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child 1", true, "parent");
        service.addSubGoal("c2", "Child 2", false, "parent");
        service.addSubGoal("c3", "Child 3", false, "parent");

        service.applyGoalProgress("c1", GoalProgressState.FULFILLED);
        service.applyGoalProgress("c2", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c3", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("c3", 2);

        assertThat(result).isFalse();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void exhaustParentIfStuck_skipsRootParent() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("c1", "Child 1", true, "root");
        service.addSubGoal("c2", "Child 2", false, "root");
        service.addSubGoal("c3", "Child 3", false, "root");

        service.applyGoalProgress("c1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c2", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c3", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("c3", 3);

        assertThat(result).isFalse();
        assertThat(service.getGoalById("root").getStatus()).isNotEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void exhaustParentIfStuck_cascadesRemainingActiveChildren() {
        // 4 children, 3 GIVEN_UP (majority reached), c4 still ACTIVE → must be cascaded
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child 1", true, "parent");
        service.addSubGoal("c2", "Child 2", false, "parent");
        service.addSubGoal("c3", "Child 3", false, "parent");
        service.addSubGoal("c4", "Child 4", false, "parent");

        service.applyGoalProgress("c1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c2", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("c3", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("c3", 3);

        assertThat(result).isTrue();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("c4").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

}
