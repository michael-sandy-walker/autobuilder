package com.walpro.autobuilder.analysis.diff;

import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.analysis.diff.util.DiffSummarizer;

public record ProjectDiffResult(
    StructuralDiffNode structuredDiff,
    Map<String, String> changedFiles,
    Set<String> deletedPaths
) {
    /**
     * The candidate tree as the evaluated mutation would leave it: {@code previous} with this
     * cycle's changed files written over it and its deleted paths removed.
     *
     * <p>One home for the overlay every structural heuristic builds. Before it existed each
     * heuristic wrote {@code previous + changedFiles} by hand and none subtracted the deletions,
     * so a mutation that removed one of two parallel stores was judged with both still present
     * and rejected for the defect it had just fixed (field run 2026-09-14, the rewound
     * {@code 2026-09-13_09-44-03} cut point).
     */
    public Map<String, String> overlay(Map<String, String> previous) {
        Map<String, String> current = new java.util.LinkedHashMap<>(previous == null ? Map.of() : previous);
        if (changedFiles != null) current.putAll(changedFiles);
        if (deletedPaths != null) deletedPaths.forEach(current::remove);
        return current;
    }

    public String summary() {
        return structuredDiff != null
                ? DiffSummarizer.summarize(structuredDiff)
                : "(no structural diff)";
    }
}
