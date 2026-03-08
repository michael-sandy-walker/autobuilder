package com.walpro.autobuilder.agent.analysis.model;

import java.util.ArrayList;
import java.util.List;

public record AnalysisOutcome(
        List<Finding> findings,
        List<String> hypotheses,
        List<String> proposedExecutableSubGoals,
        String message,
        /** Raw kind string from the analysis agent: COGNITIVE, EXECUTABLE, or HYBRID. Null if not emitted. */
        String goalKind
) {
    public static AnalysisOutcome empty(String message) {
        return new AnalysisOutcome(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), message, null);
    }

    public record Finding(
            String severity,   // INFO | WARNING | CRITICAL
            String type,       // e.g. "MODE_MISMATCH_LOOP"
            String evidence,   // short evidence string
            String path        // optional file/path
    ) {}
}
