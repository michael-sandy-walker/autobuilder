package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;
import java.util.Set;

/**
 * T1-DW: snapshot of filesystem state captured immediately before a set of paths is written
 * during a merge. Used to restore the pre-merge state when a post-merge build gate fails.
 */
public record MergeRollbackPoint(
        String cycleId,
        Map<String, String> originalContents,
        Set<String> newlyAddedPaths
) {
    // T1-ED: @JsonIgnore — Jackson otherwise treats isXxx() as a JavaBean getter and serialises
    // "empty" as a 4th JSON property, which fails to deserialise back into this 3-component
    // record (UnrecognizedPropertyException). Reached via CycleContextData.lastRollbackPoint
    // when meta.json is persisted.
    @JsonIgnore
    public boolean isEmpty() {
        return originalContents == null || originalContents.isEmpty();
    }
}
