package com.walpro.autobuilder.core.merge;

import java.util.List;

/**
 * T4-BA: forward signal carried for exactly one cycle after the MergeAgent's interim
 * package-fragmentation guard rejects a mutation. Without it the evolution agent has no
 * record that its file placement collided with an existing type — at temperature 0.0 it
 * would re-propose the identical split next cycle. Mirrors {@link RolledBackReport}.
 *
 * @param cycleId   cycle whose mutation was rejected for fragmentation
 * @param conflicts one entry per type the rejected mutation tried to duplicate
 */
public record PackageFragmentationReport(
        String cycleId,
        List<Conflict> conflicts
) {
    /**
     * @param typeName      the simple type name that would be split across packages
     * @param existingPath  the source file already declaring it (relative to the output root)
     * @param attemptedPath the rejected file that would have declared it in another package
     */
    public record Conflict(String typeName, String existingPath, String attemptedPath) {}
}
