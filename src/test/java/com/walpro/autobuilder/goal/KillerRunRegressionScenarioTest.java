package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.testsupport.ControlLoopScenarioHarness;
import com.walpro.autobuilder.testsupport.GoalStackFixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-AF stage-fixture scenarios: each test drives production closure code over the REAL
 * terminal goal tree of a preserved field run that killed a root on a self-authored
 * requirement (the July 2026 false-give-up family). What previously took a 45-minute live
 * run to rediscover is summoned here in milliseconds from a committed snapshot.
 */
class KillerRunRegressionScenarioTest {

    // Run 18's blocker: #NoDiff-promoted COMPLETED goal whose criteria check banked a ✗ seconds later.
    private static final String RUN18_BLOCKER = "goal-439dfd7d-668a-4eae-bd01-e35ce007ba11";
    private static final String RUN18_ROOT = "goal-4002d0fb-5557-4a8e-84c9-423bb54ea85a";

    // Run 19's blocker: planner-authored "bookId and memberId are strings" (spec-contradicting).
    private static final String RUN19_BLOCKER = "goal-d4e9d9e5-f5a1-4165-9d23-1ed9f7ea641e";
    private static final String RUN19_ROOT = "goal-bba8d454-450f-4f70-8e36-bb60da5770f5";

    @Test
    void run18_closedButUnmetBlocker_isReopenedForRework_rootSurvives() {
        // The 2026-07-06 run-18 terminal state, rewound one transition: the closure edge must
        // reopen the contradicted COMPLETED blocker instead of giving the root up (the fix
        // shipped in 2c0a997; before it, this exact tree produced false give-up #4).
        ControlLoopScenarioHarness harness = ControlLoopScenarioHarness.create();
        Map<String, GoalEntity> goals = GoalStackFixtureLoader.load(harness, "endgame-closed-but-unmet-run18");
        GoalStackFixtureLoader.reactivateRoot(goals, RUN18_ROOT);
        assertThat(goals.get(RUN18_BLOCKER).getStatus()).isEqualTo(GoalStatus.COMPLETED);

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
