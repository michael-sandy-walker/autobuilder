package com.walpro.autobuilder.protocol.meta;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

public final class MetaDeliberationPromptBuilder {

    private MetaDeliberationPromptBuilder() {}

    public static String getUserPrompt(CycleContextData ctx) {
        return getUserPrompt(ctx, "(not provided)");
    }

    public static String getUserPrompt(CycleContextData ctx, String eligibilityBlock) {
        return getUserPrompt(ctx, eligibilityBlock, "(not provided)");
    }

    public static String getUserPrompt(CycleContextData ctx, String eligibilityBlock, String metaLearningBlock) {
        return getUserPrompt(ctx, eligibilityBlock, metaLearningBlock, "(not provided)");
    }

    /**
     * Preferred overload: caller provides a compact graph-derived ledger block.
     * This avoids misleading per-cycle runtime booleans (outcomes reset each cycle).
     */
    public static String getUserPrompt(
            CycleContextData ctx,
            String eligibilityBlock,
            String metaLearningBlock,
            String orchestrationLedgerBlock
    ) {
        String diff = "(none)";
        if (ctx.projectDiff() != null && ctx.projectDiff().summary() != null && !ctx.projectDiff().summary().isBlank()) {
            diff = ctx.projectDiff().summary();
        }

        String goalProg = ctx.goalProgress() == null ? "(n/a)" : ctx.goalProgress().name();
        String mutState = ctx.mutationState() == null ? "(n/a)" : ctx.mutationState().name();
        String cycleRes = ctx.cycleResultState() == null ? "(n/a)" : ctx.cycleResultState().name();

        boolean stagnating = ctx.goalProgress() == GoalProgressState.STAGNATING;
        boolean rejected   = ctx.mutationState() == MutationAcceptanceState.REJECTED;

        int remaining = ctx.remainingGoals() == null ? 0 : ctx.remainingGoals().size();
        int completed = ctx.completedGoals() == null ? 0 : ctx.completedGoals().size();

        String lastAttemptId = ctx.lastAttemptId() == null ? "(none)" : ctx.lastAttemptId();

        return """
            You are deciding the next meta-steps for Autobuilder.
            Do NOT execute generated code. Do NOT propose code. Reason only about orchestration.

            --- EXECUTION ELIGIBILITY (truth from canExecute) ---
            %s

            --- GOAL STATE ---
            Current Goal: %s
            Current SubGoal: %s
            RemainingGoalsCount: %d
            CompletedGoalsCount: %d

            --- ORCHESTRATION LEDGER (graph-derived) ---
            LastAttemptId: %s
            %s

            --- CURRENT PATTERNS ---
            GoalProgressState: %s
            MutationAcceptanceState: %s
            CycleResultState: %s
            Stagnating: %s
            Rejected: %s

            --- DIFF SUMMARY ---
            %s
            
            --- DELIBERATION MODE ---
            Mode: EVALUATE
            AllowedRoles: [evaluation]
            
            --- META LEARNINGS (recent skip patterns) ---
            %s

            --- TASK ---
            Output an ORDERED PLAN of 1..5 Steps that can be executed within the SAME cycle if needed.
            Each step must target exactly one role: PLANNER, EVOLUTION, EVALUATION, MERGE.
            Each step must include:
              Need: NONE | MISSING_INFO | REDO_LAST | UNSTICK | NEXT_STEP
              Request: one concrete line
              StopIf: one concrete condition describing when to stop early

            Prefer the smallest plan that resolves missing outcomes or makes progress.
            """.formatted(
                eligibilityBlock,
                nullToNone(ctx.currentGoalDescription()),
                nullToNone(ctx.currentSubGoalDescription()),
                remaining,
                completed,
                lastAttemptId,
                safeBlock(orchestrationLedgerBlock),
                goalProg,
                mutState,
                cycleRes,
                stagnating,
                rejected,
                diff,
                metaLearningBlock
        );
    }

    private static String nullToNone(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }

    private static String safeBlock(String block) {
        return (block == null || block.isBlank()) ? "(none)" : block;
    }
}
