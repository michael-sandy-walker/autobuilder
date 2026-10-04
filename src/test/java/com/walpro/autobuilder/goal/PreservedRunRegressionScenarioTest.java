package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.testsupport.ControlLoopScenarioHarness;
import com.walpro.autobuilder.testsupport.GoalStackFixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-AF stage-fixture scenarios: each test drives production control-loop code over the REAL
 * goal tree of a preserved field run that exposed a control-loop defect. What previously took a
 * 45-minute live run to rediscover is reproduced here in milliseconds from a committed snapshot.
 */
class PreservedRunRegressionScenarioTest {

    // Run 18's blocker: #NoDiff-promoted COMPLETED goal whose criteria check banked a ✗ seconds later.
    private static final String RUN18_BLOCKER = "goal-439dfd7d-668a-4eae-bd01-e35ce007ba11";
    private static final String RUN18_ROOT = "goal-4002d0fb-5557-4a8e-84c9-423bb54ea85a";

    // Run 19's blocker: planner-authored "bookId and memberId are strings" (spec-contradicting).
    private static final String RUN19_BLOCKER = "goal-d4e9d9e5-f5a1-4165-9d23-1ed9f7ea641e";
    private static final String RUN19_ROOT = "goal-bba8d454-450f-4f70-8e36-bb60da5770f5";

    // Run 20's livelock: a structural repair leaf for POST /loans stays ACTIVE (mapping absent)
    // while a child marked COMPLETED claims the endpoint is implemented — the contradiction.
    private static final String RUN20_ROOT = "goal-615d6754-fd55-484f-963a-565dd96de6e2";
    private static final String RUN20_REPAIR_LEAF = "goal-0d2118ff-3253-4413-8ea4-9a3f6a6a781f";
    private static final String RUN20_ENDPOINT_CHILD = "goal-f5c693a8-8a26-4a0f-be67-87bbe22435f2";

    @Test
    void run18_closedButUnmetBlocker_isReopenedForRework_rootSurvives() {
        // The 2026-07-06 run-18 terminal state, rewound one transition: the closure edge must
        // reopen a contradicted COMPLETED blocker that is REQUIRED instead of giving the root up
        // (the fix shipped in 2c0a997; before it, this exact tree produced false give-up #4).
        ControlLoopScenarioHarness harness = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> goals = GoalStackFixtureLoader.load(harness, "endgame-closed-but-unmet-run18");
        GoalStackFixtureLoader.reactivateRoot(goals, RUN18_ROOT);
        assertThat(goals.get(RUN18_BLOCKER).getStatus()).isEqualTo(GoalStatus.COMPLETED);
        goals.get(RUN18_BLOCKER).getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY, GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);

        harness.goalPlannerService.autoCompleteRootIfSatisfied();

