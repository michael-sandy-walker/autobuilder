package com.walpro.autobuilder.graph.input;

import com.walpro.autobuilder.goal.purpose.InputEntry;

import java.util.List;

/** T4-EC slice 1: where a project's inputs live -- the graph is their primary store, as it is for goals. */
public interface InputGraphService {

    /** Upserts one input; its lineage and creating run are stamped once, on create. */
    void save(InputEntry entry);

    /** This lineage's inputs in arrival order. */
    List<InputEntry> findAll();
}
