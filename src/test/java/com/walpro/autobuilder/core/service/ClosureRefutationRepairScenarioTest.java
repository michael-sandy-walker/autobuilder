package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.goal.ClaimRefutation;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.testsupport.ControlLoopScenarioHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T4-CU's field DoD, made deterministic: <i>"a criterion the evaluator marked met is contradicted
 * by the running artifact and becomes a work item the loop then repairs."</i>
 *
 * <p><b>Why a scenario rather than another live run.</b> Five runs carried this clause unmet, and
 * the 2026-09-17 run showed the precondition is rarer than "wait longer" implies: the verifier is
 * only reached when a CLOSING subgoal carries a SPECIFICATION-STATED criterion, and T3-CC filters
 * the loop's own authored criteria out before the verifier is called. In that run every criterion
 * on every criteria-bearing closure was authored, so six closures produced zero opportunities. The
 * shape does not arrive by running longer, and a frozen tree cannot express this clause either --
 * it is a SEQUENCE across cycles, which is exactly what this harness drives.
 *
 * <p><b>What is real here and what is scripted.</b> The goal tree, the handler ladder, the closure
 * gate, the bank arithmetic and the injection are production code. Two inputs are scripted: what
 * the cycle execution ends in, and what the two verifiers answer -- the criteria verifier (standing
 * in for the LLM's reading) and the closure-claim verifier (standing in for the probe executing
 * against a running artifact). Scripting the second is the point rather than a weakness: the defect
 * this ticket exists for is the LLM's reading disagreeing with execution, so the test states that
 * disagreement directly.
 *
 * <p><b>What this does NOT prove.</b> That the model then writes a correct repair. It proves the
 * loop's routing: a refuted claim blocks the closure, becomes addressable work naming what to fix,
 * does not re-fire forever, and lets the subgoal close once the artifact stops contradicting it.
 */
class ClosureRefutationRepairScenarioTest {

    private static final String DESCRIPTION = "Implement Book validation";
    /** Both spec-stated, so T3-CC would pass them through to the verifier. */
    private static final String HELD =
            "GET /books returns HTTP 200 with the book list";
    private static final String REFUTED =
            "POST /books with a title over 120 characters returns HTTP 400";
    private static final List<String> CRITERIA = List.of(HELD, REFUTED);

    /** The evaluator's reading: every criterion met. This is the claim execution will refute. */
    private static void evaluatorSaysAllMet(ControlLoopScenarioHarness h) {
        when(h.verifier.verifyAndRecord(any(), any())).thenReturn(Map.of(1, true, 2, true));
    }

    private static ControlLoopScenarioHarness armed() {
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withRetries(false).withDivideOnExhaustion(false).withExhaustionCap(10);
        h.seedTree(DESCRIPTION, CRITERIA, false);
        evaluatorSaysAllMet(h);
        h.carryContext(h.exhaustedContext(DESCRIPTION, CRITERIA));
        h.scriptCycleResults(CycleResultState.SUBGOAL_EXHAUSTED);
        return h;
    }

    private static List<GoalEntity> repairItems(ControlLoopScenarioHarness h) {
        return h.goalPlannerService.getAllGoals().stream()
                .filter(g -> g.getDescription() != null && g.getDescription().startsWith("[BUILD_FIX]"))
                .toList();
    }

