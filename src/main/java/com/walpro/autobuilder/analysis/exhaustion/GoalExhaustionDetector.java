package com.walpro.autobuilder.analysis.exhaustion;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.model.GoalProgressState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class GoalExhaustionDetector {

    /**
     * v1 rules (deliberately simple):
     * Goal is exhausted if:
     *  - current goal progress is STAGNATING (or a strong STAGNATION signal exists), AND
     *  - (repetition detected OR severe score regression OR low-entropy diff),
     * because that combination signals "no meaningful degrees of freedom left under current constraints".
     */
    public ExhaustionReport detect(CycleContextData context) {
        if (context == null) {
            return ExhaustionReport.none("No context available");
        }

        String goalId = firstNonBlank(context.currentSubGoalId(), context.currentGoalId());
        if (goalId == null) {
            return ExhaustionReport.none("No goalId available for goal exhaustion detection");
        }

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("goalId", goalId);
        evidence.put("cycleName", context.cycleName());

        Set<Signal> signals = new HashSet<>(context.signals().values());

        boolean stagnatingByState = context.goalProgress() == GoalProgressState.STAGNATING;
        boolean stagnatingBySignal = hasSignal(signals, BasicSignals.STAGNATION, 0.50);

        boolean isStagnating = stagnatingByState || stagnatingBySignal;
        evidence.put("stagnatingByState", stagnatingByState);
        evidence.put("stagnatingBySignal", stagnatingBySignal);

        if (!isStagnating) {
            return ExhaustionReport.none("Not stagnating — goal exhaustion not suspected");
        }

        // 1) Repetition hint (very strong exhaustion indicator in your setup)
        boolean repetition = hasSignal(signals, BasicSignals.REPETITION_DETECTED, 0.10);
        evidence.put("repetitionDetected", repetition);

        // 2) Severe score regression (also strong: complexity added without goal gain)
        Double lastScore = context.lastScore();
        double currentScore = currentScore(context.evaluationOutcome());
        boolean hasScore = !Double.isNaN(currentScore);
        Double delta = (lastScore == null || !hasScore) ? null : (currentScore - lastScore);
        boolean severeRegression = delta != null && delta <= -0.15;

        evidence.put("lastScore", lastScore);
        evidence.put("currentScore", hasScore ? currentScore : null);
        evidence.put("delta", delta);
        evidence.put("severeRegression", severeRegression);

        // 3) Low-entropy diff (tiny diffs repeatedly are usually thrash for a solved goal)
        ProjectDiffResult diff = context.projectDiff();
        DiffEntropy entropy = DiffEntropy.from(diff);

        boolean lowEntropy = entropy.changedFiles() <= 1 && entropy.hasAnyChanges();
        boolean noChanges = !entropy.hasAnyChanges();

        evidence.put("diffChangedFiles", entropy.changedFiles());
        evidence.put("diffHasAnyChanges", entropy.hasAnyChanges());
        evidence.put("lowEntropyDiff", lowEntropy);
        evidence.put("noChanges", noChanges);

        // Confidence scoring (simple and interpretable)
        double confidence = 0.0;

        // stagnation is already true -> base confidence
        confidence += 0.35;

        if (repetition) confidence += 0.30;
        if (severeRegression) confidence += 0.25;
        if (noChanges) confidence += 0.20;
        else if (lowEntropy) confidence += 0.10;

        confidence = clamp01(confidence);

        // Decide exhausted
        // threshold chosen so: stagnation + (repetition OR severeRegression) reliably triggers exhaustion
        boolean exhausted = confidence >= 0.65 && (repetition || severeRegression || noChanges);

        if (exhausted) {
            return ExhaustionReport.of(
                    confidence,
                    "Goal appears exhausted: stagnation combined with repetition/regression/low-entropy diffs suggests no meaningful progress space left",
                    evidence
            );
        }

        return ExhaustionReport.none("Stagnation observed, but exhaustion confidence too low (" + confidence + ")");
    }

    private static boolean hasSignal(Set<Signal> signals, Object expectedType, double minStrength) {
        if (signals == null || signals.isEmpty() || expectedType == null) return false;

        for (Signal s : signals) {
            if (s == null) continue;
            if (s.type() != null && s.type().equals(expectedType)) {
                return s.strength() >= minStrength;
            }
            // Fallback: compare by string name if implementations differ
            if (s.type() != null && expectedType.toString().equalsIgnoreCase(s.type().toString())) {
                return s.strength() >= minStrength;
            }
        }
        return false;
    }

    private static double currentScore(EvaluationOutcome outcome) {
        if (outcome == null) return Double.NaN;
        return outcome.score();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private record DiffEntropy(int changedFiles, boolean hasAnyChanges) {
        static DiffEntropy from(ProjectDiffResult diff) {
            if (diff == null) return new DiffEntropy(0, false);

            int changed = 0;

            // This is intentionally conservative, because your ProjectDiffResult structure may evolve.
            // If it has a "changes map" + "deleted set" pattern (common in your codebase), this covers it.
            try {
                if (diff.changedFiles() != null) changed += diff.changedFiles().size();
            } catch (Throwable ignored) { }

            try {
                if (diff.deletedPaths() != null) changed += diff.deletedPaths().size();
            } catch (Throwable ignored) { }

            boolean any = changed > 0;
            return new DiffEntropy(changed, any);
        }
    }
}
