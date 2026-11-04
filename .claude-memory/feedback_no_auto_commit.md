---
name: no-automatic-commits
description: Commit only with user authorization (standing or per-task); NEVER push, except to the session's claude/ branch in cloud sessions
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
  modified: 2026-11-04T13:20:49.010Z
---

Never commit code without authorization. Default: after finishing an implementation, show the changes and stop — wait for explicit instruction ("commit this").

**Update 2026-06-11:** during explicitly-authorized autonomous operation ("Run and fix further autonomously" + "Commits are ok, but no pushes"), committing at sensible checkpoints is allowed. **Pushing is never allowed** — no `git push` under any circumstances; the user pushes manually.

**Update 2026-09-24 (CLOUD SESSIONS ONLY):** in a Claude Code on the web CLOUD CONTAINER -- test `CLAUDE_CODE_REMOTE_ENVIRONMENT_TYPE` starting with `cloud` (e.g. `cloud_default`), NOT `CLAUDE_CODE_REMOTE` alone, which a Remote Control session on the user's own machine may also set -- committing AND pushing to the session's own designated `claude/...` branch is allowed once the user has approved the change. The container is ephemeral, so unpushed work is lost when it is reclaimed. Still never push to any other branch, never open a PR unasked. Everywhere else (local sessions) the rule above is unchanged: never push.

**Update 2026-11-04 (standing, all sessions):** ticket and memory NOTES (`.claude-memory/`, this memory directory) are committed as they are written, without asking -- the user, after asking why notes sat uncommitted: "I authorize it for this session and next ones. Pushes are not authorized." Code commits still need authorization (per task, or a standing one in an autonomous run). Never push, unchanged.

**Why:** permanent standing order after an unrequested auto-commit of T2-AU; relaxed for autonomous sessions on 2026-06-11 because uncommitted multi-fix working trees make checkpointing impossible.

**How to apply:** in interactive work, propose and wait. In authorized autonomous runs, commit logical units with the repo's message style (+ Co-Authored-By trailer), never push, and report the commit hashes in the summary.
