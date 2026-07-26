package com.walpro.autobuilder.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;

/**
 * T4-CJ slice 2: a completion decision must rest on the CURRENT run's evidence.
 *
 * <p>The orchestration graph accumulates across every run of a lineage (measured 2026-07-26:
 * 1374 Action nodes over 4 runIds in one graph, one goal tree of 132 nodes), and the snapshot's
 * anchor query is ordered by timestamp with no run predicate. So on a resumed run whose current
 * subgoal has not been evolved yet, the newest attempt for that goal belongs to the PREVIOUS run —
 * and its score could promote a subgoal to FULFILLED in this one. These pin the guard that stops it.
 */
class RunScopedEvidenceTest {

    private static OrchestrationSnapshot snapshotFromRun(String runId) {
        return OrchestrationSnapshot.fromRow("goal-1", "sub-1",
                Map.of("attemptId", "attempt-1", "attemptRunId", runId));
    }

    @Test
    void evidenceFromThisRunIsAccepted() {
        assertThat(AutobuilderService.isThisRunsEvidence(snapshotFromRun("run-A"), "run-A")).isTrue();
    }

    @Test
    void evidenceFromAnEarlierRunOfTheSameLineageIsRejected() {
        // The observed shape: runs 2-4 of one lineage share a goal tree, so the newest attempt
        // for a carried-over subgoal can predate this run entirely.
        assertThat(AutobuilderService.isThisRunsEvidence(snapshotFromRun("run-B"), "run-A")).isFalse();
    }

    @Test
    void unattributableEvidenceFailsClosed() {
        // A row written before the stamp existed cannot be shown to be this run's, and
        // "cannot be shown to be ours" must not read as "is ours".
        OrchestrationSnapshot unstamped = OrchestrationSnapshot.fromRow("goal-1", "sub-1",
                Map.of("attemptId", "attempt-1"));
        assertThat(AutobuilderService.isThisRunsEvidence(unstamped, "run-A")).isFalse();
    }

    @Test
    void missingSnapshotIsRejected() {
        assertThat(AutobuilderService.isThisRunsEvidence(null, "run-A")).isFalse();
    }

    @Test
    void unknownCurrentRunFailsClosed() {
        assertThat(AutobuilderService.isThisRunsEvidence(snapshotFromRun("run-A"), null)).isFalse();
    }

    @Test
    void emptySnapshotCarriesNoEvidence() {
        assertThat(AutobuilderService.isThisRunsEvidence(
                OrchestrationSnapshot.empty("goal-1", "sub-1", "attempt-1"), "run-A")).isFalse();
    }
}