    @Test
    void aRefutedClaimBlocksTheClosureAndBecomesWorkNamingWhatToRepair() {
        ControlLoopScenarioHarness h = armed();
        h.goalPlannerService.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation(
                        "the application runs, but acceptance criteria fail when exercised against"
                                + " it: \"" + REFUTED + "\" — but the running application answered 201",
                        List.of(REFUTED))));

        h.runCycle();

        // 1. The false closure is blocked. ACTIVE is the truthful description of unfinished work.
        assertThat(h.goalPlannerService.getGoalById("S").getStatus())
                .as("a claim the artifact contradicts must not close the subgoal")
                .isEqualTo(GoalStatus.ACTIVE);

        // 2. The refutation became addressable work -- the half that was unreachable until the
        //    port carried the criteria, because a repair item had nothing to name.
        List<GoalEntity> items = repairItems(h);
        assertThat(items).as("a refused closure must leave something pointing at the defect").hasSize(1);
        GoalEntity item = items.get(0);
        assertThat(item.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(item.getMetadata().get(BlockerTypes.ORIGINATING_GATE_KEY))
                .as("the gate stamp is what stops a COMPILE_OK observation retiring it")
                .isEqualTo(BlockerTypes.GATE_CRITERIA);

        // 3. Its done-when is the refuted criterion itself, not the log sentence -- so the next
        //    closure of this item is judged against the thing that actually failed.
        assertThat(item.getMetadata().get("acceptanceCriteria"))
                .as("the work item names the criterion, which is what the widened port bought")
                .isEqualTo(List.of(REFUTED));
    }

    @Test
    void theRefusalDoesNotRespawnTheSameClaimEveryCycle() {
        // The livelock this mechanism nearly caused: with the bank left intact, T3-BL's
        // "all criteria banked" early exit re-declares FULFILLED next cycle, the gate refuses
        // again, and the loop spins with zero work -- 78 early exits on ONE subgoal in run
        // 2026-08-01_19-29-42. The narrow drop must still break it, which is the risk the
        // narrowing introduced: clearing ONE criterion instead of all of them must be enough.
        ControlLoopScenarioHarness h = armed();
        h.goalPlannerService.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation("answered 201", List.of(REFUTED))));

        h.runCycle();
        h.runCycle();
        h.runCycle();

        assertThat(h.goalPlannerService.areCriteriaCumulativelySatisfied("S"))
                .as("the refuted criterion must be re-earned, so the banked claim cannot re-fire")
                .isFalse();
        assertThat(repairItems(h))
                .as("three cycles of the same refusal must not stack three identical work items")
                .hasSize(1);
    }

    @Test
    void theCriterionThatHeldIsNotReEarnedForItsNeighboursFault() {
        ControlLoopScenarioHarness h = armed();
        h.goalPlannerService.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation("answered 201", List.of(REFUTED))));

        h.runCycle();

        Map<Integer, Boolean> bank =
                h.goalPlannerService.getCriteriaPassStatus(h.goalPlannerService.getGoalById("S"));
        assertThat(bank)
                .as("only the refuted criterion is dropped; the one execution upheld stands")
                .containsExactly(Map.entry(1, Boolean.TRUE));
    }

    @Test
    void theInjectedRepairItemBecomesTheLoopsNextWork() {
        // THIS IS THE DoD CLAUSE -- "becomes a work item the loop THEN REPAIRS", and the
        // handover is immediate: the injection makes the repair item the loop's current work
        // within the refusing cycle. The refuted subgoal is NOT advanced past -- it stays ACTIVE
        // -- so the T4-CU branch's concern (advancing would leave the defect with nothing
        // pointing at it) is satisfied twice over.
        ControlLoopScenarioHarness h = armed();
        h.goalPlannerService.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation("answered 201", List.of(REFUTED))));

        h.runCycle();

        GoalEntity item = repairItems(h).get(0);
        assertThat(h.goalPlannerService.peekCurrentSubGoal().getId())
                .as("the loop's current work becomes the repair item, which is the clause five"
                        + " field runs could not show")
                .isEqualTo(item.getId());
        assertThat(h.goalPlannerService.getGoalById("S").getStatus())
                .as("and the refuted subgoal stays ACTIVE rather than being advanced past -- the"
                        + " defect keeps an owner either way, now the repair item as well")
                .isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void onceTheArtifactStopsContradictingIt_theSubgoalCloses() {
        // The control for every test above: a gate that refused forever would be
        // indistinguishable from one that is simply broken.
        ControlLoopScenarioHarness h = armed();
        boolean[] repaired = {false};
        h.goalPlannerService.setClosureClaimVerifier((goalId, criteria, specText) ->
                repaired[0] ? Optional.empty()
                        : Optional.of(new ClaimRefutation("answered 201", List.of(REFUTED))));

        h.runCycle();
        assertThat(h.goalPlannerService.getGoalById("S").getStatus()).isEqualTo(GoalStatus.ACTIVE);

        repaired[0] = true;                 // the repair landed; execution no longer refutes
        evaluatorSaysAllMet(h);             // and the criteria are banked again

        // The loop works the repair item first and returns to S after -- so the closure is not
        // expected on the very next cycle, and a test asserting that was asserting the wrong
        // thing about a mechanism that was working.
        for (int i = 0; i < 4 && h.goalPlannerService.getGoalById("S").getStatus() != GoalStatus.COMPLETED; i++) {
            h.runCycle();
        }

        assertThat(h.goalPlannerService.getGoalById("S").getStatus())
                .as("once execution agrees, the closure proceeds exactly as it did before the gate")
                .isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void anUnattributedRefutationBlocksTheClosureButRaisesNoItem() {
        // An artifact that will not boot refutes the claim without belonging to any one
        // criterion. The build gates own that and raise their own items; a second item saying
        // only "this claim is false" would re-ask the same question through a costlier channel.
        ControlLoopScenarioHarness h = armed();
        h.goalPlannerService.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(ClaimRefutation.unattributed("the application exits at startup (exit 1)")));

        h.runCycle();

        assertThat(h.goalPlannerService.getGoalById("S").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(repairItems(h)).isEmpty();
    }
}
