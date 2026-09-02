package com.walpro.autobuilder.core.checkpoint;

/**
 * T4-DQ: the three dispositions a piece of run state can have when the loop checkpoints itself.
 *
 * <p>The bucket is a declaration, not an omission. "Persist the in-memory holders" is not a
 * complete instruction — a third of the loop's mutable state is <em>deliberately</em> derived per
 * cycle, and restoring it would reintroduce bugs earlier tickets closed. So a holder says which
 * of these it is, and {@code StateHolderRegistrationContractTest} fails the build for a holder
 * that says nothing.
 */
public enum StateBucket {

    /**
     * Decision-bearing state that must be captured. It gates behaviour, so a wrong restore
     * silently changes decisions — the dangerous failure mode, not the obvious one. These
     * holders implement {@link CheckpointableState}.
     */
    CAPTURE,

    /**
     * State that already has a durable home of its own — a store file, the goal stack, the
     * orchestration graph, the per-cycle tree snapshot. The checkpoint references that home
     * rather than copying it, so there is no second model to diverge from.
     */
    ALREADY_PERSISTED,

    /**
     * State a restore must NOT put back. Two kinds live here, and both belong.
     *
     * <p>The first is derived: memoized lookups, session accounting, live subscriber lists,
     * environment probes — recomputed per cycle or per run and never carried forward.
     * {@code ContextLineageTracker} says it outright about its own carriers: "per-cycle
     * derived-fact carriers — never carried forward".
     *
     * <p>The second is what a rewind may not refund. Wall clock and the run budget it funds, and
     * the rewind budget itself: rewound time IS spent, and a run that could restore its own
     * allowance of rewinds would have an unlimited supply of them. State can be perfectly real,
     * perfectly durable, and still belong here.
     *
     * <p><b>Restoring one of these is a bug, not an omission.</b> The category is declarable so
     * the next author does not re-add them as a "fix".
     */
    DERIVED_DO_NOT_RESTORE
}
