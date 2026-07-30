---
name: feedback-short-field-run-after-context-change
description: A green suite and a clean offline replay cannot see integration defects — run a short live run after any change that touches the cycle context or a persisted record
metadata: 
  node_type: memory
  type: feedback
  originSessionId: df54f45e-184f-4e0a-8554-38e62ac1303c
  modified: 2026-07-30T16:40:22.161Z
---

After shipping a change that adds to `CycleContextData`, to anything persisted per cycle, or to any surface the loop reads back, do a **short live run and read what the mechanism prints** before starting a long validation run. Minutes are enough.

**Why:** on 2026-07-30 T4-DA shipped with a green 2325-test suite and an offline replay validated against three real corpora. Two defects survived both, and each was found within minutes of a real run:

- The readout counted the ROOT goal as covering the seed, because the root's description *is* the seed. It printed `0 unclaimed` and would have answered "no drift" forever. The offline prototype had excluded the root by array index; the production call used `getAllGoals()`, which does not.
- A derived accessor (`isEmpty()`) on the new record serialized as a property the canonical constructor could not read, so `meta.json` failed to load and `MutationSnapshotService` rewrote it with `mutationState=REJECTED` — **9 mutations accepted, 0 merged, 38 persist failures.** The run could not merge anything.

Neither defect was in the logic under test. Both were in how the new code met the surrounding system: the real call site, and the real serialization path. Unit tests construct their own inputs, and replay drives the component directly — so by construction neither exercises the seam.

**How to apply:** ship, then run live for ~10 minutes and grep the log for the mechanism's own log line and for `persist_failed` / `REJECTED` / merge counts. If a mechanism cannot be seen working in the field within a few cycles, that is the finding. This does not weaken [[feedback-replay-over-batches]] — replay stays the instrument for deciding whether a change HELPS, and small live batches still cannot adjudicate that. This is about whether the change is WIRED, which is a different question and one replay is blind to. Where a convention exists but nothing enforces it (four sibling records carried `@JsonIgnore`; a closed ticket named the same defect class), add the guard keyed on the type rather than on a remembered list, and verify it fails on the real defect before trusting it.
