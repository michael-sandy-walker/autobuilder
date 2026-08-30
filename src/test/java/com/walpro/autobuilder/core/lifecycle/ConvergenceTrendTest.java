package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The trend decision itself, apart from the brake that consults it. */
class ConvergenceTrendTest {

    private static List<Integer> series(int... v) {
        return java.util.Arrays.stream(v).boxed().toList();
    }

    @Test
    void workLandingInTheWindowIsWorkLanding() {
        var trend = ConvergenceTrend.assess(series(1, 1, 0, 0, 1, 0), 3);

        assertThat(trend.stillLandingWork()).isTrue();
        assertThat(trend.windowMerges()).isEqualTo(1);
        assertThat(trend.earlierMerges()).isEqualTo(2);
    }

    @Test
    void anEmptyWindowIsNothingLanding_evenAfterAProductiveRun() {
        var trend = ConvergenceTrend.assess(series(1, 1, 1, 0, 0, 0), 3);

        assertThat(trend.stillLandingWork()).isFalse();
        assertThat(trend.windowMerges()).isZero();
        assertThat(trend.earlierMerges()).isEqualTo(3);
    }

    @Test
    void theRatesReportedAreTheRunsOwn() {
        // Window = the last four cycles (1,0,0,1); earlier = the first four (1,1,1,1).
        var trend = ConvergenceTrend.assess(series(1, 1, 1, 1, 1, 0, 0, 1), 4);

        assertThat(trend.windowRate()).isCloseTo(0.5, within(0.001));
        assertThat(trend.earlierRate()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void aWindowShorterThanItsOwnLengthYieldsNoTrend() {
        var trend = ConvergenceTrend.assess(series(1, 1), 5);

        assertThat(trend.stillLandingWork()).isFalse();
        assertThat(trend.windowCycles()).isZero();
        assertThat(trend.windowRate()).isZero();
    }

    @Test
    void aRunWithNoHistoryAtAllReportsNothingRatherThanGuessing() {
        assertThat(ConvergenceTrend.assess(null, 5).stillLandingWork()).isFalse();
        assertThat(ConvergenceTrend.assess(List.of(), 5).stillLandingWork()).isFalse();
        assertThat(ConvergenceTrend.assess(series(1, 1, 1), 0).stillLandingWork()).isFalse();
    }
}
