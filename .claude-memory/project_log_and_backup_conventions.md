---
name: project-log-and-backup-conventions
description: "Where autobuilder logs live, how they roll, why the latest run is the UNSUFFIXED behaviour.ndjson / autobuilder.log, and how the backup tree overlaps with logs/."
metadata: 
  node_type: memory
  type: project
  originSessionId: 862bd440-cef5-42c8-9544-b744753d7c48
  modified: 2026-07-26T11:16:14.576Z
---

**Log files (post-T5-AE, shipped 2026-06-03 in commit `cd8e89c`).** Live targets are stable:

```
${ROOT_PATH}/logs/autobuilder.log            ← current run, stable tail target
${ROOT_PATH}/logs/behaviour.ndjson           ← current run's behaviour stream
${ROOT_PATH}/logs/autobuilder-<startTime>.log     ← archived prior runs
${ROOT_PATH}/logs/behaviour-<startTime>.ndjson    ← archived prior behaviour
```

`ROOT_PATH` resolves from `$FILE_BASE_PATH` (default `/data/firestarter`; in this checkout `firestarter/`). On every JVM startup, `core/logging/StartupLogRotator` (registered via `META-INF/spring.factories` so it fires on `ApplicationStartingEvent` *before* logback initialises) renames any existing `autobuilder.log` / `behaviour.ndjson` to `<stem>-<itsOwnStartTime>.<ext>` based on the first parseable timestamp inside the file. logback then opens fresh `autobuilder.log` / `behaviour.ndjson`. So `tail -f firestarter/logs/autobuilder.log` reliably tracks the current run; archived runs preserve full content (no mid-run rollover).

⚠️ **"Analyse the last run" = read the UNSUFFIXED `logs/behaviour.ndjson` (and `logs/autobuilder.log`).** Those hold the most recent completed run until the *next* JVM start rotates them to `behaviour-<ts>.ndjson`. A glob like `behaviour-*.ndjson` silently EXCLUDES the live file, so sorting timestamped files by name/mtime points at the second-newest run. Burned 2026-06-08: missed a post-T2-DZ run sitting in `behaviour.ndjson`, analysed a stale `behaviour-2026-06-07_21-37-50.ndjson` instead, and drew wrong conclusions. Always include the unsuffixed file (or just check its mtime first).

**Historical (pre-T5-AE):** the config bound `<file>` to `${startTime}` so the live file was timestamped from JVM start. There was no `autobuilder.log` to tail; identifying the latest run required `ls -t`. T5-AE restored the stable target. Pre-T5-H (before commit `ccf103e`) the live target was already `autobuilder.log` but used daily rollover via `%d{yyyy-MM-dd}`, concatenating same-day runs.

**JVM kills are still visible.** `immediateFlush` defaults to `true` in logback's `FileAppender`. When the JVM is killed mid-cycle (Ctrl-C / SIGKILL during an LLM HTTP call), no log lines are being emitted *between* the prompt template and the response, so the log appears to end mid-prompt with no `SpringApplicationShutdownHook` or `Cycle … complete` line. That's not lost buffer; it's silence. **Absence of a termination marker does not mean "the loop didn't terminate" — it usually means the process was killed during an outbound request.** Cross-check against process state / file mtime when distinguishing "ran to natural end" vs "killed externally."

**Behaviour NDJSON format.** Despite the `.ndjson` extension the file is **pretty-printed multi-line JSON**, not classic newline-delimited. Parse with a streaming decoder:

```python
decoder = json.JSONDecoder()
i, records = 0, []
while i < len(text):
    while i < len(text) and text[i].isspace(): i += 1
    if i >= len(text): break
    obj, end = decoder.raw_decode(text, i)
    records.append(obj); i = end
```

`grep -c '"event"'` over-counts; line-based `wc -l` under-counts (each record spans many lines).

**Backup tree.** `backup/<YYYYMMDD>_<label>/` holds **whole-state snapshots of interesting runs**: `mutations/`, `output/`, `responses/`, `meta/`, and the **entire `logs/` folder copied verbatim**. Because the `logs/` folder gets copied wholesale, **the same log file commonly appears in many backup directories** — that's overlap, not significance. Deduplicate by base log timestamp (e.g. `autobuilder-2026-05-21_23-08-50.log.gz` appears in 11 backups; it's one run).

**Backup labels** are curated by the user (`_fulfilled`, `_almost`, `_stunningly_fulfilled`, `_enabled_true`, `_enabled_false`, `_without_compile`, `_ollama_restart`, …). The label describes the *snapshot occasion*, not the run contents — do not infer "this run was fulfilled" from a `_fulfilled` parent directory. Treat labels as loose orientation, not indexed metadata.

