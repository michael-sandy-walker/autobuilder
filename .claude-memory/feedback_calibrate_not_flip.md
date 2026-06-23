---
name: feedback-calibrate-not-flip
description: "When a noisy result contradicts a mechanistically-grounded diagnosis, LOWER confidence and get a cleaner test — don't flip to the opposite confident claim. And resist single-cause over-commitment when the data keeps being multi-factor."
metadata:
  node_type: memory
  type: feedback
  originSessionId: 609aab02-3d49-46ec-874f-e42f8a4aa75f
---

Two related failures, both = confidence not tracking evidence. Surfaced hard in the 2026-06-23 T4-BN-2 thread (user: "didn't we know that already? kind of?").

**1. Don't OVER-retract a sound diagnosis on one noisy result.** I correctly diagnosed that T4-BN-2's per-cycle `mvn test-compile` froze main evolution (test breaks reject main-green mutations), and built the steer fix on it. Then a SINGLE confounded gate-vs-steer A/B pair looked like it contradicted the freeze, and I flipped to the opposite confident claim — "the freeze is real in code but distally inert; the cliff is just 'test goals are harder'" — and retracted the diagnosis. A later git-bisect re-vindicated the ORIGINAL diagnosis exactly (the command flip WAS the regression; cannot-find-symbol 1534→61 on reverting it). So I paid to rediscover what I already knew, because a noisy disconfirming result flipped me instead of lowering my confidence.
   - **Rule:** a noisy result that contradicts a mechanistically-grounded mechanism should move you toward "uncertain, get a cleaner test," NOT toward the opposite confident assertion. The mechanism (test-compile gates the whole probe → rejects main) was sound; one stochastic A/B pair (high variance, confounded by seed/decomposition) was weak counter-evidence. Weight them by reliability. This is the mirror of [[feedback-no-defensive-recast]]: that warns against keeping a thesis alive after a correction; this warns against KILLING a sound thesis on weak disconfirmation. Both are "let confidence track evidence quality."

**2. Resist single-cause over-commitment.** Same thread, I asserted in sequence: "it's environment/model drift, not code" → (bisect) "it's code (T4-BN-2), not environment" → (validation) the honest answer was BOTH: T4-BN-2 is the large fixable code regression AND a residual remains (even bare main 1108cb5, which fulfilled 3/3 on 06-17, no longer fulfills in 90 min — env/variance). Each confident single-cause claim got revised within hours.
   - **Rule:** when successive single-cause claims keep getting revised, the system is probably multi-factor — say so, and quantify what each factor accounts for, instead of crowning one cause. "T4-BN-2 explains the 0.26-vs-0.74 build-health gap; a separate factor explains why even the good baseline no longer fulfills quickly" is the honest shape.

**How to apply:** before flipping a diagnosis, ask "is the contradicting evidence more reliable than the mechanism it contradicts?" If it's a single noisy run/A/B vs a clear mechanism, don't flip — get the clean single-variable test (here: bisect + a one-flag command revert proved it in two runs). And when you've revised the cause two+ times, switch to a multi-factor framing rather than the next single cause. Pairs with [[feedback-verify-diff-is-substantive]], [[feedback-activity-not-progress]].
