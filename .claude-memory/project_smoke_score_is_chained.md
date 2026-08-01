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
