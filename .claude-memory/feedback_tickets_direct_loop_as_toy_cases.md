---
name: feedback-tickets-direct-loop-as-toy-cases
description: roadmap tickets are implemented directly by the session; the loop's own ability is tested separately on versioned toy cases and its output is measured, not ported
metadata:
  type: feedback
---
Implement roadmap tickets directly. Do not route a ticket through the loop (seed -> toy case -> loop run -> port) to
get it built. A ticket may still become a toy case, as a CAPABILITY TEST: the loop's output is measured against our own
implementation of the ticket, not ported. Toy cases are versioned: when the code they are cut from moves on, update the
toy case as a new version with a changelog entry, and let every run record the version it faced.

**Why:** 2026-09-27 the user: "I don't think we should mix our roadmap work of Autobuilder with the work Autobuilder is
capable of doing itself on its own further for now ... Otherwise we would bottleneck the capabilities of the overall
development too much regarding the capabilities of the local model." And: "That's why we version the toy cases ... So we
also have a history what changed regarding the toy cases over time."

**How to apply:** a ticket's critical path never waits on a loop run; toy-case runs are measurements with a stated bar.
Porting a loop solution happens only when it is clearly better and someone proposes it (then the M-E review applies).
Process record: `meta_process.md` M-F. Related: [[feedback-operator-reviews-ports]], [[feedback-process-apart-from-product]].
