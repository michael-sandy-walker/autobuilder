package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.core.checkpoint.CheckpointableState;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

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
 * <p><b>The safety condition, and why it is derived rather than tracked.</b> An identical proposal
 * is not always still wrong — a mutation that failed to compile before a missing type landed may
 * compile afterwards. What can have changed the verdict is a change to the tree the proposal is
 * judged against, so a verdict is keyed on the state of that tree and expires by itself the moment
 * it moves.
 *
 * <p>The obvious cheaper rule — "a rejection stands until the next merge" — is <b>wrong</b>, and
 * measurably so. A merge is not the only writer: the compile-fix injector's files are deliberately
 * <em>preserved</em> through the rejection rollback (the T4-AQ carve-out, so a bootstrapped pom or
 * a skeleton is not stranded), and consolidation writes too. Measured over the 33 run corpora: of
 * 39 duplicate rejections, 38 have no intervening merge but only 34 have no intervening injector
 * write — so a merge-only rule would refuse 4 proposals the tree had since made viable. Keying on
 * the tree catches every writer without anyone having to enumerate them, which is the part that
 * would rot: a writer added next month is covered by construction, and a missed one would mean a
 * wrong refusal.
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
@StateHolder(value = StateBucket.CAPTURE,
        note = "a standing rejection refuses a repeat proposal before the evaluator sees it")
public class RejectedProposalLedger implements CheckpointableState {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** How many repeats this ledger has refused — the measurement half of the ticket. */
    private int refusals;

    /**
     * Record that {@code proposal} was rejected against {@code treeState}.
     *
     * @param treeState a fingerprint of the tree the proposal was judged against. The verdict is
     *                  scoped to it: once the tree moves, this entry stops standing, whichever
     *                  writer moved it.
     * @param reason    the rejection reason as authored, relayed verbatim when a repeat is refused
     *                  so the generator is told the same thing rather than a paraphrase of it
     */
    public synchronized void recordRejection(String proposal, String treeState, String reason, String subgoalId) {
        if (proposal == null || proposal.isBlank()) return;
        Entry entry = entries.computeIfAbsent(proposal, f -> new Entry());
        entry.rejections++;
        entry.treeState = treeState;
        if (reason != null && !reason.isBlank()) entry.reason = reason.trim();
        if (subgoalId != null && !subgoalId.isBlank()) entry.subgoalIds.add(subgoalId);
    }

    /**
     * The prior verdict on {@code proposal} when it still stands against {@code treeState}, else
     * empty.
     *
     * <p>Empty is "no opinion", never "this is fine" — a caller must not read an empty result as
     * approval. It is empty for a proposal never seen, and for one whose verdict was reached
     * against a different tree.
     *
     * <p>An unknown tree state also yields empty. A verdict that cannot be scoped is not a verdict
     * this ledger will act on: refusing on an unknown base is exactly the wrong-refusal case the
     * scoping exists to prevent.
     */
    public synchronized Optional<Repeat> standingRejection(String proposal, String treeState) {
        if (proposal == null || proposal.isBlank()) return Optional.empty();
        if (treeState == null || treeState.isBlank()) return Optional.empty();
        Entry entry = entries.get(proposal);
        if (entry == null || entry.rejections == 0) return Optional.empty();
        if (!treeState.equals(entry.treeState)) return Optional.empty();
        return Optional.of(new Repeat(entry.rejections, entry.reason, entry.subgoalIds.size()));
    }

    /**
     * Note that a standing rejection was acted on. Kept separate from {@link #standingRejection} so
     * that consulting the ledger — which a readout or a test may do freely — cannot inflate the
     * count of what it actually refused.
     */
    public synchronized void noteRefusal(String proposal, String treeState, String subgoalId) {
        refusals++;
        recordRejection(proposal, treeState, null, subgoalId);
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

    // ---------------------------------------------------------------------------------
    // T4-DQ: checkpoint / rewind
    // ---------------------------------------------------------------------------------

    /**
     * The standing verdicts and how many repeats they have refused. Each verdict carries the tree
     * state it was reached against and expires when the tree moves — which a rewind also moves, so
     * restoring the verdicts alongside the tree keeps the pairing that makes them meaningful.
     */
    public record Snapshot(java.util.Map<String, EntryState> entries, int refusals) {}

    /** One proposal's standing rejection. */
    public record EntryState(int rejections, String treeState, String reason,
                             java.util.Set<String> subgoalIds) {}

    @Override
    public String stateKey() {
        return "rejectedProposals";
    }

    @Override
    public synchronized Object captureState() {
        java.util.Map<String, EntryState> flat = new java.util.LinkedHashMap<>();
        entries.forEach((k, e) -> flat.put(k, new EntryState(
                e.rejections, e.treeState, e.reason, new java.util.LinkedHashSet<>(e.subgoalIds))));
        return new Snapshot(flat, refusals);
    }

    @Override
    public synchronized void restoreState(com.fasterxml.jackson.databind.JsonNode state,
                                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        entries.clear();
        refusals = 0;
        if (state == null || state.isNull()) return;
        Snapshot snapshot = mapper.convertValue(state, Snapshot.class);
        if (snapshot.entries() != null) {
            snapshot.entries().forEach((k, v) -> {
                Entry e = new Entry();
                e.rejections = v.rejections();
                e.treeState = v.treeState();
                e.reason = v.reason();
                if (v.subgoalIds() != null) e.subgoalIds.addAll(v.subgoalIds());
                entries.put(k, e);
            });
        }
        refusals = snapshot.refusals();
    }

    private static final class Entry {
        int rejections;
        /** The tree this verdict was reached against; the verdict expires when the tree moves. */
        String treeState;
        String reason;
        final Set<String> subgoalIds = new HashSet<>();
    }
}
