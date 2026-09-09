---
name: feedback-baseline-before-accuracy
description: "Three ways a predictor-hunt lies: an unstated baseline, a best-of-N search reported as one test, and extremes-only comparison. All three hit T6-R in one sitting."
metadata:
  node_type: memory
  type: feedback
---

**Before reporting that a feature predicts an outcome, three numbers must accompany
it, and T6-R (2026-09-10) hit all three failure modes in a single analysis.**

**1. The baseline, because accuracy without it is meaningless.** With 19 good runs
against 5 bad, *"always say good"* already scores **79%**. Four features landed at
exactly 79% and would have been reported as "79% accurate" by anyone reading an
accuracy column. **Report lift over the majority-class rate, never raw accuracy**,
and say what the baseline is so a reader can check.

**2. The permutation p, because best-of-N is not one test.** Trying eleven features
and reporting the winner inflates the winner. The honest instrument shuffles the
OUTCOME and refits the ENTIRE search, then asks how often chance reaches the
observed best. T6-R's best graph feature scored 92% and came out at **p = 0.091 --
not significant**. Without that test it would have shipped as a discriminator.

**3. Whether the middle was dropped, because extremes flatter everything.**
Comparing only the best and worst runs makes every feature look sharp.
**`score_max` had the strongest correlation of anything measured (rho +0.54) and a
leave-one-out accuracy of 64%, BELOW the 79% baseline** -- it tracked the middle of
the distribution and carried nothing where the two populations actually differ.

**A correlation and a discriminator are different claims.** A feature can have the
best rho in the study and be worse than a constant at the question being asked, and
nothing but a holdout will show it.

**Why:** this project keeps asking "does X predict a good run?", and every such
question is an unbalanced, small-n, many-features problem -- the exact shape where
an accuracy number is most flattering and least informative.

**How to apply:** state the population and the filter first ([[feedback-verify-population-before-claim]]);
name every feature tried, not the ones that worked; print baseline, lift, and
permutation p together; and check any winner against a holdout before believing it.
A negative result reported with these three numbers is worth more than a positive
one without them. Related: [[feedback-dont-group-defects-by-vocabulary]] is the same
discipline for mechanisms rather than numbers.
