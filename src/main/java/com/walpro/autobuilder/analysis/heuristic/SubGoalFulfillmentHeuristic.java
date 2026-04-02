package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class SubGoalFulfillmentHeuristic {

    // Tune later
    private static final double MIN_SCORE_FOR_CLOSURE = 0.75;

    /**
     * T1-AQ: plateau-override escape. When the score has been flat for this many consecutive
     * accepted cycles AND it is above MIN_SCORE_FOR_PLATEAU_OVERRIDE, accept the subgoal as
     * fulfilled rather than spinning indefinitely below the normal threshold.
     */
    private static final int PLATEAU_OVERRIDE_CYCLES = 10;
    private static final double MIN_SCORE_FOR_PLATEAU_OVERRIDE = 0.65;

    public FulfillmentDecision decide(CycleContextData ctx) {
        if (ctx == null) return FulfillmentDecision.no("no context");

        if (ctx.currentSubGoalId() == null || ctx.currentSubGoalId().isBlank()) {
            return FulfillmentDecision.no("no current subgoal id");
        }

        if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
            return FulfillmentDecision.no("mutation not accepted");
        }

        GoalProgressState gp = ctx.goalProgress();
        if (gp == null) {
            return FulfillmentDecision.no("no goal progress");
        }

        // If interpreter says fulfilled, close immediately.
        if (gp == GoalProgressState.FULFILLED) {
            return FulfillmentDecision.yes(1.0, "interpreter=FULFILLED");
        }

        // Otherwise: only allow closure on strong progressing + meaningful change + good score.
        if (gp != GoalProgressState.PROGRESSING) {
            return FulfillmentDecision.no("not progressing");
        }

        if (!hasMeaningfulChange(ctx.projectDiff())) {
            return FulfillmentDecision.no("no meaningful diff");
        }

        double score = extractScore(ctx);
        if (score < MIN_SCORE_FOR_CLOSURE) {
            // T1-AQ: plateau escape — if the score has been flat for many cycles and clears the
            // lower bar, accept as fulfilled to avoid spinning below threshold indefinitely.
            if (ctx.plateauCycles() >= PLATEAU_OVERRIDE_CYCLES && score >= MIN_SCORE_FOR_PLATEAU_OVERRIDE) {
                return FulfillmentDecision.yes(score,
                        "plateau-override after " + ctx.plateauCycles() + " flat cycles (score=" + score + ")");
            }
            return FulfillmentDecision.no(score, "score below closure threshold");
        }

        return FulfillmentDecision.yes(score, "score-based closure + meaningful diff");
    }

    private static boolean hasMeaningfulChange(ProjectDiffResult diff) {
        if (diff == null || diff.structuredDiff() == null) return false;

        // simplest possible: if there are any changed files or deleted paths, treat as meaningful
        if (diff.changedFiles() != null && !diff.changedFiles().isEmpty()) return true;
        if (diff.deletedPaths() != null && !diff.deletedPaths().isEmpty()) return true;

        // fallback: treat "(no changes)" as not meaningful
        String s = diff.summary();
        return s != null && !s.isBlank() && !"(no changes)".equals(s);
    }

    private double extractScore(CycleContextData ctx) {
        if (ctx.evaluationOutcome() == null) return 0.5;
        Double s = ctx.evaluationOutcome().score();
        return s != null ? clamp(s) : 0.5;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public record FulfillmentDecision(boolean fulfilled, double confidence, String rationale) {
        public static FulfillmentDecision yes(double c, String r) { return new FulfillmentDecision(true, c, r); }
        public static FulfillmentDecision no(String r) { return new FulfillmentDecision(false, 0.0, r); }
        public static FulfillmentDecision no(double c, String r) { return new FulfillmentDecision(false, c, r); }
    }
}
