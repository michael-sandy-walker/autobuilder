package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.List;

/**
 * T4-BX: renders the {@code --- WHY THE PREVIOUS ATTEMPT WAS REJECTED ---} block from the authored
 * reasons the evaluator and heuristics recorded on the previous cycle ({@code rejectionFeedback}).
 * This relays one agent's reasoning to the next so it knows what NOT to repeat — the reasons are
 * the original authored sentences, shown to the LLM verbatim.
 *
 * <p>Returns {@code null} when no reasons are carried (single-cycle lifecycle enforced by
 * {@link com.walpro.autobuilder.core.lifecycle.ContextLineageTracker}, cleared on merge). Shared by
 * the evolution and planner contracts.
 */
public final class RejectionFeedbackPromptBlock {

    private RejectionFeedbackPromptBlock() {}

    public static String render(CycleContextData ctx) {
        return render(ctx, null);
    }

    /**
     * @param alreadyShown a reason line the caller renders elsewhere in the same prompt (the planner
     *                     shows the evaluator feedback in its own EVALUATION FEEDBACK block) — skipped
     *                     here to avoid duplication. Pass {@code null} to render every reason.
     */
    public static String render(CycleContextData ctx, String alreadyShown) {
        if (ctx == null) return null;
        List<String> reasons = ctx.rejectionFeedback();
        if (reasons == null || reasons.isEmpty()) return null;

        String shown = alreadyShown != null ? alreadyShown.trim() : null;
        List<String> lines = new java.util.ArrayList<>();
        for (String reason : reasons) {
            if (reason == null || reason.isBlank()) continue;
            String trimmed = reason.trim();
            if (shown != null && trimmed.equals(shown)) continue;
            lines.add(trimmed);
        }
        if (lines.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- WHY THE PREVIOUS ATTEMPT WAS REJECTED ---\n");
        sb.append("Your last mutation was NOT merged. The evaluation raised:\n");
        for (String line : lines) {
            sb.append("  - ").append(line).append("\n");
        }
        sb.append("Address these concerns this cycle. Do not resubmit an attempt with the same problems.");
        return sb.toString();
    }
}
