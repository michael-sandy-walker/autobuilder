package com.walpro.autobuilder.goal;

import java.time.Instant;

/**
 * T4-DQ blocker 1: one appended fact about a goal's status.
 *
 * <p>Goal status used to be mutated in place — {@code SET g.status = 'COMPLETED'} with no record
 * of what it was before — so there was nothing to rewind to. This is the event whose fold
 * reconstructs the goal tree's status as of any earlier point.
 *
 * @param seq        monotonic within a lineage; the fold's cut point
 * @param cycleId    the cycle the change became durable in, or null before the first cycle
 * @param goalId     the goal
 * @param from       the previous status, or null for the first observation of this goal
 * @param to         the status after the change
 * @param kind       {@link Kind#CREATED} for a goal seen for the first time, {@link Kind#CHANGED}
 *                   for a status that moved
 * @param resolution the resolution string carried by a terminal transition, when there was one
 * @param at         wall clock
 */
public record GoalTransition(
        long seq,
        String cycleId,
        String goalId,
        String from,
        String to,
        Kind kind,
        String resolution,
        Instant at) {

    /**
     * Why the event was appended. Both kinds set a status in the fold; they differ in what they
     * say about EXISTENCE, which is what tells a rewind that a goal planned after the cut point
     * did not exist yet.
     */
    public enum Kind {
        /** First time this goal was observed — its creation, or its load at startup. */
        CREATED,
        /** The goal's status moved. */
        CHANGED,
        /** The status was set by a rewind rather than by the loop's own work. */
        REWOUND,
        /**
         * The goal was rewound out of existence — it was planned after the cut point, so as of
         * the restored state it had not been thought of yet. Recorded rather than deleted: the
         * history stays append-only, and the fold drops the goal from every later cut.
         */
        REMOVED
    }

    /**
     * T4-DQ follow-up: the status this transition moved AWAY from, which is the goal's status just
     * before it — the only record of what a goal was at a cut point it has no transition at or
     * before.
     */
    public GoalStatus fromStatus() {
        try {
            return from == null ? null : GoalStatus.valueOf(from);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public GoalStatus toStatus() {
        try {
            return to == null ? null : GoalStatus.valueOf(to);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
