# Autonomous development-loop runbook

Companion to `AUTONOMOUS_TEST_LOOP.md`. That one drives **validation** and stops at code changes
("the driver does not write code"). This one covers the **development** cycle: pick work, satisfy
its gate, build, close the books, commit.

Safety rules from `AUTONOMOUS_TEST_LOOP.md` apply unchanged — never raw `mvn`, never launch a run
outside `preflight.sh`, never `git push`, never branch-switch (`.claude-memory/` is live memory),
ports 8081/8085 belong to preflight.

## 0. Check the budget before committing to an iteration

```bash
python3 scripts/session_usage.py          # current 5h window · burn rate · today
python3 scripts/session_usage.py --limit 500000   # add a percent, if you know your cap
```

The account's plan limit is **not** in the transcripts, so this reports spend and rate, never
"% remaining" unless you supply `--limit`. Read `full-rate` (fresh input + cache writes + output),
not `all_tokens`: cache reads are billed at a fraction and run ~50x the full-rate figure on a long
session, so lumping them in makes every window look spent.

Pace on what is left in the window: a measurement iteration (corpus replay + write-up) has been
costing roughly 100–200k full-rate tokens; a live preflight run costs far more in wall clock than in
assistant tokens. If the window is nearly gone, prefer finishing the closure bookkeeping of work
already done over starting a new ticket.

## 1. Orient — cheaply

```bash
python3 scripts/next_work.py              # census · bookkeeping warnings · dead deps · live queue
python3 scripts/next_work.py --id T4-CP   # one ticket's marker, title, gate lines
```

Read, in this order:
1. the **START HERE** block of `.claude-memory/project_resume_state.md` (newest first — one block, not the file),
2. the `next_work.py` digest,
3. `--id <ticket>` for the one you pick.

**Do not read `roadmap.md` end to end.** It is thousands of lines; the digest exists so orientation
does not consume the budget the work needs. Read a full body only for the ticket being worked.

**A recorded plan can be stale — trust ticket state over plan prose.** The 2026-07-24 wave-2 plan
named "T4-CE slice 2 → T4-E" as its fallback spine, but T4-CE had already closed NO-BUILD, so the
plan was dead as written. `next_work.py` reports that class as `closed-without-building`; check the
DEAD DEPS section before adopting any written plan.

## 2. Respect the gate — a measurement may replace the build

Many tickets carry an explicit re-entry condition that is a **cheap corpus measurement, not a green
light**. If a ticket states one:

1. Run the measurement first, with the decision rule stated **before** looking at results
   ([[project-coverage-guard-experiment]] shape).
2. Let it decide. **A negative is a deliverable, not a failed session** — record it on the ticket
   with the numbers, mark `[>]` (revisable, per [[feedback-capability-gap-not-prohibition]]), and
   stop. Do not build anyway.

Precedent, both 2026-07-25: T6-B's aggregate closed T4-CP's gate negative (one scripts session
instead of a `core/lifecycle` mechanism), and T4-H's depth query refuted its own premise over 1672
observations. Cheap measurement beating a speculative build is the normal good outcome here.

Before building, also re-check the gate's *arithmetic*. T6-B was filed "build-gated on a batch of
dual-scored corpora"; the batch turned out to be one comparable row, which changed what got built.

## 3. Build

- One capability, one scope — build it, rewire every caller, delete the dead path
  ([[feedback-ship-full-scope]]). Never split the cleanup into a T5 follow-up.
- Prefer deterministic corpus replay over live batches for validation
  ([[feedback-replay-over-batches]]). Live runs are for rare strategic questions with N and a
  decision rule fixed in advance.
- Java change → full suite must be green before closing. Scripts-only change → the affected
  `scripts/test_*.py` plus its neighbours; no suite run needed (T6-A precedent).
- New `scripts/*.py` gets a durable stdlib test alongside it, no maven/model needed.

## 3b. Verify a field claim against run logs, not test logs

`backup/*/logs/` interleaves **test-suite** logs with **run** logs under the same
`autobuilder-*.log` naming, and a test log matches the very patterns the watch registry greps for.
Closing T2-EO on 2026-07-25 nearly recorded ~20 field confirmations of a rescue that has **never**
fired in a real run: each hit was its own two regression tests.

Tell them apart before believing a field claim:

| | run log | test-suite log |
|---|---|---|
| `grep -c 'CYCLE cycle-'` | many | **0** |
| thread name | `[scheduling-1]` | `[main]` |
| junit/surefire markers | none | hundreds |

`scripts/watch_report.py` is not affected — it reads an assembled run corpus. Ad-hoc greps across
backups are. Prefer `watch_report.py <corpus>` over grepping, and when grepping anyway, filter to
logs with a non-zero `CYCLE cycle-` count.

## 4. Close the books

The mechanical part, and the part that drifts. `next_work.py` re-checks 1 and 2 afterwards.

1. `roadmap.md` — flip the checkbox. **Append the closure, never rewrite the filed body**
   ([[feedback-append-dont-rewrite-tickets]]); state deviations explicitly.
2. `roadmap-overview.md` — update that ticket's marker line and the census.
3. `~/Documents/autobuilder/analysis_<topic>_<date>` — the write-up, with the numbers.
4. `project_resume_state.md` — a new dated block at the top, and refresh the `description:`
   frontmatter (it is what a cold session reads first). Fold run findings in here; no new memory
   file per run ([[feedback-no-per-run-memory-files]]).
5. Commit on `main` — code and docs as separate commits, message suffixed
   `Co-Authored-By:`. Checkpoint commits are fine in an authorized autonomous session; **never push**
   ([[feedback-no-auto-commit]]).

## 5. Stop and ask

Report and wait rather than guessing when:

- a false completion or false give-up appears (highest-value finding — see the test-loop table),
- the next spine is a genuine direction choice rather than a queue order,
- a gate's own arithmetic is ambiguous and either reading implies materially different work,
- something outward-facing or hard to reverse is implied (pushing, scheduled/cron agents, deleting
  corpora, anything mutating `firestarter/` beyond a preflight run).

## Token discipline

Each of these was a measured cost, not a guess:

- Orient from the digest + one resume-state block, not from whole files (§1).
- Summarise corpora with a script; never read behaviour/`autobuilder.log` into context directly.
  They run 100 KB–1.7 MB each — `drift_aggregate.py`, `watch_report.py`, `prompt_block_report.py`
  and `health_report` exist so a finding costs a few lines instead of a megabyte.
- Deduplicate before measuring: the backup tree holds 4132 behaviour file copies of 648 distinct
  runs. Dedupe by run name, largest copy wins.
- Prefer replay over live runs — a live run is ~25 min of wall clock and tens of thousands of
  model tokens on the loop's own side.
- One surface per commit, so a regression bisects instead of needing re-derivation.
