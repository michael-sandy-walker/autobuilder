package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.testsupport.ControlLoopScenarioHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T5-AF cycle scenarios for the give-up edge: the ladder AutobuilderService runs when a cycle
 * ends SUBGOAL_EXHAUSTED (banked-criteria promotion → verify-and-promote → T4-BP-4 residue →
 * T2-EI 1b retries → T2-EJ division → give-up → runaway brake). Field runs visit these edges
 * rarely (T2-EJ's division fired first in the field three weeks after shipping); these scenarios
 * summon each on demand with scripted verifier answers, asserting on the REAL goal tree.
 */
class GiveUpEdgeScenarioTest {

    private static final String DESCRIPTION = "Implement POST /loans with its business rules";
    private static final List<String> CRITERIA = List.of(
            "POST /loans returns 404 when the book does not exist",
            "POST /loans returns 409 when the book is already on loan",
            "POST /loans returns 422 when the member holds 3 active loans");

    /** All criteria unmet, every time — the run-2 (2026-06-27) profile that motivated T2-EJ. */
    private static void verifierSaysAllUnmet(ControlLoopScenarioHarness h) {
        when(h.verifier.verifyAndRecord(any(), any()))
                .thenReturn(Map.of(1, false, 2, false, 3, false));
    }

    @Test
    void requiredSubgoal_retriesExhaust_thenDivisionCreatesRequiredChildren_insteadOfGiveUp() {
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withExhaustionCap(5);
        h.seedTree(DESCRIPTION, CRITERIA, false);
        verifierSaysAllUnmet(h);
        h.carryContext(h.exhaustedContext(DESCRIPTION, CRITERIA));
        h.scriptCycleResults(CycleResultState.SUBGOAL_EXHAUSTED);

        // Exhaustions 1..3 are absorbed by the earned retry budget (3 unmet criteria = 3 retries).
        for (int i = 0; i < 3; i++) {
            h.runCycle();
            assertThat(h.goalPlannerService.getGoalById("S").getStatus())
                    .as("retry %d keeps the subgoal ACTIVE", i + 1)
                    .isEqualTo(GoalStatus.ACTIVE);
        }

        // Exhaustion 4: budget spent → T2-EJ divides instead of giving up.
        h.runCycle();

        GoalEntity original = h.goalPlannerService.getGoalById("S");
        assertThat(original.getStatus()).isEqualTo(GoalStatus.SUBSUMED);

        List<GoalEntity> children = h.goalPlannerService.getAllGoals().stream()
                .filter(g -> GoalPlannerService.DIVIDED_CRITERION_ROLE.equals(g.getRole()))
                .toList();
        assertThat(children).hasSize(3);
        // The T2-EJ correctness catch: children must inherit REQUIRED origin, otherwise slice-1a
        // closure absorption would swallow their failures and the root could falsely complete.
        assertThat(children).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo(GoalStatus.ACTIVE);
            assertThat(c.getMetadata().get(GoalPlannerService.SUBGOAL_ORIGIN_KEY))
                    .isEqualTo(GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);
            assertThat(c.getMetadata().get("acceptanceCriteria"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .hasSize(1);
        });
    }

    @Test
    void dividedChild_neverDividesAgain_oneDivisionLevelOnly() {
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withExhaustionCap(10);
        h.seedTree(DESCRIPTION, CRITERIA, false);
        verifierSaysAllUnmet(h);
        h.carryContext(h.exhaustedContext(DESCRIPTION, CRITERIA));
        h.scriptCycleResults(CycleResultState.SUBGOAL_EXHAUSTED);

        for (int i = 0; i < 3; i++) h.runCycle(); // the earned retries: one per unmet criterion
        h.runCycle(); // divides
        assertThat(h.goalPlannerService.getAllGoals().stream()
                .filter(g -> GoalPlannerService.DIVIDED_CRITERION_ROLE.equals(g.getRole())).count())
                .isEqualTo(3);

        // Children exhaust in turn: multi-criterion or not, a divided child must go GIVEN_UP,
        // never spawn grandchildren.
        when(h.verifier.verifyAndRecord(any(), any())).thenReturn(Map.of(1, false));
        for (int i = 0; i < 3; i++) {
            h.runCycle();
        }
        assertThat(h.goalPlannerService.getAllGoals().stream()
                .filter(g -> GoalPlannerService.DIVIDED_CRITERION_ROLE.equals(g.getRole())).count())
                .as("no second division level")
                .isEqualTo(3);
        assertThat(h.goalPlannerService.getAllGoals().stream()
                .filter(g -> GoalPlannerService.DIVIDED_CRITERION_ROLE.equals(g.getRole()))
                .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP)
                .count()).isGreaterThan(0);
    }

    @Test
    void exhaustionRunaway_divisionsCountTowardTheBrake_capEscalatesToSystemPause() {
        // Division + rescope count as exhaustions so a divide-give-up churn cannot run forever:
        // at the cap the give-up wins and the run escalates for review.
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withExhaustionCap(2);
        h.seedTree(DESCRIPTION, CRITERIA, false);
        verifierSaysAllUnmet(h);
        h.carryContext(h.exhaustedContext(DESCRIPTION, CRITERIA));
        h.scriptCycleResults(CycleResultState.SUBGOAL_EXHAUSTED);

        for (int i = 0; i < 3; i++) h.runCycle(); // the earned retries: one per unmet criterion
        h.runCycle(); // division (counts 1/2)
        h.runCycle(); // child gives up honestly — cap guard refuses further division (2/2)
        h.runCycle(); // at the cap: runaway → GOAL_EXHAUSTED + SYSTEM_PAUSED

        assertThat(DerivedFacts.goalExhausted(h.context())).isTrue();
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(h.context())).isTrue();
    }

    @Test
    void verifierConfirmsAllCriteria_promotesToCompleted_insteadOfGivingUp() {
        // The T4-BP promoting direction (and T2-EK's rescue outcome): work landed under siblings,
        // this subgoal's own attempts look exhausted — verification against the output proves the
        // criteria hold and the ladder promotes instead of giving up (run-2 2026-07-02 field case).
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withExhaustionCap(5);
        h.seedTree(DESCRIPTION, CRITERIA, false);
        when(h.verifier.verifyAndRecord(any(), eq("S")))
                .thenReturn(Map.of(1, true, 2, true, 3, true));
        h.carryContext(h.exhaustedContext(DESCRIPTION, CRITERIA));
        h.scriptCycleResults(CycleResultState.CONTINUE);

        h.runCycle();

        assertThat(h.goalPlannerService.getGoalById("S").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        // Banked: the pass status is persisted so the T2-DR closure ledger sees real coverage.
        assertThat(h.goalPlannerService.areCriteriaCumulativelySatisfied("S")).isTrue();
    }

    @Test
    void truePartialPass_rescopesResidue_carryingExactlyTheUnmetCriteria() {
        // T4-BP-4: some ✓ / some ✗ is a diagnosis — one follow-up subgoal carries the unmet
        // residue, the original passes the ledger as SUBSUMED, banked criteria stay banked.
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withExhaustionCap(5);
        h.seedTree(DESCRIPTION, CRITERIA, false);
        when(h.verifier.verifyAndRecord(any(), eq("S")))
                .thenReturn(Map.of(1, true, 2, true, 3, false));
        h.carryContext(h.exhaustedContext(DESCRIPTION, CRITERIA));
        h.scriptCycleResults(CycleResultState.CONTINUE);

        h.runCycle();

        assertThat(h.goalPlannerService.getGoalById("S").getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        List<GoalEntity> residues = h.goalPlannerService.getAllGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL)
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE)
                .filter(g -> !"S".equals(g.getId()))
                .toList();
        assertThat(residues).hasSize(1);
        // The residue names the original in its description; the unmet criteria travel as its
        // declared acceptance criteria — exactly the ✗ one, none of the banked ✓ ones.
        assertThat(residues.get(0).getDescription()).contains(DESCRIPTION);
        assertThat(residues.get(0).getMetadata().get("acceptanceCriteria"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(CRITERIA.get(2));
    }

    @Test
    void extraOriginSubgoal_skipsRetryAndDivision_givesUpDirectly() {
        // EXTRA-origin subgoals are absorbed at closure anyway — spending retries or dividing
        // them would burn cycles on work the root does not require.
        ControlLoopScenarioHarness h = ControlLoopScenarioHarness.create()
                .withExhaustionCap(5);
        h.seedTree("Add optional unit tests for the controller", CRITERIA, true);
        verifierSaysAllUnmet(h);
        h.carryContext(h.exhaustedContext("Add optional unit tests for the controller", CRITERIA));
        h.scriptCycleResults(CycleResultState.CONTINUE);

        h.runCycle();

        assertThat(h.goalPlannerService.getGoalById("S").getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(h.goalPlannerService.getAllGoals().stream()
                .filter(g -> GoalPlannerService.DIVIDED_CRITERION_ROLE.equals(g.getRole()))
                .count()).isZero();
    }
}
