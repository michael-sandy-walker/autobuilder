package com.walpro.autobuilder.core.checkpoint;

import java.time.Instant;

/**
 * T4-DQ slice 4: one path explored forward from a checkpoint.
 *
 * <p>A branch is a CYCLE RANGE, not a stamp on anything. Cycle ids are monotonic and a branch
 * occupies a contiguous stretch of them, so membership is already recorded by every checkpoint,
 * snapshot and behaviour event the loop writes — nothing needed a new identity column, and
 * deliberately so: {@code T4-CJ}'s lineage id answers "which evolutionary line is this target on"
 * across runs, and overloading it with within-run exploration would multiply lineages and break
 * the {@code T1-FM} resume ownership check that expects one per target.
 *
 * <p>Open while the loop is working it ({@code endedAtCycleId} null), sealed when the loop leaves
 * it — at which point its outcome is measured once and never re-derived.
 *
 * @param id                a short branch identifier, unique within the run
 * @param label             what this branch is, in words, for the trace
 * @param forkedFromCycleId the checkpoint it started from; null for the run's original path
 * @param startedAtCycleId  the first cycle worked on this branch
 * @param endedAtCycleId    the last cycle worked on it, or null while it is the live branch
 * @param outcome           what the branch achieved, measured at seal time; null while open
 * @param openedAt          wall clock
 */
public record ExplorationBranch(
        String id,
        String label,
        String forkedFromCycleId,
        String startedAtCycleId,
        String endedAtCycleId,
        BranchOutcome outcome,
        Instant openedAt) {

    /** The branch the run starts on, before any fork. */
    public static final String MAIN = "main";

    public boolean open() {
        return endedAtCycleId == null;
    }

    public ExplorationBranch sealedAt(String cycleId, BranchOutcome measured) {
        return new ExplorationBranch(id, label, forkedFromCycleId, startedAtCycleId, cycleId,
                measured, openedAt);
    }

    /**
     * What a branch achieved. Every field is read from state the loop already writes — checkpoints
     * in the branch's cycle range, and the goal tree at the moment the branch is sealed.
     *
     * @param cycles                     checkpoints taken while this branch was live
     * @param merges                     how many of those cycles landed a merge
     * @param subgoalsCompletedWithMerge the root-progress measure the convergence brake uses: the
     *                                   one thing the planner cannot inflate by re-decomposing
     * @param tipCycleId                 the branch's last checkpoint, which is what adopting it
     *                                   returns to
     * @param tipBuildResult             the carried build outcome at the tip, or null
     * @param tipEntryPointPresent       whether the tip's tree has an entry point at all
     */
    public record BranchOutcome(int cycles, int merges, int subgoalsCompletedWithMerge,
                                String tipCycleId, String tipBuildResult,
                                boolean tipEntryPointPresent) {

        public static final BranchOutcome NONE = new BranchOutcome(0, 0, 0, null, null, false);

        /** Merges per cycle — reported, never used as the ranking key (see BranchExplorer). */
        public double mergeRate() {
            return cycles == 0 ? 0.0 : (double) merges / cycles;
        }

        public boolean tipCompiles() {
            return "COMPILE_OK".equals(tipBuildResult);
        }
    }
}
