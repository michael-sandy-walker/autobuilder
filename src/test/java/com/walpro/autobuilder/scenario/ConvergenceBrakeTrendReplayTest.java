package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.lifecycle.ConvergenceTrend;

/**
 * Replays every recorded convergence stall in the archive against the trend test.
 *
 * <p>Each series is one run's own merges per cycle, taken from its {@code behaviour.ndjson}
 * ({@code mutation_merged} grouped by {@code cycleId}). The window under judgement is the last
 * {@code cycleLimit} cycles, which at the halt is exactly the drought that fired the brake.
 *
 * <p>The gate this test exists to answer, stated on the ticket before it was written: would the
 * trend test have held the brake on the run that deserved its halt, and released it through the
 * trough of the run that recovered? Both, and the wider corpus divides the same way — the six
 * windows with nothing landing are the runs that produced no scorable artifact.
 */
class ConvergenceBrakeTrendReplayTest {

    private static final int WINDOW = 20;

    private static List<Integer> series(int... merges) {
        return java.util.Arrays.stream(merges).boxed().toList();
    }

    /** The run that deserved its halt: 21 consecutive cycles proposing and being rejected. */
    private static final List<Integer> STALLED_18_44_37 =
            series(1,1,0,1,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);

    /** The run that looked identical at 14 of 20 and closed at 23/23 twenty cycles later. */
    private static final List<Integer> RECOVERED_19_42_35 =
            series(1,0,1,0,0,1,1,1,1,0,1,0,0,1,1,1,0,1,1,1,0,1,1,0,1,0,0,0,1,0,0,0,0,0,0,0,0);

    @Test
    void theRunThatDeservedItsHaltStillHalts() {
        var trend = ConvergenceTrend.assess(STALLED_18_44_37, WINDOW);

        assertThat(trend.stillLandingWork()).isFalse();
        assertThat(trend.windowMerges()).isZero();
        // It had been landing work before the drought — this is a run that stopped, not one that
        // never started.
        assertThat(trend.earlierMerges()).isPositive();
    }

    @Test
    void theRunThatRecoveredIsReleasedThroughItsTrough() {
        // The ticket's discriminating case: judged at the point its counter read 14 of 20.
        var atTheTrough = ConvergenceTrend.assess(RECOVERED_19_42_35.subList(0, 34), WINDOW);
        assertThat(atTheTrough.stillLandingWork()).isTrue();
        // Nine merges in the twenty cycles the brake would have been judging.
        assertThat(atTheTrough.windowMerges()).isEqualTo(9);

        // And judged at the end of the run, where the closure tail merges nothing at all.
        assertThat(ConvergenceTrend.assess(RECOVERED_19_42_35, WINDOW).stillLandingWork()).isTrue();
    }

    @Test
    void aRateRatioWouldNeedAThresholdThisCorpusDoesNotSupply() {
        // Recorded as data because a rate ratio is the rule this design started with, and it does
        // separate the corpus — but only on a threshold sited between these two runs, which are
        // 0.09 apart and land on opposite sides of the outcome. Siting a constant in that gap on
        // thirteen runs is the fixed constant this ticket exists to remove.
        var noArtifact = ConvergenceTrend.assess(
                series(0,0,0,1,1,0,1,0,0,0,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0), WINDOW);
        var scoredFullMarks = ConvergenceTrend.assess(
                series(1,0,1,1,0,1,1,0,0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0), WINDOW);

        assertThat(noArtifact.windowRate() / noArtifact.earlierRate()).isCloseTo(0.14, within(0.01));
        assertThat(scoredFullMarks.windowRate() / scoredFullMarks.earlierRate())
                .isCloseTo(0.23, within(0.01));
        // Both are held by the shipped rule; the cost of holding the first is one window, and the
        // cost of halting the second was a 19/19 run.
        assertThat(noArtifact.stillLandingWork()).isTrue();
        assertThat(scoredFullMarks.stillLandingWork()).isTrue();
    }

