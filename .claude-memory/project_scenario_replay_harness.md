---
name: project-scenario-replay-harness
description: "Freeze a real run's tree as a test scenario and replay it against the deterministic machinery — how to add one, and what it can and cannot answer."
metadata:
  node_type: memory
  type: project
---

**Built 2026-09-03, user-requested: "load specific scenarios by the rewinding mechanism so we can
test the last wrong run at cycle X immediately after we fixed something."**

- `scripts/freeze_scenario.sh <source-tree> <name> ["what went wrong"]` copies sources plus
  provenance into `src/test/resources/scenarios/<name>/`. Three sources worth freezing from:
  a preflight `run_dir/artifact` (the run's final tree), `checkpoint-archive/<cycleId>` (**the only
  per-cycle tree that survives a reset**, so a cycle stays freezable after the next run starts), and
  `snapshots/<cycleId>` (this run only).
- `Scenario.load(name)` in the test `scenario` package returns the tree as `path -> content`, the
  same shape `readFolderContentsResolved` hands the loop at merge time, so a rule reads a scenario
  exactly as it reads the live tree. `describe()` carries the provenance into assertion messages.
- **The boundary is deliberate:** a scenario answers everything decided WITHOUT the model — what
  the structural analyzers see, what the gates conclude, what a repair would plan. It does not
  answer what the model would write next. Those are the decisions worth regression-testing because
  they are supposed to be identical every time. Full cycle replay would additionally need
  `llm-recordings/exchanges.ndjson`, which preflight runs do not record by default.
- **Always prove the scenario discriminates**: flip the fix off and confirm the test fails
  ([[feedback-verify-diff-is-substantive]]). The first scenario failed 3 of 4 under the old
  threshold and passes under the new one.
- First scenario: `unregistered-repositories-2026-09-03`. See
  [[project-detector-went-silent-as-the-fault-spread]].
