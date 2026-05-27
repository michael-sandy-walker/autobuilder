---
name: feedback-one-invariant-one-home
description: "When two patches at two enforcement sites would encode the same invariant, that's the symptom of a missing capability — file one capability ticket owning the invariant, not two patches catching it twice"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a616c347-0dd6-4ed6-a22b-cb68d4638f05
---

When proposing tickets after a run analysis, if the same invariant ("no two files declare the same simple name in different packages", "no goal transitions to FULFILLED without a passing critic", etc.) needs catching at two or more enforcement sites, **that is the symptom**. The right shape is one capability ticket that owns the invariant once and is consulted from each site, not two narrow patches that each compensate for the missing capability. "Defence in depth" is the rationalisation that hides the duplication.

**Why:** Surfaced 2026-05-24 in the `behaviour-2026-05-24_22-21-21.ndjson` run analysis. I filed `T1-ES` (fix `MergeAgent`'s broken `!fileExistsInOutput` guard ordering) *and* a narrow `T4-BH` (add a fragmentation pre-check to `DeferredMutationRestager`). Both were catching the same invariant — "no same-simple-name-different-package collision against current output" — at two sites that the missing capability would have consulted from one place. The user pushed back: "Why are they separate?" Consolidated into a single `T4-BH` (shared `PackageFragmentationCheck` called from `CompileProbeService` upstream of probe writes; merge-time guard re-pointed at it; restage path covered transitively). Two patches replaced by one capability + two deletes; cross-references made one-way; cognitive surface shrank. This is the sibling rule to [[feedback-ship-full-scope]] — that rule says "don't leave old code when you build the new capability"; this rule says "don't pre-emptively encode the duplication by filing two patches when one capability covers both sites".

**How to apply:** When drafting tickets after run analysis, list the enforcement sites first (probe, merge, ledger restage, …) and ask: are these distinct invariants, or one invariant with multiple call sites? If the latter, the ticket is the *check itself* — extracted, named, owned by one module, called from every site, with the would-have-been-separate patches becoming `delete` or `rewire` line-items in the same scope. The test is "could one new helper subsume all the would-be patches?" — if yes, build the helper and rewire; if no, the patches really are independent and file them separately. The framing question that surfaces this: **"where does this invariant live?"** A patch can't answer that; a capability can.
