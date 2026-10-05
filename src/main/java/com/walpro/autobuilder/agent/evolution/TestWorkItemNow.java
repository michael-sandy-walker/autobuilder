package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalPlannerService;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * T6-T: the test gate's work item, stated with the test run of the tree as it is NOW.
 *
 * <p>The item is made at a refused closure, with the gate's blocker of that moment in its text, and the gate
 * runs again only at the next closure -- which a subgoal that is never fulfilled does not reach. Ladder-rows
 * run 2026-10-05_22-14-30: the item said "Failures: 6" and named six tests from 23:19 to the end; from 23:48 one
 * other test was failing, named in no prompt, and the model spent its last 26 minutes on the class the six had
 * been about. So for a subgoal the test gate made, the gate is run on the tree before the prompt is built (a
 * few seconds), and the item reads with that run's result.
 */
final class TestWorkItemNow {

    private TestWorkItemNow() {
    }

    /**
     * {@code ctx} with its subgoal stated from {@code runGate}'s result when the subgoal's originating gate
     * ({@code gateOf}) is the test gate; otherwise {@code ctx} unchanged.
     */
    static CycleContextData restate(CycleContextData ctx, Function<String, String> gateOf,
                                    Supplier<Optional<String>> runGate) {
        if (ctx == null || ctx.currentSubGoalId() == null || gateOf == null || runGate == null) return ctx;
        if (!BlockerTypes.GATE_TEST.equals(gateOf.apply(ctx.currentSubGoalId()))) return ctx;
        Optional<String> now;
        try {
            now = runGate.get();
        } catch (RuntimeException e) {
            return ctx; // a prompt is never withheld over a run that could not be made
        }
        if (now == null) return ctx;
        String stated = GoalPlannerService.TEST_SUITE_WORK_ITEM + (now.isPresent()
                ? "the test run on the tree as it is now: " + now.get()
                : "the test run on the tree as it is now passes -- nothing in the suite fails any more");
        return ctx.withCurrentSubGoalDescription(stated);
    }
}
