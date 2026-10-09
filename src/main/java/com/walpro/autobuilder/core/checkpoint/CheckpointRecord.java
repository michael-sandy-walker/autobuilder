package com.walpro.autobuilder.core.checkpoint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * T4-DQ: one point the loop can return to.
 *
 * <p>Most of a checkpoint is a set of REFERENCES rather than copies, because the expensive half
 * already runs: {@code snapshots/<cycleId>/} is a full copy of the output tree written every
 * cycle, and {@code mutations/<cycleId>/meta.json} is the entire serialised cycle context. What
 * was missing was never the volume — it was the decision-bearing in-memory state, and a cut point
 * in the goal history to fold back to.
 *
 * @param cycleId            the cycle this checkpoint was taken at the end of
 * @param at                 wall clock
 * @param runId              the run that took it
 * @param lineageId          the lineage it belongs to
 * @param rootGoalId         the root goal at the time
 * @param currentSubGoalId   the subgoal the loop was working on
 * @param goalTransitionSeq  the fold cut point in {@code meta/goal-transitions.ndjson}
 * @param snapshotPath       the output-tree copy this checkpoint restores from, or null when the
 *                           cycle wrote none
 * @param mutationMetaPath   the serialised cycle context, or null on a cycle with no evolution
 * @param merged             whether the cycle this point was taken at landed a merge — what makes
 *                           it a point where work was still landing, and so a candidate to return to
 * @param cacheCaptured      whether {@code cache/} was copied into the checkpoint (FAITHFUL needs it)
 * @param holders            captured {@link StateBucket#CAPTURE} state, keyed by state key
 * @param captureFailures    holders that refused to be captured — a checkpoint that says so is
 *                           safer than one that looks complete
 * @param fingerprint        the per-component digest of the state at this point, recomputed and
 *                           compared after a rewind so an uncaptured piece of state that MATTERS
 *                           announces itself at the restore instead of being discovered later
 * @param treeTests          T6-T: how the operator's suite stood on the tree at a merged point, or null
 *                           when it was not known (no declared suite, no run since the merge, not a merge)
 */
public record CheckpointRecord(
        String cycleId,
        Instant at,
        String runId,
        String lineageId,
        String rootGoalId,
        String currentSubGoalId,
        long goalTransitionSeq,
        String snapshotPath,
        String mutationMetaPath,
        boolean merged,
        boolean cacheCaptured,
        Map<String, Object> holders,
        List<String> captureFailures,
        StateFingerprint fingerprint,
        com.walpro.autobuilder.core.build.TreeTestMeasure treeTests) {

    /** A point with no test measure: written before T6-T, or rebuilt from the graph index, which keeps none. */
    public CheckpointRecord(String cycleId, Instant at, String runId, String lineageId, String rootGoalId,
                            String currentSubGoalId, long goalTransitionSeq, String snapshotPath,
                            String mutationMetaPath, boolean merged, boolean cacheCaptured,
                            Map<String, Object> holders, List<String> captureFailures,
                            StateFingerprint fingerprint) {
        this(cycleId, at, runId, lineageId, rootGoalId, currentSubGoalId, goalTransitionSeq, snapshotPath,
                mutationMetaPath, merged, cacheCaptured, holders, captureFailures, fingerprint, null);
    }

    /** A checkpoint is restorable only if it has a tree to restore. */
    public boolean restorable() {
        return snapshotPath != null && !snapshotPath.isBlank();
    }

    public boolean complete() {
        return captureFailures == null || captureFailures.isEmpty();
    }
}
