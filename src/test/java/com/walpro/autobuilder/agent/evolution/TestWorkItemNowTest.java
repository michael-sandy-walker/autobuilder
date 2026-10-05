package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** T6-T: the test gate's work item reads with the test run of the tree as it is now, not as it was when made. */
class TestWorkItemNowTest {

    private static final String MADE = GoalPlannerService.TEST_SUITE_WORK_ITEM
            + "test suite failing (Tests run: 9, Failures: 6, Errors: 0): Tests, zeta.InkwellTest.pours";

    private final CycleContextData ctx = CycleContextData.builder().currentSubGoalId("sg-7")
            .currentSubGoalDescription(MADE).cycleName("c9").build();

    @Test
    void aTestGateItemIsStatedWithTheRunOfTheTreeAsItIsNow() {
        CycleContextData now = TestWorkItemNow.restate(ctx, id -> BlockerTypes.GATE_TEST,
                () -> Optional.of("test suite failing (Tests run: 9, Failures: 1, Errors: 0): Tests, zeta.NibTest.widens"));

        assertThat(now.currentSubGoalDescription()).startsWith(GoalPlannerService.TEST_SUITE_WORK_ITEM)
                .contains("the test run on the tree as it is now: test suite failing (Tests run: 9, Failures: 1",
                        "zeta.NibTest.widens")
                .doesNotContain("Failures: 6").doesNotContain("InkwellTest");
    }

    @Test
    void aPassingRunSaysNothingFailsAnyMore() {
        assertThat(TestWorkItemNow.restate(ctx, id -> BlockerTypes.GATE_TEST, Optional::empty).currentSubGoalDescription())
                .contains("passes -- nothing in the suite fails any more");
    }

    @Test
    void anotherGatesItemOrAFailedRunLeavesTheSubgoalAsItIs() {
        assertThat(TestWorkItemNow.restate(ctx, id -> BlockerTypes.GATE_PACKAGE, () -> Optional.of("x"))).isSameAs(ctx);
        assertThat(TestWorkItemNow.restate(ctx, id -> null, () -> Optional.of("x"))).isSameAs(ctx);
        assertThat(TestWorkItemNow.restate(ctx, id -> BlockerTypes.GATE_TEST, () -> { throw new IllegalStateException(); }))
                .isSameAs(ctx);
    }
}
