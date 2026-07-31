package com.walpro.autobuilder.core.mutation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * T2-EY: run-level record of which proposals were already rejected, so an identical one is refused
 * instead of re-evaluated.
 *
 * <p><b>The gap this closes.</b> A rejection carries its reason into the next evolution prompt
 * (T4-BX's relay, which demonstrably works — the words are there). Nothing converts that reason
 * into a constraint on what the next proposal may <em>contain</em>. The prose is context; the
 * generator is free to ignore it, and does. This ledger is the constraint: a proposal whose content
 * is byte-identical to one this run already rejected does not reach the evaluator at all.
 *
 * <p><b>The safety condition, and why it is exactly this one.</b> An identical proposal is not
 * always still wrong — a mutation that failed to compile before a missing type landed may compile
 * afterwards. The only thing that can have changed the verdict is a merge: nothing else alters the
 * tree the proposal is judged against. So a repeat is refused only when <em>no merge has happened
 * since the rejection it repeats</em>. Measured over the 33 run corpora under
 * {@code /tmp/preflight_runs}: 39 duplicate rejections, of which 38 had no intervening merge and 1
 * did. Without the condition that one would have been refused wrongly; with it, none are.
 *
 * <p><b>Scale.</b> Those 39 are 8.9% of the 438 rejections in that corpus and occur in 15 of the 33
 * runs, so this is a recurring loss rather than one run's quirk. Each refusal saves an evaluation
 * call — the agent that consumes ~72% of a run's prompt tokens — and returns the cycle to work that
 * has not already been judged.
 *
 * <p>State is process-local and therefore naturally per-run, the same lifecycle as
 * {@link com.walpro.autobuilder.core.build.RecurringDefectTracker}. Nothing here is persisted: a
 * verdict about a proposal is only meaningful against the tree that produced it.
 */
@Slf4j
@Component
public class RejectedProposalLedger {

    /**
     * Monotonic tick over the events that can change a verdict. Only rejections and merges advance
     * it, because only those two change what a proposal would be judged against.
     */
    private long sequence;

    /** The tick of the most recent merge, or 0 when this run has merged nothing yet. */
    private long lastMergeTick;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** How many repeats this ledger has refused — the measurement half of the ticket. */
    private int refusals;

    /**
     * Record that {@code fingerprint} was rejected, with the authored reason that settled it.
     *
     * @param reason the rejection reason as authored, relayed verbatim when a repeat is refused so
     *               the generator is told the same thing rather than a paraphrase of it
     */
    public synchronized void recordRejection(String fingerprint, String reason, String subgoalId) {
        if (fingerprint == null || fingerprint.isBlank()) return;
        sequence++;
        Entry entry = entries.computeIfAbsent(fingerprint, f -> new Entry());
        entry.rejections++;
        entry.lastRejectionTick = sequence;
        if (reason != null && !reason.isBlank()) entry.reason = reason.trim();
        if (subgoalId != null && !subgoalId.isBlank()) entry.subgoalIds.add(subgoalId);
    }

    /**
     * Record that a mutation merged. This is what makes an earlier verdict revisable: the tree the
     * next proposal is judged against is no longer the one that produced the rejection.
     */
    public synchronized void recordMerge() {
        sequence++;
        lastMergeTick = sequence;
    }

    /**
     * The prior verdict on {@code fingerprint} when it still stands, else empty.
     *
     * <p>Empty is "no opinion", never "this is fine" — a caller must not read an empty result as
     * approval. It is empty both for a proposal never seen and for one whose rejection a merge has
     * since made revisable.
     */
    public synchronized Optional<Repeat> standingRejection(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return Optional.empty();
        Entry entry = entries.get(fingerprint);
        if (entry == null || entry.rejections == 0) return Optional.empty();
        if (lastMergeTick >= entry.lastRejectionTick) return Optional.empty();
        return Optional.of(new Repeat(entry.rejections, entry.reason, entry.subgoalIds.size()));
    }

    /**
     * Note that a standing rejection was acted on. Kept separate from {@link #standingRejection} so
     * that consulting the ledger — which a readout or a test may do freely — cannot inflate the
     * count of what it actually refused.
     */
    public synchronized void noteRefusal(String fingerprint, String subgoalId) {
        refusals++;
        recordRejection(fingerprint, null, subgoalId);
    }

    /** Per-run totals for the summary line: how often the loop re-proposed what it had been refused. */
    public synchronized Readout readout() {
        int repeatedProposals = 0;
        int worst = 0;
        for (Entry e : entries.values()) {
            if (e.rejections > 1) repeatedProposals++;
            worst = Math.max(worst, e.rejections);
        }
        return new Readout(entries.size(), repeatedProposals, refusals, worst);
    }

    /** Drops all state. For tests; production state is per-process and naturally per-run. */
    public synchronized void clear() {
        entries.clear();
        sequence = 0;
        lastMergeTick = 0;
        refusals = 0;
    }

    /**
     * A standing prior verdict.
     *
     * @param priorRejections how many times this exact content has been rejected, including the
     *                        rejection being repeated — 2 means the loop is on its second attempt
     *                        at something already refused once
     * @param reason          the authored reason, for relay
     * @param subgoalSpan     how many distinct subgoals proposed it; &gt;1 means the repetition is
     *                        surviving replanning, which makes it the plan's problem and not the
     *                        mutation's
     */
    public record Repeat(int priorRejections, String reason, int subgoalSpan) {}

    /**
     * @param distinctRejected  distinct proposals rejected this run
     * @param repeatedProposals how many of those were proposed more than once
     * @param refusals          how many repeats were refused before reaching the evaluator
     * @param worstRepeatCount  the most times one proposal was rejected
     */
    public record Readout(int distinctRejected, int repeatedProposals, int refusals, int worstRepeatCount) {
        public boolean isEmpty() { return distinctRejected == 0; }
    }

    private static final class Entry {
        int rejections;
        long lastRejectionTick;
        String reason;
        final Set<String> subgoalIds = new HashSet<>();
    }
}
