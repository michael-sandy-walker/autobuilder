package com.walpro.autobuilder.protocol.scope;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.util.DiffSummarizer;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SubgoalScopePromptBuilder {

    private SubgoalScopePromptBuilder() {}

    public static String buildUserPrompt(CycleContextData ctx, List<GoalEntity> remainingGoals, List<GoalEntity> completedGoals) {
        String currentSubGoalId = safe(ctx.currentSubGoalId());
        String currentSubGoalDesc = safe(ctx.currentSubGoalDescription());

        String remainingBlock = formatGoals(remainingGoals);
        String completedBlock = formatGoals(completedGoals);
        String diffSummary = formatDiffSummary(ctx);
        // Scope classification is a name/path-level decision: the classifier matches changed
        // declarations against subgoal descriptions. Full file bodies added nothing but volume —
        // the previous raw ProjectDiffResult toString averaged ~9-10k tokens per call and
        // overflowed the model context (32k) on large cycles, truncating the prompt.
        String diffBlock = formatStructuralDiff(ctx);

        return """
--- CURRENT SUBGOAL ---
Id: %s
Text: %s

--- REMAINING SUBGOALS ---
%s

--- COMPLETED SUBGOALS ---
%s

--- DIFF SUMMARY ---
%s

--- STRUCTURAL DIFF (changed declarations) ---
%s

--- TASK ---
Classify whether the DIFF is in-scope for the CURRENT SUBGOAL.
If OUT_OF_SCOPE and a remaining subgoal matches better, set BestMatchingSubGoalId to that id; otherwise (none).
""".formatted(currentSubGoalId, currentSubGoalDesc, remainingBlock, completedBlock, diffSummary, diffBlock);
    }

    private static String formatStructuralDiff(CycleContextData ctx) {
        if (ctx == null || ctx.projectDiff() == null) return "NO_DIFF_OBJECT: true";
        try {
            return DiffSummarizer.summarizeForPrompt(ctx.projectDiff().structuredDiff());
        } catch (Exception e) {
            return "STRUCTURAL_DIFF_ERROR: " + e.getClass().getSimpleName();
        }
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

    private static String formatGoals(List<GoalEntity> goals) {
        if (goals == null || goals.isEmpty()) return "(none)";

        StringBuilder sb = new StringBuilder();
        for (GoalEntity g : goals) {
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
