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

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** One switch, in case backing out ever behaves worse in the field than dying did. */
    @Setter
    @Value("${autobuilder.rewind.on-grind-enabled:true}")
    private boolean enabled = true;

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

    /**
     * Called at the halt edge, once the brake has decided the run is grinding. Returns the result
     * when the loop went back instead, or empty when it should halt as before.
     *
     * @param atCycleId       the cycle the halt was about to happen in
     * @param cyclesInDrought how long the run has been stalled, for the trace
     */
    public Optional<RewindService.Result> considerRewindInsteadOfHalting(String atCycleId, int cyclesInDrought) {
        if (!enabled) return Optional.empty();
        if (rewindsUsed >= maxRewinds) {
            log.info("T4-DQ: grinding at {} but the run has already used its {} rewind(s) — halting as before.",
                    atCycleId, rewindsUsed);
            return Optional.empty();
        }
        // Strictly older than the last target, so a second rewind cannot re-walk the same stretch
        // from the same place.
        String before = lastTargetCycleId != null ? lastTargetCycleId : atCycleId;
        Optional<CheckpointRecord> target = rewindService.lastMergedBefore(before);
        if (target.isEmpty()) {
            log.info("T4-DQ: grinding at {} but no earlier checkpoint landed work — nothing better to return to, halting as before.",
                    atCycleId);
            return Optional.empty();
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
        log.warn("T4-DQ: {} cycles of grinding at {} — returned to {}, the last point that landed work "
                        + "(rewind {} of {}). The plan that led here stays in the graph, so the planner "
                        + "cannot re-propose it.",
                cyclesInDrought, atCycleId, record.cycleId(), rewindsUsed, maxRewinds);
        return Optional.of(result);
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
