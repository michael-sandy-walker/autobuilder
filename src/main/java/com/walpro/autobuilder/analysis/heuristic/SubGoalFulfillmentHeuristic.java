package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class SubGoalFulfillmentHeuristic {

    private final GoalSimilarityService similarityService;

    // Conservative default – tune later
    private static final double FULFILLMENT_THRESHOLD = 0.75;

    public SubGoalFulfillmentHeuristic(GoalSimilarityService similarityService) {
        this.similarityService = similarityService;
    }

    public FulfillmentDecision decide(CycleContextData ctx) {
        if (ctx == null) {
            return FulfillmentDecision.no("no context");
        }

        String subGoalText = ctx.currentSubGoalDescription();
        if (subGoalText == null || subGoalText.isBlank()) {
            return FulfillmentDecision.no("no subgoal description");
        }

        // Hard gates: do NOT even consider fulfillment otherwise
        if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
            return FulfillmentDecision.no("mutation not accepted");
        }

        if (ctx.goalProgress() != GoalProgressState.PROGRESSING) {
            return FulfillmentDecision.no("goal not progressing");
        }

        ProjectDiffResult diff = ctx.projectDiff();
        if (diff == null || diff.structuredDiff() == null) {
            return FulfillmentDecision.no("no structural diff");
        }

        String diffSummary = diff.summary();
        if (diffSummary == null || diffSummary.isBlank()) {
            return FulfillmentDecision.no("empty diff summary");
        }

        // Semantic alignment: what changed vs what we wanted
        double similarity = similarityService.similarity(subGoalText, diffSummary);

        // Optional: incorporate evaluation score if available
        double score = extractScore(ctx);
        double confidence = blend(similarity, score);

        String rationale =
                "similarity=" + round(similarity) +
                        ", score=" + round(score);

        if (confidence >= FULFILLMENT_THRESHOLD) {
            return FulfillmentDecision.yes(confidence, rationale);
        }

        return FulfillmentDecision.no(confidence, rationale);
    }

    private double extractScore(CycleContextData ctx) {
        if (ctx.evaluationOutcome() == null) return 0.5;
        Double score = ctx.evaluationOutcome().score();
        return score != null ? clamp(score) : 0.5;
    }

    private static double blend(double similarity, double score) {
        // Intent alignment slightly outweighs evaluator optimism
        return 0.6 * similarity + 0.4 * score;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String round(double v) {
        return String.format("%.3f", v);
    }

    /* ---------- result ---------- */

    public record FulfillmentDecision(
            boolean fulfilled,
            double confidence,
            String rationale
    ) {
        public static FulfillmentDecision yes(double c, String r) {
            return new FulfillmentDecision(true, c, r);
        }

        public static FulfillmentDecision no(String r) {
            return new FulfillmentDecision(false, 0.0, r);
        }

        public static FulfillmentDecision no(double c, String r) {
            return new FulfillmentDecision(false, c, r);
        }
    }
}
