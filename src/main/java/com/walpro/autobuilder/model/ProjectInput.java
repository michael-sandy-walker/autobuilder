package com.walpro.autobuilder.model;

import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.analysis.diff.DiffReport;

import java.io.IOException;
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

    public static ProjectInput forBootstrap(
    		String outputFolder,
            String initialGoal,
            DefaultProjectStructureSummarizer summarizer
    ) {
        String structureSummary;
        try {
            structureSummary = summarizer.summarizeProjectStructure(outputFolder);
        } catch (IOException e) {
            structureSummary = "[Error summarizing structure: " + e.getMessage() + "]";
        }

        return new ProjectInput(
                new DiffReport(List.of()),       // No previous state yet
                structureSummary,
                Map.of(),                        // No previous files
                outputFolder,
                initialGoal,                     // Root goal = seed
                null,                            // No subgoal
                BOOTSTRAP_ID,                     // Use a special ID
                null,
                List.of(),                       // No remaining
                List.of(),                       // No completed
                null,                            // No rejections
                null                             // No recommendations
        );
    }
}
