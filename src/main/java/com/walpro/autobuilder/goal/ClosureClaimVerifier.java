package com.walpro.autobuilder.goal;

import java.util.List;
import java.util.Optional;

/**
 * T4-CU: asks whether a work item's acceptance criteria actually hold, at the moment the loop
 * claims they do.
 *
 * <p><b>The defect this closes.</b> A leaf closes on a model's reading of the source, and
 * {@code transitionGoal(COMPLETED)} then banks every declared criterion as {@code true} whether or
 * not it holds. Measured in run {@code 2026-07-30_21-25-43}: the subgoal "Implement input
 * validation for Book model with title length and ISBN format requirements" closed COMPLETED with
 * criteria stating that {@code POST /books} answers 400 on a too-long title. No validation trigger
 * existed anywhere in the artifact, the endpoint answered 201, and a structural fact saying exactly
 * that was in the prompt twelve times. The closure was false, and it cost more than a wrong tick:
 * the probe later caught the same defect, raised a work item, decomposed it into that very subgoal
 * — which was already marked COMPLETED — and the parent was retired {@code exhausted after 1 cycles
 * without merge}. The loop abandoned a repair it had correctly diagnosed because a leaf lied to it.
 *
 * <p><b>Why this is an interface and not a method on the planner.</b> Executing a criterion means
 * knowing what kind of thing the target is — a request and a response status for one that serves,
 * an invocation and an exit status for one that does not. The goal tree must not learn that; it is
 * already the largest single accumulation of target-domain knowledge in the codebase (T4-DD's
 * inventory). So the planner states the question in its own terms — "is this claim refuted?" — and
 * an implementation that understands the target answers it. This is the first real exercise of the
 * T4-DD seam: requirement extraction and execution live behind it, and nothing about endpoints,
 * statuses or command words crosses back.
 *
 * <p><b>It refutes; it never confirms.</b> Every implementation must return empty unless it has
 * positive evidence that a criterion is false. Absence of evidence — an unparseable criterion, an
 * artifact that will not build or boot, a target it does not understand — is not a refutation, and
 * closure proceeds exactly as it did before. That asymmetry is deliberate: blocking on abstention
 * would stall every work item whose criteria are structural, which is two thirds of them (measured:
 * 150 of 452 closed subgoals across 32 runs carry a criterion that can be executed at all). The
 * check therefore cannot make closure stricter than the evidence it actually has.
 */
public interface ClosureClaimVerifier {

    /**
     * @param goalId   the work item about to be closed, for logging and attribution
     * @param criteria its declared acceptance criteria, verbatim
     * @param specText the specification as stated, or null when the caller has none. Still not
     *                 target-domain knowledge crossing back into the planner: the planner passes
     *                 the words it was given and forms no opinion about them. An implementation
     *                 needs them because a criterion naming a declared operation rather than a
     *                 literal invocation ("count &lt;file&gt; prints … as a bare integer") can only
     *                 be turned into a probe against the surface the specification itself states.
     *                 T4-DK: passing the empty string here made every criterion on a target with no
     *                 network surface unexercisable, so the check abstained at every closure and a
     *                 model reading source was the only evidence left at the completion edge.
     * @return the contradiction when the artifact demonstrably refutes one of {@code criteria} —
     *         its description phrased so the loop can act on it, and the subset of
     *         {@code criteria} that was refuted where the implementation can attribute it — or
     *         empty when it does not, or when nothing could be checked. Empty is "no opinion",
     *         never "verified".
     *         <p>T4-CU: this used to be an {@code Optional<String>}. An implementation probes the
     *         criteria one at a time and so knows which failed; flattening that to one sentence
     *         left the consumer unable to name the criterion without matching prose back to it,
     *         which blocked the repair work item, forced T2-FB to drop a whole criteria bank, and
     *         made refusals uncountable. See {@link ClaimRefutation}.
     */
    Optional<ClaimRefutation> refute(String goalId, List<String> criteria, String specText);
}
