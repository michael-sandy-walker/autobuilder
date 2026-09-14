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


## 2026-09-14 — what a frozen tree and a rewind each CANNOT answer

Two limits found by trying to verify one escalation three ways, both worth knowing before
choosing an instrument.

- **A frozen tree is ONE CYCLE'S INPUT. It cannot express a sequence.** Anything whose trigger is
  "the same thing N cycles running" is out of its reach by construction; that belongs in a unit
  test that drives the agent, or in a live run.
- **A defect that only ever exists in REJECTED candidates is invisible to every tree-based
  harness here.** `freeze_scenario.sh` captures merged trees. Run `2026-09-13_09-44-03` rejected a
  parallel store seven times and archived 22 cycles, and **not one contains the offending file** —
  it never entered merged state. The candidate survives only as text in `autobuilder.log`, so the
  scenario has to be RECONSTRUCTED from the log and labelled as such
  (`parallel-store-rejected-2026-09-13`). Worth checking before proposing a freeze: grep the
  archive for the thing you intend to freeze, rather than assuming the run that produced it kept it.
- **A REWIND reproduces a STATE, not a TRAJECTORY.** Restoring the cut point before that run's
  seven rejections reproduced the defect once, with a different partner class, and the loop then
  moved on — no grind, so nothing that needs a repeat could fire. Prompts go out at temperature 0,
  but rewound-out goals stay in the graph's description corpus that the planner's duplicate check
  reads, so the plan diverges anyway. See [[project-state-time-travel-design-note]].

## 2026-09-14 later — the third instrument: a rewind WITH the carried context

A frozen tree is one cycle's input; a rewind gives a fresh roll from a restored STATE. The
2026-09-14 T2-ES run showed what a rewind can answer once the checkpoint carries the cycle
context the next tick reads (streaks, warnings, relayed verdicts, build observations): **a
sequence-dependent mechanism can be cut ONE cycle short of its threshold and tested in one cycle.**
The escalation that no natural run had reached in two weeks fired 2 minutes 20 seconds after the
resume. Requirements, learned the hard way: the point must carry its context (every checkpoint
before 2026-09-14 does not; a reconstructed one is accepted and labelled), the run's `mutations/`
is gone after the boundary so the context of an old point has to be rebuilt from its logs, and
the reconstruction is verified by diffing the first rendered prompt against the original run's,
not by inspecting the file. What it still cannot do: force the model's next roll — the planner
re-rolled too (a structured replan the original run's planner did not manage), and only the
DETERMINISTIC half of the claim is settled by one rewind.

