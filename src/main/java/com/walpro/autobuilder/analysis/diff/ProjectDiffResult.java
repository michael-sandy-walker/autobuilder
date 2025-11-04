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
    public String summary() {
        return structuredDiff != null
                ? DiffSummarizer.summarize(structuredDiff)
                : "(no structural diff)";
    }
}
