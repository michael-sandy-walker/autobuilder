---
name: no-automatic-commits
description: Commit only with user authorization (standing or per-task); NEVER push, except to the session's claude/ branch in cloud sessions
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
---

Never commit code without authorization. Default: after finishing an implementation, show the changes and stop — wait for explicit instruction ("commit this").

**Update 2026-06-11:** during explicitly-authorized autonomous operation ("Run and fix further autonomously" + "Commits are ok, but no pushes"), committing at sensible checkpoints is allowed. **Pushing is never allowed** — no `git push` under any circumstances; the user pushes manually.

**Update 2026-09-24 (CLOUD SESSIONS ONLY):** when `CLAUDE_CODE_REMOTE=true` (Claude Code on the web), committing AND pushing to the session's own designated `claude/...` branch is allowed once the user has approved the change. The container is ephemeral, so unpushed work is lost when it is reclaimed. Still never push to any other branch, never open a PR unasked. Everywhere else (local sessions) the rule above is unchanged: never push.

**Why:** permanent standing order after an unrequested auto-commit of T2-AU; relaxed for autonomous sessions on 2026-06-11 because uncommitted multi-fix working trees make checkpointing impossible.

**How to apply:** in interactive work, propose and wait. In authorized autonomous runs, commit logical units with the repo's message style (+ Co-Authored-By trailer), never push, and report the commit hashes in the summary.
