---
name: feedback-compare-against-the-last-good-build
description: Judge a new line of work against the last GOOD build's runs on the same target, never against its own predecessor; state that baseline before the first run.
metadata:
  type: feedback
---

A new mechanism is measured against the runs of the last build known to be good on the same target -- not against the previous build of the same line of work.

**Why:** 2026-10-09. The skeleton line (stubs, skeleton items, member merge; `a80e2c74`..`23db7c4a`) was built to break a compile wall that `3ca592b6` had already broken two days earlier. I compared field53 (46/51) only with field52 (44/51) -- both skeleton builds -- and reported progress. The user pointed out that runs before the line had scored 47-48. Checked against the five 10-07 runs on `fa60d2fa`: every one of them reached 1 failing test (smoke 45-48); every skeleton run floored at 4-6 failing (41-46). The line was reverted (8 commits, ~2,800 lines). The same lapse had let "trace every failing test" (`ecd2bcdf`) through one batch earlier.

**How to apply:** before the first run of a new mechanism, name the reference: the build hash, its runs on this target, their scores AND their failing check sets, and the best the loop's own measure reached (for a declared suite: the fewest failing tests). Put that line in the batch script's header comment. A result is "better" only against that reference; "better than the last batch" is a statement about the line, not about the product. When the reference runs are not on disk, run the reference build first. Related: [[feedback-dont-evaluate-a-build-with-older-runs]], [[project-ticket-premises-expire]], [[feedback-options-before-building-and-stop-weak-batches]].
