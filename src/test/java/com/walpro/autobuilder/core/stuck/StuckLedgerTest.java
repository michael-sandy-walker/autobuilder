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

    /**
     * T6-T, ladder-grind 2026-10-10_19-22-18: four answers found no room in the prompt and were counted delivered;
     * the next recurrence moved each to its next question, past a fact the model had never read.
     */
    @Test
    void aRecurrenceDoesNotMovePastAnAnswerThatWasNeverShown() {
        StuckLedger l = new StuckLedger();
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckLedger.Entry e = l.pending().get(0);
        l.recordAsked(e, answered(), "c3", "sg");
        int rung = e.rung();
        l.withheld(e, true);

        assertThat(l.observe(build("c4"))).isEqualTo(StuckLedger.Change.COUNTED);
        assertThat(e.status()).as("still owed, at the same question").isEqualTo(StuckLedger.Status.DELIVERED);
        assertThat(e.rung()).isEqualTo(rung);
        assertThat(l.delivered()).containsExactly(e);

        l.withheld(e, false);   // shown at last
        assertThat(l.observe(build("c5"))).isEqualTo(StuckLedger.Change.RECURRED_AFTER_DELIVERY);
        assertThat(e.rung()).isEqualTo(rung + 1);
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
        for (int i = 1; i < StuckDiagnosis.questionsFor(StuckObservation.Source.BUILD); i++) {
            assertThat(l.recordAsked(e, none, "c3", "sg")).isFalse();
        }
        assertThat(l.recordAsked(e, none, "c3", "sg")).as("every BUILD question asked, none answered").isTrue();
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
    /** An answer to failing tests ends when the suite passes, as a build answer ends when a build compiles. */
    @Test
    void aPassingSuiteEndsADeliveredTestFailureAnswerAndLeavesBuildAnswersAlone() {
        StuckLedger l = new StuckLedger();
        StuckObservation tests = new StuckObservation(StuckObservation.Source.TEST_FAILURES,
                "the same tests keep failing: QuillTest.dries", "sg-1", "c1", List.of("QuillTest.dries -- expected 3"));
        l.observe(tests);
        StuckLedger.Entry t = l.pending().get(0);
        l.recordAsked(t, answered(), "c1", "sg-1");
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckLedger.Entry b = l.pending().get(0);
        l.recordAsked(b, answered(), "c3", "sg-1");

        assertThat(l.testsPassed("c4")).containsExactly(t);
        assertThat(t.status()).isEqualTo(StuckLedger.Status.ENDED);
        assertThat(b.status()).as("a build answer ends on a compiling build, not on the tests").isEqualTo(StuckLedger.Status.DELIVERED);
    }

    @Test
    void answersAboutTheTreeStandWhateverSubgoalIsCurrent() {
        assertThat(StuckLayer.aboutTheTree(StuckObservation.Source.BUILD)).isTrue();
        assertThat(StuckLayer.aboutTheTree(StuckObservation.Source.TEST_FAILURES)).isTrue();
        assertThat(StuckLayer.aboutTheTree(StuckObservation.Source.CLOSURE_REFUSAL)).isFalse();
    }
    /**
     * T6-T: an answer about the tests whose failure came back with no question left still stands -- it is the
     * last fact read about a failure that is still there -- and ends with the suite like a delivered one.
     */
    @Test
    void aTestAnswerWithNoQuestionLeftStandsWhileTheFailureDoes_andEndsWithTheSuite() {
        StuckLedger l = new StuckLedger();
        StuckObservation tests = new StuckObservation(StuckObservation.Source.TEST_FAILURES,
                "the same tests keep failing: QuillTest.dries", "sg-1", "c1", List.of("QuillTest.dries -- expected 3"));
        l.observe(tests);
        StuckLedger.Entry t = l.pending().get(0);
        l.recordAsked(t, answered(), "c1", "sg-1");
        l.observe(new StuckObservation(StuckObservation.Source.TEST_FAILURES,
                "the same tests keep failing: QuillTest.dries", "sg-1", "c2", List.of("QuillTest.dries -- expected 3")));
        // The trace question repeats: asked again, and with every test already traced it has nothing new to say.
        assertThat(t.status()).isEqualTo(StuckLedger.Status.PENDING);
        assertThat(t.rung()).as("the same question, not the next").isZero();
        l.recordAsked(t, new StuckDiagnosis.Asked("What did the failing test's call execute?", "observation", null, false), "c2", "sg-1");

        assertThat(t.status()).isEqualTo(StuckLedger.Status.UNANSWERED);
        assertThat(l.delivered()).containsExactly(t);
        assertThat(l.testsPassed("c3")).containsExactly(t);
        assertThat(t.status()).isEqualTo(StuckLedger.Status.ENDED);
        assertThat(l.delivered()).isEmpty();
    }

    /**
     * T6-T, ladder-grind 2026-10-13, three runs of three: a call a library type does not declare was answered at
     * minute 2, the build went green on it, and some fifty minutes later the same call was written again -- and
     * met the entry two questions further on, neither with an answer. An answer that ended the failure is the
     * answer to the same failure made anew.
     */
    @Test
    void aFailureThatComesBackAfterItsAnswerEndedIt_isAskedTheQuestionThatAnsweredIt() {
        StuckLedger l = new StuckLedger();
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckLedger.Entry e = l.pending().get(0);
        l.recordAsked(e, answered(), "c3", "sg");
        int answeredAt = e.rung();
        assertThat(l.buildSucceeded("c4")).containsExactly(e);
        assertThat(l.corrected()).as("the register of what was put right").containsExactly(e);

        assertThat(l.observe(build("c9"))).isEqualTo(StuckLedger.Change.RECURRED_AFTER_DELIVERY);

        assertThat(e.rung()).as("the same question, not the next").isEqualTo(answeredAt);
        assertThat(e.status()).isEqualTo(StuckLedger.Status.PENDING);
        assertThat(l.corrected()).as("failing again: it is an open question, not a corrected one").isEmpty();
    }

    /** An answer that was shown and did NOT end the failure still gives way to the next question. */
    @Test
    void aFailureThatPersistsThroughItsAnswerStillMovesOn() {
        StuckLedger l = new StuckLedger();
        for (String c : List.of("c1", "c2", "c3")) l.observe(build(c));
        StuckLedger.Entry e = l.pending().get(0);
        l.recordAsked(e, answered(), "c3", "sg");
        int answeredAt = e.rung();

        l.observe(build("c4"));

        assertThat(e.rung()).isEqualTo(answeredAt + 1);
    }
}