        assertThat(goals.get(RUN18_ROOT).getStatus())
                .as("root must not be given up on a closed-but-unmet contradiction")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(goals.get(RUN18_BLOCKER).getStatus())
                .as("the contradicted completion goes back to the ladder")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(goals.get(RUN18_BLOCKER).getMetadata().get("closureEdgeReopens")).isEqualTo(1);
    }

    @Test
    void run18_asRecorded_theUnmetCriterionIsOnAnExtraLeaf_andTheSpecRequirementIsMet_soTheRootCloses() {
        // As recorded, the blocker is EXTRA-origin: its unmet "422 when member exceeds loan limit" repeats
        // the spec requirement the REQUIRED Loans goal holds as met (its criterion 5). Depth-first closure,
        // which every measured run used (the switch was removed 2026-10-04), absorbs an EXTRA leaf's
        // failure, so the root closes -- correctly by the specification.
        ControlLoopScenarioHarness harness = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> goals = GoalStackFixtureLoader.load(harness, "endgame-closed-but-unmet-run18");
        GoalStackFixtureLoader.reactivateRoot(goals, RUN18_ROOT);

        harness.goalPlannerService.autoCompleteRootIfSatisfied();

        assertThat(goals.get(RUN18_ROOT).getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void run19_authoredBlocker_withGroundingStamps_rootCompletes_withoutThem_rootDies() {
        // The 2026-07-07 run-19 terminal state: a GIVEN_UP subgoal whose 3 unmet criteria were
        // all minted by the planner from a transient compile diagnostic. Same real tree twice;
        // the only difference is the criteriaBinding stamp the grounding gate would have
        // written at authoring time. Without it the root dies (the recorded field outcome);
        // with it the invented requirement is advisory and the root completes honestly.
        ControlLoopScenarioHarness withoutStamps = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> bare = GoalStackFixtureLoader.load(withoutStamps, "endgame-authored-blocker-run19");
        GoalStackFixtureLoader.reactivateRoot(bare, RUN19_ROOT);
        assertThat(bare.get(RUN19_BLOCKER).getStatus()).isEqualTo(GoalStatus.GIVEN_UP);

        withoutStamps.goalPlannerService.autoCompleteRootIfSatisfied();
        assertThat(bare.get(RUN19_ROOT).getStatus())
                .as("baseline: unmet spec-default criteria on a GIVEN_UP blocker doom the root (run 19's field outcome)")
                .isEqualTo(GoalStatus.GIVEN_UP);

        ControlLoopScenarioHarness withStamps = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> stamped = GoalStackFixtureLoader.load(withStamps, "endgame-authored-blocker-run19");
        GoalStackFixtureLoader.reactivateRoot(stamped, RUN19_ROOT);
        stamped.get(RUN19_BLOCKER).getMetadata().put("criteriaBinding",
                Map.of("1", "AUTHORED", "2", "AUTHORED", "3", "AUTHORED"));

        withStamps.goalPlannerService.autoCompleteRootIfSatisfied();
        assertThat(stamped.get(RUN19_ROOT).getStatus())
                .as("with the grounding stamp the invented requirement cannot kill the root")
                .isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void run20_fixture_loads_theLivelockContradictionShape() {
        // The 2026-07-07 run-20 mid-spin state: a structural repair leaf ("Provide the POST /loans
        // endpoint … no request mapping … exists in the output") stays ACTIVE while one of its
        // children is marked COMPLETED claiming the endpoint is implemented — the text/structure
        // contradiction the loop could neither close (a structural gate refused) nor halt on (the
        // convergence guard kept resetting on a flat re-promotion). The non-halt guard itself is
        // unit-tested in RootConvergenceTrackerTest; this pins the preserved tree shape.
        ControlLoopScenarioHarness harness = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> goals = GoalStackFixtureLoader.load(harness, "livelock-nonhalt-run20");

        GoalEntity repairLeaf = goals.get(RUN20_REPAIR_LEAF);
        assertThat(repairLeaf).as("the structural repair leaf is present").isNotNull();
        assertThat(repairLeaf.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(repairLeaf.getDescription().toLowerCase()).contains("post /loans");

        assertThat(goals.get(RUN20_ENDPOINT_CHILD).getStatus())
                .as("a child claims the endpoint is done while the parent repair leaf stays open")
                .isEqualTo(GoalStatus.COMPLETED);

        assertThat(goals.get(RUN20_ROOT).getStatus())
                .as("root never closed — the structural gate refused")
                .isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void run20_bankedButStructurallyAbsentCriterion_isUnbanked_andTheLeafBecomesWorkable() {
        // The run-20 deadlock rewound one transition on the REAL preserved tree: the repair
        // leaf's criterion "A request mapping for POST /loans exists in the application
        // sources" was banked MET by the text read while the sources provably lacked the
        // mapping — T3-BL re-fired its FULFILLED early exit on that stale bank every cycle
        // for 63 minutes while the D2 guard (correctly) refused each one. T2-EL: the FULFILLED
        // application reconciles the bank against structure; the refusal now also CORRECTS the
        // bank, so the early-exit trigger is gone and ordinary agent execution resumes.
        ControlLoopScenarioHarness harness = ControlLoopScenarioHarness.create();
        GoalStackFixtureLoader.load(harness, "livelock-nonhalt-run20");

        var config = (com.walpro.autobuilder.config.GoalPlannerConfigProperties)
                org.springframework.test.util.ReflectionTestUtils.getField(harness.goalPlannerService, "goalPlannerConfig");
        harness.goalPlannerService.setEndpointSurfaceQuery(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery());

        // The field state at the deadlock: bank says MET (the GoalStack snapshot does not
        // carry the in-flight bank, so restore it), sources say ABSENT — run 20's real
        // LoanController served GET /loans and POST /loans/{id}/return but not POST /loans.
        harness.goalPlannerService.saveCriteriaPassStatus(RUN20_REPAIR_LEAF, Map.of(1, true));
        String run20Controller = """
                package com.example.app;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/loans")
                public class LoanController {
                    @GetMapping public String list() { return "[]"; }
                    @PostMapping("/{id}/return") public String returnLoan(@PathVariable String id) { return "ok"; }
                }
                """;
        harness.goalPlannerService.setCurrentCycleContext(
                com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                        .previousOutput(Map.of("LoanController.java", run20Controller)).build());

        assertThat(harness.goalPlannerService.allCriteriaDefinedAndBanked(RUN20_REPAIR_LEAF))
                .as("precondition — the exact T3-BL early-exit trigger from the field run")
                .isTrue();
        assertThat(harness.goalPlannerService.isOpenStructuralRepairLeaf(RUN20_REPAIR_LEAF))
                .as("T2-EM recognizes the leaf, so scope-mismatch cannot disarm its actuator")
                .isTrue();

        var refused = harness.goalPlannerService.applyGoalProgress(
                RUN20_REPAIR_LEAF, com.walpro.autobuilder.model.GoalProgressState.FULFILLED);

        assertThat(refused.promoted()).isFalse();
        assertThat(harness.goalPlannerService.getGoalById(RUN20_REPAIR_LEAF).getStatus())
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(harness.goalPlannerService.allCriteriaDefinedAndBanked(RUN20_REPAIR_LEAF))
                .as("the stale bank is corrected — T3-BL's early exit stops re-firing and the leaf is workable")
                .isFalse();

        // Once the missing mapping lands, the same promotion completes the leaf honestly.
        harness.goalPlannerService.setCurrentCycleContext(
                com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                        .previousOutput(Map.of("LoanController.java", run20Controller.replace(
                                "@GetMapping public String list() { return \"[]\"; }",
                                "@GetMapping public String list() { return \"[]\"; }\n    @PostMapping public String lend() { return \"created\"; }")))
                        .build());
        harness.goalPlannerService.applyGoalProgress(
                RUN20_REPAIR_LEAF, com.walpro.autobuilder.model.GoalProgressState.FULFILLED);
        assertThat(harness.goalPlannerService.getGoalById(RUN20_REPAIR_LEAF).getStatus())
                .isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void run17_fixture_loads_theBrokenTestTrapShape() {
        // Guard for the committed run-17 snapshot (false give-up on a 16/16 runtime-correct
        // artifact over born-broken self-authored tests). The quarantine actuator itself is
        // unit-tested; this pins the fixture so packaging-edge scenarios can start from it.
        ControlLoopScenarioHarness harness = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> goals = GoalStackFixtureLoader.load(harness, "midgame-broken-tests-run17");

        assertThat(goals).hasSizeGreaterThan(50);
        GoalEntity root = goals.values().stream().filter(g -> g.getType() == GoalType.GOAL).findFirst().orElseThrow();
        assertThat(root.getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        List<GoalEntity> testFlavored = goals.values().stream()
                .filter(g -> g.getDescription() != null && g.getDescription().toLowerCase().contains("test"))
                .toList();
        assertThat(testFlavored).as("the trap's test-flavored subgoals are present").isNotEmpty();
    }
}
