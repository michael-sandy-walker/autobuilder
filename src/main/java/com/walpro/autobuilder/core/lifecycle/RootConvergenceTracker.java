package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.core.checkpoint.CheckpointableState;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

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
@StateHolder(value = StateBucket.CAPTURE,
        note = "the progress counters and cycle/minute budgets decide when the run gives up")
public class RootConvergenceTracker implements CheckpointableState {

    /** Public so the cycle owner can recognise this brake's halt and offer the T4-DQ rewind. */
    public static final String HALT_SOURCE = "root-convergence-stall";

    private final GoalPlannerService goalPlannerService;

    /** T2-EQ: optional — absent means packaging is not verified before the halt (prior behaviour). */
    @Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.PreHaltPackagingCheck preHaltPackagingCheck;

    /** Consecutive cycles without root progress before the loop halts for review. */
    @Setter
    @Value("${autobuilder.deadlock.root-convergence-cycle-limit:20}")
    private int cycleLimit = 20;

    /**
     * T2-FH: the run's declared time budget in minutes, so the brake can tell "stop this waste"
     * from "stop this run with most of its budget unspent". Zero means no budget was declared and
     * the brake makes no claim about time — the trend test still applies.
     */
    @Setter
    @Value("${autobuilder.run.budget-minutes:0}")
    private int budgetMinutes = 0;

    private int lastProgressCount = 0;
    private int lastInjectionCount = 0;
    private int cyclesSinceProgress = 0;

    /** T2-FH: merges per cycle, the one measure that still varies inside a root-progress drought. */
    private final java.util.List<Integer> mergesPerCycle = new java.util.ArrayList<>();

    /** Wall clock, overridable so a test can spend a budget without spending the time. */
    @Setter
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private long firstCycleAt = 0L;
    private int extensionsGranted = 0;

    /**
     * T4-DQ slice 3: the drought belonged to a path the run has just backed out of, so the
     * counters resync against the state it returned to rather than carrying the abandoned stretch
     * forward. Called by the cycle owner after a rewind — this class judges whether the run is
     * grinding and says so; what to DO about a halt is not its decision.
     */
    /** How long the run has been stalled — read by the halt-edge rewind decision for its trace. */
    public int cyclesSinceProgress() {
        return cyclesSinceProgress;
    }

    public void resyncAfterRewind() {
        lastProgressCount = goalPlannerService.countCompletedSubgoalsWithMerges();
        lastInjectionCount = goalPlannerService.countClosureEdgeInjections();
        cyclesSinceProgress = 0;
        mergesPerCycle.clear();
    }

    // ---------------------------------------------------------------------------------
    // T4-DQ: checkpoint / rewind
    // ---------------------------------------------------------------------------------

    /**
     * The brake's own accounting. {@code cycleLimit}, {@code budgetMinutes} and {@code clock} are
     * absent: the first two are configuration and identical either side of a rewind, and the third
     * is wall clock, which a rewind does not turn back — rewound time IS spent, and a brake that
     * forgot it would let a stuck run buy itself unlimited extensions by rewinding.
     */
    public record Snapshot(int lastProgressCount, int lastInjectionCount, int cyclesSinceProgress,
                           java.util.List<Integer> mergesPerCycle, int extensionsGranted) {}

    @Override
    public String stateKey() {
        return "rootConvergence";
    }

    @Override
    public Object captureState() {
        return new Snapshot(lastProgressCount, lastInjectionCount, cyclesSinceProgress,
                java.util.List.copyOf(mergesPerCycle), extensionsGranted);
    }

    @Override
    public void restoreState(com.fasterxml.jackson.databind.JsonNode state,
                             com.fasterxml.jackson.databind.ObjectMapper mapper) {
        lastProgressCount = 0;
        lastInjectionCount = 0;
        cyclesSinceProgress = 0;
        mergesPerCycle.clear();
        extensionsGranted = 0;
        if (state == null || state.isNull()) return;
        Snapshot snapshot = mapper.convertValue(state, Snapshot.class);
        lastProgressCount = snapshot.lastProgressCount();
        lastInjectionCount = snapshot.lastInjectionCount();
        cyclesSinceProgress = snapshot.cyclesSinceProgress();
        if (snapshot.mergesPerCycle() != null) mergesPerCycle.addAll(snapshot.mergesPerCycle());
        extensionsGranted = snapshot.extensionsGranted();
    }

    /**
     * Post-cycle hook. Returns the context unchanged while the root is progressing; returns it
     * with {@code GOAL_EXHAUSTED + SYSTEM_PAUSED} once the stall limit is reached.
     */
    public CycleContextData track(CycleContextData context) {
        if (firstCycleAt == 0L) firstCycleAt = clock.getAsLong();
        mergesPerCycle.add(context.merged() ? 1 : 0);
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
            // T2-FH: the drought is real, but is the run grinding or still working? Root progress
            // is zero here by construction, so the question is asked of the measure that still
            // varies: merges landing in the window just elapsed, against the run's own history.
            ConvergenceTrend.Assessment trend = ConvergenceTrend.assess(mergesPerCycle, cycleLimit);
            if (trend.stillLandingWork() && budgetAllowsAnotherWindow()) {
                extensionsGranted++;
                log.warn("T2-FH: root progress is stalled at {} for {} cycles, but work is still landing — {} merge(s) in the window ({} per cycle, against {} per cycle earlier). Granting another window (extension {}); not halting.",
                        progressNow, cyclesSinceProgress, trend.windowMerges(),
                        String.format("%.2f", trend.windowRate()), String.format("%.2f", trend.earlierRate()),
                        extensionsGranted);
                cyclesSinceProgress = 0;
                return context;
            }
            log.error("T4-I: root convergence stall — {} consecutive cycles without a new completed-with-merge subgoal (count frozen at {}); verify-before-pause found nothing to promote. T2-FH: {} merge(s) landed in that window ({} per cycle, against {} per cycle earlier){}. Pausing for review.",
                    cyclesSinceProgress, progressNow, trend.windowMerges(),
                    String.format("%.2f", trend.windowRate()), String.format("%.2f", trend.earlierRate()),
                    trend.stillLandingWork() ? " — work was still landing, but the budget cannot fund another window" : "");
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

    /**
     * T2-FH: whether the run can still pay for another window, measured in its OWN cycle
     * durations rather than a fixed number of minutes — a slow run's window costs more wall time
     * than a fast one's, and the brake must not pretend otherwise
     * ([[feedback-relative-thresholds]]).
     *
     * <p>An undeclared budget grants the window: the loop was given no deadline to respect, and
     * inventing one here would be the fixed constant this ticket exists to remove.
     */
    private boolean budgetAllowsAnotherWindow() {
        if (budgetMinutes <= 0) return true;
        if (mergesPerCycle.isEmpty()) return false;
        long elapsedMs = clock.getAsLong() - firstCycleAt;
        long remainingMs = budgetMinutes * 60_000L - elapsedMs;
        long meanCycleMs = elapsedMs / mergesPerCycle.size();
        long windowCostMs = meanCycleMs * cycleLimit;
        if (remainingMs > windowCostMs) return true;
        log.info("T2-FH: work is still landing, but {} min of budget leaves no room for another {}-cycle window at this run's own pace ({} s per cycle).",
                remainingMs / 60_000L, cycleLimit, meanCycleMs / 1000);
        return false;
    }
}
