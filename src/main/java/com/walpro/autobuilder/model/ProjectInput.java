package com.walpro.autobuilder.model;

import com.walpro.autobuilder.analysis.diff.DiffReport;

import java.util.List;
import java.util.Map;

public record ProjectInput(
        DiffReport diff,
        String structureSummary,
        Map<String, String> previousOutput,
        String outputFolder,
        String currentGoalDescription,
        String currentSubGoalDescription,
        String currentGoalId,
        String currentSubGoalId,
        List<String> remainingGoals,
        List<String> completedGoals,
        String lastRejectionFeedback,
        String recommendation
) {
    public static final String BOOTSTRAP_ID = "bootstrap";

}
