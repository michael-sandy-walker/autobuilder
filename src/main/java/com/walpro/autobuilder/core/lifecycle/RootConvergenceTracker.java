package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * T4-I (root-scoped slice): the convergence brake — detects <i>activity without convergence</i>.
 *
 * <p>Every existing termination brake is activity-based and resets on any merge, injection, or
 * fresh subgoal (T1-EB zero-work, T1-EI planner-empty-output, per-parent exhaustion). A run in
 * productive churn — merges landing, files written, real subgoals injected each replan, but no
 * net progress sticking at the root — resets all of them forever (observed 2026-06-08: 9 merges,
 * build flapping 43 OK / 145 ERROR, Loans never converged, no brake fired).
 *
 * <p>This tracker watches the one measure the planner cannot reset by renaming or re-decomposing
 * work: {@link GoalPlannerService#countCompletedSubgoalsWithMerges()} — completed subgoals that
 * carried at least one merge. A merge alone does <b>not</b> reset the stall counter; only that
 * count increasing does. When the count fails to increase for
 * {@code autobuilder.deadlock.root-convergence-cycle-limit} consecutive cycles, the loop halts
 * through the existing {@code GOAL_EXHAUSTED + SYSTEM_PAUSED} path (same shape as the T1-EB and
 * T1-EI halts).
 *
 * <p>A decrease in the count (goal-tree reset, new session in the same JVM) resynchronises the
 * baseline without firing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RootConvergenceTracker {

    static final String HALT_SOURCE = "root-convergence-stall";

    private final GoalPlannerService goalPlannerService;

    /** Optional behaviour trace logger — emits {@code system_paused} when the brake fires. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** Consecutive cycles without root progress before the loop halts for review. */
    @Setter
    @Value("${autobuilder.deadlock.root-convergence-cycle-limit:20}")
    private int cycleLimit = 20;

    private int lastProgressCount = 0;
    private int cyclesSinceProgress = 0;

    /**
     * Post-cycle hook. Returns the context unchanged while the root is progressing; returns it
     * with {@code GOAL_EXHAUSTED + SYSTEM_PAUSED} once the stall limit is reached.
     */
    public CycleContextData track(CycleContextData context) {
        int progressNow = goalPlannerService.countCompletedSubgoalsWithMerges();

        if (progressNow != lastProgressCount) {
            // Increase = real root progress. Decrease = tree reset/shrunk — resync, don't fire.
            lastProgressCount = progressNow;
            cyclesSinceProgress = 0;
            return context;
        }

        cyclesSinceProgress++;
        if (cyclesSinceProgress >= cycleLimit) {
            log.error("T4-I: root convergence stall — {} consecutive cycles without a new completed-with-merge subgoal (count frozen at {}). Pausing for review.",
                    cyclesSinceProgress, progressNow);
            if (behaviourLogger != null) {
                behaviourLogger.systemPaused(context.cycleName(), HALT_SOURCE, cyclesSinceProgress);
            }
            return context
                    .withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, HALT_SOURCE))
                    .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, HALT_SOURCE));
        }
        if (cyclesSinceProgress * 2 >= cycleLimit) {
            log.warn("T4-I: no root progress for {} cycles (limit {}); completed-with-merge subgoals frozen at {}",
                    cyclesSinceProgress, cycleLimit, progressNow);
        }
        return context;
    }
}
