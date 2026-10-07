---
name: project-yardstick-must-not-outrun-the-spec
description: "A yardstick may only assert what the specification states. Twice now a check has graded the loop on something nobody told it — T6-P (malformed JSON, 7 checks) and the create status (201 vs 200, 5 checks) — and both looked like loop defects."
metadata:
  node_type: memory
  type: project
---

**The rule: every check a yardstick makes must correspond to something the specification
states.** Where it does not, the check grades the loop on a convention nobody told it about, and
the result is indistinguishable from a defect.

**Two instances, both initially misread as the loop getting worse:**

- **`T6-P` (2026-08-24):** `smoke23.sh` interpolated ids unquoted, so an artifact choosing UUID ids
  received malformed JSON and answered 400 to every create. Worth **7 checks** on one run
  (13/23 → 20/23). The artifact was correct.
- **The create status (2026-08-27):** the suite asserts **201** on a create; the specification
  stated no success status at all. Worth **5 checks** (checks 1, 8, 13, 17, 18 are one defect).
  Two runs on the same build scored 18/23 and 23/23 purely on which status the model chose.

**Why it is not self-correcting, and this is the part that matters.** An unstated requirement
cannot be repaired in-loop, because the chain is designed to refuse exactly that: the loop authors
the criterion, `stampCriteriaBinding` classifies it **AUTHORED** (it is not grounded in the spec),
and `specBoundBehaviouralCriteria()` excludes AUTHORED criteria from the completion gate. **That
exclusion is correct** — it is what stops the loop blocking on requirements it invented, the
failure `T1-FW`'s 404 case demonstrates. So the requirement is simultaneously graded by the
yardstick and unverifiable by the gate.

**How to check it:** read every assertion the yardstick makes and find the sentence in the seed
that licenses it. The audit is one pass and it found the whole gap in a single sitting. Do it
whenever either side changes, and before attributing a score change to the loop.

**How to fix it:** state the requirement in the SPECIFICATION, not by relaxing the yardstick.
Stating it does not lower the bar — the artifact must still satisfy it — and it makes the
requirement spec-bound, so the gate that already has the machinery can finally verify it.

**⚠ Cost to accept knowingly:** changing a seed changes the target, so scores stop being strictly
comparable with earlier runs on that axis. Say so when it happens
([[feedback-dont-evaluate-a-build-with-older-runs]]). The alternative is a permanent coin flip in
the measurement.

**✅ CONFIRMED END TO END 2026-08-27, by reading the field the GATE reads.** Stating the statuses in
the seed flipped the stamp on the same criterion: run `2026-08-26_21-36-31` had *"POST /books
returns HTTP 201…"* stamped **AUTHORED**; run `2026-08-27_12-40-17`, after the change, stamps it
**SPEC**. The create was then genuinely probed rather than body-refused — the probe's own derived
body `{"title":"value","isbn":"0000000000000"}` answers **201** on the new artifact and **200** on
the old one, which is the contradiction the gate could not see before. Both runs scored 23/23; the
difference is that the second one CHECKED it.

**⚠ A METHOD TRAP THAT COST TWO WRONG READINGS IN ONE SITTING:** the goal stack carries the
criteria in more than one place, and only `metadata.acceptanceCriteria` is the list
`specBoundBehaviouralCriteria()` consumes — the other copy has no `criteriaBinding`, so a walk that
matches any "criteria"-ish key reports every criterion as unstamped and therefore SPEC. That is
what made a correct diagnosis look refuted. **Read the field the production code reads, by name.**

Related: [[project-smoke-score-is-chained]] (compare failing SETS, never totals — this gap
presents as five failures from one cause).

**AND IT LAGS, TOO — 2026-08-28.** The same audit obligation runs in the other direction. Run
`2026-08-28_16-35-37` was scored with `smoke_cli.sh` (12 checks) while its seed is the QUOTED-fields
spec, which states that a quoted field may contain commas and newlines. The suite never asks that
question, returned **12/12**, and made a CORRECT closure refusal look over-tight — the artifact
rescored **18/19** on `smoke_cli_quoted.sh`, with the single failing check being exactly the
requirement the loop had refused to call done. **A perfect score on the wrong suite is worse than a
low score on the right one**, because it reads as evidence against a mechanism that was working.
**Before quoting any score: check `PREFLIGHT_SMOKE` against that target's `bootstrap/seed.txt`.**
The outcome ledger's row for that run says 12/12 and is misleading.


**2026-10-07, both directions on ladder-rows (T6-T).** (1) The SEED outran the tests: the ladder generator wrote the original commit's changed classes as "The classes to change are ...", the obligations made "X is modified" binding, and the closure refused a correct 29/29 tree over it (field41 run 2) -- fixed by making that line orientation (v3). (2) Changing the seed broke the YARDSTICK, which parsed the removed line: every tree scored 0/1 and early_stop paused a correct run (field42). **Before editing a toy case's seed, grep the yardstick scripts for what they read from it, and score a known-good tree on the edited case before launching a batch.**
