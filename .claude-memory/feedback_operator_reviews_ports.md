---
name: feedback-operator-reviews-ports
description: loop-built ticket code reaches src/ only after the operator reviews a packet (loop vs blind reference vs proposed port) and approves; the session never ports on its own review
metadata:
  type: feedback
---
A solution the loop builds for a ticket toy case is ported into Autobuilder's `src/` only after the user has
reviewed it and approved. The session prepares, never decides:
1. The run's artifact is archived durably (`preflight.sh` writes `run-archive/<run>/artifact-src.tar.gz`), and
   the ticket gets a tag line `LOOP-BUILT: <target> run <run>, yardstick <n/m>, ... -- PORT: <state>`
   (`awaiting-review | ported-unreviewed | approved | ported | declined`); `scripts/next_work.py` lists the
   open ones under "LOOP-BUILT: WAITING FOR THE OPERATOR".
2. A BLIND reference: `process/blind_reference_workspace.sh <target>` + a fresh agent that sees only the
   pristine module, the seed and the spec (instructed, not enforced -- say so in the packet).
3. `process/port_review.py` writes `process/ports/<target>/REVIEW.md`: the four versions (origin, loop, reference,
   port), suite results, size/churn/tables, and behaviour beyond the spec (a differential over archived inputs
   plus labelled probes). No opinion in it.
4. A second fresh agent writes `ASSESSMENT.md` (better/worse) -- the session that wrote the spec and the port
   does not judge its own work.
5. The user decides in the packet's Decision section; only then the port (or a revert).

**Why:** 2026-09-27 the user: references "should exist, too, so I can review it myself", "non-biased (separated
from the toy case code)", "a summary section what's better/worse", "the review stage should be done by me (at
least initially), which then needs approval, followed by the decision if the porting goes through or not".
Three ports had gone in on the session's own review (`27459ebc`, `fbd037b1`, `213af020`); they are tagged
`ported-unreviewed` and get retro packets.

**How to apply:** never commit a loop-built port without a recorded approval; "at least initially" means the
user may later relax this -- evidence for that would be several packets approved without changes.
Related: [[feedback-no-auto-commit]], [[project-invented-facts-have-authorities]].
