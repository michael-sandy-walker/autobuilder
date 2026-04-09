package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * T3-BK: graduation — when this fraction of defined criteria have ever passed, the subgoal
     * is eligible to graduate (force-fulfill) if the remaining unmet criteria have been stuck
     * for at least {@code graduationMinFailStreak} consecutive cycles.
     */
    @Value("${autobuilder.criteria.graduation-ratio:0.80}")
    double graduationRatio = 0.80;

    /**
     * T3-BK: minimum number of consecutive cycles an unmet criterion must have failed before
     * it qualifies as "structurally stuck" and triggers graduation.
     */
    @Value("${autobuilder.criteria.graduation-min-fail-streak:3}")
    int graduationMinFailStreak = 3;

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

        // If interpreter says fulfilled, close immediately — unless a criterion is explicitly unmet or omitted (T3-BA/T3-BC).
        if (gp == GoalProgressState.FULFILLED) {
            if (hasUnmetCriterion(ctx.criteriaAssessments(), ctx.currentSubGoalCriteria(), ctx.cumulativeCriteriaStatus())) {
                return FulfillmentDecision.no("interpreter=FULFILLED suppressed: unmet acceptance criterion");
            }
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

        // T3-BA/T3-BC: veto score-based closure when any criterion is explicitly unmet or was omitted by the LLM.
        if (hasUnmetCriterion(ctx.criteriaAssessments(), ctx.currentSubGoalCriteria(), ctx.cumulativeCriteriaStatus())) {
            // T3-BK: graduation escape — bank progress if enough criteria passed and remaining are structurally stuck.
            FulfillmentDecision graduation = decideGraduation(score, ctx);
            if (graduation != null) return graduation;
            return FulfillmentDecision.no(score, "unmet acceptance criterion");
        }

        return FulfillmentDecision.yes(score, "score-based closure + meaningful diff");
    }

    /**
     * T1-BG: A criterion is "unmet" if:
     *  - it maps to false (or is absent) in cumulativeCriteriaStatus when that map is available, OR
     *  - (fallback, no cumulative map) any assessment line contains ✗, OR
     *  - (fallback, no cumulative map) the LLM omitted lines (assessed count < defined count).
     *
     * When cumulativeCriteriaStatus is present, it is the authoritative source: a criterion that
     * passed in a prior cycle is considered satisfied even if this cycle's LLM omitted or failed it.
     */
    private static boolean hasUnmetCriterion(List<String> assessments, List<String> definedCriteria,
                                              java.util.Map<Integer, Boolean> cumulativeCriteriaStatus) {
        // T1-BG: use cumulative map when available
        if (cumulativeCriteriaStatus != null && definedCriteria != null && !definedCriteria.isEmpty()) {
            for (int i = 1; i <= definedCriteria.size(); i++) {
                Boolean passed = cumulativeCriteriaStatus.get(i);
                if (passed == null || !passed) return true;
            }
            return false;
        }
        // Fallback (no cumulative map yet): original T3-BA/T3-BC logic
        // T3-BA: explicit ✗ marker
        if (assessments != null && assessments.stream().anyMatch(a -> a.contains("\u2717"))) return true;
        // T3-BC: LLM omitted lines — any defined criterion without a corresponding assessment line is an implicit fail
        if (definedCriteria != null && !definedCriteria.isEmpty()) {
            int assessed = assessments != null ? assessments.size() : 0;
            if (assessed < definedCriteria.size()) return true;
        }
        return false;
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

    /**
     * T3-BK: graduation check — returns a graduation {@link FulfillmentDecision} when:
     * <ol>
     *   <li>{@code passedCount / totalCount >= graduationRatio}</li>
     *   <li>every unmet criterion has a consecutive-fail streak ≥ {@code graduationMinFailStreak}</li>
     * </ol>
     * Returns {@code null} when graduation does not apply (caller falls through to the normal veto).
     */
    private FulfillmentDecision decideGraduation(double score, CycleContextData ctx) {
        List<String> definedCriteria = ctx.currentSubGoalCriteria();
        Map<Integer, Boolean> cumulative = ctx.cumulativeCriteriaStatus();
        Map<Integer, Integer> failStreak = ctx.criteriaConsecutiveFailCount();

        if (definedCriteria == null || definedCriteria.isEmpty()) return null;
        if (cumulative == null || cumulative.isEmpty()) return null;
        if (failStreak == null || failStreak.isEmpty()) return null;

        int totalCount = definedCriteria.size();
        long passedCount = cumulative.values().stream().filter(Boolean::booleanValue).count();

        if ((double) passedCount / totalCount < graduationRatio) return null;

        // Identify unmet criteria and verify each has been stuck long enough.
        List<Integer> unmetIndices = new ArrayList<>();
        for (int i = 1; i <= totalCount; i++) {
            Boolean passed = cumulative.get(i);
            if (passed == null || !passed) {
                int streak = failStreak.getOrDefault(i, 0);
                if (streak < graduationMinFailStreak) return null; // not yet stuck
                unmetIndices.add(i);
            }
        }

        if (unmetIndices.isEmpty()) return null; // shouldn't happen if passedCount < totalCount, but guard

        return FulfillmentDecision.graduation(score,
                "graduation: %d/%d criteria satisfied, %d remaining injected as micro-subgoal(s)"
                        .formatted(passedCount, totalCount, unmetIndices.size()),
                unmetIndices);
    }

    public record FulfillmentDecision(boolean fulfilled, boolean graduated, double confidence, String rationale,
                                      List<Integer> unmetCriteriaIndices) {
        public static FulfillmentDecision yes(double c, String r) {
            return new FulfillmentDecision(true, false, c, r, List.of());
        }
        public static FulfillmentDecision graduation(double c, String r, List<Integer> unmet) {
            return new FulfillmentDecision(true, true, c, r, unmet);
        }
        public static FulfillmentDecision no(String r) {
            return new FulfillmentDecision(false, false, 0.0, r, List.of());
        }
        public static FulfillmentDecision no(double c, String r) {
            return new FulfillmentDecision(false, false, c, r, List.of());
        }
    }
}
