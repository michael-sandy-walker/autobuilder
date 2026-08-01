---
name: feedback-count-events-not-log-lines
description: "Counting log lines that MENTION a fact as occurrences of the event has overstated two ticket headlines by 23x and 3x — count the emitting event, and say which line emits it"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c0e66d91-c338-4977-92b4-abc4b6be89ca
  modified: 2026-08-01T16:44:07.043Z
---

A `grep -c` over a log counts lines that **mention** a fact. It does not count times the fact **happened**. Autobuilder re-renders its own records — injector fix records, heuristic firings, rejection reasons — back into prompts and diagnostics, so one event leaves many lines, and the ratio is not stable across facts or runs.

**Two measured instances, same shape, both in filed ticket headlines:**

- **T2-EZ, overstated ~23×.** `grep -cE 'skeleton-class.*token=Loan\b'` = **138** on run `2026-08-01_01-10-45`. Actual skeleton creations of `Loan`: **6**. The other 132 lines are the fix record rendered into prompts (`skeleton-class [CANNOT_FIND_SYMBOL] token=… paths=[…]`). The emitting line is `IdiomSkeletonResolver … created skeleton …`.
- **T2-EY, overstated ~3×.** The filed body's "29 identical firings" was a heuristic FIRING count, not a rejection count — a heuristic fires without being the decisive rejector. The behaviour log recorded 9 re-rejections.

**Why it matters beyond tidiness.** Both times the inflated number was the reason the ticket looked urgent, and both times the real mechanism survived the correction while its *magnitude* did not. A wrong magnitude picks the wrong ticket next.

**How to apply:**
- Count the **event**, from the one line (or behaviour-log event) that the emitting code writes once per occurrence. Find it in the source before trusting a grep — `grep -rn "<the message>" src/main/java` names the emitter.
- Prefer `behaviour.ndjson` events over `autobuilder.log` lines: one JSON object per occurrence by construction ([[feedback-run-analysis-log-order]]).
- When quoting a count in a ticket, **state the line that produces it**, so the next reader can re-derive it rather than re-grep differently.
- Sanity-check the ratio: if a "per cycle" count exceeds the run's cycle count, it is counting renderings.

Related: [[feedback-verify-population-before-claim]] (which asks whether the whole corpus was scanned — this asks what a matched line MEANS), [[feedback-activity-not-progress]], [[project-smoke-score-is-chained]].
