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

**2026-09-21 — the build is not the only thing that has to be constant. THE TARGET DOES TOO, and this
corpus gained two mid-stream.** Asked whether 92 pooled runs were comparable, I checked and they were
not: `clitarget` entered around 2026-08-25 and `flightsim` around 08-28, and they look at their
artifact at very different rates (clitarget 0.711 package-gate visits per cycle against firestarter's
0.398). My pooled figure of "0.188 vs 0.500" was a blend of two populations. Re-run on firestarter
alone the same comparison reads **0.105 vs 0.458, a 4.4x gap** — the conclusion strengthened and every
number I had quoted was wrong.

**Targets are a harder axis than builds, because a target changes what a measurement MEANS, not just
its value.** `clitarget` is a non-serving command-line target: its boot gate is not asking the
question firestarter's is, and its yardstick has 19 checks against 23. Pooling those is
[[feedback-baseline-before-accuracy]]'s fourth lie — a denominator that means something different per
row — arriving through the population rather than the parse.

**This cost me twice in one session, which is what makes it worth writing down.** The same morning,
`scripts/failure_family.py` was found with firestarter's check count pinned into a regex, putting 23
of 96 scoring runs into a failure distribution. Same root cause: **the project became multi-target at
the end of August and the analysis habits did not follow.** Single-target instincts stay silently
correct for months and then quietly average two things.

**How to apply, added to the list above:**
- Before pooling ANY cross-run statistic, split by target first and look at the per-target values.
  Pool only if they agree; report them split if they do not. Do this before the build-window check,
  since it is cheaper and bites harder.
- State which axes you checked and which you did not. On this occasion three moved inside the window
  (targets, the T4-DQ rewind capturing the counter being measured, and `BootGate` changing on the
  3 newest runs) and one did not (the model swap landed 27 minutes before the earliest run — luck,
  not design).
- A statistic that survives the split is worth MORE, not less: here the headline held at 26%/22%/33%
  across targets while the rate it was built on did not.
