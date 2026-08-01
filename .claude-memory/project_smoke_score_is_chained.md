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

**Recoverable points per run, by family (12+ backfilled runs):**

| family | direct | unlocks | pts/run |
|---|---|---|---|
| the two creates (checks 1, 8) | 4 | **16** | 1.43 |
| reject invalid input with 400 (2, 3, 4, 10) | **20** | 0 | 1.43 |
| create a loan (13) | 2 | 10 | 0.86 |
| referential 404 on a bad id | 10 | 0 | 0.71 |
| bounded count → 422 | 5 | 0 | 0.36 |
| return semantics | 4 | 0 | 0.29 |

The two leading families are opposite in character, which is the whole reason the split matters.
**The creates are worth almost nothing directly and everything indirectly** — when `POST /books` or
`POST /members` fails, `jid()` falls back to id 1 and eleven downstream checks probe resources that
do not exist. **Validation is the mirror image: 20 points, all direct, no echoes**, with check 10
(`POST /members` with no name → 400) failing in 7 of 12 runs on its own.

**That validation family is the same defect the project has been circling from the other side** —
`T4-CU`'s false completions were *"validation COMPLETED"* against an artifact answering 201. It now
carries a price: **1.43 points per run**.

**Use `originated`, never `raw`, to choose work.** A check with a high raw count and no originated
failures needs nothing done to it.

