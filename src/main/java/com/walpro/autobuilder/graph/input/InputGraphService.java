package com.walpro.autobuilder.graph.input;

import com.walpro.autobuilder.goal.purpose.InputEntry;
import com.walpro.autobuilder.goal.purpose.InputEvent;

import java.util.List;

/** T4-EC slice 1: where a project's inputs live -- the graph is their primary store, as it is for goals. */
public interface InputGraphService {

    /** Upserts one input; its lineage and creating run are stamped once, on create. */
    void save(InputEntry entry);

    /** {@code inputId} landed as {@code goalId}; it came from {@code sourceRootGoalId} when the graph holds that goal. */
    void link(String inputId, String goalId, String sourceRootGoalId);

    /**
     * T4-EG: {@code inputId} brought {@code goalId} -- added it ({@code how} = {@code added}) or was unified with it
     * ({@code text}, {@code criteria}, {@code model}: what judged them the same). Provenance only: nothing in the loop
     * reads it; "what did this import bring" is a query.
     */
    void linkBrought(String inputId, String goalId, String how);

    /** Upserts one event: a conflict's decision replaces its open state. */
    void saveEvent(InputEvent event, String lineageId);

    /** This lineage's events in order. */
    List<InputEvent> findEvents();

    /** This lineage's inputs in arrival order. */
    List<InputEntry> findAll();
}
