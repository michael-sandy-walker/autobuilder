---
name: project-log-and-backup-conventions
description: "Where autobuilder logs live, how they roll, why there's no \"current\" file, and how the backup tree overlaps with logs/."
metadata: 
  node_type: memory
  type: project
  originSessionId: 862bd440-cef5-42c8-9544-b744753d7c48
---

**Log files (post-T5-AE, shipped 2026-06-03 in commit `cd8e89c`).** Live targets are stable:

```
${ROOT_PATH}/logs/autobuilder.log            ← current run, stable tail target
${ROOT_PATH}/logs/behaviour.ndjson           ← current run's behaviour stream
${ROOT_PATH}/logs/autobuilder-<startTime>.log     ← archived prior runs
${ROOT_PATH}/logs/behaviour-<startTime>.ndjson    ← archived prior behaviour
```

`ROOT_PATH` resolves from `$FILE_BASE_PATH` (default `/data/firestarter`; in this checkout `firestarter/`). On every JVM startup, `core/logging/StartupLogRotator` (registered via `META-INF/spring.factories` so it fires on `ApplicationStartingEvent` *before* logback initialises) renames any existing `autobuilder.log` / `behaviour.ndjson` to `<stem>-<itsOwnStartTime>.<ext>` based on the first parseable timestamp inside the file. logback then opens fresh `autobuilder.log` / `behaviour.ndjson`. So `tail -f firestarter/logs/autobuilder.log` reliably tracks the current run; archived runs preserve full content (no mid-run rollover).

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
