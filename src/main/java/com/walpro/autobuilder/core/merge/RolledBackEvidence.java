package com.walpro.autobuilder.core.merge;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;

import java.util.List;

/**
 * T1-DX: forward signal carried for exactly one cycle after a {@link MergeRollbackService}
 * rollback so the next cycle's planner and evolution prompts see what happened. Without this,
 * the rollback restores {@code buildOutcome} to the previous COMPILE_OK and the next cycle
 * has no record that the previous mutation failed — the LLM proposes the same broken pattern.
 *
 * @param cycleId             cycle that was rolled back
 * @param restoredPaths       paths whose pre-merge content was restored (or deleted, if newly added)
 * @param stderrSummary       short summary of the compile error that triggered rollback
 * @param errorKind           classifier output, surfaced verbatim in prompts and behaviour log
 */
public record RolledBackEvidence(
        String cycleId,
        List<String> restoredPaths,
        String stderrSummary,
        BuildErrorClassifier.Kind errorKind
) {}
