package com.walpro.autobuilder.core.mutation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-EY: the ledger's whole value is the safety condition, so most of these pin that rather than
 * the bookkeeping. The numbers in the comments come from the 33 run corpora the ticket was measured
 * against, not from invented cases.
 */
class RejectedProposalLedgerTest {

    private RejectedProposalLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new RejectedProposalLedger();
    }

    @Test
    void holdsNoOpinionOnAProposalItHasNeverSeen() {
        assertThat(ledger.standingRejection("never-seen")).isEmpty();
    }

    @Test
    void aRejectionStandsUntilSomethingCouldHaveChangedIt() {
        ledger.recordRejection("fp-a", "MergeRegressionHeuristic: takes an annotation off a type", "goal-1");

        assertThat(ledger.standingRejection("fp-a")).hasValueSatisfying(r -> {
            assertThat(r.priorRejections()).isEqualTo(1);
            assertThat(r.reason()).contains("takes an annotation off a type");
        });
    }

    @Test
    void aMergeMakesTheEarlierVerdictRevisable() {
        // The case the safety condition exists for: 1 of the 39 duplicate rejections in the corpus
        // had a merge in between, so the tree it would be judged against was no longer the same.
        // Refusing it would have been wrong.
        ledger.recordRejection("fp-a", "Compile probe failed", "goal-1");
        ledger.recordMerge();

        assertThat(ledger.standingRejection("fp-a")).isEmpty();
    }

    @Test
    void aRejectionAfterTheMergeStandsAgain() {
        ledger.recordRejection("fp-a", "Compile probe failed", "goal-1");
        ledger.recordMerge();
        ledger.recordRejection("fp-a", "Compile probe failed", "goal-1");

        assertThat(ledger.standingRejection("fp-a")).hasValueSatisfying(
                r -> assertThat(r.priorRejections()).isEqualTo(2));
    }

    @Test
    void aMergeDoesNotRehabilitateAProposalRejectedAfterIt() {
        // Ordering matters, not merely whether a merge ever happened: the merge must postdate the
        // rejection to have changed anything about it.
        ledger.recordMerge();
        ledger.recordRejection("fp-a", "Compile probe failed", "goal-1");

        assertThat(ledger.standingRejection("fp-a")).isPresent();
    }

    @Test
    void oneProposalsMergeDoesNotRehabilitateAnothersRejection() {
        // A merge is a fact about the tree, so it revises every standing verdict — including ones
        // for proposals unrelated to what merged. This is deliberately conservative: the ledger
        // cannot tell which rejections the merge could have affected, so it stands down on all.
        ledger.recordRejection("fp-a", "reason a", "goal-1");
        ledger.recordRejection("fp-b", "reason b", "goal-1");
        ledger.recordMerge();

        assertThat(ledger.standingRejection("fp-a")).isEmpty();
        assertThat(ledger.standingRejection("fp-b")).isEmpty();
    }

    @Test
    void theSubgoalSpanSaysWhetherReplanningIsSurvivingTheRepetition() {
        // A repeat confined to one subgoal is the mutation's problem; one that survives a replan is
        // the plan's, and that is the distinction the escalation reads.
        ledger.recordRejection("fp-a", "reason", "goal-1");
        ledger.recordRejection("fp-a", "reason", "goal-2");

        assertThat(ledger.standingRejection("fp-a")).hasValueSatisfying(
                r -> assertThat(r.subgoalSpan()).isEqualTo(2));
    }

    @Test
    void consultingTheLedgerDoesNotCountAsRefusingAnything() {
        // The readout must measure what was refused, not what was asked about — otherwise a test or
        // a readout that consults it would inflate the very figure the ticket collects.
        ledger.recordRejection("fp-a", "reason", "goal-1");
        ledger.standingRejection("fp-a");
        ledger.standingRejection("fp-a");

        assertThat(ledger.readout().refusals()).isZero();

        ledger.noteRefusal("fp-a", "goal-1");
        assertThat(ledger.readout().refusals()).isEqualTo(1);
    }

    @Test
    void aRefusalIsItselfARejectionSoRepeatsEscalate() {
        ledger.recordRejection("fp-a", "reason", "goal-1");
        ledger.noteRefusal("fp-a", "goal-1");

        assertThat(ledger.standingRejection("fp-a")).hasValueSatisfying(
                r -> assertThat(r.priorRejections()).isEqualTo(2));
    }

    @Test
    void aRefusalKeepsTheOriginalReasonRatherThanBlankingIt() {
        // The relay is the point: the generator must be told the same thing it was told the first
        // time, not a paraphrase and not nothing.
        ledger.recordRejection("fp-a", "ScoreRegressionHeuristic: severe score drop", "goal-1");
        ledger.noteRefusal("fp-a", "goal-1");

        assertThat(ledger.standingRejection("fp-a")).hasValueSatisfying(
                r -> assertThat(r.reason()).isEqualTo("ScoreRegressionHeuristic: severe score drop"));
    }

    @Test
    void theReadoutCountsRepeatedProposalsSeparatelyFromDistinctOnes() {
        ledger.recordRejection("fp-a", "reason", "goal-1");
        ledger.recordRejection("fp-a", "reason", "goal-1");
        ledger.recordRejection("fp-b", "reason", "goal-1");

        var readout = ledger.readout();
        assertThat(readout.distinctRejected()).isEqualTo(2);
        assertThat(readout.repeatedProposals()).isEqualTo(1);
        assertThat(readout.worstRepeatCount()).isEqualTo(2);
        assertThat(readout.isEmpty()).isFalse();
    }

    @Test
    void aBlankFingerprintIsNotAnIdentity() {
        // 46 rejections in the corpus carried no fingerprint at all. Treating absent identity as a
        // shared one would make every such rejection a repeat of every other.
        ledger.recordRejection(null, "reason", "goal-1");
        ledger.recordRejection("  ", "reason", "goal-1");

        assertThat(ledger.readout().distinctRejected()).isZero();
        assertThat(ledger.standingRejection(null)).isEmpty();
        assertThat(ledger.standingRejection("  ")).isEmpty();
    }

    @Test
    void clearDropsEverything() {
        ledger.recordRejection("fp-a", "reason", "goal-1");
        ledger.noteRefusal("fp-a", "goal-1");
        ledger.clear();

        assertThat(ledger.readout().isEmpty()).isTrue();
        assertThat(ledger.readout().refusals()).isZero();
        assertThat(ledger.standingRejection("fp-a")).isEmpty();
    }
}
