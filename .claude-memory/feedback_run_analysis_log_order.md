---
name: Run analysis — log file order
description: When asked to analyse the last run, start with the behaviour NDJSON; fall back to autobuilder.log only when more detail is needed.
type: feedback
originSessionId: acf05627-f765-4d15-ad61-e97461898c45
---
When the user asks to analyse the last run (or any past run), read the behaviour NDJSON first. Only fall back to the prose log when the NDJSON does not contain enough detail.

**Files (under `${FILE_BASE_PATH}/logs/`, `FILE_BASE_PATH=firestarter` for test runs, default `/data/firestarter` otherwise):**
- Per-run filenames are timestamped: `behaviour-YYYY-MM-DD_HH-mm-ss.ndjson` and `autobuilder-YYYY-MM-DD_HH-mm-ss.log`. The most recent pair = last run. Use `ls -t` to pick.
- Active rolled chunks within a run: `.%i.ndjson.gz` / `.%i.log.gz` (size-rolled at 50 MB).

**How to apply:**
1. Locate the most recent `behaviour-*.ndjson` and read it as the primary source. It has structured records: `cycle_start`, `agent_fitness_ranked`, `agent_selected`, `agent_skipped`, `cycle_end`, `mutation_proposed`, `mutation_scored`, `heuristic_result`, `mutation_negotiated`, `mutation_merged`, `mutation_rejected`, `deliberation_outcome`. Each record is a JSON envelope `{ timestamp, cycleId, event, payload }`.
2. Records are **pretty-printed** (multi-line JSON objects), not one-per-line — and `jq` is **not installed** in this environment. Parse with a Python script: `json.JSONDecoder().raw_decode(text, i)` in a loop, advancing `i` past whitespace each iteration. Aggregate with `collections.Counter` for event tallies and group-by-`cycleId` for per-cycle summaries. Python scripts are encouraged for any non-trivial log analysis (behaviour NDJSON or the prose `autobuilder-*.log`).
3. Reach for the matching `autobuilder-*.log` only when the NDJSON does not answer the question (root-cause stack traces, prose rationale strings the structured trace summarises, exception messages, signal arithmetic that didn't cross an event boundary).
4. Both files use **local-time** timestamps — the NDJSON `timestamp` is `OffsetDateTime` with the system zone (`+02:00` etc.), so `grep "23:12:35"` lines up across both files.

**Why:** T5-H added the behaviour NDJSON specifically so "why did this cycle do X?" is answered from a structured stream instead of grepping ~394 free-form log calls. The user wants that workflow honoured: structured first, prose for rare deeper dives.
