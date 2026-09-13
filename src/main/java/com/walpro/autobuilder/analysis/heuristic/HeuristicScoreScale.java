package com.walpro.autobuilder.analysis.heuristic;

/**
 * The origin of the deterministic mutation-score scale.
 *
 * <p>{@link #NEUTRAL} is where a mutation starts before any heuristic has anything to say about
 * it. Findings move it up or down; a mutation that triggers none — or whose findings cancel —
 * lands back here. It therefore means <em>no net evidence</em>, which is a statement about the
 * scorer, not about the mutation.
 *
 * <p><b>It is deliberately NOT an acceptance threshold.</b> Until T2-FJ's 2026-09-13 audit this
 * same value was also {@code DeterministicScoreHeuristic}'s acceptance cut, so the scale's origin
 * and the cut on that scale were one number. A mutation nobody had found anything about scored
 * exactly the cut, cleared it on the accepting side, and was accepted at full confidence: over the
 * archive, 140 of 140 such mutations were ACCEPTED. The comment on the old baseline read "neutral
 * baseline instead of 'perfect by default'"; sharing the number with the cut made it accept by
 * default instead.
 *
 * <p>Whoever changes this value: it translates the whole score distribution, so scores recorded
 * before the change are not comparable with scores after it. The acceptance cut is a separate
 * knob ({@code autobuilder.tuning.score.merge-acceptance-floor}) and moves independently.
 */
public final class HeuristicScoreScale {

    /** Where a mutation scores when the deterministic heuristics have no net opinion about it. */
    public static final double NEUTRAL = 0.75;

    /**
     * Tolerance for "this score is the neutral baseline". The score reaching the heuristic is a
     * blend of doubles, so it is compared rather than equated; in the archive every net-neutral
     * score landed on {@link #NEUTRAL} to well within this.
     */
    public static final double NEUTRAL_EPSILON = 1e-9;

    /** True when {@code score} carries no net evidence — it sits on the scale's origin. */
    public static boolean isNeutral(double score) {
        return Math.abs(score - NEUTRAL) < NEUTRAL_EPSILON;
    }

    private HeuristicScoreScale() {
    }
}
