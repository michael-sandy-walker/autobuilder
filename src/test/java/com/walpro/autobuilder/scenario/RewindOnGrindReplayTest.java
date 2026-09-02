package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.core.lifecycle.ConvergenceTrend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DQ slice 3, measured against the runs it exists for. Each series is one run's own merges per
 * cycle, taken from its {@code behaviour.ndjson} ({@code cycle_end.merged}).
 *
 * <p>The question, stated before the test was written: at the halt edge, does the judgement the
 * brake already makes separate the runs that ground to death from the runs that finished — so that
 * "back out instead of dying" fires on the first group and never on the second? It does, and the
 * separation is not marginal.
 *
 * <p><b>A correction to the ticket's own figures, recorded because the ticket cites them.</b>
 * {@code T4-DQ} describes run {@code 2026-09-01_23-11-25} as "53 cycles, 25 rollbacks, 3 merges".
 * Its behaviour trace holds 53 cycles, FIVE merges, and ZERO {@code merge_rolled_back} events;
 * {@code 2026-08-31_05-47-22} likewise holds three merges and no rollback events. What the traces
 * do show is the shape the ticket is really about, and more starkly: after their early merges both
 * runs stopped landing anything at all, for 42 of 53 cycles (79%) and 60 of 65 (92%).
 */
class RewindOnGrindReplayTest {

    /** The brake's window, and so the window the rewind decision is taken over. */
    private static final int WINDOW = 20;

    private static List<Integer> series(int... merges) {
        return java.util.Arrays.stream(merges).boxed().toList();
    }

    /** 2026-09-01_23-11-25 — 53 cycles, 5 merges, shipped 5/23. */
    private static final List<Integer> GROUND_09_01_23_11 = series(
            1,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,1,0,0,0);

    /** 2026-08-31_05-47-22 — 65 cycles, 3 merges, shipped 5/23. */
    private static final List<Integer> GROUND_08_31_05_47 = series(
            1,1,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);

    /** 2026-09-01_03-31-05 — 26 cycles, 15 merges: a run that kept landing work throughout. */
    private static final List<Integer> HEALTHY_09_01_03_31 = series(
            1,1,0,1,0,0,1,0,1,0,1,0,1,1,0,0,1,1,1,0,1,1,0,1,0,1);

    /** 2026-09-01_02-50-26 — 41 cycles, 9 merges: droughts, but work kept landing after them. */
    private static final List<Integer> WORKING_09_01_02_50 = series(
            1,1,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,0,0,0,0,1,0,0,0,0,1,0,0,0,1,0,0,0,0,0,1,0);

    /**
     * The first cycle at which the trailing window holds no merge at all — the earliest point the
     * brake could reach its "nothing is landing" verdict, and so the earliest point a rewind would
     * be taken. Empty when the run never has such a window.
     */
    private static java.util.OptionalInt firstEmptyWindow(List<Integer> run) {
        for (int end = WINDOW; end <= run.size(); end++) {
            if (ConvergenceTrend.assess(run.subList(0, end), WINDOW).windowMerges() == 0) {
                return java.util.OptionalInt.of(end);
            }
        }
        return java.util.OptionalInt.empty();
    }

    @Test
    @DisplayName("both runs that ground to death reach a window with nothing in it, with most of the run left")
    void theGrindingRunsWouldHaveBackedOut() {
        java.util.OptionalInt first0901 = firstEmptyWindow(GROUND_09_01_23_11);
        java.util.OptionalInt first0831 = firstEmptyWindow(GROUND_08_31_05_47);

        assertThat(first0901).isPresent();
        assertThat(first0831).isPresent();
        // 24 of 53 and 25 of 65: more than half of each run was still ahead of it.
        assertThat(first0901.getAsInt()).isEqualTo(24);
        assertThat(first0831.getAsInt()).isEqualTo(25);
        assertThat(GROUND_09_01_23_11.size() - first0901.getAsInt()).isGreaterThan(25);
        assertThat(GROUND_08_31_05_47.size() - first0831.getAsInt()).isGreaterThan(35);

        // And there is an earlier point that landed work to return to, which is what makes backing
        // out possible rather than merely desirable.
        for (List<Integer> run : List.of(GROUND_09_01_23_11, GROUND_08_31_05_47)) {
            assertThat(run.subList(0, WINDOW).stream().mapToInt(Integer::intValue).sum()).isPositive();
        }
    }

    @Test
    @DisplayName("neither run that finished healthily ever reaches an empty window")
    void theHealthyRunsAreLeftAlone() {
        assertThat(firstEmptyWindow(HEALTHY_09_01_03_31))
                .as("15 merges in 26 cycles — the brake never reaches the rewind decision")
                .isEmpty();
        assertThat(firstEmptyWindow(WORKING_09_01_02_50))
                .as("droughts of up to 13 cycles, but work keeps landing inside every window")
                .isEmpty();
    }

    @Test
    @DisplayName("a late flurry is why the decision is taken during the grind, not at the end")
    void theDecisionBelongsDuringTheGrindNotAtTheEnd() {
        // 2026-09-01_23-11-25 landed two merges in its last seven cycles and died anyway, so its
        // FINAL window is not empty. Judging only at the terminal would have missed it entirely —
        // the run is caught at cycle 24, not at cycle 53.
        assertThat(ConvergenceTrend.assess(GROUND_09_01_23_11, WINDOW).windowMerges()).isEqualTo(2);
        assertThat(ConvergenceTrend.assess(GROUND_09_01_23_11.subList(0, 30), WINDOW).windowMerges())
                .isZero();
    }

    @Test
    @DisplayName("the two populations do not overlap at the point the decision is taken")
    void thePopulationsAreDisjoint() {
        assertThat(firstEmptyWindow(GROUND_09_01_23_11)).isPresent();
        assertThat(firstEmptyWindow(GROUND_08_31_05_47)).isPresent();
        assertThat(firstEmptyWindow(HEALTHY_09_01_03_31)).isEmpty();
        assertThat(firstEmptyWindow(WORKING_09_01_02_50)).isEmpty();
    }
}
