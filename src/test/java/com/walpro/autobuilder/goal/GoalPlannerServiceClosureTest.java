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
    void autoCompleteRoot_unmetAuthoredCriteria_doNotBlockClosure_andJoinNoCompletionSet() throws IOException {
        // Run 19 (2026-07-07): the sole root blocker was a GIVEN_UP subgoal whose 3 unmet
        // criteria were all invented by the planner from a transient compile diagnostic
        // ("bookId and memberId are strings" — spec-contradicting). AUTHORED-stamped unmet
        // criteria are advisory: they cannot block closure, and they never join the
        // completion-verification set (a false ✗ on an invented requirement at the root edge
        // was what gave up run 18's root).
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        var verification = mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        when(verification.verifyCompletion(any(), any(), any()))
                .thenReturn(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.allClear());
        service.setCriteriaVerificationService(verification);

        GoalEntity root = new GoalEntity("G", "root spec", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        // Spec-bound goal: met SPEC criterion (no binding stamp = SPEC default).
        GoalEntity specGoal = new GoalEntity("S", "loans endpoint", "planner", GoalType.SUBGOAL, "G");
        specGoal.setStatus(GoalStatus.COMPLETED);
        specGoal.getMetadata().put("acceptanceCriteria", java.util.List.of("POST /loans returns 422 at the loan limit"));
        specGoal.getMetadata().put("criteriaPassStatus", Map.of("1", true));
        // The run-19 shape: GIVEN_UP goal, all unmet criteria stamped AUTHORED.
        GoalEntity authoredGoal = new GoalEntity("A", "validate ids are strings", "planner", GoalType.SUBGOAL, "G");
        authoredGoal.setStatus(GoalStatus.GIVEN_UP);
        authoredGoal.getMetadata().put("acceptanceCriteria", java.util.List.of(
                "bookId and memberId are validated as strings", "type validation before business logic"));
        authoredGoal.getMetadata().put("criteriaPassStatus", Map.of("1", false, "2", false));
        authoredGoal.getMetadata().put("criteriaBinding", Map.of("1", "AUTHORED", "2", "AUTHORED"));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(specGoal.getId(), specGoal);
        goalMap.put(authoredGoal.getId(), authoredGoal);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        // Only the SPEC criterion reached completion verification — never the authored ones.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<String>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(verification).verifyCompletion(any(), captor.capture(), any());
        assertThat(captor.getValue()).containsExactly("POST /loans returns 422 at the loan limit");
    }

    @Test
    void countUnmetCriteria_authoredUnmet_earnNoRetryBudget() throws IOException {
        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity g = new GoalEntity("X", "mixed goal", "planner", GoalType.SUBGOAL, "G");
        g.setStatus(GoalStatus.ACTIVE);
        g.getMetadata().put("acceptanceCriteria", java.util.List.of(
                "spec-bound unmet", "authored unmet", "spec-bound met"));
        g.getMetadata().put("criteriaPassStatus", Map.of("1", false, "2", false, "3", true));
        g.getMetadata().put("criteriaBinding", Map.of("2", "AUTHORED"));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(g.getId(), g);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);

        // Only the spec-bound unmet criterion counts toward retry budget.
        assertThat(service.countUnmetCriteria("X")).isEqualTo(1);
    }

    @Test
    void setAcceptanceCriteria_buildFixGoal_stampsAllAuthored_withoutLlm() throws IOException {
        // Deterministic pre-pass: BUILD_FIX criteria are gate-owned build-status facts — run
        // 19's root-blocker ("integer return values handled as strings where required") was
        // minted exactly here, from a transient compile diagnostic. No LLM involved.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        when(goalPlannerConfig.isCriteriaGroundingEnabled()).thenReturn(true);
        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root spec", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity bf = new GoalEntity("B", "[BUILD_FIX] resolve incompatible types", "planner", GoalType.SUBGOAL, "G");
        bf.setStatus(GoalStatus.ACTIVE);
        bf.getMetadata().put(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY,
                com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(bf.getId(), bf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.setAcceptanceCriteria("B", java.util.List.of(
                "LoanService.java compiles without incompatible types errors",
                "All integer return values are handled as strings where required"));

        @SuppressWarnings("unchecked")
        Map<String, String> binding = (Map<String, String>) bf.getMetadata().get("criteriaBinding");
        assertThat(binding).containsEntry("1", "AUTHORED").containsEntry("2", "AUTHORED");
    }

    @Test
    void autoCompleteRoot_closedButUnmetBlocker_reopensOnce_thenGivesUpHonestly() throws IOException {
        // Run 18 (2026-07-06): the #NoDiff promotion raced ahead of its criteria check; the ✗
        // banked seconds later left a COMPLETED blocker with unmet criteria, and T2-DR gave the
        // ROOT up on work nothing ever retried — false give-up #4, on behaviour that ground-
        // truthed as working. The contradiction must reopen the blocker (once, bank kept);
        // the root gives up only when the blocker is honestly terminal.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity closedUnmet = new GoalEntity("C", "closed but unmet", "planner", GoalType.SUBGOAL, "G");
        closedUnmet.setStatus(GoalStatus.COMPLETED);
        closedUnmet.getMetadata().put("acceptanceCriteria",
                java.util.List.of("returns 422 when the limit is exceeded", "returns 201 on success"));
        closedUnmet.getMetadata().put("criteriaPassStatus", Map.of("1", false, "2", true));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(closedUnmet.getId(), closedUnmet);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        // Root NOT given up; the blocker is reopened with its bank kept — the unmet entry is
        // the diagnosis, and it blocks the "all criteria banked" early exit (no run-9 oscillation).
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getGoalById("C").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(closedUnmet.getMetadata().get("closureEdgeReopens")).isEqualTo(1);
        assertThat(closedUnmet.getMetadata().get("criteriaPassStatus")).isNotNull();

        // Second contradiction on the same goal: promote-falsely/reopen cycling — honest give-up.
        closedUnmet.setStatus(GoalStatus.COMPLETED);
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(service.getGoalById("C").getStatus()).isEqualTo(GoalStatus.COMPLETED);
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
    void autoCompleteRoot_structureQuery_blocksClosureAndInjectsMissingEndpoint() throws IOException {
        // T2-EI D2 (P5): the deterministic endpoint-surface query at the closure edge. The root
        // spec names POST /loans; the output has no such mapping — closure must refuse and inject
        // the endpoint as a REQUIRED work item instead of completing (false completion #2 shape).
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));
        when(goalPlannerConfig.isStructureQueryEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setEndpointSurfaceQuery(new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery());
        service.setCurrentCycleContext(com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .cycleName("structure-query-test")
                .previousOutput(Map.of("src/main/java/BookController.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/books")
                        public class BookController {
                            @PostMapping
                            public String create(@RequestBody String b) { return b; }
                        }
                        """))
                .build());

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /books creates a book. POST /loans creates a loan.",
                "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement POST /books", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        // Root NOT completed; the missing endpoint became a REQUIRED work item.
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        GoalEntity injected = service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL)
                .filter(g -> g.getDescription() != null && g.getDescription().contains("POST /loans"))
                .findFirst().orElse(null);
        assertThat(injected).isNotNull();
        assertThat(injected.getMetadata().get(GoalPlannerService.SUBGOAL_ORIGIN_KEY))
                .isEqualTo(GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
    }

    @Test
    void autoCompleteRoot_structureQuery_completesWhenEndpointSurfaceCovered() throws IOException {
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());
        when(goalPlannerConfig.isStructureQueryEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setEndpointSurfaceQuery(new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery());
        service.setCurrentCycleContext(com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .cycleName("structure-query-test")
                .previousOutput(Map.of("src/main/java/Controllers.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class Controllers {
                            @PostMapping("/books")
                            public String book(@RequestBody String b) { return b; }
                            @PostMapping("/loans")
                            public String loan(@RequestBody String l) { return l; }
                        }
                        """))
                .build());

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /books creates a book. POST /loans creates a loan.",
                "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement both endpoints", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_specTraceability_absorbsMisBinnedTestSubgoal() throws IOException {
        // T4-BT: the T2-EJ field-run-1 death — an unrequested unit-test subgoal created at top
        // level was stamped REQUIRED by the origin rule and doomed the root. With traceability,
        // a test-flavored subgoal on a spec that never asks for tests is EXTRA regardless of the
        // stamp, so its failure is absorbed under depth-first.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());
        when(goalPlannerConfig.isDepthFirstDecomposition()).thenReturn(true);
        when(goalPlannerConfig.isSpecTraceabilityEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setSpecTraceability(new SpecTraceability(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery()));

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /books creates a book.", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement POST /books", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);
        done.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
        // The mis-binned blocker: top-level test subgoal, stamped REQUIRED, GIVEN_UP.
        GoalEntity testLeaf = new GoalEntity("T", "Add unit tests for the book endpoints",
                "planner", GoalType.SUBGOAL, "G");
        testLeaf.setStatus(GoalStatus.GIVEN_UP);
        testLeaf.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        goalMap.put(testLeaf.getId(), testLeaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_specTraceability_blocksOnSpecTracedSubgoalDespiteExtraStamp() throws IOException {
        // The other mis-bin direction: a subgoal naming a spec-declared endpoint is REQUIRED even
        // when the origin stamp says extra (late/nested addition) — its failure must still block.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        when(goalPlannerConfig.isDepthFirstDecomposition()).thenReturn(true);
        when(goalPlannerConfig.isSpecTraceabilityEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setSpecTraceability(new SpecTraceability(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery()));

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /books creates a book. POST /loans creates a loan.",
                "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement POST /books", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);
        GoalEntity loanLeaf = new GoalEntity("L", "Implement POST /loans", "planner", GoalType.SUBGOAL, "D");
        loanLeaf.setStatus(GoalStatus.GIVEN_UP);
        loanLeaf.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_EXTRA);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        goalMap.put(loanLeaf.getId(), loanLeaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

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
    void admissionCap_holdsSurplusActiveLeaves_currentSelectionPrefersAdmitted() throws IOException {
        // T2-EI count-based admission queue: with cap=2 and 4 active leaves, only the 2 oldest are
        // admitted (selectable as current); the 2 newest are HELD (still ACTIVE, but not worked).
        // The 3-run experiment showed run-C churned because the model front-loaded all resources;
        // the cap bounds the worked set deterministically regardless of how many are proposed.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        when(goalPlannerConfig.getAdmissionCap()).thenReturn(2);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put("G", root);
        java.time.Instant t0 = java.time.Instant.parse("2026-06-25T00:00:00Z");
        for (int i = 1; i <= 4; i++) {
            GoalEntity leaf = new GoalEntity("L" + i, "leaf" + i, "planner", GoalType.SUBGOAL, "G");
            leaf.setStatus(GoalStatus.ACTIVE);
            leaf.setCreated(t0.plusSeconds(i)); // L1 oldest … L4 newest
            goalMap.put(leaf.getId(), leaf);
        }
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        ReflectionTestUtils.setField(service, "currentSubGoalId", null);

        // Selection (null current → recovery) must land on an ADMITTED (oldest-2) leaf, never a held one.
        GoalEntity selected = service.getCurrentSubGoal();
        assertThat(selected).isNotNull();
        assertThat(selected.getId()).isIn("L1", "L2");

        // getRemainingGoals is intentionally NOT filtered — the backlog-non-empty guard and root
        // closure must still see all 4 active (held leaves block premature completion).
        assertThat(service.getRemainingGoals()).hasSize(4);
        root.setStatus(GoalStatus.ACTIVE);
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE); // held leaves block

        // Control: cap disabled → no holding (selection may be any active leaf).
        when(goalPlannerConfig.getAdmissionCap()).thenReturn(0);
        ReflectionTestUtils.setField(service, "currentSubGoalId", null);
        assertThat(service.getCurrentSubGoal()).isNotNull();
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

        // The work item declares the requirement as its criterion — not the build-output text
        // (a criteria-less work item gets its blob description declared as criterion at
        // promotion, and the next completion verification blocks on the blob; run 4 2026-07-03).
        GoalEntity workItem = service.getAllGoals().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE
                        && g.getDescription() != null
                        && g.getDescription().contains("Unable to find main class"))
                .findFirst().orElseThrow();
        assertThat(workItem.getMetadata().get("acceptanceCriteria"))
                .isEqualTo(java.util.List.of(
                        "The project packages into a startable application artifact without build errors"));

        // Gate passing → completion proceeds.
        when(packageGate.verifyPackageable(any())).thenReturn(java.util.Optional.empty());
        service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .forEach(g -> g.setStatus(GoalStatus.SUBSUMED));
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_testSourcePackagingFailure_ensurerFixesPom_completionProceeds() throws IOException {
        // T4-BN-3 at the live edge (2026-07-02 run 2): closure reached 0 required blockers, then
        // the package gate failed on a test source missing the test-framework dependency and the
        // LLM churned to a halt on the injected work item. When the failure is in test sources
        // and the deterministic ensurer modifies the pom, the gate re-verifies once and a now-
        // clean packaging lets completion proceed — no work item injected.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.core.build.PackageGate packageGate =
                mock(com.walpro.autobuilder.core.build.PackageGate.class);
        when(packageGate.verifyPackageable(any()))
                .thenReturn(java.util.Optional.of(
                        "/x/output/src/test/java/com/example/LoanControllerIntegrationTest.java:[74,6] cannot find symbol: class Test"))
                .thenReturn(java.util.Optional.empty());
        service.setPackageGate(packageGate);
        com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer ensurer =
                mock(com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer.class);
        when(ensurer.ensure()).thenReturn(true);
        service.setTestDependencyEnsurer(ensurer);

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

        verify(ensurer).ensure();
        verify(packageGate, times(2)).verifyPackageable(any());
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_testSourcePackagingFailure_ensurerCannotHelp_workItemInjected() throws IOException {
        // Ensurer no-op (pom already has the starter / non-Spring project) → the existing
        // injection path proceeds unchanged. A non-test-source failure never consults it.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.core.build.PackageGate packageGate =
                mock(com.walpro.autobuilder.core.build.PackageGate.class);
        when(packageGate.verifyPackageable(any()))
                .thenReturn(java.util.Optional.of(
                        "/x/output/src/test/java/com/example/LoanControllerIntegrationTest.java:[74,6] cannot find symbol: class Test"));
        service.setPackageGate(packageGate);
        com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer ensurer =
                mock(com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer.class);
        when(ensurer.ensure()).thenReturn(false);
        service.setTestDependencyEnsurer(ensurer);

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

        verify(packageGate, times(1)).verifyPackageable(any());
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getAllGoals())
                .anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE
                        && g.getDescription() != null
                        && g.getDescription().contains("cannot find symbol"));

        // Main-source failure (no src/test/ path) → ensurer never consulted.
        org.mockito.Mockito.reset(packageGate, ensurer);
        when(packageGate.verifyPackageable(any()))
                .thenReturn(java.util.Optional.of("Unable to find main class"));
        service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .forEach(g -> g.setStatus(GoalStatus.SUBSUMED));
        service.autoCompleteRootIfSatisfied();
        verify(ensurer, never()).ensure();
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
        // Root "root" names no tests → testsRequired=false → the work item offers removal of
        // unrepairable self-authored tests as a resolution (run 2's churn-to-halt escape hatch).
        assertThat(service.getAllGoals())
                .anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE
                        && g.getDescription() != null
                        && g.getDescription().contains("test suite pass")
                        && g.getDescription().contains("removing test sources that cannot be made to pass"));
    }

    @Test
    void autoCompleteRoot_testGateFailure_specRequiresTests_workItemOffersNoRemoval() throws IOException {
        // When the goal itself calls for tests, deleting them is never a resolution — the work
        // item must stay repair-only (and T4-AN-2b blocks absence separately).
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.core.build.TestRunGate testRunGate =
                mock(com.walpro.autobuilder.core.build.TestRunGate.class);
        when(testRunGate.verifyTestsPass(any(), anyBoolean()))
                .thenReturn(java.util.Optional.of("test suite failing (Tests run: 3, Failures: 3)"));
        service.setTestRunGate(testRunGate);

        GoalEntity root = new GoalEntity("G", "REST API with unit tests for every endpoint", "system", GoalType.GOAL, null);
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
                        && g.getDescription().contains("test suite pass")
                        && !g.getDescription().contains("removing test sources"));
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
                .thenReturn(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.blocked(
                        "1 acceptance criterion(s) not confirmed in the current code: POST /carts/{id}/checkout returns an OrderResponse with a computed PriceBreakdown",
                        java.util.List.of("POST /carts/{id}/checkout returns an OrderResponse with a computed PriceBreakdown")));
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
        when(verifier.verifyCompletion(any(), any(), any())).thenReturn(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.allClear());
        service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .forEach(g -> g.setStatus(GoalStatus.SUBSUMED));
        service.autoCompleteRootIfSatisfied();
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_nonSpecBoundGoalCriteria_doNotGateRootCompletion() throws IOException {
        // Run 11 (2026-07-04): a planner-authored structural stepping-stone ("Create ... package",
        // origin-stamped REQUIRED, T4-BT binding UNKNOWN) carried the state-snapshot criterion
        // "No classes or files are present in the package yet" — unconfirmable once code existed —
        // and walled completion verification for the rest of the run. When the spec offers a
        // traceability signal, only criteria of POSITIVELY spec-bound goals reach the verifier.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());
        when(goalPlannerConfig.isDepthFirstDecomposition()).thenReturn(true);
        when(goalPlannerConfig.isSpecTraceabilityEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setSpecTraceability(new SpecTraceability(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery()));
        com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService verifier =
                mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        lenient().when(verifier.verifyCompletion(any(), any(), any())).thenReturn(
                com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.allClear());
        service.setCriteriaVerificationService(verifier);
        service.setCurrentCycleContext(com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .previousOutput(Map.of("A.java", "public class A {}")).build());

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /books creates a book.", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        // Run 11's exact shape: REQUIRED origin stamp, but no spec binding (UNKNOWN) — the
        // stamp must not put its state-snapshot criterion in front of the verifier.
        GoalEntity structural = new GoalEntity("S", "Create com.example.demo.model.books package",
                "planner", GoalType.SUBGOAL, "G");
        structural.setStatus(GoalStatus.COMPLETED);
        structural.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
        structural.getMetadata().put("acceptanceCriteria",
                java.util.List.of("No classes or files are present in the package yet"));
        structural.getMetadata().put("criteriaPassStatus", Map.of("1", true));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(structural.getId(), structural);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        verify(verifier, never()).verifyCompletion(any(), any(), any());
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_specBoundGoalCriteria_stillReachTheVerifier() throws IOException {
        // The narrowing must not weaken the verifier where it matters: criteria on a goal that
        // mentions a spec-declared endpoint stay in the completion re-verification set.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());
        when(goalPlannerConfig.isDepthFirstDecomposition()).thenReturn(true);
        when(goalPlannerConfig.isSpecTraceabilityEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setSpecTraceability(new SpecTraceability(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery()));
        com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService verifier =
                mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        when(verifier.verifyCompletion(any(), any(), any())).thenReturn(
                com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.allClear());
        service.setCriteriaVerificationService(verifier);
        service.setCurrentCycleContext(com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .previousOutput(Map.of("A.java", "public class A {}")).build());

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /books creates a book.", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity endpointGoal = new GoalEntity("E", "Implement POST /books with validation",
                "planner", GoalType.SUBGOAL, "G");
        endpointGoal.setStatus(GoalStatus.COMPLETED);
        endpointGoal.getMetadata().put("acceptanceCriteria",
                java.util.List.of("POST /books returns HTTP 400 when the isbn is invalid"));
        endpointGoal.getMetadata().put("criteriaPassStatus", Map.of("1", true));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(endpointGoal.getId(), endpointGoal);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        org.mockito.ArgumentCaptor<java.util.List<String>> criteriaArg =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(verifier).verifyCompletion(any(), criteriaArg.capture(), any());
        assertThat(criteriaArg.getValue())
                .containsExactly("POST /books returns HTTP 400 when the isbn is invalid");
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void autoCompleteRoot_structureQuery_workItemsCarryMappingExistsCriterion() throws IOException {
        // The D2 work item's description embeds "no request mapping … exists" — as a banked
        // description-as-criterion it is unconfirmable once the endpoint is built (run 11
        // carried it into the completion set). Injection now declares the requirement.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));
        when(goalPlannerConfig.isStructureQueryEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setEndpointSurfaceQuery(new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery());
        service.setCurrentCycleContext(com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .cycleName("structure-query-test")
                .previousOutput(Map.of("src/main/java/A.java", "public class A {}"))
                .build());

        GoalEntity root = new GoalEntity("G",
                "Library service. POST /loans creates a loan.", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement the model", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.COMPLETED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        GoalEntity workItem = service.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL)
                .filter(g -> g.getDescription() != null && g.getDescription().contains("POST /loans"))
                .findFirst().orElseThrow();
        assertThat(workItem.getMetadata().get("acceptanceCriteria"))
                .isEqualTo(java.util.List.of("A request mapping for POST /loans exists in the application sources"));
    }

    @Test
    void autoCompleteRoot_buildFixGoalCriteria_neverReachTheCompletionVerifier() throws IOException {
        // BUILD_FIX goals' done-ness is owned by the deterministic gates (T1-DR closes them on a
        // green build; the package/test gates re-prove build health at this same edge). Their
        // criteria are build-status facts the LLM verifier cannot read from source — run 9
        // (2026-07-04): feeding them to T4-BP-3 produced a permanent false block.
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService verifier =
                mock(com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.class);
        service.setCriteriaVerificationService(verifier);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity buildFix = new GoalEntity("BF", "[BUILD_FIX] Resolve missing symbol Foo", "system", GoalType.SUBGOAL, "G");
        buildFix.setStatus(GoalStatus.SUBSUMED);
        buildFix.getMetadata().put(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY,
                com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX);
        buildFix.getMetadata().put("acceptanceCriteria",
                java.util.List.of("mvn compile exits 0 with no errors"));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(buildFix.getId(), buildFix);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");

        service.autoCompleteRootIfSatisfied();

        // No criteria reached the verifier (empty passing set skips the call) and the root closed.
        verify(verifier, never()).verifyCompletion(any(), any(), any());
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

}