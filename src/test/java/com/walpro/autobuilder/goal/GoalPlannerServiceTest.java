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
    private GoalPlannerConfigProperties config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        goalGraphService = mock(GoalGraphService.class);
        config = mock(GoalPlannerConfigProperties.class);

        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);
        // T2-EG: the coverage gate is off-by-default in production now; the coverage-gate cases below
        // validate the (opt-in) incumbent behaviour, so enable it for the suite. The dedicated
        // default-off case overrides this back to false.
        lenient().when(config.isCompletionCoverageGateEnabled()).thenReturn(true);

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

    /**
     * T2-DR: helper — declare one acceptance criterion and bank it as passing for each
     * given subgoal. Lets ratio-era tests keep expressing "this work was done" semantics
     * under the evidence-ledger closure rule. Apply BEFORE marking the goal terminal.
     */
    private void seedCriteriaPassed(String... goalIds) {
        for (String id : goalIds) {
            service.setAcceptanceCriteria(id, List.of("done"));
            service.saveCriteriaPassStatus(id, java.util.Map.of(1, true));
        }
    }

    @Test
    void addSubGoal_buildFixDescriptionPrefix_stampsBlockerMetadata() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("bf", "[BUILD_FIX] Resolve missing symbol Foo referenced by Bar", false, "root");
        service.addSubGoal("plain", "Implement the Foo feature", false, "root");

        assertThat(service.getGoalById("bf").getMetadata())
                .containsEntry(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY,
                        com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX);
        assertThat(service.getGoalById("plain").getMetadata())
                .doesNotContainKey(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY);
    }

    @Test
    void addSubGoal_defectStatementDescription_normalizedWithProvenance() {
        service.resetInitialGoal("root", "Main Goal");

        String defect = "GET /loans?memberId={id} endpoint does not list all loans for a member including returned ones";
        service.addSubGoal("d", defect, false, "root");
        service.addSubGoal("r", "The endpoint returns HTTP 404 when the record does not exist", false, "root");

        GoalEntity normalized = service.getGoalById("d");
        assertThat(normalized.getDescription())
                .isEqualTo("GET /loans?memberId={id} endpoint must list all loans for a member including returned ones");
        assertThat(normalized.getMetadata()).containsEntry("normalizedFrom", defect);

        GoalEntity requirement = service.getGoalById("r");
        assertThat(requirement.getDescription())
                .isEqualTo("The endpoint returns HTTP 404 when the record does not exist");
        assertThat(requirement.getMetadata()).doesNotContainKey("normalizedFrom");
    }

    @Test
    void setAcceptanceCriteria_dropsStructuralPlaceholder_keepsRealCriteria() {
        // Run 4 (2026-07-03): the planner-prompted placeholder was persisted as a criterion and,
        // unconfirmable against code by construction, led every T4-BP-3 closure block.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("s", "Implement the Foo feature", false, "root");

        service.setAcceptanceCriteria("s", List.of(
                "* (subgoal is structural — no observable output criterion)",
                "POST /foo returns HTTP 201 with the stored entity"));

        assertThat(service.getGoalById("s").getMetadata().get("acceptanceCriteria"))
                .isEqualTo(List.of("POST /foo returns HTTP 201 with the stored entity"));
    }

    @Test
    void injectCoverageGaps_reopensContradictedTwin_insteadOfInjectingCopy() {
        // Run 7 (2026-07-03): D2 re-detected the same missing endpoint 8×; each injection minted
        // a twin while 4 earlier twins sat falsely COMPLETED. The re-detected gap is deterministic
        // proof the completion is false — the twin reopens, no copy is created.
        service.resetInitialGoal("root", "Main Goal");
        String gap = "Provide the POST /loans endpoint named in the goal specification — no request mapping for it exists in the output.";
        service.addSubGoal("twin", gap, false, "root");
        service.setAcceptanceCriteria("twin", List.of("the endpoint responds"));
        service.saveCriteriaPassStatus("twin", java.util.Map.of(1, true));
        service.getGoalById("twin").setStatus(GoalStatus.COMPLETED);
        long before = service.getAllGoals().size();

        CoverageReviewResult result = service.injectCoverageGaps(service.getGoalById("root"), List.of(gap));

        assertThat(service.getAllGoals()).hasSize((int) before);
        assertThat(service.getGoalById("twin").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getGoalById("twin").getMetadata()).containsEntry("closureEdgeReopens", 1);
        // The banked pass-map belongs to the contradicted completion: cleared, or T3-BL's
        // all-criteria-banked early exit re-promotes the goal next cycle with zero work
        // (run 9, 2026-07-04: 262 reopens of one goal at ~4/min).
        assertThat(service.getGoalById("twin").getMetadata()).doesNotContainKey("criteriaPassStatus");
        assertThat(result.injected()).containsExactly(gap);
        assertThat(result.injectedIds()).containsExactly("twin");
    }

    @Test
    void injectCoverageGaps_repeatReopen_isNotBrakeEvidence() {
        service.resetInitialGoal("root", "Main Goal");
        String gap = "Provide the POST /loans endpoint named in the goal specification — no request mapping for it exists in the output.";
        service.addSubGoal("twin", gap, false, "root");
        service.getGoalById("twin").setStatus(GoalStatus.COMPLETED);

        long base = service.countClosureEdgeInjections();
        service.injectCoverageGaps(service.getGoalById("root"), List.of(gap));
        long afterFirst = service.countClosureEdgeInjections();

        // Falsely re-promoted between closure visits — the promote/reopen oscillation.
        service.getGoalById("twin").setStatus(GoalStatus.COMPLETED);
        CoverageReviewResult second = service.injectCoverageGaps(service.getGoalById("root"), List.of(gap));

        assertThat(afterFirst).isEqualTo(base + 1);
        assertThat(service.countClosureEdgeInjections())
                .as("a repeat reopen must not reset the convergence brake")
                .isEqualTo(afterFirst);
        assertThat(service.getGoalById("twin").getMetadata()).containsEntry("closureEdgeReopens", 2);
        assertThat(second.injected()).containsExactly(gap);
    }

    @Test
    void injectCoverageGaps_activeTwin_blocksWithoutDuplicating() {
        service.resetInitialGoal("root", "Main Goal");
        String gap = "Provide the GET /books endpoint named in the goal specification — no request mapping for it exists in the output.";
        service.addSubGoal("twin", gap, false, "root");
        long before = service.getAllGoals().size();

        CoverageReviewResult result = service.injectCoverageGaps(service.getGoalById("root"), List.of(gap));

        // Closure still blocks (the gap is real, work is queued) but no copy is created and no
        // brake-progress id is minted for an unchanged tree.
        assertThat(service.getAllGoals()).hasSize((int) before);
        assertThat(result.injected()).containsExactly(gap);
        assertThat(result.injectedIds()).isEmpty();
    }

    @Test
    void injectCoverageGaps_freshGap_createsSubgoal() {
        service.resetInitialGoal("root", "Main Goal");
        String gap = "Provide the GET /members/{} endpoint named in the goal specification — no request mapping for it exists in the output.";
        long before = service.getAllGoals().size();

        CoverageReviewResult result = service.injectCoverageGaps(service.getGoalById("root"), List.of(gap));

        assertThat(service.getAllGoals()).hasSize((int) before + 1);
        assertThat(result.injected()).containsExactly(gap);
        assertThat(result.injectedIds()).hasSize(1);
        assertThat(service.getGoalById(result.injectedIds().get(0)).getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void setAcceptanceCriteria_allPlaceholders_leavesGoalCriteriaLess() {
        // A purely structural subgoal stays criteria-less — its description doubles as the
        // requirement downstream; the sentinel must never become a declared criterion.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("s", "Refactor controller wiring", false, "root");

        service.setAcceptanceCriteria("s", List.of(
                RequirementPhrasingNormalizer.STRUCTURAL_CRITERION_PLACEHOLDER));

        assertThat(service.getGoalById("s").getMetadata()).doesNotContainKey("acceptanceCriteria");
    }

    @Test
    void countCompletedSubgoalsWithMerges_countsVerifiedCriteriaCompletions_withoutOwnMerge() {
        // T4-I measure extension: rescue-promoted subgoals complete with verified banked
        // criteria but no mergeCount of their own (work landed under siblings) — they are
        // evidence-backed convergence progress and must move the brake's count.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("m", "merged work", false, "root");
        service.incrementGoalMergeCount("m");
        service.getGoalById("m").setStatus(GoalStatus.COMPLETED);

        service.addSubGoal("v", "verified rescue promotion", false, "root");
        service.setAcceptanceCriteria("v", List.of("c1", "c2"));
        service.saveCriteriaPassStatus("v", java.util.Map.of(1, true, 2, true));
        service.getGoalById("v").setStatus(GoalStatus.COMPLETED);

        // Completed but neither merged nor verified — must NOT count.
        service.addSubGoal("n", "bare completion", false, "root");
        service.getGoalById("n").setStatus(GoalStatus.COMPLETED);

        assertThat(service.countCompletedSubgoalsWithMerges()).isEqualTo(2);
    }

    @Test
    void rescopeResidueSubGoal_createsFollowUpWithUnmetCriteria_andSubsumesOriginal() {
        // T4-BP-4: partial verification at the give-up edge — unmet criteria become one
        // focused follow-up; the exhausted goal passes the closure ledger as SUBSUMED.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Validate book and member before loan creation", true, "root");
        service.setAcceptanceCriteria("x", List.of("helpers exist", "404 when book or member missing"));

        var residueId = service.rescopeResidueSubGoal("x", List.of("404 when book or member missing"));

        assertThat(residueId).isPresent();
        GoalEntity original = service.getGoalById("x");
        assertThat(original.getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        GoalEntity residue = service.getGoalById(residueId.get());
        assertThat(residue.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(residue.getRole()).isEqualTo(GoalPlannerService.VERIFICATION_RESIDUE_ROLE);
        assertThat(residue.getParentId()).isEqualTo("root");
        assertThat(residue.getMetadata().get("acceptanceCriteria"))
                .isEqualTo(List.of("404 when book or member missing"));
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo(residueId.get());
    }

    @Test
    void rescopeResidueSubGoal_oneGenerationOnly_residueGoalsAreNotRescoped() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Validate input shapes", true, "root");
        service.setAcceptanceCriteria("x", List.of("a", "b"));

        var gen1 = service.rescopeResidueSubGoal("x", List.of("b"));
        assertThat(gen1).isPresent();

        var gen2 = service.rescopeResidueSubGoal(gen1.get(), List.of("b"));
        assertThat(gen2).isEmpty();
        assertThat(service.getGoalById(gen1.get()).getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void rescopeResidueSubGoal_emptyUnmetOrUnknownGoal_noOp() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Some work", true, "root");

        assertThat(service.rescopeResidueSubGoal("x", List.of())).isEmpty();
        assertThat(service.rescopeResidueSubGoal("nope", List.of("c"))).isEmpty();
        assertThat(service.getGoalById("x").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void divideExhaustedSubGoal_createsPerCriterionChildren_inheritsRequiredOrigin_andSubsumesOriginal() {
        // T2-EJ: a 0-pass exhausted subgoal with multiple unmet criteria is divided into
        // per-criterion subgoals. The children must inherit the REQUIRED origin — addSubGoal's
        // own rule would stamp the injected role as EXTRA and the slice-1a closure would absorb
        // their failures, falsely completing the root.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Implement the loan creation endpoint", true, "root");
        service.setAcceptanceCriteria("x", List.of(
                "404 when book or member missing", "409 when book already on loan", "422 when member has too many active loans"));

        List<String> childIds = service.divideExhaustedSubGoal("x", List.of(
                "404 when book or member missing", "409 when book already on loan", "422 when member has too many active loans"));

        assertThat(childIds).hasSize(3);
        assertThat(service.getGoalById("x").getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        GoalEntity first = service.getGoalById(childIds.get(0));
        assertThat(first.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(first.getRole()).isEqualTo(GoalPlannerService.DIVIDED_CRITERION_ROLE);
        assertThat(first.getParentId()).isEqualTo("root");
        assertThat(first.getMetadata().get("acceptanceCriteria"))
                .isEqualTo(List.of("404 when book or member missing"));
        for (String id : childIds) {
            assertThat(service.getGoalById(id).getMetadata())
                    .containsEntry(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
        }
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo(childIds.get(0));
    }

    @Test
    void divideExhaustedSubGoal_oneDivisionLevelOnly_dividedAndResidueGoalsAreNotDivided() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Implement the loan endpoints", true, "root");
        service.setAcceptanceCriteria("x", List.of("a", "b"));

        List<String> gen1 = service.divideExhaustedSubGoal("x", List.of("a", "b"));
        assertThat(gen1).hasSize(2);
        assertThat(service.divideExhaustedSubGoal(gen1.get(0), List.of("a1", "a2"))).isEmpty();
        assertThat(service.getGoalById(gen1.get(0)).getStatus()).isEqualTo(GoalStatus.ACTIVE);

        service.addSubGoal("y", "Validate input shapes", true, "root");
        service.setAcceptanceCriteria("y", List.of("c", "d"));
        var residue = service.rescopeResidueSubGoal("y", List.of("c", "d"));
        assertThat(residue).isPresent();
        assertThat(service.divideExhaustedSubGoal(residue.get(), List.of("c", "d"))).isEmpty();
    }

    @Test
    void divideExhaustedSubGoal_fewerThanTwoDistinctCriteria_noOp() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Some work", true, "root");

        assertThat(service.divideExhaustedSubGoal("x", List.of())).isEmpty();
        assertThat(service.divideExhaustedSubGoal("x", List.of("only one"))).isEmpty();
        // Two entries that dedupe to one distinct criterion — nothing to split.
        assertThat(service.divideExhaustedSubGoal("x", List.of("same", " same "))).isEmpty();
        assertThat(service.divideExhaustedSubGoal("nope", List.of("a", "b"))).isEmpty();
        assertThat(service.getGoalById("x").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void divideExhaustedSubGoal_allChildrenFilteredByGivenUpDedup_noOp() {
        // T1-DE dedup: a child description matching an already-GIVEN_UP goal is not re-proposed;
        // when every child is filtered, the caller proceeds with the normal give-up.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("x", "Some work", true, "root");
        GoalSimilarityService similarity = mock(GoalSimilarityService.class);
        when(similarity.countGivenUpMatches(anyString()))
                .thenReturn((long) GoalSimilarityService.GIVEN_UP_RETRY_LIMIT);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "goalSimilarityService", similarity);

        assertThat(service.divideExhaustedSubGoal("x", List.of("a", "b"))).isEmpty();
        assertThat(service.getGoalById("x").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void testDeepGoalTreeAndSubgoalPromotion() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("a", "Step A", true, "root");
        service.addSubGoal("b", "Step B", true, "a");
        service.addSubGoal("c", "Step C", true, "b");
        seedCriteriaPassed("a", "b", "c");

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
        seedCriteriaPassed("g1");
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
        seedCriteriaPassed("g1");
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
        seedCriteriaPassed("g1");
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
        seedCriteriaPassed("g1");

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
        seedCriteriaPassed("g1");

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
    void autoCompleteRootIfSatisfied_T2DR_allCompletedAndSubsumed_marksRootCompleted() {
        // T2-DR: 4 COMPLETED with banked criteria + 1 GIVEN_UP whose criteria were already
        // banked-true at give-up time (which auto-promotes it to SUBSUMED). The evidence-
        // ledger closure rule accepts both states and marks root COMPLETED.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Child one valid text", false, "root");
        service.addSubGoal("g2", "Child two valid text", false, "root");
        service.addSubGoal("g3", "Child three valid text", false, "root");
        service.addSubGoal("g4", "Child four valid text", false, "root");
        service.addSubGoal("g5", "Child five valid text", false, "root");
        seedCriteriaPassed("g1", "g2", "g3", "g4", "g5");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        service.markGoalCompleted("g3");
        service.markGoalCompleted("g4");
        // g5 was given up but its criteria are already banked-true → promoted to SUBSUMED
        service.markGoalGivenUp("g5");
        assertThat(service.getGoalById("g5").getStatus()).isEqualTo(GoalStatus.SUBSUMED);

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void autoCompleteRootIfSatisfied_deferredWhileReplanInFlight() {
        // Replan atomicity: the planner prunes before it injects; the transiently empty
        // active set must not complete the root mid-replan (2026-06-08 23:53:26 false positive).
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only child valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.beginPlanning();
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);

        service.endPlanning();
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRootIfSatisfied_coverageGap_injectsInsteadOfCompleting() {
        // Coverage gate: all loaded subgoals closed clean, but the retrospective reports a
        // root-level feature that was never targeted — inject it, don't complete.
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        when(coverage.reviewMissingFeatures(anyString(), anyList()))
                .thenReturn(java.util.Optional.of(List.of("Implement POST /loans endpoint")));
        service.setCoverageRetrospectiveService(coverage);

        service.resetInitialGoal("root", "Library API with books and loans");
        service.addSubGoal("g1", "Books endpoint valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getRemainingGoals())
                .anySatisfy(g -> assertThat(g.getDescription()).isEqualTo("Implement POST /loans endpoint"));
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void markGoalGivenUp_T4AJ_semanticAdoptiveParent_winsOverAncestor() {
        // T4-AJ Part A: a merge-bearing child of a dying parent is re-parented to the
        // reviewer's semantic match, not the structural nearest-ACTIVE-ancestor.
        var adoption = mock(GoalAdoptionService.class);
        service.setGoalAdoptionService(adoption);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("dying-parent", "Old loans branch valid text", false, "root");
        service.addSubGoal("orphan", "Implement LoanService validation valid text", false, "dying-parent");
        service.addSubGoal("semantic-target", "Loan service business logic valid text", false, "root");
        service.getGoalById("orphan").getMetadata().put("mergeCount", 1);

        when(adoption.findAdoptiveParent(argThat(g -> g != null && g.getId().equals("orphan")), anyList()))
                .thenReturn(java.util.Optional.of(service.getGoalById("semantic-target")));

        service.markGoalGivenUp("dying-parent");

        GoalEntity orphan = service.getGoalById("orphan");
        assertThat(orphan.getParentId()).isEqualTo("semantic-target");
        assertThat(orphan.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(orphan.getMetadata().get("adoptionSource")).isEqualTo("semantic-reviewer");
    }

    @Test
    void markGoalGivenUp_T4AJ_reviewerEmpty_fallsBackToStructuralAncestor() {
        var adoption = mock(GoalAdoptionService.class);
        when(adoption.findAdoptiveParent(any(), anyList())).thenReturn(java.util.Optional.empty());
        service.setGoalAdoptionService(adoption);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("dying-parent", "Old loans branch valid text", false, "root");
        service.addSubGoal("orphan", "Implement LoanService validation valid text", false, "dying-parent");
        service.getGoalById("orphan").getMetadata().put("mergeCount", 1);

        service.markGoalGivenUp("dying-parent");

        GoalEntity orphan = service.getGoalById("orphan");
        assertThat(orphan.getParentId()).isEqualTo("root");
        assertThat(orphan.getMetadata().get("adoptionSource")).isEqualTo("structural-ancestor");
    }

    @Test
    void autoCompleteRootIfSatisfied_T4BP_gapsVerifiedImplemented_completesInsteadOfInjecting() {
        // T4-BP consumer #2: the description-level reviewer over-reports; candidates the code
        // already satisfies are dropped, and when all drop, the root completes.
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        when(coverage.reviewMissingFeatures(anyString(), anyList()))
                .thenReturn(java.util.Optional.of(List.of("GET /books/{id} (404 if absent)", "POST /loans endpoint")));
        service.setCoverageRetrospectiveService(coverage);

        var verifier = mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        when(verifier.verifyFeatures(anyString(), anyList(), anyMap()))
                .thenReturn(java.util.Map.of(1, true, 2, true));
        when(verifier.verifyCompletion(any(), any(), any())).thenReturn(
                com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.allClear());
        service.setCriteriaVerificationService(verifier);

        service.setCurrentCycleContext(CycleContextData.builder()
                .previousOutput(java.util.Map.of("BookController.java", "code", "LoanController.java", "code"))
                .build());

        service.resetInitialGoal("root", "Library API");
        service.addSubGoal("g1", "Books endpoint valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getRemainingGoals()).isEmpty();
    }

    @Test
    void autoCompleteRootIfSatisfied_T4BP_partiallyVerified_injectsOnlyGenuineGaps() {
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        when(coverage.reviewMissingFeatures(anyString(), anyList()))
                .thenReturn(java.util.Optional.of(List.of("GET /books/{id} (already in code)", "Member loan limit 422 handling")));
        service.setCoverageRetrospectiveService(coverage);

        var verifier = mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        when(verifier.verifyFeatures(anyString(), anyList(), anyMap()))
                .thenReturn(java.util.Map.of(1, true, 2, false));
        service.setCriteriaVerificationService(verifier);

        service.setCurrentCycleContext(CycleContextData.builder()
                .previousOutput(java.util.Map.of("BookController.java", "code"))
                .build());

        service.resetInitialGoal("root", "Library API");
        service.addSubGoal("g1", "Books endpoint valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getRemainingGoals())
                .extracting(GoalEntity::getDescription)
                .containsExactly("Member loan limit 422 handling");
    }

    @Test
    void autoCompleteRootIfSatisfied_T4BP_verificationFails_keepsAllCandidates() {
        // No evidence != implemented: empty pass map keeps the gate's previous behaviour.
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        when(coverage.reviewMissingFeatures(anyString(), anyList()))
                .thenReturn(java.util.Optional.of(List.of("POST /loans endpoint")));
        service.setCoverageRetrospectiveService(coverage);

        var verifier = mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        when(verifier.verifyFeatures(anyString(), anyList(), anyMap())).thenReturn(java.util.Map.of());
        service.setCriteriaVerificationService(verifier);

        service.setCurrentCycleContext(CycleContextData.builder()
                .previousOutput(java.util.Map.of("A.java", "code"))
                .build());

        service.resetInitialGoal("root", "Library API");
        service.addSubGoal("g1", "Books endpoint valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getRemainingGoals())
                .extracting(GoalEntity::getDescription)
                .containsExactly("POST /loans endpoint");
    }

    @Test
    void autoCompleteRootIfSatisfied_coverageClean_completes() {
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        when(coverage.reviewMissingFeatures(anyString(), anyList())).thenReturn(java.util.Optional.of(List.of()));
        service.setCoverageRetrospectiveService(coverage);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only child valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        verify(coverage).reviewMissingFeatures(eq("Root"), anyList());
    }

    @Test
    void peekCurrentSubGoal_isReadOnly_neverDelegatesToClosure() {
        // T1-FD slice 1: a UI tree refresh reached autoCompleteRootIfSatisfied (and its
        // coverage-reviewer LLM call) through getCurrentSubGoal's T1-CN delegation when the
        // tree was all-terminal — on http-nio threads, past the system pause, livelocking
        // the local model (2026-07-02 run 4). Observers peek; only the loop recovers/closes.
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        service.setCoverageRetrospectiveService(coverage);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only child valid text", true, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");
        // All children terminal, pointer cleared — exactly run 4's end state.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "currentSubGoalId", null);

        assertThat(service.peekCurrentSubGoal()).isNull();
        assertThat(service.getRootGoal().getStatus()).isNotEqualTo(GoalStatus.COMPLETED);
        verifyNoInteractions(coverage);

        // A live pointer is returned as-is.
        service.addSubGoal("g2", "Second child valid text", true, "root");
        assertThat(service.peekCurrentSubGoal().getId()).isEqualTo("g2");
        verifyNoInteractions(coverage);
    }

    @Test
    void autoCompleteRootIfSatisfied_coverageReviewerAbstains_defersInsteadOfCompleting() {
        // 2026-07-02 false completion #2: the reviewer timed out and the abstain collapsed into
        // "no gaps" — root COMPLETED with two of three spec resources never decomposed. No
        // result must DEFER the closure attempt, never grant it.
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        when(coverage.reviewMissingFeatures(anyString(), anyList())).thenReturn(java.util.Optional.empty());
        service.setCoverageRetrospectiveService(coverage);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only child valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
        // Nothing injected either — deferral is a wait, not a work item.
        assertThat(service.getRemainingGoals()).isEmpty();
    }

    @Test
    void autoCompleteRootIfSatisfied_T2EG_coverageGateDisabledByDefault_skipsItAndCompletes() {
        // T2-EG: with the coverage gate off (the production default), the completion edge never
        // consults the coverage retrospective — even one that would report a gap. T4-BP-3 is the
        // single home for "is the work done?"; with no verifier wired and all subgoals closed, the
        // root completes without the extra LLM call.
        lenient().when(config.isCompletionCoverageGateEnabled()).thenReturn(false);
        var coverage = mock(com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService.class);
        service.setCoverageRetrospectiveService(coverage);

        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Only child valid text", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        verify(coverage, never()).reviewMissingFeatures(anyString(), anyList());
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
        seedCriteriaPassed("s1", "s2");
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

    // --- T2-DR: evidence-ledger closure rejects any subgoal with unmet criteria ---

    @Test
    void autoCompleteRootIfSatisfied_T2DR_givenUpWithUnmetCriteria_blocksClosure() {
        // T2-DR replaces the first-wave ratio. Even if most subgoals completed cleanly,
        // a single GIVEN_UP subgoal whose acceptance criteria are not banked blocks
        // closure — root flips to GIVEN_UP + GOAL_EXHAUSTED.
        service.resetInitialGoal("root", "Root");

        service.addSubGoal("fw1", "First wave subgoal one", false, "root");
        service.addSubGoal("fw2", "First wave subgoal two", false, "root");
        seedCriteriaPassed("fw1", "fw2");
        service.markGoalCompleted("fw1");
        service.markGoalCompleted("fw2");

        // Later-wave feature subgoal: criteria declared but never banked → unmet
        service.addSubGoal("fw3", "Later feature missing implementation", false, "root");
        service.setAcceptanceCriteria("fw3", List.of("done"));
        service.markGoalGivenUp("fw3");
        assertThat(service.getGoalById("fw3").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isTrue();
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
    void autoCompleteRootIfSatisfied_T2DR_allWavesBankedTrue_marksRootCompleted() {
        // T2-DR: planning-wave grouping is irrelevant under the evidence-ledger rule. Any
        // mix of waves passes closure as long as every subgoal is COMPLETED-with-banked or
        // SUBSUMED. Here a later wave is given up with all criteria pre-banked → SUBSUMED.
        service.resetInitialGoal("root", "Root");

        for (int i = 1; i <= 8; i++) {
            service.addSubGoal("fw" + i, "First wave subgoal " + i, false, "root");
            seedCriteriaPassed("fw" + i);
            service.markGoalCompleted("fw" + i);
        }

        service.beginPlanningRun();
        for (int i = 1; i <= 7; i++) {
            service.addSubGoal("sw" + i, "Second wave subgoal " + i, false, "root");
            seedCriteriaPassed("sw" + i);
            service.markGoalGivenUp("sw" + i);
            assertThat(service.getGoalById("sw" + i).getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        }

        CycleContextData ctx = CycleContextData.builder().evaluationFeedback("test").build();
        service.setCurrentCycleContext(ctx);

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCurrentCycleContext().hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
    }

    // --- T1-DH: pre-plan auto-complete fires before re-plan callback ---

    @Test
    void promoteCurrentSubGoal_T1DH_lastBankedSubgoalCompletes_skipsReplanCallback() {
        // T1-DH integration under T2-DR: with 7 completed-with-banked-criteria siblings plus one
        // ACTIVE banked subgoal, promoting the last one satisfies the evidence ledger and the
        // pre-plan auto-complete check fires before any planner re-entry — root is marked
        // COMPLETED and the planner callback is never invoked.
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback);

        service.resetInitialGoal("root", "Root");
        for (int i = 1; i <= 7; i++) {
            service.addSubGoal("fw" + i, "First wave subgoal " + i, false, "root");
            seedCriteriaPassed("fw" + i);
            service.markGoalCompleted("fw" + i);
        }
        service.addSubGoal("fw8", "Last first wave subgoal", true, "root");
        seedCriteriaPassed("fw8");
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
        seedCriteriaPassed("s1", "s2");
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
        seedCriteriaPassed("g1", "g2", "g3");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        // g3 already has banked criteria → markGoalGivenUp auto-promotes to SUBSUMED
        service.markGoalGivenUp("g3");
        // Replacement under g3 — also subsumed via the same path
        service.addSubGoal("r1", "Replacement for child three", false, "g3");
        seedCriteriaPassed("r1");
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
        // Root-completion path under T2-DR: when every descendant subgoal has banked criteria
        // (COMPLETED or auto-promoted SUBSUMED) the evidence ledger is clear and root completes.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Child one valid description", false, "root");
        service.addSubGoal("g2", "Child two valid description", false, "root");
        service.addSubGoal("g3", "Child three valid description", false, "root");
        seedCriteriaPassed("g1", "g2", "g3");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2");
        service.markGoalGivenUp("g3"); // promoted to SUBSUMED
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

    // ── T2-DR: evidence-ledger closure rule ────────────────────────────────────

    @Test
    void autoCompleteRootIfSatisfied_T2DR_criteriaLessStatusesSplit_completedPasses_givenUpBlocks() {
        // Revised 2026-06-12 (was: criteria-less blocks regardless of status). A COMPLETED
        // goal with no declared criteria was accepted by the evaluation that promoted it —
        // blocking it starves root closure on bookkeeping (2 of the 4 final blockers in the
        // 23:57 run). A GIVEN_UP goal without criteria still blocks: abandoned work with no
        // evidence of coverage.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "First subgoal valid description", false, "root");
        service.addSubGoal("g2", "Legacy subgoal valid description", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");
        service.markGoalCompleted("g2"); // criteria-less, COMPLETED → passes closure

        service.setCurrentCycleContext(CycleContextData.builder().build());
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);

        // Criteria-less GIVEN_UP still blocks.
        service.resetInitialGoal("root2", "Root2");
        service.addSubGoal("h1", "Subgoal valid description", false, "root2");
        seedCriteriaPassed("h1");
        service.markGoalCompleted("h1");
        service.addSubGoal("h2", "Abandoned criteria-less subgoal", false, "root2");
        service.markGoalGivenUp("h2", "abandoned");

        service.autoCompleteRootIfSatisfied();
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void autoCompleteRootIfSatisfied_T2DR_activeDescendant_defersClosure() {
        // T2-DR: an ACTIVE descendant of any depth defers closure. Replaces the prior
        // T2-CH guard which only inspected GIVEN_UP first-wave children for active replacements.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "First subgoal valid description", false, "root");
        service.addSubGoal("g2", "GivenUp subgoal valid description", false, "root");
        seedCriteriaPassed("g1");
        service.markGoalCompleted("g1");
        service.markGoalGivenUp("g2");
        service.addSubGoal("r1", "Replacement active descendant", false, "g2");
        seedCriteriaPassed("r1");

        service.setCurrentCycleContext(CycleContextData.builder().build());
        service.autoCompleteRootIfSatisfied();

        // r1 is still ACTIVE → closure must defer
        assertThat(service.getRootGoal().getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void markGoalGivenUp_T2DR_subgoalWithBankedCriteria_promotesToSubsumed() {
        // T2-DR: when a subgoal goes GIVEN_UP but its own criteria are already banked-true,
        // it auto-promotes to SUBSUMED so the evidence ledger no longer treats it as failed.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Subgoal valid description", false, "root");
        seedCriteriaPassed("g1");

        service.markGoalGivenUp("g1");

        assertThat(service.getGoalById("g1").getStatus()).isEqualTo(GoalStatus.SUBSUMED);
    }

    @Test
    void markGoalGivenUp_T2DR_subgoalWithUnmetCriteria_staysGivenUp() {
        // T2-DR: GIVEN_UP without banked criteria stays GIVEN_UP (and blocks closure).
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("g1", "Subgoal valid description", false, "root");
        service.setAcceptanceCriteria("g1", List.of("done"));
        // No saveCriteriaPassStatus → criterion 1 is null/unmet

        service.markGoalGivenUp("g1");

        assertThat(service.getGoalById("g1").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    // ── T1-EK: adoption of merge-bearing children stranded under GIVEN_UP parent ──

    @Test
    void markGoalGivenUp_T1EK_mergeBearingChildAdoptedToGrandparent() {
        // T1-EK: when the T1-EJ guard fires, the protected merge-bearing child is reparented
        // to its grandparent so the loop can still reach it.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("mid", "Intermediate parent", false, "root");
        service.addSubGoal("leaf", "Merge-bearing leaf", false, "mid");
        service.incrementGoalMergeCount("leaf");

        service.markGoalGivenUp("mid");

        GoalEntity leaf = service.getGoalById("leaf");
        assertThat(leaf.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        // Grandparent of "leaf" is "root" (since its parent "mid" was given up).
        assertThat(leaf.getParentId()).isEqualTo("root");
        assertThat(leaf.getMetadata().get("adoptedFromParentId")).isEqualTo("mid");
        assertThat(leaf.getMetadata().get("adoptionReason")).asString().contains("parent given up");
    }

    @Test
    void markGoalGivenUp_T1EK_adoptedChildReachableViaGetRemainingGoals() {
        // T1-EK acceptance criterion: the adopted child appears in getRemainingGoals().
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("mid", "Intermediate parent", false, "root");
        service.addSubGoal("leaf", "Merge-bearing leaf", false, "mid");
        service.incrementGoalMergeCount("leaf");

        service.markGoalGivenUp("mid");

        List<GoalEntity> remaining = service.getRemainingGoals();
        assertThat(remaining).extracting(GoalEntity::getId).contains("leaf");
    }

    @Test
    void markGoalGivenUp_T1EK_grandparentGivenUp_adoptsToRoot() {
        // T1-EK: if the grandparent is also GIVEN_UP, the child is adopted to the root goal
        // rather than left under a dead ancestor.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("gp", "Grandparent", false, "root");
        service.addSubGoal("mid", "Intermediate parent", false, "gp");
        service.addSubGoal("leaf", "Merge-bearing leaf", false, "mid");
        service.incrementGoalMergeCount("leaf");
        service.transitionGoal("gp", GoalStatus.GIVEN_UP, null); // cascade gives up mid; leaf protected and adopted

        GoalEntity leaf = service.getGoalById("leaf");
        assertThat(leaf.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(leaf.getParentId()).isEqualTo("root");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T4-BC: transitionGoal per-intent contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void transitionGoal_FULFILLED_banksAllCriteriaTrue_andFlipsCompleted() {
        service.resetInitialGoal("root", "Goal");
        service.addSubGoal("sub", "Sub", true, "root");
        service.setAcceptanceCriteria("sub", List.of("c1", "c2", "c3"));

        service.transitionGoal("sub", GoalStatus.COMPLETED, "rationale");

        GoalEntity sub = service.getGoalById("sub");
        assertThat(sub.getStatus()).isEqualTo(GoalStatus.COMPLETED);
        var passStatus = service.getCriteriaPassStatus(sub);
        assertThat(passStatus).containsEntry(1, true).containsEntry(2, true).containsEntry(3, true);
    }

    @Test
    void transitionGoal_FAILED_setsGivenUp_andLeavesCriteriaAlone() {
        service.resetInitialGoal("root", "Goal");
        service.addSubGoal("sub", "Sub", true, "root");
        service.setAcceptanceCriteria("sub", List.of("c1", "c2"));
        // Pre-set partial criteria status to verify FAILED does NOT clobber it.
        service.saveCriteriaPassStatus("sub", java.util.Map.of(1, true, 2, false));

        service.transitionGoal("sub", GoalStatus.GIVEN_UP, "exhausted");

        GoalEntity sub = service.getGoalById("sub");
        assertThat(sub.getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        var passStatus = service.getCriteriaPassStatus(sub);
        assertThat(passStatus).containsEntry(1, true).containsEntry(2, false);
    }

    @Test
    void transitionGoal_SUPERSEDED_setsSubsumedDirectly_skippingGivenUpAndCascade() {
        service.resetInitialGoal("root", "Goal");
        service.addSubGoal("sub", "Sub", true, "root");
        service.addSubGoal("child", "Child of sub", false, "sub");
        service.setAcceptanceCriteria("sub", List.of("c1"));

        service.transitionGoal("sub", GoalStatus.SUBSUMED, "replaced");

        GoalEntity sub = service.getGoalById("sub");
        assertThat(sub.getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        // Criteria are untouched — supersession does not claim the original work was done.
        assertThat(service.getCriteriaPassStatus(sub)).isEmpty();
        // No cascade — the child stays ACTIVE (the replacement work owns the obligation).
        GoalEntity child = service.getGoalById("child");
        assertThat(child.getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void transitionGoal_SUBSUMED_setsSubsumedDirectly() {
        service.resetInitialGoal("root", "Goal");
        service.addSubGoal("sub", "Sub", true, "root");

        service.transitionGoal("sub", GoalStatus.SUBSUMED, "build cleared");

        GoalEntity sub = service.getGoalById("sub");
        assertThat(sub.getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        assertThat(sub.getResolution()).isEqualTo("build cleared");
    }

    @Test
    void transitionGoal_SUBSUMED_withReason_storesStructuredMetadata() {
        service.resetInitialGoal("root", "Goal");
        service.addSubGoal("replaced", "Sub replaced by replanning", true, "root");
        service.addSubGoal("covered", "Sub passively covered", true, "root");

        service.transitionGoal("replaced", GoalStatus.SUBSUMED, "superseded by replanning",
                SubsumptionReason.REPLACED);
        service.transitionGoal("covered", GoalStatus.SUBSUMED, "build cleared",
                SubsumptionReason.COVERED);

        assertThat(service.getGoalById("replaced").getMetadata())
                .containsEntry(SubsumptionReason.METADATA_KEY, "REPLACED");
        assertThat(service.getGoalById("covered").getMetadata())
                .containsEntry(SubsumptionReason.METADATA_KEY, "COVERED");
    }

    @Test
    void transitionGoal_SUBSUMED_withoutReason_omitsMetadataKey() {
        service.resetInitialGoal("root", "Goal");
        service.addSubGoal("sub", "Sub", true, "root");

        service.transitionGoal("sub", GoalStatus.SUBSUMED, "no structured reason");

        assertThat(service.getGoalById("sub").getMetadata())
                .doesNotContainKey(SubsumptionReason.METADATA_KEY);
    }

    // -----------------------------------------------------------------------------------
    // T1-ET Part B — markGoalCompleted outcome + promoteCurrentSubGoal no-clobber
    // -----------------------------------------------------------------------------------

    @Test
    void markGoalCompleted_T1ET_leafGoal_returnsCompletedOutcome() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("leaf", "Leaf", true, "root");

        MarkCompletedOutcome outcome = service.markGoalCompleted("leaf");

        assertThat(outcome.kind()).isEqualTo(MarkCompletedOutcome.Kind.COMPLETED);
        assertThat(outcome.goalId()).isEqualTo("leaf");
        assertThat(service.getGoalById("leaf").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void markGoalCompleted_T1ET_unknownGoal_returnsUnknownGoalOutcome() {
        service.resetInitialGoal("root", "Root");

        MarkCompletedOutcome outcome = service.markGoalCompleted("nope");

        assertThat(outcome.kind()).isEqualTo(MarkCompletedOutcome.Kind.UNKNOWN_GOAL);
    }

    @Test
    void markGoalCompleted_T1ET_parentWithActiveDescendants_returnsRefusedAndRepointsCurrentSubGoal() {
        // Parent goal with two ACTIVE leaves. Try to complete the parent — T1-DD should
        // refuse and re-point currentSubGoalId to one of the active leaves. T1-ET's outcome
        // record now carries that re-point so callers can react.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", true, "root");
        service.addSubGoal("leaf-a", "Leaf A", false, "parent");
        service.addSubGoal("leaf-b", "Leaf B", false, "parent");

        // Force currentSubGoalId to the parent so the refusal needs to relocate it.
        service.setCurrentSubGoal("parent");

        MarkCompletedOutcome outcome = service.markGoalCompleted("parent");

        assertThat(outcome.kind()).isEqualTo(MarkCompletedOutcome.Kind.REFUSED_ACTIVE_DESCENDANTS);
        assertThat(outcome.activeChildCount()).isEqualTo(2);
        assertThat(outcome.repointedCurrentSubGoalId()).isIn("leaf-a", "leaf-b");
        // Parent must remain ACTIVE — refusal blocked the flip.
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        // currentSubGoalId is now on an active leaf, not the parent.
        assertThat(service.getCurrentSubGoal().getId()).isIn("leaf-a", "leaf-b");
    }

    @Test
    void promoteCurrentSubGoal_T1ET_refusalEarlyReturn_doesNotClobberCurrentSubGoalId() {
        // Regression-lock for the `behaviour-2026-05-25_00-31-18.ndjson` 16-cycle zero-work
        // loop. Build a parent whose banked criteria came from child merges (here we just
        // assert the structure — currentSubGoalId on the parent, ACTIVE leaves remain). When
        // promoteCurrentSubGoal fires on the parent, T1-DD inside markGoalCompleted refuses;
        // promoteCurrentSubGoal must now early-return WITHOUT clobbering the leaf pointer
        // T1-DD just set.
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", true, "root");
        service.addSubGoal("leaf-a", "Leaf A", false, "parent");
        service.addSubGoal("leaf-b", "Leaf B", false, "parent");
        service.setCurrentSubGoal("parent");

        boolean promoted = service.promoteCurrentSubGoal();

        // We DID move (T1-DD re-pointed), so the promote call returns true.
        assertThat(promoted).isTrue();
        // Parent stays ACTIVE — refusal stood.
        assertThat(service.getGoalById("parent").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        // currentSubGoalId is on an active leaf — NOT clobbered to null, NOT the parent.
        assertThat(service.getCurrentSubGoal()).isNotNull();
        assertThat(service.getCurrentSubGoal().getId()).isIn("leaf-a", "leaf-b");
    }

    @Test
    void hasNoActiveDescendants_T1ET_emptySubtreeReturnsTrue() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("leaf", "Leaf", true, "root");

        assertThat(service.hasNoActiveDescendants("leaf")).isTrue();
    }

    @Test
    void hasNoActiveDescendants_T1ET_activeChildReturnsFalse() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", true, "root");
        service.addSubGoal("leaf", "Leaf", false, "parent");

        assertThat(service.hasNoActiveDescendants("parent")).isFalse();
    }

    @Test
    void hasNoActiveDescendants_T1ET_allDescendantsTerminalReturnsTrue() {
        service.resetInitialGoal("root", "Root");
        service.addSubGoal("parent", "Parent", true, "root");
        service.addSubGoal("leaf-a", "Leaf A", false, "parent");
        service.addSubGoal("leaf-b", "Leaf B", false, "parent");

        service.markGoalCompleted("leaf-a");
        service.markGoalCompleted("leaf-b");

        assertThat(service.hasNoActiveDescendants("parent")).isTrue();
    }

    @Test
    void transitionGoal_COMPLETED_viaNoDiffClose_banksCriteriaSoClosureRuleDoesNotBlock() {
        // Drives the T2-CC #NoDiff close path: the closer flips status COMPLETED and must
        // bank every criterion or T2-DR's closure rule will count the subgoal as a blocker
        // against root because criteriaPassStatus would otherwise be empty.
        service.resetInitialGoal("root", "Library API");
        service.addSubGoal("post-loans", "POST /loans creates a loan", true, "root");
        service.setAcceptanceCriteria("post-loans", List.of("c1", "c2", "c3", "c4"));

        service.transitionGoal("post-loans", GoalStatus.COMPLETED,
                "evolution #NoDiff — output already satisfies all criteria (T2-CC)");

        GoalEntity g = service.getGoalById("post-loans");
        assertThat(g.getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.allCriteriaDefinedAndBanked("post-loans")).isTrue();
    }
}
