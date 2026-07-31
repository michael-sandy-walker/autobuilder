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

    private static final String TREE = "tree-state-a";
    private static final String MOVED = "tree-state-b";

    @Test
    void holdsNoOpinionOnAProposalItHasNeverSeen() {
        assertThat(ledger.standingRejection("never-seen", TREE)).isEmpty();
    }

    @Test
    void aRejectionStandsWhileTheTreeIsUnchanged() {
        ledger.recordRejection("fp-a", TREE, "MergeRegressionHeuristic: takes an annotation off a type", "goal-1");

        assertThat(ledger.standingRejection("fp-a", TREE)).hasValueSatisfying(r -> {
            assertThat(r.priorRejections()).isEqualTo(1);
            assertThat(r.reason()).contains("takes an annotation off a type");
        });
    }

    @Test
    void aTreeThatHasMovedMakesTheEarlierVerdictRevisable() {
        // The case the scoping exists for: a proposal that could not compile before a missing type
        // landed may compile after. Refusing it then would be wrong.
        ledger.recordRejection("fp-a", TREE, "Compile probe failed", "goal-1");

        assertThat(ledger.standingRejection("fp-a", MOVED)).isEmpty();
    }

    @Test
    void aRejectionAgainstTheNewTreeStandsAgain() {
        ledger.recordRejection("fp-a", TREE, "Compile probe failed", "goal-1");
        ledger.recordRejection("fp-a", MOVED, "Compile probe failed", "goal-1");

        assertThat(ledger.standingRejection("fp-a", MOVED)).hasValueSatisfying(
                r -> assertThat(r.priorRejections()).isEqualTo(2));
    }

    @Test
    void aWriterOtherThanAMergeAlsoRevisesTheVerdict() {
        // A merge-only rule would have refused 4 of the corpus's 39 duplicates wrongly: the
        // compile-fix injector's files are preserved through the rejection rollback by design, so
        // the tree moves without a merge. Keying on the tree covers that without anyone having to
        // enumerate the writers.
        ledger.recordRejection("fp-a", TREE, "Compile probe failed", "goal-1");

        assertThat(ledger.standingRejection("fp-a", "tree-after-an-injector-skeleton-landed")).isEmpty();
    }

    @Test
    void anUnknownTreeIsNeverGroundsForRefusing() {
        // A verdict that cannot be scoped is not one to act on — refusing against an unknown base
        // is exactly the wrong-refusal case the scoping prevents.
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");

        assertThat(ledger.standingRejection("fp-a", null)).isEmpty();
        assertThat(ledger.standingRejection("fp-a", "  ")).isEmpty();
    }

    @Test
    void theSubgoalSpanSaysWhetherReplanningIsSurvivingTheRepetition() {
        // A repeat confined to one subgoal is the mutation's problem; one that survives a replan is
        // the plan's, and that is the distinction the escalation reads.
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");
        ledger.recordRejection("fp-a", TREE, "reason", "goal-2");

        assertThat(ledger.standingRejection("fp-a", TREE)).hasValueSatisfying(
                r -> assertThat(r.subgoalSpan()).isEqualTo(2));
    }

    @Test
    void consultingTheLedgerDoesNotCountAsRefusingAnything() {
        // The readout must measure what was refused, not what was asked about — otherwise a test or
        // a readout that consults it would inflate the very figure the ticket collects.
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");
        ledger.standingRejection("fp-a", TREE);
        ledger.standingRejection("fp-a", TREE);

        assertThat(ledger.readout().refusals()).isZero();

        ledger.noteRefusal("fp-a", TREE, "goal-1");
        assertThat(ledger.readout().refusals()).isEqualTo(1);
    }

    @Test
    void aRefusalIsItselfARejectionSoRepeatsEscalate() {
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");
        ledger.noteRefusal("fp-a", TREE, "goal-1");

        assertThat(ledger.standingRejection("fp-a", TREE)).hasValueSatisfying(
                r -> assertThat(r.priorRejections()).isEqualTo(2));
    }

    @Test
    void aRefusalKeepsTheOriginalReasonRatherThanBlankingIt() {
        // The relay is the point: the generator must be told the same thing it was told the first
        // time, not a paraphrase and not nothing.
        ledger.recordRejection("fp-a", TREE, "ScoreRegressionHeuristic: severe score drop", "goal-1");
        ledger.noteRefusal("fp-a", TREE, "goal-1");

        assertThat(ledger.standingRejection("fp-a", TREE)).hasValueSatisfying(
                r -> assertThat(r.reason()).isEqualTo("ScoreRegressionHeuristic: severe score drop"));
    }

    @Test
    void theReadoutCountsRepeatedProposalsSeparatelyFromDistinctOnes() {
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");
        ledger.recordRejection("fp-b", TREE, "reason", "goal-1");

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
        ledger.recordRejection(null, TREE, "reason", "goal-1");
        ledger.recordRejection("  ", TREE, "reason", "goal-1");

        assertThat(ledger.readout().distinctRejected()).isZero();
        assertThat(ledger.standingRejection(null, TREE)).isEmpty();
        assertThat(ledger.standingRejection("  ", TREE)).isEmpty();
    }

    @Test
    void clearDropsEverything() {
        ledger.recordRejection("fp-a", TREE, "reason", "goal-1");
        ledger.noteRefusal("fp-a", TREE, "goal-1");
        ledger.clear();

        assertThat(ledger.readout().isEmpty()).isTrue();
        assertThat(ledger.readout().refusals()).isZero();
        assertThat(ledger.standingRejection("fp-a", TREE)).isEmpty();
    }
}
