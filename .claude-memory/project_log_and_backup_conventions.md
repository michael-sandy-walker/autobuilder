---
name: project-log-and-backup-conventions
description: "Where autobuilder logs live, how they roll, why there's no \"current\" file, and how the backup tree overlaps with logs/."
metadata: 
  node_type: memory
  type: project
  originSessionId: 862bd440-cef5-42c8-9544-b744753d7c48
---

**Log files.** `logback-spring.xml` binds the file name to `${startTime}` captured at JVM startup:

```
${ROOT_PATH}/logs/autobuilder-YYYY-MM-DD_HH-mm-ss.log
${ROOT_PATH}/logs/behaviour-YYYY-MM-DD_HH-mm-ss.ndjson
```

`ROOT_PATH` resolves from `$FILE_BASE_PATH` (default `/data/firestarter`; in this checkout `firestarter/`). Each JVM session creates a uniquely-timestamped pair. **There is no `autobuilder.log` symlink, no rolling-to-current pattern, no "latest" file** — at the time of writing. (A T5-style ticket to restore current-file convention is anticipated; see in-flight session 2026-06-03 discussion.)

**Why:** intentional per the logback comment "Captures the JVM startup time so each run gets its own log file." Downside: `tail -f` requires picking the right timestamped file each session; identifying the latest run requires `ls -t`.

**No `immediateFlush`.** The file appenders rely on default flushing. JVMs killed mid-cycle (Ctrl-C / SIGKILL during an LLM HTTP call) leave the log ending mid-prompt with no `SpringApplicationShutdownHook` or `Cycle … complete` line. **Absence of a termination marker does not mean "the loop didn't terminate" — it usually means the process was killed.** Cross-check against process state / file mtime when distinguishing "ran to natural end" vs "killed externally."

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
