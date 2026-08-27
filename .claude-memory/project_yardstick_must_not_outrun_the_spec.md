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

Related: [[project-smoke-score-is-chained]] (compare failing SETS, never totals — this gap
presents as five failures from one cause).
