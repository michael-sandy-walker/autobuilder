package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T4-DQ: the grind predicate the rewind is triggered on, after the field showed that wiring it to
 * the convergence brake's HALT reached only 12% of runs.
 *
 * <p>The re-fire guard is the one that matters most here. A rewind clears the merge series, and an
 * empty series read naively says "nothing is landing" — which would rewind again on the very next
 * cycle, and the next, without the new path ever getting a chance. Requiring a full window of
 * history is what makes a fresh branch un-judgeable until it has had one.
 */
class RootConvergenceTrackerGrindingTest {

    private RootConvergenceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RootConvergenceTracker(mock(GoalPlannerService.class));
        ReflectionTestUtils.setField(tracker, "cycleLimit", 20);
        ReflectionTestUtils.setField(tracker, "midRunWindowFactor", 1.5);
    }

    @Test
    @DisplayName("a run with less than a window of history is never judged to be grinding")
    void aShortHistoryIsNotAGrind() {
        feed(nZeros(tracker.midRunWindow() - 1));
        assertThat(tracker.grinding())
                .as("a window that holds fewer cycles than the drought it describes is not a rate")
                .isFalse();
    }

    @Test
    @DisplayName("a full window with nothing landing is a grind")
    void afullEmptyWindowIsAGrind() {
        feed(nZeros(tracker.midRunWindow()));
        assertThat(tracker.grinding()).isTrue();
    }

    @Test
    @DisplayName("work landing anywhere in the window is not a grind")
    void workInTheWindowIsNotAGrind() {
        List<Integer> series = new ArrayList<>(nZeros(tracker.midRunWindow() - 1));
        series.add(1);
        feed(series);
        assertThat(tracker.grinding()).isFalse();
    }

    @Test
    @DisplayName("a drought shorter than the window does not fire, however long the run is")
    void aShortDroughtInsideALongRunIsNotAGrind() {
        // The measured shape of 2026-09-01_02-50-26: droughts of up to 13 cycles, work landing
        // inside every window, and a run that finished healthily.
        List<Integer> series = new ArrayList<>();
        for (int block = 0; block < 4; block++) {
            series.add(1);
            series.addAll(nZeros(13));
        }
        feed(series);
        assertThat(tracker.grinding()).isFalse();
    }

    @Test
    @DisplayName("the 23/23 run's nineteen-cycle closure tail does not read as a grind")
    void aCompletedRunsClosureTailIsNotAGrind() {
        // Run 2026-09-02_23-42-24, exactly as its behaviour trace records it: six merges in the
        // first eight cycles, then nineteen cycles of closure and verification work, and a 23/23
        // artifact at the end. At the brake's own window of 20 this run sat ONE cycle away from
        // being rewound; the wider mid-run window is what buys the margin.
        feed(List.of(1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        assertThat(tracker.grinding())
                .as("a complete artifact closing itself out lands no merges and must not be rewound")
                .isFalse();
    }

    @Test
    @DisplayName("the runs that ground to death do read as a grind, with most of the run still left")
    void theGroundRunsAreCaught() {
        // 2026-08-31_05-47-22: three merges in its first five cycles, then sixty cycles of nothing.
        List<Integer> ground = new ArrayList<>(List.of(1, 1, 0, 0, 1));
        ground.addAll(nZeros(60));
        feed(ground);

        assertThat(tracker.grinding()).isTrue();
    }

    @Test
    @DisplayName("after a rewind the new path gets a full window before it can be judged again")
    void aRewoundPathIsNotImmediatelyRejudged() {
        feed(nZeros(40));
        assertThat(tracker.grinding()).isTrue();

        tracker.resyncAfterRewind();

        assertThat(tracker.grinding())
                .as("the series is empty; without the window requirement this would rewind again "
                        + "on the very next cycle, forever")
                .isFalse();
        feed(nZeros(tracker.midRunWindow() - 1));
        assertThat(tracker.grinding()).isFalse();
        feed(nZeros(1));
        assertThat(tracker.grinding())
                .as("and it becomes judgeable again only once the new path has had a full window")
                .isTrue();
    }

    private void feed(List<Integer> merges) {
        @SuppressWarnings("unchecked")
        List<Integer> series = (List<Integer>) ReflectionTestUtils.getField(tracker, "mergesPerCycle");
        series.addAll(merges);
    }

    private static List<Integer> nZeros(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(0);
        return out;
    }
}
