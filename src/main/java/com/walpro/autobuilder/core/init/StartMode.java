package com.walpro.autobuilder.core.init;

/**
 * How a run begins — the one setting that used to be an accident of five flags.
 *
 * <p><b>Why this exists (T4-DP).</b> Three ways to start already existed and none was named: which
 * one you got was the product of {@code bootstrap.enabled}, {@code service.reset-on-start},
 * {@code service.resume-from-preserved}, the target path, and whether a seed file happened to be
 * present. On 2026-09-03 that nearly destroyed a project: the documented way to start a run
 * defaults reset-on-start to true, reset deletes {@code output/}, and the target was a codebase the
 * loop had not written. It had to be launched by hand with a flag combination nothing named and
 * nothing validated.
 *
 * <p>Naming the modes is only half of it. The half that protects anything is that a mode can now be
 * checked against what is actually in the tree, so {@link #NEW_PROJECT} cannot quietly delete work
 * the loop did not author. See {@link StartModeService}.
 */
public enum StartMode {

    /**
     * Begin a project from a specification: clear the tree and seed the root goal from
     * {@code bootstrap/seed.txt}. The only destructive mode, and the reason the checks exist.
     */
    NEW_PROJECT,

    /**
     * Carry on a lineage this loop built before, restoring the preserved artifact and its goal
     * state. Never resets — the reset would delete the very artifact the resume restores.
     */
    CONTINUE_LINEAGE,

    /**
     * Work on a codebase the loop did not write. Never resets and never seeds over what is there.
     *
     * <p>A tree adopted this way has no goal yet, and inventing one is the thing {@code T4-CQ}
     * warns against, so the loop holds rather than cycling on a goal nobody set. Read the tree with
     * {@code GET /autobuilder/cold-read} and give it a goal deliberately.
     */
    ADOPT_PROJECT,

    /**
     * Touch nothing and wait to be told which of the above applies.
     *
     * <p>The resting state, not a pause on the way to a default. Nothing here times out into an
     * action: a timeout that falls through is how a chooser becomes a destructive default with
     * extra steps. An unattended box that got this mode by accident sits idle, which is always
     * recoverable.
     */
    ASK;

    /** True when this mode may clear a tree, which is the only case worth guarding. */
    public boolean discardsTheTree() {
        return this == NEW_PROJECT;
    }
}
