---
name: feedback-replay-over-batches
description: "Under measured run-to-run variance, never validate a change with a small-N live batch — use deterministic corpus replay + passive field watch items; live batches only for rare strategic questions with N and a decision rule stated before launch."
metadata:
  node_type: memory
  type: feedback
---

Do not validate individual changes by launching live runs when the outcome distribution is known to be high-variance. The user caught the contradiction directly (2026-07-13): *"tests currently don't mean a lot because of nondeterminism, yet we're spending hours running more tests?"* — after an overnight 3v3 paired batch cost ~4.5 h and returned zero information (the change's trigger never occurred, so the arms were code-path identical and the tally was pure variance).

**Why:** with P(good run) ≈ 0.3–0.4, detecting a realistic Δ needs ~40 runs per arm (~60 h). Any affordable batch is underpowered, and an underpowered batch invites exactly the false narratives it was meant to prevent (three retracted single-run narratives in the week before this; the batch tally "treatment 0/3 GOOD" was the fourth in the making). This sharpens, not contradicts, [[feedback-runs-are-variant-by-design]] and the distribution-thinking in [[project-reliability-plan]].

**How to apply:**
- Validate mechanisms against RECORDED corpora: replay the rule/predicate over per-cycle recorded state and check the trigger fires where the diagnosis says it must (2026-07-13 example: `stall_anatomy_report.py` in the bisect harness refuted the 7a1fda2 guard in minutes — its trigger occurred 0× across 20 recorded runs).
- Field validation = passive watch items (a marker string to grep on runs that happen anyway), never dedicated runs.
- A live batch is allowed only for a rare strategic question, with N, the metric, and the decision rule written down BEFORE launch ([[project-coverage-guard-experiment]] runbook shape) — and the metric must be one the classifier actually computes.
- Corollary from the same incident: before building a fix from a churn/log anatomy, verify the claimed mechanism IN the logs ([[feedback-verify-population-before-claim]]) — the refuted guard was built on a code-inferred "keeps resetting" claim that zero recorded runs exhibited.
