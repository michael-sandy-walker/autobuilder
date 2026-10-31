package com.walpro.autobuilder.graph.input;

import com.walpro.autobuilder.goal.purpose.InputEntry;

import java.util.List;

/** T4-EC slice 1: where a project's inputs live -- the graph is their primary store, as it is for goals. */
public interface InputGraphService {

    /** Upserts one input; its lineage and creating run are stamped once, on create. */
    void save(InputEntry entry);

    /** {@code inputId} landed as {@code goalId}; it came from {@code sourceRootGoalId} when the graph holds that goal. */
    void link(String inputId, String goalId, String sourceRootGoalId);

    /** This lineage's inputs in arrival order. */
    List<InputEntry> findAll();
}
