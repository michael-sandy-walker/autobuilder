package com.walpro.autobuilder.protocol.scope;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SubgoalScopePromptBuilder {

    private SubgoalScopePromptBuilder() {}

    public static String buildUserPrompt(CycleContextData ctx, List<GoalEntity> remainingGoals) {
        String currentSubGoalId = safe(ctx.currentSubGoalId());
        String currentSubGoalDesc = safe(ctx.currentSubGoalDescription());

        String remainingBlock = formatRemainingGoals(remainingGoals);
        String diffSummary = formatDiffSummary(ctx);
        String diffBlock = String.valueOf(ctx.projectDiff()); // keep for forensic detail

        return """
--- CURRENT SUBGOAL ---
Id: %s
Text: %s

--- REMAINING SUBGOALS ---
%s

--- DIFF SUMMARY ---
%s

--- DIFF (stringified ProjectDiffResult) ---
%s

--- TASK ---
Classify whether the DIFF is in-scope for the CURRENT SUBGOAL.
If OUT_OF_SCOPE and a remaining subgoal matches better, set BestMatchingSubGoalId to that id; otherwise (none).
""".formatted(currentSubGoalId, currentSubGoalDesc, remainingBlock, diffSummary, diffBlock);
    }

    private static String formatDiffSummary(CycleContextData ctx) {
        if (ctx == null) return "NO_CONTEXT: true";

        ProjectDiffResult diff = ctx.projectDiff();
        if (diff == null) return "NO_DIFF_OBJECT: true";

        try {
            Map<String, String> changedFiles = diff.changedFiles();
            Set<String> deletedPaths = diff.deletedPaths();

            int changedCount = (changedFiles == null) ? 0 : changedFiles.size();
            int deletedCount = (deletedPaths == null) ? 0 : deletedPaths.size();
            boolean noop = (changedCount == 0 && deletedCount == 0);

            String changedKeys = (changedFiles == null || changedFiles.isEmpty())
                    ? "(none)"
                    : String.join(", ", changedFiles.keySet());

            String deletedKeys = (deletedPaths == null || deletedPaths.isEmpty())
                    ? "(none)"
                    : String.join(", ", deletedPaths);

            String structural = safe(diff.summary());

            return """
NO_OP: %s
ChangedFilesCount: %d
DeletedPathsCount: %d
ChangedFiles: %s
DeletedPaths: %s
StructuralSummary: %s
""".formatted(noop, changedCount, deletedCount, changedKeys, deletedKeys, structural).trim();

        } catch (Exception e) {
            // Don’t fail prompt building; just provide a safe hint.
            return "DIFF_SUMMARY_ERROR: " + e.getClass().getSimpleName();
        }
    }

    private static String formatRemainingGoals(List<GoalEntity> remainingGoals) {
        if (remainingGoals == null || remainingGoals.isEmpty()) return "(none)";

        StringBuilder sb = new StringBuilder();
        for (GoalEntity g : remainingGoals) {
            if (g == null) continue;
            String id = safe(g.getId());
            String desc = safe(g.getDescription());
            if (id.isBlank() && desc.isBlank()) continue;
            sb.append("- ").append(id).append(": ").append(desc).append("\n");
        }
        String s = sb.toString().trim();
        return s.isBlank() ? "(none)" : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
