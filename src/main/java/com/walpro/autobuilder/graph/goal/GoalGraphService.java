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

    /**
     * Optional legacy hook (kept for compatibility).
     * Domain promotion should live in GoalPlannerService, not in the graph service.
     */
    void promote(String id);

    Optional<GoalGraphState> getState();

    void setState(String rootGoalId, String currentSubGoalId);

    /**
     * Deletes all goal nodes, goal relationships, and the SystemState pointer node(s).
     * Intended for system reset only.
     */
    void purgeAll();

    /**
     * Returns all goal descriptions for similarity checks (no text filter, all statuses).
     * Capped at {@code limit} to stay bounded; 200 is a safe upper bound for typical goal graphs.
     */
    List<String> findAllDescriptions(int limit);
}
