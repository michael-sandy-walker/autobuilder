package com.walpro.autobuilder.protocol.meta;

import java.util.stream.Collectors;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;

public final class MetaControlPromptBuilder {

    private MetaControlPromptBuilder() {}

    public static String getUserPrompt(CycleContextData ctx) {
        String goal = safe(ctx.currentGoalDescription());
        String sub  = safe(ctx.currentSubGoalDescription());

        String remaining = formatGoals(ctx.remainingGoals());
        String completed = formatGoals(ctx.completedGoals());

        String diff = "(none)";
        if (ctx.projectDiff() != null && ctx.projectDiff().summary() != null && !ctx.projectDiff().summary().isBlank()) {
            diff = ctx.projectDiff().summary();
        }

        String score = ctx.evaluationOutcome() == null ? "(n/a)" : String.format("%.2f", ctx.evaluationOutcome().score());
        String last  = ctx.lastScore() == null ? "(n/a)" : String.format("%.2f", ctx.lastScore());

        String goalProgress = ctx.goalProgress() == null ? "(n/a)" : ctx.goalProgress().name();
        String cycleState   = ctx.cycleResultState() == null ? "(n/a)" : ctx.cycleResultState().name();

        return """
                Decide what Autobuilder should do next.
                Do NOT execute or test generated code. Reason ONLY over goal state, diff summary, and the presence of outcomes.

                --- GOALS ---
                Current Goal: %s
                Current SubGoal: %s

                Remaining Goals:
                %s

                Completed Goals:
                %s

                --- LAST DIFF SUMMARY ---
                %s

                --- STATE ---
                Current Score: %s
                Last Score: %s
                GoalProgressState: %s
                CycleResultState: %s

                --- OUTCOMES PRESENT? ---
                Has EvolutionOutcome: %s
                Has EvaluationOutcome: %s
                Has PlannerOutcome: %s
                Merged: %s

                --- RESPONSE FORMAT (STRICT) ---
                NextRole: PLANNER | EVOLUTION | EVALUATION | MERGE
                Stop: true | false
                Message: <one line>
                """.formatted(
                goal, sub,
                remaining, completed,
                diff,
                score, last,
                goalProgress, cycleState,
                ctx.evolutionOutcome() != null ? "true" : "false",
                ctx.evaluationOutcome() != null ? "true" : "false",
                ctx.plannerOutcome() != null ? "true" : "false",
                ctx.merged() ? "true" : "false"
        );
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }

    private static String formatGoals(java.util.List<? extends GoalEntity> goals) {
        if (goals == null || goals.isEmpty()) return "(none)";
        return goals.stream()
                .map(g -> "- " + g.getId() + ": " + g.getDescription())
                .collect(Collectors.joining("\n"));
    }
}
