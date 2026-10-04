package com.walpro.autobuilder.core.checkpoint;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * T4-DQ slice 3 — the first consumer: back out of a bad path instead of dying on it.
 *
 * <p><b>Where it sits, and why there.</b> At the halt edge, and nowhere else. The convergence brake
 * already asks the only question that matters — is this run still landing work, judged against its
 * own earlier rate ({@code ConvergenceTrend}) — and today it has exactly one answer available when
 * the answer is no: stop the run. This adds the answer that was missing. Nothing new is measured
 * and no new constant is introduced; the existing relative judgement gains a second, strictly
 * better response, taken before the terminal one.
 *
 * <p><b>What the archive says the trigger is worth.</b> Merge droughts, measured from the runs'
 * own behaviour traces: {@code 2026-09-01_23-11-25} spent 42 of its 53 cycles (79%) without landing
 * anything after its third merge, and {@code 2026-08-31_05-47-22} spent 60 of 65 (92%) the same
 * way. Both shipped 5/23. Runs that finished healthily sat at droughts of 2 and 13 cycles. The
 * populations are not close, and the brake already separates them — this changes what happens to
 * the runs on the wrong side of the line.
 *
 * <p><b>Why it does not loop.</b> Three bounds, each closing a different way of spinning:
 * <ul>
 *   <li>a run gets at most {@link #maxRewinds} of them;</li>
 *   <li>each must target a point strictly OLDER than the last one tried, so the same stretch is
 *       never re-walked from the same place;</li>
 *   <li>rewound time is still spent — the wall clock and the run budget are deliberately outside
 *       the capture, so a run cannot buy itself unlimited windows by going backwards.</li>
 * </ul>
 * When no eligible point remains, the halt happens exactly as it does today.
 *
 * <p><b>Why the next attempt differs from the last one.</b> A rewind alone would be a deterministic
 * repeat — the loop asks at temperature zero, so the same state asks the same question and gets
 * the same answer. What breaks the repeat is that the goals planned during the drought are rewound
 * OUT of the live tree but stay in the graph as history, and the planner's duplicate check reads
 * the graph's descriptions rather than the live tree. The plan that led into the grind is therefore
 * still known, still refused as a duplicate, and the planner has to propose something else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "the per-run rewind budget and the last target must NOT come back with a rewind — a "
                + "run that refunded its own allowance would have an unlimited supply of them")
public class RewindPolicy {

    /** Named on the rewind event and in the halt log, so the trace says what asked for it. */
    public static final String TRIGGER = "rewind-on-grind";

    private final RewindService rewindService;

    /**
     * T4-DQ slice 4: optional — when present, each rewind SEALS the path being abandoned and
     * OPENS a branch from the fork point, so the run accumulates a comparable record of what it
     * has tried instead of only a position. Absent (unit constructions) leaves slice 3's
     * behaviour exactly as it was.
     */
    @Setter
    @Autowired(required = false)
    private BranchExplorer branchExplorer;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * How many times one run may back out. Two, because a run that has ground twice from two
     * different points is telling us something a third rewind will not fix, and because each one
     * discards real wall clock the budget still charges for.
     */
    @Setter
    @Value("${autobuilder.rewind.max-per-run:2}")
    private int maxRewinds = 2;

    /** Whether the resolution cache comes back with the state. See {@link CacheMode}. */
    @Setter
    @Value("${autobuilder.rewind.cache-mode:FAITHFUL}")
    private CacheMode cacheMode = CacheMode.FAITHFUL;

    private int rewindsUsed;
    private String lastTargetCycleId;

    /** One-shot: exploration is over and the run has been put on the best path it found. */
    private boolean settledOnBestPath;

