package com.walpro.autobuilder.model;

import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.protocol.ProtocolContract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ProjectInput(
    DiffReport diff,
    String structureSummary,
    Map<String, String> previousOutput,
    Path outputPath,
    String currentGoal,
    List<String> remainingGoals,
    List<String> completedGoals
) {
    public static ProjectInput empty(Path outputPath, String goal) {
        return new ProjectInput(
            ProtocolContract.emptyDiffReport(),
            "", // empty structure summary
            Map.of(), // no previous output
            outputPath,
            goal != null && !goal.isBlank() ? goal : null,
            List.of(), // no remaining goals
            List.of()  // no completed goals
        );
    }
}
