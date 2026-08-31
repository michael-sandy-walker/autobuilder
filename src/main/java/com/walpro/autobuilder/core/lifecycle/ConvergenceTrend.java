package com.walpro.autobuilder.core.lifecycle;

import java.util.List;

/**
 * T2-FH: whether a run that has stopped CLOSING subgoals has also stopped LANDING work.
 *
 * <p>WHY THE BRAKE NEEDED THIS. {@link RootConvergenceTracker} halts after a fixed number of
 * consecutive cycles without a completed-with-merge subgoal. That input is identical in shape for
 * a run that is grinding and one that is improving, because it counts a drought and never asks
 * whether the drought is deepening. Two runs on one afternoon, same target and same build, made
 * the point: one deserved its halt, and one sat at 14 of the same 20 cycles, recovered, and closed
 * at full score.
 *
 * <p>WHY IT MEASURES MERGES AND NOT ROOT PROGRESS. Root progress is zero in the stall window by
 * construction — that is what makes it a stall window — so a trend computed over it can only ever
 * say "zero, as before" and would be inert whatever the run was doing. Merges keep happening in a
 * healthy drought: work lands, closure does not follow yet. Over the thirteen recorded stalls in
 * the archive the two populations are disjoint, not close: six halted windows hold ZERO merges and
 * five of those six produced no scorable artifact at all, while seven hold between three and
 * fifteen and scored up to full marks.
 *
 * <p>WHY NOT A RATE RATIO, which is what this was first going to be. Requiring the window's merge
 * rate to stay within some fraction of the run's earlier rate does separate the same corpus — but
 * only with a threshold sited in a gap 0.09 wide: {@code 2026-08-28_03-51-21} merged at 0.14 of
 * its earlier rate and produced no artifact, while {@code 2026-08-29_23-08-29} merged at 0.23 of
 * its earlier rate and scored full marks. A constant tuned into that gap on thirteen runs is the
 * fixed constant this ticket exists to remove, wearing a ratio's clothes
 * ([[feedback-relative-thresholds]]).
 *
 * <p>WHAT DECIDES IT IS THE COST ASYMMETRY, not the cleaner boundary. Holding a run that is in
 * fact dead costs ONE more window; halting a run that is still working costs the rest of the run,
 * and the corpus shows that bill being paid — runs scoring 19, 21, 22 and 23 out of 23 were
 * stopped by this brake while merges were still landing. The one arguable member of the corpus
 * under this rule ({@code 03-51-21}, a single merge in its window, no artifact) is held for one
 * window and then halts if nothing further lands. That is the trade taken deliberately.
 */
public final class ConvergenceTrend {

    /** What the window says about the run, and the numbers behind it, for the halt log. */
    public record Assessment(boolean stillLandingWork, int windowMerges, int windowCycles,
                             int earlierMerges, int earlierCycles) {

        /** Merges per cycle in the window under judgement. */
        public double windowRate() {
            return windowCycles == 0 ? 0.0 : (double) windowMerges / windowCycles;
        }

        /** Merges per cycle across everything before the window — the run's own reference. */
        public double earlierRate() {
            return earlierCycles == 0 ? 0.0 : (double) earlierMerges / earlierCycles;
        }
    }

    private ConvergenceTrend() {}

    /**
     * Judges the last {@code window} cycles of {@code mergesPerCycle} against everything before
     * them.
     *
     * <p>A history shorter than the window is judged as nothing landing: a window that holds
     * fewer cycles than the drought it is supposed to describe is not a rate, and inventing one
     * from a handful of cycles is the false-trend error this ticket's own first diagnosis made.
     */
    public static Assessment assess(List<Integer> mergesPerCycle, int window) {
        if (mergesPerCycle == null || window <= 0 || mergesPerCycle.size() < window) {
            return new Assessment(false, 0, 0, 0, 0);
        }
        int size = mergesPerCycle.size();
        int windowMerges = 0;
        for (int i = size - window; i < size; i++) windowMerges += mergesPerCycle.get(i);
        int earlierMerges = 0;
        for (int i = 0; i < size - window; i++) earlierMerges += mergesPerCycle.get(i);
        return new Assessment(windowMerges > 0, windowMerges, window, earlierMerges, size - window);
    }
}
