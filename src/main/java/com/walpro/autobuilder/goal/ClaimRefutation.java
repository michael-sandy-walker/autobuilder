package com.walpro.autobuilder.goal;

import java.util.List;

/**
 * T4-CU: what a {@link ClosureClaimVerifier} observed when the running artifact contradicted a
 * work item's acceptance criteria — the description it always carried, plus WHICH criteria were
 * refuted.
 *
 * <p><b>The gap this closes.</b> The port returned {@code Optional<String>}: one sentence. But an
 * implementation reaches that sentence by probing criteria individually, so it knows exactly which
 * one failed and discarded that at the return. Downstream, {@code GoalPlannerService} could
 * therefore not name the criterion, and its refusal branch said so in as many words: <i>"the
 * refutation arrives as prose from a port that does not name which criterion failed, and matching
 * it back to one would be code consuming prose for control flow."</i> That reasoning was right —
 * a regex over another component's prose is exactly the coupling this codebase refuses — so the
 * fix belongs here, at the boundary, and not at the consumer.
 *
 * <p><b>Three things were blocked by the one return type</b>, which is why this is a record rather
 * than a second parameter somewhere: the repair work item had no criterion to name, so T4-CU's own
 * field DoD was unreachable; T2-FB had to drop a subgoal's WHOLE banked criteria map because it
 * could not tell which entry the refutation belonged to; and nothing about a refusal was countable,
 * so "which gate refused this closure, and on what" existed only as unstructured log prose.
 *
 * <p><b>The T4-DD seam is unmoved, and that is the design check that matters.</b> The planner
 * PASSES the criteria in. Handing back the subset that was refuted returns only what the planner
 * already owns, so no target-domain knowledge crosses back: this says WHICH claim failed, never
 * what kind of thing it was or how it was exercised.
 *
 * <p><b>{@code refutedCriteria} may be empty, and empty is not "none".</b> A refutation that is not
 * attributable to a single criterion — the artifact never boots, a spec-named endpoint is
 * unreachable so every probe's setup fails — is real and must still block the closure. Consumers
 * therefore treat empty as "could not attribute" and fall back to the whole-bank behaviour that
 * predates this record, rather than reading it as "no criterion was at fault".
 *
 * @param description      what was observed, phrased so the loop can act on it. Unchanged in
 *                         content and wording from what the port used to return, so every reader
 *                         that only wants the prose keeps working.
 * @param refutedCriteria  the verbatim criterion texts the artifact contradicted, as they were
 *                         passed in. Empty when the refutation could not be attributed to
 *                         particular criteria.
 * @param claim            which claim the refutation makes, when the gate knows it better than
 *                         "criteria or not" — a {@code BlockerTypes} claim constant. Null for the
 *                         default, where attribution alone decides the stamp.
 */
public record ClaimRefutation(String description, List<String> refutedCriteria, String claim) {

    public ClaimRefutation {
        refutedCriteria = refutedCriteria == null ? List.of() : List.copyOf(refutedCriteria);
    }

    public ClaimRefutation(String description, List<String> refutedCriteria) {
        this(description, refutedCriteria, null);
    }

    /**
     * T4-DW: the packaged archive cannot be launched because it declares no entry point. Its own
     * claim, so the item it raises is retired by evidence about the archive rather than by a
     * serving launch that may never come.
     */
    public static ClaimRefutation archiveEntryPoint(String description) {
        return new ClaimRefutation(description, List.of(),
                com.walpro.autobuilder.core.build.BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT);
    }

    /**
     * A refutation nothing could attribute to a named criterion — a boot failure, or an endpoint
     * fault that makes every probe's setup fail for a reason that is not the criterion's own.
     */
    public static ClaimRefutation unattributed(String description) {
        return new ClaimRefutation(description, List.of());
    }

    /** True when a consumer can act on named criteria rather than on the whole claim. */
    public boolean isAttributed() {
        return !refutedCriteria.isEmpty();
    }
}
