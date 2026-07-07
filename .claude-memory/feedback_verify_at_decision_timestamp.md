---
name: feedback-verify-at-decision-timestamp
description: When diagnosing why a runtime decision (refusal/verdict/gate) fired, compare it against the state AT that decision's timestamp, not an earlier or later snapshot — cross-check via the actual per-cycle artifacts (snapshots/, behaviour.ndjson) before concluding a mechanism is buggy.
metadata:
  node_type: memory
  type: feedback
  originSessionId: 8cd10b7a-e9ec-4d1b-8d45-058b431b04c8
---

A log line that says "X is missing/wrong" is a claim about the state the mechanism read AT THAT MOMENT. Before concluding the mechanism is buggy, find the state at that exact timestamp — not the final artifact, not an earlier cached read, not what "should" have been true by then.

**Why:** 2026-07-07 run-21 — I read a D2 refusal WARN ("no request mapping for POST /loans"), then read the FINAL corpus artifact (copied ~9 minutes later, after further cycles fixed it) and concluded D2 had a composition bug (false-refusing correct code) — filed as T1-FH with confident "VERIFIED" language. A user-directed audit caught it: `firestarter/snapshots/cycle-*` (a full `output/` copy taken at the end of every cycle) proved the file was **genuinely absent** at the refusal's exact timestamp; it only appeared in the cycle immediately after. D2 was right. The bug was in my comparison — end-state vs. decision-time state — not in the code.

**How to apply:** when a run's log shows a mechanism firing a verdict, reject, or refusal, and the artifact contradicts it:
1. Find the per-cycle snapshot (or equivalent point-in-time capture) whose timestamp is closest to — and no later than — the decision's own timestamp. Cycle folder NAMES often encode START time while the snapshot WRITE happens at cycle END (post-cycle hook) — check which one before trusting a name-based lookup.
2. Confirm the mechanism's actual INPUT (e.g. `previousOutput()`, a cached context field) reflects that same moment, not a later merge.
3. Only then trace the source code's logic manually (or with a fixture) to see if it's actually wrong given that input.
4. If steps 1-3 aren't done, don't use language like "VERIFIED bug" — say "observed discrepancy, mechanism not yet confirmed" instead (see [[feedback-no-invented-labels]] on confident-sounding language outrunning the evidence).

This is a sibling to [[feedback-verify-diff-is-substantive]] (which guards against claiming MY OWN code changes did something without proving it) — this one guards against misdiagnosing THE SYSTEM'S runtime behavior by comparing across the wrong two points in time.
