---
name: project-smoke-score-is-chained
description: "The 23-check smoke score is not 23 independent measurements — it is a stateful chain where one missing endpoint costs 8 points and two checks pass spuriously when that endpoint is absent"
metadata: 
  node_type: memory
  type: project
  originSessionId: f54dc5b0-d9fb-4267-bae8-a3bce5956657
---

`scripts/smoke23.sh` is sequential and stateful. `BOOK1` comes from check 1 and feeds checks 6, 13, 16–19 and 23; `MEM1` comes from check 8 and feeds 11, 13, 17–19, 23; `LOAN1` comes from check 13 and feeds 20 and 21. When a create fails, `jid()` silently falls back to id `1` and every downstream check probes a resource that may not exist. **The score is therefore quantised by which link breaks, not a gradient of quality.**

**Measured on run `2026-08-01_01-10-45` (14/23), by booting its preserved artifact and running the suite per check:**

- **8 of the 9 failures are one defect** — checks 13, 16, 17, 18, 19, 20, 21, 23 all answer 404 on `/loans` because **`LoanController.java` does not exist in the artifact.** `Loan.java`, `LoanStatus.java`, `LoanRepository.java` and `InMemoryLoanRepository.java` are all present; only the controller is missing.
- **1 failure is independent** — check 10, `POST /members` with no name answers 201 instead of 400 (validation not enforced).
- **2 checks PASS SPURIOUSLY.** Checks 14 and 15 want 404 for a bad book/member id and get 404 — because the endpoint is absent and everything under `/loans` 404s. The suite awards two points for a missing controller.

So 14/23 is not "nine things wrong". It is **one missing file, one validation gap, and two free points for the missing file**. The same arithmetic explains why scores cluster at 10 / 14 / 18 / 23 rather than drifting: those are the states of a few chain links.

**How to use this.** Never read a smoke delta as a quality delta without checking WHICH checks moved — a 4-point swing is usually one endpoint appearing or vanishing, not four independent regressions. `18 → 14` between two builds is one file, and attributing it to a mechanism change is a category error. When comparing runs, compare the failing check SETS, not the totals. Related: [[project-first-perfect-smoke-score]], [[feedback-verify-diff-is-substantive]].

**The uncomfortable corollary for this session.** Every proposal T2-EY refused in that run was `LoanController.java` — fingerprints `63eacec`, `1cd0418`, `d36e59d`, refused 13 times between them. The loop was trying to write the one file worth 8 points, failing, and re-emitting identical bytes. The gate made that failure cheap and legible and did nothing to resolve it. That is the difference between a veto and an actuator, stated in points.

## 2026-08-01 — the chain is now MEASURED, not just described, and it names the work

`scripts/smoke_chain_report.py` turns the chain from a caution into an instrument. For every check it
separates **RAW** (failed at all) from **ORIGINATED** (failed while every check it depends on was
observed AND passed). An unobserved dependency counts as unknown rather than passed, so a partial
sheet cannot promote an echo into a defect. `scripts/rescore_preserved.sh` backfills sheets by
booting preserved artifact trees — a measurement, not a reconstruction, since the tree is the input
and scoring is deterministic.

**The instrument immediately proved its own point:** checks 6 and 21 show raw failures with ZERO
originated. They are pure echoes. A raw tally would have sent work at the return endpoint when the
break was loan creation upstream.

**Recoverable points per run, by family — FINAL, over 25 runs with sheets (23 backfilled):**

| family | direct | unlocks | pts/run |
|---|---|---|---|
| the creates 201 (1, 8, 13) | 9 | **47** | 2.24 |
| loan referential 404 + limits (14, 15, 19, 22) | **44** | 0 | 1.76 |
| reject invalid input 400/409 (2, 3, 4, 9, 10) | **34** | 0 | 1.36 |
| return semantics 200/409 (20, 21) | 11 | 2 | 0.52 |
| reads (5, 6, 7, 11, 12, 17, 18, 23) | 13 | 0 | 0.52 |
| already-on-loan 409 (16) | 7 | 0 | 0.28 |

**⚠ DIRECT is a firm floor; UNLOCKS are an upper bound.** Fixing check 1 lets checks 6/13/15/16 be
measured honestly — it does not make them correct. So the creates' 47 unlocked points are the
optimistic case, and a plan built on them could deliver a fraction of what it promised. **Choose
work by DIRECT points.**

On direct points the order is **loan referential + limits (1.76/run), then validation (1.36/run)**,
and those two alone move the per-attempt baseline **62% → 75%**. Everything else is ≤0.52/run.

**⚠ AN INTERIM READ OVER 12 RUNS GOT THIS WRONG and is recorded so the correction is visible:** at
half the sample the answer looked like "the creates and validation", because the creates' unlock
count was already large and validation led on direct points. The full sample kept validation but
promoted the **loan referential/limit family** past it, and moved the creates into the speculative
column. Half a corpus reordered the top three.

**Top single independent defects (25 runs):** `[19]` bounded-count 422 and `[15]` loans-bad-member
13/25 each, `[10]` members-missing-name and `[14]` loans-bad-book 12/25 each. One run scored
**22/23 with check 10 as its only failure.**

**Use `originated`, never `raw`, to choose work.** A check with a high raw count and no originated
failures needs nothing done to it.

