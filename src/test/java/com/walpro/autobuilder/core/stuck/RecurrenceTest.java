package com.walpro.autobuilder.core.stuck;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-DY: a failure is persistent by ratios over the run -- never by a fixed count. */
class RecurrenceTest {

    private static final String S = "the same tests keep failing: QuillTest.dries";

    @Test
    void oneSightingSpansNothing() {
        assertThat(new Recurrence(0).observe(S, 10_000)).isFalse();
    }

    @Test
    void aFailureHeldOverAQuarterOfTheRunInMostObservationsIsPersistent() {
        Recurrence r = new Recurrence(0);
        r.observe(S, 240_000);
        r.observe(S, 900_000);
        assertThat(r.observe(S, 1_140_000)).as("minutes 4, 15, 19 of 19").isTrue();
    }

    @Test
    void theSameSightingsLateInALongRunSpanTooLittleOfIt() {
        Recurrence r = new Recurrence(0);
        r.observe(S, 3_300_000);
        assertThat(r.observe(S, 3_480_000)).as("3 of 58 minutes").isFalse();
    }

    @Test
    void cleanOrOtherObservationsLowerTheShare() {
        Recurrence r = new Recurrence(0);
        r.observe(S, 60_000);
        r.observe(null, 300_000);
        r.observe("other", 500_000);
        assertThat(r.observe(S, 600_000)).as("2 of 4 since first").isFalse();
        assertThat(r.observe(null, 700_000)).as("a clean observation is never persistent").isFalse();
    }
}
