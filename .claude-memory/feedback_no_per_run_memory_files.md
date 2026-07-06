---
name: feedback-no-per-run-memory-files
description: Do not create a new memory file per field run — fold run findings into project_resume_state.md (newest-first block); separate files only for durable cross-run insights.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: bce83296-00d9-43e4-ab5a-b2dd0102d6ef
---

Do not create a new memory file for every field run (user correction 2026-07-06, after `project_run18_false_giveup.md`).

**Why:** run findings are session-state, and `project_resume_state.md` is the designated newest-first home for them (runs 12–17 all live there as blocks). Per-run files fragment the record, bloat the MEMORY.md index, and duplicate what the resume state already carries.

**How to apply:** after analysing a run, add/extend a dated block at the top of [[project-resume-state]] and update its MEMORY.md pointer line. Create a separate memory file only for a durable insight that outlives the run narrative (a new failure CLASS with its own lessons, e.g. [[project-false-completion-3]]) — and even then, prefer updating an existing file that covers the theme.
