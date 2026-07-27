package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
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

    /** T2-EQ: optional — absent means packaging is not verified before the halt (prior behaviour). */
    @Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.PreHaltPackagingCheck preHaltPackagingCheck;

    /** Consecutive cycles without root progress before the loop halts for review. */
    @Setter
    @Value("${autobuilder.deadlock.root-convergence-cycle-limit:20}")
    private int cycleLimit = 20;

    private int lastProgressCount = 0;
    private int lastInjectionCount = 0;
    private int cyclesSinceProgress = 0;

    /**
     * Post-cycle hook. Returns the context unchanged while the root is progressing; returns it
     * with {@code GOAL_EXHAUSTED + SYSTEM_PAUSED} once the stall limit is reached.
     */
    public CycleContextData track(CycleContextData context) {
        int progressNow = goalPlannerService.countCompletedSubgoalsWithMerges();
        int injectionsNow = goalPlannerService.countClosureEdgeInjections();

        if (progressNow != lastProgressCount) {
            // Increase = real root progress. Decrease = tree reset/shrunk — resync, don't fire.
            // The injection baseline resyncs too: a same-cycle injection must not linger as a
            // stale delta that false-resets a genuine stall many cycles later.
            lastProgressCount = progressNow;
            lastInjectionCount = injectionsNow;
            cyclesSinceProgress = 0;
            return context;
        }

        // Second progress class: the closure edge injected fresh required work. A gate injection
        // is measured distance-to-done (the ledger was otherwise clean and a gate named exactly
        // what is missing) — unfakeable by the planner, and about to be worked. Run 1 of
        // 2026-07-03 was paused 34 s after the package gate injected the entry-point work item;
        // the identical injection in run 4b was fixed in-loop and led to fulfillment. Bounded:
        // T1-DE drops GIVEN_UP-paraphrase re-injections, so a failing item cannot reset forever.
        if (injectionsNow != lastInjectionCount) {
            log.info("T4-I: closure edge injected {} new work item(s) since the last check — counting as root progress, not stall.",
                    injectionsNow - lastInjectionCount);
            lastInjectionCount = injectionsNow;
            cyclesSinceProgress = 0;
            return context;
        }

        cyclesSinceProgress++;
        if (cyclesSinceProgress >= cycleLimit) {
            // T2-EH / T4-I tuning: verify-before-pause. Before guillotining a near-complete
            // artifact, re-verify the active subgoals against the current code and promote those
            // that pass, then attempt root completion. If that completes the root or promotes any
            // subgoal (real progress the stall had been hiding), resync and continue rather than
            // halt — the fixed limit stops being a hard guillotine and becomes a verify checkpoint.
            GoalPlannerService.VerifyBeforePauseResult verify =
                    goalPlannerService.verifyAndPromoteBeforePause(context);
            if (verify.madeProgress()) {
                int progressAfter = goalPlannerService.countCompletedSubgoalsWithMerges();
                // A promotion only breaks the stall if it moved the one measure the planner cannot
                // fake by re-promoting: the root completed, or a new completed-with-merge subgoal
                // appeared. A bare re-promotion that leaves the count flat is the non-halt
                // signature — the same leaf re-promoted every stall window while a structural
                // gate refuses closure, "completed-with-merge N -> N" (2026-07-07: this spun ~63
                // min before it was stopped by hand). Genuine promotion-driven progress is already
                // caught above when the count advances, so reaching here flat means there is
                // nothing to promote toward; fall through to the injection re-check and pause.
                if (verify.rootCompleted() || progressAfter > progressNow) {
                    log.info("T2-EH: verify-before-pause broke the convergence stall (rootCompleted={}, promoted={}, completed-with-merge {} -> {}) — not halting.",
                            verify.rootCompleted(), verify.promoted(), progressNow, progressAfter);
                    lastProgressCount = progressAfter;
                    lastInjectionCount = goalPlannerService.countClosureEdgeInjections();
                    cyclesSinceProgress = 0;
                    return context;
                }
                log.warn("T2-EH: verify-before-pause promoted {} subgoal(s) but the root metric is flat (completed-with-merge {} -> {}) — not a stall break; the promotion did not move the root (non-halt guard).",
                        verify.promoted(), progressNow, progressAfter);
            }
            // Verify-before-pause runs the completion edge, whose gates may have injected fresh
            // work items just now — madeProgress() only sees promotions, so re-check the
            // injection counter before halting on work the loop was handed this very cycle.
            injectionsNow = goalPlannerService.countClosureEdgeInjections();
            if (injectionsNow != lastInjectionCount) {
                log.info("T4-I: verify-before-pause promoted nothing, but the closure edge injected {} new work item(s) — not halting.",
                        injectionsNow - lastInjectionCount);
                lastInjectionCount = injectionsNow;
                cyclesSinceProgress = 0;
                return context;
            }
            // T2-EQ: last check before the run dies — can the build tool package this artifact at
            // all? The closure path owns that question, but it early-returns while any subgoal is
            // ACTIVE, which is precisely this state, so a stalling run never asked it (measured: 0
            // gate invocations in 65 cycles). A failure here is gate-measured, unfakeable work of
            // exactly the class already treated as progress above, so hand it to the loop and keep
            // going. Self-bounding: nothing is created while a build-fix item is already open.
            if (preHaltPackagingCheck != null
                    && preHaltPackagingCheck.rescueIfNotPackageable(context.cycleName())) {
                log.info("T2-EQ: the artifact does not package — repair work item injected at the halt edge; not halting.");
                lastInjectionCount = goalPlannerService.countClosureEdgeInjections();
                cyclesSinceProgress = 0;
                return context;
            }
            log.error("T4-I: root convergence stall — {} consecutive cycles without a new completed-with-merge subgoal (count frozen at {}); verify-before-pause found nothing to promote. Pausing for review.",
                    cyclesSinceProgress, progressNow);
            // T5-BF follow-up: the system_paused behaviour event is emitted at the single
            // AutobuilderService home (post-cycle, same tick) off the pausedReason below.
            return context
                    .withGoalExhaustedReason(HALT_SOURCE)
                    .withPausedReason(HALT_SOURCE);
        }
        if (cyclesSinceProgress * 2 >= cycleLimit) {
            log.warn("T4-I: no root progress for {} cycles (limit {}); completed-with-merge subgoals frozen at {}",
                    cyclesSinceProgress, cycleLimit, progressNow);
        }
        return context;
    }
}
