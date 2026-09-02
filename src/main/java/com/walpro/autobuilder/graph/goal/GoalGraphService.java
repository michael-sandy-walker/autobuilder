package com.walpro.autobuilder.graph.goal;

import com.walpro.autobuilder.goal.GoalEntity;

import java.util.List;
import java.util.Optional;

public interface GoalGraphService {

    /**
     * Upserts goal node properties only.
     * Must NOT change any relationships/topology.
     */
    void save(GoalEntity goal);

    void setParent(String childId, String parentId);

    Optional<GoalEntity> findById(String id);

    List<GoalEntity> findSubgoals(String parentId);

    List<GoalEntity> findAll();

    void markCompleted(String id);

    /** T3-BP: marks completed and also records the resolution string. */
    default void markCompleted(String id, String resolution) {
        markCompleted(id);
    }

    /**
     * Optional legacy hook (kept for compatibility).
     * Domain promotion should live in GoalPlannerService, not in the graph service.
     */
    void promote(String id);

    /**
     * T1-FM: the T4-CJ origin-lineage stamp of a goal node, used by the resume gate's
     * ownership check. Empty when the node is absent or carries no stamp. Default empty so
     * non-Neo4j test doubles need no change.
     */
    default Optional<String> findLineageId(String goalId) {
        return Optional.empty();
    }

    /**
     * T4-DQ blocker 1: append one goal-status transition as history. A PROJECTION — the
     * authoritative log is {@code meta/goal-transitions.ndjson}, which the fold reads, so the
     * loop can still rewind with the database unavailable. Default no-op so non-Neo4j test
     * doubles need no change.
     */
    default void recordStatusTransition(com.walpro.autobuilder.goal.GoalTransition transition) {
        // no-op: the file log is authoritative
    }

    /**
     * T4-DQ: tombstone goals a rewind put back out of existence — they were planned after the
     * checkpoint, so as of the restored state they had not been thought of. Deliberately NOT a
     * delete: the graph is history, and "this was once planned and then rewound away" is worth
     * keeping. {@link #findAll()} excludes tombstoned nodes so a JVM restart does not reload
     * them into the live tree. Default no-op for non-Neo4j doubles.
     */
    default void markRewoundOut(java.util.List<String> goalIds, String cycleId) {
        // no-op
    }

    Optional<GoalGraphState> getState();

    void setState(String rootGoalId, String currentSubGoalId);

    /**
     * Deletes all goal nodes, goal relationships, and the SystemState pointer node(s).
     * Intended for system reset only.
     */
    void purgeAll();

    /**
     * Returns goal descriptions for similarity checks, excluding GIVEN_UP goals.
     * Capped at {@code limit} to stay bounded; 200 is a safe upper bound for typical goal graphs.
     */
    List<String> findAllDescriptions(int limit);

    /**
     * Returns goal descriptions filtered by a specific status string (e.g. "GIVEN_UP").
     * Used to count prior given-up attempts for a re-injection retry limit.
     */
    List<String> findDescriptionsByStatus(String status, int limit);
}
