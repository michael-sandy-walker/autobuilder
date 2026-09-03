package com.walpro.autobuilder.graph.checkpoint;

import com.walpro.autobuilder.core.checkpoint.CheckpointRecord;

import java.util.List;
import java.util.Optional;

/**
 * T4-DQ: the cross-run index of points a lineage can be returned to.
 *
 * <p><b>Why the graph and not the filesystem.</b> Checkpoints on disk are run-scoped — reset and
 * resume both clear them, deliberately, because they reference per-cycle tree copies that would
 * otherwise accumulate without bound. That made the loop able to rewind only inside the run it was
 * in. But a LINEAGE spans runs (a T4-CD resume continues one), so "time travel within a lineage"
 * has always meant across runs; the filesystem was simply the wrong place to keep the index.
 *
 * <p>The graph already keeps everything else across runs — since T4-CJ/T1-D a reset severs the
 * lineage instead of purging, so 139 runs of history sit there tagged by {@code runId} and
 * {@code lineageId}. The goal-status transitions this ticket added in slice 1 are already
 * accumulating that way. Checkpoint records join them.
 *
 * <p>Distinct from {@code T4-CR}, which bundles a phase to move it between MACHINES. This is one
 * lineage on one machine, remembering further back than one run.
 */
public interface CheckpointGraphService {

    /** Record a checkpoint so it can be found again after the run that took it has ended. */
    default void record(CheckpointRecord record, String archivePath) {
        // no-op: without a graph the loop keeps its within-run checkpoints and nothing more
    }

    /** Every recorded point for a lineage, newest first. Empty without a graph. */
    default List<StoredCheckpoint> forLineage(String lineageId, int limit) {
        return List.of();
    }

    /** One recorded point by cycle id, from any run of any lineage. */
    default Optional<StoredCheckpoint> byCycleId(String cycleId) {
        return Optional.empty();
    }

    /** The runs that have recorded points, newest first, for the operator's run selector. */
    default List<StoredRun> runsWithCheckpoints(String lineageId, int limit) {
        return List.of();
    }

    /**
     * A checkpoint as the graph holds it: the record itself plus where its tree copy was archived.
     *
     * @param record      the checkpoint, rebuilt from the stored properties
     * @param archivePath the source-only tree copy this point restores from, or null when the
     *                    point was recorded without one
     */
    record StoredCheckpoint(CheckpointRecord record, String archivePath) {}

    /** A run that has points, and how many — what the operator's run selector is built from. */
    record StoredRun(String runId, String lineageId, int checkpoints, String firstCycleId,
                     String lastCycleId, java.time.Instant lastAt) {}
}
