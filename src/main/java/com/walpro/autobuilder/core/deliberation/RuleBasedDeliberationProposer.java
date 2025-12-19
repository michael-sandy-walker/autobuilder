package com.walpro.autobuilder.core.deliberation;

import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import com.walpro.autobuilder.core.lifecycle.Signal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
@RequiredArgsConstructor
public class RuleBasedDeliberationProposer implements DeliberationProposer {

    private final GoalExhaustionDetector goalExhaustionDetector;

    @Override
    public DeliberationResult propose(CycleContextData context) {
        if (context == null) {
            return DeliberationResult.noop("Context is null.");
        }

        // If the system is paused, deliberation should not override that.
        if (context.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)) {
            return new DeliberationResult(
                    DeliberationMode.PAUSE,
                    1.0,
                    "SYSTEM_PAUSED signal is strong.",
                    List.of("Signal gate prevents progression."),
                    List.of(),
                    Map.of("signal", "SYSTEM_PAUSED"),
                    List.of()
            );
        }

        double repetition = strength(context, BasicSignals.REPETITION_DETECTED);
        double lowConfidence = strength(context, BasicSignals.LOW_CONFIDENCE);
        if (repetition >= 0.90 && lowConfidence >= 0.90) {
            return new DeliberationResult(
                    DeliberationMode.PAUSE,
                    0.75,
                    "High repetition + low confidence indicates thrash; pausing for planner/merge intervention.",
                    List.of("repetition=" + repetition, "lowConfidence=" + lowConfidence),
                    List.of(),
                    Map.of("repetition", repetition, "lowConfidence", lowConfidence),
                    List.of()
            );
        }

        var report = goalExhaustionDetector.detect(context);
        if (report != null && report.confidence() >= 0.75) {
            Signal exhausted = Signal.of(BasicSignals.GOAL_EXHAUSTED, report.confidence(), 0.03, "GoalExhaustionDetector");
            return new DeliberationResult(
                    DeliberationMode.PAUSE,
                    report.confidence(),
                    report.rationale(),
                    List.of("Goal exhaustion detected."),
                    List.of(),
                    report.evidence(),
                    List.of(exhausted)
            );
        }

        // Use what we already compute (evaluation outcome / goal progress / mutation state).
        var outcome = context.evaluationOutcome();
        GoalProgressState gp = outcome != null ? outcome.goalProgress() : null;
        MutationAcceptanceState ms = outcome != null ? outcome.mutationState() : null;

        // Minimal “do something meaningful” defaults:
        if (gp == GoalProgressState.STAGNATING) {
            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.75,
                    "Goal is stagnating → prefer replanning to escape local optimum.",
                    List.of("Stagnation suggests missing subgoal or wrong path."),
                    List.of(),
                    Map.of("goalProgress", "STAGNATING"),
                    List.of()
            );
        }

//        // Thrash pause (run hits this: repetition=0.95, low_confidence=0.95) // TODO: Use recombine logic instead
//        if (ms == MutationAcceptanceState.REJECTED) {
//            return new DeliberationResult(
//                    DeliberationMode.EVOLVE,
//                    0.65,
//                    "Mutation rejected → try evolving alternatives.",
//                    List.of("Rejected mutation implies low utility change."),
//                    Map.of("mutationState", "REJECTED")
//            );
//        }

        // NEW: exhaustion short-circuit (generic)
        if (report != null && report.exhausted() && report.confidence() >= 0.65) {
            return new DeliberationResult(
                    DeliberationMode.PAUSE, // or REPLAN if we prefer “continue but reframe”
                    report.confidence(),
                    "Exhaustion detected → pausing to avoid thrash and require goal transition.",
                    List.of(report.rationale()),
                    List.of(),
                    Map.of("exhaustionEvidence", report.evidence()),
                    List.of()
            );
        }

        // Default: keep the normal flow.
        return new DeliberationResult(
                DeliberationMode.NOOP,
                0.55,
                "No strong reason to deviate from the default execution flow.",
                List.of("No pause", "No stagnation", "No rejection"),
                List.of(),
                Map.of(),
                List.of()
        );
    }

    private static double strength(CycleContextData ctx, Object expectedType) {
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty() || expectedType == null) return 0.0;

        for (Signal s : ctx.signals().values()) {
            if (s == null || s.type() == null) continue;

            if (s.type().equals(expectedType)) return s.strength();

            // fallback: compare by string to survive class/interface mismatches
            if (expectedType.toString().equalsIgnoreCase(s.type().toString())) return s.strength();
        }
        return 0.0;
    }

}
