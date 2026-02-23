package com.walpro.autobuilder.analysis.scope;

public record ScopeAssessment(
        ScopeFit fit,
        double confidence,
        String message,
        String bestMatchingSubGoalId
) {
    public static ScopeAssessment inScope(double confidence, String message) {
        return new ScopeAssessment(ScopeFit.IN_SCOPE, confidence, message, null);
    }
}