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

        // T2-R: pre-emptive REPLAN when planner fingerprint repetition is detected early,
        // before the heavier count-based PLANNING_THROTTLED mechanism fires.
        // REPLAN_THRASH_DETECTED is emitted by ReplanPatternDetector after ≥4 samples
        // with repetition severity ≥ 0.50 in the last 6 planner actions.
        if (context.hasStrongSignal(BasicSignals.REPLAN_THRASH_DETECTED, 0.50)) {
            double thrashStrength = strength(context, BasicSignals.REPLAN_THRASH_DETECTED);
            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.80, 0.10, "ReplanPatternDetector");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "ReplanPatternDetector");
            log.info("rationale=REPLAN_THRASH_DETECTED={} → pre-emptive REPLAN to broaden approach before throttle engages.", thrashStrength);
            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.80,
                    "Planner fingerprint repetition detected (leading indicator) → REPLAN to broaden approach before throttle engages.",
                    List.of("REPLAN_THRASH_DETECTED=" + thrashStrength),
                    List.of(),
                    Map.of("thrashStrength", thrashStrength, "action", "preemptive_replan"),
                    List.of(planner, replan)
            );
        }

        double repetition = strength(context, BasicSignals.REPETITION_DETECTED);

        // T2-AN: a single repetition detection is not sufficient to trigger REPLAN — the
        // DIVERGENCE_REQUEST mechanism should be given a chance to break the pattern first (T2-AO).
        // Escalate to REPLAN only when REPETITION_DETECTED has been *reinforced* across consecutive
        // cycles (strength ≥ 2.0 requires at least 2 re-detections, since Signal.reinforce() resets
        // age on every fire making age-based checks unreliable as a cycle counter).
        if (repetition >= 2.0) {
            Signal diverge = Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "DeliberationProposer");
            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.85, 0.10, "DeliberationProposer");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "DeliberationProposer");

            log.info("rationale=Sustained repetition pattern (strength={}) → breakout (REPLAN + DIVERGENCE), not PAUSE.", repetition);
            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.85,
                    "Sustained repetition (reinforced across ≥2 consecutive cycles) indicates a stuck pattern → request breakout via replanning and divergence.",
                    List.of("repetition=" + repetition),
                    List.of(),
                    Map.of(
                            "repetition", repetition,
                            "sustainedPattern", true,
                            "action", "replan+diverge"
                    ),
                    List.of(planner, replan, diverge)
            );
        }

        // Root goal exhaustion = halt; do not trigger replanning (T1-AK).
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

        // Subgoal exhaustion is recoverable: trigger replanning / goal transition (T1-AK).
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

        // T2-AP: score plateau handling, with escalation.
        // First attempt: boost ROLE_MERGE (clears the accepted-not-merged blocker when merge
        // runs and MutationRestoreService can recover the variant from the graph).
        // Inject DIVERGENCE_REQUEST so that once evolution unblocks it tries a different approach.
        // Escalation: if the plateau persists long enough that the merge boost clearly hasn't
        // helped (strength ≥ 3.0, ~3 consecutive cycles), force a replan instead.
        double plateau = strength(context, BasicSignals.SCORE_PLATEAU);
        if (plateau >= 3.0) {
            Signal replan = Signal.of(BasicSignals.REPLAN_REQUESTED, 0.90, 0.10, "score-plateau");
            Signal planner = Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.20, "score-plateau");
            log.warn("rationale=SCORE_PLATEAU={} sustained → merge boost did not break deadlock, escalating to REPLAN.", plateau);
            return new DeliberationResult(
                    DeliberationMode.REPLAN,
                    0.80,
                    "Score plateau sustained (merge boost ineffective) → replan to escape.",
                    List.of("SCORE_PLATEAU=" + plateau),
                    List.of(),
                    Map.of("plateau", plateau, "action", "replan_escalation"),
                    List.of(planner, replan)
            );
        }
        if (plateau >= 1.0) {
            Signal merge = Signal.of(BasicSignals.ROLE_MERGE, 2.0, 0.10, "score-plateau");
            Signal diverge = Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "score-plateau");
            log.info("rationale=SCORE_PLATEAU={} → boost ROLE_MERGE to unblock evolution; inject DIVERGENCE_REQUEST for next cycle.", plateau);
            return new DeliberationResult(
                    DeliberationMode.NOOP,
                    0.60,
                    "Score plateau detected → boost merge to clear accepted-not-merged blocker; inject DIVERGENCE_REQUEST for divergent evolution.",
                    List.of("SCORE_PLATEAU=" + plateau),
                    List.of(),
                    Map.of("plateau", plateau, "action", "boost_merge_inject_divergence"),
                    List.of(merge, diverge)
            );
        }

        // Use what we already compute (evaluation outcome / goal progress / mutation state).
        var outcome = context.evaluationOutcome();
        GoalProgressState gp = outcome != null ? outcome.goalProgress() : null;
        MutationAcceptanceState ms = outcome != null ? outcome.mutationState() : null;

        // Stagnation handling: prefer divergent evolution when DIVERGENCE_REQUEST is already set
        // (injected by AutobuilderService on the previous STAGNATING cycle), otherwise replan.
        if (gp == GoalProgressState.STAGNATING) {
            double divergence = strength(context, BasicSignals.DIVERGENCE_REQUEST);
            if (divergence >= 0.5) {
                // DIVERGENCE_REQUEST is active — a fresh approach via evolution is warranted.
                // Return NOOP so all roles remain eligible; evolution will rank highest via its
                // signal strength and attempt a structurally different mutation.
                Signal evolution = Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.10, "DeliberationProposer");
                log.info("rationale=Stagnating + DIVERGENCE_REQUEST={} active → NOOP (divergent evolution path, not REPLAN).", divergence);
                return new DeliberationResult(
                        DeliberationMode.NOOP,
                        0.75,
                        "Stagnating but DIVERGENCE_REQUEST active → open all roles for divergent evolution attempt.",
                        List.of("goalProgress=STAGNATING", "DIVERGENCE_REQUEST=" + divergence),
                        List.of(),
                        Map.of("goalProgress", "STAGNATING", "action", "diverge"),
                        List.of(evolution)
                );
            }

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
        // decay and projectDiff being cleared between cycles (T1-AC).
        boolean pendingFingerprint = context.lastEvolutionFingerprint() != null
                && !Objects.equals(context.lastEvolutionFingerprint(), context.lastEvaluatedEvolutionFingerprint());

        boolean hasCandidate =
                pendingFingerprint
                        || context.hasSignal(BasicSignals.EVOLUTION_OCCURRED)
                        || context.projectDiff() != null;

        boolean notYetEvaluated = pendingFingerprint
                || !Objects.equals(context.lastEvolutionFingerprint(), context.lastEvaluatedEvolutionFingerprint());

        if (hasCandidate && notYetEvaluated) {
            // Safety net (T1-G): EVOLUTION_OCCURRED can linger as a decaying signal even when
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
