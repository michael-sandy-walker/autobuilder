package com.walpro.autobuilder.analysis.scope;

public record ScopeAssessment(
        ScopeFit fit,
        double confidence,
        String message,
        String bestMatchingSubGoalId
) {
}