**Reading the corpus correctly.**

```bash
# Full corpus enumeration (firestarter + backups, both compressed and not):
find /home/anugeshtu/IdeaProjects/autobuilder -name "autobuilder-2026-*.log*"

# Compressed logs need zgrep:
zgrep "pattern" .../autobuilder-…log.gz

# Deduplicate by run timestamp before counting any cross-run metric.
```

**Date range as of 2026-06-03:** corpus spans 2026-04-28 → 2026-06-01, ~3514 log files, ~50+ distinct runs after deduplication. See [[project-goal-fulfilled-is-real-happy-path]] for what successful termination looks like in this corpus.

See [[feedback-verify-population-before-claim]] — this file documents *where* to look; that one documents *that you must*.

## Test-suite logs sit next to run logs and match watch patterns (2026-07-25)

`backup/*/logs/` and `firestarter/logs/` hold **both** kinds of `autobuilder-*.log`: real runs and
`mvn test` executions ([[project-tests-rotate-live-logs]] is why they land there). A test log
contains the agent's own log lines — including the exact strings the `scripts/watches.json` registry
greps for — so an ad-hoc grep across backups can report field confirmations that never happened.

Near-miss that produced this note: closing T2-EO, a grep for its rescue pattern hit ~20 logs with
`opportunity=1 rescue_fired=1` each. All were test-suite logs running the ticket's own two
regression tests; the rescue has **never** fired in a real run.

Separate them before believing any field claim:

```bash
grep -c 'CYCLE cycle-' "$f"      # real run: many · test log: 0
# real runs log on [scheduling-1]; tests on [main]; test logs also carry junit/surefire markers
```

`scripts/watch_report.py` is immune (it reads an assembled run corpus, not the backup tree) — prefer
it. Also folded into `scripts/AUTONOMOUS_DEV_LOOP.md` §3b.

## Stale surefire reports inflate any suite count (2026-07-26)

`mvn test` (no `clean`) does NOT purge `target/surefire-reports/`, so XML from **deleted test classes
survives** and any aggregate over that directory overcounts. Burned reading 2143 tests right after
deleting a test class — the total had gone UP. Four stale reports were present, one of them the class
deleted minutes earlier (`CompileErrorSymbolExtractorTest`), one deleted the session before
(`PlanningThrashSignalAnalyzerTest`).

Filter by mtime against the run's start before believing a total:

```python
if os.path.getmtime(p) < run_start: continue   # stale report from an earlier run
```

True figure that day: **2112/0/5** (= 2119 − the 7 methods of the deleted class). Same family as the
inert-instrument lessons below: the number was confident, well-formed, and about the wrong population.

## `log.debug` NEVER reaches a field log (2026-07-26)

`logback-spring.xml:48` pins `<logger name="com.walpro.autobuilder" level="INFO"/>` and nothing
overrides it — `FILE_LOG_LEVEL=DEBUG` is declared at the top of that file but referenced by no
appender, and the FILE appender has no level filter of its own, so it inherits INFO. **All 183
`log.debug` calls across 65 files in `src/main` are unreachable in every run**, dev-auto included.

Consequence, and the rule: **a field instrument must log at INFO (or emit a behaviour event) — never
`debug`.** Burned on T5-BH: `ReplanPatternDetector`'s five self-reported abstention reasons shipped
at `debug`, so the planned "watch next run's log for the dominant reason" would have returned zero
lines, and zero reads as *no data* rather than *the level swallowed it*. Same shape as T6-A/T6-C/T6-E
— **prove the instrument emits before drawing any conclusion from its silence.**

Most existing `debug` calls are genuine trace and fine where they are; the rule binds new
instruments, not a sweep.

**Token spend of the assistant's own sessions** is readable locally too:
`~/.claude/projects/<slug>/*.jsonl` carries `message.usage` per turn — `scripts/session_usage.py`
(M-C) turns it into current-window spend + burn rate. Read `full-rate`, not total: cache reads run
~50x larger and are billed at a fraction. The plan limit is NOT in the data.

**Run-archive durability (user-corrected 2026-08-28):** `~/.autobuilder/run-archive` has no
retention code pointed at it TODAY — `RetentionPolicy` prunes only the target dirs under
`file.base-path` (7–14 active-day windows) — but it is unversioned and machine-local, and the
measured survival record for unversioned evidence here is 4 of 28 runs (T6-M), with one recorded
0-GB-free incident making ad-hoc cleanup of exactly such folders likely in week/month-long
sessions. **Anything that must outlive the session (specimens, evidence a ticket cites) goes into
git (`design/specimens/…`); the archive is working storage, not preservation.**

