package com.walpro.autobuilder.graph.goal;

import com.walpro.autobuilder.goal.GoalEntity;

import java.util.List;
import java.util.Optional;

public interface GoalGraphService {
    void save(GoalEntity goal);
    Optional<GoalEntity> findById(String id);
    List<GoalEntity> findSubgoals(String parentId);
    List<GoalEntity> findAll();
    void markCompleted(String id);
    void promote(String id); // optional, domain-specific
}
