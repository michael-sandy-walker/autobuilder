package com.walpro.autobuilder.core.stuck;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-DY slice B: the ledger's rules -- when a question is due, what a recurrence does, when it ends. */
class StuckLedgerTest {

    private static StuckObservation build(String cycle) {
        return new StuckObservation(StuckObservation.Source.BUILD, "the build fails: Quill has no getInk()", "sg", cycle, List.of());
    }

    private static StuckDiagnosis.Asked answered() {
        return new StuckDiagnosis.Asked("q", "structure", "Quill declares: ink().", false);
    }

    @Test
    void aSignatureBecomesAQuestionAtItsThirdCycleAndCountsOncePerCycle() {
        StuckLedger l = new StuckLedger();
        assertThat(l.observe(build("c1"))).isEqualTo(StuckLedger.Change.COUNTED);
        assertThat(l.observe(build("c1"))).isEqualTo(StuckLedger.Change.NONE);
        assertThat(l.observe(build("c2"))).isEqualTo(StuckLedger.Change.COUNTED);
        assertThat(l.pending()).isEmpty();
        assertThat(l.observe(build("c3"))).isEqualTo(StuckLedger.Change.NOW_PENDING);
        assertThat(l.pending()).hasSize(1);
    }

    @Test
    void aRecurrenceAfterADeliveredAnswerMovesToTheNextQuestionEvenInTheSameCycle() {
        StuckLedger l = new StuckLedger();
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckLedger.Entry e = l.pending().get(0);
        l.recordAsked(e, answered(), "c3", "sg");
        assertThat(e.status()).isEqualTo(StuckLedger.Status.DELIVERED);

        assertThat(l.observe(build("c3"))).as("the move after the answer, in the answer's own cycle")
                .isEqualTo(StuckLedger.Change.RECURRED_AFTER_DELIVERY);
        assertThat(e.rung()).isEqualTo(1);
        assertThat(e.status()).isEqualTo(StuckLedger.Status.PENDING);
        assertThat(e.recurrencesAfterDelivery()).isEqualTo(1);
    }

    @Test
    void anUnansweredQuestionPastTheLastRungHandsTheEntryToTheOperatorOnce() {
        StuckLedger l = new StuckLedger();
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckLedger.Entry e = l.pending().get(0);
        StuckDiagnosis.Asked none = new StuckDiagnosis.Asked("q", "structure", null, false);
        assertThat(l.recordAsked(e, none, "c3", "sg")).isFalse();
        assertThat(l.recordAsked(e, none, "c3", "sg")).as("both BUILD questions asked, neither answered").isTrue();
        assertThat(e.status()).isEqualTo(StuckLedger.Status.UNANSWERED);
        assertThat(l.noQuestionLeft(e)).as("asked once, not again").isFalse();
    }

    @Test
    void aGreenBuildEndsADeliveredBuildSignatureAndReturnsAnUndeliveredOneToWatching() {
        StuckLedger l = new StuckLedger();
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckObservation other = new StuckObservation(StuckObservation.Source.BUILD, "the build fails: Ink.java: x", "sg", "c1", List.of());
        for (String c : List.of("c1", "c2", "c3")) l.observe(new StuckObservation(other.source(), other.signature(), "sg", c, List.of()));
        StuckLedger.Entry delivered = l.pending().get(0);
        l.recordAsked(delivered, answered(), "c4", "sg");

        List<StuckLedger.Entry> ended = l.buildSucceeded("c4");

        assertThat(ended).containsExactly(delivered);
        assertThat(delivered.status()).isEqualTo(StuckLedger.Status.ENDED);
        assertThat(delivered.recurrencesAfterDelivery()).isZero();
        assertThat(l.pending()).as("no late answer for a failure that stopped").isEmpty();
    }

    @Test
    void aBrakeIsAQuestionAtItsFirstReport() {
        StuckLedger l = new StuckLedger();
        assertThat(l.observe(new StuckObservation(StuckObservation.Source.FLAT_ROOT, "the root has not moved", null, "c1", List.of())))
                .isEqualTo(StuckLedger.Change.NOW_PENDING);
        StuckLedger.Entry e = l.pending().get(0);
        assertThat(l.noQuestionLeft(e)).isTrue();
        assertThat(e.status()).isEqualTo(StuckLedger.Status.UNANSWERED);
    }
}