    @Test
    void everyRecordedStallDividesTheSameWay() {
        record Recorded(String run, boolean stillLandingWork, List<Integer> merges) {}
        // Thirteen runs whose logs carry "root convergence stall". The verdict column is what the
        // trend test says; the comment is what the run actually produced.
        List<Recorded> corpus = List.of(
                new Recorded("2026-08-22_06-19-55", false, series(1,1,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)),           // no artifact
                new Recorded("2026-08-23_18-44-18", true,  series(1,1,1,1,1,1,1,0,0,0,0,1,0,1,0,1,0,1,1,1,1,0,0,0,0,1,0,0,1,0,1,0,1,1,0,0,1,1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0,0,0,1,1)),     // 22/23
                new Recorded("2026-08-24_00-07-06", false, series(1,0,0,0,1,1,1,1,1,0,1,0,1,1,0,0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)),                                       // no artifact
                new Recorded("2026-08-24_22-14-05", true,  series(1,1,1,1,1,1,0,1,0,0,0,1,1,1,0,1,1,0,0,0,0,1,0,1,0,1,0,0,0,1,1,1,0,1,0,1,1,1,0,1,0,0,0,0,0,0,0,0,1,1,0,1,0,1,0,1,1,1)),   // 19/23
                new Recorded("2026-08-24_23-24-13", true,  series(1,1,1,1,1,0,0,0,1,0,0,0,0,1,1,0,0,1,0,0,1,0,0,1,0,1,1,0,0,1,1,0,0,0,1,1,0,0,0,1,1,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,1,0,1,1,1,1,0,0,0,0,0,0,0,0,1,1,0,1,1,0)), // 23/23
                new Recorded("2026-08-25_04-53-00", true,  series(1,1,1,1,0,1,1,0,0,1,1,1,0,1,1,1,0,1,1,0,1,0,0,0,0,0,1,1,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,1,0,1,1,1,0,0,0,1,0,1,0,1,0,1,0,0,1,1,0,0,1,0,0,0,1,0,1,0,1,1,0,0,1,0)), // 23/23
                new Recorded("2026-08-25_10-46-31", true,  series(1,0,0,1,1,1,1,1,1,0,1,0,1,1,0,0,1,1,0,0,0,0,0,1,1,1,0,0,0,1,1,1,0,1,1,1,0,1,1,0,1,0,1,1,1,1,1,1,1,1,0,1,0,1,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0)), // 21/23
                new Recorded("2026-08-28_03-51-21", true,  series(0,0,0,1,1,0,1,0,0,0,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)),                                                         // no artifact
                new Recorded("2026-08-28_13-58-46", false, series(0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)),                             // no artifact
                new Recorded("2026-08-28_16-35-37", false, series(1,0,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)),                             // 12/12, already finished
                new Recorded("2026-08-28_20-37-30", false, series(1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)),                                                             // no artifact
                new Recorded("2026-08-29_23-08-29", true,  series(1,0,1,1,0,1,1,0,0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0)),                                                                   // 19/19
                new Recorded("2026-08-30_18-44-37", false, STALLED_18_44_37));                                                                                                             // no artifact

        for (Recorded r : corpus) {
            assertThat(ConvergenceTrend.assess(r.merges(), WINDOW).stillLandingWork())
                    .describedAs(r.run())
                    .isEqualTo(r.stillLandingWork());
        }
        // Six halts and seven holds: the brake keeps its teeth on the runs that stopped working.
        assertThat(corpus.stream().filter(r -> !r.stillLandingWork()).count()).isEqualTo(6);
        assertThat(corpus.stream().filter(Recorded::stillLandingWork).count()).isEqualTo(7);
    }

    @Test
    void aHistoryShorterThanTheWindowIsNotARate() {
        // Property 2c: a window that holds fewer cycles than the drought it describes cannot be
        // a rate, and reading a trend from it is the false-trend error this ticket's own first
        // diagnosis committed in miniature.
        assertThat(ConvergenceTrend.assess(series(1, 1, 1), WINDOW).stillLandingWork()).isFalse();
    }
}
