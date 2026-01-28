package com.walpro.autobuilder.core.deliberation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.walpro.autobuilder.core.lifecycle.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
@RequiredArgsConstructor
@Slf4j
public class RuleBasedDeliberationProposer implements DeliberationProposer {

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
            Signal paused = Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.01, "DeliberationProposer");

            // Optional: reinforce the observed signals so they're visible and decay consistently
//            Signal rep = Signal.of(BasicSignals.REPETITION_DETECTED, 0.20, 0.03, "DeliberationProposer");
//            Signal low = Signal.of(BasicSignals.LOW_CONFIDENCE, 0.20, 0.03, "DeliberationProposer");

            log.info("rationale=High repetition + low confidence indicates thrash; pausing deliberation phase.");
            return new DeliberationResult(
                    DeliberationMode.PAUSE,
                    0.75,
                    "High repetition + low confidence indicates thrash; pausing deliberation phase.",
                    List.of("repetition=" + repetition, "lowConfidence=" + lowConfidence),
                    List.of(),
                    Map.of("repetition", repetition, "lowConfidence", lowConfidence),
                    List.of(paused/*, rep, low*/)
            );
        }

        if (context.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.75)) {
            return new DeliberationResult(
                    DeliberationMode.PAUSE, // or REPLAN if we want “pause but allow planner”
                    0.75,
                    "Goal exhaustion signal is strong; pausing to avoid thrash and require goal transition.",
                    List.of("GOAL_EXHAUSTED >= 0.75"),
                    List.of(),
                    Map.of("signal", "GOAL_EXHAUSTED"),
                    List.of()
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

        boolean hasCandidate =
                context.hasSignal(BasicSignals.EVOLUTION_OCCURRED)
                        || context.projectDiff() != null;

        boolean notYetEvaluated = !Objects.equals(context.lastEvolutionFingerprint(), context.lastEvaluatedEvolutionFingerprint());

        if (hasCandidate && notYetEvaluated) {
            return new DeliberationResult(
                    DeliberationMode.EVALUATE,
                    0.8,
                    "Candidate change exists and has not been evaluated yet.",
                    List.of("Evolution occurred", "No evaluation outcome"),
                    List.of(),
                    Map.of("reason", "pending_evaluation"),
                    List.of()
            );
        }

//        if (context.currentSubGoalId().equals(context.currentGoalId()) && context.parentCycleName() == null /* if previous cycle is null, there was no subgoal */) {
//            return new DeliberationResult(
//                    DeliberationMode.REPLAN,
//                    0.9,
//                    "No active subgoal exists; planning required.",
//                    List.of("currentSubGoalId is null or equal to currentGoalId"),
//                    List.of(),
//                    Map.of("reason", "missing_focus"),
//                    List.of()
//            );
//        }

        // bootstrap
        if (context.parentCycleName() == null) {
            return new DeliberationResult(
                    DeliberationMode.BOOTSTRAP,
                    0.9,
                    "Bootstrap.",
                    List.of(),
                    List.of(),
                    Map.of(),
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
