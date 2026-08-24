---
name: feedback-dont-evaluate-a-build-with-older-runs
description: A run window is only a rate when the build is constant across it — this session's history evaluates the previous build, never the one it just fixed.
metadata:
  type: feedback
---

**Do not evaluate the current build using runs that predate it.** The history of the session doing
the fixing is evidence about the system as it *was*. Whether the fixes worked is the *next*
session's measurement.

**Why:** stated by the user on 2026-08-24, after I reported "the last ten runs average 67% against
a 75% target" as if it described where the loop stands. It did not. Both zeros in that window
predate the changes that address them — one failed on the undeclared-dependency shape whose
classifier fix shipped hours *later the same day*. A ten-run window spanning a code change measures
a mixture of builds, so the number is real as history and meaningless as a current rate.

**How to apply:**
- Before quoting a multi-run statistic, ask what changed *inside* the window. If the build moved,
  say so and report the post-change population separately, even when that population is n=1.
- "Ten consecutive runs" clauses mean ten runs on an *unchanged* build. A mixed window quietly
  substitutes a different, easier question.
- Old figures stay quotable as history — label them that way, never as the current rate.
- A zero-run whose cause has since been fixed is not evidence the loop still fails that way; it is
  a case to re-test forward, and it should be named as pending rather than counted against the fix.

Distinct from [[feedback-verify-population-before-claim]] (scan the WHOLE population, not a recency
cache): that one is about the population being complete, this one about it being the RIGHT one.
Related: [[feedback-replay-over-batches]] — a deterministic replay over a fixed corpus does not have
this problem, which is another reason to prefer it for anything that is not an occurrence question.
