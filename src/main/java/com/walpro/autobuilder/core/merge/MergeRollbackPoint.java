package com.walpro.autobuilder.core.merge;

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
    public boolean isEmpty() {
        return originalContents == null || originalContents.isEmpty();
    }
}
