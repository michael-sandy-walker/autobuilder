package com.walpro.autobuilder.agent.analysis.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record AnalysisOutcome(
        List<Finding> findings,
        List<String> hypotheses,
        List<String> proposedExecutableSubGoals,
        /**
         * Optional acceptance criteria per proposed subgoal description.
         * Keyed by the subgoal description string (same as in {@code proposedExecutableSubGoals}).
         * Null when no criteria were provided.
         */
        Map<String, List<String>> goalCriteria,
        String message,
        /** Raw kind string from the analysis agent: COGNITIVE, EXECUTABLE, or HYBRID. Null if not emitted. */
        String goalKind,
        /** T2-D: true when the agent believes the current goal may already be fully implemented. */
        boolean possiblyComplete
) {
    public static AnalysisOutcome empty(String message) {
        return new AnalysisOutcome(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null, message, null, false);
    }

    public record Finding(
            String severity,   // INFO | WARNING | CRITICAL
            String type,       // e.g. "MODE_MISMATCH_LOOP"
            String evidence,   // short evidence string
            String path        // optional file/path
    ) {}
}
