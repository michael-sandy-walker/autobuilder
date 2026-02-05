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

        // Do NOT deadlock early: treat repetition+lowConfidence as a breakout trigger (diverge + replan),
        // not as a pause. The graph/meta layer can then propose concrete steps.
        if (repetition >= 0.90 && lowConfidence >= 0.90) {
            Signal diverge = Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "DeliberationProposer");
            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.85, 0.10, "DeliberationProposer");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "DeliberationProposer");

            log.info("rationale=High repetition + low confidence -> breakout (REPLAN + DIVERGENCE), not PAUSE.");
            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.85,
                    "High repetition + low confidence indicates thrash → request breakout via replanning and divergence.",
                    List.of("repetition=" + repetition, "lowConfidence=" + lowConfidence),
                    List.of(),
                    Map.of(
                            "repetition", repetition,
                            "lowConfidence", lowConfidence,
                            "thrash", true,
                            "action", "replan+diverge"
                    ),
                    List.of(planner, replan, diverge)
            );
        }

        // Goal exhaustion should trigger transition behavior.
        if (context.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.75)) {
            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.90, 0.10, "DeliberationProposer");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "DeliberationProposer");

            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.85,
                    "Goal exhaustion is strong → request replanning / goal transition instead of pausing.",
                    List.of("GOAL_EXHAUSTED >= 0.75"),
                    List.of(),
                    Map.of("signal", "GOAL_EXHAUSTED", "action", "replan_transition"),
                    List.of(planner, replan)
            );
        }

        // Use what we already compute (evaluation outcome / goal progress / mutation state).
        var outcome = context.evaluationOutcome();
        GoalProgressState gp = outcome != null ? outcome.goalProgress() : null;
        MutationAcceptanceState ms = outcome != null ? outcome.mutationState() : null;

        // Prefer replanning on stagnation (existing behavior).
        if (gp == GoalProgressState.STAGNATING) {
            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.75, 0.10, "DeliberationProposer");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 0.90, 0.20, "DeliberationProposer");

            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.75,
                    "Goal is stagnating → prefer replanning to escape local optimum.",
                    List.of("Stagnation suggests missing subgoal or wrong path."),
                    List.of(),
                    Map.of("goalProgress", "STAGNATING"),
                    List.of(planner, replan)
            );
        }

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
