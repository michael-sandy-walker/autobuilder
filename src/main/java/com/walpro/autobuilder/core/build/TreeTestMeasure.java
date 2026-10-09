package com.walpro.autobuilder.core.build;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * T6-T: how the operator's suite stood on a merged tree -- the measure a rewind target and a preserved copy are
 * chosen by on a target that declares its suite. Recorded with every merged checkpoint and snapshot; absent
 * (null) wherever the gate did not know.
 *
 * <p><b>Why it is recorded, not re-measured.</b> Field52 run 1 (2026-10-08_17-40-20) merged a change at 18:52
 * that took the suite from 6 failing to 4. At the halt a minute later the rewind went back to the 18:27 merge
 * (6 failing) because the newest untried point that landed work was the only rule, and the preserved copy was
 * the 18:27 tree because "the newest that compiles" was the only rule there. Both choices were made among
 * trees whose suite had already been run; nothing had kept the answer.
 *
 * @param testsCompile whether the suite compiled against the tree at all
 * @param failing      the failing tests when it compiled; the test files that did not compile when it did not
 */
public record TreeTestMeasure(boolean testsCompile, int failing) {

    /** Best first: a suite that compiles over one that does not, then fewer failing. */
    public static final Comparator<TreeTestMeasure> BEST_FIRST = Comparator
            .comparing((TreeTestMeasure m) -> !m.testsCompile())
            .thenComparingInt(TreeTestMeasure::failing);

    /** Whether this tree leaves more of the operator's suite failing than {@code other}. */
    public boolean worseThan(TreeTestMeasure other) {
        return other != null && BEST_FIRST.compare(this, other) > 0;
    }

    /**
     * Whether a point measured {@code candidate} may replace a tree measured {@code current}: always when the
     * current tree's measure is unknown (nothing to keep), never when the candidate's is unknown and the current
     * one is known (the choice would be blind), otherwise when it is no worse.
     */
    public static boolean admissible(TreeTestMeasure candidate, TreeTestMeasure current) {
        if (current == null) return true;
        return candidate != null && !candidate.worseThan(current);
    }

    /** The best measure among {@code measures}, ignoring the unknown ones; empty when none is known. */
    public static Optional<TreeTestMeasure> best(List<TreeTestMeasure> measures) {
        return measures.stream().filter(java.util.Objects::nonNull).min(BEST_FIRST);
    }

    public String describe() {
        return testsCompile ? failing + " test(s) failing" : failing + " test file(s) not compiling";
    }
}
