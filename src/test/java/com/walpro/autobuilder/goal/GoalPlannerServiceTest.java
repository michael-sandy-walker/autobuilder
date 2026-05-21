package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.GoalProgressState;
import org.mockito.ArgumentMatchers;
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
        when(config.getMinCompletionRatio()).thenReturn(0.5);

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
    void promoteCurrentSubGoal_T1DH_branchExhaustedAboveRatio_skipsPlannerAndCompletesRoot() {
        // T1-DH: when promoting the last active subgoal would otherwise trigger a re-plan against
        // root, the pre-plan autoComplete check fires first. With ratio 1/1 = 1.0 > 0.5 the root
        // is marked COMPLETED and the planner callback is NOT invoked — preventing the cycle-7
        // duplicate-injection waste described in the T1-DH ticket.
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        service.resetInitialGoal("root", "Milestone");
        service.addSubGoal("g1", "Single Goal", true, "root");
        assertEquals("g1", service.getCurrentSubGoal().getId());

        boolean success = service.promoteCurrentSubGoal();

        assertTrue(success);
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        verify(mockCallback, never()).planInto(any(CycleContextData.class), anyString());

        // A second promote on a now-completed root is a no-op; the planner still must not fire.
        service.promoteCurrentSubGoal();
        verify(mockCallback, never()).planInto(any(CycleContextData.class), anyString());
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
    void promoteCurrentSubGoal_T1DH_repeatedPromoteOnExhaustedRoot_doesNotInvokePlanner() {
        // T1-DH supersedes the older plannedOnce-based throttle: once the last subgoal has been
        // promoted under root, autoComplete marks root terminal and any number of follow-up
        // promote calls leave the planner uninvoked.
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");
        assertEquals("g1", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal();
        assertNull(service.getCurrentSubGoal());
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);

        service.promoteCurrentSubGoal();
        service.promoteCurrentSubGoal();

        verify(mockCallback, never()).planInto(any(CycleContextData.class), anyString());
    }

    @Test
    void promoteCurrentSubGoal_T1DH_plannerCallbackNotInvoked_whenRootAutoCompletesFirst() {
        // T1-DH: with a single completed child under root, the pre-plan autoComplete check marks
        // root COMPLETED before any planInto call can fire. The callback must therefore never see
        // the cycle context, even when promotion is invoked twice.
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("fb").build();
        service.setCurrentCycleContext(ctx);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");
        service.promoteCurrentSubGoal();

        assertNull(service.getCurrentSubGoal());
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);

        service.promoteCurrentSubGoal();

        verify(mockCallback, never()).planInto(any(CycleContextData.class), anyString());
    }

    @Test
    void promoteCurrentSubGoal_T1DH_plannerCallbackNotInvoked_evenWhenWillingToAddNewLeaf() {
        // T1-DH: a callback that would otherwise inject new leaves under root must not be reached
        // when the first wave is already satisfied. After promotion, root is COMPLETED and the
        // currentSubGoalId stays null — no spurious second-wave subgoals are added.
        PlanningCallback callback = (ctx, parentId) -> {
            service.addSubGoal("n1", "New 1", true, parentId);
            service.addSubGoal("n2", "New 2", false, parentId);
            return 2;
        };
        service.setPlanningCallback(callback);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");

        service.promoteCurrentSubGoal();

        assertNull(service.getCurrentSubGoal());
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertNull(service.getGoalById("n1"));
        assertNull(service.getGoalById("n2"));
    }

    @Test
    void promoteCurrentSubGoal_whenPlannerAddsNoNewLeaf_resultsInExhaustedPointer() {
        PlanningCallback callback = (ctx, parentId) -> {
            // intentionally inject nothing
            return 0;
        };
        service.setPlanningCallback(callback);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");

        service.promoteCurrentSubGoal();

        assertNull(service.getCurrentSubGoal());
    }

    @Test
    void promoteCurrentSubGoal_T1DH_supersedesT1BKDedupZeroPath_byPreemptingPlannerEntirely() {
        // T1-BK previously handled the "planner called → 0 injected (all deduped)" path by
        // skipping autoComplete and raising REPLAN_REQUESTED. T1-DH supersedes that path for the
        // single-child-completed configuration: autoComplete fires BEFORE planInto, so the
        // planInto-injects-zero scenario is now structurally unreachable from this entrypoint and
        // the root is marked COMPLETED instead of being held ACTIVE under REPLAN_REQUESTED.
        PlanningCallback callback = (ctx, parentId) -> 0;
        service.setPlanningCallback(callback);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only leaf", true, "root");

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.promoteCurrentSubGoal();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.REPLAN_REQUESTED)).isFalse();
    }

    @Test
    void autoCompleteRootIfSatisfied_T1BZ_majorityGivenUp_marksRootGivenUpAndRaisesGoalExhausted() {
        // T1-BZ: when the majority of direct children are GIVEN_UP, root must be marked GIVEN_UP
        // and GOAL_EXHAUSTED must be raised on currentCycleContext (not COMPLETED).
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Child one valid text", false, "root");
        service.addSubGoal("g2", "Child two valid text", false, "root");
        service.addSubGoal("g3", "Child three valid text", false, "root");
        service.addSubGoal("g4", "Child four valid text", false, "root");
        service.addSubGoal("g5", "Child five valid text", false, "root");
        service.addSubGoal("g6", "Child six valid text", false, "root");
        // 4 GIVEN_UP, 2 COMPLETED → ratio = 2/6 ≈ 0.33 ≤ 0.5 threshold → GIVEN_UP
        service.markGoalGivenUp("g1");
        service.markGoalGivenUp("g2");
        service.markGoalGivenUp("g3");
        service.markGoalGivenUp("g4");
        service.markGoalCompleted("g5");
        service.markGoalCompleted("g6");

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getCurrentCycleContext()).isNotNull();
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isTrue();
    }

    @Test
    void autoCompleteRootIfSatisfied_T1BZ_majorityCompleted_marksRootCompleted() {
        // T1-BZ: when the majority of direct children are COMPLETED, root must be marked COMPLETED
        // and GOAL_EXHAUSTED must NOT be raised.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Child one valid text", false, "root");
        service.addSubGoal("g2", "Child two valid text", false, "root");
        service.addSubGoal("g3", "Child three valid text", false, "root");
        service.addSubGoal("g4", "Child four valid text", false, "root");
        service.addSubGoal("g5", "Child five valid text", false, "root");
        // 4 COMPLETED, 1 GIVEN_UP → ratio = 4/5 = 0.8 > 0.5 threshold → COMPLETED
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        service.markGoalCompleted("g3");
        service.markGoalCompleted("g4");
        service.markGoalGivenUp("g5");

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
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

    @Test
    void markGoalGivenUp_cascade_marksAllActiveChildrenGivenUp() {
        // T1-CA: ACTIVE children with no merged work are cascaded GIVEN_UP when the parent is given up.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child one", false, "parent");
        service.addSubGoal("c2", "Child two", false, "parent");

        service.markGoalGivenUp("parent");

        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("c1").getStatus()).isEqualTo(GoalStatus.GIVEN_UP); // cascaded
        assertThat(service.getGoalById("c2").getStatus()).isEqualTo(GoalStatus.GIVEN_UP); // cascaded
    }

    @Test
    void markGoalGivenUp_cascade_marksEntireSubtreeGivenUp() {
        // T1-CA: entire merge-less subtree (including grandchildren) is cascaded.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child of parent", false, "parent");
        service.addSubGoal("grandchild", "Grandchild of c1", false, "c1");

        service.markGoalGivenUp("parent");

        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("c1").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("grandchild").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void markGoalGivenUp_T1EJ_protectsMergeBearingChildFromCascade() {
        // T1-EJ: cascade must skip merge-bearing descendants. C1 (mergeCount=0) is cascaded
        // GIVEN_UP; C2 (mergeCount=1) carries real merged work and stays ACTIVE.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child without merge", false, "parent");
        service.addSubGoal("c2", "Child with merge", false, "parent");

        service.incrementGoalMergeCount("c2");

        service.markGoalGivenUp("parent");

        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("c1").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("c2").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void markGoalGivenUp_cascade_childrenOfGivenUpParentNotReturnedByGetRemainingGoals() {
        // T1-CA: orphaned children must not appear in getRemainingGoals() after parent is given up
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", false, "root");
        service.addSubGoal("c1", "Child one", false, "parent");
        service.addSubGoal("c2", "Child two", false, "parent");
        service.addSubGoal("grandchild", "Grandchild", false, "c1");

        service.markGoalGivenUp("parent");

        List<GoalEntity> remaining = service.getRemainingGoals();
        assertThat(remaining).extracting(GoalEntity::getId)
                .doesNotContain("parent", "c1", "c2", "grandchild");
    }

    // --- T1-CB: getCurrentSubGoal does not clobber GIVEN_UP root to COMPLETED ---

    @Test
    void getCurrentSubGoal_T1CB_whenRootGivenUpAndCurrentSubGoalIdNull_doesNotClobberToCompleted() {
        // Reproduces the bug where T1-BZ marks root GIVEN_UP, then getCurrentSubGoal()
        // overwrites it to COMPLETED — causing GOAL_FULFILLED instead of GOAL_EXHAUSTED.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("s1", "S1", false, "root"); // setAsCurrent=false → currentSubGoalId stays null

        // Simulate T1-BZ: root marked GIVEN_UP (T1-CA cascades to s1)
        service.markGoalGivenUp("root");

        // currentSubGoalId is null, root is GIVEN_UP — calling getCurrentSubGoal() must NOT
        // clobber root back to COMPLETED
        GoalEntity result = service.getCurrentSubGoal();

        assertThat(result).isNull();
        assertThat(service.getGoalById("root").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void getCurrentSubGoal_T1CB_whenRootCompletedAndCurrentSubGoalIdNull_returnsNull() {
        // Ensure the existing COMPLETED guard still works correctly after the GIVEN_UP guard is added.
        // T1-DD: complete the leaf first so root completion is allowed by the active-descendants guard.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("s1", "S1", false, "root");
        service.markGoalCompleted("s1");
        service.markGoalCompleted("root");

        GoalEntity result = service.getCurrentSubGoal();

        assertThat(result).isNull();
        assertThat(service.getGoalById("root").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    // --- T1-DB: getCurrentSubGoal recovers null pointer when active replacements exist ---

    @Test
    void getCurrentSubGoal_T1DB_whenCurrentSubGoalIdNullButActiveReplacementsExist_advancesPointer() {
        // Reproduces the T2-CH deadlock: first replacement fulfils → pointer goes null,
        // then planner injects more replacements, but currentSubGoalId stays null forever.
        service.resetInitialGoal("root", "Root");

        // Simulate: original subgoal given up, replacement under it active
        service.addSubGoal("given-up-parent", "Original endpoint", false, "root");
        service.markGoalGivenUp("given-up-parent");

        // Add fresh replacement under the GIVEN_UP parent (T1-CP pattern)
        service.addSubGoal("replacement-1", "Replacement A", false, "given-up-parent");
        // currentSubGoalId is null (never set — simulates mid-cycle injection after pointer went null)

        GoalEntity result = service.getCurrentSubGoal();

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("replacement-1");
    }

    // --- T2-BW: countUnmetCriteria ---

    @Test
    void countUnmetCriteria_unknownGoal_returnsOne() {
        assertThat(service.countUnmetCriteria("nonexistent")).isEqualTo(1);
    }

    @Test
    void countUnmetCriteria_nullGoalId_returnsOne() {
        assertThat(service.countUnmetCriteria(null)).isEqualTo(1);
    }

    @Test
    void countUnmetCriteria_noCriteria_returnsOne() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("s1", "Do something", false, "root");
        // no acceptance criteria set

        assertThat(service.countUnmetCriteria("s1")).isEqualTo(1);
    }

    @Test
    void countUnmetCriteria_allPassing_returnsZero() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("s1", "Do something", false, "root");
        service.setAcceptanceCriteria("s1", List.of("Criterion A", "Criterion B"));
        service.saveCriteriaPassStatus("s1", java.util.Map.of(1, true, 2, true));

        assertThat(service.countUnmetCriteria("s1")).isEqualTo(0);
    }

    @Test
    void countUnmetCriteria_somePassingSomeNot_returnsUnmetCount() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("s1", "Do something", false, "root");
        service.setAcceptanceCriteria("s1", List.of("Criterion A", "Criterion B", "Criterion C"));
        service.saveCriteriaPassStatus("s1", java.util.Map.of(1, true, 2, false));
        // index 3 absent → unmet

        assertThat(service.countUnmetCriteria("s1")).isEqualTo(2); // indices 2 and 3
    }

    // --- T2-BW: getGlobalAnalysisInjectionBudget ---

    @Test
    void getGlobalAnalysisInjectionBudget_noActiveSubgoals_returnsZero() {
        service.resetInitialGoal("root", "Root");
        // no subgoals at all

        assertThat(service.getGlobalAnalysisInjectionBudget()).isEqualTo(0);
    }

    @Test
    void getGlobalAnalysisInjectionBudget_activeUnmetMinusInjectionsMinusGivenUp() {
        service.resetInitialGoal("root", "Root");
        // 2 ACTIVE subgoals: s1 has 2 unmet, s2 has 1 unmet (no criteria → default 1)
        service.addSubGoal("s1", "Subgoal 1", false, "root");
        service.setAcceptanceCriteria("s1", List.of("C1", "C2"));
        // s1 pass status: both unmet → countUnmetCriteria = 2

        service.addSubGoal("s2", "Subgoal 2", false, "root");
        // s2 no criteria → countUnmetCriteria = 1

        // 1 GIVEN_UP subgoal with 1 criterion
        service.addSubGoal("s3", "Subgoal 3", false, "root");
        service.setAcceptanceCriteria("s3", List.of("C-gone"));
        service.markGoalGivenUp("s3");

        // No injections yet: budget = (2 + 1) - 0 - 1 = 2
        assertThat(service.getGlobalAnalysisInjectionBudget()).isEqualTo(2);

        // Record 1 injection: budget = 3 - 1 - 1 = 1
        service.incrementTotalAnalysisInjections();
        assertThat(service.getGlobalAnalysisInjectionBudget()).isEqualTo(1);

        // Record 1 more: budget = 3 - 2 - 1 = 0
        service.incrementTotalAnalysisInjections();
        assertThat(service.getGlobalAnalysisInjectionBudget()).isEqualTo(0);

        // Never goes negative
        service.incrementTotalAnalysisInjections();
        assertThat(service.getGlobalAnalysisInjectionBudget()).isEqualTo(0);
    }

    @Test
    void getGlobalAnalysisInjectionBudget_excludesGivenUpFromPriorRuns() {
        service.resetInitialGoal("root", "Root");

        // One ACTIVE subgoal with 2 unmet criteria (current run = 1)
        service.addSubGoal("s1", "Current run goal", false, "root");
        service.setAcceptanceCriteria("s1", List.of("C1", "C2"));

        // A GIVEN_UP subgoal from the current run: should be counted
        service.addSubGoal("s-curr", "Current run given-up", false, "root");
        service.setAcceptanceCriteria("s-curr", List.of("Old1"));
        service.markGoalGivenUp("s-curr");

        // Simulate a prior-run GIVEN_UP goal: stamp planningRunId = 0 (previous session)
        service.addSubGoal("s-prior", "Prior run given-up", false, "root");
        service.setAcceptanceCriteria("s-prior", List.of("Old2", "Old3", "Old4"));
        service.getGoalById("s-prior").getMetadata().put("planningRunId", 0); // override to prior run
        service.markGoalGivenUp("s-prior");

        // budget = activeUnmet(2) - injections(0) - givenUpCurrentRun(1) = 1
        // prior-run goal's 3 criteria must NOT be counted
        assertThat(service.getGlobalAnalysisInjectionBudget()).isEqualTo(1);
    }

    // --- T1-CJ: getCurrentSubGoal delegates root completion to autoCompleteRootIfSatisfied ---

    @Test
    void getCurrentSubGoal_T1CJ_majorityGivenUpChildren_marksRootGivenUpNotCompleted() {
        // Reproduces: currentSubGoalId == null + all direct children GIVEN_UP → root must end
        // GIVEN_UP (not COMPLETED) so that GOAL_EXHAUSTED is raised rather than GOAL_FULFILLED.
        service.resetInitialGoal("root", "Root");

        // Two GIVEN_UP children (majority = all, ratio = 0/2 = 0 < threshold 0.5)
        service.addSubGoal("s1", "Child one", false, "root");
        service.addSubGoal("s2", "Child two", false, "root");
        service.markGoalGivenUp("s1");
        service.markGoalGivenUp("s2");

        // currentSubGoalId is null (both subgoals are terminal)
        service.setCurrentCycleContext(CycleContextData.builder().build());

        GoalEntity result = service.getCurrentSubGoal();

        assertThat(result).isNull();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isTrue();
    }

    @Test
    void getCurrentSubGoal_T1CJ_majorityCompletedChildren_marksRootCompleted() {
        // Verify the COMPLETED path still works after the T1-CJ delegation change.
        service.resetInitialGoal("root", "Root");

        service.addSubGoal("s1", "Child one", false, "root");
        service.addSubGoal("s2", "Child two", false, "root");
        service.markGoalCompleted("s1");
        service.markGoalCompleted("s2");

        service.setCurrentCycleContext(CycleContextData.builder().build());

        GoalEntity result = service.getCurrentSubGoal();

        assertThat(result).isNull();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
    }

    // --- T1-CP: replacement subgoals nested under GIVEN_UP parent, not promoted to root ---

    @Test
    void getRemainingGoals_T1CP_excludesStaleOrphansUnderGivenUpParent() {
        // A stale orphan is an ACTIVE goal under a GIVEN_UP parent whose planningRunId
        // is older than the current run. In practice the cascade ensures these don't exist,
        // but the filter must handle them as a safety net.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("sg1", "Stagnated subgoal under root", true, "root");
        service.addSubGoal("old-child", "Old child of stagnated subgoal", false, "sg1");

        // Mark sg1 GIVEN_UP — cascade marks old-child GIVEN_UP too
        service.markGoalGivenUp("sg1");

        // Manually revert old-child to ACTIVE and stamp a prior planningRunId to simulate a stale orphan
        service.getGoalById("old-child").setStatus(GoalStatus.ACTIVE);
        service.getGoalById("old-child").getMetadata().put("planningRunId", 0);

        List<GoalEntity> remaining = service.getRemainingGoals();
        assertThat(remaining).extracting(GoalEntity::getId).doesNotContain("old-child");
    }

    @Test
    void getRemainingGoals_T1CP_includesFreshInjectionsUnderGivenUpParent() {
        // Fresh replacement goals injected under a GIVEN_UP parent (T1-CP) carry the
        // current planningRunId and must appear in getRemainingGoals().
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("sg1", "Stagnated subgoal under root", true, "root");
        service.markGoalGivenUp("sg1");

        // T1-CP: inject fresh replacement directly under the GIVEN_UP subgoal
        service.addSubGoal("fresh-replacement", "Fresh replacement subgoal with valid description", true, "sg1");

        List<GoalEntity> remaining = service.getRemainingGoals();
        assertThat(remaining).extracting(GoalEntity::getId).contains("fresh-replacement");
    }

    // --- T1-CQ: autoCompleteRootIfSatisfied uses first-wave children for ratio ---

    @Test
    void autoCompleteRootIfSatisfied_T1CQ_usesFirstWaveChildrenForRatio() {
        // 6 first-wave children (planningRunId=1): 4 COMPLETED, 2 GIVEN_UP → ratio = 4/6 = 67% > 50%
        // 4 second-wave children injected at root level (planningRunId=2): all GIVEN_UP
        // Without T1-CQ: 4/10 = 40% ≤ 50% → GIVEN_UP (wrong)
        // With T1-CQ:    4/6  = 67% > 50%  → COMPLETED (correct)
        service.resetInitialGoal("root", "Root");

        service.addSubGoal("fw1", "First wave subgoal one", false, "root");
        service.addSubGoal("fw2", "First wave subgoal two", false, "root");
        service.addSubGoal("fw3", "First wave subgoal three", false, "root");
        service.addSubGoal("fw4", "First wave subgoal four", false, "root");
        service.addSubGoal("fw5", "First wave subgoal five given up", false, "root");
        service.addSubGoal("fw6", "First wave subgoal six given up", false, "root");
        service.markGoalCompleted("fw1");
        service.markGoalCompleted("fw2");
        service.markGoalCompleted("fw3");
        service.markGoalCompleted("fw4");
        service.markGoalGivenUp("fw5");
        service.markGoalGivenUp("fw6");

        // Second-wave replacements at root level (edge case where T1-CP routing was bypassed):
        // stamp planningRunId=2 to simulate a later replan round
        service.addSubGoal("sw1", "Second wave replacement one", false, "root");
        service.addSubGoal("sw2", "Second wave replacement two", false, "root");
        service.addSubGoal("sw3", "Second wave replacement three", false, "root");
        service.addSubGoal("sw4", "Second wave replacement four", false, "root");
        service.getGoalById("sw1").getMetadata().put("planningRunId", 2);
        service.getGoalById("sw2").getMetadata().put("planningRunId", 2);
        service.getGoalById("sw3").getMetadata().put("planningRunId", 2);
        service.getGoalById("sw4").getMetadata().put("planningRunId", 2);
        service.markGoalGivenUp("sw1");
        service.markGoalGivenUp("sw2");
        service.markGoalGivenUp("sw3");
        service.markGoalGivenUp("sw4");

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
    }

    // --- T1-DG: beginPlanningRun increments and stamps the run-id on the root ---

    @Test
    void beginPlanningRun_incrementsCounterAndStampsOnRoot() {
        // Each invocation must advance the counter by 1 and mirror the new value onto the root
        // goal's metadata so that future starts can resume from the correct run-id.
        service.resetInitialGoal("root", "Root");
        int before = (int) service.getRootGoal().getMetadata().getOrDefault("planningRunId", 0);

        int first = service.beginPlanningRun();
        int second = service.beginPlanningRun();
        int third = service.beginPlanningRun();

        assertThat(first).isEqualTo(before + 1);
        assertThat(second).isEqualTo(before + 2);
        assertThat(third).isEqualTo(before + 3);
        assertThat(service.getRootGoal().getMetadata().get("planningRunId")).isEqualTo(third);
    }

    @Test
    void addSubGoalAfterBeginPlanningRun_stampsSubgoalsWithCurrentRunId() {
        // T1-DG: subgoals injected after beginPlanningRun() carry the new run-id, allowing the
        // T1-CQ first-wave filter to distinguish replan waves from the original plan.
        service.resetInitialGoal("root", "Root");

        service.addSubGoal("fw1", "First wave one", false, "root");
        int firstWaveRunId = (int) service.getGoalById("fw1").getMetadata().get("planningRunId");

        service.beginPlanningRun();
        service.addSubGoal("sw1", "Second wave one", false, "root");
        int secondWaveRunId = (int) service.getGoalById("sw1").getMetadata().get("planningRunId");

        assertThat(secondWaveRunId).isEqualTo(firstWaveRunId + 1);
    }

    @Test
    void autoCompleteRootIfSatisfied_T1DG_secondWaveAllGivenUp_firstWaveAllCompleted_marksRootCompleted() {
        // Reproduces the cycle-7 scenario from the T1-DG ticket: 8 first-wave children all
        // COMPLETED, then a second wave of 7 GIVEN_UP siblings injected with a fresh run-id via
        // beginPlanningRun(). The first-wave filter must isolate the original 8 → ratio 8/8 → root
        // COMPLETED rather than being diluted to 8/15.
        service.resetInitialGoal("root", "Root");

        for (int i = 1; i <= 8; i++) {
            service.addSubGoal("fw" + i, "First wave subgoal " + i, false, "root");
            service.markGoalCompleted("fw" + i);
        }

        service.beginPlanningRun();
        for (int i = 1; i <= 7; i++) {
            service.addSubGoal("sw" + i, "Second wave subgoal " + i, false, "root");
            service.markGoalGivenUp("sw" + i);
        }

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
    }

    // --- T1-DH: pre-plan auto-complete fires before re-plan callback ---

    @Test
    void promoteCurrentSubGoal_T1DH_lastFirstWaveSatisfiesRatio_skipsReplanCallback() {
        // T1-DH integration: simulate the cycle-7 scenario at the promote site. With 7 first-wave
        // already completed plus one ACTIVE subgoal, promoting the eighth pushes the ratio above
        // threshold. The pre-plan auto-complete check must fire before the re-plan callback would
        // be triggered for root, leaving the planner uninvoked and root marked COMPLETED.
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        service.resetInitialGoal("root", "Root");
        for (int i = 1; i <= 7; i++) {
            service.addSubGoal("fw" + i, "First wave subgoal " + i, false, "root");
            service.markGoalCompleted("fw" + i);
        }
        service.addSubGoal("fw8", "Last first wave subgoal", true, "root");
        assertEquals("fw8", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        verify(mockCallback, never()).planInto(any(CycleContextData.class), anyString());
    }

    @Test
    void promoteCurrentSubGoal_T1DH_lastFirstWaveBelowRatio_marksRootGivenUpAndSkipsCallback() {
        // T1-DH: when the final promotion pushes the ratio below threshold, autoComplete marks
        // root GIVEN_UP and the re-plan callback is still skipped. The loop halts via the
        // GOAL_EXHAUSTED signal raised by autoComplete rather than churning through more LLM
        // calls on a structurally-failed branch.
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        service.resetInitialGoal("root", "Root");
        for (int i = 1; i <= 5; i++) {
            service.addSubGoal("fw" + i, "First wave subgoal " + i, false, "root");
            service.markGoalGivenUp("fw" + i);
        }
        service.addSubGoal("fw6", "Last first wave subgoal", true, "root");
        assertEquals("fw6", service.getCurrentSubGoal().getId());

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.promoteCurrentSubGoal();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isTrue();
        verify(mockCallback, never()).planInto(any(CycleContextData.class), anyString());
    }

    // --- T1-CN: stale REPLAN_REQUESTED must not block root completion when all children are terminal ---

    @Test
    void getCurrentSubGoal_T1CN_allChildrenTerminal_withReplanPending_marksRootCompleted() {
        // Reproduces: currentSubGoalId == null + all direct children COMPLETED + REPLAN_REQUESTED present.
        // The stale REPLAN_REQUESTED was raised by T1-BK in a prior cycle (dedup-blocked injection),
        // but all work is genuinely done — root must be marked COMPLETED and planner not re-entered.
        service.resetInitialGoal("root", "Root");

        service.addSubGoal("s1", "Child one", false, "root");
        service.addSubGoal("s2", "Child two", false, "root");
        service.markGoalCompleted("s1");
        service.markGoalCompleted("s2");

        // Inject stale REPLAN_REQUESTED into the cycle context
        CycleContextData ctxWithReplan = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.2, "test-stale-replan"));
        service.setCurrentCycleContext(ctxWithReplan);

        GoalEntity result = service.getCurrentSubGoal();

        assertThat(result).isNull();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    // ── T3-BN: coverage retrospective ──────────────────────────────────────

    private void buildLowRatioTree() {
        // 4 GIVEN_UP, 2 COMPLETED → ratio = 2/6 ≈ 0.33 ≤ 0.5 threshold → normally GIVEN_UP
        service.resetInitialGoal("root", "Build a REST API with POST /members and GET /members endpoints");
        service.addSubGoal("g1", "POST /members endpoint", false, "root");
        service.addSubGoal("g2", "GET /members endpoint", false, "root");
        service.addSubGoal("g3", "Child three valid text", false, "root");
        service.addSubGoal("g4", "Child four valid text", false, "root");
        service.addSubGoal("g5", "Child five valid text", false, "root");
        service.addSubGoal("g6", "Child six valid text", false, "root");
        service.markGoalGivenUp("g1");
        service.markGoalGivenUp("g2");
        service.markGoalGivenUp("g3");
        service.markGoalGivenUp("g4");
        service.markGoalCompleted("g5");
        service.markGoalCompleted("g6");
        service.setCurrentCycleContext(CycleContextData.builder().build());
    }

    @Test
    void autoCompleteRootIfSatisfied_lowRatio_noRetro_proceedsToGivenUp() {
        // Regression guard: low ratio with no retrospective wired → GIVEN_UP as before.
        buildLowRatioTree();

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isTrue();
    }

    // ── T2-CH: active-descendants guard ────────────────────────────────────

    @Test
    void autoCompleteRootIfSatisfied_T2CH_givenUpChildWithActiveDescendant_defersCompletion() {
        // T2-CH regression test: the session-2026-04-21 failure mode.
        // Planner injected replacement subgoals under a GIVEN_UP child (T1-CP).
        // autoCompleteRootIfSatisfied must defer when those replacements are still ACTIVE,
        // not immediately mark root COMPLETED because the first-wave ratio is > threshold.
        service.resetInitialGoal("root", "REST API with GET /loans endpoint");
        // First wave: 6 COMPLETED + 1 GIVEN_UP = 7 children → ratio 6/7 ≈ 0.86 > 0.5
        service.addSubGoal("g1", "Child one valid text", false, "root");
        service.addSubGoal("g2", "Child two valid text", false, "root");
        service.addSubGoal("g3", "Child three valid text", false, "root");
        service.addSubGoal("g4", "Child four valid text", false, "root");
        service.addSubGoal("g5", "Child five valid text", false, "root");
        service.addSubGoal("g6", "Child six valid text", false, "root");
        service.addSubGoal("g7", "Implement GET /loans endpoint", false, "root");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        service.markGoalCompleted("g3");
        service.markGoalCompleted("g4");
        service.markGoalCompleted("g5");
        service.markGoalCompleted("g6");
        service.markGoalGivenUp("g7");
        // T1-CP: planner injected a replacement under the GIVEN_UP g7 — this is ACTIVE
        service.addSubGoal("r1", "Add GET /loans retrieval logic", true, "g7");

        service.setCurrentCycleContext(CycleContextData.builder().build());

        service.autoCompleteRootIfSatisfied();

        // Must defer — r1 is still ACTIVE under a GIVEN_UP first-wave child
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.REPLAN_REQUESTED)).isFalse();
    }

    @Test
    void autoCompleteRootIfSatisfied_T2CH_givenUpChildWithTerminalDescendant_proceeds() {
        // T2-CH: once the replacement under the GIVEN_UP child is also terminal,
        // auto-completion must fire normally (not defer forever).
        service.resetInitialGoal("root", "REST API");
        service.addSubGoal("g1", "Child one valid text", false, "root");
        service.addSubGoal("g2", "Child two valid text", false, "root");
        service.addSubGoal("g3", "Child three valid text", false, "root");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        service.markGoalGivenUp("g3");
        // Replacement under g3 — also GIVEN_UP (no longer active)
        service.addSubGoal("r1", "Replacement for child three", false, "g3");
        service.markGoalGivenUp("r1");

        service.setCurrentCycleContext(CycleContextData.builder().build());

        service.autoCompleteRootIfSatisfied();

        // No active descendants → auto-complete fires. 2/3 = 0.67 > 0.5 → COMPLETED
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    // ── triggerManualCoverageReview ──────────────────────────────────────────

    @Test
    void triggerManualCoverageReview_findsGaps_injectsSubgoalsAndRaisesReplan() {
        service.resetInitialGoal("root", "Build a REST API with all endpoints");
        service.addSubGoal("g1", "Implement GET /books endpoint", false, "root");
        service.addSubGoal("g2", "Implement POST /loans endpoint", false, "root");
        service.markGoalCompleted("g1");
        service.markGoalGivenUp("g2");

        CoverageRetrospectiveService retro = mock(CoverageRetrospectiveService.class);
        when(retro.findMissingFeatures(any(), any()))
                .thenReturn(List.of("Implement GET /loans endpoint"));
        service.setCoverageRetrospectiveService(retro);
        service.setCurrentCycleContext(CycleContextData.builder().build());

        CoverageReviewResult result = service.triggerManualCoverageReview();

        assertThat(result.injected()).containsExactly("Implement GET /loans endpoint");
        assertThat(result.dropped()).isZero();
        assertThat(service.getSubGoals("root")).anyMatch(g -> g.getDescription().contains("GET /loans"));
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void triggerManualCoverageReview_noGaps_returnsEmpty() {
        service.resetInitialGoal("root", "Build a REST API");
        service.addSubGoal("g1", "Implement GET /books endpoint", false, "root");
        service.markGoalCompleted("g1");

        CoverageRetrospectiveService retro = mock(CoverageRetrospectiveService.class);
        when(retro.findMissingFeatures(any(), any())).thenReturn(List.of());
        service.setCoverageRetrospectiveService(retro);
        service.setCurrentCycleContext(CycleContextData.builder().build());

        CoverageReviewResult result = service.triggerManualCoverageReview();

        assertThat(result.injected()).isEmpty();
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.REPLAN_REQUESTED)).isFalse();
    }

    @Test
    void triggerManualCoverageReview_completedRoot_resetsToActive() {
        service.resetInitialGoal("root", "Build a REST API");
        service.addSubGoal("g1", "Implement GET /books endpoint", false, "root");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("root");

        CoverageRetrospectiveService retro = mock(CoverageRetrospectiveService.class);
        when(retro.findMissingFeatures(any(), any()))
                .thenReturn(List.of("Implement POST /books endpoint"));
        service.setCoverageRetrospectiveService(retro);
        service.setCurrentCycleContext(CycleContextData.builder().build());

        service.triggerManualCoverageReview();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void triggerManualCoverageReview_givenUpRoot_resetsToActive() {
        service.resetInitialGoal("root", "Build a REST API");
        service.addSubGoal("g1", "Implement GET /books endpoint", false, "root");
        service.markGoalGivenUp("g1");
        service.markGoalGivenUp("root");

        CoverageRetrospectiveService retro = mock(CoverageRetrospectiveService.class);
        when(retro.findMissingFeatures(any(), any()))
                .thenReturn(List.of("Implement POST /books endpoint"));
        service.setCoverageRetrospectiveService(retro);
        service.setCurrentCycleContext(CycleContextData.builder().build());

        service.triggerManualCoverageReview();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void triggerManualCoverageReview_retroNotWired_returnsEmpty() {
        service.resetInitialGoal("root", "Build a REST API");
        service.addSubGoal("g1", "Implement GET /books endpoint", false, "root");
        service.markGoalGivenUp("g1");
        // No retro wired

        CoverageReviewResult result = service.triggerManualCoverageReview();

        assertThat(result.injected()).isEmpty();
    }

    @Test
    void triggerManualCoverageReview_injectedGoalsJoinFirstWave() {
        // Injected goals get planningRunId=0 so they join the first-wave minimum.
        service.resetInitialGoal("root", "Build a REST API");
        service.addSubGoal("g1", "Implement GET /books endpoint", false, "root");
        service.markGoalGivenUp("g1");

        CoverageRetrospectiveService retro = mock(CoverageRetrospectiveService.class);
        when(retro.findMissingFeatures(any(), any()))
                .thenReturn(List.of("Implement POST /books endpoint"));
        service.setCoverageRetrospectiveService(retro);
        service.setCurrentCycleContext(CycleContextData.builder().build());

        service.triggerManualCoverageReview();

        GoalEntity injected = service.getSubGoals("root").stream()
                .filter(g -> g.getDescription().contains("POST /books"))
                .findFirst().orElseThrow();
        assertThat(injected.getMetadata().get("planningRunId")).isEqualTo(0);
    }

    // ── T1-DD: parent terminal status must reconcile with descendants ──────────

    @Test
    void markGoalCompleted_T1DD_parentWithActiveChild_isNoOpAndRePointsCurrent() {
        // A parent must not flip to COMPLETED while a direct child is still ACTIVE.
        // The guard refuses the flip and re-points currentSubGoalId to the active leaf
        // so the descendant remains reachable.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent subgoal valid description", false, "root");
        service.addSubGoal("child", "Child subgoal valid description", false, "parent");

        // Force currentSubGoalId off the child to verify the guard re-points it.
        service.setCurrentSubGoal("parent");

        service.markGoalCompleted("parent");

        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getGoalById("child").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getCurrentSubGoal()).isNotNull();
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("child");
    }

    @Test
    void markGoalCompleted_T1DD_parentWithAllTerminalChildren_proceeds() {
        // Regression check: when every direct child is terminal, completion must still fire.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent subgoal valid description", false, "root");
        service.addSubGoal("c1", "First child valid description", false, "parent");
        service.addSubGoal("c2", "Second child valid description", false, "parent");
        service.markGoalCompleted("c1");
        service.markGoalGivenUp("c2");

        service.markGoalCompleted("parent");

        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void markGoalCompleted_T1DD_rootWithAllTerminalChildren_completes() {
        // Root-completion path is unchanged: when first-wave children are terminal and the ratio
        // crosses the threshold, autoCompleteRootIfSatisfied flips root to COMPLETED.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Child one valid description", false, "root");
        service.addSubGoal("g2", "Child two valid description", false, "root");
        service.addSubGoal("g3", "Child three valid description", false, "root");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        service.markGoalGivenUp("g3");
        service.setCurrentCycleContext(CycleContextData.builder().build());

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void applyGoalProgress_T1DD_nonCurrentFulfilledWithActiveDescendants_isNoOp() {
        // The non-current FULFILLED branch routes through markGoalCompleted, so the guard
        // applies there too: a node with ACTIVE direct children stays ACTIVE.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent subgoal valid description", false, "root");
        service.addSubGoal("child", "Child subgoal valid description", false, "parent");
        service.addSubGoal("other", "Other current subgoal valid description", false, "root");
        service.setCurrentSubGoal("other"); // make 'parent' non-current

        service.applyGoalProgress("parent", GoalProgressState.FULFILLED);

        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getGoalById("child").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void assertGoalTreeIntegrity_T1DD_completedNodeWithActiveChild_reportsViolation() {
        // The integrity walk must surface pre-existing violations even though the guard prevents
        // new ones. Force the bad state by setting status directly on the entity.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent subgoal valid description", false, "root");
        service.addSubGoal("child", "Child subgoal valid description", false, "parent");
        // Bypass the guard by manipulating the entity directly (simulates a regression).
        service.getGoalById("parent").setStatus(GoalStatus.COMPLETED);

        int violations = service.assertGoalTreeIntegrity();

        assertThat(violations).isEqualTo(1);
    }

    @Test
    void assertGoalTreeIntegrity_T1DD_cleanTree_reportsNoViolations() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Child one valid description", false, "root");
        service.addSubGoal("g2", "Child two valid description", false, "root");
        service.markGoalCompleted("g1");
        service.markGoalGivenUp("g2");

        int violations = service.assertGoalTreeIntegrity();

        assertThat(violations).isZero();
    }

    // ── T1-CZ: analysis-spawned children must not cascade-abandon a criteria-satisfied parent ──

    @Test
    void addSubGoal_analysisRole_tagsSpawnedByAnalysis() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("a1", "Analysis child valid description", false, "root", "analysis", null);
        GoalEntity goal = service.getGoalById("a1");
        assertThat(goal.getMetadata().get("spawnedBy")).isEqualTo("analysis");
    }

    @Test
    void addSubGoal_plannerRole_doesNotTagSpawnedBy() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("p1", "Planner child valid description", false, "root", "planner", null);
        GoalEntity goal = service.getGoalById("p1");
        assertThat(goal.getMetadata().containsKey("spawnedBy")).isFalse();
    }

    @Test
    void exhaustParentIfStuck_onlyAnalysisChildrenExhausted_criteriaNotSatisfied_doesNotExhaust() {
        // Analysis children failing with no merge (criteria not satisfied) → no exhaustion, no promotion
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent valid description", false, "root");
        // No incrementGoalMergeCount → criteria not satisfied (mergeCount=0)
        service.addSubGoal("a1", "Analysis child one valid description", false, "parent", "analysis", null);
        service.addSubGoal("a2", "Analysis child two valid description", false, "parent", "analysis", null);
        service.applyGoalProgress("a1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("a2", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("a2", 1);

        assertThat(result).isFalse();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void exhaustParentIfStuck_onlyAnalysisChildrenExhausted_criteriaSatisfied_promotesParentToFulfilled() {
        // Parent had a merge (criteria satisfied via fallback). Analysis children both GIVEN_UP.
        // Expected: promoted to FULFILLED (COMPLETED in storage) rather than GIVEN_UP.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent valid description", false, "root");
        service.incrementGoalMergeCount("parent"); // criteria fallback: mergeCount > 0 → satisfied
        service.addSubGoal("a1", "Analysis child one valid description", false, "parent", "analysis", null);
        service.addSubGoal("a2", "Analysis child two valid description", false, "parent", "analysis", null);
        service.applyGoalProgress("a1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("a2", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("a2", 1);

        assertThat(result).isTrue();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void exhaustParentIfStuck_mixedRegularAndAnalysisChildren_firesOnRegularThreshold() {
        // 3 regular children GIVEN_UP (threshold=3 → fires), 1 analysis child also GIVEN_UP.
        // The analysis child should not affect the threshold or block exhaustion.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent valid description", false, "root");
        service.addSubGoal("r1", "Regular child one valid description", false, "parent");
        service.addSubGoal("r2", "Regular child two valid description", false, "parent");
        service.addSubGoal("r3", "Regular child three valid description", false, "parent");
        service.addSubGoal("a1", "Analysis child valid description", false, "parent", "analysis", null);
        service.applyGoalProgress("r1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("r2", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("r3", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("a1", GoalProgressState.GIVEN_UP);

        boolean result = service.exhaustParentIfStuck("a1", 3);

        assertThat(result).isTrue();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void exhaustParentIfStuck_analysisChildrenAloneDoNotMeetRegularThreshold_noExhaustion() {
        // 2 regular children still active; analysis children GIVEN_UP — threshold not met by regular count.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent valid description", false, "root");
        service.addSubGoal("r1", "Regular child one valid description", true, "parent");
        service.addSubGoal("r2", "Regular child two valid description", false, "parent");
        service.addSubGoal("a1", "Analysis child valid description", false, "parent", "analysis", null);
        service.addSubGoal("a2", "Analysis child two valid description", false, "parent", "analysis", null);
        service.applyGoalProgress("a1", GoalProgressState.GIVEN_UP);
        service.applyGoalProgress("a2", GoalProgressState.GIVEN_UP);
        // r1 and r2 still ACTIVE

        boolean result = service.exhaustParentIfStuck("a2", 3);

        assertThat(result).isFalse();
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    // ── T1-DE: coverage-review dedup and session budget ──────────────────────────

    @Test
    void triggerManualCoverageReview_dropsParaphrasedGivenUpProposals() {
        service.resetInitialGoal("root", "Build a REST API");
        service.addSubGoal("g1", "Already tried feature", false, "root");
        service.markGoalGivenUp("g1");

        CoverageRetrospectiveService retro = mock(CoverageRetrospectiveService.class);
        when(retro.findMissingFeatures(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of("Already tried feature paraphrase", "Fresh new feature to implement"));
        service.setCoverageRetrospectiveService(retro);
        service.setCurrentCycleContext(CycleContextData.builder().build());

        GoalSimilarityService mockSimilarity = mock(GoalSimilarityService.class);
        when(mockSimilarity.countGivenUpMatches("Already tried feature paraphrase")).thenReturn(1L);
        when(mockSimilarity.countGivenUpMatches("Fresh new feature to implement")).thenReturn(0L);
        service.setGoalSimilarityService(mockSimilarity);

        CoverageReviewResult result = service.triggerManualCoverageReview();

        assertThat(result.injected()).containsExactly("Fresh new feature to implement");
        assertThat(result.dropped()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // T1-EL: repairCurrentSubGoalIfStale
    // ---------------------------------------------------------------------------------

    @Test
    void repairCurrentSubGoalIfStale_returnsNull_whenPointerHealthy() {
        service.resetInitialGoal("root", "Main");
        service.addSubGoal("a", "Step A", true, "root");

        GoalPlannerService.SubGoalRepair repair = service.repairCurrentSubGoalIfStale();

        assertThat(repair).isNull();
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("a");
    }

    @Test
    void repairCurrentSubGoalIfStale_returnsNull_whenPointerNull() {
        service.resetInitialGoal("root", "Main");
        // No subgoals — currentSubGoalId is null.

        GoalPlannerService.SubGoalRepair repair = service.repairCurrentSubGoalIfStale();

        assertThat(repair).isNull();
    }

    @Test
    void repairCurrentSubGoalIfStale_repairs_whenPointerReferencesGivenUpGoal() {
        service.resetInitialGoal("root", "Main");
        service.addSubGoal("a", "Step A (will be given up)", false, "root");
        service.addSubGoal("b", "Step B (active replacement)", false, "root");

        // Force the pointer onto "a", then mark "a" GIVEN_UP without going through promotion
        // (mirrors the 22:39 cascade situation where the pointer survived the cascade).
        service.setCurrentSubGoal("a");
        service.markGoalGivenUp("a");
        // markGoalGivenUp's cascade fallback may have advanced the pointer; force it back so
        // we exercise the repair path explicitly.
        // Reach in via reflection to set the field — package-private would be cleaner but the
        // test asserts the invariant that the public repair API rescues the loop.
        try {
            var field = GoalPlannerService.class.getDeclaredField("currentSubGoalId");
            field.setAccessible(true);
            field.set(service, "a");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        GoalPlannerService.SubGoalRepair repair = service.repairCurrentSubGoalIfStale();

        assertThat(repair).isNotNull();
        assertThat(repair.oldId()).isEqualTo("a");
        assertThat(repair.newId()).isEqualTo("b");
        assertThat(repair.reason()).isEqualTo("given_up");
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("b");
    }

    @Test
    void repairCurrentSubGoalIfStale_repairs_whenPointerReferencesMissingGoal() {
        service.resetInitialGoal("root", "Main");
        service.addSubGoal("a", "Step A", true, "root");

        // Force pointer to a non-existent id.
        try {
            var field = GoalPlannerService.class.getDeclaredField("currentSubGoalId");
            field.setAccessible(true);
            field.set(service, "ghost-id");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        GoalPlannerService.SubGoalRepair repair = service.repairCurrentSubGoalIfStale();

        assertThat(repair).isNotNull();
        assertThat(repair.oldId()).isEqualTo("ghost-id");
        assertThat(repair.newId()).isEqualTo("a");
        assertThat(repair.reason()).isEqualTo("missing");
    }

}
