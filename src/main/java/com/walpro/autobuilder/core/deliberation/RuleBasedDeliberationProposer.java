package com.walpro.autobuilder.core.deliberation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import static com.walpro.autobuilder.goal.GoalKindClassifier.GoalKind.EXECUTABLE;
import static com.walpro.autobuilder.goal.GoalKindClassifier.GoalKind.HYBRID;

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

        if (context.analysisOutcome() != null
                && context.analysisOutcome().proposedExecutableSubGoals() != null
                && !context.analysisOutcome().proposedExecutableSubGoals().isEmpty()
                && context.analysisScopeSubGoalId() != null
                && context.analysisScopeSubGoalId().equals(context.currentSubGoalId())) {

            // Analysis unlocked executable next steps -> prefer normal flow (not REPLAN).
            Signal evolution = Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.20, "DeliberationProposer");
            return new DeliberationResult(
                    DeliberationMode.NOOP,
                    0.70,
                    "Analysis produced executable subgoals for the current scope → proceed with execution flow.",
                    List.of("analysis_proposed_executable=true"),
                    List.of(),
                    Map.of("action", "resume_execution"),
                    List.of(evolution)
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

        // Root goal exhaustion = halt; do not trigger replanning (T2-AK).
        if (context.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.75)) {
            return new DeliberationResult(
                    DeliberationMode.PAUSE,
                    1.0,
                    "GOAL_EXHAUSTED signal is strong — root exhaustion, no recovery possible.",
                    List.of("Signal gate prevents progression."),
                    List.of(),
                    Map.of("signal", "GOAL_EXHAUSTED"),
                    List.of()
            );
        }

        // Subgoal exhaustion is recoverable: trigger replanning / goal transition (T2-AK).
        if (context.hasStrongSignal(BasicSignals.SUBGOAL_EXHAUSTED, 0.75)) {
            boolean hasExecutableRemaining =
                    context.remainingGoals().stream()
                            .map(g -> g != null ? g.getKind() : null)
                            .anyMatch(k -> k == EXECUTABLE || k == HYBRID);

            if (hasExecutableRemaining) {
                Signal evolution = Signal.of(BasicSignals.ROLE_EVOLUTION, 0.75, 0.10, "DeliberationProposer");

                return new DeliberationResult(
                        DeliberationMode.NOOP,
                        0.55,
                        "Executable subgoals still available.",
                        List.of("Prefer execution over replanning."),
                        List.of(),
                        Map.of("hasExecutableRemaining", true),
                        List.of(evolution)
                );
            }

            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.90, 0.10, "DeliberationProposer");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "DeliberationProposer");

            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.85,
                    "Subgoal exhaustion is strong → request replanning / goal transition.",
                    List.of("SUBGOAL_EXHAUSTED >= 0.75"),
                    List.of(),
                    Map.of("signal", "SUBGOAL_EXHAUSTED", "action", "replan_transition"),
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

        // Fingerprint check is the most reliable indicator: it survives EVOLUTION_OCCURRED signal
        // decay and projectDiff being cleared between cycles (T2-AC).
        boolean pendingFingerprint = context.lastEvolutionFingerprint() != null
                && !Objects.equals(context.lastEvolutionFingerprint(), context.lastEvaluatedEvolutionFingerprint());

        boolean hasCandidate =
                pendingFingerprint
                        || context.hasSignal(BasicSignals.EVOLUTION_OCCURRED)
                        || context.projectDiff() != null;

        boolean notYetEvaluated = pendingFingerprint
                || !Objects.equals(context.lastEvolutionFingerprint(), context.lastEvaluatedEvolutionFingerprint());

        if (hasCandidate && notYetEvaluated) {
            // Safety net (T2-E): EVOLUTION_OCCURRED can linger as a decaying signal even when
            // the diff is gone. Without a diff, evaluation is blocked regardless of signal strength.
            // If REPLAN_REQUESTED is strong enough, fall back to REPLAN instead of deadlocking.
            if (context.projectDiff() == null) {
                double replanStrength = strength(context, BasicSignals.REPLAN_REQUESTED);
                if (replanStrength >= 0.50) {
                    Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, replanStrength, 0.10, "DeliberationProposer");
                    Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "DeliberationProposer");
                    log.info("rationale=Candidate pending but no diff available and REPLAN_REQUESTED={} strong → replan fallback.", replanStrength);
                    return new DeliberationResult(
                            DeliberationMode.REPLAN,
                            0.70,
                            "Candidate change present but diff unavailable; evaluation cannot run → replan fallback.",
                            List.of("hasCandidate=true", "projectDiff=null", "REPLAN_REQUESTED=" + replanStrength),
                            List.of(),
                            Map.of("reason", "evaluation_infeasible_replan_fallback"),
                            List.of(planner, replan)
                    );
                }
            }

            // Re-inject ROLE_EVALUATION every cycle while evaluation is pending.
            // Without this, the signal decays below the canExecute threshold (0.10) within
            // a few no-op cycles and the loop deadlocks: mode=EVALUATE but agent cannot execute.
            Signal evalFloor = Signal.of(BasicSignals.ROLE_EVALUATION, 0.5, 0.10, "PendingEvaluation");
            return new DeliberationResult(
                    DeliberationMode.EVALUATE,
                    0.8,
                    "Candidate change exists and has not been evaluated yet.",
                    List.of("Evolution occurred", "No evaluation outcome"),
                    List.of(),
                    Map.of("reason", "pending_evaluation"),
                    List.of(evalFloor)
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

    private static double strength(CycleContextData ctx, SignalType type) {
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty() || type == null) return 0.0;
        Signal s = ctx.signals().get(type);
        return s != null ? s.strength() : 0.0;
    }

}
