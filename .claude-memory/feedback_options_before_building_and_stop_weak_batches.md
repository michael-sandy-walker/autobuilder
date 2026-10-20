---
name: feedback-options-before-building-and-stop-weak-batches
description: Lay out the option space before building a design; stop a batch as soon as a run shows the approach is weak instead of finishing it for "pattern" evidence.
metadata:
  type: feedback
---

**Two rules from the user, 2026-10-08 (after field50/field51, skeleton first):**

1. **Do not keep a batch running on an approach a run has already shown to be suboptimal.** The user, verbatim:
   "I never asked for this ... That if the skeleton-first was that suboptimal, to continue the runs." Earlier the same
   day: "I don't think, that showing a pattern here is necessary. The current behaviour speaks for itself."
   **Why:** a batch on a visibly weak approach spends hours of the box and the user's attention to confirm what one
   run already showed; "n is small, wait for runs 2 and 3" is not a reason when the behaviour itself is the finding.
   **How to apply:** when a run's behaviour clearly shows the approach falls short (not just a lower score), stop the
   batch, report what it showed, and bring options -- do not "let it finish for completeness". This refines
   [[feedback-runs-are-variant-by-design]]: variance argues against over-reading a SCORE, not against acting on a
   plainly visible mechanism failure.

2. **Before building a design, present the option space -- several genuinely different ways to do it -- and let
   the user choose.** The user: "the skeleton-first approach could have been done differently ... derived as stubs by
   brute-force compilation ... could have been done by deriving a chain more deductively ... the problem of only
   solving part of a body could have been done differently, by inheriting mechanisms of diff algorithms, ASTs ...
   there are lots of more options than we just have implemented."
   **Why:** I built the first workable design (compile, read errors, stub, repeat) and presented it as THE design;
   the user's "yes" was to the idea, not to that one mechanism.
   **How to apply:** for any non-trivial capability, name 2-4 distinct mechanisms with their trade-offs (and what each
   would need), recommend one, and build only after the user picks. Related: [[feedback-flag-ceremony-cost-early]],
   [[feedback-ship-full-scope]].

3. **Options must be ALTERNATIVES, not stages of one plan (user, 2026-10-20).** Asked how to merge goal trees and
   criteria across lineages, I offered "one umbrella ticket / un-defer T4-CN and T4-CM / interface first" as three
   options; the user: "Aren't those options one plan as a whole?" -- they were (matching, view and merge are stages
   of one operation), and the result was filed as one ticket with stages (`T4-EC`).
   **How to apply:** before posing options, check each pair: could both be done, one after the other, toward the same
   end? Then they are stages -- present them as an ordered plan and ask only about what genuinely competes (which
   stage first, which mechanism for a stage).
