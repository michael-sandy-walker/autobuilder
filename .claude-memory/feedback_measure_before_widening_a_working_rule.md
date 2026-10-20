---
name: feedback-measure-before-widening-a-working-rule
description: When a run closes despite a known gap in a rule, do not widen the rule on that run's evidence; measure across runs whether the gap costs outcomes, then decide.
metadata:
  type: feedback
---

After field87 closed while the indentation-shift rule refused one non-uniformly re-nested hunk (it cost four failed builds), I proposed widening the rule to per-line re-nesting. The user (2026-10-19): "Why should we still change it if it already works?" -- and then chose "2 more runs please" with no code changes. The measurement (refusals by kind over field84-88: exact misses 4, uniform shifts applied in 10 answers, non-uniform refused 1) showed no case for widening; the stalls were the model's coverage of a multi-file change, which led to the rule that did matter (moved-nested-type rewrite).

**Why:** every widening of a rule that applies edits without exact text adds a way to apply a wrong edit silently; one run's cost in minutes is not evidence that the gap decides outcomes, and building on it is the activity-for-progress trap ([[feedback-activity-not-progress]]).

**How to apply:** when a gap shows up in a run that still succeeds, record it as evidence for later and propose measuring (classify the events by kind across several runs, tie them to outcomes) before proposing the widening. Widen only when the counts show the gap in runs that stall or fail. Related: [[feedback-replay-over-batches]], [[feedback-options-before-building-and-stop-weak-batches]].
