---
name: feedback-commit-the-run-ledger
description: "scripts/run_outcomes.json is committed with the session's work, never left uncommitted for the user (user, 2026-10-31)."
metadata:
  type: feedback
---

`scripts/run_outcomes.json` is the run ledger preflight appends to and the analysis scripts read. Commit it with the session's work, including runs that were not measurements: the ledger names those itself. Never report it as "left for the user".

**Why:** the user: "Please don't leave the run_outcomes.json to me anymore. If it's not important, then it should be ignored. Otherwise, it should be just also committed." Earlier resume blocks say "left for the user"; that convention is over.

**How to apply:** add it to the commit that records a run. Commits are still authorized as usual ([[feedback-no-auto-commit]]); never push.
