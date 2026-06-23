---
name: project-t4bq-naming-consistency-ab
description: "T4-BQ slice-2 naming-consistency block A/B, 3 pairs (2026-06-18 + 2026-06-21): block fires reliably but NO robust effect on its own metric (pair3 reversed) and ZERO run-outcome benefit (0/6 fulfilled). STOOD DOWN — flag back to false, code dormant. Establishes ONLY the negative result; what would help is unproven, and synonym-minting looks like a symptom (0/6 regardless of count), not the binding constraint."
metadata:
  node_type: memory
  type: project
  originSessionId: 609aab02-3d49-46ec-874f-e42f8a4aa75f
---

**Verdict (3 pairs, n=6 runs): the inbound naming-consistency block is truthful but unproven — STOOD DOWN 2026-06-21 (flag → false, tested code kept dormant).** Same disposition slice 1 (`synthesize-members`) got 2026-06-16. The pair-1-only read below was an overclaim corrected by pairs 2–3.

**Setup.** Test-authoring seed (library goal WITH "JUnit 5 tests for every endpoint"). Single jar, `reset-on-start`, identical seed; the ONLY difference per run was `--autobuilder.evolution.naming-consistency.enabled=true|false`. Backups: `/tmp/t4bq-naming-block-ab/pair{1,2,3}/{block-enabled,baseline}/`. Pairs 2–3 order counterbalanced. All 6 runs honest terminals, zero false completions.

| pair | config | terminal | cyc | merg | cfs | cfs/cyc | invented exc | fulfilled |
|---|---|---|---|---|---|---|---|---|
| 1 | block-enabled | paused (T4-I stall) | 38 | 5 | 153 | 4.0 | 0 | no |
| 1 | baseline | GIVEN_UP | 83 | 14 | 1087 | 13.1 | 5 | no |
| 2 | block-enabled | paused | 68 | 11 | 204 | 3.0 | 0 | no |
| 2 | baseline | (cut at 70min) | 66 | 10 | 307 | 4.7 | 0 | no |
| 3 | block-enabled | paused | 41 | 11 | 324 | 7.9 | **7** | no |
| 3 | baseline | paused | 28 | 4 | 178 | 6.4 | 4 | no |

("invented exc" = `*Exception` names referenced as a missing symbol that were never declared on disk. "cfs" = total `cannot find symbol`.)

**What 3 pairs establish:**
1. **Fires reliably** — 37/64/39× when enabled, 0× in every baseline. Half-1 closed.
2. **Zero run-outcome benefit** — 0/6 fulfillments; the completion ceiling held in EVERY run, both conditions. The block is not the convergence lever (as the deferral always predicted — the binding constraint is last-mile/local-model capability).
3. **No robust effect even on its own design metric.** Pair 1 looked emphatic (invented 0 vs 5; churn 4.0 vs 13.1/cyc) but pair 2 was marginal and **pair 3 REVERSED it** (enabled invented 7 vs baseline 4; churn 7.9 vs 6.4/cyc). Pair-1's win was substantially decomposition luck (its planner happened to pick 2 generic exceptions). The pair-2 baseline is the clean counter-example: it declared 7 exceptions and invented 0 — perfectly coherent naming WITHOUT the block.
4. **The mechanism's ceiling is exposed directly.** In pair 3 the enabled run minted synonyms of names the block was actively listing: declared `MemberHasTooManyActiveLoansException` but invented `TooManyActiveLoansException`; declared `BookAlreadyReturnedException` but invented `ReturnAlreadyException`. **The local model ignores the prompt directive even when the canonical name is in front of it.** So a stronger/longer inbound prompt cannot fix this — it is the wrong layer.

**Why no inbound "fix" was attempted (despite the brief).** The failure is not a bug in the block; it is that surfacing information does not force a local model to use it under load. Building a bigger inbound block would be exactly the reflexive, evidence-free steering [[feedback-activity-not-progress]] forbids.

**What these runs do NOT establish — and an overclaim corrected (2026-06-21, user-flagged).** The first writeup said "the real lever is outbound deterministic enforcement (T4-AW `SymbolReviewer`)." That is itself an unvalidated assumption — the same error, one layer over. Two unjustified leaps: (1) that an outbound reviewer would reduce synonyms — plausible (a mechanical rename is more reliable than a prompt the model ignores) but **untested**; (2) the bigger one — that reducing synonyms would improve run *outcomes*. The data argues against (2): **0/6 fulfilled regardless of synonym count, and pair-1 enabled had ~0 invented synonyms and still did not finish.** So synonym-minting reads as a **symptom** of local-model capability, not the binding constraint on completion — a perfect synonym-eliminator might leave 0/6 unchanged. The ONLY thing the 6 runs establish is the narrow negative: this inbound block does not help → stand it down. T4-AW outbound enforcement is a *hypothesis worth testing for reducing synonyms*, NOT an established lever for completion; do not file it as "the fix."

**Re-entry condition:** none asserted. Before building anything in this space, get evidence that synonym-minting is actually on the critical path to completion (it currently is not, per the 0/6). Until then the block stays off and no replacement is presumed.

**Methodology note:** pair-1-alone produced a confident "design target met emphatically" claim that 2 more pairs falsified. Single nondeterministic pairs are not evidence ([[feedback-verify-population-before-claim]]); the runbook's ≥3-pair minimum earned its keep here. Also [[feedback-no-invented-labels]] (don't name runs "arm ON/OFF" — use block-enabled / baseline).