    /**
     * Called at the halt edge, once the brake has decided the run is grinding. Returns the result
     * when the loop went back instead, or empty when it should halt as before.
     *
     * @param atCycleId       the cycle the halt was about to happen in
     * @param cyclesInDrought how long the run has been stalled, for the trace
     */
    public Optional<RewindService.Result> considerRewindInsteadOfHalting(String atCycleId, int cyclesInDrought) {
        if (false) return Optional.empty();
        if (rewindsUsed >= maxRewinds) {
            log.info("T4-DQ: grinding at {} but the run has already used its {} rewind(s) — exploration is over.",
                    atCycleId, rewindsUsed);
            return settleOnBestPath(atCycleId);
        }
        // WHERE TO GO BACK TO. With slice 4's ledger the answer is "the newest point that landed
        // work and that no branch has been forked from yet" — a fact about what has been TRIED.
        // Without it, the fallback is positional: strictly older than the last target, so a second
        // rewind at least cannot re-walk the same stretch from the same place. The ledger version
        // is strictly better because a point can be untried and NEWER than the last fork, which
        // the positional rule would skip for no reason beyond its own arithmetic.
        boolean branching = branchExplorer != null;
        Optional<CheckpointRecord> target = branching
                ? branchExplorer.nextUntriedForkPoint()
                : rewindService.lastMergedBefore(lastTargetCycleId != null ? lastTargetCycleId : atCycleId);
        if (target.isEmpty()) {
            log.info("T4-DQ: grinding at {} but {} — exploration is over.",
                    atCycleId, branching
                            ? "every checkpoint that landed work has already been explored"
                            : "no earlier checkpoint landed work");
            return settleOnBestPath(atCycleId);
        }

        CheckpointRecord record = target.get();
        RewindService.Result result = rewindService.rewindTo(
                record.cycleId(), cacheMode, TRIGGER, atCycleId);
        if (!result.performed()) {
            log.warn("T4-DQ: grinding at {} and a rewind to {} was refused ({}) — halting as before.",
                    atCycleId, record.cycleId(), result.refusedBecause());
            return Optional.empty();
        }

        rewindsUsed++;
        lastTargetCycleId = record.cycleId();
        if (branching) {
            branchExplorer.forkAt(record, atCycleId,
                    cyclesInDrought + " cycles without root progress on the path being left");
        }
        log.warn("T4-DQ: {} cycles of grinding at {} — returned to {}, {} that landed work "
                        + "(rewind {} of {}). The plan that led here stays in the graph, so the planner "
                        + "cannot re-propose it.",
                cyclesInDrought, atCycleId, record.cycleId(),
                branching ? "the newest untried point" : "the last point",
                rewindsUsed, maxRewinds);
        return Optional.of(result);
    }

    /**
     * T4-DQ slice 4: exploration is over — the budget is spent or every point that landed work has
     * been tried. Put the run on the most advanced path it found, once, and let it carry on there.
     *
     * <p><b>Why here and not only at the halt.</b> Adoption was wired to the pause transition
     * alone, and a run killed by the harness deadline never reaches it — a SIGTERM runs no
     * terminal code, and that is 55 of 115 recorded runs. The field showed it directly: a run
     * explored three paths, spent its budget, ground on for four more cycles and died on the
     * deadline still on the LAST branch, with a better one sealed and ignored. This is the same
     * mistake the trigger made one level up, and it has the same fix — put the decision where the
     * run actually reaches it.
     *
     * <p>The moment is well defined and costs nothing: once no further exploration is possible,
     * staying on a worse path than one already found is pure loss. One-shot, so the repeated
     * "exploration is over" cycles do not re-adopt.
     */
    private Optional<RewindService.Result> settleOnBestPath(String atCycleId) {
        if (settledOnBestPath || branchExplorer == null) {
            return Optional.empty();
        }
        settledOnBestPath = true;
        branchExplorer.sealCurrent(atCycleId);
        log.info("T4-DQ: paths explored this run — {}", branchExplorer.readout());
        return branchExplorer.adoptMostAdvanced(atCycleId);
    }

    /** How many rewinds this run has spent. */
    public int rewindsUsed() {
        return rewindsUsed;
    }

    /**
     * How many rewinds are allowed in total — read by the halt log so a run that stopped because
     * it was out of rewinds says so.
     */
    public int maxRewinds() {
        return maxRewinds;
    }
}
